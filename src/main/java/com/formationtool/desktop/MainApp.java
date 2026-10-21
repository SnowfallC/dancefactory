package com.formationtool.desktop;

import com.formationtool.desktop.audio.AudioPlaybackController;
import com.formationtool.desktop.controller.KeyframeController;
import com.formationtool.desktop.controller.DesktopDialogController;
import com.formationtool.desktop.controller.ExportFlowController;
import com.formationtool.desktop.controller.FormationActionController;
import com.formationtool.desktop.controller.KeyboardShortcutController;
import com.formationtool.desktop.controller.MediaCleanupController;
import com.formationtool.desktop.controller.MediaImportController;
import com.formationtool.desktop.controller.ProjectFileController;
import com.formationtool.desktop.controller.MediaRestoreController;
import com.formationtool.desktop.controller.ProjectSessionController;
import com.formationtool.desktop.controller.TeamPresetController;
import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.media.MediaProcessingService;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Formation;
import com.formationtool.desktop.view.ControlPanelFactory;
import com.formationtool.desktop.view.MainLayoutFactory;
import com.formationtool.desktop.view.StageView;
import com.formationtool.desktop.view.TimelineView;
import com.formationtool.desktop.viewmodel.FormationViewModel;
import com.formationtool.desktop.model.ExportConfig;
import com.formationtool.desktop.update.UpdateChecker;
import com.formationtool.desktop.update.UpdateDownloader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * 主应用程序
 */
public class MainApp extends Application {

    private static final String OFFICIAL_DOWNLOAD_URL = "https://dfworkshop.top/";

    // 静态变量用于工程切换
    public static String selectedProjectId = null;

    private FormationViewModel viewModel;
    private DataManager dataManager;
    private StageView stageView;
    private TimelineView timelineView;
    private Label statusLabel;
    private ListView<String> dancerListView;
    private ListView<String> formationListView;

    // 统一播放控制器（所有播放操作都通过此控制器）
    private AudioPlaybackController playbackController;

    // 关键帧控制器
    private KeyframeController keyframeController;

    // 媒体文件引用
    private File currentAudioFile;
    private File currentVideoFile;
    private ImageView previewImageView;

    // 播放控制
    private Button playPauseButton;  // 合并的播放/暂停按钮

    // 菜单项引用（用于禁用/启用）
    private MenuItem importAudioMenuItem;
    private MenuItem importVideoMenuItem;

    // 导出进度窗口组件
    private ExportFlowController exportFlowController;
    private DesktopDialogController desktopDialogController;
    private MediaImportController mediaImportController;
    private ProjectFileController projectFileController;
    private ProjectSessionController projectSessionController;
    private TeamPresetController teamPresetController;
    private FormationActionController formationActionController;
    private KeyboardShortcutController keyboardShortcutController;
    private MediaRestoreController mediaRestoreController;
    private MediaProcessingService mediaProcessingService;

    // 自动保存定时器（30秒一次）
    private java.util.Timer autoSaveTimer;
    private static final long AUTO_SAVE_INTERVAL = 30000; // 30秒
    private static final long MEDIA_LOAD_TIMEOUT_MS = 80_000L; // 80秒

    @Override
    public void init() {
        com.formationtool.desktop.utils.AppLog.install();
    }

    @Override
    public void start(Stage primaryStage) {
        viewModel = new FormationViewModel();
        dataManager = new DataManager();

        // 初始化统一播放控制器
        playbackController = new AudioPlaybackController(viewModel);

        // 设置播放完成回调，处理F键标记结束（播放到音频尾端）
        playbackController.setOnPlaybackComplete(() -> {
            System.out.println("[播放完成] 音频播放到尾端");
            // 如果正在F键扫描序列中，触发结束逻辑（创建结尾关键帧）
            if (keyframeController != null && keyframeController.isInScanSequence()) {
                System.out.println("[播放完成] 检测到F键扫描序列，触发结束逻辑");
                keyframeController.handleFKeyMarkOnPause();
            }
        });

        System.out.println("[初始化] 播放控制器已创建");

        // 如果没有选中工程，先显示工程列表
        if (selectedProjectId == null) {
            showProjectListWindow(primaryStage);
            return;
        }

        // 加载选中的工程
        loadProjectAndShow(primaryStage, selectedProjectId);
    }

    /**
     * 显示工程列表窗口
     */
    private void showProjectListWindow(Stage primaryStage) {
        ProjectListWindow projectList = new ProjectListWindow(primaryStage, dataManager);
        projectList.setOnProjectSelected(() -> {
            // 用户选择了工程，重新启动主窗口
            loadProjectAndShow(primaryStage, selectedProjectId);
        });
        projectList.show();
    }

