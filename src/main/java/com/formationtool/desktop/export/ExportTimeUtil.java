package com.formationtool.desktop.export;

import java.util.Locale;

final class ExportTimeUtil {
    private ExportTimeUtil() {
    }

    static String formatTime(long ms) {
        long hours = ms / 3_600_000;
        long minutes = (ms % 3_600_000) / 60_000;
        long seconds = (ms % 60_000) / 1000;
        long millis = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }
}
