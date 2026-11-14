#!/bin/bash
# Empryo PoC on the API 36 x86_64 emulator, inside the Hermes Local app (CI build of the empryo-poc
# branch: CI hooks + libempryo.so / libemp_*.so glibc in nativeLibraryDir).
#   emulator.sh <apk> <out dir> <empryo tarball>
# A) guest: Empryo installed in the Hermes rootfs and run through the app's own proot (ci_guest):
#    --version, doctor, headless turn (stub model on the CI host -> shell tool), phone-service + idle
#    cost of every app process (proot tracer included), bridge reached from the CI host via adb forward.
# B) host: the app process itself, no proot (ci_host: untrusted_app + app seccomp): exec from app
#    data (W^X), ld.so --library-path, patchelf'd PT_INTERP, and "proot-lite" (proot -r / as a seccomp
#    shim, no rootfs) with the same checks.
set -uo pipefail
APK=$1; OUT=$(mkdir -p "$2" && cd "$2" && pwd); TB=$(realpath "$3"); TBN=$(basename "$TB")
H=$(cd "$(dirname "$0")" && pwd)
PKG=dev.syed.hermeslocal
CHK=$OUT/checks.txt; MET=$OUT/metrics.txt; : >"$CHK"; : >"$MET"
check() { local l; if [ "$2" = 1 ]; then l="CHECK ok   $1"; else l="CHECK FAIL $1"; fi; echo "$l" | tee -a "$CHK"; }
note() { echo "$*" | tee -a "$MET"; }
adb wait-for-device; adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
note "device: $(adb shell getprop ro.build.version.release | tr -d '\r') sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r') abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r') kernel=$(adb shell uname -r | tr -d '\r') selinux=$(adb shell getenforce | tr -d '\r')"
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
adb logcat -G 16M >/dev/null 2>&1; adb logcat -c; adb logcat -v time >"$OUT/logcat.txt" 2>&1 & LOGCAT=$!
ci_log() { adb logcat -d -s HermesLocalCI:I 2>/dev/null; }
appsh() { adb shell "cd /data/data/$PKG && $1"; }
# run_hook <ci_guest|ci_host> '<script>' [timeout s]  -> prints the script's output (markers stripped)
hook_start() { local id=$1 kind=$2 b; b=$(printf '%s\n' "$3" | base64 -w0)
  if [ "$kind" = ci_guest ]; then
    adb shell "am start -n $PKG/.MainActivity -e ci_guest 'echo BEGIN_$id; echo $b | base64 -d >/tmp/$id.sh; bash /tmp/$id.sh 2>&1; echo END_$id'" >/dev/null
  else
    adb shell "am start -n $PKG/.MainActivity -e ci_host 'echo BEGIN_$id; echo $b | base64 -d >\$CACHE/$id.sh; sh \$CACHE/$id.sh 2>&1; echo END_$id'" >/dev/null
  fi; }
hook_wait() { local id=$1 t=${2:-600}; for _ in $(seq 1 "$t"); do ci_log | grep -q "END_$id" && break; sleep 1; done
  ci_log | sed -n "/BEGIN_$id/,/END_$id/p" | sed -E 's/^.*HermesLocalCI[^:]*: (guest|host): //' | grep -vE "^(BEGIN|END)_"; }
run_hook() { local id="h$RANDOM$RANDOM"; hook_start "$id" "$1" "$2"; hook_wait "$id" "${3:-600}"; }
val() { grep -m1 -o "$1=[^ ]*" | cut -d= -f2; }   # val KEY < text

# ---- first run: engine install (same wait as the Hermes e2e)
t0=$(date +%s); adb shell am start -n $PKG/.MainActivity >/dev/null
for _ in $(seq 1 240); do
  appsh "ls files/r/opt/hermes-local/bin/hermes-local-start" >/dev/null 2>&1 && ! appsh "ls files/r.partial" >/dev/null 2>&1 && break; sleep 5; done
note "hermes engine installed after $(( $(date +%s) - t0 ))s"
APPUID=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r'); note "app uid=$APPUID"
adb push "$H/sample.sh" /data/local/tmp/sample.sh >/dev/null; adb shell chmod 755 /data/local/tmp/sample.sh
snap() { adb shell sh /data/local/tmp/sample.sh "$APPUID" >"$1"; }

