package com.formationtool.desktop.controller;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;

public final class MediaImportController {
    /**
     * JavaFX supports codecs, not file extensions.  In particular, an .mp4
     * container may contain HEVC, AV1, or 10-bit H.264 that Windows Media
     * Foundation cannot play through JavaFX.
     */
    private static final class VideoCompatibility {
        private final boolean supported;
        private final String issue;

        private VideoCompatibility(boolean supported, String issue) {
            this.supported = supported;
            this.issue = issue;
        }
    }

    @FunctionalInterface
    public interface ErrorHandler {
        void show(String title, String message);
    }

    private final FormationViewModel viewModel;
    private final DataManager dataManager;
    private final AudioPlaybackController playbackController;
    private final MediaView videoView;
    private final Label statusLabel;
    private final long mediaLoadTimeoutMs;
    private final ErrorHandler errorHandler;
    private final Function<File, File> audioConverter;
    private final Function<File, File> audioExtractor;
    private final Function<File, File> videoConverter;
    private final Function<File, File> previewProxyGenerator;
    private final Consumer<File> waveformExtractor;
    private final Runnable mediaMenuStateUpdater;
    private final Consumer<File> currentAudioFileSetter;
    private final Consumer<File> currentVideoFileSetter;
    private final LongFunction<String> durationFormatter;

    public MediaImportController(
        FormationViewModel viewModel,
        DataManager dataManager,
        AudioPlaybackController playbackController,
        MediaView videoView,
        Label statusLabel,
        long mediaLoadTimeoutMs,
        ErrorHandler errorHandler,
        Function<File, File> audioConverter,
        Function<File, File> audioExtractor,
        Function<File, File> videoConverter,
        Function<File, File> previewProxyGenerator,
        Consumer<File> waveformExtractor,
        Runnable mediaMenuStateUpdater,
        Consumer<File> currentAudioFileSetter,
        Consumer<File> currentVideoFileSetter,
        LongFunction<String> durationFormatter
    ) {
        this.viewModel = Objects.requireNonNull(viewModel);
        this.dataManager = Objects.requireNonNull(dataManager);
        this.playbackController = Objects.requireNonNull(playbackController);
        this.videoView = Objects.requireNonNull(videoView);
        this.statusLabel = Objects.requireNonNull(statusLabel);
        this.mediaLoadTimeoutMs = mediaLoadTimeoutMs;
        this.errorHandler = Objects.requireNonNull(errorHandler);
        this.audioConverter = Objects.requireNonNull(audioConverter);
        this.audioExtractor = Objects.requireNonNull(audioExtractor);
        this.videoConverter = Objects.requireNonNull(videoConverter);
        this.previewProxyGenerator = Objects.requireNonNull(previewProxyGenerator);
        this.waveformExtractor = Objects.requireNonNull(waveformExtractor);
        this.mediaMenuStateUpdater = Objects.requireNonNull(mediaMenuStateUpdater);
        this.currentAudioFileSetter = Objects.requireNonNull(currentAudioFileSetter);
        this.currentVideoFileSetter = Objects.requireNonNull(currentVideoFileSetter);
        this.durationFormatter = Objects.requireNonNull(durationFormatter);
    }

    public void importAudio(Stage stage) {
        if (viewModel.getCurrentGroup() == null) {
            errorHandler.show("导入失败", "请先创建或打开一个工程");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择音频文件");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(
                "音频文件",
                "*.mp3", "*.wav", "*.m4a", "*.flac", "*.aac",
                "*.ogg", "*.wma", "*.ape", "*.opus", "*.alac",
                "*.aiff", "*.amr", "*.ac3"
            ),
            new FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        File file = fileChooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }

        statusLabel.setText("正在检测音频格式: " + file.getName() + "...");

