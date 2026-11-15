#!/bin/bash
# Minimal glibc rootfs for Empryo Local: Ubuntu 24.04 minbase + git + ca-certificates + sh, plus the fake
# /proc entries the app binds under proot (Android hides /proc/version etc. from apps). Empryo itself is
# NOT in here: the app downloads it from dl.empryo.com on first run.
#   sudo build-rootfs.sh <out dir>     (on a host of the target arch: ubuntu-24.04-arm or ubuntu-24.04)
# Output: <out>/rootfs.tar.xz + rootfs.tar.xz.sha256 + rootfs-info.txt
set -euo pipefail
OUT=$(realpath -m "${1:?out dir}"); mkdir -p "$OUT"
ARCH=$(dpkg --print-architecture)
MIRROR=$([ "$ARCH" = arm64 ] && echo http://ports.ubuntu.com/ubuntu-ports || echo http://archive.ubuntu.com/ubuntu)
R=$(mktemp -d /tmp/empryo-rootfs.XXXX)
echo "== debootstrap minbase noble ($ARCH)"
debootstrap --variant=minbase --include=git,ca-certificates noble "$R" "$MIRROR" >"$OUT/debootstrap.log" 2>&1 || { tail -40 "$OUT/debootstrap.log"; exit 1; }

echo "== shrink"
rm -rf "$R"/var/lib/apt/lists/* "$R"/var/cache/apt/* "$R"/var/cache/debconf/*-old "$R"/var/log/* "$R"/tmp/* "$R"/var/tmp/* \
  "$R"/usr/share/{man,info,groff,lintian,doc-base,bug,menu}
find "$R/usr/share/doc" -mindepth 1 -not -name copyright -not -type d -delete 2>/dev/null || true
find "$R/usr/share/locale" -mindepth 1 -maxdepth 1 ! -name locale.alias -exec rm -rf {} + 2>/dev/null || true
find "$R" -xdev -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true

echo "== identity, DNS, fake /proc"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions single-request-reopen\n' >"$R/etc/resolv.conf"
printf '127.0.0.1 localhost empryo-local\n::1 localhost ip6-localhost ip6-loopback\n' >"$R/etc/hosts"
printf 'empryo-local\n' >"$R/etc/hostname"
: >"$R/etc/machine-id"
fp="$R/opt/empryo-local/proc"; install -d -m 0755 "$fp"
printf '0.12 0.07 0.02 2/165 765\n' >"$fp/loadavg"
printf '284684.56 513853.46\n' >"$fp/uptime"
printf 'Linux version 6.1.0-empryo-local (proot@localhost) (gcc) #1 SMP PREEMPT\n' >"$fp/version"
printf '40\n' >"$fp/cap_last_cap"
printf '524288\n' >"$fp/max_user_watches"
printf 'cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\nintr 63361 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n' >"$fp/stat"
printf 'nr_free_pages 1743136\npgpgin 198\npgpgout 0\npswpin 0\npswpout 0\n' >"$fp/vmstat"
chmod 0644 "$fp"/*
install -d -m 0700 "$R/root" "$R/root/workspace"
mkdir -p "$R/tmp" "$R/var/tmp" "$R/var/log" "$R/opt"; chmod 1777 "$R/tmp" "$R/var/tmp"

echo "== chroot smoke"
chroot "$R" /bin/sh -c 'git --version && ls /etc/ssl/certs/ca-certificates.crt && /bin/sh -c "echo sh ok"' | tee "$OUT/smoke.txt"

echo "== pack"
# --hard-dereference: the app's SELinux domain cannot create hard links.
tar -C "$R" --numeric-owner --owner=0 --group=0 --hard-dereference --sort=name \
  --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' -cf - . | xz -T0 --memlimit-compress=60% -7 --check=crc64 >"$OUT/rootfs.tar.xz"
(cd "$OUT" && sha256sum rootfs.tar.xz >rootfs.tar.xz.sha256)
{
  echo "arch=$ARCH"
  echo "installed_bytes=$(du -sxb "$R" | cut -f1)"
  echo "xz_bytes=$(stat -c %s "$OUT/rootfs.tar.xz")"
  echo "sha256=$(cut -d' ' -f1 "$OUT/rootfs.tar.xz.sha256")"
  echo "glibc=$(chroot "$R" ldd --version 2>/dev/null | head -1)"
  echo "git=$(chroot "$R" git --version)"
} | tee "$OUT/rootfs-info.txt"
rm -rf "$R"
