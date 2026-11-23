#!/bin/bash
# Builds the Hermes Local engine payload: an Ubuntu 24.04 rootfs with Hermes Agent installed by its
# official installer, the antigravity-oauth plugin, Google's agy CLI and the hermes-local helper
# scripts, packed as rootfs-<arch>.tar.zst for PRoot on Android.
#
# Runs as root on an Ubuntu host of the target arch (ubuntu-24.04-arm for arm64, ubuntu-24.04 for
# x86_64, which only the CI emulator uses). Approach copied from OpenBot Local's engine/build-rootfs.sh.
#
# Layout inside the rootfs:
#   /opt/hermes/hermes-agent   Hermes checkout + venv (code; replaced by engine updates)
#   /opt/hermes/tools          pm-managed node/uv/etc. (HERMES_RUNTIME_DIR)
#   /opt/hermes-local/bin      our start/passwd/auth/status scripts (on PATH via /usr/local/bin)
#   /root/.hermes              HERMES_HOME: config, auth, plugins, sessions (user data; the app carries an
#                              allow-list of it over on update). Its installs/ (the one dependency generation)
#                              and cache/ belong to the engine and are never carried over.
#
# Env: OUT_DIR, WORK_DIR, HERMES_REF (tag, default v0.23.2), HERMES_COMMIT (sha of that tag),
#      ENGINE_REVISION, GIT_SHA, ZSTD_LEVEL (default 19, --long=27), WITH_AGY (default 1),
#      SIZE_LIMIT (bytes installed on the phone, default 3200000000: the build fails above it)
set -euo pipefail
export LC_ALL=C DEBIAN_FRONTEND=noninteractive

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
OUT_DIR=$(realpath -m "${OUT_DIR:-./out}")
WORK_DIR=$(realpath -m "${WORK_DIR:-./build}")
ROOTFS="$WORK_DIR/rootfs"
HERMES_REF="${HERMES_REF:-v0.23.2}"
HERMES_COMMIT="${HERMES_COMMIT:-}"
ZSTD_LEVEL="${ZSTD_LEVEL:-19}"
SIZE_LIMIT="${SIZE_LIMIT:-3200000000}"
WITH_AGY="${WITH_AGY:-1}"
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
    AGY_CLI_DISABLE_AUTO_UPDATE=true CI=1 bash -euo pipefail -c "$1"
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
# System dependencies for the PM-managed Chromium, matching the Hermes Docker image on Noble.
# openssh-server is for the official Hermes app's Terminal, which connects by SSH as user "admin".
pkgs="ca-certificates curl wget git openssh-client openssh-server procps psmisc less xz-utils unzip zip tmux ripgrep jq
python3 python3-venv python3-dev gcc g++ make libffi-dev libatomic1 libstdc++6 tini sqlite3
iproute2 iputils-ping file bash coreutils util-linux tzdata locales fonts-dejavu-core
libasound2t64 libatk-bridge2.0-0t64 libatk1.0-0t64 libatspi2.0-0t64 libcairo2 libcups2t64
libdbus-1-3 libgbm1 libglib2.0-0t64 libnspr4 libnss3 libpango-1.0-0 libx11-6 libxcb1
libxcomposite1 libxdamage1 libxext6 libxfixes3 libxkbcommon0 libxrandr2"
in_root "apt-get update && apt-get -y upgrade && apt-get install -y --no-install-recommends $(echo $pkgs)"
# The official Hermes app's Terminal connects by SSH as user "admin". Create the user at build
# time; hermes-local-passwd keeps its system password in sync with the dashboard password, and
# hermes-local-start runs sshd for it. Host keys are per-device, so do not ship the build's:
# hermes-local-start generates them with ssh-keygen -A on first start.
in_root "id admin 2>/dev/null || useradd -m -s /bin/bash admin"
rm -f "$ROOTFS"/etc/ssh/ssh_host_*
endstep

