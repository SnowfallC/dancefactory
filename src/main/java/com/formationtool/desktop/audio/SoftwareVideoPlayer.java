package com.formationtool.desktop.audio;

import com.formationtool.desktop.utils.FFmpegUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/** 使用独立 FFmpeg 进程解码预览，不依赖 JavaFX 的视频解码器。 */
public final class SoftwareVideoPlayer implements AutoCloseable {
    private static final int width = 640;
    private static final int height = 360;
    private static final int frameBytes = width * height * 4;
    private static final Pattern durationPattern = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)");
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "SoftwareVideoDecoder");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "SoftwareVideoWatchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private final AtomicReference<PendingFrame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean frameQueued = new AtomicBoolean();
    private final Consumer<Image> frameConsumer;
    private final Consumer<String> errorConsumer;
    private ScheduledFuture<?> pendingTask;
    private File source;
    private volatile long durationMs;
    private volatile boolean closed;

    private record PendingFrame(long generation, byte[] pixels) { }

    public SoftwareVideoPlayer(Consumer<Image> frameConsumer, Consumer<String> errorConsumer) {
        this.frameConsumer = frameConsumer;
        this.errorConsumer = errorConsumer;
        worker.setRemoveOnCancelPolicy(true);
        watchdog.setRemoveOnCancelPolicy(true);
    }

    public void load(File file, Runnable onReady) {
        source = file;
        durationMs = 0;
        long session = cancelDecode();
        pendingTask = worker.schedule(() -> decode(file, 0, session, null, onReady, null), 0, TimeUnit.MILLISECONDS);
    }

    public void play(long positionMs, LongSupplier playbackClock, Runnable onEnd) {
        long session = cancelDecode();
        File file = source;
        pendingTask = worker.schedule(() -> decode(file, positionMs, session, playbackClock, null, onEnd), 0, TimeUnit.MILLISECONDS);
    }

    public void seek(long positionMs) {
        long session = cancelDecode();
        File file = source;
        // 合并连续拖动请求，只处理最后一次定位。
        long target = durationMs > 0 ? Math.min(positionMs, Math.max(0, durationMs - 40)) : positionMs;
        pendingTask = worker.schedule(() -> decode(file, Math.max(0, target), session, null, null, null), 60, TimeUnit.MILLISECONDS);
    }

    public void pause() { cancelDecode(); }
    public long getDurationMs() { return durationMs; }

    private long cancelDecode() {
        long session = generation.incrementAndGet();
        if (pendingTask != null) pendingTask.cancel(true);
        Process previous = activeProcess.getAndSet(null);
        if (previous != null) previous.destroyForcibly();
        return session;
    }

    private boolean isCurrent(long session) { return !closed && generation.get() == session; }

    private void decode(File file, long positionMs, long session, LongSupplier clock, Runnable ready, Runnable ended) {
        Process process = null;
        ScheduledFuture<?> timeout = null;
        try {
            if (!isCurrent(session)) return;
            String ffmpeg = FFmpegUtil.getFFmpegPath();
            if (ffmpeg == null) throw new IllegalStateException("未找到内置 FFmpeg");
            List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-nostats",
                "-loglevel", ready != null ? "info" : "error", "-ss", String.format(Locale.ROOT, "%.3f", positionMs / 1000.0),
                "-i", file.getAbsolutePath(), "-map", "0:v:0", "-an", "-sn", "-dn",
                "-vf", "fps=25,scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1",
                "-pix_fmt", "bgra"));
            if (clock == null) command.addAll(List.of("-frames:v", "1"));
            command.addAll(List.of("-f", "rawvideo", "pipe:1"));
            process = new ProcessBuilder(command).start();
            activeProcess.set(process);
            if (!isCurrent(session)) return;
            Process current = process;
            if (clock == null) timeout = watchdog.schedule(current::destroyForcibly, 15, TimeUnit.SECONDS);
            Thread errors = new Thread(() -> readDiagnostics(current, session), "SoftwareVideoOutput");
            errors.setDaemon(true);
            errors.start();
            long frames = 0;
            try (InputStream input = process.getInputStream()) {
                while (isCurrent(session)) {
                    byte[] pixels = input.readNBytes(frameBytes);
                    if (pixels.length == 0) break;
                    if (pixels.length != frameBytes) throw new IllegalStateException("视频解码帧不完整");
                    long frameTime = positionMs + frames++ * 40;
                    if (clock != null) {
                        while (isCurrent(session) && frameTime > clock.getAsLong() + 40) Thread.sleep(5);
                        if (frameTime < clock.getAsLong() - 120) continue;
                    }
                    if (isCurrent(session)) queueFrame(session, pixels);
                }
            }
            if (!isCurrent(session)) return;
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("视频解码进程未及时结束");
            errors.join(1000);
            if (process.exitValue() != 0 || frames == 0) throw new IllegalStateException("FFmpeg 无法解码该视频，退出码=" + process.exitValue());
            Runnable completion = ready != null ? ready : ended;
            if (completion != null) Platform.runLater(() -> { if (isCurrent(session)) completion.run(); });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (isCurrent(session)) {
                System.err.println("[兼容播放] 解码失败: " + error);
                Platform.runLater(() -> { if (isCurrent(session)) errorConsumer.accept(error.getMessage()); });
            }
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                activeProcess.compareAndSet(process, null);
            }
        }
    }

    private void readDiagnostics(Process process, long session) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            int recorded = 0;
            while ((line = reader.readLine()) != null) {
                if (!isCurrent(session)) continue;
                var matcher = durationPattern.matcher(line);
                if (matcher.find() && isCurrent(session)) {
                    durationMs = Math.round((Long.parseLong(matcher.group(1)) * 3600
                        + Long.parseLong(matcher.group(2)) * 60 + Double.parseDouble(matcher.group(3))) * 1000);
                }
                if (recorded < 32768) { System.out.println("[兼容播放] " + line); recorded += line.length(); }
            }
        } catch (Exception error) {
            if (isCurrent(session)) System.err.println("[兼容播放] 诊断读取结束: " + error.getMessage());
        }
    }

    private void queueFrame(long session, byte[] pixels) {
        pendingFrame.set(new PendingFrame(session, pixels));
        scheduleFrame();
    }

    private void scheduleFrame() {
        if (!frameQueued.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            try {
                PendingFrame frame = pendingFrame.getAndSet(null);
                if (frame != null && isCurrent(frame.generation())) {
                    WritableImage image = new WritableImage(width, height);
                    image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(), frame.pixels(), 0, width * 4);
                    frameConsumer.accept(image);
                }
            } finally {
                frameQueued.set(false);
                if (pendingFrame.get() != null) scheduleFrame();
            }
        });
    }

    @Override public void close() {
        closed = true;
        cancelDecode();
        pendingFrame.set(null);
        worker.shutdownNow();
        watchdog.shutdownNow();
    }
}
