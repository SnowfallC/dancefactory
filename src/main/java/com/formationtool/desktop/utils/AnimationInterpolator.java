package com.formationtool.desktop.utils;

/**
 * 动画插值器 - 提供多种过渡效果
 */
public class AnimationInterpolator {

    /**
     * 插值类型
     */
    public enum Type {
        LINEAR,
        EASE_IN,
        EASE_OUT,
        EASE_IN_OUT,
        BOUNCE,
        OVERSHOOT
    }

    /**
     * 根据插值类型计算插值
     */
    public static float interpolate(Type type, float fraction) {
        switch (type) {
            case LINEAR:
                return linear(fraction);
            case EASE_IN:
                return easeIn(fraction);
            case EASE_OUT:
                return easeOut(fraction);
            case EASE_IN_OUT:
                return easeInOut(fraction);
            case BOUNCE:
                return bounce(fraction);
            case OVERSHOOT:
                return overshoot(fraction);
            default:
                return linear(fraction);
        }
    }

    /**
     * 线性插值
     */
    private static float linear(float t) {
        return t;
    }

    /**
     * 缓入插值（开始慢，后面快）
     */
    private static float easeIn(float t) {
        return t * t * t;
    }

    /**
     * 缓出插值（开始快，后面慢）
     */
    private static float easeOut(float t) {
        float f = 1 - t;
        return 1 - f * f * f;
    }

    /**
     * 缓入缓出插值（开始慢，中间快，结束慢）
     */
    private static float easeInOut(float t) {
        if (t < 0.5f) {
            return 4 * t * t * t;
        } else {
            float f = 2 * t - 2;
            return 1 + f * f * f / 2;
        }
    }

    /**
     * 弹跳效果
     */
    private static float bounce(float t) {
        if (t < 0.5f) {
            return 8 * (1 - t) * t * t * t;
        } else {
            float f = 2 * t - 2;
            return 1 - 8 * f * f * f * f;
        }
    }

    /**
     * 超越效果（会超过目标值再回来）
     */
    private static float overshoot(float t) {
        float overshoot = 1.5f;
        return (float) (Math.pow(t - 1, 2) * ((overshoot + 1) * (t - 1) + overshoot) + 1);
    }

    /**
     * 弹性效果
     */
    public static float elastic(float t) {
        if (t == 0f || t == 1f) return t;
        float p = 0.3f;
        float s = p / 4;
        return (float) (Math.pow(2.0, -10.0 * t) * Math.sin((t - s) * (2 * Math.PI) / p) + 1);
    }
}
