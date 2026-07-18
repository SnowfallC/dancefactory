package com.formationtool.desktop.export;

import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.ExportConfig;
import com.formationtool.desktop.utils.FFmpegUtil;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
public class DesktopVideoExporter {
    private final FormationViewModel viewModel;
    private final StageView stageView;
    private final File audioFile;
    private final File referenceVideo;
    private String ffmpegPath;
    private volatile boolean isCancelled = false;
    private Process currentProcess = null;

    public interface ProgressListener {
        void onProgress(double progress, String message);
    }

    private ProgressListener progressListener;

    // 优化参数
    private static final int EXPORT_FPS = 60;  // 导出帧率 60fps
    private static final String ENCODE_PRESET = "veryfast";  // 更快的编码预设

    public DesktopVideoExporter(FormationViewModel viewModel, StageView stageView,
                                File audioFile, File referenceVideo) {
        this.viewModel = viewModel;
        this.stageView = stageView;
        this.audioFile = audioFile;
        this.referenceVideo = referenceVideo;
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    public void cancel() {
        isCancelled = true;
        if (currentProcess != null) {
            currentProcess.destroyForcibly();
        }
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    /**
     * 执行导出
     */
    public void export(boolean hasReferenceVideo, File outputFile, long inPointMs, long outPointMs) throws Exception {
        if (isCancelled) return;

        ffmpegPath = FFmpegUtil.getFFmpegPath();
        if (ffmpegPath == null) {
            throw new Exception("FFmpeg 不可用，请确保应用目录下包含 ffmpeg");
        }
        System.out.println("[Export] 使用 FFmpeg: " + ffmpegPath);

        // 检查 ffmpeg 是否可用
        ProcessBuilder testProcess = new ProcessBuilder(ffmpegPath, "-version");
        testProcess.redirectErrorStream(true);
        Process test = testProcess.start();
        if (test.waitFor() != 0) {
            throw new Exception("FFmpeg 无法执行，请检查文件是否损坏");
        }

        if (isCancelled) return;

        // 计算导出时长（从入点到出点）
        long exportDurationMs = outPointMs - inPointMs;

        System.out.println("[Export] 开始导出，范围: " + inPointMs + "-" + outPointMs + "ms, 时长: " + exportDurationMs + "ms, 帧率: " + EXPORT_FPS + "fps");

        int totalFrames = (int) ((exportDurationMs / 1000.0) * EXPORT_FPS);

        // 获取舞台视图尺寸
        int stageWidth = (int) stageView.getWidth();
        int stageHeight = (int) stageView.getHeight();
        if (stageWidth <= 0) stageWidth = 960;
        if (stageHeight <= 0) stageHeight = 1080;

        // 确保尺寸是偶数（ffmpeg 要求）
        stageWidth = (stageWidth / 2) * 2;
        stageHeight = (stageHeight / 2) * 2;

        System.out.println("[Export] 舞台视图尺寸: " + stageWidth + "x" + stageHeight);
        System.out.println("[Export] 总帧数: " + totalFrames);

        if (hasReferenceVideo && referenceVideo != null) {
            // 有参考视频：先生成临时帧再合成（因为需要 filter_complex）
            ReferenceVideoOptimizedExporter.export(
                viewModel,
                stageView,
                referenceVideo,
                ffmpegPath,
                EXPORT_FPS,
                ENCODE_PRESET,
                outputFile,
                inPointMs,
                exportDurationMs,
                totalFrames,
                () -> isCancelled,
                (progress, message) -> {
                    if (progressListener != null) {
                        progressListener.onProgress(progress, message);
                    }
                },
                this::runFfmpeg
            );
        } else {
            // 纯 2D 视角：使用 pipe 直接输入
            exportWithPipe(outputFile, inPointMs, exportDurationMs, totalFrames, stageWidth, stageHeight);
        }

        if (isCancelled) {
            if (outputFile.exists()) outputFile.delete();
            System.out.println("[Export] 导出已取消，清理文件");
        } else {
            System.out.println("[Export] 导出完成: " + outputFile.getAbsolutePath());
        }
    }

    /**
     * 使用 pipe 直接输入像素流导出（无参考视频，最快模式）
     */
    private void exportWithPipe(File outputFile, long inPointMs, long exportDurationMs, int totalFrames,
                                int width, int height) throws Exception {
        if (isCancelled) return;

        // 构建 ffmpeg 命令：从 stdin 读取原始像素
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");  // 覆盖输出文件

        // 输入：从 stdin 读取原始像素 (BGRA 格式)
        command.add("-f"); command.add("rawvideo");
        command.add("-pix_fmt"); command.add("bgra");
        command.add("-s"); command.add(width + "x" + height);
        command.add("-r"); command.add(String.valueOf(EXPORT_FPS));
        command.add("-i"); command.add("pipe:0");

        // 如果有音频文件，添加音频（从入点到出点）
        if (audioFile != null && audioFile.exists()) {
            command.add("-ss");
            command.add(ExportTimeUtil.formatTime(inPointMs));  // 从入点开始
            command.add("-t");
            command.add(ExportTimeUtil.formatTime(exportDurationMs));  // 导出时长
            command.add("-i");
            command.add(audioFile.getAbsolutePath());
            command.add("-map"); command.add("0:v");
            command.add("-map"); command.add("1:a");
            command.add("-c:a"); command.add("aac");
            command.add("-b:a"); command.add("128k");
            command.add("-shortest");  // 以较短的流为准
        }

        // 视频编码参数（优化速度）
        command.add("-c:v"); command.add("libx264");
        command.add("-preset"); command.add(ENCODE_PRESET);
        command.add("-crf"); command.add("23");
        command.add("-pix_fmt"); command.add("yuv420p");
        command.add("-threads"); command.add("0");  // 自动检测 CPU 核心数

        command.add(outputFile.getAbsolutePath());

        System.out.println("[Export] 使用 pipe 模式导出...");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        currentProcess = pb.start();
        final Process process = currentProcess;

        // 在单独线程中读取 ffmpeg 输出（避免缓冲区满导致阻塞）
        Thread outputReader = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("frame=") || line.contains("error")) {
                        System.out.println("[FFmpeg] " + line);
                    }
                }
            } catch (Exception e) {
                // 忽略读取错误
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        // 获取 ffmpeg 的 stdin
        java.io.OutputStream ffmpegInput = process.getOutputStream();

        // 预分配像素缓冲区
        int pixelBufferSize = width * height * 4;  // BGRA
        byte[] pixelBuffer = new byte[pixelBufferSize];

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);

