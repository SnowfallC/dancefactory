package com.formationtool.desktop.controller;

import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class TeamPresetController {
    private static final class DancerPreset {
        private final String name;
        private final int color;

        private DancerPreset(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    private static final DancerPreset[] MUSE_DANCERS = {
        new DancerPreset("穗乃果", 0xFFFF6600),
        new DancerPreset("绘里", 0xFF7FFFFF),
        new DancerPreset("小鸟", 0xFFC8C8C8),
        new DancerPreset("海未", 0xFF1569C7),
        new DancerPreset("凛", 0xFFFFF000),
        new DancerPreset("真姬", 0xFFFF3333),
        new DancerPreset("希", 0xFF744896),
        new DancerPreset("花阳", 0xFF5BAE23),
        new DancerPreset("妮可", 0xFFFF60AF)
    };

    private static final DancerPreset[] AQOURS_DANCERS = {
        new DancerPreset("千歌", 0xFFFF7F27),
        new DancerPreset("梨子", 0xFFFF9EAC),
        new DancerPreset("果南", 0xFF00D29E),
        new DancerPreset("黛雅", 0xFFF23B4C),
        new DancerPreset("曜", 0xFF5AC7FA),
        new DancerPreset("善子", 0xFFC0C0C0),
        new DancerPreset("花丸", 0xFFFFDD00),
        new DancerPreset("鞠莉", 0xFFAE59F5),
        new DancerPreset("露比", 0xFFFF6FCF)
    };

    private static final DancerPreset[] NIJIGASAKI_DANCERS = {
        new DancerPreset("步梦", 0xFFFFAAC8),
        new DancerPreset("霞", 0xFFFFDC00),
        new DancerPreset("雫", 0xFFA0D8EF),
        new DancerPreset("果林", 0xFF4682B4),
        new DancerPreset("爱", 0xFFFF6600),
        new DancerPreset("彼方", 0xFF9966CC),
        new DancerPreset("雪菜", 0xFFEE0000),
        new DancerPreset("艾玛", 0xFF88CC66),
        new DancerPreset("璃奈", 0xFFC8C8C8),
        new DancerPreset("栞子", 0xFF00D0B1),
        new DancerPreset("泰勒", 0xFFB0C4DE),
        new DancerPreset("钟岚珠", 0xFFF8C8DC),
        new DancerPreset("高咲侑", 0xFFDC143C)
    };

    private static final DancerPreset[] LIELLA_DANCERS = {
        new DancerPreset("香音", 0xFFFF7F27),
        new DancerPreset("可可", 0xFF5EC8F5),
        new DancerPreset("千砂都", 0xFFFF6FCF),
        new DancerPreset("堇", 0xFF74DF00),
        new DancerPreset("恋", 0xFF7B68EE),
        new DancerPreset("希奈子", 0xFFFFF44F),
        new DancerPreset("芽衣", 0xFFEE1166),
        new DancerPreset("四季", 0xFF9CA5B0),
        new DancerPreset("夏美", 0xFFBB88FF),
        new DancerPreset("薇恩", 0xFFCC0033),
        new DancerPreset("冬毬", 0xFFFFB6C1)
    };

    private static final DancerPreset[] HASUNOSORA_DANCERS = {
        new DancerPreset("花帆", 0xFFE2A9A1),
        new DancerPreset("沙耶香", 0xFF5B8DBF),
        new DancerPreset("乙宗梢", 0xFF976E9A),
        new DancerPreset("缀理", 0xFFF8C8C4),
        new DancerPreset("瑠璃乃", 0xFF6BB6BB),
        new DancerPreset("藤岛慈", 0xFFC3D825),
        new DancerPreset("吟子", 0xFFF8E577),
        new DancerPreset("小铃", 0xFFEE86A7),
        new DancerPreset("姬芽", 0xFFF39E7D),
        new DancerPreset("塞拉斯", 0xFFA77BCA),
        new DancerPreset("桂城泉", 0xFF9CA5B0)
    };

    private final FormationViewModel viewModel;
    private final Runnable dancerListUpdater;
    private final Runnable stageRenderer;
    private final Consumer<String> statusUpdater;

    public TeamPresetController(
        FormationViewModel viewModel,
        Runnable dancerListUpdater,
        Runnable stageRenderer,
        Consumer<String> statusUpdater
    ) {
        this.viewModel = viewModel;
        this.dancerListUpdater = dancerListUpdater;
        this.stageRenderer = stageRenderer;
        this.statusUpdater = statusUpdater;
    }

    public void showTeamPresetDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("团队预设");
        dialog.setHeaderText("选择一个团队预设来替换当前所有舞者");

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));

        Button museBtn = new Button("μ's (穆斯) - 9人");
        Button aqoursBtn = new Button("Aqours - 9人");
        Button nijiBtn = new Button("虹咲学园 - 13人");
        Button liellaBtn = new Button("Liella! - 11人");
        Button hasunosoraBtn = new Button("莲之空 - 11人");

        museBtn.setMaxWidth(Double.MAX_VALUE);
        aqoursBtn.setMaxWidth(Double.MAX_VALUE);
        nijiBtn.setMaxWidth(Double.MAX_VALUE);
        liellaBtn.setMaxWidth(Double.MAX_VALUE);
        hasunosoraBtn.setMaxWidth(Double.MAX_VALUE);

        museBtn.setOnAction(e -> {
            applyTeamPreset(MUSE_DANCERS);
            dialog.setResult("μ's");
            dialog.close();
        });

        aqoursBtn.setOnAction(e -> {
            applyTeamPreset(AQOURS_DANCERS);
            dialog.setResult("Aqours");
            dialog.close();
        });

        nijiBtn.setOnAction(e -> {
            applyTeamPreset(NIJIGASAKI_DANCERS);
            dialog.setResult("虹咲");
            dialog.close();
        });

        liellaBtn.setOnAction(e -> {
            applyTeamPreset(LIELLA_DANCERS);
            dialog.setResult("Liella!");
            dialog.close();
        });

        hasunosoraBtn.setOnAction(e -> {
            applyTeamPreset(HASUNOSORA_DANCERS);
            dialog.setResult("莲之空");
            dialog.close();
        });

        content.getChildren().addAll(museBtn, aqoursBtn, nijiBtn, liellaBtn, hasunosoraBtn);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(team -> statusUpdater.accept("已应用" + team + "预设"));
    }

    public void applyMusPreset() {
        applyTeamPreset(MUSE_DANCERS);
    }

    public void applyAqoursPreset() {
        applyTeamPreset(AQOURS_DANCERS);
    }

    public void applyNijigasakiPreset() {
        applyTeamPreset(NIJIGASAKI_DANCERS);
    }

    public void applyLiellasPreset() {
        applyTeamPreset(LIELLA_DANCERS);
    }

    public void applyHasunosoraPreset() {
        applyTeamPreset(HASUNOSORA_DANCERS);
    }

    private void applyTeamPreset(DancerPreset[] presets) {
        if (viewModel.getCurrentGroup() == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认应用预设");
        confirm.setHeaderText("应用团队预设");
        confirm.setContentText("将替换所有现有舞者\n是否继续?");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            DanceGroup group = viewModel.getCurrentGroup();

            List<String> dancerIds = new ArrayList<>();
            for (Dancer dancer : group.getFormations().get(0).getDancers()) {
                dancerIds.add(dancer.getId());
            }
            for (String id : dancerIds) {
                viewModel.deleteDancer(id);
            }

            for (DancerPreset preset : presets) {
                viewModel.addDancer(preset.name, preset.color);
            }

            dancerListUpdater.run();
            stageRenderer.run();
        }
    }
}
