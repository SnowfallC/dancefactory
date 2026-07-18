package com.formationtool.desktop.audio;

import com.formationtool.desktop.viewmodel.FormationViewModel;
import com.formationtool.desktop.utils.FFmpegUtil;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一的播放控制器
 *
 * 播放策略（参考剪映）：
 * - 有视频时：只播放视频（视频自带音轨），音频仅用于提取波形
 * - 只有音频时：才用音频播放器播放
 * 这样避免了双播放器同步问题，视频更流畅
 *
 * 性能优化：
 * - 自动重建机制：长时间使用后自动重建 MediaPlayer 防止性能退化
 * - 减少 play/pause 循环：暂停时直接 seek 刷新帧，不做 play/pause
 */
public class AudioPlaybackController {

    private final FormationViewModel viewModel;

    // 播放器实例
    private MediaPlayer audioPlayer;      // 音频播放器（仅在无视频时使用）
    private MediaPlayer videoPlayer;      // 视频播放器（优先使用）
    private MediaPlayer previewProxyPlayer; // 预览代理播放器（仅用于拖动预览）
    private MediaView boundVideoView;     // 绑定的视频视图（用于主/代理播放器切换）
    private ImageView boundPreviewImageView; // 绑定的视频预览图层（JavaCV拖动预览）
    private File previewProxyFile = null; // 预览代理文件引用
    private File currentVideoSourceFile = null; // 当前主视频源文件（JavaCV预览解码用）
    private File currentPreviewDecodeSourceFile = null; // 当前预览解码源（优先代理）
    private boolean hasVideo = false;     // 是否有视频（决定播放策略）

    // 音频文件引用（用于重建播放器）
    private File currentAudioFile = null;

    // 播放状态
    private AnimationTimer playbackTimer;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isSeeking = new AtomicBoolean(false);
    private final AtomicBoolean suppressPropertySeekSync = new AtomicBoolean(false);  // 防止seekTo触发属性监听重复seek
    private final AtomicLong lastSeekTime = new AtomicLong(0);  // 上次 seek 的时间戳（毫秒）
    private final AtomicBoolean isPreviewScrubbing = new AtomicBoolean(false);  // 是否处于拖动预览阶段
    private final AtomicLong pendingScrubTargetMs = new AtomicLong(-1L);  // 预览拖动待处理目标时间
    private final AtomicLong lastDispatchedScrubMs = new AtomicLong(-1L);  // 最近一次已派发预览时间
    private final AtomicLong previewSessionId = new AtomicLong(0L);  // 预览会话ID，用于丢弃过期任务
    private final AtomicBoolean isVideoReady = new AtomicBoolean(false);  // 视频是否完全准备好
    private final AtomicBoolean isProxyReady = new AtomicBoolean(false);  // 代理视频是否准备好
    private final AtomicBoolean isUsingProxyPreview = new AtomicBoolean(false); // 当前是否在使用代理预览
    private final ScheduledExecutorService scrubExecutor;
    private volatile ScheduledFuture<?> scrubDispatchFuture;
    private FFmpegFrameGrabber scrubFrameGrabber = null;
    private Java2DFrameConverter scrubFrameConverter = null;
    private String scrubGrabberSourcePath = null;

    // 性能优化：自动重建计数器
    private final AtomicInteger playCount = new AtomicInteger(0);      // 播放次数计数
    private final AtomicInteger seekCount = new AtomicInteger(0);      // seek 次数计数
    private static final int REBUILD_PLAY_THRESHOLD = 50;              // 播放50次后重建
    private static final int REBUILD_SEEK_THRESHOLD = 500;             // seek 500次后重建
    private final AtomicBoolean isRebuilding = new AtomicBoolean(false);

    // 视频帧刷新控制（避免频繁刷新）- 使用单例避免资源泄漏
    private PauseTransition frameRefreshDelay = null;
    private static final long SEEK_THROTTLE_MS = 80;  // seek 节流间隔（降低到80ms提升响应性）
    private static final long SCRUB_PROXY_DISPATCH_INTERVAL_MS = 100L;  // 代理模式追帧频率
    private static final long SCRUB_MAIN_DISPATCH_INTERVAL_MS = 160L;  // 主视频模式追帧频率
    private static final long SCRUB_MIN_DISPATCH_DELTA_MS = 120L;  // 连续派发最小时间差（毫秒）
    private static final long SCRUB_FFMPEG_TIMEOUT_MS = 1500L;  // FFmpeg单帧抽取超时（毫秒）
    private static final boolean ENABLE_JAVACV_SCRUB_PREVIEW = false;  // JavaCV预览路径（JDK25下默认关闭，避免本地崩溃）
    private static final boolean ENABLE_FFMPEG_PROCESS_SCRUB_PREVIEW = true;  // 外部FFmpeg帧预览路径
    private static final boolean ENABLE_SCRUB_DEBUG_LOG = false;  // 拖动调试日志开关
    private final AtomicLong scrubDispatchCount = new AtomicLong(0L);  // 已派发次数
    private final AtomicLong scrubDroppedCount = new AtomicLong(0L);  // 被丢弃次数
    private final AtomicLong scrubFinalSeekCount = new AtomicLong(0L);  // 最终seek次数

