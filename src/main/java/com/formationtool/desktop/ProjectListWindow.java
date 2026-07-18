package com.formationtool.desktop;

import com.formationtool.desktop.data.DataManager;
import com.formationtool.desktop.model.DanceGroup;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 工程列表窗口 - 类似剪映的草稿箱
 */
public class ProjectListWindow {

    private final Stage stage;
    private final DataManager dataManager;
    private final GridPane projectGrid;
    private Runnable onProjectSelected;

    public ProjectListWindow(Stage stage, DataManager dataManager) {
        this.stage = stage;
        this.dataManager = dataManager;
        this.projectGrid = new GridPane();

        initUI();
    }

    private void initUI() {
        // 主容器
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #1A1A1F;");

        // 标题栏
        HBox header = new HBox(20);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("我的工程");
        title.setTextFill(Color.WHITE);
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 新建工程按钮
        Button newProjectBtn = new Button("+ 新建工程");
        newProjectBtn.setStyle(
            "-fx-background-color: #1976D2;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 14px;" +
            "-fx-padding: 10 20 10 20;" +
            "-fx-background-radius: 5;" +
            "-fx-cursor: hand;"
        );
        newProjectBtn.setOnAction(e -> showNewProjectDialog());

        header.getChildren().addAll(title, spacer, newProjectBtn);

        // 工程网格（卡片视图）
        projectGrid.setHgap(15);
        projectGrid.setVgap(15);
        projectGrid.setPadding(new Insets(10));

        ScrollPane scrollPane = new ScrollPane(projectGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #1A1A1F; -fx-background-color: #1A1A1F;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root.getChildren().addAll(header, scrollPane);

        // 创建场景
        Scene scene = new Scene(root, 900, 600);
        stage.setScene(scene);
        stage.setTitle("舞形工坊 - 工程列表");

        // 加载工程列表
        loadProjects();
    }

    /**
     * 加载工程列表
     */
    private void loadProjects() {
        projectGrid.getChildren().clear();

        var projects = dataManager.listProjects();

        if (projects.isEmpty()) {
            // 显示空状态
            Label emptyLabel = new Label("还没有工程\n点击右上角新建工程开始");
            emptyLabel.setTextFill(Color.GRAY);
            emptyLabel.setStyle("-fx-font-size: 18px; -fx-text-alignment: center;");
            projectGrid.add(emptyLabel, 0, 0);
            return;
        }

        // 显示工程卡片（3列）
        int col = 0;
        int row = 0;
        for (var project : projects) {
            VBox card = createProjectCard(project);
            projectGrid.add(card, col, row);

            col++;
            if (col >= 3) {
                col = 0;
                row++;
            }
        }
    }

    /**
     * 创建工程卡片
     */
    private VBox createProjectCard(DataManager.ProjectInfo project) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(10));
        card.setStyle(
            "-fx-background-color: #2A2A3F;" +
            "-fx-background-radius: 8;" +
            "-fx-border-color: #3A3A4F;" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 8;" +
            "-fx-cursor: hand;"
        );
        card.setPrefWidth(250);
        card.setPrefHeight(200);

        // 缩略图区域
        StackPane thumbnailPane = new StackPane();
        thumbnailPane.setPrefHeight(120);
        thumbnailPane.setStyle("-fx-background-color: #1E1E2E; -fx-background-radius: 5;");

        if (project.thumbnailPath != null && new File(project.thumbnailPath).exists()) {
            try {
                Image thumbnail = new Image(new File(project.thumbnailPath).toURI().toString());
                ImageView imageView = new ImageView(thumbnail);
                imageView.setFitWidth(230);
                imageView.setFitHeight(120);
                imageView.setPreserveRatio(true);
                thumbnailPane.getChildren().add(imageView);
            } catch (Exception e) {
                // 加载失败，显示默认图标
                addDefaultThumbnail(thumbnailPane);
            }
        } else {
            addDefaultThumbnail(thumbnailPane);
        }

        // 工程名称
        Label nameLabel = new Label(project.name);
        nameLabel.setTextFill(Color.WHITE);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        // 工程信息
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
        String dateStr = sdf.format(new Date(project.modifiedTime));

        Label infoLabel = new Label(
            String.format("%d舞者 · %d队形", project.dancerCount, project.formationCount)
        );
        infoLabel.setTextFill(Color.GRAY);
        infoLabel.setStyle("-fx-font-size: 11px;");

        Label dateLabel = new Label(dateStr);
        dateLabel.setTextFill(Color.GRAY);
        dateLabel.setStyle("-fx-font-size: 10px;");

