package com.formationtool.app.viewmodel

import androidx.lifecycle.viewModelScope
import com.formationtool.app.model.DanceGroup
import com.formationtool.app.model.backstageWidth
import com.formationtool.app.model.Dancer
import com.formationtool.app.model.Formation
import com.formationtool.app.viewmodel.FormationViewModel.Companion.DEFAULT_GREEN
import com.formationtool.app.viewmodel.FormationViewModel.Companion.DEFAULT_RED
import com.formationtool.app.viewmodel.FormationViewModel.Companion.MIN_DURATION
import com.formationtool.app.viewmodel.FormationViewModel.RegionType
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.UUID

fun FormationViewModel.batchAddDancers(dancersToAdd: List<Pair<String, Int>>) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                if (dancersToAdd.isEmpty()) return@withLock

                // 保存撤销快照
                undoManager.saveSnapshot(group)

                // 获取初始舞者数量
                val initialCount = group.formations.firstOrNull()?.dancers?.size ?: 0

                // 计算舞台半宽和半深
                val halfWidth = group.stageWidth / 2f

                // 创建新舞者列表
                val newDancers = dancersToAdd.mapIndexed { index, (name, color) ->
                    val currentCount = initialCount + index
                    Triple(
                        UUID.randomUUID().toString(),  // ID
                        name.ifEmpty { "舞者${currentCount + 1}" },  // 名称
                        color
                    )
                }

                // 计算总数和间距
                val finalCount = initialCount + newDancers.size

                // 更新所有队形中的舞者
                group.formations.forEach { formation ->
                    // 批量添加新舞者
                    newDancers.forEachIndexed { index, (id, name, color) ->
                        val position = initialCount + index + 1
                        val newX = (if ((position - 1) % 2 == 0) -1 else 1) * (halfWidth + group.backstageWidth() / 2)
                        val newDancer = Dancer(
                            id = id,
                            name = name,
                            color = color,
                            x = newX,
                            y = 0f,
                            z = 0.5f
                        )
                        formation.dancers.add(newDancer)
                    }
                }

                // 更新舞者总数
                group.backstageVisible = true
                group.dancerCount = finalCount

                // 发送更新通知
                emitGroupUpdateLocked(group)

                android.util.Log.d("FormationViewModel", "批量添加舞者完成: 新增${newDancers.size}个, 总计${finalCount}个")
            }
        }
    }

fun FormationViewModel.addDancer(name: String = "", color: Int? = null) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 保存撤销快照
                undoManager.saveSnapshot(group)

                // 获取当前舞者数量和生成默认属性
                val currentCount = group.formations.firstOrNull()?.dancers?.size ?: 0
                val dancerId = UUID.randomUUID().toString()
                val dancerColor = color ?: getDefaultColor(currentCount)
                val dancerName = name.ifEmpty { "舞者${currentCount + 1}" }

                // 计算舞台范围
                val halfWidth = group.stageWidth / 2f
                val halfDepth = group.stageDepth / 2f

                //
                // 新舞者放在左右台下，避免占用台上位置。
                val newX = (if (currentCount % 2 == 0) -1 else 1) * (halfWidth + group.backstageWidth() / 2)
                val newY = halfDepth - 1f - (currentCount / 2 % maxOf(1, group.stageDepth.toInt() - 1))
                group.backstageVisible = true

                // 在所有队形中添加新舞者
                group.formations.forEach { formation ->
                    val newDancer = Dancer(
                        id = dancerId,
                        name = dancerName,
                        color = dancerColor,
                        x = newX,
                        y = newY,
                        z = 0.5f
                    )
                    formation.dancers.add(newDancer)
                }

                group.dancerCount = group.formations.firstOrNull()?.dancers?.size ?: 0

                // 更新计数并通知
                emitGroupUpdateLocked(group)
            }
        }
    }

