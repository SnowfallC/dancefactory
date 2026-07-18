package com.formationtool.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.formationtool.app.model.Formation
import kotlin.math.abs

/**
 * 音频波形视图
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val waveformPaint = Paint().apply {
        color = 0x804A9EFF.toInt()  // 半透明蓝色
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val backgroundPaint = Paint().apply {
        color = 0xFF1E1E28.toInt()
        style = Paint.Style.FILL
    }

    private val centerLinePaint = Paint().apply {
        color = 0xFF3A3A4F.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val hintTextPaint = Paint().apply {
        color = 0xFF888899.toInt()  // 灰色提示文字
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // 保持片段的背景色（绿色半透明）
    private val holdRegionPaint = Paint().apply {
        color = 0x4000FF00.toInt()  // 绿色半透明 - 阵型保持不动
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 变换片段的背景色（红色半透明）
    private val transitionRegionPaint = Paint().apply {
        color = 0x40FF4444.toInt()  // 红色半透明 - 阵型变换中
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 片段边界线
    private val regionBorderPaint = Paint().apply {
        color = 0x80FFFFFF.toInt()  // 半透明白色
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    var waveformData: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    var zoomLevel: Float = 1f
        set(value) {
            field = value.coerceIn(1f, 15f)
            invalidate()
        }

    // 关键帧数据（用于绘制保持区和变换区）
    var formations: List<Formation> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    // 音频总时长（用于计算位置）
    var audioDuration: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    // 显式设置的宽度（解决布局更新延迟导致触摸计算不准确的问题）
    // 当设置了此值时，优先使用此值而不是 view.width
    private var explicitWidth: Int = 0

    /**
     * 获取有效宽度（优先使用显式设置的宽度，否则使用实际测量宽度）
     * 这解决了 layoutParams.width 设置后，view.width 还没更新的问题
     */
    private fun getEffectiveWidth(): Float {
        return if (explicitWidth > 0) explicitWidth.toFloat() else width.toFloat()
    }

    /**
     * 设置显式宽度（在 updateTimelineZoom 中调用）
     * 这确保了触摸计算使用正确的宽度，即使布局还没完成
     */
    fun setExplicitWidth(w: Int) {
        explicitWidth = w
        invalidate()
    }

    // ========== F键标记预览 ==========
    private var fKeyMarkStartTime = -1L  // F键按下的开始时间
    private var fKeyMarkEndTime = -1L    // F键松开的时间（红色区开始）
    private var isFKeyCurrentlyPressed = false  // F键是否当前按下
    private var currentPlayTime = 0L     // 当前播放时间（用于实时预览）

    // F键标记高亮画笔
    private val fKeyGreenPaint = Paint().apply {
        color = 0x8000FF00.toInt()  // 更高亮的绿色
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val fKeyRedPaint = Paint().apply {
        color = 0x80FF4444.toInt()  // 更高亮的红色
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 边界调整回调（绿色后边界）
    var onBoundaryAdjust: ((formationIndex: Int, isHoldBoundary: Boolean, newDuration: Long) -> Unit)? = null

    // 起始边界调整回调（绿色前边界，移动关键帧位置）
    var onStartBoundaryAdjust: ((formationIndex: Int, newTimestamp: Long) -> Unit)? = null

    // 长按区域回调（长按绿色/红色区域调整时长）
    var onRegionLongPress: ((formationIndex: Int, isHoldRegion: Boolean) -> Unit)? = null

    // 点击导入音频回调（没有音频时触发）
    var onClickToImport: (() -> Unit)? = null

    // 边界拖动开始回调（用于保存撤销快照）
    var onBoundaryDragStart: (() -> Unit)? = null

    // 拖动状态
    private var isDraggingBoundary = false
    private var draggingFormationIndex = -1
    private var isDraggingStartBoundary = false  // true=拖动绿色前边界(timestamp), false=拖动绿色后边界(holdDuration结束)
    private var isDraggingHoldBoundary = false  // 兼容旧代码
    private var dragStartX = 0f
    private var dragStartDuration = 0L
    private var dragStartTimestamp = 0L  // 拖动前边界时的原始 timestamp
    private var lastCallbackTime = 0L  // 上次回调时间（用于节流）
    private val callbackThrottleMs = 50L  // 回调节流间隔（毫秒）

    // 长按检测
    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val longPressThreshold = 800L  // 长按阈值（毫秒）- 增加到800ms避免与拖动冲突
    private val moveThreshold = 15f  // 移动阈值（像素）
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        if (!isDraggingBoundary) {
            handleLongPress(touchDownX, touchDownY)
            longPressTriggered = true
        }
    }

    // 边界点击容差（像素）
    private val boundaryTouchTolerance = 30f

    // 拖动最小移动距离（像素）- 防止点击时触摸抖动导致意外移动
    private val minDragDistance = 5f
    private var hasDraggedBeyondThreshold = false  // 是否已经超过最小移动距离

    /**
     * 取消待处理的长按（供父容器调用）
     */
    fun cancelPendingLongPress() {
        removeCallbacks(longPressRunnable)
        longPressTriggered = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val centerY = viewHeight / 2

        // 绘制背景
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, backgroundPaint)

        // 先绘制保持区和变换区（在波形下层）
        if (formations.isNotEmpty() && audioDuration > 0) {
            drawRegions(canvas, viewWidth, viewHeight)
        }

        // 绘制F键标记的临时区域（实时预览）
        if (fKeyMarkStartTime >= 0 && audioDuration > 0) {
            drawFKeyMarkRegion(canvas, viewWidth, viewHeight)
        }

        // 绘制中心线
        canvas.drawLine(0f, centerY, viewWidth, centerY, centerLinePaint)

        // 绘制波形或提示文字
        if (waveformData.isNotEmpty()) {
            drawWaveform(canvas, viewWidth, viewHeight, centerY)
        } else {
            // 没有波形数据时显示提示
            canvas.drawText("点击导入音频", viewWidth / 2, centerY + 16f, hintTextPaint)
        }
    }

    /**
     * 绘制保持区和变换区
     * 前红后绿逻辑：
     * - 红色区范围: [timestamp - duration, timestamp) - 前红，从前一帧过渡到当前帧
     * - 绿色区范围: [timestamp, timestamp + holdDuration) - 后绿，保持当前帧
     */
    private fun drawRegions(canvas: Canvas, width: Float, height: Float) {
        if (audioDuration <= 0 || width <= 0) return

        formations.forEachIndexed { index, formation ->
            val timestamp = formation.timestamp
            val duration = formation.duration  // 前红
            val holdDuration = formation.holdDuration  // 后绿

            // 红色区起始位置（前红）- 第一帧无红色区
            val redStartTime = timestamp - duration
            val redStartX = (redStartTime.toFloat() / audioDuration * width)
            val timestampX = (timestamp.toFloat() / audioDuration * width)

            // 绿色区结束位置（后绿）
            val greenEndTime = timestamp + holdDuration
            val greenEndX = (greenEndTime.toFloat() / audioDuration * width)

            // 绘制红色区（前红）- 第一帧无红色区
            if (index > 0 && duration > 0) {
                canvas.drawRect(
                    redStartX,
                    0f,
                    timestampX,
                    height,
                    transitionRegionPaint
                )
                // 绘制红色区左边界线
                canvas.drawLine(redStartX, 0f, redStartX, height, regionBorderPaint)
            }

            // 绘制绿色区（后绿）
            if (holdDuration > 0) {
                canvas.drawRect(
                    timestampX,
                    0f,
                    greenEndX,
                    height,
                    holdRegionPaint
                )
                // 绘制绿色区右边界线
                canvas.drawLine(greenEndX, 0f, greenEndX, height, regionBorderPaint)
            }

            // 绘制 timestamp 分界线（红绿分界）
            canvas.drawLine(timestampX, 0f, timestampX, height, regionBorderPaint)
        }
    }

    /**
     * 绘制F键标记的临时区域（实时预览）
     */
    private fun drawFKeyMarkRegion(canvas: Canvas, width: Float, height: Float) {
        if (audioDuration <= 0) return

        if (isFKeyCurrentlyPressed) {
            // F键按下中 - 绘制从开始时间到当前时间的绿色区（高亮）
            val startX = (fKeyMarkStartTime.toFloat() / audioDuration * width)
            val endX = (currentPlayTime.toFloat() / audioDuration * width)

            if (endX > startX) {
                val rectStartX = startX.coerceAtLeast(0f)
                val rectEndX = endX.coerceAtMost(width)
                if (rectStartX < rectEndX) {
                    canvas.drawRect(rectStartX, 0f, rectEndX, height, fKeyGreenPaint)
                }
            }
        } else if (fKeyMarkEndTime > 0) {
            // F键已松开 - 绘制绿色区 + 红色区
            // 绿色区：fKeyMarkStartTime 到 fKeyMarkEndTime
            val greenStartX = (fKeyMarkStartTime.toFloat() / audioDuration * width)
            val greenEndX = (fKeyMarkEndTime.toFloat() / audioDuration * width)

            if (greenEndX > greenStartX) {
                val rectStartX = greenStartX.coerceAtLeast(0f)
                val rectEndX = greenEndX.coerceAtMost(width)
                if (rectStartX < rectEndX) {
                    canvas.drawRect(rectStartX, 0f, rectEndX, height, fKeyGreenPaint)
                }
            }

            // 红色区：fKeyMarkEndTime 到当前时间
            val redStartX = (fKeyMarkEndTime.toFloat() / audioDuration * width)
            val redEndX = (currentPlayTime.toFloat() / audioDuration * width)

            if (redEndX > redStartX) {
                val rectStartX = redStartX.coerceAtLeast(0f)
                val rectEndX = redEndX.coerceAtMost(width)
                if (rectStartX < rectEndX) {
                    canvas.drawRect(rectStartX, 0f, rectEndX, height, fKeyRedPaint)
                }
            }
        }
    }

    private fun drawWaveform(canvas: Canvas, width: Float, height: Float, centerY: Float) {
        if (waveformData.isEmpty() || width <= 0) return

        val amplitudeScale = height * 0.45f
        val dataSize = waveformData.size

        // 每个像素对应多少数据点
        val samplesPerPixel = (dataSize / width).coerceAtLeast(1f)

        var x = 0f
        while (x < width) {
            // 计算当前位置对应的数据索引
            val dataIndex = ((x / width) * dataSize).toInt().coerceIn(0, dataSize - 1)
            val amplitude = waveformData[dataIndex] * amplitudeScale

            // 绘制垂直条（上下对称）
            canvas.drawLine(
                x, centerY - amplitude,
                x, centerY + amplitude,
                waveformPaint
            )

            x += 2f // 每2像素绘制一条
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 没有音频时，点击任意位置触发导入
        if (waveformData.isEmpty()) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    return true  // 消费事件以接收ACTION_UP
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    // 如果没有移动太多，视为点击
                    if (kotlin.math.sqrt(dx * dx + dy * dy) < moveThreshold) {
                        onClickToImport?.invoke()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        // 有音频时的正常处理逻辑
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = System.currentTimeMillis()
                touchDownX = event.x
                touchDownY = event.y
                longPressTriggered = false

                // 检测是否点击到边界
                val hitBoundary = handleTouchDown(event.x, event.y)
                if (hitBoundary) {
                    // 点到边界，拦截事件，进入拖动模式
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                // 没有点到边界，启动长按检测，但不拦截事件
                postDelayed(longPressRunnable, longPressThreshold)
                // 返回false，让父容器处理滚动
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingBoundary) {
                    // 拖动边界模式：取消长按并处理拖动
                    removeCallbacks(longPressRunnable)
                    handleTouchMove(event.x)
                    return true
                }

                // 如果移动距离超过阈值，取消长按
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (kotlin.math.sqrt(dx * dx + dy * dy) > moveThreshold) {
                    removeCallbacks(longPressRunnable)
                }
                return false  // 让父容器处理滚动
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 无论如何都取消长按
                removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)

                if (isDraggingBoundary) {
                    handleTouchUp()
                    return true
                }

                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }

                return false
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 处理长按，检测长按的是哪个区域
     * 前红后绿逻辑：
     * - 红色区: [timestamp - duration, timestamp)
     * - 绿色区: [timestamp, timestamp + holdDuration)
     * 注意：从后往前遍历，与电脑端保持一致
     */
    private fun handleLongPress(x: Float, y: Float) {
        if (formations.isEmpty() || audioDuration <= 0) return

        val viewWidth = getEffectiveWidth()

        // 从后往前遍历（与电脑端保持一致）
        for (index in formations.indices.reversed()) {
            val formation = formations[index]
            val timestamp = formation.timestamp
            val duration = formation.duration
            val holdDuration = formation.holdDuration

            // 红色区范围（前红）
            val redStartTime = timestamp - duration
            val redStartX = (redStartTime.toFloat() / audioDuration * viewWidth)
            val timestampX = (timestamp.toFloat() / audioDuration * viewWidth)

            // 绿色区范围（后绿）
            val greenEndTime = timestamp + holdDuration
            val greenEndX = (greenEndTime.toFloat() / audioDuration * viewWidth)

            // 检测是否长按在红色区（前红）- 第一帧无红色区
            if (index > 0 && duration > 0 && x >= redStartX && x < timestampX) {
                onRegionLongPress?.invoke(index, false)  // false = 红色区（变换区）
                return
            }

            // 检测是否长按在绿色区（后绿）
            if (holdDuration > 0 && x >= timestampX && x < greenEndX) {
                onRegionLongPress?.invoke(index, true)  // true = 绿色区（保持区）
                return
            }
        }
    }

    /**
     * 处理触摸开始，检测是否点击到边界
     * 绿色前边界 = 关键帧timestamp（可移动，除了第一个关键帧）
     * 绿色后边界 = timestamp + holdDuration（可拖动改变保持时长）
     * 红色区是自动填充区，不可拖动
     * 注意：从后往前遍历，与电脑端保持一致
     */
    private fun handleTouchDown(x: Float, y: Float): Boolean {
        if (formations.isEmpty() || audioDuration <= 0) return false

        val viewWidth = getEffectiveWidth()

        // 重置拖动状态
        hasDraggedBeyondThreshold = false

        // 从后往前遍历（与电脑端保持一致）
        for (index in formations.indices.reversed()) {
            val formation = formations[index]
            val startTime = formation.timestamp
            val holdEndTime = startTime + formation.holdDuration
            val startX = (startTime.toFloat() / audioDuration * viewWidth)
            val holdEndX = (holdEndTime.toFloat() / audioDuration * viewWidth)

            // 检测绿色前边界（timestamp）- 第一个关键帧(00:00)不能移动
            if (index > 0 && abs(x - startX) < boundaryTouchTolerance) {
                // 通知外部保存撤销快照
                onBoundaryDragStart?.invoke()

                isDraggingBoundary = true
                draggingFormationIndex = index
                isDraggingStartBoundary = true
                isDraggingHoldBoundary = false
                dragStartX = x
                dragStartTimestamp = startTime
                return true
            }

            // 检测绿色后边界（holdDuration结束位置）
            if (abs(x - holdEndX) < boundaryTouchTolerance) {
                // 通知外部保存撤销快照
                onBoundaryDragStart?.invoke()

                isDraggingBoundary = true
                draggingFormationIndex = index
                isDraggingStartBoundary = false
                isDraggingHoldBoundary = true
                dragStartX = x
                dragStartDuration = formation.holdDuration
                return true
            }
        }

        return false
    }

    /**
     * 处理拖动
     */
    private fun handleTouchMove(x: Float) {
        if (draggingFormationIndex < 0 || draggingFormationIndex >= formations.size) return

        val viewWidth = getEffectiveWidth()
        val deltaX = x - dragStartX

        // 检查是否超过最小移动距离（防止点击时触摸抖动导致意外移动）
        if (!hasDraggedBeyondThreshold) {
            if (abs(deltaX) < minDragDistance) {
                return  // 还没有超过最小移动距离，不触发回调
            }
            hasDraggedBeyondThreshold = true  // 标记已超过阈值
        }

        // 计算时间偏移（根据拖动距离）
        val deltaDuration = ((deltaX / viewWidth) * audioDuration).toLong()

        // 节流回调：只在距离上次回调超过50ms时触发
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCallbackTime >= callbackThrottleMs) {
            lastCallbackTime = currentTime

            if (isDraggingStartBoundary) {
                // 拖动绿色前边界（移动关键帧位置）
                val newTimestamp = (dragStartTimestamp + deltaDuration).coerceAtLeast(0L)
                onStartBoundaryAdjust?.invoke(draggingFormationIndex, newTimestamp)
            } else {
                // 拖动绿色后边界（改变 holdDuration）
                val newDuration = (dragStartDuration + deltaDuration).coerceAtLeast(0L)
                onBoundaryAdjust?.invoke(draggingFormationIndex, true, newDuration)
            }
        }

        // 即时重绘，提供视觉反馈
        invalidate()
    }

    /**
     * 处理拖动结束
     */
    private fun handleTouchUp() {
        isDraggingBoundary = false
        draggingFormationIndex = -1
        isDraggingStartBoundary = false
        isDraggingHoldBoundary = false
        hasDraggedBeyondThreshold = false
    }

    // ========== F键标记接口 ==========

    /**
     * F键按下 - 开始绿色区标记
     */
    fun onFKeyPressed(currentTime: Long) {
        isFKeyCurrentlyPressed = true
        fKeyMarkStartTime = currentTime
        fKeyMarkEndTime = -1
        this.currentPlayTime = currentTime
        invalidate()
    }

    /**
     * F键松开 - 结束绿色区，开始红色区
     */
    fun onFKeyReleased(currentTime: Long) {
        isFKeyCurrentlyPressed = false
        fKeyMarkEndTime = currentTime
        this.currentPlayTime = currentTime
        invalidate()
    }

    /**
     * 清除F键标记（扫描序列结束时调用）
     */
    fun clearFKeyMark() {
        isFKeyCurrentlyPressed = false
        fKeyMarkStartTime = -1
        fKeyMarkEndTime = -1
        invalidate()
    }

    /**
     * 更新当前播放时间（用于F键标记实时预览）
     */
    fun updateCurrentPlayTime(time: Long) {
        this.currentPlayTime = time
        // 只有在F键标记激活时才重绘
        if (fKeyMarkStartTime >= 0) {
            invalidate()
        }
    }
}