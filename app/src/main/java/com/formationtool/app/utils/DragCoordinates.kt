package com.formationtool.app.utils

import com.formationtool.app.model.CameraView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** 屏幕位移转换为舞台位移；舞台翻转只作用于舞者，不作用于相机。 */
internal object DragCoordinates {
    fun stageDelta(deltaX: Float, deltaY: Float, camera: CameraView, width: Float, height: Float, flipped: Boolean): Pair<Float, Float> {
        if (width <= 0f || height <= 0f) return 0f to 0f
        val scale = 2f * camera.z * tan(Math.toRadians(camera.fov.toDouble() / 2)).toFloat() / height
        val rawX = deltaX * scale
        val rawY = deltaY * scale * if (flipped) -1f else 1f
        val yaw = Math.toRadians(camera.yaw.toDouble())
        val depth = if (abs(camera.pitch) > 85f) rawY else rawY * cos(Math.toRadians(-camera.pitch.toDouble())).toFloat()
        return (rawX * cos(yaw).toFloat() + depth * sin(yaw).toFloat()) to
            (-rawX * sin(yaw).toFloat() + depth * cos(yaw).toFloat())
    }

    fun cameraPanDelta(distanceX: Float, distanceY: Float, camera: CameraView, width: Float, height: Float): Pair<Float, Float> {
        val delta = stageDelta(distanceX, distanceY, camera, width, height, false)
        // 手势 distance 与手指位移相反；渲染器又将 targetY 转为负的 OpenGL Z。
        return delta.first to -delta.second
    }
}
