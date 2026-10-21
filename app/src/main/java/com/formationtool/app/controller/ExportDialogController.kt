package com.formationtool.app.controller

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.formationtool.app.export.VideoExporter
import com.formationtool.app.view.ExportPreviewView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ExportDialogController {
    fun show(
        context: Context,
        hasReferenceVideo: Boolean,
        initialFlipped: Boolean,
        showDancerNames: Boolean,
        onConfirm: (VideoExporter.ExportConfig) -> Unit
    ) {
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        val previewSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = 16
            }
            gravity = Gravity.CENTER
        }

        val previewLabel = TextView(context).apply {
            text = "输出预览"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        previewSection.addView(previewLabel)

        val previewContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }

        val previewView = ExportPreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        previewContainer.addView(previewView)
        previewSection.addView(previewContainer)

        val resolutionLabel = TextView(context).apply {
            text = "1920 × 1080"
            textSize = 10f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
        }
        previewSection.addView(resolutionLabel)
        mainLayout.addView(previewSection)

        val scrollView = ScrollView(context)
        val settingsSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(settingsSection)
        scrollView.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )
        mainLayout.addView(scrollView)

        val layoutLabel = TextView(context).apply {
            text = "布局模式"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        settingsSection.addView(layoutLabel)

        val layoutModes = arrayOf("单屏", "左右分屏", "上下分屏")
        val layoutSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(this@apply.context, android.R.layout.simple_spinner_dropdown_item, layoutModes)
            setSelection(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6
                bottomMargin = 12
            }
        }
        settingsSection.addView(layoutSpinner)

        val primaryLabel = TextView(context).apply {
            text = "主视图"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        settingsSection.addView(primaryLabel)

        val viewTypes = if (hasReferenceVideo) {
            arrayOf("2D视角", "3D视角", "参考视频")
        } else {
            arrayOf("2D视角", "3D视角")
        }
        val primarySpinner = Spinner(context).apply {
            adapter = ArrayAdapter(this@apply.context, android.R.layout.simple_spinner_dropdown_item, viewTypes)
            setSelection(0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6
                bottomMargin = 12
            }
        }
        settingsSection.addView(primarySpinner)

        val secondaryLabel = TextView(context).apply {
            text = "次视图"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        settingsSection.addView(secondaryLabel)

        val secondarySpinner = Spinner(context).apply {
            adapter = ArrayAdapter(this@apply.context, android.R.layout.simple_spinner_dropdown_item, viewTypes)
            setSelection(if (viewTypes.size > 1) 1 else 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6
                bottomMargin = 12
            }
        }
        settingsSection.addView(secondarySpinner)

        val scaleLabel = TextView(context).apply {
            text = "视角缩放: 1.0x"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        settingsSection.addView(scaleLabel)

        val scaleSeekBar = SeekBar(context).apply {
            max = 150
            progress = 50
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6
                bottomMargin = 12
            }
        }
        settingsSection.addView(scaleSeekBar)

        val flipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 12
            }
        }
        flipRow.addView(TextView(context).apply {
            text = "相对当前舞台旋转180°"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val flipSwitch = SwitchCompat(context).apply {
            isChecked = false
        }
        flipRow.addView(flipSwitch)
        settingsSection.addView(flipRow)

        val mirrorSwitch = SwitchCompat(context).apply {
            text = "舞台左右镜像"
            setTextColor(Color.WHITE)
        }
        settingsSection.addView(mirrorSwitch)
        val dancerSizeLabel = TextView(context).apply { text = "角色大小：1.0 倍"; setTextColor(Color.WHITE) }
        settingsSection.addView(dancerSizeLabel)
        val dancerSize = SeekBar(context).apply { max = 150; progress = 50 }
        settingsSection.addView(dancerSize)
        dancerSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { dancerSizeLabel.text = "角色大小：${0.5f + value / 100f} 倍" }
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
        })

        fun updateUI() {
            val isSingle = layoutSpinner.selectedItemPosition == 0
            secondaryLabel.visibility = if (isSingle) View.GONE else View.VISIBLE
            secondarySpinner.visibility = if (isSingle) View.GONE else View.VISIBLE

            val layoutMode = layoutSpinner.selectedItemPosition
            val primaryView = primarySpinner.selectedItemPosition
            val secondaryView = if (isSingle) -1 else secondarySpinner.selectedItemPosition
            val scale = 0.5f + scaleSeekBar.progress / 100f

            resolutionLabel.text = when (layoutMode) {
                2 -> "1080 × 1920"
                else -> "1920 × 1080"
            }
            previewView.updatePreview(
                layoutMode = layoutMode,
                primaryView = primaryView,
                secondaryView = secondaryView,
                scale = scale,
                flipped = initialFlipped.xor(flipSwitch.isChecked)
            )
            previewView.requestLayout()
        }

        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateUI()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
        layoutSpinner.onItemSelectedListener = selectionListener
        primarySpinner.onItemSelectedListener = selectionListener
        secondarySpinner.onItemSelectedListener = selectionListener

        scaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = 0.5f + progress / 100f
                scaleLabel.text = "视角缩放: ${String.format("%.2f", scale)}x"
                if (fromUser) {
                    updateUI()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })

        flipSwitch.setOnCheckedChangeListener { _, _ ->
            updateUI()
        }

        updateUI()

        MaterialAlertDialogBuilder(context)
            .setTitle("导出配置")
            .setView(mainLayout)
            .setPositiveButton("导出") { _, _ ->
                val layoutMode = when (layoutSpinner.selectedItemPosition) {
                    0 -> VideoExporter.LayoutMode.SINGLE
                    1 -> VideoExporter.LayoutMode.SPLIT_HORIZONTAL
                    else -> VideoExporter.LayoutMode.SPLIT_VERTICAL
                }
                val primaryView = toViewType(primarySpinner.selectedItemPosition)
                val secondaryView = toViewType(secondarySpinner.selectedItemPosition)
                val viewScale = 0.5f + scaleSeekBar.progress / 100f

                onConfirm(
                    VideoExporter.ExportConfig(
                        layoutMode = layoutMode,
                        primaryView = primaryView,
                        secondaryView = secondaryView,
                        viewScale = viewScale,
                        mirrored = mirrorSwitch.isChecked,
                        dancerScale = 0.5f + dancerSize.progress / 100f,
                        flipped = initialFlipped.xor(flipSwitch.isChecked),
                        showDancerNames = showDancerNames
                    )
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toViewType(position: Int): VideoExporter.ViewType {
        return when (position) {
            0 -> VideoExporter.ViewType.VIEW_2D
            1 -> VideoExporter.ViewType.VIEW_45_DEGREE
            else -> VideoExporter.ViewType.REFERENCE_VIDEO
        }
    }
}
