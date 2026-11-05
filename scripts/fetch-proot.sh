#!/usr/bin/env bash
# Downloads proot + its two libraries from the Termux arm64 repository, verifies each .deb against
# the repository index, and puts them into android/src/main/jniLibs/arm64-v8a under the names
# EnginePaths.kt / ProotCommand.kt expect:
#
#   libproot.so          <- usr/bin/proot                  (NEEDED libtalloc.so.2 -> libtalloc.so)
#   libproot-loader.so   <- usr/libexec/proot/loader       (static, untouched)
#   libtalloc.so         <- usr/lib/libtalloc.so.2.x       (SONAME libtalloc.so.2 -> libtalloc.so)
#   libandroid-shmem.so  <- usr/lib/libandroid-shmem.so
#
# Android only extracts lib*.so files from an APK into nativeLibraryDir, so every binary is renamed
# and the DT_RUNPATH pointing at /data/data/com.termux is removed: the libraries are found through
# LD_LIBRARY_PATH=nativeLibraryDir (ProotCommand.hostEnv).
#
# Writes:
#   android/src/main/jniLibs/arm64-v8a/*.so    (git-ignored)
#   android/proot-jnilibs.sha256               (git-ignored; checked by verifyProotJniLibs in build.gradle)
#   scripts/proot.lock                         (committed: versions + .deb sha256 of the last fetch)
#
# Env:
#   TERMUX_REPO     default https://packages.termux.dev/apt/termux-main
#   PROOT_LOCKED=1  refuse anything other than the .deb hashes recorded in scripts/proot.lock
# Needs: curl, sha256sum, tar, python3, and dpkg-deb (or ar + xz); patchelf (apt `patchelf` or `pip install patchelf`).
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MODULE_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
INDEX_URL="$REPO/dists/stable/main/binary-aarch64/Packages"
JNI_DIR="$MODULE_DIR/android/src/main/jniLibs/arm64-v8a"
SUMS_FILE="$MODULE_DIR/android/proot-jnilibs.sha256"
LOCK_FILE="$SCRIPT_DIR/proot.lock"
MIN_PROOT=5.1.107.91 # .90 and older: broken on Android 15 / hang on some kernels

fail() { echo "fetch-proot: $*" >&2; exit 1; }
fetch() { curl --fail --location --silent --show-error --retry 3 --proto '=https' "$@"; }

for c in curl sha256sum tar python3; do command -v "$c" >/dev/null || fail "missing $c"; done
PATCHELF=$(command -v patchelf || true)
if [ -z "$PATCHELF" ] && [ -x "$HOME/.local/bin/patchelf" ]; then PATCHELF="$HOME/.local/bin/patchelf"; fi
[ -n "$PATCHELF" ] || fail "patchelf not found (apt-get install patchelf, or pip install patchelf)"

# version_ge A B: A >= B (Debian version order when dpkg is present).
version_ge() {
  if command -v dpkg >/dev/null; then dpkg --compare-versions "$1" ge "$2"
  else [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n1)" = "$2" ]; fi
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "==> Termux index: $INDEX_URL"
fetch -o "$WORK/Packages" "$INDEX_URL"

# field PKG FIELD: one field of one stanza in the index.
field() {
  awk -v pkg="$1" -v key="$2" '
    /^$/ { inpkg = 0 }
    $0 == "Package: " pkg { inpkg = 1 }
    inpkg && index($0, key ": ") == 1 { print substr($0, length(key) + 3); exit }
  ' "$WORK/Packages"
}

locked_sha() { [ -f "$LOCK_FILE" ] && awk -v p="$1" '$1 == p { print $3 }' "$LOCK_FILE" || true; }

extract_deb() { # deb dest
  mkdir -p "$2"
  if command -v dpkg-deb >/dev/null; then
    dpkg-deb -x "$1" "$2"
  else
    command -v ar >/dev/null || fail "need dpkg-deb or ar (binutils) to unpack $1"
    local t; t=$(mktemp -d -p "$WORK")
    (cd "$t" && ar x "$1")
    local data; data=$(ls "$t"/data.tar.* | head -n1)
    tar -xf "$data" -C "$2"
  fi
}

declare -A VERSION DEB_SHA
for pkg in proot libtalloc libandroid-shmem; do
  version=$(field "$pkg" Version)
  filename=$(field "$pkg" Filename)
  sha=$(field "$pkg" SHA256)
  [ -n "$version" ] && [ -n "$filename" ] && [[ $sha =~ ^[0-9a-f]{64}$ ]] || fail "$pkg not found in the Termux index"
  if [ "$pkg" = proot ]; then
    case "$version" in 5.1.107.90 | 5.1.107.90-*) fail "proot $version is the known-bad build; refusing" ;; esac
    version_ge "$version" "$MIN_PROOT" || fail "proot $version is older than $MIN_PROOT"
  fi
  if [ "${PROOT_LOCKED:-0}" = 1 ]; then
    want=$(locked_sha "$pkg")
    [ -n "$want" ] || fail "PROOT_LOCKED=1 but $pkg is not in $LOCK_FILE"
    [ "$want" = "$sha" ] || fail "$pkg: index has $sha ($version), lock has $want"
  fi
  echo "==> $pkg $version ($filename)"
  fetch -o "$WORK/$pkg.deb" "$REPO/$filename"
  echo "$sha  $WORK/$pkg.deb" | sha256sum -c --quiet - || fail "$pkg.deb does not match the index SHA256"
  extract_deb "$WORK/$pkg.deb" "$WORK/root"
  VERSION[$pkg]=$version
  DEB_SHA[$pkg]=$sha
