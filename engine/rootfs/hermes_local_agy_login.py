#!/usr/bin/env python3
"""Hermes Local: drive Google's agy CLI sign-in from the app (no terminal).

agy signs in only from its interactive TUI: `agy -p` without a session prints
"authentication required. Run 'agy' to log in", and the antigravity-oauth plugin's
`hermes auth add antigravity-oauth` refuses a non-tty stdin. So this runs `agy` on a
pseudo-terminal, picks "Google OAuth" and relays (one line each, flushed):

  HL_STATE <text>        progress
  HL_URL <url>           the Google sign-in URL (OSC 8 link target or unwrapped screen text)
  HL_PROMPT code         agy waits for the authorization code; the app sends it as one stdin line
  HL_ERROR <text>        agy rejected the code (the app may send another one)
  HL_DONE ok|fail <why>  final result; ok only after `agy models` answers (a live session)
  HL_RAW <text>          other agy screen text, for the log view

Captured agy 1.5.1 screens (2026-11-06): "Select login method: > 1. Google OAuth",
"Open the URL below in your browser:", "authorization code...", on a bad code
"Got an error: token exchange failed: oauth2: \"invalid_grant\"" + "Press any key to go back.".
"""
import codecs
import os
import pty
import re
import select
import signal
import struct
import subprocess
import sys
import tempfile
import time

ANSI = re.compile(r"\x1b\[[0-9;?<>=]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>78DMEc]")
OSC8 = re.compile(r"\x1b\]8;[^;\x07\x1b]*;(https://[^\x07\x1b]+)(?:\x07|\x1b\\)")
URL = re.compile(r"https://[^\s\"'<>\x1b]+")
TOKEN_FILES = ("jetski-standalone-oauth-token", "antigravity-oauth-token")
TOKEN_DIR = os.path.expanduser("~/.gemini/antigravity-cli")
CODE_WAIT = int(os.environ.get("HL_AGY_CODE_TIMEOUT", "600"))  # how long the user may take
AGY = os.environ.get("HL_AGY", "agy")


def strip(text: str) -> str:
    return ANSI.sub("", text).replace("\r", "\n")


def emit(*parts: str) -> None:
    print(" ".join(parts), flush=True)


def pick_url(raw: str, screen: str):
    """OSC 8 hyperlink target when agy sends one, else the longest google https URL on screen."""
    links = [u for u in OSC8.findall(raw) if "google" in u]
    if links:
        return max(links, key=len)
    found = [m.group(0).rstrip(".,)") for m in URL.finditer(screen) if "google" in m.group(0)]
    return max(found, key=len) if found else None


def token_present() -> bool:
    return any(os.path.isfile(os.path.join(TOKEN_DIR, n)) and os.path.getsize(os.path.join(TOKEN_DIR, n)) > 10
               for n in TOKEN_FILES)


def models_ok():
    try:
        r = subprocess.run([AGY, "models"], capture_output=True, text=True, timeout=90, stdin=subprocess.DEVNULL)
    except Exception as exc:  # noqa: BLE001
        return False, str(exc)
    lines = [ln for ln in r.stdout.splitlines() if ln.strip() and "fetching" not in ln.lower()]
    if r.returncode == 0 and lines:
        return True, f"{len(lines)} models"
    err = (r.stderr or r.stdout).strip().splitlines()
    return False, err[-1] if err else f"agy models exit {r.returncode}"


def main() -> int:
    os.environ.setdefault("AGY_CLI_DISABLE_AUTO_UPDATE", "1")
    if token_present():
        ok, detail = models_ok()
        if ok:
            emit("HL_DONE", "ok", "already signed in;", detail)
            return 0
    cwd = tempfile.mkdtemp(prefix="hl-agy-login-")
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(cwd)
        os.environ["TERM"] = "xterm-256color"
        os.environ["BROWSER"] = "echo"
        os.execvp(AGY, [AGY])
    try:  # a very wide terminal, so agy never wraps the URL
        import fcntl
        import termios
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 60, 1000, 0, 0))
        os.kill(pid, signal.SIGWINCH)
    except Exception:  # noqa: BLE001
        pass

    decoder = codecs.getincrementaldecoder("utf-8")("replace")
    raw, screen, seen = "", "", set()
    url = None
    selected = False
    waiting_code = False   # agy shows the code prompt
    code_sent_at = None
    started = time.monotonic()
    stdin_open = True
    result = None
    emit("HL_STATE", "starting agy")

    def send(data: str) -> None:
        os.write(fd, data.encode())

    while result is None:
        now = time.monotonic()
        if code_sent_at is None and now - started > CODE_WAIT + 60:
            result = ("fail", "timed out waiting for the code")
            break
        if code_sent_at is not None and now - code_sent_at > 90:
            result = ("fail", "agy did not confirm the sign-in within 90 s")
            break
        fds = [fd] + ([sys.stdin.fileno()] if stdin_open and waiting_code else [])
        r, _, _ = select.select(fds, [], [], 0.5)
        if fd in r:
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                chunk = b""
            if not chunk:
                result = ("fail", "agy exited")
                break
            piece = decoder.decode(chunk)
            raw = (raw + piece)[-60000:]
            text = strip(piece)
            screen = (screen + text)[-20000:]
            for ln in text.split("\n"):
                s = ln.strip()
                if s and len(s) > 2 and s not in seen and len(seen) < 400 and not set(s) <= set("─━-"):
                    seen.add(s)
                    emit("HL_RAW", s[:400])
            low = screen.lower()
            if not selected and "login method" in low:
                time.sleep(0.3)
                send("\r")  # "1. Google OAuth" is preselected
                selected = True
                emit("HL_STATE", "chose Google OAuth")
            # Take the URL only once the paste prompt shows: the long URL may arrive in pieces.
            if url is None and "authorization code" in low:
                url = pick_url(raw, screen)
                if url:
                    emit("HL_URL", url)
                    emit("HL_PROMPT", "code")
                    waiting_code = True
            if code_sent_at is not None:
                tail = screen[-3000:]
                m = re.search(r"Got an error:\s*(.+)", tail)
                if m:
                    emit("HL_ERROR", m.group(1).strip()[:300])
                    # "Press any key to go back" returns to the method menu: start over there.
                    send("\r")
                    screen, raw = "", ""
                    selected, url, waiting_code, code_sent_at = False, None, False, None
                    seen.clear()
        if token_present():
            result = ("ok", "token saved")
            break
        if waiting_code and sys.stdin.fileno() in r:
            line = sys.stdin.readline()
            if not line:
                stdin_open = False
            elif line.strip():
                screen = screen[-200:]
                send(line.strip() + "\r")
                code_sent_at = time.monotonic()
                waiting_code = False
                emit("HL_STATE", "code sent, waiting for agy")
        done, _ = os.waitpid(pid, os.WNOHANG)
        if done:
            result = ("ok" if token_present() else "fail", "agy exited")
            break

    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.kill(pid, sig)
            time.sleep(0.8)
        except OSError:
            break
    try:
        os.waitpid(pid, 0)
    except OSError:
        pass
    if result[0] != "ok":
        emit("HL_DONE", "fail", result[1])
        return 1
    ok, detail = models_ok()
    emit("HL_DONE", "ok" if ok else "fail", detail)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
