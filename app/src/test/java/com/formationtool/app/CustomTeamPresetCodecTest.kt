package com.formationtool.app

import com.formationtool.app.data.CustomTeamPreset
import com.formationtool.app.data.CustomTeamPresetCodec
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CustomTeamPresetCodecTest {
    private val preset = CustomTeamPreset("μ's 本机预设", listOf(
        CustomTeamPreset.Member("穗乃果", "#FFA500"),
        CustomTeamPreset.Member("海未", "#0000FF")
    ))

    @Test fun savedFieldsMatchReaderSchema() {
        val json = CustomTeamPresetCodec.encode(listOf(preset))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(setOf("version", "presets"), root.keySet())
        val saved = root.getAsJsonArray("presets")[0].asJsonObject
        assertEquals(setOf("name", "members"), saved.keySet())
        assertEquals(setOf("name", "color"), saved.getAsJsonArray("members")[0].asJsonObject.keySet())
        assertEquals(listOf(preset), CustomTeamPresetCodec.decode(json))
    }

    @Test fun readsAndMigratesObfuscated116PresetsWithoutLosingMembers() {
        val legacy = """{"a":1,"b":[{"a":"μ's 本机预设","b":[{"a":"穗乃果","b":"#FFA500"},{"a":"海未","b":"#0000FF"}]}]}"""
        val loaded = CustomTeamPresetCodec.decode(legacy)
        assertEquals(listOf(preset), loaded)
        val migrated = CustomTeamPresetCodec.encode(loaded)
        assertTrue(migrated.contains("\"version\""))
        assertEquals(loaded, CustomTeamPresetCodec.decode(migrated))
    }

    @Test fun supportsEmptyLibrary() {
        assertEquals(emptyList<CustomTeamPreset>(), CustomTeamPresetCodec.decode(CustomTeamPresetCodec.encode(emptyList())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedVersion() {
        CustomTeamPresetCodec.decode("""{"version":2,"presets":[]}""")
    }

    @Test fun importsExportedLibraryAndReplacesOnlyMatchingNames() {
        val existing = preset.copy(name = "保留的预设")
        val replacement = preset.copy(members = listOf(CustomTeamPreset.Member("凛", "#FFFF00")))
        val imported = CustomTeamPresetCodec.decode(CustomTeamPresetCodec.encode(listOf(replacement)))
        assertEquals(listOf(existing, replacement), CustomTeamPresetCodec.merge(listOf(existing, preset), imported))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidImportedColors() {
        CustomTeamPresetCodec.decode("""{"version":1,"presets":[{"name":"测试","members":[{"name":"舞者","color":"bad"}]}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateImportedNames() {
        CustomTeamPresetCodec.merge(emptyList(), listOf(preset, preset))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImportExceedingLibraryLimit() {
        CustomTeamPresetCodec.merge((1..100).map { preset.copy(name = "团队$it") }, listOf(preset))
    }
}
