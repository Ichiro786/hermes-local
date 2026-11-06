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
  echo "$PW" | run /bin/bash -c 'export HF_HUB_CACHE=/opt/hermes-local/hf-hub; /bin/bash /opt/hermes-local/bin/hermes-py -I /tmp/hermes_local_voice_check.py http://127.0.0.1:9119 admin /tmp/jfk.wav country' \
    >"$work/voice-$label.txt" 2>&1
  grep -E '^VOICE' "$work/voice-$label.txt" | sed "s/^/[$label] /"
  grep -q '^VOICE_RESULT' "$work/voice-$label.txt" || tail -n 15 "$work/voice-$label.txt"
  local name
  for name in stt-provider-resolved stt tts speak-stream; do
    local ok=0
    grep -qE "^VOICE $name: ok" "$work/voice-$label.txt" && ok=1
    if [ "$strict" = 1 ]; then check "voice ($label) $name" "$ok"; else note "voice ($label) $name $([ $ok = 1 ] && echo ok || echo FAIL)"; fi
  done
  # What the engine logged for these requests (provider, detected language, errors).
  grep -hE 'Transcri|whisper|STT|TTS|audio' "$M/root/.hermes/logs/"*.log 2>/dev/null | tail -n 12 | cut -c1-300 | sed "s/^/[$label log] /"
  [ -n "${CAPTURE_DIR:-}" ] && cp "$work/voice-$label.txt" "$CAPTURE_DIR/voice-$label.txt"
  return 0
}

voice_bench() { # Whisper tiny/base/small speed + language detection on this CPU (informational)
  local here; here=$(dirname "$(realpath "${BASH_SOURCE[0]}")")
  cp "$here/voice-bench.py" "$M/tmp/voice-bench.py"; cp "$here/testdata/jfk.wav" "$M/tmp/jfk.wav"
  echo "== whisper benchmark"
  run /bin/bash -c "timeout 900 /bin/bash /opt/hermes-local/bin/hermes-py -I /tmp/voice-bench.py /tmp/jfk.wav ${VOICE_BENCH_MODELS:-tiny,base,small}" 2>&1 \
    | grep -E '^BENCH|Error|error' | cut -c1-400
}

voice_gptlive_probe() { # optional OpenAI key path (the app's "OpenAI API key (for GPT-Live)" field)
  local strict=${1:-0} st st2 code fake
  [ -f "$M/opt/hermes-local/bin/hermes-local-voice" ] || { note "gpt-live key path: hermes-local-voice missing (old engine)"; return 0; }
  echo "== GPT-Live key path"
  fake="sk-hermeslocal-smoke-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  echo "$fake" | run /bin/bash /opt/hermes-local/bin/hermes-local-voice openai-key set >"$work/gl-set.txt" 2>&1
  tail -n1 "$work/gl-set.txt" | sed 's/"openai_key_hint": "[^"]*"/"openai_key_hint": "…"/'
  st=$(curl -s --max-time 60 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" http://127.0.0.1:9119/api/audio/voice-live/status)
  echo "   status with key (no restart): $st"
  code=$(curl -s -o "$work/gl-sess.json" -w '%{http_code}' --max-time 90 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" \
    -H 'Content-Type: application/json' -d '{"sdp":"v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"}' http://127.0.0.1:9119/api/audio/voice-live/session)
  echo "   session with a fake key -> $code $(head -c 300 "$work/gl-sess.json")"
  grep -q "$fake" "$work/gl-sess.json" "$work/gl-set.txt" && echo "   ::warning::key echoed back"
  local mode; mode=$(run /bin/bash -c 'stat -c %a /root/.hermes/.env' 2>/dev/null)
  run /bin/bash /opt/hermes-local/bin/hermes-local-voice openai-key clear >"$work/gl-clr.txt" 2>&1
  st2=$(curl -s --max-time 60 -b "$work/cookies" -H "Origin: http://127.0.0.1:9119" http://127.0.0.1:9119/api/audio/voice-live/status)
  echo "   status after clear: $st2 (.env mode $mode)"
  local ok=0
  grep -q '"available":true' <<<"$st" && grep -q '"available":false' <<<"$st2" && [ "$mode" = 600 ] && ok=1
  if [ "$strict" = 1 ]; then check "gpt-live key set -> available, clear -> unavailable (.env 0600)" "$ok"; else note "gpt-live key path ok=$ok"; fi
  # 502 with OpenAI's 401 = Hermes really sent the SDP offer to api.openai.com/v1/live/sessions.
  note "gpt-live session with fake key -> $code $(grep -oE 'failed \([0-9]+\)' "$work/gl-sess.json" | head -n1)"
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
