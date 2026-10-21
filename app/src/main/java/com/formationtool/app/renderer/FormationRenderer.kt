package com.formationtool.app.renderer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.formationtool.app.model.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

/**
 * 队形渲染器 - 使用OpenGL ES 2.0渲染3D/2D舞台
 */
class FormationRenderer : GLSurfaceView.Renderer {

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var circleShape: CircleShape? = null
    private var ringShape: RingShape? = null  // 选中边框
    private var gridLines: GridLines? = null
    private var lineShape: LineShape? = null
    private var numberRenderer: NumberRenderer? = null
    private var needsGridUpdate = false  // 标记是否需要更新网格

    var cameraView = CameraView()
    // 主线程发布独立快照，渲染线程不得遍历正在编辑的集合。
    @Volatile var currentFormation: Formation? = null
        set(value) { field = value?.renderSnapshot() }
    @Volatile var nextFormation: Formation? = null
        set(value) { field = value?.renderSnapshot() }
    @Volatile var transitionProgress: Float = 0f  // 0-1之间的过渡进度
    var backstageVisible = true
    private var backstageRenderer: BackstageRenderer? = null
    var stageWidth: Float = 16f
    var stageDepth: Float = 12f
    var is2DMode: Boolean = false
    var showDancerNames: Boolean = false
    var showCenterLine: Boolean = false  // 是否显示C位线
    var flipped: Boolean = false

    companion object {
        private const val SNAP_DISTANCE = 0.3f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 设置背景色
        GLES20.glClearColor(0.1f, 0.1f, 0.15f, 1.0f)

        // 启用深度测试
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // 启用混合（透明度）
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // 初始化形状
        backstageRenderer = BackstageRenderer()
        circleShape = CircleShape()
        ringShape = RingShape()
        gridLines = GridLines(stageWidth, stageDepth)
        lineShape = LineShape()

        // 初始化数字渲染器
        numberRenderer = NumberRenderer()
        numberRenderer?.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)

        val ratio: Float = width.toFloat() / height.toFloat()

        // 设置投影矩阵
        Matrix.perspectiveM(projectionMatrix, 0, cameraView.fov, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 清除缓冲区
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 根据需要更新网格
        if (needsGridUpdate) {
            gridLines = GridLines(stageWidth, stageDepth)
            needsGridUpdate = false
        }

        // 设置相机视图矩阵
        updateViewMatrix()

        // 计算VP矩阵
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 绘制网格
        gridLines?.draw(vpMatrix)

        if (backstageVisible) backstageRenderer?.draw(vpMatrix, viewMatrix, stageWidth, stageDepth, numberRenderer)

        // 绘制中心线
        if (showCenterLine) {
            drawCenterLine()
        }

        // 绘制舞台标签
        drawStageLabels()

        // 绘制舞者
        drawDancers()

        // 绘制道具
        drawProps()

        // 绘制过渡路径线
        if (nextFormation != null && transitionProgress > 0f && transitionProgress < 1f) {
            drawTransitionLines()
        }
    }

    /**
     * 更新相机视图矩阵
     */
    private fun updateViewMatrix() {
        // 计算俯仰角和偏航角的弧度
        val pitchRad = Math.toRadians(-cameraView.pitch.toDouble())
        val yawRad = Math.toRadians(cameraView.yaw.toDouble())

        // 使用球坐标系计算相机位置
        // eye位置 = 距离 * (cos俯仰 * sin偏航, sin俯仰, cos俯仰 * cos偏航)
        val eyeX = (cameraView.z * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (cameraView.z * sin(pitchRad)).toFloat()
        val eyeZ = (cameraView.z * cos(pitchRad) * cos(yawRad)).toFloat()

        // 设置上方向向量
        // 默认Y轴朝上
        // 除非俯仰角接近正负90度（垂直俯仰）
        var upX = 0f
        var upY = 1f
        var upZ = 0f

        if (abs(cameraView.pitch) > 85f) {
            // 接近垂直时，切换上方向为Z轴
            upY = 0f
            upZ = if (cameraView.pitch < 0) -1f else 1f
        }

        // 使用观察点偏移（targetX, targetY）
        // 相机位置和观察点同时偏移
        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX + cameraView.targetX, eyeY, eyeZ - cameraView.targetY,  // 相机位置偏移
            cameraView.targetX, 0f, -cameraView.targetY,         // 观察点偏移
            upX, upY, upZ
        )
    }

