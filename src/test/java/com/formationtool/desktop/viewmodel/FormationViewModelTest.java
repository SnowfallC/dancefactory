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
}