# ---- CI host servers: tarball + stub model (the emulator reaches the host as 10.0.2.2)
(cd "$(dirname "$TB")" && python3 -m http.server 18998 --bind 0.0.0.0 >"$OUT/http.log" 2>&1) & HTTP=$!
python3 "$H/stub_openai.py" 18999 "$OUT/stub.log" 0.0.0.0 & STUB=$!
sleep 2
CONF='{"nerdFont":true,"remotePort":18787,"providers":[{"id":"stub","name":"Stub","baseURL":"http://10.0.2.2:18999/v1","models":["stub-1"]}]}'
ENVX='export TERM=dumb NO_COLOR=1 EMPRYO_NO_TTY=1 EMPRYO_NO_PROMPT=1 DO_NOT_TRACK=1 EMPRYO_REQUEST_COMPRESSION=off'

# ======================= A) guest: proot + the Hermes glibc rootfs =======================
G1=$(cat <<EOF
echo "ctx=\$(cat /proc/self/attr/current 2>&1 | tr -d '\0') \$(grep Seccomp: /proc/self/status | tr -d '\t')"
echo "os=\$(. /etc/os-release; echo \$VERSION_ID) glibc=\$(ldd --version | head -1 | awk '{print \$NF}')"
mkdir -p /root/emp && cd /root/emp && rm -rf empryo-*
curl -fsS -o $TBN http://10.0.2.2:18998/$TBN || /opt/hermes-local/bin/hermes-py -c "import urllib.request as u; u.urlretrieve('http://10.0.2.2:18998/$TBN', '$TBN')"; echo "download rc=\$? sha=\$(sha256sum $TBN | cut -c1-16)"
t=\$(date +%s%N); tar xzf $TBN; echo "untar_ms=\$(( (\$(date +%s%N)-t)/1000000 ))"
D=\$(ls -d /root/emp/empryo-*/ | head -1)
$ENVX; export HOME=/root/eh
t=\$(date +%s%N); bash \$D/install.sh --quiet >/root/emp/install.log 2>&1; echo "INSTALL rc=\$? ms=\$(( (\$(date +%s%N)-t)/1000000 ))"
echo '$CONF' >\$HOME/.empryo/config.json
mkdir -p /root/proj && (cd /root/proj && git init -q 2>/dev/null)
E=\$HOME/.empryo/bin/empryo
t=\$(date +%s%N); \$E --version; echo "VERSION rc=\$? ms=\$(( (\$(date +%s%N)-t)/1000000 ))"
t=\$(date +%s%N); \$E --version >/dev/null; echo "VERSION2 rc=\$? ms=\$(( (\$(date +%s%N)-t)/1000000 ))"
\$E doctor 2>&1 | grep -E 'Doctor|✓|✗' ; echo "DOCTOR rc=\${PIPESTATUS[0]}"
t=\$(date +%s%N); \$E --headless --no-genome --yolo --model stub/stub-1 --events --timeout 90000 --cwd /root/proj "run uname" >/root/emp/turn.jsonl 2>/root/emp/turn.err
echo "TURN rc=\$? ms=\$(( (\$(date +%s%N)-t)/1000000 ))"
grep -o '"type":"tool-result","tool":"shell"[^}]*' /root/emp/turn.jsonl | head -1; echo "TURN_DONE=\$(grep -c '"type":"done"' /root/emp/turn.jsonl)"
tail -3 /root/emp/turn.err
echo "HOME_BYTES=\$(du -sb \$HOME/.empryo | cut -f1)"
# data-only copy for the no-proot runs (B): everything but the binaries
rm -rf /root/ed && mkdir -p /root/ed/.empryo && cp -a \$HOME/.empryo/wasm \$HOME/.empryo/opentui-assets \$HOME/.empryo/native \$HOME/.empryo/init.lua \$HOME/.empryo/config.json /root/ed/.empryo/ 2>&1
echo G1_DONE
EOF
)
echo "== A1 guest setup + runs"; run_hook ci_guest "$G1" 600 | tee "$OUT/guest-runs.txt"
GR=$OUT/guest-runs.txt
check "guest: Empryo installed in the Hermes rootfs through proot (INSTALL rc=$(val rc <<<"$(grep INSTALL "$GR")"))" "$(grep -q 'INSTALL rc=0' "$GR" && echo 1 || echo 0)"
check "guest: --version ($(grep -m1 '^empryo ' "$GR"))" "$(grep -q 'VERSION rc=0' "$GR" && echo 1 || echo 0)"
check "guest: doctor ($(grep -m1 'Doctor' "$GR"))" "$(grep -q 'DOCTOR rc=0' "$GR" && echo 1 || echo 0)"
check "guest: headless turn, shell tool ran ($(grep -m1 -o 'EMPRYO_SHELL_OK[^\\]*' "$GR"))" "$(grep -q 'TURN rc=0' "$GR" && grep -q EMPRYO_SHELL_OK "$GR" && echo 1 || echo 0)"
note "guest version_ms_cold=$(grep '^VERSION ' "$GR" | val ms) version_ms_warm=$(grep '^VERSION2 ' "$GR" | val ms) turn_ms=$(grep '^TURN ' "$GR" | val ms) install_ms=$(grep INSTALL "$GR" | val ms) home_bytes=$(grep HOME_BYTES "$GR" | cut -d= -f2) $(grep -m1 '^ctx=' "$GR") $(grep -m1 '^os=' "$GR")"

