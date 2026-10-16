#!/usr/bin/env bash
set -euo pipefail

bash gradlew connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.searchPickerWindow=compact' --no-parallel --max-workers=1
mv app/build/reports/androidTests app/build/reports/androidTests-compact
mv app/build/outputs/androidTest-results app/build/outputs/androidTest-results-compact

# The emulator runner executes each input line in its own shell; keep cleanup in this process.
trap 'adb shell wm size reset; adb shell wm density reset' EXIT
adb shell wm size 1000x800
adb shell wm density 160
bash gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=net.weero.measix.pilot.ui.components.ai.SearchPickerBackAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.searchPickerWindow=expanded' --no-parallel --max-workers=1
