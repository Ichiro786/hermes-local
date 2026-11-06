#!/bin/bash
# Builds the Hermes Local engine payload: an Ubuntu 24.04 rootfs with Hermes Agent installed by its
# official installer, the antigravity-oauth plugin, Google's agy CLI and the hermes-local helper
# scripts, packed as rootfs-<arch>.tar.xz for PRoot on Android.
#
# Runs as root on an Ubuntu host of the target arch (ubuntu-24.04-arm for arm64, ubuntu-24.04 for
# x86_64, which only the CI emulator uses). Approach copied from OpenBot Local's engine/build-rootfs.sh.
#
# Layout inside the rootfs:
#   /opt/hermes/hermes-agent   Hermes checkout + venv (code; replaced by engine updates)
#   /opt/hermes/tools          pm-managed node/uv/etc. (HERMES_RUNTIME_DIR)
#   /opt/hermes-local/bin      our start/passwd/auth/status scripts (on PATH via /usr/local/bin)
#   /root/.hermes              HERMES_HOME: config, auth, plugins, sessions (user data; kept on update)
#
# Env: OUT_DIR, WORK_DIR, HERMES_REF (tag, default v0.23.0), HERMES_COMMIT (sha of that tag),
#      ENGINE_REVISION, GIT_SHA, XZ_LEVEL (default 7: multithreaded within memory), WITH_AGY (default 1),
#      WHISPER_MODEL (default small)
set -euo pipefail
export LC_ALL=C DEBIAN_FRONTEND=noninteractive

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
OUT_DIR=$(realpath -m "${OUT_DIR:-./out}")
WORK_DIR=$(realpath -m "${WORK_DIR:-./build}")
ROOTFS="$WORK_DIR/rootfs"
HERMES_REF="${HERMES_REF:-v0.23.0}"
HERMES_COMMIT="${HERMES_COMMIT:-}"
XZ_LEVEL="${XZ_LEVEL:-7}"
WITH_AGY="${WITH_AGY:-1}"
WHISPER_MODEL="${WHISPER_MODEL:-small}"  # faster-whisper model preinstalled for Live Mode (tiny|base|small)
UBUNTU_BASE_DIR="${UBUNTU_BASE_DIR:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release}"

fail() { echo "build-rootfs: $*" >&2; exit 1; }
step() { echo "::group::$*"; echo "==> $*"; }
endstep() { echo "::endgroup::"; }
fetch() { curl --fail --location --proto '=https' --tlsv1.2 --silent --show-error --retry 3 "$@"; }

[ "$(id -u)" -eq 0 ] || fail "run as root"
# TARGET_ARCH=arm64|amd64 (default: the host's). A foreign arch needs qemu-user-static binfmt with
# the F (fix-binary) flag, so the chroot runs the target's binaries transparently.
case "${TARGET_ARCH:-$(uname -m)}" in
  aarch64|arm64) ARCH=arm64 ;;
  x86_64|amd64) ARCH=amd64 ;;
  *) fail "unsupported arch ${TARGET_ARCH:-$(uname -m)}" ;;
esac
ASSET_ARCH=$([ "$ARCH" = arm64 ] && echo arm64 || echo x86_64)

