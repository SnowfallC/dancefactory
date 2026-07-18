package com.formationtool.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.formationtool.app.controller.DancerDialogController
import com.formationtool.app.controller.ExportDialogController
import com.formationtool.app.controller.FormationActionController
import com.formationtool.app.controller.StageSettingsDialogController
import com.formationtool.app.MainActivity.DancerPreset
import com.formationtool.app.renderer.FormationRenderer
import com.formationtool.app.update.AppUpdateChecker
import com.formationtool.app.viewmodel.addDancer
import com.formationtool.app.viewmodel.batchAddDancers
import com.formationtool.app.viewmodel.clearAllDancers
import com.formationtool.app.viewmodel.deleteDancer
import com.formationtool.app.viewmodel.updateDancer
import com.formationtool.app.viewmodel.updateDancerStyle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


internal fun MainActivity.requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要 READ_MEDIA_VIDEO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            //
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

internal fun MainActivity.setupGLSurfaceView() {
        glSurfaceView = findViewById(R.id.glSurfaceView)
        glSurfaceView.setEGLContextClientVersion(2)

        renderer = FormationRenderer()
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // 添加舞者标签覆盖层，覆盖在 OpenGL 视图上方显示舞者名称
        dancerLabelOverlay = findViewById(R.id.dancerLabelOverlay)
        dancerLabelOverlay.visibility = View.GONE

        // 初始化参考视频视图
        referenceVideoView = findViewById(R.id.referenceVideoView)
    }

internal fun MainActivity.getSidebarWidth(): Int {
        // 侧边栏宽度（Android端使用ConstraintLayout布局，此处返回0由布局系统管理）
        return 0
    }

internal fun MainActivity.setupDancerGrid() {
        val gridLayout = findViewById<GridLayout>(R.id.dancerGrid)

        // 创建5列4行的舞者网格（共20个格子）
        val columns = 5
        val rows = 4
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val cellIndex = row * columns + col
                val cellView = createGridCell(cellIndex)
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(col, 1f)
                    rowSpec = GridLayout.spec(row)
                    setMargins(1, 1, 1, 1)
                }
                cellView.layoutParams = params
                cellView.tag = cellIndex
                gridLayout.addView(cellView)
            }
        }
    }

internal fun MainActivity.createGridCell(index: Int): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                30.dpToPx()  // [中文注释]
            )

            // 创建圆形标记
            val circle = View(this@createGridCell).apply {
                id = View.generateViewId()
                layoutParams = FrameLayout.LayoutParams(
                    26.dpToPx(),  // [中文注释]
                    26.dpToPx()
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
                setBackgroundResource(R.drawable.circle_shape)
                setBackgroundColor(android.graphics.Color.parseColor("#3A3A4F"))
            }
            addView(circle)
            val styleImage = android.widget.ImageView(this@createGridCell).apply {
                layoutParams = FrameLayout.LayoutParams(
                    26.dpToPx(),
                    26.dpToPx()
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            addView(styleImage)

            //
            val number = TextView(this@createGridCell).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
                text = ""
                textSize = 10f  // [中文注释]
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }
            addView(number)

            // 点击选中/取消选中舞者
            setOnClickListener {
                val group = viewModel.currentGroup.value
                val formation = group?.formations?.getOrNull(viewModel.currentFormationIndex.value)
                formation?.dancers?.getOrNull(index)?.let { dancer ->
                    viewModel.toggleDancerSelection(dancer.id)
                }
            }

            // 长按弹出编辑/删除对话框
            setOnLongClickListener {
                // 如果正在删除中，忽略长按操作
                if (isDeletingDancer) {
                    android.util.Log.d("MainActivity", "长按事件：正在删除中，忽略操作")
                    return@setOnLongClickListener true
                }

                val group = viewModel.currentGroup.value
                val formation = group?.formations?.getOrNull(viewModel.currentFormationIndex.value)
                formation?.dancers?.getOrNull(index)?.let { dancer ->
                    showEditOrDeleteDialog(dancer)
                }
                true
            }
        }
    }

