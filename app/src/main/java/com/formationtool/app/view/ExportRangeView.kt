package com.formationtool.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 导出视频入点/出点选择条
 * 显示在波形下方，可以拖动入点和出点标记
 */
class ExportRangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 显式设置的宽度（解决缩放后布局延迟导致的触摸坐标偏差）
    private var explicitWidth: Int = 0

    // 入点和出点时间（毫秒）
    var inPointMs: Long = 0L
        set(value) {
            field = value.coerceIn(0L, audioDuration)
            invalidate()
        }

    var outPointMs: Long = 0L
        set(value) {
            field = value.coerceIn(0L, audioDuration)
            invalidate()
        }

    // 音频总时长
    var audioDuration: Long = 0L
        set(value) {
            field = value
            if (outPointMs == 0L || outPointMs > value) {
                outPointMs = value
            }
            invalidate()
        }

    // 回调
    var onRangeChanged: ((inMs: Long, outMs: Long) -> Unit)? = null

    // 绘制相关
    private val backgroundPaint = Paint().apply {
        color = 0xFF252530.toInt()
        style = Paint.Style.FILL
    }

    private val selectedRangePaint = Paint().apply {
        color = 0x401976D2.toInt()  // 半透明蓝色
        style = Paint.Style.FILL
    }

    private val markerPaint = Paint().apply {
        color = 0xFF1976D2.toInt()  // 蓝色
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val markerFillPaint = Paint().apply {
        color = 0xFF1976D2.toInt()
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = 0xFFAAAAAA.toInt()
        textSize = 20f
        isAntiAlias = true
    }

    // 拖动状态
    private var isDraggingInPoint = false
    private var isDraggingOutPoint = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val touchTolerance = 40f  // 触摸容差（像素）

    /**
     * 获取有效宽度（优先使用显式宽度）
     */
    private fun getEffectiveWidth(): Float {
        return if (explicitWidth > 0) explicitWidth.toFloat() else width.toFloat()
    }

    /**
     * 设置显式宽度（在时间轴缩放后立即同步）
     */
    fun setExplicitWidth(w: Int) {
        explicitWidth = w
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = getEffectiveWidth()
        val viewHeight = height.toFloat()

        // 绘制背景
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, backgroundPaint)

        if (audioDuration <= 0) {
            // 没有音频时显示提示
            canvas.drawText("导入音频后可设置导出范围", viewWidth / 2, viewHeight / 2 + 8f, labelTextPaint)
            return
        }

        // 计算入点和出点位置
        val inPointX = (inPointMs.toFloat() / audioDuration * viewWidth).coerceIn(0f, viewWidth)
        val outPointX = (outPointMs.toFloat() / audioDuration * viewWidth).coerceIn(0f, viewWidth)

        // 绘制选中范围（蓝色半透明区域）
        canvas.drawRect(inPointX, 0f, outPointX, viewHeight, selectedRangePaint)

        // 绘制入点标记（左侧，绿色）
        drawMarker(canvas, inPointX, viewHeight, "入点", 0xFF4CAF50.toInt(), inPointMs)

        // 绘制出点标记（右侧，红色）
        drawMarker(canvas, outPointX, viewHeight, "出点", 0xFFF44336.toInt(), outPointMs)
    }

    private fun drawMarker(canvas: Canvas, x: Float, height: Float, label: String, color: Int, timeMs: Long) {
        // 垂直线
        val linePaint = Paint().apply {
            this.color = color
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(x, 0f, x, height, linePaint)

        // 顶部三角形标记
        val triangleSize = 12f
        val trianglePaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val path = android.graphics.Path().apply {
            moveTo(x, 0f)
            lineTo(x - triangleSize, triangleSize)
            lineTo(x + triangleSize, triangleSize)
            close()
        }
        canvas.drawPath(path, trianglePaint)

        // 时间文字（在标记上方）
        val timeText = formatTime(timeMs)
        val timePaint = Paint().apply {
            this.color = 0xFFFFFFFF.toInt()
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(timeText, x, height - 10f, timePaint)

        // 标签文字
        val labelPaint = Paint().apply {
            this.color = color
            textSize = 18f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText(label, x, height - 30f, labelPaint)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (audioDuration <= 0) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y

                // 检测是否点击到入点或出点标记
                val viewWidth = getEffectiveWidth()
                val inPointX = inPointMs.toFloat() / audioDuration * viewWidth
                val outPointX = outPointMs.toFloat() / audioDuration * viewWidth

                when {
                    abs(event.x - inPointX) < touchTolerance -> {
                        isDraggingInPoint = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    abs(event.x - outPointX) < touchTolerance -> {
                        isDraggingOutPoint = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    else -> return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingInPoint || isDraggingOutPoint) {
                    val viewWidth = getEffectiveWidth()
                    if (viewWidth <= 0f) return true
                    val newTimeMs = (event.x / viewWidth * audioDuration).toLong().coerceIn(0L, audioDuration)

                    if (isDraggingInPoint) {
                        inPointMs = newTimeMs.coerceAtMost(outPointMs - 100)  // 至少间隔0.1秒
                        onRangeChanged?.invoke(inPointMs, outPointMs)
                    } else if (isDraggingOutPoint) {
                        outPointMs = newTimeMs.coerceAtLeast(inPointMs + 100)  // 至少间隔0.1秒
                        onRangeChanged?.invoke(inPointMs, outPointMs)
                    }

                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDraggingInPoint = false
                isDraggingOutPoint = false
                return true
            }
        }

        return super.onTouchEvent(event)
    }
}
