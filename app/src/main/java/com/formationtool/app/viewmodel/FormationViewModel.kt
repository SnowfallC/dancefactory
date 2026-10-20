package com.formationtool.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.formationtool.app.model.*
import com.formationtool.app.utils.UndoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 主ViewModel - 管理队形编辑状态
 */
class FormationViewModel : ViewModel() {

    // 撤销管理器
    val undoManager = UndoManager()

    internal val _currentGroup = MutableStateFlow<DanceGroup?>(null)
    val currentGroup: StateFlow<DanceGroup?> = _currentGroup.asStateFlow()

    internal val _currentFormationIndex = MutableStateFlow(0)
    val currentFormationIndex: StateFlow<Int> = _currentFormationIndex.asStateFlow()

    internal val _selectedDancers = MutableStateFlow<Set<String>>(emptySet())
    val selectedDancers: StateFlow<Set<String>> = _selectedDancers.asStateFlow()

    internal val _cameraView = MutableStateFlow(CameraView())
    val cameraView: StateFlow<CameraView> = _cameraView.asStateFlow()

    internal val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    internal val _currentTime = MutableStateFlow(0L)
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    internal val _colorGroups = MutableStateFlow<List<ColorGroup>>(emptyList())
    val colorGroups: StateFlow<List<ColorGroup>> = _colorGroups.asStateFlow()

    // 音频相关
    internal val _audioPath = MutableStateFlow<String?>(null)
    val audioPath: StateFlow<String?> = _audioPath.asStateFlow()

    internal val _audioDuration = MutableStateFlow(0L)
    val audioDuration: StateFlow<Long> = _audioDuration.asStateFlow()

    internal val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    // 互斥锁，防止并发修改
    internal val groupMutex = Mutex()

    // 修改标记（用于自动保存）
    internal val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    /**
     * 标记为已修改
     */
    internal fun markDirty() {
        _isDirty.value = true
    }

    /**
     * 清除修改标记（保存后调用）
     */
    fun clearDirty() {
        _isDirty.value = false
    }

