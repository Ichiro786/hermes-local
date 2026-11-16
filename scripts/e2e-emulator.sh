#!/bin/bash
# Emulator e2e for Hermes Local (CI variants built with -PciHooks=true).
#   e2e-emulator.sh <test5 apk> <out dir> [<test4 apk>]
# A. Fresh install: first-run engine download (zstd, sha256), password, lever on (dashboard + login checks),
#    data size after first boot, the Providers screen (Codex device code, Antigravity link + rejected code,
#    stub Codex sign-in -> Use for chat -> chat through /api/ws, voice keys), screenshots of every screen,
#    no agy self-update, a plugin install with Python deps then Storage > Clean up back near the baseline.
# B. Upgrade (with <test4 apk>): test4 with its bundled engine, password, stub sign-in, a chat session, user
#    files; then test5 over it: the engine updates itself, logins/sessions/password survive, the old
#    dependency environment is gone, chat still works.
# Every check lands in <out>/checks.txt ("CHECK ok|FAIL"); read it, not only the exit code.
set -uo pipefail
APK=$1; OUT=${2:-e2e}; OLD_APK=${3:-}
PKG=dev.syed.hermeslocal
mkdir -p "$OUT"
adb wait-for-device
# Root shell (google_apis image) for reading app files: the CI APK is a release build, so run-as refuses it
# ("package not debuggable"); appsh falls back to run-as for debuggable builds.
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
appsh() { if [ "$(adb shell id -u | tr -d '\r')" = 0 ]; then adb shell "cd /data/data/$PKG && $1"; else adb shell run-as $PKG sh -c "'$1'"; fi; }
adb shell settings put global window_animation_scale 1
adb shell cmd uimode night yes || true   # the AMOLED look Syed approved
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
check() { # check <name> <1|0>  (no pipeline around the if: prov_ok/all_ok must change in this shell)
  local l; if [ "$2" = 1 ]; then l="CHECK ok   $1"; else l="CHECK FAIL $1"; prov_ok=0; all_ok=0; fi; echo "$l" | tee -a "$OUT/checks.txt"; }
note() { echo "NOTE $*" | tee -a "$OUT/checks.txt"; }
all_ok=1
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
  dt=$(( ($(date +%s%N) - t) / 1000000 ))ms; echo "codex code+url after ${dt} (limit 15 s)" | tee -a "$OUT/checks.txt"
  [ $got = 1 ] || wait_log "flow Codex: code " 60 || true   # late result is still recorded, the check stays failed
  url=$(ci_log | grep -m1 -o "flow Codex: url .*" | awk '{print $4}'); code=$(ci_log | grep -m1 -o "flow Codex: code .*" | awk '{print $4}')
  echo "codex url=$url code=$code" | tee -a "$OUT/checks.txt"
  check "Codex code + URL within 15 s" "$got"
  check "Codex URL is https://auth.openai.com/codex/device (no ANSI)" "$([ "$url" = https://auth.openai.com/codex/device ] && echo 1 || echo 0)"
  sleep 3; echo "after code: $(resumed)" | tee -a "$OUT/checks.txt"   # the app opens the browser itself
  shot 11-codex-browser; front; top; shot 12-codex-code
  check "Codex code + URL on screen" "$(grep -q "text=\"$code\"" "$P/12-codex-code.xml" && grep -q 'auth.openai.com/codex/device' "$P/12-codex-code.xml" && echo 1 || echo 0)"
  # test2 cosmetic bug: the log under the flow showed "2. Enter this code:" with the code line missing.
  check "flow log shows the URL and the code under their steps" "$(python3 -c '
