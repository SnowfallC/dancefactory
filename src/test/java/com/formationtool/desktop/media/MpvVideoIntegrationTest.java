package com.formationtool.desktop.media;

import com.formationtool.desktop.audio.MpvVideoPlayer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** 在实际 JavaFX 图层验证原生播放、暂停精确定位、快速拖动和生命周期。 */
@EnabledIfSystemProperty(named = "mpvRegression", matches = ".+")
class MpvVideoIntegrationTest {
    static void onFx(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> { try { action.run(); done.complete(null); } catch (Throwable error) { done.completeExceptionally(error); } });
        done.get(15, TimeUnit.SECONDS);
    }
    static void waitFor(BooleanSupplier test, long timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!test.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(test.getAsBoolean(), "等待播放条件超时");
    }
    static int frameNumber(Image image) {
        int frame = 0;
        for (int bit = 0; bit < 16; bit++) {
            int color = image.getPixelReader().getArgb(bit * 64 + 32, 660);
            if ((color & 255) > 128) frame |= 1 << bit;
        }
        return frame;
    }

    @Test void shouldSeekRenderPlayAndRelease() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        AtomicInteger frame = new AtomicInteger(-1), updates = new AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>();
        CompletableFuture<Void> ready = new CompletableFuture<>(), ended = new CompletableFuture<>();
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<ImageView> imageView = new AtomicReference<>();
        onFx(() -> {
            ImageView view = new ImageView(); view.setFitWidth(960); view.setFitHeight(540); view.setPreserveRatio(true);
            imageView.set(view); Stage window = new Stage(); window.setTitle("舞形工坊播放内核验证");
            window.setScene(new Scene(new StackPane(view), 960, 540)); window.show(); stage.set(window);
        });
        MpvVideoPlayer player = new MpvVideoPlayer(image -> {
            imageView.get().setImage(image); frame.set(frameNumber(image)); updates.incrementAndGet();
        }, message -> { error.set(message); ready.completeExceptionally(new IllegalStateException(message)); }, () -> ended.complete(null));
        try {
            player.load(new File(System.getProperty("mpvRegression")), () -> ready.complete(null));
            ready.get(20, TimeUnit.SECONDS);
            assertTrue(player.getDurationMs() >= 59900);
            List<String> results = new ArrayList<>();
            for (double target : new double[]{17.3, 2.1, 43.7, 8.4, 55.2, 12.8, 31.1, 4.7, 48.3, 22.5}) {
                int before = updates.get(); long start = System.nanoTime(); player.seek(Math.round(target * 1000));
                waitFor(() -> updates.get() > before && Math.abs(frame.get() / 30.0 - target) < .04, 3000);
                results.add(String.format(java.util.Locale.ROOT, "%.1fs: %.1fms frame=%d", target, (System.nanoTime() - start) / 1e6, frame.get()));
            }
            for (int i = 0; i < 60; i++) { player.seek((i * 997L) % 55000); Thread.sleep(10); }
            player.seek(11700); waitFor(() -> frame.get() == 351, 3000);
            player.play(); waitFor(() -> player.getPositionMs() > 12500 && frame.get() > 370, 4000);
            player.pause(); Thread.sleep(250); int pausedFrame = frame.get(); Thread.sleep(250); assertEquals(pausedFrame, frame.get());
            onFx(() -> {
                try { javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(imageView.get().getImage(), null), "png", new File("target/player-comparison/javafx-mpv-frame.png")); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            player.seek(59200); player.play(); ended.get(5, TimeUnit.SECONDS);
            player.seek(0); player.play(); waitFor(() -> player.getPositionMs() > 300 && player.getPositionMs() < 1500, 4000);
            player.pause();
            assertNull(error.get());
            String resultName = new File(System.getProperty("mpvRegression")).getName().contains("1080") ? "javafx-1080-seek-results.txt" : "javafx-seek-results.txt";
            Files.write(Path.of("target/player-comparison", resultName), results);
        } finally { player.close(); player.termination().get(15, TimeUnit.SECONDS); }
        // 关闭后重新创建，检验原生资源释放及新会话的首帧。
        for (int i = 0; i < 3; i++) {
            CompletableFuture<Void> loaded = new CompletableFuture<>();
            MpvVideoPlayer next = new MpvVideoPlayer(image -> {}, message -> loaded.completeExceptionally(new IllegalStateException(message)), () -> {});
            next.load(new File(System.getProperty("mpvRegression")), () -> loaded.complete(null));
            loaded.get(20, TimeUnit.SECONDS); next.close(); next.termination().get(15, TimeUnit.SECONDS);
        }
        onFx(() -> stage.get().close());
        Platform.exit();
    }
}
