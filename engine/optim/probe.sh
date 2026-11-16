#!/bin/bash
# research/optimize: measure size, unpack speed, boot time and idle cost of the Hermes Local engine on a native
# arm64 runner, the app's way (termux/proot as an unprivileged owner of the rootfs). Never fails the job on a
# single measurement: every step logs and continues. Output: $OUT (uploaded as an artifact).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
export OUT=${OUT:-$PWD/out}; mkdir -p "$OUT"
NEW=${NEW_TAG:-engine-v0.23.1-1}; OLD=${OLD_TAG:-engine-v0.23.0-3}
# work dir on the disk with the most free space (arm runners: / is usually bigger than /mnt)
if [ -z "${WORK:-}" ]; then
  if [ "$(df --output=avail -B1 /mnt 2>/dev/null | tail -n1)" -gt "$(df --output=avail -B1 / | tail -n1)" ] 2>/dev/null; then WORK=/mnt/hl; else WORK=/var/hl; fi
fi
W=$WORK; mkdir -p "$W"; chmod 755 "$W"
export PROOT_TMP=$W/prtmp
log() { echo "== $(date -u +%H:%M:%S) $*" | tee -a "$OUT/steps.txt"; }
t() { local s=$(date +%s.%N); "$@"; local rc=$?; echo "TIME $(echo "$(date +%s.%N) - $s" | bc) rc=$rc :: $*" | tee -a "$OUT/times.txt"; return $rc; }
boot() { python3 "$HERE/boot.py" "$@" 2>&1 | tee -a "$OUT/boot-driver.txt"; }
extract() { # extract <tar> <dir>: like the app (no mtimes kept, no owners), then hand the tree to the runner user
  rm -rf "$2"; mkdir -p "$2"
  tar -C "$2" -x -m --no-same-owner -f "$1" && chown -R runner:runner "$2"; }

log "host: $(nproc) cpus, $(free -g | awk '/Mem/{print $2}') GB, $(uname -r)"; df -h / /mnt | tee -a "$OUT/steps.txt"
lscpu | grep -E 'Model name|MHz|Vendor' | tee -a "$OUT/steps.txt"

log "download $NEW + $OLD"
for tag in "$NEW" "$OLD"; do
  gh release download "$tag" --repo "$GITHUB_REPOSITORY" -D "$W/$tag" -p 'rootfs-arm64.tar.xz*' && (cd "$W/$tag" && sha256sum -c rootfs-arm64.tar.xz.sha256)
done
XZ=$W/$NEW/rootfs-arm64.tar.xz
ls -l "$W"/*/rootfs-arm64.tar.xz | tee -a "$OUT/steps.txt"

log "decompress: xz single thread (phone decodes with one thread)"
t bash -c "xz -T1 -dc '$XZ' > '$W/new.tar'"
ls -l "$W/new.tar" | tee -a "$OUT/steps.txt"
t bash -c "xz -T1 -dc '$XZ' > /dev/null"
for lvl in 3 12; do t zstd -q -T0 -$lvl -f "$W/new.tar" -o "$W/new.$lvl.tar.zst"; done
t zstd -q -T0 -19 --long=27 -f "$W/new.tar" -o "$W/new.19L.tar.zst"
ls -l "$W"/new.* | tee -a "$OUT/steps.txt" "$OUT/sizes.txt"
for f in "$W"/new.3.tar.zst "$W"/new.12.tar.zst "$W"/new.19L.tar.zst; do t bash -c "zstd -q -T1 -dc --long=27 '$f' > /dev/null"; done

log "Java: the app's decoder (tukaani xz + commons-compress) vs zstd-jni"
mkdir -p "$W/jars"
for u in org/tukaani/xz/1.10/xz-1.10.jar org/apache/commons/commons-compress/1.27.1/commons-compress-1.27.1.jar \
  commons-io/commons-io/2.16.1/commons-io-2.16.1.jar org/apache/commons/commons-lang3/3.16.0/commons-lang3-3.16.0.jar \
  commons-codec/commons-codec/1.17.1/commons-codec-1.17.1.jar com/github/luben/zstd-jni/1.5.7-6/zstd-jni-1.5.7-6.jar; do
  curl -sfL -o "$W/jars/$(basename $u)" "https://repo1.maven.org/maven2/$u"; done
CP="$W/jars/*"
java -version 2>&1 | head -1 | tee -a "$OUT/steps.txt"
for args in "xz $XZ tar" "zstd $W/new.19L.tar.zst tar" "zstd $W/new.12.tar.zst tar" "xz $XZ"; do
  java -Xmx512m -cp "$CP" "$HERE/Bench.java" $args 2>&1 | tail -n 3 | tee -a "$OUT/java-bench.txt"
done
rm -f "$W"/new.3.tar.zst

log "extract NEW like the app"
t extract "$W/new.tar" "$W/rn"
du -sx -B1 "$W/rn" | tee "$OUT/du-new-blocks.txt"; du -sx -B1 --apparent-size "$W/rn" | tee -a "$OUT/du-new-blocks.txt"
find "$W/rn" -xdev -type f | wc -l | tee -a "$OUT/du-new-blocks.txt"
du -x -B1M -d4 "$W/rn" 2>/dev/null | sort -rn | head -n 60 | sed "s|$W/rn||" >"$OUT/du-new-top.txt"
python3 "$HERE/scan.py" "$W/rn" "$OUT/new" 2>&1 | tee -a "$OUT/steps.txt"
l=$(chroot "$W/rn" readlink -f /usr/local/bin/hermes); echo "launcher: $l" >"$OUT/launcher.txt"; cat "$W/rn$l" >>"$OUT/launcher.txt" 2>&1
real=$(sed -n 's/^exec \([^ ]*\) .*/\1/p' "$W/rn$l" | head -n1); echo "--- real: $real" >>"$OUT/launcher.txt"; [ -n "$real" ] && cat "$W/rn$real" >>"$OUT/launcher.txt" 2>&1
cat "$W/rn/opt/hermes/hermes-agent/.bytecode-fingerprint" "$W/rn/opt/hermes/hermes-agent/node_modules/.hermes-node-deps" >"$OUT/markers.txt" 2>&1
cat "$W/rn/opt/hermes/hermes-agent/install-stamp.json" >>"$OUT/markers.txt" 2>&1
ls -la "$W/rn/root/.hermes" "$W/rn/root/.hermes/cache" >>"$OUT/markers.txt" 2>&1

