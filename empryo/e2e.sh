#!/bin/bash
# Emulator smoke for the Empryo Local CI build (-PciHooks=true, x86_64):
# install -> first-run setup (rootfs unpack + Empryo download/verify/unpack) -> lever on (empryo acp) ->
# diagnostics (version, doctor, bridge, idle cost) -> one chat turn against a stub model on the host
# (shell tool behind an Allow) -> screens -> lever off. Evidence in <out>/ (checks.txt first).
#   e2e.sh <apk> <out dir>
set -uo pipefail
APK=$1; OUT=${2:-e2e}
PKG=run.hermes.empryo.test
H=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
FAILS=0
check() { if [ "$2" = 1 ]; then echo "CHECK ok   $1" | tee -a "$OUT/checks.txt"; else echo "CHECK FAIL $1" | tee -a "$OUT/checks.txt"; FAILS=$((FAILS+1)); fi; }
note() { echo "NOTE $*" | tee -a "$OUT/checks.txt"; }
ci_log() { adb logcat -d -s EmpryoCI:I 2>/dev/null; }
wait_log() { local end=$(( $(date +%s) + $2 )); while [ "$(date +%s)" -lt "$end" ]; do ci_log | grep -qE "$1" && return 0; sleep 1; done; return 1; }
shot() { adb exec-out screencap -p >"$OUT/$1.png"; }
am_app() { adb shell "am start -n $PKG/.MainActivity $*" >/dev/null; }

adb wait-for-device
adb shell cmd uimode night yes || true
python3 "$H/../scripts/empryo-poc/stub_openai.py" 18990 "$OUT/stub.log" 0.0.0.0 & STUB=$!
adb install -r -g "$APK" >"$OUT/install.txt" 2>&1; check "install" "$(grep -q Success "$OUT/install.txt" && echo 1 || echo 0)"
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
adb logcat -c; adb logcat -v time >"$OUT/logcat.txt" 2>&1 & LOGCAT=$!

t0=$(date +%s); am_app; sleep 8; shot 00-setup
wait_log "phase: Stopped|Setup failed" 900; if ci_log | grep -q "phase: Stopped"; then ok=1; else ok=0; fi
note "first-run setup took $(( $(date +%s) - t0 )) s"
check "first-run setup: rootfs + Empryo download, sha256, unpack" $ok
ci_log | grep "phase: NotInstalled" | tail -2 | tee -a "$OUT/checks.txt"
shot 01-off

# Stub model on the host (10.0.2.2 from the emulator), as the engine's default model.
cfg='{"defaultModel":"stub/stub-1","providers":[{"id":"stub","name":"Stub","baseURL":"http://10.0.2.2:18990/v1","models":["stub-1"]}]}'
b=$(printf '%s' "$cfg" | base64 -w0)
am_app -e ci_guest "'mkdir -p /root/.empryo && echo $b | base64 -d > /root/.empryo/config.json && echo CFG_OK; /opt/empryo/empryo --version; ls /opt/empryo /opt/empryo/deps; du -sh /opt/empryo /usr 2>/dev/null'"
wait_log "guest-exit" 120; ci_log | grep "guest:" | tee "$OUT/guest.txt"
check "guest sh in proot + empryo --version" "$(grep -q 'empryo 3.12.8-beta' "$OUT/guest.txt" && echo 1 || echo 0)"
check "lazygit dropped" "$(grep -q lazygit "$OUT/guest.txt" && echo 0 || echo 1)"

am_app --ez ci_toggle true
if wait_log "phase: Running" 180; then ok=1; else ok=0; fi
check "lever on -> empryo acp initialized (Running)" $ok
sleep 3; shot 02-running

adb logcat -c 2>/dev/null; sleep 1
am_app --ez ci_diag true
if wait_log "diag-done" 420; then ok=1; else ok=0; fi
check "diagnostics finished" $ok
adb shell run-as $PKG sh -c "'cat files/logs/diagnostics-*.txt'" >"$OUT/diagnostics.txt" 2>&1
check "diag: --version rc=0" "$(grep -A1 '## empryo --version' "$OUT/diagnostics.txt" | grep -q 'rc=0' && echo 1 || echo 0)"
check "diag: doctor OK" "$(grep -qE 'Doctor: OK|checks passed' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
check "diag: bridge result recorded" "$(grep -qE 'BRIDGE (OK|EXITED|NOT LISTENING)' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
note "bridge: $(grep -m1 -E 'BRIDGE ' "$OUT/diagnostics.txt")"
note "mkdir probe: $(grep -m1 -E 'MKDIR_OK|cannot create' "$OUT/diagnostics.txt")"
check "diag: idle sample of the acp tree" "$(grep -q 'acp tree over' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
note "$(grep -m1 'acp tree over' "$OUT/diagnostics.txt")"
note "$(grep -m1 'phone-service tree over' "$OUT/diagnostics.txt")"

adb logcat -c 2>/dev/null; sleep 1
am_app -e ci_prompt "'run uname'"
if wait_log "chat-done" 240; then ok=1; else ok=0; fi
ci_log | grep "chat" | tee "$OUT/chat.txt"
check "chat turn over ACP finished" $ok
check "chat: permission request seen and allowed" "$(grep -q 'chat: permission' "$OUT/chat.txt" && echo 1 || echo 0)"
check "chat: shell tool ran in proot" "$(grep -q 'EMPRYO_SHELL_OK' "$OUT/chat.txt" && echo 1 || echo 0)"
check "chat: streamed reply" "$(grep -q 'chat: agent STUB_REPLY_OK' "$OUT/chat.txt" && echo 1 || echo 0)"

for s in chat settings diagnostics accounts; do am_app -e ci_screen $s; sleep 3; shot "10-$s"; done
am_app -e ci_screen main; sleep 2

adb logcat -c 2>/dev/null; sleep 1
am_app --ez ci_toggle false
if wait_log "phase: Stopped" 60; then ok=1; else ok=0; fi
check "lever off -> engine stopped" $ok
sleep 2; shot 20-off-again
adb shell run-as $PKG sh -c "'cat files/logs/engine.log'" >"$OUT/engine.log" 2>&1
kill $LOGCAT $STUB 2>/dev/null
echo "E2E: $FAILS failed checks" | tee -a "$OUT/checks.txt"
[ "$FAILS" = 0 ]
