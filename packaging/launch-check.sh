#!/usr/bin/env bash
# Installs an APK on the connected emulator/device, opens it, and fails if the app is not running
# or logged a crash. Catches optimizer (R8) problems that unit tests on the debug build cannot see.
set -uo pipefail
APK="$1"; PKG=io.github.arnavdugad.usagenotch; ADB="${ADB:-adb}"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
"$ADB" install "$APK" || { echo "Install failed"; exit 1; }
"$ADB" logcat -c
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 15
pid="$("$ADB" shell pidof "$PKG" | tr -d '\r')"
crash="$("$ADB" logcat -d -b crash | grep -A40 "Process: $PKG" || true)"
if [ -z "$pid" ] || [ -n "$crash" ]; then echo "$crash"; echo "Launch check FAILED"; exit 1; fi
resumed="$("$ADB" shell dumpsys activity activities | grep -E "ResumedActivity.*$PKG/.MainActivity" || true)"
[ -n "$resumed" ] || { echo "MainActivity is not in the foreground"; exit 1; }
echo "Launch check passed (pid $pid)"
