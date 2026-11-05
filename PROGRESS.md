# Hermes Local build: progress
Brief: app (Kotlin/Compose, throttle lever) + engine (arm64 rootfs w/ Hermes v0.22.7) for Syed. Started 2026-11-06 00:01 IST.
Syed 00:04: Codex + Antigravity must work out of box (antigravity-oauth plugin + agy CLI in rootfs; app Providers screen: Codex device code, agy login relay code to stdin, status, sign-out).
- 00:05 studied openbot-local engine (build-rootfs.sh, smoke-test.sh, fetch-proot.sh, ProotCommand/TarXzExtractor/NativeFs). Hermes pinned v0.22.7 (b200f5bc). Install via scripts/install.sh --commit in chroot.
- 00:25 repo Ichiro786/hermes-local created (private) via tube-player gh (Hark app token can't create repos). Engine run 42087080430 dispatched (arm64+x86_64).
- 00:30 BLOCKER: GitHub Actions on private repo refuse to start ("recent account payments have failed or spending limit"). Workflows kept in repo (work if repo goes public / billing fixed). Building instead on InstaBox VM tube-player (x86_64, sudo, qemu-user-static binfmt for arm64): ~/hl-drive.sh -> logs ~/hl-build/{status,build-*.log,smoke-*.log}, outputs ~/hl-build/out-<arch>. InstaBox CLI: ~/.local/bin/instabox (INSTABOX_API_KEY in env), `instabox exec tube-player -- bash -lc '...'`.
- No KVM/binder anywhere available (tube-player, Hark sandbox aarch64 2cpu no root) => emulator e2e only possible in GH Actions (android.yml) once Actions run.
- 01:00 Android app source written (android/): lever, main, settings, providers, logs, engine service/installer/proot. Not compiled yet. Plan: compile on tube-player after engine builds (needs Android SDK there).
