package com.formationtool.app

import android.content.DialogInterface
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.formationtool.app.MainActivity.KeyframeViewHolder
import com.formationtool.app.audio.AudioManager
import com.formationtool.app.viewmodel.deleteKeyframe
import com.formationtool.app.viewmodel.updateFormationHoldDuration
import com.formationtool.app.viewmodel.updateFormationTransitionDuration
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch


internal fun MainActivity.showDeleteConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除参考视频")
            .setMessage("确定要删除参考视频和音频吗？\n\n注意：所有队形的关键帧数据也将被清除！")
            .setPositiveButton("删除") { _, _ ->
                deleteReferenceVideo()
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.deleteReferenceVideo() {
        // 清空参考视频
        hasReferenceVideo = false
        referenceVideoUri = null

        // 停止并清空视频播放器
        videoPlaybackSyncJob?.cancel()
        videoPlaybackSyncJob = null
        videoSeekSyncJob?.cancel()
        videoSeekSyncJob = null
        referenceVideoView.stopPlayback()
        referenceVideoView.suspend()  // 暂停渲染
        referenceVideoView.setVideoURI(null)

        // 隐藏VideoView显示黑屏背景
        referenceVideoView.visibility = View.GONE

        // 显示提示文本，隐藏删除按钮
        findViewById<TextView>(R.id.tvNoVideo).visibility = View.VISIBLE
        findViewById<Button>(R.id.btnDeleteVideo).visibility = View.GONE

        // 清空音频/波形数据
        currentAudioUri = null
        audioManager.release()
        audioManager = AudioManager(this)

        // 重新设置 audioManager 的监听
        setupAudioManagerObservers()

        // 清空波形数据，恢复导入状态
        viewModel.setAudioPath(null, 0L)

        // 清空波形视图
        waveformView.waveformData = FloatArray(0)
        waveformView.audioDuration = 0L
        waveformView.formations = mutableListOf()
        waveformView.invalidate()

        // 清空关键帧标签
        val markersContainer = findViewById<FrameLayout>(R.id.keyframeMarkersContainer)
        markersContainer.removeAllViews()

        // 清除所有队形的关键帧数据（关键：只保留一个初始帧）
        val group = viewModel.currentGroup.value
        if (group != null && group.formations.isNotEmpty()) {
            android.util.Log.d("MainActivity", "[媒体清除] 开始清理关键帧，当前有 ${group.formations.size} 个关键帧")

            // 保留第一个关键帧作为模板（保存舞者位置）
            val templateDancers = mutableListOf<com.formationtool.app.model.Dancer>()
            if (group.formations.isNotEmpty()) {
                group.formations[0].dancers.forEach { dancer ->
                    templateDancers.add(dancer.copy(isSelected = false))
                }
            }

            // 清空所有关键帧
            val oldCount = group.formations.size
            group.formations.clear()

            // 只创建一个初始关键帧在 0:00
            val initialFormation = com.formationtool.app.model.Formation(
                id = java.util.UUID.randomUUID().toString(),
                name = "初始队形",
                timestamp = 0L
            )
            initialFormation.holdDuration = 4000L  // 默认保持4秒
            initialFormation.duration = 0L         // 第一帧无前红区

            // 恢复舞者位置
            templateDancers.forEach { dancer ->
                initialFormation.dancers.add(dancer)
            }

            group.formations.add(initialFormation)

            android.util.Log.d("MainActivity", "[媒体清除] 已删除 $oldCount 个关键帧，创建1个初始关键帧")

            // 同步更新
            viewModel.syncCurrentGroup()
        }

        // 重置音频名称显示
        findViewById<TextView>(R.id.tvAudioNameTimeline).text = "点击导入音频"

        // 刷新渲染器，确保视图更新
        glSurfaceView.requestRender()

        android.widget.Toast.makeText(this, "已删除参考视频、音频和关键帧数据", android.widget.Toast.LENGTH_SHORT).show()
    }

internal fun MainActivity.setupAudioManagerObservers() {
        audioPlayingObserverJob?.cancel()
        waveformObserverJob?.cancel()

        // 监听播放状态，更新按钮文字和F按钮状态
        audioPlayingObserverJob = lifecycleScope.launch {
            audioManager.isPlaying.collect { isPlaying ->
                findViewById<Button>(R.id.btnAudioPlayPause).text = if (isPlaying) "⏸" else "▶"

                // F按钮只有在播放时才能用
                val fabFKey = findViewById<Button>(R.id.fabFKey)
                fabFKey.isEnabled = isPlaying
                fabFKey.alpha = if (isPlaying) 1.0f else 0.5f

                // 播放停止时（包括播放完成/到达结尾），触发F扫描结束逻辑
                if (!isPlaying && keyframeController?.isScanning == true) {
                    keyframeController?.handleFKeyMarkOnPause()
                }
            }
        }

        // 监听波形数据，更新波形视图
        waveformObserverJob = lifecycleScope.launch {
            audioManager.waveformData.collect { data ->
                waveformView.waveformData = data
                updateTimelineZoom()
            }
        }
    }

internal fun MainActivity.updateDancerLabelOverlay(
        currentFormation: com.formationtool.app.model.Formation,
        nextFormation: com.formationtool.app.model.Formation?,
        progress: Float = 0f
    ) {
        runOnUiThread {
            dancerLabelOverlay.updateDancers(
                currentDancers = currentFormation.dancers,
                nextDancers = nextFormation?.dancers,
                progress = progress,
                camera = viewModel.cameraView.value,
                flipped = renderer.flipped
            )
        }
    }

internal fun MainActivity.formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

internal fun MainActivity.updateKeyframeLabels(formations: MutableList<com.formationtool.app.model.Formation>) {
    // 编号直接绘制在队形色块内，不再使用独立标签。
    findViewById<FrameLayout>(R.id.keyframeMarkersContainer).visibility = View.GONE
    waveformView.formations = formations
}

internal fun MainActivity.showFormationActions(index: Int) {
    val group = viewModel.currentGroup.value ?: return
    val formation = group.formations.getOrNull(index) ?: return
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val panel = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#242938"))
            cornerRadius = dp(14).toFloat()
        }
    }
    val dialog = android.app.Dialog(this)
    dialog.setCanceledOnTouchOutside(false)
    val header = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
    header.addView(TextView(this).apply {
        text = "队形 ${index + 1}"
        gravity = android.view.Gravity.CENTER_VERTICAL
        textSize = 16f
        setTextColor(android.graphics.Color.WHITE)
        setPadding(dp(12), 0, 0, 0)
    }, android.widget.LinearLayout.LayoutParams(0, dp(40), 1f))
    header.addView(TextView(this).apply {
        text = "×"
        textSize = 24f
        gravity = android.view.Gravity.CENTER
        contentDescription = "关闭"
        setTextColor(android.graphics.Color.parseColor("#A7B0C4"))
        setOnClickListener { dialog.dismiss() }
    }, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
    panel.addView(header)
    fun addAction(label: String, destructive: Boolean = false, action: (Int) -> Unit) {
        panel.addView(TextView(this).apply {
            text = label
            textSize = 14f
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(android.graphics.Color.parseColor(if (destructive) "#FF8585" else "#E4EAF5"))
            val selectable = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            setBackgroundResource(selectable.resourceId)
            setOnClickListener {
                dialog.dismiss()
                // 按稳定标识定位，避免菜单打开期间索引变化操作错队形。
                val currentIndex = viewModel.currentGroup.value?.formations?.indexOfFirst { it.id == formation.id } ?: -1
                if (currentIndex >= 0) action(currentIndex)
            }
        }, android.widget.LinearLayout.LayoutParams(-1, dp(44)))
    }
    addAction("调整保持时长") { showAdjustHoldDurationDialog(it, formation) }
    if (group.formations.size > 1) addAction("删除本队形", true) { viewModel.deleteKeyframe(it) }
    dialog.setContentView(panel)
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    dialog.show()
    dialog.window?.setLayout(minOf(dp(320), resources.displayMetrics.widthPixels - dp(40)), -2)

}

