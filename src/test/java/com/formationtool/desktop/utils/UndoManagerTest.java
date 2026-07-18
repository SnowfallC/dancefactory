package com.formationtool.desktop.utils;

import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Dancer;
import com.formationtool.desktop.model.Formation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UndoManagerTest {

    @Test
    void shouldReturnNullWhenUndoStackIsEmpty() {
        UndoManager undoManager = new UndoManager();
        DanceGroup group = createGroup();

        DanceGroup restored = undoManager.undo(group);

        assertNull(restored);
        assertFalse(undoManager.canUndo());
    }

    @Test
    void shouldKeepAtMostFiftySnapshots() {
        UndoManager undoManager = new UndoManager();
        DanceGroup group = createGroup();

        for (int i = 0; i < 60; i++) {
            group.getFormations().get(0).setNotes("note-" + i);
            undoManager.saveSnapshot(group);
        }

        assertEquals(50, undoManager.getStackSize());
    }

    @Test
    void shouldUndoContinuouslyUntilStackIsEmpty() {
        UndoManager undoManager = new UndoManager();
        DanceGroup group = createGroup();

        for (int i = 0; i < 3; i++) {
            group.getFormations().get(0).setNotes("version-" + i);
            undoManager.saveSnapshot(group);
        }

        int undoCount = 0;
        while (undoManager.canUndo()) {
            DanceGroup restored = undoManager.undo(group);
            assertNotNull(restored);
            undoCount++;
        }

        assertEquals(3, undoCount);
        assertFalse(undoManager.canUndo());
    }

    private DanceGroup createGroup() {
        DanceGroup group = new DanceGroup("g-" + UUID.randomUUID(), "测试组", 16f, 12f);
        Formation formation = new Formation(UUID.randomUUID().toString(), "关键帧 00:00", 0L);
        formation.getDancers().add(new Dancer(UUID.randomUUID().toString(), "D1", 0f, 0f, 0xFFFFFFFF));
        group.getFormations().add(formation);
        group.setDancerCount(1);
        return group;
    }
}
