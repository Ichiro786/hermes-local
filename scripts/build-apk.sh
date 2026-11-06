#!/bin/bash
# Builds the Hermes Local APK with the engine payload bundled.
#   ENGINE_DIR=<dir with rootfs-<arch>.tar.xz + .sha256>  (required unless NO_ENGINE=1)
#   ABI=arm64-v8a|x86_64 (default arm64-v8a)  ENGINE_TAG=engine-v0.23.0-3
#   VERSION_NAME=1.0.0 VERSION_CODE=1  GRADLE=gradle (a Gradle 9.8+ binary)
#   Signing: KEYSTORE_FILE + KEYSTORE_PASSWORD + KEY_ALIAS + KEY_PASSWORD (else debug-signed)
# Output: out/hermes-local-<version>-<abi>.apk
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ABI=${ABI:-arm64-v8a}
ARCH=$([ "$ABI" = x86_64 ] && echo x86_64 || echo arm64)
ENGINE_TAG=${ENGINE_TAG:-engine-v0.23.0-3}
VERSION_NAME=${VERSION_NAME:-1.0.0}
VERSION_CODE=${VERSION_CODE:-1}
GRADLE=${GRADLE:-gradle}

ABI=$ABI bash "$ROOT/scripts/fetch-proot.sh"

assets="$ROOT/android/app/src/main/assets/engine"
rm -rf "$assets"; mkdir -p "$assets"
if [ "${NO_ENGINE:-0}" != 1 ]; then
  src="${ENGINE_DIR:?set ENGINE_DIR}/rootfs-$ARCH.tar.xz"
  [ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
  (cd "$(dirname "$src")" && sha256sum -c "rootfs-$ARCH.tar.xz.sha256")
  ln -f "$src" "$assets/rootfs.tar.xz" 2>/dev/null || cp "$src" "$assets/rootfs.tar.xz"
  echo "$(cut -d' ' -f1 "$src.sha256")  rootfs.tar.xz" >"$assets/rootfs.tar.xz.sha256"
fi

props="$ROOT/android/keystore.properties"
rm -f "$props"
if [ -n "${KEYSTORE_FILE:-}" ]; then
  cat >"$props" <<EOF
storeFile=$KEYSTORE_FILE
storePassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
fi
trap 'rm -f "$props"' EXIT

cd "$ROOT/android"
"$GRADLE" --no-daemon -PengineAbi="$ABI" -PengineTag="$ENGINE_TAG" -PversionName="$VERSION_NAME" -PversionCode="$VERSION_CODE" \
  ${GRADLE_ARGS:-} :app:assembleRelease
mkdir -p "$ROOT/out"
out="$ROOT/out/hermes-local-$VERSION_NAME-$ABI.apk"
cp app/build/outputs/apk/release/app-release.apk "$out"
ls -l "$out"
# The payload must be stored (not deflated) and the proot binaries present.
unzip -v "$out" | grep -E 'lib/.*/lib(proot|proot-loader|talloc|android-shmem)\.so|assets/engine/' || true
if [ "${NO_ENGINE:-0}" != 1 ]; then
  unzip -v "$out" | grep -E 'assets/engine/rootfs\.tar\.xz$' | grep -q ' Stored ' || { echo "payload compressed or missing" >&2; exit 1; }
fi
