#!/bin/bash
# Empryo PoC on a Linux runner (native arm64 on ubuntu-24.04-arm): can Empryo run headless the way
# Hermes Local runs Hermes (proot + glibc rootfs)? Runs, for each mode, --version (timed), doctor,
# a headless agent turn against a local stub model (the stub calls Empryo's shell tool), and the
# phone bridge (`empryo phone-service`, what `empryo phone on` registers as a systemd unit) with idle
# RSS / CPU / context switches.
#
# Modes:
#   native      the release binary as shipped (host glibc)
#   loader      patchelf'd copy: PT_INTERP + RUNPATH -> a 7-file glibc dir (the "no proot" route)
#   proot       termux/proot (same build + flags as the Hermes Local app) on a minbase glibc rootfs
#   *-android   the same under an approximation of Android's app seccomp policy (sec-android),
#               which traps (SIGSYS) every syscall a zygote-spawned app may not make
#   tiny-*      proot on a tiny rootfs: glibc libs + busybox only
#
#   linux.sh <out dir>       env: EMPRYO_VERSION (default: dl.empryo.com/latest.txt), AOSP_DIR
set -uo pipefail
export LC_ALL=C.UTF-8
OUT=$(mkdir -p "${1:-out}" && cd "${1:-out}" && pwd)
H=$(cd "$(dirname "$0")" && pwd)
W=${WORK:-$RUNNER_TEMP/emp}; mkdir -p "$W"
CHK=$OUT/checks.txt; MET=$OUT/metrics.txt; : >"$CHK"; : >"$MET"
A=$(uname -m); EA=$([ "$A" = aarch64 ] && echo arm64 || echo x64); SA=$([ "$A" = aarch64 ] && echo arm64 || echo x86_64)
PORT=18787; STUB=18999
ok_all=1
check() { local l; if [ "$2" = 1 ]; then l="CHECK ok   $1"; else l="CHECK FAIL $1"; ok_all=0; fi; echo "$l" | tee -a "$CHK"; }
note() { echo "$*" | tee -a "$MET"; }
ms() { echo $(( ($(date +%s%N) - $1) / 1000000 )); }
sec() { echo "::group::$*"; }; endsec() { echo "::endgroup::"; }