    // 回调接口
    private Runnable onPlaybackComplete;
    private java.util.function.Consumer<Long> onTimeUpdate;
    private java.util.function.Consumer<String> onError;
    private Runnable onAudioReady;

    public AudioPlaybackController(FormationViewModel viewModel) {
        this.viewModel = viewModel;
        this.scrubExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "PreviewScrubDispatcher");
            thread.setDaemon(true);
            return thread;
        });
        setupViewModelBinding();
    }

    /**
     * 绑定视频显示组件（用于主视频/代理视频的预览切换）
     */
    public void bindVideoView(MediaView videoView) {
        this.boundVideoView = videoView;
    }

    /**
     * 绑定拖动预览图层（JavaCV解码帧显示）
     */
    public void bindPreviewImageView(ImageView previewImageView) {
        this.boundPreviewImageView = previewImageView;
        runOnFxThread(() -> {
            if (boundPreviewImageView != null) {
                boundPreviewImageView.setVisible(false);
                boundPreviewImageView.setImage(null);
            }
        });
    }

    /**
     * 设置当前视频源文件（用于拖动预览解码）
     */
    public void setCurrentVideoSourceFile(File sourceFile) {
        this.currentVideoSourceFile = sourceFile;
    }

    /**
     * 绑定 ViewModel 的播放状态变化
     */
    private void setupViewModelBinding() {
        // 监听 ViewModel 的播放状态，统一响应
        viewModel.isPlayingProperty().addListener((obs, wasPlaying, shouldPlay) -> {
            System.out.println("[播放控制器] 状态变化: " + wasPlaying + " -> " + shouldPlay);

            if (shouldPlay && !isPlaying.get()) {
                doPlay();
            } else if (!shouldPlay && isPlaying.get()) {
                doPause();
            }
        });

        // 监听时间变化，暂停时实时刷新视频帧（降低频率避免卡顿）
        viewModel.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            // seekTo 内部已处理过视频定位时，跳过本监听，避免重复seek阻塞UI线程
            if (suppressPropertySeekSync.get()) {
                return;
            }
            if (isUsingProxyPreview.get()) {
                return;
            }

            // 只有在暂停状态且有视频时才刷新
            if (!isPlaying.get() && hasVideo && videoPlayer != null) {
                long newTimeMs = newTime.longValue();
                long now = System.currentTimeMillis();
                // 节流：每 SEEK_THROTTLE_MS 最多刷新一次
                if (now - lastSeekTime.get() > SEEK_THROTTLE_MS) {
                    lastSeekTime.set(now);
                    videoPlayer.seek(Duration.millis(newTimeMs));
                }
            }
        });

        System.out.println("[播放控制器] ViewModel绑定完成");
    }

    /**
     * 刷新视频帧（暂停时拖动进度条用）
     * 使用 PauseTransition 避免创建大量线程
     */
    private void refreshVideoFrame(long positionMs) {
        if (videoPlayer == null || isPlaying.get()) return;

        videoPlayer.seek(Duration.millis(positionMs));
        // 只需 seek 即可，JavaFX MediaPlayer 会自动刷新帧
        // 不需要 play/pause 循环
    }

    /**
     * 加载音频文件
     */
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

    /**
     * 创建音频播放器（内部方法，用于初始化和重建）
     */
    private void createAudioPlayer(File audioFile, boolean notifyReady) {
        try {
            String uri = audioFile.toURI().toString();
            Media media = new Media(uri);
            audioPlayer = new MediaPlayer(media);

            audioPlayer.setOnReady(() -> {
                long durationMs = (long) audioPlayer.getMedia().getDuration().toMillis();
                System.out.println("[播放控制器] 音频就绪，时长: " + durationMs + "ms");

                Platform.runLater(() -> {
                    viewModel.setAudioDuration(durationMs);
                    if (notifyReady && onAudioReady != null) {
                        onAudioReady.run();
                    }
                });
            });

            audioPlayer.setOnError(() -> {
                String errorMsg = audioPlayer.getError() != null ?
                    audioPlayer.getError().getMessage() : "未知错误";
                System.err.println("[播放控制器] 音频加载错误: " + errorMsg);
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(errorMsg));
                }
            });

            audioPlayer.setOnEndOfMedia(() -> {
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

    /**
     * 检查是否需要重建播放器（防止性能退化）
     */
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

    /**
     * 设置视频播放器
     * 重要：有视频时，视频是主播放器，音频播放器不播放
     */
    public void setVideoPlayer(MediaPlayer videoPlayer) {
        setVideoPlayer(videoPlayer, null);
    }

    /**
     * 设置视频播放器（带完成回调）
     * 重要：有视频时，视频是主播放器，音频播放器不播放
     *
     * 优化：导入视频后进行初始化 seek，确保播放器缓冲准备好
     * @param videoPlayer 视频播放器
     * @param onReady 播放器完全初始化后的回调
     */
    public void setVideoPlayer(MediaPlayer videoPlayer, Runnable onReady) {
        this.videoPlayer = videoPlayer;
        this.hasVideo = (videoPlayer != null);
        isUsingProxyPreview.set(false);
        clearPendingPreviewSeek();
        // 主视频切换时清空旧代理，避免跨视频复用
        disposePreviewProxy();

        // 重置状态
        playCount.set(0);
        seekCount.set(0);
        lastSeekTime.set(0);
        lastDispatchedScrubMs.set(-1L);
        isVideoReady.set(false);

        if (videoPlayer != null) {
            // 视频正常播放声音（不静音）
            videoPlayer.setVolume(1.0);
            System.out.println("[播放控制器] 视频播放器已设置，开始初始化...");

            // 关键优化：执行一次初始化 seek 到 0，让播放器预加载缓冲
            // 这样第一次用户拖动时间轴时，播放器已经准备好了
            Platform.runLater(() -> {
                try {
                    // 先暂停确保处于可控状态
                    videoPlayer.pause();

                    // seek 到起始位置，触发预加载
                    videoPlayer.seek(Duration.ZERO);

                    // 短暂播放一帧然后暂停，确保解码器完全初始化
                    // 使用 PauseTransition 延迟执行，避免阻塞
                    PauseTransition initDelay = new PauseTransition(Duration.millis(100));
                    initDelay.setOnFinished(e -> {
                        try {
                            videoPlayer.play();
                            // 再次短暂延迟后暂停
                            PauseTransition stopDelay = new PauseTransition(Duration.millis(50));
                            stopDelay.setOnFinished(ev -> {
                                videoPlayer.pause();
                                videoPlayer.seek(Duration.ZERO);
                                isVideoReady.set(true);
                                System.out.println("[播放控制器] 视频初始化完成，可以正常seek");
                                // 调用完成回调
                                if (onReady != null) {
                                    onReady.run();
                                }
                            });
                            stopDelay.play();
                        } catch (Exception ex) {
                            // 即使初始化失败，也标记为就绪
                            isVideoReady.set(true);
                            System.err.println("[播放控制器] 视频初始化警告: " + ex.getMessage());
                            if (onReady != null) {
                                onReady.run();
                            }
                        }
                    });
                    initDelay.play();

                } catch (Exception e) {
                    // 即使初始化失败，也标记为就绪
                    isVideoReady.set(true);
                    System.err.println("[播放控制器] 视频预加载异常: " + e.getMessage());
                    if (onReady != null) {
                        onReady.run();
                    }
                }
            });
        } else {
            isVideoReady.set(true);  // 没有视频时直接就绪
            if (onReady != null) {
                Platform.runLater(onReady);
            }
        }
    }

    /**
     * 设置预览代理视频文件（拖动时间轴时使用）
     */
    public void setPreviewProxyFile(File proxyFile) {
        if (proxyFile == null || !proxyFile.exists() || !proxyFile.isFile()) {
            disposePreviewProxy(false);
            return;
        }

        if (previewProxyFile != null
            && previewProxyFile.getAbsolutePath().equals(proxyFile.getAbsolutePath())
            && previewProxyPlayer != null
            && isProxyReady.get()) {
            return;
        }

        disposePreviewProxy(false);
        previewProxyFile = proxyFile;
        final String targetPath = proxyFile.getAbsolutePath();

        Platform.runLater(() -> {
            // 如果期间代理目标已变化，放弃当前创建
            if (previewProxyFile == null || !targetPath.equals(previewProxyFile.getAbsolutePath())) {
                return;
            }

            try {
                Media proxyMedia = new Media(proxyFile.toURI().toString());
                MediaPlayer proxyPlayer = new MediaPlayer(proxyMedia);
                proxyPlayer.setMute(true);
                proxyPlayer.setVolume(0.0);

                proxyPlayer.setOnReady(() -> {
                    // 防止快速切换视频时旧代理回调覆盖新代理状态
                    if (previewProxyFile == null || !targetPath.equals(previewProxyFile.getAbsolutePath())) {
                        try {
                            proxyPlayer.dispose();
                        } catch (Exception ignore) {
                            // 旧代理清理失败不影响主流程
                        }
                        return;
                    }
                    previewProxyPlayer = proxyPlayer;
                    isProxyReady.set(true);
                    System.out.println("[播放控制器] 代理预览视频就绪: " + proxyFile.getName());
                });

                proxyPlayer.setOnError(() -> {
                    String err = proxyPlayer.getError() != null ? proxyPlayer.getError().getMessage() : "未知错误";
                    System.err.println("[播放控制器] 代理预览视频错误: " + err);
                    if (previewProxyPlayer == proxyPlayer) {
                        previewProxyPlayer = null;
                    }
                    isProxyReady.set(false);
                    try {
                        proxyPlayer.dispose();
                    } catch (Exception ignore) {
                        // 代理播放器释放失败不影响主流程
                    }
                });
            } catch (Exception e) {
                System.err.println("[播放控制器] 创建代理预览播放器异常: " + e.getMessage());
                isProxyReady.set(false);
            }
        });
    }

    private void disposePreviewProxy() {
        disposePreviewProxy(true);
    }

    private void disposePreviewProxy(boolean clearScrubState) {
        isProxyReady.set(false);
        isUsingProxyPreview.set(false);
        if (clearScrubState) {
            clearPendingPreviewSeek();
        }
        previewProxyFile = null;

        MediaPlayer playerToDispose = previewProxyPlayer;
        previewProxyPlayer = null;

        Runnable switchBackTask = () -> {
            if (boundVideoView != null && playerToDispose != null && boundVideoView.getMediaPlayer() == playerToDispose) {
                if (videoPlayer != null) {
                    boundVideoView.setMediaPlayer(videoPlayer);
                } else {
                    boundVideoView.setMediaPlayer(null);
                }
            }
        };
        if (Platform.isFxApplicationThread()) {
            switchBackTask.run();
        } else {
            Platform.runLater(switchBackTask);
        }

        if (playerToDispose != null) {
            Platform.runLater(() -> {
                try {
                    playerToDispose.stop();
                    playerToDispose.dispose();
                } catch (Exception ignore) {
                    // 代理播放器释放失败不影响主流程
                }
            });
        }
    }

    private void switchToMainVideoViewIfNeeded() {
        isUsingProxyPreview.set(false);
        if (boundVideoView != null && videoPlayer != null && boundVideoView.getMediaPlayer() != videoPlayer) {
            boundVideoView.setMediaPlayer(videoPlayer);
        }
    }

    private boolean switchToProxyVideoViewIfPossible() {
        if (boundVideoView == null || previewProxyPlayer == null || !isProxyReady.get()) {
            return false;
        }
        if (boundVideoView.getMediaPlayer() != previewProxyPlayer) {
            boundVideoView.setMediaPlayer(previewProxyPlayer);
        }
        isUsingProxyPreview.set(true);
        return true;
    }

    /**
     * 播放（内部实现）
     * 策略：有视频只播视频，无视频才播音频
     */
    private void doPlay() {
        if (isPlaying.getAndSet(true)) {
            System.out.println("[播放控制器] 已经在播放中，忽略");
            return;
        }
        clearPendingPreviewSeek();

        // 增加播放计数
        playCount.incrementAndGet();

        long currentTime = viewModel.getCurrentTime();

        if (hasVideo && videoPlayer != null) {
            // 播放时始终切回主视频播放器
            switchToMainVideoViewIfNeeded();
            if (previewProxyPlayer != null) {
                previewProxyPlayer.pause();
            }

            // 有视频：只播放视频（视频自带音轨）
            System.out.println("[播放控制器] 执行播放（视频模式）");
            // 取消可能正在进行的帧刷新延迟
            if (frameRefreshDelay != null) {
                frameRefreshDelay.stop();
            }

            // 优化：先 seek，等待一帧后再播放，避免跳帧
            videoPlayer.seek(Duration.millis(currentTime));

            // 延迟一小段时间确保 seek 完成
            Platform.runLater(() -> {
                if (isPlaying.get() && videoPlayer != null) {
                    videoPlayer.play();
                    startPlaybackTimer();
                }
            });
        } else if (audioPlayer != null) {
            // 无视频：播放音频
            System.out.println("[播放控制器] 执行播放（音频模式）");

            // 优化：先 seek，等待一帧后再播放，避免跳帧
            audioPlayer.seek(Duration.millis(currentTime));

            // 延迟一小段时间确保 seek 完成
            Platform.runLater(() -> {
                if (isPlaying.get() && audioPlayer != null) {
                    audioPlayer.play();
                    startPlaybackTimer();

                    // 检查是否需要重建播放器
                    checkAndRebuildIfNeeded();
                }
            });
        } else {
            System.err.println("[播放控制器] 无法播放：没有可用的播放器");
            isPlaying.set(false);
            return;
        }
    }

    /**
     * 暂停（内部实现）
     */
    private void doPause() {
        if (!isPlaying.getAndSet(false)) {
            System.out.println("[播放控制器] 已经暂停，忽略");
            return;
        }

        System.out.println("[播放控制器] 执行暂停");

        // 停止定时器
        stopPlaybackTimer();

        // 暂停当前活动的播放器
        if (hasVideo && videoPlayer != null) {
            videoPlayer.pause();
        } else if (audioPlayer != null) {
            audioPlayer.pause();
        }

        // 检查是否需要重建播放器
        checkAndRebuildIfNeeded();
    }

    /**
     * 跳转到指定位置（暂停时也会刷新视频画面）
     * 优化：减少 play/pause 循环，直接 seek 刷新帧
     */
    public void seekTo(long positionMs) {
        clearPendingPreviewSeek();
        // 先做时间边界收敛，避免越界
        long safePositionMs = Math.max(0L, positionMs);
        long durationMs = viewModel.getAudioDuration();
        if (durationMs > 0) {
            safePositionMs = Math.min(safePositionMs, durationMs);
        }

        // 无论是否触发真实seek，都先更新ViewModel时间，保证时间轴/黄色标跟手
        suppressPropertySeekSync.set(true);
        try {
            viewModel.setCurrentTime(safePositionMs);
        } finally {
            suppressPropertySeekSync.set(false);
        }

        // 最终定位时切回主视频播放器并结束代理预览状态
        switchToMainVideoViewIfNeeded();
        if (previewProxyPlayer != null) {
            previewProxyPlayer.pause();
        }

        // 如果视频还没准备好，跳过 seek（避免首次拖动时音画不同步）
        if (hasVideo && !isVideoReady.get()) {
            System.out.println("[播放控制器] 视频未就绪，跳过seek");
            return;
        }

        // 节流：避免频繁 seek
        long now = System.currentTimeMillis();
        if (now - lastSeekTime.get() < SEEK_THROTTLE_MS) {
            return;
        }

        if (isSeeking.getAndSet(true)) {
            return;
        }

        lastSeekTime.set(now);
        seekCount.incrementAndGet();  // 增加 seek 计数

        try {
            // seek 当前活动的播放器
            if (hasVideo && videoPlayer != null) {
                // 优化：暂停时直接 seek，不做 play/pause 循环
                // JavaFX MediaPlayer 在暂停状态下 seek 会自动刷新帧
                videoPlayer.seek(Duration.millis(safePositionMs));

                // 只有在某些情况下（如首次加载后）才需要短暂 play 来刷新帧
                // 大多数情况 seek 已经足够
            } else if (audioPlayer != null) {
                audioPlayer.seek(Duration.millis(safePositionMs));
            }

            // 定期检查是否需要重建（每100次seek检查一次）
            if (seekCount.get() % 100 == 0) {
                checkAndRebuildIfNeeded();
            }

        } finally {
            isSeeking.set(false);
        }
    }

    /**
     * 开始拖动预览会话（黄线先跟手，视频低频追帧）
     */
    public void beginPreviewScrub() {
        if (isPlaying.get()) {
            return;
        }

        long session = previewSessionId.incrementAndGet();
        isPreviewScrubbing.set(true);
        pendingScrubTargetMs.set(-1L);
        lastDispatchedScrubMs.set(-1L);
        currentPreviewDecodeSourceFile = resolvePreviewDecodeSourceFile();
        hidePreviewImageLayer();

        // 拖动预览只暂停播放器，帧由JavaCV后台解码输出
        runOnFxThread(() -> {
            if (!isPreviewScrubbing.get() || previewSessionId.get() != session) {
                return;
            }

            if (previewProxyPlayer != null) {
                previewProxyPlayer.pause();
            }
            if (videoPlayer != null) {
                videoPlayer.pause();
            }
        });

        long dispatchIntervalMs = isUsingProxyDecodeSource(currentPreviewDecodeSourceFile)
            ? SCRUB_PROXY_DISPATCH_INTERVAL_MS
            : SCRUB_MAIN_DISPATCH_INTERVAL_MS;
        startPreviewScrubDispatcher(session, dispatchIntervalMs);
    }

    /**
     * 更新拖动目标时间（仅保留最新值）
     */
    public void updatePreviewScrubTarget(long positionMs) {
        if (!isPreviewScrubbing.get()) {
            return;
        }
        pendingScrubTargetMs.set(clampScrubPosition(positionMs));
    }

    /**
     * 结束拖动预览会话并执行最终精确定位
     */
    public void endPreviewScrub(long finalPositionMs) {
        clearPendingPreviewSeek();
        runOnFxThread(() -> {
            switchToMainVideoViewIfNeeded();
            if (previewProxyPlayer != null) {
                previewProxyPlayer.pause();
            }
        });
        scrubFinalSeekCount.incrementAndGet();
        seekTo(finalPositionMs);
        if (ENABLE_SCRUB_DEBUG_LOG) {
            System.out.println("[拖动预览] 结束会话: 派发=" + scrubDispatchCount.get()
                + ", 丢弃=" + scrubDroppedCount.get()
                + ", 最终seek=" + scrubFinalSeekCount.get());
        }
    }

    /**
     * 兼容旧预览接口：转发到新的拖动预览会话机制
     */
    public void previewSeekTo(long positionMs) {
        if (!isPreviewScrubbing.get()) {
            beginPreviewScrub();
        }
        updatePreviewScrubTarget(positionMs);
    }

    private void startPreviewScrubDispatcher(long sessionId, long intervalMs) {
        stopPreviewScrubDispatcher();
        scrubDispatchFuture = scrubExecutor.scheduleAtFixedRate(
            () -> dispatchPreviewScrubFrame(sessionId),
            0L,
            intervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void stopPreviewScrubDispatcher() {
        ScheduledFuture<?> future = scrubDispatchFuture;
        scrubDispatchFuture = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    private void dispatchPreviewScrubFrame(long sessionId) {
        if (!isPreviewScrubbing.get() || previewSessionId.get() != sessionId || isPlaying.get()) {
            return;
        }

        long nextTargetMs = pendingScrubTargetMs.getAndSet(-1L);
        if (nextTargetMs < 0L) {
            return;
        }

        long lastTargetMs = lastDispatchedScrubMs.get();
        if (lastTargetMs >= 0L && Math.abs(nextTargetMs - lastTargetMs) < SCRUB_MIN_DISPATCH_DELTA_MS) {
            scrubDroppedCount.incrementAndGet();
            return;
        }

        lastDispatchedScrubMs.set(nextTargetMs);
        scrubDispatchCount.incrementAndGet();

        File decodeSource = currentPreviewDecodeSourceFile;
        if (decodeSource != null && decodeSource.exists() && decodeSource.isFile()) {
            boolean rendered = false;
            if (ENABLE_JAVACV_SCRUB_PREVIEW) {
                rendered = decodeAndRenderPreviewFrame(sessionId, decodeSource, nextTargetMs);
            } else if (ENABLE_FFMPEG_PROCESS_SCRUB_PREVIEW) {
                rendered = decodeAndRenderPreviewFrameByFFmpeg(sessionId, decodeSource, nextTargetMs);
            }
            if (!rendered) {
                Platform.runLater(() -> applyPreviewScrubSeek(sessionId, nextTargetMs));
            }
        } else {
            // 无可用解码源时回退到旧链路
            Platform.runLater(() -> applyPreviewScrubSeek(sessionId, nextTargetMs));
        }
    }

    private void applyPreviewScrubSeek(long sessionId, long targetMs) {
        if (!isPreviewScrubbing.get() || previewSessionId.get() != sessionId || isPlaying.get()) {
            return;
        }

        try {
            if (boundPreviewImageView != null && boundPreviewImageView.isVisible()) {
                boundPreviewImageView.setVisible(false);
                boundPreviewImageView.setImage(null);
            }

            MediaPlayer targetPlayer = null;
            if (switchToProxyVideoViewIfPossible()) {
                targetPlayer = previewProxyPlayer;
            } else if (hasVideo && videoPlayer != null && isVideoReady.get()) {
                switchToMainVideoViewIfNeeded();
                targetPlayer = videoPlayer;
            }

            if (targetPlayer != null) {
                targetPlayer.seek(Duration.millis(targetMs));
            }
        } catch (Exception e) {
            System.err.println("[拖动预览] 追帧seek异常: " + e.getMessage());
        }
    }

    private boolean decodeAndRenderPreviewFrame(long sessionId, File sourceFile, long targetMs) {
        if (!isPreviewScrubbing.get() || previewSessionId.get() != sessionId) {
            return false;
        }

        try {
            ensureScrubFrameGrabber(sourceFile);
            if (scrubFrameGrabber == null) {
                scrubDroppedCount.incrementAndGet();
                return false;
            }

            scrubFrameGrabber.setTimestamp(targetMs * 1000L);
            Frame frame = scrubFrameGrabber.grabImage();
            if (frame == null) {
                frame = scrubFrameGrabber.grabFrame();
            }
            if (frame == null || scrubFrameConverter == null) {
                scrubDroppedCount.incrementAndGet();
                return false;
            }

            BufferedImage bufferedImage = scrubFrameConverter.convert(frame);
            if (bufferedImage == null) {
                scrubDroppedCount.incrementAndGet();
                return false;
            }

            Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);
            Platform.runLater(() -> showPreviewImageForScrub(sessionId, fxImage));
            return true;
        } catch (Exception e) {
            scrubDroppedCount.incrementAndGet();
            System.err.println("[拖动预览] JavaCV解码异常: " + e.getMessage());
            closeScrubFrameGrabber();
            return false;
        }
    }

    /**
     * 使用外部FFmpeg进程抽取单帧并渲染到预览图层
     */
    private boolean decodeAndRenderPreviewFrameByFFmpeg(long sessionId, File sourceFile, long targetMs) {
        if (!isPreviewScrubbing.get() || previewSessionId.get() != sessionId || sourceFile == null) {
            return false;
        }

        String ffmpegPath = FFmpegUtil.getFFmpegPath();
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            scrubDroppedCount.incrementAndGet();
            return false;
        }

        long safeTargetMs = Math.max(0L, targetMs);
        String targetSecondsText = String.format(Locale.US, "%.3f", safeTargetMs / 1000.0);

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-ss");
        command.add(targetSecondsText);
        command.add("-i");
        command.add(sourceFile.getAbsolutePath());
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        command.add("-frames:v");
        command.add("1");
        command.add("-vf");
        command.add("scale=640:-2");
        command.add("-f");
        command.add("image2pipe");
        command.add("-vcodec");
        command.add("mjpeg");
        command.add("-q:v");
        command.add("4");
        command.add("pipe:1");

        Process process = null;
        Thread outputReaderThread = null;
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(128 * 1024);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = processBuilder.start();

            Process runningProcess = process;
            outputReaderThread = new Thread(() -> {
                try (InputStream inputStream = runningProcess.getInputStream()) {
                    byte[] readBuffer = new byte[8192];
                    int readLength;
                    while ((readLength = inputStream.read(readBuffer)) != -1) {
                        synchronized (outputBuffer) {
                            outputBuffer.write(readBuffer, 0, readLength);
                        }
                    }
                } catch (IOException ignored) {
                    // 进程销毁或流提前关闭时会进入该分支，属于可接受情况
                }
            }, "PreviewFfmpegReader");
            outputReaderThread.setDaemon(true);
            outputReaderThread.start();

            boolean finished = process.waitFor(SCRUB_FFMPEG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                scrubDroppedCount.incrementAndGet();
                return false;
            }

            if (outputReaderThread.isAlive()) {
                outputReaderThread.join(150L);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                scrubDroppedCount.incrementAndGet();
                return false;
            }

            byte[] imageBytes;
            synchronized (outputBuffer) {
                imageBytes = outputBuffer.toByteArray();
            }
            if (imageBytes.length == 0) {
                scrubDroppedCount.incrementAndGet();
                return false;
            }
            if (!isPreviewScrubbing.get() || previewSessionId.get() != sessionId || isPlaying.get()) {
                return false;
            }

            Image fxImage = new Image(new ByteArrayInputStream(imageBytes));
            if (fxImage.isError() || fxImage.getWidth() <= 0 || fxImage.getHeight() <= 0) {
                scrubDroppedCount.incrementAndGet();
                return false;
            }

            Platform.runLater(() -> showPreviewImageForScrub(sessionId, fxImage));
            return true;
        } catch (Exception e) {
            scrubDroppedCount.incrementAndGet();
            System.err.println("[拖动预览] FFmpeg抽帧异常: " + e.getMessage());
            return false;
        } finally {
            if (outputReaderThread != null && outputReaderThread.isAlive()) {
                outputReaderThread.interrupt();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                outputBuffer.close();
            } catch (IOException ignore) {
                // ByteArrayOutputStream关闭失败可忽略
            }
        }
    }

    private void showPreviewImageForScrub(long sessionId, Image image) {
        if (!isPreviewScrubbing.get() || previewSessionId.get() != sessionId) {
            return;
        }
        if (boundPreviewImageView == null || image == null) {
            return;
        }
        boundPreviewImageView.setImage(image);
        boundPreviewImageView.setVisible(true);
    }

    private void hidePreviewImageLayer() {
        runOnFxThread(() -> {
            if (boundPreviewImageView != null) {
                boundPreviewImageView.setVisible(false);
                boundPreviewImageView.setImage(null);
            }
        });
    }

    private File resolvePreviewDecodeSourceFile() {
        if (previewProxyFile != null && previewProxyFile.exists() && previewProxyFile.isFile()) {
            return previewProxyFile;
        }
        if (currentVideoSourceFile != null && currentVideoSourceFile.exists() && currentVideoSourceFile.isFile()) {
            return currentVideoSourceFile;
        }
        return null;
    }

    private boolean isUsingProxyDecodeSource(File sourceFile) {
        return sourceFile != null
            && previewProxyFile != null
            && sourceFile.getAbsolutePath().equals(previewProxyFile.getAbsolutePath());
    }

    private void ensureScrubFrameGrabber(File sourceFile) throws Exception {
        if (sourceFile == null) {
            closeScrubFrameGrabber();
            return;
        }

        String sourcePath = sourceFile.getAbsolutePath();
        if (scrubFrameGrabber != null && sourcePath.equals(scrubGrabberSourcePath)) {
            return;
        }

        closeScrubFrameGrabber();
        scrubFrameGrabber = new FFmpegFrameGrabber(sourceFile);
        scrubFrameGrabber.setOption("threads", "1");
        scrubFrameGrabber.start();
        scrubFrameConverter = new Java2DFrameConverter();
        scrubGrabberSourcePath = sourcePath;
    }

    private void closeScrubFrameGrabber() {
        if (scrubFrameGrabber != null) {
            try {
                scrubFrameGrabber.stop();
            } catch (Exception ignore) {
                // 停止失败不影响后续清理
            }
            try {
                scrubFrameGrabber.release();
            } catch (Exception ignore) {
                // 释放失败不影响主流程
            }
            scrubFrameGrabber = null;
        }
        if (scrubFrameConverter != null) {
            scrubFrameConverter.close();
            scrubFrameConverter = null;
        }
        scrubGrabberSourcePath = null;
    }

    private long clampScrubPosition(long positionMs) {
        long safePositionMs = Math.max(0L, positionMs);
        long durationMs = viewModel.getAudioDuration();
        if (durationMs > 0) {
            safePositionMs = Math.min(safePositionMs, durationMs);
        }
        return safePositionMs;
    }

    private void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private void clearPendingPreviewSeek() {
        isPreviewScrubbing.set(false);
        pendingScrubTargetMs.set(-1L);
        lastDispatchedScrubMs.set(-1L);
        previewSessionId.incrementAndGet();
        stopPreviewScrubDispatcher();
        currentPreviewDecodeSourceFile = null;
        closeScrubFrameGrabber();
        hidePreviewImageLayer();
    }

    /**
     * 启动播放计时器
     */
    private void startPlaybackTimer() {
        stopPlaybackTimer();

        playbackTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!isPlaying.get()) return;

                // 从当前活动的播放器获取时间
                long currentMs = 0;
                if (hasVideo && videoPlayer != null) {
                    currentMs = (long) videoPlayer.getCurrentTime().toMillis();
                } else if (audioPlayer != null) {
                    currentMs = (long) audioPlayer.getCurrentTime().toMillis();
                }

                // 更新 ViewModel 时间
                viewModel.setCurrentTime(currentMs);

                // 回调时间更新
                if (onTimeUpdate != null) {
                    onTimeUpdate.accept(currentMs);
                }

                // 检查播放完成
                long duration = viewModel.getAudioDuration();
                if (duration > 0 && currentMs >= duration - 100) {
                    Platform.runLater(() -> onPlaybackEnded());
                }
            }
        };
        playbackTimer.start();
        System.out.println("[播放控制器] 计时器已启动");
    }

    /**
     * 停止播放计时器
     */
    private void stopPlaybackTimer() {
        if (playbackTimer != null) {
            playbackTimer.stop();
            playbackTimer = null;
            System.out.println("[播放控制器] 计时器已停止");
        }
    }

    /**
     * 播放结束处理
     */
    private void onPlaybackEnded() {
        System.out.println("[播放控制器] 播放结束");

        isPlaying.set(false);
        stopPlaybackTimer();

        // 重置当前活动的播放器
        if (hasVideo && videoPlayer != null) {
            videoPlayer.stop();
            videoPlayer.seek(Duration.ZERO);
        } else if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer.seek(Duration.ZERO);
        }

        viewModel.setCurrentTime(0);
        viewModel.setPlaying(false);

        if (onPlaybackComplete != null) {
            onPlaybackComplete.run();
        }
    }

    /**
     * 释放音频资源
     */
    public void disposeAudio() {
        System.out.println("[播放控制器] 释放音频资源");

        stopPlaybackTimer();
        isPlaying.set(false);

        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer.dispose();
            audioPlayer = null;
        }
    }

    /**
     * 释放视频资源
     */
    public void disposeVideo() {
        disposeVideo(null);
    }

    /**
     * 释放视频资源（带回调）
     * @param onDisposed 释放完成后的回调，可以为null
     */
    public void disposeVideo(Runnable onDisposed) {
        System.out.println("[播放控制器] 释放视频资源");
        disposePreviewProxy();

        if (videoPlayer != null) {
            MediaPlayer playerToDispose = videoPlayer;
            videoPlayer = null;
            hasVideo = false;
            isVideoReady.set(false);

            // 使用原子标志防止重复回调
            final AtomicBoolean callbackExecuted = new AtomicBoolean(false);

            Runnable safeCallback = () -> {
                if (callbackExecuted.compareAndSet(false, true) && onDisposed != null) {
                    System.out.println("[播放控制器] 执行释放完成回调");
                    onDisposed.run();
                }
            };

            // 监听播放器状态，等待完全释放
            playerToDispose.statusProperty().addListener((obs, oldStatus, newStatus) -> {
                System.out.println("[播放控制器] 视频播放器状态: " + oldStatus + " -> " + newStatus);
                if (newStatus == MediaPlayer.Status.DISPOSED) {
                    System.out.println("[播放控制器] 视频资源已完全释放");
                    Platform.runLater(safeCallback);
                }
            });

            // 执行释放
            playerToDispose.stop();
            playerToDispose.dispose();

            // 超时保底（某些情况下 DISPOSED 状态可能不触发）
            if (onDisposed != null) {
                PauseTransition timeout = new PauseTransition(Duration.millis(500));
                timeout.setOnFinished(e -> safeCallback.run());
                timeout.play();
            }
        } else {
            hasVideo = false;
            isVideoReady.set(false);
            // 没有视频需要释放，直接回调
            if (onDisposed != null) {
                Platform.runLater(onDisposed);
            }
        }
    }

    /**
     * 释放所有资源
     */
    public void dispose() {
        // 停止帧刷新延迟
        if (frameRefreshDelay != null) {
            frameRefreshDelay.stop();
            frameRefreshDelay = null;
        }
        clearPendingPreviewSeek();
        disposeAudio();
        disposeVideo();
        scrubExecutor.shutdownNow();
    }

    // ===== Getter/Setter =====

    public MediaPlayer getAudioPlayer() {
        return audioPlayer;
    }

    public MediaPlayer getVideoPlayer() {
        return videoPlayer;
    }

    public boolean isPlaying() {
        return isPlaying.get();
    }

    public void setOnPlaybackComplete(Runnable callback) {
        this.onPlaybackComplete = callback;
    }

    public void setOnTimeUpdate(java.util.function.Consumer<Long> callback) {
        this.onTimeUpdate = callback;
    }

    public void setOnError(java.util.function.Consumer<String> callback) {
        this.onError = callback;
    }
}
