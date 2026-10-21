package com.formationtool.desktop.controller;

import com.formationtool.desktop.AppVersion;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.view.ColorPickerDialog;
import com.formationtool.desktop.view.ImageCropDialog;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class DesktopDialogController {
    @FunctionalInterface
    public interface ErrorHandler {
        void show(String title, String message);
    }

    private final FormationViewModel viewModel;
    private final DataManager dataManager;
    private final StageView stageView;
    private final Runnable dancerListUpdater;
    private final Runnable formationListUpdater;
    private final Consumer<String> statusUpdater;
    private final ErrorHandler errorHandler;

    public DesktopDialogController(
        FormationViewModel viewModel,
        DataManager dataManager,
        StageView stageView,
        Runnable dancerListUpdater,
        Runnable formationListUpdater,
        Consumer<String> statusUpdater,
        ErrorHandler errorHandler
    ) {
        this.viewModel = viewModel;
        this.dataManager = dataManager;
        this.stageView = stageView;
        this.dancerListUpdater = dancerListUpdater;
        this.formationListUpdater = formationListUpdater;
        this.statusUpdater = statusUpdater;
        this.errorHandler = errorHandler;
    }

    public void showNewProjectDialog() {
        TextInputDialog dialog = new TextInputDialog("新工程");
        dialog.setTitle("新建工程");
        dialog.setHeaderText("创建新的舞蹈编排工程");
        dialog.setContentText("工程名称:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            viewModel.createNewGroup(name, 9, 16f, 12f);
            dancerListUpdater.run();
            formationListUpdater.run();
            statusUpdater.accept("已创建工程: " + name);
        });
    }

    public void showAddDancerDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("添加舞者");
        dialog.setHeaderText("添加新舞者");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.setStyle("-fx-background-color: #1E1E2E;");
        dialogPane.lookup(".header-panel").setStyle("-fx-background-color: #1E1E2E;");

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: #1E1E2E;");

        TextField nameField = new TextField();
        nameField.setPromptText("留空自动编号");
        nameField.setStyle("-fx-background-color: #2A2A3F; -fx-text-fill: white; -fx-prompt-text-fill: gray;");
        Label nameLabel = new Label("舞者名称:");
        nameLabel.setTextFill(Color.WHITE);
        VBox nameBox = new VBox(5, nameLabel, nameField);

        Label colorLabel = new Label("选择颜色:");
        colorLabel.setTextFill(Color.WHITE);

        HBox colorBox = new HBox(10);
        colorBox.setAlignment(Pos.CENTER_LEFT);

        final int[] selectedColor = {0xFFFF0000};

        Rectangle colorPreview = new Rectangle(50, 50);
        colorPreview.setFill(Color.rgb(
            (selectedColor[0] >> 16) & 0xFF,
            (selectedColor[0] >> 8) & 0xFF,
            selectedColor[0] & 0xFF
        ));
        colorPreview.setStroke(Color.WHITE);
        colorPreview.setStrokeWidth(2);

        Button colorPickerBtn = new Button("选择颜色");
        colorPickerBtn.setStyle("-fx-background-color: #2A2A3F; -fx-text-fill: white;");
        colorPickerBtn.setOnAction(e -> {
            Color initialColor = Color.rgb(
                (selectedColor[0] >> 16) & 0xFF,
                (selectedColor[0] >> 8) & 0xFF,
                selectedColor[0] & 0xFF
            );

            ColorPickerDialog colorDialog = new ColorPickerDialog(initialColor);
            Optional<Color> result = colorDialog.showAndWait();
            result.ifPresent(color -> {
                selectedColor[0] = ((int) (color.getRed() * 255) << 16) |
                    ((int) (color.getGreen() * 255) << 8) |
                    (int) (color.getBlue() * 255);
                colorPreview.setFill(color);
            });
        });

        colorBox.getChildren().addAll(colorPreview, colorPickerBtn);

        content.getChildren().addAll(nameBox, colorLabel, colorBox);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String name = nameField.getText();
            int count = viewModel.getCurrentGroup().getFormations().get(0).getDancers().size();
            int colorWithAlpha = 0xFF000000 | selectedColor[0];

            System.out.println("[添加舞者] 当前舞者数: " + count);
            System.out.println("[添加舞者] 新舞者名称: " + (name.isEmpty() ? "舞者" + (count + 1) : name));
            System.out.println("[添加舞者] 新舞者颜色: " + Integer.toHexString(colorWithAlpha));

            viewModel.addDancer(name.isEmpty() ? "舞者" + (count + 1) : name, colorWithAlpha);

            int newCount = viewModel.getCurrentGroup().getFormations().get(0).getDancers().size();
            System.out.println("[添加舞者] 添加后舞者数: " + newCount);

            dancerListUpdater.run();
            stageView.render();
            statusUpdater.accept("已添加舞者");
        }
    }

    public void editSelectedDancer() {
        if (viewModel.getCurrentGroup() == null) {
            return;
        }

        Formation formation = viewModel.getCurrentGroup().getFormations()
            .get(viewModel.getCurrentFormationIndex());

        for (Dancer dancer : formation.getDancers()) {
            if (!dancer.isSelected()) {
                continue;
            }

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("编辑舞者");
            dialog.setHeaderText("编辑舞者信息");

            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            HBox nameBox = new HBox(10);
            Label nameLabel = new Label("舞者名称");
            TextField nameField = new TextField(dancer.getName());
            nameBox.getChildren().addAll(nameLabel, nameField);

            Label colorLabel = new Label("舞者颜色");
            HBox colorBox = new HBox(10);

            int currentColor = dancer.getColor();
            Rectangle colorPreview = new Rectangle(50, 30);
            Color currentJavaFXColor = Color.rgb(
                (currentColor >> 16) & 0xFF,
                (currentColor >> 8) & 0xFF,
                currentColor & 0xFF
            );
            colorPreview.setFill(currentJavaFXColor);
            colorPreview.setStroke(Color.WHITE);
            colorPreview.setStrokeWidth(2);

            final int[] selectedColor = {currentColor & 0xFFFFFF};
            final String[] pendingStylePath = {dancer.getCustomStylePath()};

            Button colorPickerBtn = new Button("选择颜色");
            colorPickerBtn.setOnAction(e -> {
                ColorPicker colorPicker = new ColorPicker(currentJavaFXColor);
                Dialog<ButtonType> colorDialog = new Dialog<>();
                colorDialog.setTitle("选择颜色");
                colorDialog.getDialogPane().setContent(colorPicker);
                colorDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

                colorDialog.showAndWait().ifPresent(result -> {
                    if (result == ButtonType.OK) {
                        Color color = colorPicker.getValue();
                        selectedColor[0] = ((int) (color.getRed() * 255) << 16) |
                            ((int) (color.getGreen() * 255) << 8) |
                            (int) (color.getBlue() * 255);
                        colorPreview.setFill(color);
                    }
                });
            });

            colorBox.getChildren().addAll(colorPreview, colorPickerBtn);

            Label styleLabel = new Label("自定义样式");
            HBox stylePreviewRow = new HBox(10);
            stylePreviewRow.setAlignment(Pos.CENTER_LEFT);

            ImageView stylePreview = new ImageView();
            stylePreview.setFitWidth(52);
            stylePreview.setFitHeight(52);
            stylePreview.setPreserveRatio(true);
            stylePreview.setSmooth(true);
            stylePreview.setStyle("-fx-border-color: #666; -fx-border-width: 1;");
            refreshStylePreview(stylePreview, pendingStylePath[0]);

            Label stylePathLabel = new Label();
            stylePathLabel.setWrapText(true);
            stylePathLabel.setMaxWidth(260);
            stylePathLabel.setText(pendingStylePath[0] == null ? "未设置样式" : new File(pendingStylePath[0]).getName());

            stylePreviewRow.getChildren().addAll(stylePreview, stylePathLabel);

            HBox styleButtonRow = new HBox(10);
            styleButtonRow.setAlignment(Pos.CENTER_LEFT);
            Button uploadStyleBtn = new Button("上传样式");
            Button clearStyleBtn = new Button("清除样式");
            Button previewStyleBtn = new Button("预览");

            clearStyleBtn.setDisable(pendingStylePath[0] == null || pendingStylePath[0].isBlank());
            previewStyleBtn.setDisable(pendingStylePath[0] == null || pendingStylePath[0].isBlank());

            uploadStyleBtn.setOnAction(e -> {
                Window owner = dialog.getDialogPane().getScene() != null
                    ? dialog.getDialogPane().getScene().getWindow()
                    : null;
                File selectedImage = chooseStyleImage(owner);
                if (selectedImage == null) {
                    return;
                }

                try {
                    ImageCropDialog cropDialog = new ImageCropDialog(owner, selectedImage);
                    Optional<BufferedImage> cropped = cropDialog.showAndWait();
                    if (cropped.isEmpty()) {
                        return;
                    }

                    String projectId = viewModel.getCurrentGroup().getId();
                    File savedStyle = dataManager.saveDancerStyleImage(projectId, dancer.getId(), cropped.get());
                    if (savedStyle == null) {
                        errorHandler.show("样式保存失败", "无法保存裁剪后的样式图片");
                        return;
                    }

                    pendingStylePath[0] = savedStyle.getAbsolutePath();
                    stylePathLabel.setText(savedStyle.getName());
                    refreshStylePreview(stylePreview, pendingStylePath[0]);
                    clearStyleBtn.setDisable(false);
                    previewStyleBtn.setDisable(false);
                } catch (Exception ex) {
                    errorHandler.show("样式上传失败", ex.getMessage() == null ? "未知错误" : ex.getMessage());
                }
            });

            clearStyleBtn.setOnAction(e -> {
                pendingStylePath[0] = null;
                stylePathLabel.setText("未设置样式");
                refreshStylePreview(stylePreview, null);
                clearStyleBtn.setDisable(true);
                previewStyleBtn.setDisable(true);
            });

            previewStyleBtn.setOnAction(e -> {
                Window owner = dialog.getDialogPane().getScene() != null
                    ? dialog.getDialogPane().getScene().getWindow()
                    : null;
                showStylePreview(pendingStylePath[0], owner);
            });

            styleButtonRow.getChildren().addAll(uploadStyleBtn, clearStyleBtn, previewStyleBtn);

            content.getChildren().addAll(nameBox, colorLabel, colorBox, styleLabel, stylePreviewRow, styleButtonRow);
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            Optional<ButtonType> result = dialog.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                String name = nameField.getText();
                int colorWithAlpha = 0xFF000000 | selectedColor[0];

                dancer.setName(name);
                dancer.setColor(colorWithAlpha);

                if (pendingStylePath[0] == null || pendingStylePath[0].isBlank()) {
                    dataManager.deleteDancerStyleImage(viewModel.getCurrentGroup().getId(), dancer.getId());
                }
                viewModel.updateDancerStyle(dancer.getId(), pendingStylePath[0]);

                dancerListUpdater.run();
                stageView.render();
                statusUpdater.accept("舞者信息已更新");
            }
            break;
        }
    }

    private void refreshStylePreview(ImageView stylePreview, String stylePath) {
        if (stylePath == null || stylePath.isBlank()) {
            stylePreview.setImage(null);
            stylePreview.setOpacity(0.3);
            return;
        }

        File file = new File(stylePath);
        if (!file.exists() || !file.isFile()) {
            stylePreview.setImage(null);
            stylePreview.setOpacity(0.3);
            return;
        }

        stylePreview.setImage(new Image(file.toURI().toString(), true));
        stylePreview.setOpacity(1.0);
    }

    private void showStylePreview(String stylePath, Window owner) {
        if (stylePath == null || stylePath.isBlank()) {
            errorHandler.show("预览失败", "当前舞者未设置样式");
            return;
        }

        File file = new File(stylePath);
        if (!file.exists() || !file.isFile()) {
            errorHandler.show("预览失败", "样式文件不存在");
            return;
        }

        ImageView imageView = new ImageView(new Image(file.toURI().toString(), true));
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(420);
        imageView.setFitHeight(420);

        Alert previewAlert = new Alert(Alert.AlertType.NONE);
        previewAlert.setTitle("样式预览");
        previewAlert.setHeaderText(null);
        previewAlert.getDialogPane().setContent(new VBox(imageView));
        previewAlert.getButtonTypes().add(ButtonType.CLOSE);
        if (owner != null) {
            previewAlert.initOwner(owner);
        }
        previewAlert.showAndWait();
    }

    private File chooseStyleImage(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择样式图片");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp")
        );
        return chooser.showOpenDialog(owner);
    }

    public void showStageSizeDialog() {
        if (viewModel.getCurrentGroup() == null) {
            errorHandler.show("设置失败", "请先创建或打开一个工程");
            return;
        }

        DanceGroup group = viewModel.getCurrentGroup();
        float currentWidth = group.getStageWidth();
        float currentDepth = group.getStageDepth();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("舞台设置");
        dialog.setHeaderText("设置舞台尺寸（米）");

        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        Label widthLabel = new Label("宽度（米）：");
        TextField widthField = new TextField(String.valueOf(currentWidth));
        widthField.setPromptText("请输入舞台宽度");

        Label depthLabel = new Label("深度（米）：");
        TextField depthField = new TextField(String.valueOf(currentDepth));
        depthField.setPromptText("请输入舞台深度");

        Label hintLabel = new Label("建议尺寸：16米 x 12米");
        hintLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        grid.add(widthLabel, 0, 0);
        grid.add(widthField, 1, 0);
        grid.add(depthLabel, 0, 1);
        grid.add(depthField, 1, 1);
        grid.add(hintLabel, 1, 2);
        javafx.scene.control.CheckBox backstageSwitch = new javafx.scene.control.CheckBox("显示左右台下区域");
        backstageSwitch.setSelected(group.isBackstageVisible());
        backstageSwitch.setStyle("-fx-text-fill: white;");
        grid.add(backstageSwitch, 1, 3);

        dialog.getDialogPane().setContent(grid);

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.setStyle("-fx-background-color: #1E1E2E;");
        dialogPane.lookup(".header-panel").setStyle("-fx-background-color: #1E1E2E;");
        widthLabel.setStyle("-fx-text-fill: white;");
        depthLabel.setStyle("-fx-text-fill: white;");

        Platform.runLater(widthField::requestFocus);

        Optional<ButtonType> result = dialog.showAndWait();

        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                float newWidth = Float.parseFloat(widthField.getText());
                float newDepth = Float.parseFloat(depthField.getText());

                if (newWidth <= 0 || newDepth <= 0) {
                    errorHandler.show("输入错误", "舞台尺寸必须大于0");
                    return;
                }

                if (newWidth > 100 || newDepth > 100) {
                    errorHandler.show("输入错误", "舞台尺寸过大，请输入合理的尺寸（小于100米）");
                    return;
                }

                group.setStageWidth(newWidth);
                group.setStageDepth(newDepth);

                viewModel.setBackstageVisible(backstageSwitch.isSelected());
                stageView.updateStageSize(newWidth, newDepth);
                dataManager.saveProject(group);

                statusUpdater.accept(String.format("舞台尺寸已设置为: %.1f米 x %.1f米", newWidth, newDepth));

            } catch (NumberFormatException e) {
                errorHandler.show("输入错误", "请输入有效的数字");
            }
        }
    }

    public void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle("关于舞形工坊");
        alert.setHeaderText(null);

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: #1E1E2E;");

        Label titleLabel = new Label("舞形工坊 v" + AppVersion.VERSION);
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1976D2;");

        Label subtitleLabel = new Label("队形编排工具 - Windows桌面版");
        subtitleLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #AAAAAA;");

        Label organizationLabel = new Label("舞形工坊");
        organizationLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label authorLabel = new Label("雪落出品");
        authorLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: white;");

        Label freeLabel = new Label("完全免费，谨防诈骗");
        freeLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #FF5555;");

        Label descriptionLabel = new Label(
            "本软件是为舞蹈队形编排设计的工具，\n" +
            "支持音视频导入、关键帧编辑、视频导出等功能。\n\n" +
            "官网: https://dfworkshop.top/\n" +
            "QQ群: 895779897\n"
        );
        descriptionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #CCCCCC; -fx-text-alignment: center;");
        descriptionLabel.setTextAlignment(TextAlignment.CENTER);
        descriptionLabel.setWrapText(true);
        descriptionLabel.setMaxWidth(400);

        javafx.scene.control.Separator separator1 = new javafx.scene.control.Separator();
        javafx.scene.control.Separator separator2 = new javafx.scene.control.Separator();

        content.getChildren().addAll(
            titleLabel,
            subtitleLabel,
            separator1,
            organizationLabel,
            authorLabel,
            separator2,
            freeLabel,
            descriptionLabel
        );
        content.setAlignment(Pos.CENTER);

        alert.getDialogPane().setContent(content);

        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setStyle("-fx-background-color: #1E1E2E;");

        alert.getButtonTypes().add(ButtonType.OK);
        dialogPane.lookupButton(ButtonType.OK).setStyle(
            "-fx-background-color: #1976D2; -fx-text-fill: white; -fx-font-weight: bold;"
        );

        alert.showAndWait();
    }

    public void showFormationPresetsDialog() {
        DanceGroup group = viewModel.getCurrentGroup();
        if (group == null || group.getFormations().isEmpty()) return;
        TextInputDialog dialog = new TextInputDialog(String.valueOf(group.getDancerCount()));
        dialog.setTitle("设置人数");
        dialog.setHeaderText("输入总人数（1–200），保留已有人员姓名、颜色和站位");
        dialog.setContentText("人数：");
        dialog.showAndWait().ifPresent(value -> {
            try {
                int count = Integer.parseInt(value.trim());
                if (count < 1 || count > 200) throw new NumberFormatException();
                List<Dancer> dancers = group.getFormations().get(0).getDancers();
                if (count < dancers.size()) {
                    String names = dancers.subList(count, dancers.size()).stream().map(Dancer::getName)
                        .collect(java.util.stream.Collectors.joining("、"));
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "将从所有队形移除：" + names + "。可撤销。", ButtonType.OK, ButtonType.CANCEL);
                    confirm.setHeaderText("减少人员");
                    if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                }
                viewModel.setDancerCount(count);
                dancerListUpdater.run();
                stageView.render();
                statusUpdater.accept("已设置为 " + count + " 人");
            } catch (NumberFormatException error) {
                new Alert(Alert.AlertType.WARNING, "请输入 1–200 的整数。").showAndWait();
            }
        });
    }

}
