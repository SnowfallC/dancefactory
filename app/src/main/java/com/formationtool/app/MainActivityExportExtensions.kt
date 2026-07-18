package com.formationtool.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
internal fun MainActivity.startExportWithConfig(config: com.formationtool.app.export.VideoExporter.ExportConfig) {
        val group = viewModel.currentGroup.value
        if (group == null || group.formations.isEmpty()) {
            android.widget.Toast.makeText(this, "没有可导出的队形数据", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val duration = viewModel.audioDuration.value
        if (duration <= 0) {
            android.widget.Toast.makeText(this, "请先导入音频或视频", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 导出前先保存工程
        saveProjectBeforeExport { success ->
            if (success) {
                // 保存成功，继续导出流程
                proceedWithExport(config)
            } else {
                android.widget.Toast.makeText(this, "保存工程失败，无法导出", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

internal fun MainActivity.saveProjectBeforeExport(callback: (Boolean) -> Unit) {
        val activity = this
        val group = viewModel.currentGroup.value ?: run {
            callback(false)
            return
        }

        // 更新工程信息
        group.referenceVideoUri = referenceVideoUri?.toString()
        group.audioUri = currentAudioUri?.toString()
        group.modifiedTime = System.currentTimeMillis()

        // 如果是新工程，需要让用户输入名称
        if (currentProjectId == null) {
            val input = android.widget.EditText(activity).apply {
                hint = "工程名称"
                setText(group.name)
            }

            MaterialAlertDialogBuilder(activity)
                .setTitle("保存工程")
                .setMessage("导出前需要先保存工程")
                .setView(input)
                .setPositiveButton("保存并导出") { _, _ ->
                    val name = input.text.toString()
                    if (name.isNotEmpty()) {
                        group.name = name
                        val success = dataManager.saveDanceGroup(group)
                        if (success) {
                            currentProjectId = group.id
                            viewModel.clearDirty()
                            android.widget.Toast.makeText(activity, "工程已保存，开始导出...", android.widget.Toast.LENGTH_SHORT).show()
                            callback(true)
                        } else {
                            callback(false)
                        }
                    } else {
                        callback(false)
                    }
                }
                .setNegativeButton("取消") { _: android.content.DialogInterface, _: Int ->
                    callback(false)
                }
                .setCancelable(false)
                .show()
        } else {
            // 已有工程ID，直接保存
            val success = dataManager.saveDanceGroup(group)
            if (success) {
                viewModel.clearDirty()
                android.widget.Toast.makeText(activity, "工程已保存，开始导出...", android.widget.Toast.LENGTH_SHORT).show()
                callback(true)
            } else {
                callback(false)
            }
        }
    }

internal fun MainActivity.proceedWithExport(config: com.formationtool.app.export.VideoExporter.ExportConfig) {
        val activity = this
        val group = viewModel.currentGroup.value ?: return
        val duration = viewModel.audioDuration.value

        // 创建进度对话框
        showExportProgressDialog()

        // 创建输出文件
        val outputDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val outputFile = java.io.File(outputDir, "formation_export_$timestamp.mp4")

        // 根据布局模式调整分辨率
        val (exportWidth, exportHeight) = when (config.layoutMode) {
            com.formationtool.app.export.VideoExporter.LayoutMode.SINGLE -> Pair(1920, 1080)
            com.formationtool.app.export.VideoExporter.LayoutMode.SPLIT_HORIZONTAL -> Pair(1920, 1080)
            com.formationtool.app.export.VideoExporter.LayoutMode.SPLIT_VERTICAL -> Pair(1080, 1920)  // 上下分屏用竖屏
        }

        // 创建导出器
        videoExporter = com.formationtool.app.export.VideoExporter(
            context = activity,
            outputFile = outputFile,
            width = exportWidth,
            height = exportHeight,
            fps = 60,
            bitRate = 16_000_000
        )

        // 设置进度回调
        videoExporter?.setCallback(object : com.formationtool.app.export.VideoExporter.ProgressCallback {
            override fun onProgress(progress: Float, message: String) {
                runOnUiThread {
                    exportProgressBar?.progress = (progress * 100).toInt()
                    exportProgressText?.text = message
                }
            }

            override fun onComplete(success: Boolean, outputFile: java.io.File?) {
                runOnUiThread {
                    dismissExportProgressDialog()

                    if (success && outputFile != null && outputFile.exists() && outputFile.length() > 0L) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val savedUri = activity.saveVideoToGallery(outputFile)
                            withContext(Dispatchers.Main) {
                                if (savedUri != null) {
                                    // 触发媒体扫描，确保相册能显示（解决红米手机问题）
                                    activity.triggerMediaScan(savedUri)

                                    MaterialAlertDialogBuilder(activity)
                                        .setTitle("导出成功")
                                        .setMessage("视频已保存到相册\n路径: Movies/FormationTool\n\n是否要分享？")
                                        .setPositiveButton("分享") { _, _ ->
                                            activity.shareVideoUri(savedUri)
                                        }
                                        .setNegativeButton("取消", null)
                                        .show()
                                } else {
                                    android.widget.Toast.makeText(activity, "保存到相册失败", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        android.widget.Toast.makeText(activity, "导出失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    dismissExportProgressDialog()
                    android.widget.Toast.makeText(activity, "导出失败: $error", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })

        // 在IO线程执行导出
        lifecycleScope.launch(Dispatchers.IO) {
            // 检查是否需要参考视频
            val needRefVideo = config.primaryView == com.formationtool.app.export.VideoExporter.ViewType.REFERENCE_VIDEO ||
                              config.secondaryView == com.formationtool.app.export.VideoExporter.ViewType.REFERENCE_VIDEO

            videoExporter?.exportWithConfig(
                config = config,
                formations = group.formations,
                durationMs = duration,
                stageWidth = group.stageWidth,
                stageDepth = group.stageDepth,
                referenceVideoUri = if (needRefVideo) referenceVideoUri else null,
                audioUri = currentAudioUri,
                inPointMs = exportInPointMs,
                outPointMs = exportOutPointMs
            )
        }
    }

internal fun MainActivity.showExportProgressDialog() {
        val activity = this
        // 创建自定义布局
        val layout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E2E"))
        }

        exportProgressText = android.widget.TextView(activity).apply {
            text = "准备导出..."
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(exportProgressText)

        exportProgressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            // 设置进度条颜色
            progressDrawable.setColorFilter(android.graphics.Color.parseColor("#1976D2"), android.graphics.PorterDuff.Mode.SRC_IN)
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                12
            )
            layoutParams = params
        }
        layout.addView(exportProgressBar)

        val percentText = android.widget.TextView(activity).apply {
            text = "0%"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#888899"))
            gravity = android.view.Gravity.END
            setPadding(0, 16, 0, 0)
        }
        layout.addView(percentText)

        exportProgressDialog = MaterialAlertDialogBuilder(activity, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
            .setTitle("正在导出视频")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .create()

        exportProgressDialog?.show()

        // 自定义取消按钮逻辑：增加确认提示
        exportProgressDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(android.graphics.Color.GRAY)
            setOnClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("确认取消")
                    .setMessage("确定要停止当前的导出任务吗？")
                    .setPositiveButton("确定") { _, _ ->
                        videoExporter?.cancel()
                        dismissExportProgressDialog()
                        android.widget.Toast.makeText(activity, "导出已取消", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("继续", null)
                    .show()
            }
        }

        // 定时更新百分比文本
        lifecycleScope.launch {
            while (exportProgressDialog?.isShowing == true) {
                val progress = exportProgressBar?.progress ?: 0
                percentText.text = "$progress%"
                kotlinx.coroutines.delay(100)
            }
        }
    }

internal fun MainActivity.dismissExportProgressDialog() {
        exportProgressDialog?.dismiss()
        exportProgressDialog = null
        exportProgressBar = null
        exportProgressText = null
        videoExporter = null
    }

internal fun MainActivity.shareVideoWithCallback(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // 使用 startActivityForResult 来监听分享结果
            val chooserIntent = Intent.createChooser(shareIntent, "分享视频")
            shareVideoLauncher.launch(chooserIntent)

            // 保存文件引用，以便分享后处理
            pendingShareFile = file
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error sharing video", e)
            android.widget.Toast.makeText(this, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

internal fun MainActivity.shareVideo(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "分享视频"))
    }

internal fun MainActivity.saveVideoToGallery(videoFile: java.io.File): Uri? {
        return try {
            if (!videoFile.exists() || videoFile.length() <= 0L) {
                android.util.Log.e("MainActivity", "拒绝保存空视频: ${videoFile.absolutePath}")
                return null
            }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val displayName = "formation_$timestamp.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FormationTool")
                // 添加日期信息，确保相册能正确排序
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) return null
            try {
                val written = contentResolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
                } ?: throw java.io.IOException("无法打开相册输出流")
                if (written != videoFile.length()) {
                    throw java.io.IOException("视频写入不完整: $written/${videoFile.length()}")
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                videoFile.delete()
                android.util.Log.d("MainActivity", "Video saved to gallery: $uri")
                uri
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error saving video to gallery", e)
            null
        }
    }

internal fun MainActivity.triggerMediaScan(uri: Uri) {
        try {
            // 方法1: 发送广播通知系统扫描
            val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            scanIntent.data = uri
            sendBroadcast(scanIntent)

            android.util.Log.d("MainActivity", "Media scan triggered for: $uri")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to trigger media scan", e)
        }
    }

internal fun MainActivity.saveVideoToApp(videoFile: java.io.File): java.io.File? {
        return try {
            // 创建应用内部视频目录
            val videoDir = java.io.File(filesDir, "videos")
            if (!videoDir.exists()) {
                videoDir.mkdirs()
            }

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val destFile = java.io.File(videoDir, "formation_$timestamp.mp4")

            // 移动文件
            if (videoFile.renameTo(destFile)) {
                android.util.Log.d("MainActivity", "视频已保存到: ${destFile.absolutePath}")
                destFile
            } else {
                // 如果重命名失败，尝试复制
                videoFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                videoFile.delete()
                android.util.Log.d("MainActivity", "视频已复制到: ${destFile.absolutePath}")
                destFile
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error saving video to app", e)
            null
        }
    }

internal fun MainActivity.shareVideoUri(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "分享视频"))
    }