internal fun MainActivity.updateDancerGrid(dancers: List<com.formationtool.app.model.Dancer>) {
        runOnUiThread {
            try {
                val gridLayout = findViewById<GridLayout>(R.id.dancerGrid)
                if (gridLayout == null) {
                    android.util.Log.e("MainActivity", "updateDancerGrid: gridLayout is null")
                    return@runOnUiThread
                }

                val dancersCopy = dancers.toList()

                for (i in 0 until gridLayout.childCount) {
                    val cell = gridLayout.getChildAt(i) as? FrameLayout ?: continue

                    if (i < dancersCopy.size) {
                        val dancer = dancersCopy[i]
                        val circle = cell.getChildAt(0) as? View
                        val styleImage = cell.getChildAt(1) as? android.widget.ImageView
                        val number = cell.getChildAt(2) as? TextView

                        val customStylePath = dancer.customStylePath
                        val hasCustomStyle = !customStylePath.isNullOrEmpty() && java.io.File(customStylePath).exists()
                        val dancerNumber = dancer.name.replace("舞者", "").ifEmpty { "?" }

                        if (hasCustomStyle) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(customStylePath)
                            styleImage?.setImageBitmap(bitmap)
                            styleImage?.visibility = View.VISIBLE
                            circle?.visibility = View.INVISIBLE
                            number?.visibility = View.GONE
                        } else {
                            styleImage?.setImageDrawable(null)
                            styleImage?.visibility = View.GONE
                            circle?.visibility = View.VISIBLE
                            circle?.setBackgroundResource(R.drawable.circle_shape)
                            (circle?.background as? android.graphics.drawable.GradientDrawable)?.setColor(dancer.color)
                            number?.text = dancerNumber
                            number?.visibility = View.VISIBLE
                        }

                        if (dancer.isSelected) {
                            if (hasCustomStyle) {
                                styleImage?.background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.OVAL
                                    setStroke(3, android.graphics.Color.WHITE)
                                    setColor(android.graphics.Color.TRANSPARENT)
                                }
                            } else {
                                circle?.apply {
                                    setPadding(3, 3, 3, 3)
                                    background = android.graphics.drawable.LayerDrawable(
                                        arrayOf(
                                            android.graphics.drawable.GradientDrawable().apply {
                                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                                setColor(dancer.color)
                                            },
                                            android.graphics.drawable.GradientDrawable().apply {
                                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                                setStroke(4, android.graphics.Color.WHITE)
                                                setColor(android.graphics.Color.TRANSPARENT)
                                            }
                                        )
                                    )
                                }
                            }
                        } else {
                            circle?.setPadding(0, 0, 0, 0)
                            styleImage?.background = null
                        }

                        cell.visibility = View.VISIBLE
                        cell.isEnabled = true
                    } else {
                        val circle = cell.getChildAt(0) as? View
                        val styleImage = cell.getChildAt(1) as? android.widget.ImageView
                        val number = cell.getChildAt(2) as? TextView
                        circle?.visibility = View.INVISIBLE
                        styleImage?.setImageDrawable(null)
                        styleImage?.visibility = View.INVISIBLE
                        number?.text = ""
                        number?.visibility = View.INVISIBLE
                        cell.visibility = View.VISIBLE
                        cell.isEnabled = false
                    }
                }

                val hasSelection = dancersCopy.any { it.isSelected }
                val fab = findViewById<FloatingActionButton>(R.id.fabClearSelection)
                fab?.visibility = if (hasSelection) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "updateDancerGrid error", e)
            }
        }
    }
internal fun MainActivity.setupUI() {
        // 切换侧边栏菜单显示/隐藏
        findViewById<FloatingActionButton>(R.id.fabSettings).setOnClickListener {
            toggleMenu()
        }

        //
        findViewById<FloatingActionButton>(R.id.fabExport).setOnClickListener {
            showExportDialog()
            toggleMenu()  // [中文注释]
        }

        // 添加舞者按钮点击事件
        findViewById<FloatingActionButton>(R.id.fabStageSize).setOnClickListener {
            showStageSizeDialog()
            toggleMenu()  // [中文注释]
        }

        // 删除舞者按钮点击事件
        findViewById<FloatingActionButton>(R.id.fabProject).setOnClickListener {
            showProjectMenu()
            toggleMenu()  // [中文注释]
        }

        // 加载组按钮点击事件
        findViewById<Button>(R.id.btnAddDancerQuick).setOnClickListener {
            showAddDancerDialog()
        }

        //
        findViewById<Button>(R.id.btnFormationPresets).setOnClickListener {
            showFormationPresetsDialog()
        }

        //
        val btnToggleView = findViewById<Button>(R.id.btnToggleView)
        btnToggleView.setOnClickListener {
            is3DMode = !is3DMode
            btnToggleView.text = if (is3DMode) "3D" else "2D"

            // 导出按钮点击事件，检查并请求存储权限
            renderer.is2DMode = !is3DMode

            //
            if (is3DMode) {
                // 3D视图下需要设置渲染器并请求渲染
                viewModel.updateCamera(pitch = -30f, yaw = 0f, distance = 15f, targetX = 0f, targetY = 0f)
            } else {
                // 2D视图下需要重绘画布
                viewModel.updateCamera(pitch = -89f, yaw = 0f, distance = 20f, targetX = 0f, targetY = 0f)
            }
        }

        //
        findViewById<Button>(R.id.btnSnapToGrid).setOnClickListener {
            viewModel.snapDancersToGrid()
        }

        // 重置播放状态按钮点击事件
        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            if (viewModel.canUndo()) {
                viewModel.undo()
                // 撤销操作成功后显示Toast提示
            } else {
                // 撤销操作失败（无可撤销内容）显示Toast
            }
        }

        // 监听当前队形索引变化，更新UI
        findViewById<Button>(R.id.btnCopyPrevFrame)?.setOnClickListener {
            copyPreviousKeyframe()
        }

        //
        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            togglePreviewMode()
        }

        // 加载组对话框 - 列出所有可用的组并让用户选择
        val fabClearSelection = findViewById<FloatingActionButton>(R.id.fabClearSelection)
        fabClearSelection.setOnClickListener { v ->
            android.util.Log.d("MainActivity", "加载组按钮点击，打开组选择对话框")
            viewModel.clearSelection()
            v.visibility = View.GONE  // 隐藏菜单
        }
        // 选择对应的组并加载
        fabClearSelection.isClickable = true
        fabClearSelection.isFocusable = true

        // 处理舞者网格中舞者的选中状态
        val btnToggleVideoPanel = findViewById<ImageButton>(R.id.btnToggleVideoPanel)
        val videoPanel = findViewById<LinearLayout>(R.id.videoPanel)
        var isVideoPanelVisible = true

        btnToggleVideoPanel.setOnClickListener {
            isVideoPanelVisible = !isVideoPanelVisible
            if (isVideoPanelVisible) {
                videoPanel.visibility = View.VISIBLE
                btnToggleVideoPanel.setImageResource(android.R.drawable.ic_media_previous)
            } else {
                videoPanel.visibility = View.GONE
                btnToggleVideoPanel.setImageResource(android.R.drawable.ic_media_next)
            }
        }

        // 显示导出格式选择对话框，支持多种视频格式
        val btnToggleDancerPanel = findViewById<ImageButton>(R.id.btnToggleDancerPanel)
        val controlPanel = findViewById<LinearLayout>(R.id.controlPanel)

        btnToggleDancerPanel.setOnClickListener {
            isSidebarVisible = !isSidebarVisible
            if (isSidebarVisible) {
                controlPanel.visibility = View.VISIBLE
                btnToggleDancerPanel.setImageResource(android.R.drawable.ic_media_next)
            } else {
                controlPanel.visibility = View.GONE
                btnToggleDancerPanel.setImageResource(android.R.drawable.ic_media_previous)
            }
            // 处理音频文件导入，包括复制文件和初始化播放器
            updateTimelineZoom()
        }

        // 注册音频文件选择器结果回调，处理文件URI和权限
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            checkAndroidUpdate()
        }

        findViewById<Button>(R.id.btnDeleteVideo).setOnClickListener {
            showDeleteConfirmDialog()
        }

        // 获取音频文件信息并初始化播放器
        findViewById<FrameLayout>(R.id.videoContainer).setOnClickListener {
            if (!hasReferenceVideo) {
                importVideo()
            }
        }
    }

