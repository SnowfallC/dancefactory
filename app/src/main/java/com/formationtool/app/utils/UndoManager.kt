package com.formationtool.app.utils

import com.formationtool.app.model.DanceGroup
import java.util.ArrayDeque

/** 保存完整编排快照；媒体引用保持当前值，避免撤销站位时恢复旧媒体。 */
class UndoManager {
    private val undoStack = ArrayDeque<DanceGroup>()
    private val redoStack = ArrayDeque<DanceGroup>()
    private val maxUndoSteps = 50

    fun saveSnapshot(group: DanceGroup) {
        undoStack.addFirst(snapshot(group))
        trim(undoStack)
        redoStack.clear()
    }

    fun undo(currentGroup: DanceGroup): DanceGroup? = restore(undoStack, redoStack, currentGroup)
    fun redo(currentGroup: DanceGroup): DanceGroup? = restore(redoStack, undoStack, currentGroup)
    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun getStackSize(): Int = undoStack.size
    fun clear() { undoStack.clear(); redoStack.clear() }

    private fun restore(from: ArrayDeque<DanceGroup>, to: ArrayDeque<DanceGroup>, current: DanceGroup): DanceGroup? {
        if (from.isEmpty()) return null
        to.addFirst(snapshot(current))
        trim(to)
        val saved = from.removeFirst()
        current.stageWidth = saved.stageWidth
        current.stageDepth = saved.stageDepth
        current.standardMode = saved.standardMode
        current.backstageVisible = saved.backstageVisible
        current.dancerCount = saved.dancerCount
        current.formations.clear()
        current.formations.addAll(saved.formations)
        return current
    }

    private fun trim(stack: ArrayDeque<DanceGroup>) {
        while (stack.size > maxUndoSteps) stack.removeLast()
    }

    private fun snapshot(group: DanceGroup): DanceGroup = group.copy(
        formations = group.formations.map { formation -> formation.copy(
            dancers = formation.dancers.map { it.copy() }.toMutableList(),
            props = formation.props.map { it.copy() }.toMutableList()
        ) }.toMutableList()
    )
}