        try {
            for (int frame = 0; frame < totalFrames; frame++) {
                if (isCancelled) break;

                final int currentFrame = frame;
                // 计算当前时间（加上入点偏移）
                long timeMs = inPointMs + (long) ((frame / (double) EXPORT_FPS) * 1000);
                if (timeMs > inPointMs + exportDurationMs) timeMs = inPointMs + exportDurationMs;
                final long finalTimeMs = timeMs;

                // 在 JavaFX 线程中截图并提取像素
                CountDownLatch latch = new CountDownLatch(1);
                final int[][] pixelsHolder = new int[1][];

                Platform.runLater(() -> {
                    try {
                        viewModel.setCurrentTime(finalTimeMs);
                        stageView.render();

                        WritableImage image = stageView.snapshot(params, null);
                        if (image != null) {
                            int imgWidth = (int) image.getWidth();
                            int imgHeight = (int) image.getHeight();
                            pixelsHolder[0] = new int[imgWidth * imgHeight];
                            image.getPixelReader().getPixels(
                                0, 0, imgWidth, imgHeight,
                                javafx.scene.image.WritablePixelFormat.getIntArgbInstance(),
                                pixelsHolder[0], 0, imgWidth
                            );
                        }
                    } catch (Exception e) {
                        System.err.println("[Export] 截图失败: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });

                // 添加超时保护，避免无限等待（最多等待5秒）
                if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    System.err.println("[Export] 警告: 帧 " + frame + " 渲染超时");
                    continue;
                }

                if (isCancelled) break;

                // 将像素写入 ffmpeg stdin
                if (pixelsHolder[0] != null) {
                    // 转换 ARGB 到 BGRA
                    int[] pixels = pixelsHolder[0];
                    int actualSize = Math.min(pixels.length * 4, pixelBufferSize);
                    for (int i = 0, j = 0; j < actualSize && i < pixels.length; i++, j += 4) {
                        int argb = pixels[i];
                        pixelBuffer[j] = (byte) (argb & 0xFF);         // B
                        pixelBuffer[j + 1] = (byte) ((argb >> 8) & 0xFF);  // G
                        pixelBuffer[j + 2] = (byte) ((argb >> 16) & 0xFF); // R
                        pixelBuffer[j + 3] = (byte) ((argb >> 24) & 0xFF); // A
                    }
                    ffmpegInput.write(pixelBuffer, 0, pixelBufferSize);
                }

                // 进度日志
                if (frame % 10 == 0 || frame == totalFrames - 1) {
                    double progress = (frame + 1) * 1.0 / totalFrames;
                    if (progressListener != null) {
                        progressListener.onProgress(progress, String.format("正在渲染帧 %d/%d (%.1f%%)",
                            frame + 1, totalFrames, progress * 100));
                    }
                }
            }

            ffmpegInput.flush();
            ffmpegInput.close();

        } catch (Exception e) {
            process.destroyForcibly();
            throw e;
        }

        if (isCancelled) {
            process.destroyForcibly();
        } else {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Exception("FFmpeg 处理失败，退出码: " + exitCode);
            }
        }
    }

    /**
     * 运行 ffmpeg 命令
     */
    private void runFfmpeg(List<String> command) throws Exception {
        FfmpegExecutor.execute(
            command,
            () -> isCancelled,
            process -> currentProcess = process,
            () -> {
                if (progressListener != null) {
                    progressListener.onProgress(0.9, "正在编码...");
                }
            }
        );
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }

    /**
     * 使用配置导出视频
     * 支持单屏、左右分屏、上下分屏等布局模式
     */
    public void exportWithConfig(ExportConfig config, File outputFile, long inPointMs, long outPointMs) throws Exception {
        if (isCancelled) return;

        ffmpegPath = FFmpegUtil.getFFmpegPath();
        if (ffmpegPath == null) {
            throw new Exception("FFmpeg 不可用，请确保应用目录下包含 ffmpeg");
        }
        System.out.println("[Export] 使用 FFmpeg: " + ffmpegPath);

        // 检查 ffmpeg 是否可用
        ProcessBuilder testProcess = new ProcessBuilder(ffmpegPath, "-version");
        testProcess.redirectErrorStream(true);
        Process test = testProcess.start();
        if (test.waitFor() != 0) {
            throw new Exception("FFmpeg 无法执行，请检查文件是否损坏");
        }

        if (isCancelled) return;

        long exportDurationMs = outPointMs - inPointMs;
        System.out.println("[Export] 布局模式: " + config.getLayoutMode() + ", 范围: " + inPointMs + "-" + outPointMs + "ms");

        int totalFrames = (int) ((exportDurationMs / 1000.0) * EXPORT_FPS);

        // 根据布局模式确定输出尺寸
        int outputWidth, outputHeight;
        int stageWidth = (int) stageView.getWidth();
        int stageHeight = (int) stageView.getHeight();
        if (stageWidth <= 0) stageWidth = 960;
        if (stageHeight <= 0) stageHeight = 1080;
        stageWidth = (stageWidth / 2) * 2;
        stageHeight = (stageHeight / 2) * 2;

        switch (config.getLayoutMode()) {
            case SINGLE:
                outputWidth = 1920;
                outputHeight = 1080;
                break;
            case SPLIT_VERTICAL:
                outputWidth = 1080;
                outputHeight = 1920;
                break;
            case SPLIT_HORIZONTAL:
            default:
                outputWidth = 1920;
                outputHeight = 1080;
                break;
        }

        System.out.println("[Export] 输出尺寸: " + outputWidth + "x" + outputHeight + ", 总帧数: " + totalFrames);

        // 检查是否需要参考视频
        boolean needRefVideo = config.getPrimaryView() == ExportConfig.ViewType.REFERENCE_VIDEO ||
                               (config.getLayoutMode() != ExportConfig.LayoutMode.SINGLE &&
                                config.getSecondaryView() == ExportConfig.ViewType.REFERENCE_VIDEO);

        // Calculate correct render dimensions for the 2D view
        int renderWidth = outputWidth;
        int renderHeight = outputHeight;

        if (config.isSplitMode()) {
            if (config.isHorizontalSplit()) {
                renderWidth = outputWidth / 2;
            } else if (config.isVerticalSplit()) {
                renderHeight = outputHeight / 2;
            }
        }

        System.out.println("[Export] 2D视图渲染尺寸: " + renderWidth + "x" + renderHeight);

        // 使用 CPU 编码器（移除 GPU 加速以避免 FFmpeg -22 错误）
        String encoder = "libx264";
        String preset = ENCODE_PRESET;
        System.out.println("[Export] 使用 CPU 编码器: " + encoder);

        if (needRefVideo && referenceVideo != null) {
            exportWithConfigAndRefVideo(config, outputFile, inPointMs, exportDurationMs, totalFrames,
                                       stageWidth, stageHeight, outputWidth, outputHeight, renderWidth, renderHeight, encoder, preset);
        } else {
            exportWithConfigNormal(config, outputFile, inPointMs, exportDurationMs, totalFrames,
                                   stageWidth, stageHeight, outputWidth, outputHeight, renderWidth, renderHeight, encoder, preset);
        }

        if (isCancelled) {
            if (outputFile.exists()) outputFile.delete();
            System.out.println("[Export] 导出已取消");
        } else {
            System.out.println("[Export] 导出完成: " + outputFile.getAbsolutePath());
        }
    }

    /**
     * 带配置导出（无参考视频）
     * 修复：使用固定的输出尺寸作为渲染尺寸，避免画面变形
     */
    private void exportWithConfigNormal(ExportConfig config, File outputFile, long inPointMs, long exportDurationMs,
                                        int totalFrames, int stageWidth, int stageHeight, int outputWidth, int outputHeight,
                                        int renderWidth, int renderHeight,
                                        String encoder, String preset) throws Exception {
        if (isCancelled) return;

        // 修复：使用固定的输出尺寸作为渲染尺寸，确保比例正确
        System.out.println("[Export] 渲染尺寸: " + renderWidth + "x" + renderHeight + " (输出尺寸: " + outputWidth + "x" + outputHeight + ")");

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");

        // 输入：从 stdin 读取原始像素（使用固定的渲染尺寸）
        command.add("-f"); command.add("rawvideo");
        command.add("-pix_fmt"); command.add("bgra");
        command.add("-s"); command.add(renderWidth + "x" + renderHeight);
        command.add("-r"); command.add(String.valueOf(EXPORT_FPS));
        command.add("-i"); command.add("pipe:0");

        // 音频
        if (audioFile != null && audioFile.exists()) {
            command.add("-ss"); command.add(ExportTimeUtil.formatTime(inPointMs));
            command.add("-t"); command.add(ExportTimeUtil.formatTime(exportDurationMs));
            command.add("-i"); command.add(audioFile.getAbsolutePath());
            command.add("-map"); command.add("0:v");
            command.add("-map"); command.add("1:a");
            command.add("-c:a"); command.add("aac");
            command.add("-b:a"); command.add("128k");
            command.add("-shortest");
        }

        // 编码参数（渲染尺寸与输出尺寸相同，无需额外缩放）
        command.add("-c:v"); command.add(encoder);
        command.add("-preset"); command.add(preset);
        if (encoder.equals("libx264")) {
            command.add("-crf"); command.add("23");
        } else {
            command.add("-b:v"); command.add("8M");
        }
        command.add("-pix_fmt"); command.add("yuv420p");
        command.add("-threads"); command.add("0");

        command.add(outputFile.getAbsolutePath());

        System.out.println("[Export] 使用 pipe 模式导出...");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        currentProcess = pb.start();
        final Process process = currentProcess;

        Thread outputReader = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("frame=") || line.contains("error")) {
                        System.out.println("[FFmpeg] " + line);
                    }
                }
            } catch (Exception e) {
                System.err.println("[FFmpeg] 读取输出线程异常: " + e.getMessage());
                e.printStackTrace();
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        java.io.OutputStream ffmpegInput = process.getOutputStream();

        // 使用固定的渲染尺寸创建像素缓冲区
        int pixelBufferSize = renderWidth * renderHeight * 4;
        byte[] pixelBuffer = new byte[pixelBufferSize];

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.rgb(26, 26, 31));  // 使用舞台背景色而非透明

        // 创建离屏 StageView 用于导出，避免影响主界面
        final StageView[] exportStageViewHolder = new StageView[1];
        CountDownLatch initLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            StageView exportView = new StageView(viewModel);
            exportView.setWidth(renderWidth);
            exportView.setHeight(renderHeight);
            // 复制主界面设置
            exportView.setShowDancerNames(stageView.isShowDancerNames());
            exportView.setShowCenterLine(stageView.isShowCenterLine());
            exportView.setFlipped(config.isFlipped());
            // 关键修复：同步舞台尺寸
            DanceGroup group = viewModel.getCurrentGroup();
            if (group != null) {
                exportView.updateStageSize(group.getStageWidth(), group.getStageDepth());
            }
            exportStageViewHolder[0] = exportView;
            initLatch.countDown();
        });
        initLatch.await();
        final StageView exportStageView = exportStageViewHolder[0];

        try {
            for (int frame = 0; frame < totalFrames; frame++) {
                if (isCancelled) break;

                final int currentFrame = frame;
                long timeMs = inPointMs + (long) ((frame / (double) EXPORT_FPS) * 1000);
                if (timeMs > inPointMs + exportDurationMs) timeMs = inPointMs + exportDurationMs;
                final long finalTimeMs = timeMs;

                CountDownLatch latch = new CountDownLatch(1);
                final int[][] pixelsHolder = new int[1][];

                Platform.runLater(() -> {
                    try {
                        // 应用缩放
                        exportStageView.setViewScale(config.getViewScale());
                        viewModel.setCurrentTime(finalTimeMs);
                        exportStageView.render();

                        // 使用预创建的 WritableImage 确保尺寸精确
                        WritableImage image = new WritableImage(renderWidth, renderHeight);
                        exportStageView.snapshot(params, image);

                        pixelsHolder[0] = new int[renderWidth * renderHeight];
                        image.getPixelReader().getPixels(
                            0, 0, renderWidth, renderHeight,
                            javafx.scene.image.WritablePixelFormat.getIntArgbInstance(),
                            pixelsHolder[0], 0, renderWidth
                        );
                    } catch (Exception e) {
                        System.err.println("[Export] 截图失败: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });

                latch.await();

                if (isCancelled) break;

                if (pixelsHolder[0] != null) {
                    int[] pixels = pixelsHolder[0];
                    for (int i = 0, j = 0; i < pixels.length && j < pixelBufferSize; i++, j += 4) {
                        int argb = pixels[i];
                        pixelBuffer[j] = (byte) (argb & 0xFF);
                        pixelBuffer[j + 1] = (byte) ((argb >> 8) & 0xFF);
                        pixelBuffer[j + 2] = (byte) ((argb >> 16) & 0xFF);
                        pixelBuffer[j + 3] = (byte) ((argb >> 24) & 0xFF);
                    }
                    ffmpegInput.write(pixelBuffer, 0, pixelBufferSize);
                }

                if (frame % 10 == 0 || frame == totalFrames - 1) {
                    double progress = (frame + 1) * 1.0 / totalFrames;
                    if (progressListener != null) {
                        progressListener.onProgress(progress, String.format("正在渲染帧 %d/%d (%.1f%%)",
                            frame + 1, totalFrames, progress * 100));
                    }
                }
            }

            ffmpegInput.flush();
            ffmpegInput.close();

        } catch (Exception e) {
            process.destroyForcibly();
            throw e;
        } finally {
            // 无需恢复尺寸，因为使用的是离屏 StageView
        }

        if (isCancelled) {
            process.destroyForcibly();
        } else {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Exception("FFmpeg 处理失败，退出码: " + exitCode);
            }
        }
    }

    /**
     * 带配置导出（有参考视频）
     * 修复：使用固定的输出尺寸作为渲染尺寸，避免画面变形
     */
    private void exportWithConfigAndRefVideo(ExportConfig config, File outputFile, long inPointMs, long exportDurationMs,
                                              int totalFrames, int stageWidth, int stageHeight, int outputWidth, int outputHeight,
                                              int renderWidth, int renderHeight,
                                              String encoder, String preset) throws Exception {
        if (isCancelled) return;

        // 修复：使用固定的输出尺寸作为渲染尺寸
        System.out.println("[Export] 渲染尺寸: " + renderWidth + "x" + renderHeight + " (有参考视频)");

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "formation_export_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) {
            throw new Exception("无法创建临时目录");
        }

        // 创建离屏 StageView 用于导出，避免影响主界面
        final StageView[] exportStageViewHolder = new StageView[1];
        CountDownLatch initLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            StageView exportView = new StageView(viewModel);
            exportView.setWidth(renderWidth);
            exportView.setHeight(renderHeight);
            // 复制主界面设置
            exportView.setShowDancerNames(stageView.isShowDancerNames());
            exportView.setShowCenterLine(stageView.isShowCenterLine());
            exportView.setFlipped(config.isFlipped());
            // 关键修复：同步舞台尺寸
            DanceGroup group = viewModel.getCurrentGroup();
            if (group != null) {
                exportView.updateStageSize(group.getStageWidth(), group.getStageDepth());
            }
            exportStageViewHolder[0] = exportView;
            initLatch.countDown();
        });
        initLatch.await();
        final StageView exportStageView = exportStageViewHolder[0];

        try {
            System.out.println("[Export] 生成帧序列...");

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.rgb(26, 26, 31));  // 使用舞台背景色

            for (int frame = 0; frame < totalFrames; frame++) {
                if (isCancelled) break;

                final int currentFrame = frame;
                long timeMs = inPointMs + (long) ((frame / (double) EXPORT_FPS) * 1000);
                if (timeMs > inPointMs + exportDurationMs) timeMs = inPointMs + exportDurationMs;
                final long finalTimeMs = timeMs;

                CountDownLatch latch = new CountDownLatch(1);
                final WritableImage[] imageHolder = new WritableImage[1];

                Platform.runLater(() -> {
                    try {
                        exportStageView.setViewScale(config.getViewScale());
                        viewModel.setCurrentTime(finalTimeMs);
                        exportStageView.render();

                        // 使用预创建的 WritableImage 确保尺寸精确
                        imageHolder[0] = new WritableImage(renderWidth, renderHeight);
                        exportStageView.snapshot(params, imageHolder[0]);
                    } catch (Exception e) {
                        System.err.println("[Export] 截图失败: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });

                // 添加超时保护，避免无限等待（最多等待5秒）
                if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    System.err.println("[Export] 警告: 帧 " + currentFrame + " 渲染超时");
                    continue;
                }

                if (isCancelled) break;

                if (imageHolder[0] != null) {
                    File frameFile = new File(tempDir, String.format("frame_%05d.jpg", currentFrame));
                    java.awt.image.BufferedImage bimg = SwingFXUtils.fromFXImage(imageHolder[0], null);
                    java.awt.image.BufferedImage rgbImage = new java.awt.image.BufferedImage(
                        bimg.getWidth(), bimg.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    rgbImage.createGraphics().drawImage(bimg, 0, 0, java.awt.Color.BLACK, null);
                    ImageIO.write(rgbImage, "jpg", frameFile);
                }

                if (frame % 10 == 0 || frame == totalFrames - 1) {
                    double progress = (frame + 1) * 0.8 / totalFrames;
                    if (progressListener != null) {
                        progressListener.onProgress(progress, String.format("已生成帧: %d/%d", frame + 1, totalFrames));
                    }
                }
            }

            if (isCancelled) return;

            System.out.println("[Export] 开始编码视频...");
            if (progressListener != null) {
                progressListener.onProgress(0.85, "开始编码视频...");
            }

            // 构建 filter_complex 根据布局模式
            String filter;
            switch (config.getLayoutMode()) {
                case SINGLE:
                    // 单屏模式：只输出一个视图
                    if (config.getPrimaryView() == ExportConfig.ViewType.REFERENCE_VIDEO) {
                        filter = "[1:v]scale=" + outputWidth + ":" + outputHeight + "[v]";
                    } else {
                        filter = "[0:v]scale=" + outputWidth + ":" + outputHeight + "[v]";
                    }
                    break;
                case SPLIT_VERTICAL:
                    // 上下分屏
                    filter = ExportFilterBuilder.buildVerticalSplitFilter(config, outputWidth, outputHeight);
                    break;
                case SPLIT_HORIZONTAL:
                default:
                    // 左右分屏
                    filter = ExportFilterBuilder.buildHorizontalSplitFilter(config, outputWidth, outputHeight);
                    break;
            }

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-y");

            // 输入 1：帧序列
            command.add("-framerate"); command.add(String.valueOf(EXPORT_FPS));
            command.add("-i"); command.add(new File(tempDir, "frame_%05d.jpg").getAbsolutePath());

            // 输入 2：参考视频
            command.add("-ss"); command.add(ExportTimeUtil.formatTime(inPointMs));
            command.add("-t"); command.add(ExportTimeUtil.formatTime(exportDurationMs));
            command.add("-i"); command.add(referenceVideo.getAbsolutePath());

            command.add("-filter_complex"); command.add(filter);

            command.add("-map"); command.add("[v]");
            command.add("-map"); command.add("1:a?");

            command.add("-c:v"); command.add(encoder);
            command.add("-preset"); command.add(preset);
            if (encoder.equals("libx264")) {
                command.add("-crf"); command.add("23");
            } else {
                command.add("-b:v"); command.add("8M");
            }
            command.add("-c:a"); command.add("aac");
            command.add("-b:a"); command.add("128k");
            command.add("-threads"); command.add("0");

            command.add(outputFile.getAbsolutePath());

            runFfmpeg(command);

        } finally {
            // 无需恢复尺寸
            deleteDirectory(tempDir);
        }
    }
}
