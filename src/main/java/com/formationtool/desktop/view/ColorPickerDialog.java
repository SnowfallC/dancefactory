package com.formationtool.desktop.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

import java.util.Optional;

/**
 * 类似PS的颜色选择器对话框
 */
public class ColorPickerDialog extends Dialog<Color> {

    private Canvas hueCanvas;
    private Canvas satValCanvas;
    private Circle hueIndicator;
    private Circle satValIndicator;
    private Rectangle previewRect;

    private double[] currentHSV = {0.0, 1.0, 1.0};  // Hue, Saturation, Value
    private Color currentColor = Color.RED;

    private static final double HUE_CANVAS_SIZE = 200;
    private static final double SAT_VAL_CANVAS_WIDTH = 260;
    private static final double SAT_VAL_CANVAS_HEIGHT = 200;

    public ColorPickerDialog(Color initialColor) {
        setTitle("选择颜色");
        setHeaderText("调色盘");

        // 设置深色主题
        DialogPane dialogPane = getDialogPane();
        dialogPane.setStyle("-fx-background-color: #1E1E2E;");
        if (dialogPane.lookup(".header-panel") != null) {
            dialogPane.lookup(".header-panel").setStyle("-fx-background-color: #1E1E2E;");
        }

        // 创建主容器
        VBox mainContent = new VBox(15);
        mainContent.setPadding(new Insets(20));
        mainContent.setStyle("-fx-background-color: #1E1E2E;");
        mainContent.setAlignment(Pos.CENTER);

        // 创建色环（上方）
        StackPane huePane = createHueCircle();

        // 创建饱和度/明度选择器（下方）
        StackPane satValPane = createSatValRect();

        // 创建预览和RGB值显示
        VBox previewBox = createPreviewBox();

        mainContent.getChildren().addAll(huePane, satValPane, previewBox);

        dialogPane.setContent(mainContent);
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // 设置结果转换器
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                return currentColor;
            }
            return null;
        });

        // 设置初始颜色（在Canvas创建之后）
        if (initialColor != null) {
            setColor(initialColor);
        } else {
            // 初始绘制
            drawHueCircle();
            drawSatValRect();
            updatePreview();
        }
    }

    private StackPane createHueCircle() {
        StackPane pane = new StackPane();
        pane.setPrefSize(HUE_CANVAS_SIZE, HUE_CANVAS_SIZE);

        hueCanvas = new Canvas(HUE_CANVAS_SIZE, HUE_CANVAS_SIZE);
        hueIndicator = new Circle(6, Color.WHITE);
        hueIndicator.setStroke(Color.BLACK);
        hueIndicator.setStrokeWidth(2);

        pane.getChildren().addAll(hueCanvas, hueIndicator);

        // 鼠标事件
        hueCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::handleHueClick);
        hueCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::handleHueClick);

        return pane;
    }

    private StackPane createSatValRect() {
        StackPane pane = new StackPane();
        pane.setPrefSize(SAT_VAL_CANVAS_WIDTH, SAT_VAL_CANVAS_HEIGHT);

        satValCanvas = new Canvas(SAT_VAL_CANVAS_WIDTH, SAT_VAL_CANVAS_HEIGHT);
        satValIndicator = new Circle(6, Color.TRANSPARENT);
        satValIndicator.setStroke(Color.WHITE);
        satValIndicator.setStrokeWidth(2);

        pane.getChildren().addAll(satValCanvas, satValIndicator);

        // 鼠标事件
        satValCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::handleSatValClick);
        satValCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::handleSatValClick);

        return pane;
    }

    private VBox createPreviewBox() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);

        Label previewLabel = new Label("颜色预览:");
        previewLabel.setTextFill(Color.WHITE);

        previewRect = new Rectangle(100, 40);
        previewRect.setStroke(Color.WHITE);
        previewRect.setStrokeWidth(1);

        box.getChildren().addAll(previewLabel, previewRect);
        return box;
    }

    private void drawHueCircle() {
        GraphicsContext gc = hueCanvas.getGraphicsContext2D();
        double centerX = HUE_CANVAS_SIZE / 2;
        double centerY = HUE_CANVAS_SIZE / 2;
        double radius = HUE_CANVAS_SIZE / 2 - 5;

        // 设置线条宽度
        gc.setLineWidth(2.5);

        // 绘制色环
        for (int angle = 0; angle < 360; angle++) {
            gc.setStroke(Color.hsb(angle, 1.0, 1.0));  // 使用setStroke而不是setFill
            double radians = Math.toRadians(angle);
            double x1 = centerX + Math.cos(radians) * (radius - 20);
            double y1 = centerY + Math.sin(radians) * (radius - 20);
            double x2 = centerX + Math.cos(radians) * radius;
            double y2 = centerY + Math.sin(radians) * radius;
            gc.strokeLine(x1, y1, x2, y2);
        }

        updateHueIndicator();
    }

    private void drawSatValRect() {
        GraphicsContext gc = satValCanvas.getGraphicsContext2D();

        // 创建饱和度渐变（左到右：白色到当前色调的纯色）
        Color hueColor = Color.hsb(currentHSV[0], 1.0, 1.0);

        for (int x = 0; x < SAT_VAL_CANVAS_WIDTH; x++) {
            for (int y = 0; y < SAT_VAL_CANVAS_HEIGHT; y++) {
                double sat = x / SAT_VAL_CANVAS_WIDTH;
                double val = 1.0 - (y / SAT_VAL_CANVAS_HEIGHT);

                Color color = Color.hsb(currentHSV[0], sat, val);
                gc.getPixelWriter().setColor(x, y, color);
            }
        }

        updateSatValIndicator();
    }

    private void handleHueClick(MouseEvent event) {
        double centerX = HUE_CANVAS_SIZE / 2;
        double centerY = HUE_CANVAS_SIZE / 2;
        double dx = event.getX() - centerX;
        double dy = event.getY() - centerY;

        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) angle += 360;

        currentHSV[0] = angle;
        updateColor();
        drawSatValRect();  // 重绘饱和度/明度区域
    }

    private void handleSatValClick(MouseEvent event) {
        double x = event.getX();
        double y = event.getY();

        currentHSV[1] = Math.max(0, Math.min(1, x / SAT_VAL_CANVAS_WIDTH));
        currentHSV[2] = Math.max(0, Math.min(1, 1.0 - y / SAT_VAL_CANVAS_HEIGHT));

        updateColor();
    }

    private void updateHueIndicator() {
        double centerX = HUE_CANVAS_SIZE / 2;
        double centerY = HUE_CANVAS_SIZE / 2;
        double radius = HUE_CANVAS_SIZE / 2 - 10;
        double radians = Math.toRadians(currentHSV[0]);

        double x = centerX + Math.cos(radians) * radius;
        double y = centerY + Math.sin(radians) * radius;

        hueIndicator.setTranslateX(x - centerX);
        hueIndicator.setTranslateY(y - centerY);
    }

    private void updateSatValIndicator() {
        double x = currentHSV[1] * SAT_VAL_CANVAS_WIDTH;
        double y = (1.0 - currentHSV[2]) * SAT_VAL_CANVAS_HEIGHT;

        satValIndicator.setTranslateX(x - SAT_VAL_CANVAS_WIDTH / 2);
        satValIndicator.setTranslateY(y - SAT_VAL_CANVAS_HEIGHT / 2);
    }

    private void updateColor() {
        currentColor = Color.hsb(currentHSV[0], currentHSV[1], currentHSV[2]);
        updateHueIndicator();
        updateSatValIndicator();
        updatePreview();
    }

    private void updatePreview() {
        previewRect.setFill(currentColor);
    }

    private void setColor(Color color) {
        double hue = color.getHue();
        double sat = color.getSaturation();
        double val = color.getBrightness();

        currentHSV[0] = Double.isNaN(hue) ? 0 : hue;
        currentHSV[1] = sat;
        currentHSV[2] = val;

        currentColor = color;

        // 重绘所有组件以匹配新颜色
        drawHueCircle();
        drawSatValRect();
        updateHueIndicator();
        updateSatValIndicator();
        updatePreview();
    }

    public Color getSelectedColor() {
        return currentColor;
    }
}
