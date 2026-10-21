package com.formationtool.app.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 显式构造存储字段，避免正式包混淆改变本机文件格式。 */
internal object CustomTeamPresetCodec {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun encode(presets: List<CustomTeamPreset>): String {
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("presets", JsonArray().apply {
                presets.forEach { preset ->
                    add(JsonObject().apply {
                        addProperty("name", preset.name)
                        add("members", JsonArray().apply {
                            preset.members.forEach { member ->
                                add(JsonObject().apply {
                                    addProperty("name", member.name)
                                    addProperty("color", member.color)
                                })
                            }
                        })
                    })
                }
            })
        }
        return gson.toJson(root)
    }

    fun decode(json: String): List<CustomTeamPreset> {
        val root = JsonParser.parseString(json).asJsonObject
        // 1.1.6 正式包的混淆映射将每层字段依次写为 a、b；读取后在下次保存时迁移。
        val legacy = !root.has("version") && root.has("a") && root.has("b")
        val versionKey = if (legacy) "a" else "version"
        val presetsKey = if (legacy) "b" else "presets"
        val nameKey = if (legacy) "a" else "name"
        val membersKey = if (legacy) "b" else "members"
        val colorKey = if (legacy) "b" else "color"
        require(root.get(versionKey).asInt == 1) { "预设版本不支持" }
        val presets = root.getAsJsonArray(presetsKey)
        require(presets.size() <= 100) { "预设数量过多" }
        return presets.map { value ->
            val item = value.asJsonObject
            CustomTeamPreset(item.get(nameKey).asString, item.getAsJsonArray(membersKey).map { raw ->
                val member = raw.asJsonObject
                CustomTeamPreset.Member(member.get(nameKey).asString, member.get(colorKey).asString)
            }).also(::validate)
        }
    }

    fun merge(existing: List<CustomTeamPreset>, imported: List<CustomTeamPreset>): List<CustomTeamPreset> {
        require(imported.isNotEmpty()) { "文件中没有预设" }
        imported.forEach(::validate)
        require(imported.map { it.name }.distinct().size == imported.size) { "文件中存在重复预设名称" }
        val names = imported.map { it.name }.toSet()
        return (existing.filterNot { it.name in names } + imported).also {
            require(it.size <= 100) { "最多保存 100 个预设" }
        }
    }

    fun validate(preset: CustomTeamPreset) {
        require(preset.name.isNotBlank() && preset.name.length <= 64) { "请输入 1—64 字的预设名称" }
        require(preset.members.size in 1..200) { "人数需要在 1—200 之间" }
        require(preset.members.all { it.name.isNotBlank() && it.name.length <= 64 && it.color.matches(Regex("#[0-9A-Fa-f]{6}")) }) {
            "请检查姓名和颜色"
        }
    }
}