    /**
     * 绘制舞台标签（观众/后台）
     */
    private fun drawStageLabels() {
        val halfDepth = stageDepth / 2

        // 先按舞台语义坐标定义位置，再统一转换成渲染坐标，避免翻转逻辑不一致。
        val audienceStageY = halfDepth + 1.5f
        val backstageStageY = -(halfDepth + 1.5f)
        val audienceRenderY = if (flipped) -audienceStageY else audienceStageY
        val backstageRenderY = if (flipped) -backstageStageY else backstageStageY

        numberRenderer?.drawLabel(
            vpMatrix = vpMatrix,
            viewMatrix = viewMatrix,
            labelIndex = 1,  // 1:观众
            x = 0f,
            y = audienceRenderY,
            z = 0f,
            scale = 0.6f
        )

        numberRenderer?.drawLabel(
            vpMatrix = vpMatrix,
            viewMatrix = viewMatrix,
            labelIndex = 0,  // 0:后台
            x = 0f,
            y = backstageRenderY,
            z = 0f,
            scale = 0.6f
        )
    }

    /**
     * 绘制中心线
     */
    private fun drawCenterLine() {
        val halfDepth = stageDepth / 2

        // 在舞台中央画一条竖线
        lineShape?.drawLine(
            vpMatrix,
            0f, 0.01f, halfDepth,
            0f, 0.01f, -halfDepth,
            floatArrayOf(1f, 0.84f, 0f, 0.9f),  // 金黄色
            6f  // 线宽
        )
    }

