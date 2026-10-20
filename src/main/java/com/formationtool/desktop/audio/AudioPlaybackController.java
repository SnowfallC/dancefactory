package com.formationtool.desktop.audio;

import com.formationtool.desktop.viewmodel.FormationViewModel;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

/** 视频使用 libmpv 或 FFmpeg，JavaFX MediaPlayer 仅负责音频。 */
public final class AudioPlaybackController {
    private final FormationViewModel viewModel;
    private MpvVideoPlayer nativeVideo;
    private SoftwareVideoPlayer softwareVideo;
    private MediaPlayer audioPlayer;
    private ImageView boundPreviewImageView;
    private File currentAudioFile;
    private boolean hasVideo;
    private final AtomicBoolean isVideoReady = new AtomicBoolean();
    private final AtomicBoolean isPlaying = new AtomicBoolean();
    private final AtomicBoolean suppressPropertySeekSync = new AtomicBoolean();
    private final AtomicBoolean isPreviewScrubbing = new AtomicBoolean();
    private final AtomicLong videoRevision = new AtomicLong();
    private final AtomicLong softwareClockMs = new AtomicLong();
    private long softwareStartMs;
    private long softwareStartNanos;
    private boolean softwareUsesAudio;
    private AnimationTimer playbackTimer;
    private Runnable onPlaybackComplete;
    private Consumer<Long> onTimeUpdate;
    private Consumer<String> onError;
    private Runnable onAudioReady;
    private final AtomicInteger playCount = new AtomicInteger();
    private final AtomicInteger seekCount = new AtomicInteger();
    private final AtomicBoolean isRebuilding = new AtomicBoolean();
    private static final int REBUILD_PLAY_THRESHOLD = 50;
    private static final int REBUILD_SEEK_THRESHOLD = 500;

    public AudioPlaybackController(FormationViewModel viewModel) {
        this.viewModel = viewModel;
        viewModel.isPlayingProperty().addListener((obs, previous, playing) -> {
            if (playing) doPlay(); else doPause();
        });
        viewModel.currentTimeProperty().addListener((obs, previous, time) -> {
            if (!isPlaying.get() && !suppressPropertySeekSync.get() && !isPreviewScrubbing.get()) {
                seekActivePlayer(time.longValue());
            }
        });
    }

    public void bindPreviewImageView(ImageView imageView) {
        boundPreviewImageView = imageView;
        clearVideoImage();
    }

    public void loadAudio(File audioFile, Runnable onReady, java.util.function.Consumer<String> onError) {
        this.onAudioReady = onReady;
        this.onError = onError;
        this.currentAudioFile = audioFile;  // 保存文件引用用于重建

        // 重置计数器
        playCount.set(0);
        seekCount.set(0);

        // 先清理旧的播放器
        disposeAudio();

        createAudioPlayer(audioFile, true);
    }

    private void createAudioPlayer(File audioFile, boolean notifyReady) {
        try {
            String uri = audioFile.toURI().toString();
            Media media = new Media(uri);
            MediaPlayer created = new MediaPlayer(media);
            audioPlayer = created;

            created.setOnReady(() -> {
                if (audioPlayer != created) return;
                long durationMs = (long) created.getMedia().getDuration().toMillis();
                System.out.println("[播放控制器] 音频就绪，时长: " + durationMs + "ms");

                Platform.runLater(() -> {
                    if (audioPlayer != created) return;
                    if (nativeVideo == null && softwareVideo == null) viewModel.setAudioDuration(durationMs);
                    if (notifyReady && onAudioReady != null) {
                        onAudioReady.run();
                    }
                });
            });

            created.setOnError(() -> {
                if (audioPlayer != created) return;
                String errorMsg = created.getError() != null ?
                    created.getError().getMessage() : "未知错误";
                System.err.println("[播放控制器] 音频加载错误: " + errorMsg);
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(errorMsg));
                }
            });

