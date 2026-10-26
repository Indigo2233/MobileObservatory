#!/usr/bin/env bash
set -uo pipefail

test_classes=${POLAR_UI_TEST_CLASSES:-com.indigo.mobileobservatory.camera.CameraCfaPipelineInstrumentedTest,com.indigo.mobileobservatory.ui.PolarWorkflowScreenInstrumentedTest,com.indigo.mobileobservatory.ui.PolarPreviewRenderingInstrumentedTest,com.indigo.mobileobservatory.ui.GuideScreenInstrumentedTest,com.indigo.mobileobservatory.ui.GuideWorkbenchInstrumentedTest,com.indigo.mobileobservatory.ui.GuideEntryInstrumentedTest,com.indigo.mobileobservatory.ui.GuideMainActivityInstrumentedTest,com.indigo.mobileobservatory.ui.GuideSettingsInstrumentedTest}
./gradlew connectedDebugAndroidTest -PstellariumNonCommercial=true -PemulatorTest=true -PsequenceEnabled=false "-Pandroid.testInstrumentationRunnerArguments.class=$test_classes"
result=$?
mkdir -p app/build/reports
adb logcat -b crash -d > app/build/reports/ui-crash.log || true
adb pull /sdcard/Android/data/com.indigo.mobileobservatory.emulatortest/files/logs/app.log app/build/reports/ui-app.log || true
adb pull /sdcard/Download/polar-ui-screenshots app/build/reports/polar-ui-screenshots || true
adb pull /sdcard/Android/data/com.indigo.mobileobservatory.emulatortest/files/guide-entry app/build/reports/guide-entry-screenshots || true
exit "$result"
