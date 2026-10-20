package com.formationtool.desktop.media;

import com.formationtool.desktop.audio.MpvVideoPlayer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** 配合 WASAPI 回环记录，测量到达 JavaFX 图层的同步闪光时间。 */
@EnabledIfSystemProperty(named = "avRegression", matches = ".+")
class MpvAvIntegrationTest {
    @Test void shouldPlayWithStableClockFor55Seconds() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<ImageView> view = new AtomicReference<>();
        List<String> flashes = new ArrayList<>();
        AtomicBoolean white = new AtomicBoolean();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        AtomicReference<String> failure = new AtomicReference<>();
        MpvVideoIntegrationTest.onFx(() -> {
            view.set(new ImageView()); view.get().setFitWidth(960); view.get().setFitHeight(540);
            stage.set(new Stage()); stage.get().setScene(new Scene(new StackPane(view.get()), 960, 540)); stage.get().show();
        });
        MpvVideoPlayer player = new MpvVideoPlayer(image -> {
            view.get().setImage(image);
            boolean bright = (image.getPixelReader().getArgb(40, 40) & 255) > 128;
            if (bright && !white.getAndSet(true)) flashes.add(System.currentTimeMillis() + "," + MpvVideoIntegrationTest.frameNumber(image));
            if (!bright) white.set(false);
        }, message -> { failure.set(message); ready.completeExceptionally(new IllegalStateException(message)); }, () -> {});
        try {
            player.load(new File(System.getProperty("avRegression")), () -> ready.complete(null));
            ready.get(20, TimeUnit.SECONDS);
            MpvVideoIntegrationTest.onFx(() -> { flashes.clear(); white.set(false); });
            long start = System.currentTimeMillis(); player.play(); Thread.sleep(55000); player.pause();
            long end = System.currentTimeMillis();
            assertNull(failure.get()); assertTrue(player.getPositionMs() > 54500);
            MpvVideoIntegrationTest.onFx(() -> {
                try {
                    Files.write(Path.of("target/player-comparison/javafx-av-flashes.csv"), flashes);
                    Files.writeString(Path.of("target/player-comparison/javafx-av-phase.txt"), start + "," + end);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
        } finally {
            player.close(); player.termination().get(15, TimeUnit.SECONDS);
            MpvVideoIntegrationTest.onFx(() -> stage.get().close()); Platform.exit();
        }
    }
}
