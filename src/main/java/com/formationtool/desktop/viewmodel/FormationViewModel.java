package com.formationtool.desktop.viewmodel;

import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.utils.UndoManager;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 *
 */
public class FormationViewModel {

    // Observable 属性
    private final ObjectProperty<DanceGroup> currentGroup = new SimpleObjectProperty<>();
    private final IntegerProperty currentFormationIndex = new SimpleIntegerProperty(0);
    private final LongProperty currentTime = new SimpleLongProperty(0);
    private final BooleanProperty isPlaying = new SimpleBooleanProperty(false);
    private final LongProperty audioDuration = new SimpleLongProperty(0);

    // 撤销管理器
    private final UndoManager undoManager = new UndoManager();

    //
    private volatile boolean isDirty = false;

    //

    //
    public FormationViewModel() {
        //
        currentTime.addListener((obs, oldVal, newVal) -> {
            if (currentGroup.get() != null) {
                int formationIndex = getFormationIndexAtTime(currentGroup.get(), newVal.longValue());
                if (currentFormationIndex.get() != formationIndex) {
                    currentFormationIndex.set(formationIndex);
                }
            }
        });
    }

    // 创建新组并初始化默认队形
    public void createNewGroup(String name, int dancerCount, float stageWidth, float stageDepth) {
        DanceGroup group = new DanceGroup(UUID.randomUUID().toString(), name, stageWidth, stageDepth);
        group.setDancerCount(dancerCount);

        // 创建初始队形
        Formation initialFormation = new Formation(
            UUID.randomUUID().toString(),
            "关键帧 00:00",
            0L
        );

        //
        float spacing = stageWidth / (dancerCount + 1);
        for (int i = 0; i < dancerCount; i++) {
            Dancer dancer = new Dancer(
                UUID.randomUUID().toString(),
                "Dancer " + (i + 1),
                spacing * (i + 1) - stageWidth / 2,
                0f,
                getDefaultColor(i)
            );
            initialFormation.getDancers().add(dancer);
        }

        group.getFormations().add(initialFormation);
        currentGroup.set(group);
        currentFormationIndex.set(0);
    }

    //
    //
    public void addDancer(String name, int color) {
        DanceGroup group = currentGroup.get();
        if (group == null) return;

        //
        undoManager.saveSnapshot(group);

        int currentCount = group.getFormations().get(0).getDancers().size();
        String dancerId = UUID.randomUUID().toString();
        String dancerName = name.isEmpty() ? "Dancer " + (currentCount + 1) : name;

        float halfWidth = group.getStageWidth() / 2f;
        float halfDepth = group.getStageDepth() / 2f;

        //
        //
        float newX = 0f;  // 中间位置
        float newY = halfDepth - 1f;

        //
        for (Formation formation : group.getFormations()) {
            Dancer newDancer = new Dancer(
                dancerId,
                dancerName,
                newX,
                newY,
                color
            );
            formation.getDancers().add(newDancer);
        }

        group.setDancerCount(currentCount + 1);
        notifyGroupChange();
    }

    //
    public void deleteDancer(String dancerId) {
        DanceGroup group = currentGroup.get();
        if (group == null) return;

        //
        undoManager.saveSnapshot(group);

        for (Formation formation : group.getFormations()) {
            formation.getDancers().removeIf(d -> d.getId().equals(dancerId));
        }

        group.setDancerCount(group.getFormations().get(0).getDancers().size());
        notifyGroupChange();
    }

    //
    public void updateDancerPosition(String dancerId, float x, float y) {
        DanceGroup group = currentGroup.get();
        if (group == null) return;

        Formation formation = group.getFormations().get(currentFormationIndex.get());
        float halfWidth = group.getStageWidth() / 2f;
        float halfDepth = group.getStageDepth() / 2f;

        for (Dancer dancer : formation.getDancers()) {
            if (dancer.getId().equals(dancerId)) {
                dancer.setX(Math.max(-halfWidth, Math.min(halfWidth, x)));
                dancer.setY(Math.max(-halfDepth, Math.min(halfDepth, y)));
                break;
            }
        }

        //
        notifyGroupChangeLightweight();
    }