fun FormationViewModel.deleteDancer(dancerId: String) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 保存撤销快照
                undoManager.saveSnapshot(group)

                // 从所有队形中移除指定舞者
                group.formations.forEach { formation ->
                    formation.dancers.removeAll { it.id == dancerId }
                }

                group.dancerCount = group.formations.firstOrNull()?.dancers?.size ?: 0

                // 更新计数并通知
                emitGroupUpdateLocked(group)
            }
        }
    }

fun FormationViewModel.clearAllDancers() {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 保存撤销快照
                undoManager.saveSnapshot(group)

                // 清空所有队形中的舞者
                group.formations.forEach { formation ->
                    formation.dancers.clear()
                }

                group.dancerCount = 0

                // 更新计数并通知
                emitGroupUpdateLocked(group)
            }
        }
    }

suspend fun FormationViewModel.setDancersToCount(targetCount: Int, getDefaultColor: (Int) -> Int) {
        groupMutex.withLock {
            val group = _currentGroup.value ?: return@withLock
            val safeTargetCount = targetCount.coerceAtLeast(0)
            val currentCount = group.formations.firstOrNull()?.dancers?.size ?: 0

            if (currentCount == safeTargetCount) {
                return@withLock
            }

            // 保存一次撤销快照，确保预设人数是一个完整操作
            undoManager.saveSnapshot(group)

            if (currentCount < safeTargetCount) {
                val halfWidth = group.stageWidth / 2f
                val finalCount = safeTargetCount
                val dancersToAdd = (currentCount until finalCount).map { index ->
                    Triple(UUID.randomUUID().toString(), "舞者${index + 1}", getDefaultColor(index))
                }

                group.formations.forEach { formation ->
                    dancersToAdd.forEachIndexed { index, (id, name, color) ->
                        val position = currentCount + index + 1
                        val x = (if ((position - 1) % 2 == 0) -1 else 1) * (halfWidth + group.backstageWidth() / 2)
                        formation.dancers.add(
                            Dancer(
                                id = id,
                                name = name,
                                color = color,
                                x = x,
                                y = 0f,
                                z = 0.5f
                            )
                        )
                    }
                }
            } else {
                val keepIds = group.formations
                    .firstOrNull()
                    ?.dancers
                    ?.take(safeTargetCount)
                    ?.map { it.id }
                    ?.toSet()
                    ?: emptySet()

                group.formations.forEach { formation ->
                    formation.dancers.removeAll { dancer -> dancer.id !in keepIds }
                }
            }

            group.dancerCount = group.formations.firstOrNull()?.dancers?.size ?: 0
            emitGroupUpdateLocked(group)
        }
    }

fun FormationViewModel.updateDancer(dancerId: String, name: String? = null, color: Int? = null) {
    viewModelScope.launch {
        groupMutex.withLock {
            val group = _currentGroup.value ?: return@withLock

            // 保存撤销快照
            undoManager.saveSnapshot(group)

            // 在所有队形中更新指定舞者的属性
            group.formations.forEach { formation ->
                formation.dancers.find { it.id == dancerId }?.apply {
                    if (name != null) this.name = name
                    if (color != null) this.color = color
                }
            }

            emitGroupUpdateLocked(group)
        }
    }
}

