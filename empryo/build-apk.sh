#!/bin/bash
# Builds the Empryo Local test APK with the minimal rootfs bundled (Empryo itself downloads on first run).
#   ROOTFS_DIR=<dir with rootfs.tar.xz + .sha256>  ABI=arm64-v8a|x86_64  VERSION_NAME VERSION_CODE  GRADLE=gradle
#   Signing: KEYSTORE_FILE + KEYSTORE_PASSWORD + KEY_ALIAS + KEY_PASSWORD (else debug-signed)
#   GRADLE_ARGS (e.g. -PciHooks=true)
# Output: out/empryo-local-<version>-<abi>.apk (repo root)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
E="$ROOT/empryo/android"
ABI=${ABI:-arm64-v8a}
VERSION_NAME=${VERSION_NAME:-0.1.0-test3}
VERSION_CODE=${VERSION_CODE:-1}
GRADLE=${GRADLE:-gradle}

# proot + loader + talloc from Termux, renamed lib*.so (same script and checks as Hermes Local).
ABI=$ABI bash "$ROOT/scripts/fetch-proot.sh"
rm -rf "$E/app/src/main/jniLibs"; mkdir -p "$E/app/src/main/jniLibs"
cp -r "$ROOT/android/app/src/main/jniLibs/$ABI" "$E/app/src/main/jniLibs/"

assets="$E/app/src/main/assets/rootfs"
rm -rf "$assets"; mkdir -p "$assets"
(cd "${ROOTFS_DIR:?set ROOTFS_DIR}" && sha256sum -c rootfs.tar.xz.sha256)
cp "$ROOTFS_DIR/rootfs.tar.xz" "$assets/rootfs.tar.xz"
cp "$ROOTFS_DIR/rootfs.tar.xz.sha256" "$assets/rootfs.tar.xz.sha256"

props="$E/keystore.properties"
rm -f "$props"
if [ -n "${KEYSTORE_FILE:-}" ]; then
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$KEYSTORE_FILE" "$KEYSTORE_PASSWORD" "$KEY_ALIAS" "$KEY_PASSWORD" >"$props"
fi
trap 'rm -f "$props"' EXIT

cd "$E"
"$GRADLE" --no-daemon -PengineAbi="$ABI" -PversionName="$VERSION_NAME" -PversionCode="$VERSION_CODE" ${GRADLE_ARGS:-} :app:assembleRelease
mkdir -p "$ROOT/out"
out="$ROOT/out/empryo-local-$VERSION_NAME-$ABI.apk"
cp app/build/outputs/apk/release/app-release.apk "$out"
ls -l "$out"
unzip -v "$out" | grep -E 'lib/.*/lib(proot|proot-loader|talloc|android-shmem)\.so|assets/rootfs/' || true
unzip -v "$out" | grep -E 'assets/rootfs/rootfs\.tar\.xz$' | grep -q ' Stored ' || { echo "rootfs payload compressed or missing" >&2; exit 1; }
