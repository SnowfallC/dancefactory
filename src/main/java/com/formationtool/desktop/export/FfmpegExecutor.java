package com.formationtool.desktop.export;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class FfmpegExecutor {
    private FfmpegExecutor() {
    }

    static void execute(
        List<String> command,
        BooleanSupplier isCancelled,
        Consumer<Process> onProcessStarted,
        Runnable onFrameProgress
    ) throws Exception {
        System.out.println("[FFmpeg] 鎵ц鍛戒护...");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        if (onProcessStarted != null) {
            onProcessStarted.accept(process);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("frame=") || line.contains("error") || line.contains("Error")) {
                    System.out.println("[FFmpeg] " + line);
                    if (line.contains("frame=") && onFrameProgress != null) {
                        onFrameProgress.run();
                    }
                }
            }
        }

        if (isCancelled != null && isCancelled.getAsBoolean()) {
            process.destroyForcibly();
        } else {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Exception("FFmpeg 澶勭悊澶辫触锛岄€€鍑虹爜: " + exitCode);
            }
        }
    }
}
