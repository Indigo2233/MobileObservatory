package com.indigo.mobileobservatory.ui

import com.indigo.mobileobservatory.ui.screens.ObservatoryDestination.*
import com.indigo.mobileobservatory.ui.screens.ObservatoryNavigation
import com.indigo.mobileobservatory.ui.screens.ObservatoryWorkspace.*
import org.junit.Assert.*
import org.junit.Test

class ObservatoryNavigationTest {
    @Test fun backWalksPreparationAndPreservesOriginThenReturnsToCapture() {
        val root = ObservatoryNavigation().select(STAR_MAP)
        val preparation = root.open(PREPARATION)
        val alignment = preparation.open(POLAR_ALIGNMENT)
        assertEquals(PREPARATION, alignment.back().current)
        assertEquals(STAR_MAP, alignment.back().back().workspace)
        assertNull(alignment.back().back().current)
        assertEquals(CAPTURE, alignment.back().back().back().workspace)
        assertFalse(alignment.back().back().back().canGoBack)
    }

    @Test fun tabSwitchesDoNotAccumulateBackHistory() {
        val nav = ObservatoryNavigation().select(STAR_MAP).select(MEDIA).select(EQUIPMENT)
        assertEquals(CAPTURE, nav.back().workspace)
        assertEquals(emptyList<Any>(), nav.pages)
        assertFalse(nav.back().canGoBack)
    }

    @Test fun mediaProcessingResultReturnsThroughTheActualSource() {
        val nav = ObservatoryNavigation().select(MEDIA).open(STACK).open(PLAYER).open(SETTINGS)
        assertEquals(PLAYER, nav.back().current)
        assertEquals(STACK, nav.back().back().current)
        assertEquals(MEDIA, nav.back().back().back().workspace)
        assertNull(nav.back().back().back().current)
    }

    @Test fun restoredNavigationKeepsUniqueEntryStateAndSource() {
        val nav = ObservatoryNavigation().select(STAR_MAP).open(TARGETS).open(PUSH_TO).open(TARGETS)
        val restored = ObservatoryNavigation.restore(nav.save())
        assertEquals(nav, restored)
        assertEquals(3, restored.pages.map { it.id }.toSet().size)
        assertEquals(PUSH_TO, restored.back().current)
        assertEquals(restored, restored.open(TARGETS))
    }

    @Test fun obsoleteSavedNavigationFallsBackToCaptureWithoutSideEffects() {
        assertEquals(ObservatoryNavigation(), ObservatoryNavigation.restore(listOf("TOOLS")))
        val root = ObservatoryNavigation()
        assertSame(root, root.back())
    }

    @Test fun nestedPlayersAndStacksKeepTheirOwnArguments() {
        val nav = ObservatoryNavigation().open(STACK, "LIVE")
            .open(PLAYER, "/captures/M42.png", "STACK_RESULTS")
            .open(STACK, "PLANETARY").open(PLAYER, "/captures/Jupiter.png", "RECENT")
        val restored = ObservatoryNavigation.restore(nav.save())
        assertEquals("PLANETARY", restored.back().pages.last().argument)
        assertEquals("/captures/M42.png", restored.back().back().pages.last().argument)
        assertEquals("LIVE", restored.back().back().back().pages.last().argument)
    }

    @Test fun openingAnotherRecordingReusesTheSinglePlanetaryTaskInsteadOfLeavingAStaleAncestor() {
        val stack = ObservatoryNavigation().select(MEDIA).open(STACK, "PLANETARY")
        val result = stack.open(PLAYER, "/result.png", "STACK_RESULTS")
        val reopened = result.open(STACK, "PLANETARY")
        assertEquals(stack.pages, reopened.pages)
        assertEquals(MEDIA, reopened.back().workspace)
        val settings = reopened.open(SETTINGS)
        assertEquals(settings.pages, settings.open(GUIDE).open(SETTINGS).pages)
    }

    @Test fun switchingWorkspacesRetainsIndependentStacksAndPageIdentity() {
        val polar = ObservatoryNavigation().select(STAR_MAP).open(PREPARATION).open(POLAR_ALIGNMENT)
        val originalIds = polar.pages.map { it.id }
        val capture = polar.select(CAPTURE).open(PLATE_SOLVE)
        val resumed = capture.select(STAR_MAP)
        assertEquals(POLAR_ALIGNMENT, resumed.current)
        assertEquals(originalIds, resumed.pages.map { it.id })
        assertEquals(PLATE_SOLVE, resumed.select(CAPTURE).current)
        assertEquals(PREPARATION, resumed.back().current)
        assertEquals(3, resumed.allPages.map { it.id }.toSet().size)
    }

