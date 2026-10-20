package com.formationtool.desktop.media;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.controller.MediaImportController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.export.DesktopVideoExporter;
import com.formationtool.desktop.utils.AppLog;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.view.MainLayoutFactory;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** 覆盖实际导入控制器、原生组件缺失后的自动兜底、重复载入和原片导出。 */
@EnabledIfSystemProperty(named = "playbackRegression", matches = ".+")
class DesktopPlaybackPathIntegrationTest {
    @Test void shouldImportPlayFallbackReimportAndExport() throws Exception {
        AppLog.install();
        Platform.startup(() -> Platform.setImplicitExit(false));
        File source = new File(System.getProperty("playbackRegression"));
        assertTrue(source.isFile());
        Path unicode = Path.of("target/player-comparison/中文 空格 #&视频.mp4").toAbsolutePath();
        Files.copy(source.toPath(), unicode, StandardCopyOption.REPLACE_EXISTING);
        String library = System.getProperty("formation.mpv.library");
        AtomicReference<MediaImportController> importer = new AtomicReference<>();
        AtomicReference<AudioPlaybackController> playback = new AtomicReference<>();
        AtomicReference<ImageView> surface = new AtomicReference<>();
        AtomicReference<Label> placeholder = new AtomicReference<>();
        AtomicReference<javafx.scene.layout.StackPane> videoSection = new AtomicReference<>();
        AtomicReference<File> selected = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> operation = new AtomicReference<>();
        FormationViewModel model = new FormationViewModel();
        MediaProcessingService processing = new MediaProcessingService(model, null);
        MpvVideoIntegrationTest.onFx(() -> {
            model.createNewGroup("播放路径回归", 3, 16, 12);
            playback.set(new AudioPlaybackController(model));
            var section = MainLayoutFactory.createVideoSection(model, playback.get());
            videoSection.set(section.getVideoSection());
            surface.set(section.getPreviewImageView());
            new javafx.scene.Scene(videoSection.get(), 1100, 850);
            videoSection.get().applyCss(); videoSection.get().layout();
            placeholder.set((Label) videoSection.get().lookup("#video-empty-placeholder"));
            assertTrue(placeholder.get().isVisible());
            importer.set(new MediaImportController(model, new DataManager(), playback.get(), new Label(), 15000,
                (title, message) -> operation.get().completeExceptionally(new IllegalStateException(title + ": " + message)),
                file -> null, processing::extractAudioFromVideo,
                file -> {}, () -> {}, file -> {}, selected::set, Long::toString));
        });
        try {
            Path audioOnly = Path.of("target/player-comparison/audio-only-regression.wav").toAbsolutePath();
            Process synthesize = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100:duration=2", "-ac", "2", audioOnly.toString()).start();
            assertTrue(synthesize.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, synthesize.exitValue());
            CompletableFuture<Void> audioReady = new CompletableFuture<>(), audioEnded = new CompletableFuture<>();
            MpvVideoIntegrationTest.onFx(() -> {
                playback.get().setOnPlaybackComplete(() -> audioEnded.complete(null));
                playback.get().loadAudio(audioOnly.toFile(), () -> audioReady.complete(null), message -> audioReady.completeExceptionally(new IllegalStateException(message)));
            });
            audioReady.get(10, TimeUnit.SECONDS);
            MpvVideoIntegrationTest.onFx(() -> { assertTrue(playback.get().canPlay()); assertFalse(playback.get().hasVideo()); model.setPlaying(true); });
            MpvVideoIntegrationTest.waitFor(() -> model.getCurrentTime() > 300, 4000);
            MpvVideoIntegrationTest.onFx(() -> { model.setPlaying(false); playback.get().seekTo(1200); model.setPlaying(true); });
            audioEnded.get(5, TimeUnit.SECONDS);
            MpvVideoIntegrationTest.onFx(() -> {
                assertFalse(model.isPlaying()); assertEquals(0, model.getCurrentTime());
                playback.get().disposeAudio(); playback.get().setOnPlaybackComplete(null);
            });
            for (int pass = 0; pass < 3; pass++) {
                operation.set(new CompletableFuture<>());
                MpvVideoIntegrationTest.onFx(() -> importer.get().loadImportedVideo(unicode.toFile(), () -> operation.get().complete(null)));
                operation.get().get(25, TimeUnit.SECONDS);
                MpvVideoIntegrationTest.onFx(() -> {
                    assertTrue(playback.get().isNativeVideo()); assertTrue(playback.get().canPlay());
                    assertNotNull(surface.get().getImage()); assertEquals(unicode.toFile(), selected.get());
                    assertFalse(placeholder.get().isVisible(), "原生视频出现后必须隐藏空状态提示");
                    playback.get().seekTo(2000); model.setPlaying(true);
                });
                MpvVideoIntegrationTest.waitFor(() -> model.getCurrentTime() > 2400, 5000);
                MpvVideoIntegrationTest.onFx(() -> { model.setPlaying(false); playback.get().seekTo(1000); });
                Thread.sleep(150);
                assertEquals(1000, model.getCurrentTime());
            }
            MpvVideoIntegrationTest.onFx(() -> {
                videoSection.get().applyCss(); videoSection.get().layout();
                try { javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(videoSection.get().snapshot(null, null), null),
                    "png", new File("target/player-comparison/video-section-native-fixed.png")); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            // 无音轨视频应能单独播放，且无需 JavaFX 视频解码器。
            Path silent = Path.of("target/player-comparison/silent-source.mp4").toAbsolutePath();
            Process stripAudio = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", source.getAbsolutePath(),
                "-map", "0:v:0", "-c", "copy", "-t", "8", "-an", silent.toString()).start();
            assertTrue(stripAudio.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, stripAudio.exitValue());
            operation.set(new CompletableFuture<>());
            MpvVideoIntegrationTest.onFx(() -> importer.get().loadImportedVideo(silent.toFile(), () -> operation.get().complete(null)));
            operation.get().get(20, TimeUnit.SECONDS);
            MpvVideoIntegrationTest.onFx(() -> { playback.get().seekTo(1000); model.setPlaying(true); });
            MpvVideoIntegrationTest.waitFor(() -> model.getCurrentTime() > 1400, 5000);
            MpvVideoIntegrationTest.onFx(() -> model.setPlaying(false));
            // 同时到来的旧载入请求不能触发成功回调或覆盖新媒体。
            java.util.concurrent.atomic.AtomicBoolean obsoleteReady = new java.util.concurrent.atomic.AtomicBoolean();
            MpvVideoIntegrationTest.onFx(() -> {
                importer.get().loadImportedVideo(silent.toFile(), () -> obsoleteReady.set(true));
                playback.get().disposeVideo();
            });
            Thread.sleep(300); assertFalse(obsoleteReady.get()); assertFalse(playback.get().hasVideo());
            MpvVideoIntegrationTest.onFx(() -> { assertTrue(placeholder.get().isVisible()); assertNull(surface.get().getImage()); });
            operation.set(new CompletableFuture<>());
            MpvVideoIntegrationTest.onFx(() -> {
                importer.get().loadImportedVideo(silent.toFile(), () -> obsoleteReady.set(true));
                importer.get().loadImportedVideo(unicode.toFile(), () -> operation.get().complete(null));
            });
            operation.get().get(25, TimeUnit.SECONDS); assertFalse(obsoleteReady.get());
            assertEquals(unicode.toFile(), selected.get());
            // 使用不存在的应用内核路径触发真实自动兜底，不人为伪造成功回调。
            System.setProperty("formation.mpv.library", Path.of("target/player-comparison/missing-libmpv.dll").toAbsolutePath().toString());
            operation.set(new CompletableFuture<>());
            MpvVideoIntegrationTest.onFx(() -> importer.get().loadImportedVideo(unicode.toFile(), () -> operation.get().complete(null)));
            operation.get().get(30, TimeUnit.SECONDS);
            MpvVideoIntegrationTest.onFx(() -> {
                assertTrue(playback.get().isSoftwareVideo()); assertNotNull(playback.get().getAudioPlayer());
                assertFalse(placeholder.get().isVisible(), "兼容视频出现后必须隐藏空状态提示");
                assertNotNull(surface.get().getImage()); playback.get().seekTo(1000); model.setPlaying(true);
            });
            MpvVideoIntegrationTest.waitFor(() -> model.getCurrentTime() > 1400, 5000);
            if (source.getName().startsWith("sync-")) {
                for (int sample = 0; sample < 20; sample++) {
                    MpvVideoIntegrationTest.onFx(() -> {
                        var image = surface.get().getImage(); int encodedFrame = 0;
                        for (int bit = 0; bit < 16; bit++) if ((image.getPixelReader().getArgb(bit * 32 + 16, 330) & 255) > 128) encodedFrame |= 1 << bit;
                        assertTrue(Math.abs(encodedFrame * 1000 / 30 - model.getCurrentTime()) < 200, "兼容播放画面应跟随音频时钟");
                    });
                    Thread.sleep(100);
                }
            }
            MpvVideoIntegrationTest.onFx(() -> model.setPlaying(false));
            long paused = model.getCurrentTime(); Thread.sleep(250); assertEquals(paused, model.getCurrentTime());
            MpvVideoIntegrationTest.onFx(() -> {
                playback.get().beginPreviewScrub(); playback.get().updatePreviewScrubTarget(5000);
                playback.get().updatePreviewScrubTarget(800); playback.get().endPreviewScrub(800);
            });
            Thread.sleep(500); assertEquals(800, model.getCurrentTime());
            AtomicReference<StageView> exportStage = new AtomicReference<>();
            MpvVideoIntegrationTest.onFx(() -> exportStage.set(new StageView(model, 640, 360)));
            File output = new File("target/player-comparison/native-path-export.mp4");
            new DesktopVideoExporter(model, exportStage.get(), null, selected.get()).export(true, output, 2000, 3000);
            assertTrue(output.length() > 0);
            Process probe = new ProcessBuilder("ffmpeg", "-hide_banner", "-i", output.getAbsolutePath()).redirectErrorStream(true).start();
            String metadata = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS));
            assertTrue(metadata.contains("Duration: 00:00:01."), metadata);
            AppLog.flush(); assertTrue(AppLog.isEnabled());
            String logs;
            try (var paths = Files.list(AppLog.getDirectory())) {
                StringBuilder content = new StringBuilder();
                for (Path path : paths.filter(p -> p.toString().endsWith(".log")).toList()) content.append(Files.readString(path));
                logs = content.toString();
            }
            assertTrue(logs.contains("libmpv 就绪")); assertTrue(logs.contains("切换 FFmpeg"));
            assertTrue(logs.contains("FFmpeg 视频就绪"));
            MpvVideoIntegrationTest.onFx(() -> {
                playback.get().disposeVideo();
                assertNull(surface.get().getImage());
                assertTrue(placeholder.get().isVisible(), "移除兼容视频后必须恢复提示");
            });
        } finally {
            if (library != null) System.setProperty("formation.mpv.library", library);
            MpvVideoIntegrationTest.onFx(() -> playback.get().dispose());
            Platform.exit();
        }
    }
}
