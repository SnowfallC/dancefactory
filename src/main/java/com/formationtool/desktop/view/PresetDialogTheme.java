package com.formationtool.desktop.view;

import javafx.scene.control.Dialog;
import javafx.scene.paint.Color;

/** 为预设相关弹窗统一加载主题，包括最外层场景底板。 */
public final class PresetDialogTheme {
    private PresetDialogTheme() {}

    public static void apply(Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        String stylesheet = PresetDialogTheme.class.getResource("/style.css").toExternalForm();
        if (!pane.getStyleClass().contains("preset-dialog")) pane.getStyleClass().add("preset-dialog");
        if (!pane.getStylesheets().contains(stylesheet)) pane.getStylesheets().add(stylesheet);
        pane.setStyle("-fx-background-color: #141D2C;");
        if (pane.getScene() != null) pane.getScene().setFill(Color.web("#141D2C"));
        pane.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) scene.setFill(Color.web("#141D2C"));
        });
    }
}
