package com.formationtool.desktop.media;

import com.formationtool.desktop.utils.FFmpegUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** JavaFX 加载失败后，独立检查视频开头能否被内置 FFmpeg 解码。 */
public final class VideoDiagnostics {
    private VideoDiagnostics() { }

    public static void logDecodeFailure(File file) {
        Thread worker = new Thread(() -> checkDecode(file), "VideoDecodeDiagnostics");
        worker.setDaemon(true);
        worker.start();
    }

    static boolean checkDecode(File file) {
        String ffmpeg = FFmpegUtil.getFFmpegPath();
        if (ffmpeg == null || file == null || !file.isFile()) {
            System.err.println("[视频诊断] 无法检查：FFmpeg 或文件不存在");
            return false;
        }
        Process process = null;
        try {
            ProcessBuilder command = new ProcessBuilder(ffmpeg, "-hide_banner", "-nostdin", "-xerror",
                "-i", file.getAbsolutePath(), "-t", "3", "-map", "0:v:0", "-an", "-f", "null", "-");
            command.redirectErrorStream(true);
            System.out.println("[视频诊断] 开始 文件=" + file.getAbsolutePath() + " 大小=" + file.length());
            System.out.println("[视频诊断] 命令参数=" + command.command());
            process = command.start();
            Process active = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8))) {
                    int recorded = 0;
                    String line;
                    while ((line = output.readLine()) != null) {
                        // 持续排空管道，但限制诊断输出量，避免损坏文件刷满日志。
                        if (recorded < 65536) {
                            System.out.println("[视频诊断] " + line);
                            recorded += line.length();
                        }
                    }
                } catch (Exception error) {
                    System.err.println("[视频诊断] 输出读取结束: " + error.getMessage());
                }
            }, "VideoDiagnosticOutput");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("[视频诊断] 检查超时，已停止诊断进程");
                return false;
            }
            reader.join(2000);
            boolean decoded = process.exitValue() == 0;
            System.out.println("[视频诊断] exitCode=" + process.exitValue() + " 前3秒软件解码=" + decoded
                + "；此结果不能代替整段视频检查，也不能单独确认系统组件故障。");
            return decoded;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            System.err.println("[视频诊断] 检查失败: " + error);
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
