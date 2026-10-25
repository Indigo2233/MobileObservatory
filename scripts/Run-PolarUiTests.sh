#!/usr/bin/env bash
set -uo pipefail

test_classes=${POLAR_UI_TEST_CLASSES:-com.indigo.mobileobservatory.camera.CameraCfaPipelineInstrumentedTest,com.indigo.mobileobservatory.ui.PolarWorkflowScreenInstrumentedTest,com.indigo.mobileobservatory.ui.PolarPreviewRenderingInstrumentedTest,com.indigo.mobileobservatory.ui.GuideScreenInstrumentedTest,com.indigo.mobileobservatory.ui.GuideWorkbenchInstrumentedTest}
./gradlew connectedDebugAndroidTest -PstellariumNonCommercial=true -PemulatorTest=true -PsequenceEnabled=false "-Pandroid.testInstrumentationRunnerArguments.class=$test_classes"
result=$?
adb pull /sdcard/Download/polar-ui-screenshots app/build/reports/polar-ui-screenshots || true
exit "$result"
