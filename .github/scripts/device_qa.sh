#!/usr/bin/env bash
# Runs the on-device QA tests on the CI emulator and collects screenshots and logs into qa/.
set -x
# A busy CI emulator sometimes reports the launcher as not responding; that dialog would cover
# the app and swallow taps, so error dialogs are turned off for the run.
adb shell settings put global hide_error_dialogs 1 || true
adb logcat -G 16M || true
adb shell dumpsys webviewupdate | grep -iE "current webview package|version" | head -3 > webview.txt || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class app.shaqati.QaTour app.shaqati.test/androidx.test.runner.AndroidJUnitRunner | tee qa-tour.txt
adb logcat -d -v time ShaqatiMap:V chromium:V '*:S' > map-log.txt || true
adb logcat -d -b crash > crash.txt || true
adb shell am instrument -w -e class app.shaqati.LiveRefreshTest app.shaqati.test/androidx.test.runner.AndroidJUnitRunner | tee qa-live.txt
adb logcat -d -b crash >> crash.txt || true
rm -rf qa && mkdir -p qa
adb pull /sdcard/Android/data/app.shaqati/files/qa/. qa/ || true
cp qa-tour.txt qa-live.txt map-log.txt webview.txt crash.txt qa/ || true
adb logcat -d -t 3000 '*:E' ShaqatiQA:I > qa/logcat.txt || true
ls -la qa
exit 0
