package com.formationtool.desktop.controller;

import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class ProjectSessionController {
    private final FormationViewModel viewModel;
    private final DataManager dataManager;
    private final StageView stageView;
    private final Consumer<String> statusUpdater;
    private final Runnable dancerListUpdater;
    private final Runnable formationListUpdater;
    private final Consumer<DanceGroup> mediaRestorer;
    private final Runnable saveCurrentProject;
    private final Runnable stopAutoSaveTimer;
    private final Runnable startAutoSaveTimer;

    public ProjectSessionController(
        FormationViewModel viewModel,
        DataManager dataManager,
        StageView stageView,
        Consumer<String> statusUpdater,
        Runnable dancerListUpdater,
        Runnable formationListUpdater,
        Consumer<DanceGroup> mediaRestorer,
        Runnable saveCurrentProject,
        Runnable stopAutoSaveTimer,
        Runnable startAutoSaveTimer
    ) {
        this.viewModel = viewModel;
        this.dataManager = dataManager;
        this.stageView = stageView;
        this.statusUpdater = statusUpdater;
        this.dancerListUpdater = dancerListUpdater;
        this.formationListUpdater = formationListUpdater;
        this.mediaRestorer = mediaRestorer;
        this.saveCurrentProject = saveCurrentProject;
        this.stopAutoSaveTimer = stopAutoSaveTimer;
        this.startAutoSaveTimer = startAutoSaveTimer;
    }

    public void loadAndBindProject(Stage primaryStage, String projectId) {
        try {
            System.out.println("[工程加载] 窗口已显示，开始加载工程数据...");

            DanceGroup group = dataManager.loadProject(projectId);
            if (group != null) {
                System.out.println("[工程加载] 工程数据加载成功: " + group.getName());
                System.out.println("[工程加载] 队形数量: " + group.getFormations().size());

                if (group.getFormations().isEmpty()) {
                    System.out.println("[工程加载] 队形列表为空，创建默认关键帧...");
                    Formation defaultFormation = new Formation(
                        UUID.randomUUID().toString(),
                        "关键帧 00:00",
                        0L
                    );
                    group.getFormations().add(defaultFormation);
                    System.out.println("[工程加载] 默认关键帧已创建");
                }

                viewModel.setCurrentGroup(group);
                System.out.println("[工程加载] 工程已设置到 ViewModel");

                stageView.updateStageSize(group.getStageWidth(), group.getStageDepth());
                System.out.println("[工程加载] 舞台尺寸已同步: " + group.getStageWidth() + "m x " + group.getStageDepth() + "m");

                dancerListUpdater.run();
                System.out.println("[工程加载] 舞者列表已更新");

                formationListUpdater.run();
                System.out.println("[工程加载] 队形列表已更新");

                stageView.render();
                System.out.println("[工程加载] 舞台视图已渲染");

                System.out.println("[工程加载] 开始恢复媒体文件...");
                mediaRestorer.accept(group);
                System.out.println("[工程加载] 媒体文件恢复完成");

                statusUpdater.accept("已打开: " + group.getName());
                System.out.println("[工程加载] 工程加载完成！");
            } else {
                System.err.println("[工程加载] 错误：无法加载工程数据");
                viewModel.createNewGroup("示例舞团", 9, 16f, 12f);
                dancerListUpdater.run();
                formationListUpdater.run();
                statusUpdater.accept("已创建示例工程");
            }

            viewModel.currentGroupProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    formationListUpdater.run();
                }
            });

            primaryStage.setOnCloseRequest(event -> {
                if (viewModel.isDirty()) {
                    event.consume();

                    Alert alert = new Alert(Alert.AlertType.NONE);
                    alert.setTitle("保存提示");
                    alert.setHeaderText(null);
                    alert.setContentText("是否在退出前保存工程？");

                    DialogPane dp = alert.getDialogPane();
                    dp.setStyle("-fx-background-color: #1E1E2E;");
                    dp.lookupAll(".label").forEach(node -> node.setStyle("-fx-text-fill: white;"));
                    dp.lookupAll(".header-panel").forEach(node -> node.setStyle("-fx-background-color: #1E1E2E;"));
                    dp.lookupAll(".content").forEach(node -> node.setStyle("-fx-text-fill: white;"));
                    dp.lookupAll(".button").forEach(node -> node.setStyle(
                        "-fx-background-color: #3A3A4A; -fx-text-fill: white;"
                    ));

                    ButtonType saveButton = new ButtonType("保存", ButtonBar.ButtonData.YES);
                    ButtonType noSaveButton = new ButtonType("不保存", ButtonBar.ButtonData.NO);
                    ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                    alert.getButtonTypes().setAll(saveButton, noSaveButton, cancelButton);

                    Optional<ButtonType> result = alert.showAndWait();
                    if (result.isPresent()) {
                        if (result.get() == saveButton) {
                            saveCurrentProject.run();
                            stopAutoSaveTimer.run();
                            primaryStage.close();
                        } else if (result.get() == noSaveButton) {
                            stopAutoSaveTimer.run();
                            primaryStage.close();
                        }
                    }
                } else {
                    stopAutoSaveTimer.run();
                }
            });

            startAutoSaveTimer.run();

        } catch (Exception e) {
            System.err.println("[工程加载] 发生异常: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("加载失败");
                alert.setHeaderText("无法加载工程");
                alert.setContentText("错误信息: " + e.getMessage() + "\n\n" +
                    "请查看控制台输出获取详细信息。");
                alert.showAndWait();
            });
        }
    }
}
