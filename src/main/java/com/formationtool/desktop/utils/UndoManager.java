package com.formationtool.desktop.utils;

import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 撤销管理器 - 基于状态快照实现
 * 支持 Ctrl+Z 撤销最近的操作
 */
public class UndoManager {

    // 撤销栈（存储状态快照）
    private final Deque<DanceGroupSnapshot> undoStack = new ArrayDeque<>();
    // 重做栈（存储被撤销前的状态快照）
    private final Deque<DanceGroupSnapshot> redoStack = new ArrayDeque<>();

    // 最大撤销步数
    private static final int MAX_UNDO_STEPS = 50;

    // 是否正在执行撤销/重做（防止恢复过程本身被记录）
    private boolean isUndoing = false;

    /**
     * 保存当前状态快照（在执行修改操作前调用）
     */
    public void saveSnapshot(DanceGroup group) {
        if (group == null || isUndoing) return;

        DanceGroupSnapshot snapshot = createSnapshot(group);
        undoStack.push(snapshot);
        trimStack(undoStack);
        // 新操作产生后，之前的重做链失效
        redoStack.clear();

        System.out.println("[撤销管理器] 保存快照，当前栈深度: " + undoStack.size());
    }

    /**
     * 撤销最近的操作
     * @param currentGroup 当前的 DanceGroup（用于恢复）
     * @return 恢复后的 DanceGroup，如果无法撤销返回 null
     */
    public DanceGroup undo(DanceGroup currentGroup) {
        if (undoStack.isEmpty() || currentGroup == null) {
            System.out.println("[撤销管理器] 撤销栈为空，无法撤销");
            return null;
        }

        isUndoing = true;
        try {
            // 撤销前先保存当前状态，供重做使用
            redoStack.push(createSnapshot(currentGroup));
            trimStack(redoStack);

            DanceGroupSnapshot snapshot = undoStack.pop();
            DanceGroup restored = restoreFromSnapshot(snapshot, currentGroup);
            System.out.println("[撤销管理器] 撤销成功，剩余栈深度: " + undoStack.size());
            return restored;
        } finally {
            isUndoing = false;
        }
    }

    /**
     * 重做最近一次撤销
     * @param currentGroup 当前的 DanceGroup（用于恢复）
     * @return 恢复后的 DanceGroup，如果无法重做返回 null
     */
    public DanceGroup redo(DanceGroup currentGroup) {
        if (redoStack.isEmpty() || currentGroup == null) {
            System.out.println("[撤销管理器] 重做栈为空，无法重做");
            return null;
        }

        isUndoing = true;
        try {
            // 重做前先保存当前状态，支持再次撤销
            undoStack.push(createSnapshot(currentGroup));
            trimStack(undoStack);

            DanceGroupSnapshot snapshot = redoStack.pop();
            DanceGroup restored = restoreFromSnapshot(snapshot, currentGroup);
            System.out.println("[撤销管理器] 重做成功，剩余重做栈深度: " + redoStack.size());
            return restored;
        } finally {
            isUndoing = false;
        }
    }

    /**
     * 是否可以撤销
     */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /**
     * 是否可以重做
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * 清空撤销栈（切换工程时调用）
     */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        System.out.println("[撤销管理器] 撤销栈已清空");
    }

    /**
     * 获取当前撤销栈深度
     */
    public int getStackSize() {
        return undoStack.size();
    }

    private void trimStack(Deque<DanceGroupSnapshot> stack) {
        while (stack.size() > MAX_UNDO_STEPS) {
            ((ArrayDeque<DanceGroupSnapshot>) stack).removeLast();
        }
    }

    /**
     * 创建 DanceGroup 的快照
     */
    private DanceGroupSnapshot createSnapshot(DanceGroup group) {
        DanceGroupSnapshot snapshot = new DanceGroupSnapshot();
        snapshot.id = group.getId();
        snapshot.name = group.getName();
        snapshot.standardMode = group.isStandardMode();
        snapshot.backstageVisible = group.isBackstageVisible();
        snapshot.stageWidth = group.getStageWidth();
        snapshot.stageDepth = group.getStageDepth();
        snapshot.dancerCount = group.getDancerCount();
        snapshot.musicPath = group.getMusicPath();
        snapshot.audioPath = group.getAudioPath();
        snapshot.audioUri = group.getAudioUri();
        snapshot.referenceVideoPath = group.getReferenceVideoPath();
        snapshot.referenceVideoUri = group.getReferenceVideoUri();

        // 深拷贝 formations
        for (Formation formation : group.getFormations()) {
            FormationSnapshot fs = new FormationSnapshot();
            fs.id = formation.getId();
            fs.name = formation.getName();
            fs.timestamp = formation.getTimestamp();
            fs.holdDuration = formation.getHoldDuration();
            fs.duration = formation.getDuration();

            // 深拷贝 dancers
            for (Dancer dancer : formation.getDancers()) {
                DancerSnapshot ds = new DancerSnapshot();
                ds.id = dancer.getId();
                ds.name = dancer.getName();
                ds.x = dancer.getX();
                ds.y = dancer.getY();
                ds.color = dancer.getColor();
                ds.customStylePath = dancer.getCustomStylePath();
                ds.isSelected = dancer.isSelected();
                fs.dancers.add(ds);
            }

            snapshot.formations.add(fs);
        }

        return snapshot;
    }

    /**
     * 从快照恢复 DanceGroup
     */
    private DanceGroup restoreFromSnapshot(DanceGroupSnapshot snapshot, DanceGroup currentGroup) {
        // 清空当前 formations
        currentGroup.getFormations().clear();

        // 恢复基本属性
        currentGroup.setDancerCount(snapshot.dancerCount);
        currentGroup.setStandardMode(snapshot.standardMode);
        currentGroup.setBackstageVisible(snapshot.backstageVisible);

        // 恢复 formations
        for (FormationSnapshot fs : snapshot.formations) {
            Formation formation = new Formation(fs.id, fs.name, fs.timestamp);
            formation.setHoldDuration(fs.holdDuration);
            formation.setDuration(fs.duration);

            // 恢复 dancers
            for (DancerSnapshot ds : fs.dancers) {
                Dancer dancer = new Dancer(ds.id, ds.name, ds.x, ds.y, ds.color);
                dancer.setCustomStylePath(ds.customStylePath);
                dancer.setSelected(ds.isSelected);
                formation.getDancers().add(dancer);
            }

            currentGroup.getFormations().add(formation);
        }

        return currentGroup;
    }

    // ========== 内部快照类 ==========

    private static class DanceGroupSnapshot {
        String id;
        String name;
        boolean standardMode;
        boolean backstageVisible;
        float stageWidth;
        float stageDepth;
        int dancerCount;
        String musicPath;
        String audioPath;
        String audioUri;
        String referenceVideoPath;
        String referenceVideoUri;
        java.util.List<FormationSnapshot> formations = new java.util.ArrayList<>();
    }

    private static class FormationSnapshot {
        String id;
        String name;
        long timestamp;
        long holdDuration;
        long duration;
        java.util.List<DancerSnapshot> dancers = new java.util.ArrayList<>();
    }

    private static class DancerSnapshot {
        String id;
        String name;
        float x;
        float y;
        int color;
        String customStylePath;
        boolean isSelected;
    }
}