import sys, xml.etree.ElementTree as ET
code = sys.argv[2]
texts = [n.get("text") or "" for n in ET.parse(sys.argv[1]).getroot().iter("node")]
ok = any("Open this URL" in t and "https://auth.openai.com/codex/device" in t and ("Enter this code:\n   " + code) in t for t in texts)
print(1 if ok else 0)' "$P/12-codex-code.xml" "$code" 2>/dev/null || echo 0)"

  echo "== Antigravity while Codex is waiting (independent flows)"
  r=$(find_node '^Sign in with Antigravity$'); echo "agy button: $r"
  check "Antigravity button enabled while Codex flow runs" "$(grep -q ' true ' <<<"$r" && echo 1 || echo 0)"
  tap_node '^Sign in with Antigravity$'
  wait_log "flow Antigravity: prompt code" 120; a=$?
  check "Antigravity link shown + waiting for code" "$([ $a = 0 ] && ci_log | grep -q 'flow Antigravity: url https://accounts.google.com/' && echo 1 || echo 0)"
  sleep 3; echo "after agy link: $(resumed)" | tee -a "$OUT/checks.txt"
  front; shot 13-both-running
  check "both flows running at once" "$(ci_log | grep -qE 'flow Codex: (cancel|exit)' && echo 0 || { ci_log | grep -q 'flow Antigravity: prompt code' && grep -q "text=\"$code\"" "$P/13-both-running.xml" && echo 1 || echo 0; })"

  echo "== Codex Cancel resets only Codex"
  top; tap_node '^Cancel$' --after '^Codex \(ChatGPT\)$' --before '^Antigravity \(Gemini, Claude\)$'
  wait_log "flow Codex: (cancel|exit)" 30; sleep 3
  r=$(find_node '^Sign in with Codex$'); echo "codex button after cancel: $r"
  check "Codex Cancel resets (button usable again)" "$(grep -q ' true ' <<<"$r" && echo 1 || echo 0)"
  shot 14-codex-cancelled
  check "no lingering 'Sign-in cancelled.' card after Cancel" "$(grep -q 'Sign-in cancelled' "$P/14-codex-cancelled.xml" && echo 0 || echo 1)"
  check "Antigravity still waiting after Codex cancel" "$(find_node '^Paste the code from Google$' >/dev/null && echo 1 || echo 0)"

  echo "== Antigravity: rejected code -> fresh link"
  tap_node '^Paste the code from Google$'; sleep 1   # the field's placeholder, not the card description
  adb shell input text HLE2EBADCODE123; sleep 1
  # Hide the keyboard (it covers Submit and the swipe area); BACK only while the IME is up, else it leaves the screen.
  adb shell dumpsys input_method | grep -qE "mInputShown=true|mIsInputViewShown=true|isInputViewShown=true" && { echo "IME up -> BACK"; adb shell input keyevent 4; }; sleep 1
  shot 15-agy-code-typed
  tap_node '^Submit code$'
  wait_log "flow Antigravity: error" 120; e=$?
  ci_log | grep "flow Antigravity: error" | tail -1 | tee -a "$OUT/checks.txt"
  check "Antigravity rejects the bad code" "$([ $e = 0 ] && echo 1 || echo 0)"
  for _ in $(seq 1 120); do [ "$(ci_log | grep -c 'flow Antigravity: prompt code')" -ge 2 ] && break; sleep 1; done
  urls=$(ci_log | grep -o "flow Antigravity: url .*" | awk '{print $4}' | sort -u | wc -l)
  echo "antigravity distinct urls: $urls" | tee -a "$OUT/checks.txt"
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
)" 200 | tee -a "$OUT/checks.txt"
  adb shell am start -n $PKG/.MainActivity -e ci_screen main >/dev/null; sleep 2
  n0=$(ci_log | grep -c "status: ")
  adb shell am start -n $PKG/.MainActivity -e ci_screen providers >/dev/null
  for _ in $(seq 1 150); do [ "$(ci_log | grep -c 'status: ')" -gt "$n0" ] && break; sleep 1; done; sleep 2
  top; shot 17-stub-signed-in
  tap_node '^Use for chat$'
  for _ in $(seq 1 150); do ci_log | grep "status: " | tail -1 | grep -q '"model_provider": "openai-codex"' && break; sleep 1; done; sleep 3
  ci_log | grep "status: " | tail -1 | tee -a "$OUT/checks.txt"
  top; shot 18-used-for-chat
  check "Use for chat sets openai-codex as the chat model" "$(ci_log | grep 'status: ' | tail -1 | grep -q '"model_provider": "openai-codex"' && grep -q 'Used for chat' "$P/18-used-for-chat.xml" && echo 1 || echo 0)"
  guest "$CHATPY" 240 | grep -E "CHAT_|MODEL_INFO|delta" | cut -c1-300 | tee "$P/chat-after.txt"
  check "chat via /api/ws streams the stub reply" "$(grep -q '^CHAT_OK.*stub model' "$P/chat-after.txt" && echo 1 || echo 0)"
  check "no 'not connected' error after Use for chat" "$(grep -q 'not connected' "$P/chat-after.txt" && echo 0 || echo 1)"

  echo "== Voice card: no local Whisper, GPT-Live on the (stub) ChatGPT/Codex sign-in"
  r=$(find_node '^Live Mode hears with '); echo "voice line: $r" | tee -a "$OUT/checks.txt"
  check "voice defaults: no key -> hears with 'nothing yet: add an OpenAI or Gemini key', speaks with Edge" "$(grep -q 'hears with nothing yet: add an OpenAI or Gemini key . speaks with Edge' <<<"$r" && echo 1 || echo 0)"
  gl=0; for _ in $(seq 1 60); do find_node '^GPT-Live uses your ChatGPT \(Codex\) sign-in$' >/dev/null && { gl=1; break; }; sleep 2; done
  shot 18b-voice-codex
  check "GPT-Live available via the ChatGPT (Codex) sign-in, no key" "$gl"
  check "no engine selector with a single engine" "$(grep -q 'GPT-Live engine' "$P/18b-voice-codex.xml" && echo 0 || echo 1)"

  echo "== OpenAI API key (for GPT-Live) card"
  tap_node '^OpenAI API key \(sk-'; sleep 1
  adb shell input text sk-hle2e-fake-key-0123456789abcd; sleep 1
  adb shell dumpsys input_method | grep -qE "mInputShown=true|mIsInputViewShown=true|isInputViewShown=true" && adb shell input keyevent 4; sleep 1
  tap_node '^Save key$' --after '^OpenAI API key \(for GPT-Live\)$' --before '^Gemini API key \(for Gemini voice\)$'
  saved=0; for _ in $(seq 1 60); do find_node '^Saved …abcd · GPT-Live ready$' >/dev/null && { saved=1; break; }; sleep 2; done
  shot 19-openai-key-saved
  check "OpenAI key saved -> 'Saved …abcd · GPT-Live ready'" "$saved"
  guest 'stat -c "%a %n" /root/.hermes/.env; grep -c "^VOICE_TOOLS_OPENAI_KEY=" /root/.hermes/.env' 60 | tee "$P/openai-key-env.txt"
  check "key in ~/.hermes/.env (0600) as VOICE_TOOLS_OPENAI_KEY" "$(grep -q '^600 ' "$P/openai-key-env.txt" && grep -qx 1 "$P/openai-key-env.txt" && echo 1 || echo 0)"
  check "key not shown on screen" "$(grep -q 'sk-hle2e' "$P/19-openai-key-saved.xml" && echo 0 || echo 1)"
  r=$(find_node '^Live Mode hears with '); echo "voice line: $r" | tee -a "$OUT/checks.txt"
  check "with the OpenAI key: hears with OpenAI (cloud)" "$(grep -q 'hears with OpenAI (cloud)' <<<"$r" && echo 1 || echo 0)"
  tap_node '^OpenAI API key · gpt-live-1$'; sel=0
  for _ in $(seq 1 60); do find_node '^GPT-Live uses your OpenAI API key$' >/dev/null && { sel=1; break; }; sleep 2; done
  shot 19b-engine-openai
  guest 'grep -A3 "gpt_live:" /root/.hermes/config.yaml' 60 | tee "$P/live-backend-openai.txt"
  check "engine selector (Codex + OpenAI): pick OpenAI -> 'GPT-Live uses your OpenAI API key', voice.gpt_live.backend openai" \
    "$([ $sel = 1 ] && grep -q 'backend: openai' "$P/live-backend-openai.txt" && echo 1 || echo 0)"
  tap_node '^Remove$' --after '^OpenAI API key \(for GPT-Live\)$'; sleep 2
  tap_node '^Remove$' --after '^Remove the OpenAI key\?$'
  gone=0; for _ in $(seq 1 60); do find_node '^Not set$' --after '^Antigravity \(Gemini, Claude\)$' --before '^Gemini API key \(for Gemini voice\)$' >/dev/null && { gone=1; break; }; sleep 2; done
  shot 20-openai-key-removed
  check "Remove clears the key ('Not set')" "$gone"
  check "after removing the OpenAI key: GPT-Live back on the ChatGPT (Codex) sign-in, selector gone" \
    "$(find_node '^GPT-Live uses your ChatGPT \(Codex\) sign-in$' >/dev/null && ! grep -q 'GPT-Live engine' "$P/20-openai-key-removed.xml" && echo 1 || echo 0)"

  echo "== Gemini API key card (Gemini TTS)"
  tap_node '^Gemini API key \(AIza'; sleep 1
  adb shell input text AIzaSyHLe2eDummyKey0123456789wxyz; sleep 1
  adb shell dumpsys input_method | grep -qE "mInputShown=true|mIsInputViewShown=true|isInputViewShown=true" && adb shell input keyevent 4; sleep 1
  tap_node '^Save key$' --after '^Gemini API key \(for Gemini voice\)$'
  saved=0; for _ in $(seq 1 60); do find_node '^Saved …wxyz · Gemini TTS ready$' >/dev/null && { saved=1; break; }; sleep 2; done
  shot 21-gemini-key-saved
  check "Gemini key saved -> 'Saved …wxyz · Gemini TTS ready'" "$saved"
  r=$(find_node '^Live Mode hears with '); echo "voice line: $r" | tee -a "$OUT/checks.txt"
  check "voice line: hears with Gemini (cloud), speaks with Gemini gemini-3.8-flash-lite-tts" "$(grep -q 'hears with Gemini (cloud) . speaks with Gemini gemini-3.8-flash-lite-tts' <<<"$r" && echo 1 || echo 0)"
  tap_node '^Gemini API key · Gemini Live$'; sel=0
  for _ in $(seq 1 60); do find_node '^GPT-Live uses Gemini Live \(gemini-3.8-live\)$' >/dev/null && { sel=1; break; }; sleep 2; done
  shot 21b-engine-gemini
  check "engine selector (Codex + Gemini): pick Gemini -> 'GPT-Live uses Gemini Live (gemini-3.8-live)'" "$sel"
  guest 'stat -c "%a" /root/.hermes/.env; grep -c "^GEMINI_API_KEY=" /root/.hermes/.env; grep -A3 "^tts:" /root/.hermes/config.yaml' 60 | tee "$P/gemini-key-env.txt"
  check "GEMINI_API_KEY in .env (0600) + tts.provider gemini" "$(grep -qx 600 "$P/gemini-key-env.txt" && grep -qx 1 "$P/gemini-key-env.txt" && grep -q 'provider: gemini' "$P/gemini-key-env.txt" && echo 1 || echo 0)"
  check "Gemini key not shown on screen" "$(grep -q 'HLe2eDummy' "$P/21-gemini-key-saved.xml" && echo 0 || echo 1)"
  tap_node '^Remove$' --after '^Gemini API key \(for Gemini voice\)$'; sleep 2
  tap_node '^Remove$' --after '^Remove the Gemini key\?$'
  gone=0; for _ in $(seq 1 60); do find_node '^Not set$' --after '^OpenAI API key \(sk-' >/dev/null && { gone=1; break; }; sleep 2; done
  shot 22-gemini-key-removed
  check "Gemini Remove clears the key, voice back to Edge" "$([ $gone = 1 ] && find_node 'speaks with Edge$' >/dev/null && echo 1 || echo 0)"
  check "after removing the Gemini key: GPT-Live back on the ChatGPT (Codex) sign-in" "$(find_node '^GPT-Live uses your ChatGPT \(Codex\) sign-in$' >/dev/null && echo 1 || echo 0)"
  rec_stop
  ci_log >"$P/ci-log.txt"
  [ "$prov_ok" = 1 ]
}


