# Hermes Local build: progress
Brief: app (Kotlin/Compose, throttle lever) + engine (arm64 rootfs w/ Hermes v0.22.7) for Syed. Started 2026-11-06 00:01 IST.
Syed 00:04: Codex + Antigravity must work out of box (antigravity-oauth plugin + agy CLI in rootfs; app Providers screen: Codex device code, agy login relay code to stdin, status, sign-out).
- 00:05 studied openbot-local engine (build-rootfs.sh, smoke-test.sh, fetch-proot.sh, ProotCommand/TarXzExtractor/NativeFs). Hermes pinned v0.22.7 (b200f5bc). Install via scripts/install.sh --commit in chroot.
