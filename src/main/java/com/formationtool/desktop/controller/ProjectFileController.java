package com.formationtool.desktop.controller;

import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ProjectFileController {
    @FunctionalInterface
    public interface ErrorHandler {
        void show(String title, String message);
    }

    private final DataManager dataManager;
    private final FormationViewModel viewModel;
    private final Consumer<String> statusUpdater;
    private final Runnable dancerListUpdater;
    private final Runnable formationListUpdater;
    private final Runnable stageRenderer;
    private final Consumer<File> importedAudioLoader;
    private final Consumer<File> importedVideoLoader;
    private final Consumer<DanceGroup> mediaRestorer;
    private final ErrorHandler errorHandler;
    private final Supplier<String> projectIdSupplier;
    private final Consumer<String> projectIdSetter;

    public ProjectFileController(
        DataManager dataManager,
        FormationViewModel viewModel,
        Consumer<String> statusUpdater,
        Runnable dancerListUpdater,
        Runnable formationListUpdater,
        Runnable stageRenderer,
        Consumer<File> importedAudioLoader,
        Consumer<File> importedVideoLoader,
        Consumer<DanceGroup> mediaRestorer,
        ErrorHandler errorHandler,
        Supplier<String> projectIdSupplier,
        Consumer<String> projectIdSetter
    ) {
        this.dataManager = dataManager;
        this.viewModel = viewModel;
        this.statusUpdater = statusUpdater;
        this.dancerListUpdater = dancerListUpdater;
        this.formationListUpdater = formationListUpdater;
        this.stageRenderer = stageRenderer;
        this.importedAudioLoader = importedAudioLoader;
        this.importedVideoLoader = importedVideoLoader;
        this.mediaRestorer = mediaRestorer;
        this.errorHandler = errorHandler;
        this.projectIdSupplier = projectIdSupplier;
        this.projectIdSetter = projectIdSetter;
    }

    public void exportProjectFile(Stage stage) {
        if (viewModel.getCurrentGroup() == null) {
            errorHandler.show("导出失败", "请先创建或打开一个工程");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出工程文件");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("队形工程文件", "*.ftproj"),
            new FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        fileChooser.setInitialFileName(viewModel.getCurrentGroup().getName() + ".ftproj");

        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            String projectId = projectIdSupplier.get();
            File audioFile = projectId != null ? dataManager.getProjectAudioFile(projectId) : null;
            File videoFile = projectId != null ? dataManager.getProjectVideoFile(projectId) : null;

            boolean success = dataManager.exportProjectAsZip(viewModel.getCurrentGroup(),
                audioFile, videoFile, file);
            if (success) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("导出成功");
                alert.setHeaderText("工程文件已导出");
                alert.setContentText("文件位置: " + file.getAbsolutePath() +
                    "\n\n可以将此.ftproj文件分享给Android端或其他设备导入使用。" +
                    "\n工程文件包含所有队形数据、音频和视频。");
                alert.showAndWait();
                statusUpdater.accept("已导出工程: " + file.getName());
            } else {
                errorHandler.show("导出失败", "无法导出工程文件");
            }
        }
    }

    public void importProjectFile(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导入工程文件");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("队形工程文件", "*.ftproj", "*.zip"),
            new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            statusUpdater.accept("正在载入工程文件: " + file.getName() + "...");

            final File finalFile = file;
            Thread importThread = new Thread(() -> {
                try {
                    DataManager.ProjectImportResult result = dataManager.importProjectFromZip(finalFile);

                    Platform.runLater(() -> {
                        if (result != null && result.group != null) {
                            dataManager.saveProject(result.group);

                            if (result.audioFile != null && result.audioFile.exists()) {
                                File copiedAudio = dataManager.copyAudioToProject(result.group.getId(), result.audioFile);
                                if (copiedAudio != null) {
                                    System.out.println("[导入] 音频已复制到工程文件夹: " + copiedAudio.getAbsolutePath());
                                }
                            }

                            if (result.videoFile != null && result.videoFile.exists()) {
                                File copiedVideo = dataManager.copyVideoToProject(result.group.getId(), result.videoFile);
                                if (copiedVideo != null) {
                                    System.out.println("[导入] 视频已复制到工程文件夹: " + copiedVideo.getAbsolutePath());
                                }
                            }

                            projectIdSetter.accept(result.group.getId());
                            viewModel.setCurrentGroup(result.group);
                            dancerListUpdater.run();
                            formationListUpdater.run();
                            stageRenderer.run();

                            if (result.audioFile != null && result.audioFile.exists()) {
                                importedAudioLoader.accept(result.audioFile);
                            }

                            if (result.videoFile != null && result.videoFile.exists()) {
                                importedVideoLoader.accept(result.videoFile);
                            }

                            Alert alert = new Alert(Alert.AlertType.INFORMATION);
                            alert.setTitle("导入成功");
                            alert.setHeaderText("工程已导入");
                            alert.setContentText("工程名称: " + result.group.getName() +
                                "\n舞者数量: " + result.group.getDancerCount() +
                                "\n队形数量: " + result.group.getFormations().size() +
                                (result.audioFile != null ? "\n已导入音频" : "") +
                                (result.videoFile != null ? "\n已导入视频" : "") +
                                "\n\n支持Android端导出的.ftproj工程文件。");
                            alert.showAndWait();

                            statusUpdater.accept("已导入工程: " + result.group.getName());
                        } else {
                            statusUpdater.accept("导入失败");
                            errorHandler.show("导入失败", "无法读取工程文件\n请确保文件格式正确。\n支持Android端导出的.ftproj格式。");
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        statusUpdater.accept("导入失败");
                        errorHandler.show("导入失败", "加载工程时出错: " + ex.getMessage());
                    });
                }
            });
            importThread.setDaemon(true);
            importThread.start();
        }
    }

    public void showProjectListDialog() {
        var projects = dataManager.listProjects();

        if (projects.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("工程列表");
            alert.setHeaderText("没有找到已保存的工程");
            alert.showAndWait();
            return;
        }

        ListView<String> listView = new ListView<>();
        for (var project : projects) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            String dateStr = sdf.format(new Date(project.modifiedTime));
            String item = String.format("%s (%d舞者, %d队形) - %s",
                project.name, project.dancerCount, project.formationCount, dateStr);
            listView.getItems().add(item);
        }

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        Label hint = new Label("双击打开工程，或选中后点击按钮操作");
        hint.setTextFill(Color.GRAY);
        hint.setStyle("-fx-font-size: 12px;");

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        Button deleteButton = new Button("删除");
        deleteButton.setStyle("-fx-background-color: #D32F2F; -fx-text-fill: white;");
        deleteButton.setOnAction(e -> {
            int selectedIndex = listView.getSelectionModel().getSelectedIndex();
            if (selectedIndex >= 0) {
                var project = projects.get(selectedIndex);
                Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
                confirmAlert.setTitle("确认删除");
                confirmAlert.setHeaderText("删除工程");
                confirmAlert.setContentText("确定要删除工程 \"" + project.name + "\" 吗？\n\n此操作不可恢复！");

                Optional<ButtonType> confirmResult = confirmAlert.showAndWait();
                if (confirmResult.isPresent() && confirmResult.get() == ButtonType.OK) {
                    boolean success = dataManager.deleteProject(project.id);
                    if (success) {
                        projects.remove(selectedIndex);
                        listView.getItems().remove(selectedIndex);
                        statusUpdater.accept("已删除工程: " + project.name);

                        if (projects.isEmpty()) {
                            ((Stage) deleteButton.getScene().getWindow()).close();
                        }
                    } else {
                        errorHandler.show("删除失败", "无法删除工程");
                    }
                }
            }
        });
        buttonBox.getChildren().add(deleteButton);

        content.getChildren().addAll(hint, listView, buttonBox);
        VBox.setVgrow(listView, Priority.ALWAYS);
        listView.setPrefHeight(400);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("打开工程");
        dialog.setHeaderText("工程列表");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                int selectedIndex = listView.getSelectionModel().getSelectedIndex();
                if (selectedIndex >= 0) {
                    dialog.setResult(ButtonType.OK);
                    dialog.close();
                }
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            int selectedIndex = listView.getSelectionModel().getSelectedIndex();
            if (selectedIndex >= 0) {
                var project = projects.get(selectedIndex);

                statusUpdater.accept("正在载入工程: " + project.name + "...");

                Thread loadThread = new Thread(() -> {
                    try {
                        DanceGroup group = dataManager.loadProject(project.id);

                        Platform.runLater(() -> {
                            if (group != null) {
                                viewModel.setCurrentGroup(group);
                                dancerListUpdater.run();
                                formationListUpdater.run();
                                stageRenderer.run();
                                statusUpdater.accept("已打开: " + group.getName());
                                mediaRestorer.accept(group);
                            } else {
                                statusUpdater.accept("打开失败");
                                errorHandler.show("打开失败", "无法加载工程数据");
                            }
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> {
                            statusUpdater.accept("打开失败");
                            errorHandler.show("打开失败", "加载工程时出错: " + ex.getMessage());
                        });
                    }
                });
                loadThread.setDaemon(true);
                loadThread.start();
            }
        }
    }
}
