package com.formationtool.desktop.view;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * 导出视频入点/出点选择条（PC端JavaFX版）
 * 显示在波形上方，可以拖动入点和出点标记
 */
public class ExportRangeBar extends HBox {

    private final Canvas canvas;
    private final Label inPointLabel;
    private final Label outPointLabel;

    // 入点和出点时间（毫秒）
    private long inPointMs = 0L;
    private long outPointMs = 0L;

    // 音频总时长
    private long audioDuration = 0L;

    // 当前可见视口范围（毫秒）
    private long viewportStartMs = 0L;
    private long viewportEndMs = 0L;

    // 回调接口
    public interface RangeChangeListener {
        void onRangeChanged(long inMs, long outMs);
    }

    private RangeChangeListener onRangeChanged;

    // 拖动状态
    private boolean isDraggingInPoint = false;
    private boolean isDraggingOutPoint = false;
    private double dragStartX = 0.0;
    private static final double TOUCH_TOLERANCE = 20.0;  // 触摸容差（像素）

    public ExportRangeBar() {
        setSpacing(0);
        setStyle("-fx-background-color: transparent; -fx-padding: 0;");  // 透明背景
        setPrefHeight(30);
        setMinHeight(30);
        setMaxHeight(30);

        // 入点标签
        inPointLabel = new Label("入点: 00:00");
        inPointLabel.setTextFill(Color.rgb(76, 175, 80));  // 绿色
        inPointLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 0 4 0 4;");

        // 画布
        canvas = new Canvas(800, 30);  // 初始宽度
        canvas.widthProperty().addListener((obs, oldW, newW) -> render());
        setupMouseEvents();

        // 出点标签
        outPointLabel = new Label("出点: 00:00");
        outPointLabel.setTextFill(Color.rgb(244, 67, 54));  // 红色
        outPointLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 0 4 0 4;");

        // 标签作为覆盖层，不参与横向宽度分配，保证画布与波形同宽
        StackPane canvasContainer = new StackPane();
        canvasContainer.setPrefHeight(30);
        canvasContainer.setMinHeight(30);
        canvasContainer.setMaxHeight(30);

        BorderPane labelOverlay = new BorderPane();
        labelOverlay.setMouseTransparent(true);
        labelOverlay.setPickOnBounds(false);
        labelOverlay.setLeft(inPointLabel);
        labelOverlay.setRight(outPointLabel);
        labelOverlay.setPadding(new Insets(0, 6, 0, 6));

        canvasContainer.getChildren().addAll(canvas, labelOverlay);

        getChildren().add(canvasContainer);
        HBox.setHgrow(canvasContainer, Priority.ALWAYS);

        // 监听ExportRangeBar自身宽度变化，确保画布宽度与组件宽度一致
        widthProperty().addListener((obs, oldWidth, newWidth) -> {
            double w = newWidth.doubleValue();
            if (w > 0) {
                canvas.setWidth(w);
            }
        });
    }

    private void setupMouseEvents() {
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnMouseReleased(this::handleMouseReleased);
    }

    private void handleMousePressed(MouseEvent event) {
        if (audioDuration <= 0) return;

        double x = event.getX();
        double width = canvas.getWidth();
        long viewportStart = getViewportStartMs();
        long viewportEnd = getViewportEndMs();

        // 计算入点和出点的X坐标
        double inPointX = timeToCanvasX(inPointMs, width);
        double outPointX = timeToCanvasX(outPointMs, width);
        boolean isInPointVisible = inPointMs >= viewportStart && inPointMs <= viewportEnd;
        boolean isOutPointVisible = outPointMs >= viewportStart && outPointMs <= viewportEnd;

        // 检测是否点击到标记
        if (isInPointVisible && Math.abs(x - inPointX) < TOUCH_TOLERANCE) {
            isDraggingInPoint = true;
            dragStartX = x;
        } else if (isOutPointVisible && Math.abs(x - outPointX) < TOUCH_TOLERANCE) {
            isDraggingOutPoint = true;
            dragStartX = x;
        }
    }

    private void handleMouseDragged(MouseEvent event) {
        if (!isDraggingInPoint && !isDraggingOutPoint) return;
        if (audioDuration <= 0) return;

        double x = event.getX();
        double width = canvas.getWidth();

        // 计算新的时间
        long newTimeMs = canvasXToTime(x, width);
        newTimeMs = Math.max(0, Math.min(audioDuration, newTimeMs));

        if (isDraggingInPoint) {
            inPointMs = Math.min(newTimeMs, outPointMs - 100);  // 至少间隔0.1秒
            updateLabels();
            render();
            if (onRangeChanged != null) {
                onRangeChanged.onRangeChanged(inPointMs, outPointMs);
            }
        } else if (isDraggingOutPoint) {
            outPointMs = Math.max(newTimeMs, inPointMs + 100);  // 至少间隔0.1秒
            updateLabels();
            render();
            if (onRangeChanged != null) {
                onRangeChanged.onRangeChanged(inPointMs, outPointMs);
            }
        }
    }

    private void handleMouseReleased(MouseEvent event) {
        isDraggingInPoint = false;
        isDraggingOutPoint = false;
    }

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();

