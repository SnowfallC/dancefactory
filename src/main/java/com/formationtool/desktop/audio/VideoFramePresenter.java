package com.formationtool.desktop.audio;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/** 合并原生解码帧，只在 JavaFX 线程更新画面，防止拖动时积压旧帧。 */
final class VideoFramePresenter {
    private record Frame(int width, int height, byte[] pixels) { }
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Consumer<Image> consumer;
    private WritableImage image;
    private volatile boolean closed;

    VideoFramePresenter(Consumer<Image> consumer) { this.consumer = consumer; }

    void present(int width, int height, byte[] pixels) {
        if (closed) return;
        latest.set(new Frame(width, height, pixels));
        schedule();
    }

    private void schedule() {
        if (closed || !queued.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            try {
                Frame frame = latest.getAndSet(null);
                if (closed || frame == null) return;
                if (image == null || image.getWidth() != frame.width || image.getHeight() != frame.height) {
                    image = new WritableImage(frame.width, frame.height);
                }
                image.getPixelWriter().setPixels(0, 0, frame.width, frame.height,
                    PixelFormat.getByteBgraPreInstance(), frame.pixels, 0, frame.width * 4);
                consumer.accept(image);
            } finally {
                queued.set(false);
                if (latest.get() != null) schedule();
            }
        });
    }

    void close() { closed = true; latest.set(null); }
}
