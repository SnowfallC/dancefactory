package com.formationtool.app.controller

import com.formationtool.app.model.Formation
import com.formationtool.app.view.WaveformView
import com.formationtool.app.viewmodel.FormationViewModel
import com.formationtool.app.viewmodel.insertKeyframeSmart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * 关键帧控制器
 * 负责处理F键拍打功能
 *
 * F键边播边扫机制（前红后绿结构）：
 * 状态机：按F → 绿色区 → 松F → 红色区 → 按F → 绿色区 → ... → 暂停/到头 → 结束
 */
class KeyframeController(
    private val viewModel: FormationViewModel,
    private val coroutineScope: CoroutineScope,
    private val waveformView: WaveformView?,  // 波形视图（用于实时预览）
    private val onStateChanged: () -> Unit  // 状态变化回调，用于更新UI
) {

    // ========== F键拍打状态 ==========
    private var isFKeyPressed = false           // F键是否按下
    private var isInGreenMode = false           // 是否在绿色区模式
    private var isFirstFKey = true              // 是否是序列中第一次按F
    private var currentRegionStartTime = -1L    // 当前区域开始时间
    private var scanStartTime = -1L             // 扫描开始时间（用于删除旧关键帧）
    private var currentKeyframeId: String? = null  // 当前正在编辑的关键帧ID
    private val protectedKeyframeIds = mutableSetOf<String>()  // 保护的关键帧ID（不删除）

    // 状态标签文本
    var statusText: String = ""
        private set

    // 是否在扫描中
    val isScanning: Boolean
        get() = !isFirstFKey || isFKeyPressed

    // 是否正在按F
    val isFPressed: Boolean
        get() = isFKeyPressed

    private var scanJob: Job? = null

    /**
     * 获取当前关键帧（从 group 中根据 ID 查找）
     */
    private fun getCurrentKeyframe(): Formation? {
        val id = currentKeyframeId ?: return null
        return viewModel.currentGroup.value?.formations?.find { it.id == id }
    }

    init {
        // 设置时间更新监听，用于实时删除扫描范围内的旧关键帧
        setupAutoDeleteListener()
    }

    /**
     * 设置自动删除监听和绿色区实时更新
     */
    private fun setupAutoDeleteListener() {
        scanJob = coroutineScope.launch(Dispatchers.Main) {
            viewModel.currentTime.collect { currentTime ->
                // 更新波形视图的当前时间（用于F键标记实时预览）
                waveformView?.updateCurrentPlayTime(currentTime)

                // 扫描删除旧关键帧
                if (scanStartTime >= 0 && currentTime > scanStartTime) {
                    deleteOldKeyframesInScanRange(currentTime)
                }

                // 实时更新绿色区长度（按住F时）
                val currentKeyframe = getCurrentKeyframe()
                if (isInGreenMode && currentKeyframe != null && currentRegionStartTime >= 0) {
                    val greenDuration = currentTime - currentRegionStartTime
                    if (greenDuration > 0) {
                        currentKeyframe.holdDuration = greenDuration
                        // 实时刷新UI显示绿色区增长
                        viewModel.notifyGroupChanged()
                    }
                }
            }
        }
    }

    /**
     * 删除扫描范围内的旧关键帧
     */
    private fun deleteOldKeyframesInScanRange(currentScanTime: Long) {
        val group = viewModel.currentGroup.value ?: return
        val formations = group.formations
        val toDelete = mutableListOf<Formation>()

        for (formation in formations) {
            // 跳过第一帧和受保护的关键帧
            if (formation.timestamp == 0L) continue
            if (protectedKeyframeIds.contains(formation.id)) continue

            // 如果关键帧在扫描范围内，标记删除
            if (formation.timestamp > scanStartTime && formation.timestamp <= currentScanTime) {
                toDelete.add(formation)
            }
        }

        // 执行删除
        toDelete.forEach { formation ->
            android.util.Log.d("KeyframeCtrl", "[F键扫描] 删除旧关键帧 @ ${formation.timestamp}ms")
            formations.remove(formation)
        }

        if (toDelete.isNotEmpty()) {
            // 实时刷新UI
            viewModel.notifyGroupChanged()
            onStateChanged()
        }
    }

    /**
     * 处理F键按下
     * - 第一次按F：智能插入创建关键帧，开始绿色区
     * - 再次按F：保存红色区长度，创建新关键帧，开始绿色区
     */
    fun handleFKeyPressed() {
        if (isFKeyPressed) return

        val group = viewModel.currentGroup.value
        if (group == null || group.formations.isEmpty()) return

        isFKeyPressed = true
        val currentTime = viewModel.currentTime.value

        if (isFirstFKey) {
            // ========== 第一次按F：启动扫描序列 ==========
            isFirstFKey = false
            scanStartTime = currentTime
            protectedKeyframeIds.clear()

            // 保存撤销快照
            viewModel.saveUndoSnapshot()

            // 使用智能插入创建第一个关键帧
            coroutineScope.launch {
                val keyframe = viewModel.insertKeyframeSmart(currentTime, null)
                if (keyframe != null) {
                    // 保存ID，然后从 group 中重新获取并修改
                    currentKeyframeId = keyframe.id
                    protectedKeyframeIds.add(keyframe.id)

                    // 从 group 中获取实际对象并修改
                    getCurrentKeyframe()?.let { kf ->
                        kf.holdDuration = 0L  // 初始化绿色区为0，等松开F时再设置
                    }

                    android.util.Log.d("KeyframeCtrl", "[F键拍打] 启动，创建第一个关键帧 @ ${currentTime}ms（绿色区待设置）")
                    // 实时刷新UI
                    viewModel.notifyGroupChanged()
                }
            }

        } else {
            // ========== 再次按F：红色区 → 绿色区 ==========
            // 计算红色区长度
            val redDuration = currentTime - currentRegionStartTime

            // 创建新关键帧，并设置其前红色区
            coroutineScope.launch {
                val newKeyframe = viewModel.insertKeyframeSmart(currentTime, null)
                if (newKeyframe != null) {
                    // 保存ID，然后从 group 中重新获取并修改
                    currentKeyframeId = newKeyframe.id
                    protectedKeyframeIds.add(newKeyframe.id)

                    // 从 group 中获取实际对象并修改
                    getCurrentKeyframe()?.let { kf ->
                        kf.duration = redDuration
                        kf.holdDuration = 0L  // 中间帧不设默认绿色区，等松开F时再设置
                    }

                    android.util.Log.d("KeyframeCtrl", "[F键拍打] 再次按F，红色区长度=${redDuration}ms，创建新关键帧 @ ${currentTime}ms（绿色区待设置）")
                    // 实时刷新UI
                    viewModel.notifyGroupChanged()
                }
            }
        }

        // 进入绿色区模式
        isInGreenMode = true
        currentRegionStartTime = currentTime

        // 通知波形视图 F键按下（实时预览）
        waveformView?.onFKeyPressed(currentTime)

        statusText = "F键按下 - 绿色区 @ ${formatDuration(currentTime)}"
        onStateChanged()
    }

    /**
     * 处理F键松开
     * - 保存绿色区长度到当前关键帧
     * - 切换到红色区模式
     */
    fun handleFKeyReleased() {
        if (!isFKeyPressed) return

        isFKeyPressed = false
        val currentTime = viewModel.currentTime.value
        val group = viewModel.currentGroup.value
        val currentKeyframe = getCurrentKeyframe()

        if (group == null || currentKeyframe == null) return

        // 计算绿色区长度
        val greenDuration = currentTime - currentRegionStartTime

        if (greenDuration > 0) {
            // 保存绿色区长度到当前关键帧
            currentKeyframe.holdDuration = greenDuration
            android.util.Log.d("KeyframeCtrl", "[F键拍打] 松开F，绿色区长度=${greenDuration}ms 保存到 [${currentKeyframe.name}]")

            // 重新计算下一帧的红色区
            recalculateRedRegion(group, currentKeyframe)

            // 实时刷新UI
            viewModel.notifyGroupChanged()
        }

        // 切换到红色区模式
        isInGreenMode = false
        currentRegionStartTime = currentTime

        // 通知波形视图 F键松开（开始红色区预览）
        waveformView?.onFKeyReleased(currentTime)

        statusText = "F键松开 - 红色区 @ ${formatDuration(currentTime)}"
        onStateChanged()
    }

    /**
     * 暂停时处理F键标记（结束扫描序列）
     */
    fun handleFKeyMarkOnPause() {
        val group = viewModel.currentGroup.value ?: return
        val currentKeyframe = getCurrentKeyframe()

        // 如果不在扫描序列中，直接返回
        if (isFirstFKey && !isFKeyPressed) {
            return
        }

        val pauseTime = viewModel.currentTime.value
        android.util.Log.d("KeyframeCtrl", "[F键拍打] 暂停/结束 @ ${pauseTime}ms, 当前模式: ${if (isInGreenMode) "绿色区" else "红色区"}")

        if (isFKeyPressed && currentKeyframe != null) {
            // ========== 正在按着F（绿色区结束） ==========
            val greenDuration = pauseTime - currentRegionStartTime
            if (greenDuration > 0) {
                currentKeyframe.holdDuration = greenDuration
                android.util.Log.d("KeyframeCtrl", "[F键拍打] 结束时保存绿色区长度=${greenDuration}ms")
                recalculateRedRegion(group, currentKeyframe)
            }

            // 使用智能插入创建结尾关键帧
            coroutineScope.launch {
                createEndingKeyframe(group, pauseTime)
                clearFKeyMarkState()
                viewModel.notifyGroupChanged()
                onStateChanged()
            }

        } else if (!isInGreenMode && currentRegionStartTime >= 0) {
            // ========== 红色区结束 ==========
            val redDuration = pauseTime - currentRegionStartTime

            // 创建结尾关键帧，并设置其前红色区
            coroutineScope.launch {
                val endKeyframe = createEndingKeyframe(group, pauseTime)
                if (endKeyframe != null && redDuration > 0) {
                    // 从 group 中获取实际对象并修改
                    viewModel.currentGroup.value?.formations?.find { it.id == endKeyframe.id }?.let { kf ->
                        kf.duration = redDuration
                        android.util.Log.d("KeyframeCtrl", "[F键拍打] 结束时保存红色区长度=${redDuration}ms")
                    }
                }
                clearFKeyMarkState()
                viewModel.notifyGroupChanged()
                onStateChanged()
            }
        } else {
            clearFKeyMarkState()
            onStateChanged()
        }

        android.util.Log.d("KeyframeCtrl", "[F键拍打] 序列结束")
    }

    /**
     * 创建结尾关键帧
     */
    private suspend fun createEndingKeyframe(group: com.formationtool.app.model.DanceGroup, timestamp: Long): Formation? {
        // 检查是否已存在
        val exists = group.formations.any { it.timestamp == timestamp }

        if (exists) {
            android.util.Log.d("KeyframeCtrl", "[F键拍打] 结尾位置已有关键帧 @ ${timestamp}ms")
            return group.formations.find { it.timestamp == timestamp }
        }

        // 使用智能插入创建结尾关键帧
        val endKeyframe = viewModel.insertKeyframeSmart(timestamp, null)
        if (endKeyframe != null) {
            protectedKeyframeIds.add(endKeyframe.id)
            android.util.Log.d("KeyframeCtrl", "[F键拍打] 创建结尾关键帧 @ ${timestamp}ms")
        }
        return endKeyframe
    }

    /**
     * 清除F键标记状态
     */
    fun clearFKeyMarkState() {
        isFKeyPressed = false
        isInGreenMode = false
        isFirstFKey = true
        currentRegionStartTime = -1L
        scanStartTime = -1L
        currentKeyframeId = null
        protectedKeyframeIds.clear()
        statusText = ""

        // 清除波形视图的F键标记预览
        waveformView?.clearFKeyMark()
    }

    /**
     * 重新计算下一帧的红色区
     */
    private fun recalculateRedRegion(group: com.formationtool.app.model.DanceGroup, keyframe: Formation) {
        val formations = group.formations
        val index = formations.indexOf(keyframe)
        if (index < 0 || index >= formations.size - 1) return

        val nextKeyframe = formations[index + 1]
        val greenEnd = keyframe.timestamp + keyframe.holdDuration
        val newRedDuration = nextKeyframe.timestamp - greenEnd

        if (newRedDuration >= 0) {
            nextKeyframe.duration = newRedDuration
        }
    }

    /**
     * 格式化时间
     */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * 销毁控制器
     */
    fun destroy() {
        scanJob?.cancel()
        clearFKeyMarkState()
    }
}
