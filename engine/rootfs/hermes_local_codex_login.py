#!/usr/bin/env python3
"""Hermes Local: Codex (ChatGPT) sign-in through Hermes' own device-code login.

Runs `hermes auth add openai-codex --type oauth --no-browser` (Hermes v0.23.0:
hermes_cli/auth_codex.py::_codex_device_code_login) and turns its human output into lines the
app can rely on:

  HL_URL <url>       the device page (https://auth.openai.com/codex/device)
  HL_CODE <code>     the one-time code, e.g. 2BNU-H1QFC
  HL_STATE <text>    progress
  HL_DONE ok|fail <detail>
  HL_RAW <text>      every other line, ANSI stripped

Hermes prints the URL and the code each on its own line wrapped in ANSI colour
(`\x1b[94m...\x1b[0m`) after "Open this URL" / "Enter this code" lines; see
android/app/src/test/resources/captures/ for real captures.
"""
import os
import re
import subprocess
import sys

ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")
URL = re.compile(r"https://[^\s\"'<>]+")
CODE = re.compile(r"^[A-Z0-9]{4,}(?:-[A-Z0-9]{4,})+$")


def emit(*parts):
    print(" ".join(parts), flush=True)


def main():
    env = dict(os.environ, PYTHONUNBUFFERED="1", NO_COLOR="1", BROWSER="echo", TERM="dumb")
    cmd = ["hermes", "auth", "add", "openai-codex", "--type", "oauth", "--no-browser"]
    p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
                         bufsize=0)
    url = code = None
    expect = None  # "url" | "code": the next non-empty line carries it
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
            if "open this url" in low:
                expect = "url"
            elif "enter this code" in low:
                expect = "code"
            m = URL.search(line)
            if url is None and m and (expect == "url" or "/device" in m.group(0)):
                url = m.group(0).rstrip(".,)")
                emit("HL_URL", url)
                expect = None
                continue
            if code is None and CODE.match(line) and (expect == "code" or url):
                code = line
                emit("HL_CODE", code)
                expect = None
                continue
            if "waiting for sign-in" in low:
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
