package com.formationtool.desktop.view;

import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.Cursor;
import javafx.scene.shape.Line;

import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * 时间轴控制组件（包含波形和播放进度）
 */
public class TimelineView extends VBox {

    private final FormationViewModel viewModel;
    private final Canvas waveformCanvas;  // 波形画布（包含波形+区域+进度条）
    private final StackPane waveformPane;  // 波形容器（叠加黄色播放线）
    private final Line playheadOverlayLine;  // 黄色播放线覆盖层（独立于波形重绘）
    private final Slider timeSlider;
    private final Label timeLabel;
    private final Button resetButton;  // 归零按钮
    private final ExportRangeBar exportRangeBar;  // 导出范围选择条

    // 波形数据
    private float[] waveformData = new float[0];

    // 缩放和视口管理
    private double zoomLevel = 1.0;  // 缩放级别 1.0-15.0
    private double viewportStartTime = 0;  // 可见时间范围起始（毫秒）
    private double viewportEndTime = 0;    // 可见时间范围结束（毫秒）
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 15.0;
    private static final double ZOOM_STEP = 0.1;  // 滚轮缩放步进

    // 边界拖动状态
    private boolean isDraggingBoundary = false;
    private int draggingFormationIndex = -1;
    private boolean isDraggingStartBoundary = false;  // true=拖动绿色前边界(timestamp), false=拖动绿色后边界(holdDuration结束)
    private boolean isDraggingHoldBoundary = false;  // 兼容旧代码，同 isDraggingStartBoundary 取反
    private double dragStartX = 0;
    private long dragStartDuration = 0;
    private long dragStartTimestamp = 0;  // 拖动前边界时的原始 timestamp
    private boolean hasSavedUndoSnapshot = false;  // 标记是否已为当前拖动保存撤销快照

    // 进度条拖动状态
    private boolean isDraggingPlayhead = false;  // 是否正在拖动黄色进度条
    private double playheadDragLastX = 0;
    private static final double PLAYHEAD_EDGE_AUTO_SCROLL_PX = 24.0;  // 贴边自动平移阈值（像素）
    private long dragPreviewTimeMs = -1L;  // 拖动中的本地预览时间（优先于viewModel，保证跟手）
    private long lastDragLabelUpdateTimeMs = 0L;  // 拖动时上次更新时间文本的时间戳
    private long lastViewportRenderTimeMs = 0L;  // 拖动时上次视口重绘时间戳
    private static final long DRAG_LABEL_UPDATE_INTERVAL_MS = 80L;  // 拖动时时间文本刷新节流
    private static final long PLAYHEAD_VIEWPORT_RENDER_INTERVAL_MS = 50L;  // 拖动贴边平移时重绘节流

    // 缩放视口拖动状态（放大后按住空白区域拖动）
    private boolean isDraggingViewport = false;
    private double viewportDragStartX = 0;
    private double viewportDragStartTime = 0;
    private double viewportDragVisibleDuration = 0;

    // 框选功能状态
    private boolean isBoxSelecting = false;  // 是否正在框选
    private double boxSelectStartX = 0;  // 框选起始 X 坐标
    private double boxSelectCurrentX = 0;  // 框选当前 X 坐标
    private List<Integer> selectedKeyframeIndices = new java.util.ArrayList<>();  // 选中的关键帧索引

    // 边界点击容差（像素）
    private static final double BOUNDARY_TOLERANCE = 15.0;
    private static final double PLAYHEAD_TOLERANCE = 10.0;  // 进度条点击容差

    // F键标记临时区域（实时显示）
    private long fKeyMarkStartTime = -1;  // F键按下的开始时间，-1表示未按下
    private long fKeyMarkEndTime = -1;    // F键松开时的结束时间（红色区开始）
    private boolean isFKeyCurrentlyPressed = false;  // F键当前是否按下

    // 渲染节流控制（避免频繁重绘导致卡顿）
    private long lastRenderTime = 0;
    private static final long RENDER_THROTTLE_MS = 33;  // 约30fps，比AnimationTimer的~60fps低

    // 关键帧更新回调（用于通知外部更新UI）
    private Runnable onFormationsChanged;
    private LongConsumer onSeekRequested;  // 时间定位回调（用于同步音视频seek）
    private LongConsumer onPreviewSeekRequested;  // 兼容旧回调：拖动中的预览seek
    private Runnable onPreviewScrubStarted;  // 拖动开始回调
    private LongConsumer onPreviewScrubUpdated;  // 拖动更新回调（仅保留最新时间）
    private LongConsumer onPreviewScrubEnded;  // 拖动结束回调（最终定位）

