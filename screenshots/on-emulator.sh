#!/usr/bin/env bash
# Runs inside the Android emulator job: clean status bar, install, run the tour, pull the PNGs.
set -x
PKG=com.cinnamon.vantracker
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell cmd alarm set-timezone Asia/Colombo || adb shell setprop persist.sys.timezone Asia/Colombo || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for p in POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do adb shell pm grant $PKG android.permission.$p || true; done
adb shell am instrument -w -e class $PKG.ScreenshotTour $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.log
mkdir -p out
adb exec-out run-as $PKG tar -C files -cf - shots > out/shots.tar || true
(cd out && tar -xf shots.tar && rm -f shots.tar) || true
ls -la out/shots || true
cat out/shots/log.txt || true
adb logcat -d -t 3000 > out/logcat.txt || true
