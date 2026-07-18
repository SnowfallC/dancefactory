package com.formationtool.desktop.view;

import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.utils.AnimationInterpolator;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2D舞台视图 - 渲染舞者在舞台上的位置
 */
public class StageView extends Canvas {

    private final FormationViewModel viewModel;
    private float stageWidth = 16f;
    private float stageDepth = 12f;
    private float scale = 30f;
    private float baseScale = 30f;
    private float zoomFactor = 1.0f;
    private static final float MIN_ZOOM = 0.3f;
    private static final float MAX_ZOOM = 3.0f;
    private Dancer draggedDancer = null;
    private double dragOffsetX = 0;
    private double dragOffsetY = 0;
    private boolean hasMoved = false;
    private Map<String, float[]> originalDancerPositions = null;
    private List<Dancer> dragDisplayDancers = null;
    private boolean showDancerNames = false;
    private boolean showCenterLine = false;
    private boolean flipped = false;
    private boolean autoSnap = true;
    private static final float SNAP_DISTANCE = 0.3f;

    // 渲染节流控制
    private long lastRenderTime = 0;
    private static final long RENDER_THROTTLE_MS = 16; // ~60fps
    private String preferredTextFontFamily;

    // 缓存插值后的舞者列表
    private List<Dancer> cachedInterpolatedDancers = null;
    private long cachedTime = -1;
    private int cachedFormationIndex = -1;
    private final Map<String, Image> customStyleCache = new HashMap<>();
    private final Map<String, Long> customStyleModifiedCache = new HashMap<>();

    public StageView(FormationViewModel viewModel) {
        super(100, 100);
        this.viewModel = viewModel;
        initializeView();
    }

    public StageView(FormationViewModel viewModel, double width, double height) {
        super(width, height);
        this.viewModel = viewModel;
        initializeView();
    }