        Thread loadThread = new Thread(() -> {
            try {
                String fileName = file.getName().toLowerCase();
                boolean isNativelySupported = fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".m4a");
                boolean needsConversion = false;

                if (!isNativelySupported) {
                    final CountDownLatch latch = new CountDownLatch(1);
                    final boolean[] loadSuccess = {false};
                    final String[] errorMessage = {null};

                    Platform.runLater(() -> {
                        try {
                            String uri = file.toURI().toString();
                            Media testMedia = new Media(uri);
                            MediaPlayer testPlayer = new MediaPlayer(testMedia);

                            testPlayer.setOnReady(() -> {
                                loadSuccess[0] = true;
                                testPlayer.dispose();
                                latch.countDown();
                            });

                            testPlayer.setOnError(() -> {
                                MediaException error = testPlayer.getError();
                                errorMessage[0] = error.getMessage();
                                loadSuccess[0] = false;
                                testPlayer.dispose();
                                latch.countDown();
                            });

                            new Thread(() -> {
                                try {
                                    Thread.sleep(mediaLoadTimeoutMs);
                                    if (latch.getCount() > 0) {
                                        errorMessage[0] = "Media load timeout (" + (mediaLoadTimeoutMs / 1000) + "s)";
                                        loadSuccess[0] = false;
                                        System.err.println("[ERROR] MediaPlayer创建超时");
                                        latch.countDown();
                                    }
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }).start();

                        } catch (Exception e) {
                            errorMessage[0] = e.getMessage();
                            loadSuccess[0] = false;
                            latch.countDown();
                        }
                    });

                    latch.await();

                    if (!loadSuccess[0]) {
                        needsConversion = true;
                        System.out.println("[音频导入] 检测到不支持的格式: " + errorMessage[0]);

                        final boolean[] userConfirmed = {false};
                        final CountDownLatch confirmLatch = new CountDownLatch(1);

                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("音频格式不支持");
                            alert.setHeaderText("检测到音频格式可能不受支持");
                            alert.setContentText(
                                "当前音频格式: " + fileName.substring(fileName.lastIndexOf(".")) + "\n" +
                                "错误信息: " + (errorMessage[0] != null ? errorMessage[0] : "未知错误") + "\n\n" +
                                "是否将音频转换为 WAV 格式？\n" +
                                "(转换可能需要一些时间)"
                            );

                            ButtonType buttonTypeYes = new ButtonType("转换", ButtonBar.ButtonData.YES);
                            ButtonType buttonTypeNo = new ButtonType("取消", ButtonBar.ButtonData.NO);
                            alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);

                            Optional<ButtonType> result = alert.showAndWait();
                            userConfirmed[0] = result.isPresent() && result.get() == buttonTypeYes;
                            confirmLatch.countDown();
                        });

                        confirmLatch.await();

                        if (!userConfirmed[0]) {
                            Platform.runLater(() -> statusLabel.setText("已取消导入音频"));
                            return;
                        }
                    }
                }

                File audioToLoad = file;
                if (needsConversion || !isNativelySupported) {
                    System.out.println("[音频导入] 开始转换音频格式: " + file.getName());
                    Platform.runLater(() -> statusLabel.setText("正在转换音频格式: " + file.getName() + "..."));
                    audioToLoad = audioConverter.apply(file);
                    if (audioToLoad == null) {
                        Platform.runLater(() -> {
                            errorHandler.show("导入失败", "音频格式转换失败，请检查文件是否损坏。");
                            statusLabel.setText("音频导入失败");
                        });
                        return;
                    }
                    System.out.println("[音频导入] 音频转换成功: " + audioToLoad.getName());
                }

                final File finalAudioFile = audioToLoad;
                final File originalFile = file;

