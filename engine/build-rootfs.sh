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
# Env: OUT_DIR, WORK_DIR, HERMES_REF (tag, default v0.22.7), HERMES_COMMIT (sha of that tag),
#      ENGINE_REVISION, GIT_SHA, XZ_LEVEL (default 9), WITH_AGY (default 1)
set -euo pipefail
export LC_ALL=C DEBIAN_FRONTEND=noninteractive

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
OUT_DIR=$(realpath -m "${OUT_DIR:-./out}")
WORK_DIR=$(realpath -m "${WORK_DIR:-./build}")
ROOTFS="$WORK_DIR/rootfs"
HERMES_REF="${HERMES_REF:-v0.22.7}"
HERMES_COMMIT="${HERMES_COMMIT:-}"
XZ_LEVEL="${XZ_LEVEL:-9}"
WITH_AGY="${WITH_AGY:-1}"
UBUNTU_BASE_DIR="${UBUNTU_BASE_DIR:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release}"

fail() { echo "build-rootfs: $*" >&2; exit 1; }
step() { echo "::group::$*"; echo "==> $*"; }
endstep() { echo "::endgroup::"; }
fetch() { curl --fail --location --proto '=https' --tlsv1.2 --silent --show-error --retry 3 "$@"; }

[ "$(id -u)" -eq 0 ] || fail "run as root"
case "$(uname -m)" in
  aarch64) ARCH=arm64 ;;
  x86_64) ARCH=amd64 ;;
  *) fail "unsupported host arch $(uname -m)" ;;
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

if [ "$WITH_AGY" = 1 ]; then
  step "Google agy CLI"
  fetch -o "$ROOTFS/tmp/agy-install.sh" https://antigravity.google/cli/install.sh
  in_root "bash /tmp/agy-install.sh --dir /usr/local/bin </dev/null" || echo "::warning::agy install failed"
  in_root "ls -l /usr/local/bin/agy && (timeout 30 agy --version || true)" || echo "::warning::agy not runnable"
  endstep
fi

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
mkdir -p "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/var/log" "$ROOTFS/var/lib/apt/lists/partial" "$ROOTFS/var/cache/apt/archives/partial"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp"
endstep

step "Pack rootfs-$ASSET_ARCH.tar.xz"
asset="rootfs-$ASSET_ARCH.tar.xz"
# --hard-dereference: Android's app domain cannot create hard links.
tar -C "$ROOTFS" --numeric-owner --owner=0 --group=0 --hard-dereference --sort=name \
  --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' -cf - . | xz -T0 "-$XZ_LEVEL" --check=crc64 >"$OUT_DIR/$asset"
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
