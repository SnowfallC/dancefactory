package com.formationtool.app.renderer

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * 圆形形状 - 用于表示舞者
 */
class CircleShape {
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val program: Int
    private var vertexBuffer: FloatBuffer
    private val vertexCount: Int
    private val vertexStride = 3 * 4 // 3个float，每个4字节

    init {
        // 创建圆形顶点
        val segments = 32
        val vertices = mutableListOf<Float>()

        // 中心点
        vertices.add(0f)
        vertices.add(0f)
        vertices.add(0f)

        // 圆周点
        for (i in 0..segments) {
            val angle = 2.0 * Math.PI * i / segments
            vertices.add(cos(angle).toFloat())
            vertices.add(sin(angle).toFloat())
            vertices.add(0f)
        }

        vertexCount = vertices.size / 3

        // 初始化顶点缓冲
        val bb = ByteBuffer.allocateDirect(vertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(vertices.toFloatArray())
        vertexBuffer.position(0)

        // 编译着色器
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        // 创建程序
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(mvpMatrix: FloatArray, color: FloatArray) {
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, vertexStride, vertexBuffer)

        val colorHandle = GLES20.glGetUniformLocation(program, "vColor")
        GLES20.glUniform4fv(colorHandle, 1, color, 0)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}

/**
 * 圆环形状 - 用于表示选中状态的白色边框
 */
class RingShape {
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val program: Int
    private var vertexBuffer: FloatBuffer
    private val vertexCount: Int
    private val vertexStride = 3 * 4

    init {
        // 创建圆环顶点（线条构成的圆环）
        val segments = 32
        val vertices = mutableListOf<Float>()

        for (i in 0..segments) {
            val angle = 2.0 * Math.PI * i / segments
            vertices.add(cos(angle).toFloat())
            vertices.add(sin(angle).toFloat())
            vertices.add(0f)
        }

        vertexCount = vertices.size / 3

        val bb = ByteBuffer.allocateDirect(vertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(vertices.toFloatArray())
        vertexBuffer.position(0)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(mvpMatrix: FloatArray, color: FloatArray, lineWidth: Float = 3f) {
        GLES20.glUseProgram(program)
        GLES20.glLineWidth(lineWidth)

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, vertexStride, vertexBuffer)

        val colorHandle = GLES20.glGetUniformLocation(program, "vColor")
        GLES20.glUniform4fv(colorHandle, 1, color, 0)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}

/**
 * 网格线 - 用于表示舞台
 */
class GridLines(private val width: Float, private val depth: Float) {
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val program: Int
    private var mainVertexBuffer: FloatBuffer  // 整格实线
    private val mainVertexCount: Int
    private var halfVertexBuffer: FloatBuffer  // 半格虚线
    private val halfVertexCount: Int

    init {
        val mainVertices = mutableListOf<Float>()
        val halfVertices = mutableListOf<Float>()
        val gridSpacing = 1f
        val halfGridSpacing = 0.5f
        val halfWidth = width / 2
        val halfDepth = depth / 2

        // 整格横向线
        var x = -halfWidth
        while (x <= halfWidth) {
            mainVertices.add(x)
            mainVertices.add(0f)
            mainVertices.add(-halfDepth)

            mainVertices.add(x)
            mainVertices.add(0f)
            mainVertices.add(halfDepth)

            x += gridSpacing
        }

        // 整格纵向线
        var z = -halfDepth
        while (z <= halfDepth) {
            mainVertices.add(-halfWidth)
            mainVertices.add(0f)
            mainVertices.add(z)

            mainVertices.add(halfWidth)
            mainVertices.add(0f)
            mainVertices.add(z)

            z += gridSpacing
        }

        // 半格横向虚线（在整格中间）
        x = -halfWidth + halfGridSpacing
        while (x < halfWidth) {
            halfVertices.add(x)
            halfVertices.add(0f)
            halfVertices.add(-halfDepth)

            halfVertices.add(x)
            halfVertices.add(0f)
            halfVertices.add(halfDepth)

            x += gridSpacing
        }

        // 半格纵向虚线（在整格中间）
        z = -halfDepth + halfGridSpacing
        while (z < halfDepth) {
            halfVertices.add(-halfWidth)
            halfVertices.add(0f)
            halfVertices.add(z)

            halfVertices.add(halfWidth)
            halfVertices.add(0f)
            halfVertices.add(z)

            z += gridSpacing
        }

        mainVertexCount = mainVertices.size / 3
        halfVertexCount = halfVertices.size / 3

        // 主网格线缓冲区
        val bb = ByteBuffer.allocateDirect(mainVertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        mainVertexBuffer = bb.asFloatBuffer()
        mainVertexBuffer.put(mainVertices.toFloatArray())
        mainVertexBuffer.position(0)

        // 半格线缓冲区
        val bb2 = ByteBuffer.allocateDirect(halfVertices.size * 4)
        bb2.order(ByteOrder.nativeOrder())
        halfVertexBuffer = bb2.asFloatBuffer()
        halfVertexBuffer.put(halfVertices.toFloatArray())
        halfVertexBuffer.position(0)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val colorHandle = GLES20.glGetUniformLocation(program, "vColor")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        // 先绘制半格虚线（更淡的颜色）
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, halfVertexBuffer)
        GLES20.glUniform4fv(colorHandle, 1, floatArrayOf(0.22f, 0.22f, 0.26f, 0.4f), 0)
        GLES20.glLineWidth(1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, halfVertexCount)

        // 再绘制整格实线
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, mainVertexBuffer)
        GLES20.glUniform4fv(colorHandle, 1, floatArrayOf(0.3f, 0.3f, 0.35f, 0.5f), 0)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, mainVertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}

/**
 * 线条形状 - 用于绘制轨迹
 */
class LineShape {
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val program: Int

    init {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    fun drawLine(
        mvpMatrix: FloatArray,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        color: FloatArray
    ) {
        drawLine(mvpMatrix, x1, y1, z1, x2, y2, z2, color, 2f)
    }

    fun drawLine(
        mvpMatrix: FloatArray,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        color: FloatArray,
        lineWidth: Float
    ) {
        val vertices = floatArrayOf(x1, y1, z1, x2, y2, z2)

        val bb = ByteBuffer.allocateDirect(vertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(vertices)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glLineWidth(lineWidth)

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        val colorHandle = GLES20.glGetUniformLocation(program, "vColor")
        GLES20.glUniform4fv(colorHandle, 1, color, 0)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
