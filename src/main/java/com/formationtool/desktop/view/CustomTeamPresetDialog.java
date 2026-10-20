package com.formationtool.desktop.view;

import com.formationtool.desktop.data.CustomTeamPresetStore;
import com.formationtool.desktop.model.CustomTeamPreset;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** 编辑本机自定义人员模板。 */
public final class CustomTeamPresetDialog {
    private record MemberRow(TextField name, ColorPicker color) {}
    private CustomTeamPresetDialog() {}

    public static Dialog<CustomTeamPreset> create(CustomTeamPreset initial) {
        Dialog<CustomTeamPreset> dialog = new Dialog<>();
        PresetDialogTheme.apply(dialog);
        dialog.setTitle(initial == null ? "新建自定义预设" : "编辑自定义预设");
        dialog.setHeaderText(null);
        TextField presetName = new TextField(initial == null ? "" : initial.name());
        presetName.setPromptText("预设名称");
        presetName.setId("preset-name");
        Spinner<Integer> count = new Spinner<>(1, CustomTeamPresetStore.maxDancers,
            initial == null ? 9 : initial.members().size());
        count.setEditable(true);
        count.setId("preset-count");
        count.setPrefWidth(90);
        Button generate = new Button("生成人员");
        generate.setId("generate-members");
        Label error = new Label();
        error.getStyleClass().add("preset-error");
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());
        List<MemberRow> rows = new ArrayList<>();
        VBox members = new VBox(6);
        ScrollPane scroll = new ScrollPane(members);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("preset-members-scroll");
        Label memberCount = new Label();
        memberCount.getStyleClass().add("preset-count-badge");
        Runnable rebuild = () -> {
            try {
                int size = Integer.parseInt(count.getEditor().getText().trim());
                if (size < 1 || size > CustomTeamPresetStore.maxDancers) throw new IllegalArgumentException("人数需要在 1—200 之间");
                while (rows.size() > size) rows.remove(rows.size() - 1);
                while (rows.size() < size) {
                    int index = rows.size();
                    var member = initial != null && index < initial.members().size() ? initial.members().get(index) : null;
                    rows.add(new MemberRow(new TextField(member == null ? "舞者 " + (index + 1) : member.name()),
                        new ColorPicker(member == null ? Color.hsb(index * 137.508 % 360, 0.65, 0.9) : Color.web(member.color()))));
                }
                members.getChildren().clear();
                for (int i = 0; i < rows.size(); i++) {
                    MemberRow row = rows.get(i);
                    Label number = new Label(String.format(Locale.ROOT, "%02d", i + 1));
                    number.getStyleClass().add("preset-member-number");
                    number.setMinSize(30, 30);
                    number.setAlignment(Pos.CENTER);
                    row.color().setPrefWidth(132);
                    row.color().setMinWidth(132);
                    row.name().getStyleClass().setAll("text-field", "preset-member-name");
                    row.name().setMinWidth(100);
                    HBox line = new HBox(10, number, row.name(), row.color());
                    line.setAlignment(Pos.CENTER_LEFT);
                    line.getStyleClass().add("preset-member-row");
                    HBox.setHgrow(row.name(), Priority.ALWAYS);
                    members.getChildren().add(line);
                }
                memberCount.setText(rows.size() + " 人");
                scroll.setPrefHeight(Math.min(6, rows.size()) * 58 + 4);
                scroll.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                scroll.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                error.setText("");
                if (dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage stage && stage.isShowing()) {
                    stage.sizeToScene();
                }
            } catch (IllegalArgumentException e) {
                error.setText("请输入 1—200 之间的整数人数");
            }
        };
        generate.setOnAction(event -> rebuild.run());
        rebuild.run();
        Label title = new Label(initial == null ? "新建预设" : "编辑预设");
        title.getStyleClass().add("preset-title");

        Label nameLabel = new Label("预设名称");
        nameLabel.getStyleClass().add("preset-field-label");
        Label countLabel = new Label("舞者人数");
        countLabel.getStyleClass().add("preset-field-label");
        HBox countControls = new HBox(8, count, generate);
        countControls.setAlignment(Pos.CENTER_LEFT);
        VBox nameField = new VBox(8, nameLabel, presetName);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        VBox countField = new VBox(8, countLabel, countControls);
        HBox settings = new HBox(18, nameField, countField);
        settings.getStyleClass().add("preset-settings-card");

        Label membersLabel = new Label("舞者名单");
        membersLabel.getStyleClass().add("preset-section-title");
        HBox section = new HBox(8, membersLabel, memberCount);
        section.setAlignment(Pos.CENTER_LEFT);
        Label nameHeading = new Label("姓名");
        HBox.setHgrow(nameHeading, Priority.ALWAYS);
        nameHeading.setMaxWidth(Double.MAX_VALUE);
        Label colorHeading = new Label("代表色");
        colorHeading.setMinWidth(132);
        HBox columns = new HBox(10, new Label("编号"), nameHeading, colorHeading);
        columns.getChildren().get(0).setStyle("-fx-min-width: 30;");
        columns.getStyleClass().add("preset-column-headings");
        VBox roster = new VBox(9, section, columns, scroll);

        VBox content = new VBox(20, title, settings, roster, error);
        content.setPadding(new Insets(24, 24, 10, 24));
        content.setPrefWidth(620);
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType("保存到本机", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).getStyleClass().add("preset-primary-action");
        AtomicReference<CustomTeamPreset> result = new AtomicReference<>();
        dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, event -> {
            rebuild.run();
            if (!error.getText().isEmpty()) { event.consume(); return; }
            List<CustomTeamPreset.Member> values = new ArrayList<>();
            for (MemberRow row : rows) {
                Color color = row.color().getValue();
                String hex = String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(color.getRed() * 255),
                    Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
                values.add(new CustomTeamPreset.Member(row.name().getText().trim(), hex));
            }
            CustomTeamPreset preset = new CustomTeamPreset(presetName.getText().trim(), values);
            try { CustomTeamPresetStore.validate(preset); result.set(preset); }
            catch (IllegalArgumentException e) { error.setText(e.getMessage()); event.consume(); }
        });
        dialog.setResultConverter(button -> button == save ? result.get() : null);
        return dialog;
    }
}