log "boots (proot, app flags, as uid runner)"
boot --label b1-first-cold --rootfs "$W/rn" --setpw --cold --snapshot --checks --idle 60 --settle 30
boot --label b2-second-cold --rootfs "$W/rn" --cold --snapshot --idle 0
boot --label b3-warm --rootfs "$W/rn" --idle 60 --settle 20
boot --label b4-noseccomp --rootfs "$W/rn" --env PROOT_NO_SECCOMP=1 --idle 60 --settle 20
boot --label b5-lean-flags --rootfs "$W/rn" --flags lean --checks --idle 60 --settle 20
boot --label b6-chroot --rootfs "$W/rn" --mode chroot --strace --idle 60 --settle 20

log "CLI call cost (what the app runs per screen): proot vs chroot, warm"
cat >"$W/cli.py" <<'PY'
import sys, os, json
sys.path.insert(0, os.environ["HERE"]); import boot
r = sys.argv[1]; res = {}
cases = {"true": ["/bin/true"], "py-pass": ["/bin/bash", "/opt/hermes-local/bin/hermes-py", "-c", "pass"],
         "hermes-version": ["hermes", "--version"], "passwd-check": ["/bin/bash", "/opt/hermes-local/bin/hermes-local-passwd", "--check"],
         "auth-status": ["/bin/bash", "/opt/hermes-local/bin/hermes-local-auth", "status"]}
for mode in ("proot", "chroot"):
    mounts = []
    if mode == "chroot":
        rr = os.path.realpath(r)
        for d in ("proc", "sys", "dev"): os.system(f"mount --bind /{d} {rr}/{d}"); mounts.append(f"{rr}/{d}")
    for k, argv in cases.items():
        ts = []
        for i in range(3):
            rc, s, o = boot.run_guest(r if mode == "proot" else os.path.realpath(r), mode, "app", argv, timeout=300)
            ts.append(round(s, 2))
        res[f"{mode}:{k}"] = dict(rc=rc, secs=ts, tail=o.strip()[-120:])
    for m in reversed(mounts): os.system(f"umount -l {m}")
print(json.dumps(res, indent=1))
PY
HERE=$HERE python3 "$W/cli.py" "$W/rn" 2>&1 | tee "$OUT/cli-times.json"

log "import time of the dashboard + gateway (launcher patched with -X importtime)"
if [ -n "$real" ] && [ -f "$W/rn$real" ]; then
  cp -p "$W/rn$real" "$W/launcher.bak"
  sed -i -E '0,/(\/[^ "]*\/bin\/python3?[0-9.]*)( +-I)/s//\1 -X importtime\2/' "$W/rn$real"
  grep -n 'importtime' "$W/rn$real" | head -n 3 | tee -a "$OUT/steps.txt"
  boot --label b7-importtime --rootfs "$W/rn" --idle 0
  cp -p "$W/launcher.bak" "$W/rn$real"
  python3 - "$OUT/boot-b7-importtime.log" >"$OUT/importtime-top.txt" <<'PY'