    @Test fun savedWorkspaceStacksRestoreAllReturnSourcesAndRejectDuplicateIds() {
        val nav = ObservatoryNavigation().open(PLATE_SOLVE).select(MEDIA).open(PLAYER, "/a.ser", "RECENT")
            .select(STAR_MAP).open(PREPARATION).open(POLAR_ALIGNMENT)
        val restored = ObservatoryNavigation.restore(nav.save())
        assertEquals(nav, restored)
        assertEquals("/a.ser", restored.select(MEDIA).pages.last().argument)
        assertEquals(PLATE_SOLVE, restored.select(CAPTURE).current)
        val duplicate = nav.copy(suspendedPages = nav.suspendedPages + (CAPTURE to nav.pages.take(1)))
        assertEquals(ObservatoryNavigation(), ObservatoryNavigation.restore(duplicate.save()))
    }

    @Test fun globalCoordinatorDestinationsReuseTheirOriginalWorkspaceAcrossStacks() {
        val planetary = ObservatoryNavigation().select(MEDIA).open(STACK, "PLANETARY")
        val nested = planetary.open(PLAYER, "/result.png", "STACK_RESULTS")
        val capture = nested.select(CAPTURE).open(PLATE_SOLVE)
        val reopened = capture.open(STACK, "PLANETARY")
        assertEquals(MEDIA, reopened.workspace)
        assertEquals(planetary.pages, reopened.pages)
        assertEquals(1, reopened.allPages.count { it.destination == STACK && it.argument == "PLANETARY" })
        assertEquals(PLATE_SOLVE, reopened.select(CAPTURE).current)
        val settings = reopened.open(SETTINGS).select(STAR_MAP).open(SETTINGS)
        assertEquals(MEDIA, settings.workspace)
        assertEquals(1, settings.allPages.count { it.destination == SETTINGS })
    }

    @Test fun calibrationAliasesReuseTheSamePageAndItsSelectedSource() {
        val calibration = ObservatoryNavigation().select(STAR_MAP).open(PREPARATION).open(POINTING_SETUP)
        val reopened = calibration.open(PLATE_SOLVE).open(AUXILIARY_CALIBRATION)
        assertEquals(calibration.pages, reopened.pages)
        assertEquals(POINTING_SETUP, reopened.current)
        assertEquals(calibration.nextId + 1, reopened.nextId)
        val auxiliary = ObservatoryNavigation().open(AUXILIARY_CALIBRATION)
        assertEquals(auxiliary, auxiliary.open(POINTING_SETUP))
    }

    @Test fun calibrationAliasesRestoreTheirOriginalWorkspaceAcrossStacks() {
        val calibration = ObservatoryNavigation().select(STAR_MAP).open(PREPARATION).open(AUXILIARY_CALIBRATION)
        val capture = calibration.open(GUIDE).select(CAPTURE).open(PLATE_SOLVE)
        val reopened = capture.open(POINTING_SETUP)
        assertEquals(STAR_MAP, reopened.workspace)
        assertEquals(calibration.pages, reopened.pages)
        assertEquals(PLATE_SOLVE, reopened.select(CAPTURE).current)
        assertEquals(1, reopened.allPages.count { it.destination in setOf(POINTING_SETUP, AUXILIARY_CALIBRATION) })
        assertEquals(reopened, ObservatoryNavigation.restore(reopened.save()))
    }

    @Test fun rootBackReturnsToCaptureTaskWithoutBuildingTabHistoryAndReadsLegacyState() {
        val nav = ObservatoryNavigation().open(PLATE_SOLVE).select(STAR_MAP)
        assertEquals(PLATE_SOLVE, nav.back().current)
        assertEquals(CAPTURE, nav.back().workspace)
        assertFalse(nav.back().back().canGoBack)
        val legacy = listOf("STAR_MAP", "2", "1", "POLAR_ALIGNMENT", "", "")
        assertEquals(POLAR_ALIGNMENT, ObservatoryNavigation.restore(legacy).current)
    }
}
