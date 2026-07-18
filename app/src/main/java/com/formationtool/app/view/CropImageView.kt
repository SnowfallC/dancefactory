package com.formationtool.app.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val cropRect = RectF()
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var minScale = 1f
    private var maxScale = 6f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val size = min(w, h) * 0.78f
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        cropRect.set(left, top, left + size, top + size)
        fitImageToCrop()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fitImageToCrop() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) return

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, cropRect.top, shadePaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, shadePaint)
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, shadePaint)
        canvas.drawRect(0f, cropRect.bottom, w, h, shadePaint)
        canvas.drawRect(cropRect, cropPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    drawMatrix.postTranslate(dx, dy)
                    constrainToCrop()
                    imageMatrix = drawMatrix
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
        return true
    }

    fun getCroppedBitmap(outputSize: Int = 512): Bitmap {
        val src = drawableToBitmap(drawable)
        val inverse = Matrix()
        drawMatrix.invert(inverse)

        val pts = floatArrayOf(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
        inverse.mapPoints(pts)

        val left = pts[0].coerceIn(0f, src.width.toFloat() - 1f)
        val top = pts[1].coerceIn(0f, src.height.toFloat() - 1f)
        val right = pts[2].coerceIn(left + 1f, src.width.toFloat())
        val bottom = pts[3].coerceIn(top + 1f, src.height.toFloat())

        val cropW = max(1, (right - left).toInt())
        val cropH = max(1, (bottom - top).toInt())
        val cropped = Bitmap.createBitmap(src, left.toInt(), top.toInt(), cropW, cropH)
        if (cropped.width == outputSize && cropped.height == outputSize) {
            return cropped
        }
        return Bitmap.createScaledBitmap(cropped, outputSize, outputSize, true)
    }

    private fun fitImageToCrop() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || cropRect.width() <= 0f) return

        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)

        val scale = max(cropRect.width() / dw, cropRect.height() / dh)
        minScale = scale
        currentScale = scale

        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        val tx = cropRect.centerX() - (dw * scale) / 2f
        val ty = cropRect.centerY() - (dh * scale) / 2f
        drawMatrix.postTranslate(tx, ty)
        constrainToCrop()
        imageMatrix = drawMatrix
        invalidate()
    }

    private fun constrainToCrop() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)

        var dx = 0f
        var dy = 0f
        if (rect.left > cropRect.left) dx = cropRect.left - rect.left
        if (rect.top > cropRect.top) dy = cropRect.top - rect.top
        if (rect.right < cropRect.right) dx = cropRect.right - rect.right
        if (rect.bottom < cropRect.bottom) dy = cropRect.bottom - rect.bottom
        drawMatrix.postTranslate(dx, dy)
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: 1
        val h = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: 1
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable?.setBounds(0, 0, w, h)
        drawable?.draw(canvas)
        return bitmap
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nextScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = nextScale / currentScale
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = nextScale
            constrainToCrop()
            imageMatrix = drawMatrix
            invalidate()
            return true
        }
    }
}
