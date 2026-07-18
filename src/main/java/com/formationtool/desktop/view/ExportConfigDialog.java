package com.formationtool.desktop.view;

import com.formationtool.desktop.model.ExportConfig;
import com.formationtool.desktop.model.ExportConfig.*;
import com.formationtool.desktop.model.ExportConfig.StageOrientation;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;

/**
 * 导出配置对话框
 * 简洁高效的导出设置界面，带实时预览框
 */
public class ExportConfigDialog {

    private final Stage dialog;
    private final ExportConfig config;
    private boolean confirmed = false;
    private final FormationViewModel viewModel;
    private final StageView stageView;
    private final boolean initialStageFlipped;

    // UI 组件
    private Canvas previewCanvas;
    private ToggleGroup layoutGroup;
    private ToggleGroup primaryViewGroup;
    private ToggleGroup secondaryViewGroup;
    private ToggleGroup orientationGroup;  // 方向选择
    private Slider scaleSlider;
    private Label scaleLabel;
    private VBox secondaryViewBox;
    private Label resolutionLabel;

    public ExportConfigDialog(Stage owner, boolean hasReferenceVideo, FormationViewModel viewModel, StageView stageView) {
        this.config = new ExportConfig();
        this.config.setHasReferenceVideo(hasReferenceVideo);
        this.viewModel = viewModel;
        this.stageView = stageView;
        this.initialStageFlipped = stageView.isFlipped();
        this.config.setStageOrientation(initialStageFlipped ? StageOrientation.FLIPPED : StageOrientation.NORMAL);

        dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UTILITY);
        dialog.setTitle("导出设置");
        dialog.setResizable(true);  // 允许调整大小