    public void updateDancerStyle(String dancerId, String customStylePath) {
        DanceGroup group = currentGroup.get();
        if (group == null) return;

        undoManager.saveSnapshot(group);

        for (Formation formation : group.getFormations()) {
            for (Dancer dancer : formation.getDancers()) {
                if (dancer.getId().equals(dancerId)) {
                    dancer.setCustomStylePath(customStylePath);
                }
            }
        }

        notifyGroupChange();
    }

    //
    public void toggleDancerSelection(String dancerId) {
        DanceGroup group = currentGroup.get();
        if (group == null) return;

        //
        for (Formation formation : group.getFormations()) {
            for (Dancer dancer : formation.getDancers()) {
                dancer.setSelected(dancer.getId().equals(dancerId));
            }
        }

        //
        notifyGroupChangeLightweight();
    }


    //
    public void clearSelection() {
        DanceGroup group = currentGroup.get();
        if (group == null) return;

        for (Formation formation : group.getFormations()) {
            for (Dancer dancer : formation.getDancers()) {
                dancer.setSelected(false);
            }
        }

        //
        notifyGroupChangeLightweight();
    }


    // 添加队形（关键帧）
    public void addFormation(String name) {
        DanceGroup group = currentGroup.get();
        if (group == null) return;

        //
        undoManager.saveSnapshot(group);

        //
        long newTimestamp = currentTime.get();

        //
        for (Formation f : group.getFormations()) {
            if (f.getTimestamp() == newTimestamp) {
                //
                for (int i = 0; i < group.getFormations().size(); i++) {
                    if (group.getFormations().get(i).getId().equals(f.getId())) {
                        currentFormationIndex.set(i);
                        break;
                    }
                }
                return;
            }
        }

        Formation currentFormation = group.getFormations().get(currentFormationIndex.get());
        String formationName = name.isEmpty() ? "\u5173\u952e\u5e27 " + formatTime(newTimestamp) : name;

        Formation newFormation = new Formation(
            UUID.randomUUID().toString(),
            formationName,
            newTimestamp
        );
        //
        newFormation.setHoldDuration(4000L);
        newFormation.setDuration(2000L);

        //
        for (Dancer dancer : currentFormation.getDancers()) {
            newFormation.getDancers().add(dancer.copy());
        }

        group.getFormations().add(newFormation);

        // 按时间戳排序队列
        group.getFormations().sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        //
        int insertIndex = -1;
        for (int i = 0; i < group.getFormations().size(); i++) {
            if (group.getFormations().get(i).getId().equals(newFormation.getId())) {
                insertIndex = i;
                currentFormationIndex.set(i);
                break;
            }
        }

        //
        if (insertIndex > 0) {
            adjustAdjacentFormations(group, insertIndex, newTimestamp);
        }

        notifyGroupChange();
    }

    /**
     * 格式化时间为 mm:ss
     */
    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     *
     *
     */
    public void deleteFormation(int index) {
        DanceGroup group = currentGroup.get();
        if (group == null || group.getFormations().size() <= 1) return;

        //
        if (index <= 0) return;

        //
        undoManager.saveSnapshot(group);

        //
        Formation deletedFormation = group.getFormations().get(index);
        long deletedRedStart = deletedFormation.getTimestamp() - deletedFormation.getDuration();

        //
        Formation prevFormation = group.getFormations().get(index - 1);
        long prevGreenEnd = prevFormation.getTimestamp() + prevFormation.getHoldDuration();

        //
        group.getFormations().remove(index);

        //
        //
        if (index < group.getFormations().size()) {
            Formation nextFormation = group.getFormations().get(index);
            //
            long newRedDuration = nextFormation.getTimestamp() - prevGreenEnd;
            nextFormation.setDuration(Math.max(0L, newRedDuration));
        }
        //

        // 调整当前队形索引
        if (currentFormationIndex.get() >= group.getFormations().size()) {
            currentFormationIndex.set(group.getFormations().size() - 1);
        } else if (currentFormationIndex.get() == index) {
            currentFormationIndex.set(Math.max(0, index - 1));
        }

        notifyGroupChange();
    }

