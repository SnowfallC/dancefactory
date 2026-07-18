package com.formationtool.desktop.controller;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.export.DesktopVideoExporter;
import com.formationtool.desktop.export.ExportProgressDialog;
import com.formationtool.desktop.model.ExportConfig;
import com.formationtool.desktop.view.ExportConfigDialog;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.view.TimelineView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ExportFlowController {
    @FunctionalInterface
    public interface ErrorHandler {
        void show(String title, String message);
    }

    private final FormationViewModel viewModel;
    private final AudioPlaybackController playbackController;
    private final StageView stageView;
    private final TimelineView timelineView;
    private final Supplier<File> currentAudioFileSupplier;
    private final Supplier<File> currentVideoFileSupplier;
    private final Consumer<String> statusUpdater;
    private final ErrorHandler errorHandler;

    private ExportProgressDialog exportProgressDialog;
    private DesktopVideoExporter currentExporter;

    public ExportFlowController(
        FormationViewModel viewModel,
        AudioPlaybackController playbackController,
        StageView stageView,
        TimelineView timelineView,
        Supplier<File> currentAudioFileSupplier,
        Supplier<File> currentVideoFileSupplier,
        Consumer<String> statusUpdater,
        ErrorHandler errorHandler
    ) {
        this.viewModel = viewModel;
        this.playbackController = playbackController;
        this.stageView = stageView;
        this.timelineView = timelineView;
        this.currentAudioFileSupplier = currentAudioFileSupplier;
        this.currentVideoFileSupplier = currentVideoFileSupplier;
        this.statusUpdater = statusUpdater;
        this.errorHandler = errorHandler;
    }

    public void showExportDialog(Stage stage) {
        if (viewModel.getCurrentGroup() == null ||
            viewModel.getCurrentGroup().getFormations().isEmpty()) {
            errorHandler.show("导出失败", "没有可导出的队形数据");
            return;
        }

        long duration = viewModel.getAudioDuration();
        MediaPlayer videoPlayer = (playbackController != null) ? playbackController.getVideoPlayer() : null;
        if (duration <= 0 && videoPlayer != null && videoPlayer.getMedia() != null) {
            duration = (long) videoPlayer.getMedia().getDuration().toMillis();
        }
        if (duration <= 0) {
            errorHandler.show("导出失败", "请先导入音频或视频");
            return;
        }
        final long finalDuration = duration;

        File currentVideoFile = currentVideoFileSupplier.get();
        boolean hasReferenceVideo =
            (currentVideoFile != null && currentVideoFile.exists());

        ExportConfigDialog configDialog =
            new ExportConfigDialog(stage, hasReferenceVideo, viewModel, stageView);
        ExportConfig exportConfig = configDialog.showAndWait();

        if (exportConfig != null) {
            if (exportConfig.needsReferenceVideo() && !hasReferenceVideo) {
                errorHandler.show("导出失败", "请先导入参考视频");
                return;
            }
            startVideoExportFlow(exportConfig, stage, finalDuration);
        }
    }

    private void startVideoExportFlow(ExportConfig exportConfig, Stage stage, long durationMs) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出视频");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("MP4 视频文件", "*.mp4"),
            new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        fileChooser.setInitialFileName("formation_export_" + timestamp + ".mp4");

        File outputFile = fileChooser.showSaveDialog(stage);
        if (outputFile == null) {
            return;
        }

        long inPointMs = timelineView.getExportRangeBar().getInPointMs();
        long outPointMs = timelineView.getExportRangeBar().getOutPointMs();

        System.out.println("[Export] 导出范围: " + inPointMs + "ms - " + outPointMs + "ms");
        System.out.println("[Export] 布局模式: " + exportConfig.getLayoutMode());
        System.out.println("[Export] 主视图: " + exportConfig.getPrimaryView());
        if (exportConfig.isSplitMode()) {
            System.out.println("[Export] 次视图: " + exportConfig.getSecondaryView());
        }
        System.out.println("[Export] 视角缩放: " + exportConfig.getViewScale());

        if (exportProgressDialog == null) {
            exportProgressDialog = new ExportProgressDialog();
        }
        exportProgressDialog.show(stage, () -> {
            if (currentExporter != null) {
                currentExporter.cancel();
            }
            statusUpdater.accept("导出已取消");
        });

        currentExporter = new DesktopVideoExporter(
            viewModel, stageView, currentAudioFileSupplier.get(), currentVideoFileSupplier.get()
        );

        currentExporter.setProgressListener((progress, message) -> {
            Platform.runLater(() -> exportProgressDialog.update(progress, message));
        });

        Thread exportThread = new Thread(() -> {
            try {
                currentExporter.exportWithConfig(exportConfig, outputFile, inPointMs, outPointMs);

                Platform.runLater(() -> {
                    if (exportProgressDialog != null) {
                        exportProgressDialog.close();
                    }

                    if (currentExporter != null && currentExporter.isCancelled()) {
                        return;
                    }

                    statusUpdater.accept("导出成功: " + outputFile.getName());

                    Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
                    successAlert.setTitle("导出成功");
                    successAlert.setHeaderText("视频已导出");
                    successAlert.setContentText("文件位置: " + outputFile.getAbsolutePath());

                    DialogPane sp = successAlert.getDialogPane();
                    sp.setStyle("-fx-background-color: #1E1E2E;");
                    sp.lookupAll(".header-panel").forEach(node -> node.setStyle("-fx-background-color: #1E1E2E;"));
                    sp.lookupAll(".label").forEach(node -> node.setStyle("-fx-text-fill: white;"));
                    sp.lookupAll(".content").forEach(node -> node.setStyle("-fx-text-fill: white;"));
                    sp.lookupAll(".button").forEach(node -> node.setStyle(
                        "-fx-background-color: #3A3A4A;" +
                        "-fx-text-fill: white;"
                    ));

                    successAlert.showAndWait();
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> {
                    if (exportProgressDialog != null) {
                        exportProgressDialog.close();
                    }
                    statusUpdater.accept("导出失败");
                    errorHandler.show("导出失败", "导出时出错: " + ex.getMessage());
                });
            }
        });
        exportThread.setDaemon(true);
        exportThread.start();
    }
}
