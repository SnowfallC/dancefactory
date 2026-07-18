package com.formationtool.app.export

import android.opengl.GLES20
import com.formationtool.app.model.CameraView
import com.formationtool.app.model.Formation
import java.io.File

class OffscreenRenderer(
    private val width: Int,
    private val height: Int,
    private val stageWidth: Float,
    private val stageDepth: Float,
    private val useFBO: Boolean = true  // 是否使用FBO，导出时设为false
) {
    private var frameBufferId = IntArray(1)
    private var textureId = IntArray(1)
    private var depthBufferId = IntArray(1)

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var cameraView = CameraView()
    private var is2DMode = false
    private var flipped = false  // 是否翻转方向
    private var showDancerNames = true
    private var currentFormation: Formation? = null
    private var nextFormation: Formation? = null
    private var transitionProgress = 0f

    // Shader程序
    private var circleProgram = 0
    private var gridProgram = 0

    // 数字渲染器
    private var numberRenderer: com.formationtool.app.renderer.NumberRenderer? = null

    fun init() {
        if (useFBO) {
            // 创建帧缓冲对象（FBO）
            GLES20.glGenFramebuffers(1, frameBufferId, 0)
            GLES20.glGenTextures(1, textureId, 0)
            GLES20.glGenRenderbuffers(1, depthBufferId, 0)

            // 配置纹理
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            // 配置深度缓冲
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthBufferId[0])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height)

            // 组装FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId[0], 0)
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthBufferId[0])
        }

        // 初始化着色器
        initShaders()

        // 初始化数字渲染器
        numberRenderer = com.formationtool.app.renderer.NumberRenderer()
        numberRenderer?.initialize()

        // 设置投影矩阵
        val ratio = width.toFloat() / height.toFloat()
        // Keep the same default field of view as the on-screen renderer.
        // A hard-coded 60° here made exported framing differ from preview.
        android.opengl.Matrix.perspectiveM(projectionMatrix, 0, cameraView.fov, ratio, 0.1f, 100f)
    }

    private fun initShaders() {
        // 圆形着色器
        val circleVertexShader = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
            }
        """.trimIndent()

        val circleFragmentShader = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent()

        circleProgram = createProgram(circleVertexShader, circleFragmentShader)
        gridProgram = circleProgram
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vertexShader, vertexCode)
        GLES20.glCompileShader(vertexShader)

        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fragmentShader, fragmentCode)
        GLES20.glCompileShader(fragmentShader)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        return program
    }

    fun setCameraView(camera: CameraView) {
        this.cameraView = camera
        val ratio = width.toFloat() / height.toFloat()
        android.opengl.Matrix.perspectiveM(projectionMatrix, 0, camera.fov, ratio, 0.1f, 100f)
    }

    fun setIs2DMode(is2D: Boolean) {
        this.is2DMode = is2D
    }

    fun setFlipped(flip: Boolean) {
        this.flipped = flip
    }

    fun setShowDancerNames(show: Boolean) {
        this.showDancerNames = show
    }

    fun setFormation(current: Formation?, next: Formation?, progress: Float) {
        this.currentFormation = current
        this.nextFormation = next
        this.transitionProgress = progress
    }

    fun render() {
        if (useFBO) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId[0])
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        GLES20.glViewport(0, 0, width, height)

        GLES20.glClearColor(0.1f, 0.1f, 0.15f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // 更新视图矩阵
        updateViewMatrix()
        android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 绘制网格
        drawGrid()

        // 绘制舞者
        drawDancers()
        drawDancerNumbersWithTexture()
    }

    /**
     * 直接渲染到屏幕（不使用FBO）
     */
    fun renderDirect() {
        // 清除缓冲区
        GLES20.glClearColor(0.1f, 0.1f, 0.15f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // 更新视图矩阵
        updateViewMatrix()
        android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 绘制网格
        drawGrid()

        // 绘制舞者
        drawDancers()

        // 绘制舞者编号
        drawDancerNumbersWithTexture()
    }

    private fun updateViewMatrix() {
        val pitchRad = Math.toRadians(-cameraView.pitch.toDouble())
        val yawRad = Math.toRadians(cameraView.yaw.toDouble())

        val eyeX = (cameraView.z * kotlin.math.cos(pitchRad) * kotlin.math.sin(yawRad)).toFloat()
        val eyeY = (cameraView.z * kotlin.math.sin(pitchRad)).toFloat()
        val eyeZ = (cameraView.z * kotlin.math.cos(pitchRad) * kotlin.math.cos(yawRad)).toFloat()

        var upX = 0f
        var upY = 1f
        var upZ = 0f

        if (kotlin.math.abs(cameraView.pitch) > 85f) {
            upY = 0f
            upZ = if (cameraView.pitch < 0) -1f else 1f
        }

        // 使用观察点偏移（targetX, targetY）
        android.opengl.Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX + cameraView.targetX, eyeY, eyeZ - cameraView.targetY,
            cameraView.targetX, 0f, -cameraView.targetY,
            upX, upY, upZ
        )
    }

    private fun drawGrid() {
        GLES20.glUseProgram(gridProgram)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(gridProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(gridProgram, "uColor")
        val positionHandle = GLES20.glGetAttribLocation(gridProgram, "aPosition")

        // 网格线颜色
        val gridColor = floatArrayOf(0.3f, 0.3f, 0.35f, 1.0f)
        GLES20.glUniform4fv(colorHandle, 1, gridColor, 0)

        val halfWidth = stageWidth / 2
        val halfDepth = stageDepth / 2

        // 绘制边框
        val borderVertices = floatArrayOf(
            -halfWidth, 0f, -halfDepth,
            halfWidth, 0f, -halfDepth,
            halfWidth, 0f, halfDepth,
            -halfWidth, 0f, halfDepth,
            -halfWidth, 0f, -halfDepth
        )

        val borderBuffer = java.nio.ByteBuffer.allocateDirect(borderVertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(borderVertices)
        borderBuffer.position(0)

        android.opengl.Matrix.setIdentityM(modelMatrix, 0)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, borderBuffer)
        GLES20.glLineWidth(4f)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, 5)

        // 半格网格线
        val halfGridLines = mutableListOf<Float>()

        // 竖线（半格偏移）
        var x = -halfWidth + 0.5f
        while (x < halfWidth) {
            halfGridLines.addAll(listOf(
                x, 0f, -halfDepth,
                x, 0f, halfDepth
            ))
            x += 1f
        }

        // 横线（半格偏移）
        var z = -halfDepth + 0.5f
        while (z < halfDepth) {
            halfGridLines.addAll(listOf(
                -halfWidth, 0f, z,
                halfWidth, 0f, z
            ))
            z += 1f
        }

        if (halfGridLines.isNotEmpty()) {
            val halfGridBuffer = java.nio.ByteBuffer.allocateDirect(halfGridLines.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(halfGridLines.toFloatArray())
            halfGridBuffer.position(0)

            // 半格网格线颜色
            val halfGridColor = floatArrayOf(0.18f, 0.18f, 0.22f, 0.6f)
            GLES20.glUniform4fv(colorHandle, 1, halfGridColor, 0)

            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, halfGridBuffer)
            GLES20.glLineWidth(1.5f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, halfGridLines.size / 3)
        }

        // 整格网格线
        val gridLines = mutableListOf<Float>()

        // 竖线（沿Z轴）
        x = -halfWidth + 1f
        while (x < halfWidth) {
            gridLines.addAll(listOf(
                x, 0f, -halfDepth,
                x, 0f, halfDepth
            ))
            x += 1f
        }

        // 横线（沿X轴）
        z = -halfDepth + 1f
        while (z < halfDepth) {
            gridLines.addAll(listOf(
                -halfWidth, 0f, z,
                halfWidth, 0f, z
            ))
            z += 1f
        }

        if (gridLines.isNotEmpty()) {
            val gridBuffer = java.nio.ByteBuffer.allocateDirect(gridLines.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(gridLines.toFloatArray())
            gridBuffer.position(0)

            // 整格网格线颜色
            val lightGridColor = floatArrayOf(0.25f, 0.25f, 0.3f, 0.8f)
            GLES20.glUniform4fv(colorHandle, 1, lightGridColor, 0)

            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, gridBuffer)
            GLES20.glLineWidth(2.5f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLines.size / 3)
        }

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawDancers() {
        val formation = currentFormation ?: return

        GLES20.glUseProgram(circleProgram)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(circleProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(circleProgram, "uColor")
        val positionHandle = GLES20.glGetAttribLocation(circleProgram, "aPosition")

        val segments = 32
        val circleVertices = FloatArray((segments + 2) * 3)
        circleVertices[0] = 0f
        circleVertices[1] = 0f
        circleVertices[2] = 0f

        for (i in 0..segments) {
            val angle = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
            circleVertices[(i + 1) * 3] = kotlin.math.cos(angle)
            circleVertices[(i + 1) * 3 + 1] = kotlin.math.sin(angle)
            circleVertices[(i + 1) * 3 + 2] = 0f
        }

        val circleBuffer = java.nio.ByteBuffer.allocateDirect(circleVertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(circleVertices)
        circleBuffer.position(0)

        formation.dancers.forEach { dancer ->
            val x: Float
            val y: Float
            val z: Float

            if (nextFormation != null && transitionProgress >= 0f) {
                val nextDancer = nextFormation?.dancers?.find { it.id == dancer.id }
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

            val renderX = if (flipped) -x else x
            val renderY = if (flipped) -y else y
            val customStylePath = dancer.customStylePath
            val hasCustomStyle = !customStylePath.isNullOrEmpty() && File(customStylePath).exists()

            if (hasCustomStyle) {
                numberRenderer?.drawCustomStyle(
                    vpMatrix = vpMatrix,
                    viewMatrix = viewMatrix,
                    imagePath = customStylePath!!,
                    x = renderX,
                    y = renderY,
                    z = z,
                    scale = 0.8f * dancer.scale
                )
                return@forEach
            }

            android.opengl.Matrix.setIdentityM(modelMatrix, 0)
            android.opengl.Matrix.translateM(modelMatrix, 0, renderX, z, renderY)

            if (is2DMode) {
                android.opengl.Matrix.rotateM(modelMatrix, 0, 90f, 1f, 0f, 0f)
            }
            android.opengl.Matrix.scaleM(modelMatrix, 0, 0.5f * dancer.scale, 0.5f * dancer.scale, 0.5f * dancer.scale)

            android.opengl.Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

            val r = ((dancer.color shr 16) and 0xFF) / 255f
            val g = ((dancer.color shr 8) and 0xFF) / 255f
            val b = (dancer.color and 0xFF) / 255f
            GLES20.glUniform4f(colorHandle, r, g, b, 1f)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, circleBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, segments + 2)
            GLES20.glDisableVertexAttribArray(positionHandle)
        }
    }

    private fun drawDancerNumbersWithTexture() {
        val formation = currentFormation ?: return
        val renderer = numberRenderer ?: return

        formation.dancers.forEachIndexed { index, dancer ->
            val x: Float
            val y: Float
            val z: Float

            if (nextFormation != null && transitionProgress >= 0f) {
                val nextDancer = nextFormation?.dancers?.find { it.id == dancer.id }
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

            val renderX = if (flipped) -x else x
            val renderY = if (flipped) -y else y
            val customStylePath = dancer.customStylePath
            val hasCustomStyle = !customStylePath.isNullOrEmpty() && File(customStylePath).exists()

            if (showDancerNames) {
                renderer.drawDancerName(
                    vpMatrix,
                    viewMatrix,
                    dancer.name,
                    renderX,
                    renderY,
                    z + 0.3f,
                    0.6f
                )
            } else if (!hasCustomStyle) {
                val number = (index + 1).coerceIn(0, 19)
                renderer.drawNumber(
                    vpMatrix,
                    viewMatrix,
                    number,
                    renderX,
                    renderY,
                    z,
                    0.5f
                )
            }
        }
    }
    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    fun readPixels(buffer: IntArray) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId[0])

        val pixelBuffer = java.nio.ByteBuffer.allocateDirect(width * height * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asIntBuffer()

        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
        pixelBuffer.position(0)
        pixelBuffer.get(buffer)

        // 翻转像素（OpenGL坐标系与图像坐标系Y轴相反）
        flipVertically(buffer, width, height)
    }

    private fun flipVertically(buffer: IntArray, width: Int, height: Int) {
        val temp = IntArray(width)
        for (y in 0 until height / 2) {
            val topRow = y * width
            val bottomRow = (height - 1 - y) * width
            System.arraycopy(buffer, topRow, temp, 0, width)
            System.arraycopy(buffer, bottomRow, buffer, topRow, width)
            System.arraycopy(temp, 0, buffer, bottomRow, width)
        }
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, frameBufferId, 0)
        GLES20.glDeleteTextures(1, textureId, 0)
        GLES20.glDeleteRenderbuffers(1, depthBufferId, 0)
        GLES20.glDeleteProgram(circleProgram)
        numberRenderer?.release()
    }
}