import re, sys, collections
rows = collections.defaultdict(list)
for line in open(sys.argv[1], errors="replace"):
    m = re.match(r"\s*[\d.]+ \[(\w+)\] import time:\s+(\d+) \|\s+(\d+) \|(\s*)(\S+)", line)
    if m: rows[m.group(1)].append((int(m.group(3)), int(m.group(2)), len(m.group(4)), m.group(5)))
for role, r in rows.items():
    tot_self = sum(x[1] for x in r)
    print(f"== {role}: {len(r)} imports, sum(self) {tot_self/1e6:.2f} s")
    for cum, self_, ind, name in sorted([x for x in r if x[2] <= 3], reverse=True)[:30]: print(f"{cum/1e6:7.3f} s cum {self_/1e3:8.1f} ms self  {name}")
PY
fi

log "slim A: drop .git, tests, website/docs, ffplay, pnpm store (low risk)"
cp -a "$W/rn" "$W/rs" 2>/dev/null || true
before=$(du -sx -B1 "$W/rs" | cut -f1)
H=$W/rs/opt/hermes/hermes-agent
rm -rf "$H/.git" "$H/tests" "$H/tests-js" "$H/website" "$H/docs" "$H/contributors" "$H/evals" "$W/rs/root/.hermes/cache/pnpm" \
  "$W"/rs/opt/hermes/tools/ffmpeg-*/bin/ffplay
find "$W/rs" -xdev -name '*.map' -path '*node_modules*' -delete
afterA=$(du -sx -B1 "$W/rs" | cut -f1); echo "SLIM_A saved $(( (before-afterA)/1048576 )) MiB, total $((afterA/1048576)) MiB" | tee -a "$OUT/slim.txt"
boot --label s1-slimA --rootfs "$W/rs" --checks --snapshot --idle 0
log "slim B: + node_modules, apps, ui-tui, gcc toolchain + headers + python3-dev, unused google discovery docs"
rm -rf "$H/node_modules" "$H/apps" "$H/ui-tui" "$W/rs/usr/libexec/gcc" "$W/rs/usr/lib/gcc" "$W/rs/usr/include" \
  "$W"/rs/usr/lib/python3.12/config-3.12-* "$W"/rs/usr/bin/aarch64-linux-gnu-lto-dump-*
find "$W/rs/root/.hermes/installs" -path '*googleapiclient/discovery_cache/documents/*.json' \
  ! -name 'gmail.v1.json' ! -name 'calendar.v3.json' ! -name 'drive.v3.json' ! -name 'docs.v1.json' ! -name 'sheets.v4.json' \
  ! -name 'slides.v1.json' ! -name 'people.v1.json' ! -name 'tasks.v1.json' ! -name 'forms.v1.json' ! -name 'youtube.v3.json' -delete
afterB=$(du -sx -B1 "$W/rs" | cut -f1); echo "SLIM_B saved $(( (afterA-afterB)/1048576 )) MiB more, total $((afterB/1048576)) MiB" | tee -a "$OUT/slim.txt"
boot --label s2-slimB --rootfs "$W/rs" --checks --snapshot --idle 30 --settle 15
HERE=$HERE python3 - "$W/rs" <<'PY' 2>&1 | tee -a "$OUT/slim.txt"
import sys, os; sys.path.insert(0, os.environ["HERE"]); import boot
for argv in (["hermes", "--version"], ["bash", "-lc", "COLUMNS=200 hermes plugins list"], ["bash", "/opt/hermes-local/bin/hermes-local-auth", "status"],
             ["bash", "/opt/hermes-local/bin/hermes-py", "-c", "import hermes_bootstrap, googleapiclient.discovery as d; print('gapi ok')"]):
    rc, s, o = boot.run_guest(sys.argv[1], "proot", "app", argv, timeout=300)
    print("SLIMB", argv[-1][:40], "rc", rc, round(s, 1), o.strip()[-300:].replace("\n", " | "))
PY
du -sx -B1 "$W/rs" | tee -a "$OUT/slim.txt"
rm -rf "$W/rs"
rm -f "$W"/new.12.tar.zst "$W"/new.19L.tar.zst

log "precompiled: fresh NEW + compileall --invalidation-mode unchecked-hash, first boot cold"
extract "$W/new.tar" "$W/rp"
b0=$(du -sx -B1 "$W/rp" | cut -f1)
for d in proc sys dev dev/shm; do mount --bind "/$d" "$W/rp/$d"; done
for d in opt/hermes/hermes-agent root/.hermes/installs opt/hermes-local opt/hermes/tools; do
  t chroot "$W/rp" /bin/bash /opt/hermes-local/bin/hermes-py -m compileall -q -j0 --invalidation-mode unchecked-hash \
    -x '/(tests|tests-js|node_modules|website|evals|apps)/' "/$d" >/dev/null 2>&1
