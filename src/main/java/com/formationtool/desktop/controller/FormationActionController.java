package com.formationtool.desktop.controller;

import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.view.TimelineView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class FormationActionController {
    private static final long KEYFRAME_MATCH_TOLERANCE_MS = 50L;

    private static final class KeyframeClipboard {
        private final long sourceTimestamp;
        private final Map<String, float[]> dancerPositions;

        private KeyframeClipboard(long sourceTimestamp, Map<String, float[]> dancerPositions) {
            this.sourceTimestamp = sourceTimestamp;
            this.dancerPositions = dancerPositions;
        }
    }

    private final FormationViewModel viewModel;
    private final DataManager dataManager;
    private final TimelineView timelineView;
    private final StageView stageView;
    private final Runnable formationListUpdater;
    private final Consumer<String> statusUpdater;
    private KeyframeClipboard keyframeClipboard;

    public FormationActionController(
        FormationViewModel viewModel,
        DataManager dataManager,
        TimelineView timelineView,
        StageView stageView,
        Runnable formationListUpdater,
        Consumer<String> statusUpdater
    ) {
        this.viewModel = viewModel;
        this.dataManager = dataManager;
        this.timelineView = timelineView;
        this.stageView = stageView;
        this.formationListUpdater = formationListUpdater;
        this.statusUpdater = statusUpdater;
    }

    public void batchDeleteFormations(ListView<String> formationListView) {
        if (viewModel.getCurrentGroup() == null || formationListView == null) {
            return;
        }

        ObservableList<Integer> selectedIndices = formationListView.getSelectionModel().getSelectedIndices();
        if (selectedIndices.isEmpty()) {
            showWarning("提示", "没有选中关键帧", "请在列表中选中要删除的关键帧（可多选）。");
            return;
        }

        List<Integer> indicesToDelete = new ArrayList<>(selectedIndices);
        if (indicesToDelete.contains(0)) {
            showWarning("无法删除", "不能删除第一帧", "第一帧（初始帧）不能被删除。");
            return;
        }

        DanceGroup group = viewModel.getCurrentGroup();
        if (indicesToDelete.size() >= group.getFormations().size()) {
            showWarning("无法删除", "至少保留一帧", "必须至少保留一个关键帧。");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认批量删除");
        confirm.setHeaderText("批量删除关键帧");
        confirm.setContentText("确定要删除选中的 " + indicesToDelete.size() + " 个关键帧吗？");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        viewModel.getUndoManager().saveSnapshot(group);

        indicesToDelete.sort(Collections.reverseOrder());
        List<Formation> formations = group.getFormations();

        for (int index : indicesToDelete) {
            if (index <= 0 || index >= formations.size()) {
                continue;
            }

            formations.remove(index);

            // 应用删除后的红绿区间合并规则，保证下一个关键帧衔接正确。
            if (index < formations.size() && index > 0) {
                Formation nextFormation = formations.get(index);
                Formation prevFormation = formations.get(index - 1);
                long prevGreenEnd = prevFormation.getTimestamp() + prevFormation.getHoldDuration();
                long newDuration = nextFormation.getTimestamp() - prevGreenEnd;
                nextFormation.setDuration(Math.max(1L, newDuration));
            }
        }

        dataManager.saveProject(group);
        formationListUpdater.run();
        timelineView.setWaveformData(timelineView.getWaveformData());
        stageView.render();
        statusUpdater.accept("已删除 " + indicesToDelete.size() + " 个关键帧");
    }

    /**
     * 复制当前关键帧的位置数据到内部剪贴板
     */
    public void copyCurrentKeyframe() {
        DanceGroup group = viewModel.getCurrentGroup();
        if (group == null) {
            statusUpdater.accept("请先创建工程");
            return;
        }

        List<Formation> formations = group.getFormations();
        if (formations.isEmpty()) {
            statusUpdater.accept("请先添加关键帧");
            return;
        }

        int currentIndex = viewModel.getCurrentFormationIndex();
        int safeIndex = Math.max(0, Math.min(currentIndex, formations.size() - 1));
        Formation sourceFormation = formations.get(safeIndex);
        Map<String, float[]> dancerPositions = extractDancerPositions(sourceFormation);

        if (dancerPositions.isEmpty()) {
            statusUpdater.accept("当前关键帧没有可复制的位置数据");
            return;
        }

        keyframeClipboard = new KeyframeClipboard(sourceFormation.getTimestamp(), dancerPositions);
        statusUpdater.accept("已复制关键帧 " + formatTime(sourceFormation.getTimestamp()) + "，可在其他时间粘贴");
    }

    /**
     * 将剪贴板中的关键帧位置粘贴到当前时间
     */
    public void pasteCopiedKeyframeToCurrentTime() {
        if (keyframeClipboard == null || keyframeClipboard.dancerPositions.isEmpty()) {
            statusUpdater.accept("剪贴板为空，请先复制关键帧");
            return;
        }

        DanceGroup group = viewModel.getCurrentGroup();
        if (group == null) {
            statusUpdater.accept("请先创建工程");
            return;
        }

        List<Formation> formations = group.getFormations();
        if (formations.isEmpty()) {
            statusUpdater.accept("请先添加关键帧");
            return;
        }

        long currentTime = viewModel.getCurrentTime();
        int hitIndex = findFormationIndexAtTime(formations, currentTime);
        if (hitIndex >= 0) {
            Formation targetFormation = formations.get(hitIndex);
            viewModel.getUndoManager().saveSnapshot(group);
            int appliedCount = applyDancerPositionsToFormation(group, targetFormation, keyframeClipboard.dancerPositions);
            if (appliedCount <= 0) {
                statusUpdater.accept("粘贴失败：舞者数据不匹配");
                return;
            }

            viewModel.setCurrentFormationIndex(hitIndex);
            viewModel.setCurrentTime(targetFormation.getTimestamp());
            viewModel.markGroupChanged();
            formationListUpdater.run();
            timelineView.setWaveformData(timelineView.getWaveformData());
            stageView.render();
            statusUpdater.accept(
                "已将 " + formatTime(keyframeClipboard.sourceTimestamp)
                    + " 粘贴到关键帧 " + formatTime(targetFormation.getTimestamp())
            );
            return;
        }

        Formation newFormation = viewModel.insertKeyframeSmart(currentTime, keyframeClipboard.dancerPositions);
        if (newFormation == null) {
            statusUpdater.accept("粘贴失败：无法创建关键帧");
            return;
        }

        int insertIndex = formations.indexOf(newFormation);
        if (insertIndex >= 0) {
            viewModel.setCurrentFormationIndex(insertIndex);
        }

        formationListUpdater.run();
        timelineView.setWaveformData(timelineView.getWaveformData());
        stageView.render();
        statusUpdater.accept(
            "已将 " + formatTime(keyframeClipboard.sourceTimestamp)
                + " 粘贴为新关键帧 " + formatTime(newFormation.getTimestamp())
        );
    }

    public void copyPreviousKeyframe() {
        DanceGroup group = viewModel.getCurrentGroup();
        if (group == null) {
            statusUpdater.accept("请先创建工程");
            return;
        }

        List<Formation> formations = group.getFormations();
        if (formations.isEmpty()) {
            statusUpdater.accept("请先添加关键帧");
            return;
        }

        long currentTime = viewModel.getCurrentTime();
        int currentIndex = viewModel.getCurrentFormationIndex();
        Formation currentFormation = formations.get(currentIndex);
        boolean onKeyframe = Math.abs(currentTime - currentFormation.getTimestamp()) < KEYFRAME_MATCH_TOLERANCE_MS;

        if (onKeyframe) {
            copyToCurrentKeyframe(group, formations, currentIndex, currentFormation);
            return;
        }

        Formation prevFormation = findPreviousFormation(formations, currentTime);
        if (prevFormation == null) {
            statusUpdater.accept("当前时间之前没有关键帧");
            return;
        }

        Map<String, float[]> dancerPositions = extractDancerPositions(prevFormation);

        Formation newFormation = viewModel.insertKeyframeSmart(currentTime, dancerPositions);
        if (newFormation == null) {
            statusUpdater.accept("创建关键帧失败");
            return;
        }

        int insertIndex = formations.indexOf(newFormation);
        if (insertIndex >= 0) {
            viewModel.setCurrentFormationIndex(insertIndex);
        }
        formationListUpdater.run();
        stageView.render();
        statusUpdater.accept("已在当前时间创建新关键帧并复制前一帧位置");
    }

    private void copyToCurrentKeyframe(
        DanceGroup group,
        List<Formation> formations,
        int currentIndex,
        Formation currentFormation
    ) {
        if (currentIndex <= 0) {
            statusUpdater.accept("已经是第一帧，无法复制");
            return;
        }

        Formation prevFormation = formations.get(currentIndex - 1);
        viewModel.getUndoManager().saveSnapshot(group);
        int appliedCount = applyDancerPositionsToFormation(group, currentFormation, extractDancerPositions(prevFormation));
        if (appliedCount <= 0) {
            statusUpdater.accept("复制失败：舞者数据不匹配");
            return;
        }

        viewModel.markGroupChanged();
        formationListUpdater.run();
        timelineView.setWaveformData(timelineView.getWaveformData());
        stageView.render();
        statusUpdater.accept("已复制前一帧的舞者位置");
    }

    private Formation findPreviousFormation(List<Formation> formations, long currentTime) {
        for (int i = formations.size() - 1; i >= 0; i--) {
            Formation formation = formations.get(i);
            if (formation.getTimestamp() < currentTime) {
                return formation;
            }
        }
        return null;
    }

    private int findFormationIndexAtTime(List<Formation> formations, long targetTimeMs) {
        for (int i = 0; i < formations.size(); i++) {
            Formation formation = formations.get(i);
            if (Math.abs(formation.getTimestamp() - targetTimeMs) <= KEYFRAME_MATCH_TOLERANCE_MS) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, float[]> extractDancerPositions(Formation formation) {
        Map<String, float[]> dancerPositions = new HashMap<>();
        if (formation == null) {
            return dancerPositions;
        }

        for (Dancer dancer : formation.getDancers()) {
            dancerPositions.put(dancer.getId(), new float[]{dancer.getX(), dancer.getY()});
        }
        return dancerPositions;
    }

    private int applyDancerPositionsToFormation(
        DanceGroup group,
        Formation targetFormation,
        Map<String, float[]> dancerPositions
    ) {
        if (group == null || targetFormation == null || dancerPositions == null || dancerPositions.isEmpty()) {
            return 0;
        }

        float halfWidth = group.getStageWidth() / 2f;
        float halfDepth = group.getStageDepth() / 2f;
        int appliedCount = 0;

        for (Dancer targetDancer : targetFormation.getDancers()) {
            float[] copiedPos = dancerPositions.get(targetDancer.getId());
            if (copiedPos == null || copiedPos.length < 2) {
                continue;
            }

            float safeX = Math.max(-halfWidth, Math.min(halfWidth, copiedPos[0]));
            float safeY = Math.max(-halfDepth, Math.min(halfDepth, copiedPos[1]));
            targetDancer.setX(safeX);
            targetDancer.setY(safeY);
            appliedCount++;
        }

        return appliedCount;
    }

    private String formatTime(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / 60000) % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void showWarning(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
