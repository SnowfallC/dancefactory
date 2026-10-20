package com.formationtool.desktop.utils;

import com.formationtool.desktop.AppVersion;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** 将现有控制台输出和未捕获异常写入本机滚动日志。 */
public final class AppLog {
    private static final Logger logger = Logger.getLogger("com.formationtool.desktop.runtime");
    private static final DateTimeFormatter timeFormat = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static FileHandler fileHandler;
    private static Path directory;
    private static boolean installed;

    private AppLog() { }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        String localAppData = System.getenv("LOCALAPPDATA");
        Path preferred = localAppData != null && !localAppData.isBlank()
            ? Path.of(localAppData, "FormationTool", "logs")
            : Path.of(System.getProperty("user.home"), ".formation-tool", "logs");
        for (Path candidate : new Path[]{preferred,
                Path.of(System.getProperty("java.io.tmpdir"), "FormationTool", "logs")}) {
            try {
                fileHandler = openHandler(candidate);
                directory = candidate.toAbsolutePath();
                break;
            } catch (Exception error) {
                originalErr.println("[日志] 无法写入 " + candidate + ": " + error.getMessage());
            }
        }
        if (fileHandler == null) {
            directory = preferred.toAbsolutePath();
            return;
        }
        logger.addHandler(fileHandler);
        System.setOut(new PrintStream(new LineCapture(originalOut, Level.INFO), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LineCapture(originalErr, Level.WARNING), true, StandardCharsets.UTF_8));
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            logger.log(Level.SEVERE, "未捕获异常，线程: " + thread.getName(), error);
            flush();
            if (previousHandler != null) previousHandler.uncaughtException(thread, error);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(AppLog::flush, "FlushApplicationLog"));
        logger.info("应用启动 version=" + AppVersion.VERSION + " pid=" + ProcessHandle.current().pid());
        logger.info("系统=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " arch=" + System.getProperty("os.arch") + " Java=" + System.getProperty("java.version")
            + " vendor=" + System.getProperty("java.vendor") + " JavaFX=" + System.getProperty("javafx.runtime.version", "未知"));
        logger.info("日志目录=" + directory);
    }

    static FileHandler openHandler(Path path) throws Exception {
        Files.createDirectories(path);
        // 每份约 2 MB，最多保留 5 份；FileHandler 也会处理同时启动多个实例时的文件锁。
        FileHandler handler = new FileHandler(path.resolve("formation-%u-%g.log").toString(), 2 * 1024 * 1024, 5, true);
        handler.setEncoding(StandardCharsets.UTF_8.name());
        handler.setFormatter(new Formatter() {
            @Override public String format(LogRecord record) {
                String text = timeFormat.format(record.getInstant()) + " [" + record.getLevel() + "] [线程 "
                    + record.getLongThreadID() + "] " + formatMessage(record) + System.lineSeparator();
                if (record.getThrown() == null) return text;
                StringWriter stack = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(stack));
                return text + stack;
            }
        });
        return handler;
    }

    public static synchronized Path getDirectory() {
        install();
        return directory;
    }

    public static synchronized boolean isEnabled() { return fileHandler != null; }

    public static void error(String title, String message) {
        install();
        logger.severe(title + ": " + message);
        flush();
    }

    public static synchronized void flush() {
        if (fileHandler != null) fileHandler.flush();
    }

    private static final class LineCapture extends OutputStream {
        private final PrintStream original;
        private final Level level;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        private LineCapture(PrintStream original, Level level) {
            this.original = original;
            this.level = level;
        }

        @Override public synchronized void write(int value) {
            original.write(value);
            capture(value);
        }

        @Override public synchronized void write(byte[] data, int offset, int length) {
            original.write(data, offset, length);
            for (int index = offset; index < offset + length; index++) capture(data[index] & 0xff);
        }

        private void capture(int value) {
            if (value == '\n') emit();
            else if (value != '\r') {
                pending.write(value);
                if (pending.size() >= 65536) emit();
            }
        }

        private void emit() {
            if (pending.size() == 0) return;
            logger.log(level, pending.toString(StandardCharsets.UTF_8));
            pending.reset();
        }

        @Override public synchronized void flush() {
            original.flush();
            // 自动刷新会在写出部分 UTF-8 字节时发生，仅在完整换行后记录。
            AppLog.flush();
        }

        @Override public synchronized void close() {
            emit();
            flush();
        }
    }
}