internal fun MainActivity.showAdjustHoldDurationDialog(index: Int, formation: com.formationtool.app.model.Formation) {
    showFormationDurationInput("保持此队形的时长", formation.holdDuration) {
        viewModel.saveUndoSnapshot()
        viewModel.updateFormationHoldDuration(index, it)
    }
}

internal fun MainActivity.showAdjustTransitionDurationDialog(index: Int, formation: com.formationtool.app.model.Formation) {
    showFormationDurationInput("变到队形 ${index + 1} 的用时", formation.duration) {
        viewModel.updateFormationTransitionDuration(index, it)
    }
}

private fun MainActivity.showFormationDurationInput(title: String, duration: Long, onSave: (Long) -> Unit) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val content = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), 0)
    }
    content.addView(TextView(this).apply {
        text = title
        textSize = 16f
        setTextColor(android.graphics.Color.WHITE)
    })
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        textSize = 14f
        hint = "毫秒"
        setText(duration.toString())
        selectAll()
    }
    content.addView(input)
    content.addView(TextView(this).apply {
        text = "单位：毫秒"
        textSize = 11f
        setTextColor(android.graphics.Color.parseColor("#A7B0C4"))
    })
    val dialog = MaterialAlertDialogBuilder(this)
        .setView(content)
        .setPositiveButton("确定", null)
        .setNegativeButton("取消", null)
        .create()
    dialog.show()
    dialog.window?.setLayout(minOf(dp(320), resources.displayMetrics.widthPixels - dp(40)), -2)
    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val value = input.text.toString().toLongOrNull()
        if (value == null || value < 0) input.error = "请输入有效的时长"
        else { onSave(value); dialog.dismiss() }
    }
}