    public TimelineView(FormationViewModel viewModel) {
        this.viewModel = viewModel;

        setSpacing(0);  // 移除间距，让波形和入出点条紧密相连
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #2A2A3F;");
        setPrefHeight(150);

        // 波形画布
        waveformCanvas = new Canvas(800, 80);
        waveformPane = new StackPane();
        waveformPane.setPrefHeight(80);
        waveformPane.setMinHeight(80);
        waveformPane.setMaxHeight(80);

        playheadOverlayLine = new Line();
        playheadOverlayLine.setStroke(Color.YELLOW);
        playheadOverlayLine.setStrokeWidth(3);
        playheadOverlayLine.setManaged(false);
        playheadOverlayLine.setMouseTransparent(true);
        playheadOverlayLine.setVisible(false);
        StackPane.setAlignment(waveformCanvas, Pos.TOP_LEFT);
        StackPane.setAlignment(playheadOverlayLine, Pos.TOP_LEFT);
        waveformPane.getChildren().addAll(waveformCanvas, playheadOverlayLine);

        // 导出范围选择条（波形正下方，三角形指向上方）
        exportRangeBar = new ExportRangeBar();
        exportRangeBar.setPrefHeight(30);  // 较小的高度，只显示三角形和选中区域
        exportRangeBar.setMinHeight(30);
        exportRangeBar.setMaxHeight(30);
        exportRangeBar.setOnRangeChanged((inMs, outMs) -> {
            // 存储范围，导出时使用
            System.out.println("Export range: " + inMs + " - " + outMs);
        });

        // 时间滑块
        timeSlider = new Slider(0, 100, 0);
        timeSlider.setBlockIncrement(1);
        timeSlider.setMinHeight(20);
        timeSlider.setPrefHeight(25);
        timeSlider.setStyle(
            "-fx-control-inner-background: #3A3A4F;" +
            "-fx-accent: #4A90D9;" +
            "-fx-background-color: transparent;"
        );
        HBox.setHgrow(timeSlider, Priority.ALWAYS);

        // 归零按钮
        resetButton = new Button("◀|");
        resetButton.setStyle(
            "-fx-background-color: #3A3A4F;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 12px;" +
            "-fx-padding: 5 10;" +
            "-fx-cursor: hand;"
        );
        resetButton.setOnAction(e -> {
            viewModel.setPlaying(false);
            setCurrentTimeFromTimeline(0L, true);
        });

        // 时间标签
        timeLabel = new Label("00:00 / 00:00");
        timeLabel.setTextFill(Color.WHITE);
        timeLabel.setMinWidth(100);

        HBox sliderBox = new HBox(10, resetButton, timeSlider, timeLabel);
        sliderBox.setAlignment(Pos.CENTER_LEFT);
        sliderBox.setMinHeight(35);
        sliderBox.setPadding(new Insets(5, 0, 0, 0));

        // 添加到VBox：波形 + 入出点条 + 进度条（三层独立）
        getChildren().addAll(waveformPane, exportRangeBar, sliderBox);

        // 监听 prefWidth 变化（绑定到 scene.widthProperty 时触发）
        prefWidthProperty().addListener((obs, oldWidth, newWidth) -> {
            double w = newWidth.doubleValue();
            if (w > 40) {
                double contentWidth = w - 20;
                if (Math.abs(waveformCanvas.getWidth() - contentWidth) < 1.0) return;
                waveformCanvas.setWidth(contentWidth);
                exportRangeBar.setPrefWidth(contentWidth);
                exportRangeBar.setMaxWidth(contentWidth);
                sliderBox.setPrefWidth(contentWidth);
                sliderBox.setMaxWidth(contentWidth);
                renderWaveform();
                updatePlayheadOverlay();
            }
        });

        widthProperty().addListener((obs, oldWidth, newWidth) -> {
            double w = newWidth.doubleValue();
            if (w > 40) {
                double contentWidth = w - 20;
                if (Math.abs(waveformCanvas.getWidth() - contentWidth) < 1.0) return;
                waveformCanvas.setWidth(contentWidth);
                exportRangeBar.setPrefWidth(contentWidth);
                exportRangeBar.setMaxWidth(contentWidth);
                sliderBox.setPrefWidth(contentWidth);
                sliderBox.setMaxWidth(contentWidth);
                renderWaveform();
                updatePlayheadOverlay();
            }
        });

        // 设置Canvas鼠标事件
        setupCanvasEvents();

        // 设置滚轮缩放
        setupZoomEvents();

        // 监听数据变化
        viewModel.audioDurationProperty().addListener((obs, oldVal, newVal) -> {
            timeSlider.setMax(newVal.longValue());
            updateTimeLabel();
            renderWaveform();
            // 更新导出范围选择条
            exportRangeBar.setAudioDuration(newVal.longValue());
            exportRangeBar.setViewportRange(0L, newVal.longValue());
        });

        viewModel.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
            if (!timeSlider.isValueChanging()) {
                timeSlider.setValue(newVal.longValue());
            }
            updateTimeLabel();

            // 播放时自动翻页（当进度条走到可见区域末尾时）
            if (viewModel.isPlaying()) {
                autoScrollToCurrentTime(newVal.longValue());
            }

            // 节流渲染：播放时降低重绘频率，避免卡顿
            requestRenderThrottled();
            // 黄色线独立刷新，优先保证跟手
            updatePlayheadOverlay();
        });

        viewModel.currentGroupProperty().addListener((obs, oldVal, newVal) -> {
            renderWaveform();
        });

        // 滑块拖动
        timeSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (isChanging) {
                dispatchPreviewScrubStarted();
            }
            if (!isChanging) {
                setCurrentTimeFromTimeline((long) timeSlider.getValue(), true);
            }
        });

        timeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (timeSlider.isValueChanging()) {
                setCurrentTimeFromTimeline(newVal.longValue(), true);
            }
        });

        // 初始渲染
        renderWaveform();
        updatePlayheadOverlay();
    }

    /**
     * 设置波形数据
     */
    public void setWaveformData(float[] data) {
        this.waveformData = data != null ? data : new float[0];
        renderWaveform();
    }

    /**
     * 获取波形数据
     */
    public float[] getWaveformData() {
        return waveformData;
    }

    /**
     * 更新视口范围（根据缩放级别和焦点时间）
     */
    private void updateViewport(double focusTime) {
        updateViewportWithAnchor(focusTime, 0.5);
    }

    /**
     * 更新视口范围（根据缩放级别和锚点位置）
     * anchorRatio: 焦点在画布中的相对位置，0=最左，1=最右
     */
    private void updateViewportWithAnchor(double focusTime, double anchorRatio) {
        long duration = viewModel.getAudioDuration();
        if (duration <= 0) {
            viewportStartTime = 0;
            viewportEndTime = 0;
            return;
        }

        double safeAnchorRatio = Math.max(0.0, Math.min(1.0, anchorRatio));

        // 计算可见时间范围（缩放越大，可见范围越小）
        double visibleDuration = duration / zoomLevel;

        // 以锚点位置保持焦点时间不变
        viewportStartTime = focusTime - safeAnchorRatio * visibleDuration;
        viewportEndTime = viewportStartTime + visibleDuration;

        // 边界检查
        if (viewportStartTime < 0) {
            viewportStartTime = 0;
            viewportEndTime = visibleDuration;
        }
        if (viewportEndTime > duration) {
            viewportEndTime = duration;
            viewportStartTime = duration - visibleDuration;
            if (viewportStartTime < 0) viewportStartTime = 0;
        }
    }

    /**
     * 自动滚动到当前播放时间（类似剪映/PR的自动翻页）
     */
    private void autoScrollToCurrentTime(long currentTime) {
        long duration = viewModel.getAudioDuration();
        if (duration <= 0) return;

        // 如果没有缩放，不需要滚动
        if (zoomLevel <= 1.0) {
            viewportStartTime = 0;
            viewportEndTime = duration;
            return;
        }

        // 计算可见范围
        double visibleDuration = duration / zoomLevel;

        // 当播放位置接近可见区域末尾时，自动向右滚动
        double threshold = viewportEndTime - visibleDuration * 0.2;  // 到达80%时开始滚动

        if (currentTime > threshold && currentTime <= duration) {
            // 将播放位置保持在30%位置（留有足够的右侧空间）
            viewportStartTime = currentTime - visibleDuration * 0.3;
            viewportEndTime = viewportStartTime + visibleDuration;

            // 边界检查
            if (viewportEndTime > duration) {
                viewportEndTime = duration;
                viewportStartTime = duration - visibleDuration;
                if (viewportStartTime < 0) viewportStartTime = 0;
            }
        }
        // 如果播放位置在可见范围左侧之外（用户手动seek了），立即跳转
        else if (currentTime < viewportStartTime) {
            viewportStartTime = Math.max(0, currentTime - visibleDuration * 0.3);
            viewportEndTime = viewportStartTime + visibleDuration;
        }
    }

    /**
     * 设置Canvas鼠标事件（用于拖动边界和长按调整时长）
     */
    private void setupCanvasEvents() {
        waveformCanvas.setOnMousePressed(this::handleMousePressed);
        waveformCanvas.setOnMouseDragged(this::handleMouseDragged);
        waveformCanvas.setOnMouseReleased(this::handleMouseReleased);
        waveformCanvas.setOnMouseMoved(this::handleMouseMoved);
    }

    /**
     * 设置滚轮缩放事件
     */
    private void setupZoomEvents() {
        waveformCanvas.setOnScroll(event -> {
            if (viewModel.getAudioDuration() <= 0) return;

            double deltaY = event.getDeltaY();
            if (deltaY == 0) return;

            // 计算鼠标在时间轴上的位置（作为缩放中心）
            double mouseX = event.getX();
            double canvasWidth = waveformCanvas.getWidth();
            if (canvasWidth <= 0) return;
            double anchorRatio = Math.max(0.0, Math.min(1.0, mouseX / canvasWidth));
            double currentVisibleDuration = viewportEndTime - viewportStartTime;
            if (currentVisibleDuration <= 0) {
                currentVisibleDuration = viewModel.getAudioDuration();
                viewportStartTime = 0;
                viewportEndTime = currentVisibleDuration;
            }
            double mouseFocusTime = viewportStartTime + anchorRatio * currentVisibleDuration;

            // 计算新的缩放级别
            double oldZoom = zoomLevel;
            if (deltaY > 0) {
                // 向上滚动，放大
                zoomLevel = Math.min(MAX_ZOOM, zoomLevel + ZOOM_STEP);
            } else {
                // 向下滚动，缩小
                zoomLevel = Math.max(MIN_ZOOM, zoomLevel - ZOOM_STEP);
            }

            if (oldZoom != zoomLevel) {
                // 更新视口，保持鼠标焦点与鼠标相对位置绑定
                updateViewportWithAnchor(mouseFocusTime, anchorRatio);
                renderWaveform();
            }

            event.consume();
        });
    }

    /**
     * 鼠标按下
     */
    private void handleMousePressed(MouseEvent event) {
        if (viewModel.getCurrentGroup() == null || viewModel.getAudioDuration() <= 0) {
            return;
        }

        double x = event.getX();

        // 优先检测是否点击到黄色进度条
        if (checkPlayheadHit(x)) {
            isDraggingPlayhead = true;
            playheadDragLastX = x;
            dragPreviewTimeMs = viewModel.getCurrentTime();
            lastDragLabelUpdateTimeMs = 0L;
            lastViewportRenderTimeMs = 0L;
            waveformCanvas.setCursor(Cursor.H_RESIZE);
            dispatchPreviewScrubStarted();
            dispatchPreviewScrubUpdated(dragPreviewTimeMs);
            updatePlayheadOverlayWithTime(dragPreviewTimeMs);
            event.consume();
            return;
        }

        // 检测是否点击到边界线
        if (checkBoundaryHit(x)) {
            // 拖动边界
            event.consume();
        } else if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            // 双击：跳转到点击位置
            long clickTime = canvasXToTime(x);
            if (clickTime >= 0 && clickTime <= viewModel.getAudioDuration()) {
                setCurrentTimeFromTimeline(clickTime, true);
            }
            event.consume();
        } else if (event.getButton() == MouseButton.SECONDARY) {
            // 右键：显示调整时长对话框
            handleRegionClick(x);
            event.consume();
        } else if (event.getButton() == MouseButton.PRIMARY && event.isControlDown()) {
            // Ctrl+左键：开始框选
            isBoxSelecting = true;
            boxSelectStartX = x;
            boxSelectCurrentX = x;
            selectedKeyframeIndices.clear();
            waveformCanvas.setCursor(Cursor.CROSSHAIR);
            event.consume();
        } else if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1 && zoomLevel > 1.0) {
            // 放大状态下，左键按住空白区域可拖动视口
            isDraggingViewport = true;
            viewportDragStartX = x;
            viewportDragStartTime = viewportStartTime;
            viewportDragVisibleDuration = viewportEndTime - viewportStartTime;
            waveformCanvas.setCursor(Cursor.CLOSED_HAND);
            event.consume();
        }
        // 单击不做任何操作
    }

    /**
     * 检测是否点击到黄色进度条
     */
    private boolean checkPlayheadHit(double x) {
        double width = waveformCanvas.getWidth();
        long duration = viewModel.getAudioDuration();
        if (width <= 0 || duration <= 0) return false;

        long currentTime = (isDraggingPlayhead && dragPreviewTimeMs >= 0)
            ? dragPreviewTimeMs
            : viewModel.getCurrentTime();
        double playheadX = timeToCanvasX(currentTime, width, duration);

        return Math.abs(x - playheadX) < PLAYHEAD_TOLERANCE;
    }

    /**
     * 将画布X坐标转换为时间（基于当前视口）
     */
    private long canvasXToTime(double x) {
        double width = waveformCanvas.getWidth();
        if (width <= 0) return 0;
        double visibleRange = viewportEndTime - viewportStartTime;
        return (long) (viewportStartTime + (x / width) * visibleRange);
    }

    /**
     * 平移当前视口（用于黄色标贴边拖动时自动滚动）
     */
    private void shiftViewportBy(double deltaTime, long duration) {
        double visibleRange = viewportEndTime - viewportStartTime;
        if (visibleRange <= 0 || duration <= 0) return;

        double maxStart = Math.max(0, duration - visibleRange);
        double newStart = Math.max(0, Math.min(maxStart, viewportStartTime + deltaTime));

        viewportStartTime = newStart;
        viewportEndTime = newStart + visibleRange;
    }

    /**
     * 更新时间并按需通知外部seek（用于同步视频刷新）
     */
    private void setCurrentTimeFromTimeline(long timeMs, boolean requestSeek) {
        long duration = viewModel.getAudioDuration();
        long clampedTime = duration > 0
            ? Math.max(0, Math.min(timeMs, duration))
            : Math.max(0, timeMs);

        if (requestSeek && onSeekRequested != null) {
            onSeekRequested.accept(clampedTime);
            return;
        }

        if (viewModel.getCurrentTime() != clampedTime) {
            viewModel.setCurrentTime(clampedTime);
        }
    }

    /**
     * 拖动开始事件分发
     */
    private void dispatchPreviewScrubStarted() {
        if (onPreviewScrubStarted != null) {
            onPreviewScrubStarted.run();
        }
    }

    /**
     * 拖动更新事件分发（兼容旧预览回调）
     */
    private void dispatchPreviewScrubUpdated(long timeMs) {
        long duration = viewModel.getAudioDuration();
        long clampedTime = duration > 0
            ? Math.max(0, Math.min(timeMs, duration))
            : Math.max(0, timeMs);

        if (onPreviewScrubUpdated != null) {
            onPreviewScrubUpdated.accept(clampedTime);
            return;
        }

        if (onPreviewSeekRequested != null) {
            onPreviewSeekRequested.accept(clampedTime);
        }
    }

    /**
     * 拖动结束事件分发
     */
    private void dispatchPreviewScrubEnded(long timeMs) {
        long duration = viewModel.getAudioDuration();
        long clampedTime = duration > 0
            ? Math.max(0, Math.min(timeMs, duration))
            : Math.max(0, timeMs);

        if (onPreviewScrubEnded != null) {
            onPreviewScrubEnded.accept(clampedTime);
            return;
        }

        // 兼容旧链路：未注入新回调时走普通定位
        setCurrentTimeFromTimeline(clampedTime, true);
    }

    /**
     * 仅在贴边平移时触发的重绘节流
     */
    private void requestViewportRenderThrottled(long now) {
        if (now - lastViewportRenderTimeMs < PLAYHEAD_VIEWPORT_RENDER_INTERVAL_MS) {
            return;
        }
        lastViewportRenderTimeMs = now;
        renderWaveform();
    }

    /**
     * 鼠标拖动
     */
    private void handleMouseDragged(MouseEvent event) {
        double x = event.getX();
        double width = waveformCanvas.getWidth();
        long duration = viewModel.getAudioDuration();

        if (width <= 0 || duration <= 0) return;

        // 框选拖动
        if (isBoxSelecting) {
            boxSelectCurrentX = x;
            updateBoxSelection();
            renderWaveform();
            event.consume();
            return;
        }

        // 拖动黄色进度条
        if (isDraggingPlayhead) {
            double clampedX = Math.max(0, Math.min(x, width));
            long newTime = canvasXToTime(clampedX);
            newTime = Math.max(0, Math.min(newTime, duration));
            boolean viewportChanged = false;

            // 放大时，拖到左右边缘自动平移视口，避免黄色标“拖一段卡住”
            if (zoomLevel > 1.0) {
                double visibleRange = viewportEndTime - viewportStartTime;
                if (visibleRange > 0) {
                    double deltaX = x - playheadDragLastX;

                    if (x >= width - PLAYHEAD_EDGE_AUTO_SCROLL_PX && deltaX >= 0 && viewportEndTime < duration) {
                        double effectiveDeltaX = Math.max(deltaX, 1.0);
                        shiftViewportBy((effectiveDeltaX / width) * visibleRange, duration);
                        viewportChanged = true;
                    } else if (x <= PLAYHEAD_EDGE_AUTO_SCROLL_PX && deltaX <= 0 && viewportStartTime > 0) {
                        double effectiveDeltaX = Math.min(deltaX, -1.0);
                        shiftViewportBy((effectiveDeltaX / width) * visibleRange, duration);
                        viewportChanged = true;
                    }

                    if (viewportChanged) {
                        clampedX = Math.max(0, Math.min(x, width));
                        newTime = canvasXToTime(clampedX);
                        newTime = Math.max(0, Math.min(newTime, duration));
                    }
                }
            }

            // 本地预览时间每次都更新，确保黄色标严格跟手
            dragPreviewTimeMs = newTime;
            long now = System.currentTimeMillis();
            if (now - lastDragLabelUpdateTimeMs >= DRAG_LABEL_UPDATE_INTERVAL_MS) {
                lastDragLabelUpdateTimeMs = now;
                updateTimeLabelWithCurrent(newTime);
            }
            dispatchPreviewScrubUpdated(newTime);

            // 黄线优先跟手：拖动时先更新覆盖线，避免整块波形重绘拖慢交互
            updatePlayheadOverlayWithTime(newTime);
            // 视口发生变化时再节流重绘波形
            if (viewportChanged) {
                requestViewportRenderThrottled(now);
            }
            playheadDragLastX = x;
            event.consume();
            return;
        }

        // 拖动缩放视口
        if (isDraggingViewport) {
            if (viewportDragVisibleDuration <= 0) {
                event.consume();
                return;
            }

            double deltaX = x - viewportDragStartX;
            // 拖动方向与时间方向反向：向右拖看到更早时间
            double deltaTime = -(deltaX / width) * viewportDragVisibleDuration;
            double maxStart = Math.max(0, duration - viewportDragVisibleDuration);
            double newStart = Math.max(0, Math.min(maxStart, viewportDragStartTime + deltaTime));

            viewportStartTime = newStart;
            viewportEndTime = newStart + viewportDragVisibleDuration;
            renderWaveform();
            event.consume();
            return;
        }

        // 拖动绿色区边界
        if (!isDraggingBoundary) return;

        // 计算拖动的时间偏移（基于视口）
        double deltaX = x - dragStartX;
        double visibleRange = viewportEndTime - viewportStartTime;
        long deltaDuration = (long) ((deltaX / width) * visibleRange);

        if (isDraggingStartBoundary) {
            // 拖动绿色前边界（移动关键帧位置）
            long newTimestamp = Math.max(0, dragStartTimestamp + deltaDuration);
            viewModel.moveFormationTimestamp(draggingFormationIndex, newTimestamp);
        } else {
            // 拖动绿色后边界（改变 holdDuration）
            long newDuration = Math.max(0, dragStartDuration + deltaDuration);
            viewModel.updateFormationHoldDuration(draggingFormationIndex, newDuration);
        }
        renderWaveform();
    }

    /**
     * 鼠标释放
     */
    private void handleMouseReleased(MouseEvent event) {
        boolean wasDraggingPlayhead = isDraggingPlayhead;

        // 框选结束
        if (isBoxSelecting) {
            isBoxSelecting = false;
            waveformCanvas.setCursor(Cursor.DEFAULT);

            // 如果有选中的关键帧，显示删除提示
            if (!selectedKeyframeIndices.isEmpty()) {
                showDeleteSelectedKeyframesDialog();
            }

            // 清除选中状态并重绘
            selectedKeyframeIndices.clear();
            renderWaveform();
            event.consume();
            return;
        }

        isDraggingBoundary = false;
        isDraggingPlayhead = false;
        isDraggingViewport = false;
        draggingFormationIndex = -1;
        hasSavedUndoSnapshot = false;  // 重置撤销快照标记
        waveformCanvas.setCursor(Cursor.DEFAULT);

        // 拖动结束时强制一次seek，确保视频帧与黄色标最终位置一致
        if (wasDraggingPlayhead) {
            long finalTime = dragPreviewTimeMs >= 0 ? dragPreviewTimeMs : viewModel.getCurrentTime();
            dispatchPreviewScrubEnded(finalTime);
            dragPreviewTimeMs = -1L;
            lastDragLabelUpdateTimeMs = 0L;
            lastViewportRenderTimeMs = 0L;
            updateTimeLabelWithCurrent(viewModel.getCurrentTime());
            renderWaveform();
            updatePlayheadOverlay();
        }
    }

    /**
     * 鼠标移动（改变光标）
     */
    private void handleMouseMoved(MouseEvent event) {
        if (viewModel.getCurrentGroup() == null || viewModel.getAudioDuration() <= 0) {
            return;
        }

        double x = event.getX();

        // 检测是否靠近黄色进度条
        if (checkPlayheadHit(x)) {
            waveformCanvas.setCursor(Cursor.H_RESIZE);
            return;
        }

        // 检测是否靠近边界
        if (isNearBoundary(x)) {
            waveformCanvas.setCursor(Cursor.H_RESIZE);
        } else {
            waveformCanvas.setCursor(Cursor.DEFAULT);
        }
    }

    /**
     * 检测是否点击到边界
     * 新逻辑（前红后绿）：
     * - 红色区前边界 = timestamp - duration（可拖动改变前红区长度）
     * - 关键帧位置 = timestamp（可移动，除了第一帧）
     * - 绿色区后边界 = timestamp + holdDuration（可拖动改变绿色区长度）
     * 注意：倒序遍历，优先匹配后面的关键帧（避免重叠时卡死）
     */
    private boolean checkBoundaryHit(double x) {
        if (viewModel.getCurrentGroup() == null) return false;

        double width = waveformCanvas.getWidth();
        long duration = viewModel.getAudioDuration();

        if (width <= 0 || duration <= 0) return false;

        List<Formation> formations = viewModel.getCurrentGroup().getFormations();

        // 倒序遍历，优先匹配后面的关键帧
        for (int i = formations.size() - 1; i >= 0; i--) {
            Formation formation = formations.get(i);
            long timestamp = formation.getTimestamp();
            long greenEnd = timestamp + formation.getHoldDuration();

            // 检测关键帧位置（timestamp）- 第一个关键帧(00:00)不能移动
            if (i > 0) {
                double timestampX = timeToCanvasX(timestamp, width, duration);
                if (Math.abs(x - timestampX) < BOUNDARY_TOLERANCE) {
                    // 在开始拖动时保存撤销快照
                    viewModel.getUndoManager().saveSnapshot(viewModel.getCurrentGroup());
                    hasSavedUndoSnapshot = true;

                    isDraggingBoundary = true;
                    draggingFormationIndex = i;
                    isDraggingStartBoundary = true;  // 拖动关键帧位置
                    isDraggingHoldBoundary = false;
                    dragStartX = x;
                    dragStartTimestamp = timestamp;
                    waveformCanvas.setCursor(Cursor.H_RESIZE);
                    return true;
                }
            }

            // 检测绿色区后边界（holdDuration结束位置）
            double greenEndX = timeToCanvasX(greenEnd, width, duration);
            if (Math.abs(x - greenEndX) < BOUNDARY_TOLERANCE) {
                // 在开始拖动时保存撤销快照
                viewModel.getUndoManager().saveSnapshot(viewModel.getCurrentGroup());
                hasSavedUndoSnapshot = true;

                isDraggingBoundary = true;
                draggingFormationIndex = i;
                isDraggingStartBoundary = false;
                isDraggingHoldBoundary = true;  // 拖动绿色区长度
                dragStartX = x;
                dragStartDuration = formation.getHoldDuration();
                waveformCanvas.setCursor(Cursor.H_RESIZE);
                return true;
            }
        }

        return false;
    }

    /**
     * 检测是否靠近边界（用于显示拖动光标）
     * 新逻辑（前红后绿）：检测 timestamp 和 greenEnd 位置
     * 注意：倒序遍历，优先匹配后面的关键帧
     */
    private boolean isNearBoundary(double x) {
        if (viewModel.getCurrentGroup() == null) return false;

        double width = waveformCanvas.getWidth();
        long duration = viewModel.getAudioDuration();

        if (width <= 0 || duration <= 0) return false;

        List<Formation> formations = viewModel.getCurrentGroup().getFormations();

        // 倒序遍历，优先匹配后面的关键帧
        for (int i = formations.size() - 1; i >= 0; i--) {
            Formation formation = formations.get(i);
            long timestamp = formation.getTimestamp();
            long greenEnd = timestamp + formation.getHoldDuration();

            // 检测关键帧位置（第一个关键帧除外）
            if (i > 0) {
                double timestampX = timeToCanvasX(timestamp, width, duration);
                if (Math.abs(x - timestampX) < BOUNDARY_TOLERANCE) {
                    return true;
                }
            }

            // 检测绿色区后边界
            double greenEndX = timeToCanvasX(greenEnd, width, duration);
            if (Math.abs(x - greenEndX) < BOUNDARY_TOLERANCE) {
                return true;
            }
        }

        return false;
    }

    /**
     * 处理区域点击（右键或双击调整时长）
     * 新逻辑（前红后绿）：
     * - 红色区: [timestamp - duration, timestamp)
     * - 绿色区: [timestamp, timestamp + holdDuration)
     */
    private void handleRegionClick(double x) {
        if (viewModel.getCurrentGroup() == null) return;

        double width = waveformCanvas.getWidth();
        long duration = viewModel.getAudioDuration();

        if (width <= 0 || duration <= 0) return;

        List<Formation> formations = viewModel.getCurrentGroup().getFormations();

        for (int i = 0; i < formations.size(); i++) {
            Formation formation = formations.get(i);
            long timestamp = formation.getTimestamp();
            long redStart = timestamp - formation.getDuration();
            long greenEnd = timestamp + formation.getHoldDuration();

            double redStartX = timeToCanvasX(redStart, width, duration);
            double timestampX = timeToCanvasX(timestamp, width, duration);
            double greenEndX = timeToCanvasX(greenEnd, width, duration);

            // 检测是否点击在红色变换区（前面）- 第一帧无红色区
            if (i > 0 && formation.getDuration() > 0) {
                if (x >= redStartX && x < timestampX) {
                    showAdjustTransitionDurationDialog(i, formation);
                    return;
                }
            }

            // 检测是否点击在绿色保持区（后面）
            if (x >= timestampX && x < greenEndX) {
                showAdjustHoldDurationDialog(i, formation);
                return;
            }
        }
    }

    /**
     * 显示调整保持时长对话框（绿色区 - 后面）
     */
    private void showAdjustHoldDurationDialog(int index, Formation formation) {
        TextInputDialog dialog = new TextInputDialog(String.valueOf(formation.getHoldDuration()));
        dialog.setTitle("调整保持时长（绿色区）");
        dialog.setHeaderText("关键帧 " + (index + 1) + ": " + formation.getName());
        dialog.setContentText("保持时长（毫秒）：\n从timestamp开始，在此时间内舞者保持当前阵型完全不动。");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(value -> {
            try {
                long newDuration = Long.parseLong(value);
                if (newDuration >= 0) {
                    viewModel.updateFormationHoldDuration(index, newDuration);
                    renderWaveform();
                }
            } catch (NumberFormatException e) {
                // 忽略无效输入
            }
        });
    }

    /**
     * 显示调整变换时长对话框（红色区 - 前面）
     */
    private void showAdjustTransitionDurationDialog(int index, Formation formation) {
        TextInputDialog dialog = new TextInputDialog(String.valueOf(formation.getDuration()));
        dialog.setTitle("调整变换时长（红色区）");
        dialog.setHeaderText("关键帧 " + index + " 到 关键帧 " + (index + 1));
        dialog.setContentText("变换时长（毫秒）：\n从前一帧绿色区结束到当前帧timestamp，舞者过渡变换的时间。");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(value -> {
            try {
                long newDuration = Long.parseLong(value);
                if (newDuration >= 0) {
                    viewModel.updateFormationTransitionDuration(index, newDuration);
                    renderWaveform();
                }
            } catch (NumberFormatException e) {
                // 忽略无效输入
            }
        });
    }

    /**
     * 节流渲染请求（播放时避免频繁重绘）
     */
    private void requestRenderThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastRenderTime >= RENDER_THROTTLE_MS) {
            lastRenderTime = now;
            renderWaveform();
        }
    }

    /**
     * 渲染波形（包括波形、区域、进度条）
     */
    private void renderWaveform() {
        GraphicsContext gc = waveformCanvas.getGraphicsContext2D();
        double width = waveformCanvas.getWidth();
        double height = waveformCanvas.getHeight();

        // 清空画布（深色背景）
        gc.setFill(Color.rgb(30, 30, 40));
        gc.fillRect(0, 0, width, height);

        if (viewModel.getCurrentGroup() == null || viewModel.getAudioDuration() <= 0) {
            // 显示提示文字
            gc.setFill(Color.rgb(136, 136, 153));
            gc.setFont(javafx.scene.text.Font.font(14));
            gc.fillText("点击导入音频后显示波形", width / 2 - 70, height / 2);
            return;
        }

        long duration = viewModel.getAudioDuration();

        // 初始化视口（首次渲染时）
        if (viewportEndTime == 0) {
            viewportStartTime = 0;
            viewportEndTime = duration;
        }

        // 同步导出范围条的视口，让入点/出点选择器与波形缩放保持一致
        syncExportRangeViewport(duration);

        // 绘制波形（如果有数据）
        if (waveformData.length > 0) {
            drawWaveform(gc, width, height, duration);
        }

        // 绘制保持区和变换区
        drawRegions(gc, width, height, duration);

        // 绘制框选矩形
        if (isBoxSelecting) {
            drawBoxSelection(gc, height);
        }

        // 绘制缩放级别提示
        if (zoomLevel > 1.0) {
            gc.setFill(Color.rgb(200, 200, 200, 0.8));
            gc.setFont(javafx.scene.text.Font.font(10));
            gc.fillText(String.format("缩放: %.1fx", zoomLevel), width - 60, height - 5);
        }

        // 波形重绘后同步覆盖层位置
        updatePlayheadOverlay();
    }

    /**
     * 绘制波形
     */
    private void drawWaveform(GraphicsContext gc, double width, double height, long duration) {
        gc.setStroke(Color.rgb(100, 150, 255, 0.6));  // 蓝色半透明
        gc.setLineWidth(1);

        double centerY = height / 2;

        // 根据视口采样波形数据
        int startSampleIndex = (int) ((viewportStartTime / duration) * waveformData.length);
        int endSampleIndex = (int) ((viewportEndTime / duration) * waveformData.length);

        startSampleIndex = Math.max(0, Math.min(startSampleIndex, waveformData.length - 1));
        endSampleIndex = Math.max(0, Math.min(endSampleIndex, waveformData.length));

        int sampleCount = endSampleIndex - startSampleIndex;
        if (sampleCount <= 0) return;

        double sampleStep = (double) sampleCount / width;

        gc.beginPath();
        for (int x = 0; x < width; x++) {
            int sampleIndex = startSampleIndex + (int) (x * sampleStep);
            if (sampleIndex >= waveformData.length) break;

            float amplitude = waveformData[sampleIndex];
            double y = centerY - (amplitude * centerY * 0.8);  // 0.8是缩放系数

            if (x == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    /**
     * 绘制保持区和变换区
     * 新逻辑（前红后绿）：
     * - 红色区: [timestamp - duration, timestamp)
     * - 绿色区: [timestamp, timestamp + holdDuration)
     */
    private void drawRegions(GraphicsContext gc, double width, double height, long duration) {
        List<Formation> formations = viewModel.getCurrentGroup().getFormations();

        for (int i = 0; i < formations.size(); i++) {
            Formation formation = formations.get(i);
            long timestamp = formation.getTimestamp();
            long redStart = timestamp - formation.getDuration();  // 前面红色区开始
            long greenEnd = timestamp + formation.getHoldDuration();  // 后面绿色区结束

            // 计算在画布上的位置（基于视口）
            double redStartX = timeToCanvasX(redStart, width, duration);
            double timestampX = timeToCanvasX(timestamp, width, duration);
            double greenEndX = timeToCanvasX(greenEnd, width, duration);

            // 跳过不在视口内的元素
            if (greenEnd < viewportStartTime || redStart > viewportEndTime) {
                continue;
            }

            // 绘制红色变换区（前面）- 第一帧无红色区
            if (formation.getDuration() > 0 && i > 0) {
                double rectStartX = Math.max(0, redStartX);
                double rectEndX = Math.min(width, timestampX);
                if (rectStartX < rectEndX) {
                    gc.setFill(Color.rgb(255, 68, 68, 0.25));  // 0x40FF4444
                    gc.fillRect(rectStartX, 0, rectEndX - rectStartX, height);
                }
            }

            // 绘制关键帧标记（timestamp位置）
            if (timestampX >= 0 && timestampX <= width) {
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(2);
                gc.strokeLine(timestampX, 0, timestampX, height);

                // 绘制关键帧编号
                gc.setFill(Color.WHITE);
                gc.setFont(javafx.scene.text.Font.font(10));
                gc.fillText(String.valueOf(i + 1), timestampX + 2, 12);
            }

            // 绘制绿色保持区（后面）
            if (formation.getHoldDuration() > 0) {
                double rectStartX = Math.max(0, timestampX);
                double rectEndX = Math.min(width, greenEndX);
                if (rectStartX < rectEndX) {
                    gc.setFill(Color.rgb(0, 255, 0, 0.25));  // 0x4000FF00
                    gc.fillRect(rectStartX, 0, rectEndX - rectStartX, height);
                }
                // 绘制绿色区右边界线
                if (greenEndX >= 0 && greenEndX <= width) {
                    gc.setStroke(Color.rgb(255, 255, 255, 0.5));
                    gc.setLineWidth(2);
                    gc.strokeLine(greenEndX, 0, greenEndX, height);
                }
            }
        }

        // 绘制F键标记的临时区域（更高亮度，区分于已有区域）
        drawFKeyMarkRegion(gc, width, height, duration);
    }

    /**
     * 绘制F键标记的临时区域
     */
    private void drawFKeyMarkRegion(GraphicsContext gc, double width, double height, long duration) {
        if (fKeyMarkStartTime < 0) return;

        long currentTime = viewModel.getCurrentTime();

        if (isFKeyCurrentlyPressed) {
            // F键按下中 - 绘制从开始时间到当前时间的绿色区（高亮）
            double startX = timeToCanvasX(fKeyMarkStartTime, width, duration);
            double endX = timeToCanvasX(currentTime, width, duration);

            if (endX > startX) {
                double rectStartX = Math.max(0, startX);
                double rectEndX = Math.min(width, endX);
                if (rectStartX < rectEndX) {
                    gc.setFill(Color.rgb(0, 255, 0, 0.5));  // 更高亮度的绿色
                    gc.fillRect(rectStartX, 0, rectEndX - rectStartX, height);
                }
            }
        } else if (fKeyMarkEndTime > 0) {
            // F键已松开 - 绘制绿色区 + 红色区
            // 绿色区：fKeyMarkStartTime 到 fKeyMarkEndTime
            double greenStartX = timeToCanvasX(fKeyMarkStartTime, width, duration);
            double greenEndX = timeToCanvasX(fKeyMarkEndTime, width, duration);

            if (greenEndX > greenStartX) {
                double rectStartX = Math.max(0, greenStartX);
                double rectEndX = Math.min(width, greenEndX);
                if (rectStartX < rectEndX) {
                    gc.setFill(Color.rgb(0, 255, 0, 0.5));
                    gc.fillRect(rectStartX, 0, rectEndX - rectStartX, height);
                }
            }

            // 红色区：fKeyMarkEndTime 到当前时间
            double redStartX = timeToCanvasX(fKeyMarkEndTime, width, duration);
            double redEndX = timeToCanvasX(currentTime, width, duration);

            if (redEndX > redStartX) {
                double rectStartX = Math.max(0, redStartX);
                double rectEndX = Math.min(width, redEndX);
                if (rectStartX < rectEndX) {
                    gc.setFill(Color.rgb(255, 68, 68, 0.5));  // 更高亮度的红色
                    gc.fillRect(rectStartX, 0, rectEndX - rectStartX, height);
                }
            }
        }
    }

    private void updatePlayheadOverlay() {
        long currentTime = (isDraggingPlayhead && dragPreviewTimeMs >= 0)
            ? dragPreviewTimeMs
            : viewModel.getCurrentTime();
        updatePlayheadOverlayWithTime(currentTime);
    }

    private void updatePlayheadOverlayWithTime(long currentTime) {
        double width = waveformCanvas.getWidth();
        double height = waveformCanvas.getHeight();
        long duration = viewModel.getAudioDuration();

        if (width <= 0 || height <= 0 || duration <= 0 || viewModel.getCurrentGroup() == null) {
            playheadOverlayLine.setVisible(false);
            return;
        }

        if (viewportEndTime <= viewportStartTime) {
            playheadOverlayLine.setVisible(false);
            return;
        }

        if (currentTime < viewportStartTime || currentTime > viewportEndTime) {
            playheadOverlayLine.setVisible(false);
            return;
        }

        double currentX = timeToCanvasX(currentTime, width, duration);
        double clampedX = Math.max(0, Math.min(width, currentX));
        playheadOverlayLine.setStartX(clampedX);
        playheadOverlayLine.setEndX(clampedX);
        playheadOverlayLine.setStartY(0);
        playheadOverlayLine.setEndY(height);
        playheadOverlayLine.setVisible(true);
    }

    private void syncExportRangeViewport(long duration) {
        if (duration <= 0) {
            exportRangeBar.setViewportRange(0L, 0L);
            return;
        }

        long viewportStart = Math.max(0L, Math.min((long) viewportStartTime, duration));
        long viewportEnd = Math.max(viewportStart + 1, Math.min((long) viewportEndTime, duration));
        exportRangeBar.setViewportRange(viewportStart, viewportEnd);
    }

    /**
     * 将时间转换为画布X坐标（基于当前视口）
     */
    private double timeToCanvasX(long time, double width, long duration) {
        if (duration <= 0) return 0;
        double visibleRange = viewportEndTime - viewportStartTime;
        if (visibleRange <= 0) return 0;
        return ((time - viewportStartTime) / visibleRange) * width;
    }

    private void updateTimeLabel() {
        updateTimeLabelWithCurrent(viewModel.getCurrentTime());
    }

    private void updateTimeLabelWithCurrent(long current) {
        long total = viewModel.getAudioDuration();
        timeLabel.setText(formatTime(current) + " / " + formatTime(total));
    }

    private String formatTime(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / (1000 * 60)) % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ========== F键标记接口 ==========

    /**
     * F键按下 - 开始绿色区标记
     */
    public void onFKeyPressed(long currentTime) {
        isFKeyCurrentlyPressed = true;
        fKeyMarkStartTime = currentTime;
        fKeyMarkEndTime = -1;
        renderWaveform();
    }

    /**
     * F键松开 - 结束绿色区，开始红色区
     */
    public void onFKeyReleased(long currentTime) {
        isFKeyCurrentlyPressed = false;
        fKeyMarkEndTime = currentTime;
        renderWaveform();
    }

    /**
     * 清除F键标记（暂停时调用）
     */
    public void clearFKeyMark() {
        isFKeyCurrentlyPressed = false;
        fKeyMarkStartTime = -1;
        fKeyMarkEndTime = -1;
        renderWaveform();
    }

    /**
     * 获取F键标记的开始时间
     */
    public long getFKeyMarkStartTime() {
        return fKeyMarkStartTime;
    }

    /**
     * 获取F键标记的结束时间（绿色区结束/红色区开始）
     */
    public long getFKeyMarkEndTime() {
        return fKeyMarkEndTime;
    }

    /**
     * F键是否当前按下
     */
    public boolean isFKeyPressed() {
        return isFKeyCurrentlyPressed;
    }

    /**
     * 获取导出范围的入点时间（毫秒）
     */
    public long getExportInPointMs() {
        return exportRangeBar.getInPointMs();
    }

    /**
     * 获取导出范围的出点时间（毫秒）
     */
    public long getExportOutPointMs() {
        return exportRangeBar.getOutPointMs();
    }

    /**
     * 获取导出范围选择条
     */
    public ExportRangeBar getExportRangeBar() {
        return exportRangeBar;
    }

    /**
     * 设置关键帧更新回调（用于通知外部更新列表）
     */
    public void setOnFormationsChanged(Runnable callback) {
        this.onFormationsChanged = callback;
    }

    /**
     * 设置时间定位回调（由外部播放控制器注入，用于同步音视频seek）
     */
    public void setOnSeekRequested(LongConsumer callback) {
        this.onSeekRequested = callback;
    }

    /**
     * 设置拖动预览seek回调（仅用于拖动中的视频预览，不触发重状态同步）
     */
    public void setOnPreviewSeekRequested(LongConsumer callback) {
        this.onPreviewSeekRequested = callback;
    }

    /**
     * 设置拖动开始回调（黄线开始拖动时触发）
     */
    public void setOnPreviewScrubStarted(Runnable callback) {
        this.onPreviewScrubStarted = callback;
    }

    /**
     * 设置拖动更新回调（拖动中高频触发，仅传最新时间）
     */
    public void setOnPreviewScrubUpdated(LongConsumer callback) {
        this.onPreviewScrubUpdated = callback;
    }

    /**
     * 设置拖动结束回调（鼠标释放时触发最终定位）
     */
    public void setOnPreviewScrubEnded(LongConsumer callback) {
        this.onPreviewScrubEnded = callback;
    }

    // ========== 框选功能 ==========

    /**
     * 绘制框选矩形
     */
    private void drawBoxSelection(GraphicsContext gc, double height) {
        double minX = Math.min(boxSelectStartX, boxSelectCurrentX);
        double maxX = Math.max(boxSelectStartX, boxSelectCurrentX);
        double width = maxX - minX;

        if (width > 0) {
            // 绘制半透明红色填充（表示删除操作）
            gc.setFill(Color.rgb(255, 68, 68, 0.3));
            gc.fillRect(minX, 0, width, height);

            // 绘制红色边框
            gc.setStroke(Color.rgb(255, 68, 68, 0.8));
            gc.setLineWidth(2);
            gc.strokeRect(minX, 0, width, height);
        }
    }

    /**
     * 更新框选区域，检测框选范围内的关键帧
     */
    private void updateBoxSelection() {
        if (viewModel.getCurrentGroup() == null) return;

        selectedKeyframeIndices.clear();

        double minX = Math.min(boxSelectStartX, boxSelectCurrentX);
        double maxX = Math.max(boxSelectStartX, boxSelectCurrentX);

        double width = waveformCanvas.getWidth();
        long duration = viewModel.getAudioDuration();
        if (width <= 0 || duration <= 0) return;

        List<Formation> formations = viewModel.getCurrentGroup().getFormations();

        // 检测每个关键帧是否在框选范围内
        for (int i = 0; i < formations.size(); i++) {
            Formation formation = formations.get(i);
            long timestamp = formation.getTimestamp();
            double timestampX = timeToCanvasX(timestamp, width, duration);

            if (timestampX >= minX && timestampX <= maxX) {
                selectedKeyframeIndices.add(i);
            }
        }
    }

    /**
     * 显示删除选中关键帧的对话框
     */
    private void showDeleteSelectedKeyframesDialog() {
        if (selectedKeyframeIndices.isEmpty()) return;

        // 检查是否包含第一帧
        if (selectedKeyframeIndices.contains(0)) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle("无法删除");
            alert.setHeaderText("不能删除第一帧");
            alert.setContentText("第一帧（初始帧）不能被删除。");
            alert.showAndWait();
            return;
        }

        // 检查是否全部删除
        com.formationtool.desktop.model.DanceGroup group = viewModel.getCurrentGroup();
        if (selectedKeyframeIndices.size() >= group.getFormations().size()) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle("无法删除");
            alert.setHeaderText("至少保留一帧");
            alert.setContentText("必须至少保留一个关键帧。");
            alert.showAndWait();
            return;
        }

        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认批量删除");
        confirm.setHeaderText("批量删除关键帧");
        confirm.setContentText("确定要删除选中的 " + selectedKeyframeIndices.size() + " 个关键帧吗？");

        java.util.Optional<javafx.scene.control.ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == javafx.scene.control.ButtonType.OK) {
            deleteSelectedKeyframes();
        }
    }

    /**
     * 删除选中的关键帧
     */
    private void deleteSelectedKeyframes() {
        com.formationtool.desktop.model.DanceGroup group = viewModel.getCurrentGroup();
        if (group == null || selectedKeyframeIndices.isEmpty()) return;

        // 保存撤销快照
        viewModel.getUndoManager().saveSnapshot(group);

        // 按降序排序索引，从后往前删除，避免索引变化
        java.util.Collections.sort(selectedKeyframeIndices, java.util.Collections.reverseOrder());

        List<com.formationtool.desktop.model.Formation> formations = group.getFormations();
        for (int index : selectedKeyframeIndices) {
            if (index > 0 && index < formations.size()) {
                formations.remove(index);

                // 应用删除关键帧逻辑：绿色区转红色并合并
                if (index < formations.size()) {
                    com.formationtool.desktop.model.Formation nextFormation = formations.get(index);
                    if (index > 0) {
                        com.formationtool.desktop.model.Formation prevFormation = formations.get(index - 1);
                        // 计算新的红色区长度
                        long prevGreenEnd = prevFormation.getTimestamp() + prevFormation.getHoldDuration();
                        long newDuration = nextFormation.getTimestamp() - prevGreenEnd;
                        nextFormation.setDuration(Math.max(1L, newDuration));
                    }
                }
            }
        }

        // 重绘波形
        renderWaveform();

        // 通知外部更新列表
        if (onFormationsChanged != null) {
            onFormationsChanged.run();
        }

        System.out.println("[框选删除] 已删除 " + selectedKeyframeIndices.size() + " 个关键帧");
    }
}