fun FormationViewModel.updateDancerStyle(dancerId: String, customStylePath: String?) {
    viewModelScope.launch {
        groupMutex.withLock {
            val group = _currentGroup.value ?: return@withLock

            // 保存撤销快照
            undoManager.saveSnapshot(group)

            // 在所有队形中更新指定舞者的自定义样式
            group.formations.forEach { formation ->
                formation.dancers.find { it.id == dancerId }?.apply {
                    this.customStylePath = customStylePath
                }
            }

            emitGroupUpdateLocked(group)
        }
    }
}
fun FormationViewModel.createKeyframe(timeMs: Long, originalPositions: Map<String, Triple<Float, Float, Float>>? = null) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 保存撤销快照
                undoManager.saveSnapshot(group)

                val draggedFormationIdx = _currentFormationIndex.value
                val draggedFormation = group.formations.getOrNull(draggedFormationIdx) ?: return@withLock

                // 获取当前队形中所有舞者的新位置
                val newPositions = draggedFormation.dancers.associate {
                    it.id to Triple(it.x, it.y, it.z)
                }

                // 判断是否在同一帧上拖动
                val isDraggingSameFrame = draggedFormation.timestamp == timeMs

                // 检查目标时间是否已有关键帧
                val existingIndex = group.formations.indexOfFirst { it.timestamp == timeMs }

                if (isDraggingSameFrame) {
                    // 在同一帧上拖动，直接更新位置
                    emitGroupUpdateLocked(group)
                } else if (existingIndex >= 0 && existingIndex != draggedFormationIdx) {
                    // 拖到了别的已存在的关键帧上
                    // 先恢复被拖动帧的原始位置
                    if (originalPositions != null) {
                        draggedFormation.dancers.forEach { dancer ->
                            originalPositions[dancer.id]?.let { (x, y, z) ->
                                dancer.x = x
                                dancer.y = y
                                dancer.z = z
                            }
                        }
                    }

                    // 更新那个已存在的关键帧
                    val existingFormation = group.formations[existingIndex]
                    existingFormation.dancers.forEach { dancer ->
                        newPositions[dancer.id]?.let { (x, y, z) ->
                            dancer.x = x
                            dancer.y = y
                            dancer.z = z
                        }
                    }
                    _currentFormationIndex.value = existingIndex
                    emitGroupUpdateLocked(group)
                } else {
                    // 拖到了空白位置，创建新关键帧
                    // 先恢复被拖动帧的原始位置
                    if (originalPositions != null) {
                        draggedFormation.dancers.forEach { dancer ->
                            originalPositions[dancer.id]?.let { (x, y, z) ->
                                dancer.x = x
                                dancer.y = y
                                dancer.z = z
                            }
                        }
                    }

                    // 创建新的关键帧
                    val newTimeMs = if (timeMs <= 0L) 500L else timeMs
                    val newFormation = createIndependentFormation(
                        timestamp = newTimeMs,
                        positions = newPositions,
                        templateDancers = draggedFormation.dancers
                    )

                    // 插入到正确的时间位置
                    var actualInsertIndex = group.formations.indexOfFirst { it.timestamp > newTimeMs }
                    if (actualInsertIndex >= 0) {
                        group.formations.add(actualInsertIndex, newFormation)
                        _currentFormationIndex.value = actualInsertIndex
                    } else {
                        // 追加到末尾
                        group.formations.add(newFormation)
                        actualInsertIndex = group.formations.size - 1
                        _currentFormationIndex.value = actualInsertIndex
                    }
                    _currentTime.value = newTimeMs

                    // 调整相邻队形的过渡时间
                    adjustAdjacentFormations(group, actualInsertIndex, newTimeMs)

                    emitGroupUpdateLocked(group)
                }
            }
        }
    }

internal fun FormationViewModel.createIndependentFormation(
        timestamp: Long,
        positions: Map<String, Triple<Float, Float, Float>>,
        templateDancers: List<Dancer>
    ): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "关键帧 ${formatTime(timestamp)}",
            timestamp = timestamp,
            holdDuration = 4000L,  // 默认保持 4 秒
            duration = 2000L
        )

        // 使用模板舞者创建新队形的舞者
        templateDancers.forEach { template ->
            val pos = positions[template.id]
            formation.dancers.add(Dancer(
                id = template.id,
                name = template.name,
                x = pos?.first ?: template.x,
                y = pos?.second ?: template.y,
                z = pos?.third ?: template.z,
                color = template.color,
                customStylePath = template.customStylePath,
                scale = template.scale,
                rotation = template.rotation,
                isSelected = false,
                groupId = template.groupId
            ))
        }

        return formation
    }