G2=$(cat <<EOF
$ENVX; export HOME=/root/eh; E=\$HOME/.empryo/bin/empryo
setsid \$E phone-service --cwd /root/proj --port 18787 --login-item >/root/emp/ps.log 2>&1 </dev/null & SP=\$!
t=\$(date +%s%N); up=0; for i in \$(seq 1 300); do (echo >/dev/tcp/127.0.0.1/18787) 2>/dev/null && { up=1; break; }; sleep 0.1; done
echo "PS_PORT up=\$up ms=\$(( (\$(date +%s%N)-t)/1000000 )) pid=\$SP"
if [ \$up = 1 ]; then sleep 100; else sleep 5; fi
echo "BRIDGE_IN_GUEST=\$(curl -sk -m 5 https://127.0.0.1:18787/)"
echo "alive=\$(kill -0 \$SP 2>/dev/null && echo yes || echo no)"; ps -eo pid,stat,rss,args 2>/dev/null | grep -i empryo | grep -v grep | cut -c1-150
echo "-- ps.log"; tail -20 /root/emp/ps.log
echo "-- mobile/"; ls -la \$HOME/.empryo/mobile 2>&1 | head; tail -20 \$HOME/.empryo/mobile/service.log 2>&1
echo "-- logs/"; ls -t \$HOME/.empryo/logs 2>&1 | head; for f in \$(ls -t \$HOME/.empryo/logs/* 2>/dev/null | head -3); do echo "== \$f"; tail -25 \$f | cut -c1-300; done
echo "-- netlink probe (getifaddrs: RTM_GETLINK is refused to apps since Android 11)"
/opt/hermes-local/bin/hermes-py -c "import socket; print('if_nameindex', socket.if_nameindex())" 2>&1 | tail -1
ip -o addr 2>&1 | head -5
kill -TERM -- -\$SP 2>/dev/null; sleep 1; kill -KILL -- -\$SP 2>/dev/null; echo G2_DONE
EOF
)
idle_window() { # idle_window <label> <id of the running hook>: sample all app processes 60 s, bridge via adb forward
  local l=$1 id=$2
  for _ in $(seq 1 90); do ci_log | sed -n "/BEGIN_$id/,\$p" | grep -q "PS_PORT" && break; sleep 1; done
  sleep 15
  adb forward tcp:28787 tcp:18787 >/dev/null
  local b; b=$(curl -sk -m 5 https://127.0.0.1:28787/); note "$l bridge via adb forward from the CI host: '$b'"
  check "$l: bridge reachable from outside the app (adb forward) ('$b')" "$(grep -q 'Empryo bridge' <<<"$b" && echo 1 || echo 0)"
  snap "$OUT/$l-snap0.txt"; sleep 60; snap "$OUT/$l-snap1.txt"
  python3 "$H/apptree.py" "$l" "$OUT/$l-snap0.txt" "$OUT/$l-snap1.txt" 60 | tee "$OUT/$l-idle.txt" | grep METRIC >>"$MET"; cat "$OUT/$l-idle.txt"
  adb forward --remove tcp:28787 >/dev/null 2>&1
}
echo "== A2 guest phone-service"; id="h$RANDOM$RANDOM"; hook_start "$id" ci_guest "$G2"
idle_window guest-proot "$id"
hook_wait "$id" 300 | tee "$OUT/guest-phone.txt"
check "guest: phone-service listening ($(grep -m1 PS_PORT "$OUT/guest-phone.txt"))" "$(grep -q 'PS_PORT up=1' "$OUT/guest-phone.txt" && echo 1 || echo 0)"
note "guest phone_service_port_open_ms=$(grep PS_PORT "$OUT/guest-phone.txt" | val ms)"

# ======================= B) host: the app process, no proot =======================
H1=$(cat <<'EOF'
echo "ctx=$(cat /proc/self/attr/current | tr -d '\0') $(grep Seccomp: /proc/self/status | tr -d '\t') uid=$(id -u)"
echo "bin_sh=$(ls -l /bin/sh 2>&1)"
EG=$FILES/eg; mkdir -p $EG $CACHE/etmp $CACHE/proot
ln -sf $NLD/libemp_ld.so $EG/ld-linux-x86-64.so.2
for p in c:libc.so.6 m:libm.so.6 dl:libdl.so.2 pthread:libpthread.so.0 rt:librt.so.1 gcc_s:libgcc_s.so.1; do ln -sf $NLD/libemp_${p%%:*}.so $EG/${p#*:}; done
ls -l $NLD | grep -E 'libemp|libproot' | sed 's/^/  /'
export HOME=$FILES/r/root/ed TMPDIR=$CACHE/etmp TERM=dumb NO_COLOR=1 EMPRYO_NO_TTY=1 EMPRYO_NO_PROMPT=1 DO_NOT_TRACK=1 EMPRYO_REQUEST_COMPRESSION=off
ms() { echo $(( ($(date +%s%N) - $1) / 1000000 )); }
echo "-- W^X: exec Empryo straight from app data"
$FILES/r/root/eh/.empryo/bin/empryo --version; echo "WX_DATA_EXEC rc=$?"
echo "-- ld.so (nativeLibraryDir) --library-path, Empryo from nativeLibraryDir"
$NLD/libemp_ld.so --library-path $EG $NLD/libempryo.so --version; echo "LDSO rc=$?"
echo "-- ld.so (nativeLibraryDir) mapping the official, unpatched binary from app data"
$NLD/libemp_ld.so --library-path $EG $FILES/r/root/eh/.empryo/bin/empryo --version; echo "LDSO_DATA rc=$?"
echo "-- patchelf'd PT_INTERP"
$NLD/libempryo.so --version; echo "PATCHELF rc=$?"
echo "-- proot-lite: proot -r / as a seccomp/exec shim, no rootfs"
export PROOT_LOADER=$NLD/libproot-loader.so PROOT_TMP_DIR=$CACHE/proot
# no `env` here: nativeLibraryDir is /data/app/~~xx==/pkg-yy==/lib/x86_64 and env takes "a==b" for NAME=VALUE
PL() { LD_LIBRARY_PATH=$NLD $NLD/libproot.so --kill-on-exit -b /dev -b /proc -b /sys "$@"; }
t=$(date +%s%N); PL $NLD/libempryo.so --version; echo "PL_VERSION rc=$? ms=$(ms $t)"
t=$(date +%s%N); PL $NLD/libempryo.so --version >/dev/null; echo "PL_VERSION2 rc=$? ms=$(ms $t)"
PL $NLD/libempryo.so doctor 2>&1 | grep -E 'Doctor|✓|✗|Failed|missing'; echo "PL_DOCTOR done"
echo "-- proot-lite, official unpatched binary from app data, glibc bound to its FHS paths"
t=$(date +%s%N); PL -b $EG/ld-linux-x86-64.so.2:/lib64/ld-linux-x86-64.so.2 -b $EG:/lib/x86_64-linux-gnu $FILES/r/root/eh/.empryo/bin/empryo --version; echo "PL_DATA rc=$? ms=$(ms $t)"
mkdir -p $FILES/r/root/proj
t=$(date +%s%N); PL $NLD/libempryo.so --headless --no-genome --yolo --model stub/stub-1 --events --timeout 90000 --cwd $FILES/r/root/proj "run uname" >$CACHE/pl-turn.jsonl 2>$CACHE/pl-turn.err
echo "PL_TURN rc=$? ms=$(ms $t)"
grep -o '"type":"tool-result","tool":"shell"[^}]*' $CACHE/pl-turn.jsonl | head -1; echo "PL_TURN_DONE=$(grep -c '"type":"done"' $CACHE/pl-turn.jsonl)"; tail -3 $CACHE/pl-turn.err
echo H1_DONE
EOF
)
echo "== B1 host (no proot) tests"; run_hook ci_host "$H1" 600 | tee "$OUT/host-runs.txt"
HR=$OUT/host-runs.txt
note "host $(grep -m1 '^ctx=' "$HR") $(grep -m1 '^bin_sh=' "$HR")"
note "host WX_DATA_EXEC rc=$(grep WX_DATA_EXEC "$HR" | val rc) ($(grep -B2 WX_DATA_EXEC "$HR" | head -2 | tr '\n' ' '))"
note "host LDSO rc=$(grep '^LDSO ' "$HR" | val rc) LDSO_DATA rc=$(grep '^LDSO_DATA' "$HR" | val rc) PATCHELF rc=$(grep '^PATCHELF' "$HR" | val rc) PL_DATA rc=$(grep '^PL_DATA' "$HR" | val rc) (159 = SIGSYS, 139 = SIGSEGV, 126 = EACCES)"
check "host: exec from app data is refused (W^X), rc=$(grep WX_DATA_EXEC "$HR" | val rc)" "$(grep -q 'WX_DATA_EXEC rc=0' "$HR" && echo 0 || echo 1)"
check "host: proot-lite --version ($(grep -m1 '^empryo ' "$HR"))" "$(grep -q 'PL_VERSION rc=0' "$HR" && echo 1 || echo 0)"
check "host: proot-lite doctor ($(grep -m1 'Doctor' "$HR"))" "$(grep -q 'Doctor: OK' "$HR" && echo 1 || echo 0)"
check "host: proot-lite headless turn + shell tool ($(grep -m1 -o 'EMPRYO_SHELL_OK[^\\]*' "$HR"))" "$(grep -q 'PL_TURN rc=0' "$HR" && grep -q EMPRYO_SHELL_OK "$HR" && echo 1 || echo 0)"
note "host proot-lite version_ms_cold=$(grep '^PL_VERSION ' "$HR" | val ms) version_ms_warm=$(grep '^PL_VERSION2 ' "$HR" | val ms) turn_ms=$(grep '^PL_TURN ' "$HR" | val ms)"

if grep -q 'PL_VERSION rc=0' "$HR"; then
H2=$(cat <<'EOF'
export HOME=$FILES/r/root/ed TMPDIR=$CACHE/etmp TERM=dumb NO_COLOR=1 EMPRYO_NO_TTY=1 EMPRYO_NO_PROMPT=1 DO_NOT_TRACK=1 EMPRYO_REQUEST_COMPRESSION=off
export PROOT_LOADER=$NLD/libproot-loader.so PROOT_TMP_DIR=$CACHE/proot
ms() { echo $(( ($(date +%s%N) - $1) / 1000000 )); }
t=$(date +%s%N)
LD_LIBRARY_PATH=$NLD setsid $NLD/libproot.so --kill-on-exit -b /dev -b /proc -b /sys $NLD/libempryo.so phone-service --cwd $FILES/r/root/proj --port 18787 --login-item >$CACHE/pl-ps.log 2>&1 </dev/null & SP=$!
up=0; i=0; while [ $i -lt 300 ]; do toybox nc -z 127.0.0.1 18787 2>/dev/null && { up=1; break; }; sleep 0.1; i=$((i+1)); done
echo "PS_PORT up=$up ms=$(ms $t) pid=$SP"
if [ $up = 1 ]; then sleep 100; else sleep 5; fi
tail -20 $CACHE/pl-ps.log; tail -20 $HOME/.empryo/mobile/service.log 2>&1
kill $SP 2>/dev/null; pkill -f 'libempryo.so phone-service' 2>/dev/null; sleep 1; pkill -9 -f 'libempryo.so' 2>/dev/null; kill -9 $SP 2>/dev/null; echo H2_DONE
EOF
)
  echo "== B2 proot-lite phone-service"; id="h$RANDOM$RANDOM"; hook_start "$id" ci_host "$H2"
  idle_window host-proot-lite "$id"
  hook_wait "$id" 300 | tee "$OUT/host-phone.txt"
  check "host: proot-lite phone-service listening ($(grep -m1 PS_PORT "$OUT/host-phone.txt"))" "$(grep -q 'PS_PORT up=1' "$OUT/host-phone.txt" && echo 1 || echo 0)"
fi

ci_log >"$OUT/ci-log.txt"
appsh "du -sb files/r/root/eh/.empryo files/r/root/ed/.empryo" >>"$MET" 2>/dev/null
adb shell dmesg 2>/dev/null | grep -iE 'avc: denied.*(execute|execmod)|seccomp|SIGSYS' | tail -20 >"$OUT/dmesg-avc.txt" || true
adb logcat -d -b events,main 2>/dev/null | grep -iE 'avc: +denied' | grep -i "$PKG\|untrusted_app" | tail -20 >"$OUT/logcat-avc.txt" || true
kill $LOGCAT $HTTP $STUB 2>/dev/null
echo "== summary"; cat "$CHK"; echo; cat "$MET"
exit 0