step "Hermes Agent $HERMES_REF"
install -d -m 0755 "$ROOTFS/opt/hermes" "$ROOTFS/opt/hermes-local/bin"
for f in "$SCRIPT_DIR"/rootfs/*; do install -m 0755 "$f" "$ROOTFS/opt/hermes-local/bin/$(basename "$f")"; done
fetch -o "$ROOTFS/tmp/hermes-install.sh" "https://raw.githubusercontent.com/NousResearch/hermes-agent/$HERMES_REF/scripts/install.sh"
commit_arg=""
[ -n "$HERMES_COMMIT" ] && commit_arg="--commit $HERMES_COMMIT"
in_root "bash /tmp/hermes-install.sh $commit_arg --channel stable --dir /opt/hermes/hermes-agent --hermes-home /root/.hermes \
  --non-interactive --skip-computer-use --verbose </dev/null"
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

if [ "$WITH_AGY" = 1 ]; then
  step "Google agy CLI"
  # agy updates itself on first use (+489 MiB on the probe: new binary + agy.<n>.old). It honours only
  # AGY_CLI_DISABLE_AUTO_UPDATE=true (not =1, antigravity-cli#1046) and "update.mode": "none" in its settings.
  install -d -m 0700 "$ROOTFS/root/.gemini/antigravity-cli"
  printf '{\n  "update.mode": "none"\n}\n' >"$ROOTFS/root/.gemini/antigravity-cli/settings.json"
  fetch -o "$ROOTFS/tmp/agy-install.sh" https://antigravity.google/cli/install.sh
  in_root "bash /tmp/agy-install.sh --dir /usr/local/bin </dev/null"
  # agy's installer also copies itself to ~/.local/bin (240 MB each): keep one binary.
  if [ -f "$ROOTFS/root/.local/bin/agy" ] && [ ! -L "$ROOTFS/root/.local/bin/agy" ] && [ -f "$ROOTFS/usr/local/bin/agy" ]; then
    if cmp -s "$ROOTFS/root/.local/bin/agy" "$ROOTFS/usr/local/bin/agy"; then ln -sfn /usr/local/bin/agy "$ROOTFS/root/.local/bin/agy"; fi
  fi
  in_root "timeout 30 agy --version"
  cat "$ROOTFS/root/.gemini/antigravity-cli/settings.json"
  grep -q '"update.mode": *"none"' "$ROOTFS/root/.gemini/antigravity-cli/settings.json" || fail "agy settings lost update.mode none"
  endstep

  step "antigravity-oauth plugin"
  # This upstream revision preserves the exact model IDs returned by agy models.
  in_root "hermes plugins install antigravity-oauth --ref e4d0752d873ad4192b4630b0b0585b8c98ccc503 --enable --yes-deps </dev/null"
  in_root "/opt/hermes-local/bin/hermes-py -c 'import hermes_bootstrap; from providers import get_provider_profile; assert get_provider_profile(\"antigravity-oauth\") is not None'"
  endstep
fi

step "Bridge MCP (@markmayandi/bridge 1.1.1)"
BRIDGE_VERSION=""
node_bin=$(in_root "ls -d /opt/hermes/tools/node-*/bin/node 2>/dev/null | head -n1" || true)
[ -n "$node_bin" ] || fail "no pm-managed node for Bridge"
node_version=$(in_root "$node_bin --version" || true)
node_major=$(printf '%s' "$node_version" | sed 's/^v//; s/\..*//')
[ "$node_major" -ge 18 ] 2>/dev/null || fail "pm-managed node is too old for Bridge: $node_version"
npm_bin=$(in_root "ls -d /opt/hermes/tools/npm-*/bin/npm 2>/dev/null | head -n1" || true)
[ -n "$npm_bin" ] || fail "no pm-managed npm for Bridge"
node_dir=$(dirname "$node_bin"); npm_dir=$(dirname "$npm_bin")
in_root "export PATH=\"$node_dir:$npm_dir:\$PATH\"; npm install --prefix /opt/hermes-local/bridge --no-save --no-audit --no-fund --ignore-scripts @markmayandi/bridge@1.1.1 2>&1 | tail -n 3"
BRIDGE_VERSION=$(in_root "$node_bin -p \"require('/opt/hermes-local/bridge/node_modules/@markmayandi/bridge/package.json').version\"")
[ "$BRIDGE_VERSION" = "1.1.1" ] || fail "Bridge version is $BRIDGE_VERSION, expected 1.1.1"
in_root "$node_bin --check /opt/hermes-local/bridge/node_modules/@markmayandi/bridge/bridge.js && $node_bin --check /opt/hermes-local/bridge/node_modules/@markmayandi/bridge/secure-folder-mcp/server.js"
bridge_pkg=/opt/hermes-local/bridge/node_modules/@markmayandi/bridge
case "$ARCH" in arm64) cf_arch=arm64 ;; *) cf_arch=amd64 ;; esac
fetch -o "$ROOTFS$bridge_pkg/cloudflared" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cf_arch"
chmod 0755 "$ROOTFS$bridge_pkg/cloudflared"
in_root "timeout 10 $bridge_pkg/cloudflared --version"
echo "bridge $BRIDGE_VERSION, node $node_version, cloudflared pre-installed"
endstep

