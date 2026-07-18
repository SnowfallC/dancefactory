package com.formationtool.desktop.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * FFmpeg 工具类
 * 优先使用打包在应用目录下的 FFmpeg，如果不存在则使用系统 PATH 中的
 * 支持 GPU 硬件加速检测 (NVENC)
 */
public class FFmpegUtil {

    private static String ffmpegPath = null;
    private static Boolean nvencSupported = null;  // null = 未检测, true/false = 检测结果

    /**
     * 获取 FFmpeg 可执行文件路径
     */
    public static String getFFmpegPath() {
        if (ffmpegPath != null) {
            return ffmpegPath;
        }

        System.out.println("[FFmpeg] 开始查找 FFmpeg...");

        // 1. 尝试多种方式获取应用目录
        String[] appDirs = getAppDirectories();

        for (String appDir : appDirs) {
            if (appDir == null) continue;
            System.out.println("[FFmpeg] 检查应用目录: " + appDir);

            String[] possiblePaths = {
                // jpackage 打包后: app/ffmpeg/ffmpeg.exe
                appDir + File.separator + "ffmpeg" + File.separator + "ffmpeg.exe",
                // 直接在应用目录下
                appDir + File.separator + "ffmpeg.exe",
                // 上级目录的 app/ffmpeg
                appDir + File.separator + ".." + File.separator + "app" + File.separator + "ffmpeg" + File.separator + "ffmpeg.exe",
                // 开发环境: jpackage-input/ffmpeg/ffmpeg.exe
                appDir + File.separator + ".." + File.separator + "jpackage-input" + File.separator + "ffmpeg" + File.separator + "ffmpeg.exe",
            };

            for (String path : possiblePaths) {
                File ffmpegFile = new File(path);
                String absPath = null;
                try {
                    absPath = ffmpegFile.getCanonicalPath();
                } catch (Exception e) {
                    absPath = ffmpegFile.getAbsolutePath();
                }
                System.out.println("[FFmpeg] 检查: " + absPath + " -> " + ffmpegFile.exists());
                if (ffmpegFile.exists()) {
                    ffmpegPath = absPath;
                    System.out.println("[FFmpeg] 找到内置 FFmpeg: " + ffmpegPath);
                    return ffmpegPath;
                }
            }
        }

        // 2. 尝试系统 PATH
        if (isFFmpegInPath()) {
            ffmpegPath = "ffmpeg";
            System.out.println("[FFmpeg] 使用系统 PATH 中的 FFmpeg");
            return ffmpegPath;
        }

        System.err.println("[FFmpeg] 未找到 FFmpeg");
        return null;
    }

    /**
     * 检查 FFmpeg 是否可用
     */
    public static boolean isFFmpegAvailable() {
        return getFFmpegPath() != null;
    }

    /**
     * 检查系统 PATH 中是否有 FFmpeg
     */
    private static boolean isFFmpegInPath() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否支持 NVIDIA GPU 编码 (NVENC)
     * @return true 如果支持 h264_nvenc 编码器
     */
    public static boolean isNVENCSupported() {
        if (nvencSupported != null) {
            return nvencSupported;
        }

        String ffmpeg = getFFmpegPath();
        if (ffmpeg == null) {
            nvencSupported = false;
            return false;
        }

        try {
            System.out.println("[FFmpeg] 检测 NVENC GPU 编码支持...");

            // 检查 ffmpeg 是否有 h264_nvenc 编码器
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-encoders");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean found = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("h264_nvenc")) {
                    found = true;
                    System.out.println("[FFmpeg] 找到 NVENC 编码器: " + line.trim());
                    break;
                }
            }

            process.waitFor();
            reader.close();

            if (found) {
                // 进一步测试 NVENC 是否真的可用（有些系统有编码器但没有GPU驱动）
                nvencSupported = testNVENCEncoder(ffmpeg);
            } else {
                nvencSupported = false;
            }

