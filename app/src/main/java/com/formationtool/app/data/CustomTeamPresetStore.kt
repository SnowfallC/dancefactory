package com.formationtool.app.data

import android.util.AtomicFile
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException

data class CustomTeamPreset(val name: String, val members: List<Member>) {
    data class Member(val name: String, val color: String)
}

/** 本机模板独立保存，工程不保留对模板的引用。 */
class CustomTeamPresetStore(private val file: File) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private data class Library(val version: Int = 1, val presets: List<CustomTeamPreset>)

    fun load(): List<CustomTeamPreset> {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
        try {
            val bytes = atomic.readFully()
            require(bytes.size <= 4 * 1024 * 1024) { "预设文件过大" }
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(root.get("version").asInt == 1) { "预设版本不支持" }
            val presets = root.getAsJsonArray("presets").map { value ->
                val item = value.asJsonObject
                CustomTeamPreset(item.get("name").asString, item.getAsJsonArray("members").map { raw ->
                    val member = raw.asJsonObject
                    CustomTeamPreset.Member(member.get("name").asString, member.get("color").asString)
                }).also(::validate)
            }
            require(presets.size <= 100) { "预设数量过多" }
            return presets
        } catch (error: Exception) { throw IOException("无法读取本机预设", error) }
    }

    fun save(preset: CustomTeamPreset, previousName: String? = null) {
        validate(preset)
        val presets = load().filterNot { it.name == preset.name || it.name == previousName } + preset
        require(presets.size <= 100) { "最多保存 100 个预设" }
        val bytes = gson.toJson(Library(presets = presets)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4 * 1024 * 1024) { "预设文件过大" }
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建预设目录" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private fun validate(preset: CustomTeamPreset) {
        require(preset.name.isNotBlank() && preset.name.length <= 64) { "请输入 1—64 字的预设名称" }
        require(preset.members.size in 1..200) { "人数需要在 1—200 之间" }
        require(preset.members.all { it.name.isNotBlank() && it.name.length <= 64 && it.color.matches(Regex("#[0-9A-Fa-f]{6}")) }) {
            "请检查姓名和颜色"
        }
    }
}
