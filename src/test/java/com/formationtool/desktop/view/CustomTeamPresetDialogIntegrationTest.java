package com.formationtool.desktop.view;

import com.formationtool.desktop.model.CustomTeamPreset;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.*;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "uiRegression", matches = "true")
class CustomTeamPresetDialogIntegrationTest {
    @Test void shouldValidateTypedCountsAndSaveFourIndependentMembers() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Dialog<CustomTeamPreset> dialog = CustomTeamPresetDialog.create(null);
                var pane = dialog.getDialogPane();
                pane.autosize();
                pane.applyCss(); pane.layout();
                ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                    new File("target/custom-preset-nine-preview.png"));
                ((TextField) pane.lookup("#preset-name")).setText("我的四人团");
                Spinner<?> count = (Spinner<?>) pane.lookup("#preset-count");
                count.getEditor().setText("4");
                ((Button) pane.lookup("#generate-members")).fire();
                ButtonType save = pane.getButtonTypes().get(0);
                count.getEditor().setText("错误人数");
                ((Button) pane.lookupButton(save)).fire();
                assertNull(dialog.getResult());
                count.getEditor().setText("4");
                ((Button) pane.lookup("#generate-members")).fire();
                pane.applyCss(); pane.autosize(); pane.layout();
                ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                    new File("target/custom-preset-preview.png"));
                // 检查真实弹出的颜色面板，避免只验证内部容器而遗漏白色底板。
                dialog.show();
                ColorPicker picker = (ColorPicker) pane.lookup(".color-picker");
                picker.show();
                for (var window : javafx.stage.Window.getWindows()) {
                    if (window instanceof javafx.stage.PopupWindow && window.isShowing()) {
                        var popupRoot = window.getScene().getRoot();
                        popupRoot.applyCss(); popupRoot.layout();
                        ImageIO.write(SwingFXUtils.fromFXImage(popupRoot.snapshot(null, null), null), "png",
                            new File("target/preset-color-popup-preview.png"));
                    }
                }
                picker.hide();
                ((Button) pane.lookupButton(save)).fire();
                assertNotNull(dialog.getResult());
                assertEquals("我的四人团", dialog.getResult().name());
                assertEquals(4, dialog.getResult().members().size());
                assertTrue(dialog.getResult().members().get(0).color().matches("#[0-9A-F]{6}"));
                Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
                confirmation.setHeaderText("应用团队预设");
                confirmation.setContentText("将替换工程内的舞者，可通过撤销恢复。是否继续？");
                PresetDialogTheme.apply(confirmation);
                var confirmationPane = confirmation.getDialogPane();
                confirmationPane.applyCss(); confirmationPane.autosize(); confirmationPane.layout();
                ImageIO.write(SwingFXUtils.fromFXImage(confirmationPane.snapshot(null, null), null), "png",
                    new File("target/preset-confirm-preview.png"));
                finished.complete(null);
            } catch (Throwable error) { finished.completeExceptionally(error); }
        });
        try { finished.get(20, TimeUnit.SECONDS); }
        finally { Platform.exit(); }
    }
}
