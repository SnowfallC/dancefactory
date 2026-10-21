package com.formationtool.desktop.model;

/**
 * 导出配置类
 * 支持单屏/双屏布局，2D/3D/参考视频组合，视角缩放
 */
public class ExportConfig {

    /**
     * 布局模式
     */
    public enum LayoutMode {
        SINGLE("单屏"),
        SPLIT_HORIZONTAL("左右分屏"),
        SPLIT_VERTICAL("上下分屏");

        public final String displayName;

        LayoutMode(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * 视角类型
     */
    public enum ViewType {
        VIEW_2D("2D视角"),
        VIEW_3D("3D视角"),
        REFERENCE_VIDEO("参考视频");

        public final String displayName;

        ViewType(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * 舞者标识类型
     */
    public enum DancerLabel {
        NUMBER("序号"),
        INITIAL("名字首字母");

        public final String displayName;

        DancerLabel(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * 舞台方向（观众/后台位置）
     */
    public enum StageOrientation {
        NORMAL("正常方向"),      // 观众在下方，后台在上方
        FLIPPED("翻转方向");     // 观众在上方，后台在下方

        public final String displayName;

        StageOrientation(String displayName) {
            this.displayName = displayName;
        }
    }

    // 配置字段
    private LayoutMode layoutMode = LayoutMode.SINGLE;
    private ViewType primaryView = ViewType.VIEW_2D;      // 单屏模式或双屏主视图
    private ViewType secondaryView = ViewType.VIEW_3D;    // 双屏次视图
    private boolean hasReferenceVideo = false;
    private DancerLabel dancerLabel = DancerLabel.NUMBER;
    private double viewScale = 1.0;  // 视角缩放比例 (0.5 ~ 2.0)
    private StageOrientation stageOrientation = StageOrientation.NORMAL;  // 舞台方向

    private boolean mirrored;
    private boolean referenceVideoMirrored;
    private double dancerScale = 1.0;
    public boolean isMirrored() { return mirrored; }
    public void setMirrored(boolean value) { mirrored = value; }
    public boolean isReferenceVideoMirrored() { return referenceVideoMirrored; }
    public void setReferenceVideoMirrored(boolean value) { referenceVideoMirrored = value; }
    public double getDancerScale() { return dancerScale; }
    public void setDancerScale(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("角色大小无效");
        dancerScale = Math.max(0.5, Math.min(2.0, value));
    }

    // 分辨率配置
    private int outputWidth = 1920;
    private int outputHeight = 1080;

    // Getters and Setters
    public LayoutMode getLayoutMode() { return layoutMode; }
    public void setLayoutMode(LayoutMode layoutMode) { this.layoutMode = layoutMode; }

    public ViewType getPrimaryView() { return primaryView; }
    public void setPrimaryView(ViewType view) { this.primaryView = view; }

    public ViewType getSecondaryView() { return secondaryView; }
    public void setSecondaryView(ViewType view) { this.secondaryView = view; }

    public boolean hasReferenceVideo() { return hasReferenceVideo; }
    public void setHasReferenceVideo(boolean hasReferenceVideo) { this.hasReferenceVideo = hasReferenceVideo; }

    public DancerLabel getDancerLabel() { return dancerLabel; }
    public void setDancerLabel(DancerLabel dancerLabel) { this.dancerLabel = dancerLabel; }

    public double getViewScale() { return viewScale; }
    public void setViewScale(double viewScale) {
        this.viewScale = Math.max(0.5, Math.min(2.0, viewScale));
    }

    public int getOutputWidth() { return outputWidth; }
    public void setOutputWidth(int width) { this.outputWidth = width; }

    public int getOutputHeight() { return outputHeight; }
    public void setOutputHeight(int height) { this.outputHeight = height; }

    public StageOrientation getStageOrientation() { return stageOrientation; }
    public void setStageOrientation(StageOrientation orientation) { this.stageOrientation = orientation; }

    /**
     * 是否翻转方向（观众在上方）
     */
    public boolean isFlipped() {
        return stageOrientation == StageOrientation.FLIPPED;
    }

    /**
     * 是否为单屏模式
     */
    public boolean isSingleMode() {
        return layoutMode == LayoutMode.SINGLE;
    }

    /**
     * 是否为双屏模式
     */
    public boolean isSplitMode() {
        return layoutMode == LayoutMode.SPLIT_HORIZONTAL || layoutMode == LayoutMode.SPLIT_VERTICAL;
    }

    /**
     * 是否为水平分屏（左右）
     */
    public boolean isHorizontalSplit() {
        return layoutMode == LayoutMode.SPLIT_HORIZONTAL;
    }

    /**
     * 是否为垂直分屏（上下）
     */
    public boolean isVerticalSplit() {
        return layoutMode == LayoutMode.SPLIT_VERTICAL;
    }

    /**
     * 获取单个视图的宽度
     */
    public int getViewWidth() {
        if (layoutMode == LayoutMode.SPLIT_HORIZONTAL) {
            return outputWidth / 2;
        }
        return outputWidth;
    }

    /**
     * 获取单个视图的高度
     */
    public int getViewHeight() {
        if (layoutMode == LayoutMode.SPLIT_VERTICAL) {
            return outputHeight / 2;
        }
        return outputHeight;
    }

    /**
     * 检查配置是否需要参考视频
     */
    public boolean needsReferenceVideo() {
        if (primaryView == ViewType.REFERENCE_VIDEO) return true;
        if (isSplitMode() && secondaryView == ViewType.REFERENCE_VIDEO) return true;
        return false;
    }
}
