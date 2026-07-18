package com.formationtool.app

import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.formationtool.app.audio.AudioManager
import com.formationtool.app.model.DanceGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


internal fun MainActivity.getLastProjectIdFromPrefs(): String? {
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        return prefs.getString("last_project_id", null)
    }

internal fun MainActivity.saveProjectIdToPrefs() {
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        prefs.edit().apply {
            if (currentProjectId != null) {
                putString("last_project_id", currentProjectId)
            } else {
                remove("last_project_id")
            }
            apply()
        }
    }

internal fun MainActivity.restoreLastProject(projectId: String) {
        try {
            val group = dataManager.loadDanceGroup(projectId)
            if (group != null) {
                android.util.Log.d("MainActivity", "[状态恢复] 正在恢复工程: ${group.name}")
                currentProjectId = projectId
                videoPlaybackSyncJob?.cancel()
                videoPlaybackSyncJob = null
                videoSeekSyncJob?.cancel()
                videoSeekSyncJob = null

                // 恢复参考视频
                if (group.referenceVideoPath != null && group.referenceVideoPath!!.isNotEmpty()) {
                    try {
                        val videoFile = java.io.File(group.referenceVideoPath!!)
                        if (videoFile.exists()) {
                            referenceVideoUri = Uri.fromFile(videoFile)
                            handleVideoFile(referenceVideoUri!!)
                            android.util.Log.d("MainActivity", "[状态恢复] 视频已恢复")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "[状态恢复] 视频恢复失败", e)
                    }
                } else if (group.referenceVideoUri != null && group.referenceVideoUri!!.isNotEmpty()) {
                    try {
                        referenceVideoUri = Uri.parse(group.referenceVideoUri)
                        handleVideoFile(referenceVideoUri!!)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "[状态恢复] 视频URI恢复失败", e)
                    }
                }

                // 恢复音频
                if (group.audioPath != null && group.audioPath!!.isNotEmpty()) {
                    try {
                        val audioFile = java.io.File(group.audioPath!!)
                        if (audioFile.exists()) {
                            currentAudioUri = Uri.fromFile(audioFile)
                            handleAudioFile(currentAudioUri!!)
                            android.util.Log.d("MainActivity", "[状态恢复] 音频已恢复")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "[状态恢复] 音频恢复失败", e)
                    }
                } else if (group.audioUri != null && group.audioUri!!.isNotEmpty()) {
                    try {
                        currentAudioUri = Uri.parse(group.audioUri)
                        handleAudioFile(currentAudioUri!!)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "[状态恢复] 音频URI恢复失败", e)
                    }
                }

                // 恢复舞者和关键帧数据
                viewModel.loadGroup(group)

                android.util.Log.d("MainActivity", "[状态恢复] 工程恢复完成: ${group.name}")
                android.widget.Toast.makeText(this, "已恢复工程: ${group.name}", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.w("MainActivity", "[状态恢复] 工程不存在，创建新工程")
                viewModel.createNewGroup("未命名工程", 0, 16f, 12f)
                // 清除保存的无效ID
                clearSavedProjectId()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "[状态恢复] 恢复失败", e)
            viewModel.createNewGroup("未命名工程", 0, 16f, 12f)
            clearSavedProjectId()
        }
    }

internal fun MainActivity.clearSavedProjectId() {
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        prefs.edit().remove("last_project_id").apply()
    }

