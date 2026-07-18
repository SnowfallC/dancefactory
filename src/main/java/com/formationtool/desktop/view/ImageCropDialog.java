package com.formationtool.desktop.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;
import javafx.embed.swing.SwingFXUtils;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Optional;

/**
 * Lightweight 1:1 image crop dialog with drag + zoom.
 */
public final class ImageCropDialog {
    private static final double VIEW_SIZE = 420.0;
    private static final double CROP_SIZE = 320.0;
    private static final int OUTPUT_SIZE = 512;

    private final Dialog<ButtonType> dialog;
    private final Image sourceImage;
    private final ImageView imageView = new ImageView();

    private final double cropLeft = (VIEW_SIZE - CROP_SIZE) / 2.0;
    private final double cropTop = (VIEW_SIZE - CROP_SIZE) / 2.0;

    private double scale = 1.0;
    private double minScale = 1.0;
    private double maxScale = 6.0;
    private double imageX = 0.0;
    private double imageY = 0.0;
    private double dragStartX = 0.0;
    private double dragStartY = 0.0;
    private double dragImageX = 0.0;
    private double dragImageY = 0.0;

    public ImageCropDialog(Window owner, File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IllegalArgumentException("Image file not found");
        }

        sourceImage = new Image(sourceFile.toURI().toString(), false);
        if (sourceImage.isError() || sourceImage.getWidth() <= 0 || sourceImage.getHeight() <= 0) {
            throw new IllegalArgumentException("Unable to read image");
        }

        dialog = new Dialog<>();
        dialog.setTitle("上传样式");
        dialog.setHeaderText("拖拽调整位置，滚轮缩放，裁剪为正方形");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.getDialogPane().setContent(buildContent());
        applyInitialTransform();
    }

    public Optional<BufferedImage> showAndWait() {
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            return Optional.of(cropToSquare());
        }
        return Optional.empty();
    }

    private VBox buildContent() {
        Pane viewport = new Pane();
        viewport.setPrefSize(VIEW_SIZE, VIEW_SIZE);
        viewport.setMinSize(VIEW_SIZE, VIEW_SIZE);
        viewport.setMaxSize(VIEW_SIZE, VIEW_SIZE);
        viewport.setStyle("-fx-background-color: #20242E;");

        imageView.setImage(sourceImage);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        viewport.getChildren().add(imageView);

        Rectangle shadeTop = new Rectangle(VIEW_SIZE, cropTop, Color.rgb(0, 0, 0, 0.5));
        Rectangle shadeLeft = new Rectangle(cropLeft, CROP_SIZE, Color.rgb(0, 0, 0, 0.5));
        shadeLeft.setLayoutY(cropTop);
        Rectangle shadeRight = new Rectangle(cropLeft, CROP_SIZE, Color.rgb(0, 0, 0, 0.5));
        shadeRight.setLayoutX(cropLeft + CROP_SIZE);
        shadeRight.setLayoutY(cropTop);
        Rectangle shadeBottom = new Rectangle(VIEW_SIZE, cropTop, Color.rgb(0, 0, 0, 0.5));
        shadeBottom.setLayoutY(cropTop + CROP_SIZE);

        Rectangle cropBorder = new Rectangle(CROP_SIZE, CROP_SIZE);
        cropBorder.setLayoutX(cropLeft);
        cropBorder.setLayoutY(cropTop);
        cropBorder.setFill(Color.TRANSPARENT);
        cropBorder.setStroke(Color.WHITE);
        cropBorder.setStrokeWidth(2.0);

        Pane overlay = new Pane(shadeTop, shadeLeft, shadeRight, shadeBottom, cropBorder);
        overlay.setMouseTransparent(true);
        overlay.setPrefSize(VIEW_SIZE, VIEW_SIZE);

        StackPane cropArea = new StackPane(viewport, overlay);
        cropArea.setAlignment(Pos.CENTER);
        cropArea.setPadding(new Insets(4));

        Label tip = new Label("提示: 样式最终会保存为 512x512 PNG");
        HBox tipRow = new HBox(tip);
        tipRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, cropArea, tipRow);
        content.setPadding(new Insets(10));

        viewport.setOnMousePressed(event -> {
            dragStartX = event.getX();
            dragStartY = event.getY();
            dragImageX = imageX;
            dragImageY = imageY;
        });

        viewport.setOnMouseDragged(event -> {
            imageX = dragImageX + (event.getX() - dragStartX);
            imageY = dragImageY + (event.getY() - dragStartY);
            constrainImage();
            applyImageTransform();
        });

        viewport.setOnScroll(event -> {
            double factor = event.getDeltaY() > 0 ? 1.08 : 0.92;
            double nextScale = clamp(scale * factor, minScale, maxScale);
            if (Math.abs(nextScale - scale) < 1e-6) {
                return;
            }

            double anchorX = event.getX();
            double anchorY = event.getY();
            double sourceX = (anchorX - imageX) / scale;
            double sourceY = (anchorY - imageY) / scale;

            imageX = anchorX - sourceX * nextScale;
            imageY = anchorY - sourceY * nextScale;
            scale = nextScale;

            constrainImage();
            applyImageTransform();
            event.consume();
        });

        return content;
    }

    private void applyInitialTransform() {
        minScale = Math.max(CROP_SIZE / sourceImage.getWidth(), CROP_SIZE / sourceImage.getHeight());
        maxScale = Math.max(minScale * 6.0, minScale + 1.0);
        scale = minScale;

        double scaledW = sourceImage.getWidth() * scale;
        double scaledH = sourceImage.getHeight() * scale;
        imageX = (VIEW_SIZE - scaledW) / 2.0;
        imageY = (VIEW_SIZE - scaledH) / 2.0;

        constrainImage();
        applyImageTransform();
    }

    private void applyImageTransform() {
        imageView.setFitWidth(sourceImage.getWidth() * scale);
        imageView.setFitHeight(sourceImage.getHeight() * scale);
        imageView.setLayoutX(imageX);
        imageView.setLayoutY(imageY);
    }

    private void constrainImage() {
        double scaledW = sourceImage.getWidth() * scale;
        double scaledH = sourceImage.getHeight() * scale;

        double minX = cropLeft + CROP_SIZE - scaledW;
        double maxX = cropLeft;
        double minY = cropTop + CROP_SIZE - scaledH;
        double maxY = cropTop;

        imageX = clamp(imageX, minX, maxX);
        imageY = clamp(imageY, minY, maxY);
    }

    private BufferedImage cropToSquare() {
        BufferedImage src = SwingFXUtils.fromFXImage(sourceImage, null);
        if (src == null) {
            throw new IllegalStateException("Failed to convert image");
        }

        double srcX = (cropLeft - imageX) / scale;
        double srcY = (cropTop - imageY) / scale;
        double srcSize = CROP_SIZE / scale;

        int sx1 = (int) Math.floor(clamp(srcX, 0, src.getWidth() - 1));
        int sy1 = (int) Math.floor(clamp(srcY, 0, src.getHeight() - 1));
        int sx2 = (int) Math.ceil(clamp(srcX + srcSize, sx1 + 1, src.getWidth()));
        int sy2 = (int) Math.ceil(clamp(srcY + srcSize, sy1 + 1, src.getHeight()));

        BufferedImage out = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, sx1, sy1, sx2, sy2, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