S="$OUT/screens"; mkdir -p "$S"
snap() { adb exec-out screencap -p >"$S/$1.png"; }
screen() { adb shell am start -n $PKG/.MainActivity -e ci_screen "$1" >/dev/null; sleep "${2:-3}"; }
kb() { echo $(( $(appsh "du -sk ${1:-files}" 2>/dev/null | tr -d '\r' | awk '{print $1}' | head -n1) + 0 )); }
installed_ok() { appsh "ls files/r/opt/hermes-local/bin/hermes-local-start files/engine/installed.json" >/dev/null 2>&1 && \
  ! appsh "ls files/r.partial" >/dev/null 2>&1; }
wait_install() { # wait_install <seconds> <label>: the engine is on disk and the app left "Installing"
  local t=$(date +%s)
  for _ in $(seq 1 $(( $1 / 5 ))); do installed_ok && ci_log | tail -n 400 >/dev/null && break; sleep 5; done
  sleep 10
  echo "$2: engine installed after $(( $(date +%s) - t ))s" | tee -a "$OUT/checks.txt"
}
port_open() { for i in $(seq 1 "${1:-150}"); do [ "$(adb shell "toybox nc -z 127.0.0.1 9119 && echo open" 2>/dev/null | tr -d '\r')" = open ] && { echo "port 9119 open after $((i*2))s"; return 0; }; sleep 2; done; return 1; }
port_closed() { for _ in $(seq 1 30); do [ "$(adb shell "toybox nc -z 127.0.0.1 9119 && echo open" 2>/dev/null | tr -d '\r')" = open ] || return 0; sleep 2; done; return 1; }
login_check() { # login_check <file>: dashboard root, gate, login, authed config
  adb forward tcp:19119 tcp:9119 >/dev/null
  {
    echo "GET / -> $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:19119/)"
    echo "GET /api/config (no login) -> $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:19119/api/config)"
    echo "POST /auth/password-login -> $(curl -s -o /dev/null -w '%{http_code}' -c "$OUT/cookies" -H 'Content-Type: application/json' \
      -d '{"provider":"basic","username":"admin","password":"e2e-password-123"}' http://127.0.0.1:19119/auth/password-login)"
    echo "GET /api/config (logged in) -> $(curl -s -o /dev/null -w '%{http_code}' -b "$OUT/cookies" http://127.0.0.1:19119/api/config)"
  } | tee "$1"
  grep -q "password-login -> 200" "$1" && grep -q "(no login) -> 401" "$1" && grep -q "(logged in) -> 200" "$1"
}
STUBJWT='J=$(python3 -c '"'"'import base64,json,time;b=lambda o: base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip("=");print(b({"alg":"none"})+"."+b({"exp":int(time.time())+86400,"https://api.openai.com/auth":{"chatgpt_account_id":"stub"}})+".sig")'"'"')'
SESSIONS=$(cat <<'G'
cat >/tmp/hl-sessions.py <<'PY'
import sqlite3
c = sqlite3.connect("/root/.hermes/state.db")
t = [r[0] for r in c.execute("select name from sqlite_master where type='table' and name like '%session%' order by name")]
print("SESSIONS", " ".join(n + "=" + str(c.execute("select count(*) from " + n).fetchone()[0]) for n in t))
PY
/opt/hermes-local/bin/hermes-py -I /tmp/hl-sessions.py
G
)