done

P="$WORK/root/data/data/com.termux/files/usr"
talloc=$(find "$P/lib" -maxdepth 1 -type f -name 'libtalloc.so.*' | sort -V | tail -n1)
[ -f "$P/bin/proot" ] || fail "proot binary missing from the .deb"
[ -f "$P/libexec/proot/loader" ] || fail "proot loader missing from the .deb"
[ -n "$talloc" ] || fail "libtalloc.so.* missing from the .deb"
[ -f "$P/lib/libandroid-shmem.so" ] || fail "libandroid-shmem.so missing from the .deb"

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"
install -m 0755 "$P/bin/proot" "$JNI_DIR/libproot.so"
install -m 0755 "$P/libexec/proot/loader" "$JNI_DIR/libproot-loader.so"
install -m 0755 "$talloc" "$JNI_DIR/libtalloc.so"
install -m 0755 "$P/lib/libandroid-shmem.so" "$JNI_DIR/libandroid-shmem.so"

echo "==> Patching NEEDED / SONAME / RUNPATH"
# patchelf --replace-needed / --set-soname grow the file and add a PT_LOAD segment, which old
# bionic linkers have rejected. The new names are shorter, so rewrite the .dynstr strings in place
# (NUL padded) instead; that also covers a DT_VERNEED vn_file sharing the string.
dynstr_rename() { # file old new
  python3 - "$@" <<'PY'
import struct, sys
path, old, new = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
assert len(new) <= len(old)
data = bytearray(open(path, "rb").read())
assert data[:4] == b"\x7fELF" and data[4] == 2 and data[5] == 1, "not a 64-bit little-endian ELF"
shoff, = struct.unpack_from("<Q", data, 0x28)
shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
names_off = sections[shstrndx][4]
def name(sec):
    start = names_off + sec[0]
    return bytes(data[start:data.index(b"\0", start)]).decode()
dynstr = [s for s in sections if name(s) == ".dynstr"]
assert len(dynstr) == 1, "no unique .dynstr"
off, size = dynstr[0][4], dynstr[0][5]
needle = b"\0" + old + b"\0"
hits, i = 0, data.find(needle, off, off + size)
while i >= 0:
    data[i + 1:i + 1 + len(old)] = new + b"\0" * (len(old) - len(new))
    hits += 1
    i = data.find(needle, i + 1, off + size)
open(path, "wb").write(data)
print(f"    {path.rsplit('/', 1)[-1]}: {old.decode()} -> {new.decode()} ({hits}x)")
PY
}
old_talloc=$("$PATCHELF" --print-soname "$JNI_DIR/libtalloc.so")
[[ $old_talloc == libtalloc.so.* ]] || fail "unexpected libtalloc soname '$old_talloc'"
dynstr_rename "$JNI_DIR/libtalloc.so" "$old_talloc" libtalloc.so
for lib in libproot.so libandroid-shmem.so; do
  f="$JNI_DIR/$lib"
  for needed in $("$PATCHELF" --print-needed "$f"); do
    case "$needed" in libtalloc.so.*) dynstr_rename "$f" "$needed" libtalloc.so ;; esac
  done
done
for lib in libproot.so libtalloc.so libandroid-shmem.so; do
  f="$JNI_DIR/$lib"
  # Removing DT_RUNPATH shrinks .dynamic in place (no new segment).
  [ -z "$("$PATCHELF" --print-rpath "$f")" ] || "$PATCHELF" --remove-rpath "$f"
done
# The loader is static; proot execs it for every guest program, it is never dlopen'ed.

# Every NEEDED must be a system library or one of ours.
for lib in libproot.so libtalloc.so libandroid-shmem.so; do
  for needed in $("$PATCHELF" --print-needed "$JNI_DIR/$lib"); do
    case "$needed" in
      libc.so | libdl.so | libm.so | liblog.so | libtalloc.so | libandroid-shmem.so) ;;
      *) fail "$lib needs $needed, which is neither a system library nor shipped" ;;
    esac
  done
  [ -z "$("$PATCHELF" --print-rpath "$JNI_DIR/$lib")" ] || fail "$lib still has a RUNPATH"
done
echo "    libtalloc soname $old_talloc -> $("$PATCHELF" --print-soname "$JNI_DIR/libtalloc.so")"
echo "    libproot.so NEEDED: $("$PATCHELF" --print-needed "$JNI_DIR/libproot.so" | tr '\n' ' ')"

(cd "$JNI_DIR" && sha256sum libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so) >"$SUMS_FILE"

{
  echo "# Written by scripts/fetch-proot.sh from $REPO. <package> <version> <deb sha256>"
  for pkg in proot libtalloc libandroid-shmem; do echo "$pkg ${VERSION[$pkg]} ${DEB_SHA[$pkg]}"; done
  echo "# Installed jniLibs (after patchelf): <sha256> <file>"
  sed 's/^/# /' "$SUMS_FILE"
} >"$LOCK_FILE"

echo "==> jniLibs in $JNI_DIR"
ls -l "$JNI_DIR"
