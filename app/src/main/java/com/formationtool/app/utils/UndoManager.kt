package com.formationtool.app.utils

import com.formationtool.app.model.DanceGroup
import com.formationtool.app.model.Dancer
import com.formationtool.app.model.Formation
import java.util.*

/**
 * 撤销管理器（Android版）
 * 使用快照模式保存状态
 */
class UndoManager {

    // 撤销栈（存储状态快照）
    private val undoStack = ArrayDeque<DanceGroupSnapshot>()

    // 最大撤销步数
    private val maxUndoSteps = 50

    // 是否正在执行撤销（防止撤销操作本身被记录）
    private var isUndoing = false

    /**
     * 保存当前状态快照（在执行修改操作前调用）
     */
    fun saveSnapshot(group: DanceGroup) {
        if (isUndoing) return

        val snapshot = createSnapshot(group)
        undoStack.addFirst(snapshot)

        // 限制栈大小
        while (undoStack.size > maxUndoSteps) {
            undoStack.removeLast()
        }

        println("[撤销管理器] 保存快照，当前栈深度: ${undoStack.size}")
    }

    /**
     * 撤销最近的操作
     * @param currentGroup 当前的 DanceGroup（用于恢复）
     * @return 恢复后的 DanceGroup，如果无法撤销返回 null
     */
    fun undo(currentGroup: DanceGroup): DanceGroup? {
        if (undoStack.isEmpty()) {
            println("[撤销管理器] 撤销栈为空，无法撤销")
            return null
        }

        isUndoing = true
        return try {
            val snapshot = undoStack.removeFirst()
            val restored = restoreFromSnapshot(snapshot, currentGroup)
            println("[撤销管理器] 撤销成功，剩余栈深度: ${undoStack.size}")
            restored
        } finally {
            isUndoing = false
        }
    }

    /**
     * 是否可以撤销
     */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /**
     * 清空撤销栈（切换工程时调用）
     */
    fun clear() {
        undoStack.clear()
        println("[撤销管理器] 撤销栈已清空")
    }

    /**
     * 获取当前撤销栈深度
     */
    fun getStackSize(): Int = undoStack.size

    /**
     * 创建 DanceGroup 的快照
     */
    private fun createSnapshot(group: DanceGroup): DanceGroupSnapshot {
        val snapshot = DanceGroupSnapshot().apply {
            id = group.id
            name = group.name
            stageWidth = group.stageWidth
            stageDepth = group.stageDepth
            dancerCount = group.dancerCount
            musicPath = group.musicPath
            audioPath = group.audioPath
            audioUri = group.audioUri
            referenceVideoPath = group.referenceVideoPath
            referenceVideoUri = group.referenceVideoUri
        }

        // 深拷贝 formations
        for (formation in group.formations) {
            val fs = FormationSnapshot().apply {
                id = formation.id
                name = formation.name
                timestamp = formation.timestamp
                holdDuration = formation.holdDuration
                duration = formation.duration
            }

            // 深拷贝 dancers
            for (dancer in formation.dancers) {
                val ds = DancerSnapshot().apply {
                    id = dancer.id
                    name = dancer.name
                    x = dancer.x
                    y = dancer.y
                    color = dancer.color
                    customStylePath = dancer.customStylePath
                    isSelected = dancer.isSelected
                }
                fs.dancers.add(ds)
            }

            snapshot.formations.add(fs)
        }

        return snapshot
    }

    /**
     * 从快照恢复 DanceGroup
     */
    private fun restoreFromSnapshot(snapshot: DanceGroupSnapshot, currentGroup: DanceGroup): DanceGroup {
        // 清空当前 formations
        currentGroup.formations.clear()

        // 恢复基本属性
        currentGroup.dancerCount = snapshot.dancerCount

        // 恢复 formations
        for (fs in snapshot.formations) {
            val formation = Formation(id = fs.id, name = fs.name, timestamp = fs.timestamp).apply {
                holdDuration = fs.holdDuration
                duration = fs.duration
            }

            // 恢复 dancers
            for (ds in fs.dancers) {
                val dancer = Dancer(ds.id, ds.name, ds.x, ds.y, 0.5f, ds.color).apply {
                    customStylePath = ds.customStylePath
                    isSelected = ds.isSelected
                }
                formation.dancers.add(dancer)
            }

            currentGroup.formations.add(formation)
        }

        return currentGroup
    }

    // ========== 内部快照类 ==========

    private class DanceGroupSnapshot {
        var id: String = ""
        var name: String = ""
        var stageWidth: Float = 0f
        var stageDepth: Float = 0f
        var dancerCount: Int = 0
        var musicPath: String? = null
        var audioPath: String? = null
        var audioUri: String? = null
        var referenceVideoPath: String? = null
        var referenceVideoUri: String? = null
        val formations = mutableListOf<FormationSnapshot>()
    }

    private class FormationSnapshot {
        var id: String = ""
        var name: String = ""
        var timestamp: Long = 0
        var holdDuration: Long = 0
        var duration: Long = 0
        val dancers = mutableListOf<DancerSnapshot>()
    }

    private class DancerSnapshot {
        var id: String = ""
        var name: String = ""
        var x: Float = 0f
        var y: Float = 0f
        var color: Int = 0
        var customStylePath: String? = null
        var isSelected: Boolean = false
    }
}
