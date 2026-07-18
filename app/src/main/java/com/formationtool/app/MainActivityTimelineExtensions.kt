package com.formationtool.app

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.formationtool.app.controller.KeyframeController
import com.formationtool.app.viewmodel.moveFormationTimestamp
import com.formationtool.app.viewmodel.updateFormationHoldDuration
import com.formationtool.app.viewmodel.updateFormationTransitionDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs


internal fun MainActivity.setupTimeline() {
        waveformView = findViewById(R.id.waveformView)
        timelineContent = findViewById(R.id.timelineContent)
        val timelineScrollView = findViewById<HorizontalScrollView>(R.id.timelineScrollView)
        val btnAudioPlayPause: Button = findViewById(R.id.btnAudioPlayPause)
        val exportRangeView = findViewById<com.formationtool.app.view.ExportRangeView>(R.id.exportRangeView)

        // 设置导出范围选择条回调
        exportRangeView.onRangeChanged = { inMs, outMs ->
            // 存储导出范围，导出时使用
            exportInPointMs = inMs
            exportOutPointMs = outMs
        }

        // 设置边界拖动回调（绿色后边界）
        waveformView.onBoundaryAdjust = { formationIndex, isHoldBoundary, newDuration ->
            if (isHoldBoundary) {
                viewModel.updateFormationHoldDuration(formationIndex, newDuration)
            } else {
                viewModel.updateFormationTransitionDuration(formationIndex, newDuration)
            }
        }

        // 设置起始边界拖动回调（绿色前边界，移动关键帧位置）
        waveformView.onStartBoundaryAdjust = { formationIndex, newTimestamp ->
            viewModel.moveFormationTimestamp(formationIndex, newTimestamp)
        }

        // 设置长按区域回调（长按绿色/红色区调整时长）
        waveformView.onRegionLongPress = { formationIndex, isHoldRegion ->
            viewModel.currentGroup.value?.let { group ->
                if (formationIndex in group.formations.indices) {
                    val formation = group.formations[formationIndex]
                    if (isHoldRegion) {
                        showAdjustHoldDurationDialog(formationIndex, formation)
                    } else {
                        showAdjustTransitionDurationDialog(formationIndex, formation)
                    }
                }
            }
        }

        // 点击导入音频（只在没有音频时有效）
        waveformView.onClickToImport = {
            importAudio()
        }

        // 设置边界拖动开始回调（保存撤销快照）
        waveformView.onBoundaryDragStart = {
            viewModel.saveUndoSnapshot()
        }

        // 禁用默认滚动，手动控制
        timelineScrollView.isHorizontalScrollBarEnabled = false

        // 时间轴缩放和滚动手势
        val timelineScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 双指缩放
                val oldZoom = timelineZoom
                val sidebarWidth = getSidebarWidth()
                val baseWidth = (resources.displayMetrics.widthPixels - sidebarWidth).toFloat().coerceAtLeast(1f)
                val oldScaledWidth = (baseWidth * oldZoom).coerceAtLeast(1f)
                val halfWidth = timelineScrollView.width / 2f

                // 以双指焦点为锚点，保持缩放前后的相对时间位置一致
                val focusContentX = timelineScrollView.scrollX + detector.focusX
                val focusWaveX = (focusContentX - halfWidth).coerceIn(0f, oldScaledWidth)
                val focusRatio = (focusWaveX / oldScaledWidth).coerceIn(0f, 1f)

                timelineZoom = (timelineZoom * detector.scaleFactor).coerceIn(1f, 15f)

                if (oldZoom != timelineZoom) {
                    updateTimelineZoom()

                    timelineScrollView.post {
                        val newScaledWidth = (baseWidth * timelineZoom).coerceAtLeast(1f)
                        val newFocusWaveX = focusRatio * newScaledWidth
                        val targetScrollX = (newFocusWaveX + halfWidth - detector.focusX).toInt()
                        val maxScrollX = kotlin.math.max(0, timelineContent.width - timelineScrollView.width)
                        timelineScrollView.scrollTo(targetScrollX.coerceIn(0, maxScrollX), 0)
                    }
                }
                return true
            }
        })

        var lastScrollX = 0f
        var isDragging = false
        var lastVideoSeekTime = 0L  // 用于节流视频seek
        timelineScrollView.setOnTouchListener { _, event ->
            when (event.pointerCount) {
                1 -> {
                    // 单指滚动
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastScrollX = event.rawX  // 使用屏幕绝对坐标
                            isDragging = true
                            isScrollingProgrammatically = false  // 用户手动滚动
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isDragging) {
                                val deltaX = lastScrollX - event.rawX

                                // 开始滚动时取消WaveformView的长按检测
                                if (kotlin.math.abs(deltaX) > 5) {
                                    waveformView.cancelPendingLongPress()
                                }

                                timelineScrollView.scrollBy(deltaX.toInt(), 0)
                                lastScrollX = event.rawX

                                // 实时更新播放位置和参考视频（带节流）
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastVideoSeekTime >= 50) {  // 50ms节流
                                    lastVideoSeekTime = currentTime
                                    updateTimeFromScrollRealtime()
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            // 取消WaveformView的长按检测
                            waveformView.cancelPendingLongPress()

                            if (isDragging) {
                                // 拖动结束时最终更新播放位置
                                updateTimeFromScroll()
                            }
                            isDragging = false
                            isScrollingProgrammatically = false
                            true
                        }
                        else -> false
                    }
                }
                2 -> {
                    // 双指缩放
                    isDragging = false  // 缩放时取消拖动
                    waveformView.cancelPendingLongPress()  // 取消长按
                    timelineScaleDetector.onTouchEvent(event)
                    true
                }
                else -> false
            }
        }

        // 音频播放/暂停
        btnAudioPlayPause.setOnClickListener {
            if (audioManager.isPlaying.value) {
                // 暂停时处理F模式结束
                keyframeController?.handleFKeyMarkOnPause()
                audioManager.pause()
            } else {
                // 播放前记录当前时间，确保平滑开始
                val currentTime = viewModel.currentTime.value
                audioManager.play()
                // 播放开始后立即同步一次位置，防止跳帧
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(50) // 等待播放真正开始
                    viewModel.seekTo(currentTime)
                }
            }
        }

        // 初始化F模式控制器
        keyframeController = KeyframeController(
            viewModel = viewModel,
            coroutineScope = lifecycleScope,
            waveformView = waveformView  // 传入波形视图用于实时预览
        ) {
            // 状态变化回调：刷新UI
            runOnUiThread {
                viewModel.currentGroup.value?.let { group ->
                    updateKeyframeLabels(group.formations)
                }
            }
        }

        // F按钮触摸事件（按下/松开）
        val fabFKey = findViewById<Button>(R.id.fabFKey)
        fabFKey.setOnTouchListener { v, event ->
            // 只有启用时才响应
            if (!fabFKey.isEnabled) return@setOnTouchListener false

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // 按下F键
                    keyframeController?.handleFKeyPressed()
                    // 按下时改变按钮颜色（绿色）
                    fabFKey.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#4CAF50")
                    )
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // 松开F键
                    keyframeController?.handleFKeyReleased()
                    // 恢复按钮颜色（橙色）
                    fabFKey.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#FF5722")
                    )
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        // 定时更新播放位置
        var lastUpdateTime = 0L  // 记录上次更新的时间，用于检测跳变
        lifecycleScope.launch {
            while (true) {
                if (audioManager.isPlaying.value) {
                    audioManager.updatePosition()
                    val position = audioManager.getCurrentPosition()

                    // 检测时间跳变（超过100ms），第一次播放时可能出现
                    val timeDelta = kotlin.math.abs(position - lastUpdateTime)
                    if (lastUpdateTime > 0 && timeDelta > 100) {
                        // 发现异常跳变，使用渐进式更新而不是立即跳转
                        val targetTime = position
                        val startTime = viewModel.currentTime.value
                        val smoothSteps = 3  // 分3步平滑过渡
                        for (step in 1..smoothSteps) {
                            val smoothTime = startTime + (targetTime - startTime) * step / smoothSteps
                            viewModel.seekTo(smoothTime)
                            kotlinx.coroutines.delay(16)
                        }
                    } else {
                        viewModel.seekTo(position)
                    }

                    lastUpdateTime = position

                    // 播放时自动滚动波形
                    scrollToTime(position)
                } else {
                    // 暂停时重置，准备下次播放
                    lastUpdateTime = 0L
                }
                kotlinx.coroutines.delay(16) // ~60fps
            }
        }
    }

