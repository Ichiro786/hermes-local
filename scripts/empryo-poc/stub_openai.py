#!/usr/bin/env python3
"""Tiny OpenAI Chat Completions stub (streaming + non-streaming) for Empryo PoC runs.
Turn 1: if a shell-like tool is offered, call it with `echo EMPRYO_SHELL_OK $(uname -m)`.
After a tool result: reply with text. No credentials, localhost only.
Usage: stub_openai.py PORT LOGFILE [BIND_ADDR]
"""
import json, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]); LOG = open(sys.argv[2], "a", buffering=1)
SHELL_NAMES = ("shell", "bash", "run_command", "exec", "terminal", "run_shell", "command")

def pick_shell(tools):
    for t in tools or []:
        f = t.get("function", t)
        n = f.get("name", "")
        if n.lower() in SHELL_NAMES:
            props = (f.get("parameters") or {}).get("properties", {})
            key = "command" if "command" in props else next((k for k, v in props.items() if v.get("type") == "string"), "command")
            return n, key
    return None, None

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code)
        self.send_header("content-type", "application/json"); self.send_header("content-length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        LOG.write(f"GET {self.path}\n")
        if self.path.rstrip("/").endswith("models"):
            return self._send(200, {"object": "list", "data": [{"id": "stub-1", "object": "model", "owned_by": "stub"}]})
        self._send(404, {"error": "nope"})
    def do_POST(self):
        n = int(self.headers.get("content-length", 0)); raw = self.rfile.read(n)
        enc = (self.headers.get("content-encoding") or "identity").lower()
        if enc != "identity":  # Empryo compresses requests to non-local hosts; a refusal makes it resend plain
            LOG.write(f"POST {self.path} refused content-encoding={enc}\n")
            return self._send(415, {"error": {"message": f"unsupported content-encoding {enc}"}})
        body = json.loads(raw or b"{}")
        msgs = body.get("messages", []); tools = body.get("tools", [])
        names = [ (t.get("function", t)).get("name") for t in tools ]
        has_tool_result = any(m.get("role") == "tool" for m in msgs)
        tool_text = " | ".join(str(m.get("content"))[:200] for m in msgs if m.get("role") == "tool")
        LOG.write(f"POST {self.path} stream={body.get('stream')} msgs={len(msgs)} tools={len(tools)} names={names[:60]} tool_result={tool_text!r}\n")
        sname, skey = pick_shell(tools)
        if sname and not has_tool_result:
            args = json.dumps({skey: "echo EMPRYO_SHELL_OK $(uname -m) $(id -u)"})
            delta = {"role": "assistant", "content": None, "tool_calls": [{"index": 0, "id": "call_1", "type": "function", "function": {"name": sname, "arguments": args}}]}
            finish = "tool_calls"
        else:
            delta = {"role": "assistant", "content": "STUB_REPLY_OK" + (f" saw: {tool_text[:120]}" if has_tool_result else "")}
            finish = "stop"
        if body.get("stream"):
            self.send_response(200); self.send_header("content-type", "text/event-stream"); self.end_headers()
            base = {"id": "c1", "object": "chat.completion.chunk", "created": int(time.time()), "model": body.get("model", "stub-1")}
            for ch in ({**base, "choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
                       {**base, "choices": [{"index": 0, "delta": {}, "finish_reason": finish}], "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}):
                self.wfile.write(f"data: {json.dumps(ch)}\n\n".encode()); self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
        else:
            msg = {k: v for k, v in delta.items()}
            self._send(200, {"id": "c1", "object": "chat.completion", "created": int(time.time()), "model": body.get("model"),
                             "choices": [{"index": 0, "message": msg, "finish_reason": finish}], "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}})

ThreadingHTTPServer((sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1", PORT), H).serve_forever()