    /**
     *
     *
     * 时间线模型说明：
     *
     *
     *
     */
    private int getFormationIndexAtTime(DanceGroup group, long t) {
        List<Formation> formations = group.getFormations();
        if (formations.isEmpty()) return 0;
        if (formations.size() == 1) return 0;

        //
        for (int i = 0; i < formations.size(); i++) {
            Formation f = formations.get(i);
            long timestamp = f.getTimestamp();
            long redStart = timestamp - f.getDuration();
            long greenEnd = timestamp + f.getHoldDuration();

            //
            if (i > 0 && f.getDuration() > 0 && t >= redStart && t < timestamp) {
                return i;
            }

            //
            if (t >= timestamp && t < greenEnd) {
                return i;
            }
        }

        //
        return formations.size() - 1;
    }

    /**
     *
     */
    private void invalidateCache() {
        //
    }

    // 获取默认舞者颜色
    private int getDefaultColor(int index) {
        int[] colors = {
            0xFF2196F3, // 蓝色
            0xFFF44336, // 红色
            0xFF4CAF50, // 绿色
            0xFFFF9800, // 橙色
            0xFF9C27B0, // 紫色
            0xFFFFEB3B, // 黄色
            0xFF00BCD4, // 青色
            0xFFE91E63  // 粉色
        };
        return colors[index % colors.length];
    }

    //
    private void notifyGroupChange() {
        DanceGroup group = currentGroup.get();
        if (group != null) {
            group.setModifiedTime(System.currentTimeMillis());
            //
            isDirty = true;
            // 刷新缓存，触发重新渲染
            invalidateCache();

            // JavaFX 模块化环境下反射调用 fireValueChangedEvent 会失败，这里直接使用稳定的刷新策略
            refreshCurrentGroupProperty();
        }
    }

    /**
     *
     *
     */
    private void notifyGroupChangeLightweight() {
        DanceGroup group = currentGroup.get();
        if (group != null) {
            group.setModifiedTime(System.currentTimeMillis());
            //
            isDirty = true;
            //

            refreshCurrentGroupProperty();
        }
    }

    /**
     * 外部批量修改模型后，统一触发一次完整刷新与脏标记
     */
    public void markGroupChanged() {
        notifyGroupChange();
    }

    // 强制触发 currentGroup 的变更通知，确保依赖 ChangeListener 的 UI 能刷新
    private void refreshCurrentGroupProperty() {
        DanceGroup temp = currentGroup.get();
        currentGroup.set(null);
        currentGroup.set(temp);
    }

    /**
     *
     */
    public boolean isDirty() {
        return isDirty;
    }

    /**
     *
     */
    public void clearDirty() {
        isDirty = false;
    }

    // Property 访问器方法
    public ObjectProperty<DanceGroup> currentGroupProperty() { return currentGroup; }
    public DanceGroup getCurrentGroup() { return currentGroup.get(); }
    public void setCurrentGroup(DanceGroup group) {
        currentGroup.set(group);
        if (group == null || group.getFormations().isEmpty()) {
            currentFormationIndex.set(0);
            return;
        }
        currentFormationIndex.set(0);
    }

    public IntegerProperty currentFormationIndexProperty() { return currentFormationIndex; }
    public int getCurrentFormationIndex() { return currentFormationIndex.get(); }
    public void setCurrentFormationIndex(int index) {
        DanceGroup group = currentGroup.get();
        if (group == null || group.getFormations().isEmpty()) {
            currentFormationIndex.set(0);
            return;
        }
        int safeIndex = Math.max(0, Math.min(index, group.getFormations().size() - 1));
        currentFormationIndex.set(safeIndex);
    }

    public LongProperty currentTimeProperty() { return currentTime; }
    public long getCurrentTime() { return currentTime.get(); }
    public void setCurrentTime(long time) { currentTime.set(time); }

    //
    public void updateCurrentTimeFromPlayer(long time) {
        //
        currentTime.set(time);
    }

    public BooleanProperty isPlayingProperty() { return isPlaying; }
    public boolean isPlaying() { return isPlaying.get(); }
    public void setPlaying(boolean playing) { isPlaying.set(playing); }

    public LongProperty audioDurationProperty() { return audioDuration; }
    public long getAudioDuration() { return audioDuration.get(); }
    public void setAudioDuration(long duration) { audioDuration.set(duration); }

