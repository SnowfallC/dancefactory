package com.formationtool.desktop.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AppLogTest {
    @TempDir Path temp;

    @Test void shouldCaptureConsoleAndUnhandledExceptionsInUtf8() throws Exception {
        ProcessBuilder command = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", System.getProperty("java.class.path"), LogProbe.class.getName());
        // 只为测试子进程指定本机数据目录，不修改主进程环境或用户设置。
        command.environment().put("LOCALAPPDATA", temp.toString());
        command.redirectErrorStream(true);
        command.redirectOutput(temp.resolve("console.txt").toFile());
        Process child = command.start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            Path folder = temp.resolve("FormationTool/logs");
            StringBuilder content = new StringBuilder();
            try (var files = Files.list(folder)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".log")).toList()) {
                    byte[] bytes = Files.readAllBytes(file);
                    assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf);
                    content.append(new String(bytes, StandardCharsets.UTF_8));
                }
            }
            assertTrue(content.toString().contains("标准输出中文"));
            assertTrue(content.toString().contains("错误输出中文"));
            assertTrue(content.toString().contains("未捕获异常"));
            assertTrue(content.toString().contains("java.lang.IllegalStateException: 解码失败样本"));
            assertTrue(content.toString().contains("应用启动 version="));
            assertEquals(1, content.toString().split("应用启动 version=", -1).length - 1);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    @Test void shouldLimitRetainedLogFiles() throws Exception {
        var handler = AppLog.openHandler(temp);
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(handler);
        try {
            String text = "诊断".repeat(10000);
            for (int count = 0; count < 220; count++) log.log(Level.INFO, text);
        } finally { handler.close(); }
        try (var files = Files.list(temp)) {
            var logs = files.filter(p -> p.toString().endsWith(".log")).toList();
            assertTrue(logs.size() <= 5);
            assertTrue(logs.size() > 1);
            for (Path file : logs) assertTrue(Files.size(file) <= 2 * 1024 * 1024 + 100000);
        }
    }

    public static final class LogProbe {
        public static void main(String[] args) throws Exception {
            AppLog.install();
            AppLog.install();
            System.out.println("标准输出中文");
            System.err.println("错误输出中文");
            Thread failed = new Thread(() -> { throw new IllegalStateException("解码失败样本"); }, "测试异常线程");
            failed.start(); failed.join();
            AppLog.flush();
        }
    }
}
