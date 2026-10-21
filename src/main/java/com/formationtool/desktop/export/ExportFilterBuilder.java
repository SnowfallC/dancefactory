package com.formationtool.desktop.export;

import com.formationtool.desktop.model.ExportConfig;

final class ExportFilterBuilder {
    private ExportFilterBuilder() {
    }

    static String buildHorizontalSplitFilter(ExportConfig config, int outputWidth, int outputHeight) {
        int halfWidth = outputWidth / 2;
        StringBuilder filter = new StringBuilder();

        if (config.getPrimaryView() == ExportConfig.ViewType.REFERENCE_VIDEO) {
            filter.append(config.isReferenceVideoMirrored() ? "[1:v]hflip,scale=" : "[1:v]scale=").append(halfWidth).append(":").append(outputHeight)
                .append(":force_original_aspect_ratio=decrease,")
                .append("pad=").append(halfWidth).append(":").append(outputHeight).append(":(ow-iw)/2:(oh-ih)/2:black[left];");
        } else {
            filter.append("[0:v]scale=").append(halfWidth).append(":").append(outputHeight).append("[left];");
        }

        if (config.getSecondaryView() == ExportConfig.ViewType.REFERENCE_VIDEO) {
            filter.append(config.isReferenceVideoMirrored() ? "[1:v]hflip,scale=" : "[1:v]scale=").append(halfWidth).append(":").append(outputHeight)
                .append(":force_original_aspect_ratio=decrease,")
                .append("pad=").append(halfWidth).append(":").append(outputHeight).append(":(ow-iw)/2:(oh-ih)/2:black[right];");
        } else {
            filter.append("[0:v]scale=").append(halfWidth).append(":").append(outputHeight).append("[right];");
        }

        filter.append("[left][right]hstack=inputs=2[v]");
        return filter.toString();
    }

    static String buildVerticalSplitFilter(ExportConfig config, int outputWidth, int outputHeight) {
        int halfHeight = outputHeight / 2;
        StringBuilder filter = new StringBuilder();

        if (config.getPrimaryView() == ExportConfig.ViewType.REFERENCE_VIDEO) {
            filter.append(config.isReferenceVideoMirrored() ? "[1:v]hflip,scale=" : "[1:v]scale=").append(outputWidth).append(":").append(halfHeight)
                .append(":force_original_aspect_ratio=decrease,")
                .append("pad=").append(outputWidth).append(":").append(halfHeight).append(":(ow-iw)/2:(oh-ih)/2:black[top];");
        } else {
            filter.append("[0:v]scale=").append(outputWidth).append(":").append(halfHeight).append("[top];");
        }

        if (config.getSecondaryView() == ExportConfig.ViewType.REFERENCE_VIDEO) {
            filter.append(config.isReferenceVideoMirrored() ? "[1:v]hflip,scale=" : "[1:v]scale=").append(outputWidth).append(":").append(halfHeight)
                .append(":force_original_aspect_ratio=decrease,")
                .append("pad=").append(outputWidth).append(":").append(halfHeight).append(":(ow-iw)/2:(oh-ih)/2:black[bottom];");
        } else {
            filter.append("[0:v]scale=").append(outputWidth).append(":").append(halfHeight).append("[bottom];");
        }

        filter.append("[top][bottom]vstack=inputs=2[v]");
        return filter.toString();
    }
}
