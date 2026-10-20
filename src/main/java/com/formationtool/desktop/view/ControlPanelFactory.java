package com.formationtool.desktop.view;

import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.function.Consumer;

public final class ControlPanelFactory {
    public static final class PanelResult {
        private final VBox panel;
        private final ListView<String> dancerListView;
        private final ListView<String> formationListView;

        public PanelResult(VBox panel, ListView<String> dancerListView, ListView<String> formationListView) {
            this.panel = panel;
            this.dancerListView = dancerListView;
            this.formationListView = formationListView;
        }

        public VBox getPanel() {
            return panel;
        }

        public ListView<String> getDancerListView() {
            return dancerListView;
        }

        public ListView<String> getFormationListView() {
            return formationListView;
        }
    }

    private ControlPanelFactory() {
    }

    public static PanelResult create(
        FormationViewModel viewModel,
        StageView stageView,
        Consumer<String> statusUpdater,
        Runnable onShowAddDancerDialog,
        Runnable onDeleteSelectedDancer,
        Runnable onEditSelectedDancer,
        Runnable onDeleteCurrentFormation,
        Runnable onBatchDeleteFormations,
        Runnable onSnapDancersToGrid,
        Runnable onShowFormationPresetsDialog,
        Runnable onCopyPreviousKeyframe,
        Runnable onCopyCurrentKeyframe,
        Runnable onPasteCopiedKeyframe
    ) {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #1E1E2E;");

        Label dancerLabel = new Label("舞者列表");
        dancerLabel.setTextFill(Color.WHITE);
        dancerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        ListView<String> dancerListView = new ListView<>();
        dancerListView.setMinHeight(100);
        dancerListView.setPrefHeight(150);
        VBox.setVgrow(dancerListView, Priority.ALWAYS);

        dancerListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && viewModel.getCurrentGroup() != null) {
                Formation formation = viewModel.getCurrentGroup().getFormations()
                    .get(viewModel.getCurrentFormationIndex());
                for (Dancer dancer : formation.getDancers()) {
                    if (newVal.equals(dancer.getName())) {
                        viewModel.toggleDancerSelection(dancer.getId());
                        stageView.render();
                        break;
                    }
                }
            }
        });
        dancerListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                onEditSelectedDancer.run();
            }
        });

        HBox dancerButtons = new HBox(5);
        Button addDancerBtn = new Button("+");
        Button removeDancerBtn = new Button("-");
        addDancerBtn.setOnAction(e -> onShowAddDancerDialog.run());
        removeDancerBtn.setOnAction(e -> onDeleteSelectedDancer.run());
        dancerButtons.getChildren().addAll(addDancerBtn, removeDancerBtn);

        Label formationLabel = new Label("队形列表");
        formationLabel.setTextFill(Color.WHITE);
        formationLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        ListView<String> formationListView = new ListView<>();
        formationListView.setMinHeight(100);
        formationListView.setPrefHeight(150);
        VBox.setVgrow(formationListView, Priority.ALWAYS);
        formationListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        formationListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.intValue() >= 0) {
                viewModel.setCurrentFormationIndex(newVal.intValue());
                if (viewModel.isStandardMode() && !viewModel.isPlaying()
                        && newVal.intValue() < viewModel.getCurrentGroup().getFormations().size()) {
                    viewModel.setCurrentTime(viewModel.getCurrentGroup().getFormations().get(newVal.intValue()).getTimestamp());
                    stageView.render();
                }
            }
        });
        formationListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                int selectedIndex = formationListView.getSelectionModel().getSelectedIndex();
                if (selectedIndex >= 0 && viewModel.getCurrentGroup() != null) {
                    Formation formation = viewModel.getCurrentGroup().getFormations().get(selectedIndex);
                    viewModel.setCurrentTime(formation.getTimestamp());
                    viewModel.setCurrentFormationIndex(selectedIndex);
                    stageView.render();
                    statusUpdater.accept("已跳转到: " + formation.getName());
                }
            }
        });

        HBox formationButtons = new HBox(5);
        Button removeFormationBtn = new Button("-");
        removeFormationBtn.setOnAction(e -> onDeleteCurrentFormation.run());
        Button batchDeleteFormationBtn = new Button("批量删除");
        batchDeleteFormationBtn.setOnAction(e -> onBatchDeleteFormations.run());
        Button addFormationBtn = new Button("+ 队形");
        addFormationBtn.setTooltip(new Tooltip("在当前播放位置建立队形区间，拖动区间边界调整保持和过渡时长"));
        addFormationBtn.setOnAction(e -> {
            if (viewModel.getCurrentGroup() == null || viewModel.isPlaying()) return;
            Formation formation = viewModel.addFormationInterval();
            if (formation != null) {
                viewModel.setCurrentFormationIndex(viewModel.getCurrentGroup().getFormations().indexOf(formation));
                stageView.render();
            } else {
                statusUpdater.accept("当前音乐剩余时长不足，请将播放位置移到前面再添加队形");
            }
        });
        formationButtons.getChildren().addAll(addFormationBtn, removeFormationBtn, batchDeleteFormationBtn);

        Label quickActionsLabel = new Label("快速操作");
        quickActionsLabel.setTextFill(Color.WHITE);
        quickActionsLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        GridPane buttonGrid = new GridPane();
        buttonGrid.setHgap(5);
        buttonGrid.setVgap(5);

        Button snapToGridBtn = new Button("对齐网格");
        Button formationBtn = new Button("快速编队");
        Button clearSelectionBtn = new Button("清除选择");
        Button toggleNamesBtn = new Button("显示名字");
        Button copyPrevFrameBtn = new Button("复制前帧");
        Button copyFrameBtn = new Button("复制关键帧");
        Button pasteFrameBtn = new Button("粘贴关键帧");
        Button toggleCenterLineBtn = new Button("显示C位线");
        Button flipDirectionBtn = new Button("翻转方向");
        Button autoSnapBtn = new Button("自动吸附");

        snapToGridBtn.setMaxWidth(Double.MAX_VALUE);
        formationBtn.setMaxWidth(Double.MAX_VALUE);
        clearSelectionBtn.setMaxWidth(Double.MAX_VALUE);
        toggleNamesBtn.setMaxWidth(Double.MAX_VALUE);
        copyPrevFrameBtn.setMaxWidth(Double.MAX_VALUE);
        copyFrameBtn.setMaxWidth(Double.MAX_VALUE);
        pasteFrameBtn.setMaxWidth(Double.MAX_VALUE);
        toggleCenterLineBtn.setMaxWidth(Double.MAX_VALUE);
        flipDirectionBtn.setMaxWidth(Double.MAX_VALUE);
        autoSnapBtn.setMaxWidth(Double.MAX_VALUE);

        snapToGridBtn.setOnAction(e -> onSnapDancersToGrid.run());
        formationBtn.setOnAction(e -> onShowFormationPresetsDialog.run());
        clearSelectionBtn.setOnAction(e -> viewModel.clearSelection());
        copyPrevFrameBtn.setOnAction(e -> onCopyPreviousKeyframe.run());
        copyFrameBtn.setOnAction(e -> onCopyCurrentKeyframe.run());
        pasteFrameBtn.setOnAction(e -> onPasteCopiedKeyframe.run());

        final boolean[] showCenterLine = {false};
        toggleCenterLineBtn.setOnAction(e -> {
            showCenterLine[0] = !showCenterLine[0];
            stageView.setShowCenterLine(showCenterLine[0]);
            toggleCenterLineBtn.setText(showCenterLine[0] ? "隐藏c位线" : "显示c位线");
        });

        final boolean[] showNames = {false};
        toggleNamesBtn.setOnAction(e -> {
            showNames[0] = !showNames[0];
            stageView.setShowDancerNames(showNames[0]);
            toggleNamesBtn.setText(showNames[0] ? "隐藏名字" : "显示名字");
        });

        final boolean[] flipped = {false};
        flipDirectionBtn.setOnAction(e -> {
            flipped[0] = !flipped[0];
            stageView.setFlipped(flipped[0]);
            flipDirectionBtn.setText(flipped[0] ? "恢复方向" : "翻转方向");
        });

        final boolean[] autoSnapEnabled = {true};
        autoSnapBtn.setText("关闭吸附");
        autoSnapBtn.setOnAction(e -> {
            autoSnapEnabled[0] = !autoSnapEnabled[0];
            stageView.setAutoSnap(autoSnapEnabled[0]);
            autoSnapBtn.setText(autoSnapEnabled[0] ? "关闭吸附" : "开启吸附");
        });

        buttonGrid.add(snapToGridBtn, 0, 0);
        buttonGrid.add(formationBtn, 1, 0);
        buttonGrid.add(clearSelectionBtn, 0, 1);
        buttonGrid.add(toggleNamesBtn, 1, 1);
        buttonGrid.add(copyPrevFrameBtn, 0, 2);
        buttonGrid.add(copyFrameBtn, 1, 2);
        buttonGrid.add(pasteFrameBtn, 0, 3);
        buttonGrid.add(toggleCenterLineBtn, 1, 3);
        buttonGrid.add(flipDirectionBtn, 0, 4);
        buttonGrid.add(autoSnapBtn, 1, 4);

        for (int i = 0; i < 2; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(50);
            buttonGrid.getColumnConstraints().add(col);
        }

        panel.getChildren().addAll(
            dancerLabel, dancerListView, dancerButtons,
            new Separator(),
            formationLabel, formationListView, formationButtons,
            new Separator(),
            quickActionsLabel, buttonGrid
        );

        return new PanelResult(panel, dancerListView, formationListView);
    }
}
