# Hermes Local voice probe: sourced by smoke-test.sh (and feature-probe.sh). Calls the dashboard voice
# endpoints the Hermes apps' Live Mode uses (engine/rootfs/hermes_local_voice_check.py) with a real speech
# sample (engine/testdata/jfk.wav, public-domain JFK inaugural excerpt, 11 s, 16 kHz mono).
#   voice_probe <label> [strict]   strict=1: failures fail the smoke (check), else informational (note)
# Needs from smoke-test.sh: run, M, work, PW, check, note.
voice_probe() {
  local label=$1 strict=${2:-0} here
  here=$(dirname "$(realpath "${BASH_SOURCE[0]}")")
  echo "== voice probe ($label)"
  # Always the repo copies: an older engine (the repro) may not have them yet.
  cp "$here/rootfs/hermes_local_voice_check.py" "$M/tmp/hermes_local_voice_check.py"
  cp "$here/testdata/jfk.wav" "$M/tmp/jfk.wav"
  echo "$PW" | run /bin/bash -c '/bin/bash /opt/hermes-local/bin/hermes-py -I /tmp/hermes_local_voice_check.py http://127.0.0.1:9119 admin /tmp/jfk.wav country' \
    >"$work/voice-$label.txt" 2>&1
  grep -E '^VOICE' "$work/voice-$label.txt" | sed "s/^/[$label] /"
  grep -q '^VOICE_RESULT' "$work/voice-$label.txt" || tail -n 15 "$work/voice-$label.txt"
  local name
  for name in tts speak-stream; do
    local ok=0
    grep -qE "^VOICE $name: ok" "$work/voice-$label.txt" && ok=1
    if [ "$strict" = 1 ]; then check "voice ($label) $name" "$ok"; else note "voice ($label) $name $([ $ok = 1 ] && echo ok || echo FAIL)"; fi
  done
  # No local Whisper any more: with no OpenAI/Gemini key, transcribe must answer a clear message (not "No STT
  # provider available") so the app can show what to do.
  local sttok=0
  grep -qE '^VOICE stt: .*POST /api/audio/transcribe 400 .*add an OpenAI or Gemini API key' "$work/voice-$label.txt" && sttok=1
  if [ "$strict" = 1 ]; then check "voice ($label) transcribe without a key -> clear 'add an OpenAI or Gemini API key' error" "$sttok"; fi
  check "engine ships no faster-whisper / Whisper model" "$( [ ! -d "$M/opt/hermes-local/hf-hub" ] && ! ls -d "$M"/opt/hermes/hermes-agent/.venv/lib/python3*/site-packages/faster_whisper >/dev/null 2>&1 && echo 1 || echo 0)"
  # What the engine logged for these requests (provider, detected language, errors).
  grep -hE 'Transcri|whisper|STT|TTS|audio' "$M/root/.hermes/logs/"*.log 2>/dev/null | tail -n 12 | cut -c1-300 | sed "s/^/[$label log] /"
  [ -n "${CAPTURE_DIR:-}" ] && cp "$work/voice-$label.txt" "$CAPTURE_DIR/voice-$label.txt"
  return 0
}

