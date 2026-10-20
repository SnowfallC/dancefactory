package com.formationtool.desktop.data;

import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DataManagerTest {
    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void shouldSaveAndLoadProjectSuccessfully() {
        DataManager dataManager = new DataManager();
        DanceGroup group = createGroup("project-save-load");

        group.setStandardMode(true);
        group.setBackstageVisible(true);
        boolean saved = dataManager.saveProject(group);
        DanceGroup loaded = dataManager.loadProject(group.getId());

        assertTrue(saved);
        assertTrue(loaded.isStandardMode());
        assertTrue(loaded.isBackstageVisible());
        assertNotNull(loaded);
        assertEquals(group.getId(), loaded.getId());
        assertEquals(group.getName(), loaded.getName());
        assertEquals(1, loaded.getFormations().size());
        assertEquals(2, loaded.getFormations().get(0).getDancers().size());
    }

    @Test
    void shouldCopyAndDeleteAudioAndVideoInProjectDirectory() throws Exception {
        DataManager dataManager = new DataManager();
        String projectId = "project-media-" + UUID.randomUUID();

        File audioSrc = tempDir.resolve("sample.mp3").toFile();
        File videoSrc = tempDir.resolve("sample.mp4").toFile();
        Files.write(audioSrc.toPath(), new byte[]{1, 2, 3, 4});
        Files.write(videoSrc.toPath(), new byte[]{5, 6, 7, 8});

        File copiedAudio = dataManager.copyAudioToProject(projectId, audioSrc);
        File copiedVideo = dataManager.copyVideoToProject(projectId, videoSrc);

        assertNotNull(copiedAudio);
        assertNotNull(copiedVideo);
        assertTrue(copiedAudio.exists());
        assertTrue(copiedVideo.exists());
        assertNotNull(dataManager.getProjectAudioFile(projectId));
        assertNotNull(dataManager.getProjectVideoFile(projectId));

        assertTrue(dataManager.deleteAudioFromProject(projectId));
        assertTrue(dataManager.deleteVideoFromProject(projectId));
        assertNull(dataManager.getProjectAudioFile(projectId));
        assertNull(dataManager.getProjectVideoFile(projectId));
    }

    @Test
    void shouldRoundTripZipWithMediaAndDancerStyle() throws Exception {
        DataManager dataManager = new DataManager();
        DanceGroup group = createGroup("project-zip-roundtrip");
        Dancer firstDancer = group.getFormations().get(0).getDancers().get(0);

        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        File styleFile = dataManager.saveDancerStyleImage(group.getId(), firstDancer.getId(), image);
        assertNotNull(styleFile);
        firstDancer.setCustomStylePath(styleFile.getAbsolutePath());

        File audioSrc = tempDir.resolve("zip-audio.wav").toFile();
        File videoSrc = tempDir.resolve("zip-video.mp4").toFile();
        Files.write(audioSrc.toPath(), new byte[]{11, 12, 13});
        Files.write(videoSrc.toPath(), new byte[]{21, 22, 23});

        File zipFile = tempDir.resolve("project.ftproj").toFile();
        boolean exported = dataManager.exportProjectAsZip(group, audioSrc, videoSrc, zipFile);
        DataManager.ProjectImportResult imported = dataManager.importProjectFromZip(zipFile);

        assertTrue(exported);
        assertTrue(zipFile.exists());
        assertNotNull(imported);
        assertNotNull(imported.group);
        assertNotNull(imported.audioFile);
        assertNotNull(imported.videoFile);
        assertTrue(imported.audioFile.exists());
        assertTrue(imported.videoFile.exists());

        Dancer importedDancer = imported.group.getFormations().get(0).getDancers().get(0);
        assertNotNull(importedDancer.getCustomStylePath());
        assertTrue(new File(importedDancer.getCustomStylePath()).exists());
    }

    @Test
    void shouldPersistChineseTextUsingUtf8() throws Exception {
        DataManager dataManager = new DataManager();
        DanceGroup group = createGroup("project-utf8-" + UUID.randomUUID());
        group.setName("前台后台测试");
        group.getFormations().get(0).setName("关键帧 测试");
        group.getFormations().get(0).getDancers().get(0).setName("舞者张三");

        boolean saved = dataManager.saveProject(group);
        assertTrue(saved);

        Path projectFile = dataManager.getProjectDir(group.getId()).resolve("project.json");
        byte[] raw = Files.readAllBytes(projectFile);
        String json = new String(raw, StandardCharsets.UTF_8);
        assertTrue(json.contains("前台后台测试"));
        assertTrue(json.contains("舞者张三"));

        DanceGroup loaded = dataManager.loadProject(group.getId());
        assertNotNull(loaded);
        assertEquals("前台后台测试", loaded.getName());
        assertEquals("关键帧 测试", loaded.getFormations().get(0).getName());
        assertEquals("舞者张三", loaded.getFormations().get(0).getDancers().get(0).getName());
    }

    @Test
    void shouldLoadLegacyGb18030ProjectFile() throws Exception {
        DataManager dataManager = new DataManager();
        String projectId = "project-gb18030-" + UUID.randomUUID();
        Path projectDir = dataManager.getProjectDir(projectId);
        Files.createDirectories(projectDir);

        DanceGroup legacyGroup = createGroup(projectId);
        legacyGroup.setName("旧版工程");
        legacyGroup.getFormations().get(0).setName("关键帧 旧版");
        legacyGroup.getFormations().get(0).getDancers().get(0).setName("舞者李四");

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(legacyGroup);
        Files.write(projectDir.resolve("project.json"), json.getBytes(Charset.forName("GB18030")));

        DanceGroup loaded = dataManager.loadProject(projectId);
        assertNotNull(loaded);
        assertEquals("旧版工程", loaded.getName());
        assertEquals("关键帧 旧版", loaded.getFormations().get(0).getName());
        assertEquals("舞者李四", loaded.getFormations().get(0).getDancers().get(0).getName());
    }

    @Test
    void shouldRejectUnsafeArchiveEntry() throws Exception {
        DataManager dataManager = new DataManager();
        File archive = tempDir.resolve("unsafe.ftproj").toFile();
        DanceGroup group = createGroup("project-unsafe");
        String json = new GsonBuilder().create().toJson(group);

        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            out.putNextEntry(new ZipEntry("project.json"));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("dancer_styles/../../outside.png"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
        }

        assertNull(dataManager.importProjectFromZip(archive));
        assertFalse(Files.exists(tempDir.resolve("outside.png")));
    }

    private DanceGroup createGroup(String projectId) {
        DanceGroup group = new DanceGroup(projectId, "测试工程-" + projectId, 16f, 12f);
        Formation formation = new Formation(UUID.randomUUID().toString(), "关键帧 00:00", 0L);
        formation.getDancers().add(new Dancer(UUID.randomUUID().toString(), "A", -1f, 0f, 0xFFFF0000));
        formation.getDancers().add(new Dancer(UUID.randomUUID().toString(), "B", 1f, 0f, 0xFF00FF00));
        group.getFormations().add(formation);
        group.setDancerCount(formation.getDancers().size());
        return group;
    }
    @Test
    void shouldOpenProjectAfterLocalPresetIsUnavailable() throws Exception {
        Path presetFile = tempDir.resolve("Presets/custom-teams.json");
        CustomTeamPresetStore store = new CustomTeamPresetStore(presetFile);
        store.save(new com.formationtool.desktop.model.CustomTeamPreset("本机模板", java.util.List.of(
            new com.formationtool.desktop.model.CustomTeamPreset.Member("米娅", "#B0C4DE"))));
        var preset = store.load().get(0);
        var member = preset.members().get(0);
        var viewModel = new com.formationtool.desktop.viewmodel.FormationViewModel();
        viewModel.createNewGroup("跨设备工程", 2, 16, 12);
        viewModel.insertKeyframeSmart(6000, null);
        viewModel.replaceDancerRoster(java.util.List.of(new Dancer("member-id", member.name(), 0, 0,
            0xFF000000 | Integer.parseInt(member.color().substring(1), 16))));
        DataManager manager = new DataManager();
        var group = viewModel.getCurrentGroup();
        assertTrue(manager.saveProject(group));
        // 模拟其他设备只有工程、没有此机器的模板文件。
        Files.move(presetFile, tempDir.resolve("unavailable-preset.json"));
        var loaded = manager.loadProject(group.getId());
        assertNotNull(loaded);
        assertEquals(2, loaded.getFormations().size());
        for (Formation formation : loaded.getFormations()) {
            assertEquals("米娅", formation.getDancers().get(0).getName());
            assertEquals(0xFFB0C4DE, formation.getDancers().get(0).getColor());
        }
        assertTrue(viewModel.undo());
        assertEquals(2, viewModel.getCurrentGroup().getDancerCount());
    }
}
