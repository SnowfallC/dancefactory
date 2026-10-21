package com.formationtool.desktop.view;

import com.formationtool.desktop.export.DesktopVideoExporter;
import com.formationtool.desktop.model.*;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import java.io.File;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** 使用实际渲染和 FFmpeg 验证 30 人、大舞台、姓名、镜像和导出配置。 */
@EnabledIfSystemProperty(named = "uiRegression", matches = "true")
class Export117IntegrationTest {
    @Test void shouldRenderThirtyDancersAndExportMirroredStage() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        CompletableFuture<DesktopVideoExporter> ready = new CompletableFuture<>();
        File directory = new File("target/qa-1.1.7");
        directory.mkdirs();
        Platform.runLater(() -> {
            try {
                FormationViewModel model = new FormationViewModel();
                model.createNewGroup("30人导出回归", 30, 32, 20);
                var dancers = model.getCurrentGroup().getFormations().get(0).getDancers();
                for (int index = 0; index < dancers.size(); index++) {
                    dancers.get(index).setX((index % 10 - 4.5f) * 2.5f);
                    dancers.get(index).setY((index / 10 - 1) * 3f);
                    dancers.get(index).setName("成员" + (index + 1));
                }
                StageView view = new StageView(model);
                new Scene(new StackPane(view), 960, 540);
                view.setWidth(960); view.setHeight(540);
                view.updateStageSize(32, 20);
                view.setShowDancerNames(true);
                view.render();
                ImageIO.write(SwingFXUtils.fromFXImage(view.snapshot(null, null), null), "png", new File(directory, "stage-30-normal.png"));
                view.setMirrored(true);
                ImageIO.write(SwingFXUtils.fromFXImage(view.snapshot(null, null), null), "png", new File(directory, "stage-30-mirrored.png"));
                view.setMirrored(false);
                ready.complete(new DesktopVideoExporter(model, view, null, null));
            } catch (Throwable error) { ready.completeExceptionally(error); }
        });
        DesktopVideoExporter exporter = ready.get(20, TimeUnit.SECONDS);
        ExportConfig config = new ExportConfig();
        config.setMirrored(true);
        config.setDancerScale(1.25);
        File output = new File(directory, "stage-30-export.mp4");
        exporter.exportWithConfig(config, output, 0, 1000);
        assertTrue(output.isFile());
        assertTrue(output.length() > 1000);
    }
}