            created.setOnEndOfMedia(() -> {
                if (audioPlayer != created || nativeVideo != null) return;
                System.out.println("[播放控制器] 音频播放完成");
                Platform.runLater(this::onPlaybackEnded);
            });

        } catch (Exception e) {
            System.err.println("[播放控制器] 加载音频异常: " + e.getMessage());
            if (onError != null) {
                Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        }
    }

    private void checkAndRebuildIfNeeded() {
        // 如果正在重建或没有音频文件，跳过
        if (isRebuilding.get() || currentAudioFile == null || hasVideo) {
            return;
        }

        int plays = playCount.get();
        int seeks = seekCount.get();

        if (plays >= REBUILD_PLAY_THRESHOLD || seeks >= REBUILD_SEEK_THRESHOLD) {
            if (isRebuilding.compareAndSet(false, true)) {
                System.out.println("[播放控制器] 性能优化：重建音频播放器 (播放:" + plays + ", seek:" + seeks + ")");

                // 记录当前位置
                long currentPos = 0;
                if (audioPlayer != null) {
                    currentPos = (long) audioPlayer.getCurrentTime().toMillis();
                }
                final long savedPos = currentPos;

                // 异步重建避免阻塞
                Platform.runLater(() -> {
                    try {
                        // 释放旧播放器
                        if (audioPlayer != null) {
                            audioPlayer.stop();
                            audioPlayer.dispose();
                            audioPlayer = null;
                        }

                        // 创建新播放器
                        createAudioPlayer(currentAudioFile, false);

                        // 等待就绪后恢复位置
                        if (audioPlayer != null) {
                            audioPlayer.setOnReady(() -> {
                                audioPlayer.seek(Duration.millis(savedPos));
                                System.out.println("[播放控制器] 重建完成，已恢复到: " + savedPos + "ms");
                            });
                        }

                        // 重置计数器
                        playCount.set(0);
                        seekCount.set(0);

                    } finally {
                        isRebuilding.set(false);
                    }
                });
            }
        }
    }

    public void loadNativeVideo(File file, Runnable ready, java.util.function.Consumer<String> failure) {
        if (boundPreviewImageView == null) { failure.accept("视频预览图层尚未初始化"); return; }
        if (nativeVideo != null) nativeVideo.close();
        if (softwareVideo != null) { softwareVideo.close(); softwareVideo = null; }
        isVideoReady.set(false);
        MpvVideoPlayer loading = new MpvVideoPlayer(image -> {
            boundPreviewImageView.setImage(image);
            boundPreviewImageView.setVisible(true);
        }, message -> {
            viewModel.setPlaying(false);
            failure.accept(message);
        }, this::onPlaybackEnded);
        nativeVideo = loading;
        loading.load(file, () -> {
            if (nativeVideo != loading) return;
            hasVideo = true; isVideoReady.set(true);
            viewModel.setAudioDuration(loading.getDurationMs());
            if (viewModel.getCurrentTime() > 0) loading.seek(viewModel.getCurrentTime());
            if (ready != null) ready.run();
        });
    }

    public void loadSoftwareVideo(File file, Runnable ready, java.util.function.Consumer<String> failure) {
        if (boundPreviewImageView == null) {
            failure.accept("视频预览图层尚未初始化");
            return;
        }
        if (nativeVideo != null) { nativeVideo.close(); nativeVideo = null; }
        if (softwareVideo != null) softwareVideo.close();
        isVideoReady.set(false);
        softwareVideo = new SoftwareVideoPlayer(image -> {
            boundPreviewImageView.setImage(image);
            boundPreviewImageView.setVisible(true);
        }, message -> {
            viewModel.setPlaying(false);
            failure.accept(message);
        });
        SoftwareVideoPlayer loading = softwareVideo;
        loading.load(file, () -> {
            if (softwareVideo != loading) return;
            hasVideo = true;
            isVideoReady.set(true);
            if (loading.getDurationMs() > 0) viewModel.setAudioDuration(loading.getDurationMs());
            if (viewModel.getCurrentTime() > 0) loading.seek(viewModel.getCurrentTime());
            System.out.println("[兼容播放] FFmpeg 视频就绪，时长=" + loading.getDurationMs());
            if (ready != null) ready.run();
        });
    }

    private void startSoftwarePlayback(long positionMs) {
        softwareStartMs = positionMs;
        softwareStartNanos = System.nanoTime();
        softwareClockMs.set(positionMs);
        softwareUsesAudio = audioPlayer != null;
        if (audioPlayer != null) {
            audioPlayer.seek(Duration.millis(positionMs));
            audioPlayer.play();
        }
        softwareVideo.play(positionMs, softwareClockMs::get, this::onPlaybackEnded);
        startPlaybackTimer();
    }

    public boolean isNativeVideo() { return nativeVideo != null && isVideoReady.get(); }
    public boolean isSoftwareVideo() { return softwareVideo != null && isVideoReady.get(); }
    public boolean hasVideo() { return hasVideo; }
    public long getVideoRevision() { return videoRevision.get(); }
    public boolean canPlay() { return (hasVideo && isVideoReady.get()) || audioPlayer != null; }
    public long getVideoDurationMs() {
        if (nativeVideo != null) return nativeVideo.getDurationMs();
        return softwareVideo != null ? softwareVideo.getDurationMs() : 0;
    }

    private void doPlay() {
        if (!canPlay()) { viewModel.setPlaying(false); return; }
        if (isPlaying.getAndSet(true)) return;
        isPreviewScrubbing.set(false);
        playCount.incrementAndGet();
        long position = viewModel.getCurrentTime();
        if (nativeVideo != null) {
            nativeVideo.seek(position);
            nativeVideo.play();
            startPlaybackTimer();
        } else if (softwareVideo != null) {
            startSoftwarePlayback(position);
        } else if (audioPlayer != null) {
            audioPlayer.seek(Duration.millis(position));
            audioPlayer.play();
            startPlaybackTimer();
        }
    }

    private void doPause() {
        isPlaying.set(false);
        stopPlaybackTimer();
        if (nativeVideo != null) nativeVideo.pause();
        if (softwareVideo != null) softwareVideo.pause();
        if (audioPlayer != null) audioPlayer.pause();
        checkAndRebuildIfNeeded();
    }

    public void seekTo(long positionMs) {
        long position = clampPosition(positionMs);
        isPreviewScrubbing.set(false);
        suppressPropertySeekSync.set(true);
        try { viewModel.setCurrentTime(position); }
        finally { suppressPropertySeekSync.set(false); }
        if (softwareVideo != null && isPlaying.get()) startSoftwarePlayback(position);
        else seekActivePlayer(position);
        seekCount.incrementAndGet();
        if (seekCount.get() % 100 == 0) checkAndRebuildIfNeeded();
    }

    private void seekActivePlayer(long positionMs) {
        long position = clampPosition(positionMs);
        if (nativeVideo != null && isVideoReady.get()) nativeVideo.seek(position);
        else if (softwareVideo != null && isVideoReady.get()) {
            softwareVideo.seek(position);
            if (audioPlayer != null) audioPlayer.seek(Duration.millis(position));
        } else if (audioPlayer != null) audioPlayer.seek(Duration.millis(position));
    }

    private long clampPosition(long position) {
        return Math.max(0, viewModel.getAudioDuration() > 0 ? Math.min(position, viewModel.getAudioDuration()) : position);
    }

    public void beginPreviewScrub() {
        if (!isPlaying.get()) isPreviewScrubbing.set(true);
    }

    public void updatePreviewScrubTarget(long positionMs) {
        if (!isPreviewScrubbing.get()) return;
        long position = clampPosition(positionMs);
        if (nativeVideo != null) nativeVideo.seek(position);
        else if (softwareVideo != null) softwareVideo.seek(position);
    }

    public void endPreviewScrub(long positionMs) {
        isPreviewScrubbing.set(false);
        seekTo(positionMs);
    }

    private void startPlaybackTimer() {
        stopPlaybackTimer();
        playbackTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (!isPlaying.get()) return;
                long position;
                if (nativeVideo != null) position = nativeVideo.getPositionMs();
                else if (softwareVideo != null) {
                    position = softwareUsesAudio && audioPlayer != null
                        ? Math.max(softwareStartMs, (long) audioPlayer.getCurrentTime().toMillis())
                        : softwareStartMs + (System.nanoTime() - softwareStartNanos) / 1_000_000;
                    softwareClockMs.set(position);
                } else if (audioPlayer != null) position = (long) audioPlayer.getCurrentTime().toMillis();
                else return;
                viewModel.setCurrentTime(position);
                if (onTimeUpdate != null) onTimeUpdate.accept(position);
                long duration = viewModel.getAudioDuration();
                if (duration > 0 && position >= duration) onPlaybackEnded();
            }
        };
        playbackTimer.start();
    }

    private void stopPlaybackTimer() {
        if (playbackTimer != null) { playbackTimer.stop(); playbackTimer = null; }
    }

    private void onPlaybackEnded() {
        if (!isPlaying.getAndSet(false)) return;
        stopPlaybackTimer();
        if (nativeVideo != null) { nativeVideo.pause(); nativeVideo.seek(0); }
        if (softwareVideo != null) { softwareVideo.pause(); softwareVideo.seek(0); }
        if (audioPlayer != null) { audioPlayer.stop(); audioPlayer.seek(Duration.ZERO); }
        suppressPropertySeekSync.set(true);
        try { viewModel.setCurrentTime(0); }
        finally { suppressPropertySeekSync.set(false); }
        viewModel.setPlaying(false);
        if (onPlaybackComplete != null) onPlaybackComplete.run();
    }

    public void disposeAudio() {
        viewModel.setPlaying(false);
        stopPlaybackTimer();
        isPlaying.set(false);
        if (softwareVideo != null) softwareVideo.pause();
        MediaPlayer previous = audioPlayer;
        audioPlayer = null;
        if (previous != null) { previous.stop(); previous.dispose(); }
    }

    public void disposeVideo() { disposeVideo(null); }

    public void disposeVideo(Runnable onDisposed) {
        videoRevision.incrementAndGet();
        viewModel.setPlaying(false);
        stopPlaybackTimer();
        isPlaying.set(false);
        isPreviewScrubbing.set(false);
        MpvVideoPlayer previous = nativeVideo;
        nativeVideo = null;
        if (softwareVideo != null) { softwareVideo.close(); softwareVideo = null; }
        hasVideo = false;
        isVideoReady.set(false);
        clearVideoImage();
        if (previous != null) {
            previous.close();
            previous.termination().whenComplete((result, error) -> {
                if (error != null) System.err.println("[播放后端] 旧内核释放异常: " + error);
                if (onDisposed != null) Platform.runLater(onDisposed);
            });
        } else if (onDisposed != null) Platform.runLater(onDisposed);
    }

    private void clearVideoImage() {
        if (boundPreviewImageView != null) {
            boundPreviewImageView.setVisible(false);
            boundPreviewImageView.setImage(null);
        }
    }

    public void dispose() { disposeAudio(); disposeVideo(); }
    public MediaPlayer getAudioPlayer() { return audioPlayer; }
    public boolean isPlaying() { return isPlaying.get(); }
    public void setOnPlaybackComplete(Runnable callback) { onPlaybackComplete = callback; }
    public void setOnTimeUpdate(Consumer<Long> callback) { onTimeUpdate = callback; }
    public void setOnError(Consumer<String> callback) { onError = callback; }
}
