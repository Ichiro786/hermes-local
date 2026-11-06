#!/bin/bash
# Emulator e2e for Hermes Local (CI variant built with -PciHooks=true).
#   e2e-emulator.sh <apk> <out dir>
# Installs, launches (first-run engine install), sets a password, flips the lever on via CI hook while
# recording the screen, and checks the dashboard on 127.0.0.1:9119 inside the device. Then the Providers
# screen: Codex device code + URL, Cancel, Antigravity link + rejected code -> fresh link, both flows at
# once, stub Codex sign-in -> Use for chat -> a chat turn through /api/ws (evidence in <out>/providers/).
set -uo pipefail
APK=$1; OUT=${2:-e2e}
PKG=dev.syed.hermeslocal
mkdir -p "$OUT"
adb wait-for-device
# Root shell (google_apis image) for reading app files: the CI APK is a release build, so run-as refuses it
# ("package not debuggable"); appsh falls back to run-as for debuggable builds.
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
appsh() { if [ "$(adb shell id -u | tr -d '\r')" = 0 ]; then adb shell "cd /data/data/$PKG && $1"; else adb shell run-as $PKG sh -c "'$1'"; fi; }
adb shell settings put global window_animation_scale 1
adb shell cmd uimode night yes || true   # the AMOLED look Syed approved
adb install -r -g "$APK" || exit 1
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
adb logcat -c
adb logcat -v time >"$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

UI=$(dirname "$0")/ui.py
P="$OUT/providers"; mkdir -p "$P"
ci_log() { adb logcat -d -s HermesLocalCI:I 2>/dev/null; }
# guest '<bash>' [timeout s]: runs a command inside the engine via the CI hook, prints its output.
# The script travels base64-encoded inside the intent extra (no host-side write into the app's files).
guest() {
  local id="g$RANDOM$RANDOM" t=${2:-300} b
  b=$(printf '%s\n' "$1" | base64 -w0)
  adb shell "am start -n $PKG/.MainActivity -e ci_guest 'echo GUEST_BEGIN_$id; echo $b | base64 -d >/tmp/$id.sh; bash /tmp/$id.sh 2>&1; echo GUEST_END_$id'" >/dev/null
  for _ in $(seq 1 "$t"); do ci_log | grep -q "GUEST_END_$id" && break; sleep 1; done
  ci_log | sed -n "/GUEST_BEGIN_$id/,/GUEST_END_$id/p" | sed 's/^.*guest: //' | grep -v "GUEST_"
}
check() { # check <name> <1|0>  (no pipeline around the if: prov_ok must change in this shell)
  local l; if [ "$2" = 1 ]; then l="CHECK ok   $1"; else l="CHECK FAIL $1"; prov_ok=0; fi; echo "$l" | tee -a "$P/checks.txt"; }