internal fun MainActivity.updateTimelineZoom() {
        val duration = viewModel.audioDuration.value
        val timelineScrollView = findViewById<HorizontalScrollView>(R.id.timelineScrollView)
        val exportRangeView = findViewById<com.formationtool.app.view.ExportRangeView>(R.id.exportRangeView)

        // 确保View已经测量完成
        timelineScrollView.post {
            val halfWidth = timelineScrollView.width / 2

            if (duration > 0 && halfWidth > 0) {
                // 侧边栏宽度 200dp + 切换按钮 24dp + 边距 = 232
                val sidebarWidth = getSidebarWidth()
                val baseWidth = resources.displayMetrics.widthPixels - sidebarWidth
                val scaledWidth = (baseWidth * timelineZoom).toInt()

                // 添加左右padding，让指针能滑到头尾
                val totalWidth = scaledWidth + halfWidth * 2

                timelineContent.layoutParams.width = totalWidth
                timelineContent.setPadding(halfWidth, 0, halfWidth, 0)
                timelineContent.requestLayout()

                // 更新波形容器宽度（包含关键帧标签和波形）
                val waveformContainer = findViewById<LinearLayout>(R.id.waveformContainer)
                val containerParams = waveformContainer.layoutParams
                containerParams.width = scaledWidth
                waveformContainer.layoutParams = containerParams

                // 更新波形尺寸
                val waveParams = waveformView.layoutParams
                waveParams.width = scaledWidth
                waveformView.layoutParams = waveParams

                // 设置显式宽度，解决布局更新延迟导致触摸计算不准确的问题
                waveformView.setExplicitWidth(scaledWidth)
                exportRangeView.setExplicitWidth(scaledWidth)

                // 更新关键帧标签容器宽度
                val markersContainer = findViewById<FrameLayout>(R.id.keyframeMarkersContainer)
                val markersParams = markersContainer.layoutParams
                markersParams.width = scaledWidth
                markersContainer.layoutParams = markersParams

                // 等待布局完成后再更新关键帧标签位置
                markersContainer.post {
                    viewModel.currentGroup.value?.let { group ->
                        updateKeyframeLabels(group.formations)
                    }
                }
            }
        }
    }