MOUNTS=()
cleanup_mounts() {
  local i
  for ((i = ${#MOUNTS[@]} - 1; i >= 0; i--)); do umount -l "${MOUNTS[$i]}" 2>/dev/null || true; done
  MOUNTS=()
}
trap cleanup_mounts EXIT
bind_mount() { mkdir -p "$2"; mount --bind "$1" "$2"; MOUNTS+=("$2"); }
in_root() { # runs a bash snippet in the chroot with a clean env
  chroot "$ROOTFS" /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TERM=dumb \
    PATH=/opt/hermes-local/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HERMES_HOME=/root/.hermes HERMES_RUNTIME_DIR=/opt/hermes/tools DEBIAN_FRONTEND=noninteractive \
    CI=1 bash -euo pipefail -c "$1"
}

rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR" "$OUT_DIR"

step "ubuntu-base 24.04 $ARCH"
sums=$(fetch "$UBUNTU_BASE_DIR/SHA256SUMS")
base_file=$(grep -oE "ubuntu-base-24\.04(\.[0-9]+)?-base-$ARCH\.tar\.gz" <<<"$sums" | sort -uV | tail -n 1)
[ -n "$base_file" ] || fail "no ubuntu-base $ARCH tarball"
base_sha=$(awk -v f="$base_file" '$2 == f || $2 == "*"f {print $1}' <<<"$sums" | head -n 1)
fetch -o "$WORK_DIR/$base_file" "$UBUNTU_BASE_DIR/$base_file"
echo "$base_sha  $WORK_DIR/$base_file" | sha256sum -c -
mkdir -p "$ROOTFS"
tar -xpzf "$WORK_DIR/$base_file" -C "$ROOTFS" --numeric-owner
rm -f "$WORK_DIR/$base_file"
base_version=${base_file#ubuntu-base-}; base_version=${base_version%-base-$ARCH.tar.gz}
endstep

step "Packages"
cp -L /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
printf '#!/bin/sh\nexit 101\n' >"$ROOTFS/usr/sbin/policy-rc.d"; chmod 0755 "$ROOTFS/usr/sbin/policy-rc.d"
cat >"$ROOTFS/etc/dpkg/dpkg.cfg.d/01-hermes-local-nodoc" <<'EOF'
path-exclude /usr/share/doc/*
path-include /usr/share/doc/*/copyright
path-exclude /usr/share/man/*
path-exclude /usr/share/info/*
path-exclude /usr/share/locale/*
path-include /usr/share/locale/locale.alias
EOF
printf 'APT::Install-Recommends "false";\nAPT::Install-Suggests "false";\nAcquire::Languages "none";\n' \
  >"$ROOTFS/etc/apt/apt.conf.d/01-hermes-local"
bind_mount /proc "$ROOTFS/proc"
bind_mount /sys "$ROOTFS/sys"
bind_mount /dev "$ROOTFS/dev"
bind_mount /dev/pts "$ROOTFS/dev/pts"
# What Hermes' Dockerfile installs, minus docker/browser libs (no Chromium on the phone in v1).
pkgs="ca-certificates curl wget git openssh-client procps psmisc less xz-utils unzip zip tmux ripgrep jq
python3 python3-venv python3-dev gcc g++ make libffi-dev libatomic1 libstdc++6 tini sqlite3
iproute2 iputils-ping file bash coreutils util-linux tzdata locales fonts-dejavu-core"
in_root "apt-get update && apt-get -y upgrade && apt-get install -y --no-install-recommends $(echo $pkgs)"
endstep

step "Hermes Agent $HERMES_REF"
install -d -m 0755 "$ROOTFS/opt/hermes" "$ROOTFS/opt/hermes-local/bin"
for f in "$SCRIPT_DIR"/rootfs/*; do install -m 0755 "$f" "$ROOTFS/opt/hermes-local/bin/$(basename "$f")"; done
fetch -o "$ROOTFS/tmp/hermes-install.sh" "https://raw.githubusercontent.com/NousResearch/hermes-agent/$HERMES_REF/scripts/install.sh"
commit_arg=""
[ -n "$HERMES_COMMIT" ] && commit_arg="--commit $HERMES_COMMIT"
in_root "bash /tmp/hermes-install.sh $commit_arg --channel stable --dir /opt/hermes/hermes-agent --hermes-home /root/.hermes \
  --non-interactive --skip-browser --skip-computer-use --verbose </dev/null"
# One stable entry point regardless of where command publication put the launcher.
hermes_bin=""
for c in /root/.local/bin/hermes /opt/hermes/hermes-agent/venv/bin/hermes /opt/hermes/hermes-agent/.venv/bin/hermes /usr/local/bin/hermes; do
  if [ -e "$ROOTFS$c" ]; then hermes_bin=$c; break; fi
done
[ -n "$hermes_bin" ] || { find "$ROOTFS" -xdev -name hermes -type f -path '*bin*' 2>/dev/null | head; fail "hermes launcher not found"; }
echo "hermes launcher: $hermes_bin"
if [ "$hermes_bin" != /usr/local/bin/hermes ]; then
  if [ -L "$ROOTFS$hermes_bin" ] || [[ $hermes_bin == /opt/* ]]; then ln -sfn "$hermes_bin" "$ROOTFS/usr/local/bin/hermes"
  else cp -a "$ROOTFS$hermes_bin" "$ROOTFS/usr/local/bin/hermes"; fi
fi
in_root "hermes --version"
# The dashboard SPA must be prebuilt: the phone starts with --skip-build.
in_root "ls /opt/hermes/hermes-agent/hermes_cli/web_dist/index.html 2>/dev/null || ls -d /opt/hermes/hermes-agent/web/dist 2>/dev/null || \
  (find /opt/hermes/hermes-agent -maxdepth 4 -name index.html -path '*dist*' | head -5)"
echo "HERMES_HOME contents:"; ls -la "$ROOTFS/root/.hermes" || true
du -sh "$ROOTFS/opt/hermes"/* "$ROOTFS/root/.hermes" 2>/dev/null || true
endstep

step "antigravity-oauth plugin"
in_root "hermes plugins install antigravity-oauth --enable --yes-deps </dev/null" ||
  echo "::warning::antigravity-oauth plugin install failed"
in_root "hermes plugins list 2>&1 | tail -n 20" || true
endstep

step "Voice: faster-whisper + Whisper $WHISPER_MODEL + Edge TTS (Live Mode works without an on-demand install)"
# Without these, Hermes v0.23.0 has no STT provider: /api/audio/transcribe answers 400 "No STT provider
# available" (the Hermes Android app's Live Mode: "That didn't come through: HTTP 400"), and the extras
# would only load after an engine restart.
size_before=$(du -sb "$ROOTFS" | cut -f1)
in_root "hermes pm install --extra stt-whisper </dev/null 2>&1 | grep -v '^ *$' | tail -n 4"
in_root "hermes pm install --extra edge-tts </dev/null 2>&1 | grep -v '^ *$' | tail -n 4"
rm -rf "$ROOTFS/root/.cache"
size_deps=$(du -sb "$ROOTFS" | cut -f1)
install -d -m 0755 "$ROOTFS/opt/hermes-local/hf-hub"
install -m 0644 "$SCRIPT_DIR/testdata/jfk.wav" "$ROOTFS/tmp/jfk.wav"
# Download the model into the engine's own HF cache (hermes-local-start points HF_HUB_CACHE there) and
# transcribe a real sample with automatic language detection, the way Hermes' local provider loads it.
in_root "HF_HUB_CACHE=/opt/hermes-local/hf-hub HF_HUB_DISABLE_TELEMETRY=1 /bin/bash /opt/hermes-local/bin/hermes-py -c '
import time, hermes_bootstrap
from faster_whisper import WhisperModel
t = time.monotonic(); m = WhisperModel(\"$WHISPER_MODEL\", device=\"cpu\", compute_type=\"int8\"); load = time.monotonic() - t
t = time.monotonic(); segs, info = m.transcribe(\"/tmp/jfk.wav\", vad_filter=True); text = \" \".join(s.text.strip() for s in segs)
print(\"whisper $WHISPER_MODEL: load %.1fs, transcribe %.1fs of %.1fs audio, language %s: %s\" % (load, time.monotonic() - t, info.duration, info.language, text))
assert \"country\" in text.lower(), text
import edge_tts; print(\"edge-tts\", edge_tts.__version__)
'"
rm -f "$ROOTFS/tmp/jfk.wav"; rm -rf "$ROOTFS/root/.cache"
printf '%s\n' "$WHISPER_MODEL" >"$ROOTFS/opt/hermes-local/whisper-model"
size_after=$(du -sb "$ROOTFS" | cut -f1)
echo "voice size: python deps +$(( (size_deps - size_before) / 1048576 )) MiB, whisper $WHISPER_MODEL model +$(( (size_after - size_deps) / 1048576 )) MiB (installed)"
du -sh "$ROOTFS/opt/hermes-local/hf-hub" || true
endstep

if [ "$WITH_AGY" = 1 ]; then
  step "Google agy CLI"
  fetch -o "$ROOTFS/tmp/agy-install.sh" https://antigravity.google/cli/install.sh
  in_root "bash /tmp/agy-install.sh --dir /usr/local/bin </dev/null" || echo "::warning::agy install failed"
  # agy's installer also copies itself to ~/.local/bin (240 MB each): keep one binary.
  if [ -f "$ROOTFS/root/.local/bin/agy" ] && [ ! -L "$ROOTFS/root/.local/bin/agy" ] && [ -f "$ROOTFS/usr/local/bin/agy" ]; then
    if cmp -s "$ROOTFS/root/.local/bin/agy" "$ROOTFS/usr/local/bin/agy"; then ln -sfn /usr/local/bin/agy "$ROOTFS/root/.local/bin/agy"; fi
  fi
  in_root "ls -l /usr/local/bin/agy /root/.local/bin/agy 2>&1; (AGY_CLI_DISABLE_AUTO_UPDATE=1 timeout 30 agy --version || true)" || echo "::warning::agy not runnable"
  endstep
fi

step "No self-update on the phone"
# The engine is replaced as a whole by the app (engine-<ref>-<rev> releases). Hermes must never
# update itself or finish a "source update" at launch inside proot: on v1.0.0-test1 every launch
# under proot printed "source-update completion failed: an update is still running" (the dashboard
# and the sign-in command raced for the update lock while re-syncing dependencies).
#  1. diagnostics: what the launch-time check thinks before we change anything
install -m 0644 "$SCRIPT_DIR/diag-update.py" "$ROOTFS/opt/hermes-local/diag-update.py"
in_root "/opt/hermes-local/bin/hermes-py -I /opt/hermes-local/diag-update.py" || true
#  2. updateMechanism=external (what Hermes' own Docker image uses: "the image is rebuilt and
#     re-pulled, it never updates itself"): venv_sync.prepare_launch then never runs the
#     source-update completion, and channels refuse.
in_root "python3 - <<'PY'
import json, pathlib
p = pathlib.Path('/opt/hermes/hermes-agent/install-stamp.json')
d = json.loads(p.read_text())
d['updateMechanism'] = 'external'
d['distribution'] = 'hermes-local'
p.write_text(json.dumps(d, indent=2) + '\\n')
print('install stamp:', d)
PY"
#  3. image provenance marker: `hermes update` (CLI and the dashboard's update endpoint) refuses.
install -d -m 0755 "$ROOTFS/etc/hermes"
printf '{"schema":1,"deployment_kind":"image","manager":"hermes-local","version":"%s"}\n' "${HERMES_REF#v}" >"$ROOTFS/etc/hermes/image-provenance.json"
chmod 0444 "$ROOTFS/etc/hermes/image-provenance.json"
#  4. agy and plugins never update themselves either.
in_root "hermes config set plugins.auto_apply false >/dev/null 2>&1 || true"
in_root "/opt/hermes-local/bin/hermes-py -I /opt/hermes-local/diag-update.py" || true
in_root "hermes update --check 2>&1 | tail -n 3" || true
endstep

step "Fake /proc entries (Android hides these from apps; the app binds them only when unreadable)"
fp="$ROOTFS/opt/hermes-local/proc"
install -d -m 0755 "$fp"
printf '0.12 0.07 0.02 2/165 765\n' >"$fp/loadavg"
printf '284684.56 513853.46\n' >"$fp/uptime"
printf 'Linux version 6.1.0-hermes-local (proot@localhost) (gcc) #1 SMP PREEMPT\n' >"$fp/version"
printf '40\n' >"$fp/cap_last_cap"
printf '524288\n' >"$fp/max_user_watches"
cat >"$fp/stat" <<'EOT'
cpu  1957 0 2877 93280 262 342 254 87 0 0
cpu0 31 0 226 12027 82 10 4 9 0 0
intr 63361 0 9 0 0 0 0 3 0 0 0 0 0 0 0 0 0
ctxt 38014093
btime 1694292441
processes 26442
procs_running 1
procs_blocked 0
softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
EOT
cat >"$fp/vmstat" <<'EOT'
nr_free_pages 1743136
nr_zone_inactive_anon 179281
nr_zone_active_anon 7183
nr_zone_inactive_file 22858
nr_zone_active_file 51328
pgpgin 198
pgpgout 0
pswpin 0
pswpout 0
EOT
chmod 0644 "$fp"/*
endstep

step "Identity, DNS, version"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions single-request-reopen\n' >"$ROOTFS/etc/resolv.conf"
printf '127.0.0.1 localhost hermes-local\n::1 localhost ip6-localhost ip6-loopback\n' >"$ROOTFS/etc/hosts"
printf 'hermes-local\n' >"$ROOTFS/etc/hostname"
: >"$ROOTFS/etc/machine-id"
for f in "$ROOTFS"/opt/hermes-local/bin/*; do ln -sfn "/opt/hermes-local/bin/$(basename "$f")" "$ROOTFS/usr/local/bin/$(basename "$f")"; done
hermes_version=$(in_root "hermes --version 2>/dev/null | head -n1" || echo unknown)
agy_version=$(in_root "timeout 30 agy --version 2>/dev/null | head -n1" || echo none)
cat >"$ROOTFS/opt/hermes-local/engine-version" <<EOF
hermes_ref=$HERMES_REF
hermes_commit=${HERMES_COMMIT:-}
hermes_version=$hermes_version
agy=$agy_version
whisper=$WHISPER_MODEL
ubuntu_base=$base_version
arch=$ARCH
built=$(date -u +%Y-%m-%dT%H:%M:%SZ)
git=${GIT_SHA:-unknown}
revision=${ENGINE_REVISION:-0}
EOF
cat "$ROOTFS/opt/hermes-local/engine-version"
endstep

step "Shrink"
in_root "apt-get clean"
cleanup_mounts
rm -f "$ROOTFS/usr/sbin/policy-rc.d"
rm -rf "$ROOTFS"/var/lib/apt/lists/* "$ROOTFS"/var/cache/apt/* "$ROOTFS"/var/log/* "$ROOTFS"/tmp/* "$ROOTFS"/var/tmp/* \
  "$ROOTFS"/root/.cache "$ROOTFS"/root/.npm "$ROOTFS"/root/.bash_history "$ROOTFS"/usr/share/{man,info,groff,lintian}
find "$ROOTFS/usr/share/locale" -mindepth 1 -maxdepth 1 ! -name locale.alias -exec rm -rf {} + 2>/dev/null || true
rm -rf "$ROOTFS/root/.hermes/logs"/* "$ROOTFS/root/.hermes/sessions"/* 2>/dev/null || true
# Stale lock/marker files from the build (update lock, pm install locks, agy updater lock).
echo "lock files left by the build:"; find "$ROOTFS/root" "$ROOTFS/opt/hermes" -xdev \( -name '*.lock' -o -name 'source-completion-pending' \
  -o -name 'hermes-update-pull' \) -print 2>/dev/null | sed "s|^$ROOTFS||"
# Only empty flock files: uv.lock & co. are dependency lockfiles (plugin installs need pm/uv.lock).
find "$ROOTFS/root" "$ROOTFS/opt/hermes" -xdev \( \( -name '*.lock' -size 0 \) -o -name 'source-completion-pending' \) -type f -delete 2>/dev/null || true
mkdir -p "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/var/log" "$ROOTFS/var/lib/apt/lists/partial" "$ROOTFS/var/cache/apt/archives/partial"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp"
endstep

step "Pack rootfs-$ASSET_ARCH.tar.xz"
asset="rootfs-$ASSET_ARCH.tar.xz"
# --hard-dereference: Android's app domain cannot create hard links.
tar -C "$ROOTFS" --numeric-owner --owner=0 --group=0 --hard-dereference --sort=name \
  --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' -cf - . | xz -T0 --memlimit-compress=60% "-$XZ_LEVEL" --check=crc64 >"$OUT_DIR/$asset"
(cd "$OUT_DIR" && sha256sum "$asset" >"$asset.sha256")
sha=$(cut -d' ' -f1 "$OUT_DIR/$asset.sha256")
compressed=$(stat -c %s "$OUT_DIR/$asset")
installed=$(du -sb "$ROOTFS" | cut -f1)
cat >"$OUT_DIR/engine-$ASSET_ARCH.json" <<EOF
{
  "name": "hermes-local-engine",
  "hermes_ref": "$HERMES_REF",
  "hermes_version": "$hermes_version",
  "agy": "$agy_version",
  "arch": "$ASSET_ARCH",
  "asset": "$asset",
  "sha256": "$sha",
  "compressed_bytes": $compressed,
  "installed_bytes": $installed,
  "built": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "git": "${GIT_SHA:-unknown}"
}
EOF
cat "$OUT_DIR/engine-$ASSET_ARCH.json"
endstep