python3 "$(dirname "$0")/stub-llm.py" 18080 >"$OUT/stub-llm.log" 2>&1 &
STUB=$!

# ======================= A. fresh install of test5 =======================
echo "===== A. fresh install"
adb install -r -g "$APK" || exit 1
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
adb shell dumpsys package $PKG | grep -m3 -E "versionCode|versionName" | tee -a "$OUT/checks.txt"
apk_kb=$(( $(stat -c %s "$APK") / 1024 )); note "test5 APK $((apk_kb / 1024)) MiB (CI x86_64 build)"
check "APK is tiny (no bundled engine, < 60 MiB)" "$([ "$apk_kb" -lt 61440 ] && echo 1 || echo 0)"
t0=$(date +%s)
adb shell am start -n $PKG/.MainActivity
sleep 20; snap 01-first-run-download
wait_install 1500 "fresh install"
check "first-run download + install finished" "$(installed_ok && echo 1 || echo 0)"
appsh "cat files/engine/installed.json; echo; ls -la files/engine/downloads 2>&1; cat files/r/opt/hermes-local/engine-version" | tee "$OUT/fresh-installed.txt"
check "installed.json: source default, engine-v0.23.1-2" "$(grep -q '"source":"default"' "$OUT/fresh-installed.txt" && grep -q 'engine-v0.23.1-2' "$OUT/fresh-installed.txt" && echo 1 || echo 0)"
check "download deleted after unpacking (downloads/ empty), no r.partial" "$([ -z "$(appsh 'ls files/engine/downloads' 2>/dev/null | tr -d '\r')" ] && ! appsh 'ls files/r.partial' >/dev/null 2>&1 && echo 1 || echo 0)"
mt=$(appsh "stat -c %Y files/r/opt/hermes/hermes-agent/hermes_cli/__init__.py" | tr -d '\r')
note "hermes_cli/__init__.py mtime $mt, app started $t0 (the unpack ran after that)"
check "mtimes restored on unpack (hermes_cli/__init__.py keeps its build-time mtime)" "$([ -n "$mt" ] && [ "$mt" -lt "$t0" ] && echo 1 || echo 0)"
fresh_kb=$(kb); note "data after install (before first boot): $((fresh_kb / 1024)) MiB"
sleep 5; snap 02-password-card

