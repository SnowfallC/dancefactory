package com.formationtool.desktop.controller;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.view.TimelineView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 关键帧控制器
 * 负责处理关键帧创建和F键拍打功能
 *
 * F键边播边扫机制（前红后绿结构）：
 * 状态机：按F → 绿色区 → 松F → 红色区 → 按F → 绿色区 → ... → 暂停/到头 → 结束
 */
public class KeyframeController {

    private final FormationViewModel viewModel;
    private final AudioPlaybackController playbackController;
    private final TimelineView timelineView;
    private final Label statusLabel;
    private final Runnable updateFormationListCallback;

    // ========== F键拍打状态 ==========
    private boolean isFKeyPressed = false;           // F键是否按下
    private boolean isInGreenMode = false;           // 是否在绿色区模式
    private boolean isFirstFKey = true;              // 是否是序列中第一次按F
    private long currentRegionStartTime = -1;        // 当前区域开始时间
    private long scanStartTime = -1;                 // 扫描开始时间（用于删除旧关键帧）
    private Formation currentKeyframe = null;        // 当前正在编辑的关键帧
    private List<String> protectedKeyframeIds = new ArrayList<>();  // 保护的关键帧ID（不删除）

    // 默认区域长度常量
    private static final long DEFAULT_RED = 2000L;    // 默认红色区2秒
    private static final long DEFAULT_GREEN = 4000L;  // 默认绿色区4秒
    private static final long MIN_DURATION = 1L;      // 最小区域长度1ms

    public KeyframeController(
            FormationViewModel viewModel,
            AudioPlaybackController playbackController,
            TimelineView timelineView,
            Label statusLabel,
            Runnable updateFormationListCallback) {

        this.viewModel = viewModel;
        this.playbackController = playbackController;
        this.timelineView = timelineView;
        this.statusLabel = statusLabel;
        this.updateFormationListCallback = updateFormationListCallback;

        // 设置时间更新监听，用于F键自动停止
        setupAutoStopListener();
    }

