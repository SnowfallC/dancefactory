package com.formationtool.desktop.media;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "mediaRegression", matches = ".+")
class VideoDiagnosticsIntegrationTest {
    @TempDir Path temp;

    @Test void shouldDistinguishDecodableVideoFromCorruptInput() throws Exception {
        assertTrue(VideoDiagnostics.checkDecode(Path.of(System.getProperty("mediaRegression")).toFile()));
        Path invalid = temp.resolve("bad.mp4");
        Files.writeString(invalid, "损坏的视频");
        assertFalse(VideoDiagnostics.checkDecode(invalid.toFile()));
    }
}