adb shell am start -n $PKG/.MainActivity -e ci_password e2e-password-123
wait_log "password:" 120; ci_log | grep "password:" | tail -1
sleep 3; snap 03-lever-off
guest 'mkdir -p /root/.hermes && echo HERMES_CODEX_BASE_URL=http://10.0.2.2:18080/v1 >>/root/.hermes/.env && grep CODEX /root/.hermes/.env' 120

adb shell screenrecord --time-limit 60 --bit-rate 6000000 /sdcard/lever-on.mp4 &
REC=$!; sleep 2
adb shell am start -n $PKG/.MainActivity --ez ci_toggle true
port_open 150; ok=$?
[ $ok = 0 ] && ok=1 || ok=0
sleep 6; snap 04-running
kill -INT $REC 2>/dev/null; sleep 3; adb pull /sdcard/lever-on.mp4 "$S/lever-on.mp4" >/dev/null 2>&1 || true
# login_check tees its lines: keep them out of the $( ) value (test5 run 10 failed this check on a 200/401/200 dashboard)
check "fresh: dashboard up, password gate on, login works" "$([ $ok = 1 ] && login_check "$OUT/dashboard-check.txt" >/dev/null && echo 1 || echo 0)"
boot_kb=$(kb); note "data after first boot: $((boot_kb / 1024)) MiB ($boot_kb KiB)"
check "data after first boot below 2.6 GB (x86_64 engine; arm64 is smaller)" "$([ "$boot_kb" -gt 0 ] && [ "$boot_kb" -lt 2539062 ] && echo 1 || echo 0)"

prov_ok=1
if [ "$ok" = 1 ]; then providers_e2e || prov_ok=0; else prov_ok=0; fi
cp "$P"/1[0-9]-*.png "$P"/2[0-9]-*.png "$S/" 2>/dev/null || true

echo "== screens: settings, storage, logs"
screen settings; snap 30-settings
adb shell input swipe 540 1900 540 600 400; sleep 1; snap 31-settings-scrolled
adb shell input swipe 540 1900 540 600 400; sleep 1; snap 32-settings-bottom
screen logs; snap 33-logs
screen storage 8; snap 34-storage-running
check "no agy self-update (no agy.*.old, settings update.mode none)" "$(! appsh 'ls files/r/usr/local/bin/' | grep -q 'agy\..*\.old' && appsh 'cat files/r/root/.gemini/antigravity-cli/settings.json' | grep -q '"update.mode": *"none"' && echo 1 || echo 0)"
appsh "ls -la files/r/usr/local/bin/ | grep agy" | tee -a "$OUT/checks.txt"