voice_gptlive_probe() { # optional OpenAI key path (the app's "OpenAI API key" field): GPT-Live direct + OpenAI STT
  local strict=${1:-0} st st2 code fake mode ok=0
  [ -f "$M/opt/hermes-local/bin/hermes-local-voice" ] || { note "gpt-live key path: hermes-local-voice missing (old engine)"; return 0; }
  echo "== GPT-Live key path (OpenAI, direct)"
  fake="sk-hermeslocal-smoke-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  echo "$fake" | hlv openai-key set >"$work/gl-set.txt" 2>&1
  tail -n1 "$work/gl-set.txt" | sed 's/"openai_key_hint": "[^"]*"/"openai_key_hint": "…"/' | cut -c1-500
  hlv live-backend set openai >/dev/null 2>&1
  st=$(live_status); echo "   status with key, backend openai (no restart): $st"
  code=$(curl -s -o "$work/gl-sess.json" -w '%{http_code}' --max-time 90 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" \
    -H 'Content-Type: application/json' -d '{"sdp":"v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"}' http://127.0.0.1:9119/api/audio/voice-live/session)
  echo "   session with a fake key -> $code $(head -c 300 "$work/gl-sess.json")"
  # 502 with OpenAI's 401 = Hermes really sent the SDP offer to api.openai.com/v1/live/sessions (stock path).
  check "gpt-live (OpenAI key): session reaches api.openai.com (401 for the fake key)" \
    "$([ "$code" = 502 ] && grep -qE 'failed \(401\)' "$work/gl-sess.json" && echo 1 || echo 0)"
  code=$(stt_post "$work/gl-stt.json"); echo "   transcribe with the fake key -> $code $(head -c 300 "$work/gl-stt.json")"
  grep -q '"stt_provider": "openai"' "$work/gl-set.txt" && echo "   stt provider: openai (Hermes' own)"
  check "OpenAI STT: transcribe reaches api.openai.com (401 / incorrect key)" \
    "$(grep -q '"stt_provider": "openai"' "$work/gl-set.txt" && grep -qiE '401|incorrect api key' "$work/gl-stt.json" && echo 1 || echo 0)"
  grep -q "$fake" "$work/gl-sess.json" "$work/gl-set.txt" "$work/gl-stt.json" && echo "   ::warning::key echoed back"
  mode=$(run /bin/bash -c 'stat -c %a /root/.hermes/.env' 2>/dev/null)
  hlv openai-key clear >"$work/gl-clr.txt" 2>&1
  st2=$(live_status)
  echo "   status after clear: $st2 (.env mode $mode) stt: $(grep -o '"stt_provider": "[^"]*"' "$work/gl-clr.txt")"
  grep -q '"backend":"openai"' <<<"$st" && grep -q '"available":true' <<<"$st" && grep -q '"selected":"auto"' <<<"$st2" \
    && ! grep -q '"openai"' <<<"$(grep -o '"backends":\[[^]]*\]' <<<"$st2")" && [ "$mode" = 600 ] \
    && grep -q '"stt_provider": "hermes-cloud"' "$work/gl-clr.txt" && ok=1
  if [ "$strict" = 1 ]; then check "openai key set -> backend openai; clear -> openai gone, selection auto, STT hermes-cloud (.env 0600)" "$ok"; else note "gpt-live key path ok=$ok"; fi
}

voice_gemini_probe() { # optional Gemini key path (the app's "Gemini API key" field): Gemini TTS
  local strict=${1:-0} code det st ok=0 back
  [ -f "$M/opt/hermes-local/bin/hermes-local-voice" ] || { note "gemini key path: hermes-local-voice missing (old engine)"; return 0; }
  echo "== Gemini key path (dummy key)"
  echo "AIzaSyHermesLocalSmoke$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')" | run /bin/bash /opt/hermes-local/bin/hermes-local-voice gemini-key set >"$work/gm-set.txt" 2>&1
  st=$(tail -n1 "$work/gm-set.txt"); echo "   $(sed 's/"gemini_key_hint": "[^"]*"/"gemini_key_hint": "…"/' <<<"$st")"
  run /bin/bash -c 'grep -A3 "^tts:" /root/.hermes/config.yaml; grep -c "^GEMINI_API_KEY=" /root/.hermes/.env; stat -c %a /root/.hermes/.env' 2>&1 | sed 's/^/   /' | tee "$work/gm-cfg.txt"
  code=$(curl -s -o "$work/gm-speak.json" -w '%{http_code}' --max-time 120 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" \
    -H 'Content-Type: application/json' -d '{"text":"Hello from Hermes Local."}' http://127.0.0.1:9119/api/audio/speak)
  det=$(head -c 400 "$work/gm-speak.json"); echo "   speak with the dummy key -> $code $det"
  # Clean failure = Google rejected the key (its "API key not valid" / API_KEY_INVALID / 401 / 403), i.e. the
  # request reached generativelanguage.googleapis.com with the configured model; bad wiring would say
  # "No TTS provider", "GEMINI_API_KEY not set" or name an unknown provider/model.
  grep -q '"tts_provider": "gemini"' <<<"$st" && grep -q '"tts_model": "gemini-3.8-flash-lite-tts"' <<<"$st" && grep -q '"gemini_key": 1' <<<"$st" \
    && grep -qiE 'Gemini TTS API error \(HTTP (400|401|403)\).*(API key not valid|API_KEY_INVALID|PERMISSION_DENIED|UNAUTHENTICATED|api key)' <<<"$det" \
    && grep -qx 600 <<<"$(tail -n1 "$work/gm-cfg.txt" | tr -d ' ')" && ok=1
  if [ "$strict" = 1 ]; then check "gemini key: config written (tts gemini-3.8-flash-lite-tts), speak fails with Google's auth error" "$ok"; else note "gemini key path ok=$ok"; fi
  run /bin/bash /opt/hermes-local/bin/hermes-local-voice gemini-key clear >"$work/gm-clr.txt" 2>&1
  back=$(curl -s -o /dev/null -w '%{http_code}' --max-time 120 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" \
    -H 'Content-Type: application/json' -d '{"text":"Back to Edge."}' http://127.0.0.1:9119/api/audio/speak)
  echo "   after clear: $(tail -n1 "$work/gm-clr.txt" | grep -o '"tts_provider": "[a-z]*"'), speak -> $back"
  if [ "$strict" = 1 ]; then check "gemini key clear -> Edge TTS speaks again" "$([ "$back" = 200 ] && grep -q '"tts_provider": "edge"' "$work/gm-clr.txt" && echo 1 || echo 0)"; fi
}

# ---- live voice (the app's GPT-Live mode) through the host: hermes_local_live.py --------------------------------
hlv() { run /bin/bash /opt/hermes-local/bin/hermes-local-voice "$@"; }
live_status() { curl -s --max-time 60 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" http://127.0.0.1:9119/api/audio/voice-live/status; }
live_check() { # live_check <label> <action> [port]: stand-in app (real aiortc offer) through POST /api/audio/voice-live/session
  cp "$(dirname "$(realpath "${BASH_SOURCE[0]}")")/rootfs/hermes_local_live_check.py" "$M/tmp/hermes_local_live_check.py"
  echo "$PW" | run /bin/bash -c "cd /tmp && timeout 200 /bin/bash /opt/hermes-local/bin/hermes-py -I /tmp/hermes_local_live_check.py endpoint http://127.0.0.1:9119 admin $2 ${3:-18770}" \
    >"$work/live-$1.txt" 2>&1
  grep -E '^LIVE' "$work/live-$1.txt" | cut -c1-500 | sed "s/^/[$1] /"
  grep -q '^LIVE_RESULT' "$work/live-$1.txt" || tail -n 20 "$work/live-$1.txt"
  [ -n "${CAPTURE_DIR:-}" ] && cp "$work/live-$1.txt" "$CAPTURE_DIR/live-$1-$MODE.txt"
  return 0
}

voice_codex_live_probe() { # needs the stub Codex sign-in made earlier by the smoke
  local st
  echo "== live voice: ChatGPT (Codex) backend, no API key"
  st=$(live_status); echo "   status: $st"
  check "voice-live/status available via the Codex sign-in (no API key)" "$(grep -q '"available":true' <<<"$st" && grep -q '"backend":"codex"' <<<"$st" && grep -q '"model":"gpt-live-1-codex"' <<<"$st" && echo 1 || echo 0)"
  live_check codex-real session
  # chatgpt.com/backend-api/codex/realtime/calls validated the stub token itself (OpenAI's auth layer), i.e. the
  # host built its own WebRTC offer and sent it with the Codex bearer + ChatGPT-Account-Id + quicksilver=v2.
  check "codex: real offer reaches chatgpt.com realtime/calls (auth error 401/403 for the stub token)" \
    "$(grep -qE '^LIVE_SESSION 502 .*rejected by chatgpt.com \(HTTP 40[13]\)' "$work/live-codex-real.txt" && echo 1 || echo 0)"
  hlv live-url codex http://127.0.0.1:18772/realtime/calls >/dev/null
  live_check codex-fake fake-codex 18772
  check "codex relay end to end through the dashboard (stand-in app <-> host <-> fake Codex peer)" \
    "$(grep -q '^LIVE_RESULT ok' "$work/live-codex-fake.txt" && echo 1 || echo 0)"
  hlv live-url codex clear >/dev/null
}

voice_gemini_live_probe() { # dummy Gemini key: Gemini Live (real Google reject + fake end to end) and Gemini STT
  local st code
  echo "== live voice + STT: Gemini key (dummy)"
  echo "AIzaSyHermesLocalLive$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')" | hlv gemini-key set >"$work/gml-set.txt" 2>&1
  hlv live-backend set gemini >"$work/gml-be.txt" 2>&1; tail -n1 "$work/gml-be.txt" | sed 's/"gemini_key_hint": "[^"]*"/"gemini_key_hint": "…"/' | cut -c1-400
  st=$(live_status); echo "   status: $st"
  check "voice-live/status: Gemini Live selected (gemini-3.8-live)" "$(grep -q '"available":true' <<<"$st" && grep -q '"backend":"gemini"' <<<"$st" && grep -q '"model":"gemini-3.8-live"' <<<"$st" && echo 1 || echo 0)"
  live_check gemini-real session
  check "gemini live: Google rejects the dummy key at session setup ('API key not valid')" \
    "$(grep -qE '^LIVE_SESSION 502 .*Gemini Live rejected the session \(gemini-3.8-live\).*API key not valid' "$work/live-gemini-real.txt" && echo 1 || echo 0)"
  hlv live-url gemini ws://127.0.0.1:18771/ws >/dev/null
  live_check gemini-fake fake-gemini 18771
  check "gemini live end to end through the dashboard (stand-in app <-> host <-> fake Gemini Live)" \
    "$(grep -q '^LIVE_RESULT ok' "$work/live-gemini-fake.txt" && echo 1 || echo 0)"
  hlv live-url gemini clear >/dev/null
  # Gemini speech-to-text (no OpenAI key saved): hermes_local_stt.py -> generateContent, Google's own auth error.
  code=$(stt_post "$work/gml-stt.json"); echo "   transcribe -> $code $(head -c 300 "$work/gml-stt.json")"
  check "gemini STT: transcribe reaches Google (400 'API key not valid')" \
    "$(grep -qE 'Gemini transcription \(gemini-3.5-flash-lite\) failed: HTTP 400: API key not valid' "$work/gml-stt.json" && echo 1 || echo 0)"
  hlv gemini-key clear >"$work/gml-clr.txt" 2>&1
  st=$(live_status); echo "   after clear: $st"
  check "gemini key clear -> live backend back to auto (codex)" "$(grep -q '"backend":"codex"' <<<"$st" && grep -q '"selected":"auto"' <<<"$st" && echo 1 || echo 0)"
}

stt_post() { # stt_post <out file>: POST jfk.wav to /api/audio/transcribe, print the HTTP code
  python3 -c 'import base64,json,sys; print(json.dumps({"data_url":"data:audio/wav;base64,"+base64.b64encode(open(sys.argv[1],"rb").read()).decode(),"mime_type":"audio/wav"}))' \
    "$(dirname "$(realpath "${BASH_SOURCE[0]}")")/testdata/jfk.wav" >"$work/stt-body.json"
  curl -s -o "$1" -w '%{http_code}' --max-time 180 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" \
    -H 'Content-Type: application/json' --data-binary @"$work/stt-body.json" http://127.0.0.1:9119/api/audio/transcribe
}
