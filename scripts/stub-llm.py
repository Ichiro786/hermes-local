#!/usr/bin/env python3
"""A fake model server for tests: OpenAI chat completions (+SSE) and the Responses API (+SSE),
which is what Hermes' Codex transport speaks. Every answer is STUB_REPLY.

  stub-llm.py [port]     (default 18080; logs each request path to stderr)
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLY = "Hello from the Hermes Local stub model."
MODELS = ["stub-model", "gpt-5.5", "gpt-6-sol", "gemini-3.8-flash"]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("stub-llm: " + (fmt % args) + "\n")

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _sse_start(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

    def _sse(self, obj, event=None):
        data = (f"event: {event}\n" if event else "") + "data: " + json.dumps(obj) + "\n\n"
        self.wfile.write(data.encode())
        self.wfile.flush()

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            return self._json(200, {"object": "list", "data": [{"id": m, "object": "model", "owned_by": "stub"} for m in MODELS],
                                    "models": [{"slug": m, "id": m, "display_name": m} for m in MODELS]})
        return self._json(200, {"ok": True})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            req = json.loads(self.rfile.read(n) or b"{}")
        except Exception:  # noqa: BLE001
            req = {}
        model = req.get("model") or "stub-model"
        now = int(time.time())
        if self.path.rstrip("/").endswith("/chat/completions"):
            if not req.get("stream"):
                return self._json(200, {"id": "chatcmpl-stub", "object": "chat.completion", "created": now, "model": model,
                                        "choices": [{"index": 0, "finish_reason": "stop",
                                                     "message": {"role": "assistant", "content": REPLY}}],
                                        "usage": {"prompt_tokens": 5, "completion_tokens": 8, "total_tokens": 13}})
            self._sse_start()
            base = {"id": "chatcmpl-stub", "object": "chat.completion.chunk", "created": now, "model": model}
            self._sse({**base, "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}]})
            for word in REPLY.split(" "):
                self._sse({**base, "choices": [{"index": 0, "delta": {"content": word + " "}, "finish_reason": None}]})
            self._sse({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                       "usage": {"prompt_tokens": 5, "completion_tokens": 8, "total_tokens": 13}})
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            self.close_connection = True
            return
        if self.path.rstrip("/").endswith("/responses"):
            rid, mid = "resp_stub", "msg_stub"
            item = {"id": mid, "type": "message", "role": "assistant", "status": "completed",
                    "content": [{"type": "output_text", "text": REPLY, "annotations": []}]}
            resp = {"id": rid, "object": "response", "created_at": now, "model": model, "status": "completed",
                    "output": [item], "usage": {"input_tokens": 5, "output_tokens": 8, "total_tokens": 13}}
            if not req.get("stream"):
                return self._json(200, resp)
            self._sse_start()
            self._sse({"type": "response.created", "response": {**resp, "status": "in_progress", "output": []}}, "response.created")
            self._sse({"type": "response.output_item.added", "output_index": 0,
                       "item": {**item, "status": "in_progress", "content": []}}, "response.output_item.added")
            self._sse({"type": "response.content_part.added", "item_id": mid, "output_index": 0, "content_index": 0,
                       "part": {"type": "output_text", "text": ""}}, "response.content_part.added")
            for word in REPLY.split(" "):
                self._sse({"type": "response.output_text.delta", "item_id": mid, "output_index": 0, "content_index": 0,
                           "delta": word + " "}, "response.output_text.delta")
            self._sse({"type": "response.output_text.done", "item_id": mid, "output_index": 0, "content_index": 0,
                       "text": REPLY}, "response.output_text.done")
            self._sse({"type": "response.output_item.done", "output_index": 0, "item": item}, "response.output_item.done")
            self._sse({"type": "response.completed", "response": resp}, "response.completed")
            self.close_connection = True
            return
        return self._json(404, {"error": {"message": f"stub: no route {self.path}"}})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