done
for d in dev/shm dev sys proc; do umount -l "$W/rp/$d"; done
chown -R runner:runner "$W/rp"
b1=$(du -sx -B1 "$W/rp" | cut -f1); echo "PRECOMPILE added $(( (b1-b0)/1048576 )) MiB" | tee -a "$OUT/slim.txt"
boot --label p1-precompiled-first-cold --rootfs "$W/rp" --setpw --cold --snapshot --idle 0
rm -rf "$W/rp"

log "update path like the app: OLD installed + used once, then NEW unpacked and OLD /root moved over it"
t bash -c "xz -T0 -dc '$W/$OLD/rootfs-arm64.tar.xz' > '$W/old.tar'"
extract "$W/old.tar" "$W/ru"; rm -f "$W/old.tar"
boot --label u1-old-first --rootfs "$W/ru" --setpw --idle 0
du -x -B1M -d3 "$W/ru/root" | sort -rn | head -n 15 | sed "s|$W/ru||" >"$OUT/update-root-before.txt"
du -sx -B1 "$W/ru" | tee -a "$OUT/update.txt"
extract "$W/new.tar" "$W/ru.partial"
rm -rf "$W/ru.partial/root"; mv "$W/ru/root" "$W/ru.partial/root"; rm -rf "$W/ru"; mv "$W/ru.partial" "$W/ru"; mkdir -p "$W/ru/tmp"; chown runner:runner "$W/ru/tmp"
du -sx -B1 "$W/ru" | tee -a "$OUT/update.txt"
boot --label u2-after-update --rootfs "$W/ru" --snapshot --checks --idle 30 --settle 15 --timeout 900
du -sx -B1 "$W/ru" | tee -a "$OUT/update.txt"
du -x -B1M -d3 "$W/ru/root" | sort -rn | head -n 20 | sed "s|$W/ru||" >"$OUT/update-root-after.txt"
ls -la "$W"/ru/root/.hermes/installs/*/environments/ >>"$OUT/update.txt" 2>&1
grep -iE 'sync|install|environment|generation|bytecode|pnpm|node dep|rebuild|stale' "$OUT/boot-u2-after-update.log" | head -n 60 >"$OUT/update-log-grep.txt"
echo "l2s links in /root: $(find "$W/ru/root" -xdev -type l -lname '*/.l2s/*' | wc -l), dangling: $(find "$W/ru/root" -xdev -xtype l -lname '*/.l2s/*' | wc -l)" | tee -a "$OUT/update.txt"

log "growth from normal use: plugin with python deps (langfuse) under proot"
r0=$(du -sx -B1 "$W/rn/root" | cut -f1)
HERE=$HERE python3 - "$W/rn" <<'PY' 2>&1 | tail -n 15 | tee -a "$OUT/plugin.txt"
import sys, os; sys.path.insert(0, os.environ["HERE"]); import boot
rc, s, o = boot.run_guest(sys.argv[1], "proot", "app", ["bash", "-lc", "timeout 600 hermes plugins install langfuse --enable --yes-deps </dev/null 2>&1 | tail -n 8"], timeout=700)
print("PLUGIN rc", rc, "secs", round(s, 1)); print(o[-1500:])
PY
r1=$(du -sx -B1 "$W/rn/root" | cut -f1); echo "PLUGIN /root grew $(( (r1-r0)/1048576 )) MiB" | tee -a "$OUT/plugin.txt"
du -x -B1M -d4 "$W/rn/root" | sort -rn | head -n 20 | sed "s|$W/rn||" >>"$OUT/plugin.txt"
echo "l2s store: $(du -sx -B1M "$W/rn/.l2s" | cut -f1) MiB, links into it: $(find "$W/rn" -xdev -type l -lname '*/.l2s/*' | wc -l)" | tee -a "$OUT/plugin.txt"

log "l2s path spelling (app bug: absolutePath vs canonicalPath)"
ln -sfn "$W/rn" "$W/rn-sym"
for spelling in canonical symlinked; do
  if [ $spelling = canonical ]; then L2S="$W/rn/.l2s"; else L2S="$W/rn-sym/.l2s"; fi
  out=$(sudo -u runner env -i PATH=/usr/bin:/bin PROOT_TMP_DIR="$PROOT_TMP" PROOT_L2S_DIR="$L2S" "$PROOT" --link2symlink -0 -r "$W/rn-sym" -w /root \
    /usr/bin/env -i PATH=/usr/bin:/bin /bin/sh -c 'cd /tmp && echo hi > l2s-a && ln l2s-a l2s-b && cat l2s-b && rm -f l2s-a l2s-b' 2>&1)
  echo "L2S $spelling -> $(echo $out | tr '\n' ' ')" | tee -a "$OUT/steps.txt"
done
log "done"; df -h /mnt / | tee -a "$OUT/steps.txt"