fun FormationViewModel.insertKeyframeWithPositions(timeMs: Long, interpolatedDancers: List<Dancer>) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 检查是否已存在相近时间的关键帧
                val existing = group.formations.find { kotlin.math.abs(it.timestamp - timeMs) < 50 }
                if (existing != null) {
                    android.util.Log.d("FormationVM", "[插入关键帧] 关键帧已存在 @ ${timeMs}ms")
                    return@withLock
                }

                // 保存撤销快照
                undoManager.saveSnapshot(group)

                // 创建新的关键帧
                val newFormation = Formation(
                    id = UUID.randomUUID().toString(),
                    name = "关键帧 ${formatTime(timeMs)}",
                    timestamp = timeMs,
                    holdDuration = DEFAULT_GREEN,
                    duration = DEFAULT_RED
                )

                // 使用插值位置创建舞者
                interpolatedDancers.forEach { interpolated ->
                    newFormation.dancers.add(Dancer(
                        id = interpolated.id,
                        name = interpolated.name,
                        x = interpolated.x,
                        y = interpolated.y,
                        z = interpolated.z,
                        color = interpolated.color,
                        customStylePath = interpolated.customStylePath,
                        scale = interpolated.scale,
                        rotation = interpolated.rotation,
                        isSelected = interpolated.isSelected,
                        groupId = interpolated.groupId
                    ))
                }

                // 计算插入位置
                var insertIndex = group.formations.indexOfFirst { it.timestamp > timeMs }
                if (insertIndex < 0) {
                    group.formations.add(newFormation)
                    insertIndex = group.formations.size - 1
                } else {
                    group.formations.add(insertIndex, newFormation)
                }

                // 切换到新帧并调整相邻队形
                _currentFormationIndex.value = insertIndex
                _currentTime.value = timeMs

                //
                adjustAdjacentFormations(group, insertIndex, timeMs)

                android.util.Log.d("FormationVM", "[创建关键帧] 在${timeMs}ms处创建新关键帧")

                emitGroupUpdateLocked(group)
            }
        }
    }

fun FormationViewModel.deleteKeyframe(formationIndex: Int): Boolean {
    val current = _currentGroup.value ?: return false
    if (current.formations.size <= 1 || formationIndex !in current.formations.indices) return false
    val formationId = current.formations[formationIndex].id
    viewModelScope.launch {
        groupMutex.withLock {
            val group = _currentGroup.value ?: return@withLock
            val index = group.formations.indexOfFirst { it.id == formationId }
            if (group.formations.size <= 1 || index < 0) return@withLock
            undoManager.saveSnapshot(group)
            group.formations.removeAt(index)
            if (index == 0) {
                // 首队形从零开始，后续队形的时间位置保持不变。
                val first = group.formations.first()
                first.holdDuration += first.timestamp
                first.timestamp = 0L
                first.duration = 0L
            } else if (index < group.formations.size) {
                val previous = group.formations[index - 1]
                group.formations[index].duration =
                    (group.formations[index].timestamp - previous.timestamp - previous.holdDuration).coerceAtLeast(0L)
            }
            val selected = _currentFormationIndex.value
            _currentFormationIndex.value = (if (selected >= index) selected - 1 else selected)
                .coerceIn(0, group.formations.lastIndex)
            emitGroupUpdateLocked(group)
        }
    }
    return true
}

fun FormationViewModel.deleteKeyframeByTimestamp(timestamp: Long): Boolean {
        val group = _currentGroup.value ?: return false
        val index = group.formations.indexOfFirst { it.timestamp == timestamp }
        if (index > 0) {
            deleteKeyframe(index)
            return true
        }
        return false
    }

internal fun FormationViewModel.formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

internal fun FormationViewModel.determineRegionType(T: Long, K_prev: Formation?, K_next: Formation?): RegionType {
        // 检查是否在红色区域内
        if (K_next != null && K_next.duration > 0) {
            val redStart = K_next.timestamp - K_next.duration
            if (T >= redStart && T < K_next.timestamp) {
                return RegionType.RED
            }
        }

        // 检查是否在绿色区域内
        if (K_prev != null) {
            val greenEnd = K_prev.timestamp + K_prev.holdDuration
            if (T >= K_prev.timestamp && T < greenEnd) {
                return RegionType.GREEN
            }
        }

        // 否则在空白区域
        return RegionType.BLANK
    }