    /**
     * 加载工程并显示主窗口
     */
    private void loadProjectAndShow(Stage primaryStage, String projectId) {
        try {
            System.out.println("[工程加载] 开始加载工程 ID: " + projectId);
            exportFlowController = null;
            desktopDialogController = null;
            mediaImportController = null;
            projectFileController = null;
            projectSessionController = null;
            teamPresetController = null;
            formationActionController = null;
            keyboardShortcutController = null;
            mediaRestoreController = null;
            mediaProcessingService = null;

            // 获取屏幕尺寸
            Screen screen = Screen.getPrimary();
            Rectangle2D bounds = screen.getVisualBounds();

            // === 主布局：使用 VBox + HBox 代替 BorderPane ===
            VBox root = new VBox();
            root.setStyle("-fx-background-color: #1A1A1F;");
            root.setFillWidth(true);  // 子元素填满宽度

            // 顶部：菜单栏
            HBox topBar = createTopBar(primaryStage);
            topBar.setMinHeight(40);
            topBar.setMaxHeight(50);
            topBar.setMaxWidth(Double.MAX_VALUE);  // 填满宽度

            // 中间层：中央区域 + 右侧控制面板
            HBox middleArea = new HBox();
            middleArea.setMinHeight(350);  // 确保舞者/队形列表不会与底部重叠
            middleArea.setMaxWidth(Double.MAX_VALUE);  // 填满宽度
            VBox.setVgrow(middleArea, Priority.ALWAYS);

            // 中央区域：视频 + 舞台
            HBox centerArea = createCenterArea();
            HBox.setHgrow(centerArea, Priority.ALWAYS);

            // 右侧控制面板
            VBox controlPanel = createControlPanel();
            controlPanel.setMinWidth(220);
            controlPanel.setPrefWidth(250);
            controlPanel.setMaxWidth(280);

            middleArea.getChildren().addAll(centerArea, controlPanel);

            // 底部：时间轴 + 播放控制
            VBox bottomPanel = new VBox(5);
            bottomPanel.setMinHeight(235);
            bottomPanel.setPrefHeight(245);
            bottomPanel.setMaxHeight(280);
            bottomPanel.setMaxWidth(Double.MAX_VALUE);  // 填满父容器宽度
            bottomPanel.setFillWidth(true);  // 子元素填满宽度
            VBox.setVgrow(bottomPanel, Priority.NEVER);

            timelineView = new TimelineView(viewModel);
            timelineView.setMinHeight(160);
            timelineView.setPrefHeight(170);
            timelineView.setMaxHeight(200);
            timelineView.setMaxWidth(Double.MAX_VALUE);  // 填满宽度
            VBox.setVgrow(timelineView, Priority.NEVER);

            // 设置关键帧更新回调，用于同步列表
            timelineView.setOnFormationsChanged(this::updateFormationList);
            // 设置时间定位回调，用于拖动时间轴时实时同步视频帧
            timelineView.setOnSeekRequested(playbackController::seekTo);
            // 设置拖动预览三段式回调：开始、更新、结束
            timelineView.setOnPreviewScrubStarted(this::beginTimelineScrub);
            timelineView.setOnPreviewScrubUpdated(playbackController::updatePreviewScrubTarget);
            timelineView.setOnPreviewScrubEnded(playbackController::endPreviewScrub);

            HBox bottomControlBar = createBottomControlBar();
            bottomControlBar.setMinHeight(55);
            bottomControlBar.setPrefHeight(65);
            bottomControlBar.setMaxWidth(Double.MAX_VALUE);  // 填满宽度
            VBox.setVgrow(bottomControlBar, Priority.NEVER);

            bottomPanel.getChildren().addAll(timelineView, bottomControlBar);

            // 组装主布局
            root.getChildren().addAll(topBar, middleArea, bottomPanel);

            // 创建场景，并绑定 root 宽度到场景宽度
            Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight());
            root.prefWidthProperty().bind(scene.widthProperty());

            // 关键：直接绑定 timelineView 宽度到窗口宽度
            timelineView.prefWidthProperty().bind(scene.widthProperty());

            // 加载CSS
            String css = getClass().getResource("/style.css").toExternalForm();
            scene.getStylesheets().add(css);

            primaryStage.setTitle("舞形工坊 - Windows桌面版");
            primaryStage.setScene(scene);

            // 初始化关键帧控制器
            keyframeController = new KeyframeController(
                viewModel,
                playbackController,
                timelineView,
                statusLabel,
                this::updateFormationList
            );

            // 设置F键监听（播放时按F键即可标记区域）
            setupFKeyListener(scene);

            // 最小窗口尺寸：40(顶部) + 350(中间) + 235(底部) + 40(窗口框) = 665
            primaryStage.setMinWidth(1100);
            primaryStage.setMinHeight(680);

            // 默认最大化
            primaryStage.setMaximized(true);

            primaryStage.show();

            checkForUpdates(false);

            // 窗口尺寸变化时刷新
            scene.widthProperty().addListener((obs, oldVal, newVal) -> {
                if (stageView != null) stageView.render();
            });
            scene.heightProperty().addListener((obs, oldVal, newVal) -> {
                if (stageView != null) stageView.render();
            });
            getProjectSessionController().loadAndBindProject(primaryStage, projectId);

        } catch (Exception e) {
            System.err.println("[工程加载] 发生异常: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("加载失败");
                alert.setHeaderText("无法加载工程");
                alert.setContentText("错误信息: " + e.getMessage() + "\n\n" +
                    "请点击“打开日志文件夹”获取详细信息。");
                com.formationtool.desktop.view.DiagnosticDialogs.attachLogButton(alert);
                alert.showAndWait();
            });
        }
    }

    // 创建菜单栏
    private MenuBar createMenuBar(Stage stage) {
        MainLayoutFactory.MenuBuildResult result = MainLayoutFactory.createMenuBar(
            () -> {
                selectedProjectId = null;
                stage.close();
                Stage newStage = new Stage();
                start(newStage);
            },
            this::saveCurrentProject,
            () -> exportProjectFile(stage),
            () -> importProjectFile(stage),
            () -> importAudio(stage),
            () -> importVideo(stage),
            this::reloadCurrentVideo,
            this::clearMediaFiles,
            stage::close,
            this::applyMusPreset,
            this::applyAqoursPreset,
            this::applyNijigasakiPreset,
            this::applyLiellasPreset,
            this::applyHasunosoraPreset,
            () -> getTeamPresetController().showCustomPresetDialog(),
            () -> showStageSizeDialog(stage),
            () -> checkForUpdates(true),
            () -> showAboutDialog(stage)
        );
        importAudioMenuItem = result.getImportAudioMenuItem();
        importVideoMenuItem = result.getImportVideoMenuItem();
        return result.getMenuBar();
    }

    // 创建顶部栏：菜单栏 + 右上角蓝色导出按钮
    private HBox createTopBar(Stage stage) {
        MenuBar menuBar = createMenuBar(stage);
        return MainLayoutFactory.createTopBar(
            menuBar,
            () -> showExportDialog(stage),
            this::performUndoAction,
            this::performRedoAction
        );
    }

    // 创建中央区域：左侧视频+右侧舞台
    private HBox createCenterArea() {
        HBox centerArea = new HBox(10);
        centerArea.setPadding(new Insets(10));
        centerArea.setStyle("-fx-background-color: #1A1A1F;");

        // 左侧：参考视频区域
        StackPane videoSection = createVideoSection();
        HBox.setHgrow(videoSection, Priority.ALWAYS);

        // 右侧：3D舞台预览
        StackPane stageContainer = new StackPane();
        stageContainer.setStyle("-fx-background-color: #1E1E2E; -fx-border-color: #2A2A3F; -fx-border-width: 1; -fx-background-radius: 5; -fx-border-radius: 5;");
        stageView = new StageView(viewModel, 200, 150);  // 极小初始尺寸
        stageContainer.getChildren().add(stageView);
        stageView.bindToContainer(stageContainer);
        HBox.setHgrow(stageContainer, Priority.ALWAYS);

        centerArea.getChildren().addAll(videoSection, stageContainer);

        return centerArea;
    }

    // 创建视频区域（响应式布局）
    private StackPane createVideoSection() {
        MainLayoutFactory.VideoSectionResult result = MainLayoutFactory.createVideoSection(viewModel, playbackController);
        previewImageView = result.getPreviewImageView();
        return result.getVideoSection();
    }

    // 创建控制面板（仅包含舞者列表和队形列表）
    private VBox createControlPanel() {
        ControlPanelFactory.PanelResult result = ControlPanelFactory.create(
            viewModel,
            stageView,
            message -> statusLabel.setText(message),
            this::showAddDancerDialog,
            this::deleteSelectedDancer,
            this::editSelectedDancer,
            this::deleteCurrentFormation,
            this::batchDeleteFormations,
            this::snapDancersToGrid,
            this::showFormationPresetsDialog,
            this::copyPreviousKeyframe,
            this::copyCurrentKeyframe,
            this::pasteCopiedKeyframe
        );
        dancerListView = result.getDancerListView();
        formationListView = result.getFormationListView();
        return result.getPanel();
    }

    // 创建底部控制栏（左侧播放控制 + 右侧状态）- 响应式
    private HBox createBottomControlBar() {
        MainLayoutFactory.BottomBarResult result = MainLayoutFactory.createBottomControlBar(
            viewModel,
            this::startPlayback,
            this::pausePlayback,
            this::formatDuration
        );
        playPauseButton = result.getPlayPauseButton();
        statusLabel = result.getStatusLabel();
        return result.getControlBar();
    }
    private void showNewProjectDialog() {
        getDesktopDialogController().showNewProjectDialog();
    }

    // 添加舞者对话框（带颜色选择器）
    private void showAddDancerDialog() {
        getDesktopDialogController().showAddDancerDialog();
    }

    // 删除选中舞者
    private void deleteSelectedDancer() {
        if (viewModel.getCurrentGroup() == null) return;

        Formation formation = viewModel.getCurrentGroup().getFormations()
            .get(viewModel.getCurrentFormationIndex());

        for (Dancer dancer : formation.getDancers()) {
            if (dancer.isSelected()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("确认删除");
                confirm.setHeaderText("删除舞者");
                confirm.setContentText("确定要删除舞者 " + dancer.getName() + " 吗?");

                Optional<ButtonType> result = confirm.showAndWait();
                if (result.isPresent() && result.get() == ButtonType.OK) {
                    viewModel.deleteDancer(dancer.getId());
                    updateDancerList();
                    stageView.render();
                }
                break;
            }
        }
    }

    // 编辑选中舞者
    private void editSelectedDancer() {
        getDesktopDialogController().editSelectedDancer();
    }

    // 添加队形对话框
    private void showAddFormationDialog() {
        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("添加队形");
        dialog.setHeaderText("添加新队形");
        dialog.setContentText("队形名称(留空自动编号):");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            viewModel.addFormation(name);
            updateFormationList();
            stageView.render();
        });
    }

    // 删除当前队形
    private void deleteCurrentFormation() {
        if (viewModel.getCurrentGroup() == null ||
            viewModel.getCurrentGroup().getFormations().size() <= 1) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除队形");
        confirm.setContentText("确定要删除当前队形吗?");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            viewModel.deleteFormation(viewModel.getCurrentFormationIndex());
            updateFormationList();
            stageView.render();
        }
    }

    // Del 快捷键：根据焦点上下文删除舞者或关键帧（复用已有确认弹窗）
    private boolean handleDeleteShortcut() {
        if (viewModel.getCurrentGroup() == null) {
            return false;
        }

        Scene scene = statusLabel != null ? statusLabel.getScene() : null;
        if (scene == null) {
            return false;
        }

        Node focusOwner = scene.getFocusOwner();
        if (focusOwner instanceof TextInputControl) {
            return false;
        }

        boolean focusOnFormationList = isFocusWithinControl(focusOwner, formationListView);
        boolean focusOnDancerList = isFocusWithinControl(focusOwner, dancerListView);
        boolean hasSelectedDancer = hasSelectedDancerInCurrentFormation();

        if (focusOnDancerList || (!focusOnFormationList && hasSelectedDancer)) {
            deleteSelectedDancer();
            return true;
        }

        deleteCurrentFormation();
        return true;
    }

    private boolean hasSelectedDancerInCurrentFormation() {
        if (viewModel.getCurrentGroup() == null || viewModel.getCurrentGroup().getFormations().isEmpty()) {
            return false;
        }

        int currentIndex = viewModel.getCurrentFormationIndex();
        if (currentIndex < 0 || currentIndex >= viewModel.getCurrentGroup().getFormations().size()) {
            return false;
        }

        Formation formation = viewModel.getCurrentGroup().getFormations().get(currentIndex);
        for (Dancer dancer : formation.getDancers()) {
            if (dancer.isSelected()) {
                return true;
            }
        }
        return false;
    }

    private boolean isFocusWithinControl(Node focusOwner, Control control) {
        if (focusOwner == null || control == null) {
            return false;
        }

        Node current = focusOwner;
        while (current != null) {
            if (current == control) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    // 批量删除关键帧
    private void batchDeleteFormations() {
        getFormationActionController().batchDeleteFormations(formationListView);
    }

    private void snapDancersToGrid() {
        if (viewModel.getCurrentGroup() == null) return;

        Formation formation = viewModel.getCurrentGroup().getFormations()
            .get(viewModel.getCurrentFormationIndex());

        for (Dancer dancer : formation.getDancers()) {
            // 吸附到半格（四舍五入到最近0.5的倍数）
            float snappedX = Math.round(dancer.getX() * 2) / 2.0f;
            float snappedY = Math.round(dancer.getY() * 2) / 2.0f;
            dancer.setX(snappedX);
            dancer.setY(snappedY);
        }

        stageView.render();
    }

    /**
     * 复制前一个关键帧的舞者位置
     * - 如果当前在关键帧上，则复制上一个关键帧的位置到当前关键帧
     * - 如果当前不在关键帧上，则在当前时间使用智能插入创建新关键帧并复制前一帧的位置
     */
    private void copyPreviousKeyframe() {
        getFormationActionController().copyPreviousKeyframe();
    }

    /**
     * 执行撤销并刷新UI
     */
    private void performUndoAction() {
        if (!viewModel.canUndo()) {
            if (statusLabel != null) {
                statusLabel.setText("无法撤销 - 没有更多历史记录");
            }
            return;
        }

        boolean success = viewModel.undo();
        if (!success) {
            if (statusLabel != null) {
                statusLabel.setText("撤销失败");
            }
            return;
        }

        updateFormationList();
        updateDancerList();
        if (stageView != null) {
            stageView.render();
        }
        if (timelineView != null) {
            timelineView.setWaveformData(timelineView.getWaveformData());
        }
        if (statusLabel != null) {
            statusLabel.setText("已撤销");
        }
    }

    /**
     * 执行重做并刷新UI
     */
    private void performRedoAction() {
        if (!viewModel.canRedo()) {
            if (statusLabel != null) {
                statusLabel.setText("无法重做 - 没有可恢复的历史记录");
            }
            return;
        }

        boolean success = viewModel.redo();
        if (!success) {
            if (statusLabel != null) {
                statusLabel.setText("重做失败");
            }
            return;
        }

        updateFormationList();
        updateDancerList();
        if (stageView != null) {
            stageView.render();
        }
        if (timelineView != null) {
            timelineView.setWaveformData(timelineView.getWaveformData());
        }
        if (statusLabel != null) {
            statusLabel.setText("已重做");
        }
    }

    /**
     * 复制当前关键帧到内部剪贴板
     */
    private void copyCurrentKeyframe() {
        getFormationActionController().copyCurrentKeyframe();
    }

    /**
     * 在当前时间粘贴已复制关键帧
     */
    private void pasteCopiedKeyframe() {
        getFormationActionController().pasteCopiedKeyframeToCurrentTime();
    }

    private void applyMusPreset() {
        getTeamPresetController().applyMusPreset();
    }

    // Aqours 预设
    private void applyAqoursPreset() {
        getTeamPresetController().applyAqoursPreset();
    }

    // 虹咲 预设（完整 13 人）
    private void applyNijigasakiPreset() {
        getTeamPresetController().applyNijigasakiPreset();
    }

    // Liella! 预设（完整 11 人）
    private void applyLiellasPreset() {
        getTeamPresetController().applyLiellasPreset();
    }

    // 莲之空 预设（完整 11 人）
    private void applyHasunosoraPreset() {
        getTeamPresetController().applyHasunosoraPreset();
    }

    /**
     * 显示舞台尺寸设置对话框
     */
    private void showStageSizeDialog(Stage stage) {
        getDesktopDialogController().showStageSizeDialog();
    }

    /**
     * 显示关于我们对话框
     */
    private void showAboutDialog(Stage stage) {
        getDesktopDialogController().showAboutDialog();
    }

    // 快速编队对话框
    private void showFormationPresetsDialog() {
        getDesktopDialogController().showFormationPresetsDialog();
    }
    private void saveCurrentProject() {
        if (viewModel.getCurrentGroup() == null) return;

        boolean success = dataManager.saveProject(viewModel.getCurrentGroup());
        if (success) {
            viewModel.clearDirty();  // 清除修改标记
            statusLabel.setText("工程已保存");
        } else {
            showError("保存失败", "无法保存工程");
        }
    }

    // 导出工程文件（与Android端完全兼容，.ftproj格式）
    private void exportProjectFile(Stage stage) {
        getProjectFileController().exportProjectFile(stage);
    }

    // 导入工程文件（与Android端完全兼容，支持.ftproj）
    private void importProjectFile(Stage stage) {
        getProjectFileController().importProjectFile(stage);
    }

    // 显示工程列表对话框
    private void updateDancerList() {
        dancerListView.getItems().clear();
        if (viewModel.getCurrentGroup() == null) return;

        // 检查队形列表是否为空
        if (viewModel.getCurrentGroup().getFormations().isEmpty()) {
            return;
        }

        // 检查当前队形索引是否有效
        int currentIndex = viewModel.getCurrentFormationIndex();
        if (currentIndex < 0 || currentIndex >= viewModel.getCurrentGroup().getFormations().size()) {
            return;
        }

        Formation formation = viewModel.getCurrentGroup().getFormations().get(currentIndex);

        // 添加舞者名字到列表
        String selectedDancerName = null;
        for (Dancer dancer : formation.getDancers()) {
            dancerListView.getItems().add(dancer.getName());
            // 记录选中的舞者名字
            if (dancer.isSelected()) {
                selectedDancerName = dancer.getName();
            }
        }

        // 如果有选中的舞者，在列表中高亮显示
        if (selectedDancerName != null) {
            dancerListView.getSelectionModel().select(selectedDancerName);
        }
    }

    // 更新队形列表
    private void updateFormationList() {
        formationListView.getItems().clear();
        if (viewModel.getCurrentGroup() == null) return;

        List<Formation> formations = viewModel.getCurrentGroup().getFormations();
        for (int i = 0; i < formations.size(); i++) {
            Formation formation = formations.get(i);
            // 使用实际的timestamp生成显示文本，并加上序号
            long timestamp = formation.getTimestamp();
            String timeStr = formatDuration(timestamp);
            String displayName = (i + 1) + ". 关键帧 " + timeStr;
            formationListView.getItems().add(displayName);
        }

        formationListView.getSelectionModel().select(viewModel.getCurrentFormationIndex());
    }

    // 显示错误对话框
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        com.formationtool.desktop.view.DiagnosticDialogs.attachLogButton(alert);
        alert.showAndWait();
    }

    private MediaProcessingService getMediaProcessingService() {
        if (mediaProcessingService == null) {
            mediaProcessingService = new MediaProcessingService(viewModel, timelineView);
        }
        return mediaProcessingService;
    }

    private ExportFlowController getExportFlowController() {
        if (exportFlowController == null) {
            exportFlowController = new ExportFlowController(
                viewModel,
                playbackController,
                stageView,
                timelineView,
                () -> currentAudioFile,
                () -> currentVideoFile,
                message -> statusLabel.setText(message),
                this::showError
            );
        }
        return exportFlowController;
    }

    private ProjectSessionController getProjectSessionController() {
        if (projectSessionController == null) {
            projectSessionController = new ProjectSessionController(
                viewModel,
                dataManager,
                stageView,
                message -> statusLabel.setText(message),
                this::updateDancerList,
                this::updateFormationList,
                this::restoreMediaFiles,
                this::saveCurrentProject,
                this::stopAutoSaveTimer,
                this::startAutoSaveTimer
            );
        }
        return projectSessionController;
    }

    private DesktopDialogController getDesktopDialogController() {
        if (desktopDialogController == null) {
            desktopDialogController = new DesktopDialogController(
                viewModel,
                dataManager,
                stageView,
                this::updateDancerList,
                this::updateFormationList,
                message -> statusLabel.setText(message),
                this::showError
            );
        }
        return desktopDialogController;
    }

    private ProjectFileController getProjectFileController() {
        if (projectFileController == null) {
            projectFileController = new ProjectFileController(
                dataManager,
                viewModel,
                message -> statusLabel.setText(message),
                this::updateDancerList,
                this::updateFormationList,
                () -> stageView.render(),
                this::loadImportedAudio,
                this::loadImportedVideo,
                this::restoreMediaFiles,
                this::showError,
                () -> selectedProjectId,
                id -> selectedProjectId = id
            );
        }
        return projectFileController;
    }

    private TeamPresetController getTeamPresetController() {
        if (teamPresetController == null) {
            teamPresetController = new TeamPresetController(
                viewModel,
                this::updateDancerList,
                () -> stageView.render(),
                message -> statusLabel.setText(message)
            );
        }
        return teamPresetController;
    }

    private FormationActionController getFormationActionController() {
        if (formationActionController == null) {
            formationActionController = new FormationActionController(
                viewModel,
                dataManager,
                timelineView,
                stageView,
                this::updateFormationList,
                message -> statusLabel.setText(message)
            );
        }
        return formationActionController;
    }

    private KeyboardShortcutController getKeyboardShortcutController() {
        if (keyboardShortcutController == null) {
            keyboardShortcutController = new KeyboardShortcutController(
                viewModel,
                keyframeController,
                this::startPlayback,
                this::pausePlayback,
                this::updateFormationList,
                () -> stageView.render(),
                () -> timelineView.setWaveformData(timelineView.getWaveformData()),
                message -> statusLabel.setText(message),
                this::handleDeleteShortcut,
                this::copyCurrentKeyframe,
                this::pasteCopiedKeyframe
            );
        }
        return keyboardShortcutController;
    }

    private MediaRestoreController getMediaRestoreController() {
        if (mediaRestoreController == null) {
            mediaRestoreController = new MediaRestoreController(
                dataManager,
                () -> selectedProjectId,
                this::loadImportedAudio,
                this::loadImportedVideo,
                this::updateMediaMenuState
            );
        }
        return mediaRestoreController;
    }

    private MediaImportController getMediaImportController() {
        if (mediaImportController == null) {
            MediaProcessingService mediaProcessing = getMediaProcessingService();
            mediaImportController = new MediaImportController(
                viewModel,
                dataManager,
                playbackController,
                statusLabel,
                MEDIA_LOAD_TIMEOUT_MS,
                this::showError,
                mediaProcessing::convertAudioToWAV,
                mediaProcessing::extractAudioFromVideo,
                mediaProcessing::extractWaveformData,
                this::updateMediaMenuState,
                file -> currentAudioFile = file,
                file -> currentVideoFile = file,
                this::formatDuration
            );
        }
        return mediaImportController;
    }

    // 导入音频
    private void importAudio(Stage stage) {
        getMediaImportController().importAudio(stage);
    }

    // 加载导入的音频文件（用于工程导入）
    private void loadImportedAudio(File audioFile) {
        getMediaImportController().loadImportedAudio(audioFile);
    }

    // 加载导入的视频文件（用于工程导入）
    private void loadImportedVideo(File videoFile) {
        getMediaImportController().loadImportedVideo(videoFile);
    }

    /**
     * 加载视频文件（带完成回调）
     * @param videoFile 视频文件
     * @param onComplete 加载完成回调（成功或失败都会调用）
     */
    private void loadImportedVideo(File videoFile, Runnable onComplete) {
        getMediaImportController().loadImportedVideo(videoFile, onComplete);
    }

    // 导入视频
    private void importVideo(Stage owner) {
        getMediaImportController().importVideo(owner);
    }

    // 格式化时长显示
    private String formatDuration(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / (1000 * 60)) % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * 更新菜单项状态（有媒体文件时禁用导入）
     */
    private void updateMediaMenuState() {
        Platform.runLater(() -> {
            boolean hasAudio = (currentAudioFile != null);
            boolean hasVideo = (currentVideoFile != null);

            if (importAudioMenuItem != null) {
                // 只有已经有音频时才禁用导入音频
                importAudioMenuItem.setDisable(false);
                importAudioMenuItem.setText(hasAudio ? "更换音频…" : "导入音频…");
            }
            if (importVideoMenuItem != null) {
                // 只有已经有视频时才禁用导入视频
                importVideoMenuItem.setDisable(false);
                importVideoMenuItem.setText(hasVideo ? "更换参考视频…" : "导入参考视频…");
            }

            System.out.println("[菜单状态] 导入音频:" + (hasAudio ? "禁用" : "启用") +
                             ", 导入视频:" + (hasVideo ? "禁用" : "启用"));
        });
    }

    // 恢复媒体文件(从工程加载后)
    private void restoreMediaFiles(DanceGroup group) {
        getMediaRestoreController().restoreMediaFiles(group);
    }

    private void startPlayback() {
        if (playbackController == null || !playbackController.canPlay()) {
            showError("播放失败", "请先导入音频文件");
            return;
        }

        System.out.println("[播放控制] 开始播放");
        viewModel.setPlaying(true);
    }

    /**
     * 暂停播放（简化版：只设置状态，控制器会响应）
     */
    private void pausePlayback() {
        System.out.println("[播放控制] 暂停播放");

        // 处理F键标记（无论是否按下F，只要在扫描序列中）
        if (keyframeController != null && keyframeController.isInScanSequence()) {
            keyframeController.handleFKeyMarkOnPause();
        }

        viewModel.setPlaying(false);
        System.out.println("[播放控制] 暂停完成，当前位置: " + viewModel.getCurrentTime() + "ms");
    }

    private void beginTimelineScrub() {
        if (viewModel.isPlaying()) {
            pausePlayback();
        }
        playbackController.beginPreviewScrub();
    }

    /**
     * 停止播放（仅用于完全停止，如关闭工程）
     */
    private void stopPlayback() {
        viewModel.setPlaying(false);
        viewModel.setCurrentTime(0);

        if (playbackController != null) {
            playbackController.seekTo(0);
        }
    }

    // ========== F键标记功能（播放时按F键立即开始标记） ==========

    /**
     * 设置键盘快捷键监听（在创建场景后调用）
     * - 空格键：播放/暂停（全局，使用事件过滤器确保优先级）
     * - F键：拍打关键帧标记
     * - Ctrl+Z / Ctrl+Y：撤销 / 重做
     * - Ctrl+C / Ctrl+V：复制/粘贴关键帧
     * - Del键：删除舞者或关键帧（含确认弹窗）
     */
    private void setupFKeyListener(Scene scene) {
        getKeyboardShortcutController().bind(scene);
    }

    private void showExportDialog(Stage stage) {
        getExportFlowController().showExportDialog(stage);
    }
    /**
     * 清除媒体文件（音频和视频）
     */
    private void clearMediaFiles() {
        MediaCleanupController.ClearMediaResult result = MediaCleanupController.clearMediaFiles(
            playbackController,
            viewModel,
            timelineView,
            dataManager,
            importAudioMenuItem,
            importVideoMenuItem,
            this::updateFormationList
        );

        if (result == null) {
            return;
        }

        if (result.isAudioCleared()) {
            currentAudioFile = null;
        }
        if (result.isVideoCleared()) {
            currentVideoFile = null;
        }

        statusLabel.setText(result.getStatusMessage());
    }

    /**
     * 删除音频
     */
    private void deleteAudio() {
        String status = MediaCleanupController.deleteAudio(playbackController, viewModel);
        if (status == null) {
            return;
        }

        currentAudioFile = null;
        statusLabel.setText(status);
    }

    /**
     * 重新加载当前视频（用于解决视频加载失败的情况）
     */
    private void reloadCurrentVideo() {
        MediaCleanupController.reloadCurrentVideo(
            selectedProjectId,
            dataManager,
            currentVideoFile,
            this::showError,
            statusLabel::setText,
            (videoFile, onComplete) -> loadImportedVideo(videoFile, onComplete),
            this::updateMediaMenuState
        );
    }

    /**
     * 删除视频
     */
    private void deleteVideo() {
        String status = MediaCleanupController.deleteVideo(playbackController, viewModel);
        if (status == null) {
            return;
        }

        currentVideoFile = null;
        statusLabel.setText(status);
    }

    /**
     * 启动自动保存定时器
     */
    private void startAutoSaveTimer() {
        if (autoSaveTimer != null) {
            autoSaveTimer.cancel();
        }

        autoSaveTimer = new java.util.Timer("AutoSaveTimer", true);
        autoSaveTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                // 只有在有未保存修改时才保存
                if (viewModel.isDirty()) {
                    Platform.runLater(() -> {
                        if (viewModel.getCurrentGroup() != null && selectedProjectId != null) {
                            boolean success = dataManager.saveProject(viewModel.getCurrentGroup());
                            if (success) {
                                viewModel.clearDirty();
                                System.out.println("[自动保存] 工程已自动保存");
                                statusLabel.setText("工程已自动保存");
                            }
                        }
                    });
                }
            }
        }, AUTO_SAVE_INTERVAL, AUTO_SAVE_INTERVAL);

        System.out.println("[自动保存] 定时器已启动，间隔: " + AUTO_SAVE_INTERVAL + "ms");
    }

    /**
     * 停止自动保存定时器
     */
    private void stopAutoSaveTimer() {
        if (autoSaveTimer != null) {
            autoSaveTimer.cancel();
            autoSaveTimer = null;
            System.out.println("[自动保存] 定时器已停止");
        }
    }

    private void checkForUpdates(boolean showResultWhenNoUpdate) {
        Thread t = new Thread(() -> {
            try {
                UpdateChecker.UpdateInfo info = UpdateChecker.check(AppVersion.VERSION);
                if (info == null) {
                    System.out.println("[更新检查] 当前已是最新版本");
                    if (showResultWhenNoUpdate) {
                        Platform.runLater(() -> showInfo(
                            "检查更新",
                            "当前已是最新版本",
                            "当前版本：" + AppVersion.VERSION
                        ));
                    }
                    return;
                }
                Platform.runLater(() -> showUpdateDialog(info));
            } catch (Exception e) {
                System.out.println("[更新检查] 检查失败: " + e.getMessage());
                if (showResultWhenNoUpdate) {
                    Platform.runLater(() -> showUpdateFailure(
                        "检查更新失败",
                        "无法完成更新检查。\n\n错误信息：" + e.getMessage()
                    ));
                }
            }
        }, "UpdateCheckThread");
        t.setDaemon(true);
        t.start();
    }

    private void showInfo(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本: ").append(info.latestVersion).append("\n\n");
        if (info.notes != null && !info.notes.isEmpty()) {
            msg.append("更新内容:\n").append(info.notes).append("\n\n");
        }
        String sizeMb = String.format("%.1f", info.size / 1024.0 / 1024.0);
        msg.append("安装包大小: ").append(sizeMb).append(" MB");

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("应用更新");
        dialog.setHeaderText("舞形工坊有新版本可用");
        dialog.setContentText(msg.toString());

        if (info.mandatory) {
            dialog.getButtonTypes().setAll(ButtonType.OK);
        }

        dialog.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                performUpdate(info);
            }
        });
    }

    private void performUpdate(UpdateChecker.UpdateInfo info) {
        Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
        progressAlert.setTitle("正在更新");
        progressAlert.setHeaderText("正在下载更新...");
        progressAlert.setContentText("请勿关闭应用程序");
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressAlert.getDialogPane().setContent(progressBar);
        progressAlert.getButtonTypes().setAll();

        progressAlert.show();

        UpdateDownloader.downloadAndInstall(info, new UpdateDownloader.ProgressCallback() {
            @Override
            public void onProgress(double percent) {
                Platform.runLater(() -> progressBar.setProgress(percent));
            }

            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> progressAlert.setHeaderText(message));
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> {
                    progressAlert.close();
                    showUpdateFailure("更新失败", message);
                });
            }

            @Override
            public void onComplete(File updatedApp) {
                Platform.runLater(() -> {
                    progressAlert.close();
                    Alert restart = new Alert(Alert.AlertType.INFORMATION);
                    restart.setTitle("更新完成");
                    restart.setHeaderText("更新已下载并验证完成");
                    restart.setContentText("点击确定后应用会关闭，更新程序将安全替换文件并自动重新启动。");
                    restart.showAndWait();
                    Platform.exit();
                });
            }
        });
    }

    private void showUpdateFailure(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText("自动更新未完成");
        alert.setContentText(message + "\n\n请前往官网重新下载安装最新版本。");

        ButtonType downloadButton = new ButtonType("前往官网重新下载");
        alert.getButtonTypes().setAll(downloadButton, ButtonType.CLOSE);
        com.formationtool.desktop.view.DiagnosticDialogs.attachLogButton(alert);
        alert.showAndWait().ifPresent(button -> {
            if (button == downloadButton) {
                openOfficialDownloadPage();
            }
        });
    }

    private void openOfficialDownloadPage() {
        Thread browserThread = new Thread(() -> {
            try {
                if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new IllegalStateException("当前系统不支持打开浏览器");
                }
                Desktop.getDesktop().browse(URI.create(OFFICIAL_DOWNLOAD_URL));
            } catch (Exception e) {
                Platform.runLater(() -> showError(
                    "无法打开官网",
                    "请在浏览器中打开：" + OFFICIAL_DOWNLOAD_URL
                ));
            }
        }, "OfficialDownloadPageThread");
        browserThread.setDaemon(true);
        browserThread.start();
    }

    public static void main(String[] args) {
        com.formationtool.desktop.utils.AppLog.install();
        launch(args);
    }
}