# ---------------------------------------------------------------- 1. release inventory
sec "release"
V=${EMPRYO_VERSION:-$(curl -fsSL https://dl.empryo.com/latest.txt | tr -d '[:space:]')}
B=empryo-$V-linux-$EA
curl -fsSL -o "$W/$B.tar.gz" "https://dl.empryo.com/v$V/$B.tar.gz"
curl -fsSL -o "$W/SHA256SUMS.txt" "https://dl.empryo.com/v$V/SHA256SUMS.txt"
exp=$(grep "  $B.tar.gz\$" "$W/SHA256SUMS.txt" | cut -d' ' -f1); act=$(sha256sum "$W/$B.tar.gz" | cut -d' ' -f1)
check "sha256 $B.tar.gz matches SHA256SUMS ($act)" "$([ "$exp" = "$act" ] && echo 1 || echo 0)"
tar -C "$W" -xzf "$W/$B.tar.gz"; D=$W/$B
note "release=$V tarball=$B.tar.gz bytes=$(stat -c %s "$W/$B.tar.gz") unpacked_bytes=$(du -sb "$D" | cut -f1) binary_bytes=$(stat -c %s "$D/empryo")"
(cd "$D" && find . -type f -printf '%s %p\n' | sort -n) >"$OUT/tarball-files.txt"
note "binary_xz7_bytes=$(xz -7 -T0 -c "$D/empryo" | wc -c) whole_dir_tar_xz7_bytes=$(tar -C "$W" -cf - "$B" | xz -7 -T0 | wc -c)"
for f in "$D/empryo" "$D"/deps/native/*/* "$D/deps/rg" "$D/deps/fd" "$D/deps/lazygit"; do
  [ -f "$f" ] || continue
  g=$(objdump -T "$f" 2>/dev/null | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -1)
  n=$(readelf -d "$f" 2>/dev/null | grep NEEDED | grep -o '\[.*\]' | tr -d '[]' | tr '\n' ' ')
  i=$(readelf -l "$f" 2>/dev/null | grep -o 'interpreter: [^]]*' | cut -d' ' -f2)
  note "elf ${f#$D/}: max=${g:-static} interp=${i:-none} needed=[${n% }]"
done
# Old GitHub release line (SoulForge v2.20.25): inventory only.
GH=soulforge-2.20.25-linux-$EA
if curl -fsSL -o "$W/$GH.tar.gz" "https://github.com/proxysoul/Empryo/releases/download/v2.20.25/$GH.tar.gz"; then
  tar -C "$W" -xzf "$W/$GH.tar.gz"
  note "github v2.20.25: bytes=$(stat -c %s "$W/$GH.tar.gz") unpacked=$(du -sb "$W/$GH" | cut -f1) version='$(HOME=$W/h-gh "$W/$GH/soulforge" --version 2>&1 | head -1)' glibc_max=$(objdump -T "$W/$GH/soulforge" | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -1)"
fi
note "host: $(uname -srm) glibc=$(ldd --version | head -1 | awk '{print $NF}') cpus=$(nproc) kvm=$([ -e /dev/kvm ] && echo yes || echo no)"
endsec

# ---------------------------------------------------------------- 2. helpers
python3 "$H/stub_openai.py" $STUB "$OUT/stub.log" & STUBPID=$!
# conf <home> [stub base url]: remote port + the stub provider in the global config
conf() { python3 - "$1" "${2:-http://127.0.0.1:$STUB/v1}" "$PORT" <<'EOF'
import json, os, sys
home, base, port = sys.argv[1], sys.argv[2], int(sys.argv[3])
p = os.path.join(home, ".empryo", "config.json"); os.makedirs(os.path.dirname(p), exist_ok=True)
d = json.load(open(p)) if os.path.exists(p) else {}
d["remotePort"] = port
d["providers"] = [{"id": "stub", "name": "Stub", "baseURL": base, "models": ["stub-1"]}]
json.dump(d, open(p, "w"), indent=1)
EOF
}
# Every mode: RUN is a bash array prefix that runs a command "inside" that mode; EBIN the empryo path
# as that mode sees it; EHOME the HOME as that mode sees it and HHOME the same dir on the host.
SECW=()
ENVS=(TERM=dumb NO_COLOR=1 EMPRYO_NO_TTY=1 EMPRYO_NO_PROMPT=1 DO_NOT_TRACK=1 no_proxy='*' NO_PROXY='*')
mode_run() { # mode_run <label>
  local m=$1 t rc out
  sec "mode $m"
  rm -f "$HHOME/.empryo/mobile/service.json"
  # --version (cold + warm)
  t=$(date +%s%N); out=$("${RUN[@]}" env "${ENVS[@]}" HOME="$EHOME" "${SECW[@]}" "$EBIN" --version 2>&1); rc=$?; local c1; c1=$(ms "$t")
  t=$(date +%s%N); "${RUN[@]}" env "${ENVS[@]}" HOME="$EHOME" "${SECW[@]}" "$EBIN" --version >/dev/null 2>&1; local c2; c2=$(ms "$t")
  echo "$out" | head -5 | tee "$OUT/$m-version.txt"
  check "$m: --version exits 0 ($(echo "$out" | head -1))" "$([ $rc = 0 ] && grep -q "$V" <<<"$out" && echo 1 || echo 0)"
  note "$m version_ms_cold=$c1 version_ms_warm=$c2 rc=$rc"
  if [ $rc != 0 ]; then note "$m stops here: --version rc=$rc ($( [ $rc = 159 ] && echo 'SIGSYS: Bad system call'))"; endsec; return; fi
  # doctor
  "${RUN[@]}" env "${ENVS[@]}" HOME="$EHOME" "${SECW[@]}" "$EBIN" doctor >"$OUT/$m-doctor.txt" 2>&1; rc=$?
  check "$m: doctor $(grep -o 'Doctor: [A-Z]*  [0-9/]* checks passed' "$OUT/$m-doctor.txt" | head -1)" "$([ $rc = 0 ] && echo 1 || echo 0)"
  # headless turn: stub model -> shell tool -> stub reply
  t=$(date +%s%N)
  "${RUN[@]}" env "${ENVS[@]}" HOME="$EHOME" "${SECW[@]}" "$EBIN" --headless --no-genome --yolo --model stub/stub-1 --events --timeout 90000 \
    --cwd "$ECWD" "run uname" >"$OUT/$m-turn.jsonl" 2>"$OUT/$m-turn.err"; rc=$?; local tt; tt=$(ms "$t")
  local res; res=$(grep -o '"type":"tool-result","tool":"shell"[^}]*' "$OUT/$m-turn.jsonl" | head -1)
  echo "$res"
  check "$m: headless turn rc=$rc, shell tool ran (${res:0:120})" "$([ $rc = 0 ] && grep -q EMPRYO_SHELL_OK <<<"$res" && grep -q '"type":"done"' "$OUT/$m-turn.jsonl" && echo 1 || echo 0)"
  note "$m headless_turn_ms=$tt"
  # phone bridge
  t=$(date +%s%N)
  setsid "${RUN[@]}" env "${ENVS[@]}" HOME="$EHOME" TMPDIR="$ETMP" "${SECW[@]}" "$EBIN" phone-service --cwd "$ECWD" --port $PORT --login-item \
    >"$OUT/$m-phone-service.log" 2>&1 </dev/null & local SP=$!
  local up=0
  for _ in $(seq 1 300); do python3 -c "import socket;socket.create_connection(('127.0.0.1',$PORT),0.2)" 2>/dev/null && { up=1; break; }; sleep 0.1; done
  local pms; pms=$(ms "$t")
  check "$m: phone-service listens on $PORT (after ${pms} ms)" "$up"
  if [ $up = 1 ]; then
    note "$m phone_service_port_open_ms=$pms"
    sleep 20
    local body; body=$(curl -sk --noproxy '*' -m 5 "https://127.0.0.1:$PORT/"); echo "bridge: $body"
    check "$m: bridge answers over TLS ('$body')" "$(grep -q 'Empryo bridge' <<<"$body" && echo 1 || echo 0)"
    python3 "$H/procstat.py" "$m" "$SP" 60 | tee "$OUT/$m-idle.txt" | grep METRIC >>"$MET"; cat "$OUT/$m-idle.txt"
    [ -f "$HHOME/.empryo/mobile/service.json" ] && note "$m service.json exe=$(python3 -c "import json;d=json.load(open('$HHOME/.empryo/mobile/service.json'));print(d.get('exe'),d.get('endpoint'))")"
    grep -q 'quick link connected' "$OUT/$m-phone-service.log" && note "$m relay: quick link connected"
  fi
  kill -TERM -- -"$SP" 2>/dev/null; sleep 2; kill -KILL -- -"$SP" 2>/dev/null
  cat "$OUT/$m-phone-service.log" | head -20
  note "$m home_du_bytes=$(du -sxb "$HHOME" 2>/dev/null | cut -f1)"
  endsec
}

# ---------------------------------------------------------------- 3. Android seccomp emulation
sec "sec-android"
AOSP=${AOSP_DIR:-$W/aosp}; mkdir -p "$AOSP"
for f in SYSCALLS.TXT SECCOMP_ALLOWLIST_COMMON.TXT SECCOMP_ALLOWLIST_APP.TXT SECCOMP_BLOCKLIST_APP.TXT SECCOMP_BLOCKLIST_COMMON.TXT; do
  [ -s "$AOSP/$f" ] || curl -fsSL "https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/$f?format=TEXT" | base64 -d >"$AOSP/$f"
done
python3 "$H/android_seccomp.py" "$AOSP" "$SA" "$W/sec-android.c" && gcc -O2 -o "$W/sec-android" "$W/sec-android.c"
python3 "$H/android_seccomp.py" --allowed "$AOSP" "$SA" >"$OUT/android-app-allowed-syscalls.txt"
SECA=$W/sec-android
# self-test: a bionic-free static program that only uses allowed calls must survive; glibc ls must die
"$SECA" /bin/ls / >/dev/null 2>&1; sl=$?
note "sec-android self-test: glibc /bin/ls under the policy rc=$sl (159 = SIGSYS, as on Android)"
strace -f -qq -o "$OUT/sigsys-ls.txt" "$SECA" /bin/ls / >/dev/null 2>&1; grep -m3 -E 'SIGSYS|killed' "$OUT/sigsys-ls.txt" | tee -a "$MET"
endsec

# ---------------------------------------------------------------- 4. native
HHOME=$W/h-native; EHOME=$HHOME; ETMP=$W/tmp-native; ECWD=$W/proj; mkdir -p "$ETMP" "$ECWD"; git -C "$ECWD" init -q
sec "native install"
t=$(date +%s%N); HOME=$HHOME EMPRYO_NO_PROMPT=1 "$D/install.sh" --quiet >"$OUT/native-install.txt" 2>&1; rc=$?
check "native: install.sh rc=$rc in $(ms "$t") ms" "$([ $rc = 0 ] && echo 1 || echo 0)"
note "native installed_bytes=$(du -sb "$HHOME/.empryo" | cut -f1) (bin $(du -sb "$HHOME/.empryo/bin" | cut -f1), wasm $(du -sb "$HHOME/.empryo/wasm" | cut -f1))"
conf "$HHOME"
endsec
EBIN=$HHOME/.empryo/bin/empryo; RUN=(); SECW=(); mode_run native
# syscall inventory of the native binary (what proot must pass through or emulate)
sec "syscalls"
HOME=$HHOME strace -f -qq -c -o "$OUT/syscalls-version.txt" "$EBIN" --version >/dev/null 2>&1
HOME=$HHOME strace -f -qq -c -o "$OUT/syscalls-doctor.txt" env "${ENVS[@]}" "$EBIN" doctor >/dev/null 2>&1
HOME=$HHOME strace -f -qq -c -o "$OUT/syscalls-turn.txt" env "${ENVS[@]}" "$EBIN" --headless --no-genome --yolo --model stub/stub-1 --cwd "$ECWD" "run uname" >/dev/null 2>&1
HOME=$HHOME timeout -s INT 30 strace -f -qq -c -o "$OUT/syscalls-phone-service.txt" env "${ENVS[@]}" TMPDIR="$ETMP" "${SECW[@]}" "$EBIN" phone-service --cwd "$ECWD" --port $PORT --login-item >/dev/null 2>&1
pkill -f "phone-service --cwd $ECWD" 2>/dev/null; sleep 2
python3 "$H/android_seccomp.py" --check "$AOSP" "$SA" "$OUT"/syscalls-*.txt | tee -a "$MET"
endsec
# native under the Android policy (expected: SIGSYS)
EBIN=$HHOME/.empryo/bin/empryo; RUN=(); SECW=("$SECA"); mode_run native-android; SECW=()
strace -f -qq -o "$OUT/native-android-sigsys.txt" env HOME="$HHOME" "$SECA" "$EBIN" --version >/dev/null 2>&1
grep -m5 -E 'SIGSYS|killed by' "$OUT/native-android-sigsys.txt" | tee -a "$MET"

# ---------------------------------------------------------------- 5. rootfs (minbase) + tiny
sec "rootfs"
R=$W/rootfs; MIRROR=$([ "$A" = aarch64 ] && echo http://ports.ubuntu.com/ubuntu-ports || echo http://archive.ubuntu.com/ubuntu)
t=$(date +%s%N)
sudo debootstrap --variant=minbase --include=git,ca-certificates,curl noble "$R" "$MIRROR" >"$OUT/debootstrap.log" 2>&1; rc=$?
check "debootstrap minbase noble + git rc=$rc ($(ms "$t") ms)" "$([ $rc = 0 ] && echo 1 || echo 0)"
sudo chroot "$R" apt-get clean; sudo rm -rf "$R"/var/lib/apt/lists/* "$R"/var/cache/apt/* "$R"/usr/share/doc "$R"/usr/share/man "$R"/usr/share/locale
sudo chown -R "$(id -u):$(id -g)" "$R"; mkdir -p "$R/tmp" "$R/root/proj"; chmod 1777 "$R/tmp"; git -C "$R/root/proj" init -q
note "rootfs minbase(+git,curl,ca-certificates, docs stripped) bytes=$(du -sxb "$R" | cut -f1) tar_xz7_bytes=$(tar -C "$R" -cf - . 2>/dev/null | xz -7 -T0 | wc -c) glibc=$(ls "$R"/usr/lib/*-linux-gnu/libc.so.6 >/dev/null && "$R"/usr/lib/*-linux-gnu/libc.so.6 2>/dev/null | head -1 | grep -o 'version [0-9.]*')"
# tiny: the 7 glibc files + busybox (static) as /bin/sh; nothing else
T=$W/tiny; mkdir -p "$T"/{bin,lib,tmp,root/proj,etc,usr/bin} && chmod 1777 "$T/tmp"
LIBD=$(ls -d "$R"/usr/lib/*-linux-gnu); GL=$W/glibc; mkdir -p "$GL"
for l in libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0 librt.so.1 libgcc_s.so.1; do cp -L "$LIBD/$l" "$GL/"; done
cp -L "$R"/usr/lib/ld-linux-*.so.* "$GL/" 2>/dev/null || cp -L "$LIBD"/ld-linux-*.so.* "$GL/"
mkdir -p "$T/${LIBD#$R/}"; cp "$GL"/lib*.so* "$T/${LIBD#$R/}/"; LDN=$(basename "$GL"/ld-linux-*); cp "$GL/$LDN" "$T/lib/"
[ "$A" = x86_64 ] && { mkdir -p "$T/lib64"; cp "$GL/$LDN" "$T/lib64/"; }
cp "$R/etc/ssl/certs/ca-certificates.crt" "$T/etc/" 2>/dev/null; cp "$R/etc/passwd" "$R/etc/group" "$T/etc/"
(cd "$W" && apt-get download busybox-static >/dev/null 2>&1 && dpkg-deb -x busybox-static_*.deb bb) && cp "$W/bb/bin/busybox" "$T/bin/busybox"
for c in sh ash uname echo id cat ls env mkdir rm; do ln -sf busybox "$T/bin/$c"; done; ln -sf /bin/env "$T/usr/bin/env"
note "glibc-only dir ($(ls "$GL" | tr '\n' ' ')) bytes=$(du -sb "$GL" | cut -f1); tiny rootfs (glibc + busybox) bytes=$(du -sxb "$T" | cut -f1)"
endsec

# ---------------------------------------------------------------- 6. termux proot (same as the app)
sec "proot build"
git clone -q --depth 1 https://github.com/termux/proot "$W/proot-src"
make -s -C "$W/proot-src/src" -j"$(nproc)" HAS_LOADER_32BIT= proot >/dev/null 2>&1
PROOT=$W/proot-src/src/proot; note "termux/proot $(git -C "$W/proot-src" rev-parse --short HEAD) built=$([ -x "$PROOT" ] && echo yes || echo no)"
export PROOT_LOADER=$W/proot-src/src/loader/loader PROOT_TMP_DIR=$W/proot-tmp; mkdir -p "$PROOT_TMP_DIR"
endsec
# prootcmd <rootfs>: the app's ProotCommand argument list (minus the Android-only fake /proc binds)
prootcmd() { local r=$1; export PROOT_L2S_DIR=$r/.l2s; mkdir -p "$PROOT_L2S_DIR"
  RUN=("$PROOT" --kill-on-exit --link2symlink --sysvipc -0 -L -r "$r" -w /root -b /dev -b /proc -b /sys
       -b /dev/urandom:/dev/random -b /proc/self/fd:/dev/fd -b "$r/tmp:/dev/shm"
       /usr/bin/env -i PATH=/root/.empryo/bin:/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8); }

# install inside the rootfs, through proot (as the app would on first run)
cp -a "$D" "$R/opt-empryo-dist"
prootcmd "$R"
t=$(date +%s%N); "${RUN[@]}" HOME=/root EMPRYO_NO_PROMPT=1 /bin/bash /opt-empryo-dist/install.sh --quiet >"$OUT/proot-install.txt" 2>&1; rc=$?
check "proot: install.sh inside the rootfs rc=$rc ($(ms "$t") ms)" "$([ $rc = 0 ] && echo 1 || echo 0)"
rm -rf "$R/opt-empryo-dist"; conf "$R/root"
note "rootfs + empryo installed bytes=$(du -sxb "$R" | cut -f1) tar_xz7_bytes=$(tar -C "$R" -cf - . 2>/dev/null | xz -7 -T0 | wc -c)"
HHOME=$R/root; EHOME=/root; ETMP=/tmp; ECWD=/root/proj; EBIN=/root/.empryo/bin/empryo
prootcmd "$R"; mode_run proot
prootcmd "$R"; RUN=("$SECA" "${RUN[@]}"); mode_run proot-android

# tiny rootfs: empryo files copied in (no install.sh: it needs bash/coreutils)
mkdir -p "$T/root/.empryo"; cp -a "$R/root/.empryo/." "$T/root/.empryo/"; rm -rf "$T/root/.empryo/mobile" "$T/root/.empryo/sessions"
conf "$T/root"
note "tiny rootfs + empryo bytes=$(du -sxb "$T" | cut -f1) tar_xz7_bytes=$(tar -C "$T" -cf - . 2>/dev/null | xz -7 -T0 | wc -c)"
HHOME=$T/root; EHOME=/root; ETMP=/tmp; ECWD=/root/proj; EBIN=/root/.empryo/bin/empryo
prootcmd "$T"; RUN=("$SECA" "${RUN[@]}"); mode_run tiny-proot-android

# ---------------------------------------------------------------- 7. loader-direct (no proot)
sec "loader"
HHOME=$W/h-loader; EHOME=$HHOME; ETMP=$W/tmp-loader; ECWD=$W/proj; mkdir -p "$ETMP"
mkdir -p "$HHOME"; cp -a "$W/h-native/.empryo" "$HHOME/"; rm -rf "$HHOME/.empryo/mobile"
LB=$HHOME/.empryo/bin/empryo-pe; cp "$HHOME/.empryo/bin/empryo" "$LB"
"${PATCHELF:-patchelf}" --set-interpreter "$GL/$LDN" --set-rpath "$GL" "$LB"; rc=$?
note "patchelf used: $("${PATCHELF:-patchelf}" --version) (0.18.0 corrupts the Bun binary: SIGSEGV; 0.19.3 works)"
check "loader: patchelf PT_INTERP=$GL/$LDN RUNPATH=$GL rc=$rc (Bun payload survives?)" "$([ $rc = 0 ] && echo 1 || echo 0)"
# ld.so --library-path form: works for one-shot runs, but process.execPath becomes ld.so (engine respawns break)
t=$(date +%s%N); o=$(env HOME="$HHOME" "$GL/$LDN" --library-path "$GL" "$HHOME/.empryo/bin/empryo" --version 2>&1); note "loader ld.so--library-path --version: '$o' ($(ms "$t") ms)"
endsec
EBIN=$LB; RUN=(); SECW=(); mode_run loader
SECW=("$SECA"); mode_run loader-android; SECW=()
strace -f -qq -o "$OUT/loader-android-sigsys.txt" env HOME="$HHOME" "$SECA" "$LB" --version >/dev/null 2>&1
grep -m5 -E 'SIGSYS|killed by' "$OUT/loader-android-sigsys.txt" | tee -a "$MET"

kill $STUBPID 2>/dev/null
echo "== summary"; cat "$CHK"; echo; cat "$MET"
echo "overall=$([ $ok_all = 1 ] && echo ok || echo some-checks-failed)" | tee -a "$CHK"
exit 0