                Platform.runLater(() -> {
                    try {
                        currentAudioFileSetter.accept(finalAudioFile);

                        playbackController.loadAudio(finalAudioFile,
                            () -> {
                                long millis = viewModel.getAudioDuration();

                                DanceGroup group = viewModel.getCurrentGroup();
                                if (group != null) {
                                    group.setMusicPath(finalAudioFile.getAbsolutePath());
                                    dataManager.copyAudioToProject(group.getId(), finalAudioFile);
                                    dataManager.saveProject(group);
                                }

                                statusLabel.setText("已载入音频: " + originalFile.getName() + " (时长: " + durationFormatter.apply(millis) + ")");
                                waveformExtractor.accept(finalAudioFile);
                                mediaMenuStateUpdater.run();
                            },
                            (errorMsg) -> {
                                System.err.println("[ERROR] 音频加载错误: " + errorMsg);
                                errorHandler.show("音频加载失败", "无法播放音频: " + errorMsg);
                            }
                        );

                    } catch (Exception ex) {
                        errorHandler.show("导入失败", "加载音频时出错: " + ex.getMessage());
                    }
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    errorHandler.show("导入失败", "处理音频时出错: " + ex.getMessage());
                });
            }
        });
        loadThread.setDaemon(true);
        loadThread.start();
    }

    public void loadImportedAudio(File audioFile) {
        try {
            System.out.println("[DEBUG] 开始加载音频: " + audioFile.getAbsolutePath());
            System.out.println("[DEBUG] 文件大小: " + audioFile.length() + " bytes");
            System.out.println("[DEBUG] 文件存在: " + audioFile.exists());

            String fileName = audioFile.getName().toLowerCase();
            String extension = "";
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) {
                extension = fileName.substring(lastDot + 1);
            }
            System.out.println("[DEBUG] 音频格式: " + extension);

            File targetAudioFile = audioFile;
            if (!extension.equals("wav")) {
                System.out.println("[INFO] 检测到非-WAV音频格式 (" + extension + ")，尝试转换为WAV...");
                targetAudioFile = audioConverter.apply(audioFile);
                if (targetAudioFile == null) {
                    System.err.println("[错误] 音频转换失败，跳过音频加载");
                    return;
                }
                System.out.println("[INFO] 音频转换成功: " + targetAudioFile.getAbsolutePath());
            }

            currentAudioFileSetter.accept(targetAudioFile);

            final File finalAudioFile = audioFile;
            final File finalTargetFile = targetAudioFile;

            playbackController.loadAudio(targetAudioFile,
                () -> {
                    System.out.println("[DEBUG] 音频准备就绪回调被触发");

                    DanceGroup group = viewModel.getCurrentGroup();
                    if (group != null) {
                        group.setMusicPath(finalTargetFile.getAbsolutePath());
                    }

                    long duration = viewModel.getAudioDuration();
                    statusLabel.setText("已载入音频: " + finalAudioFile.getName() + " (时长: " + durationFormatter.apply(duration) + ")");
                    waveformExtractor.accept(finalTargetFile);
                    mediaMenuStateUpdater.run();
                },
                (errorMsg) -> {
                    System.err.println("[ERROR] 音频加载错误: " + errorMsg);
                }
            );

            System.out.println("[DEBUG] 音频加载流程初始化完成，等待onReady回调");

        } catch (Exception ex) {
            System.err.println("[ERROR] 加载音频异常: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public void loadImportedVideo(File videoFile) {
        loadImportedVideo(videoFile, null);
    }

    public void loadImportedVideo(File videoFile, Runnable onComplete) {
        System.out.println("[DEBUG] 开始加载视频: " + videoFile.getAbsolutePath());
        System.out.println("[DEBUG] 文件大小: " + videoFile.length() + " bytes");
        System.out.println("[DEBUG] 文件存在: " + videoFile.exists());
        playbackController.setCurrentVideoSourceFile(videoFile);  // 注册当前视频源，供JavaCV拖动预览解码使用
        playbackController.setPreviewProxyFile(null);  // 切换新视频前先清空旧代理

        playbackController.disposeVideo(() -> {
            doLoadVideoWithRetry(videoFile, onComplete, 0, 3);
        });
    }

    private void doLoadVideoWithRetry(File videoFile, Runnable onComplete, int attempt, int maxAttempts) {
        System.out.println("[DEBUG] 加载视频尝试 " + (attempt + 1) + "/" + maxAttempts);

        doLoadVideo(videoFile, onComplete, (errorMsg) -> {
            if (attempt + 1 < maxAttempts) {
                int delayMs = 500 * (attempt + 1);
                System.out.println("[DEBUG] 加载失败，" + delayMs + "ms 后重试...");

                PauseTransition retry = new PauseTransition(Duration.millis(delayMs));
                retry.setOnFinished(e -> doLoadVideoWithRetry(videoFile, onComplete, attempt + 1, maxAttempts));
                retry.play();
            } else {
                System.err.println("[ERROR] 视频加载失败，已重试" + maxAttempts + "次");
                errorHandler.show(
                    "无法播放视频",
                    "视频文件可能损坏或格式不兼容。\n\n" +
                    "错误信息: " + errorMsg + "\n\n" +
                    "建议：\n" +
                    "1. 尝试重新导入视频\n" +
                    "2. 使用格式转换工具转换为标准MP4格式\n" +
                    "3. 检查视频文件是否完整"
                );
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    private void doLoadVideo(File videoFile, Runnable onComplete) {
        doLoadVideo(videoFile, onComplete, null);
    }

    private void doLoadVideo(File videoFile, Runnable onComplete, Consumer<String> onError) {
        System.out.println("[DEBUG] doLoadVideo 开始执行，线程: " + Thread.currentThread().getName());

        if (!Platform.isFxApplicationThread()) {
            System.out.println("[DEBUG] 不在JavaFX线程，转入Platform.runLater");
            Platform.runLater(() -> doLoadVideo(videoFile, onComplete, onError));
            return;
        }

        try {
            currentVideoFileSetter.accept(videoFile);
            String uri = videoFile.toURI().toString();
            System.out.println("[DEBUG] 视频URI: " + uri);

            Media media = new Media(uri);
            MediaPlayer newVideoPlayer = new MediaPlayer(media);
            System.out.println("[DEBUG] MediaPlayer 已创建，等待 onReady...");

            final AtomicBoolean callbackExecuted = new AtomicBoolean(false);

            Runnable safeComplete = () -> {
                if (callbackExecuted.compareAndSet(false, true) && onComplete != null) {
                    onComplete.run();
                }
            };

            newVideoPlayer.setOnReady(() -> {
                System.out.println("[DEBUG] 视频播放器就绪 (onReady)");
                playbackController.setVideoPlayer(newVideoPlayer, () -> {
                    System.out.println("[DEBUG] 视频播放器预热完成，设置到 videoView");
                    playbackController.setCurrentVideoSourceFile(videoFile);
                    videoView.setMediaPlayer(newVideoPlayer);

                    DanceGroup group = viewModel.getCurrentGroup();
                    if (group != null) {
                        group.setReferenceVideoPath(videoFile.getAbsolutePath());
                    }

                    statusLabel.setText("已载入视频: " + videoFile.getName());
                    // 后台生成预览代理，不阻塞主视频加载
                    buildPreviewProxyAsync(videoFile);
                    System.out.println("[DEBUG] 视频加载完成");
                    safeComplete.run();
                });
            });

            newVideoPlayer.setOnError(() -> {
                MediaException error = newVideoPlayer.getError();
                final String errorMsg;
                final String errorType;

                if (error != null) {
                    errorMsg = error.getMessage();
                    errorType = error.getType() != null ? error.getType().toString() : "NULL";
                } else {
                    errorMsg = "未知错误";
                    errorType = "UNKNOWN";
                }

                System.err.println("[ERROR] 视频加载错误: " + errorMsg);
                System.err.println("[ERROR] 错误类型: " + errorType);
                System.err.println("[ERROR] 视频文件: " + videoFile.getAbsolutePath());
                System.err.println("[ERROR] 文件大小: " + videoFile.length() + " bytes");

                try {
                    newVideoPlayer.dispose();
                } catch (Exception e) {
                    System.err.println("[ERROR] dispose异常: " + e.getMessage());
                }

                if (onError != null) {
                    callbackExecuted.set(true);
                    Platform.runLater(() -> onError.accept(errorMsg));
                } else {
                    Platform.runLater(() -> {
                        errorHandler.show(
                            "无法播放视频",
                            "视频文件可能损坏或格式不兼容。\n\n" +
                            "错误信息: " + errorMsg + "\n" +
                            "错误类型: " + errorType + "\n\n" +
                            "建议：\n" +
                            "1. 尝试重新导入视频\n" +
                            "2. 使用格式转换工具转换为标准MP4格式\n" +
                            "3. 检查视频文件是否完整"
                        );
                        safeComplete.run();
                    });
                }
            });

            PauseTransition timeout = new PauseTransition(Duration.seconds(10));
            timeout.setOnFinished(e -> {
                if (callbackExecuted.get()) {
                    return;
                }

                System.err.println("[ERROR] 视频加载超时 (10秒)");
                System.err.println("[ERROR] 视频文件: " + videoFile.getAbsolutePath());

                try {
                    newVideoPlayer.dispose();
                } catch (Exception ex) {
                    System.err.println("[ERROR] 超时dispose异常: " + ex.getMessage());
                }

                if (onError != null) {
                    callbackExecuted.set(true);
                    onError.accept("加载超时");
                } else {
                    safeComplete.run();
                }
            });
            timeout.play();

        } catch (Exception ex) {
            System.err.println("[ERROR] 加载视频异常: " + ex.getMessage());
            ex.printStackTrace();

            if (onError != null) {
                Platform.runLater(() -> onError.accept(ex.getMessage()));
            } else {
                Platform.runLater(() -> {
                    errorHandler.show(
                        "视频加载失败",
                        "无法创建视频播放器。\n\n" +
                        "错误: " + ex.getMessage() + "\n\n" +
                        "请尝试重新导入视频。"
                    );

                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        }
    }

    /**
     * 后台生成预览代理视频（用于拖动时间轴时的低开销预览）
     */
    private void buildPreviewProxyAsync(File sourceVideo) {
        if (sourceVideo == null || !sourceVideo.exists()) {
            return;
        }

        Thread proxyThread = new Thread(() -> {
            try {
                File proxyFile = previewProxyGenerator.apply(sourceVideo);
                if (proxyFile != null && proxyFile.exists() && proxyFile.length() > 0) {
                    playbackController.setPreviewProxyFile(proxyFile);
                    System.out.println("[代理视频] 已注册代理预览: " + proxyFile.getName());
                } else {
                    System.out.println("[代理视频] 代理生成失败，继续使用原视频预览");
                }
            } catch (Exception e) {
                System.err.println("[代理视频] 代理生成线程异常: " + e.getMessage());
            }
        }, "ProxyVideoBuilder");
        proxyThread.setDaemon(true);
        proxyThread.start();
    }

    public void importVideo(Stage owner) {
        if (viewModel.getCurrentGroup() == null) {
            errorHandler.show("导入失败", "请先创建或打开一个工程");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择视频文件");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(
                "视频文件",
                "*.mp4", "*.mov", "*.mkv", "*.avi", "*.flv",
                "*.wmv", "*.m4v", "*.3gp", "*.webm", "*.vob",
                "*.mpg", "*.mpeg", "*.ts", "*.mts", "*.m2ts"
            ),
            new FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        File file = fileChooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }

        statusLabel.setText("正在检测视频格式: " + file.getName() + "...");

        Thread loadThread = new Thread(() -> {
            try {
                String fileName = file.getName().toLowerCase();
                boolean needsConversion = false;
                String detectionIssue = null;

                // Follow the same principle as OpenCut's media pipeline: decide
                // from the actual stream decoder, not from the filename suffix.
                // This also prevents silent videos from being treated as invalid.
                VideoCompatibility compatibility = probeJavaFxVideo(file);
                if (!compatibility.supported) {
                    needsConversion = true;
                    detectionIssue = compatibility.issue;
                    System.out.println("[视频导入] JavaFX 无法解码视频: " + detectionIssue);
                }

                if (needsConversion) {
                    final boolean[] userConfirmed = {false};
                    final CountDownLatch confirmLatch = new CountDownLatch(1);
                    final String finalIssue = detectionIssue;

                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("视频格式问题");
                        alert.setHeaderText("检测到视频可能存在兼容性问题");
                        alert.setContentText(
                            "当前视频: " + file.getName() + "\n" +
                            "格式: " + fileName.substring(fileName.lastIndexOf(".")) + "\n" +
                            "问题: " + finalIssue + "\n\n" +
                            "是否将视频重新编码为标准 MP4 格式？\n" +
                            "(转换可能需要一些时间)\n" +
                            "转换后的视频将包含兼容的音视频编码。"
                        );

                        ButtonType buttonTypeYes = new ButtonType("转换", ButtonBar.ButtonData.YES);
                        ButtonType buttonTypeNo = new ButtonType("取消", ButtonBar.ButtonData.NO);
                        alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);

                        Optional<ButtonType> result = alert.showAndWait();
                        userConfirmed[0] = result.isPresent() && result.get() == buttonTypeYes;
                        confirmLatch.countDown();
                    });

                    confirmLatch.await();

                    if (!userConfirmed[0]) {
                        Platform.runLater(() -> statusLabel.setText("已取消导入视频"));
                        return;
                    }
                }

                File videoToLoad = file;
                if (needsConversion) {
                    System.out.println("[视频导入] 开始转换视频格式: " + file.getName());
                    Platform.runLater(() -> statusLabel.setText("正在转换视频格式: " + file.getName() + "..."));
                    videoToLoad = videoConverter.apply(file);
                    if (videoToLoad == null) {
                        Platform.runLater(() -> {
                            errorHandler.show("导入失败", "视频格式转换失败，请检查文件是否损坏。");
                            statusLabel.setText("视频导入失败");
                        });
                        return;
                    }
                    System.out.println("[视频导入] 视频转换成功: " + videoToLoad.getName());

                    VideoCompatibility convertedCompatibility = probeJavaFxVideo(videoToLoad);
                    if (!convertedCompatibility.supported) {
                        final String convertedIssue = convertedCompatibility.issue;
                        Platform.runLater(() -> {
                            errorHandler.show(
                                "视频转换后仍无法播放",
                                "已生成兼容 MP4，但当前系统的视频解码组件仍无法加载它。\n\n" +
                                "错误信息: " + convertedIssue
                            );
                            statusLabel.setText("视频导入失败");
                        });
                        return;
                    }
                }

                final File finalVideoFile = videoToLoad;
                File extractedAudio = audioExtractor.apply(finalVideoFile);

                Platform.runLater(() -> {
                    try {
                        loadImportedVideo(finalVideoFile, () -> {
                            System.out.println("[视频导入] 视频播放器初始化完成");

                            DanceGroup group = viewModel.getCurrentGroup();
                            if (group != null) {
                                File copiedVideo = dataManager.copyVideoToProject(group.getId(), finalVideoFile);
                                System.out.println("[视频导入] 视频已复制到工程文件夹: " +
                                    (copiedVideo != null ? copiedVideo.getAbsolutePath() : "null"));
                            }

                            statusLabel.setText("已载入视频: " + file.getName());
                            mediaMenuStateUpdater.run();

                            if (extractedAudio != null && extractedAudio.exists()) {
                                System.out.println("[视频导入] 开始加载提取的音频...");
                                loadImportedAudio(extractedAudio);

                                if (group != null) {
                                    File copiedAudio = dataManager.copyAudioToProject(group.getId(), extractedAudio);
                                    System.out.println("[视频导入] 音频已复制到工程文件夹: " +
                                        (copiedAudio != null ? copiedAudio.getAbsolutePath() : "null"));
                                    dataManager.saveProject(group);
                                }

                                statusLabel.setText("已载入视频和音频: " + file.getName());
                            } else {
                                System.out.println("[视频导入] 无法提取音频或视频不包含音频轨");
                                statusLabel.setText("已载入视频: " + file.getName() + " (未检测到音频轨)");

                                if (group != null) {
                                    dataManager.saveProject(group);
                                }
                                mediaMenuStateUpdater.run();
                            }
                        });

                    } catch (Exception ex) {
                        errorHandler.show("导入失败", "加载视频时出错: " + ex.getMessage());
                    }
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    errorHandler.show("导入失败", "处理视频时出错: " + ex.getMessage());
                });
            }
        });
        loadThread.setDaemon(true);
        loadThread.start();
    }

    /**
     * Ask the same JavaFX pipeline used by the preview to load the file.  This
     * is deliberately run for MP4 too: MP4 identifies only the container, not
     * a playable codec combination.
     */
    private VideoCompatibility probeJavaFxVideo(File videoFile) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean supported = new AtomicBoolean(false);
        AtomicReference<String> issue = new AtomicReference<>("未知错误");
        AtomicReference<MediaPlayer> playerRef = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                MediaPlayer player = new MediaPlayer(new Media(videoFile.toURI().toString()));
                playerRef.set(player);

                player.setOnReady(() -> {
                    supported.set(true);
                    finishVideoProbe(player, finished, latch);
                });
                player.setOnError(() -> {
                    MediaException error = player.getError();
                    issue.set(error != null && error.getMessage() != null ? error.getMessage() : "JavaFX 无法解码该视频");
                    finishVideoProbe(player, finished, latch);
                });
            } catch (Exception e) {
                issue.set(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                if (finished.compareAndSet(false, true)) {
                    latch.countDown();
                }
            }
        });

        if (!latch.await(mediaLoadTimeoutMs, TimeUnit.MILLISECONDS)) {
            issue.set("Media load timeout (" + (mediaLoadTimeoutMs / 1000) + "s)");
            if (finished.compareAndSet(false, true)) {
                MediaPlayer player = playerRef.get();
                if (player != null) {
                    Platform.runLater(() -> disposeMediaPlayer(player));
                }
            }
        }
        return new VideoCompatibility(supported.get(), issue.get());
    }

    private static void finishVideoProbe(MediaPlayer player, AtomicBoolean finished, CountDownLatch latch) {
        if (finished.compareAndSet(false, true)) {
            disposeMediaPlayer(player);
            latch.countDown();
        }
    }

    private static void disposeMediaPlayer(MediaPlayer player) {
        try {
            player.dispose();
        } catch (Exception ignored) {
            // A completed probe can race with the JavaFX media shutdown path.
        }
    }
}
