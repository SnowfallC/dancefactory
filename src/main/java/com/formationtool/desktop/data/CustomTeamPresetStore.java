package com.formationtool.desktop.data;

import com.formationtool.desktop.model.CustomTeamPreset;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 独立保存本机模板，工程读取、播放与导出均不依赖此文件。 */
public final class CustomTeamPresetStore {
    public static final int maxDancers = 200;
    private static final long maxFileBytes = 4 * 1024 * 1024;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private record Library(int version, List<CustomTeamPreset> presets) {}

    public CustomTeamPresetStore() {
        this(Path.of(System.getProperty("user.home"), "Documents", "FormationTool", "Presets", "custom-teams.json"));
    }

    public CustomTeamPresetStore(Path file) { this.file = file; }
    public Path getFile() { return file; }

    public List<CustomTeamPreset> load() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        if (Files.size(file) > maxFileBytes) throw new IOException("预设文件过大");
        try {
            Library library = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Library.class);
            if (library == null || library.version() != 1 || library.presets() == null || library.presets().size() > 100) {
                throw new IllegalArgumentException("预设文件结构无效");
            }
            for (CustomTeamPreset preset : library.presets()) validate(preset);
            return new ArrayList<>(library.presets());
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("无法读取本机预设：" + e.getMessage(), e);
        }
    }

    public void save(CustomTeamPreset preset) throws IOException {
        save(preset, preset == null ? null : preset.name());
    }

    public void save(CustomTeamPreset preset, String previousName) throws IOException {
        validate(preset);
        List<CustomTeamPreset> presets = load();
        presets.removeIf(item -> item.name().equals(preset.name()) || item.name().equals(previousName));
        presets.add(preset);
        if (presets.size() > 100) throw new IOException("最多保存 100 个本机预设");
        String json = gson.toJson(new Library(1, presets));
        if (json.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) throw new IOException("预设文件过大");
        Files.createDirectories(file.toAbsolutePath().getParent());
        // 临时文件写完后再替换，防止中途退出破坏已有模板。
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "custom-teams-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void validate(CustomTeamPreset preset) {
        if (preset == null || preset.name() == null || preset.name().isBlank() || preset.name().length() > 64) {
            throw new IllegalArgumentException("预设名称需要 1—64 个字符");
        }
        if (preset.members() == null || preset.members().isEmpty() || preset.members().size() > maxDancers) {
            throw new IllegalArgumentException("舞者数量需要在 1—200 之间");
        }
        for (var member : preset.members()) {
            if (member == null || member.name() == null || member.name().isBlank() || member.name().length() > 64
                    || member.color() == null || !member.color().matches("#[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("每位舞者需要姓名（最多 64 字）和有效颜色");
            }
        }
    }
}
