package com.formationtool.desktop.controller;

import com.formationtool.desktop.viewmodel.FormationViewModel;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class KeyboardShortcutController {
    private final FormationViewModel viewModel;
    private final KeyframeController keyframeController;
    private final Runnable startPlayback;
    private final Runnable pausePlayback;
    private final Runnable formationListUpdater;
    private final Runnable stageRenderer;
    private final Runnable timelineRefresher;
    private final Consumer<String> statusUpdater;
    private final BooleanSupplier deleteShortcutAction;
    private final Runnable copyCurrentKeyframeAction;
    private final Runnable pasteCopiedKeyframeAction;

    public KeyboardShortcutController(
        FormationViewModel viewModel,
        KeyframeController keyframeController,
        Runnable startPlayback,
        Runnable pausePlayback,
        Runnable formationListUpdater,
        Runnable stageRenderer,
        Runnable timelineRefresher,
        Consumer<String> statusUpdater,
        BooleanSupplier deleteShortcutAction,
        Runnable copyCurrentKeyframeAction,
        Runnable pasteCopiedKeyframeAction
    ) {
        this.viewModel = viewModel;
        this.keyframeController = keyframeController;
        this.startPlayback = startPlayback;
        this.pausePlayback = pausePlayback;
        this.formationListUpdater = formationListUpdater;
        this.stageRenderer = stageRenderer;
        this.timelineRefresher = timelineRefresher;
        this.statusUpdater = statusUpdater;
        this.deleteShortcutAction = deleteShortcutAction;
        this.copyCurrentKeyframeAction = copyCurrentKeyframeAction;
        this.pasteCopiedKeyframeAction = pasteCopiedKeyframeAction;
    }

    public void bind(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.SPACE) {
                if (viewModel.isPlaying()) {
                    pausePlayback.run();
                } else {
                    startPlayback.run();
                }
                event.consume();
            }
        });

        scene.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.Z) {
                if (event.isShiftDown()) {
                    handleRedo(event);
                } else {
                    handleUndo(event);
                }
                return;
            }

            if (event.isControlDown() && event.getCode() == KeyCode.Y) {
                handleRedo(event);
                return;
            }

            if (event.isControlDown() && event.getCode() == KeyCode.C) {
                if (!isTextInputFocused(scene)) {
                    copyCurrentKeyframeAction.run();
                    event.consume();
                }
                return;
            }

            if (event.isControlDown() && event.getCode() == KeyCode.V) {
                if (!isTextInputFocused(scene)) {
                    pasteCopiedKeyframeAction.run();
                    event.consume();
                }
                return;
            }

            if (event.getCode() == KeyCode.DELETE) {
                if (deleteShortcutAction.getAsBoolean()) {
                    event.consume();
                }
                return;
            }

            if (event.getCode() == KeyCode.F && !keyframeController.isFKeyPressed()) {
                if (viewModel.isPlaying()) {
                    keyframeController.handleFKeyPressed();
                }
                event.consume();
            }
        });

        scene.setOnKeyReleased(event -> {
            if (event.getCode() == KeyCode.F && keyframeController.isFKeyPressed()) {
                keyframeController.handleFKeyReleased();
                event.consume();
            }
        });
    }

    private boolean isTextInputFocused(Scene scene) {
        return scene.getFocusOwner() instanceof TextInputControl;
    }

    private void handleUndo(KeyEvent event) {
        if (viewModel.canUndo()) {
            boolean success = viewModel.undo();
            if (success) {
                formationListUpdater.run();
                stageRenderer.run();
                timelineRefresher.run();
                statusUpdater.accept("已撤销");
                System.out.println("[撤销] 撤销成功");
            }
        } else {
            statusUpdater.accept("无法撤销 - 没有更多历史记录");
        }
        event.consume();
    }

    private void handleRedo(KeyEvent event) {
        if (viewModel.canRedo()) {
            boolean success = viewModel.redo();
            if (success) {
                formationListUpdater.run();
                stageRenderer.run();
                timelineRefresher.run();
                statusUpdater.accept("已重做");
                System.out.println("[撤销] 重做成功");
            }
        } else {
            statusUpdater.accept("无法重做 - 没有可恢复的历史记录");
        }
        event.consume();
    }
}
