#!/usr/bin/env bash
set -uo pipefail

test_classes="${UI_TEST_CLASSES:-com.indigo.mobileobservatory.ui.PolarWorkflowScreenInstrumentedTest}"
./gradlew connectedDebugAndroidTest -PstellariumNonCommercial=true -PemulatorTest=true -PsequenceEnabled=false "-Pandroid.testInstrumentationRunnerArguments.class=$test_classes"
result=$?
mkdir -p app/build/reports
adb logcat -b crash -d > app/build/reports/ui-crash.log || true
adb pull /sdcard/Download/polar-ui-screenshots app/build/reports/polar-ui-screenshots || true
exit "$result"
