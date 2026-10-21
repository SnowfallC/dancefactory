package com.formationtool.app.data

import android.util.AtomicFile
import java.io.File
import java.io.IOException

data class CustomTeamPreset(val name: String, val members: List<Member>) {
    data class Member(val name: String, val color: String)
}

/** 本机模板独立保存，工程不保留对模板的引用。 */
class CustomTeamPresetStore(private val file: File) {
    fun load(): List<CustomTeamPreset> {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
        try {
            val bytes = atomic.readFully()
            require(bytes.size <= 4 * 1024 * 1024) { "预设文件过大" }
            val presets = CustomTeamPresetCodec.decode(bytes.toString(Charsets.UTF_8))
            require(presets.size <= 100) { "预设数量过多" }
            return presets
        } catch (error: Exception) { throw IOException("无法读取本机预设", error) }
    }

    fun save(preset: CustomTeamPreset, previousName: String? = null) {
        CustomTeamPresetCodec.validate(preset)
        val presets = load().filterNot { it.name == preset.name || it.name == previousName } + preset
        write(presets)
    }

    fun importJson(json: String): Int {
        require(json.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "预设文件过大" }
        val imported = CustomTeamPresetCodec.decode(json)
        write(CustomTeamPresetCodec.merge(load(), imported))
        return imported.size
    }

    private fun write(presets: List<CustomTeamPreset>) {
        require(presets.size <= 100) { "最多保存 100 个预设" }
        val bytes = CustomTeamPresetCodec.encode(presets).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4 * 1024 * 1024) { "预设文件过大" }
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建预设目录" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

}