internal fun FormationViewModel.adjustAdjacentFormations(group: DanceGroup, insertIndex: Int, newTimeMs: Long) {
        val formations = group.formations
        val K_new = formations[insertIndex]

        val K_prev = if (insertIndex > 0) formations[insertIndex - 1] else null
        val K_next = if (insertIndex < formations.size - 1) formations[insertIndex + 1] else null

        // 确定插入点的区域类型
        val regionType = determineRegionType(newTimeMs, K_prev, K_next)

        when (regionType) {
            RegionType.GREEN -> handleGreenRegionInsert(K_new, K_prev!!, K_next, newTimeMs)
            RegionType.RED -> handleRedRegionInsert(K_new, K_prev!!, K_next!!, newTimeMs)
            RegionType.BLANK -> handleBlankRegionInsert(K_new, K_prev, K_next, newTimeMs)
        }
    }

internal fun FormationViewModel.handleBlankRegionInsert(K_new: Formation, K_prev: Formation?, K_next: Formation?, T: Long) {
        // 设置K_new的前红色区
        if (K_prev != null) {
            val prevGreenEnd = K_prev.timestamp + K_prev.holdDuration
            val redDuration = T - prevGreenEnd
            K_new.duration = redDuration.coerceAtLeast(0L)
            android.util.Log.d("FormationVM", "[空白区插入] K_new前红色区=${K_new.duration}ms")
        } else {
            K_new.duration = 0L  // 第一帧无前红色区
        }

        // 设置默认绿色持续时长
        K_new.holdDuration = DEFAULT_GREEN

        // 处理后续帧的过渡
        if (K_next != null) {
            val K_new_greenEnd = T + K_new.holdDuration
            if (K_new_greenEnd > K_next.timestamp) {
                // 如果K_new的绿色区域超过了K_next的时间戳
                val actualGreen = K_next.timestamp - T - DEFAULT_RED
                K_new.holdDuration = actualGreen.coerceAtLeast(MIN_DURATION)
                android.util.Log.d("FormationVM", "[调整相邻队形] K_new绿色持续时长=${K_new.holdDuration}ms")
            }

            // 设置K_next的前红色区
            val K_new_greenEnd_final = T + K_new.holdDuration
            val K_next_redDuration = K_next.timestamp - K_new_greenEnd_final
            K_next.duration = K_next_redDuration.coerceAtLeast(0L)
            android.util.Log.d("FormationVM", "[空白区插入] K_next前红色区=${K_next.duration}ms")
        }
    }

internal fun FormationViewModel.handleGreenRegionInsert(K_new: Formation, K_prev: Formation, K_next: Formation?, T: Long) {
        // 获取原始绿色区域结束时间
        val originalGreenEnd = K_prev.timestamp + K_prev.holdDuration

        // 计算K_new的前红色区
        val desiredRedDuration = DEFAULT_RED
        val maxRedDuration = T - K_prev.timestamp - MIN_DURATION
        val actualRedDuration = desiredRedDuration.coerceAtMost(maxRedDuration.coerceAtLeast(0L))
        K_new.duration = actualRedDuration

        // 截断K_prev的绿色区
        val newPrevGreen = T - actualRedDuration - K_prev.timestamp
        K_prev.holdDuration = newPrevGreen.coerceAtLeast(MIN_DURATION)
        android.util.Log.d("FormationVM", "[调整相邻队形] K_prev绿色持续时长=${K_prev.holdDuration}ms, K_new红色过渡=${K_new.duration}ms")

        // 计算K_new继承的绿色持续时长
        val inheritedGreen = originalGreenEnd - T
        K_new.holdDuration = inheritedGreen.coerceAtLeast(MIN_DURATION)
        android.util.Log.d("FormationVM", "[调整相邻队形] K_new 持续时长=${K_new.holdDuration}ms")

        // 设置K_next的前红色区
        if (K_next != null) {
            val K_new_greenEnd = T + K_new.holdDuration
            val K_next_redDuration = K_next.timestamp - K_new_greenEnd
            K_next.duration = K_next_redDuration.coerceAtLeast(0L)
        }
    }

