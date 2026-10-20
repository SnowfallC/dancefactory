package com.formationtool.app

import com.formationtool.app.model.*
import com.formationtool.app.utils.UndoManager
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ProjectModesTest {
    @Test fun shouldRoundTripDesktopModesAndOffstagePositionsWithoutPresetReferences() {
        val json = """{"id":"cross-client","name":"跨端工程","standardMode":true,"backstageVisible":true,"stageWidth":16,"stageDepth":12,"formations":[{"id":"first","name":"队形1","timestamp":0,"holdDuration":4000,"duration":0,"dancers":[{"id":"mia","name":"米娅","x":-9.6,"y":2,"color":-5192482}]}]}"""
        val group = Gson().fromJson(json, DanceGroup::class.java)
        assertTrue(group.standardMode)
        assertTrue(group.backstageVisible)
        assertEquals(-9.6f, group.formations[0].dancers[0].x, 0.001f)
        val saved = Gson().toJson(group)
        assertFalse(saved.contains("preset"))
        assertEquals("米娅", Gson().fromJson(saved, DanceGroup::class.java).formations[0].dancers[0].name)
    }

    @Test fun shouldRestoreModesAndRosterInOneUndo() {
        val group = DanceGroup("group", "测试", formations = mutableListOf(Formation("first",
            dancers = mutableListOf(Dancer("mia", "米娅", -9.6f, 0f)))))
        val undo = UndoManager()
        undo.saveSnapshot(group)
        group.standardMode = true
        group.backstageVisible = false
        group.formations[0].dancers.clear()
        val restored = undo.undo(group)!!
        assertFalse(restored.standardMode)
        assertTrue(restored.backstageVisible)
        assertEquals("米娅", restored.formations[0].dancers[0].name)
        assertEquals(3.2f, restored.backstageWidth(), 0.001f)
    }
    @Test fun shouldSelectNearestEdgeAndKeepPositionInsideFormation() {
        val formation = Formation("clip", timestamp = 10000, holdDuration = 4000)
        assertEquals(10000L, formation.selectionTime(0))
        assertEquals(14000L, formation.selectionTime(20000))
        assertEquals(12000L, formation.selectionTime(12000))
    }
}
