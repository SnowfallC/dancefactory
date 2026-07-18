package com.formationtool.desktop.export;

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
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

final class ReferenceVideoOptimizedExporter {
    @FunctionalInterface
    interface CommandRunner {
        void run(List<String> command) throws Exception;
    }

    private ReferenceVideoOptimizedExporter() {
    }

    static void export(
        FormationViewModel viewModel,
        StageView stageView,
        File referenceVideo,
        String ffmpegPath,
        int exportFps,
        String encodePreset,
        File outputFile,
        long inPointMs,
        long exportDurationMs,
        int totalFrames,
        BooleanSupplier isCancelled,
        BiConsumer<Double, String> onProgress,
        CommandRunner commandRunner
    ) throws Exception {
        if (isCancelled.getAsBoolean()) {
            return;
        }

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "formation_export_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) {
            throw new Exception("无法创建临时目录");
        }

        try {
            System.out.println("[Export] 开始渲染帧...");

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);

            for (int frame = 0; frame < totalFrames; frame++) {
                if (isCancelled.getAsBoolean()) {
                    break;
                }

                final int currentFrame = frame;
                long timeMs = inPointMs + (long) ((frame / (double) exportFps) * 1000);
                if (timeMs > inPointMs + exportDurationMs) {
                    timeMs = inPointMs + exportDurationMs;
                }
                final long finalTimeMs = timeMs;

                CountDownLatch latch = new CountDownLatch(1);
                final WritableImage[] imageHolder = new WritableImage[1];

                Platform.runLater(() -> {
                    try {
                        viewModel.setCurrentTime(finalTimeMs);
                        stageView.render();
                        imageHolder[0] = stageView.snapshot(params, null);
                    } catch (Exception e) {
                        System.err.println("[Export] 截图失败: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });

                if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    System.err.println("[Export] 渲染超时: 第" + currentFrame + " 帧");
                    continue;
                }

                if (isCancelled.getAsBoolean()) {
                    break;
                }

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
                    if (onProgress != null) {
                        onProgress.accept(progress, String.format("已生成帧: %d/%d (%.1f%%)",
                            frame + 1, totalFrames, (frame + 1) * 100.0 / totalFrames));
                    }
                }
            }

            if (isCancelled.getAsBoolean()) {
                return;
            }

            System.out.println("[Export] 生成帧序列...");
            if (onProgress != null) {
                onProgress.accept(0.85, "生成帧序列...");
            }

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-y");

            command.add("-framerate");
            command.add(String.valueOf(exportFps));
            command.add("-i");
            command.add(new File(tempDir, "frame_%05d.jpg").getAbsolutePath());

            command.add("-i");
            command.add(referenceVideo.getAbsolutePath());

            String filter =
                "[1:v]scale=960:1080:force_original_aspect_ratio=decrease," +
                "pad=960:1080:(ow-iw)/2:(oh-ih)/2:black[left];" +
                "[0:v]scale=960:1080:force_original_aspect_ratio=decrease," +
                "pad=960:1080:(ow-iw)/2:(oh-ih)/2:black[right];" +
                "[left][right]hstack=inputs=2[v]";
            command.add("-filter_complex");
            command.add(filter);

            command.add("-map");
            command.add("[v]");
            command.add("-map");
            command.add("1:a?");

            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add(encodePreset);
            command.add("-crf");
            command.add("23");
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("128k");
            command.add("-threads");
            command.add("0");

            command.add(outputFile.getAbsolutePath());

            commandRunner.run(command);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