    /**
     * 绘制舞者
     * 支持过渡动画插值和自定义样式
     */
    private fun drawDancers() {
        val current = currentFormation ?: return
        val next = nextFormation

        if (current.dancers.isEmpty()) {
            android.util.Log.w("FormationRenderer", "current formation has no dancers")
            return
        }

        val dancerPositions = mutableListOf<Triple<Float, Float, Float>>()
        val hasCustomStyles = mutableListOf<Boolean>()

        current.dancers.forEach { dancer ->
            val x: Float
            val y: Float
            val z: Float

            if (next != null && transitionProgress >= 0f) {
                val nextDancer = next.dancers.find { it.id == dancer.id }
                if (nextDancer != null) {
                    val safeProgress = transitionProgress.coerceIn(0f, 1f)
                    x = lerp(dancer.x, nextDancer.x, safeProgress)
                    y = lerp(dancer.y, nextDancer.y, safeProgress)
                    z = lerp(dancer.z, nextDancer.z, safeProgress)
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

            dancerPositions.add(Triple(x, y, z))

            val customStylePath = dancer.customStylePath
            val hasCustomStyle = !customStylePath.isNullOrEmpty() && File(customStylePath).exists()
            hasCustomStyles.add(hasCustomStyle)

            val renderY = if (flipped) -y else y

            if (hasCustomStyle) {
                numberRenderer?.drawCustomStyle(
                    vpMatrix = vpMatrix,
                    viewMatrix = viewMatrix,
                    imagePath = customStylePath!!,
                    x = x,
                    y = renderY,
                    z = z,
                    scale = 0.8f * dancer.scale
                )
            } else {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, x, z, renderY)

                if (is2DMode) {
                    Matrix.rotateM(modelMatrix, 0, 90f, 1f, 0f, 0f)
                    Matrix.scaleM(modelMatrix, 0, 0.5f * dancer.scale, 0.5f * dancer.scale, 0.01f)
                } else {
                    Matrix.scaleM(modelMatrix, 0, 0.5f * dancer.scale, 0.5f * dancer.scale, 0.5f * dancer.scale)
                }

                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

                val a = ((dancer.color shr 24) and 0xFF) / 255f
                val r = ((dancer.color shr 16) and 0xFF) / 255f
                val g = ((dancer.color shr 8) and 0xFF) / 255f
                val b = (dancer.color and 0xFF) / 255f
                val finalAlpha = if (a < 0.1f) 1.0f else a

                circleShape?.draw(mvpMatrix, floatArrayOf(r, g, b, finalAlpha))
            }

            if (dancer.isSelected) {
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, x, z, renderY)

                if (is2DMode) {
                    Matrix.rotateM(modelMatrix, 0, 90f, 1f, 0f, 0f)
                    Matrix.scaleM(modelMatrix, 0, 0.6f * dancer.scale, 0.6f * dancer.scale, 0.01f)
                } else {
                    Matrix.scaleM(modelMatrix, 0, 0.6f * dancer.scale, 0.6f * dancer.scale, 0.6f * dancer.scale)
                }

                Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
                ringShape?.draw(mvpMatrix, floatArrayOf(1f, 1f, 1f, 1f), 4f)
            }
        }

        current.dancers.forEachIndexed { index, dancer ->
            val pos = dancerPositions.getOrNull(index) ?: return@forEachIndexed
            val renderY = if (flipped) -pos.second else pos.second
            val hasCustomStyle = hasCustomStyles.getOrNull(index) == true

            if (showDancerNames) {
                numberRenderer?.drawDancerName(
                    vpMatrix,
                    viewMatrix,
                    dancer.name,
                    pos.first,
                    renderY,
                    pos.third + 0.3f,
                    0.6f
                )
            } else if (!hasCustomStyle) {
                val number = index + 1
                numberRenderer?.drawNumber(
                    vpMatrix,
                    viewMatrix,
                    number,
                    pos.first,
                    renderY,
                    pos.third,
                    0.5f
                )
            }
        }
    }
    private fun drawProps() {
        val formation = currentFormation ?: return

        formation.props.forEach { prop ->
            Matrix.setIdentityM(modelMatrix, 0)
            val renderY = if (flipped) -prop.y else prop.y
            Matrix.translateM(modelMatrix, 0, prop.x, prop.z, renderY)
            Matrix.rotateM(modelMatrix, 0, prop.rotation, 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, prop.width, 0.1f, prop.height)

            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            val r = ((prop.color shr 16) and 0xFF) / 255f
            val g = ((prop.color shr 8) and 0xFF) / 255f
            val b = (prop.color and 0xFF) / 255f
            val a = ((prop.color shr 24) and 0xFF) / 255f

            // 根据道具类型选择绘制方式
            when (prop.type) {
                PropType.CIRCLE -> circleShape?.draw(mvpMatrix, floatArrayOf(r, g, b, a * 0.5f))
                else -> circleShape?.draw(mvpMatrix, floatArrayOf(r, g, b, a * 0.5f))
            }
        }
    }

    /**
     * 绘制过渡路径线（前后帧之间的连线）
     */
    private fun drawTransitionLines() {
        val current = currentFormation ?: return
        val next = nextFormation ?: return

        // 遍历每个舞者，绘制到下一帧的路径
        current.dancers.forEach { dancer ->
            val nextDancer = next.dancers.find { it.id == dancer.id } ?: return@forEach

            // 只当移动距离大于阈值时才画线
            val dx = nextDancer.x - dancer.x
            val dy = nextDancer.y - dancer.y
            val dz = nextDancer.z - dancer.z
            val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

            if (distance > 0.1f) {
                val fromRenderY = if (flipped) -dancer.y else dancer.y
                val toRenderY = if (flipped) -nextDancer.y else nextDancer.y
                lineShape?.drawLine(
                    vpMatrix,
                    dancer.x, dancer.z, fromRenderY,
                    nextDancer.x, nextDancer.z, toRenderY,
                    floatArrayOf(1f, 1f, 1f, 0.3f)
                )
            }
        }
    }

    /**
     * 线性插值
     */
    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    /**
     * 更新舞台尺寸
     */
    fun updateStageSize(width: Float, depth: Float) {
        stageWidth = width
        stageDepth = depth
        needsGridUpdate = true
    }
}
