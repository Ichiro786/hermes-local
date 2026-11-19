#!/bin/bash
# Builds the Hermes Local APK. The engine is not bundled (since v1.0.0-test5): the app downloads
# rootfs-<arch>.tar.zst from the engine release on first run and checks it against ENGINE_SHA256.
#   ENGINE_TAG=engine-v0.23.2-2  ENGINE_SHA256=<sha of rootfs-<arch>.tar.zst> (or ENGINE_DIR=<dir with
#   rootfs-<arch>.tar.zst.sha256>; else read from the release)
#   ABI=arm64-v8a|x86_64 (default arm64-v8a)  VERSION_NAME=1.0.0 VERSION_CODE=1  GRADLE=gradle (Gradle 9.8+)
#   Signing: KEYSTORE_FILE + KEYSTORE_PASSWORD + KEY_ALIAS + KEY_PASSWORD (else debug-signed)
# Output: out/hermes-local-<version>-<abi>.apk
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ABI=${ABI:-arm64-v8a}
ARCH=$([ "$ABI" = x86_64 ] && echo x86_64 || echo arm64)
ENGINE_TAG=${ENGINE_TAG:-engine-v0.23.2-2}
VERSION_NAME=${VERSION_NAME:-1.0.0}
VERSION_CODE=${VERSION_CODE:-1}
GRADLE=${GRADLE:-gradle}

ABI=$ABI bash "$ROOT/scripts/fetch-proot.sh"

if [ -z "${ENGINE_SHA256:-}" ]; then
  if [ -n "${ENGINE_DIR:-}" ] && [ -f "$ENGINE_DIR/rootfs-$ARCH.tar.zst.sha256" ]; then
    ENGINE_SHA256=$(cut -d' ' -f1 "$ENGINE_DIR/rootfs-$ARCH.tar.zst.sha256")
  else
    ENGINE_SHA256=$(curl -fsSL "https://github.com/Ichiro786/hermes-local/releases/download/$ENGINE_TAG/rootfs-$ARCH.tar.zst.sha256" | cut -d' ' -f1)
  fi
fi
[[ $ENGINE_SHA256 =~ ^[0-9a-f]{64}$ ]] || { echo "no engine sha256 for $ENGINE_TAG ($ARCH)" >&2; exit 1; }
echo "engine $ENGINE_TAG rootfs-$ARCH.tar.zst sha256 $ENGINE_SHA256"
rm -rf "$ROOT/android/app/src/main/assets/engine"

props="$ROOT/android/keystore.properties"
rm -f "$props"
if [ -n "${KEYSTORE_FILE:-}" ]; then
  cat >"$props" <<PROPS
storeFile=$KEYSTORE_FILE
storePassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
PROPS
fi
trap 'rm -f "$props"' EXIT

cd "$ROOT/android"
"$GRADLE" --no-daemon -PengineAbi="$ABI" -PengineTag="$ENGINE_TAG" -PengineSha256="$ENGINE_SHA256" -PversionName="$VERSION_NAME" \
  -PversionCode="$VERSION_CODE" ${GRADLE_ARGS:-} :app:assembleRelease
mkdir -p "$ROOT/out"
out="$ROOT/out/hermes-local-$VERSION_NAME-$ABI.apk"
cp app/build/outputs/apk/release/app-release.apk "$out"
ls -l "$out"
unzip -v "$out" | grep -E 'lib/.*/lib(proot|proot-loader|talloc|android-shmem|zstd-jni[^/]*)\.so' || true
unzip -l "$out" | grep 'assets/engine/' >/dev/null && { echo "an engine payload got bundled" >&2; exit 1; }
# no grep -q here: it exits at the first match, unzip gets SIGPIPE and pipefail fails the build at random
unzip -l "$out" | grep -E "lib/$ABI/libzstd-jni" >/dev/null || { echo "zstd-jni native library missing for $ABI" >&2; exit 1; }