    /**
     *
     * 时间线模型说明：
     *
     *
     *
     *
     */
    public void updateFormationHoldDuration(int formationIndex, long newHoldDuration) {
        if (currentGroup.get() == null) return;
        List<Formation> formations = currentGroup.get().getFormations();
        if (formationIndex < 0 || formationIndex >= formations.size()) return;

        Formation formation = formations.get(formationIndex);
        boolean isLastFormation = (formationIndex == formations.size() - 1);

        if (isLastFormation) {
            //
            long maxHoldDuration = audioDuration.get() - formation.getTimestamp();
            newHoldDuration = Math.max(0L, Math.min(newHoldDuration, maxHoldDuration));
            formation.setHoldDuration(newHoldDuration);
            //
        } else {
            //
            Formation nextFormation = formations.get(formationIndex + 1);
            long nextRedStart = nextFormation.getTimestamp() - nextFormation.getDuration();

            //
            long maxHoldDuration = nextRedStart - formation.getTimestamp();

            //
            maxHoldDuration = nextFormation.getTimestamp() - formation.getTimestamp();

            newHoldDuration = Math.max(0L, Math.min(newHoldDuration, maxHoldDuration));
            formation.setHoldDuration(newHoldDuration);

            //
            long currentGreenEnd = formation.getTimestamp() + newHoldDuration;
            long newNextRedDuration = nextFormation.getTimestamp() - currentGreenEnd;
            nextFormation.setDuration(Math.max(0L, newNextRedDuration));
        }

        notifyGroupChange();
    }

    /**
     *
     * 时间线模型说明：
     *
     *
     *
     *
     */
    public void updateFormationTransitionDuration(int formationIndex, long newTransitionDuration) {
        if (currentGroup.get() == null) return;
        List<Formation> formations = currentGroup.get().getFormations();
        if (formationIndex < 0 || formationIndex >= formations.size()) return;

        // 第一个队形不能修改过渡时长
        if (formationIndex == 0) return;

        //
        undoManager.saveSnapshot(currentGroup.get());

        Formation formation = formations.get(formationIndex);
        Formation prevFormation = formations.get(formationIndex - 1);

        //
        long prevGreenEnd = prevFormation.getTimestamp() + prevFormation.getHoldDuration();

        //
        long maxRedDuration = formation.getTimestamp() - prevGreenEnd;
        newTransitionDuration = Math.max(0L, Math.min(newTransitionDuration, maxRedDuration));

        formation.setDuration(newTransitionDuration);

        //
        notifyGroupChange();
    }

    /**
     *
     * 时间线模型说明：
     * - timestamp 表示队形到达目标位置的时间点
     *
     *
     *
     *
     *
     *
     */
    public void moveFormationTimestamp(int formationIndex, long newTimestamp) {
        if (currentGroup.get() == null) return;
        List<Formation> formations = currentGroup.get().getFormations();

        //
        if (formationIndex <= 0 || formationIndex >= formations.size()) return;

        Formation formation = formations.get(formationIndex);
        Formation prevFormation = formations.get(formationIndex - 1);

        //
        long prevGreenEnd = prevFormation.getTimestamp() + prevFormation.getHoldDuration();

        //
        long greenEndPosition = formation.getTimestamp() + formation.getHoldDuration();

        //
        newTimestamp = Math.max(prevGreenEnd, newTimestamp);

        //
        newTimestamp = Math.min(newTimestamp, greenEndPosition);

        //
        long newHoldDuration = greenEndPosition - newTimestamp;

        //
        long newRedDuration = newTimestamp - prevGreenEnd;

        //
        formation.setTimestamp(newTimestamp);
        formation.setDuration(Math.max(0L, newRedDuration));
        formation.setHoldDuration(newHoldDuration);

        notifyGroupChange();
    }

    /**
     *
     *
     *
     *
     *
     *
     */
    @Deprecated
    public void recalculateTimestamps(DanceGroup group) {
        List<Formation> formations = group.getFormations();
        if (formations.isEmpty()) return;

        // 第一个队形无过渡时长
        formations.get(0).setDuration(0L);

        //
        for (int i = 1; i < formations.size(); i++) {
            Formation prev = formations.get(i - 1);
            Formation curr = formations.get(i);

            long prevGreenEnd = prev.getTimestamp() + prev.getHoldDuration();
            long expectedRedDuration = curr.getTimestamp() - prevGreenEnd;

            //
            if (expectedRedDuration >= 0) {
                curr.setDuration(expectedRedDuration);
            } else {
                //
                curr.setDuration(0L);
                System.err.println("[Warning] 时间线冲突检测: frame " + i + " timestamp=" + curr.getTimestamp() + ", prevGreenEnd=" + prevGreenEnd);
            }
        }
    }

