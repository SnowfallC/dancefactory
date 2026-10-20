package com.formationtool.desktop.media;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.controller.MediaImportController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.view.TimelineView;
import com.formationtool.desktop.view.ControlPanelFactory;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import com.formationtool.desktop.export.DesktopVideoExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 指定本地媒体后执行真实转码、重复载入与原生播放回归。 */
@EnabledIfSystemProperty(named = "mediaRegression", matches = ".+")
class VideoReimportIntegrationTest {
    private static void onFx(Runnable action) throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { action.run(); finished.complete(null); }
            catch (Throwable error) { finished.completeExceptionally(error); }
        });
        finished.get(20, TimeUnit.SECONDS);
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y) {
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
            false, false, false, false, true, false, false, false, false, false, null);
    }

    @Test
    void shouldConvertAndPlayTheSameVideoThreeTimes() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        FormationViewModel viewModel = new FormationViewModel();
        File source = new File(System.getProperty("mediaRegression"));
        assertTrue(source.isFile());
        File converted = new MediaProcessingService(viewModel, null).convertVideoToMP4(source);
        assertNotNull(converted, "附件转码必须成功");
        Path result = Path.of("target", "compatibility-regression.mp4").toAbsolutePath();
        Files.copy(converted.toPath(), result, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[视频回归] 转码样本: " + result);
        AtomicReference<MediaImportController> importer = new AtomicReference<>();
        AtomicReference<AudioPlaybackController> playback = new AtomicReference<>();
        AtomicReference<String> loadError = new AtomicReference<>();
        onFx(() -> {
            viewModel.createNewGroup("视频回归", 3, 16, 12);
            playback.set(new AudioPlaybackController(viewModel));
            playback.get().bindPreviewImageView(new javafx.scene.image.ImageView());
            importer.set(new MediaImportController(viewModel, new DataManager(), playback.get(),
                new Label(), 15000,
                (title, message) -> loadError.set(title + ": " + message),
                file -> null, file -> null,
                file -> {}, () -> {}, file -> {}, file -> {}, time -> Long.toString(time)));
        });
        try {
            for (int i = 0; i < 3; i++) {
                CompletableFuture<Void> released = new CompletableFuture<>();
                onFx(() -> {
                    playback.get().disposeVideo(() -> released.complete(null));
                });
                released.get(15, TimeUnit.SECONDS);
                CompletableFuture<Void> loaded = new CompletableFuture<>();
                onFx(() -> importer.get().loadImportedVideo(result.toFile(), () -> loaded.complete(null)));
                loaded.get(20, TimeUnit.SECONDS);
                assertNull(loadError.get());
                onFx(() -> { assertTrue(playback.get().isNativeVideo()); playback.get().seekTo(0); viewModel.setPlaying(true); });
                MpvVideoIntegrationTest.waitFor(() -> viewModel.getCurrentTime() > 300, 15000);
                onFx(() -> viewModel.setPlaying(false));
                System.out.println("[视频回归] 第 " + (i + 1) + " 次载入并播放通过");
                if (i == 0) {
                    AtomicReference<StageView> exportStage = new AtomicReference<>();
                    onFx(() -> exportStage.set(new StageView(viewModel, 640, 360)));
                    File exported = new File("target/reimport-export-regression.mp4");
                    new DesktopVideoExporter(viewModel, exportStage.get(), null, result.toFile())
                        .export(true, exported, 0, 1000);
                    assertTrue(exported.length() > 0, "首次载入后必须能够导出");
                    System.out.println("[视频回归] 首次导出通过，继续重新导入");
                }
            }
            onFx(() -> {
                viewModel.setAudioDuration(20000);
                viewModel.setStandardMode(true);
                viewModel.insertKeyframeSmart(6000, null);
                StageView stageView = new StageView(viewModel, 800, 530);
                TimelineView timeline = new TimelineView(viewModel);
                var panel = ControlPanelFactory.create(viewModel, stageView, text -> {},
                    () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {},
                    () -> {}, () -> {}, () -> {}, () -> {});
                BorderPane root = new BorderPane(stageView);
                root.setRight(panel.getPanel());
                root.setBottom(timeline);
                Scene scene = new Scene(root, 1120, 760);
                scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
                panel.getDancerListView().getItems().setAll("成员 1", "成员 2", "成员 3");
                panel.getFormationListView().getItems().setAll("队形 1", "队形 2");
                root.applyCss(); root.layout(); stageView.render();
                // 验证标准模式在区间内拖动只修改当前区间，且支持撤销。
                int count = viewModel.getCurrentGroup().getFormations().size();
                var first = viewModel.getCurrentGroup().getFormations().get(0).getDancers().get(0);
                float originalX = first.getX();
                double scale = Math.min(stageView.getWidth() * 0.9 / 22.4, stageView.getHeight() * 0.9 / 12);
                double startX = stageView.getWidth() / 2 + first.getX() * scale;
                double startY = stageView.getHeight() / 2 - first.getY() * scale;
                stageView.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, startX, startY));
                stageView.getOnMouseDragged().handle(mouse(MouseEvent.MOUSE_DRAGGED, stageView.getWidth() / 2, stageView.getHeight() / 2));
                stageView.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, stageView.getWidth() / 2, stageView.getHeight() / 2));
                assertEquals(count, viewModel.getCurrentGroup().getFormations().size());
                assertEquals(0f, first.getX());
                assertEquals(originalX, viewModel.getCurrentGroup().getFormations().get(1).getDancers().get(0).getX());
                assertTrue(viewModel.undo());
                assertEquals(originalX, viewModel.getCurrentGroup().getFormations().get(0).getDancers().get(0).getX());
                root.applyCss(); root.layout(); stageView.render();
                try {
                    ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null, null), null), "png",
                        new File("target/standard-mode-preview.png"));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            onFx(() -> playback.get().dispose());
            Platform.exit();
        }
    }
}