internal fun MainActivity.showProjectMenu() {
        val options = arrayOf(
            "保存工程",
            "查看工程"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("工程管理")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveCurrentProject()
                    1 -> openProjectList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.openProjectList() {
        android.util.Log.d("MainActivity", "[打开工程列表] 启动ProjectListActivity...")
        val intent = Intent(this, ProjectListActivity::class.java)
        // 传递当前工程ID
        intent.putExtra("current_project_id", currentProjectId)
        projectListLauncher.launch(intent)
    }

internal fun MainActivity.showNewProjectDialog() {
        // 检查当前是否有未保存的工程
        val hasUnsavedProject = currentProjectId == null && viewModel.currentGroup.value != null

        if (hasUnsavedProject) {
            // 有未保存的工程，提示用户保存
            MaterialAlertDialogBuilder(this)
                .setTitle("新建工程")
                .setMessage("当前工程尚未保存，是否保存？")
                .setPositiveButton("保存") { _, _ ->
                    // 保存当前工程
                    saveCurrentProject()
                    // 稍后再显示新建对话框
                    lifecycleScope.launch {
                        delay(500)
                        showCreateNewProjectDialog()
                    }
                }
                .setNegativeButton("不保存") { _, _ ->
                    // 不保存，直接创建新工程
                    showCreateNewProjectDialog()
                }
                .setNeutralButton("取消", null)
                .show()
        } else if (currentProjectId != null) {
            // 已有保存的工程，自动保存后清除
            MaterialAlertDialogBuilder(this)
                .setTitle("新建工程")
                .setMessage("当前工程将自动保存，确认要创建新工程吗？")
                .setPositiveButton("确认") { _, _ ->
                    // 自动保存当前工程
                    saveCurrentProject()
                    // 稍后再清除并创建新工程
                    lifecycleScope.launch {
                        delay(300)
                        clearMediaAndCreateNewProject()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 没有工程，直接创建
            showCreateNewProjectDialog()
        }
    }

internal fun MainActivity.showCreateNewProjectDialog() {
        val input = EditText(this).apply {
            hint = "工程名称"
            setText("新工程")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建工程")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().ifEmpty { "新工程" }
                clearMediaAndCreateNewProject(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.clearMediaAndCreateNewProject(projectName: String = "新工程") {
        android.util.Log.d("MainActivity", "[新建工程] 开始清除媒体数据...")

        // 1. 清空参考视频
        hasReferenceVideo = false
        referenceVideoUri = null

        // 2. 停止并清空视频播放器
        try {
            videoPlaybackSyncJob?.cancel()
            videoPlaybackSyncJob = null
            videoSeekSyncJob?.cancel()
            videoSeekSyncJob = null
            referenceVideoView.stopPlayback()
            referenceVideoView.suspend()
            referenceVideoView.setVideoURI(null)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "[新建工程] 清空视频播放器异常", e)
        }

        // 3. 隐藏VideoView显示黑屏背景
        referenceVideoView.visibility = View.GONE
        findViewById<TextView>(R.id.tvNoVideo).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnDeleteVideo).visibility = View.GONE

        // 4. 清空音频/波形数据
        currentAudioUri = null
        audioManager.release()
        audioManager = AudioManager(this)

        // 5. 重新设置 audioManager 的监听
        setupAudioManagerObservers()

        // 6. 清空波形数据，恢复导入状态
        viewModel.setAudioPath(null, 0L)

        // 7. 清空波形视图
        waveformView.waveformData = FloatArray(0)
        waveformView.audioDuration = 0L
        waveformView.formations = mutableListOf()
        waveformView.invalidate()

        // 8. 清空关键帧标签
        val markersContainer = findViewById<FrameLayout>(R.id.keyframeMarkersContainer)
        markersContainer.removeAllViews()

        // 9. 重置音频名称显示
        findViewById<TextView>(R.id.tvAudioNameTimeline).text = "点击导入音频"

        // 10. 创建新工程
        viewModel.createNewGroup(projectName, 1, 16f, 12f)
        currentProjectId = null

        // 11. 刷新渲染器
        glSurfaceView.requestRender()

        android.util.Log.d("MainActivity", "[新建工程] 媒体数据清除完成")
        android.widget.Toast.makeText(this, "已创建新工程: $projectName", android.widget.Toast.LENGTH_SHORT).show()
    }

internal fun MainActivity.saveCurrentProject() {
        val group = viewModel.currentGroup.value
        if (group == null) {
            android.widget.Toast.makeText(this, "没有可保存的工程", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.w("MainActivity", "saveCurrentProject: group is null")
            return
        }

        android.util.Log.d("MainActivity", "========== 开始保存工程 ==========")
        android.util.Log.d("MainActivity", "saveCurrentProject: dancers=${group.dancerCount}, formations=${group.formations.size}")
        android.util.Log.d("MainActivity", "saveCurrentProject: audioUri=${group.audioUri}, videoUri=${group.referenceVideoUri}")

        // 检查是否有舞者数据
        if (group.dancerCount == 0) {
            android.widget.Toast.makeText(this, "警告：工程中没有舞者！", android.widget.Toast.LENGTH_LONG).show()
        }
        if (group.formations.isEmpty()) {
            android.util.Log.w("MainActivity", "saveCurrentProject: WARNING - formations list is EMPTY!")
        }

        // 更新工程信息
        group.referenceVideoUri = referenceVideoUri?.toString()
        group.audioUri = currentAudioUri?.toString()
        group.modifiedTime = System.currentTimeMillis()

        android.util.Log.d("MainActivity", "saveCurrentProject: updated audioUri=${group.audioUri}, videoUri=${group.referenceVideoUri}")

        // 如果是新工程，让用户输入名称
        if (currentProjectId == null) {
            val input = EditText(this).apply {
                hint = "工程名称"
                setText(group.name)
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("保存工程")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val name = input.text.toString()
                    if (name.isNotEmpty()) {
                        group.name = name
                        android.util.Log.d("MainActivity", "saveCurrentProject: saving new project '${name}'")
                        val success = dataManager.saveDanceGroup(group)
                        if (success) {
                            currentProjectId = group.id
                            viewModel.clearDirty()  // 清除修改标记
                            val message = "工程已保存\n舞者:${group.dancerCount} 关键帧:${group.formations.size}"
                            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                            android.util.Log.d("MainActivity", "========== 保存成功 ==========")
                        } else {
                            android.widget.Toast.makeText(this, "保存失败！请查看日志", android.widget.Toast.LENGTH_LONG).show()
                            android.util.Log.e("MainActivity", "========== 保存失败 ==========")
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 更新已存在的工程
            android.util.Log.d("MainActivity", "saveCurrentProject: updating existing project '${group.name}' (id=${currentProjectId})")
            val success = dataManager.saveDanceGroup(group)
            if (success) {
                viewModel.clearDirty()  // 清除修改标记
                val message = "工程已保存\n舞者:${group.dancerCount} 关键帧:${group.formations.size}"
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                android.util.Log.d("MainActivity", "========== 保存成功 ==========")
            } else {
                android.widget.Toast.makeText(this, "保存失败！请查看日志", android.widget.Toast.LENGTH_LONG).show()
                android.util.Log.e("MainActivity", "========== 保存失败 ==========")
            }
        }
    }

internal fun MainActivity.saveNewProject(projectName: String) {
        val group = viewModel.currentGroup.value
        if (group == null) {
            android.util.Log.e("MainActivity", "saveNewProject: group is null")
            return
        }

        group.name = projectName
        group.modifiedTime = System.currentTimeMillis()

        android.util.Log.d("MainActivity", "saveNewProject: saving new project '$projectName'")
        val success = dataManager.saveDanceGroup(group)
        if (success) {
            currentProjectId = group.id
            saveProjectIdToPrefs()  // 保存到SharedPreferences
            viewModel.clearDirty()
            android.widget.Toast.makeText(this, "工程已保存: $projectName", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.d("MainActivity", "saveNewProject: save success, id=${group.id}")
        } else {
            android.widget.Toast.makeText(this, "保存失败！", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.e("MainActivity", "saveNewProject: save failed")
        }
    }

internal fun MainActivity.saveNewProjectForMediaImport(projectName: String) {
        val group = viewModel.currentGroup.value
        if (group == null) {
            android.util.Log.e("MainActivity", "saveNewProjectForMediaImport: group is null")
            return
        }

        group.name = projectName
        group.modifiedTime = System.currentTimeMillis()

        android.util.Log.d("MainActivity", "saveNewProjectForMediaImport: saving new project '$projectName'")
        val success = dataManager.saveDanceGroup(group)
        if (success) {
            currentProjectId = group.id
            saveProjectIdToPrefs()  // 保存到SharedPreferences
            viewModel.clearDirty()
            android.widget.Toast.makeText(this, "工程已保存: $projectName", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.d("MainActivity", "saveNewProjectForMediaImport: save success, id=${group.id}")
        } else {
            android.widget.Toast.makeText(this, "保存失败！", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.e("MainActivity", "saveNewProjectForMediaImport: save failed")
        }
    }

internal fun MainActivity.loadProject(projectId: String) {
        // 直接加载工程（保存提示已移到工程列表中处理）
        proceedLoadProject(projectId)
    }

internal fun MainActivity.saveCurrentProjectSilent() {
        val group = viewModel.currentGroup.value ?: return

        if (currentProjectId == null) {
            // 新工程，使用默认名称保存
            group.modifiedTime = System.currentTimeMillis()
            val success = dataManager.saveDanceGroup(group)
            if (success) {
                currentProjectId = group.id
                saveProjectIdToPrefs()  // 保存到SharedPreferences
                viewModel.clearDirty()
                android.util.Log.d("MainActivity", "saveCurrentProjectSilent: 新工程保存成功")
            }
        } else {
            // 已有工程ID，直接保存
            group.referenceVideoUri = referenceVideoUri?.toString()
            group.audioUri = currentAudioUri?.toString()
            group.modifiedTime = System.currentTimeMillis()
            val success = dataManager.saveDanceGroup(group)
            if (success) {
                viewModel.clearDirty()
                android.util.Log.d("MainActivity", "saveCurrentProjectSilent: 工程保存成功")
            }
        }
    }

internal fun MainActivity.proceedLoadProject(projectId: String) {

        try {
            val group = dataManager.loadDanceGroup(projectId)
            if (group == null) {
                android.util.Log.e("MainActivity", "loadProject: Failed to load group")
                android.widget.Toast.makeText(this, "加载工程失败", android.widget.Toast.LENGTH_LONG).show()
                return
            }

            android.util.Log.d("MainActivity", "loadProject: Loaded group '${group.name}'")
            android.util.Log.d("MainActivity", "loadProject: dancers=${group.dancerCount}, formations=${group.formations.size}")
            android.util.Log.d("MainActivity", "loadProject: audioUri=${group.audioUri}")
            android.util.Log.d("MainActivity", "loadProject: videoUri=${group.referenceVideoUri}")

            // 恢复工程ID
            currentProjectId = projectId
            videoPlaybackSyncJob?.cancel()
            videoPlaybackSyncJob = null
            videoSeekSyncJob?.cancel()
            videoSeekSyncJob = null
            // 保存到SharedPreferences，以便切后台返回时恢复
            saveProjectIdToPrefs()

            // 恢复参考视频（优先使用内部存储路径）
            if (group.referenceVideoPath != null && group.referenceVideoPath!!.isNotEmpty()) {
                // 优先使用内部存储路径
                try {
                    val videoFile = File(group.referenceVideoPath!!)
                    if (videoFile.exists()) {
                        referenceVideoUri = Uri.fromFile(videoFile)
                        android.util.Log.d("MainActivity", "loadProject: Loading video from internal storage: ${videoFile.absolutePath}")
                        handleVideoFile(referenceVideoUri!!)
                    } else {
                        android.util.Log.w("MainActivity", "loadProject: Internal video file not found: ${group.referenceVideoPath}")
                        // 尝试使用原始URI
                        if (group.referenceVideoUri != null && group.referenceVideoUri!!.isNotEmpty()) {
                            referenceVideoUri = Uri.parse(group.referenceVideoUri)
                            android.util.Log.d("MainActivity", "loadProject: Fallback to original video URI: $referenceVideoUri")
                            handleVideoFile(referenceVideoUri!!)
                        } else {
                            hasReferenceVideo = false
                            referenceVideoUri = null
                            findViewById<TextView>(R.id.tvNoVideo).visibility = View.VISIBLE
                            findViewById<Button>(R.id.btnDeleteVideo).visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "loadProject: Failed to load video", e)
                    hasReferenceVideo = false
                    referenceVideoUri = null
                    findViewById<TextView>(R.id.tvNoVideo).visibility = View.VISIBLE
                    findViewById<Button>(R.id.btnDeleteVideo).visibility = View.GONE
                }
            } else if (group.referenceVideoUri != null && group.referenceVideoUri!!.isNotEmpty()) {
                // 如果没有内部路径，尝试使用原始URI（兼容旧数据）
                try {
                    referenceVideoUri = Uri.parse(group.referenceVideoUri)
                    android.util.Log.d("MainActivity", "loadProject: Attempting to load video from original URI: $referenceVideoUri")
                    handleVideoFile(referenceVideoUri!!)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "loadProject: Failed to load video from original URI", e)
                    hasReferenceVideo = false
                    referenceVideoUri = null
                    findViewById<TextView>(R.id.tvNoVideo).visibility = View.VISIBLE
                    findViewById<Button>(R.id.btnDeleteVideo).visibility = View.GONE
                }
            } else {
                android.util.Log.d("MainActivity", "loadProject: No video to restore")
                hasReferenceVideo = false
                referenceVideoUri = null
                findViewById<TextView>(R.id.tvNoVideo).visibility = View.VISIBLE
                findViewById<Button>(R.id.btnDeleteVideo).visibility = View.GONE
            }

            // 恢复音频（优先使用内部存储路径）
            if (group.audioPath != null && group.audioPath!!.isNotEmpty()) {
                // 优先使用内部存储路径
                try {
                    val audioFile = File(group.audioPath!!)
                    if (audioFile.exists()) {
                        currentAudioUri = Uri.fromFile(audioFile)
                        android.util.Log.d("MainActivity", "loadProject: Loading audio from internal storage: ${audioFile.absolutePath}")
                        handleAudioFile(currentAudioUri!!)
                    } else {
                        android.util.Log.w("MainActivity", "loadProject: Internal audio file not found: ${group.audioPath}")
                        // 尝试使用原始URI
                        if (group.audioUri != null && group.audioUri!!.isNotEmpty()) {
                            currentAudioUri = Uri.parse(group.audioUri)
                            android.util.Log.d("MainActivity", "loadProject: Fallback to original audio URI: $currentAudioUri")
                            handleAudioFile(currentAudioUri!!)
                        } else {
                            currentAudioUri = null
                            audioManager.release()
                            audioManager = AudioManager(this)
                            setupAudioManagerObservers()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "loadProject: Failed to load audio", e)
                    currentAudioUri = null
                    audioManager.release()
                    audioManager = AudioManager(this)
                    setupAudioManagerObservers()
                }
            } else if (group.audioUri != null && group.audioUri!!.isNotEmpty()) {
                // 如果没有内部路径，尝试使用原始URI（兼容旧数据）
                try {
                    currentAudioUri = Uri.parse(group.audioUri)
                    android.util.Log.d("MainActivity", "loadProject: Attempting to load audio from original URI: $currentAudioUri")
                    handleAudioFile(currentAudioUri!!)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "loadProject: Failed to load audio from original URI", e)
                    currentAudioUri = null
                    audioManager.release()
                    audioManager = AudioManager(this)
                    setupAudioManagerObservers()
                }
            } else {
                android.util.Log.d("MainActivity", "loadProject: No audio to restore")
                currentAudioUri = null
                audioManager.release()
                audioManager = AudioManager(this)
                setupAudioManagerObservers()
            }

            // 恢复舞者和关键帧数据
            android.util.Log.d("MainActivity", "loadProject: About to call viewModel.loadGroup...")
            viewModel.loadGroup(group)
            android.util.Log.d("MainActivity", "loadProject: viewModel.loadGroup completed")

            android.util.Log.d("MainActivity", "========== 加载完成 ==========")

            // 立即显示TOAST
            runOnUiThread {
                val message = "已加载工程: ${group.name}\n舞者:${group.dancerCount} 关键帧:${group.formations.size}"
                android.util.Log.d("MainActivity", "loadProject: Showing toast: $message")
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "loadProject: Exception occurred", e)
            e.printStackTrace()
            runOnUiThread {
                android.widget.Toast.makeText(this, "加载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

internal fun MainActivity.shareCurrentProject() {
        // 获取所有已保存的工程列表
        val projects = dataManager.getAllDanceGroups()

        if (projects.isEmpty()) {
            android.widget.Toast.makeText(this, "没有已保存的工程可以分享", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 显示工程列表供用户选择
        val projectNames = projects.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("选择要分享的工程")
            .setItems(projectNames) { _, which ->
                val selectedProject = projects[which]
                shareProject(selectedProject)
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.shareProject(group: DanceGroup) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 创建临时ZIP文件
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                val cacheDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                val zipFile = java.io.File(cacheDir, "${group.name}_$timestamp.ftproj")

                // 优先使用内部存储路径，如果没有则尝试使用原始URI
                val audioUri = if (group.audioPath != null && group.audioPath!!.isNotEmpty()) {
                    val audioFile = File(group.audioPath!!)
                    if (audioFile.exists()) {
                        android.util.Log.d("MainActivity", "Using internal audio path: ${group.audioPath}")
                        Uri.fromFile(audioFile)
                    } else {
                        android.util.Log.w("MainActivity", "Internal audio file not found, trying original URI")
                        group.audioUri?.let { Uri.parse(it) }
                    }
                } else {
                    android.util.Log.d("MainActivity", "No internal audio path, using original URI")
                    group.audioUri?.let { Uri.parse(it) }
                }

                val videoUri = if (group.referenceVideoPath != null && group.referenceVideoPath!!.isNotEmpty()) {
                    val videoFile = File(group.referenceVideoPath!!)
                    if (videoFile.exists()) {
                        android.util.Log.d("MainActivity", "Using internal video path: ${group.referenceVideoPath}")
                        Uri.fromFile(videoFile)
                    } else {
                        android.util.Log.w("MainActivity", "Internal video file not found, trying original URI")
                        group.referenceVideoUri?.let { Uri.parse(it) }
                    }
                } else {
                    android.util.Log.d("MainActivity", "No internal video path, using original URI")
                    group.referenceVideoUri?.let { Uri.parse(it) }
                }

                android.util.Log.d("MainActivity", "Exporting project: audioUri=$audioUri, videoUri=$videoUri")

                // 导出ZIP（包含视频文件）
                val success = dataManager.exportProjectAsZip(group, audioUri, videoUri, zipFile)

                withContext(Dispatchers.Main) {
                    if (success && zipFile.exists()) {
                        android.util.Log.d("MainActivity", "Export successful, zip file size: ${zipFile.length()} bytes")
                        // 使用FileProvider分享
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this@shareProject,
                            "${packageName}.fileprovider",
                            zipFile
                        )

                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "分享队形工程: ${group.name}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "分享工程"))
                    } else {
                        android.util.Log.e("MainActivity", "Export failed or zip file doesn't exist")
                        android.widget.Toast.makeText(this@shareProject, "导出工程失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error sharing project", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@shareProject, "分享失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

internal fun MainActivity.importProjectFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            // 通过文件扩展名过滤，只显示.ftproj和.zip文件
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
            ))
        }
        projectImportLauncher.launch(intent)
    }

internal fun MainActivity.handleProjectImport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 复制URI到临时文件
                val tempFile = java.io.File(cacheDir, "temp_import.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 导入工程（包含视频文件）
                val (importedGroup, audioFile, videoFile) = dataManager.importProjectFromZip(tempFile)

                withContext(Dispatchers.Main) {
                    if (importedGroup != null) {
                        // 加载导入的工程
                        viewModel.loadGroup(importedGroup)
                        currentProjectId = importedGroup.id

                        // 加载音频（如果有）
                        if (audioFile != null && audioFile.exists()) {
                            currentAudioUri = Uri.fromFile(audioFile)
                            handleAudioFile(currentAudioUri!!)
                        } else {
                            // 清空音频
                            currentAudioUri = null
                            audioManager.release()
                            audioManager = AudioManager(this@handleProjectImport)
                            setupAudioManagerObservers()
                        }

                        // 加载视频（如果有）
                        if (videoFile != null && videoFile.exists()) {
                            referenceVideoUri = Uri.fromFile(videoFile)
                            handleVideoFile(referenceVideoUri!!)
                        } else {
                            // 清空视频状态
                            hasReferenceVideo = false
                            referenceVideoUri = null
                            findViewById<TextView>(R.id.tvNoVideo).visibility = View.VISIBLE
                            findViewById<Button>(R.id.btnDeleteVideo).visibility = View.GONE
                        }

                        android.widget.Toast.makeText(
                            this@handleProjectImport,
                            "工程导入成功: ${importedGroup.name}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        android.widget.Toast.makeText(this@handleProjectImport, "导入失败：无效的工程文件", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                // 删除临时文件
                tempFile.delete()

            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error importing project", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@handleProjectImport, "导入失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
