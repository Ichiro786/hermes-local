#!/bin/bash
# Starts the packed engine once (rootfs as read-only overlay lower layer) and checks that the Hermes
# dashboard answers on 127.0.0.1:9119 behind the password gate.
#   smoke-test.sh <rootfs dir> chroot|proot
# Exit 0 when the dashboard answered and password login worked, Hermes never tried to update
# itself, the main dashboard endpoints the Hermes apps use answer, a plugin installs from inside the
# engine, and a chat turn streams through /api/ws once a (stub) Codex sign-in exists.
# Env: STUB_LLM=<path to scripts/stub-llm.py> (default: next to this repo), CAPTURE_DIR=<dir> for the
# raw sign-in captures (codex device code, agy URL), SMOKE_NET_CHECKS=0 to skip the live sign-in captures,
# FEATURES=1 for the feature probe (engine/feature-probe.sh; informational, never fails the smoke).
set -uo pipefail
ROOTFS=$(realpath "${1:?rootfs dir}")
MODE=${2:-chroot}
TIMEOUT=${SMOKE_TIMEOUT:-300}
PORT=9119
PW=smoke-test-pass-123
[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 2; }

work=$(mktemp -d /tmp/hl-smoke.XXXXXX)
mkdir -p "$work/upper" "$work/work" "$work/merged"
mount -t overlay overlay -o "lowerdir=$ROOTFS,upperdir=$work/upper,workdir=$work/work" "$work/merged"
M="$work/merged"
mounted=("$M")
cleanup() {
  [ -n "${runner:-}" ] && kill -TERM "$runner" 2>/dev/null
  [ -n "${stub:-}" ] && kill "$stub" 2>/dev/null
  sleep 3
  # Match only the engine processes: a bare "hermes" pattern also matches this script's own path.
  pkill -KILL -f "/opt/hermes" 2>/dev/null; pkill -KILL -f "hermes (dashboard|gateway)" 2>/dev/null
  local i
  for ((i = ${#mounted[@]} - 1; i >= 0; i--)); do umount -l "${mounted[$i]}" 2>/dev/null; done
  rm -rf "$work"
}
trap cleanup EXIT
cp -L /etc/resolv.conf "$M/etc/resolv.conf"
STUB_LLM=${STUB_LLM:-$(dirname "$(realpath "$0")")/../scripts/stub-llm.py}
CAPTURE_DIR=${CAPTURE_DIR:-}
[ -n "$CAPTURE_DIR" ] && mkdir -p "$CAPTURE_DIR"
STUB_PORT=18080
python3 "$STUB_LLM" $STUB_PORT >"$work/stub.log" 2>&1 &
stub=$!
# The stub sign-in below routes Codex to the stub (Hermes reads HERMES_CODEX_BASE_URL from ~/.hermes/.env).
mkdir -p "$M/root/.hermes"; echo "HERMES_CODEX_BASE_URL=http://127.0.0.1:$STUB_PORT/v1" >>"$M/root/.hermes/.env"
envs=(HOME=/root PATH=/opt/hermes-local/bin:/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 TERM=dumb)
log="$work/engine.log"

if [ "$MODE" = chroot ]; then
  for d in proc sys dev dev/pts; do mount --bind "/$d" "$M/$d"; mounted+=("$M/$d"); done
  run() { chroot "$M" /usr/bin/env -i "${envs[@]}" "$@"; }
elif [ "$MODE" = proot ]; then
  PROOT=${PROOT:-proot}
  extra=()
  help=$("$PROOT" --help 2>&1 || true)
  grep -q -- '--kill-on-exit' <<<"$help" && extra+=(--kill-on-exit)
  grep -q -- '--link2symlink' <<<"$help" && extra+=(--link2symlink)
  # Same as the app: link2symlink keeps its backing files in one directory, so renaming a directory
  # that holds "hard links" (uv moves freshly unpacked wheels into its cache) does not break them.
  export PROOT_L2S_DIR="$M/.l2s"; mkdir -p "$PROOT_L2S_DIR"
  run() { "$PROOT" "${extra[@]}" -0 -r "$M" -w /root -b /dev -b /proc -b /sys -b "$M/tmp:/dev/shm" \
    /usr/bin/env -i "${envs[@]}" "$@"; }
else
  echo "mode must be chroot or proot" >&2; exit 2
fi

# FEATURES=1: also run the feature probe (engine/feature-probe.sh), with the gateway's API server on.
if [ "${FEATURES:-0}" = 1 ]; then mkdir -p "$M/root/.hermes-local"; echo HL_API_SERVER=1 >"$M/root/.hermes-local/settings.env"; fi

echo "== set password"
echo "$PW" | run /bin/bash /opt/hermes-local/bin/hermes-local-passwd admin || { echo "SMOKE ($MODE): passwd failed"; exit 1; }
run /bin/bash /opt/hermes-local/bin/hermes-local-passwd --check && echo "password configured"

echo "== start"
run /bin/bash /opt/hermes-local/bin/hermes-local-start >"$log" 2>&1 &
runner=$!
rc=1
for _ in $(seq 1 "$TIMEOUT"); do
  grep -q 'HERMES_LOCAL_READY' "$log" && { rc=0; break; }
  grep -q 'HERMES_LOCAL_FAILED' "$log" && break
  kill -0 "$runner" 2>/dev/null || break
  sleep 1
done
echo "----- engine log ($MODE) -----"; tail -n 80 "$log"
if [ "$rc" = 0 ]; then
  base="http://127.0.0.1:$PORT"
  echo "== GET / -> $(curl -s -o /dev/null -w '%{http_code}' "$base/")"
  providers=$(curl -s "$base/api/auth/providers"); echo "== providers: $providers"
  echo "== public /api/status -> $(curl -s -o /dev/null -w '%{http_code}' "$base/api/status") (liveness probe, public by design)"
  unauth=$(curl -s -o /dev/null -w '%{http_code}' "$base/api/config")
  echo "== unauthenticated /api/config -> $unauth (401 = password gate on)"
  pname=$(jq -r '[.providers[]? // .[]? | select((.supports_password // .password // false) == true) | .name] | first // "basic"' <<<"$providers" 2>/dev/null || echo basic)
  login=$(curl -s -o "$work/login.json" -w '%{http_code}' -c "$work/cookies" -H 'Content-Type: application/json' \
    -d "{\"provider\":\"$pname\",\"username\":\"admin\",\"password\":\"$PW\"}" "$base/auth/password-login")
  echo "== password login ($pname) -> $login $(cat "$work/login.json" 2>/dev/null | head -c 300)"
  authed=$(curl -s -o /dev/null -w '%{http_code}' -b "$work/cookies" "$base/api/config")
  echo "== authenticated /api/config -> $authed"
  bad=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "{\"provider\":\"$pname\",\"username\":\"admin\",\"password\":\"wrong-password\"}" "$base/auth/password-login")
  echo "== wrong password -> $bad"
  [ "$login" = 200 ] && [ "$unauth" = 401 ] && [ "$authed" = 200 ] || rc=1
  [ "$unauth" = 401 ] || echo "::warning::password gate not engaged on loopback ($unauth)"
  check() { # check <name> <ok?>: records a failed check
    if [ "$2" = 1 ]; then echo "CHECK ok   $1"; else echo "CHECK FAIL $1"; rc=1; fi; }
  note() { echo "CHECK note $1"; }

  echo "== self-update never runs"
  run /bin/bash -c '/opt/hermes-local/bin/hermes-py -I /opt/hermes-local/diag-update.py'
  check "no source-update message in engine log" "$(grep -qiE 'source-update|update is still running' "$log" && echo 0 || echo 1)"

  echo "== dashboard endpoints the Hermes apps use"
  for ep in /api/status /api/sessions /api/skills /api/dashboard/plugins /api/tools/toolsets /api/mcp/servers /api/cron/jobs \
            /api/memory /api/model/info /api/model/options /api/files /api/profiles /api/config /api/analytics/usage \
            /api/audio/voice-config /api/skills/hub/official /api/webhooks; do
    code=$(curl -s -o "$work/ep.json" -w '%{http_code}' --max-time 90 -b "$work/cookies" "$base$ep")
    echo "   $ep -> $code $(head -c 100 "$work/ep.json" | tr '\n' ' ')"
    check "GET $ep" "$([ "$code" = 200 ] && echo 1 || echo 0)"
  done
  echo "== plugin install from inside the engine (catalog git plugin, no deps)"
  run /bin/bash -lc 'timeout 300 hermes plugins install quota --enable --yes-deps </dev/null 2>&1 | tail -n 4'
  for _ in 1 2 3; do # rich wraps to 80 columns without a tty; COLUMNS keeps one row per plugin
    run /bin/bash -lc 'COLUMNS=200 timeout 120 hermes plugins list </dev/null 2>&1' >"$work/plugins.txt"
    grep -qE '^│ quota +│ enabled' "$work/plugins.txt" && break; sleep 10
  done
  grep -E '^│ (quota|langfuse|antigravity-oauth) ' "$work/plugins.txt" | cut -c1-60; grep -iE 'error|traceback' "$work/plugins.txt" | head -n 5
  check "plugin install quota (listed enabled)" "$(grep -qE '^│ quota +│ enabled' "$work/plugins.txt" && echo 1 || echo 0)"
  echo "== plugin with python deps (langfuse: pm/uv dependency sync inside the engine)"
  run /bin/bash -lc 'timeout 600 hermes plugins install langfuse --enable --yes-deps </dev/null 2>&1 | grep -v "^ *$" | tail -n 25 | cut -c1-300'
  run /bin/bash -lc 'COLUMNS=200 timeout 120 hermes plugins list </dev/null 2>&1' >"$work/plugins.txt"
  if grep -qE '^│ langfuse +│ enabled' "$work/plugins.txt"; then
    note "plugin with python deps (langfuse) installed + enabled"; else note "plugin with python deps (langfuse) NOT enabled: $(grep -E '^│ langfuse ' "$work/plugins.txt" | cut -c1-60)"; fi
  echo "== skills hub search"
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 90 -b "$work/cookies" "$base/api/skills/hub/search?q=git")
  note "GET /api/skills/hub/search -> $code"

  echo "== hermes-local-auth status"; run /bin/bash /opt/hermes-local/bin/hermes-local-auth status
  echo "== chat before any provider sign-in (expect: not connected)"
  echo "$PW" | run /bin/bash -c 'HL_CHAT_TIMEOUT=90 /opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_chat_check.py http://127.0.0.1:9119 admin Hi' \
    >"$work/chat-before.txt" 2>&1
  grep -E "CHAT_|MODEL_INFO" "$work/chat-before.txt" | cut -c1-300
  check "chat before sign-in reports no provider" "$(grep -q 'not connected to any AI provider' "$work/chat-before.txt" && echo 1 || echo 0)"
  echo "== stub Codex sign-in (fake token, Codex routed to the stub) + provider wiring"
  jwt=$(python3 -c 'import base64,json,time;b=lambda o: base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip("=");print(b({"alg":"none"})+"."+b({"exp":int(time.time())+86400,"https://api.openai.com/auth":{"chatgpt_account_id":"stub"}})+".sig")')
  run /bin/bash -lc "hermes auth add openai-codex --type api-key --api-key '$jwt' --label stub-account </dev/null 2>&1 | tail -n 2"
  ensure=$(run /bin/bash -lc '/opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_provider.py ensure openai-codex' 2>&1 | tail -n1)
  echo "   ensure -> $ensure"
  check "provider ensure sets openai-codex" "$(grep -q '"provider": "openai-codex"' <<<"$ensure" && echo 1 || echo 0)"
  echo "$PW" | run /bin/bash -c 'HL_CHAT_TIMEOUT=180 /opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_chat_check.py http://127.0.0.1:9119 admin Hi' \
    >"$work/chat-after.txt" 2>&1
  grep -E "CHAT_|MODEL_INFO|message.complete" "$work/chat-after.txt" | cut -c1-300
  check "streaming chat turn through /api/ws (stub Codex)" "$(grep -q '^CHAT_OK' "$work/chat-after.txt" && echo 1 || echo 0)"
  echo "== stub requests:"; tail -n 8 "$work/stub.log"
  check "no source-update message after sign-in + chat" "$(grep -qiE 'source-update|update is still running' "$log" && echo 0 || echo 1)"
  # shellcheck source=feature-probe.sh
  [ "${FEATURES:-0}" = 1 ] && . "$(dirname "$(realpath "$0")")/feature-probe.sh"

  if [ "${SMOKE_NET_CHECKS:-1}" = 1 ]; then
    echo "== live Codex device-code start (real auth.openai.com, cancelled after the code)"
    (sleep 40) | run /bin/bash -c 'timeout 30 /bin/bash /opt/hermes-local/bin/hermes-local-auth login codex' >"$work/codex.txt" 2>&1
    cat "$work/codex.txt"
    if grep -q '^HL_URL https://auth.openai.com/codex/device$' "$work/codex.txt" && grep -qE '^HL_CODE [A-Z0-9]{4,}-[A-Z0-9]{4,}$' "$work/codex.txt"; then
      note "codex device code shown (URL + code parsed)"; else note "codex device code NOT captured"; fi
    echo "== live agy sign-in start (real Google URL, no code entered)"
    (sleep 60) | run /bin/bash -c 'HL_AGY_CODE_TIMEOUT=20 timeout 45 /bin/bash /opt/hermes-local/bin/hermes-local-auth login antigravity' >"$work/agy.txt" 2>&1
    grep -vE '^HL_RAW' "$work/agy.txt" | cut -c1-200
    if grep -q '^HL_URL https://accounts.google.com/' "$work/agy.txt" && grep -q '^HL_PROMPT code' "$work/agy.txt"; then
      note "agy sign-in URL shown, waiting for the code"; else note "agy sign-in URL NOT captured"; fi
    [ -n "$CAPTURE_DIR" ] && cp "$work/codex.txt" "$CAPTURE_DIR/codex-$MODE.txt" && cp "$work/agy.txt" "$CAPTURE_DIR/agy-$MODE.txt"
  fi
  [ -n "$CAPTURE_DIR" ] && cp "$work/chat-before.txt" "$CAPTURE_DIR/chat-before-$MODE.txt" && cp "$work/chat-after.txt" "$CAPTURE_DIR/chat-after-$MODE.txt" \
    && cp "$log" "$CAPTURE_DIR/engine-$MODE.log"
  echo "----- engine log tail ($MODE) -----"; tail -n 30 "$log"
fi
echo "SMOKE TEST ($MODE): $([ $rc = 0 ] && echo READY || echo NOT READY)"
exit "$rc"