step "Voice: Edge TTS, cloud speech-to-text, live voice bridge (no local Whisper)"
# v1.0.0-test3 shipped faster-whisper + Whisper small (~1 GB installed) for /api/audio/transcribe. Syed never
# asked for local STT: test4 transcribes with the key the user saved (hermes_local_stt.py: OpenAI, else Gemini)
# and keeps only Edge TTS (free default voice) and Hermes' ffmpeg.
size_before=$(du -sbx "$ROOTFS" 2>/dev/null | cut -f1)
in_root "hermes pm install --extra edge-tts </dev/null 2>&1 | grep -v '^ *$' | tail -n 4"
# An extra install publishes a new dependency generation and keeps the superseded one (engine-v0.23.0-3 first
# build: +104 MiB for edge-tts); pm gc drops generations and store entries nothing references.
in_root "hermes pm gc </dev/null 2>&1 | tail -n 3" || echo "::warning::hermes pm gc failed"
rm -rf "$ROOTFS/root/.cache"
# One sealed dependency environment (what Hermes' Docker image ships): pm gc keeps superseded generations
# (engine-v0.23.1-1: a second 348 MiB env that differs only by edge-tts), so keep the generation facts.json
# selects (and pm-runtime's selected generation) and delete the rest.
python3 - "$ROOTFS" <<'PY'
import json, pathlib, shutil, sys
root = pathlib.Path(sys.argv[1])
stores = list((root / "root/.hermes/installs").glob("*/facts.json"))
assert stores, "no pm store (root/.hermes/installs/*/facts.json)"
for facts in stores:
    store = facts.parent
    venv = json.loads(facts.read_text())["packages"]["venv"]
    keep = pathlib.PurePosixPath(venv["environment"]).parent.name  # .../environments/<id>/venv
    assert (store / "environments" / keep / "venv").is_dir(), f"selected env {keep} missing"
    for d in (store / "environments").iterdir():
        if d.name != keep:
            print("dependency generation: dropping superseded", d.name); shutil.rmtree(d)
    sel = store / "pm-runtime/selected.json"
    if sel.is_file():
        gen = pathlib.PurePosixPath(json.loads(sel.read_text())["generation"]).name
        for d in (store / "pm-runtime/generations").iterdir():
            if d.name != gen:
                print("pm runtime: dropping superseded", d.name); shutil.rmtree(d)
    print("dependency generation kept:", keep, "extras", venv.get("extras"))