        // 删除按钮
        Button deleteBtn = new Button("×");
        deleteBtn.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-text-fill: #888888;" +
            "-fx-font-size: 18px;" +
            "-fx-font-weight: bold;" +
            "-fx-cursor: hand;" +
            "-fx-padding: 0 5 0 5;"
        );
        deleteBtn.setOnMouseEntered(e -> deleteBtn.setStyle(
            "-fx-background-color: #D32F2F;" +
            "-fx-text-fill: white;" +
            "-fx-font-size: 18px;" +
            "-fx-font-weight: bold;" +
            "-fx-cursor: hand;" +
            "-fx-padding: 0 5 0 5;" +
            "-fx-background-radius: 3;"
        ));
        deleteBtn.setOnMouseExited(e -> deleteBtn.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-text-fill: #888888;" +
            "-fx-font-size: 18px;" +
            "-fx-font-weight: bold;" +
            "-fx-cursor: hand;" +
            "-fx-padding: 0 5 0 5;"
        ));
        deleteBtn.setOnAction(e -> {
            e.consume();
            deleteProject(project);
        });

        // 标题栏（名称 + 删除按钮）
        HBox titleBox = new HBox(5);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleBox.getChildren().addAll(nameLabel, spacer, deleteBtn);

        card.getChildren().addAll(thumbnailPane, titleBox, infoLabel, dateLabel);

        // 点击打开工程
        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openProject(project.id);
            }
        });

        // 右键菜单
        ContextMenu contextMenu = new ContextMenu();
        MenuItem openItem = new MenuItem("打开");
        MenuItem deleteItem = new MenuItem("删除");

        openItem.setOnAction(e -> openProject(project.id));
        deleteItem.setOnAction(e -> deleteProject(project));

        contextMenu.getItems().addAll(openItem, deleteItem);
        card.setOnContextMenuRequested(e -> contextMenu.show(card, e.getScreenX(), e.getScreenY()));

        // 鼠标悬停效果
        card.setOnMouseEntered(e -> card.setStyle(
            "-fx-background-color: #353550;" +
            "-fx-background-radius: 8;" +
            "-fx-border-color: #4A4A6F;" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 8;" +
            "-fx-cursor: hand;"
        ));
        card.setOnMouseExited(e -> card.setStyle(
            "-fx-background-color: #2A2A3F;" +
            "-fx-background-radius: 8;" +
            "-fx-border-color: #3A3A4F;" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 8;" +
            "-fx-cursor: hand;"
        ));

        return card;
    }

    /**
     * 添加默认缩略图
     */
    private void addDefaultThumbnail(StackPane pane) {
        Label icon = new Label("📋");
        icon.setStyle("-fx-font-size: 48px;");
        pane.getChildren().add(icon);
    }

    /**
     * 显示新建工程对话框
     */
    private void showNewProjectDialog() {
        TextInputDialog dialog = new TextInputDialog("新工程");
        dialog.setTitle("新建工程");
        dialog.setHeaderText("创建新的舞蹈编排工程");
        dialog.setContentText("工程名称:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            // 创建新工程
            String projectId = UUID.randomUUID().toString();
            DanceGroup group = new DanceGroup(projectId, name, 16f, 12f);
            group.setDancerCount(9);
            group.setCreatedTime(System.currentTimeMillis());
            group.setModifiedTime(System.currentTimeMillis());

            // 保存新工程
            dataManager.saveProject(group);

            // 打开新工程
            openProject(projectId);
        });
    }

    /**
     * 打开工程
     */
    private void openProject(String projectId) {
        // 先关闭当前窗口
        stage.close();

        if (onProjectSelected != null) {
            // 通知外部，选择了某个工程
            MainApp.selectedProjectId = projectId;
            onProjectSelected.run();
        }
    }

    /**
     * 删除工程
     */
    private void deleteProject(DataManager.ProjectInfo project) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除工程");
        confirm.setContentText("确定要删除工程 \"" + project.name + "\" 吗？\n\n此操作不可恢复！");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            boolean success = dataManager.deleteProject(project.id);
            if (success) {
                loadProjects();  // 重新加载列表
            } else {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle("删除失败");
                error.setHeaderText("无法删除工程");
                error.showAndWait();
            }
        }
    }

    /**
     * 设置工程选择回调
     */
    public void setOnProjectSelected(Runnable callback) {
        this.onProjectSelected = callback;
    }

    /**
     * 显示窗口
     */
    public void show() {
        stage.show();
    }
}