    /**
     *
     *
     *
     *
     *
     */
    public void createKeyframe(long timeMs, Map<String, float[]> originalPositions) {
        long startTime = System.currentTimeMillis();
        System.out.println("\n[createKeyframe] === 开始 === timeMs=" + timeMs);

        DanceGroup group = currentGroup.get();
        if (group == null) {
            System.out.println("[createKeyframe] group is null, return");
            return;
        }

        System.out.println("[createKeyframe] 当前队形数量: " + group.getFormations().size());

        //
        undoManager.saveSnapshot(group);

        int draggedFormationIdx = currentFormationIndex.get();
        Formation draggedFormation = group.getFormations().get(draggedFormationIdx);
        System.out.println("[createKeyframe] 拖拽队形索引: " + draggedFormationIdx + ", timestamp=" + draggedFormation.getTimestamp());

        //
        Map<String, float[]> newPositions = new HashMap<>();
        for (Dancer d : draggedFormation.getDancers()) {
            newPositions.put(d.getId(), new float[]{d.getX(), d.getY()});
        }

        //
        boolean isDraggingSameFrame = draggedFormation.getTimestamp() == timeMs;
        System.out.println("[createKeyframe] isDraggingSameFrame: " + isDraggingSameFrame);

        //
        int existingIndex = -1;
        for (int i = 0; i < group.getFormations().size(); i++) {
            if (group.getFormations().get(i).getTimestamp() == timeMs) {
                existingIndex = i;
                break;
            }
        }
        System.out.println("[createKeyframe] existingIndex: " + existingIndex);

        if (isDraggingSameFrame) {
            //
            System.out.println("[createKeyframe] 命中已有队形，跳过创建");
            notifyGroupChange();
        } else if (existingIndex >= 0 && existingIndex != draggedFormationIdx) {
            //
            System.out.println("[createKeyframe] 命中已有队形 @" + existingIndex);
            //
            if (originalPositions != null) {
                for (Dancer dancer : draggedFormation.getDancers()) {
                    float[] pos = originalPositions.get(dancer.getId());
                    if (pos != null) {
                        dancer.setX(pos[0]);
                        dancer.setY(pos[1]);
                    }
                }
            }

            //
            Formation existingFormation = group.getFormations().get(existingIndex);
            for (Dancer dancer : existingFormation.getDancers()) {
                float[] pos = newPositions.get(dancer.getId());
                if (pos != null) {
                    dancer.setX(pos[0]);
                    dancer.setY(pos[1]);
                }
            }
            currentFormationIndex.set(existingIndex);
            notifyGroupChange();
        } else {
            //
            System.out.println("[createKeyframe] case C: create new frame");
            //
            if (originalPositions != null) {
                for (Dancer dancer : draggedFormation.getDancers()) {
                    float[] pos = originalPositions.get(dancer.getId());
                    if (pos != null) {
                        dancer.setX(pos[0]);
                        dancer.setY(pos[1]);
                    }
                }
            }

            //
            long newTimeMs = timeMs <= 0L ? 500L : timeMs;
            System.out.println("[createKeyframe] 创建新 Formation, newTimeMs=" + newTimeMs);
            Formation newFormation = createIndependentFormation(newTimeMs, newPositions, draggedFormation.getDancers());
            System.out.println("[createKeyframe] Formation created");

            //
            int insertIndex = -1;
            for (int i = 0; i < group.getFormations().size(); i++) {
                if (group.getFormations().get(i).getTimestamp() > newTimeMs) {
                    insertIndex = i;
                    break;
                }
            }
            System.out.println("[createKeyframe] insertIndex: " + insertIndex);

            if (insertIndex >= 0) {
                group.getFormations().add(insertIndex, newFormation);
                currentFormationIndex.set(insertIndex);
            } else {
                // 添加到末尾
                group.getFormations().add(newFormation);
                insertIndex = group.getFormations().size() - 1;
                currentFormationIndex.set(insertIndex);
            }
            currentTime.set(newTimeMs);
            System.out.println("[createKeyframe] 时间线冲突检测完成，insertIndex=" + insertIndex);

            //
            System.out.println("[createKeyframe] 开始调整 adjustAdjacentFormations");
            adjustAdjacentFormations(group, insertIndex, newTimeMs);
            System.out.println("[createKeyframe] adjustAdjacentFormations done");

            System.out.println("[createKeyframe] 开始调整 notifyGroupChange");
            notifyGroupChange();
            System.out.println("[createKeyframe] notifyGroupChange done");
        }

        long endTime = System.currentTimeMillis();
        System.out.println("[createKeyframe] === 完成 === 耗时: " + (endTime - startTime) + "ms\n");
    }