shot() { adb exec-out screencap -p >"$P/$1.png"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml >"$P/$1.xml"; }
dumpui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml >"$P/cur.xml"; }
front() { adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2; } # back from the browser
resumed() { adb shell dumpsys activity activities | grep -m1 -E "ResumedActivity|topResumedActivity" | tr -s ' '; }
# find <regex> [ui.py opts]: scrolls the Providers list until a node matches; prints "x y enabled text".
find_node() {
  local i r
  for i in 0 1 2 3 4 5; do
    dumpui; r=$(python3 "$UI" "$P/cur.xml" "$@") && { echo "$r"; return 0; }
    if [ $i -lt 3 ]; then adb shell input swipe 540 1700 540 900 300; else adb shell input swipe 540 900 540 1700 300; fi
    sleep 1
  done
  return 1
}
tap_node() { local r; r=$(find_node "$@") || { echo "not found: $*"; return 1; }; echo "tap: $r"; set -- $r; adb shell input tap "$1" "$2"; }
top() { for _ in 1 2 3; do adb shell input swipe 540 700 540 1900 200; done; sleep 1; }
wait_log() { # wait_log <regex> <seconds>: 0 when a CI log line matches in time
  local end=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$end" ]; do ci_log | grep -qE "$1" && return 0; sleep 0.5; done; return 1; }
rec_start() { ( for i in 1 2 3 4 5; do [ -f "$P/.recstop" ] && break; adb shell screenrecord --time-limit 175 --bit-rate 6000000 /sdcard/prov-$i.mp4; done ) & RECP=$!; }
rec_stop() { touch "$P/.recstop"; adb shell pkill -INT screenrecord; sleep 4; kill $RECP 2>/dev/null
  for i in 1 2 3 4 5; do adb pull /sdcard/prov-$i.mp4 "$P/providers-$i.mp4" >/dev/null 2>&1 || break; done; rm -f "$P/.recstop"; }
CHATPY='echo e2e-password-123 | HL_CHAT_TIMEOUT=150 /opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_chat_check.py http://127.0.0.1:9119 admin "Say hi"'

providers_e2e() {
  rec_start
  adb shell am start -n $PKG/.MainActivity -e ci_screen providers >/dev/null
  wait_log "status: " 180 || true
  sleep 2; shot 10-providers
  check "both sign-in buttons enabled at rest" "$(python3 "$UI" "$P/10-providers.xml" '^Sign in with Codex$' | grep -q ' true ' && python3 "$UI" "$P/10-providers.xml" '^Sign in with Antigravity$' | grep -q ' true ' && echo 1 || echo 0)"

  echo "== Codex: device code + URL within 15 s"
  top; tap_node '^Sign in with Codex$'; t=$(date +%s%N)
  got=0
  while [ $(( ($(date +%s%N) - t) / 1000000 )) -lt 15000 ]; do
    ci_log | grep -q "flow Codex: code " && ci_log | grep -q "flow Codex: url " && { got=1; break; }; sleep 0.3; done
  dt=$(( ($(date +%s%N) - t) / 1000000 ))ms; echo "codex code+url after ${dt} (limit 15 s)" | tee -a "$P/checks.txt"
  [ $got = 1 ] || wait_log "flow Codex: code " 60 || true   # late result is still recorded, the check stays failed
  url=$(ci_log | grep -m1 -o "flow Codex: url .*" | awk '{print $4}'); code=$(ci_log | grep -m1 -o "flow Codex: code .*" | awk '{print $4}')
  echo "codex url=$url code=$code" | tee -a "$P/checks.txt"
  check "Codex code + URL within 15 s" "$got"
  check "Codex URL is https://auth.openai.com/codex/device (no ANSI)" "$([ "$url" = https://auth.openai.com/codex/device ] && echo 1 || echo 0)"
  sleep 3; echo "after code: $(resumed)" | tee -a "$P/checks.txt"   # the app opens the browser itself
  shot 11-codex-browser; front; top; shot 12-codex-code
  check "Codex code + URL on screen" "$(grep -q "text=\"$code\"" "$P/12-codex-code.xml" && grep -q 'auth.openai.com/codex/device' "$P/12-codex-code.xml" && echo 1 || echo 0)"

  echo "== Antigravity while Codex is waiting (independent flows)"
  r=$(find_node '^Sign in with Antigravity$'); echo "agy button: $r"
  check "Antigravity button enabled while Codex flow runs" "$(grep -q ' true ' <<<"$r" && echo 1 || echo 0)"
  tap_node '^Sign in with Antigravity$'
  wait_log "flow Antigravity: prompt code" 120; a=$?
  check "Antigravity link shown + waiting for code" "$([ $a = 0 ] && ci_log | grep -q 'flow Antigravity: url https://accounts.google.com/' && echo 1 || echo 0)"
  sleep 3; echo "after agy link: $(resumed)" | tee -a "$P/checks.txt"
  front; shot 13-both-running
  check "both flows running at once" "$(ci_log | grep -qE 'flow Codex: (cancel|exit)' && echo 0 || { ci_log | grep -q 'flow Antigravity: prompt code' && grep -q "text=\"$code\"" "$P/13-both-running.xml" && echo 1 || echo 0; })"

  echo "== Codex Cancel resets only Codex"
  top; tap_node '^Cancel$' --after '^Codex \(ChatGPT\)$' --before '^Antigravity \(Gemini, Claude\)$'
  wait_log "flow Codex: (cancel|exit)" 30; sleep 3
  r=$(find_node '^Sign in with Codex$'); echo "codex button after cancel: $r"
  check "Codex Cancel resets (button usable again)" "$(grep -q ' true ' <<<"$r" && echo 1 || echo 0)"
  shot 14-codex-cancelled
  check "Antigravity still waiting after Codex cancel" "$(find_node '^Paste the code from Google$' >/dev/null && echo 1 || echo 0)"

  echo "== Antigravity: rejected code -> fresh link"
  tap_node '^Paste the code from Google$'; sleep 1   # the field's placeholder, not the card description
  adb shell input text HLE2EBADCODE123; sleep 1; adb shell input keyevent 111 2>/dev/null; sleep 1
  shot 15-agy-code-typed
  tap_node '^Submit code$'
  wait_log "flow Antigravity: error" 120; e=$?
  ci_log | grep "flow Antigravity: error" | tail -1 | tee -a "$P/checks.txt"
  check "Antigravity rejects the bad code" "$([ $e = 0 ] && echo 1 || echo 0)"
  for _ in $(seq 1 120); do [ "$(ci_log | grep -c 'flow Antigravity: prompt code')" -ge 2 ] && break; sleep 1; done
  urls=$(ci_log | grep -o "flow Antigravity: url .*" | awk '{print $4}' | sort -u | wc -l)
  echo "antigravity distinct urls: $urls" | tee -a "$P/checks.txt"
  check "fresh Antigravity link after the rejected code" "$([ "$urls" -ge 2 ] && [ "$(ci_log | grep -c 'flow Antigravity: prompt code')" -ge 2 ] && echo 1 || echo 0)"
  sleep 3; front; shot 16-agy-fresh-link
  tap_node '^Cancel$' --after '^Antigravity \(Gemini, Claude\)$'
  wait_log "flow Antigravity: (cancel|exit)" 30; sleep 3
  r=$(find_node '^Sign in with Antigravity$'); check "Antigravity Cancel resets" "$(grep -q ' true ' <<<"$r" && echo 1 || echo 0)"

  echo "== chat before any provider is set (the test1 phone error)"
  guest "$CHATPY" 200 | grep -E "CHAT_|MODEL_INFO" | cut -c1-300 | tee "$P/chat-before.txt"
  echo "== stub Codex sign-in -> Use for chat -> chat through /api/ws"
  guest "$(cat <<'G'
