package com.formationtool.desktop.media;

import com.formationtool.desktop.utils.FFmpegUtil;
import com.formationtool.desktop.view.TimelineView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;

import java.io.File;

public final class MediaProcessingService {
    private final FormationViewModel viewModel;
    private final TimelineView timelineView;

    public MediaProcessingService(FormationViewModel viewModel, TimelineView timelineView) {
        this.viewModel = viewModel;
        this.timelineView = timelineView;
    }

    /**
     * 使用FFmpeg将音频转换为WAV格式
     * @param inputFile 输入音频文件
     * @return 转换后的WAV文件，如果转换失败返回null
     */
    public File convertAudioToWAV(File inputFile) {
        try {
            String ffmpegPath = FFmpegUtil.getFFmpegPath();
            if (ffmpegPath == null) {
                System.err.println("[ERROR] FFmpeg 不可用");
                return null;
            }

            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            File outputFile = new File(tempDir, "audio_" + System.currentTimeMillis() + ".wav");

            System.out.println("[INFO] 开始转换音频: " + inputFile.getName() + " -> " + outputFile.getName());

            ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegPath,
                "-i", inputFile.getAbsolutePath(),
                "-acodec", "pcm_s16le",
                "-ar", "44100",
                "-ac", "2",
                "-y",
                outputFile.getAbsolutePath()
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[FFmpeg] " + line);
            }

            int exitCode = process.waitFor();

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                System.out.println("[INFO] 音频转换成功: " + outputFile.getAbsolutePath());
                return outputFile;
            } else {
                System.err.println("[ERROR] FFmpeg转换失败，退出码: " + exitCode);
                return null;
            }

        } catch (Exception e) {
            System.err.println("[ERROR] 音频转换异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 使用FFmpeg从视频中提取音频
     * @param videoFile 视频文件
     * @return 提取的音频文件，如果失败返回null
     */
    public File extractAudioFromVideo(File videoFile) {
        try {
            String ffmpegPath = FFmpegUtil.getFFmpegPath();
            if (ffmpegPath == null) {
                System.err.println("[视频导入] FFmpeg 不可用，跳过音频提取");
                return null;
            }

            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            File outputFile = new File(tempDir, "extracted_audio_" + System.currentTimeMillis() + ".wav");

            System.out.println("[视频导入] 开始提取音频: " + videoFile.getName());

            ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegPath,
                "-i", videoFile.getAbsolutePath(),
                "-vn",
                "-acodec", "pcm_s16le",
                "-ar", "44100",
                "-ac", "2",
                "-y",
                outputFile.getAbsolutePath()
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[FFmpeg] " + line);
            }

            int exitCode = process.waitFor();

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                System.out.println("[视频导入] 音频提取成功");
                return outputFile;
            } else {
                System.err.println("[视频导入] FFmpeg提取失败");
                return null;
            }

        } catch (Exception e) {
            System.err.println("[视频导入] 音频提取异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 使用FFmpeg将视频转换为MP4格式
     * @param inputFile 输入视频文件
     * @return 转换后的MP4文件，如果转换失败返回null
     */
    public File convertVideoToMP4(File inputFile) {
        try {
            String ffmpegPath = FFmpegUtil.getFFmpegPath();
            if (ffmpegPath == null) {
                System.err.println("[ERROR] FFmpeg 不可用");
                return null;
            }

            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            File outputFile = new File(tempDir, "video_" + System.currentTimeMillis() + ".mp4");

            System.out.println("[INFO] 开始转换视频: " + inputFile.getName());

            ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegPath,
                "-hide_banner",
                "-i", inputFile.getAbsolutePath(),
                // Do not rely on the source container extension.  Explicit maps
                // retain the primary video and an audio stream only when present.
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-profile:v", "high",
                // yuv420p requires even dimensions; this preserves the source
                // size whenever it is already compatible.
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1",
                "-preset", "fast",
                "-crf", "21",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "48000",
                "-map_metadata", "0",
                "-sn",
                "-dn",
                "-movflags", "+faststart",
                "-y",
                outputFile.getAbsolutePath()
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[FFmpeg] " + line);
            }

            int exitCode = process.waitFor();

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                System.out.println("[INFO] 视频转换成功");
                return outputFile;
            } else {
                System.err.println("[ERROR] FFmpeg转换失败");
                return null;
            }

        } catch (Exception e) {
            System.err.println("[ERROR] 视频转换异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成或复用预览代理视频（用于拖动时间轴时提升跟手性）
     * 说明：
     * - 代理文件仅用于编辑预览，不影响导出源视频
     * - 采用低码率 + 低分辨率，优先保证 seek 响应速度
     *
     * @param sourceVideo 源视频文件
     * @return 代理视频文件，失败返回 null
     */
    public File createOrGetPreviewProxy(File sourceVideo) {
        try {
            if (sourceVideo == null || !sourceVideo.exists() || !sourceVideo.isFile()) {
                return null;
            }

            String ffmpegPath = FFmpegUtil.getFFmpegPath();
            if (ffmpegPath == null) {
                System.err.println("[代理视频] FFmpeg 不可用，跳过代理生成");
                return null;
            }

            // 基于源文件路径+修改时间+大小生成稳定键，支持缓存复用
            String cacheKey = sourceVideo.getAbsolutePath() + "|" + sourceVideo.lastModified() + "|" + sourceVideo.length();
            String proxyName = "proxy_" + Integer.toHexString(cacheKey.hashCode()) + ".mp4";
            File proxyDir = new File(System.getProperty("java.io.tmpdir"), "formationtool_proxy");
            if (!proxyDir.exists() && !proxyDir.mkdirs()) {
                System.err.println("[代理视频] 无法创建代理目录: " + proxyDir.getAbsolutePath());
                return null;
            }

            File proxyFile = new File(proxyDir, proxyName);
            if (proxyFile.exists() && proxyFile.length() > 0) {
                System.out.println("[代理视频] 复用缓存代理: " + proxyFile.getAbsolutePath());
                return proxyFile;
            }

            System.out.println("[代理视频] 开始生成代理: " + sourceVideo.getName());

            // 低码率代理：640宽 + 视频无音频，优先 seek 性能
            ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegPath,
                "-i", sourceVideo.getAbsolutePath(),
                "-vf", "scale=640:-2",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "34",
                "-b:v", "700k",
                "-maxrate", "900k",
                "-bufsize", "1800k",
                "-an",
                "-movflags", "+faststart",
                "-y",
                proxyFile.getAbsolutePath()
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[代理视频-FFmpeg] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && proxyFile.exists() && proxyFile.length() > 0) {
                System.out.println("[代理视频] 生成成功: " + proxyFile.getAbsolutePath());
                return proxyFile;
            }

            System.err.println("[代理视频] 生成失败，退出码: " + exitCode);
            return null;
        } catch (Exception e) {
            System.err.println("[代理视频] 生成异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 提取音频波形数据
     */
    public void extractWaveformData(File audioFile) {
        Thread extractThread = new Thread(() -> {
            try {
                String ffmpegPath = FFmpegUtil.getFFmpegPath();
                if (ffmpegPath == null) {
                    System.err.println("[波形提取] FFmpeg 不可用");
                    return;
                }

                System.out.println("[波形提取] 开始提取波形数据: " + audioFile.getName());

                ProcessBuilder processBuilder = new ProcessBuilder(
                    ffmpegPath,
                    "-i", audioFile.getAbsolutePath(),
                    "-ac", "1",
                    "-ar", "8000",
                    "-f", "s16le",
                    "-acodec", "pcm_s16le",
                    "-"
                );

                processBuilder.redirectErrorStream(false);
                Process process = processBuilder.start();

                java.io.InputStream inputStream = process.getInputStream();
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

                byte[] chunk = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, bytesRead);
                }

                byte[] audioData = buffer.toByteArray();
                System.out.println("[波形提取] 读取了 " + audioData.length + " 字节的音频数据");

                int sampleCount = audioData.length / 2;
                float[] samples = new float[sampleCount];

                for (int i = 0; i < sampleCount; i++) {
                    int sample = (audioData[i * 2] & 0xFF) | ((audioData[i * 2 + 1] & 0xFF) << 8);
                    if (sample > 32767) {
                        sample -= 65536;
                    }
                    samples[i] = sample / 32768.0f;
                }

                long durationMs = viewModel.getAudioDuration();
                int targetSampleCount = (int) (durationMs / 10);
                float[] waveformData = downsampleWaveform(samples, targetSampleCount);

                System.out.println("[波形提取] 波形数据提取完成，共 " + waveformData.length + " 个点");

                Platform.runLater(() -> {
                    if (timelineView != null) {
                        timelineView.setWaveformData(waveformData);
                    }
                });

                process.waitFor();

            } catch (Exception e) {
                System.err.println("[波形提取] 失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
        extractThread.setDaemon(true);
        extractThread.start();
    }

    private float[] downsampleWaveform(float[] samples, int targetCount) {
        if (samples.length <= targetCount) {
            return samples;
        }

        float[] result = new float[targetCount];
        float samplesPerBucket = (float) samples.length / targetCount;

        for (int i = 0; i < targetCount; i++) {
            int startIdx = (int) (i * samplesPerBucket);
            int endIdx = (int) ((i + 1) * samplesPerBucket);

            float maxAmp = 0;
            for (int j = startIdx; j < endIdx && j < samples.length; j++) {
                float amp = Math.abs(samples[j]);
                if (amp > maxAmp) {
                    maxAmp = amp;
                }
            }
            result[i] = maxAmp;
        }

        return result;
    }
}
