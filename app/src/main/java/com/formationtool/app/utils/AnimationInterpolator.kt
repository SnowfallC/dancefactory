package com.formationtool.app.utils

import com.formationtool.app.model.InterpolationType
import kotlin.math.pow
import kotlin.math.sin

/**
 * 动画插值器 - 提供多种过渡效果
 */
object AnimationInterpolator {

    /**
     * 根据插值类型计算插值
     */
    fun interpolate(type: InterpolationType, fraction: Float): Float {
        return when (type) {
            InterpolationType.LINEAR -> linear(fraction)
            InterpolationType.EASE_IN -> easeIn(fraction)
            InterpolationType.EASE_OUT -> easeOut(fraction)
            InterpolationType.EASE_IN_OUT -> easeInOut(fraction)
            InterpolationType.BOUNCE -> bounce(fraction)
            InterpolationType.OVERSHOOT -> overshoot(fraction)
        }
    }

    /**
     * 线性插值
     */
    private fun linear(t: Float): Float = t

    /**
     * 缓入插值（开始慢，后面快）
     */
    private fun easeIn(t: Float): Float = t * t * t

    /**
     * 缓出插值（开始快，后面慢）
     */
    private fun easeOut(t: Float): Float {
        val f = 1 - t
        return 1 - f * f * f
    }

    /**
     * 缓入缓出插值（开始慢，中间快，结束慢）
     */
    private fun easeInOut(t: Float): Float {
        return if (t < 0.5f) {
            4 * t * t * t
        } else {
            val f = 2 * t - 2
            1 + f * f * f / 2
        }
    }

    /**
     * 弹跳效果
     */
    private fun bounce(t: Float): Float {
        return if (t < 0.5f) {
            8 * (1 - t) * t * t * t
        } else {
            val f = 2 * t - 2
            1 - 8 * f * f * f * f
        }
    }

    /**
     * 超越效果（会超过目标值再回来）
     */
    private fun overshoot(t: Float): Float {
        val overshoot = 1.5f
        return (t - 1).pow(2) * ((overshoot + 1) * (t - 1) + overshoot) + 1
    }

    /**
     * 弹性效果
     */
    fun elastic(t: Float): Float {
        if (t == 0f || t == 1f) return t
        val p = 0.3f
        val s = p / 4
        return (2.0.pow(-10.0 * t) * sin((t - s) * (2 * Math.PI) / p) + 1).toFloat()
    }
}
