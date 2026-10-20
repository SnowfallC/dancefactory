package com.formationtool.app.renderer

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 舞台两侧的半透明台下区域，可供预览和导出复用。 */
class BackstageRenderer {
    private val program: Int
    private val vertices = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-0.5f, 0f, -0.5f, 0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f)); position(0)
    }
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    init {
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "uniform mat4 matrix; attribute vec4 position; void main(){ gl_Position=matrix*position; }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; void main(){ gl_FragColor=vec4(0.58,0.64,0.72,0.22); }")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
    }
    fun draw(vp: FloatArray, view: FloatArray, width: Float, depth: Float, labels: NumberRenderer?) {
        val wing = maxOf(2f, width * 0.2f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        for (side in listOf(-1, 1)) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, side * (width + wing) / 2, 0.02f, 0f)
            Matrix.scaleM(model, 0, wing, 1f, depth)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUseProgram(program)
            val location = GLES20.glGetAttribLocation(program, "position")
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glVertexAttribPointer(location, 3, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "matrix"), 1, false, mvp, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(location)
            labels?.drawLabel(vp, view, 2, side * (width + wing) / 2, 0f, 0.05f, 0.5f)
        }
        GLES20.glDepthMask(true)
    }
    fun release() { GLES20.glDeleteProgram(program) }
}
