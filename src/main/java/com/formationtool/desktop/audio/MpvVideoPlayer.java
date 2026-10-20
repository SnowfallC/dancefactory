package com.formationtool.desktop.audio;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/** libmpv 管理音画时钟，JavaFX 仅显示画面并读取播放位置。 */
public final class MpvVideoPlayer implements AutoCloseable {
    public interface Api extends Library {
        Pointer mpv_create();
        int mpv_set_option_string(Pointer handle, String name, String value);
        int mpv_initialize(Pointer handle);
        int mpv_command_async(Pointer handle, long userdata, String[] command);
        int mpv_get_property(Pointer handle, String name, int format, Pointer value);
        Pointer mpv_get_property_string(Pointer handle, String name);
        void mpv_free(Pointer memory);
        int mpv_request_log_messages(Pointer handle, String level);
        Pointer mpv_wait_event(Pointer handle, double timeout);
        int mpv_render_context_create(PointerByReference result, Pointer handle, Pointer params);
        long mpv_render_context_update(Pointer render);
        int mpv_render_context_render(Pointer render, Pointer params);
        void mpv_render_context_free(Pointer render);
        void mpv_terminate_destroy(Pointer handle);
    }

    @Structure.FieldOrder({"type", "data"})
    public static class RenderParam extends Structure {
        public int type;
        public Pointer data;
    }

