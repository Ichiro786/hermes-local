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
# applicationId (run.hermes.empryo.test) differs from the namespace, so name the activity class in full.
am_app() { local o; o=$(adb shell "am start -n $PKG/run.hermes.empryo.MainActivity $*" 2>&1); case "$o" in *Error*|*rror:*) echo "am start failed: $o" | tee -a "$OUT/checks.txt";; esac; }

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

# ---- DNS (test1 P0: getaddrinfo EAI_AGAIN on Syed's phone). Make public DNS unreachable, like a network that
# only answers its own resolver, then: old rootfs resolv.conf (1.1.1.1/8.8.8.8) must fail (red), the app's
# resolv.conf (Android's network DNS first) must resolve and fetch over HTTPS from Bun inside the engine (green).
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
rules=0
for ip in 1.1.1.1 8.8.8.8; do for pr in udp tcp; do adb shell "iptables -I OUTPUT -d $ip -p $pr --dport 53 -j DROP" >/dev/null 2>&1 && rules=$((rules+1)); done; done
note "public DNS blocked with $rules/4 iptables rules"
djs=$(printf '%s' 'const t=Date.now();try{const r=await fetch("https://dl.empryo.com/latest.txt",{signal:AbortSignal.timeout(45000)});console.log(`DNS_FETCH_OK ${r.status} ${Date.now()-t} ms`)}catch(e){console.log(`DNS_FETCH_FAIL ${e.code||""} ${e.message} ${Date.now()-t} ms`)}' | base64 -w0)
gs=$(cat <<EOS | base64 -w0
echo $djs | base64 -d > /tmp/dnscheck.js
echo "== app resolv.conf"; cat /etc/resolv.conf; cp /etc/resolv.conf /tmp/resolv.app
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions single-request-reopen\n' > /etc/resolv.conf
echo "== OLD"; BUN_BE_BUN=1 /opt/empryo/empryo /tmp/dnscheck.js
cp /tmp/resolv.app /etc/resolv.conf
echo "== NEW"; BUN_BE_BUN=1 /opt/empryo/empryo /tmp/dnscheck.js
EOS
)
adb logcat -c 2>/dev/null; sleep 1
am_app -e ci_guest "'echo $gs | base64 -d | sh'"
wait_log "guest-exit" 200; ci_log | grep "guest:" | tee "$OUT/dns.txt"
check "dns: app resolv.conf lists the emulator network DNS (10.0.2.3) first" "$(grep -A2 'app resolv.conf' "$OUT/dns.txt" | grep -q 'nameserver 10.0.2.3' && echo 1 || echo 0)"
if [ "$rules" = 4 ]; then check "dns RED: old resolv.conf -> EAI_AGAIN (test1 symptom reproduced)" "$(grep -A1 '== OLD' "$OUT/dns.txt" | grep -q 'DNS_FETCH_FAIL.*EAI_AGAIN' && echo 1 || echo 0)"
else note "iptables unavailable: red step not meaningful"; fi
check "dns GREEN: app resolv.conf -> Bun HTTPS fetch OK inside the engine" "$(grep -A1 '== NEW' "$OUT/dns.txt" | grep -q 'DNS_FETCH_OK 200' && echo 1 || echo 0)"

# ---- phone-service control socket: listen + hard link + connect (what phone-service does), with and without link2symlink.
pjs=$(printf '%s' 'const net=require("net"),fs=require("fs");const a="/tmp/lp-a.sock",b="/tmp/lp-b.sock";const rm=()=>{for(const p of [a,b])try{fs.unlinkSync(p)}catch{}};rm();const s=net.createServer(c=>c.end("hi"));const done=()=>{s.close();rm();process.exit(0)};s.listen(a,()=>{try{fs.linkSync(a,b);console.log("LINK_OK")}catch(e){console.log("LINK_ERR",e.code,e.message)}try{const x=fs.statSync(a).ino,y=fs.statSync(b).ino;console.log("INO",x===y?"SAME":"DIFF",x,y,"sym",fs.lstatSync(a).isSymbolicLink(),fs.lstatSync(b).isSymbolicLink())}catch(e){console.log("STAT_ERR",e.code)}const c=net.connect(b);c.on("data",()=>{console.log("CONNECT_B_OK");done()});c.on("error",e=>{console.log("CONNECT_B_ERR",e.code);done()})});setTimeout(()=>{console.log("PROBE_TIMEOUT");process.exit(1)},10000)' | base64 -w0)
adb logcat -c 2>/dev/null; sleep 1
am_app -e ci_guest "'echo $pjs | base64 -d > /tmp/lp.js && BUN_BE_BUN=1 /opt/empryo/empryo /tmp/lp.js'"
wait_log "guest-exit" 60; ci_log | grep "guest:" | tee "$OUT/socklink.txt"
note "socket hard-link probe (proot link2symlink): $(grep -oE 'LINK_[A-Z]+.*|INO .*|STAT_ERR.*|CONNECT_B_[A-Z]+.*|PROBE_TIMEOUT' "$OUT/socklink.txt" | tr '\n' ' ')"
check "socket link (app): same inode + connect through the linked name" "$(grep -q 'INO SAME' "$OUT/socklink.txt" && grep -q CONNECT_B_OK "$OUT/socklink.txt" && echo 1 || echo 0)"

