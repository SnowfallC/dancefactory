package com.formationtool.app

import com.formationtool.app.data.shouldCopyMedia
import com.formationtool.app.model.*
import com.formationtool.app.utils.UndoManager
import com.formationtool.app.utils.parseTimeInput
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Editing117Test {
    @Test fun renderingSnapshotSurvivesRosterChangesAndInPlaceEdits() {
        val source = Formation("first", dancers = mutableListOf(Dancer("one", "成员", x = 2f)))
        val snapshot = source.renderSnapshot()
        source.dancers[0].x = 7f
        source.dancers.clear()
        assertEquals(1, snapshot.dancers.size)
        assertEquals(2f, snapshot.dancers[0].x)
    }

    @Test fun preciseTimeSupportsMillisecondsAndRejectsMalformedInput() {
        assertEquals(83450L, parseTimeInput("1:23.450"))
        assertEquals(83450L, parseTimeInput("83.45"))
        assertEquals(1L, parseTimeInput("0.001"))
        for (value in listOf("-1", "NaN", "Infinity", "1:60", "1:2:3", "0.0001", "", "999999999999999999999999")) {
            assertNull(value, parseTimeInput(value))
        }
    }

    @Test fun mediaReplacementRequiresCopyEvenWhenOldFileStillExists() {
        val oldFile = File.createTempFile("media117", ".mp4")
        try {
            oldFile.writeText("旧视频")
            assertTrue(shouldCopyMedia("content://new", oldFile.path, "content://old"))
            assertFalse(shouldCopyMedia("content://old", oldFile.path, "content://old"))
            assertFalse(shouldCopyMedia(oldFile.toURI().toString(), oldFile.path, "content://old"))
            assertTrue(shouldCopyMedia("content://old", oldFile.path + ".missing", "content://old"))
        } finally { oldFile.delete() }
    }

    @Test fun undoRedoRestoresCompleteDancerAndPropStateAndRetainsCurrentMedia() {
        val dancer = Dancer("member", "成员", x = 2f, y = 3f, z = 1.5f, scale = 0.7f, rotation = 25f)
        val group = DanceGroup("test", "回归", dancerCount = 1,
            formations = mutableListOf(Formation("first", dancers = mutableListOf(dancer),
                props = mutableListOf(Prop("prop", "道具", x = 4f)), notes = "备注")))
        val history = UndoManager()
        history.saveSnapshot(group)
        group.formations[0].dancers[0].x = 6f
        group.formations[0].props[0].x = 9f
        group.stageWidth = 30f
        group.referenceVideoPath = "new.mp4"
        history.undo(group)
        assertEquals(2f, group.formations[0].dancers[0].x)
        assertEquals(1.5f, group.formations[0].dancers[0].z)
        assertEquals(0.7f, group.formations[0].dancers[0].scale)
        assertEquals(4f, group.formations[0].props[0].x)
        assertEquals("备注", group.formations[0].notes)
        assertEquals(16f, group.stageWidth)
        assertEquals("new.mp4", group.referenceVideoPath)
        assertTrue(history.canRedo())
        history.redo(group)
        assertEquals(6f, group.formations[0].dancers[0].x)
        assertEquals(9f, group.formations[0].props[0].x)
        assertEquals(30f, group.stageWidth)
        history.undo(group)
        history.saveSnapshot(group)
        assertFalse(history.canRedo())
        assertNull(history.redo(group))
    }

    @Test fun thirtyDancerSnapshotsKeepEveryMemberAcrossUndoRedo() {
        val group = DanceGroup("large", "30人", dancerCount = 30,
            formations = mutableListOf(Formation("first", dancers = (1..30).map {
                Dancer(it.toString(), "舞者$it", x = it / 10f)
            }.toMutableList())))
        val history = UndoManager()
        history.saveSnapshot(group)
        group.formations[0].dancers.clear()
        history.undo(group)
        assertEquals(30, group.formations[0].dancers.size)
        assertEquals("舞者30", group.formations[0].dancers.last().name)
        history.redo(group)
        assertTrue(group.formations[0].dancers.isEmpty())
    }
}