J=$(python3 -c 'import base64,json,time;b=lambda o: base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip("=");print(b({"alg":"none"})+"."+b({"exp":int(time.time())+86400,"https://api.openai.com/auth":{"chatgpt_account_id":"stub"}})+".sig")')
hermes auth add openai-codex --type api-key --api-key "$J" --label e2e-stub </dev/null 2>&1 | tail -n 2
G
)" 200 | tee -a "$P/checks.txt"
  adb shell am start -n $PKG/.MainActivity -e ci_screen main >/dev/null; sleep 2
  n0=$(ci_log | grep -c "status: ")
  adb shell am start -n $PKG/.MainActivity -e ci_screen providers >/dev/null
  for _ in $(seq 1 150); do [ "$(ci_log | grep -c 'status: ')" -gt "$n0" ] && break; sleep 1; done; sleep 2
  top; shot 17-stub-signed-in
  tap_node '^Use for chat$'
  for _ in $(seq 1 150); do ci_log | grep "status: " | tail -1 | grep -q '"model_provider": "openai-codex"' && break; sleep 1; done; sleep 3
  ci_log | grep "status: " | tail -1 | tee -a "$P/checks.txt"
  top; shot 18-used-for-chat
  check "Use for chat sets openai-codex as the chat model" "$(ci_log | grep 'status: ' | tail -1 | grep -q '"model_provider": "openai-codex"' && grep -q 'Used for chat' "$P/18-used-for-chat.xml" && echo 1 || echo 0)"
  guest "$CHATPY" 240 | grep -E "CHAT_|MODEL_INFO|delta" | cut -c1-300 | tee "$P/chat-after.txt"
  check "chat via /api/ws streams the stub reply" "$(grep -q '^CHAT_OK.*stub model' "$P/chat-after.txt" && echo 1 || echo 0)"
  check "no 'not connected' error after Use for chat" "$(grep -q 'not connected' "$P/chat-after.txt" && echo 0 || echo 1)"
  rec_stop
  ci_log >"$P/ci-log.txt"
  [ "$prov_ok" = 1 ]
}

