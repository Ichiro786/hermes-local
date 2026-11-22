# Hermes Local engine v0.23.3-1

The Linux engine that Hermes Local runs on your phone. It contains Ubuntu 24.04 and **Hermes Agent v0.23.3**, the latest stable upstream release, with the Android patches Hermes Local needs. Hermes Local 1.1.0 and later download it automatically. You never need to install it by hand.

## What's new

### Hermes Agent v0.23.3 (from v0.23.2)
The engine now builds from the upstream `v0.23.3` tag instead of `v0.23.2`. Selected upstream changes from the v0.23.2..v0.23.3 commit log:

- **Codex / ChatGPT sign-in**
  - GPT-6.2 and newer models get their system prompt as the leading developer input, and it is kept when a request overrides the input.
  - A rejected compressed request is resent once as plain JSON.
  - Model choices require verified Codex discovery.
- **MCP**
  - A re-login wakes an MCP server that was parked waiting for OAuth.
  - Single-instance stdio servers park quietly instead of probing themselves forever.
- **Nous sign-in**
  - A phone or PC clock running ahead no longer throws away a fresh sign-in.
  - Each token is judged by its own arrival time.
- **Privacy and storage**
  - Terminal secrets are kept out of the on-disk session snapshot.
  - Codex app-server rows are redacted at the storage boundary.
  - Backups wait out SQLite WAL recovery instead of failing.

These changes come from upstream. On this engine they were checked only to the extent that the smoke suite below covers them.

### Browser tools under proot on Android
Hermes' browser tools could not start Chromium inside Hermes Local. They waited and then reported *"Chromium did not expose a DevTools endpoint in time"*.

Hermes Local's CI emulator showed the cause:
- Chromium starts, and its DevTools server listens.
- Under proot on Android, writing its `DevToolsActivePort` file fails with `ENOSYS`.
- Hermes' launcher reads only that file.

This engine patches the launcher: it reads `DevToolsActivePort` first, exactly as before. If the file can't be written, it reads the same endpoint (`DevTools listening on ws://127.0.0.1:<port>/devtools/browser/<id>`) from Chromium's log for that launch. Nothing changes where the file can be written.

## Carried over from engine v0.23.2-4
- Locally installed Chromium, with Hermes' disposable browser sessions.
- Antigravity (Google) sign-in plugin and the `agy` CLI, with self-update turned off on the phone.
- Voice: Edge TTS, cloud speech-to-text with your OpenAI or Gemini key, and the GPT-Live / Gemini Live voice bridge for the official Hermes app. There is no local Whisper.
- Gemini TTS WAV passthrough fix.
- `sshd` on port 2222 for the official Hermes app's Terminal.
- Fake `/proc` entries for files Android hides from apps.
- Slim payload: one dependency generation; no `.git`, `node_modules`, tests or compilers.

## Compatibility
- **Phone:** arm64 (`rootfs-arm64.tar.zst`), run by Hermes Local through proot.
- **CI only:** `rootfs-x86_64.tar.zst`, for the Android emulator in CI. Don't install it on a phone.
- **App:** Hermes Local 1.1.0 and later select this engine automatically. Older Hermes Local versions keep the engine they were built for.

## Upgrade notes
- **One-time download.** When you open Hermes Local 1.1.0 for the first time, it downloads this engine. That is about 650 MB, shown with its exact size under Assets below. It is checked against the sha256 built into the app, then unpacked over the old engine. An interrupted download resumes. Use Wi-Fi if you can.
- **Your data is kept.** The app moves your Hermes data from the old engine to the new one:
  - your dashboard password;
  - your config and `.env`, including API keys;
  - Codex, Claude, Nous and Antigravity sign-ins;
  - sessions, memories, skills, cron jobs, plugins, MCP installs, profiles and workspace;
  - your files in `/root`.
- **Python dependencies are rebuilt.** The engine's own dependency set replaces the old one. Plugins you installed keep their settings, and their dependencies are reinstalled by Hermes when needed.
- **Offline install** works from Settings > Install from file with `rootfs-arm64.tar.zst`. The file must match the sha256 the app expects.

## Verification
- Built and smoke-tested by this release's workflow run (linked below) on native arm64 and x86_64 runners.
- Each architecture is tested both in a chroot and under termux/proot, the same proot code the phone uses.
- The smoke suite covers:
  - Hermes boot, the dashboard and its password gate;
  - the gateway and session store;
  - the browser, terminal, code-execution, MCP and Antigravity runtime checks;
  - voice;
  - the slim and self-update rules.
- A release is published only when both smoke tests pass on both architectures.
- The run of the same build script before publishing ([run 29](https://github.com/Ichiro786/hermes-local/actions/runs/45017469227)) also passed on both architectures.
- End-to-end results on the Android emulator are in the Hermes Local 1.1.0 release notes: install, upgrade with data carried over, and browser tools inside the app.

## Known issues
- **Browser on real phones not yet confirmed.**
  - On a desktop Linux proot, Chromium writes its port file, so the new fallback is exercised only on Android. It is tested in CI on an Android 16 (API 36) x86_64 emulator.
  - It has not yet been confirmed on a physical arm64 phone.
  - The underlying `ENOSYS` affects other files Chromium writes this way too, such as profile preferences. Hermes' disposable browser sessions don't rely on those being saved; a persistent browser profile may not keep its settings.
- **First browser launch can be slow.** Chromium under proot is slow to start: tens of seconds on a fast phone, and longer on the CI emulator.
- **Not covered by the smoke suite.** Real accounts were not used in testing. Codex, Claude, Nous, Antigravity, GPT-Live and Gemini Live were checked with stub or rejected credentials only.
