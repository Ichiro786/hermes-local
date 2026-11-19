#!/usr/bin/env python3
"""Hermes Local: Claude (Anthropic) sign-in through Hermes' own OAuth PKCE login.

Runs `hermes auth add anthropic --type oauth --no-browser` (Hermes v0.23.2:
auth_commands.py::_anthropic_oauth_login ->
agent/anthropic_credentials.py::run_hermes_oauth_login_pure) and turns its
human output into lines the app can rely on:

  HL_URL <url>       the Claude authorization page (https://claude.ai/oauth/authorize?...)
  HL_PROMPT code     Hermes waits for the authorization code; the app sends it as one stdin line
  HL_STATE <text>    progress
  HL_DONE ok|fail <detail>
  HL_RAW <text>      every other line, ANSI stripped

Hermes prints a box ("Authorize Hermes with your Claude Pro/Max
subscription.", "Open this link in your browser:") and the auth URL on its
own line, then `input("Authorization code: ")` — the prompt has no newline,
so this script watches the partial line for it (after the complete lines in
each chunk, so the URL is never lost), tells the app (HL_PROMPT), reads one
line from its own stdin (the code the user pasted in the app, in
`code#state` form) and forwards it to Hermes. The code is exchanged for a
Claude OAuth token pair (source `manual:hermes_pkce`); no API key is needed.
The login needs an active Claude Pro/Max subscription.
"""
import os
import re
import subprocess
import sys

ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")
URL = re.compile(r"https://[^\s\"'<>]+")
PROMPT = b"Authorization code:"


def emit(*parts):
    print(" ".join(parts), flush=True)


def main():
    env = dict(os.environ, PYTHONUNBUFFERED="1", NO_COLOR="1", BROWSER="echo", TERM="dumb")
    cmd = ["hermes", "auth", "add", "anthropic", "--type", "oauth", "--no-browser"]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
                         bufsize=0)
    url = None
    expect = None  # "url": the next non-empty line carries it
    prompted = False
    last = ""
    buf = b""

    def forward_code():
        """Read the pasted authorization code from the app and give it to Hermes."""
        nonlocal prompted
        prompted = True
        emit("HL_PROMPT", "code")
        try:
            code = sys.stdin.readline()
        except Exception:  # noqa: BLE001
            code = ""
        if code:
            try:
                # The subprocess stdin is a binary pipe (bufsize=0): the code must be bytes.
                p.stdin.write((code if code.endswith("\n") else code + "\n").encode("utf-8"))
                p.stdin.flush()
            except Exception:  # noqa: BLE001
                pass

    while True:
        chunk = p.stdout.read1(4096) if hasattr(p.stdout, "read1") else p.stdout.read(1)
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            raw, buf = buf.split(b"\n", 1)
            line = ANSI.sub("", raw.decode("utf-8", "replace")).replace("\r", "").strip()
            if not line:
                continue
            last = line
            low = line.lower()
            if "open this link" in low:
                expect = "url"
            m = URL.search(line)
            if url is None and m and (expect == "url" or "claude.ai/oauth" in m.group(0)):
                url = m.group(0).rstrip(".,)")
                emit("HL_URL", url)
                expect = None
                continue
            if "after authorizing" in low:
                emit("HL_STATE", "paste-code")
                continue
            emit("HL_RAW", line[:400])
        # The code prompt has no newline: spot it in the partial line left over
        # after the complete lines. Hermes blocks on input() until the code
        # arrives, so this must fire in the same chunk the prompt arrives in.
        if not prompted and PROMPT in buf:
            buf = buf[buf.find(PROMPT) + len(PROMPT):]
            forward_code()
    rc = p.wait()
    if rc == 0:
        emit("HL_DONE", "ok", last[:300])
    else:
        emit("HL_DONE", "fail", f"exit {rc}: {last[:300]}")
    return rc


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