t0=$(date +%s)
adb shell am start -n $PKG/.MainActivity
sleep 5; adb exec-out screencap -p >"$OUT/01-first-run.png"
# First-run install (verify + unpack ~1 GB) — wait for the password card.
for _ in $(seq 1 120); do
  appsh "ls files/r/opt/hermes-local/bin/hermes-local-start" >/dev/null 2>&1 && \
    ! appsh "ls files/r.partial" >/dev/null 2>&1 && break
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

# Stub model server on the CI host (the emulator reaches it as 10.0.2.2); Codex calls go there once a
# stub sign-in exists. Written before the dashboard starts so Hermes reads it from ~/.hermes/.env.
python3 "$(dirname "$0")/stub-llm.py" 18080 >"$OUT/stub-llm.log" 2>&1 &
STUB=$!
guest 'mkdir -p /root/.hermes && echo HERMES_CODEX_BASE_URL=http://10.0.2.2:18080/v1 >>/root/.hermes/.env && grep CODEX /root/.hermes/.env' 120

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
kill -INT $REC 2>/dev/null; sleep 4
adb pull /sdcard/e2e.mp4 "$OUT/e2e.mp4" || true

# ---------------- Providers screen (engine running) ----------------
prov_ok=1
if [ "$ok" = 1 ]; then providers_e2e || prov_ok=0; else prov_ok=0; fi

# Lever back down (stop) on camera.
adb shell screenrecord --time-limit 30 /sdcard/e2e-stop.mp4 &
REC=$!; sleep 2
adb shell am start -n $PKG/.MainActivity -e ci_screen main
sleep 3
adb shell am start -n $PKG/.MainActivity --ez ci_toggle false
sleep 12
adb exec-out screencap -p >"$OUT/05-stopped.png"
kill -INT $REC 2>/dev/null; sleep 4
adb pull /sdcard/e2e-stop.mp4 "$OUT/e2e-stop.mp4" || true
appsh "cat files/engine/logs/engine.log" >"$OUT/engine.log" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG >"$OUT/gfxinfo.txt" 2>/dev/null || true
kill $LOGCAT $STUB 2>/dev/null
grep -E "Janky frames|Total frames|90th|99th" "$OUT/gfxinfo.txt" || true
rm -f "$OUT/cookies"
# No Hermes self-update text anywhere: app log, engine log, every Providers screen dump.
if cat "$OUT/logcat.txt" "$OUT/engine.log" "$P"/*.xml 2>/dev/null | grep -v "diag-update:" | grep -qiE 'source-update|update is still running'; then
  echo "CHECK FAIL no source-update text anywhere" | tee -a "$P/checks.txt"; prov_ok=0
else echo "CHECK ok   no source-update text anywhere" | tee -a "$P/checks.txt"; fi
grep -c "CHECK ok" "$P/checks.txt"; grep "CHECK FAIL" "$P/checks.txt"
[ "$prov_ok" = 1 ] && [ "$ok" = 1 ] && grep -q "password-login -> 200" "$OUT/dashboard-check.txt" && grep -q "(no login) -> 401" "$OUT/dashboard-check.txt" && \
  grep -q "(logged in) -> 200" "$OUT/dashboard-check.txt" && { echo "E2E PASS"; exit 0; }
echo "E2E FAIL"; exit 1
