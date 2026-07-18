package com.formationtool.app.view

import android.content.Context
import android.graphics.*
import android.text.InputFilter
import android.text.InputType
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * PS风格的颜色选择器
 * 包含：正方形饱和度/明度选择器 + 色相条 + 颜色输入框
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Canvas视图
    private val canvasView: CanvasView

    // 颜色输入框
    private val colorInput: EditText

    // 当前颜色
    private var currentColor: Int = Color.RED

    // HSV值
    private val currentHSV = floatArrayOf(0f, 1f, 1f)

    // 颜色变化监听器
    var onColorChanged: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(20, 20, 20, 20)

        // 创建Canvas视图
        canvasView = CanvasView(context)
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // 创建颜色输入框
        colorInput = EditText(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20
            }
            hint = "#FFFFFF"
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7))  // #RRGGBB
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#2A2A3F"))

            // 监听输入变化
            setOnEditorActionListener { _, _, _ ->
                val colorStr = text.toString().trim()
                if (colorStr.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                    try {
                        val color = Color.parseColor(colorStr)
                        setColor(color)
                        onColorChanged?.invoke(color)
                    } catch (e: Exception) {
                        // 无效颜色
                    }
                }
                false
            }
        }
        addView(colorInput)

        // 初始化颜色
        updateColorInput()
    }

    /**
     * 设置当前颜色
     */
    fun setColor(color: Int) {
        currentColor = color
        Color.colorToHSV(color, currentHSV)
        canvasView.setHSV(currentHSV)
        updateColorInput()
        invalidate()
    }

    /**
     * 获取当前颜色
     */
    fun getColor(): Int = currentColor

    /**
     * 更新颜色输入框
     */
    private fun updateColorInput() {
        val hexColor = String.format("#%06X", 0xFFFFFF and currentColor)
        if (colorInput.text.toString().uppercase() != hexColor) {
            colorInput.setText(hexColor)
        }
    }

    /**
     * 内部Canvas视图
     */
    inner class CanvasView(context: Context) : View(context) {

        // 绘制画笔
        private val satValRectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hueBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val previewBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 区域定义
        private var satValRect = RectF()
        private var hueBarRect = RectF()
        private var previewRect = RectF()

        // 游标位置
        private var satValIndicatorX = 0f
        private var satValIndicatorY = 0f
        private var hueIndicatorY = 0f

        init {
            indicatorPaint.style = Paint.Style.STROKE
            indicatorPaint.strokeWidth = 4f
            indicatorPaint.color = Color.WHITE

            previewBorderPaint.style = Paint.Style.STROKE
            previewBorderPaint.strokeWidth = 2f
            previewBorderPaint.color = Color.WHITE
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)

            val padding = 20f
            val hueBarWidth = 50f
            val previewHeight = 80f
            val spacing = 20f

            // 计算可用空间
            val availableWidth = width - padding * 2
            val availableHeight = height - padding * 2 - previewHeight - spacing

            // 正方形尺寸（取较小值，确保能放下）
            val squareSize = (availableWidth - hueBarWidth - spacing).coerceAtMost(availableHeight)

            // 计算整体内容宽度（正方形+间距+色相条）
            val totalContentWidth = squareSize + spacing + hueBarWidth

            // 居中计算起始位置
            val startX = (width - totalContentWidth) / 2

            // 正方形饱和度/明度选择器（居中）
            satValRect.set(
                startX,
                padding,
                startX + squareSize,
                padding + squareSize
            )

            // 色相条（正方形右侧）
            hueBarRect.set(
                satValRect.right + spacing,
                padding,
                satValRect.right + spacing + hueBarWidth,
                satValRect.bottom
            )

            // 颜色预览（底部居中）
            val previewWidth = squareSize * 0.8f
            val previewX = (width - previewWidth) / 2
            previewRect.set(
                previewX,
                satValRect.bottom + spacing,
                previewX + previewWidth,
                satValRect.bottom + spacing + previewHeight
            )

            updateIndicatorPositions()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 绘制饱和度/明度正方形
            drawSatValRect(canvas)

            // 绘制色相条
            drawHueBar(canvas)

            // 绘制预览
            drawPreview(canvas)

            // 绘制游标
            canvas.drawCircle(satValIndicatorX, satValIndicatorY, 10f, indicatorPaint)

            // 绘制色相条游标（横线）
            canvas.drawLine(
                hueBarRect.left - 5f,
                hueIndicatorY,
                hueBarRect.right + 5f,
                hueIndicatorY,
                indicatorPaint
            )
        }

        private fun drawSatValRect(canvas: Canvas) {
            // 当前色相的纯色
            val pureColor = Color.HSVToColor(floatArrayOf(currentHSV[0], 1f, 1f))

            // 方法1：先绘制纯色背景
            val bgPaint = Paint()
            bgPaint.color = pureColor
            canvas.drawRect(satValRect, bgPaint)

            // 方法2：从右到左叠加白色渐变（降低饱和度）
            val satShader = LinearGradient(
                satValRect.right, 0f, satValRect.left, 0f,
                0x00FFFFFF.toInt(), 0xFFFFFFFF.toInt(),
                Shader.TileMode.CLAMP
            )
            val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            satPaint.shader = satShader
            canvas.drawRect(satValRect, satPaint)

            // 方法3：从上到下叠加白色到透明白色渐变（降低明度）
            // 顶部白色（明度低），底部透明白色（明度高）
            val valShader = LinearGradient(
                0f, satValRect.top, 0f, satValRect.bottom,
                0xFFFFFFFF.toInt(), 0x00FFFFFF.toInt(),
                Shader.TileMode.CLAMP
            )
            val valPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            valPaint.shader = valShader
            valPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            canvas.drawRect(satValRect, valPaint)
        }

        private fun drawHueBar(canvas: Canvas) {
            // 垂直渐变：从红色到红色（完整色环）
            val hueShader = LinearGradient(
                0f, hueBarRect.top, 0f, hueBarRect.bottom,
                intArrayOf(
                    Color.RED,      // 0度
                    Color.YELLOW,   // 60度
                    Color.GREEN,    // 120度
                    Color.CYAN,     // 180度
                    Color.BLUE,     // 240度
                    Color.MAGENTA,  // 300度
                    Color.RED       // 360度
                ),
                null,
                Shader.TileMode.CLAMP
            )

            hueBarPaint.shader = hueShader
            canvas.drawRect(hueBarRect, hueBarPaint)
        }

        private fun drawPreview(canvas: Canvas) {
            previewPaint.color = currentColor
            canvas.drawRect(previewRect, previewPaint)
            canvas.drawRect(previewRect, previewBorderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y

                    // 判断触摸区域
                    if (satValRect.contains(x, y)) {
                        updateSatVal(x, y)
                        return true
                    } else if (hueBarRect.contains(x, y)) {
                        updateHue(y)
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        private fun updateSatVal(x: Float, y: Float) {
            val sat = ((x - satValRect.left) / satValRect.width()).coerceIn(0f, 1f)
            val value = 1f - ((y - satValRect.top) / satValRect.height()).coerceIn(0f, 1f)

            currentHSV[1] = sat
            currentHSV[2] = value

            satValIndicatorX = x.coerceIn(satValRect.left, satValRect.right)
            satValIndicatorY = y.coerceIn(satValRect.top, satValRect.bottom)

            updateColor()
        }

        private fun updateHue(y: Float) {
            val hue = ((y - hueBarRect.top) / hueBarRect.height() * 360f).coerceIn(0f, 360f)
            currentHSV[0] = hue

            hueIndicatorY = y.coerceIn(hueBarRect.top, hueBarRect.bottom)

            updateColor()
        }

        private fun updateColor() {
            currentColor = Color.HSVToColor(currentHSV)
            this@ColorPickerView.currentColor = currentColor
            updateColorInput()
            invalidate()
            onColorChanged?.invoke(currentColor)
        }

        private fun updateIndicatorPositions() {
            // 更新饱和度/明度游标
            satValIndicatorX = satValRect.left + satValRect.width() * currentHSV[1]
            satValIndicatorY = satValRect.top + satValRect.height() * (1f - currentHSV[2])

            // 更新色相条游标
            hueIndicatorY = hueBarRect.top + hueBarRect.height() * (currentHSV[0] / 360f)
        }

        fun setHSV(hsv: FloatArray) {
            currentHSV[0] = hsv[0]
            currentHSV[1] = hsv[1]
            currentHSV[2] = hsv[2]
            updateIndicatorPositions()
            invalidate()
        }
    }
}