internal fun FormationViewModel.handleRedRegionInsert(K_new: Formation, K_prev: Formation, K_next: Formation, T: Long) {
        // K_new的前红色区 = T - K_prev.greenEnd
        val prevGreenEnd = K_prev.timestamp + K_prev.holdDuration
        val newRedDuration = T - prevGreenEnd
        K_new.duration = newRedDuration.coerceAtLeast(0L)
        android.util.Log.d("FormationVM", "[红色区插入] K_new前红色区=${K_new.duration}ms")

        // 设置K_new的最小绿色持续时长
        K_new.holdDuration = MIN_DURATION
        android.util.Log.d("FormationVM", "[红色区域插入] K_new持续时长=${MIN_DURATION}ms")

        // K_next的前红色区 = K_next.timestamp - K_new.greenEnd
        val K_new_greenEnd = T + K_new.holdDuration
        val K_next_redDuration = K_next.timestamp - K_new_greenEnd
        K_next.duration = K_next_redDuration.coerceAtLeast(0L)
        android.util.Log.d("FormationVM", "[红色区插入] K_next前红色区=${K_next.duration}ms")
    }

suspend fun FormationViewModel.insertKeyframeSmart(timestamp: Long, dancerPositions: Map<String, Triple<Float, Float, Float>>? = null): Formation? {
        return groupMutex.withLock {
            val group = _currentGroup.value ?: return@withLock null
            val formations = group.formations

            // [Chinese comment - encoding fixed]
            val existing = formations.find { it.timestamp == timestamp }
            if (existing != null) {
                android.util.Log.d("FormationVM", "[智能插入] 关键帧已存在 @ ${timestamp}ms")
                return@withLock existing
            }

            // 保存撤销快照
            undoManager.saveSnapshot(group)

            // 获取模板队形（第一帧）
            val template = if (group.standardMode) formations.lastOrNull { it.timestamp <= timestamp } ?: formations.firstOrNull() else formations.firstOrNull()

            // 创建新关键帧
            val newFormation = Formation(
                id = UUID.randomUUID().toString(),
                name = "关键帧 ${formatTime(timestamp)}",
                timestamp = timestamp
            )

            // 设置舞者位置
            if (dancerPositions != null && template != null) {
                // 使用提供的位置
                template.dancers.forEach { templateDancer ->
                    val newDancer = templateDancer.copy(isSelected = false)
                    dancerPositions[templateDancer.id]?.let { (x, y, z) ->
                        newDancer.x = x
                        newDancer.y = y
                        newDancer.z = z
                    }
                    newFormation.dancers.add(newDancer)
                }
            } else if (template != null) {
                // 复制模板帧的位置
                template.dancers.forEach { dancer ->
                    newFormation.dancers.add(dancer.copy(isSelected = false))
                }
            }

            // 设置默认值（会被adjustAdjacentFormations覆盖）
            newFormation.holdDuration = DEFAULT_GREEN
            newFormation.duration = DEFAULT_RED

            // 计算插入位置
            var insertIndex = formations.indexOfFirst { it.timestamp > timestamp }
            if (insertIndex < 0) {
                formations.add(newFormation)
                insertIndex = formations.size - 1
            } else {
                formations.add(insertIndex, newFormation)
            }

            // 调整相邻队形
            adjustAdjacentFormations(group, insertIndex, timestamp)

            emitGroupUpdateLocked(group)

            newFormation
        }
    }

