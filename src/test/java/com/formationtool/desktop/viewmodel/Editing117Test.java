package com.formationtool.desktop.viewmodel;

import com.formationtool.desktop.model.*;
import com.formationtool.desktop.utils.TimeInput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Editing117Test {
    @Test void preciseTimeHandlesMillisecondsAndRejectsInvalidValues() {
        assertEquals(83450, TimeInput.parse("1:23.450"));
        assertEquals(83450, TimeInput.parse("83.45"));
        assertEquals(1, TimeInput.parse("0.001"));
        for (String value : new String[]{"-1", "NaN", "Infinity", "1:60", "1:2:3", "0.0001", "", "9999999999999999999999"}) {
            assertThrows(RuntimeException.class, () -> TimeInput.parse(value), value);
        }
    }

    @Test void customCountPreservesExistingPositionsAndIsOneUndoableOperation() {
        FormationViewModel model = new FormationViewModel();
        model.createNewGroup("30人回归", 3, 16, 12);
        DanceGroup group = model.getCurrentGroup();
        var first = group.getFormations().get(0);
        first.getDancers().get(0).setX(2);
        first.getDancers().get(0).setName("原成员");
        Formation next = new Formation("next", "第二帧", 5000);
        for (Dancer dancer : first.getDancers()) next.getDancers().add(dancer.copy());
        group.getFormations().add(next);
        model.setDancerCount(30);
        assertEquals(30, first.getDancers().size());
        assertEquals(30, next.getDancers().size());
        assertEquals("原成员", first.getDancers().get(0).getName());
        assertEquals(2, first.getDancers().get(0).getX());
        assertNotSame(first.getDancers().get(29), next.getDancers().get(29));
        assertEquals(first.getDancers().get(29).getId(), next.getDancers().get(29).getId());
        assertTrue(model.undo());
        assertEquals(3, model.getCurrentGroup().getDancerCount());
        assertTrue(model.redo());
        assertEquals(30, model.getCurrentGroup().getDancerCount());
        model.setDancerCount(5);
        assertEquals(5, model.getCurrentGroup().getDancerCount());
        assertTrue(model.undo());
        assertEquals(30, model.getCurrentGroup().getDancerCount());
        assertThrows(IllegalArgumentException.class, () -> model.setDancerCount(0));
    }

    @Test void wholeFormationMovePreservesSpacingAndOtherFrames() {
        FormationViewModel model = new FormationViewModel();
        model.createNewGroup("移动回归", 2, 16, 12);
        DanceGroup group = model.getCurrentGroup();
        Formation first = group.getFormations().get(0);
        first.getDancers().get(0).setX(0);
        first.getDancers().get(1).setX(2);
        Formation next = new Formation("next", "下一帧", 5000);
        for (Dancer dancer : first.getDancers()) next.getDancers().add(dancer.copy());
        group.getFormations().add(next);
        model.moveCurrentFormation(100, 0);
        assertEquals(2, first.getDancers().get(1).getX() - first.getDancers().get(0).getX(), 0.001);
        assertEquals(2, next.getDancers().get(1).getX());
        assertTrue(first.getDancers().get(1).getX() <= 8 + group.getBackstageWidth());
        assertTrue(model.undo());
        assertEquals(0, model.getCurrentGroup().getFormations().get(0).getDancers().get(0).getX());
    }
}
