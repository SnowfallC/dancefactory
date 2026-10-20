package com.formationtool.desktop.viewmodel;

import com.formationtool.desktop.model.DanceGroup;
import com.formationtool.desktop.model.Formation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormationViewModelTest {

    @Test
    void shouldInitializeGroupWithOneFormationAndConfiguredDancerCount() {
        FormationViewModel viewModel = new FormationViewModel();

        viewModel.createNewGroup("测试组", 5, 16f, 12f);

        DanceGroup group = viewModel.getCurrentGroup();
        assertNotNull(group);
        assertEquals(1, group.getFormations().size());
        assertEquals(5, group.getFormations().get(0).getDancers().size());
        assertTrue(group.isStandardMode());
        assertEquals(0, viewModel.getCurrentFormationIndex());
    }

    @Test
    void shouldInsertKeyframeAndKeepTimelineSorted() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 3, 16f, 12f);

        Formation inserted = viewModel.insertKeyframeSmart(3000L, null);

        assertNotNull(inserted);
        List<Formation> formations = viewModel.getCurrentGroup().getFormations();
        assertEquals(2, formations.size());
        assertEquals(0L, formations.get(0).getTimestamp());
        assertEquals(3000L, formations.get(1).getTimestamp());
        assertTrue(formations.get(1).getHoldDuration() >= 1L);
    }

    @Test
    void shouldUndoAfterDeleteDancer() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 2, 16f, 12f);

        String dancerId = viewModel.getCurrentGroup().getFormations().get(0).getDancers().get(0).getId();
        viewModel.deleteDancer(dancerId);

        int countAfterDelete = viewModel.getCurrentGroup().getFormations().get(0).getDancers().size();
        assertEquals(1, countAfterDelete);
        assertTrue(viewModel.canUndo());
        assertTrue(viewModel.undo());

        int countAfterUndo = viewModel.getCurrentGroup().getFormations().get(0).getDancers().size();
        assertEquals(2, countAfterUndo);
    }

    @Test
    void shouldRecalculateNextRedDurationWhenUpdatingHoldDuration() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 2, 16f, 12f);
        viewModel.insertKeyframeSmart(6000L, null);

        viewModel.updateFormationHoldDuration(0, 1000L);

        List<Formation> formations = viewModel.getCurrentGroup().getFormations();
        Formation first = formations.get(0);
        Formation second = formations.get(1);
        long expectedSecondDuration = second.getTimestamp() - (first.getTimestamp() + first.getHoldDuration());

        assertEquals(1000L, first.getHoldDuration());
        assertEquals(Math.max(0L, expectedSecondDuration), second.getDuration());
    }
    @Test
    void shouldKeepStandardModeTimingAndInheritPreviousFormation() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 1, 16f, 12f);
        viewModel.insertKeyframeSmart(6000L, null);
        Formation previous = viewModel.getCurrentGroup().getFormations().get(1);
        previous.getDancers().get(0).setX(3f);
        long timestamp = previous.getTimestamp();
        long hold = previous.getHoldDuration();
        viewModel.setStandardMode(true);
        assertEquals(timestamp, previous.getTimestamp());
        assertEquals(hold, previous.getHoldDuration());
        Formation next = viewModel.insertKeyframeSmart(12000L, null);
        assertEquals(3f, next.getDancers().get(0).getX());
        assertNotSame(previous.getDancers().get(0), next.getDancers().get(0));
    }

    @Test
    void shouldCreateDancersBackstageAndAllowMovingOnAndOffStage() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 1, 16f, 12f);
        var first = viewModel.getCurrentGroup().getFormations().get(0).getDancers().get(0);
        assertTrue(first.getX() < -8f);
        viewModel.addDancer("新成员", 0xFFFFFFFF);
        var added = viewModel.getCurrentGroup().getFormations().get(0).getDancers().get(1);
        assertTrue(added.getX() > 8f);
        viewModel.updateDancerPosition(added.getId(), -9f, 0f);
        assertEquals(-9f, added.getX());
        viewModel.updateDancerPosition(added.getId(), 0f, 0f);
        assertEquals(0f, added.getX());
    }

    @Test
    void shouldUndoModeSwitchesWithoutLosingPositions() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 1, 16f, 12f);
        viewModel.setStandardMode(false);
        float x = viewModel.getCurrentGroup().getFormations().get(0).getDancers().get(0).getX();
        viewModel.setStandardMode(true);
        viewModel.setBackstageVisible(false);
        assertTrue(viewModel.undo());
        assertTrue(viewModel.getCurrentGroup().isBackstageVisible());
        assertTrue(viewModel.isStandardMode());
        assertTrue(viewModel.undo());
        assertFalse(viewModel.isStandardMode());
        assertEquals(x, viewModel.getCurrentGroup().getFormations().get(0).getDancers().get(0).getX());
    }
    @Test
    void shouldAppendStandardIntervalsAndEditDurationWithoutMusic() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 1, 16f, 12f);
        viewModel.setStandardMode(true);
        assertEquals(4000, viewModel.getTimelineDuration());
        Formation next = viewModel.addFormationInterval();
        assertNotNull(next);
        assertEquals(6000, next.getTimestamp());
        assertEquals(10000, viewModel.getTimelineDuration());
        viewModel.updateFormationHoldDuration(1, 6000);
        assertEquals(6000, next.getHoldDuration());
        assertEquals(12000, viewModel.getTimelineDuration());
        assertNotNull(viewModel.addFormationInterval());
        assertEquals(3, viewModel.getCurrentGroup().getFormations().size());
    }

    @Test
    void shouldKeepIntervalsConnectedWhenChangingTimeToFormation() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 1, 16f, 12f);
        viewModel.insertKeyframeSmart(6000L, null);
        viewModel.updateFormationTransitionDuration(1, 3000L);
        var formations = viewModel.getCurrentGroup().getFormations();
        assertEquals(3000L, formations.get(0).getHoldDuration());
        assertEquals(3000L, formations.get(1).getDuration());
        assertEquals(6000L, formations.get(1).getTimestamp());
        viewModel.updateFormationTransitionDuration(1, 1000L);
        assertEquals(5000L, formations.get(0).getHoldDuration());
        assertEquals(1000L, formations.get(1).getDuration());
        assertTrue(viewModel.undo());
        assertEquals(3000L, viewModel.getCurrentGroup().getFormations().get(0).getHoldDuration());
    }

    @Test
    void shouldDeleteFirstFormationAndRetainTimelineAndUndo() {
        FormationViewModel viewModel = new FormationViewModel();
        viewModel.createNewGroup("测试组", 1, 16f, 12f);
        viewModel.insertKeyframeSmart(6000L, null);
        viewModel.insertKeyframeSmart(12000L, null);
        var group = viewModel.getCurrentGroup();
        String secondId = group.getFormations().get(1).getId();
        long secondEnd = group.getFormations().get(1).getTimestamp() + group.getFormations().get(1).getHoldDuration();
        viewModel.setCurrentFormationIndex(2);
        viewModel.deleteFormation(0);
        assertEquals(2, group.getFormations().size());
        assertEquals(secondId, group.getFormations().get(0).getId());
        assertEquals(0L, group.getFormations().get(0).getTimestamp());
        assertEquals(secondEnd, group.getFormations().get(0).getHoldDuration());
        assertEquals(12000L, group.getFormations().get(1).getTimestamp());
        assertEquals(1, viewModel.getCurrentFormationIndex());
        assertTrue(viewModel.undo());
        assertEquals(3, viewModel.getCurrentGroup().getFormations().size());
        viewModel.deleteFormation(99);
        assertEquals(3, viewModel.getCurrentGroup().getFormations().size());
    }
    @Test
    void shouldSelectNearestFormationEdgeOrKeepPositionInside() {
        Formation formation = new Formation("clip", "队形2", 10000);
        formation.setHoldDuration(4000);
        assertEquals(10000, formation.selectionTime(0));
        assertEquals(14000, formation.selectionTime(20000));
        assertEquals(12000, formation.selectionTime(12000));
    }
}
