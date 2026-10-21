package com.formationtool.desktop.view;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.function.LongFunction;

public final class MainLayoutFactory {
    public static final class MenuBuildResult {
        private final MenuBar menuBar;
        private final MenuItem importAudioMenuItem;
        private final MenuItem importVideoMenuItem;

        public MenuBuildResult(MenuBar menuBar, MenuItem importAudioMenuItem, MenuItem importVideoMenuItem) {
            this.menuBar = menuBar;
            this.importAudioMenuItem = importAudioMenuItem;
            this.importVideoMenuItem = importVideoMenuItem;
        }

        public MenuBar getMenuBar() {
            return menuBar;
        }

        public MenuItem getImportAudioMenuItem() {
            return importAudioMenuItem;
        }

        public MenuItem getImportVideoMenuItem() {
            return importVideoMenuItem;
        }
    }

    public static final class VideoSectionResult {
        private final StackPane videoSection;
        private final ImageView previewImageView;

        public VideoSectionResult(StackPane videoSection, ImageView previewImageView) {
            this.videoSection = videoSection;
            this.previewImageView = previewImageView;
        }

        public StackPane getVideoSection() {
            return videoSection;
        }

        public ImageView getPreviewImageView() {
            return previewImageView;
        }
    }

    public static final class BottomBarResult {
        private final HBox controlBar;
        private final Button playPauseButton;
        private final Label statusLabel;

        public BottomBarResult(HBox controlBar, Button playPauseButton, Label statusLabel) {
            this.controlBar = controlBar;
            this.playPauseButton = playPauseButton;
            this.statusLabel = statusLabel;
        }

        public HBox getControlBar() {
            return controlBar;
        }

        public Button getPlayPauseButton() {
            return playPauseButton;
        }

        public Label getStatusLabel() {
            return statusLabel;
        }
    }

    private MainLayoutFactory() {
    }

