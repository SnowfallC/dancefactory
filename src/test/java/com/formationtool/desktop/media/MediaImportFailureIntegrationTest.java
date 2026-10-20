package com.formationtool.desktop.media;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.controller.MediaImportController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.view.DiagnosticDialogs;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "uiRegression", matches = "true")
class MediaImportFailureIntegrationTest {
    @Test void shouldOfferLogFolderAndNeverReportFailedLoadAsSuccessful() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        Path invalid = Path.of("target", "invalid-video-regression.mp4").toAbsolutePath();
        Files.writeString(invalid, "这是损坏的视频样本");
        CompletableFuture<Void> failed = new CompletableFuture<>();
        CompletableFuture<Void> verified = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<AudioPlaybackController> playback = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("视频转换后仍无法播放");
                alert.setContentText("ERROR_MEDIA_INVALID");
                DiagnosticDialogs.attachLogButton(alert);
                var buttonType = alert.getButtonTypes().stream().filter(b -> b.getText().equals("打开日志文件夹")).findFirst().orElseThrow();
                assertInstanceOf(Button.class, alert.getDialogPane().lookupButton(buttonType));
                assertTrue(alert.getButtonTypes().size() >= 2);
                alert.getDialogPane().applyCss();
                alert.getDialogPane().autosize();
                alert.getDialogPane().layout();
                javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(
                    alert.getDialogPane().snapshot(null, null), null), "png", new java.io.File("target/error-log-dialog-preview.png"));
                FormationViewModel model = new FormationViewModel();
                model.createNewGroup("失败回归", 0, 16, 12);
                playback.set(new AudioPlaybackController(model));
                playback.get().bindPreviewImageView(new javafx.scene.image.ImageView());
                Label status = new Label();
                MediaImportController importer = new MediaImportController(model, new DataManager(), playback.get(),
                    status, 1000, (title, message) -> failed.complete(null),
                    file -> null, file -> null,
                    file -> {}, () -> {}, file -> {}, file -> {}, Long::toString);
                importer.loadImportedVideo(invalid.toFile(), () -> completed.set(true));
            } catch (Throwable error) { failed.completeExceptionally(error); }
        });
        try {
            failed.get(25, TimeUnit.SECONDS);
            Platform.runLater(() -> {
                try { assertFalse(completed.get()); verified.complete(null); }
                catch (Throwable error) { verified.completeExceptionally(error); }
            });
            verified.get(5, TimeUnit.SECONDS);
        } finally {
            Platform.runLater(() -> { if (playback.get() != null) playback.get().dispose(); Platform.exit(); });
        }
    }
}
