package com.formationtool.desktop.controller;

import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MediaRestoreController {
    private final DataManager dataManager;
    private final Supplier<String> projectIdSupplier;
    private final Consumer<File> audioLoader;
    private final BiConsumer<File, Runnable> videoLoader;
    private final Runnable mediaMenuUpdater;

    public MediaRestoreController(
        DataManager dataManager,
        Supplier<String> projectIdSupplier,
        Consumer<File> audioLoader,
        BiConsumer<File, Runnable> videoLoader,
        Runnable mediaMenuUpdater
    ) {
        this.dataManager = dataManager;
        this.projectIdSupplier = projectIdSupplier;
        this.audioLoader = audioLoader;
        this.videoLoader = videoLoader;
        this.mediaMenuUpdater = mediaMenuUpdater;
    }

    public void restoreMediaFiles(DanceGroup group) {
        String projectId = projectIdSupplier.get();
        if (group == null || projectId == null) {
            System.out.println("[工程加载] 跳过媒体恢复：group 或 projectId 为空");
            return;
        }

        System.out.println("[工程加载] 开始恢复媒体文件，工程ID: " + projectId);

        File audioFile = dataManager.getProjectAudioFile(projectId);
        File videoFile = dataManager.getProjectVideoFile(projectId);

        System.out.println("[工程加载] 音频文件: " + (audioFile != null ? audioFile.getAbsolutePath() : "null") +
            ", 存在: " + (audioFile != null && audioFile.exists()));
        System.out.println("[工程加载] 视频文件: " + (videoFile != null ? videoFile.getAbsolutePath() : "null") +
            ", 存在: " + (videoFile != null && videoFile.exists()));

        if (audioFile != null && audioFile.exists()) {
            try {
                System.out.println("[工程加载] 恢复音频: " + audioFile.getAbsolutePath());
                audioLoader.accept(audioFile);
                System.out.println("[工程加载] 音频恢复成功");
            } catch (Exception e) {
                System.err.println("[工程加载] 恢复音频失败: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("[工程加载] 无音频文件需要恢复");
        }

        if (videoFile != null && videoFile.exists()) {
            try {
                System.out.println("[工程加载] 恢复视频: " + videoFile.getAbsolutePath());
                videoLoader.accept(videoFile, () -> {
                    System.out.println("[工程加载] 视频恢复完成，播放器已初始化");
                    mediaMenuUpdater.run();
                });
            } catch (Exception e) {
                System.err.println("[工程加载] 恢复视频失败: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("[工程加载] 无视频文件需要恢复");
        }
    }
}
