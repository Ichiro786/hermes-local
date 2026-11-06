# Hermes Local feature probe: sourced by smoke-test.sh when FEATURES=1, after the stub Codex sign-in
# and the /api/ws chat turn. Exercises what the Hermes mobile app / dashboard use, on this engine.
# Prints one "FEATURE <name>: works|fail|info <detail>" line per probe; never changes the smoke result.
# Needs from smoke-test.sh: run, base, work, PW, STUB_PORT.
feat() { echo "FEATURE $1: $2 ${3:-}" | cut -c1-400; }
api() { # api METHOD PATH [json] -> body in $work/f.json, code on stdout
  local m=$1 p=$2 d=${3:-}
  if [ -n "$d" ]; then curl -s -o "$work/f.json" -w '%{http_code}' --max-time 120 -b "$work/cookies" -X "$m" \
      -H "Origin: $base" -H 'Content-Type: application/json' -d "$d" "$base$p"
  else curl -s -o "$work/f.json" -w '%{http_code}' --max-time 120 -b "$work/cookies" -X "$m" -H "Origin: $base" "$base$p"; fi; }
body() { head -c "${1:-200}" "$work/f.json" | tr '\n' ' '; }

echo "== feature probe"
cp "$(dirname "$(realpath "$0")")/diag-update.py" "$M/tmp/diag-update.py"
run /bin/bash -c '/opt/hermes-local/bin/hermes-py -I /tmp/diag-update.py' | grep -E "venv_is_current|stamp parts" | cut -c1-900
# Sessions + analytics (gateway-owned session store)
c=$(api GET /api/sessions); n=$(jq -r '(.sessions // .items // .) | length' "$work/f.json" 2>/dev/null)
[ "$c" = 200 ] && feat sessions works "GET /api/sessions 200, $n session(s) after the chat turn" || feat sessions fail "$c $(body)"
c=$(api GET /api/analytics/usage); [ "$c" = 200 ] && feat analytics works "200" || feat analytics fail "$c $(body)"
c=$(api GET /api/status); feat gateway info "$(jq -c '{version, gateway_running, gateway_state}' "$work/f.json" 2>/dev/null)"

# Model switching (dashboard /api/model/set; applies to new sessions) + a chat on the new model
c=$(api POST /api/model/set '{"scope":"main","provider":"openai-codex","model":"gpt-5.5"}')
c2=$(api GET /api/model/info); m=$(jq -r .model "$work/f.json" 2>/dev/null)
if [ "$c" = 200 ] && [ "$m" = gpt-5.5 ]; then
  echo "$PW" | run /bin/bash -c 'HL_CHAT_TIMEOUT=120 /opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_chat_check.py http://127.0.0.1:9119 admin Again' >"$work/chat-switch.txt" 2>&1
  grep -q '^CHAT_OK' "$work/chat-switch.txt" && feat model-switch works "set gpt-5.5 via /api/model/set, chat OK on it" || feat model-switch fail "chat after switch: $(grep -m1 CHAT_ "$work/chat-switch.txt")"
else feat model-switch fail "set=$c info=$c2 model=$m"; fi