# ---- test3 root cause, A/B on this device with the same proot binary (adb root): filesDir is /data/user/0/...,
# a symlink to /data/data/...; proot resolves -r, so a link2symlink store named by the symlinked path (test2)
# makes faked hard links point outside the guest root (RED: ENOENT), the canonical name works (GREEN).
NLD=$(adb shell pm dump $PKG | grep -m1 'legacyNativeLibraryDir=' | sed 's/.*=//' | tr -d '\r')/x86_64
RU=/data/user/0/$PKG/files/r; RC=$(adb shell readlink -f $RU | tr -d '\r'); AU=$(adb shell stat -c %u $RC | tr -d '\r')
note "rootfs $RU -> $RC (uid $AU); nativeLibraryDir $NLD"
adb shell "echo $pjs | base64 -d > $RC/tmp/lp-root.js"
for v in "LEGACY=$RU/.l2s" "CANONICAL=$RC/.l2s"; do
  adb shell "cd $RC && PROOT_LOADER=$NLD/libproot-loader.so PROOT_TMP_DIR=/data/local/tmp PROOT_L2S_DIR=${v#*=} LD_LIBRARY_PATH=$NLD timeout 30 $NLD/libproot.so --kill-on-exit --link2symlink --sysvipc -0 -L -r $RU -w /root -b /dev -b /proc -b /sys /usr/bin/env -i HOME=/root TMPDIR=/tmp PATH=/opt/empryo:/usr/bin:/bin BUN_BE_BUN=1 /opt/empryo/empryo /tmp/lp-root.js" 2>&1 | sed "s/^/${v%%=*} /" | tee -a "$OUT/l2s-ab.txt"
done
adb shell "rm -f $RC/tmp/lp-root.js $RC/tmp/lp-a.sock $RC/tmp/lp-b.sock $RC/.l2s/.l2s.lp-*"
check "l2s RED: store under /data/user/0 (test2) -> linked socket ENOENT" "$(grep -q 'LEGACY CONNECT_B_ERR ENOENT' "$OUT/l2s-ab.txt" && echo 1 || echo 0)"
check "l2s GREEN: canonical store -> same inode + connect OK" "$(grep -q 'CANONICAL INO SAME' "$OUT/l2s-ab.txt" && grep -q 'CANONICAL CONNECT_B_OK' "$OUT/l2s-ab.txt" && echo 1 || echo 0)"

adb logcat -c 2>/dev/null; sleep 1
am_app --ez ci_diag true
if wait_log "diag-done" 420; then ok=1; else ok=0; fi
check "diagnostics finished" $ok
adb shell run-as $PKG sh -c "'cat files/logs/diagnostics-*.txt'" >"$OUT/diagnostics.txt" 2>&1
check "diag: --version rc=0" "$(grep -A1 '## empryo --version' "$OUT/diagnostics.txt" | grep -q 'rc=0' && echo 1 || echo 0)"
check "diag: doctor OK" "$(grep -qE 'Doctor: OK|checks passed' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
check "diag: bridge result recorded" "$(grep -qE 'BRIDGE (OK|EXITED|NOT LISTENING)' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
check "diag: phone-service bridge OK (port open, no reclaimed-stale)" "$(grep -q 'BRIDGE OK' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
note "bridge: $(grep -m1 -E 'BRIDGE ' "$OUT/diagnostics.txt")"
note "mkdir probe: $(grep -m1 -E 'MKDIR_OK|cannot create' "$OUT/diagnostics.txt")"
check "diag: idle sample of the acp tree" "$(grep -q 'acp tree over' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
note "$(grep -m1 'acp tree over' "$OUT/diagnostics.txt")"
note "$(grep -m1 'phone-service tree over' "$OUT/diagnostics.txt")"
check "diag: network section ran (Bun fetch inside the engine)" "$(grep -q 'NET_OK\|NET_FAIL' "$OUT/diagnostics.txt" && echo 1 || echo 0)"
check "diag: api.anthropic.com + chatgpt.com + dl.empryo.com reachable with public DNS blocked" "$(test "$(grep -c '^NET_OK' "$OUT/diagnostics.txt")" -ge 3 && echo 1 || echo 0)"
note "bridge attempts: $(grep -E 'BRIDGE (OK|EXITED|NOT)' "$OUT/diagnostics.txt" | tr '\n' ' ')"

