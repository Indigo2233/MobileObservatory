#!/usr/bin/env bash
set -uo pipefail

./gradlew connectedDebugAndroidTest -PstellariumNonCommercial=true -PemulatorTest=true -PsequenceEnabled=false -Pandroid.testInstrumentationRunnerArguments.class=com.indigo.mobileobservatory.ui.PolarWorkflowScreenInstrumentedTest
result=$?
adb pull /sdcard/Download/polar-ui-screenshots app/build/reports/polar-ui-screenshots || true
exit "$result"
