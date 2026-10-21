package com.formationtool.desktop.view;

import com.formationtool.desktop.viewmodel.FormationViewModel;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** 通过实际画布像素验证缩放舞台不会改变角色和文字的显示大小。 */
@EnabledIfSystemProperty(named = "uiRegression", matches = "true")
class StageMarkerSizeIntegrationTest {
    @Test void shouldKeepMarkerSizeAcrossZoomStageAndWindowSizes() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                FormationViewModel model = new FormationViewModel();
                model.createNewGroup("固定角色尺寸回归", 1, 16, 12);
                var dancer = model.getCurrentGroup().getFormations().get(0).getDancers().get(0);
                dancer.setX(0); dancer.setY(0); dancer.setColor(0xffff0000);
                StageView view = new StageView(model, 960, 540);
                File directory = new File("target/qa-1.1.7-fixed-marker");
                assertTrue(directory.isDirectory() || directory.mkdirs());
                WritableImage baseline = capture(view, directory, "normal");
                int normalDiameter = redDiameter(baseline);
                assertEquals(30, normalDiameter, 1);
                for (double zoom : new double[]{0.5, 2.0}) {
                    view.setViewScale(zoom);
                    WritableImage actual = capture(view, directory, "zoom-" + zoom);
                    assertEquals(normalDiameter, redDiameter(actual));
                    assertCenterPixelsEqual(baseline, actual);
                }
                view.updateStageSize(32, 24);
                assertCenterPixelsEqual(baseline, capture(view, directory, "large-stage"));
                view.setWidth(1280); view.setHeight(720);
                assertCenterPixelsEqual(baseline, capture(view, directory, "large-window"));
                view.setDancerScale(1.5);
                assertEquals(45, redDiameter(capture(view, directory, "export-size-1.5")), 1);
                completed.complete(null);
            } catch (Throwable error) { completed.completeExceptionally(error); }
        });
        completed.get(30, TimeUnit.SECONDS);
    }

    private static WritableImage capture(StageView view, File directory, String name) throws Exception {
        view.render();
        WritableImage image = view.snapshot(null, null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(directory, name + ".png"));
        return image;
    }

    private static int redDiameter(WritableImage image) {
        int minX = (int) image.getWidth(), maxX = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                var color = image.getPixelReader().getColor(x, y);
                if (color.getRed() > 0.8 && color.getGreen() < 0.2 && color.getBlue() < 0.2) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                }
            }
        }
        assertTrue(maxX >= minX, "必须找到红色角色");
        return maxX - minX + 1;
    }

    private static void assertCenterPixelsEqual(WritableImage expected, WritableImage actual) {
        for (int y = -13; y <= 13; y++) {
            for (int x = -13; x <= 13; x++) {
                // 圆内像素同时包含角色填色和编号，可检测文字是否也随缩放变化。
                if (x * x + y * y < 180) {
                    assertEquals(expected.getPixelReader().getArgb((int) expected.getWidth() / 2 + x,
                            (int) expected.getHeight() / 2 + y),
                            actual.getPixelReader().getArgb((int) actual.getWidth() / 2 + x,
                            (int) actual.getHeight() / 2 + y), "角色中心像素应保持一致");
                }
            }
        }
    }
}
