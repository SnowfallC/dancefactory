package com.formationtool.app.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 数字渲染器 - 在 OpenGL 中渲染数字编号
 * 使用纹理贴图方式实现
 */
class NumberRenderer {

    private var program: Int = 0
    private var textureIds: IntArray = IntArray(20)  // 0-19 的数字纹理
    private var labelTextureIds: IntArray = IntArray(3)  // 0:后台，1:观众，2:台下
    private val dancerNameTextures = mutableMapOf<String, Int>()  // 舞者名字纹理缓存
    private val customStyleTextures = mutableMapOf<String, Int>()  // 自定义样式纹理缓存
    private var vertexBuffer: FloatBuffer
    private var texCoordBuffer: FloatBuffer

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform sampler2D uTexture;
        varying vec2 vTexCoord;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            if (color.a < 0.01) discard;
            gl_FragColor = color;
        }
    """.trimIndent()

    // 正方形顶点（面向相机的广告牌）
    private val squareCoords = floatArrayOf(
        -0.5f,  0.5f, 0.0f,   // 左上
        -0.5f, -0.5f, 0.0f,   // 左下
         0.5f, -0.5f, 0.0f,   // 右下
         0.5f,  0.5f, 0.0f    // 右上
    )

    private val texCoords = floatArrayOf(
        0.0f, 0.0f,   // 左上
        0.0f, 1.0f,   // 左下
        1.0f, 1.0f,   // 右下
        1.0f, 0.0f    // 右上
    )

    private val drawOrder = shortArrayOf(0, 1, 2, 0, 2, 3)

    init {
        // 初始化顶点缓冲
        vertexBuffer = ByteBuffer.allocateDirect(squareCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(squareCoords)
        vertexBuffer.position(0)

        // 初始化纹理坐标缓冲
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer.position(0)
    }

    /**
     * 在 GL 线程中初始化
     */
    fun initialize() {
        // 创建着色器程序
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // 生成数字纹理 (0-19)
        GLES20.glGenTextures(20, textureIds, 0)
        for (i in 0 until 20) {
            createNumberTexture(i)
        }

        // 生成标签纹理 (后台、观众)
        GLES20.glGenTextures(3, labelTextureIds, 0)
        createLabelTexture(0, "后台", Color.parseColor("#4080FF"))  // 蓝色
        createLabelTexture(2, "台下", Color.parseColor("#CBD5E1"))
        createLabelTexture(1, "观众", Color.parseColor("#FF6060"))  // 红色
    }

    /**
     * 创建单个数字的纹理（带黑色描边的白色数字，透明背景）
     */
    private fun createNumberTexture(number: Int) {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 透明背景
        bitmap.eraseColor(Color.TRANSPARENT)

        val fontSize = if (number < 10) 36f else 28f
        val text = number.toString()

        // 黑色描边画笔
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            strokeWidth = 4f  // 描边宽度
        }

        // 白色填充画笔
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
        }

        val textY = size / 2f - (fillPaint.descent() + fillPaint.ascent()) / 2

        // 先绘制黑色描边
        canvas.drawText(text, size / 2f, textY, strokePaint)
        // 再绘制白色填充
        canvas.drawText(text, size / 2f, textY, fillPaint)

        // 绑定纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[number])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    /**
     * 创建文字标签纹理
     */
    private fun createLabelTexture(index: Int, text: String, textColor: Int) {
        val width = 128
        val height = 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 半透明黑色背景圆角矩形
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(4f, 4f, width - 4f, height - 4f, 12f, 12f, bgPaint)

        // 彩色文字
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 32f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, width / 2f, textY, textPaint)

        // 绑定纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTextureIds[index])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    /**
     * 绘制数字（广告牌方式，始终面向相机）
     * @param y 已经根据翻转状态调整过的Y坐标（renderY）
     */
    fun drawNumber(
        vpMatrix: FloatArray,
        viewMatrix: FloatArray,
        number: Int,
        x: Float,
        y: Float,
        z: Float,
        scale: Float = 0.4f
    ) {
        if (number < 0) return
        val textureId = if (number < textureIds.size) textureIds[number] else {
            dancerNameTextures.getOrPut(number.toString()) { createDancerNameTexture(number.toString()) }
        }

        GLES20.glUseProgram(program)
        val blendingWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        // 创建广告牌矩阵（始终面向相机）
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        // 注意：传入的y已经是renderY，直接使用，不需要再取反
        Matrix.translateM(modelMatrix, 0, x, z + 0.3f, y)  // 稍微抬高一点

        // 提取相机的旋转并反转，使数字始终面向相机
        // 简化处理：只提取 yaw 旋转
        val invView = FloatArray(16)
        Matrix.invertM(invView, 0, viewMatrix, 0)

        // 使用相机逆矩阵的旋转部分
        modelMatrix[0] = invView[0] * scale
        modelMatrix[1] = invView[1] * scale
        modelMatrix[2] = invView[2] * scale
        modelMatrix[4] = invView[4] * scale
        modelMatrix[5] = invView[5] * scale
        modelMatrix[6] = invView[6] * scale
        modelMatrix[8] = invView[8] * scale
        modelMatrix[9] = invView[9] * scale
        modelMatrix[10] = invView[10] * scale

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        // 设置顶点属性
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // 设置 MVP 矩阵
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // 绑定纹理
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (!blendingWasEnabled) GLES20.glDisable(GLES20.GL_BLEND)

        // 清理
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * 绘制文字标签（广告牌方式，始终面向相机）
     * @param labelIndex 0:后台, 1:观众
     * @param y 已经根据翻转状态调整过的Y坐标
     */
    fun drawLabel(
        vpMatrix: FloatArray,
        viewMatrix: FloatArray,
        labelIndex: Int,
        x: Float,
        y: Float,
        z: Float,
        scale: Float = 1.0f
    ) {
        if (labelIndex < 0 || labelIndex >= 3) return

        GLES20.glUseProgram(program)
        val blendingWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        // 创建广告牌矩阵（始终面向相机）
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        // 注意：传入的y已经是renderY，直接使用
        Matrix.translateM(modelMatrix, 0, x, z + 0.1f, y)

        // 提取相机的旋转并反转，使文字始终面向相机
        val invView = FloatArray(16)
        Matrix.invertM(invView, 0, viewMatrix, 0)

        // 使用相机逆矩阵的旋转部分，横向缩放2倍（矩形比例）
        modelMatrix[0] = invView[0] * scale * 2f
        modelMatrix[1] = invView[1] * scale * 2f
        modelMatrix[2] = invView[2] * scale * 2f
        modelMatrix[4] = invView[4] * scale
        modelMatrix[5] = invView[5] * scale
        modelMatrix[6] = invView[6] * scale
        modelMatrix[8] = invView[8] * scale
        modelMatrix[9] = invView[9] * scale
        modelMatrix[10] = invView[10] * scale

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        // 设置顶点属性
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // 设置 MVP 矩阵
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // 绑定纹理
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTextureIds[labelIndex])
        GLES20.glUniform1i(textureHandle, 0)

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (!blendingWasEnabled) GLES20.glDisable(GLES20.GL_BLEND)

        // 清理
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * 绘制舞者名字（动态创建纹理）
     * @param y 已经根据翻转状态调整过的Y坐标（renderY）
     */
    fun drawDancerName(
        vpMatrix: FloatArray,
        viewMatrix: FloatArray,
        name: String,
        x: Float,
        y: Float,
        z: Float,
        scale: Float = 0.4f
    ) {
        // 检查缓存，如果没有则创建纹理
        var textureId = dancerNameTextures[name]
        if (textureId == null) {
            textureId = createDancerNameTexture(name)
            dancerNameTextures[name] = textureId
        }

        GLES20.glUseProgram(program)
        val blendingWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        // 创建广告牌矩阵（始终面向相机）
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        // 注意：传入的y已经是renderY，直接使用，不需要再取反
        Matrix.translateM(modelMatrix, 0, x, z, y)

        // 提取相机的旋转并反转，使文字始终面向相机
        val invView = FloatArray(16)
        Matrix.invertM(invView, 0, viewMatrix, 0)

        // 使用相机逆矩阵的旋转部分，横向缩放1.5倍（矩形比例）
        modelMatrix[0] = invView[0] * scale * 1.5f
        modelMatrix[1] = invView[1] * scale * 1.5f
        modelMatrix[2] = invView[2] * scale * 1.5f
        modelMatrix[4] = invView[4] * scale
        modelMatrix[5] = invView[5] * scale
        modelMatrix[6] = invView[6] * scale
        modelMatrix[8] = invView[8] * scale
        modelMatrix[9] = invView[9] * scale
        modelMatrix[10] = invView[10] * scale

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        // 设置顶点属性
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // 设置 MVP 矩阵
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // 绑定纹理
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (!blendingWasEnabled) GLES20.glDisable(GLES20.GL_BLEND)

        // 清理
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun drawCustomStyle(
        vpMatrix: FloatArray,
        viewMatrix: FloatArray,
        imagePath: String,
        x: Float,
        y: Float,
        z: Float,
        scale: Float = 1.0f
    ) {
        val textureId = customStyleTextures[imagePath] ?: createCustomStyleTexture(imagePath)?.also {
            customStyleTextures[imagePath] = it
        } ?: return

        GLES20.glUseProgram(program)
        val blendingWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, z + 0.1f, y)

        val invView = FloatArray(16)
        Matrix.invertM(invView, 0, viewMatrix, 0)
        modelMatrix[0] = invView[0] * scale
        modelMatrix[1] = invView[1] * scale
        modelMatrix[2] = invView[2] * scale
        modelMatrix[4] = invView[4] * scale
        modelMatrix[5] = invView[5] * scale
        modelMatrix[6] = invView[6] * scale
        modelMatrix[8] = invView[8] * scale
        modelMatrix[9] = invView[9] * scale
        modelMatrix[10] = invView[10] * scale

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDepthMask(true)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (!blendingWasEnabled) GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * 创建舞者名字纹理（带黑色描边的白色文字，透明背景）
     */
    private fun createDancerNameTexture(name: String): Int {
        val width = 128
        val height = 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 透明背景（默认即为透明）
        bitmap.eraseColor(Color.TRANSPARENT)

        // 根据名字长度调整字号
        val fontSize = when {
            name.length <= 2 -> 36f
            name.length <= 3 -> 30f
            else -> 24f
        }

        // 黑色描边画笔
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            strokeWidth = 4f  // 描边宽度
        }

        // 白色填充画笔
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
        }

        val textY = height / 2f - (fillPaint.descent() + fillPaint.ascent()) / 2

        // 先绘制黑色描边
        canvas.drawText(name, width / 2f, textY, strokePaint)
        // 再绘制白色填充
        canvas.drawText(name, width / 2f, textY, fillPaint)

        // 生成并绑定纹理
        val textureId = IntArray(1)
        GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        return textureId[0]
    }

    private fun createCustomStyleTexture(imagePath: String): Int? {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
        val textureId = IntArray(1)
        GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textureId[0]
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    /**
     * 释放资源
     */
    fun release() {
        if (textureIds.isNotEmpty()) {
            GLES20.glDeleteTextures(20, textureIds, 0)
        }
        if (labelTextureIds.isNotEmpty()) {
            GLES20.glDeleteTextures(3, labelTextureIds, 0)
        }
        // 清理舞者名字纹理
        dancerNameTextures.values.forEach { textureId ->
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
        dancerNameTextures.clear()
        customStyleTextures.values.forEach { textureId ->
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
        customStyleTextures.clear()

        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}
