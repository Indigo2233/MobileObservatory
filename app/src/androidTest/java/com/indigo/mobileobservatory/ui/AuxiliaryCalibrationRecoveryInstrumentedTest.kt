package com.indigo.mobileobservatory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.pointing.PhoneCatalogStar
import com.indigo.mobileobservatory.pointing.SkyDirection
import com.indigo.mobileobservatory.ui.screens.AuxiliaryCalibrationRecovery
import com.indigo.mobileobservatory.ui.screens.AuxiliaryReferenceStarChoices
import com.indigo.mobileobservatory.ui.screens.auxiliaryValidationStarAllowed
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Recovery/choice controls only. No synthetic plate is saved as real calibration evidence. */
@RunWith(AndroidJUnit4::class)
class AuxiliaryCalibrationRecoveryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun samplingConditionsHaveEnabledContextualRecoveryActions() {
        val actions = mutableListOf<String>()
        compose.setContent { MaterialTheme { Column {
            AuxiliaryCalibrationRecovery(databaseReady = false, auxiliaryMissing = true, mainMissing = true,
                guiding = true, onPlateSolve = { actions += "database" }, onAuxiliaryConnect = { actions += "auxiliary" },
                onMainConnect = { actions += "primary" }, onGuide = { actions += "guide" })
        } } }
        compose.onNodeWithTag("auxiliary-install-database").assertIsEnabled().performClick()
        compose.onNodeWithTag("auxiliary-reconnect").assertIsEnabled().performClick()
        compose.onNodeWithTag("auxiliary-connect-main").assertIsEnabled().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.wfa_open_guiding)).performClick()
        compose.runOnIdle { assertEquals(listOf("database", "auxiliary", "primary", "guide"), actions) }
    }

    @Test fun secondStarPickerCannotOfferTheFirstStarOrANearbyReference() {
        val candidates = listOf(PhoneCatalogStar(120.0, 0.0, 1.0, 1, "first star"),
            PhoneCatalogStar(121.0, 0.0, 1.0, 2, "nearby star"),
            PhoneCatalogStar(130.0, 0.0, 1.0, 3, "independent star"))
        var chosen: PhoneCatalogStar? = null
        compose.setContent { MaterialTheme {
            AuxiliaryReferenceStarChoices(candidates.filter { auxiliaryValidationStarAllowed(it, SkyDirection(120.0, 0.0)) },
                validation = true, onChoose = { chosen = it })
        } }
        compose.onNodeWithText("first star").assertDoesNotExist()
        compose.onNodeWithText("nearby star").assertDoesNotExist()
        compose.onNodeWithText("independent star").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(3, chosen?.hip) }
    }

    @Test fun missingSecondStarExplainsHowToContinue() {
        compose.setContent { MaterialTheme { AuxiliaryReferenceStarChoices(emptyList(), validation = true) {} } }
        compose.onNodeWithText(compose.activity.getString(R.string.wfa_no_validation_stars)).assertIsDisplayed()
    }
}