echo "== theming (test6): Material You with three wallpaper seeds, AMOLED and dark, then dynamic colour off"
T="$OUT/theme"; mkdir -p "$T"
tsnap() { adb exec-out screencap -p >"$T/$1.png"; }
palette() { # palette <RRGGBB>: what the wallpaper picker writes; SystemUI re-themes and the activity is recreated
  adb shell "settings put secure theme_customization_overlay_packages '{\"android.theme.customization.system_palette\":\"$1\",\"android.theme.customization.theme_style\":\"TONAL_SPOT\",\"android.theme.customization.color_source\":\"preset\",\"_applied_timestamp\":$(date +%s)000}'"
  sleep 8
}
adb shell settings get secure theme_customization_overlay_packages | tee "$T/palette-before.txt"
for seed in 1E6FD9 E0711A C2185B; do
  palette $seed
  adb shell settings get secure theme_customization_overlay_packages >>"$T/palette-set.txt"
  for am in true false; do
    adb shell am start -n $PKG/.MainActivity --ez ci_dynamic true --ez ci_amoled $am -e ci_screen main >/dev/null; sleep 3
    tsnap "main-$seed-amoled_$am"
    screen settings 2; tsnap "settings-$seed-amoled_$am"
    screen storage 6; tsnap "storage-$seed-amoled_$am"
  done
  screen providers 3; tsnap "providers-$seed-amoled_false"
done
for am in true false; do
  adb shell am start -n $PKG/.MainActivity --ez ci_dynamic false --ez ci_amoled $am -e ci_screen main >/dev/null; sleep 3
  tsnap "main-nodynamic-amoled_$am"; screen settings 2; tsnap "settings-nodynamic-amoled_$am"