PY
[ "$(find "$ROOTFS"/root/.hermes/installs/*/environments -mindepth 1 -maxdepth 1 -type d | wc -l)" = 1 ] || fail "expected exactly one dependency generation"
du -sh "$ROOTFS"/root/.hermes/* "$ROOTFS"/root/.hermes/cache/* 2>/dev/null | sort -h | tail -n 8 || true
# uv's wheel cache (~300 MB) is only a download cache: the dependency environments hold their own (hard-linked)
# copies, and the packed tar stores hard links twice (--hard-dereference). Drop it unless something symlinks
# into it; plugin installs on the phone download what they need.
uvc="$ROOTFS/root/.hermes/cache/uv"
if [ -d "$uvc" ]; then
  if find "$ROOTFS/root/.hermes" "$ROOTFS/opt/hermes" -xdev -type l -lname '*/.hermes/cache/uv/*' -print -quit 2>/dev/null | grep -q .; then
    echo "::warning::environments symlink into the uv cache; keeping it"
  else
    echo "dropping uv download cache: $(du -sh "$uvc" | cut -f1)"; rm -rf "$uvc"
  fi
fi
size_edge=$(du -sbx "$ROOTFS" 2>/dev/null | cut -f1)
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -c 'import hermes_bootstrap, edge_tts; print(\"edge-tts\", edge_tts.__version__)'"
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -c 'import hermes_bootstrap, importlib.util as u; assert u.find_spec(\"faster_whisper\") is None, \"faster-whisper must not be installed\"; print(\"no faster-whisper: ok\")'"
# Live voice bridge (hermes_local_live.py): the host answers the app's GPT-Live WebRTC offer itself for the
# ChatGPT (Codex) and Gemini backends. aiortc + PyAV in their own --target dir, appended to sys.path at use.
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -m pip --version >/dev/null 2>&1 || /bin/bash /opt/hermes-local/bin/hermes-py -m ensurepip >/dev/null"
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -m pip install --disable-pip-version-check --no-cache-dir --only-binary=:all: \
  --target /opt/hermes-local/live-libs 'aiortc==1.16.0' 'aiohttp>=3.10' 2>&1 | tail -n 3"
rm -rf "$ROOTFS/opt/hermes-local/live-libs/bin" "$ROOTFS/root/.cache"
# Drop packages Hermes' own environment already provides (aiohttp, cryptography, cffi, ...): live-libs is appended
# to sys.path, so Hermes' copies would win anyway.
cat >"$ROOTFS/tmp/hl-dedupe.py" <<'PY'
import importlib.util, os, shutil, sys
sys.path.insert(0, "/opt/hermes/hermes-agent")
import hermes_bootstrap  # noqa: F401  (Hermes' dependency environment)
libs = "/opt/hermes-local/live-libs"
assert libs not in sys.path
keep_always = {"aiortc", "av", "aioice", "pylibsrtp", "pyee", "google_crc32c", "ifaddr", "dns", "OpenSSL"}
for info in sorted(os.listdir(libs)):
    if not info.endswith(".dist-info"):
        continue
    d = os.path.join(libs, info)
    tops = set()
    for line in open(os.path.join(d, "RECORD"), encoding="utf-8"):
        top = line.split(",", 1)[0].split("/", 1)[0]
        if top and not top.endswith((".dist-info", ".libs")) and top not in ("..", "__pycache__"):
            tops.add(top[:-3] if top.endswith(".py") else top)
    if not tops or tops & keep_always:
        continue
    mods = {t.split(".", 1)[0] for t in tops}  # _cffi_backend.cpython-314-....so -> _cffi_backend
    if all(importlib.util.find_spec(m) is not None for m in mods):
        for t in tops:
            for cand in (os.path.join(libs, t), os.path.join(libs, t + ".py")):
                if os.path.isdir(cand): shutil.rmtree(cand)
                elif os.path.exists(cand): os.remove(cand)
        for extra in os.listdir(libs):  # vendored shared libs of that wheel (e.g. cffi.libs)
            if extra.endswith(".libs") and extra.split(".")[0].lower() in {t.lower() for t in tops}:
                shutil.rmtree(os.path.join(libs, extra), ignore_errors=True)
        shutil.rmtree(d)
        print("live-libs: Hermes already has", info, "->", ",".join(sorted(tops)))
PY
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -I /tmp/hl-dedupe.py" || echo "::warning::live-libs dedupe failed"
rm -f "$ROOTFS/tmp/hl-dedupe.py"
du -sh "$ROOTFS"/opt/hermes-local/live-libs/* 2>/dev/null | sort -h | tail -n 8 || true
find "$ROOTFS/opt/hermes-local/live-libs" -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
size_live=$(du -sbx "$ROOTFS" 2>/dev/null | cut -f1)
# Hook: tools/voice_live.py hands status + session creation to hermes_local_live (falls back to stock on error).
vl="$ROOTFS/opt/hermes/hermes-agent/tools/voice_live.py"
grep -q '^def create_webrtc_session' "$vl" && grep -q '^def resolve_gpt_live_status' "$vl" && grep -q '^def _live_section' "$vl" \
  || fail "tools/voice_live.py changed upstream: hermes-local live hook needs a review"
cat >>"$vl" <<'PY'


# hermes-local: ChatGPT (Codex) and Gemini Live backends behind GPT-Live (/opt/hermes-local/bin/hermes_local_live.py)
try:
    import sys as _hl_sys
    if "/opt/hermes-local/bin" not in _hl_sys.path:
        _hl_sys.path.append("/opt/hermes-local/bin")
    from hermes_local_live import install as _hl_install
    _hl_install(globals())
except Exception as _hl_exc:  # noqa: BLE001
    logger.warning("hermes-local live voice backends unavailable: %s", _hl_exc)
PY
# Build-time proof on this arch: aiortc/av import with Hermes' python, and a whole live conversation (stand-in
# app <-> bridge <-> local fake Gemini / fake Codex peer: audio both ways, delegation, reply, mute, close).
for kind in gemini codex; do
  in_root "cd /tmp && timeout 150 /bin/bash /opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_live_check.py module-fake $kind" \
    >"$WORK_DIR/live-fake-$kind.txt" 2>&1 || true
  grep -E '^LIVE' "$WORK_DIR/live-fake-$kind.txt" || tail -n 30 "$WORK_DIR/live-fake-$kind.txt"
  mkdir -p "$OUT_DIR/captures-$ASSET_ARCH" && cp "$WORK_DIR/live-fake-$kind.txt" "$OUT_DIR/captures-$ASSET_ARCH/build-live-fake-$kind.txt"
  grep -q '^LIVE_RESULT ok' "$WORK_DIR/live-fake-$kind.txt" || fail "live bridge self-test ($kind) failed"
done
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -c 'import sys, hermes_bootstrap; sys.path.append(\"/opt/hermes-local/live-libs\"); import aiortc, av, os; print(\"aiortc\", aiortc.__version__, \"av\", av.__version__, flush=True); os._exit(0)'" | tee "$WORK_DIR/live-versions.txt"
# Gemini TTS: Google's current models (gemini-3.8-flash[-lite]-tts) answer generateContent with a complete
# WAV, while Hermes v0.23.0 always wraps the reply as raw PCM (WAV-in-WAV: a header-sized click). One-line
# patch: keep a reply that already is RIFF/WAVE as it is. Fails the build if the upstream line moved.
python3 - "$ROOTFS/opt/hermes/hermes-agent/tools/tts_tool_providers.py" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); s = p.read_text()
old = "return _write_wav_bytes_as(_wrap_pcm_as_wav(base64.b64decode(audio_b64)), output_path)"
new = ("audio = base64.b64decode(audio_b64)  # hermes-local: 3.8 TTS models already return WAV\n"
       "    return _write_wav_bytes_as(audio if audio[:4] == b\"RIFF\" else _wrap_pcm_as_wav(audio), output_path)")
assert s.count(old) == 1, "Gemini TTS return line not found"
p.write_text(s.replace(old, new)); print("patched Gemini TTS WAV passthrough")
PY
# speak-stream (the apps' streamed replies) decodes Edge's MP3 with `ffmpeg` from PATH; Hermes' own ffmpeg
# lives under its runtime dir, so v1.0.0-test2 answered every speak-stream with "fallback".
ff=$(cd "$ROOTFS" && ls -d opt/hermes/tools/ffmpeg-*/bin/ffmpeg 2>/dev/null | head -n1)
if [ -n "$ff" ]; then
  for b in ffmpeg ffprobe; do [ -e "$ROOTFS/${ff%/ffmpeg}/$b" ] && ln -sfn "/${ff%/ffmpeg}/$b" "$ROOTFS/usr/local/bin/$b"; done