internal fun MainActivity.checkAndroidUpdate() {
        val activity = this
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "检查"

        lifecycleScope.launch {
            try {
                val updateInfo = AppUpdateChecker.checkForUpdate(activity, showFailure = true)
                if (updateInfo == null) {
                    Toast.makeText(activity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                } else {
                    AppUpdateChecker.showUpdateDialog(activity, updateInfo)
                }
            } finally {
                btnCheckUpdate.text = "更新"
                btnCheckUpdate.isEnabled = true
            }
        }
    }

internal fun MainActivity.importAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            // 支持的音频格式：MP3, WAV, OGG, M4A, FLAC
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "audio/mpeg",           // MP3
                "audio/wav",            // WAV
                "audio/x-wav",          // WAV
                "audio/mp4",            // M4A
                "audio/x-m4a",          // M4A
                "audio/flac",           // FLAC
                "audio/x-flac",         // FLAC
                "audio/aac",            // AAC
                "audio/ogg",            // OGG
                "audio/opus",           // OPUS
                "audio/x-ms-wma",       // WMA
                "audio/amr",            // AMR
                "audio/*"               // 所有音频文件类型
            ))
        }
        audioPickerLauncher.launch(intent)
    }

internal fun MainActivity.loadPresetGroup(groupName: String, dancers: List<DancerPreset>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("加载组 $groupName")
            .setMessage("是否加载组 $groupName（共 ${dancers.size} 个舞者）？请注意，加载后当前未保存的更改将丢失。")
            .setPositiveButton("加载") { _, _ ->
                // 用户确认后执行加载：1.保存当前项目 2.清除撤销栈 3.通过ViewModel加载新组
                lifecycleScope.launch {
                    try {
                        // 1. 保存当前项目状态
                        viewModel.clearAllDancers()

                        // 2. 清除撤销历史栈
                        kotlinx.coroutines.delay(300)

                        // 3. 通过ViewModel加载选中的组，包含舞者位置和队形数据
                        viewModel.batchAddDancers(dancers.map {
                            Pair(it.name, it.color)
                        })

                        // 4. 重新初始化UI状态，包括重置音频播放器、更新舞者网格、刷新渲染
                        kotlinx.coroutines.delay(300)

                        runOnUiThread {
                            // 加载组完成后显示Toast通知用户，若失败则显示错误信息
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "加载组失败", e)
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@loadPresetGroup,
                                "加载组失败: ${e.message}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.importVideo() {
        // 初始化自动保存定时器，定期保存项目数据
        val hasAudio = currentAudioUri != null || viewModel.audioDuration.value > 0
        val hasVideo = hasReferenceVideo

        if (hasAudio && !hasVideo) {
            // 显示视频导出格式选择对话框
            MaterialAlertDialogBuilder(this)
                .setTitle("选择导出格式")
                .setMessage("选择导出格式。导出将在后台进行，完成后会通知您。请确保导入了音频/视频文件。导出范围将基于当前导入的音频/视频时长。")
                .setPositiveButton("确认") { _, _ ->
                    // 弹窗确认后开始导出
                    launchVideoImport()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 初始化视频文件选择器，用于导入参考视频
            launchVideoImport()
        }
    }

internal fun MainActivity.launchVideoImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            // 注册视频文件选择器的结果处理回调
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "video/mp4",            // MP4
                "video/quicktime",      // MOV
                "video/x-matroska",     // MKV
                "video/x-msvideo",      // AVI
                "video/x-flv",          // FLV
                "video/x-ms-wmv",       // WMV
                "video/3gpp",           // 3GP
                "video/webm",           // WEBM
                "video/mpeg",           // MPEG
                "video/*"               // 所有视频文件类型
            ))
        }
        videoPickerLauncher.launch(intent)
    }