    /**
     *
     * @param timestamp 新队形的时间戳位置
     *
     *
     */
    public Formation insertKeyframeSmart(long timestamp, Map<String, float[]> dancerPositions) {
        DanceGroup group = currentGroup.get();
        if (group == null) return null;

        List<Formation> formations = group.getFormations();

        //
        for (Formation f : formations) {
            if (f.getTimestamp() == timestamp) {
                System.out.println("[关键帧创建] 时间线冲突检测已完成，位置 @ " + timestamp + "ms");
                return f;
            }
        }

        //
        undoManager.saveSnapshot(group);

        // 获取模板（第一个队形作为参考）
        Formation template = formations.isEmpty() ? null : formations.get(0);

        //
        Formation newFormation = new Formation(
            java.util.UUID.randomUUID().toString(),
            "关键帧 " + formatTime(timestamp),
            timestamp
        );

        //
        if (dancerPositions != null) {
            //
            if (template != null) {
                for (Dancer templateDancer : template.getDancers()) {
                    Dancer newDancer = templateDancer.copy();
                    float[] pos = dancerPositions.get(templateDancer.getId());
                    if (pos != null) {
                        newDancer.setX(pos[0]);
                        newDancer.setY(pos[1]);
                    }
                    newFormation.getDancers().add(newDancer);
                }
            }
        } else {
            //
            if (template != null) {
                for (Dancer dancer : template.getDancers()) {
                    newFormation.getDancers().add(dancer.copy());
                }
            }
        }

        //
        newFormation.setHoldDuration(4000L);
        newFormation.setDuration(2000L);

        //
        int insertIndex = -1;
        for (int i = 0; i < formations.size(); i++) {
            if (formations.get(i).getTimestamp() > timestamp) {
                insertIndex = i;
                break;
            }
        }

        if (insertIndex >= 0) {
            formations.add(insertIndex, newFormation);
        } else {
            formations.add(newFormation);
            insertIndex = formations.size() - 1;
        }

        //
        adjustAdjacentFormations(group, insertIndex, timestamp);

        notifyGroupChange();

        return newFormation;
    }

    /**
     *
     */
    private Formation createIndependentFormation(long timestamp, Map<String, float[]> positions, List<Dancer> templateDancers) {
        String timeStr = formatTime(timestamp);
        Formation formation = new Formation(
            UUID.randomUUID().toString(),
            "关键帧 " + timeStr,
            timestamp
        );
        formation.setHoldDuration(4000L); // 默认绿色持续时长（秒）
        formation.setDuration(2000L);     // 默认红色过渡时长（秒）

        //
        for (Dancer templateDancer : templateDancers) {
            Dancer newDancer = templateDancer.copy();
            float[] pos = positions.get(templateDancer.getId());
            if (pos != null) {
                newDancer.setX(pos[0]);
                newDancer.setY(pos[1]);
            }
            formation.getDancers().add(newDancer);
        }

        return formation;
    }

    private static final long DEFAULT_RED = 2000L;   // 默认红色过渡时长（秒）
    private static final long DEFAULT_GREEN = 4000L; // 默认绿色持续时长（秒）
    private static final long MIN_DURATION = 1L;     // 最小时长 ms

    /**
     *
     *
     * 时间线模型说明：
     *
     *
     *
     *
     *
     * 处理逻辑：
     *
     *
     *
     *
     * @param group 舞蹈组对象
     *
     * @param newTimeMs 新队形的时间戳位置
     */
    private void adjustAdjacentFormations(DanceGroup group, int insertIndex, long newTimeMs) {
        List<Formation> formations = group.getFormations();
        Formation newFormation = formations.get(insertIndex);

        Formation prevFormation = insertIndex > 0 ? formations.get(insertIndex - 1) : null;
        Formation nextFormation = insertIndex < formations.size() - 1 ? formations.get(insertIndex + 1) : null;

        //
        RegionType regionType = determineRegionType(newTimeMs, prevFormation, nextFormation);

        switch (regionType) {
            case GREEN:
                handleGreenRegionInsert(newFormation, prevFormation, nextFormation, newTimeMs);
                break;
            case RED:
                handleRedRegionInsert(newFormation, prevFormation, nextFormation, newTimeMs);
                break;
            case BLANK:
            default:
                handleBlankRegionInsert(newFormation, prevFormation, nextFormation, newTimeMs);
                break;
        }
    }

