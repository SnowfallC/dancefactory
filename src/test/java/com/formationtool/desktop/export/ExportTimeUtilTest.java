package com.formationtool.desktop.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportTimeUtilTest {

    @Test
    void shouldFormatZeroMilliseconds() {
        assertEquals("00:00:00.000", ExportTimeUtil.formatTime(0));
    }

    @Test
    void shouldFormatHourMinuteSecondMillisecond() {
        assertEquals("01:01:01.001", ExportTimeUtil.formatTime(3_661_001));
    }
}