internal fun MainActivity.updateTimeFromScroll() {
        val timelineScrollView = findViewById<HorizontalScrollView>(R.id.timelineScrollView)
        val duration = viewModel.audioDuration.value

        if (duration > 0) {
            val sidebarWidth = getSidebarWidth()
            val baseWidth = resources.displayMetrics.widthPixels - sidebarWidth
            val scaledWidth = baseWidth * timelineZoom
            val scrollX = timelineScrollView.scrollX

            // scrollX=0 时对应时间=0，scrollX=scaledWidth 时对应时间=duration
            val progress = (scrollX / scaledWidth).coerceIn(0f, 1f)
            val time = (duration * progress).toLong().coerceIn(0L, duration)

            viewModel.seekTo(time)
            audioManager.seekTo(time)

            // 同步参考视频位置
            syncReferenceVideoPosition(time)
        }
    }

internal fun MainActivity.updateTimeFromScrollRealtime() {
        val timelineScrollView = findViewById<HorizontalScrollView>(R.id.timelineScrollView)
        val duration = viewModel.audioDuration.value

        if (duration > 0) {
            val sidebarWidth = getSidebarWidth()
            val baseWidth = resources.displayMetrics.widthPixels - sidebarWidth
            val scaledWidth = baseWidth * timelineZoom
            val scrollX = timelineScrollView.scrollX

            val progress = (scrollX / scaledWidth).coerceIn(0f, 1f)
            val time = (duration * progress).toLong().coerceIn(0L, duration)

            // 只更新ViewModel时间（会触发动画插值）
            viewModel.seekTo(time)

            // 实时同步参考视频位置
            syncReferenceVideoPosition(time)
        }
    }

internal fun MainActivity.syncReferenceVideoPosition(timeMs: Long) {
        if (hasReferenceVideo && !audioManager.isPlaying.value) {
            val videoPos = timeMs.toInt()
            referenceVideoView.seekTo(videoPos)
        }
    }

internal fun MainActivity.scrollToTime(time: Long) {
        val timelineScrollView = findViewById<HorizontalScrollView>(R.id.timelineScrollView)
        val duration = viewModel.audioDuration.value

        if (duration > 0) {
            val sidebarWidth = getSidebarWidth()
            val baseWidth = resources.displayMetrics.widthPixels - sidebarWidth
            val scaledWidth = baseWidth * timelineZoom

            val progress = time.toFloat() / duration.toFloat()
            val targetX = (scaledWidth * progress).toInt()

            isScrollingProgrammatically = true  // 标记为程序滚动
            timelineScrollView.scrollTo(targetX.coerceIn(0, (scaledWidth).toInt()), 0)
            // 注意：不要再调用updateTimeFromScroll，避免循环
        }
    }

internal fun MainActivity.refreshRendererState() {
        val group = viewModel.currentGroup.value ?: return
        val formations = group.formations
        if (formations.isEmpty()) return

        val time = viewModel.currentTime.value

        // 遍历所有关键帧，找到时间点所在的区域
        var foundAnimation = false
        for (i in formations.indices) {
            val formation = formations[i]
            val timestamp = formation.timestamp
            val duration = formation.duration
            val holdDuration = formation.holdDuration

            val redStart = timestamp - duration
            val greenEnd = timestamp + holdDuration

            // 检查是否在红色区（过渡区）- 第一帧无红色区
            if (i > 0 && duration > 0 && time >= redStart && time < timestamp) {
                val prevFormation = formations[i - 1]
                val progress = ((time - redStart).toFloat() / duration).coerceIn(0f, 1f)

                renderer.currentFormation = prevFormation
                renderer.nextFormation = formation
                renderer.transitionProgress = progress

                updateDancerLabelOverlay(prevFormation, formation, progress)
                foundAnimation = true
                break
            }

            // 检查是否在绿色区（保持区）
            if (time >= timestamp && time < greenEnd) {
                renderer.currentFormation = formation
                renderer.nextFormation = null
                renderer.transitionProgress = 0f

                val nextFormation = if (i + 1 < formations.size) formations[i + 1] else null
                updateDancerLabelOverlay(formation, nextFormation, 0f)
                foundAnimation = true
                break
            }
        }

        // 如果没有找到匹配的区域，显示最后一帧
        if (!foundAnimation && formations.isNotEmpty()) {
            val lastFormation = formations.last()
            renderer.currentFormation = lastFormation
            renderer.nextFormation = null
            renderer.transitionProgress = 0f
            updateDancerLabelOverlay(lastFormation, null, 0f)
        }
    }
