package com.formationtool.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

class ExportPreviewView(context: Context) : View(context) {
    private val paint = Paint().apply {
        isAntiAlias = true
    }

    private var layoutMode = 1  // 0=single, 1=horizontal split, 2=vertical split
    private var primaryView = 0 // 0=2D, 1=3D, 2=reference video
    private var secondaryView = 1
    private var viewScale = 1.0f
    private var isFlipped = false

    private val bgColor = Color.parseColor("#2D2D3D")
    private val view2DColor = Color.parseColor("#4A90D9")
    private val view3DColor = Color.parseColor("#5CB85C")
    private val refVideoColor = Color.parseColor("#D9534F")
    private val borderColor = Color.parseColor("#555555")

    fun updatePreview(
        layoutMode: Int,
        primaryView: Int,
        secondaryView: Int,
        scale: Float = 1.0f,
        flipped: Boolean = false
    ) {
        this.layoutMode = layoutMode
        this.primaryView = primaryView
        this.secondaryView = secondaryView
        this.viewScale = scale
        this.isFlipped = flipped
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        val width: Int
        val height: Int

        when (layoutMode) {
            2 -> {
                width = (parentWidth * 0.8f).toInt()
                height = (width * 16f / 9f).toInt()
                if (height > parentHeight) {
                    val adjustedHeight = parentHeight
                    val adjustedWidth = (adjustedHeight * 9f / 16f).toInt()
                    setMeasuredDimension(adjustedWidth, adjustedHeight)
                    return
                }
            }
            else -> {
                width = parentWidth
                height = (width * 9f / 16f).toInt()
                if (height > parentHeight) {
                    val adjustedHeight = parentHeight
                    val adjustedWidth = (adjustedHeight * 16f / 9f).toInt()
                    setMeasuredDimension(adjustedWidth, adjustedHeight)
                    return
                }
            }
        }

        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 8f

        paint.color = bgColor
        canvas.drawRoundRect(padding, padding, w - padding, h - padding, 8f, 8f, paint)

        paint.color = borderColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(padding, padding, w - padding, h - padding, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        val innerPadding = padding + 4f
        when (layoutMode) {
            0 -> drawViewArea(canvas, innerPadding, innerPadding, w - innerPadding * 2, h - innerPadding * 2, primaryView)
            1 -> {
                val halfW = (w - innerPadding * 2 - 4) / 2
                drawViewArea(canvas, innerPadding, innerPadding, halfW, h - innerPadding * 2, primaryView)
                drawViewArea(canvas, innerPadding + halfW + 4, innerPadding, halfW, h - innerPadding * 2, secondaryView)
            }
            2 -> {
                val halfH = (h - innerPadding * 2 - 4) / 2
                drawViewArea(canvas, innerPadding, innerPadding, w - innerPadding * 2, halfH, primaryView)
                drawViewArea(canvas, innerPadding, innerPadding + halfH + 4, w - innerPadding * 2, halfH, secondaryView)
            }
        }

        if (viewScale != 1.0f) {
            paint.color = Color.parseColor("#80000000")
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(w - 80f, h - 35f, w - 10f, h - 10f, 4f, 4f, paint)

            paint.color = Color.WHITE
            paint.textSize = 16f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(String.format("%.1fx", viewScale), w - 45f, h - 18f, paint)
        }
    }

    private fun drawViewArea(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, viewType: Int) {
        if (viewType == 2) {
            paint.color = refVideoColor
            paint.alpha = 180
            canvas.drawRoundRect(x, y, x + w, y + h, 4f, 4f, paint)
            paint.alpha = 255

            paint.color = Color.WHITE
            paint.textSize = 28f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("REF", x + w / 2, y + h / 2 + 10f, paint)
            return
        }

        if (viewType == 0) {
            paint.color = view2DColor
            paint.alpha = 180
            canvas.drawRoundRect(x, y, x + w, y + h, 4f, 4f, paint)
            paint.alpha = 255

            paint.color = Color.WHITE
            paint.alpha = 60
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f

            val centerX = x + w / 2
            val centerY = y + h / 2
            val stageW = w * 0.6f * viewScale
            val stageH = h * 0.5f * viewScale

            for (i in 0..4) {
                val ratio = i / 4f
                val lineY = centerY - stageH / 2 + ratio * stageH
                canvas.drawLine(centerX - stageW / 2, lineY, centerX + stageW / 2, lineY, paint)
                val lineX = centerX - stageW / 2 + ratio * stageW
                canvas.drawLine(lineX, centerY - stageH / 2, lineX, centerY + stageH / 2, paint)
            }

            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.color = Color.WHITE
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("2D", x + w / 2, y + 30f, paint)
            drawStageOrientationLabels(canvas, x, y, w, h)
            return
        }

        paint.color = view3DColor
        paint.alpha = 180
        canvas.drawRoundRect(x, y, x + w, y + h, 4f, 4f, paint)
        paint.alpha = 255

        paint.color = Color.WHITE
        paint.alpha = 80
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f

        val centerX = x + w / 2
        val centerY = y + h / 2
        val gridSize = min(w, h) * 0.5f * viewScale

        for (i in 0 until 5) {
            val ratio = i / 4f
            val lineY = centerY - gridSize / 2 + ratio * gridSize
            val lineW = gridSize * (0.3f + ratio * 0.7f)
            canvas.drawLine(centerX - lineW / 2, lineY, centerX + lineW / 2, lineY, paint)
        }

        for (i in 0 until 5) {
            val ratio = (i - 2) / 2f
            val topW = gridSize * 0.3f
            val bottomW = gridSize
            val x1 = centerX + ratio * topW
            val x2 = centerX + ratio * bottomW
            canvas.drawLine(x1, centerY - gridSize / 2, x2, centerY + gridSize / 2, paint)
        }

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = Color.WHITE
        paint.textSize = 24f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("3D 45°", x + w / 2, y + 30f, paint)
        drawStageOrientationLabels(canvas, x, y, w, h)
    }

    private fun drawStageOrientationLabels(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val topLabel = if (isFlipped) "观众" else "后台"
        val bottomLabel = if (isFlipped) "后台" else "观众"

        paint.color = Color.WHITE
        paint.alpha = 210
        paint.textSize = 12f
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(topLabel, x + w - 8f, y + 16f, paint)
        canvas.drawText(bottomLabel, x + w - 8f, y + h - 8f, paint)
        paint.alpha = 255
    }
}
