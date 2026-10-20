package com.formationtool.desktop.controller;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.view.TimelineView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class MediaCleanupController {
    private MediaCleanupController() {
    }

    @FunctionalInterface
    public interface ErrorHandler {
        void show(String title, String message);
    }

    @FunctionalInterface
    public interface VideoLoader {
        void load(java.io.File videoFile, Runnable onComplete);
    }

    public static final class ClearMediaResult {
        private final boolean audioCleared;
        private final boolean videoCleared;
        private final String statusMessage;

        public ClearMediaResult(boolean audioCleared, boolean videoCleared, String statusMessage) {
            this.audioCleared = audioCleared;
            this.videoCleared = videoCleared;
            this.statusMessage = statusMessage;
        }

        public boolean isAudioCleared() {
            return audioCleared;
        }

        public boolean isVideoCleared() {
            return videoCleared;
        }

        public String getStatusMessage() {
            return statusMessage;
        }
    }

    public static ClearMediaResult clearMediaFiles(
        AudioPlaybackController playbackController,
        FormationViewModel viewModel,
        TimelineView timelineView,
        DataManager dataManager,
        MenuItem importAudioMenuItem,
        MenuItem importVideoMenuItem,
        Runnable onFormationListUpdated
    ) {
        boolean hasAudio = playbackController != null
            && playbackController.getAudioPlayer() != null
            && viewModel.getAudioDuration() > 0;
        boolean hasVideo = playbackController != null && playbackController.hasVideo();

        if (!hasAudio && !hasVideo) {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("提示");
            info.setHeaderText("没有媒体文件");
            info.setContentText("当前没有导入音频或视频文件。");
            info.showAndWait();
            return null;
        }

        StringBuilder message = new StringBuilder("确定要清除以下媒体文件吗？\n\n");
        if (hasAudio) {
            message.append("• 音频文件\n");
        }
        if (hasVideo) {
            message.append("• 参考视频\n");
        }
        message.append("\n清除后将无法播放和导出这些媒体文件。");

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认清除");
        confirm.setHeaderText("清除媒体文件");
        confirm.setContentText(message.toString());

        Optional<ButtonType> result = confirm.showAndWait();
        if (!result.isPresent() || result.get() != ButtonType.OK) {
            return null;
        }

        DanceGroup group = viewModel.getCurrentGroup();
        boolean shouldClearKeyframes = false;

        if (group != null && group.getFormations() != null && !group.getFormations().isEmpty()) {
            Alert keyframeConfirm = new Alert(Alert.AlertType.CONFIRMATION);
            keyframeConfirm.setTitle("清除关键帧？");
            keyframeConfirm.setHeaderText("是否同时清除所有关键帧数据？");
            keyframeConfirm.setContentText(
                "当前工程有 " + group.getFormations().size() + " 个关键帧。\n\n" +
                "选择[确定]：清除媒体文件并重置所有关键帧的时间戳\n" +
                "选择[取消]：只清除媒体文件，保留关键帧数据"
            );

            ButtonType clearBtn = new ButtonType("清除关键帧", ButtonBar.ButtonData.OK_DONE);
            ButtonType keepBtn = new ButtonType("保留关键帧", ButtonBar.ButtonData.CANCEL_CLOSE);
            keyframeConfirm.getButtonTypes().setAll(clearBtn, keepBtn);

            Optional<ButtonType> keyframeResult = keyframeConfirm.showAndWait();
            shouldClearKeyframes = keyframeResult.isPresent() && keyframeResult.get() == clearBtn;
        }

        String projectId = group != null ? group.getId() : null;
        boolean audioCleared = false;
        boolean videoCleared = false;

        if (hasAudio) {
            if (playbackController != null) {
                playbackController.disposeAudio();
            }
            audioCleared = true;

            if (group != null) {
                group.setMusicPath(null);
                group.setAudioPath(null);
                group.setAudioUri(null);
            }

            if (projectId != null) {
                dataManager.deleteAudioFromProject(projectId);
            }

            viewModel.setPlaying(false);
            viewModel.setAudioDuration(0);
            viewModel.setCurrentTime(0);

            if (timelineView != null) {
                timelineView.setWaveformData(new float[0]);
            }
        }

        if (hasVideo) {
            if (playbackController != null) {
                playbackController.disposeVideo();
            }
            videoCleared = true;

            if (group != null) {
                group.setReferenceVideoPath(null);
                group.setReferenceVideoUri(null);
            }

            if (projectId != null) {
                dataManager.deleteVideoFromProject(projectId);
            }
        }

        if (shouldClearKeyframes && group != null && group.getFormations() != null) {
            List<Dancer> templateDancers = new ArrayList<>();
            if (!group.getFormations().isEmpty()) {
                Formation firstFormation = group.getFormations().get(0);
                for (Dancer dancer : firstFormation.getDancers()) {
                    templateDancers.add(dancer.copy());
                }
            }

            group.getFormations().clear();

            Formation initialFormation = new Formation(
                UUID.randomUUID().toString(),
                "初始队形",
                0L
            );
            initialFormation.setHoldDuration(4000L);
            initialFormation.setDuration(2000L);

            for (Dancer dancer : templateDancers) {
                initialFormation.getDancers().add(dancer);
            }
            group.getFormations().add(initialFormation);

            if (onFormationListUpdated != null) {
                onFormationListUpdated.run();
            }
        }

        if (group != null) {
            dataManager.saveProject(group);
        }

        String statusMessage = buildStatusMessage(hasAudio, hasVideo, shouldClearKeyframes);

        Platform.runLater(() -> {
            if (importAudioMenuItem != null) {
                importAudioMenuItem.setDisable(false);
            }
            if (importVideoMenuItem != null) {
                importVideoMenuItem.setDisable(false);
            }
        });

        return new ClearMediaResult(audioCleared, videoCleared, statusMessage);
    }

    public static String deleteAudio(
        AudioPlaybackController playbackController,
        FormationViewModel viewModel
    ) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除音频文件");
        confirm.setContentText("确定要删除当前的音频文件吗？\n\n删除后将无法播放和导出音频。");

        Optional<ButtonType> result = confirm.showAndWait();
        if (!result.isPresent() || result.get() != ButtonType.OK) {
            return null;
        }

        if (playbackController != null) {
            playbackController.disposeAudio();
        }

        DanceGroup group = viewModel.getCurrentGroup();
        if (group != null) {
            group.setMusicPath(null);
            group.setAudioPath(null);
            group.setAudioUri(null);
        }

        viewModel.setPlaying(false);
        viewModel.setAudioDuration(0);
        viewModel.setCurrentTime(0);
        return "已删除音频文件";
    }

    public static String deleteVideo(
        AudioPlaybackController playbackController,
        FormationViewModel viewModel
    ) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除参考视频");
        confirm.setContentText("确定要删除当前的参考视频吗？\n\n删除后将无法查看参考视频。");

        Optional<ButtonType> result = confirm.showAndWait();
        if (!result.isPresent() || result.get() != ButtonType.OK) {
            return null;
        }

        if (playbackController != null) {
            playbackController.disposeVideo();
        }

        DanceGroup group = viewModel.getCurrentGroup();
        if (group != null) {
            group.setReferenceVideoPath(null);
            group.setReferenceVideoUri(null);
        }
        return "已删除参考视频";
    }

    public static void reloadCurrentVideo(
        String selectedProjectId,
        DataManager dataManager,
        java.io.File currentVideoFile,
        ErrorHandler errorHandler,
        Consumer<String> statusUpdater,
        VideoLoader videoLoader,
        Runnable onReloaded
    ) {
        java.io.File videoFile = null;
        if (selectedProjectId != null) {
            videoFile = dataManager.getProjectVideoFile(selectedProjectId);
        }
        if (videoFile == null || !videoFile.exists()) {
            videoFile = currentVideoFile;
        }

        if (videoFile == null || !videoFile.exists()) {
            if (errorHandler != null) {
                errorHandler.show("重新加载失败", "没有可用的视频文件\n\n请先导入视频文件。");
            }
            return;
        }

        if (statusUpdater != null) {
            statusUpdater.accept("正在重新加载视频...");
        }

        java.io.File finalVideoFile = videoFile;
        videoLoader.load(finalVideoFile, () -> {
            if (statusUpdater != null) {
                statusUpdater.accept("视频已重新加载: " + finalVideoFile.getName());
            }
            if (onReloaded != null) {
                onReloaded.run();
            }
        });
    }

    private static String buildStatusMessage(boolean hasAudio, boolean hasVideo, boolean clearedKeyframes) {
        StringBuilder statusMsg = new StringBuilder("已清除");
        if (hasAudio && hasVideo) {
            statusMsg.append("音频和视频文件");
        } else if (hasAudio) {
            statusMsg.append("音频文件");
        } else {
            statusMsg.append("视频文件");
        }

        if (clearedKeyframes) {
            statusMsg.append("（已重置关键帧）");
        } else {
            statusMsg.append("（保留关键帧）");
        }
        return statusMsg.toString();
    }
}