        initUI(hasReferenceVideo);
    }

    private void initUI(boolean hasReferenceVideo) {
        HBox root = new HBox(20);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #1E1E2E;");

        // 左侧：预览区
        VBox previewSection = createPreviewSection();

        // 右侧：设置区
        VBox settingsSection = createSettingsSection(hasReferenceVideo);

        root.getChildren().addAll(previewSection, settingsSection);

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        dialog.setScene(scene);

        // 设置对话框尺寸，确保内容完整显示
        dialog.setMinWidth(600);
        dialog.setMinHeight(550);

        // 初始化预览
        updatePreview();
    }

    /**
     * 创建预览区域
     */
    private VBox createPreviewSection() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.TOP_CENTER);

        Label titleLabel = new Label("输出预览");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        titleLabel.setTextFill(Color.LIGHTGRAY);

        // 预览画布 (16:9 比例，会根据布局模式调整)
        previewCanvas = new Canvas(240, 135);
        previewCanvas.setStyle("-fx-background-color: #1A1A1F;");

        StackPane canvasContainer = new StackPane(previewCanvas);
        canvasContainer.setStyle(
            "-fx-background-color: #1A1A1F;" +
            "-fx-border-color: #3A3A4F;" +
            "-fx-border-width: 2;" +
            "-fx-border-radius: 4;"
        );
        canvasContainer.setPadding(new Insets(2));

        // 分辨率标签
        resolutionLabel = new Label("1920 \u00d7 1080");
        resolutionLabel.setTextFill(Color.GRAY);
        resolutionLabel.setFont(Font.font(11));

        box.getChildren().addAll(titleLabel, canvasContainer, resolutionLabel);
        return box;
    }

    /**
     * 创建设置区域
     */
    private VBox createSettingsSection(boolean hasReferenceVideo) {
        VBox box = new VBox(12);
        box.setPrefWidth(280);

        // 1. 布局模式
        VBox layoutCard = createCard("布局模式", createLayoutGroup());

        // 2. 主视图选择
        VBox primaryViewCard = createCard("主视图", createPrimaryViewGroup(hasReferenceVideo));

        // 3. 次视图选择（双屏模式时显示）
        secondaryViewBox = createCard("次视图", createSecondaryViewGroup(hasReferenceVideo));
        secondaryViewBox.setVisible(false);
        secondaryViewBox.setManaged(false);

        // 4. 舞台方向选择
        VBox orientationCard = createCard("导出方向", createOrientationGroup());

        // 5. 视角缩放
        VBox scaleCard = createCard("视角范围", createScaleControl());

        // 6. 按钮栏
        HBox buttonBox = createButtonBar();

        box.getChildren().addAll(layoutCard, primaryViewCard, secondaryViewBox, orientationCard, scaleCard, buttonBox);
        return box;
    }

    /**
     * 创建卡片容器
     */
    private VBox createCard(String title, Region content) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(10));
        card.setStyle(
            "-fx-background-color: #2A2A3F;" +
            "-fx-background-radius: 6;" +
            "-fx-border-color: #3A3A4F;" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 6;"
        );

        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        titleLabel.setTextFill(Color.LIGHTGRAY);

        card.getChildren().addAll(titleLabel, content);
        return card;
    }

    /**
     * 创建布局模式选择
     */
    private HBox createLayoutGroup() {
        layoutGroup = new ToggleGroup();

        ToggleButton btnSingle = createToggleButton("单屏", layoutGroup);
        ToggleButton btnHorizontal = createToggleButton("左右", layoutGroup);
        ToggleButton btnVertical = createToggleButton("上下", layoutGroup);

        btnSingle.setSelected(true);
        btnSingle.setUserData(LayoutMode.SINGLE);
        btnHorizontal.setUserData(LayoutMode.SPLIT_HORIZONTAL);
        btnVertical.setUserData(LayoutMode.SPLIT_VERTICAL);

        layoutGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                config.setLayoutMode((LayoutMode) newVal.getUserData());
                updateSecondaryViewVisibility();
                updateResolutionLabel();
                updatePreview();
            }
        });

        HBox box = new HBox(8, btnSingle, btnHorizontal, btnVertical);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * 创建主视图选择
     */
    private HBox createPrimaryViewGroup(boolean hasReferenceVideo) {
        primaryViewGroup = new ToggleGroup();

        ToggleButton btn2D = createToggleButton("2D", primaryViewGroup);
        ToggleButton btn3D = createToggleButton("3D", primaryViewGroup);
        ToggleButton btnVideo = createToggleButton("视频", primaryViewGroup);

        btn2D.setSelected(true);
        btn2D.setUserData(ViewType.VIEW_2D);
        btn3D.setUserData(ViewType.VIEW_3D);
        btnVideo.setUserData(ViewType.REFERENCE_VIDEO);

        // 电脑端禁用3D导出（只支持2D Canvas渲染）
        btn3D.setDisable(true);
        btn3D.setOpacity(0.5);

        // 没有参考视频时禁用视频选项
        btnVideo.setDisable(!hasReferenceVideo);
        if (!hasReferenceVideo) {
            btnVideo.setOpacity(0.5);
        }

        primaryViewGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                config.setPrimaryView((ViewType) newVal.getUserData());
                updatePreview();
            }
        });

        HBox box = new HBox(8, btn2D, btn3D, btnVideo);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * 创建次视图选择
     */
    private HBox createSecondaryViewGroup(boolean hasReferenceVideo) {
        secondaryViewGroup = new ToggleGroup();

        ToggleButton btn2D = createToggleButton("2D", secondaryViewGroup);
        ToggleButton btn3D = createToggleButton("3D", secondaryViewGroup);
        ToggleButton btnVideo = createToggleButton("视频", secondaryViewGroup);

        // 电脑端默认选择2D（因3D禁用）
        btn2D.setSelected(true);
        btn2D.setUserData(ViewType.VIEW_2D);
        btn3D.setUserData(ViewType.VIEW_3D);
        btnVideo.setUserData(ViewType.REFERENCE_VIDEO);

        config.setSecondaryView(ViewType.VIEW_2D);

        // 电脑端禁用3D导出（只支持2D Canvas渲染）
        btn3D.setDisable(true);
        btn3D.setOpacity(0.5);

        btnVideo.setDisable(!hasReferenceVideo);
        if (!hasReferenceVideo) {
            btnVideo.setOpacity(0.5);
        }

        secondaryViewGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                config.setSecondaryView((ViewType) newVal.getUserData());
                updatePreview();
            }
        });

        HBox box = new HBox(8, btn2D, btn3D, btnVideo);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * 创建舞台方向选择
     */
    private HBox createOrientationGroup() {
        orientationGroup = new ToggleGroup();

        ToggleButton btnKeepCurrent = createToggleButton("保持当前", orientationGroup);
        ToggleButton btnRotate180 = createToggleButton("旋转180°", orientationGroup);
        btnKeepCurrent.setPrefWidth(84);
        btnRotate180.setPrefWidth(92);

        btnKeepCurrent.setUserData(Boolean.FALSE);
        btnRotate180.setUserData(Boolean.TRUE);
        btnKeepCurrent.setSelected(true);

        // 默认与当前舞台方向保持一致
        config.setStageOrientation(initialStageFlipped ? StageOrientation.FLIPPED : StageOrientation.NORMAL);

        orientationGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                boolean rotate180 = Boolean.TRUE.equals(newVal.getUserData());
                boolean exportFlipped = rotate180 ? !initialStageFlipped : initialStageFlipped;
                config.setStageOrientation(exportFlipped ? StageOrientation.FLIPPED : StageOrientation.NORMAL);
                updatePreview();
            }
        });

        HBox box = new HBox(8, btnKeepCurrent, btnRotate180);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * 创建缩放控制
     */
    private HBox createScaleControl() {
        scaleSlider = new Slider(0.5, 2.0, 1.0);
        scaleSlider.setShowTickMarks(true);
        scaleSlider.setMajorTickUnit(0.5);
        scaleSlider.setPrefWidth(150);
        scaleSlider.setStyle("-fx-control-inner-background: #3A3A4F;");

        scaleLabel = new Label("100%");
        scaleLabel.setTextFill(Color.WHITE);
        scaleLabel.setMinWidth(45);

        scaleSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            double value = newVal.doubleValue();
            config.setViewScale(value);
            scaleLabel.setText(String.format("%.0f%%", value * 100));
            updatePreview();
        });

        Label minLabel = new Label("远");
        Label maxLabel = new Label("近");
        minLabel.setTextFill(Color.GRAY);
        maxLabel.setTextFill(Color.GRAY);
        minLabel.setFont(Font.font(10));
        maxLabel.setFont(Font.font(10));

        HBox box = new HBox(8, minLabel, scaleSlider, maxLabel, scaleLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * 创建切换按钮
     */
    private ToggleButton createToggleButton(String text, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setPrefWidth(60);
        btn.setStyle(
            "-fx-background-color: #3A3A4F;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 11px;" +
            "-fx-padding: 5 10 5 10;" +
            "-fx-background-radius: 4;"
        );

        btn.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                btn.setStyle(
                    "-fx-background-color: #1976D2;" +
                    "-fx-text-fill: white;" +
                    "-fx-font-size: 11px;" +
                    "-fx-padding: 5 10 5 10;" +
                    "-fx-background-radius: 4;"
                );
            } else {
                btn.setStyle(
                    "-fx-background-color: #3A3A4F;" +
                    "-fx-text-fill: white;" +
                    "-fx-font-size: 11px;" +
                    "-fx-padding: 5 10 5 10;" +
                    "-fx-background-radius: 4;"
                );
            }
        });

        return btn;
    }

    /**
     * 创建按钮栏
     */
    private HBox createButtonBar() {
        Button btnConfirm = new Button("确认导出");
        btnConfirm.setStyle(
            "-fx-background-color: #1976D2;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 13px;" +
            "-fx-padding: 8 20 8 20;" +
            "-fx-background-radius: 5;" +
            "-fx-cursor: hand;"
        );
        btnConfirm.setOnAction(e -> {
            confirmed = true;
            dialog.close();
        });

        Button btnCancel = new Button("取消");
        btnCancel.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-text-fill: #AAAAAA;" +
            "-fx-font-size: 13px;" +
            "-fx-padding: 8 20 8 20;" +
            "-fx-border-color: #AAAAAA;" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 5;" +
            "-fx-background-radius: 5;" +
            "-fx-cursor: hand;"
        );
        btnCancel.setOnAction(e -> dialog.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(10, spacer, btnCancel, btnConfirm);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(10, 0, 0, 0));
        return box;
    }

    /**
     * 更新次视图可见性
     */
    private void updateSecondaryViewVisibility() {
        boolean isSplit = config.isSplitMode();
        secondaryViewBox.setVisible(isSplit);
        secondaryViewBox.setManaged(isSplit);
    }

    /**
     * 更新分辨率标签和预览画布尺寸
     */
    private void updateResolutionLabel() {
        String resolution;
        double canvasWidth, canvasHeight;

        switch (config.getLayoutMode()) {
            case SPLIT_VERTICAL:
                resolution = "1080 \u00d7 1920";
                // 竖屏预览 (9:16 比例)
                canvasWidth = 135;
                canvasHeight = 240;
                break;
            case SINGLE:
            case SPLIT_HORIZONTAL:
            default:
                resolution = "1920 \u00d7 1080";
                // 横屏预览 (16:9 比例)
                canvasWidth = 240;
                canvasHeight = 135;
                break;
        }

        resolutionLabel.setText(resolution);
        previewCanvas.setWidth(canvasWidth);
        previewCanvas.setHeight(canvasHeight);
    }

    /**
     * 更新预览画布
     */
    private void updatePreview() {
        GraphicsContext gc = previewCanvas.getGraphicsContext2D();
        double w = previewCanvas.getWidth();
        double h = previewCanvas.getHeight();

        // 清空画布
        gc.setFill(Color.web("#1A1A1F"));
        gc.fillRect(0, 0, w, h);

        LayoutMode layout = config.getLayoutMode();
        ViewType primary = config.getPrimaryView();
        ViewType secondary = config.getSecondaryView();

        if (layout == LayoutMode.SINGLE) {
            // 单屏模式
            drawViewPreview(gc, 0, 0, w, h, primary);
        } else if (layout == LayoutMode.SPLIT_HORIZONTAL) {
            // 左右分屏
            double halfW = w / 2;
            drawViewPreview(gc, 0, 0, halfW - 1, h, primary);
            drawViewPreview(gc, halfW + 1, 0, halfW - 1, h, secondary);
            // 分割线
            gc.setStroke(Color.web("#3A3A4F"));
            gc.setLineWidth(2);
            gc.strokeLine(halfW, 0, halfW, h);
        } else if (layout == LayoutMode.SPLIT_VERTICAL) {
            // 上下分屏
            double halfH = h / 2;
            drawViewPreview(gc, 0, 0, w, halfH - 1, primary);
            drawViewPreview(gc, 0, halfH + 1, w, halfH - 1, secondary);
            // 分割线
            gc.setStroke(Color.web("#3A3A4F"));
            gc.setLineWidth(2);
            gc.strokeLine(0, halfH, w, halfH);
        }
    }

    /**
     * 绘制单个视图预览
     */
    private void drawViewPreview(GraphicsContext gc, double x, double y, double w, double h, ViewType viewType) {
        if (viewType == ViewType.REFERENCE_VIDEO) {
            // 参考视频显示为红色背景加文字
            Color bgColor = Color.web("#4A3A3A");
            String label = "视频";

            gc.setFill(bgColor);
            gc.fillRect(x, y, w, h);

            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("System", FontWeight.BOLD, 14));
            double textWidth = label.length() * 10;
            gc.fillText(label, x + (w - textWidth) / 2, y + h / 2 + 5);
        } else if (viewType == ViewType.VIEW_2D) {
            // 2D视角使用真实舞台截图
            try {
                // 保存当前缩放状态
                double originalScale = stageView.getViewScale();
                boolean originalFlipped = stageView.isFlipped();
                WritableImage snapshot;

                try {
                    // 应用配置中的缩放与翻转
                    stageView.setViewScale(config.getViewScale());
                    stageView.setFlipped(config.isFlipped());

                    // 截取舞台图像
                    SnapshotParameters params = new SnapshotParameters();
                    params.setFill(Color.TRANSPARENT);
                    snapshot = stageView.snapshot(params, null);
                } finally {
                    // 恢复原始状态，避免影响主界面视口
                    stageView.setViewScale(originalScale);
                    stageView.setFlipped(originalFlipped);
                }

                if (snapshot != null) {
                    // 绘制截图，保持宽高比
                    double imgW = snapshot.getWidth();
                    double imgH = snapshot.getHeight();
                    double scale = Math.min(w / imgW, h / imgH);
                    double scaledW = imgW * scale;
                    double scaledH = imgH * scale;
                    double offsetX = x + (w - scaledW) / 2;
                    double offsetY = y + (h - scaledH) / 2;

                    gc.drawImage(snapshot, offsetX, offsetY, scaledW, scaledH);

                    // 绘制标签
                    gc.setFill(Color.web("#000000", 0.5));
                    gc.fillRect(x + 5, y + 5, 30, 20);
                    gc.setFill(Color.WHITE);
                    gc.setFont(Font.font("System", FontWeight.BOLD, 12));
                    gc.fillText("2D", x + 10, y + 19);
                }
            } catch (Exception e) {
                // 截图失败，显示默认背景
                drawFallbackPreview(gc, x, y, w, h, "2D", Color.web("#2E4A3A"));
            }
        } else if (viewType == ViewType.VIEW_3D) {
            // 3D视角显示示意图（因为桂面是2D视图，无法3D渲染）
            Color bgColor = Color.web("#3A3A5E");

            gc.setFill(bgColor);
            gc.fillRect(x, y, w, h);

            // 绘制简单的透视示意图
            gc.setStroke(Color.web("#FFFFFF", 0.3));
            gc.setLineWidth(1);

            // 绘制一个透视网格效果
            double centerX = x + w / 2;
            double centerY = y + h / 2;
            double gridSize = Math.min(w, h) * 0.6;

            // 横线（从上到下逐渐变宽）
            for (int i = 0; i < 5; i++) {
                double ratio = i / 4.0;
                double lineY = centerY - gridSize / 2 + ratio * gridSize;
                double lineW = gridSize * (0.3 + ratio * 0.7); // 透视效果
                gc.strokeLine(centerX - lineW / 2, lineY, centerX + lineW / 2, lineY);
            }

            // 竖线
            for (int i = 0; i < 5; i++) {
                double ratio = (i - 2) / 2.0;
                double topW = gridSize * 0.3;
                double bottomW = gridSize;
                double x1 = centerX + ratio * topW;
                double x2 = centerX + ratio * bottomW;
                gc.strokeLine(x1, centerY - gridSize / 2, x2, centerY + gridSize / 2);
            }

            // 绘制标签
            gc.setFill(Color.web("#000000", 0.5));
            gc.fillRect(x + 5, y + 5, 50, 20);
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("System", FontWeight.BOLD, 12));
            gc.fillText("3D 45\u00b0", x + 10, y + 19);
        }
    }

    /**
     * 绘制备用预览（当截图失败时）
     */
    private void drawFallbackPreview(GraphicsContext gc, double x, double y, double w, double h, String label, Color bgColor) {
        gc.setFill(bgColor);
        gc.fillRect(x, y, w, h);

        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("System", FontWeight.BOLD, 14));
        double textWidth = label.length() * 10;
        gc.fillText(label, x + (w - textWidth) / 2, y + h / 2 + 5);
    }

    /**
     * 显示对话框并返回配置
     */
    public ExportConfig showAndWait() {
        dialog.showAndWait();
        return confirmed ? config : null;
    }
}