    private void initializeView() {

        // 监听数据变化，自动重新渲染
        viewModel.currentGroupProperty().addListener((obs, oldVal, newVal) -> render());
        viewModel.currentFormationIndexProperty().addListener((obs, oldVal, newVal) -> render());
        // 监听当前时间变化
        viewModel.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
            // 使用节流控制渲染频率
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastRenderTime >= RENDER_THROTTLE_MS) {
                lastRenderTime = currentTime;
                render();
            }
        });

        // 设置鼠标事件
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
        setOnMouseClicked(this::handleMouseClicked);

        // 添加鼠标滚轮缩放
        setOnScroll(this::handleScroll);

        // 初始渲染
        render();
    }

    /**
     * 绑定到场景尺寸
     */
    public void bindToScene(javafx.scene.Scene scene, double widthRatio, double heightRatio) {
        // 监听场景宽高变化
        scene.widthProperty().addListener((obs, oldVal, newVal) -> {
            double newWidth = newVal.doubleValue() * widthRatio;
            if (Math.abs(getWidth() - newWidth) > 1) {
                setWidth(newWidth);
                render();
            }
        });
        scene.heightProperty().addListener((obs, oldVal, newVal) -> {
            double newHeight = newVal.doubleValue() * heightRatio;
            if (Math.abs(getHeight() - newHeight) > 1) {
                setHeight(newHeight);
                render();
            }
        });
    }

    /**
     * 绑定到容器布局
     */
    public void bindToContainer(javafx.scene.layout.Region container) {
        // 监听容器布局变化
        container.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            double newWidth = newBounds.getWidth() - 20;
            double newHeight = newBounds.getHeight() - 20;

            if (newWidth > 100 && newHeight > 100) {
                if (Math.abs(getWidth() - newWidth) > 2 || Math.abs(getHeight() - newHeight) > 2) {
                    setWidth(newWidth);
                    setHeight(newHeight);
                    render();
                }
            }
        });
    }

    /**
     * 设置固定尺寸
     */
    public void setFixedSize(double width, double height) {
        setWidth(width);
        setHeight(height);
        render();
    }

    /**
     * 设置是否显示舞者名称
     */
    public void setShowDancerNames(boolean show) {
        this.showDancerNames = show;
        render();
    }

    public boolean isShowDancerNames() {
        return showDancerNames;
    }

    /**
     * 设置是否显示中心线
     */
    public void setShowCenterLine(boolean show) {
        this.showCenterLine = show;
        render();
    }

    public boolean isShowCenterLine() {
        return showCenterLine;
    }

    /**
     * 设置是否翻转舞台方向
     * 翻转后观众方向在上方
     */
    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
        render();
    }

    /**
     * 获取翻转状态
     */
    public boolean isFlipped() {
        return this.flipped;
    }

    /**
     * 设置是否自动吸附
     * 开启后舞者位置会对齐到0.5格
     */
    public void setAutoSnap(boolean autoSnap) {
        this.autoSnap = autoSnap;
    }

    /**
     * 获取自动吸附状态
     */
    public boolean isAutoSnap() {
        return this.autoSnap;
    }

    // 渲染舞台
    public void render() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        // 清除画布
        gc.setFill(Color.rgb(26, 26, 31));
        gc.fillRect(0, 0, w, h);

        // 计算基础缩放比例
        baseScale = (float) Math.min(
            (w * 0.9) / stageWidth,
            (h * 0.9) / stageDepth
        );

        // 应用缩放因子
        scale = baseScale * zoomFactor;

        // 绘制网格
        drawGrid(gc, w, h);

        // 绘制舞台边界
        drawStageBoundary(gc, w, h);

        // 绘制中心线（如果开启）
        if (showCenterLine) {
            drawCenterLine(gc, w, h);
        }

        // 绘制舞者
        drawDancers(gc, w, h);
    }

    // 绘制网格线
    private void drawGrid(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        double centerX = canvasWidth / 2;
        double centerY = canvasHeight / 2;

        // 半格虚线网格
        gc.setStroke(Color.rgb(40, 40, 48));
        gc.setLineWidth(0.5);
        gc.setLineDashes(4, 4);

        // 竖线（半格偏移）
        for (int i = -(int)(stageWidth / 2); i < (int)(stageWidth / 2); i++) {
            double x = centerX + (i + 0.5) * scale;
            gc.strokeLine(x, centerY - stageDepth / 2 * scale, x, centerY + stageDepth / 2 * scale);
        }

        // 横线（半格偏移）
        for (int i = -(int)(stageDepth / 2); i < (int)(stageDepth / 2); i++) {
            double y = centerY + (i + 0.5) * scale;
            gc.strokeLine(centerX - stageWidth / 2 * scale, y, centerX + stageWidth / 2 * scale, y);
        }

        // 整格网格线
        gc.setStroke(Color.rgb(50, 50, 60));
        gc.setLineWidth(1);
        gc.setLineDashes(null);

        // 竖线
        for (int i = -(int)(stageWidth / 2); i <= (int)(stageWidth / 2); i++) {
            double x = centerX + i * scale;
            gc.strokeLine(x, centerY - stageDepth / 2 * scale, x, centerY + stageDepth / 2 * scale);
        }

        // 横线
        for (int i = -(int)(stageDepth / 2); i <= (int)(stageDepth / 2); i++) {
            double y = centerY + i * scale;
            gc.strokeLine(centerX - stageWidth / 2 * scale, y, centerX + stageWidth / 2 * scale, y);
        }
    }

    // 绘制舞台边界框
    private void drawStageBoundary(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        gc.setStroke(Color.rgb(33, 150, 243, 0.5));
        gc.setLineWidth(2);

        double centerX = canvasWidth / 2;
        double centerY = canvasHeight / 2;
        double w = stageWidth * scale;
        double h = stageDepth * scale;

        gc.strokeRect(centerX - w / 2, centerY - h / 2, w, h);

        // 显示舞台标签（观众/后台）
        gc.setFill(Color.rgb(255, 255, 255, 0.8));
        gc.setFont(createStageTextFont(16));
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);

        if (flipped) {
            // 翻转模式：观众在上方
            gc.fillText("观众", centerX, centerY - h / 2 + 25);
            gc.fillText("后台", centerX, centerY + h / 2 - 10);
        } else {
            // 正常模式：后台在上方
            gc.fillText("后台", centerX, centerY - h / 2 + 25);
            gc.fillText("观众", centerX, centerY + h / 2 - 10);
        }
    }

    // 绘制中心线
    private void drawCenterLine(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        double centerX = canvasWidth / 2;
        double centerY = canvasHeight / 2;
        double h = stageDepth * scale;

        // 金黄色虚线
        gc.setStroke(Color.rgb(255, 215, 0, 0.8));
        gc.setLineWidth(3);
        gc.setLineDashes(10, 5);
        gc.strokeLine(centerX, centerY - h / 2, centerX, centerY + h / 2);
        gc.setLineDashes(null);

        // 中心线标签
        gc.setFill(Color.rgb(255, 215, 0, 0.9));
        gc.setFont(createStageTextFont(14));
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.fillText("C", centerX, centerY - h / 2 - 5);
    }

    // 绘制舞者
    private void drawDancers(GraphicsContext gc, double canvasWidth, double canvasHeight) {
        if (viewModel.getCurrentGroup() == null) return;

        List<Formation> formations = viewModel.getCurrentGroup().getFormations();
        if (formations.isEmpty()) return;

        double centerX = canvasWidth / 2;
        double centerY = canvasHeight / 2;
        double radius = 15;

        // 获取插值后的舞者位置
        List<Dancer> displayDancers = getInterpolatedDancers();

        // 构建舞者编号映射（用于显示序号）
        Formation currentFormation = getCurrentFormationSafely();
        if (currentFormation == null) return;
        java.util.Map<String, Integer> dancerIndexMap = new java.util.HashMap<>();
        for (int i = 0; i < currentFormation.getDancers().size(); i++) {
            dancerIndexMap.put(currentFormation.getDancers().get(i).getId(), i + 1);
        }

        for (Dancer dancer : displayDancers) {
            double screenX = centerX + dancer.getX() * scale;
            // Y轴处理：正常模式Y取反（屏幕坐标系），翻转模式不取反
            // screenY = centerY ± dancer.getY() * scale
            // flipped: +Y向下；normal: -Y向上
            double screenY = flipped
                ? centerY + dancer.getY() * scale
                : centerY - dancer.getY() * scale;

            String customStylePath = dancer.getCustomStylePath();
            Image customStyleImage = loadCustomStyleImage(customStylePath);
            boolean hasCustomStyle = customStyleImage != null;

            if (hasCustomStyle) {
                gc.drawImage(customStyleImage, screenX - radius, screenY - radius, radius * 2, radius * 2);
            } else {
                Color color = intToColor(dancer.getColor());
                gc.setFill(color);
                gc.fillOval(screenX - radius, screenY - radius, radius * 2, radius * 2);
            }

            // selected outline
            if (dancer.isSelected()) {
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(3);
                gc.strokeOval(screenX - radius - 2, screenY - radius - 2, radius * 2 + 4, radius * 2 + 4);
            }

            String displayText;
            double fontSize;
            if (showDancerNames) {
                displayText = dancer.getName();
                fontSize = displayText.length() <= 2 ? 12 : (displayText.length() <= 3 ? 10 : 8);
            } else {
                if (hasCustomStyle) {
                    continue;
                }
                Integer dancerNumber = dancerIndexMap.get(dancer.getId());
                displayText = dancerNumber != null ? String.valueOf(dancerNumber) : "?";
                fontSize = 12;
            }

            gc.setFont(createStageTextFont(fontSize));
            gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
            gc.setTextBaseline(javafx.geometry.VPos.CENTER);

            // 文字描边（黑色轮廓增强可读性）
            gc.setFill(Color.BLACK);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        gc.fillText(displayText, screenX + dx, screenY + dy);
                    }
                }
            }

            // 绘制白色文字
            gc.setFill(Color.WHITE);
            gc.fillText(displayText, screenX, screenY);
        }
    }

    /**
     * 获取当前时间点的插值舞者列表
     * 根据当前播放时间和队形时间轴，计算舞者的过渡位置
     * - 在绿色区域（静止）：直接返回当前帧舞者位置
     * - 在红色区域（过渡）：在前后两帧之间做线性插值
     *
     * 使用缓存机制避免重复计算
     * 拖拽时返回拖拽快照
     * @return 插值后的舞者列表
     */
    private List<Dancer> getInterpolatedDancers() {
        List<Formation> formations = viewModel.getCurrentGroup().getFormations();
        long currentTime = viewModel.getCurrentTime();
        int currentFormationIndex = viewModel.getCurrentFormationIndex();

        if (formations.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        // 拖拽中时使用拖拽快照
        if (draggedDancer != null && hasMoved && dragDisplayDancers != null) {
            return dragDisplayDancers;
        }

        // 使用缓存（时间和队形索引未变时直接返回）
        if (cachedInterpolatedDancers != null &&
            cachedTime == currentTime &&
            cachedFormationIndex == currentFormationIndex) {
            return cachedInterpolatedDancers;
        }

        // 计算当前时间应该显示哪个队形
        Formation displayFormation = null;
        Formation sourceFormation = null;
        float progress = -1f;

        for (int i = 0; i < formations.size(); i++) {
            Formation f = formations.get(i);
            long timestamp = f.getTimestamp();
            long redStart = timestamp - f.getDuration();
            long greenEnd = timestamp + f.getHoldDuration();

            // 在过渡区间内（红色区域：从前一帧过渡到当前帧）
            if (i > 0 && f.getDuration() > 0 && currentTime >= redStart && currentTime < timestamp) {
                // 使用前一帧作为源，当前帧作为目标
                sourceFormation = formations.get(i - 1);
                displayFormation = f;
                long elapsed = currentTime - redStart;
                long duration = f.getDuration();
                // 计算过渡进度
                progress = duration > 0 ? Math.min(1f, (float) elapsed / duration) : 0f;
                break;
            }

            // 在静止区间内（绿色区域：保持当前帧）
            if (currentTime >= timestamp && currentTime < greenEnd) {
                // 直接显示当前帧，不需要插值
                displayFormation = f;
                sourceFormation = null;
                progress = -1f;
                break;
            }
        }

        // 如果没有匹配的区间，默认显示最后一帧
        if (displayFormation == null) {
            displayFormation = formations.get(formations.size() - 1);
        }

        // 根据是否有过渡来决定插值
        List<Dancer> result;
        if (sourceFormation != null && progress >= 0f) {
            // 处于过渡状态，进行线性插值
            // 在源队形和目标队形之间插值
            result = interpolateDancers(sourceFormation, displayFormation, progress);
        } else {
            // 静止状态，直接使用当前队形的舞者
            result = displayFormation.getDancers();
        }

        // 更新缓存
        cachedInterpolatedDancers = result;
        cachedTime = currentTime;
        cachedFormationIndex = currentFormationIndex;

        return result;
    }

    /**
     * 在两个队形之间进行线性插值
     * @return 插值后的舞者列表
     */
    private List<Dancer> interpolateDancers(Formation from, Formation to, float progress) {
        List<Dancer> result = new java.util.ArrayList<>();
        List<Dancer> fromDancers = from.getDancers();
        List<Dancer> toDancers = to.getDancers();

        // 确保进度在0-1范围内
        float safeProgress = Math.max(0f, Math.min(1f, progress));

        // 遍历舞者进行位置插值
        for (int i = 0; i < Math.min(fromDancers.size(), toDancers.size()); i++) {
            Dancer fromDancer = fromDancers.get(i);
            Dancer toDancer = toDancers.get(i);

            // 获取起始和目标位置
            double fromX = fromDancer.getX();
            double fromY = fromDancer.getY();
            double toX = toDancer.getX();
            double toY = toDancer.getY();

            float x = (float)(fromX + (toX - fromX) * safeProgress);
            float y = (float)(fromY + (toY - fromY) * safeProgress);

            // 创建插值后的舞者对象
            Dancer interpolated = new Dancer(
                fromDancer.getId(),
                fromDancer.getName(),
                x, y,
                fromDancer.getColor()
            );
            String stylePath = fromDancer.getCustomStylePath();
            if (stylePath == null || stylePath.isBlank()) {
                stylePath = toDancer.getCustomStylePath();
            }
            interpolated.setCustomStylePath(stylePath);
            interpolated.setSelected(fromDancer.isSelected());
            result.add(interpolated);
        }

        return result;
    }

    private Formation getCurrentFormationSafely() {
        if (viewModel.getCurrentGroup() == null) return null;
        List<Formation> formations = viewModel.getCurrentGroup().getFormations();
        if (formations.isEmpty()) return null;
        int currentIndex = viewModel.getCurrentFormationIndex();
        int safeIndex = Math.max(0, Math.min(currentIndex, formations.size() - 1));
        return formations.get(safeIndex);
    }

    // 鼠标按下事件
    private void handleMousePressed(MouseEvent event) {
        if (viewModel.getCurrentGroup() == null) return;

        // 清除插值缓存
        cachedInterpolatedDancers = null;
        cachedTime = -1;
        cachedFormationIndex = -1;

        // 获取当前显示的舞者
        List<Dancer> displayDancers = getInterpolatedDancers();

        // 获取当前队形
        Formation currentFormation = getCurrentFormationSafely();
        if (currentFormation == null) return;

        double centerX = getWidth() / 2;
        double centerY = getHeight() / 2;

        // 重置拖拽状态
        hasMoved = false;

        // 创建拖拽用的舞者快照
        dragDisplayDancers = new java.util.ArrayList<>();
        for (Dancer d : displayDancers) {
            dragDisplayDancers.add(d.copy());
        }

        // 保存原始位置（用于撤销）
        originalDancerPositions = new HashMap<>();
        for (Dancer d : currentFormation.getDancers()) {
            originalDancerPositions.put(d.getId(), new float[]{d.getX(), d.getY()});
        }

        // 检测点击了哪个舞者
        for (Dancer displayDancer : displayDancers) {
            double screenX = centerX + displayDancer.getX() * scale;
            // Y轴处理（与渲染一致）
            double screenY = flipped
                ? centerY + displayDancer.getY() * scale
                : centerY - displayDancer.getY() * scale;
            double dx = event.getX() - screenX;
            double dy = event.getY() - screenY;

            if (Math.sqrt(dx * dx + dy * dy) <= 15) {
                // 在拖拽快照中查找对应舞者
                for (Dancer d : dragDisplayDancers) {
                    if (d.getId().equals(displayDancer.getId())) {
                        draggedDancer = d;
                        dragOffsetX = dx;
                        dragOffsetY = dy;
                        viewModel.toggleDancerSelection(d.getId());
                        break;
                    }
                }
                break;
            }
        }
    }

    // 鼠标拖拽事件
    private void handleMouseDragged(MouseEvent event) {
        if (draggedDancer == null) return;

        double centerX = getWidth() / 2;
        double centerY = getHeight() / 2;

        // 计算新的X坐标
        float newX = (float) ((event.getX() - dragOffsetX - centerX) / scale);

        // 计算新的Y坐标
        // 需要根据翻转状态决定Y的计算方式
        // flipped: 屏幕Y正方向 = 舞台Y正方向
        float newY;
        if (flipped) {
            // 翻转模式：直接映射
            newY = (float) (((event.getY() - dragOffsetY) - centerY) / scale);
        } else {
            // 正常模式：Y轴取反
            newY = (float) ((centerY - (event.getY() - dragOffsetY)) / scale);
        }

        // 中心线吸附
        if (showCenterLine && Math.abs(newX) < SNAP_DISTANCE) {
            newX = 0;
        }

        // 自动吸附到0.5格
        if (autoSnap) {
            newX = Math.round(newX * 2) / 2.0f;
            newY = Math.round(newY * 2) / 2.0f;
        }

        // 检查是否真的移动了（避免微小抖动触发）
        float[] originalPos = originalDancerPositions != null ? originalDancerPositions.get(draggedDancer.getId()) : null;
        if (originalPos != null) {
            float deltaX = Math.abs(newX - originalPos[0]);
            float deltaY = Math.abs(newY - originalPos[1]);
            if (deltaX > 0.1f || deltaY > 0.1f) {
                hasMoved = true;
            }
        }

        // 更新拖拽快照中的位置
        if (dragDisplayDancers != null) {
            for (Dancer d : dragDisplayDancers) {
                if (d.getId().equals(draggedDancer.getId())) {
                    d.setX(newX);
                    d.setY(newY);
                    break;
                }
            }
        }

        // 重新渲染
        render();
    }

    // 鼠标释放事件
    private void handleMouseReleased(MouseEvent event) {
        // 清除插值缓存
        cachedInterpolatedDancers = null;
        cachedTime = -1;
        cachedFormationIndex = -1;

        // 如果有拖拽且发生了移动
        if (draggedDancer != null && hasMoved && dragDisplayDancers != null) {
            // 将拖拽后的位置应用到当前队形
            Formation currentFormation = getCurrentFormationSafely();
            if (currentFormation == null) {
                draggedDancer = null;
                originalDancerPositions = null;
                dragDisplayDancers = null;
                hasMoved = false;
                return;
            }

            for (Dancer snapshotDancer : dragDisplayDancers) {
                for (Dancer currentDancer : currentFormation.getDancers()) {
                    if (currentDancer.getId().equals(snapshotDancer.getId())) {
                        currentDancer.setX(snapshotDancer.getX());
                        currentDancer.setY(snapshotDancer.getY());
                        break;
                    }
                }
            }

            // 创建关键帧（保存拖拽结果）
            long currentTime = viewModel.getCurrentTime();
            viewModel.createKeyframe(currentTime, originalDancerPositions);
        }

        // 重置拖拽状态
        draggedDancer = null;
        originalDancerPositions = null;
        dragDisplayDancers = null;
        hasMoved = false;
    }

    /**
     * 鼠标双击事件处理
     */
    private void handleMouseClicked(MouseEvent event) {
        if (event.getClickCount() == 2) {
            // 双击重置缩放
            resetZoom();
        }
    }

    /**
     * 鼠标滚轮缩放处理
     */
    private void handleScroll(javafx.scene.input.ScrollEvent event) {
        // 获取滚轮滚动量
        double deltaY = event.getDeltaY();

        if (deltaY == 0) return;

        // 计算缩放增量
        float zoomDelta = deltaY > 0 ? 0.1f : -0.1f;
        float newZoomFactor = zoomFactor + zoomDelta;

        // 限制缩放范围
        newZoomFactor = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoomFactor));

        // 应用缩放
        zoomFactor = newZoomFactor;

        // 重新渲染
        render();

        // 消费事件
        event.consume();
    }

    /**
     * 重置缩放
     */
    private void resetZoom() {
        zoomFactor = 1.0f;
        render();
    }

    /**
     * 设置视图缩放比例
     *
     */
    public void setViewScale(double scale) {
        this.zoomFactor = (float) Math.max(0.5, Math.min(2.0, scale));
        render();
    }

    /**
     * 获取当前视图缩放比例
     */
    public double getViewScale() {
        return zoomFactor;
    }

    private Image loadCustomStyleImage(String stylePath) {
        if (stylePath == null || stylePath.isBlank()) {
            return null;
        }

        File file = new File(stylePath);
        if (!file.exists() || !file.isFile()) {
            customStyleCache.remove(stylePath);
            customStyleModifiedCache.remove(stylePath);
            return null;
        }

        long lastModified = file.lastModified();
        Long cachedModified = customStyleModifiedCache.get(stylePath);
        Image cached = customStyleCache.get(stylePath);
        if (cached != null && cachedModified != null && cachedModified == lastModified) {
            return cached;
        }

        Image image = new Image(file.toURI().toString(), false);
        if (image.isError()) {
            customStyleCache.remove(stylePath);
            customStyleModifiedCache.remove(stylePath);
            return null;
        }

        customStyleCache.put(stylePath, image);
        customStyleModifiedCache.put(stylePath, lastModified);
        return image;
    }

    private javafx.scene.text.Font createStageTextFont(double size) {
        return javafx.scene.text.Font.font(resolveStageTextFontFamily(), javafx.scene.text.FontWeight.BOLD, size);
    }

    private String resolveStageTextFontFamily() {
        if (preferredTextFontFamily != null) {
            return preferredTextFontFamily;
        }

        // 优先选择常见中文字体，避免部分电脑上中文显示异常
        List<String> candidateFontFamilies = List.of(
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Source Han Sans CN",
            "WenQuanYi Micro Hei",
            "SimHei",
            "SimSun",
            "System"
        );
        List<String> availableFamilies = javafx.scene.text.Font.getFamilies();
        for (String candidate : candidateFontFamilies) {
            if ("System".equals(candidate) || availableFamilies.contains(candidate)) {
                preferredTextFontFamily = candidate;
                return preferredTextFontFamily;
            }
        }

        preferredTextFontFamily = javafx.scene.text.Font.getDefault().getFamily();
        return preferredTextFontFamily;
    }

    // 颜色转换工具
    private Color intToColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return Color.rgb(r, g, b);
    }

    // 更新舞台尺寸
    public void updateStageSize(float width, float depth) {
        this.stageWidth = width;
        this.stageDepth = depth;
        render();
    }
}
