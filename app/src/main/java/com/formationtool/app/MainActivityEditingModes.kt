package com.formationtool.app

import android.view.View
import android.widget.Button

internal fun MainActivity.setupEditingModeControls() {
    findViewById<Button>(R.id.btnEditingMode).setOnClickListener {
        if (!audioManager.isPlaying.value) {
            keyframeController?.handleFKeyMarkOnPause()
            viewModel.setStandardMode(viewModel.currentGroup.value?.standardMode != true)
        }
    }
    findViewById<Button>(R.id.btnAddInterval).setOnClickListener { viewModel.addFormationInterval() }
    listOf(R.id.btnEditingMode, R.id.btnAddInterval).forEach { id ->
        findViewById<Button>(id).apply {
            backgroundTintList = null
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#30394B"))
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), android.graphics.Color.parseColor("#53637E"))
                cornerRadius = 6 * resources.displayMetrics.density
            }
            setTextColor(android.graphics.Color.parseColor("#DCE7FA"))
            textSize = 12f
            isAllCaps = false
        }
    }

}

internal fun MainActivity.refreshEditingModeControls() {
    val group = viewModel.currentGroup.value ?: return
    if (group.standardMode && previousStandardMode != true) {
        timelineZoom = maxOf((viewModel.timelineDuration() / 20000f).coerceIn(3f, 15f), timelineZoom)
    }
    previousStandardMode = group.standardMode
    findViewById<Button>(R.id.btnEditingMode).text = if (group.standardMode) "标准模式 ⇄" else "F 拍打模式 ⇄"
    findViewById<Button>(R.id.btnEditingMode).isEnabled = !audioManager.isPlaying.value
    findViewById<Button>(R.id.btnAddInterval).visibility = if (group.standardMode) View.VISIBLE else View.GONE
    findViewById<Button>(R.id.btnAddInterval).isEnabled = !audioManager.isPlaying.value
    findViewById<Button>(R.id.fabFKey).visibility = if (group.standardMode) View.GONE else View.VISIBLE
    waveformView.standardMode = group.standardMode
    waveformView.audioDuration = viewModel.timelineDuration()
    findViewById<android.widget.TextView>(R.id.tvDuration).text = formatTime(viewModel.timelineDuration())
    updateTimelineZoom()
}
