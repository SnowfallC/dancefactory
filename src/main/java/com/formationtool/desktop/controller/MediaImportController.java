package com.formationtool.desktop.controller;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;

public final class MediaImportController {
    @FunctionalInterface
    public interface ErrorHandler {
        void show(String title, String message);
    }

    private volatile File activeVideoFile;
    private long videoSession;
    private final FormationViewModel viewModel;
    private final DataManager dataManager;
    private final AudioPlaybackController playbackController;
    private final Label statusLabel;
    private final long mediaLoadTimeoutMs;
    private final ErrorHandler errorHandler;
    private final Function<File, File> audioConverter;
    private final Function<File, File> audioExtractor;
    private final Consumer<File> waveformExtractor;
    private final Runnable mediaMenuStateUpdater;
    private final Consumer<File> currentAudioFileSetter;
    private final Consumer<File> currentVideoFileSetter;
    private final LongFunction<String> durationFormatter;

    public MediaImportController(
        FormationViewModel viewModel,
        DataManager dataManager,
        AudioPlaybackController playbackController,
        Label statusLabel,
        long mediaLoadTimeoutMs,
        ErrorHandler errorHandler,
        Function<File, File> audioConverter,
        Function<File, File> audioExtractor,
        Consumer<File> waveformExtractor,
        Runnable mediaMenuStateUpdater,
        Consumer<File> currentAudioFileSetter,
        Consumer<File> currentVideoFileSetter,
        LongFunction<String> durationFormatter
    ) {
        this.viewModel = Objects.requireNonNull(viewModel);
        this.dataManager = Objects.requireNonNull(dataManager);
        this.playbackController = Objects.requireNonNull(playbackController);
        this.statusLabel = Objects.requireNonNull(statusLabel);
        this.mediaLoadTimeoutMs = mediaLoadTimeoutMs;
        this.errorHandler = Objects.requireNonNull(errorHandler);
        this.audioConverter = Objects.requireNonNull(audioConverter);
        this.audioExtractor = Objects.requireNonNull(audioExtractor);
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
        activeVideoFile = videoFile;
        System.out.println("[DEBUG] 开始加载视频: " + videoFile.getAbsolutePath());
        System.out.println("[DEBUG] 文件大小: " + videoFile.length() + " bytes");
        System.out.println("[DEBUG] 文件存在: " + videoFile.exists());

        long session = ++videoSession;
        viewModel.setPlaying(false);
        long expectedRevision = playbackController.getVideoRevision() + 1;
        playbackController.disposeVideo(() -> {
            if (session != videoSession || playbackController.getVideoRevision() != expectedRevision || !videoFile.equals(activeVideoFile)) return;
            String backend = System.getProperty("formation.video.backend", "mpv");
            if ("ffmpeg".equals(backend)) {
                loadSoftwareVideo(videoFile, onComplete, "使用兼容播放");

            } else {
                statusLabel.setText("正在载入视频...");
                playbackController.loadNativeVideo(videoFile, () -> {
                    if (session != videoSession) return;
                    currentVideoFileSetter.accept(videoFile);
                    DanceGroup group = viewModel.getCurrentGroup();
                    if (group != null) group.setReferenceVideoPath(videoFile.getAbsolutePath());
                    statusLabel.setText("已载入视频: " + videoFile.getName());
                    mediaMenuStateUpdater.run();
                    if (onComplete != null) onComplete.run();
                }, error -> {
                    if (session != videoSession || !videoFile.equals(activeVideoFile)) return;
                    System.err.println("[播放后端] libmpv 失败，切换 FFmpeg: " + error);
                    long fallbackRevision = playbackController.getVideoRevision() + 1;
                    playbackController.disposeVideo(() -> {
                        if (session == videoSession && playbackController.getVideoRevision() == fallbackRevision
                                && videoFile.equals(activeVideoFile)) loadSoftwareVideo(videoFile, onComplete, error);
                    });
                });
            }
        });
    }

    private void loadSoftwareVideo(File file, Runnable onComplete, String nativeError) {
        long session = videoSession;
        long revision = playbackController.getVideoRevision();
        statusLabel.setText("正在准备兼容播放...");
        playbackController.disposeAudio();
        Thread prepare = new Thread(() -> {
            File audio = null;
            try { audio = audioExtractor.apply(file); }
            catch (Exception error) { System.err.println("[兼容播放] 音频提取失败: " + error); }
            File preparedAudio = audio;
            Platform.runLater(() -> {
                if (session != videoSession || playbackController.getVideoRevision() != revision || !file.equals(activeVideoFile)) return;
                if (preparedAudio != null && preparedAudio.isFile()) {
                    playbackController.loadAudio(preparedAudio,
                        () -> { if (session == videoSession && playbackController.getVideoRevision() == revision) startSoftwareVideo(file, onComplete, nativeError); },
                        error -> {
                            if (session != videoSession || playbackController.getVideoRevision() != revision) return;
                            errorHandler.show("兼容播放音频加载失败", error);
                            statusLabel.setText("视频导入失败");
                        });
                } else startSoftwareVideo(file, onComplete, nativeError);
            });
        }, "FallbackAudioPreparation");
        prepare.setDaemon(true);
        prepare.start();
    }

    private void startSoftwareVideo(File file, Runnable onComplete, String nativeError) {
        long session = videoSession;
        statusLabel.setText("正在使用兼容播放载入视频...");
        playbackController.loadSoftwareVideo(file, () -> {
            if (session != videoSession || !file.equals(activeVideoFile)) return;
            currentVideoFileSetter.accept(file);
            DanceGroup group = viewModel.getCurrentGroup();
            if (group != null) group.setReferenceVideoPath(file.getAbsolutePath());
            statusLabel.setText("已载入视频（兼容播放）: " + file.getName());
            mediaMenuStateUpdater.run();
            if (onComplete != null) onComplete.run();
        }, error -> {
            if (session != videoSession || !file.equals(activeVideoFile)) return;
            playbackController.disposeVideo();
            currentVideoFileSetter.accept(null);
            statusLabel.setText("视频导入失败");
            mediaMenuStateUpdater.run();
            com.formationtool.desktop.media.VideoDiagnostics.logDecodeFailure(file);
            errorHandler.show("无法载入视频", "主播放内核和兼容播放均未能加载该视频。\n\n"
                + "主播放内核: " + nativeError + "\nFFmpeg: " + error
                + "\n文件: " + file.getAbsolutePath());
        });
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
        activeVideoFile = null;
        CountDownLatch released = new CountDownLatch(1);
        viewModel.setPlaying(false);
        playbackController.disposeVideo(released::countDown);

        Thread loadThread = new Thread(() -> {
            try {
                if (!released.await(mediaLoadTimeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("旧视频资源释放超时，请重试导入");
                }
                // 原片直接交给独立播放内核，音频提取仅用于波形及兼容播放。
                final File finalVideoFile = file;
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

}
