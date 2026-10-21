package com.formationtool.app.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 舞者数据模型
 */
@Parcelize
data class Dancer(
    val id: String,
    var name: String = "舞者${id}",
    var x: Float = 0f,  // 舞台X坐标
    var y: Float = 0f,  // 舞台Y坐标
    var z: Float = 0f,  // 高度（用于3D视图）
    var color: Int = 0xFF2196F3.toInt(),
    var customStylePath: String? = null,
    var groupId: String? = null,  // 所属组别
    var rotation: Float = 0f,  // 旋转角度
    var scale: Float = 1f,  // 缩放比例
    var isSelected: Boolean = false
) : Parcelable

/**
 * 队形数据模型
 */
@Parcelize
data class Formation(
    val id: String,
    var name: String = "队形${id}",
    val dancers: MutableList<Dancer> = mutableListOf(),
    var timestamp: Long = 0L,  // 音乐时间戳(毫秒) - 阵型逻辑起点时间
    var holdDuration: Long = 0L,  // 阵型保持时长(毫秒) - 在此时间内阵型完全不动
    var duration: Long = 2000L,  // 过渡到下一队形的时长(毫秒) - transitionDuration
    var notes: String = "",  // 注释
    val props: MutableList<Prop> = mutableListOf()  // 道具
) : Parcelable

/**
 * 道具数据模型
 */
@Parcelize
data class Prop(
    val id: String,
    var name: String,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var width: Float = 1f,
    var height: Float = 1f,
    var rotation: Float = 0f,
    var color: Int = 0xFFFF9800.toInt(),
    var type: PropType = PropType.RECTANGLE
) : Parcelable

/**
 * 道具类型
 */
enum class PropType {
    RECTANGLE,  // 矩形
    CIRCLE,     // 圆形
    TRIANGLE,   // 三角形
    CUSTOM      // 自定义
}

/**
 * 舞团数据模型
 */
@Parcelize
data class DanceGroup(
    val id: String,
    var name: String,
    var dancerCount: Int = 0,
    val formations: MutableList<Formation> = mutableListOf(),
    var stageWidth: Float = 16f,  // 舞台宽度（米）
    var stageDepth: Float = 12f,  // 舞台深度（米）
    var musicPath: String? = null,
    var referenceVideoUri: String? = null,  // 参考视频URI（原始Content URI，重启后可能失效）
    var audioUri: String? = null,  // 音频URI（原始Content URI，重启后可能失效）
    var referenceVideoPath: String? = null,  // 参考视频内部存储路径（持久化）
    var audioPath: String? = null,  // 音频内部存储路径（持久化）
    var audioSourceUri: String? = null,
    var videoSourceUri: String? = null,
    var referenceVideoMirrored: Boolean = false,
    var thumbnailPath: String? = null,  // 缩略图路径
    var createdTime: Long = System.currentTimeMillis(),
    var modifiedTime: Long = System.currentTimeMillis(),
    var standardMode: Boolean = false,
    var backstageVisible: Boolean = true,
    var updateVersion: Long = 0  // 版本号，用于强制触发StateFlow更新
) : Parcelable

fun DanceGroup.backstageWidth(): Float = maxOf(2f, stageWidth * 0.2f)

/**
 * 颜色组别
 */
@Parcelize
data class ColorGroup(
    val id: String,
    var name: String,
    var color: Int,
    val dancerIds: MutableList<String> = mutableListOf()
) : Parcelable

/**
 * 相机视角
 */
@Parcelize
data class CameraView(
    var x: Float = 0f,
    var y: Float = -10f,  // 相机Y位置
    var z: Float = 15f,   // 相机距离
    var pitch: Float = -30f,  // 俯仰角
    var yaw: Float = 0f,      // 偏航角
    var fov: Float = 45f,     // 视野角度
    var targetX: Float = 0f,  // 观察点X偏移
    var targetY: Float = 0f   // 观察点Y偏移（舞台坐标）
) : Parcelable

/**
 * 动画插值类型
 */
enum class InterpolationType {
    LINEAR,         // 线性
    EASE_IN,        // 缓入
    EASE_OUT,       // 缓出
    EASE_IN_OUT,    // 缓入缓出
    BOUNCE,         // 弹跳
    OVERSHOOT       // 超越
}


// 点选队形时只定位到离播放头最近的区间边缘，区间内保持当前位置。
fun Formation.selectionTime(currentTime: Long): Long =
    currentTime.coerceIn(timestamp, timestamp + holdDuration)