fun FormationViewModel.updateFormationHoldDuration(formationIndex: Int, newHoldDuration: Long) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock
                if (formationIndex !in group.formations.indices) return@withLock

                val formation = group.formations[formationIndex]
                val isLastFormation = (formationIndex == group.formations.lastIndex)

                if (isLastFormation) {
                    // 最后一帧：限制不超过音频时长
                    val maxHoldDuration = if (_audioDuration.value > 0) (_audioDuration.value - formation.timestamp).coerceAtLeast(1L) else Long.MAX_VALUE
                    val clampedHoldDuration = newHoldDuration.coerceIn(1L, maxHoldDuration)  // 至少保留1ms
                    formation.holdDuration = clampedHoldDuration
                } else {
                    // 非最后一帧
                    val nextFormation = group.formations[formationIndex + 1]

                    // 固定的边界
                    val currentTimestamp = formation.timestamp
                    val nextTimestamp = nextFormation.timestamp

                    //
                    val minGreenEnd = currentTimestamp + 1L
                    val maxGreenEnd = nextTimestamp - 1L

                    // 如果没有有效范围，不移动
                    if (minGreenEnd > maxGreenEnd) return@withLock

                    // 计算新的 greenEnd
                    val desiredGreenEnd = currentTimestamp + newHoldDuration
                    val clampedGreenEnd = desiredGreenEnd.coerceIn(minGreenEnd, maxGreenEnd)

                    //
                    formation.holdDuration = clampedGreenEnd - currentTimestamp

                    // 更新后续帧的过渡时长
                    nextFormation.duration = nextTimestamp - clampedGreenEnd
                }

                emitGroupUpdateLocked(group)
            }
        }
    }

fun FormationViewModel.updateFormationTransitionDuration(formationIndex: Int, newTransitionDuration: Long) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock
                if (formationIndex !in group.formations.indices) return@withLock

                // 第一帧无红色区
                if (formationIndex == 0) return@withLock

                val formation = group.formations[formationIndex]
                val prevFormation = group.formations[formationIndex - 1]

                // 固定到达此队形的时间，调整上一队形的保持区使片段连续。
                undoManager.saveSnapshot(group)
                val maxDuration = (formation.timestamp - prevFormation.timestamp - 1L).coerceAtLeast(0L)
                val clampedTransition = newTransitionDuration.coerceIn(0L, maxDuration)
                formation.duration = clampedTransition
                prevFormation.holdDuration = formation.timestamp - prevFormation.timestamp - clampedTransition

                emitGroupUpdateLocked(group)
            }
        }
    }

fun FormationViewModel.moveFormationTimestamp(formationIndex: Int, newTimestamp: Long) {
        viewModelScope.launch {
            groupMutex.withLock {
                val group = _currentGroup.value ?: return@withLock

                // 只能移动非第一帧
                if (formationIndex <= 0 || formationIndex >= group.formations.size) return@withLock

                val formation = group.formations[formationIndex]
                val prevFormation = group.formations[formationIndex - 1]

                // 获取原始红色区域和绿色区域的边界
                val redStart = prevFormation.timestamp + prevFormation.holdDuration
                val greenEnd = formation.timestamp + formation.holdDuration

                // 限制 timestamp 范围：[redStart + 1ms, greenEnd - 1ms]
                val minTimestamp = redStart + 1L  // 红色区至少1ms
                val maxTimestamp = greenEnd - 1L

                // 如果没有有效范围，不移动
                if (minTimestamp > maxTimestamp) return@withLock

                val clampedTimestamp = newTimestamp.coerceIn(minTimestamp, maxTimestamp)

                // 更新时间戳和相关区域
                formation.timestamp = clampedTimestamp
                formation.duration = clampedTimestamp - redStart  // 红色区长度
                formation.holdDuration = greenEnd - clampedTimestamp

                // 通知更新

                emitGroupUpdateLocked(group)
            }
        }
    }

internal fun FormationViewModel.recalculateTimestamps(group: DanceGroup) {
        android.util.Log.w("FormationVM", "[Warning] recalculateTimestamps 检测到时间戳冲突或异常")
        // 已废弃方法，保留空实现
        // 保留空实现以避免旧代码调用时出错
    }

internal fun FormationViewModel.regenerateTimelineAfterInsert(group: DanceGroup, insertIndex: Int) {
        android.util.Log.w("FormationVM", "[Warning] regenerateTimelineAfterInsert 校验失败，跳过 adjustAdjacentFormations")
        // 重新计算插入位置后的时间轴
        if (insertIndex >= 0 && insertIndex < group.formations.size) {
            val formation = group.formations[insertIndex]
            adjustAdjacentFormations(group, insertIndex, formation.timestamp)
        }
    }
