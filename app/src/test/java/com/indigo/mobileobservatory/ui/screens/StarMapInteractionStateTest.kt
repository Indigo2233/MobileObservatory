package com.indigo.mobileobservatory.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapInteractionStateTest {
    @Test fun dismissCardKeepsSelectionOutsidePresentationState() {
        val state = StarMapInteractionState().selectTarget()
        assertTrue(state.targetCardVisible)
        assertFalse(state.dismissTopLayer().targetCardVisible)
        assertFalse(state.dismissTopLayer().hasInformationLayer)
    }

    @Test fun openingAndClosingPanelDoesNotReopenDismissedCard() {
        val state = StarMapInteractionState().selectTarget()
            .openPanel(StarMapCornerPanel.OBSERVING)
        assertFalse(state.targetCardVisible)
        assertEquals(StarMapCornerPanel.OBSERVING, state.panel)
        val closed = state.dismissTopLayer()
        assertFalse(closed.hasInformationLayer)
        assertEquals(closed, closed.dismissTopLayer())
    }

    @Test fun deliberateReselectionOpensCardAndClosesOtherInformation() {
        val state = StarMapInteractionState().openPanel(StarMapCornerPanel.SKY)
            .selectTarget()
        assertTrue(state.targetCardVisible)
        assertFalse(state.targetExpanded)
        assertEquals(StarMapCornerPanel.NONE, state.panel)
    }

    @Test fun panelSwitchDoesNotAccumulateHiddenLayers() {
        val state = StarMapInteractionState().selectTarget()
            .openPanel(StarMapCornerPanel.OBSERVING)
            .openPanel(StarMapCornerPanel.SKY)
        assertEquals(StarMapCornerPanel.SKY, state.panel)
        assertFalse(state.dismissTopLayer().hasInformationLayer)
    }
}