    private final ScheduledExecutorService commands = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "MpvCommands"); thread.setDaemon(true); return thread;
    });
    private final VideoFramePresenter presenter;
    private final Consumer<String> failure;
    private final Runnable ended;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicLong pendingSeek = new AtomicLong(-1);
    private volatile long requestedPositionMs = -1;
    private long seekDeadline;
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private volatile Pointer handle;
    private volatile Api api;
    private volatile Thread renderThread;
    private volatile long positionMs;
    private volatile long durationMs;
    private volatile boolean rendered;
    private volatile boolean rendering;
    private boolean loaded;
    private boolean readySent;
    private boolean playing;
    private Runnable ready;
    private long loadDeadline;

    public MpvVideoPlayer(Consumer<Image> frames, Consumer<String> failure, Runnable ended) {
        this.presenter = new VideoFramePresenter(frames);
        this.failure = failure;
        this.ended = ended;
    }

    /** 仅从应用目录加载内核，不借用用户机器上的播放器安装。 */
    public static Path libraryPath() {
        String override = System.getProperty("formation.mpv.library");
        if (override != null) return Path.of(override).toAbsolutePath();
        try {
            File location = new File(MpvVideoPlayer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File app = location.isDirectory() ? location : location.getParentFile();
            return app.toPath().resolve("native/mpv/libmpv-2.dll").toAbsolutePath();
        } catch (Exception error) { throw new IllegalStateException("无法定位播放组件", error); }
    }

    public void load(File source, Runnable onReady) {
        ready = onReady;
        commands.execute(() -> {
            try {
                Path library = libraryPath();
                System.out.println("[播放后端] libmpv 加载组件=" + library + " 文件=" + source.getAbsolutePath());
                api = Native.load(library.toString(), Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
                handle = api.mpv_create();
                if (handle == null) throw new IllegalStateException("无法创建 libmpv 播放器");
                option("config", "no"); option("terminal", "no"); option("vo", "libmpv");
                option("hwdec", "no"); option("pause", "yes"); option("keep-open", "yes");
                option("audio-client-name", "舞形工坊");
                check(api.mpv_initialize(handle), "初始化");
                Pointer version = api.mpv_get_property_string(handle, "mpv-version");
                if (version != null) {
                    try { System.out.println("[播放后端] 内核版本=" + version.getString(0, "UTF-8")); }
                    finally { api.mpv_free(version); }
                }
                api.mpv_request_log_messages(handle, "warn");
                rendering = true;
                CompletableFuture<Void> renderReady = new CompletableFuture<>();
                renderThread = new Thread(() -> renderLoop(renderReady), "MpvRender");
                renderThread.setDaemon(true); renderThread.start();
                renderReady.get(10, TimeUnit.SECONDS);
                command("loadfile", source.getAbsolutePath());
                loadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                commands.scheduleWithFixedDelay(this::poll, 0, 16, TimeUnit.MILLISECONDS);
            } catch (Throwable error) { fail("初始化失败: " + error); }
        });
    }

    private void option(String name, String value) { check(api.mpv_set_option_string(handle, name, value), name); }
    private static void check(int result, String action) {
        if (result < 0) throw new IllegalStateException(action + "失败，libmpv 错误码=" + result);
    }
    private void command(String... args) {
        String[] terminatedArgs = java.util.Arrays.copyOf(args, args.length + 1);
        check(api.mpv_command_async(handle, 0, terminatedArgs), args[0]);
    }

    private void poll() {
        if (closed.get() || failed.get()) return;
        try {
            for (int i = 0; i < 100; i++) {
                Pointer event = api.mpv_wait_event(handle, 0);
                int id = event.getInt(0);
                if (id == 0) break;
                if (id == 8) loaded = true;
                if (id == 2) {
                    Pointer data = event.getPointer(16);
                    if (data != null) {
                        String prefix = data.getPointer(0).getString(0, "UTF-8");
                        String message = data.getPointer(16).getString(0, "UTF-8");
                        System.err.println("[libmpv/" + prefix + "] " + message.strip());
                    }
                }
                if (id == 5 && event.getInt(4) < 0) throw new IllegalStateException("播放命令失败=" + event.getInt(4));
                if (id == 7) {
                    Pointer data = event.getPointer(16);
                    if (data != null && data.getInt(0) == 4) throw new IllegalStateException("媒体解码失败=" + data.getInt(4));
                }
            }
            dispatchSeek();
            durationMs = readMillis("duration", durationMs);
            long requested = requestedPositionMs;
            long actual = readMillis("time-pos", positionMs);
            if (requested < 0) positionMs = actual;
            else if (!readFlag("seeking") && Math.abs(actual - requested) <= 70 && pendingSeek.get() < 0) {
                if (requestedPositionMs == requested) requestedPositionMs = -1;
                positionMs = actual;
            } else if (seekDeadline > 0 && System.nanoTime() > seekDeadline) {
                throw new IllegalStateException("视频定位超时，目标=" + requested + "ms 实际=" + actual + "ms");
            }
            if (!readySent && loaded && rendered && durationMs > 0) {
                readySent = true;
                System.out.println("[播放后端] libmpv 就绪，时长=" + durationMs + "ms");
                notifyFx(ready);
            } else if (!readySent && System.nanoTime() > loadDeadline) {
                throw new IllegalStateException("视频首帧加载超时");
            }
            if (readySent && playing && requestedPositionMs < 0 && pendingSeek.get() < 0 && readFlag("eof-reached")) {
                playing = false;
                notifyFx(ended);
            }
        } catch (Throwable error) { fail("播放失败: " + error); }
    }

    private long readMillis(String name, long previous) {
        try (Memory value = new Memory(8)) {
            return api.mpv_get_property(handle, name, 5, value) >= 0
                ? Math.max(0, Math.round(value.getDouble(0) * 1000)) : previous;
        }
    }
    private boolean readFlag(String name) {
        try (Memory value = new Memory(4)) {
            return api.mpv_get_property(handle, name, 3, value) >= 0 && value.getInt(0) != 0;
        }
    }

    private void renderLoop(CompletableFuture<Void> started) {
        Pointer render = null;
        try (Memory apiName = string("sw"); Memory size = new Memory(8);
             Memory format = string("bgr0"); Memory stride = new Memory(Native.SIZE_T_SIZE);
             Memory storage = new Memory(1280L * 720 * 4 + 64)) {
            RenderParam[] init = params(new int[]{1, 0}, new Pointer[]{apiName, null});
            PointerByReference result = new PointerByReference();
            check(api.mpv_render_context_create(result, handle, init[0].getPointer()), "创建画面输出");
            render = result.getValue();
            int width = 1280, height = 720;
            size.setInt(0, width); size.setInt(4, height);
            if (Native.SIZE_T_SIZE == 8) stride.setLong(0, width * 4L); else stride.setInt(0, width * 4);
            long offset = (64 - (Pointer.nativeValue(storage) & 63)) & 63;
            Pointer pixels = storage.share(offset);
            RenderParam[] output = params(new int[]{17, 18, 19, 20, 0}, new Pointer[]{size, format, stride, pixels, null});
            started.complete(null);
            while (rendering && !closed.get() && !failed.get()) {
                if ((api.mpv_render_context_update(render) & 1) != 0) {
                    check(api.mpv_render_context_render(render, output[0].getPointer()), "输出画面");
                    byte[] frame = pixels.getByteArray(0, width * height * 4);
                    // bgr0 的第四字节未定义，交给 JavaFX 前明确设为不透明。
                    for (int i = 3; i < frame.length; i += 4) frame[i] = (byte) 255;
                    presenter.present(width, height, frame);
                    rendered = true;
                } else Thread.sleep(2);
            }
        } catch (Throwable error) {
            started.completeExceptionally(error);
            if (!closed.get()) fail("画面输出失败: " + error);
        } finally { if (render != null) api.mpv_render_context_free(render); }
    }

    private static Memory string(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Memory result = new Memory(bytes.length + 1L); result.write(0, bytes, 0, bytes.length); result.setByte(bytes.length, (byte) 0); return result;
    }
    private static RenderParam[] params(int[] types, Pointer[] values) {
        RenderParam[] params = (RenderParam[]) new RenderParam().toArray(types.length);
        for (int i = 0; i < types.length; i++) { params[i].type = types[i]; params[i].data = values[i]; params[i].write(); }
        return params;
    }
    private void notifyFx(Runnable callback) {
        if (callback != null) Platform.runLater(() -> { if (!closed.get() && !failed.get()) callback.run(); });
    }
    private void fail(String message) {
        if (closed.get() || !failed.compareAndSet(false, true)) return;
        System.err.println("[播放后端] libmpv " + message);
        Platform.runLater(() -> { if (!closed.get()) failure.accept(message); });
    }

    public void seek(long timeMs) {
        if (closed.get() || failed.get()) return;
        long target = Math.max(0, durationMs > 0 ? Math.min(timeMs, Math.max(0, durationMs - 1)) : timeMs);
        requestedPositionMs = target;
        positionMs = target;
        pendingSeek.set(target);
    }
    private void dispatchSeek() {
        long target = pendingSeek.getAndSet(-1);
        if (target < 0) return;
        seekDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        System.out.println("[播放后端] libmpv 精确定位=" + target + "ms");
        command("seek", String.format(java.util.Locale.ROOT, "%.3f", target / 1000.0), "absolute+exact");
    }
    public void play() { enqueue(() -> { dispatchSeek(); playing = true; command("set", "pause", "no"); }); }
    public void pause() { enqueue(() -> { playing = false; command("set", "pause", "yes"); }); }
    private void enqueue(Runnable action) {
        if (closed.get()) return;
        try { commands.execute(() -> { if (!closed.get() && !failed.get()) { try { action.run(); } catch (Throwable error) { fail(error.toString()); } } }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* 关闭时不再派发控制命令。 */ }
    }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public CompletableFuture<Void> termination() { return terminated; }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        presenter.close();
        commands.execute(() -> {
            try {
                if (handle != null) {
                    command("stop"); rendering = false;
                    if (renderThread != null) {
                        renderThread.join(10000);
                        if (renderThread.isAlive()) throw new IllegalStateException("画面线程未停止，保留内核避免释放后访问");
                    }
                    api.mpv_terminate_destroy(handle); handle = null;
                }
                terminated.complete(null);
            } catch (Throwable error) {
                System.err.println("[播放后端] libmpv 释放失败: " + error);
                terminated.completeExceptionally(error);
            } finally { commands.shutdownNow(); }
        });
    }
}
