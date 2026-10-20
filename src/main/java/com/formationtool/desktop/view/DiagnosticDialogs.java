package com.formationtool.desktop.view;

import com.formationtool.desktop.utils.AppLog;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;

/** 报错时保留原始信息，并提供本机日志入口。 */
public final class DiagnosticDialogs {
    private DiagnosticDialogs() { }

    public static void attachLogButton(Alert alert) {
        AppLog.error(alert.getTitle(), String.valueOf(alert.getHeaderText()) + "\n" + alert.getContentText());
        ButtonType openLogs = new ButtonType("打开日志文件夹", javafx.scene.control.ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes().add(0, openLogs);
        Button button = (Button) alert.getDialogPane().lookupButton(openLogs);
        button.addEventFilter(ActionEvent.ACTION, event -> {
            // 打开文件夹后保留报错窗口，方便用户同时截图。
            event.consume();
            openLogFolder();
        });
    }

    public static void openLogFolder() {
        Path directory = AppLog.getDirectory();
        AppLog.flush();
        Thread opener = new Thread(() -> {
            try {
                Files.createDirectories(directory);
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    throw new IllegalStateException("系统不支持自动打开文件夹");
                }
                Desktop.getDesktop().open(directory.toFile());
            } catch (Exception error) {
                AppLog.error("无法打开日志文件夹", error.toString());
                Platform.runLater(() -> {
                    Alert notice = new Alert(Alert.AlertType.WARNING);
                    notice.setTitle("日志文件夹");
                    notice.setHeaderText("无法自动打开，请手动访问以下路径");
                    notice.setContentText(directory.toString());
                    notice.show();
                });
            }
        }, "OpenLogFolder");
        opener.setDaemon(true);
        opener.start();
    }
}
