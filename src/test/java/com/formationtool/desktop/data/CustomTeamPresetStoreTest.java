package com.formationtool.desktop.data;

import com.formationtool.desktop.model.CustomTeamPreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CustomTeamPresetStoreTest {
    @TempDir Path directory;

    private CustomTeamPreset preset(String name) {
        return new CustomTeamPreset(name, List.of(new CustomTeamPreset.Member("米娅", "#B0C4DE"),
            new CustomTeamPreset.Member("步梦", "#FFAACC")));
    }

    @Test void shouldPersistReadableUtf8JsonAndReloadFromNewStore() throws Exception {
        Path file = directory.resolve("Presets/custom-teams.json");
        new CustomTeamPresetStore(file).save(preset("我的团队"));
        assertEquals(List.of(preset("我的团队")), new CustomTeamPresetStore(file).load());
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("米娅"));
        assertTrue(json.contains("#B0C4DE"));
        assertFalse(json.startsWith("\uFEFF"));
    }

    @Test void shouldUpdateAndRenameWithoutDuplicatingPresets() throws Exception {
        CustomTeamPresetStore store = new CustomTeamPresetStore(directory.resolve("presets.json"));
        store.save(preset("A"));
        store.save(preset("B"));
        store.save(preset("A"));
        assertEquals(2, store.load().size());
        store.save(preset("新名字"), "A");
        assertEquals(List.of(preset("B"), preset("新名字")), store.load());
    }

    @Test void shouldPreserveCorruptLibraryAndRejectInvalidColors() throws Exception {
        Path file = directory.resolve("presets.json");
        Files.writeString(file, "{ broken json", StandardCharsets.UTF_8);
        CustomTeamPresetStore store = new CustomTeamPresetStore(file);
        assertThrows(IOException.class, () -> store.save(preset("测试")));
        assertEquals("{ broken json", Files.readString(file));
        assertThrows(IllegalArgumentException.class, () -> CustomTeamPresetStore.validate(
            new CustomTeamPreset("测试", List.of(new CustomTeamPreset.Member("舞者", "wrong")))));
    }
}