else
  echo "::warning::no Hermes-managed ffmpeg; installing Ubuntu's"; in_root "apt-get install -y --no-install-recommends ffmpeg"
fi
in_root "command -v ffmpeg && ffmpeg -hide_banner -version | head -n1"
echo "voice size (installed): edge-tts +$(( (size_edge - size_before) / 1048576 )) MiB ($(( size_edge - size_before )) B), live bridge libs +$(( (size_live - size_edge) / 1048576 )) MiB ($(( size_live - size_edge )) B)"
du -sh "$ROOTFS/opt/hermes-local/live-libs" || true
endstep

step "Browser: DevTools endpoint from Chromium's log when it cannot write DevToolsActivePort"
# Under proot on Android, Chromium's DevTools server starts ("DevTools listening on ws://127.0.0.1:<port>/
# devtools/browser/<id>" on stderr) but writing <user-data-dir>/DevToolsActivePort fails with ENOSYS, so Hermes'
# launcher, which only reads that file, gave up after its launch timeout ("Chromium did not expose a DevTools
# endpoint in time"; e2e runs 17-20). The launcher sends Chromium's output to a per-launch log it opens fresh
# ("wb"), so the line there names this launch. Patch: the file first (unchanged everywhere it works), else that
# line. Fails the build if the upstream function moved.
python3 - "$ROOTFS/opt/hermes/hermes-agent/tools/browser_launcher.py" <<'PY2'
import sys, pathlib
p = pathlib.Path(sys.argv[1]); s = p.read_text()
old = "def _read_devtools_file(user_data_dir: str) -> Optional[tuple[int, str]]:\n"
assert s.count(old) == 1, "_read_devtools_file not found"
assert '_LAUNCH_LOG = "hermes-chromium.log"' in s, "_LAUNCH_LOG changed"
s = s.replace(old, "def _hl_read_devtools_port_file(user_data_dir: str) -> Optional[tuple[int, str]]:\n")
s += '''


# hermes-local: Chromium under proot on Android cannot write DevToolsActivePort (ENOSYS) but prints the endpoint.
_HL_DEVTOOLS_LINE = None


def _read_devtools_file(user_data_dir: str) -> Optional[tuple[int, str]]:
    """``(port, "/devtools/browser/<id>")`` from DevToolsActivePort, else from this launch's Chromium log."""
    global _HL_DEVTOOLS_LINE
    found = _hl_read_devtools_port_file(user_data_dir)
    if found:
        return found
    try:
        with open(os.path.join(user_data_dir, _LAUNCH_LOG), "rb") as fh:
            raw = fh.read(4 * 1024 * 1024)
    except OSError:
        return None
    if _HL_DEVTOOLS_LINE is None:
        import re as _re
        _HL_DEVTOOLS_LINE = _re.compile(rb"DevTools listening on ws://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):(\\d+)(/devtools/browser/[0-9A-Za-z-]+)")
    last = None
    for last in _HL_DEVTOOLS_LINE.finditer(raw):
        pass
    if last is None:
        return None
    return int(last.group(1)), last.group(2).decode("ascii")
'''
p.write_text(s); print("patched browser_launcher: DevTools endpoint fallback from the Chromium log")
PY2
endstep

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
agy_version=$(in_root "AGY_CLI_DISABLE_AUTO_UPDATE=true timeout 30 agy --version 2>/dev/null | head -n1" || echo none)
cat >"$ROOTFS/opt/hermes-local/engine-version" <<EOF
hermes_ref=$HERMES_REF
hermes_commit=${HERMES_COMMIT:-}
hermes_version=$hermes_version
agy=$agy_version
bridge=$BRIDGE_VERSION
live=$(tr '\n' ' ' <"$WORK_DIR/live-versions.txt" 2>/dev/null)
stt=cloud (hermes_local_stt.py: OpenAI or Gemini key)
ubuntu_base=$base_version
arch=$ARCH
built=$(date -u +%Y-%m-%dT%H:%M:%SZ)
git=${GIT_SHA:-unknown}
revision=${ENGINE_REVISION:-0}
EOF
cat "$ROOTFS/opt/hermes-local/engine-version"
endstep

