package com.formationtool.app

import android.graphics.Color
import android.net.Uri
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
import com.formationtool.app.data.CustomTeamPresetCodec
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
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
                .setItems(arrayOf("应用", "编辑", "导出文件")) { _, action ->
                    if (action == 2) exportCustomTeamPresets(listOf(preset))
                    else if (action == 1) editCustomTeamPreset(store, preset) else {
                        MaterialAlertDialogBuilder(this).setTitle("应用预设")
                            .setMessage("替换工程内的舞者？")
                            .setPositiveButton("应用") { _, _ ->
                                viewModel.replaceDancerRoster(preset.members.map { it.name to Color.parseColor(it.color) })
                            }.setNegativeButton("取消", null).show()
                    }
                }.setNegativeButton("取消", null).show()
        }
        .setPositiveButton("新建") { _, _ -> editCustomTeamPreset(store, null) }
        .setNeutralButton("导入 / 导出") { _, _ ->
            MaterialAlertDialogBuilder(this).setTitle("预设文件")
                .setItems(arrayOf("导入 JSON 文件", "导出全部")) { _, action ->
                    if (action == 0) {
                        try { presetImportLauncher.launch(arrayOf("*/*")) }
                        catch (error: Exception) { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_LONG).show() }
                    } else exportCustomTeamPresets(presets)
                }.setNegativeButton("取消", null).show()
        }
        .setNegativeButton("关闭", null).show()
}

internal fun MainActivity.importCustomTeamPresets(uri: Uri) {
    lifecycleScope.launch {
        try {
            val store = CustomTeamPresetStore(File(filesDir, "presets/custom-teams.json"))
            val (json, imported, conflicts) = withContext(Dispatchers.IO) {
                val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法打开预设文件")
                // 有界读取外部文件，避免超大文件耗尽内存。
                val bytes = input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 4 * 1024 * 1024) { "预设文件过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val presets = CustomTeamPresetCodec.decode(text)
                val existing = store.load()
                CustomTeamPresetCodec.merge(existing, presets)
                Triple(text, presets, presets.count { preset -> existing.any { it.name == preset.name } })
            }
            MaterialAlertDialogBuilder(this@importCustomTeamPresets).setTitle("导入预设")
                .setMessage("将导入 ${imported.size} 个预设。" + if (conflicts > 0) "其中 $conflicts 个同名预设将被覆盖。" else "现有预设将保留。")
                .setPositiveButton("导入") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            val count = withContext(Dispatchers.IO) { store.importJson(json) }
                            Toast.makeText(this@importCustomTeamPresets, "已导入 $count 个预设", Toast.LENGTH_SHORT).show()
                            showCustomTeamPresets()
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { Toast.makeText(this@importCustomTeamPresets, "导入失败：${error.message}", Toast.LENGTH_LONG).show() }
                    }
                }.setNegativeButton("取消", null).show()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Toast.makeText(this@importCustomTeamPresets, "无法导入预设，请检查 JSON 文件：${error.message}", Toast.LENGTH_LONG).show() }
    }
}

private fun MainActivity.exportCustomTeamPresets(presets: List<CustomTeamPreset>) {
    if (presets.isEmpty()) {
        Toast.makeText(this, "暂无可导出的预设", Toast.LENGTH_SHORT).show()
        return
    }
    lifecycleScope.launch {
        try {
            // 文件选择器可能重建 Activity，将导出快照暂存在缓存目录。
            withContext(Dispatchers.IO) {
                File(cacheDir, "custom-team-preset-export.json")
                    .writeText(CustomTeamPresetCodec.encode(presets), Charsets.UTF_8)
            }
            presetExportLauncher.launch("dancefactory-presets.json")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Toast.makeText(this@exportCustomTeamPresets, "无法导出预设：${error.message}", Toast.LENGTH_LONG).show()
        }
    }
}

internal fun MainActivity.writeCustomTeamPresetExport(uri: Uri) {
    lifecycleScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val bytes = File(cacheDir, "custom-team-preset-export.json").readBytes()
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw java.io.IOException("无法打开目标文件")
                output.use { it.write(bytes) }
            }
            Toast.makeText(this@writeCustomTeamPresetExport, "预设已导出", Toast.LENGTH_SHORT).show()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Toast.makeText(this@writeCustomTeamPresetExport, "预设导出失败：${error.message}", Toast.LENGTH_LONG).show()
        }
    }
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