# Skills: official hub install (spawns `hermes skills install`), toggle
c=$(api GET /api/skills/hub/official); id=$(jq -r '[.skills[]? | select(.installed != true) | .identifier] | first // empty' "$work/f.json" 2>/dev/null)
if [ -n "$id" ]; then
  c=$(api POST /api/skills/hub/install "{\"identifier\":\"$id\"}"); sk=${id##*/}
  for _ in $(seq 1 60); do api GET /api/skills >/dev/null; grep -q "\"name\":\"$sk\"" "$work/f.json" && break; sleep 3; done
  grep -q "\"name\":\"$sk\"" "$work/f.json" && feat skills-hub-install works "$id installed (POST /api/skills/hub/install $c)" || feat skills-hub-install fail "$id: install $c, not listed after 180 s"
else feat skills-hub-install fail "no official skill identifier ($c)"; fi
sk=$(api GET /api/skills >/dev/null; jq -r '.[0].name // empty' "$work/f.json" 2>/dev/null)
c=$(api PUT /api/skills/toggle "{\"name\":\"$sk\",\"enabled\":false}"); c2=$(api PUT /api/skills/toggle "{\"name\":\"$sk\",\"enabled\":true}")
[ "$c$c2" = 200200 ] && feat skills-toggle works "$sk off/on" || feat skills-toggle fail "$c $c2 $(body)"

# Plugins through the dashboard API (the app's Plugins screen): disable / enable an installed one
c=$(api POST /api/dashboard/agent-plugins/quota/disable); b1=$(body 160); c2=$(api POST /api/dashboard/agent-plugins/quota/enable); b2=$(body 160)
[ "$c$c2" = 200200 ] && feat plugin-enable-disable works "quota disable/enable via API" || feat plugin-enable-disable fail "disable $c $b1 / enable $c2 $b2"
c=$(api POST /api/dashboard/agent-plugins/install '{"identifier":"","catalog_name":"quota","enable":true,"force":true}')
feat plugin-install-api "$([ "$c" = 200 ] && echo works || echo fail)" "POST install quota (force) -> $c $(body 160)"
# Optional dependency groups (Hermes installs them on demand with uv): MCP SDK and Edge TTS
for x in mcp edge-tts stt-whisper; do
  out=$(run /bin/bash -lc "timeout 600 hermes pm install --extra $x </dev/null 2>&1 | grep -v '^ *$' | tail -n 4" | tr '\n' ' ')
  mod=${x//-/_}; [ "$x" = stt-whisper ] && mod=faster_whisper
  run /bin/bash -lc "/opt/hermes-local/bin/hermes-py -c 'import hermes_bootstrap, $mod' 2>/dev/null" && feat "extra-$x" works "hermes pm install --extra $x: $out" || feat "extra-$x" fail "$out"
done
# A new dependency generation is used by processes started after it (the app: lever off/on).
echo "== restart the engine to load the new dependency generation"
kill -TERM "$runner" 2>/dev/null; sleep 5; pkill -KILL -f "/opt/hermes" 2>/dev/null; sleep 2
run /bin/bash /opt/hermes-local/bin/hermes-local-start >"$work/engine2.log" 2>&1 &
runner=$!
for _ in $(seq 1 420); do grep -qE 'HERMES_LOCAL_(READY|FAILED)' "$work/engine2.log" && break; sleep 1; done
grep -E 'HERMES_LOCAL_(READY|FAILED)' "$work/engine2.log"
curl -s -o /dev/null -c "$work/cookies" -H 'Content-Type: application/json' \
  -d "{\"provider\":\"basic\",\"username\":\"admin\",\"password\":\"$PW\"}" "$base/auth/password-login"

# MCP: a stdio server on Hermes' own python (mcp SDK = the "mcp" extra above) + the node runtime for npx servers
cat >"$work/mcp_probe.py" <<'PY'
import sys
sys.path.insert(0, "/opt/hermes/hermes-agent")
import hermes_bootstrap  # noqa: F401
from mcp.server.fastmcp import FastMCP
app = FastMCP("hlprobe")
@app.tool()
def echo(text: str) -> str:
    """Echo text back."""
    return text
app.run()
PY
cp "$work/mcp_probe.py" "$M/tmp/mcp_probe.py"
c=$(api POST /api/mcp/servers '{"name":"hlprobe","command":"/opt/hermes-local/bin/hermes-py","args":["-I","/tmp/mcp_probe.py"]}')
c2=$(api POST /api/mcp/servers/hlprobe/test)
grep -q echo "$work/f.json" && feat mcp-stdio works "add $c, test $c2: $(body 160)" || feat mcp-stdio fail "add $c, test $c2: $(body 200)"
node=$(run /bin/bash -lc 'ls -d /opt/hermes/tools/node-*/bin 2>/dev/null | head -n1')
feat node-runtime info "node $(run /bin/bash -lc "$node/node --version" 2>/dev/null), npm dir: $(run /bin/bash -lc 'ls -d /opt/hermes/tools/npm-*/bin 2>/dev/null')"

# Files: managed upload + listing
c=$(api POST /api/files/upload "{\"path\":\"/root/hl-probe.txt\",\"data_url\":\"data:text/plain;base64,$(printf 'hello from hermes local' | base64)\"}")
c2=$(api GET "/api/files?path=/root"); grep -q hl-probe.txt "$work/f.json" && feat files-upload works "upload $c, listed" || feat files-upload fail "upload $c list $c2"

# Voice: config, TTS (/api/audio/speak), STT (/api/audio/transcribe with a generated wav)
api GET /api/audio/voice-config >/dev/null; feat voice-config info "$(body 300)"
c=$(api POST /api/audio/speak '{"text":"Hello from Hermes Local."}')
grep -q '"data:audio' "$work/f.json" && feat tts works "/api/audio/speak $c audio data url" || feat tts fail "$c $(body 200)"
ff=$(run /bin/bash -lc 'ls -d /opt/hermes/tools/ffmpeg-*/bin/ 2>/dev/null | head -n1')
run /bin/bash -lc "${ff}ffmpeg -loglevel error -y -f lavfi -i sine=frequency=440:duration=1 -ar 16000 -ac 1 /tmp/probe.wav" 2>/dev/null
if [ -s "$M/tmp/probe.wav" ]; then
  c=$(api POST /api/audio/transcribe "{\"data_url\":\"data:audio/wav;base64,$(base64 -w0 "$M/tmp/probe.wav")\",\"mime_type\":\"audio/wav\"}")
  feat stt "$([ "$c" = 200 ] && echo works || echo fail)" "/api/audio/transcribe $c $(body 200)"
else feat stt fail "no ffmpeg to make a test wav"; fi

# Cron: no-agent script job, triggered now and fired by the gateway scheduler
mkdir -p "$M/root/.hermes/scripts"; printf '#!/bin/bash\necho HL_CRON_OK $(date +%%s)\n' >"$M/root/.hermes/scripts/hl_probe.sh"; chmod +x "$M/root/.hermes/scripts/hl_probe.sh"
c=$(api POST /api/cron/jobs '{"name":"hl-probe","schedule":"every 1m","no_agent":true,"script":"hl_probe.sh","deliver":"local"}')
jid=$(jq -r '.id // .job.id // empty' "$work/f.json" 2>/dev/null)
if [ -n "$jid" ]; then
  api POST "/api/cron/jobs/$jid/trigger" >/dev/null
  for _ in $(seq 1 50); do api GET "/api/cron/jobs/$jid/runs" >/dev/null; [ "$(jq -r '(.runs // .) | length' "$work/f.json" 2>/dev/null)" -ge 1 ] 2>/dev/null && break; sleep 3; done
  r=$(jq -c '(.runs // .)[0]' "$work/f.json" 2>/dev/null | cut -c1-300)
  [ -n "$r" ] && [ "$r" != null ] && feat cron works "job $jid run: $r" || feat cron fail "job $jid no run in 150 s: $(body 200)"
else feat cron fail "create $c $(body 200)"; fi

# Tools: terminal backends (local works; docker/modal/ssh need the outside), browser
api GET /api/tools/terminal/backends >/dev/null; feat terminal-backends info "$(jq -c '[.backends[]? // .[]? | {name, status}]' "$work/f.json" 2>/dev/null | cut -c1-300)"
api GET /api/tools/toolsets >/dev/null; feat toolsets info "$(jq -c '[.[]? | select(.name=="browser" or .name=="terminal" or .name=="code_execution" or .name=="vision" or .name=="image_gen") | {name, enabled, available: (.available // .configured // null)}]' "$work/f.json" 2>/dev/null | cut -c1-300)"
feat chromium info "$(run /bin/bash -lc 'command -v chromium chromium-browser google-chrome 2>/dev/null; ls /root/.cache/ms-playwright 2>/dev/null' | head -n2 | tr '\n' ' ' || true)none found"

# Memory
api GET /api/memory >/dev/null; feat memory info "$(jq -c '{active, providers: [.providers[]?.name]}' "$work/f.json" 2>/dev/null | cut -c1-250)"

# OpenAI-compatible API server (gateway, HL_API_SERVER=1)
key=$(cat "$M/root/.hermes-local/api-key" 2>/dev/null)
c=$(curl -s -o "$work/f.json" -w '%{http_code}' --max-time 60 -H "Authorization: Bearer $key" http://127.0.0.1:8642/v1/models)
c2=$(curl -s -o "$work/f2.json" -w '%{http_code}' --max-time 180 -H "Authorization: Bearer $key" -H 'Content-Type: application/json' \
  -d '{"model":"hermes-agent","messages":[{"role":"user","content":"hi"}]}' http://127.0.0.1:8642/v1/chat/completions)
grep -q 'stub model' "$work/f2.json" && feat api-server works "/v1/models $c, /v1/chat/completions $c2 (stub reply)" || feat api-server fail "/v1/models $c, chat $c2 $(head -c 200 "$work/f2.json")"
echo "== feature probe done"