    /**
     * 判断插入位置所属区域类型
     */
    private enum RegionType {
        BLANK,  // 空白区域
        GREEN,  // 绿色区域
        RED     // 红色区域
    }

    /**
     *
     */
    private RegionType determineRegionType(long T, Formation K_prev, Formation K_next) {
        //
        if (K_next != null && K_next.getDuration() > 0) {
            long redStart = K_next.getTimestamp() - K_next.getDuration();
            if (T >= redStart && T < K_next.getTimestamp()) {
                return RegionType.RED;
            }
        }

        //
        if (K_prev != null) {
            long greenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();
            if (T >= K_prev.getTimestamp() && T < greenEnd) {
                return RegionType.GREEN;
            }
        }

        // 既不在红色也不在绿色区域，属于空白区域
        return RegionType.BLANK;
    }

    /**
     * 处理插入到空白区域的情况
     * - K_new 的红色过渡时长(duration) = T - K_prev.greenEnd
     *
     * - K_next 的红色过渡时长(duration) = K_next.timestamp - K_new.greenEnd
     */
    private void handleBlankRegionInsert(Formation K_new, Formation K_prev, Formation K_next, long T) {
        //
        if (K_prev != null) {
            long prevGreenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();
            long redDuration = T - prevGreenEnd;
            K_new.setDuration(Math.max(0, redDuration));
            System.out.println("[调整相邻队形] K_new 持续时长=" + K_new.getDuration() + "ms");
        } else {
            K_new.setDuration(0L);  // 第一个队形无过渡时长
        }

        //
        K_new.setHoldDuration(DEFAULT_GREEN);

        //
        if (K_next != null) {
            long K_new_greenEnd = T + K_new.getHoldDuration();
            if (K_new_greenEnd > K_next.getTimestamp()) {
                //
                long actualGreen = K_next.getTimestamp() - T - DEFAULT_RED;  // 计算K_next的绿色区间时长
                if (actualGreen < MIN_DURATION) {
                    actualGreen = MIN_DURATION;
                }
                K_new.setHoldDuration(Math.max(MIN_DURATION, actualGreen));
                System.out.println("[Blank insert] K_new holdDuration=" + K_new.getHoldDuration() + "ms");
            }

            //
            long K_new_greenEnd_final = T + K_new.getHoldDuration();
            long K_next_redDuration = K_next.getTimestamp() - K_new_greenEnd_final;
            K_next.setDuration(Math.max(0, K_next_redDuration));
            System.out.println("[调整相邻队形] K_next 持续时长=" + K_next.getDuration() + "ms");
        }
    }

    /**
     * 处理插入到绿色区域的情况
     *
     *
     *
     */
    private void handleGreenRegionInsert(Formation K_new, Formation K_prev, Formation K_next, long T) {
        //
        long originalGreenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();

        //
        long desiredRedDuration = DEFAULT_RED;
        long maxRedDuration = T - K_prev.getTimestamp() - MIN_DURATION;  // 计算距离K_prev结束的最大红色时长(ms)
        long actualRedDuration = Math.min(desiredRedDuration, Math.max(0, maxRedDuration));
        K_new.setDuration(actualRedDuration);

        //
        long newPrevGreen = T - actualRedDuration - K_prev.getTimestamp();
        K_prev.setHoldDuration(Math.max(MIN_DURATION, newPrevGreen));
        System.out.println("[Green insert] K_prev holdDuration=" + K_prev.getHoldDuration() + "ms, K_new redDuration=" + K_new.getDuration() + "ms");

        //
        long inheritedGreen = originalGreenEnd - T;
        K_new.setHoldDuration(Math.max(MIN_DURATION, inheritedGreen));
        System.out.println("[Green insert] K_new holdDuration=" + K_new.getHoldDuration() + "ms");

        //
        if (K_next != null) {
            long K_new_greenEnd = T + K_new.getHoldDuration();
            long K_next_redDuration = K_next.getTimestamp() - K_new_greenEnd;
            K_next.setDuration(Math.max(0, K_next_redDuration));
        }
    }

