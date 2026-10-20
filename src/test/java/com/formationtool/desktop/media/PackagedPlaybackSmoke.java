package com.formationtool.desktop.media;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.audio.MpvVideoPlayer;
import com.formationtool.desktop.controller.MediaImportController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.utils.AppLog;
import com.formationtool.desktop.utils.FFmpegUtil;
import com.formationtool.desktop.view.DiagnosticDialogs;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** 仅打入临时验收副本，验证实际 jpackage 启动器和裁剪运行时。 */
public final class PackagedPlaybackSmoke {
    private static void onFx(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> { try { action.run(); done.complete(null); } catch (Throwable error) { done.completeExceptionally(error); } });
        done.get(15, TimeUnit.SECONDS);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void awaitTime(FormationViewModel model, long target) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (model.getCurrentTime() < target && System.nanoTime() < until) Thread.sleep(10);
        require(model.getCurrentTime() >= target, "播放时钟没有推进");
    }

    public static void main(String[] args) {
        AppLog.install();
        Path result = Path.of(System.getProperty("formation.smoke.result"));
        AtomicReference<AudioPlaybackController> playback = new AtomicReference<>();
        AtomicReference<Stage> window = new AtomicReference<>();
        try {
            require(AppLog.isEnabled(), "日志未启用");
            require(Files.isRegularFile(MpvVideoPlayer.libraryPath()), "缺少内置 libmpv");
            String ffmpeg = FFmpegUtil.getFFmpegPath();
            require(ffmpeg != null && Path.of(ffmpeg).isAbsolute() && ffmpeg.contains("ffmpeg"), "未找到内置 FFmpeg");
            Platform.startup(() -> Platform.setImplicitExit(false));
            FormationViewModel model = new FormationViewModel();
            AtomicReference<MediaImportController> importer = new AtomicReference<>();
            AtomicReference<ImageView> image = new AtomicReference<>();
            AtomicReference<CompletableFuture<Void>> loading = new AtomicReference<>(new CompletableFuture<>());
            File source = new File(System.getProperty("formation.smoke.source"));
            onFx(() -> {
                model.createNewGroup("打包验收", 2, 16, 12);
                image.set(new ImageView()); image.get().setFitWidth(960); image.get().setFitHeight(540);
                window.set(new Stage()); window.get().setScene(new Scene(new StackPane(image.get()), 960, 540)); window.get().show();
                playback.set(new AudioPlaybackController(model)); playback.get().bindPreviewImageView(image.get());
                MediaProcessingService processing = new MediaProcessingService(model, null);
                importer.set(new MediaImportController(model, new DataManager(), playback.get(), new Label(), 15000,
                    (title, message) -> loading.get().completeExceptionally(new IllegalStateException(title + ": " + message)),
                    file -> null, processing::extractAudioFromVideo,
                    file -> {}, () -> {}, file -> {}, file -> {}, Long::toString));
                importer.get().loadImportedVideo(source, () -> loading.get().complete(null));
            });
            loading.get().get(25, TimeUnit.SECONDS);
            onFx(() -> { require(playback.get().isNativeVideo(), "主播放未使用 libmpv"); playback.get().seekTo(2000); model.setPlaying(true); });
            awaitTime(model, 2500);
            onFx(() -> model.setPlaying(false));
            // 不借用开发机的组件：副本中主内核模拟缺失后仍须使用内置 FFmpeg 完成播放。
            System.setProperty("formation.mpv.library", MpvVideoPlayer.libraryPath().resolveSibling("missing.dll").toString());
            loading.set(new CompletableFuture<>());
            onFx(() -> importer.get().loadImportedVideo(source, () -> loading.get().complete(null)));
            loading.get().get(25, TimeUnit.SECONDS);
            onFx(() -> { require(playback.get().isSoftwareVideo(), "没有自动进入 FFmpeg 兜底"); playback.get().seekTo(1000); model.setPlaying(true); });
            awaitTime(model, 1500);
            onFx(() -> {
                model.setPlaying(false);
                require(image.get().getImage() != null, "没有视频画面");
                Alert alert = new Alert(Alert.AlertType.ERROR); alert.setTitle("验收日志入口"); alert.setContentText("测试错误");
                DiagnosticDialogs.attachLogButton(alert);
                require(alert.getButtonTypes().stream().anyMatch(button -> "打开日志文件夹".equals(button.getText())), "错误弹窗缺少日志入口");
            });
            Files.writeString(result, "PASS\njava.home=" + System.getProperty("java.home") + "\nFFmpeg=" + ffmpeg
                + "\nlogs=" + AppLog.getDirectory() + "\nlibmpv、FFmpeg 自动兜底、日志与错误弹窗均通过。\n");
        } catch (Throwable error) {
            error.printStackTrace();
            try { Files.writeString(result, "FAIL\n" + error); } catch (Exception ignored) { }
            System.exit(1);
        } finally {
            try { onFx(() -> { if (playback.get() != null) playback.get().dispose(); if (window.get() != null) window.get().close(); }); }
            catch (Exception ignored) { }
            AppLog.flush(); Platform.exit();
        }
    }
}
