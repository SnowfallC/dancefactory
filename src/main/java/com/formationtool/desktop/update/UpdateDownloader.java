package com.formationtool.desktop.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class UpdateDownloader {

    private UpdateDownloader() {}

    public interface ProgressCallback {
        void onProgress(double percent);
        void onStatus(String message);
        void onError(String message);
        void onComplete(File updatedApp);
    }

    public static void downloadAndInstall(UpdateChecker.UpdateInfo info,
                                          ProgressCallback callback) {
        Thread t = new Thread(() -> {
            try {
                Path tempDir = Files.createTempDirectory("dancefactory-update");
                Path zipFile = tempDir.resolve(info.fileName);

                callback.onStatus("正在下载 " + info.fileName + " ...");
                downloadFile(info.downloadUrl, zipFile, info.size, callback);

                callback.onStatus("正在校验文件完整性...");
                String actualSha = sha256(zipFile);
                if (!actualSha.equalsIgnoreCase(info.sha256)) {
                    callback.onError("文件校验失败！\n期望: " + info.sha256 + "\n实际: " + actualSha);
                    return;
                }

                callback.onStatus("正在解压...");
                Path extractDir = tempDir.resolve("extracted");
                Files.createDirectories(extractDir);
                unzip(zipFile, extractDir);

                callback.onStatus("正在准备安全更新...");
                File appDir = getAppDirectory();
                if (appDir == null) {
                    callback.onError("无法定位当前应用目录");
                    return;
                }

                Path extractedApp = findExtractedAppDir(extractDir);
                if (extractedApp == null) {
                    callback.onError("压缩包中未找到应用目录");
                    return;
                }

                launchExternalUpdater(appDir.toPath(), extractedApp, tempDir);

                File exeFile = findExe(appDir);
                callback.onComplete(exeFile);

            } catch (Exception e) {
                callback.onError("更新失败: " + e.getMessage());
            }
        }, "UpdateDownloader");
        t.setDaemon(true);
        t.start();
    }

    private static void downloadFile(String url, Path target, long expectedSize,
                                     ProgressCallback callback) throws Exception {
        if (expectedSize <= 0 || expectedSize > 2L * 1024 * 1024 * 1024) {
            throw new SecurityException("更新包大小无效");
        }
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SecurityException("更新地址必须使用 HTTPS");
        }
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "DanceFactory-Updater/1.1");

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(target.toFile())) {

            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total > expectedSize) {
                    throw new SecurityException("下载文件超过清单声明大小");
                }
                if (expectedSize > 0) {
                    callback.onProgress(total * 1.0 / expectedSize);
                }
            }
        } finally {
            conn.disconnect();
        }
        if (Files.size(target) != expectedSize) {
            throw new SecurityException("下载文件大小与清单不一致");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static void unzip(Path zipFile, Path destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destDir)) {
                    throw new SecurityException("ZIP 条目逃逸: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static File getAppDirectory() {
        String appDir = System.getProperty("user.dir");
        if (appDir != null) {
            File f = new File(appDir);
            if (f.exists() && new File(f, "舞形工坊.exe").exists()) {
                return f;
            }
        }

        try {
            String jarPath = UpdateDownloader.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(jarPath);
            File parent = jarFile.getParentFile();
            while (parent != null) {
                if (new File(parent, "舞形工坊.exe").exists()) {
                    return parent;
                }
                parent = parent.getParentFile();
            }
        } catch (Exception ignored) {}

        String appImage = System.getProperty("jpackage.app-image-dir");
        if (appImage != null) {
            File f = new File(appImage);
            if (f.exists()) return f;
        }

        return null;
    }

    private static Path findExtractedAppDir(Path extractDir) {
        File[] top = extractDir.toFile().listFiles();
        if (top == null) return null;

        for (File f : top) {
            if (f.isDirectory() && new File(f, "舞形工坊.exe").exists()) {
                return f.toPath();
            }
        }

        for (File f : top) {
            if (f.isDirectory()) {
                Path inner = findExtractedAppDir(f.toPath());
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private static File findExe(File dir) {
        File exe = new File(dir, "舞形工坊.exe");
        return exe.exists() ? exe : null;
    }

    private static void launchExternalUpdater(Path appDir, Path sourceDir, Path tempDir) throws Exception {
        Path helper = tempDir.resolve("apply-update.ps1");
        try (InputStream in = UpdateDownloader.class.getResourceAsStream("/update-helper.ps1")) {
            if (in == null) throw new IOException("更新辅助程序缺失");
            Files.copy(in, helper, StandardCopyOption.REPLACE_EXISTING);
        }

        long processId = ProcessHandle.current().pid();
        ProcessBuilder builder = new ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass",
            "-File", helper.toString(),
            "-ProcessId", Long.toString(processId),
            "-Source", sourceDir.toAbsolutePath().toString(),
            "-Target", appDir.toAbsolutePath().toString(),
            "-ExeName", "舞形工坊.exe"
        );
        builder.directory(tempDir.toFile());
        builder.redirectOutput(tempDir.resolve("update-helper.log").toFile());
        builder.redirectErrorStream(true);
        builder.start();
    }
}
