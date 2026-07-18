package com.formationtool.app.controller

import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.formationtool.app.model.Dancer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object DancerDialogController {
    data class PresetOption(val name: String, val onSelected: () -> Unit)

    fun showAddDancerDialog(
        context: Context,
        hasMedia: Boolean,
        presetColors: List<Int>,
        presetOptions: List<PresetOption>,
        onPickColor: (Int, (Int) -> Unit) -> Unit,
        onAddDancer: (String, Int) -> Unit
    ) {
        if (!hasMedia) {
            Toast.makeText(context, "请先导入音频/视频", Toast.LENGTH_SHORT).show()
            return
        }

        var selectedColor = presetColors.firstOrNull() ?: Color.WHITE

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 15, 40, 15)
        }

        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(context).apply {
            text = "名称："
            textSize = 11f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        val inputName = EditText(context).apply {
            hint = "输入名称"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            filters = arrayOf(InputFilter.LengthFilter(3))
        }
        nameRow.addView(inputName)
        container.addView(nameRow)

        container.addView(TextView(context).apply {
            text = "颜色"
            textSize = 10f
            setTextColor(Color.GRAY)
            setPadding(0, 12, 0, 6)
        })

        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val colorPreview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(50.dpToPx(context), 50.dpToPx(context)).apply {
                marginEnd = 10
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(selectedColor)
                setStroke(2, Color.WHITE)
            }
        }

        val colorPickerBtn = Button(context).apply {
            text = "选择颜色"
            textSize = 10f
            setOnClickListener {
                onPickColor(selectedColor) { newColor ->
                    selectedColor = newColor
                    colorPreview.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(selectedColor)
                        setStroke(2, Color.WHITE)
                    }
                }
            }
        }

        colorRow.addView(colorPreview)
        colorRow.addView(colorPickerBtn)
        container.addView(colorRow)

        container.addView(TextView(context).apply {
            text = "预设"
            textSize = 10f
            setTextColor(Color.GRAY)
            setPadding(0, 12, 0, 6)
        })

        val presetScrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val presetLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        presetOptions.forEach { option ->
            val btn = Button(context).apply {
                text = option.name
                textSize = 9f
                minWidth = 0
                minHeight = 0
                setPadding(12, 6, 12, 6)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 6
                }
            }
            presetLayout.addView(btn)
        }

        presetScrollView.addView(presetLayout)
        container.addView(presetScrollView)
        scrollView.addView(container)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                onAddDancer(inputName.text.toString(), selectedColor)
            }
            .setNegativeButton("取消", null)
            .create()

        presetOptions.forEachIndexed { index, option ->
            val btn = presetLayout.getChildAt(index) as? Button
            btn?.setOnClickListener {
                option.onSelected()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    fun showEditDancerDialog(
        context: Context,
        dancer: Dancer,
        onPickColor: (Int, (Int) -> Unit) -> Unit,
        onUpdateDancer: (String, String, Int) -> Unit,
        onUploadStyle: (String) -> Unit,
        onClearStyle: (String) -> Unit,
        onPreviewStyle: (String) -> Unit
    ) {
        var selectedColor = dancer.color

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 15, 40, 15)
        }

        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(context).apply {
            text = "名称："
            textSize = 11f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        val inputName = EditText(context).apply {
            setText(dancer.name)
            hint = "输入名称"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            filters = arrayOf(InputFilter.LengthFilter(3))
        }
        nameRow.addView(inputName)
        container.addView(nameRow)

        container.addView(TextView(context).apply {
            text = "颜色"
            textSize = 10f
            setTextColor(Color.GRAY)
            setPadding(0, 12, 0, 6)
        })

        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val colorPreview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(50.dpToPx(context), 50.dpToPx(context)).apply {
                marginEnd = 10
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(selectedColor)
                setStroke(2, Color.WHITE)
            }
        }

        val colorPickerBtn = Button(context).apply {
            text = "选择颜色"
            textSize = 10f
            setOnClickListener {
                onPickColor(selectedColor) { newColor ->
                    selectedColor = newColor
                    colorPreview.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(selectedColor)
                        setStroke(2, Color.WHITE)
                    }
                }
            }
        }

        colorRow.addView(colorPreview)
        colorRow.addView(colorPickerBtn)
        container.addView(colorRow)

        container.addView(TextView(context).apply {
            text = "走位球样式"
            textSize = 10f
            setTextColor(Color.GRAY)
            setPadding(0, 12, 0, 6)
        })

        val styleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val uploadStyleBtn = Button(context).apply {
            text = "上传样式"
            textSize = 10f
            setOnClickListener {
                onUploadStyle(dancer.id)
            }
        }

        val clearStyleBtn = Button(context).apply {
            text = "清除样式"
            textSize = 10f
            isEnabled = !dancer.customStylePath.isNullOrEmpty()
            setOnClickListener {
                onClearStyle(dancer.id)
            }
        }

        val previewStyleBtn = Button(context).apply {
            text = "预览样式"
            textSize = 10f
            isEnabled = !dancer.customStylePath.isNullOrEmpty()
            setOnClickListener {
                onPreviewStyle(dancer.id)
            }
        }

        styleRow.addView(uploadStyleBtn)
        styleRow.addView(clearStyleBtn)
        styleRow.addView(previewStyleBtn)
        container.addView(styleRow)

        container.addView(TextView(context).apply {
            text = if (dancer.customStylePath.isNullOrEmpty()) {
                "当前未设置自定义样式"
            } else {
                "当前已设置自定义样式"
            }
            textSize = 9f
            setTextColor(Color.GRAY)
            setPadding(0, 6, 0, 0)
        })

        scrollView.addView(container)

        MaterialAlertDialogBuilder(context)
            .setTitle("编辑舞者")
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                onUpdateDancer(dancer.id, inputName.text.toString(), selectedColor)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showEditOrDeleteDialog(
        context: Context,
        dancer: Dancer,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("舞者 ${dancer.name.replace("舞者", "")}")
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                when (which) {
                    0 -> onEdit()
                    1 -> onDelete()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showDeleteDancerDialog(
        context: Context,
        dancer: Dancer,
        lifecycleScope: LifecycleCoroutineScope,
        isDeleting: () -> Boolean,
        setDeleting: (Boolean) -> Unit,
        onDeleteDancer: suspend (String) -> Unit
    ) {
        if (isDeleting()) {
            Log.d("MainActivity", "删除舞者请求被防抖拦截")
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("删除舞者")
            .setMessage("确定要删除 ${dancer.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        setDeleting(true)
                        Log.d("MainActivity", "开始删除舞者: ${dancer.name}")
                        onDeleteDancer(dancer.id)
                        delay(300)
                        Log.d("MainActivity", "删除舞者完成")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "删除舞者失败", e)
                        Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        setDeleting(false)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}