    /**
     * 撤销最近的操作
     */
    fun undo() {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock
                val restoredGroup = undoManager.undo(group)
                if (restoredGroup != null) {
                    // 确保当前帧索引有效
                    if (_currentFormationIndex.value >= restoredGroup.formations.size) {
                        _currentFormationIndex.value = (restoredGroup.formations.size - 1).coerceAtLeast(0)
                    }
                    emitGroupUpdateLocked(restoredGroup)
                }
            }
        }
    }

    /**
     * 是否可以撤销
     */
    fun canUndo(): Boolean = undoManager.canUndo()

    /**
     * 手动保存撤销快照（用于拖动开始时调用）
     */
    fun saveUndoSnapshot() {
        _currentGroup.value?.let { group ->
            undoManager.saveSnapshot(group)
        }
    }

    /**
     * 强制触发 StateFlow 更新
     * 使用深拷贝确保每次都是新对象
     */
    internal fun emitGroupUpdate(group: DanceGroup) {
        markDirty()  // 标记为已修改
        val newGroup = deepCopyGroup(group)
        _currentGroup.value = newGroup
    }

    /**
     * 在已持有锁的情况下触发更新（不获取锁）
     */
    internal fun emitGroupUpdateLocked(group: DanceGroup) {
        markDirty()  // 标记为已修改
        val newGroup = deepCopyGroup(group)
        _currentGroup.value = newGroup
    }

    /**
     * 同步当前 Group 状态（用于拖动结束后确保 UI 一致性）
     * 在拖动时直接修改了舞者对象，需要在结束时同步到 StateFlow
     */
    fun syncCurrentGroup() {
        val group = _currentGroup.value ?: return
        // 强制触发更新，让所有 UI 组件同步
        _currentGroup.value = deepCopyGroup(group)
    }

    /**
     * 通知 Group 变化（用于 F 模式实时刷新）
     * 同步触发 StateFlow 更新，让波形视图等实时渲染
     * 注意：必须同步执行，避免竞态条件
     */
    fun notifyGroupChanged() {
        val group = _currentGroup.value ?: return
        markDirty()
        _currentGroup.value = deepCopyGroup(group)
    }

    /**
     * 深度拷贝 DanceGroup
     * 注意：isSelected 是 UI 交互状态，完全独立于关键帧，始终保留
     */
    internal fun deepCopyGroup(group: DanceGroup): DanceGroup {
        return DanceGroup(
            id = group.id,
            name = group.name,
            dancerCount = group.dancerCount,
            formations = group.formations.map { formation ->
                Formation(
                    id = formation.id,
                    name = formation.name,
                    dancers = formation.dancers.map { dancer ->
                        Dancer(
                            id = dancer.id,
                            name = dancer.name,
                            x = dancer.x,
                            y = dancer.y,
                            z = dancer.z,
                            color = dancer.color,
                            customStylePath = dancer.customStylePath,
                            scale = dancer.scale,
                            rotation = dancer.rotation,
                            isSelected = dancer.isSelected,  // 选中状态是 UI 状态，始终保留
                            groupId = dancer.groupId
                        )
                    }.toMutableList(),
                    timestamp = formation.timestamp,
                    holdDuration = formation.holdDuration,  // 复制保持时长
                    duration = formation.duration,
                    notes = formation.notes,
                    props = formation.props.map { prop ->
                        Prop(
                            id = prop.id,
                            name = prop.name,
                            type = prop.type,
                            x = prop.x,
                            y = prop.y,
                            z = prop.z,
                            width = prop.width,
                            height = prop.height,
                            rotation = prop.rotation,
                            color = prop.color
                        )
                    }.toMutableList()
                )
            }.toMutableList(),
            standardMode = group.standardMode,
            backstageVisible = group.backstageVisible,
            stageWidth = group.stageWidth,
            stageDepth = group.stageDepth,
            musicPath = group.musicPath,
            referenceVideoUri = group.referenceVideoUri,  // 复制参考视频URI
            audioUri = group.audioUri,  // 复制音频URI
            referenceVideoPath = group.referenceVideoPath,  // 复制视频内部路径
            audioPath = group.audioPath,  // 复制音频内部路径
            thumbnailPath = group.thumbnailPath,  // 复制缩略图路径
            createdTime = group.createdTime,
            modifiedTime = System.currentTimeMillis(),
            updateVersion = group.updateVersion + 1
        )
    }

    /**
     * 创建新舞团（默认 9 人横排）
     */
    fun timelineDuration(): Long {
        if (_audioDuration.value > 0) return _audioDuration.value
        val group = _currentGroup.value ?: return 0L
        return if (group.standardMode) group.formations.maxOfOrNull { it.timestamp + it.holdDuration } ?: 0L else 0L
    }

    fun setStandardMode(enabled: Boolean) {
        val group = _currentGroup.value ?: return
        if (group.standardMode == enabled) return
        undoManager.saveSnapshot(group)
        group.standardMode = enabled
        emitGroupUpdate(group)
    }

    fun setBackstageVisible(visible: Boolean) {
        val group = _currentGroup.value ?: return
        if (group.backstageVisible == visible) return
        undoManager.saveSnapshot(group)
        group.backstageVisible = visible
        emitGroupUpdate(group)
    }

    fun addFormationInterval() {
        viewModelScope.launch {
            val group = _currentGroup.value ?: return@launch
            val selected = group.formations.getOrNull(_currentFormationIndex.value) ?: return@launch
            val time = if (group.formations.any { it.timestamp == _currentTime.value })
                selected.timestamp + selected.holdDuration + 2000 else _currentTime.value
            if (_audioDuration.value > 0 && time >= _audioDuration.value) return@launch
            insertKeyframeSmart(time)?.let { seekTo(it.timestamp) }
        }
    }

    fun updateIntervalDancer(id: String, x: Float, y: Float) {
        val group = _currentGroup.value ?: return
        val formation = group.formations.getOrNull(_currentFormationIndex.value) ?: return
        val dancer = formation.dancers.find { it.id == id } ?: return
        undoManager.saveSnapshot(group)
        dancer.x = x.coerceIn(-group.stageWidth / 2 - group.backstageWidth(), group.stageWidth / 2 + group.backstageWidth())
        dancer.y = y.coerceIn(-group.stageDepth / 2, group.stageDepth / 2)
        emitGroupUpdate(group)
    }

    fun replaceDancerRoster(roster: List<Pair<String, Int>>) {
        if (roster.isEmpty()) return
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock
                undoManager.saveSnapshot(group)
                val dancers = roster.mapIndexed { index, member ->
                    Dancer(UUID.randomUUID().toString(), member.first,
                        (if (index % 2 == 0) -1 else 1) * (group.stageWidth / 2 + group.backstageWidth() / 2),
                        group.stageDepth / 2 - (index / 2 + 1f) * group.stageDepth / ((roster.size + 1) / 2 + 1f),
                        0.5f, member.second)
                }
                group.formations.forEach { formation ->
                    formation.dancers.clear()
                    formation.dancers.addAll(dancers.map { it.copy() })
                }
                group.dancerCount = roster.size
                group.backstageVisible = true
                _selectedDancers.value = emptySet()
                emitGroupUpdateLocked(group)
            }
        }
    }

    fun createNewGroup(name: String, dancerCount: Int = 9, stageWidth: Float, stageDepth: Float) {
        viewModelScope.launch {
            // 注意：允许 dancerCount = 0，用于创建空工程
            val actualCount = if (dancerCount < 0) 0 else dancerCount

            val group = DanceGroup(
                id = UUID.randomUUID().toString(),
                name = name,
                dancerCount = actualCount,
                standardMode = true,
                stageWidth = stageWidth,
                stageDepth = stageDepth
            )

            // 创建初始队形（第 0 帧关键帧）
            val initialFormation = Formation(
                id = UUID.randomUUID().toString(),
                name = "关键帧 00:00",
                timestamp = 0L,
                holdDuration = 4000L,  // 默认保持 4 秒
                duration = 2000L  // 默认变换 2 秒
            )

            // 只在 actualCount > 0 时才创建舞者
            if (actualCount > 0) {
                // 创建舞者并横排（一排）
                for (i in 0 until actualCount) {
                    val dancer = Dancer(
                        id = UUID.randomUUID().toString(),
                        name = "舞者${i + 1}",
                        x = -stageWidth / 2 - group.backstageWidth() / 2,  // 横向均匀分布
                        y = stageDepth / 2 - (i + 1f) * stageDepth / (actualCount + 1f),
                        z = 0.5f,  // 浮在舞台上方
                        color = getDefaultColor(i)
                    )
                    initialFormation.dancers.add(dancer)
                }
            }

            group.formations.add(initialFormation)
            _currentGroup.value = group
            _currentFormationIndex.value = 0

            android.util.Log.d("FormationViewModel", "创建新工程: name=$name, dancerCount=$actualCount")
        }
    }

    /**
     * 添加新队形
     */
    fun addFormation(name: String = "") {
        viewModelScope.launch {
            val group = _currentGroup.value ?: return@launch
            val currentIndex = _currentFormationIndex.value

            // 复制当前队形的舞者位置
            val currentFormation = group.formations.getOrNull(currentIndex)
            val newFormation = Formation(
                id = UUID.randomUUID().toString(),
                name = name.ifEmpty { "队形${group.formations.size + 1}" },
                timestamp = (currentFormation?.timestamp ?: 0L) + 5000L,
                holdDuration = 4000L,  // 默认保持 4 秒
                duration = 2000L  // 默认变换 2 秒
            )

            // 复制舞者（只复制位置，不复制选中状态）
            currentFormation?.dancers?.forEach { dancer ->
                newFormation.dancers.add(dancer.copy(isSelected = false))
            }

            group.formations.add(newFormation)
            _currentFormationIndex.value = group.formations.size - 1
            emitGroupUpdate(group)
        }
    }

    /**
     * 删除队形
     */
    fun deleteFormation(index: Int) {
        viewModelScope.launch {
            val group = _currentGroup.value ?: return@launch
            if (group.formations.size <= 1) return@launch // 至少保留一个队形

            group.formations.removeAt(index)
            if (_currentFormationIndex.value >= group.formations.size) {
                _currentFormationIndex.value = group.formations.size - 1
            }
            emitGroupUpdate(group)
        }
    }

    /**
     * 切换到指定队形
     */
    fun switchToFormation(index: Int) {
        viewModelScope.launch {
            val group = _currentGroup.value ?: return@launch
            if (index in group.formations.indices) {
                _currentFormationIndex.value = index
            }
        }
    }

    /**
     * 更新舞者位置（只更新当前关键帧）
     * 注意：使用 Mutex 防止并发修改导致舞者消失
     */
    fun updateDancerPosition(dancerId: String, x: Float, y: Float, z: Float = 0f) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock
                val formationIndex = _currentFormationIndex.value
                val formation = group.formations.getOrNull(formationIndex) ?: return@withLock

                // 计算舞台边界（舞台中心在原点，范围为 ±stageWidth/2, ±stageDepth/2）
                val halfWidth = group.stageWidth / 2f
                val halfDepth = group.stageDepth / 2f

                formation.dancers.find { it.id == dancerId }?.apply {
                    // 限制舞者位置在舞台范围内
                    this.x = x.coerceIn(-halfWidth - group.backstageWidth(), halfWidth + group.backstageWidth())
                    this.y = y.coerceIn(-halfDepth, halfDepth)
                    this.z = z
                }

                // 强制触发更新
                emitGroupUpdateLocked(group)
            }
        }
    }

    /**
     * 选择/取消选择舞者
     * 选中状态是全局的，在所有关键帧中同步
     */
    fun toggleDancerSelection(dancerId: String) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 在所有帧中同步选中状态（确保切换帧时状态一致）
                group.formations.forEach { formation ->
                    // 先清除所有舞者的选中状态
                    formation.dancers.forEach { it.isSelected = false }
                    // 选中目标舞者
                    formation.dancers.find { it.id == dancerId }?.isSelected = true
                }

                // 强制触发更新
                emitGroupUpdateLocked(group)
            }
        }
    }

    /**
     * 清除选择
     * 全局清除所有帧的选中状态
     */
    fun clearSelection() {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 清除所有帧中所有舞者的选中状态
                group.formations.forEach { formation ->
                    formation.dancers.forEach { it.isSelected = false }
                }

                // 强制触发更新
                emitGroupUpdateLocked(group)
            }
        }
    }

    /**
     * 更新相机视角
     */
    fun updateCamera(pitch: Float? = null, yaw: Float? = null, distance: Float? = null, targetX: Float? = null, targetY: Float? = null) {
        viewModelScope.launch {
            val current = _cameraView.value
            _cameraView.value = current.copy(
                pitch = pitch ?: current.pitch,
                yaw = yaw ?: current.yaw,
                z = distance ?: current.z,
                targetX = targetX ?: current.targetX,
                targetY = targetY ?: current.targetY
            )
        }
    }

    /**
     * 播放/暂停动画
     */
    fun togglePlayback() {
        _isPlaying.value = !_isPlaying.value
    }

    /**
     * 强制开始播放
     */
    fun startPlayback() {
        _isPlaying.value = true
    }

    /**
     * 强制停止播放
     */
    fun stopPlayback() {
        _isPlaying.value = false
    }

    /**
     * 更新播放时间
     */
    fun updateTime(time: Long) {
        _currentTime.value = time

        // 根据时间自动切换队形（使用新的分段逻辑）
        val group = _currentGroup.value ?: return
        val formationIndex = getFormationIndexAtTime(group, time)
        if (_currentFormationIndex.value != formationIndex) {
            _currentFormationIndex.value = formationIndex
        }
    }

    /**
     * 根据时间获取当前应该显示的队形索引
     * 前红后绿逻辑：
     * - 红色区范围: [timestamp - duration, timestamp)
     * - 绿色区范围: [timestamp, timestamp + holdDuration)
     * - 红色区属于当前帧（用于从前一帧过渡到当前帧）
     */
    internal fun getFormationIndexAtTime(group: DanceGroup, t: Long): Int {
        val formations = group.formations
        if (formations.isEmpty()) return 0
        if (formations.size == 1) return 0

        // 遍历所有关键帧，找到时间点所属的帧
        for (i in formations.indices) {
            val f = formations[i]
            val timestamp = f.timestamp
            val redStart = timestamp - f.duration  // 红色区开始（前红）
            val greenEnd = timestamp + f.holdDuration  // 绿色区结束

            // 检查是否在红色区内（前面的过渡区）- 第一帧无红色区
            if (i > 0 && f.duration > 0 && t >= redStart && t < timestamp) {
                return i  // 红色区属于当前帧
            }

            // 检查是否在绿色区内（保持区）
            if (t >= timestamp && t < greenEnd) {
                return i
            }
        }

        // 时间超出所有帧范围，返回最后一帧
        return formations.lastIndex
    }

    /**
     * 根据时间获取当前应该渲染的舞者位置
     * 前红后绿逻辑：
     * - 红色区范围: [timestamp - duration, timestamp) - 从前一帧过渡到当前帧
     * - 绿色区范围: [timestamp, timestamp + holdDuration) - 保持当前帧
     *
     * @return 舞者位置列表，如果在红色区（过渡区），返回插值后的位置
     */
    fun getDancerPositionsAtTime(group: DanceGroup, t: Long): List<Dancer> {
        val formations = group.formations
        if (formations.isEmpty()) return emptyList()
        if (formations.size == 1) return formations[0].dancers

        // 遍历所有关键帧，找到时间点所属的帧
        for (i in formations.indices) {
            val f = formations[i]
            val timestamp = f.timestamp
            val redStart = timestamp - f.duration  // 红色区开始（前红）
            val greenEnd = timestamp + f.holdDuration  // 绿色区结束

            // 检查是否在红色区内（前面的过渡区）- 第一帧无红色区
            if (i > 0 && f.duration > 0 && t >= redStart && t < timestamp) {
                // 在红色区：从前一帧插值到当前帧
                val prevFormation = formations[i - 1]
                val ratio = (t - redStart).toFloat() / f.duration
                return lerpFormations(
                    prevFormation,
                    f,
                    ratio.coerceIn(0f, 1f)
                )
            }

            // 检查是否在绿色区内（保持区）
            if (t >= timestamp && t < greenEnd) {
                return f.dancers
            }
        }

        // 时间超出所有帧范围，返回最后一帧
        return formations.last().dancers
    }

    /**
     * 在两个阵型之间进行线性插值
     * 根据 ratio (0.0 ~ 1.0) 计算中间位置
     */
    internal fun lerpFormations(from: Formation, to: Formation, ratio: Float): List<Dancer> {
        val result = mutableListOf<Dancer>()

        // 按 ID 匹配舞者进行插值
        from.dancers.forEach { fromDancer ->
            val toDancer = to.dancers.find { it.id == fromDancer.id }
            if (toDancer != null) {
                result.add(Dancer(
                    id = fromDancer.id,
                    name = fromDancer.name,
                    x = lerp(fromDancer.x, toDancer.x, ratio),
                    y = lerp(fromDancer.y, toDancer.y, ratio),
                    z = lerp(fromDancer.z, toDancer.z, ratio),
                    color = fromDancer.color,
                    customStylePath = fromDancer.customStylePath,
                    scale = lerp(fromDancer.scale, toDancer.scale, ratio),
                    rotation = lerp(fromDancer.rotation, toDancer.rotation, ratio),
                    isSelected = fromDancer.isSelected,
                    groupId = fromDancer.groupId
                ))
            } else {
                // 如果目标帧中没有对应舞者，保持原位置
                result.add(fromDancer.copy())
            }
        }

        return result
    }

    /**
     * 线性插值辅助函数
     */
    internal fun lerp(start: Float, end: Float, ratio: Float): Float {
        return start + (end - start) * ratio
    }

    /**
     * 创建颜色组
     */
    fun createColorGroup(name: String, color: Int, dancerIds: List<String>) {
        viewModelScope.launch {
            val groups = _colorGroups.value.toMutableList()
            groups.add(ColorGroup(
                id = UUID.randomUUID().toString(),
                name = name,
                color = color,
                dancerIds = dancerIds.toMutableList()
            ))
            _colorGroups.value = groups
        }
    }

    /**
     * 获取默认颜色
     */
    internal fun getDefaultColor(index: Int): Int {
        val colors = listOf(
            0xFF2196F3.toInt(), // 蓝色
            0xFFF44336.toInt(), // 红色
            0xFF4CAF50.toInt(), // 绿色
            0xFFFF9800.toInt(), // 橙色
            0xFF9C27B0.toInt(), // 紫色
            0xFFFFEB3B.toInt(), // 黄色
            0xFF00BCD4.toInt(), // 青色
            0xFFE91E63.toInt()  // 粉色
        )
        return colors[index % colors.size]
    }

    /**
     * 添加道具
     */
    fun addProp(name: String, type: PropType) {
        viewModelScope.launch {
            val group = _currentGroup.value ?: return@launch
            val formation = group.formations.getOrNull(_currentFormationIndex.value) ?: return@launch

            val prop = Prop(
                id = UUID.randomUUID().toString(),
                name = name,
                type = type
            )
            formation.props.add(prop)
            emitGroupUpdate(group)
        }
    }

    /**
     * 更新队形注释
     */
    fun updateFormationNotes(notes: String) {
        viewModelScope.launch {
            val group = _currentGroup.value ?: return@launch
            val formation = group.formations.getOrNull(_currentFormationIndex.value) ?: return@launch
            formation.notes = notes
            emitGroupUpdate(group)
        }
    }

    /**
     * 批量添加舞者（一次性添加多个舞者，避免并发问题）
     * 此方法在一个事务内完成所有添加操作，确保原子性
     * @param dancersToAdd List<Pair<name, color>>
     */

    /**
     * 添加舞者（添加到所有关键帧）
     * 注意：不会移动现有舞者的位置，新舞者添加到空闲位置
     */

    /**
     * 删除舞者
     */

    /**
     * 清空所有舞者
     */

    /**
     * 编辑舞者信息（全局更新所有关键帧）
     */

    /**
     * 捕捉舞者到网格点
     * 将当前关键帧的所有舞者对齐到最近的半格点
     * 网格大小为0.5米
     */
    fun snapDancersToGrid() {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock
                val formationIndex = _currentFormationIndex.value
                val formation = group.formations.getOrNull(formationIndex) ?: return@withLock

                // 计算舞台边界
                val halfWidth = group.stageWidth / 2f
                val halfDepth = group.stageDepth / 2f

                // 将每个舞者的位置对齐到最近的半格点，并限制在舞台范围内
                formation.dancers.forEach { dancer ->
                    // 吸附到半格（四舍五入到最近0.5的倍数）
                    val snappedX = kotlin.math.round(dancer.x * 2) / 2f
                    val snappedY = kotlin.math.round(dancer.y * 2) / 2f
                    dancer.x = snappedX.coerceIn(-halfWidth - group.backstageWidth(), halfWidth + group.backstageWidth())
                    dancer.y = snappedY.coerceIn(-halfDepth, halfDepth)
                }

                // 强制触发更新
                emitGroupUpdateLocked(group)
            }
        }
    }

    /**
     * 更新舞台尺寸
     */
    fun updateStageSize(width: Float, depth: Float) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock
                group.stageWidth = width
                group.stageDepth = depth

                // 强制触发更新
                emitGroupUpdateLocked(group)
            }
        }
    }

    /**
     * 设置音频文件（可传null清空）
     */
    fun setAudioPath(path: String?, duration: Long) {
        viewModelScope.launch {
            _audioPath.value = path ?: ""
            _audioDuration.value = duration

            val group = _currentGroup.value ?: return@launch
            group.musicPath = path
            emitGroupUpdate(group)
        }
    }

    /**
     * 播放/暂停音频
     */
    fun toggleAudioPlayback() {
        _isAudioPlaying.value = !_isAudioPlaying.value
    }

    /**
     * 跳转到指定时间
     */
    fun seekTo(timeMs: Long) {
        _currentTime.value = timeMs

        // 根据时间自动切换到对应的队形
        val group = _currentGroup.value ?: return
        for (i in group.formations.indices.reversed()) {
            if (timeMs >= group.formations[i].timestamp) {
                if (_currentFormationIndex.value != i) {
                    _currentFormationIndex.value = i
                }
                break
            }
        }
    }

    /**
     * 创建/更新关键帧（松手时调用）
     *
     * 核心原则：每个关键帧的数据完全独立，互不影响
     * 前红后绿逻辑：使用 adjustAdjacentFormations 进行智能插入
     *
     * 重要：拖动时修改的是 currentFormationIndex 指向的帧（可能是上一个关键帧）
     *       松手时需要先恢复那个帧，再在当前时间创建新帧
     *
     * @param timeMs 当前时间
     * @param originalPositions 拖动前的原始位置（当时的 currentFormation 的舞者位置）
     */

    /**
     * 创建一个完全独立的关键帧（数据不共享）
     */

    /**
     * 使用插值位置插入新关键帧（用于在红色过渡区拖动时创建新帧）
     * @param timeMs 插入时间点
     * @param interpolatedDancers 插值后的舞者位置列表
     */

    /**
     * 删除关键帧
     * 前红后绿逻辑：删除后下一帧的红色区（前红）自动扩展填充空白
     */

    /**
     * 根据时间戳删除关键帧
     */

    /**
     * 格式化时间显示
     */

    // ========== 前红后绿智能插入逻辑 ==========

    internal companion object {
        const val DEFAULT_RED = 2000L   // 默认红色区2秒
        const val DEFAULT_GREEN = 4000L // 默认绿色区4秒
        const val MIN_DURATION = 1L     // 最小时长1ms
    }

    /**
     * 区域类型枚举
     */
    internal enum class RegionType {
        BLANK,  // 空白区
        GREEN,  // 绿色区
        RED     // 红色区
    }

    /**
     * 判断时间点T所在的区域类型
     */

    /**
     * 调整前后相邻关键帧的红绿区（前红后绿逻辑）
     *
     * 前红后绿结构：
     * - duration：前面的红色区长度（从前一帧绿色区结束到当前timestamp）
     * - holdDuration：后面的绿色区长度
     * - 红色区范围: [timestamp - duration, timestamp)
     * - 绿色区范围: [timestamp, timestamp + holdDuration)
     *
     * 三种场景：
     * - 场景A（空白区）：T在前一帧绿色区结束之后，且不在下一帧红色区内
     * - 场景B（绿色区）：T在前一帧绿色区内
     * - 场景C（红色区）：T在下一帧红色区内
     *
     * @param group 舞蹈组
     * @param insertIndex 新插入关键帧的索引
     * @param newTimeMs 新关键帧的时间戳
     */

    /**
     * 场景A：在空白区插入
     * - K_new的红色区(duration) = T - K_prev.greenEnd
     * - K_new的绿色区(holdDuration) = 默认DEFAULT_GREEN，但不能超过K_next.timestamp
     * - K_next的红色区(duration) = K_next.timestamp - K_new.greenEnd
     */

    /**
     * 场景B：在绿色区插入
     * - 截断K_prev的绿色区
     * - K_new的红色区(duration) = 默认DEFAULT_RED，但不能超过T - K_prev.timestamp
     * - K_new的绿色区(holdDuration) = K_prev被截断的剩余绿色区
     */

    /**
     * 场景C：在红色区插入
     * - K_new的红色区(duration) = T - K_prev.greenEnd（继承前半部分红色区）
     * - K_new的绿色区(holdDuration) = 最小1ms
     * - K_next的红色区(duration) = K_next.timestamp - K_new.greenEnd（继承后半部分红色区）
     */

    /**
     * 智能插入关键帧
     * @param timestamp 插入时间点
     * @param dancerPositions 舞者位置（可为null，则复制当前帧）
     * @return 插入的关键帧
     */

    /**
     * 加载工程（恢复所有状态）
     */
    fun loadGroup(group: DanceGroup) {
        viewModelScope.launch {
            // 清理重复的初始帧（timestamp=0）
            val initialFrames = group.formations.filter { it.timestamp == 0L }
            if (initialFrames.size > 1) {
                android.util.Log.w("FormationVM", "[加载工程] 检测到${initialFrames.size}个初始帧，正在清理...")
                // 保留第一个，删除其他
                val toRemove = initialFrames.drop(1)
                group.formations.removeAll(toRemove)
                android.util.Log.d("FormationVM", "[加载工程] 已删除${toRemove.size}个重复的初始帧")
            }

            // 确保至少有一个初始帧
            if (group.formations.isEmpty() || group.formations.none { it.timestamp == 0L }) {
                android.util.Log.w("FormationVM", "[加载工程] 缺少初始帧，正在创建...")
                val initialFormation = Formation(
                    id = UUID.randomUUID().toString(),
                    name = "关键帧 00:00",
                    timestamp = 0L,
                    holdDuration = 4000L,
                    duration = 2000L
                )
                // 复制第一个帧的舞者（如果有）
                group.formations.firstOrNull()?.dancers?.forEach { dancer ->
                    initialFormation.dancers.add(dancer.copy(isSelected = false))
                }
                group.formations.add(0, initialFormation)
                android.util.Log.d("FormationVM", "[加载工程] 已创建初始帧")
            }

            _currentGroup.value = group
            _currentFormationIndex.value = 0
            _audioPath.value = group.musicPath
            _currentTime.value = 0L
            _isPlaying.value = false
            _selectedDancers.value = emptySet()
        }
    }

    /**
     * 调整指定关键帧的保持时长（绿色区 - 后绿）
     * 前红后绿逻辑：
     * - 拖动绿色区后边界时，当前帧的timestamp固定不变
     * - 拖动绿色区后边界时，下一帧的timestamp固定不变
     * - 往前拖 → 绿色区缩短，下一帧红色区拉长
     * - 往后拖 → 绿色区拉长，下一帧红色区缩短
     * - 限制：绿色区至少保疙1ms，下一帧红色区至少保疙1ms
     * - 最后一帧可以自由向右扩展绿色区（直到音频结尾）
     */

    /**
     * 调整指定关键帧的变换时长（红色区 - 前红）
     * 前红后绿逻辑：
     * - 修改当前帧的红色区长度（前红）
     * - 第一帧（index=0）无红色区
     * - 红色区是从前一帧绿色区结束到当前帧timestamp的过渡时间
     * 注意：修改红色区不会改变timestamp，只是改变过渡效果的长度
     */

    /**
     * 移动关键帧的位置（拖动timestamp位置）
     * 前红后绿逻辑：
     * - timestamp是红绿分界点
     * - 拖动timestamp时，前一帧的绿色区结束位置固定不变（redStart）
     * - 拖动timestamp时，当前帧的绿色区结束位置固定不变（greenEnd）
     * - 往前拖 → 红色区缩短、绿色区拉长
     * - 往后拖 → 红色区拉长、绿色区缩短
     * - 限制：红色区和绿色区都至少保留1ms
     * - 第一个关键帧(00:00)不能移动
     * @param formationIndex 关键帧索引（必须 > 0）
     * @param newTimestamp 新的时间位置
     */

}
