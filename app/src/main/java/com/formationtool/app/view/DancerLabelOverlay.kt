package com.formationtool.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.Matrix as GlMatrix
import android.util.AttributeSet
import android.view.View
import com.formationtool.app.model.CameraView
import com.formationtool.app.model.Dancer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 舞者编号覆盖层
 * 在 GLSurfaceView 上方绘制舞者编号，与 OpenGL 渲染器同步
 */
class DancerLabelOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }

    // 舞者数据
    private var dancers: List<Dancer> = emptyList()
    private var nextDancers: List<Dancer>? = null
    private var transitionProgress: Float = 0f

    // 相机参数
    private var cameraView: CameraView = CameraView()
    private var isFlipped: Boolean = false

    // OpenGL 矩阵
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val resultVec = FloatArray(4)
    private val inputVec = FloatArray(4)

    /**
     * 更新舞者数据
     */
    fun updateDancers(
        currentDancers: List<Dancer>,
        nextDancers: List<Dancer>? = null,
        progress: Float = 0f,
        camera: CameraView,
        flipped: Boolean = false
    ) {
        this.dancers = currentDancers
        this.nextDancers = nextDancers
        this.transitionProgress = progress
        this.cameraView = camera
        this.isFlipped = flipped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (dancers.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0) return

        // 设置投影矩阵（与渲染器一致）
        val ratio = viewWidth / viewHeight
        GlMatrix.perspectiveM(projectionMatrix, 0, cameraView.fov, ratio, 0.1f, 100f)

        // 设置视图矩阵（与渲染器一致）
        updateViewMatrix()

        // 计算 VP 矩阵
        GlMatrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        dancers.forEach { dancer ->
            // 计算插值位置
            val x: Float
            val y: Float
            val z: Float

            if (nextDancers != null && transitionProgress > 0f) {
                val nextDancer = nextDancers?.find { it.id == dancer.id }
                if (nextDancer != null) {
                    x = lerp(dancer.x, nextDancer.x, transitionProgress)
                    y = lerp(dancer.y, nextDancer.y, transitionProgress)
                    z = lerp(dancer.z, nextDancer.z, transitionProgress)
                } else {
                    x = dancer.x
                    y = dancer.y
                    z = dancer.z
                }
            } else {
                x = dancer.x
                y = dancer.y
                z = dancer.z
            }

            // 将世界坐标转换为屏幕坐标
            val screenPos = worldToScreen(x, y, z, viewWidth, viewHeight)

            if (screenPos != null) {
                val (screenX, screenY) = screenPos

                // 提取编号
                val number = dancer.name.replace("舞者", "").ifEmpty { "?" }

                // 绘制背景圆
                val textWidth = textPaint.measureText(number)
                val radius = (textWidth / 2 + 8).coerceAtLeast(16f)
                canvas.drawCircle(screenX, screenY, radius, bgPaint)

                // 绘制编号文字
                val textY = screenY + textPaint.textSize / 3
                canvas.drawText(number, screenX, textY, textPaint)
            }
        }
    }

    /**
     * 更新相机视图矩阵（与 FormationRenderer 完全一致）
     */
    private fun updateViewMatrix() {
        val pitchRad = Math.toRadians(-cameraView.pitch.toDouble())
        val yawRad = Math.toRadians(cameraView.yaw.toDouble())

        val eyeX = (cameraView.z * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (cameraView.z * sin(pitchRad)).toFloat()
        val eyeZ = (cameraView.z * cos(pitchRad) * cos(yawRad)).toFloat()

        var upX = 0f
        var upY = 1f
        var upZ = 0f

        if (abs(cameraView.pitch) > 85f) {
            upY = 0f
            upZ = if (cameraView.pitch < 0) -1f else 1f
        }

        // 使用观察点偏移（targetX, targetY）
        GlMatrix.setLookAtM(
            viewMatrix, 0,
            eyeX + cameraView.targetX, eyeY, eyeZ - cameraView.targetY,
            cameraView.targetX, 0f, -cameraView.targetY,
            upX, upY, upZ
        )
    }

    /**
     * 将世界坐标转换为屏幕坐标（使用 OpenGL 矩阵运算）
     */
    private fun worldToScreen(x: Float, y: Float, z: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float>? {
        // 设置模型矩阵（与渲染器一致：Y 和 Z 互换，并考虑翻转）
        GlMatrix.setIdentityM(modelMatrix, 0)
        val renderY = if (isFlipped) -y else y
        GlMatrix.translateM(modelMatrix, 0, x, z, renderY)

        // 计算 MVP 矩阵
        GlMatrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        // 将原点变换到屏幕坐标
        inputVec[0] = 0f
        inputVec[1] = 0f
        inputVec[2] = 0f
        inputVec[3] = 1f

        GlMatrix.multiplyMV(resultVec, 0, mvpMatrix, 0, inputVec, 0)

        // 透视除法
        if (resultVec[3] <= 0) return null

        val ndcX = resultVec[0] / resultVec[3]
        val ndcY = resultVec[1] / resultVec[3]
        val ndcZ = resultVec[2] / resultVec[3]

        // 检查是否在可见范围内
        if (ndcZ < -1 || ndcZ > 1) return null
        if (ndcX < -1.5f || ndcX > 1.5f || ndcY < -1.5f || ndcY > 1.5f) return null

        // 转换为屏幕坐标
        val screenX = (ndcX + 1f) / 2f * viewWidth
        val screenY = (1f - ndcY) / 2f * viewHeight  // Y轴反转

        return Pair(screenX, screenY)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * t
    }
}