        // 清空背景（透明）
        gc.clearRect(0, 0, width, height);

        if (audioDuration <= 0) {
            // 显示提示
            gc.setFill(Color.rgb(170, 170, 170));
            gc.setFont(javafx.scene.text.Font.font(12));
            gc.fillText("导入音频后可设置导出范围", width / 2 - 80, height / 2 + 4);
            return;
        }

        // 计算入点和出点位置
        long viewportStart = getViewportStartMs();
        long viewportEnd = getViewportEndMs();
        long visibleRangeStart = Math.max(inPointMs, viewportStart);
        long visibleRangeEnd = Math.min(outPointMs, viewportEnd);

        // 绘制选中范围（仅绘制视口内可见部分）
        if (visibleRangeEnd > visibleRangeStart) {
            double rangeStartX = timeToCanvasX(visibleRangeStart, width);
            double rangeEndX = timeToCanvasX(visibleRangeEnd, width);
            gc.setFill(Color.rgb(25, 118, 210, 0.25));
            gc.fillRect(rangeStartX, 0, rangeEndX - rangeStartX, height);
        }

        // 绘制入点标记（绿色，仅视口内显示）
        if (inPointMs >= viewportStart && inPointMs <= viewportEnd) {
            double inPointX = timeToCanvasX(inPointMs, width);
            drawMarker(gc, inPointX, height, Color.rgb(76, 175, 80));
        }

        // 绘制出点标记（红色，仅视口内显示）
        if (outPointMs >= viewportStart && outPointMs <= viewportEnd) {
            double outPointX = timeToCanvasX(outPointMs, width);
            drawMarker(gc, outPointX, height, Color.rgb(244, 67, 54));
        }
    }

    private void drawMarker(GraphicsContext gc, double x, double height, Color color) {
        // 垂直线（从顶部到底部，与滑块一样长）
        gc.setStroke(color);
        gc.setLineWidth(3);
        gc.strokeLine(x, 0, x, height);

        // 底部三角形（指向下方滑块）
        gc.setFill(color);
        double triangleSize = 10;
        gc.fillPolygon(
            new double[]{x, x - triangleSize, x + triangleSize},
            new double[]{height, height - triangleSize, height - triangleSize},
            3
        );
    }

    private void updateLabels() {
        inPointLabel.setText("入点: " + formatTime(inPointMs));
        outPointLabel.setText("出点: " + formatTime(outPointMs));
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private long getViewportStartMs() {
        if (audioDuration <= 0) return 0L;
        long maxStart = Math.max(0L, audioDuration - 1);
        return Math.max(0L, Math.min(maxStart, viewportStartMs));
    }

    private long getViewportEndMs() {
        if (audioDuration <= 0) return 0L;
        long start = getViewportStartMs();
        long end = viewportEndMs > 0 ? viewportEndMs : audioDuration;
        return Math.max(start + 1, Math.min(audioDuration, end));
    }

    private double timeToCanvasX(long timeMs, double width) {
        if (audioDuration <= 0 || width <= 0) return 0;
        long viewportStart = getViewportStartMs();
        long viewportEnd = getViewportEndMs();
        double visibleDuration = viewportEnd - viewportStart;
        if (visibleDuration <= 0) return 0;
        return ((timeMs - viewportStart) / visibleDuration) * width;
    }

    private long canvasXToTime(double x, double width) {
        if (audioDuration <= 0 || width <= 0) return 0L;
        long viewportStart = getViewportStartMs();
        long viewportEnd = getViewportEndMs();
        double visibleDuration = viewportEnd - viewportStart;
        if (visibleDuration <= 0) return viewportStart;

        double safeX = Math.max(0, Math.min(width, x));
        return viewportStart + (long) ((safeX / width) * visibleDuration);
    }

    // Setters
    public void setInPointMs(long ms) {
        this.inPointMs = Math.max(0, Math.min(audioDuration, ms));
        updateLabels();
        render();
    }

    public void setOutPointMs(long ms) {
        this.outPointMs = Math.max(0, Math.min(audioDuration, ms));
        updateLabels();
        render();
    }

    public void setAudioDuration(long ms) {
        this.audioDuration = ms;
        if (outPointMs == 0 || outPointMs > ms) {
            outPointMs = ms;
        }
        viewportStartMs = 0L;
        viewportEndMs = ms;
        updateLabels();
        render();
    }

    public void setViewportRange(long startMs, long endMs) {
        if (audioDuration <= 0) {
            viewportStartMs = 0L;
            viewportEndMs = 0L;
            render();
            return;
        }

        long maxStart = Math.max(0L, audioDuration - 1);
        long safeStart = Math.max(0L, Math.min(startMs, maxStart));
        long safeEnd = Math.max(safeStart + 1, Math.min(endMs, audioDuration));

        if (safeStart == viewportStartMs && safeEnd == viewportEndMs) {
            return;
        }

        viewportStartMs = safeStart;
        viewportEndMs = safeEnd;
        render();
    }

    public void setOnRangeChanged(RangeChangeListener listener) {
        this.onRangeChanged = listener;
    }

    // Getters
    public long getInPointMs() {
        return inPointMs;
    }

    public long getOutPointMs() {
        return outPointMs;
    }
}
