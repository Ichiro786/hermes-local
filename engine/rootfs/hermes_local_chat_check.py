#!/usr/bin/env python3
"""Hermes Local self-test: one chat turn through the dashboard, the way the Hermes apps do it.

  hermes_local_chat_check.py <base url> <username> [prompt]      password on stdin

Signs in (POST /auth/password-login), mints a WebSocket ticket (POST /api/auth/ws-ticket), opens
/api/ws (JSON-RPC), creates a session, submits the prompt and prints every frame. Exit 0 when the
assistant streamed text and the turn completed; prints `CHAT_OK <reply>` or `CHAT_FAIL <why>`.
"""
import json
import os
import sys
import time

sys.path.insert(0, os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
try:
    import hermes_bootstrap  # noqa: F401
except ImportError:
    pass
import httpx
from websockets.sync.client import connect

base = sys.argv[1].rstrip("/")
user = sys.argv[2]
prompt = sys.argv[3] if len(sys.argv) > 3 else "Say hello in five words."
password = sys.stdin.readline().rstrip("\r\n")
TIMEOUT = float(os.environ.get("HL_CHAT_TIMEOUT", "180"))


def fail(why):
    print("CHAT_FAIL", why, flush=True)
    sys.exit(1)


with httpx.Client(base_url=base, timeout=30, trust_env=False) as http:
    providers = http.get("/api/auth/providers").json()
    names = [p.get("name") for p in (providers.get("providers") if isinstance(providers, dict) else providers) or []
             if isinstance(p, dict)]
    pname = "basic" if "basic" in names or not names else names[0]
    r = http.post("/auth/password-login", json={"provider": pname, "username": user, "password": password})
    if r.status_code != 200:
        fail(f"login {r.status_code} {r.text[:200]}")
    r = http.post("/api/auth/ws-ticket", headers={"Origin": base})
    if r.status_code != 200:
        fail(f"ws-ticket {r.status_code} {r.text[:200]}")
    ticket = r.json().get("ticket")
    status = http.get("/api/model/info")
    print("MODEL_INFO", status.status_code, status.text[:300], flush=True)

ws_url = base.replace("http", "ws", 1) + "/api/ws?ticket=" + ticket
reply, done, rid = [], False, 0
deadline = time.monotonic() + TIMEOUT


def call(ws, method, params):
    global rid
    rid += 1
    ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}))
    want = rid
    while time.monotonic() < deadline:
        msg = json.loads(ws.recv(timeout=max(1, deadline - time.monotonic())))
        print("FRAME", json.dumps(msg)[:500], flush=True)
        if msg.get("id") == want:
            if "error" in msg:
                fail(f"{method}: {msg['error']}")
            return msg.get("result") or {}
        handle(msg)
    fail(f"{method}: timeout")


def handle(msg):
    global done
    params = msg.get("params") or {}
    typ = str(params.get("type") or msg.get("method") or "")
    payload = params.get("payload") if isinstance(params.get("payload"), dict) else params
    if typ in ("message.delta", "message.chunk", "text.delta"):
        reply.append(str(payload.get("text") or payload.get("delta") or ""))
    elif typ in ("message.complete", "turn.complete", "prompt.complete"):
        text = payload.get("text") or payload.get("content")
        if isinstance(text, str) and text and not reply:
            reply.append(text)
        done = True
    elif typ in ("error", "turn.error", "session.error", "message.error"):
        fail(f"{typ}: {json.dumps(payload)[:400]}")


with connect(ws_url, open_timeout=30, max_size=None, origin=base, proxy=None) as ws:
    created = call(ws, "session.create", {})
    sid = created.get("session_id") or created.get("id") or (created.get("session") or {}).get("id")
    if not sid:
        fail(f"session.create returned {created}")
    call(ws, "prompt.submit", {"session_id": sid, "text": prompt})
    while not done and time.monotonic() < deadline:
        try:
            msg = json.loads(ws.recv(timeout=max(1, deadline - time.monotonic())))
        except TimeoutError:
            break
        print("FRAME", json.dumps(msg)[:500], flush=True)
        handle(msg)

text = "".join(reply).strip()
if done and text:
    print("CHAT_OK", text, flush=True)
    sys.exit(0)
fail(f"turn did not complete (done={done}, text={text[:100]!r})")
