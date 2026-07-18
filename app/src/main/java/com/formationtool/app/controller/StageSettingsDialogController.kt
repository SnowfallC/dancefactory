package com.formationtool.app.controller

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.formationtool.app.model.DanceGroup
import com.formationtool.app.renderer.FormationRenderer
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object StageSettingsDialogController {
    fun show(
        context: Context,
        group: DanceGroup?,
        renderer: FormationRenderer,
        autoSnap: Boolean,
        onStageSizeChanged: (Float, Float) -> Unit,
        onAutoSnapChanged: (Boolean) -> Unit
    ) {
        if (group == null) {
            Toast.makeText(context, "请先创建工程", Toast.LENGTH_SHORT).show()
            return
        }

        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }

        val widthRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        widthRow.addView(TextView(context).apply {
            text = "宽度（米）："
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
        })
        val inputWidth = EditText(context).apply {
            setText(group.stageWidth.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        widthRow.addView(inputWidth)
        container.addView(widthRow)

        val depthRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }
        depthRow.addView(TextView(context).apply {
            text = "深度（米）："
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
        })
        val inputDepth = EditText(context).apply {
            setText(group.stageDepth.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        depthRow.addView(inputDepth)
        container.addView(depthRow)

        val centerLineRow = buildSwitchRow(
            context,
            "显示C位线：",
            renderer.showCenterLine
        )
        val centerLineSwitch = centerLineRow.second
        container.addView(centerLineRow.first)

        val flipRow = buildSwitchRow(
            context,
            "翻转方向（观众在上）：",
            renderer.flipped
        )
        val flipSwitch = flipRow.second
        container.addView(flipRow.first)

        val nameRow = buildSwitchRow(
            context,
            "显示名字（关闭则显示编号）：",
            renderer.showDancerNames
        )
        val nameSwitch = nameRow.second
        container.addView(nameRow.first)

        val snapRow = buildSwitchRow(
            context,
            "自动吸附（拖动时自动吸附到半格）：",
            autoSnap
        )
        val snapSwitch = snapRow.second
        container.addView(snapRow.first)

        scrollView.addView(container)

        MaterialAlertDialogBuilder(context)
            .setTitle("舞台设置")
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                val newWidth = inputWidth.text.toString().toFloatOrNull()
                val newDepth = inputDepth.text.toString().toFloatOrNull()

                if (newWidth != null && newDepth != null && newWidth > 0f && newDepth > 0f) {
                    group.stageWidth = newWidth
                    group.stageDepth = newDepth
                    onStageSizeChanged(newWidth, newDepth)

                    renderer.showCenterLine = centerLineSwitch.isChecked
                    renderer.flipped = flipSwitch.isChecked
                    renderer.showDancerNames = nameSwitch.isChecked
                    onAutoSnapChanged(snapSwitch.isChecked)

                    Toast.makeText(context, "舞台设置已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请输入有效的尺寸", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildSwitchRow(context: Context, label: String, checked: Boolean): Pair<LinearLayout, SwitchCompat> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
            }
        }

        row.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })

        val switch = SwitchCompat(context).apply {
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(switch)

        return row to switch
    }
}
