#!/usr/bin/env python3
"""Hermes Local: Nous Portal sign-in through Hermes' own device-code login.

Runs `hermes auth add nous --type oauth --no-browser` (Hermes v0.23.2:
hermes_cli/auth_nous.py::_nous_device_code_login, shared printer
hermes_cli/auth_device_flow.py::_print_device_code_instructions) and turns its
human output into lines the app can rely on:

  HL_URL <url>       the device page (the portal's verification URL)
  HL_CODE <code>     the one-time code, e.g. ABCD-EFGH
  HL_STATE <text>    progress
  HL_DONE ok|fail <detail>
  HL_RAW <text>      every other line, ANSI stripped

Hermes prints the URL and the code on the same lines as their labels:

  To continue:
    1. Open: <url>
    2. If prompted, enter code: <code>

so this script reads the code off the "enter code:" line instead of expecting
it alone on a line (the Codex helper's shape). stdin is DEVNULL: when a shared
Nous credential already exists, Hermes asks "Import these credentials? [Y/n]:"
and EOF counts as yes, so a re-login imports it and finishes without a
URL/code. The device code polls until it expires (usually 15 minutes).
"""
import os
import re
import subprocess
import sys

ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")
URL = re.compile(r"https://[^\s\"'<>]+")
CODE = re.compile(r"^[A-Z0-9]{4,}(?:-[A-Z0-9]{4,})+$")
ENTER_CODE = re.compile(r"enter code:\s*(\S+)", re.IGNORECASE)


def emit(*parts):
    print(" ".join(parts), flush=True)


def main():
    env = dict(os.environ, PYTHONUNBUFFERED="1", NO_COLOR="1", BROWSER="echo", TERM="dumb")
    cmd = ["hermes", "auth", "add", "nous", "--type", "oauth", "--no-browser"]
    p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
                         bufsize=0)
    url = code = None
    last = ""
    buf = b""
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
            m = URL.search(line)
            if url is None and m and "open:" in low:
                url = m.group(0).rstrip(".,)")
                emit("HL_URL", url)
                continue
            cm = ENTER_CODE.search(line)
            if code is None and cm:
                cand = cm.group(1).rstrip(".,)")
                if CODE.match(cand):
                    code = cand
                    emit("HL_CODE", code)
                    continue
            if "waiting for approval" in low:
                emit("HL_STATE", "waiting")
            emit("HL_RAW", line[:400])
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