internal fun MainActivity.showDeleteKeyframeDialog(index: Int, timestamp: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除关键帧")
            .setMessage("确定要删除 ${formatTime(timestamp)} 的关键帧吗？\n删除后前后关键帧会自动生成过渡动画。")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteKeyframe(index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

internal fun MainActivity.showKeyframeMultiSelectDialog() {
        val group = viewModel.currentGroup.value ?: return
        val formations = group.formations
        if (formations.size <= 1) {
            android.widget.Toast.makeText(this, "没有可删除的关键帧", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 创建对话框
        val dialogView = layoutInflater.inflate(R.layout.dialog_keyframe_multi_select, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        // 选中集合
        val selectedIndices = mutableSetOf<Int>()

        // 获取控件
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.keyframeListRecyclerView)
        val tvCount = dialogView.findViewById<TextView>(R.id.tvMultiSelectCount)
        val btnSelectAll = dialogView.findViewById<Button>(R.id.btnMultiSelectAll)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnMultiSelectDelete)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnMultiSelectCancel)

        // 设置 RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 更新计数函数
        fun updateCount() {
            tvCount.text = "已选择 ${selectedIndices.size} 个"
            val allCount = formations.size - 1
            btnSelectAll.text = if (selectedIndices.size == allCount) "反选" else "全选"
        }

        // Adapter
        val adapter = object : RecyclerView.Adapter<KeyframeViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): KeyframeViewHolder {
                val view = layoutInflater.inflate(R.layout.item_keyframe_multi_select, parent, false)
                return KeyframeViewHolder(view)
            }

            override fun onBindViewHolder(holder: KeyframeViewHolder, position: Int) {
                val index = position + 1  // 跳过第0帧
                val formation = formations[index]
                val isSelected = selectedIndices.contains(index)
                val isCurrent = index == viewModel.currentFormationIndex.value

                // 序号
                holder.tvIndex.text = "#$index"

                // 时间
                holder.tvTime.text = formatTime(formation.timestamp)

                // 信息（绿区 + 红区）
                val holdDur = formatTime(formation.holdDuration)
                val transDur = formatTime(formation.duration)
                holder.tvInfo.text = "绿 $holdDur | 红 $transDur"

                // 当前帧标记
                holder.tvCurrent.visibility = if (isCurrent) View.VISIBLE else View.GONE

                // 复选框
                holder.checkbox.isChecked = isSelected

                // 点击事件
                holder.itemView.setOnClickListener {
                    toggleSelection(index)
                }
                holder.checkbox.setOnClickListener {
                    toggleSelection(index)
                }

                // 单独删除按钮
                holder.btnDelete.setOnClickListener {
                    MaterialAlertDialogBuilder(this@showKeyframeMultiSelectDialog)
                        .setTitle("删除关键帧")
                        .setMessage("确定要删除 ${formatTime(formation.timestamp)} 的关键帧吗？")
                        .setPositiveButton("删除") { _, _ ->
                            viewModel.deleteKeyframe(index)
                            dialog.dismiss()
                            android.widget.Toast.makeText(this@showKeyframeMultiSelectDialog, "已删除关键帧", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                // 背景颜色：选中为绿色
                holder.itemView.setBackgroundColor(
                    if (isSelected) android.graphics.Color.parseColor("#1A4CAF50")
                    else android.graphics.Color.TRANSPARENT
                )
            }

            override fun getItemCount() = formations.size - 1  // 排除第0帧

            private fun toggleSelection(index: Int) {
                if (selectedIndices.contains(index)) {
                    selectedIndices.remove(index)
                } else {
                    selectedIndices.add(index)
                }
                notifyDataSetChanged()
                updateCount()
            }
        }

        recyclerView.adapter = adapter

        // 强制RecyclerView立即测量和布局，避免初始显示异常
        recyclerView.post {
            adapter.notifyDataSetChanged()
        }

        // 全选/反选
        btnSelectAll.setOnClickListener {
            val allIndices = (1 until formations.size).toSet()
            if (selectedIndices.containsAll(allIndices)) {
                selectedIndices.clear()
            } else {
                selectedIndices.clear()
                selectedIndices.addAll(allIndices)
            }
            adapter.notifyDataSetChanged()
            updateCount()
        }

        // 删除
        btnDelete.setOnClickListener {
            if (selectedIndices.isEmpty()) {
                android.widget.Toast.makeText(this, "请选择要删除的关键帧", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("批量删除关键帧")
                .setMessage("确定要删除 ${selectedIndices.size} 个关键帧吗？")
                .setPositiveButton("删除") { _, _ ->
                    // 倒序删除
                    val sortedIndices = selectedIndices.sortedDescending()
                    sortedIndices.forEach { index ->
                        viewModel.deleteKeyframe(index)
                    }
                    dialog.dismiss()
                    android.widget.Toast.makeText(this, "已删除 ${sortedIndices.size} 个关键帧", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 取消
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        updateCount()
        dialog.show()
    }
