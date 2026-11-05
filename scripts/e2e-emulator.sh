#!/bin/bash
# Emulator e2e for Hermes Local (CI variant built with -PciHooks=true).
#   e2e-emulator.sh <apk> <out dir>
# Installs, launches (first-run engine install), sets a password, flips the lever on via CI hook while
# recording the screen, and checks the dashboard on 127.0.0.1:9119 inside the device.
set -uo pipefail
APK=$1; OUT=${2:-e2e}
PKG=dev.syed.hermeslocal
mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 1
adb shell cmd uimode night yes || true   # the AMOLED look Syed approved
adb install -r -g "$APK" || exit 1
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
adb logcat -c
adb logcat -v time >"$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

t0=$(date +%s)
adb shell am start -n $PKG/.MainActivity
sleep 5; adb exec-out screencap -p >"$OUT/01-first-run.png"
# First-run install (verify + unpack ~1 GB) — wait for the password card.
for _ in $(seq 1 120); do
  adb shell run-as $PKG ls files/r/opt/hermes-local/bin/hermes-local-start >/dev/null 2>&1 && \
    ! adb shell run-as $PKG ls files/r.partial >/dev/null 2>&1 && break
  sleep 5
done
echo "engine installed after $(( $(date +%s) - t0 ))s"
sleep 8; adb exec-out screencap -p >"$OUT/02-password-card.png"

adb shell am start -n $PKG/.MainActivity -e ci_password e2e-password-123
for _ in $(seq 1 60); do
  adb logcat -d -s HermesLocalCI | grep -q "password:" && break; sleep 2
done
adb logcat -d -s HermesLocalCI | tail -3
sleep 3; adb exec-out screencap -p >"$OUT/03-lever-off.png"

# Record the lever going up and the engine spooling up.
adb shell screenrecord --time-limit 170 --bit-rate 6000000 /sdcard/e2e.mp4 &
REC=$!
sleep 2
adb shell am start -n $PKG/.MainActivity --ez ci_toggle true
ok=0
for i in $(seq 1 150); do
  code=$(adb shell "toybox nc -z 127.0.0.1 9119 && echo open" 2>/dev/null | tr -d '\r')
  if [ "$code" = open ]; then ok=1; echo "port 9119 open after ${i}s x2"; break; fi
  sleep 2
done
sleep 6
adb exec-out screencap -p >"$OUT/04-running.png"
adb forward tcp:19119 tcp:9119
{
  echo "GET / -> $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:19119/)"
  echo "GET /api/auth/providers -> $(curl -s http://127.0.0.1:19119/api/auth/providers | head -c 300)"
  echo "GET /api/config (no login) -> $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:19119/api/config)"
  echo "POST /auth/password-login -> $(curl -s -o /dev/null -w '%{http_code}' -c "$OUT/cookies" -H 'Content-Type: application/json' \
    -d '{"provider":"basic","username":"admin","password":"e2e-password-123"}' http://127.0.0.1:19119/auth/password-login)"
  echo "GET /api/config (logged in) -> $(curl -s -o /dev/null -w '%{http_code}' -b "$OUT/cookies" http://127.0.0.1:19119/api/config)"
} | tee "$OUT/dashboard-check.txt"
sleep 8
# Lever back down (stop) on camera.
adb shell am start -n $PKG/.MainActivity --ez ci_toggle false
sleep 12
adb exec-out screencap -p >"$OUT/05-stopped.png"
kill -INT $REC 2>/dev/null; sleep 4
adb pull /sdcard/e2e.mp4 "$OUT/e2e.mp4" || true
adb shell run-as $PKG cat files/engine/logs/engine.log >"$OUT/engine.log" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG >"$OUT/gfxinfo.txt" 2>/dev/null || true
kill $LOGCAT 2>/dev/null
grep -E "Janky frames|Total frames|90th|99th" "$OUT/gfxinfo.txt" || true
rm -f "$OUT/cookies"
[ "$ok" = 1 ] && grep -q "password-login -> 200" "$OUT/dashboard-check.txt" && grep -q "(no login) -> 401" "$OUT/dashboard-check.txt" && \
  grep -q "(logged in) -> 200" "$OUT/dashboard-check.txt" && { echo "E2E PASS"; exit 0; }
echo "E2E FAIL"; exit 1