done
adb shell am start -n $PKG/.MainActivity --ez ci_dynamic true --ez ci_amoled true -e ci_screen main >/dev/null; sleep 2
check "theme screenshots taken (3 seeds x AMOLED/dark + dynamic off)" "$([ "$(ls "$T"/*.png 2>/dev/null | wc -l)" -ge 25 ] && echo 1 || echo 0)"

echo "== lever off, then a plugin with Python deps, then Storage > Clean up"
adb shell screenrecord --time-limit 40 /sdcard/lever-off.mp4 &
REC=$!; sleep 2
screen main 2
adb shell am start -n $PKG/.MainActivity --ez ci_toggle false
port_closed; sleep 8; snap 05-stopped
kill -INT $REC 2>/dev/null; sleep 3; adb pull /sdcard/lever-off.mp4 "$S/lever-off.mp4" >/dev/null 2>&1 || true
appsh "grep -E 'TERM|exited code|--- ' files/engine/logs/engine.log | tail -n 4" | tee -a "$OUT/checks.txt"
base_kb=$(kb)
guest 'timeout 900 hermes plugins install langfuse --enable --yes-deps </dev/null 2>&1 | grep -v "^ *$" | tail -n 6; ls /root/.hermes/installs/*/environments | wc -l' 960 | tee "$OUT/plugin-install.txt"
plug_kb=$(kb); envs_after=$(tail -n1 "$OUT/plugin-install.txt" | tr -dc 0-9)
note "data: baseline $((base_kb / 1024)) MiB, after langfuse install $((plug_kb / 1024)) MiB, dependency generations $envs_after"
screen storage 10; snap 35-storage-before-cleanup
for _ in $(seq 1 60); do r=$(find_node '^Clean up$') && grep -q ' true ' <<<"$r" && break; sleep 3; done
echo "clean up button: $r"; tap_node '^Clean up$'
freed=0; for _ in $(seq 1 90); do find_node '^Freed ' >/dev/null && { freed=1; break; }; sleep 2; done
sleep 6; snap 36-storage-after-cleanup
clean_kb=$(kb)
note "data after Clean up: $((clean_kb / 1024)) MiB (freed $(( (plug_kb - clean_kb) / 1024 )) MiB)"
envs=$(appsh "ls files/r/root/.hermes/installs/*/environments | wc -l" | tr -dc 0-9)
check "Clean up ran (Freed … shown) and left one dependency generation" "$([ $freed = 1 ] && [ "$envs" = 1 ] && echo 1 || echo 0)"
# Where the residue lives (run 10: +268 MiB, the Storage screen's "Hermes engine" grew 440 MB with the install):
# proot's link2symlink store (uv hard-links wheels from its cache), /tmp, and the env's own size.
appsh "du -sk files/r/.l2s files/r/tmp files/r/root/.cache files/r/root/.hermes/installs files/r/root/.hermes/cache 2>/dev/null; \
  echo l2s_entries \$(ls -A files/r/.l2s 2>/dev/null | wc -l); for e in files/r/root/.hermes/installs/*/environments/*; do \
  echo env \$e symlinks \$(find \$e -type l | wc -l) files \$(find \$e -type f | wc -l); done" | tee "$OUT/cleanup-residue.txt"
sed 's/^/NOTE residue: /' "$OUT/cleanup-residue.txt" | tr -d '\r' >>"$OUT/checks.txt"
check "after Clean up the data is back within 250 MiB of the baseline" "$([ "$clean_kb" -gt 0 ] && [ $((clean_kb - base_kb)) -lt 256000 ] && echo 1 || echo 0)"
guest 'COLUMNS=200 hermes plugins list </dev/null 2>&1 | grep -E "^│ langfuse"; /opt/hermes-local/bin/hermes-py -c "import hermes_bootstrap, langfuse; print(\"LANGFUSE_IMPORT_OK\")"' 300 | tee "$OUT/plugin-after-cleanup.txt"
check "langfuse still enabled + importable after Clean up" "$(grep -q 'LANGFUSE_IMPORT_OK' "$OUT/plugin-after-cleanup.txt" && grep -qE 'langfuse +│ enabled' "$OUT/plugin-after-cleanup.txt" && echo 1 || echo 0)"
adb shell am start -n $PKG/.MainActivity --ez ci_toggle true
port_open 150 && login_check "$OUT/dashboard-after-cleanup.txt" >/dev/null; r=$?
check "Hermes starts after Clean up" "$([ $r = 0 ] && echo 1 || echo 0)"
adb shell am start -n $PKG/.MainActivity --ez ci_toggle false; port_closed
appsh "cat files/engine/logs/engine.log" >"$OUT/engine-fresh.log" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG >"$OUT/gfxinfo.txt" 2>/dev/null || true
grep -E "Janky frames|Total frames|90th|99th" "$OUT/gfxinfo.txt" | tee -a "$OUT/checks.txt" || true

# ======================= B. upgrade from test4 =======================
if [ -n "$OLD_APK" ]; then
  echo "===== B. upgrade test4 -> test5"
  adb uninstall $PKG >/dev/null
  adb install -r -g "$OLD_APK" || check "install the test4 APK" 0
  adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
  adb logcat -c
  adb shell am start -n $PKG/.MainActivity
  for _ in $(seq 1 120); do appsh "ls files/r/opt/hermes-local/bin/hermes-local-start" >/dev/null 2>&1 && ! appsh "ls files/r.partial" >/dev/null 2>&1 && break; sleep 5; done
  sleep 15
  adb shell am start -n $PKG/.MainActivity -e ci_password e2e-password-123
  wait_log "password:" 180
  guest "$(printf '%s\n' 'mkdir -p /root/.hermes && echo HERMES_CODEX_BASE_URL=http://10.0.2.2:18080/v1 >>/root/.hermes/.env' "$STUBJWT" \
    'hermes auth add openai-codex --type api-key --api-key "$J" --label e2e-upgrade </dev/null 2>&1 | tail -n 1' \
    '/opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_provider.py ensure openai-codex 2>&1 | tail -n 1' \
    'echo upgrade-marker >/root/my-notes.txt; mkdir -p /root/.hermes/memories && echo remember-me >/root/.hermes/memories/e2e-marker.md')" 240 | tee "$OUT/upgrade-setup.txt"
  adb shell am start -n $PKG/.MainActivity --ez ci_toggle true
  port_open 200 >/dev/null; guest "$CHATPY" 240 | grep -E "CHAT_" | cut -c1-200 | tee "$OUT/upgrade-chat-test4.txt"
  guest "$SESSIONS
/bin/bash /opt/hermes-local/bin/hermes-local-auth status; cat /root/.hermes/installs/*/facts.json | grep environment; cat /opt/hermes-local/engine-version | head -3" 120 | tee "$OUT/upgrade-before.txt"
  old_env=$(grep -o 'environments/[0-9a-f]*' "$OUT/upgrade-before.txt" | head -n1)
  old_kb=$(kb); note "test4: data $((old_kb / 1024)) MiB, env $old_env, $(grep SESSIONS "$OUT/upgrade-before.txt")"
  check "test4 baseline: chat session created with the stub sign-in" "$(grep -q '^CHAT_OK' "$OUT/upgrade-chat-test4.txt" && echo 1 || echo 0)"
  adb shell am start -n $PKG/.MainActivity --ez ci_toggle false; port_closed
  snap 40-test4-before-upgrade
  echo "== install test5 over test4"
  adb install -r -g "$APK" || check "upgrade install of the test5 APK" 0
  adb logcat -c
  t1=$(date +%s)
  adb shell am start -n $PKG/.MainActivity
  sleep 20; snap 41-upgrade-downloading
  for _ in $(seq 1 300); do appsh "grep -q engine-v0.23.1-2 files/engine/installed.json" >/dev/null 2>&1 && ! appsh "ls files/r.partial" >/dev/null 2>&1 && break; sleep 5; done
  sleep 15; echo "upgrade: engine replaced after $(( $(date +%s) - t1 ))s" | tee -a "$OUT/checks.txt"
  snap 42-after-upgrade
  appsh "cat files/engine/installed.json; echo; ls files/engine/downloads; cat files/r/opt/hermes-local/engine-version | head -3; ls files/r/root/.local/share 2>&1" | tee "$OUT/upgrade-installed.txt"
  check "upgrade: the engine updated itself to engine-v0.23.1-2 (download)" "$(grep -q '"source":"default"' "$OUT/upgrade-installed.txt" && grep -q 'hermes_ref=v0.23.1' "$OUT/upgrade-installed.txt" && echo 1 || echo 0)"
  guest "$SESSIONS
/bin/bash /opt/hermes-local/bin/hermes-local-auth status; /bin/bash /opt/hermes-local/bin/hermes-local-passwd --check && echo PASSWORD_OK; cat /root/my-notes.txt /root/.hermes/memories/e2e-marker.md; ls /root/.hermes/installs/*/environments; cat /root/.hermes/installs/*/facts.json | grep environment; ls -d /root/.local/share/uv 2>&1" 180 | tee "$OUT/upgrade-after.txt"
  check "upgrade: password kept" "$(grep -q PASSWORD_OK "$OUT/upgrade-after.txt" && echo 1 || echo 0)"
  check "upgrade: Codex login kept (auth status codex 1, e2e-upgrade)" "$(grep -q '"codex": 1' "$OUT/upgrade-after.txt" && grep -q 'e2e-upgrade' "$OUT/upgrade-after.txt" && echo 1 || echo 0)"
  check "upgrade: sessions kept (same counts as on test4)" "$(b=$(grep SESSIONS "$OUT/upgrade-before.txt"); a=$(grep SESSIONS "$OUT/upgrade-after.txt"); [ -n "$b" ] && [ "$b" = "$a" ] && ! grep -q 'sessions=0' <<<"$b" && echo 1 || echo 0)"
  check "upgrade: user files and memories kept" "$(grep -qx upgrade-marker "$OUT/upgrade-after.txt" && grep -qx remember-me "$OUT/upgrade-after.txt" && echo 1 || echo 0)"
  new_env=$(grep -o 'environments/[0-9a-f]*' "$OUT/upgrade-after.txt" | tail -n1)
  check "upgrade: no old environment (test4's $old_env gone, one generation, no uv CPython)" "$([ -n "$new_env" ] && [ "$new_env" != "$old_env" ] && \
    [ "$(grep -cE '^[0-9a-f]{32}$' "$OUT/upgrade-after.txt")" = 1 ] && grep -q 'No such file' "$OUT/upgrade-after.txt" && echo 1 || echo 0)"
  check "upgrade: no leftovers (no r.partial, downloads/ empty)" "$(! appsh 'ls files/r.partial' >/dev/null 2>&1 && [ -z "$(appsh 'ls files/engine/downloads' 2>/dev/null | tr -d '\r')" ] && echo 1 || echo 0)"
  up_kb=$(kb); note "upgrade: data $((old_kb / 1024)) MiB on test4 -> $((up_kb / 1024)) MiB on test5"
  adb shell am start -n $PKG/.MainActivity --ez ci_toggle true
  port_open 200 && login_check "$OUT/dashboard-after-upgrade.txt" >/dev/null; r=$?
  check "upgrade: dashboard login with the old password" "$([ $r = 0 ] && echo 1 || echo 0)"
  guest "$CHATPY" 240 | grep -E "CHAT_" | cut -c1-200 | tee "$OUT/upgrade-chat-test5.txt"
  check "upgrade: chat still works on the carried sign-in + model setting (new dependency env)" "$(grep -q '^CHAT_OK' "$OUT/upgrade-chat-test5.txt" && echo 1 || echo 0)"
  snap 43-upgraded-running
  adb shell am start -n $PKG/.MainActivity --ez ci_toggle false; port_closed
  appsh "cat files/engine/logs/engine.log" >"$OUT/engine-upgrade.log" 2>/dev/null || true
fi

kill $LOGCAT $STUB 2>/dev/null
rm -f "$OUT/cookies"
if cat "$OUT/logcat.txt" "$OUT"/engine-*.log "$P"/*.xml 2>/dev/null | grep -v "diag-update:" | grep -qiE 'source-update|update is still running'; then
  check "no source-update text anywhere" 0; else check "no source-update text anywhere" 1; fi
echo "CHECKS: $(grep -c 'CHECK ok' "$OUT/checks.txt") ok, $(grep -c 'CHECK FAIL' "$OUT/checks.txt") failed"; grep "CHECK FAIL" "$OUT/checks.txt"
[ "$all_ok" = 1 ] && { echo "E2E PASS"; exit 0; }
echo "E2E FAIL"; exit 1
