package com.formationtool.app

import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.formationtool.app.utils.parseTimeInput

internal fun MainActivity.setupPrecisionAndMediaControls() {
    findViewById<TextView>(R.id.tvCurrentTime).apply {
        contentDescription = "点击输入时间跳转"
        setPadding(8, 8, 8, 8)
        setOnClickListener {
            val input = EditText(this@setupPrecisionAndMediaControls).apply {
                setSingleLine()
                hint = "秒数或分:秒.毫秒，例如 1:23.450"
                setText(java.lang.String.format(java.util.Locale.ROOT, "%.3f", viewModel.currentTime.value / 1000.0))
            }
            val dialog = AlertDialog.Builder(this@setupPrecisionAndMediaControls)
                .setTitle("跳转到时间").setView(input)
                .setPositiveButton("跳转", null).setNegativeButton("取消", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val time = parseTimeInput(input.text.toString())
                    if (time == null || time > viewModel.timelineDuration()) {
                        input.error = "请输入有效时间，且不超过工程时长"
                    } else {
                        audioManager.pause()
                        viewModel.seekTo(time)
                        audioManager.seekTo(time)
                        syncReferenceVideoPosition(time)
                        scrollToTime(time)
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }
    }
    findViewById<android.widget.FrameLayout>(R.id.videoContainer).setOnLongClickListener {
        showMediaActions(); true
    }
    findViewById<TextView>(R.id.tvAudioNameTimeline).apply {
        setOnClickListener { showMediaActions() }
        setOnLongClickListener { showMediaActions(); true }
    }
    // 常驻入口避免用户必须发现长按手势。
    val parent = findViewById<Button>(R.id.btnDeleteVideo).parent as? LinearLayout
    parent?.addView(Button(this).apply {
        text = "媒体"
        textSize = 10f
        minWidth = 0
        setPadding(6, 0, 6, 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * resources.displayMetrics.density).toInt())
        setOnClickListener { showMediaActions() }
    })
}

internal fun MainActivity.showMediaActions() {
    AlertDialog.Builder(this).setTitle("参考视频与音频")
        .setItems(arrayOf("添加／更换参考视频", "添加／更换音频", "参考视频左右镜像")) { _, index ->
            when (index) {
                0 -> launchVideoImport()
                1 -> importAudio()
                else -> {
                    val group = viewModel.currentGroup.value ?: return@setItems
                    group.referenceVideoMirrored = !group.referenceVideoMirrored
                    referenceVideoView.scaleX = if (group.referenceVideoMirrored) -1f else 1f
                    viewModel.notifyGroupChanged()
                    saveCurrentProjectSilent()
                }
            }
        }.setNegativeButton("取消", null).show()
}

internal fun MainActivity.showDancerCountDialog() {
    val current = viewModel.currentGroup.value?.formations?.firstOrNull()?.dancers.orEmpty()
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(current.size.toString())
        setSingleLine()
    }
    val dialog = AlertDialog.Builder(this).setTitle("设置总人数（1–200）")
        .setView(input).setPositiveButton("应用", null).setNegativeButton("取消", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val count = input.text.toString().toIntOrNull()
            if (count == null || count !in 1..200) { input.error = "请输入 1–200 的整数"; return@setOnClickListener }
            if (count < current.size) {
                AlertDialog.Builder(this).setTitle("减少人员")
                    .setMessage("将从所有队形移除：${current.drop(count).joinToString("、") { it.name }}。可撤销。")
                    .setPositiveButton("确认") { _, _ -> setDancerCount(count); dialog.dismiss() }
                    .setNegativeButton("取消", null).show()
            } else { setDancerCount(count); dialog.dismiss() }
        }
    }
    dialog.show()
}

internal fun MainActivity.showMoveFormationDialog() {
    audioManager.pause()
    val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun field(label: String) = EditText(this).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText("0")
        fields.addView(this)
    }
    val x = field("X 位移（米，正数向舞台右侧）")
    val y = field("Y 位移（米，正数向舞台后方）")
    val dialog = AlertDialog.Builder(this).setTitle("整体移动当前队形")
        .setMessage("只移动当前队形，保留相对间距，可撤销。")
        .setView(fields).setPositiveButton("移动", null).setNegativeButton("取消", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val dx = x.text.toString().toFloatOrNull()
            val dy = y.text.toString().toFloatOrNull()
            if (dx == null || dy == null || !dx.isFinite() || !dy.isFinite()) {
                Toast.makeText(this, "请输入有效位移", Toast.LENGTH_SHORT).show()
            } else { viewModel.moveCurrentFormation(dx, dy); dialog.dismiss() }
        }
    }
    dialog.show()
}

/** 主动更换媒体必须明确反馈保存失败，不能静默退回旧文件。 */
internal fun MainActivity.persistSelectedMedia() {
    val group = viewModel.currentGroup.value ?: return
    group.referenceVideoUri = referenceVideoUri?.toString()
    group.audioUri = currentAudioUri?.toString()
    if (dataManager.saveDanceGroup(group)) {
        currentProjectId = group.id
        saveProjectIdToPrefs()
        viewModel.clearDirty()
    } else {
        Toast.makeText(this, "媒体保存失败，请重新选择文件或检查剩余空间。原工程文件已保留。", Toast.LENGTH_LONG).show()
    }
}
