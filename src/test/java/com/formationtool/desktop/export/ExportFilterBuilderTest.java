package com.formationtool.desktop.export;

import com.formationtool.desktop.model.ExportConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportFilterBuilderTest {

    @Test
    void shouldBuildHorizontalSplitFilterWithReferenceVideoOnRight() {
        ExportConfig config = new ExportConfig();
        config.setLayoutMode(ExportConfig.LayoutMode.SPLIT_HORIZONTAL);
        config.setPrimaryView(ExportConfig.ViewType.VIEW_2D);
        config.setSecondaryView(ExportConfig.ViewType.REFERENCE_VIDEO);

        String filter = ExportFilterBuilder.buildHorizontalSplitFilter(config, 1920, 1080);

        assertTrue(filter.contains("[0:v]scale=960:1080[left]"));
        assertTrue(filter.contains("[1:v]scale=960:1080:force_original_aspect_ratio=decrease"));
        assertTrue(filter.endsWith("[left][right]hstack=inputs=2[v]"));
    }

    @Test
    void shouldBuildVerticalSplitFilterWithReferenceVideoOnTop() {
        ExportConfig config = new ExportConfig();
        config.setLayoutMode(ExportConfig.LayoutMode.SPLIT_VERTICAL);
        config.setPrimaryView(ExportConfig.ViewType.REFERENCE_VIDEO);
        config.setSecondaryView(ExportConfig.ViewType.VIEW_2D);

        String filter = ExportFilterBuilder.buildVerticalSplitFilter(config, 1080, 1920);

        assertTrue(filter.contains("[1:v]scale=1080:960:force_original_aspect_ratio=decrease"));
        assertTrue(filter.contains("[0:v]scale=1080:960[bottom]"));
        assertTrue(filter.endsWith("[top][bottom]vstack=inputs=2[v]"));
    }
}
