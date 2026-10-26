package com.indigo.mobileobservatory.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.indigo.mobileobservatory.ui.components.ObservatoryNavEntry
import com.indigo.mobileobservatory.ui.components.ObservatoryNavRail
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ObservatoryNavRailInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val entries = listOf("STAR_MAP" to "星图", "CAPTURE" to "拍摄", "MEDIA" to "影像",
        "EQUIPMENT" to "器材", "TOOLS" to "工具").map { (key, label) -> ObservatoryNavEntry(key, label, Icons.Default.Star) }

    @Test fun narrowerRailKeepsLabelsAndTouchTargets() {
        compose.setContent { MobileObservatoryTheme {
            var selected by remember { mutableStateOf("CAPTURE") }
            Surface {
                Row(Modifier.fillMaxWidth().height(360.dp)) {
                    ObservatoryNavRail(entries, selected, { selected = it })
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("rail-content"))
                }
            }
        } }
        val rail = compose.onNodeWithTag("observatory-nav-rail").getUnclippedBoundsInRoot()
        assertTrue("The labeled rail should be narrower than the old 92 dp rail", rail.width <= 73.dp)
        entries.forEach { entry ->
            val target = compose.onNodeWithTag("workspace-${entry.key}")
            assertTrue("Navigation touch width must stay at least 48 dp", target.getUnclippedBoundsInRoot().width >= 48.dp)
            compose.onNodeWithText(entry.label).assertExists()
        }
        compose.onNodeWithTag("workspace-TOOLS").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun cutoutInsetStaysWithinTheNavigationSurface() {
        compose.setContent { MobileObservatoryTheme {
            Surface {
                Row(Modifier.fillMaxWidth().height(360.dp)) {
                    ObservatoryNavRail(entries, "CAPTURE", {}, contentInsets = WindowInsets(left = 32.dp))
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("rail-content"))
                }
            }
        } }
        val rail = compose.onNodeWithTag("observatory-nav-rail").getUnclippedBoundsInRoot()
        val target = compose.onNodeWithTag("workspace-CAPTURE").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("rail-content").getUnclippedBoundsInRoot()
        assertTrue("Safe cutout space belongs inside the rail background", target.left >= rail.left + 32.dp)
        assertTrue("Content must begin immediately after the rail", kotlin.math.abs(content.left.value - rail.right.value) < 1f)
        assertTrue("The cutout should not shrink the navigation touch target", target.width >= 48.dp)
    }

    @Test fun largeFontNavigationCanScrollToTheLastDestination() {
        compose.setContent { MobileObservatoryTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                var selected by remember { mutableStateOf("CAPTURE") }
                Surface {
                    Row(Modifier.fillMaxWidth().height(240.dp)) {
                        ObservatoryNavRail(entries, selected, { selected = it })
                    }
                }
            }
        } }
        compose.onNodeWithTag("workspace-TOOLS").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("workspace-STAR_MAP").performScrollTo().performClick().assertIsSelected()
    }
}
