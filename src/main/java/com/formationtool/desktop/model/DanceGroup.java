package com.formationtool.desktop.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 舞团数据模型
 */
public class DanceGroup {
    private boolean referenceVideoMirrored;
    private String id;
    private String name;
    private int dancerCount;
    private List<Formation> formations;
    private float stageWidth;  // 舞台宽度(米)
    private float stageDepth;  // 舞台深度(米)
    private String musicPath;  // 音频文件路径
    private String referenceVideoUri;  // 参考视频URI（与Android端兼容）
    private String audioUri;  // 音频URI（与Android端兼容）
    private String referenceVideoPath;  // 参考视频内部存储路径
    private String audioPath;  // 音频内部存储路径
    private String thumbnailPath;  // 缩略图路径
    private long createdTime;
    private long modifiedTime;
    private boolean standardMode;  // 标准区间编排模式，旧工程默认保留拍打模式
    private boolean backstageVisible = true;  // 显示左右台下区域
    private long updateVersion;  // 版本号（与Android端兼容）

    public DanceGroup(String id, String name, float stageWidth, float stageDepth) {
        this.id = id;
        this.name = name;
        this.stageWidth = stageWidth;
        this.stageDepth = stageDepth;
        this.formations = new ArrayList<>();
        this.dancerCount = 0;
        this.createdTime = System.currentTimeMillis();
        this.modifiedTime = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getDancerCount() { return dancerCount; }
    public void setDancerCount(int dancerCount) { this.dancerCount = dancerCount; }

    public List<Formation> getFormations() { return formations; }
    public void setFormations(List<Formation> formations) { this.formations = formations; }

    public float getStageWidth() { return stageWidth; }
    public void setStageWidth(float stageWidth) { this.stageWidth = stageWidth; }

    public float getStageDepth() { return stageDepth; }
    public void setStageDepth(float stageDepth) { this.stageDepth = stageDepth; }

    public String getMusicPath() { return musicPath; }
    public void setMusicPath(String musicPath) { this.musicPath = musicPath; }

    public String getReferenceVideoUri() { return referenceVideoUri; }
    public void setReferenceVideoUri(String referenceVideoUri) { this.referenceVideoUri = referenceVideoUri; }

    public String getAudioUri() { return audioUri; }
    public void setAudioUri(String audioUri) { this.audioUri = audioUri; }

    public String getReferenceVideoPath() { return referenceVideoPath; }
    public void setReferenceVideoPath(String referenceVideoPath) { this.referenceVideoPath = referenceVideoPath; }

    public String getAudioPath() { return audioPath; }
    public void setAudioPath(String audioPath) { this.audioPath = audioPath; }

    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }

    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }

    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }

    public boolean isReferenceVideoMirrored() { return referenceVideoMirrored; }
    public void setReferenceVideoMirrored(boolean value) { referenceVideoMirrored = value; }

    public boolean isStandardMode() { return standardMode; }
    public void setStandardMode(boolean standardMode) { this.standardMode = standardMode; }
    public boolean isBackstageVisible() { return backstageVisible; }
    public void setBackstageVisible(boolean backstageVisible) { this.backstageVisible = backstageVisible; }
    public float getBackstageWidth() { return Math.max(2f, stageWidth * 0.2f); }

    public long getUpdateVersion() { return updateVersion; }
    public void setUpdateVersion(long updateVersion) { this.updateVersion = updateVersion; }
}