adb logcat -c 2>/dev/null; sleep 1
am_app -e ci_prompt "'run uname'"
if wait_log "chat-done" 240; then ok=1; else ok=0; fi
ci_log | grep "chat" | tee "$OUT/chat.txt"
check "chat turn over ACP finished" $ok
check "chat: permission request seen and allowed" "$(grep -q 'chat: permission' "$OUT/chat.txt" && echo 1 || echo 0)"
check "chat: shell tool ran in proot" "$(grep -q 'EMPRYO_SHELL_OK' "$OUT/chat.txt" && echo 1 || echo 0)"
check "chat: streamed reply" "$(grep -q 'chat: agent STUB_REPLY_OK' "$OUT/chat.txt" && echo 1 || echo 0)"

# ---- chat: slash commands get the TUI-only hint; provider errors show Empryo's real text (not "Internal error")
adb logcat -c 2>/dev/null; sleep 1
am_app -e ci_prompt "'/remote'"; wait_log "chat-done" 60; ci_log | grep "chat" | tee "$OUT/chat-slash.txt"
check "chat: /remote intercepted with the TUI-only hint" "$(grep -q 'chat: note Slash commands like /remote' "$OUT/chat-slash.txt" && echo 1 || echo 0)"
adb logcat -c 2>/dev/null; sleep 1
am_app -e ci_prompt "'STUB_FAIL please'"; wait_log "chat-done" 180; ci_log | grep "chat" | tee "$OUT/chat-error.txt"
check "chat: provider error shows its details" "$(grep -q 'chat-done: error=Internal error: Provider request failed (HTTP 400).*STUB_PROVIDER_ERROR' "$OUT/chat-error.txt" && echo 1 || echo 0)"
am_app -e ci_screen chat; sleep 3; shot "11-chat-error"

# ---- pairing the official Empryo app, headless (phone-service + control-socket helper)
# Upgrade from test2: plant the dead control-socket link test2 leaves behind (it wedged every later phone-service);
# the app must drop it before starting the bridge.
EP=$(adb shell cat $RC/root/.empryo/mobile/service.json | grep -o '"endpoint":"[^"]*"' | cut -d'"' -f4)
if [ -n "$EP" ]; then
  adb shell "rm -f $RC$EP && ln -s $RU/.l2s/.l2s.empryo-p1-dead.sock0001 $RC$EP && chown -h $AU:$AU $RC$EP && ls -l $RC$EP" | tee -a "$OUT/checks.txt"
else note "no service.json endpoint: test2 leftover not planted"; fi
adb logcat -c 2>/dev/null; sleep 1
am_app --ez ci_pair true
if wait_log "pair-ready: (true|false)" 200; then :; fi
ci_log | grep -E "pair" | tail -40 | tee "$OUT/pair.txt"
check "pair: phone-service + helper gave a code, link and QR" "$(grep -q 'pair-ready: true link=empryo://pair?c=' "$OUT/pair.txt" && echo 1 || echo 0)"
check "pair: QR matrix received" "$(grep -q 'pair-ready: true.*qr=[1-9]' "$OUT/pair.txt" && echo 1 || echo 0)"
[ -n "$EP" ] && check "pair: test2 leftover socket link replaced" "$(adb shell readlink $RC$EP | grep -q "^$RU/" && echo 0 || echo 1)"
note "pair reaches: $(grep -m1 -o 'reaches=\[[^]]*\]' "$OUT/pair.txt" | tail -1)"
adb shell run-as $PKG sh -c "'grep -E \"bridge|pair\" files/logs/engine.log | tail -40'" >"$OUT/pair-engine.log" 2>&1
am_app -e ci_screen pair; sleep 4; shot "30-pair"
am_app -e ci_pair_request "'Syed iPhone'"; sleep 3; shot "31-pair-request"
adb shell input keyevent KEYCODE_BACK; sleep 1

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
