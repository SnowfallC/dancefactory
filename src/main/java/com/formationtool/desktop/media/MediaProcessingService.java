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
                // 保留主视频和首条可选音轨，兼容无声视频。
                "-map", "0:v:0",
                "-map", "0:a:0?",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-profile:v", "main",
                "-level:v", "4.1",
                "-tag:v", "avc1",
                "-r", "30",
                // 限制为偶数尺寸和最高 1080p，保持比例且不放大小分辨率视频。
                "-vf", "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1",
                "-preset", "fast",
                "-crf", "21",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "48000",
                "-ac", "2",
                "-map_metadata", "-1",
                "-sn",
                "-dn",
                "-movflags", "+faststart",
                "-y",
                outputFile.getAbsolutePath()
            );

            System.out.println("[视频转换] 命令参数=" + processBuilder.command());
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
                System.out.println("[视频转换] 成功 文件=" + outputFile.getAbsolutePath() + " 大小=" + outputFile.length());
                return outputFile;
            } else {
                System.err.println("[视频转换] 失败 exitCode=" + exitCode + " 文件=" + outputFile.getAbsolutePath());
                return null;
            }

        } catch (Exception e) {
            System.err.println("[ERROR] 视频转换异常: " + e.getMessage());
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
