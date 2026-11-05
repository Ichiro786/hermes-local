#!/bin/bash
# Starts the packed engine once (rootfs as read-only overlay lower layer) and checks that the Hermes
# dashboard answers on 127.0.0.1:9119 behind the password gate.
#   smoke-test.sh <rootfs dir> chroot|proot
# Exit 0 when the dashboard answered and password login worked.
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
  sleep 3
  # Match only the engine processes: a bare "hermes" pattern also matches this script's own path.
  pkill -KILL -f "/opt/hermes" 2>/dev/null; pkill -KILL -f "hermes (dashboard|gateway)" 2>/dev/null
  local i
  for ((i = ${#mounted[@]} - 1; i >= 0; i--)); do umount -l "${mounted[$i]}" 2>/dev/null; done
  rm -rf "$work"
}
trap cleanup EXIT
cp -L /etc/resolv.conf "$M/etc/resolv.conf"
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
  run() { "$PROOT" "${extra[@]}" -0 -r "$M" -w /root -b /dev -b /proc -b /sys -b "$M/tmp:/dev/shm" \
    /usr/bin/env -i "${envs[@]}" "$@"; }
else
  echo "mode must be chroot or proot" >&2; exit 2
fi

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
  echo "== hermes-local-auth status"; run /bin/bash /opt/hermes-local/bin/hermes-local-auth status
fi
echo "SMOKE TEST ($MODE): $([ $rc = 0 ] && echo READY || echo NOT READY)"
exit "$rc"