    /**
     *
     *
     *
     *
     */
    private void handleRedRegionInsert(Formation K_new, Formation K_prev, Formation K_next, long T) {
        // K_new 的红色过渡时长 = T - K_prev.greenEnd
        long prevGreenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();
        long newRedDuration = T - prevGreenEnd;
        K_new.setDuration(Math.max(0, newRedDuration));
        System.out.println("[插入队形后重算时间线] K_new 持续时长=" + K_new.getDuration() + "ms");

        //
        K_new.setHoldDuration(MIN_DURATION);
        System.out.println("[插入队形后重算时间线] K_new 时长不足最小值 " + MIN_DURATION + "ms");

        // K_next 的红色过渡时长 = K_next.timestamp - K_new.greenEnd
        if (K_next != null) {
            long K_new_greenEnd = T + K_new.getHoldDuration();
            long K_next_redDuration = K_next.getTimestamp() - K_new_greenEnd;
            K_next.setDuration(Math.max(0, K_next_redDuration));
            System.out.println("[插入队形后重算时间线] K_next 持续时长=" + K_next.getDuration() + "ms");
        }
    }

    /**
     *
     *
     * 时间线模型说明：
     *
     *
     */
    @Deprecated
    public void regenerateTimelineAfterInsert(DanceGroup group, int insertIndex) {
        System.err.println("[Warning] regenerateTimelineAfterInsert 调用前校验失败，跳过 adjustAdjacentFormations");
        //
        if (insertIndex >= 0 && insertIndex < group.getFormations().size()) {
            Formation formation = group.getFormations().get(insertIndex);
            adjustAdjacentFormations(group, insertIndex, formation.getTimestamp());
        }
    }

    /**
     *
     *
     *
     */
    @Deprecated
    public void regenerateTimelineAfterInsert(DanceGroup group, int insertIndex, boolean recalculateTimestamps) {
        System.err.println("[Warning] regenerateTimelineAfterInsert 调用前校验失败，跳过 adjustAdjacentFormations");
        //
        if (insertIndex >= 0 && insertIndex < group.getFormations().size()) {
            Formation formation = group.getFormations().get(insertIndex);
            adjustAdjacentFormations(group, insertIndex, formation.getTimestamp());
        }

        //
        if (recalculateTimestamps) {
            System.err.println("[Warning] recalculateTimestamps 检测到时间戳冲突或数据异常，请检查 timestamp");
            recalculateTimestamps(group);
        }
    }

    // ========== 撤销操作 ==========

    /**
     *
     *
     */
    public boolean undo() {
        DanceGroup group = currentGroup.get();
        if (group == null) return false;

        DanceGroup restored = undoManager.undo(group);
        if (restored != null) {
            //
            currentGroup.set(null);
            currentGroup.set(restored);

            //
            if (currentFormationIndex.get() >= restored.getFormations().size()) {
                currentFormationIndex.set(restored.getFormations().size() - 1);
            }

            return true;
        }
        return false;
    }

    /**
     * 重做最近一次撤销
     */
    public boolean redo() {
        DanceGroup group = currentGroup.get();
        if (group == null) return false;

        DanceGroup restored = undoManager.redo(group);
        if (restored != null) {
            currentGroup.set(null);
            currentGroup.set(restored);

            if (restored.getFormations().isEmpty()) {
                currentFormationIndex.set(0);
            } else if (currentFormationIndex.get() >= restored.getFormations().size()) {
                currentFormationIndex.set(restored.getFormations().size() - 1);
            }

            return true;
        }
        return false;
    }

    /**
     * 检查是否有可撤销的操作
     */
    public boolean canUndo() {
        return undoManager.canUndo();
    }

    /**
     * 检查是否有可重做的操作
     */
    public boolean canRedo() {
        return undoManager.canRedo();
    }

    /**
     *
     */
    public void clearUndoHistory() {
        undoManager.clear();
    }

    /**
     *
     */
    public UndoManager getUndoManager() {
        return undoManager;
    }
}