            System.out.println("[FFmpeg] NVENC 支持: " + nvencSupported);
            return nvencSupported;

        } catch (Exception e) {
            System.err.println("[FFmpeg] NVENC 检测失败: " + e.getMessage());
            nvencSupported = false;
            return false;
        }
    }

    /**
     * 测试 NVENC 编码器是否真正可用
     */
    private static boolean testNVENCEncoder(String ffmpeg) {
        try {
            // 使用 lavfi 测试源来测试编码器
            ProcessBuilder pb = new ProcessBuilder(
                ffmpeg,
                "-f", "lavfi",
                "-i", "testsrc=duration=0.1:size=64x64:rate=1",
                "-c:v", "h264_nvenc",
                "-f", "null",
                "-"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出（避免缓冲区阻塞）
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            reader.close();

            if (exitCode == 0) {
                System.out.println("[FFmpeg] NVENC 编码器测试成功");
                return true;
            } else {
                // 检查是否是因为没有 NVIDIA GPU
                String out = output.toString().toLowerCase();
                if (out.contains("no capable devices found") ||
                    out.contains("cannot load") ||
                    out.contains("nvenc")) {
                    System.out.println("[FFmpeg] NVENC 不可用（无 NVIDIA GPU 或驱动问题）");
                }
                return false;
            }
        } catch (Exception e) {
            System.err.println("[FFmpeg] NVENC 测试异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取推荐的视频编码器
     * @param preferGPU 是否优先使用 GPU
     * @return 编码器名称 (h264_nvenc 或 libx264)
     */
    public static String getRecommendedEncoder(boolean preferGPU) {
        if (preferGPU && isNVENCSupported()) {
            return "h264_nvenc";
        }
        return "libx264";
    }

    /**
     * 获取编码器对应的预设参数
     * @param encoder 编码器名称
     * @param speed 速度优先级 ("fast", "medium", "slow")
     * @return 预设参数值
     */
    public static String getEncoderPreset(String encoder, String speed) {
        if ("h264_nvenc".equals(encoder)) {
            // NVENC 预设
            switch (speed) {
                case "fast": return "p1";   // 最快
                case "slow": return "p7";   // 最慢/最高质量
                default: return "p4";       // 平衡
            }
        } else {
            // libx264 预设
            switch (speed) {
                case "fast": return "veryfast";
                case "slow": return "slow";
                default: return "medium";
            }
        }
    }

    /**
     * 重置 GPU 检测缓存（用于重新检测）
     */
    public static void resetGPUCache() {
        nvencSupported = null;
    }

    /**
     * 获取多个可能的应用程序目录
     */
    private static String[] getAppDirectories() {
        String[] dirs = new String[4];

        // 方法1: 通过 JAR 位置
        try {
            String jarPath = FFmpegUtil.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI().getPath();

            // Windows 下去掉开头的 /
            if (jarPath.startsWith("/") && jarPath.length() > 2 && jarPath.charAt(2) == ':') {
                jarPath = jarPath.substring(1);
            }
            jarPath = java.net.URLDecoder.decode(jarPath, "UTF-8");

            File jarFile = new File(jarPath);
            dirs[0] = jarFile.getParent();
            System.out.println("[FFmpeg] JAR 目录: " + dirs[0]);
        } catch (Exception e) {
            System.err.println("[FFmpeg] 获取 JAR 目录失败: " + e.getMessage());
        }

        // 方法2: 通过 user.dir (当前工作目录)
        dirs[1] = System.getProperty("user.dir");
        System.out.println("[FFmpeg] 工作目录: " + dirs[1]);

        // 方法3: 通过 java.home 推断 (jpackage 打包后 runtime 在同级)
        try {
            String javaHome = System.getProperty("java.home");
            File runtimeDir = new File(javaHome);
            // jpackage 结构: app目录和runtime目录同级
            File appDir = new File(runtimeDir.getParent(), "app");
            if (appDir.exists()) {
                dirs[2] = appDir.getAbsolutePath();
                System.out.println("[FFmpeg] 通过 java.home 推断 app 目录: " + dirs[2]);
            }
        } catch (Exception e) {
            System.err.println("[FFmpeg] 通过 java.home 推断失败: " + e.getMessage());
        }

        // 方法4: 通过可执行文件位置 (Windows)
        try {
            String exePath = System.getProperty("jpackage.app-path");
            if (exePath != null) {
                File exeFile = new File(exePath);
                File appDir = new File(exeFile.getParent(), "app");
                if (appDir.exists()) {
                    dirs[3] = appDir.getAbsolutePath();
                    System.out.println("[FFmpeg] 通过 exe 路径推断 app 目录: " + dirs[3]);
                }
            }
        } catch (Exception e) {
            // 忽略
        }

        return dirs;
    }
}
