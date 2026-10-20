package com.formationtool.app

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.formationtool.app.data.CustomTeamPreset
import com.formationtool.app.data.CustomTeamPresetStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

internal fun MainActivity.showCustomTeamPresets() {
    val store = CustomTeamPresetStore(File(filesDir, "presets/custom-teams.json"))
    val presets = try { store.load() } catch (error: Exception) {
        Toast.makeText(this, error.message, Toast.LENGTH_LONG).show(); return
    }
    MaterialAlertDialogBuilder(this).setTitle("自定义预设")
        .setItems(presets.map { "${it.name}（${it.members.size} 人）" }.toTypedArray()) { _, index ->
            val preset = presets[index]
            MaterialAlertDialogBuilder(this).setTitle(preset.name)
                .setItems(arrayOf("应用", "编辑")) { _, action ->
                    if (action == 1) editCustomTeamPreset(store, preset) else {
                        MaterialAlertDialogBuilder(this).setTitle("应用预设")
                            .setMessage("替换工程内的舞者？")
                            .setPositiveButton("应用") { _, _ ->
                                viewModel.replaceDancerRoster(preset.members.map { it.name to Color.parseColor(it.color) })
                            }.setNegativeButton("取消", null).show()
                    }
                }.setNegativeButton("取消", null).show()
        }
        .setPositiveButton("新建") { _, _ -> editCustomTeamPreset(store, null) }
        .setNegativeButton("关闭", null).show()
}

private fun MainActivity.editCustomTeamPreset(store: CustomTeamPresetStore, initial: CustomTeamPreset?) {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
        setBackgroundColor(Color.parseColor("#141D2C"))
    }
    val name = EditText(this).apply { hint = "预设名称"; setSingleLine(); setText(initial?.name.orEmpty()) }
    val count = EditText(this).apply {
        hint = "人数"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
        setText((initial?.members?.size ?: 9).toString())
    }
    val generate = Button(this).apply { text = "生成人员" }
    val countRow = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(count, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f))
        addView(generate)
    }
    data class Row(val name: EditText, var color: Int)
    val rows = mutableListOf<Row>()
    val members = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { addView(members) }
    fun rebuild(): Boolean {
        val size = count.text.toString().toIntOrNull()
        if (size == null || size !in 1..200) { count.error = "1—200"; return false }
        count.error = null
        while (rows.size > size) rows.removeAt(rows.lastIndex)
        while (rows.size < size) {
            val index = rows.size
            val member = initial?.members?.getOrNull(index)
            rows.add(Row(EditText(this).apply {
                setSingleLine(); setText(member?.name ?: "舞者 ${index + 1}")
            }, member?.color?.let(Color::parseColor) ?: getDefaultColor(index)))
        }
        members.removeAllViews()
        rows.forEachIndexed { index, row ->
            (row.name.parent as? android.view.ViewGroup)?.removeView(row.name)
            val color = Button(this).apply {
                text = String.format(Locale.ROOT, "#%06X", row.color and 0xFFFFFF)
                backgroundTintList = android.content.res.ColorStateList.valueOf(row.color)
                setTextColor(if (androidx.core.graphics.ColorUtils.calculateLuminance(row.color) > 0.5) Color.BLACK else Color.WHITE)
                setOnClickListener {
                    showColorPickerDialog(row.color) { selected ->
                        row.color = selected
                        rebuild()
                    }
                }
            }
            val line = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@editCustomTeamPreset).apply { text = "${index + 1}" }, LinearLayout.LayoutParams(28.dpToPx(), 40.dpToPx()))
                addView(row.name, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f))
                addView(color, LinearLayout.LayoutParams(100.dpToPx(), 44.dpToPx()))
            }
            members.addView(line)
        }
        return true
    }
    generate.setOnClickListener { rebuild() }
    rebuild()
    content.addView(name)
    content.addView(countRow)
    content.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.35f).toInt()))
    val dialog = MaterialAlertDialogBuilder(this).setTitle(if (initial == null) "新建预设" else "编辑预设")
        .setBackground(android.graphics.drawable.ColorDrawable(Color.parseColor("#141D2C")))
        .setView(content).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
    dialog.setOnShowListener {
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!rebuild()) return@setOnClickListener
            val preset = CustomTeamPreset(name.text.toString().trim(), rows.map {
                CustomTeamPreset.Member(it.name.text.toString().trim(), String.format(Locale.ROOT, "#%06X", it.color and 0xFFFFFF))
            })
            try {
                store.save(preset, initial?.name)
                dialog.dismiss()
                showCustomTeamPresets()
            } catch (error: Exception) { name.error = error.message }
        }
    }
    dialog.show()
}
