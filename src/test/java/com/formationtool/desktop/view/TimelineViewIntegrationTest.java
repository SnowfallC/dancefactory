package com.formationtool.desktop.view;

import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "uiRegression", matches = "true")
class TimelineViewIntegrationTest {
    @Test void shouldSelectClipsResizeHandlesAndOfferDeletionInBothModes() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            try {
                FormationViewModel model = new FormationViewModel();
                model.createNewGroup("测试", 1, 16f, 12f);
                var formations = model.getCurrentGroup().getFormations();
                formations.get(0).setHoldDuration(16000);
                Formation second = new Formation("second", "队形2", 24000);
                second.setDuration(8000); second.setHoldDuration(14000);
                formations.add(second);
                Formation third = new Formation("third", "队形3", 45000);
                third.setDuration(7000); third.setHoldDuration(15000);
                formations.add(third);
                model.setAudioDuration(60000);
                model.setStandardMode(true);
                TimelineView view = new TimelineView(model);
                Scene scene = new Scene(view, 900, 200);
                scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
                stage.setScene(scene); stage.show();
                view.applyCss(); view.layout();
                var field = TimelineView.class.getDeclaredField("waveformCanvas");
                field.setAccessible(true);
                Canvas canvas = (Canvas) field.get(view);
                double scale = canvas.getWidth() / 60000.0;
                canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, 30000 * scale, MouseButton.PRIMARY));
                canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, 30000 * scale, MouseButton.PRIMARY));
                assertEquals(1, model.getCurrentFormationIndex());
                assertEquals(24000, model.getCurrentTime());
                canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, 38000 * scale, MouseButton.PRIMARY));
                canvas.getOnMouseDragged().handle(mouse(MouseEvent.MOUSE_DRAGGED, 36000 * scale, MouseButton.PRIMARY));
                canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, 36000 * scale, MouseButton.PRIMARY));
                assertEquals(12000, second.getHoldDuration(), 1);
                model.setCurrentTime(50000);
                canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, 30000 * scale, MouseButton.PRIMARY));
                canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, 30000 * scale, MouseButton.PRIMARY));
                assertEquals(36000, model.getCurrentTime(), 1);
                model.setCurrentTime(30000);
                canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, 32000 * scale, MouseButton.PRIMARY));
                canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, 32000 * scale, MouseButton.PRIMARY));
                assertEquals(30000, model.getCurrentTime());
                float[] wave = new float[900];
                for (int i = 0; i < wave.length; i++) wave[i] = (float) (Math.sin(i * .3) * .6);
                view.setWaveformData(wave);
                ImageIO.write(SwingFXUtils.fromFXImage(canvas.snapshot(null, null), null), "png", new File("target/desktop-timeline-preview.png"));
                for (boolean standard : new boolean[]{true, false}) {
                    model.setStandardMode(standard);
                    canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, 30000 * scale, MouseButton.SECONDARY));
                    var popup = javafx.stage.Window.getWindows().stream()
                        .filter(w -> w instanceof javafx.scene.control.ContextMenu && w.isShowing())
                        .map(w -> (javafx.scene.control.ContextMenu) w).findFirst().orElseThrow();
                    assertFalse(popup.getItems().stream().anyMatch(i -> i.getText().equals("调整变到此队形的用时")));
                    var delete = popup.getItems().stream().filter(i -> i.getText().equals("删除本队形")).findFirst().orElseThrow();
                    delete.fire(); popup.hide();
                    assertEquals(2, model.getCurrentGroup().getFormations().size());
                    assertTrue(model.undo());
                    assertEquals(3, model.getCurrentGroup().getFormations().size());
                }
                finished.complete(null);
            } catch (Throwable error) { finished.completeExceptionally(error); }
            finally { stage.close(); }
        });
        try { finished.get(20, TimeUnit.SECONDS); }
        finally { Platform.exit(); }
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, MouseButton button) {
        return new MouseEvent(type, x, 50, x, 50, button, 1,
            false, false, false, false, button == MouseButton.PRIMARY, false,
            button == MouseButton.SECONDARY, false, false, true, null);
    }
}