internal fun MainActivity.showExportDialog() {
        val duration = viewModel.audioDuration.value
        if (duration <= 0) {
            android.widget.Toast.makeText(this, "请先导入音频后再导出。导出需要音频文件来确定时长和生成最终视频。", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        ExportDialogController.show(
            context = this,
            hasReferenceVideo = hasReferenceVideo,
            initialFlipped = renderer.flipped,
            showDancerNames = renderer.showDancerNames
        ) { config ->
            startExportWithConfig(config)
        }
    }

internal fun MainActivity.observeViewModel() {
        lifecycleScope.launch {
            viewModel.currentGroup.collect { group ->
                if (group == null) {
                    // 处理参考视频导入流程
                    runOnUiThread {
                        findViewById<FloatingActionButton>(R.id.fabClearSelection).visibility = View.GONE
                    }
                    return@collect
                }

                renderer.stageWidth = group.stageWidth
                renderer.stageDepth = group.stageDepth
                renderer.updateStageSize(group.stageWidth, group.stageDepth)

                // 获取视频时长信息
                val currentFormation = group.formations.getOrNull(viewModel.currentFormationIndex.value)
                currentFormation?.let { formation ->
                    // 获取视频时长，设置导出起止时间，并同步到ViewModel
                    updateDancerGrid(formation.dancers)
                }

                // 获取视频时长并设置导入和导出范围
                refreshRendererState()

                // 获取视频文件时长（毫秒）
                updateKeyframeLabels(group.formations)

                // 获取视频文件时长并传入回调
                waveformView.formations = group.formations
                waveformView.audioDuration = viewModel.audioDuration.value
            }
        }

        lifecycleScope.launch {
            viewModel.currentFormationIndex.collect { index ->
                val group = viewModel.currentGroup.value
                group?.let {
                    if (index in it.formations.indices) {
                        // 同步 renderer.currentFormation 和 nextFormation
                        // 通过 currentTime.collect 监听时间变化并更新渲染器的当前队形和下一个队形

                        //
                        updateDancerGrid(it.formations[index].dancers)

                        // 获取视频文件时长并更新导出参数
                        updateKeyframeLabels(it.formations)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.currentTime.collect { time ->
                // 获取视频文件时长并设置导入范围
                findViewById<TextView>(R.id.tvCurrentTime).text = formatTime(time)

                // 注册视频文件选择器的结果处理
                refreshRendererState()
            }
        }

        lifecycleScope.launch {
            viewModel.audioDuration.collect { duration ->
                findViewById<TextView>(R.id.tvDuration).text = formatTime(duration)
                // 初始化音频播放控制，包括创建MediaPlayer、设置进度条和播放/暂停按钮
                if (duration > 0) {
                    updateTimelineZoom()
                }
                // 获取视频文件时长并传入回调方法
                waveformView.audioDuration = duration
                // 获取视频文件时长并设置导入和导出范围
                val exportRangeView = findViewById<com.formationtool.app.view.ExportRangeView>(R.id.exportRangeView)
                exportRangeView.audioDuration = duration
                exportOutPointMs = duration  // 设置导出终点为视频时长
            }
        }

        lifecycleScope.launch {
            viewModel.cameraView.collect { camera ->
                renderer.cameraView = camera
            }
        }

        lifecycleScope.launch {
            viewModel.isPlaying.collect { isPlaying ->
                if (isPlaying) {
                    startAnimation()
                }
            }
        }

        audioPlayingObserverJob = lifecycleScope.launch {
            audioManager.isPlaying.collect { isPlaying ->
                findViewById<Button>(R.id.btnAudioPlayPause).text = if (isPlaying) "⏸" else "▶"

                // 切换时更新按钮图标和状态
                val fabFKey = findViewById<Button>(R.id.fabFKey)
                fabFKey.isEnabled = isPlaying
                fabFKey.alpha = if (isPlaying) 1.0f else 0.5f

                // 更新音频进度条和时间显示，格式化为 当前时间/总时长
                if (!isPlaying && keyframeController?.isScanning == true) {
                    keyframeController?.handleFKeyMarkOnPause()
                }
                if (!isPlaying && viewModel.isPlaying.value) {
                    viewModel.stopPlayback()
                    if (audioManager.currentPosition.value == 0L) {
                        viewModel.updateTime(0L)
                    }
                }
            }
        }

        waveformObserverJob = lifecycleScope.launch {
            audioManager.waveformData.collect { data ->
                waveformView.waveformData = data
                updateTimelineZoom()
            }
        }
    }

internal fun MainActivity.startAnimation() {
        lifecycleScope.launch {
            while (viewModel.isPlaying.value) {
                val currentTime = viewModel.currentTime.value
                viewModel.updateTime(currentTime + 16)
                kotlinx.coroutines.delay(16)
            }
        }
    }

internal fun MainActivity.showAddDancerDialog() {
        val presetOptions = listOf(
            DancerDialogController.PresetOption("Muse") { loadPresetGroup("Muse", museDancers) },
            DancerDialogController.PresetOption("Aqours") { loadPresetGroup("Aqours", aqoursDancers) },
            DancerDialogController.PresetOption("Niji") { loadPresetGroup("Niji", nijiDancers) },
            DancerDialogController.PresetOption("Liella") { loadPresetGroup("Liella", liellaDancers) },
            DancerDialogController.PresetOption("Hasunosora") { loadPresetGroup("Hasunosora", hasunosoraDancers) }
        )

        DancerDialogController.showAddDancerDialog(
            context = this,
            hasMedia = currentAudioUri != null || hasReferenceVideo,
            presetColors = presetColors,
            presetOptions = presetOptions,
            onPickColor = this::showColorPickerDialog,
            onAddDancer = { name, color -> viewModel.addDancer(name, color) }
        )
    }

internal fun MainActivity.showColorPickerDialog(initialColor: Int, onColorSelected: (Int) -> Unit) {
        val colorPickerView = com.formationtool.app.view.ColorPickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600.dpToPx()
            )
            setColor(initialColor)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("选择预设舞者方案")
            .setView(colorPickerView)
            .setPositiveButton("确认") { _, _ ->
                onColorSelected(colorPickerView.getColor())
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.showStageSizeDialog() {
        StageSettingsDialogController.show(
            context = this,
            group = viewModel.currentGroup.value,
            renderer = renderer,
            autoSnap = autoSnap,
            onStageSizeChanged = { width, depth -> viewModel.updateStageSize(width, depth) },
            onAutoSnapChanged = { enabled -> autoSnap = enabled }
        )
    }

internal fun MainActivity.toggleMenu() {
        isMenuExpanded = !isMenuExpanded

        val fabExport = findViewById<FloatingActionButton>(R.id.fabExport)
        val fabStageSize = findViewById<FloatingActionButton>(R.id.fabStageSize)
        val fabProject = findViewById<FloatingActionButton>(R.id.fabProject)
        val fabSettings = findViewById<FloatingActionButton>(R.id.fabSettings)

        if (isMenuExpanded) {
            // 设置舞者数量的辅助方法
            fabExport.visibility = View.VISIBLE
            fabStageSize.visibility = View.VISIBLE
            fabProject.visibility = View.VISIBLE

            // 计算缩放比例：舞台宽度/舞者间距 + 边距补偿系数0.8
            fabExport.animate().scaleX(0.8f).scaleY(0.8f).alpha(1f).setDuration(200).start()
            fabStageSize.animate().scaleX(0.8f).scaleY(0.8f).alpha(1f).setDuration(200).start()
            fabProject.animate().scaleX(0.8f).scaleY(0.8f).alpha(1f).setDuration(200).start()

            // 最少保留5个舞者
            fabSettings.animate().rotation(45f).setDuration(200).start()
        } else {
            // 计算舞者间距并设置缩放，使所有舞者均匀分布于舞台，缩放系数0.4
            fabExport.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(200)
                .withEndAction { fabExport.visibility = View.GONE }.start()
            fabStageSize.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(200)
                .withEndAction { fabStageSize.visibility = View.GONE }.start()
            fabProject.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(200)
                .withEndAction { fabProject.visibility = View.GONE }.start()

            // 最少保留5个舞者（向上取整）
            fabSettings.animate().rotation(0f).setDuration(200).start()
        }
    }

internal fun MainActivity.showFormationPresetsDialog() {
        // 初始化自动保存任务，定期执行数据持久化
        if (currentAudioUri == null && !hasReferenceVideo) {
            android.widget.Toast.makeText(this, "音频未就绪，请先导入音频/视频文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val presetCounts = intArrayOf(3, 5, 7, 9, 11)
        val options = presetCounts.map { "设置为 $it 人" }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("选择预设舞者方案")
            .setItems(options) { _, which ->
                val targetCount = presetCounts.getOrNull(which) ?: return@setItems
                setDancerCount(targetCount)
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.copyPreviousKeyframe() {
        FormationActionController.copyPreviousKeyframe(
            context = this,
            viewModel = viewModel,
            audioManager = audioManager,
            lifecycleScope = lifecycleScope,
            onScrollToTime = this::scrollToTime,
            onSyncReferenceVideo = this::syncReferenceVideoPosition
        )
    }

internal fun MainActivity.setDancerCount(targetCount: Int) {
        FormationActionController.setDancerCount(
            context = this,
            viewModel = viewModel,
            lifecycleScope = lifecycleScope,
            targetCount = targetCount,
            getDefaultColor = this::getDefaultColor
        )
    }

internal fun MainActivity.getDefaultColor(index: Int): Int {
        val colors = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(), 0xFF9900FF.toInt(), 0xFFFF99CC.toInt(), 0xFFFF6FCF.toInt(), 0xFFFF3333.toInt(),
            0xFFFF8800.toInt(), 0xFFFFDD00.toInt(), 0xFF88CC66.toInt(), 0xFF00D0B1.toInt(), 0xFF5AC7FA.toInt(),
            0xFF4682B4.toInt(), 0xFF9966CC.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8C8C8.toInt(), 0xFF888888.toInt()
        )
        return colors[index % colors.size]
    }

internal fun MainActivity.showEditOrDeleteDialog(dancer: com.formationtool.app.model.Dancer) {
        DancerDialogController.showEditOrDeleteDialog(
            context = this,
            dancer = dancer,
            onEdit = { showEditDancerDialog(dancer) },
            onDelete = { showDeleteDancerDialog(dancer) }
        )
    }

internal fun MainActivity.showEditDancerDialog(dancer: com.formationtool.app.model.Dancer) {
        DancerDialogController.showEditDancerDialog(
            context = this,
            dancer = dancer,
            onPickColor = this::showColorPickerDialog,
            onUpdateDancer = { dancerId, name, color -> viewModel.updateDancer(dancerId, name, color) },
            onUploadStyle = { dancerId -> openDancerStylePicker(dancerId) },
            onClearStyle = { dancerId -> clearDancerStyle(dancerId) },
            onPreviewStyle = { dancerId -> previewDancerStyle(dancerId) }
        )
    }

internal fun MainActivity.openDancerStylePicker(dancerId: String) {
        pendingStyleDancerId = dancerId
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        dancerStylePickerLauncher.launch(intent)
    }

internal fun MainActivity.previewDancerStyle(dancerId: String) {
        val group = viewModel.currentGroup.value ?: return
        val formation = group.formations.getOrNull(viewModel.currentFormationIndex.value) ?: return
        val dancer = formation.dancers.find { it.id == dancerId } ?: return
        val stylePath = dancer.customStylePath
        if (stylePath.isNullOrEmpty() || !java.io.File(stylePath).exists()) {
            android.widget.Toast.makeText(this, "No style to preview", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val preview = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = 700
            maxWidth = 700
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(stylePath))
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Style Preview")
            .setView(preview)
            .setPositiveButton("Close", null)
            .show()
    }

internal fun MainActivity.clearDancerStyle(dancerId: String) {
        val group = viewModel.currentGroup.value ?: return
        val groupId = currentProjectId ?: group.id
        dataManager.deleteDancerStyle(groupId, dancerId)
        viewModel.updateDancerStyle(dancerId, null)
        glSurfaceView.requestRender()
        group.formations.getOrNull(viewModel.currentFormationIndex.value)?.let {
            updateDancerGrid(it.dancers)
        }
        android.widget.Toast.makeText(this, "Style cleared", android.widget.Toast.LENGTH_SHORT).show()
    }

internal fun MainActivity.handleDancerStyleImage(uri: android.net.Uri) {
        val dancerId = pendingStyleDancerId ?: return
        pendingStyleDancerId = null

        try {
            val sourceBitmap = contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
            if (sourceBitmap == null) {
                android.widget.Toast.makeText(this, "保存成功", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val cropView = com.formationtool.app.view.CropImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    900
                )
                setImageBitmap(sourceBitmap)
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("保存项目")
                .setView(cropView)
                .setPositiveButton("保存") { _, _ ->
                    val group = viewModel.currentGroup.value
                    val groupId = currentProjectId ?: group?.id
                    if (groupId.isNullOrEmpty()) {
                        android.widget.Toast.makeText(this, "项目已保存到: ${filesDir.absolutePath}", android.widget.Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val cropped = cropView.getCroppedBitmap(512)
                    val savedPath = dataManager.saveDancerStyleBitmap(groupId, dancerId, cropped)
                    if (savedPath != null) {
                        viewModel.updateDancerStyle(dancerId, savedPath)
                        glSurfaceView.requestRender()
                        group?.formations?.getOrNull(viewModel.currentFormationIndex.value)?.let {
                            updateDancerGrid(it.dancers)
                        }
                        android.widget.Toast.makeText(this, "导出开始...", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(this, "导出完成", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "handleDancerStyleImage failed", e)
            android.widget.Toast.makeText(this, "导出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

internal fun MainActivity.showDeleteDancerDialog(dancer: com.formationtool.app.model.Dancer) {
        DancerDialogController.showDeleteDancerDialog(
            context = this,
            dancer = dancer,
            lifecycleScope = lifecycleScope,
            isDeleting = { isDeletingDancer },
            setDeleting = { value -> isDeletingDancer = value },
            onDeleteDancer = { dancerId -> viewModel.deleteDancer(dancerId) }
        )
    }

internal fun MainActivity.handleAudioFile(uri: Uri) {
        // 显示保存对话框，让用户输入项目名称
        if (currentProjectId == null) {
            showProjectNameDialogForMediaImport(uri, isVideo = false)
        } else {
            loadAudioAfterProjectReady(uri)
        }
    }

internal fun MainActivity.showProjectNameDialogForMediaImport(uri: Uri, isVideo: Boolean) {
        val input = EditText(this).apply {
            hint = "输入项目名称"
            setText("新项目")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("保存项目")
            .setMessage("请输入项目名称。项目数据（包含队形、舞者和音频设置）将保存到应用目录中。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val projectName = input.text.toString().ifEmpty { "新项目" }
                // 保存项目逻辑：使用dataManager保存，更新项目ID
                viewModel.createNewGroup(projectName, 0, 16f, 12f)

                // 创建保存目录和文件
                saveNewProjectForMediaImport(projectName)

                // 保存到指定项目名称目录
                if (isVideo) {
                    loadVideoAfterProjectReady(uri)
                } else {
                    loadAudioAfterProjectReady(uri)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                // 取消后不保存，直接继续
                if (isVideo) {
                    referenceVideoUri = null
                } else {
                    currentAudioUri = null
                }
            }
            .setCancelable(false)
            .show()
    }

internal fun MainActivity.loadAudioAfterProjectReady(uri: Uri) {
        val success = audioManager.loadAudio(uri)
        if (success) {
            currentAudioUri = uri  // 保存音频URI
            val duration = audioManager.duration.value
            viewModel.setAudioPath(uri.toString(), duration)

            // 获取文件名
            val fileName = uri.lastPathSegment ?: "未知音频文件"

            // 设置视频文件时长并更新导出参数
            findViewById<TextView>(R.id.tvAudioNameTimeline).text = fileName

            // 显示保存对话框，并在保存完成后执行回调
            findViewById<Button>(R.id.btnDeleteVideo).visibility = View.VISIBLE

            // 检查参考视频文件是否存在
            audioManager.seekTo(0L)
            viewModel.seekTo(0L)
            scrollToTime(0L)
        }
    }

internal fun MainActivity.handleVideoFile(uri: Uri) {
        // 显示保存对话框，让用户输入项目名称并保存
        if (currentProjectId == null) {
            showProjectNameDialogForMediaImport(uri, isVideo = true)
        } else {
            loadVideoAfterProjectReady(uri)
        }
    }

internal fun MainActivity.loadVideoAfterProjectReady(uri: Uri) {
        try {
            // 保存音频URI
            referenceVideoUri = uri
            currentAudioUri = uri  // 保存音频URI

            // 创建保存目录并写入项目数据
            hasReferenceVideo = true
            findViewById<TextView>(R.id.tvNoVideo).visibility = View.GONE
            findViewById<Button>(R.id.btnDeleteVideo).visibility = View.VISIBLE

            // 初始化videoView参考视频播放器
            referenceVideoView.visibility = View.VISIBLE

            // 1. 保存当前项目到指定目录
            referenceVideoView.setVideoURI(uri)
            referenceVideoView.setOnPreparedListener { mp ->
                // 更新视频导出范围和音频URI
                mp.setVolume(0f, 0f)
            }

            // 2. 清除撤销历史记录
            val success = audioManager.loadAudio(uri)
            if (success) {
                val duration = audioManager.duration.value
                viewModel.setAudioPath(uri.toString(), duration)

                // 获取文件名
                val fileName = uri.lastPathSegment ?: "未知视频文件"

                // 检查参考视频文件是否存在并设置预览
                audioManager.seekTo(0L)
                viewModel.seekTo(0L)
                referenceVideoView.seekTo(0)
                scrollToTime(0L)
            }

            // 3. 清除数据并重新初始化UI
            videoPlaybackSyncJob?.cancel()
            videoPlaybackSyncJob = lifecycleScope.launch {
                audioManager.isPlaying.collect { isPlaying ->
                    if (hasReferenceVideo) {
                        if (isPlaying) {
                            referenceVideoView.start()
                        } else {
                            referenceVideoView.pause()
                        }
                    }
                }
            }

            // 4. 设置同步播放参考视频的位置
            videoSeekSyncJob?.cancel()
            videoSeekSyncJob = lifecycleScope.launch {
                viewModel.currentTime.collect { time ->
                    if (hasReferenceVideo && !audioManager.isPlaying.value) {
                        // 同步referenceVideoView到当前播放位置
                        referenceVideoView.seekTo(time.toInt())
                    }
                }
            }

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "视频导入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

internal fun MainActivity.startAutoSave() {
        // 注册自动保存的定时器，使用Handler定时执行
        stopAutoSave()

        autoSaveJob = lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_SAVE_INTERVAL)

                // 执行自动保存操作
                performAutoSave()
            }
        }

        android.util.Log.d("MainActivity", "自动保存定时器已启动，间隔 ${AUTO_SAVE_INTERVAL / 1000}秒")
    }

internal fun MainActivity.stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        android.util.Log.d("MainActivity", "自动保存定时器已停止")
    }

internal fun MainActivity.performAutoSave() {
        val group = viewModel.currentGroup.value
        if (group == null) {
            android.util.Log.d("MainActivity", "performAutoSave: group is null, skip")
            return
        }

        // 使用projectId或默认值作为保存路径
        if (currentProjectId == null) {
            android.util.Log.d("MainActivity", "performAutoSave: 正在保存项目数据...")
            return
        }

        // 获取视频文件时长并设置导入范围
        group.referenceVideoUri = referenceVideoUri?.toString()
        group.audioUri = currentAudioUri?.toString()
        group.modifiedTime = System.currentTimeMillis()

        //
        val success = dataManager.saveDanceGroup(group)
        if (success) {
            viewModel.clearDirty()
            android.util.Log.d("MainActivity", "performAutoSave: 自动保存完成 - ${group.name}")
        } else {
            android.util.Log.e("MainActivity", "performAutoSave: 自动保存失败")
        }
    }

internal fun MainActivity.togglePreviewMode() {
        isPreviewMode = !isPreviewMode

        val controlPanel = findViewById<LinearLayout>(R.id.controlPanel)
        val btnToggleVideoPanel = findViewById<ImageButton>(R.id.btnToggleVideoPanel)
        val btnToggleDancerPanel = findViewById<ImageButton>(R.id.btnToggleDancerPanel)
        val videoPanel = findViewById<LinearLayout>(R.id.videoPanel)
        val bottomPanel = findViewById<LinearLayout>(R.id.bottomPanel)
        val previewControlBar = findViewById<LinearLayout>(R.id.previewControlBar)
        val fabExport = findViewById<FloatingActionButton>(R.id.fabExport)
        val fabClearSelection = findViewById<FloatingActionButton>(R.id.fabClearSelection)
        val btnPreview = findViewById<Button>(R.id.btnPreview)
        val viewportContainer = findViewById<FrameLayout>(R.id.viewportContainer)

        if (isPreviewMode) {
            // 初始化预览模式UI，包括预览按钮和进度条的创建和事件绑定
            controlPanel.visibility = View.GONE
            videoPanel.visibility = View.GONE
            btnToggleVideoPanel.visibility = View.GONE
            btnToggleDancerPanel.visibility = View.GONE
            bottomPanel.visibility = View.GONE
            previewControlBar.visibility = View.VISIBLE
            fabExport.visibility = View.GONE
            fabClearSelection.visibility = View.GONE
            btnPreview.text = "退出"

            // 预览模式下隐藏其他控件，仅显示预览相关UI
            glSurfaceView.queueEvent {
                renderer.showDancerNames = true
            }

            // 更新预览进度条范围和位置
            val params = viewportContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomToTop = R.id.previewControlBar
            viewportContainer.layoutParams = params

            // 设置预览播放/暂停按钮状态
            setupPreviewControls()
        } else {
            // 预览模式切换时更新UI显示状态
            controlPanel.visibility = if (isSidebarVisible) View.VISIBLE else View.GONE
            videoPanel.visibility = View.VISIBLE
            btnToggleVideoPanel.visibility = View.VISIBLE
            btnToggleDancerPanel.visibility = View.VISIBLE
            bottomPanel.visibility = View.VISIBLE
            previewControlBar.visibility = View.GONE
            fabExport.visibility = View.VISIBLE
            btnPreview.text = "预览"

            // 预览模式切换时更新相关控件的可见性
            glSurfaceView.queueEvent {
                renderer.showDancerNames = false
            }

            // 设置预览区域底部约束 guidelineBottom
            val params = viewportContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomToTop = R.id.guidelineBottom
            viewportContainer.layoutParams = params
        }
    }

internal fun MainActivity.setupPreviewControls() {
        val btnPlayPause = findViewById<Button>(R.id.btnPreviewPlayPause)
        val seekBar = findViewById<SeekBar>(R.id.previewSeekBar)
        val tvTime = findViewById<TextView>(R.id.tvPreviewTime)
        val duration = viewModel.audioDuration.value

        // 设置 SeekBar 进度条的监听器
        seekBar.max = duration.toInt()

        // 更新音频播放进度和时间显示
        btnPlayPause.setOnClickListener {
            if (audioManager.isPlaying.value) {
                audioManager.pause()
                btnPlayPause.text = "Pause"
            } else {
                audioManager.play()
                btnPlayPause.text = "Play"
            }
        }

        //
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val timeMs = progress.toLong()
                    viewModel.seekTo(timeMs)
                    audioManager.seekTo(timeMs)
                    tvTime.text = "${formatTime(timeMs)} / ${formatTime(duration)}"
                    // 设置数据管理器的存储路径和文件操作
                    syncReferenceVideoPosition(timeMs)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 获取视频文件时长并设置导入范围
        lifecycleScope.launch {
            while (isPreviewMode) {
                val currentTime = viewModel.currentTime.value
                tvTime.text = "${formatTime(currentTime)} / ${formatTime(duration)}"
                seekBar.progress = currentTime.toInt()
                kotlinx.coroutines.delay(100)
            }
        }
    }
