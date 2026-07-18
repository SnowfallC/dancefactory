package com.formationtool.desktop.export;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class ExportProgressDialog {
    private Stage progressStage;
    private ProgressBar exportProgressBar;
    private Label exportProgressLabel;
    private Runnable onCancelConfirmed;

    public void show(Stage owner, Runnable onCancelConfirmed) {
        this.onCancelConfirmed = onCancelConfirmed;
        createIfNeeded(owner);
        exportProgressBar.setProgress(0);
        exportProgressLabel.setText("正在初始化...");
        progressStage.show();
    }

    public void update(double progress, String message) {
        if (exportProgressBar != null) {
            exportProgressBar.setProgress(progress);
        }
        if (exportProgressLabel != null) {
            exportProgressLabel.setText(message);
        }
    }

    public void close() {
        if (progressStage != null) {
            progressStage.close();
            progressStage = null;
            exportProgressBar = null;
            exportProgressLabel = null;
        }
    }

    private void createIfNeeded(Stage owner) {
        if (progressStage != null) {
            return;
        }

        progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.initOwner(owner);
        progressStage.setTitle("导出进度");
        progressStage.setResizable(false);

        VBox root = new VBox(20);
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #1E1E2E;");

        Label titleLabel = new Label("正在导出视频...");
        titleLabel.setTextFill(Color.WHITE);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        exportProgressBar = new ProgressBar(0);
        exportProgressBar.setPrefWidth(350);
        exportProgressBar.setPrefHeight(20);
        exportProgressBar.setStyle("-fx-accent: #1976D2;");

        exportProgressLabel = new Label("正在初始化...");
        exportProgressLabel.setTextFill(Color.LIGHTGRAY);

        Button cancelBtn = new Button("取消导出");
        cancelBtn.setStyle(
            "-fx-background-color: #3A3A4A;" +
            "-fx-text-fill: white;" +
            "-fx-padding: 8 20 8 20;" +
            "-fx-background-radius: 4;"
        );

        cancelBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.NONE);
            confirm.setTitle("确认取消");
            confirm.setHeaderText("取消导出");
            confirm.setContentText("确定要停止当前的导出任务吗？已生成的部分文件将被删除。");

            confirm.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

            DialogPane dp = confirm.getDialogPane();
            dp.setStyle(
                "-fx-background-color: #1E1E2E;" +
                "-fx-text-fill: white;"
            );
            dp.lookupAll(".label").forEach(node -> node.setStyle("-fx-text-fill: white;"));
            dp.lookupAll(".header-panel").forEach(node -> node.setStyle("-fx-background-color: #1E1E2E;"));
            dp.lookupAll(".content").forEach(node -> node.setStyle("-fx-text-fill: white;"));
            dp.lookupAll(".button").forEach(node -> node.setStyle(
                "-fx-background-color: #3A3A4A;" +
                "-fx-text-fill: white;"
            ));

            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.OK) {
                    if (this.onCancelConfirmed != null) {
                        this.onCancelConfirmed.run();
                    }
                    close();
                }
            });
        });

        root.getChildren().addAll(titleLabel, exportProgressBar, exportProgressLabel, cancelBtn);

        Scene scene = new Scene(root, 450, 220);
        progressStage.setScene(scene);
        progressStage.setOnCloseRequest(e -> e.consume());
    }
}
