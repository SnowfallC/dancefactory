package com.formationtool.desktop.controller;

import com.formationtool.desktop.model.CustomTeamPreset;
import com.formationtool.desktop.data.CustomTeamPresetStore;
import com.formationtool.desktop.view.CustomTeamPresetDialog;
import com.formationtool.desktop.view.PresetDialogTheme;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import java.io.IOException;
import java.util.UUID;
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
        new DancerPreset("米娅", 0xFFB0C4DE),
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
        PresetDialogTheme.apply(dialog);
        dialog.setTitle("团队预设");
        dialog.setHeaderText(null);

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

    public void showCustomPresetDialog() {
        CustomTeamPresetStore store = new CustomTeamPresetStore();
        Dialog<Void> dialog = new Dialog<>();
        PresetDialogTheme.apply(dialog);
        dialog.setTitle("本机自定义预设");
        dialog.setHeaderText(null);
        ListView<CustomTeamPreset> presets = new ListView<>();
        presets.setPrefHeight(260);
        presets.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(CustomTeamPreset item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + "（" + item.members().size() + " 人）");
            }
        });
        try { presets.getItems().setAll(store.load()); }
        catch (IOException e) { showPresetError(e); return; }
        Button create = new Button("新建预设");
        Button edit = new Button("编辑");
        Button apply = new Button("应用到当前工程");
        Button importFile = new Button("导入预设");
        importFile.setOnAction(event -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("导入预设 JSON 文件");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("预设 JSON 文件", "*.json"));
            java.io.File source = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (source == null) return;
            try {
                List<CustomTeamPreset> imported = store.readImport(source.toPath());
                var names = store.load().stream().map(CustomTeamPreset::name).collect(java.util.stream.Collectors.toSet());
                long conflicts = imported.stream().filter(preset -> names.contains(preset.name())).count();
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                PresetDialogTheme.apply(confirm);
                confirm.setTitle("导入预设");
                confirm.setHeaderText("将导入 " + imported.size() + " 个预设");
                confirm.setContentText(conflicts > 0 ? "其中 " + conflicts + " 个同名预设将被覆盖。" : "现有预设将保留。");
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                store.importPresets(imported);
                presets.getItems().setAll(store.load());
                statusUpdater.accept("已导入 " + imported.size() + " 个预设");
            } catch (IOException | IllegalArgumentException error) { showPresetError(error); }
        });
        edit.disableProperty().bind(presets.getSelectionModel().selectedItemProperty().isNull());
        apply.disableProperty().bind(presets.getSelectionModel().selectedItemProperty().isNull());
        Consumer<CustomTeamPreset> editPreset = initial -> CustomTeamPresetDialog.create(initial).showAndWait().ifPresent(preset -> {
            try {
                store.save(preset, initial == null ? null : initial.name());
                presets.getItems().setAll(store.load());
                presets.getSelectionModel().select(preset);
                statusUpdater.accept("已保存本机预设：" + preset.name());
            } catch (IOException e) { showPresetError(e); }
        });
        create.setOnAction(event -> editPreset.accept(null));
        edit.setOnAction(event -> editPreset.accept(presets.getSelectionModel().getSelectedItem()));
        apply.setOnAction(event -> {
            CustomTeamPreset preset = presets.getSelectionModel().getSelectedItem();
            if (preset == null) return;
            DancerPreset[] dancers = preset.members().stream()
                .map(member -> new DancerPreset(member.name(), 0xFF000000 | Integer.parseInt(member.color().substring(1), 16)))
                .toArray(DancerPreset[]::new);
            if (applyTeamPreset(dancers)) {
                statusUpdater.accept("已从预设创建舞者：" + preset.name());
                dialog.close();
            }
        });
        VBox content = new VBox(12, presets, new HBox(8, create, edit, apply, importFile));
        content.setPadding(new Insets(16));
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void showPresetError(Exception error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        PresetDialogTheme.apply(alert);
        alert.setTitle("本机预设");
        alert.setHeaderText("无法保存或读取本机预设");
        error.printStackTrace();
        alert.setContentText(error.getMessage());
        com.formationtool.desktop.view.DiagnosticDialogs.attachLogButton(alert);
        alert.showAndWait();
    }

    private boolean applyTeamPreset(DancerPreset[] presets) {
        if (viewModel.getCurrentGroup() == null) return false;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        PresetDialogTheme.apply(confirm);
        confirm.setTitle("确认应用预设");
        confirm.setHeaderText("应用团队预设");
        confirm.setContentText("将替换工程内的舞者，可通过撤销恢复。是否继续？");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return false;
        List<Dancer> roster = new ArrayList<>();
        for (DancerPreset preset : presets) {
            roster.add(new Dancer(UUID.randomUUID().toString(), preset.name, 0, 0, preset.color));
        }
        viewModel.replaceDancerRoster(roster);
        dancerListUpdater.run();
        stageRenderer.run();
        return true;
    }
}