    /**
     * 设置F键自动停止监听
     */
    private void setupAutoStopListener() {
        // 监听播放时间变化，实时删除扫描范围内的旧关键帧
        viewModel.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
            if (scanStartTime >= 0 && newVal.longValue() > scanStartTime) {
                // 扫描过程中，删除扫描范围内的旧关键帧
                deleteOldKeyframesInScanRange(newVal.longValue());
            }
        });
    }

    /**
     * 删除扫描范围内的旧关键帧
     */
    private void deleteOldKeyframesInScanRange(long currentScanTime) {
        DanceGroup group = viewModel.getCurrentGroup();
        if (group == null) return;

        List<Formation> formations = group.getFormations();
        List<Formation> toDelete = new ArrayList<>();

        for (Formation f : formations) {
            // 跳过第一帧和受保护的关键帧
            if (f.getTimestamp() == 0) continue;
            if (protectedKeyframeIds.contains(f.getId())) continue;

            // 如果关键帧在扫描范围内，标记删除
            if (f.getTimestamp() > scanStartTime && f.getTimestamp() <= currentScanTime) {
                toDelete.add(f);
            }
        }

        // 执行删除
        for (Formation f : toDelete) {
            System.out.println("[F键扫描] 删除旧关键帧 @ " + f.getTimestamp() + "ms");
            formations.remove(f);
        }

        if (!toDelete.isEmpty()) {
            Platform.runLater(updateFormationListCallback);
        }
    }

    /**
     * 处理F键按下
     * - 第一次按F：智能插入创建关键帧，开始绿色区
     * - 再次按F：保存红色区长度，创建新关键帧，开始绿色区
     */
    public void handleFKeyPressed() {
        if (isFKeyPressed) return;

        DanceGroup group = viewModel.getCurrentGroup();
        if (group == null || group.getFormations().isEmpty()) return;

        isFKeyPressed = true;
        long currentTime = viewModel.getCurrentTime();

        if (isFirstFKey) {
            // ========== 第一次按F：启动扫描序列 ==========
            isFirstFKey = false;
            scanStartTime = currentTime;
            protectedKeyframeIds.clear();

            // 保存撤销快照
            viewModel.getUndoManager().saveSnapshot(group);

            // 使用智能插入创建第一个关键帧
            currentKeyframe = viewModel.insertKeyframeSmart(currentTime, null);
            if (currentKeyframe != null) {
                currentKeyframe.setHoldDuration(0L);  // 初始化绿色区为0，等松开F时再设置
                protectedKeyframeIds.add(currentKeyframe.getId());
                System.out.println("[F键拍打] 启动，创建第一个关键帧 @ " + currentTime + "ms（绿色区待设置）");
            }

            // 如果没有播放，开始播放
            if (!viewModel.isPlaying()) {
                viewModel.setPlaying(true);
            }

        } else {
            // ========== 再次按F：红色区 → 绿色区 ==========
            // 计算红色区长度
            long redDuration = currentTime - currentRegionStartTime;

            // 创建新关键帧，并设置其前红色区
            Formation newKeyframe = viewModel.insertKeyframeSmart(currentTime, null);
            if (newKeyframe != null) {
                newKeyframe.setDuration(redDuration);
                newKeyframe.setHoldDuration(0L);  // 中间帧不设默认绿色区，等松开F时再设置
                protectedKeyframeIds.add(newKeyframe.getId());
                currentKeyframe = newKeyframe;
                System.out.println("[F键拍打] 再次按F，红色区长度=" + redDuration + "ms，创建新关键帧 @ " + currentTime + "ms（绿色区待设置）");
            }
        }

        // 进入绿色区模式
        isInGreenMode = true;
        currentRegionStartTime = currentTime;

        timelineView.onFKeyPressed(currentTime);
        statusLabel.setText("F键按下 - 绿色区 @ " + formatDuration(currentTime));
    }

    /**
     * 处理F键松开
     * - 保存绿色区长度到当前关键帧
     * - 切换到红色区模式
     */
    public void handleFKeyReleased() {
        if (!isFKeyPressed) return;

        isFKeyPressed = false;
        long currentTime = viewModel.getCurrentTime();
        DanceGroup group = viewModel.getCurrentGroup();

        if (group == null || currentKeyframe == null) return;

        // 计算绿色区长度
        long greenDuration = currentTime - currentRegionStartTime;

        if (greenDuration > 0) {
            // 保存绿色区长度到当前关键帧
            currentKeyframe.setHoldDuration(greenDuration);
            System.out.println("[F键拍打] 松开F，绿色区长度=" + greenDuration + "ms 保存到 [" + currentKeyframe.getName() + "]");

            // 重新计算下一帧的红色区
            recalculateRedRegion(group, currentKeyframe);
        }

        // 切换到红色区模式
        isInGreenMode = false;
        currentRegionStartTime = currentTime;

        timelineView.onFKeyReleased(currentTime);
        statusLabel.setText("F键松开 - 红色区 @ " + formatDuration(currentTime));
        updateFormationListCallback.run();
    }

    /**
     * 暂停时处理F键标记（结束扫描序列）
     */
    public void handleFKeyMarkOnPause() {
        DanceGroup group = viewModel.getCurrentGroup();
        if (group == null) return;

        // 如果不在扫描序列中，直接返回
        if (isFirstFKey && !isFKeyPressed) {
            return;
        }

        long pauseTime = viewModel.getCurrentTime();
        System.out.println("[F键拍打] 暂停/结束 @ " + pauseTime + "ms, 当前模式: " + (isInGreenMode ? "绿色区" : "红色区"));

        if (isFKeyPressed && currentKeyframe != null) {
            // ========== 正在按着F（绿色区结束） ==========
            long greenDuration = pauseTime - currentRegionStartTime;
            if (greenDuration > 0) {
                currentKeyframe.setHoldDuration(greenDuration);
                System.out.println("[F键拍打] 结束时保存绿色区长度=" + greenDuration + "ms");
                recalculateRedRegion(group, currentKeyframe);
            }

            // 使用完整智能插入逻辑创建结尾关键帧
            createEndingKeyframe(group, pauseTime);

        } else if (!isInGreenMode && currentRegionStartTime >= 0) {
            // ========== 红色区结束 ==========
            long redDuration = pauseTime - currentRegionStartTime;

            // 创建结尾关键帧，并设置其前红色区
            Formation endKeyframe = createEndingKeyframe(group, pauseTime);
            if (endKeyframe != null && redDuration > 0) {
                endKeyframe.setDuration(redDuration);
                System.out.println("[F键拍打] 结束时保存红色区长度=" + redDuration + "ms");
            }
        }

        // 清除状态
        clearFKeyMarkState();
        timelineView.clearFKeyMark();
        updateFormationListCallback.run();
        System.out.println("[F键拍打] 序列结束");
    }

    /**
     * 创建结尾关键帧
     */
    private Formation createEndingKeyframe(DanceGroup group, long timestamp) {
        // 检查是否已存在
        boolean exists = group.getFormations().stream()
            .anyMatch(f -> f.getTimestamp() == timestamp);

        if (exists) {
            System.out.println("[F键拍打] 结尾位置已有关键帧 @ " + timestamp + "ms");
            return findKeyframeAtTime(group, timestamp);
        }

        // 使用智能插入创建结尾关键帧
        Formation endKeyframe = viewModel.insertKeyframeSmart(timestamp, null);
        if (endKeyframe != null) {
            protectedKeyframeIds.add(endKeyframe.getId());
            System.out.println("[F键拍打] 创建结尾关键帧 @ " + timestamp + "ms");
        }
        return endKeyframe;
    }

    /**
     * 清除F键标记状态
     */
    public void clearFKeyMarkState() {
        isFKeyPressed = false;
        isInGreenMode = false;
        isFirstFKey = true;
        currentRegionStartTime = -1;
        scanStartTime = -1;
        currentKeyframe = null;
        protectedKeyframeIds.clear();
    }

    /**
     * 判断时间点是否在绿色区域
     * 新逻辑（前红后绿）：
     * - 红色区: [timestamp - duration, timestamp)
     * - 绿色区: [timestamp, timestamp + holdDuration)
     * @param group 舞蹈组
     * @param timeMs 时间点（毫秒）
     * @return [0]=是否在绿色区(1=绿色,0=红色), [1]=所属关键帧索引, [2]=下一个关键帧索引, [3]=是否在空白区(1=空白,0=非空白)
     */
    private int[] isInGreenRegion(DanceGroup group, long timeMs) {
        List<Formation> formations = group.getFormations();
        if (formations.isEmpty()) {
            return new int[]{0, -1, -1, 1};
        }

        for (int i = 0; i < formations.size(); i++) {
            Formation keyframe = formations.get(i);
            long timestamp = keyframe.getTimestamp();
            long redStart = timestamp - keyframe.getDuration();  // 红色区开始
            long greenEnd = timestamp + keyframe.getHoldDuration();  // 绿色区结束

            // 判断是否在红色区（前面）- 第一帧无红色区
            if (i > 0 && keyframe.getDuration() > 0 && timeMs >= redStart && timeMs < timestamp) {
                int prevIndex = i - 1;
                return new int[]{0, i, i, 0};  // 在红色区，属于当前帧
            }

            // 判断是否在绿色区（后面）
            if (timeMs >= timestamp && timeMs < greenEnd) {
                int nextIndex = (i < formations.size() - 1) ? i + 1 : -1;
                return new int[]{1, i, nextIndex, 0};  // 在绿色区
            }
        }

        // 在最后一帧绿色区之后，属于空白区
        return new int[]{0, formations.size() - 1, -1, 1};
    }

    /**
     * 查找指定时间点所属的关键帧
     * 新逻辑（前红后绿）：
     * - 红色区属于当前帧: [timestamp - duration, timestamp)
     * - 绿色区属于当前帧: [timestamp, timestamp + holdDuration)
     */
    private Formation findKeyframeAtTime(DanceGroup group, long timeMs) {
        List<Formation> formations = group.getFormations();
        if (formations.isEmpty()) return null;

        for (int i = 0; i < formations.size(); i++) {
            Formation keyframe = formations.get(i);
            long timestamp = keyframe.getTimestamp();
            long redStart = timestamp - keyframe.getDuration();
            long greenEnd = timestamp + keyframe.getHoldDuration();

            // 如果时间落在红色区内（前面）- 第一帧无红色区
            if (i > 0 && keyframe.getDuration() > 0 && timeMs >= redStart && timeMs < timestamp) {
                return keyframe;  // 红色区属于当前帧
            }

            // 如果时间落在绿色区内
            if (timeMs >= timestamp && timeMs < greenEnd) {
                return keyframe;
            }
        }

        // 在最后一帧之后，返回最后一帧
        return formations.get(formations.size() - 1);
    }

    /**
     * 重新计算下一帧的红色区长度
     * 新逻辑：红色区属于下一帧的前面（K_next.duration = K_next.timestamp - K_current.greenEnd）
     */
    private void recalculateRedRegion(DanceGroup group, Formation keyframe) {
        List<Formation> formations = group.getFormations();
        int index = formations.indexOf(keyframe);

        if (index < 0) return;

        // 计算当前帧绿色区结束时间
        long greenEnd = keyframe.getTimestamp() + keyframe.getHoldDuration();

        // 设置下一帧的红色区长度
        if (index < formations.size() - 1) {
            Formation nextKeyframe = formations.get(index + 1);
            long nextRedDuration = nextKeyframe.getTimestamp() - greenEnd;
            nextKeyframe.setDuration(Math.max(0, nextRedDuration));
            System.out.println("[重计算红区] 下一帧红色区长度: " + nextKeyframe.getDuration() + "ms");
        }
    }

    /**
     * 智能插入关键帧
     * 基于时间轴碰撞检测，智能计算新关键帧的绿色区和红色区
     *
     * 使用示例：
     * <pre>
     * // 在当前播放时间插入关键帧
     * long currentTime = viewModel.getCurrentTime();
     * Formation newKeyframe = keyframeController.insertKeyframeSmart(currentTime);
     *
     * // 在指定时间点插入关键帧
     * Formation keyframe = keyframeController.insertKeyframeSmart(5000L); // 5秒处
     * </pre>
     *
     * 逻辑规则：
     * <ul>
     *   <li>场景A - 空白区：往前生成DEFAULT_RED的红色区，往后生成DEFAULT_GREEN的绿色区</li>
     *   <li>场景B - 绿色区：截断K_prev的绿色区，K_new继承剩余绿色区</li>
     *   <li>场景C - 红色区：K_new继承K_prev的红色区，尝试生成DEFAULT_GREEN的绿色区</li>
     * </ul>
     *
     * 挤压规则：
     * <ul>
     *   <li>如果绿色区撞到下一个关键帧，绿色区被挤压到至少MIN_DURATION (1ms)</li>
     *   <li>如果红色区空间不足，红色区会被压缩但保证连续性</li>
     * </ul>
     *
     * @param T 插入时间点（毫秒）
     * @return 插入的关键帧，如果该时间点已存在关键帧则返回现有关键帧，如果插入失败则返回null
     */
    public Formation insertKeyframeSmart(long T) {
        // 委托给 FormationViewModel 的统一逻辑
        Formation result = viewModel.insertKeyframeSmart(T, null);

        // 刷新UI
        if (result != null) {
            updateFormationListCallback.run();
        }

        return result;
    }

    /**
     * 区域类型枚举
     */
    private enum RegionType {
        BLANK,  // 空白区
        GREEN,  // 绿色区
        RED     // 红色区
    }

    /**
     * 判断时间点T所在的区域类型
     * 新逻辑（前红后绿）：
     * - 如果T < K_next的redStart，属于K_prev的绿色区或空白区
     * - 如果T >= K_next.redStart && T < K_next.timestamp，属于K_next的红色区
     * - 如果T >= K.timestamp && T < K.greenEnd，属于K的绿色区
     */
    private RegionType determineRegionType(long T, Formation K_prev, Formation K_next) {
        if (K_prev == null && K_next == null) {
            return RegionType.BLANK;
        }

        // 检查是否在K_next的红色区内
        if (K_next != null && K_next.getDuration() > 0) {
            long redStart = K_next.getTimestamp() - K_next.getDuration();
            if (T >= redStart && T < K_next.getTimestamp()) {
                return RegionType.RED;
            }
        }

        // 检查是否在K_prev的绿色区内
        if (K_prev != null) {
            long greenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();
            if (T >= K_prev.getTimestamp() && T < greenEnd) {
                return RegionType.GREEN;
            }
        }

        // 否则在空白区
        return RegionType.BLANK;
    }

    /**
     * 创建新关键帧
     */
    private Formation createNewKeyframe(long timestamp, Formation template) {
        Formation newFormation = new Formation(
            UUID.randomUUID().toString(),
            "关键帧 " + formatDuration(timestamp),
            timestamp
        );

        // 复制舞者位置
        if (template != null && !template.getDancers().isEmpty()) {
            for (Dancer dancer : template.getDancers()) {
                newFormation.getDancers().add(dancer.copy());
            }
        }

        return newFormation;
    }

    /**
     * 场景A：在空白区插入
     * 新逻辑（前红后绿）：
     * - K_new的红色区(duration) = T - K_prev.greenEnd
     * - K_new的绿色区(holdDuration) = 默认DEFAULT_GREEN，但不能超过K_next.redStart
     * - K_next的红色区(duration) = K_next.timestamp - K_new.greenEnd
     */
    private void handleBlankRegionInsert(Formation K_new, Formation K_prev, Formation K_next, long T) {
        // 设置K_new的前红色区
        if (K_prev != null) {
            long prevGreenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();
            long redDuration = T - prevGreenEnd;
            K_new.setDuration(Math.max(0, redDuration));
            System.out.println("[空白区插入] K_new前红色区设为 " + K_new.getDuration() + "ms");
        } else {
            K_new.setDuration(0L);  // 第一帧无前红色区
        }

        // 设置K_new的后绿色区
        K_new.setHoldDuration(DEFAULT_GREEN);

        // 检查绿色区是否与K_next重叠
        if (K_next != null) {
            long K_new_greenEnd = T + K_new.getHoldDuration();
            if (K_new_greenEnd > K_next.getTimestamp()) {
                // 绿色区超过了K_next，需要截短
                long actualGreen = K_next.getTimestamp() - T - DEFAULT_RED;  // 给K_next留出红色区空间
                if (actualGreen < MIN_DURATION) {
                    actualGreen = MIN_DURATION;
                }
                K_new.setHoldDuration(Math.max(MIN_DURATION, actualGreen));
                System.out.println("[空白区插入] K_new绿色区截短到 " + K_new.getHoldDuration() + "ms");
            }

            // 设置K_next的前红色区
            long K_new_greenEnd_final = T + K_new.getHoldDuration();
            long K_next_redDuration = K_next.getTimestamp() - K_new_greenEnd_final;
            K_next.setDuration(Math.max(0, K_next_redDuration));
            System.out.println("[空白区插入] K_next前红色区设为 " + K_next.getDuration() + "ms");
        }
    }

    /**
     * 场景B：在绿色区插入
     * 新逻辑（前红后绿）：
     * - 截断K_prev的绿色区在T点
     * - K_new的红色区(duration) = 默认DEFAULT_RED，但不能超过T - K_prev.timestamp
     * - K_new的绿色区(holdDuration) = K_prev被截断的剩余绿色区
     */
    private void handleGreenRegionInsert(Formation K_new, Formation K_prev, Formation K_next, long T) {
        // 记录K_prev原始绿色区结束时间
        long originalGreenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();

        // 计算K_new的前红色区
        long desiredRedDuration = DEFAULT_RED;
        long maxRedDuration = T - K_prev.getTimestamp() - MIN_DURATION;  // 保留K_prev至少1ms绿色区
        long actualRedDuration = Math.min(desiredRedDuration, Math.max(0, maxRedDuration));
        K_new.setDuration(actualRedDuration);

        // 截断K_prev的绿色区
        long newPrevGreen = T - actualRedDuration - K_prev.getTimestamp();
        K_prev.setHoldDuration(Math.max(MIN_DURATION, newPrevGreen));
        System.out.println("[绿色区插入] K_prev绿色区截断到 " + K_prev.getHoldDuration() + "ms，K_new前红色区=" + K_new.getDuration() + "ms");

        // K_new继承K_prev被截断的剩余绿色区
        long inheritedGreen = originalGreenEnd - T;
        K_new.setHoldDuration(Math.max(MIN_DURATION, inheritedGreen));
        System.out.println("[绿色区插入] K_new继承绿色区 " + K_new.getHoldDuration() + "ms");

        // 设置K_next的前红色区
        if (K_next != null) {
            long K_new_greenEnd = T + K_new.getHoldDuration();
            long K_next_redDuration = K_next.getTimestamp() - K_new_greenEnd;
            K_next.setDuration(Math.max(0, K_next_redDuration));
        }
    }

    /**
     * 场景C：在红色区插入
     * 新逻辑（前红后绿）：
     * - K_new的红色区(duration) = T - K_prev.greenEnd
     * - K_new的绿色区(holdDuration) = 最小1ms
     * - K_next的红色区(duration) = K_next.timestamp - K_new.greenEnd
     */
    private void handleRedRegionInsert(Formation K_new, Formation K_prev, Formation K_next, long T) {
        // K_new的前红色区 = T - K_prev.greenEnd
        long prevGreenEnd = K_prev.getTimestamp() + K_prev.getHoldDuration();
        long newRedDuration = T - prevGreenEnd;
        K_new.setDuration(Math.max(0, newRedDuration));
        System.out.println("[红色区插入] K_new前红色区为 " + K_new.getDuration() + "ms");

        // K_new的后绿色区 = 最小1ms
        K_new.setHoldDuration(MIN_DURATION);
        System.out.println("[红色区插入] K_new绿色区为 " + MIN_DURATION + "ms");

        // K_next的前红色区 = K_next.timestamp - K_new.greenEnd
        if (K_next != null) {
            long K_new_greenEnd = T + K_new.getHoldDuration();
            long K_next_redDuration = K_next.getTimestamp() - K_new_greenEnd;
            K_next.setDuration(Math.max(0, K_next_redDuration));
            System.out.println("[红色区插入] K_next前红色区为 " + K_next.getDuration() + "ms");
        }
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        long ms = millis % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, ms);
    }

    // Getters
    public boolean isFKeyPressed() {
        return isFKeyPressed;
    }

    /**
     * 判断是否在F键扫描序列中（无论是否按下F）
     */
    public boolean isInScanSequence() {
        return !isFirstFKey || isFKeyPressed;
    }
}