    public static MenuBuildResult createMenuBar(
        Runnable onBackToList,
        Runnable onSaveProject,
        Runnable onExportProject,
        Runnable onImportProject,
        Runnable onImportAudio,
        Runnable onImportVideo,
        Runnable onReloadCurrentVideo,
        Runnable onClearMedia,
        Runnable onExit,
        Runnable onApplyMusPreset,
        Runnable onApplyAqoursPreset,
        Runnable onApplyNijigasakiPreset,
        Runnable onApplyLiellasPreset,
        Runnable onApplyHasunosoraPreset,
        Runnable onManageCustomPresets,
        Runnable onShowStageSizeDialog,
        Runnable onCheckForUpdates,
        Runnable onShowAboutDialog
    ) {
        MenuBar menuBar = new MenuBar();
        menuBar.setUseSystemMenuBar(false);

        Menu fileMenu = new Menu("文件");

        MenuItem backToList = new MenuItem("返回工程列表");
        MenuItem saveProject = new MenuItem("保存工程");
        saveProject.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+S"));
        MenuItem exportProject = new MenuItem("导出工程");
        MenuItem importProject = new MenuItem("导入工程");
        MenuItem importAudioMenuItem = new MenuItem("导入音频");
        MenuItem importVideoMenuItem = new MenuItem("导入视频");
        MenuItem reloadVideoMenuItem = new MenuItem("重新加载视频");
        MenuItem clearMedia = new MenuItem("清除媒体文件");
        MenuItem exit = new MenuItem("退出");

        backToList.setOnAction(e -> onBackToList.run());
        saveProject.setOnAction(e -> onSaveProject.run());
        exportProject.setOnAction(e -> onExportProject.run());
        importProject.setOnAction(e -> onImportProject.run());
        importAudioMenuItem.setOnAction(e -> onImportAudio.run());
        importVideoMenuItem.setOnAction(e -> onImportVideo.run());
        reloadVideoMenuItem.setOnAction(e -> onReloadCurrentVideo.run());
        clearMedia.setOnAction(e -> onClearMedia.run());
        exit.setOnAction(e -> onExit.run());

        fileMenu.getItems().addAll(
            backToList,
            saveProject,
            exportProject,
            importProject,
            new javafx.scene.control.SeparatorMenuItem(),
            importAudioMenuItem,
            importVideoMenuItem,
            reloadVideoMenuItem,
            clearMedia,
            new javafx.scene.control.SeparatorMenuItem(),
            exit
        );

        Menu presetsMenu = new Menu("预设");
        MenuItem musPreset = new MenuItem("μ's（9人）");
        MenuItem aqoursPreset = new MenuItem("Aqours（9人）");
        MenuItem nijigasakiPreset = new MenuItem("虹咲学园（13人）");
        MenuItem liellasPreset = new MenuItem("Liella!（11人）");
        MenuItem hasunosoraPreset = new MenuItem("莲之空（11人）");

        musPreset.setOnAction(e -> onApplyMusPreset.run());
        aqoursPreset.setOnAction(e -> onApplyAqoursPreset.run());
        nijigasakiPreset.setOnAction(e -> onApplyNijigasakiPreset.run());
        liellasPreset.setOnAction(e -> onApplyLiellasPreset.run());
        hasunosoraPreset.setOnAction(e -> onApplyHasunosoraPreset.run());

        presetsMenu.getItems().addAll(
            musPreset,
            aqoursPreset,
            nijigasakiPreset,
            liellasPreset,
            hasunosoraPreset
        );

        MenuItem customPresets = new MenuItem("自定义预设…");
        customPresets.setOnAction(event -> onManageCustomPresets.run());
        presetsMenu.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), customPresets);

        Menu settingsMenu = new Menu("设置");
        MenuItem stageSizeMenuItem = new MenuItem("舞台尺寸设置");
        stageSizeMenuItem.setOnAction(e -> onShowStageSizeDialog.run());
        settingsMenu.getItems().add(stageSizeMenuItem);

        Menu helpMenu = new Menu("帮助");
        MenuItem checkUpdates = new MenuItem("检查更新");
        MenuItem about = new MenuItem("关于");
        checkUpdates.setOnAction(e -> onCheckForUpdates.run());
        about.setOnAction(e -> onShowAboutDialog.run());
        MenuItem openLogs = new MenuItem("打开日志文件夹");
        openLogs.setOnAction(event -> DiagnosticDialogs.openLogFolder());
        helpMenu.getItems().addAll(checkUpdates, openLogs, new javafx.scene.control.SeparatorMenuItem(), about);

        menuBar.getMenus().addAll(fileMenu, presetsMenu, settingsMenu, helpMenu);

        return new MenuBuildResult(menuBar, importAudioMenuItem, importVideoMenuItem);
    }

    public static HBox createTopBar(
        MenuBar menuBar,
        Runnable onShowExportDialog,
        Runnable onUndo,
        Runnable onRedo
    ) {
        Button undoButton = new Button("↶");
        undoButton.setTooltip(new Tooltip("撤销（Ctrl+Z）"));
        undoButton.setOnAction(e -> onUndo.run());
        undoButton.setStyle(
            "-fx-background-color: #2A2A3F;" +
            "-fx-text-fill: #D8DBF2;" +
            "-fx-font-size: 16px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 4 10;" +
            "-fx-background-radius: 6;"
        );
        undoButton.setOnMouseEntered(e -> undoButton.setStyle(
            "-fx-background-color: #363654;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 16px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 4 10;" +
            "-fx-background-radius: 6;"
        ));
        undoButton.setOnMouseExited(e -> undoButton.setStyle(
            "-fx-background-color: #2A2A3F;" +
            "-fx-text-fill: #D8DBF2;" +
            "-fx-font-size: 16px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 4 10;" +
            "-fx-background-radius: 6;"
        ));

        Button redoButton = new Button("↷");
        redoButton.setTooltip(new Tooltip("重做（Ctrl+Y）"));
        redoButton.setOnAction(e -> onRedo.run());
        redoButton.setStyle(
            "-fx-background-color: #2A2A3F;" +
            "-fx-text-fill: #D8DBF2;" +
            "-fx-font-size: 16px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 4 10;" +
            "-fx-background-radius: 6;"
        );
        redoButton.setOnMouseEntered(e -> redoButton.setStyle(
            "-fx-background-color: #363654;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 16px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 4 10;" +
            "-fx-background-radius: 6;"
        ));
        redoButton.setOnMouseExited(e -> redoButton.setStyle(
            "-fx-background-color: #2A2A3F;" +
            "-fx-text-fill: #D8DBF2;" +
            "-fx-font-size: 16px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 4 10;" +
            "-fx-background-radius: 6;"
        ));

        Button exportButton = new Button("导出视频");
        exportButton.getStyleClass().add("export-button");
        exportButton.setOnAction(e -> onShowExportDialog.run());
        exportButton.setStyle(
            "-fx-background-color: #0084FF;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 8 20;" +
            "-fx-background-radius: 6;"
        );

        exportButton.setOnMouseEntered(e -> exportButton.setStyle(
            "-fx-background-color: #0078E7;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 8 20;" +
            "-fx-background-radius: 6;"
        ));
        exportButton.setOnMouseExited(e -> exportButton.setStyle(
            "-fx-background-color: #0084FF;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 8 20;" +
            "-fx-background-radius: 6;"
        ));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox menuActionBox = new HBox(6);
        menuActionBox.setAlignment(Pos.CENTER_LEFT);
        menuActionBox.getChildren().addAll(menuBar, undoButton, redoButton);

        HBox topBar = new HBox(10);
        topBar.setPadding(new Insets(5, 10, 5, 10));
        topBar.setStyle("-fx-background-color: #1E1E2E;");
        topBar.getChildren().addAll(menuActionBox, spacer, exportButton);

        return topBar;
    }

    public static VideoSectionResult createVideoSection(FormationViewModel viewModel, AudioPlaybackController playbackController) {
        StackPane videoSection = new StackPane();
        videoSection.setStyle("-fx-background-color: #1E1E2E; -fx-border-color: #2A2A3F; -fx-border-width: 1; -fx-background-radius: 5; -fx-border-radius: 5;");
        videoSection.setPadding(new Insets(10));

        ImageView previewImageView = new ImageView();
        previewImageView.setPreserveRatio(true);
        // 视频图层参与布局，在可用区域内居中并保持原始比例。
        previewImageView.setManaged(true);
        previewImageView.setVisible(false);
        previewImageView.setMouseTransparent(true);
        previewImageView.setSmooth(true);
        StackPane.setAlignment(previewImageView, Pos.CENTER);
        playbackController.bindPreviewImageView(previewImageView);

        Label title = new Label("参考视频");
        title.setTextFill(Color.WHITE);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        StackPane.setAlignment(title, Pos.TOP_CENTER);
        StackPane.setMargin(title, new Insets(5, 0, 0, 0));

        StackPane videoContainer = new StackPane();
        videoContainer.setStyle("-fx-background-color: black; -fx-background-radius: 3;");
        StackPane.setMargin(videoContainer, new Insets(35, 10, 10, 10));

        Label noVideoLabel = new Label("未导入视频\n点击菜单 → 文件 → 导入视频");
        noVideoLabel.setId("video-empty-placeholder");
        noVideoLabel.setMouseTransparent(true);
        noVideoLabel.setTextFill(Color.rgb(136, 136, 153));
        noVideoLabel.setStyle("-fx-font-size: 14px; -fx-text-alignment: center;");
        noVideoLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        videoContainer.getChildren().addAll(previewImageView, noVideoLabel);

        videoContainer.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            double containerWidth = newBounds.getWidth();
            double containerHeight = newBounds.getHeight();
            if (containerWidth > 50 && containerHeight > 50) {
                double targetFitW = containerWidth - 20;
                double targetFitH = containerHeight - 20;
                if (Math.abs(previewImageView.getFitWidth() - targetFitW) > 1.0
                        || Math.abs(previewImageView.getFitHeight() - targetFitH) > 1.0) {
                    previewImageView.setFitWidth(targetFitW);
                    previewImageView.setFitHeight(targetFitH);
                }
            }
        });

        // 两个视频后端共用图像图层，空状态直接跟随实际画面。
        noVideoLabel.visibleProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !previewImageView.isVisible() || previewImageView.getImage() == null,
                previewImageView.visibleProperty(), previewImageView.imageProperty()));
        noVideoLabel.managedProperty().bind(noVideoLabel.visibleProperty());

        Button mirror = new Button("左右镜像");
        mirror.setTooltip(new Tooltip("仅镜像参考视频，随工程保存"));
        StackPane.setAlignment(mirror, Pos.TOP_RIGHT);
        Runnable refreshMirror = () -> {
            boolean enabled = viewModel.getCurrentGroup() != null && viewModel.getCurrentGroup().isReferenceVideoMirrored();
            previewImageView.setScaleX(enabled ? -1 : 1);
            mirror.setText(enabled ? "取消镜像" : "左右镜像");
        };
        mirror.setOnAction(event -> {
            if (viewModel.getCurrentGroup() == null) return;
            viewModel.getCurrentGroup().setReferenceVideoMirrored(!viewModel.getCurrentGroup().isReferenceVideoMirrored());
            viewModel.markGroupChanged();
            refreshMirror.run();
        });
        viewModel.currentGroupProperty().addListener((obs, previous, current) -> refreshMirror.run());
        refreshMirror.run();
        videoSection.getChildren().addAll(videoContainer, title, mirror);

        return new VideoSectionResult(videoSection, previewImageView);
    }

    public static BottomBarResult createBottomControlBar(
        FormationViewModel viewModel,
        Runnable onStartPlayback,
        Runnable onPausePlayback,
        LongFunction<String> durationFormatter
    ) {
        HBox controlBar = new HBox(20);
        controlBar.setPadding(new Insets(10));
        controlBar.setStyle("-fx-background-color: #1E1E2E;");
        controlBar.setAlignment(Pos.CENTER_LEFT);

        HBox playbackBox = new HBox(10);
        playbackBox.setAlignment(Pos.CENTER_LEFT);

        Button playPauseButton = new Button("▶");
        playPauseButton.setStyle("-fx-font-size: 24px; -fx-min-width: 60px; -fx-min-height: 50px;");

        playPauseButton.setOnAction(e -> {
            if (viewModel.isPlaying()) {
                onPausePlayback.run();
            } else {
                onStartPlayback.run();
            }
        });

        viewModel.isPlayingProperty().addListener((obs, wasPlaying, isPlaying) -> {
            playPauseButton.setText(isPlaying ? "⏸" : "▶");
        });

        Label timeLabel = new Label("00:00 / 00:00");
        timeLabel.setTextFill(Color.WHITE);
        timeLabel.setStyle("-fx-font-size: 16px; -fx-font-family: monospace;");

        viewModel.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            long current = newTime.longValue();
            long total = viewModel.getAudioDuration();
            timeLabel.setText(durationFormatter.apply(current) + " / " + durationFormatter.apply(total));
        });
        viewModel.audioDurationProperty().addListener((obs, oldDur, newDur) -> {
            long current = viewModel.getCurrentTime();
            long total = newDur.longValue();
            timeLabel.setText(durationFormatter.apply(current) + " / " + durationFormatter.apply(total));
        });

        playbackBox.getChildren().addAll(playPauseButton, timeLabel);

        Label statusLabel = new Label("就绪");
        statusLabel.setTextFill(Color.WHITE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        statusLabel.setAlignment(Pos.CENTER_RIGHT);

        controlBar.getChildren().addAll(playbackBox, statusLabel);

        return new BottomBarResult(controlBar, playPauseButton, statusLabel);
    }
}