step "Slim (Docker-style: only what runs on the phone)"
H="$ROOTFS/opt/hermes/hermes-agent"
before=$(du -sx -B1 -l "$ROOTFS" | cut -f1)
# Build/dev-only trees (Hermes' Docker image ships none of them; measured safe in optimize-research slim A+B):
# .git (also grew +173 MiB at runtime), web/TUI/desktop build deps, tests, docs, the desktop app and the TUI,
# install.sh's pnpm store (+ the rest of cache/), uv's own CPython (both envs use /opt/hermes/tools/python-*), ffplay.
rm -rf "$H/.git" "$H/node_modules" "$H/tests" "$H/tests-js" "$H/evals" "$H/website" "$H/docs" "$H/contributors" \
  "$H/apps" "$H/ui-tui" "$ROOTFS/root/.hermes/cache" "$ROOTFS/root/.local/share/uv" "$ROOTFS"/opt/hermes/tools/ffmpeg-*/bin/ffplay
# C toolchain + headers (187 MiB): only plugin installs that build an sdist need them; nothing core does.
rm -rf "$ROOTFS/usr/libexec/gcc" "$ROOTFS/usr/lib/gcc" "$ROOTFS/usr/include" "$ROOTFS"/usr/lib/python3.12/config-3.12-* \
  "$ROOTFS"/usr/bin/*-linux-gnu-lto-dump-* "$ROOTFS"/usr/lib/*-linux-gnu/*.a
# Google API discovery docs: keep the Workspace/YouTube APIs (216 MiB -> a few MiB); other APIs still work with
# static_discovery=False (fetched from the network).
find "$ROOTFS/root/.hermes/installs" "$ROOTFS/opt" -path '*googleapiclient/discovery_cache/documents/*.json' \
  ! -name 'gmail.v1.json' ! -name 'calendar.v3.json' ! -name 'drive.v3.json' ! -name 'docs.v1.json' ! -name 'sheets.v4.json' \
  ! -name 'slides.v1.json' ! -name 'people.v1.json' ! -name 'tasks.v1.json' ! -name 'forms.v1.json' ! -name 'youtube.v3.json' -delete
rm -f "$ROOTFS"/usr/local/bin/agy.*.old "$ROOTFS"/root/.local/bin/agy.*.old
bind_mount /proc "$ROOTFS/proc"; bind_mount /dev "$ROOTFS/dev"
in_root "hermes --version | head -n 2"
# Check the packaged binary after trimming, so a cleanup change cannot ship a broken browser.
in_root '/opt/hermes-local/bin/hermes-py -I /opt/hermes-local/bin/hermes_local_runtime_check.py chromium'
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -c 'import hermes_bootstrap, edge_tts, googleapiclient.discovery, fastapi; print(\"imports ok\")'"
# Valid timestamp .pycs for the Hermes tree and live-libs: the app now restores mtimes, so they stay valid on the
# phone (v1.0.0-test4's first boot recompiled ~2,000 modules because every mtime was "now").
in_root "/bin/bash /opt/hermes-local/bin/hermes-py -m compileall -q /opt/hermes/hermes-agent /opt/hermes-local/live-libs >/dev/null 2>&1 || true"
cleanup_mounts
after=$(du -sx -B1 -l "$ROOTFS" | cut -f1)
echo "slim: $(( (before - after) / 1048576 )) MiB removed (net of added pyc), $(( after / 1048576 )) MiB left"
endstep

step "Shrink"
in_root "apt-get clean"
cleanup_mounts
rm -f "$ROOTFS/usr/sbin/policy-rc.d"
# Keep /var/lib/apt/lists: the Bot Screen install runs plain "apt-get install" with no update
# first, so with wiped lists apt cannot locate any package (not even xauth) and the install
# fails. The lists cost some size but make the install work out of the box; apt-get update
# refreshes them whenever the user runs it.
rm -rf "$ROOTFS"/var/cache/apt/* "$ROOTFS"/var/log/* "$ROOTFS"/tmp/* "$ROOTFS"/var/tmp/* \
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

step "Pack rootfs-$ASSET_ARCH.tar.zst"
asset="rootfs-$ASSET_ARCH.tar.zst"
# --hard-dereference: Android's app domain cannot create hard links, so the phone stores every link as a copy;
# size it the same way (du -l counts every link). zstd -19 --long=27: smaller than xz -7 and the app's zstd-jni
# decodes it ~11x faster (optimize-research section 3).
installed=$(du -sb -l "$ROOTFS" | cut -f1)
installed_disk=$(du -s -B1 -l "$ROOTFS" | cut -f1)
entries=$(find "$ROOTFS" -xdev | wc -l)
echo "installed: $installed B apparent, $installed_disk B on disk (4 KiB blocks), $entries entries; limit $SIZE_LIMIT B"
du -sx -B1M -l "$ROOTFS"/opt/hermes/* "$ROOTFS"/opt/hermes-local "$ROOTFS"/root/.hermes/* "$ROOTFS"/usr/local/bin/agy "$ROOTFS"/usr 2>/dev/null | sort -n | tail -n 12
[ "$installed_disk" -le "$SIZE_LIMIT" ] || fail "installed size $installed_disk B is above the limit ($SIZE_LIMIT B)"
tar -C "$ROOTFS" --numeric-owner --owner=0 --group=0 --hard-dereference --sort=name \
  --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' -cf - . | zstd -q -T0 "-$ZSTD_LEVEL" --long=27 >"$OUT_DIR/$asset"
(cd "$OUT_DIR" && sha256sum "$asset" >"$asset.sha256")
sha=$(cut -d' ' -f1 "$OUT_DIR/$asset.sha256")
compressed=$(stat -c %s "$OUT_DIR/$asset")
cat >"$OUT_DIR/engine-$ASSET_ARCH.json" <<EOF
{
  "name": "hermes-local-engine",
  "hermes_ref": "$HERMES_REF",
  "hermes_version": "$hermes_version",
  "agy": "$agy_version",
  "arch": "$ASSET_ARCH",
  "asset": "$asset",
  "format": "tar+zstd --long=27",
  "sha256": "$sha",
  "compressed_bytes": $compressed,
  "installed_bytes": $installed,
  "installed_disk_bytes": $installed_disk,
  "entries": $entries,
  "built": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "git": "${GIT_SHA:-unknown}"
}
EOF
cat "$OUT_DIR/engine-$ASSET_ARCH.json"
endstep
