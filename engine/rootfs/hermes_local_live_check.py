#!/usr/bin/env python3
"""Hermes Local self-test for live voice (GPT-Live mode of the Hermes apps), with a stand-in "app".

The stand-in app does what the Hermes apps' GPT-Live mode does (apps/desktop/src/lib/voice-live.ts):
RTCPeerConnection + a microphone track (a 440 Hz tone) + data channel "oai-events", SDP offer ->
host -> answer, then it answers every session.delegation.created with session.commentary.append and
measures the audio it hears.

  live_check.py module-fake gemini|codex        in-process bridge against local fake upstreams
  live_check.py module-real gemini|codex        in-process bridge, dummy credentials, REAL Google/chatgpt.com
  live_check.py endpoint <base> <user> status   GET /api/audio/voice-live/status        (password on stdin)
  live_check.py endpoint <base> <user> session  POST a real WebRTC offer to /api/audio/voice-live/session
  live_check.py endpoint <base> <user> fake-gemini|fake-codex <port>
        start a fake upstream on 127.0.0.1:<port> (the engine's config must point gemini_url/codex_url at
        it), POST the offer through the dashboard, then run a whole conversation turn over WebRTC.
Prints "LIVE <name>: ok|fail|info <detail>" lines and "LIVE_RESULT ok|fail".
"""
import asyncio
import base64
import fractions
import json
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
try:
    import hermes_bootstrap  # noqa: F401
except ImportError:
    pass
LIBS = os.environ.get("HL_LIVE_LIBS", "/opt/hermes-local/live-libs")
if os.path.isdir(LIBS):
    sys.path.append(LIBS)

import av  # noqa: E402
from aiortc import MediaStreamTrack, RTCPeerConnection, RTCSessionDescription  # noqa: E402
from aiortc.mediastreams import MediaStreamError  # noqa: E402

failed = []


def line(name, verdict, detail=""):
    print(f"LIVE {name}: {verdict} {detail}"[:700], flush=True)
    if verdict == "fail":
        failed.append(name)


class Tone(MediaStreamTrack):
    kind = "audio"

    def __init__(self, hz=440.0, rate=48000):
        super().__init__()
        self.hz, self.rate, self.pts, self.t0 = hz, rate, 0, None

    async def recv(self):
        if self.readyState != "live":
            raise MediaStreamError
        n = self.rate // 50
        if self.t0 is None:
            self.t0 = time.time()
        wait = self.t0 + self.pts / self.rate - time.time()
        if wait > 0:
            await asyncio.sleep(wait)
        samples = bytearray()
        for i in range(n):
            v = int(9000 * math.sin(2 * math.pi * self.hz * (self.pts + i) / self.rate))
            samples += v.to_bytes(2, "little", signed=True)
        f = av.AudioFrame(format="s16", layout="mono", samples=n)
        f.planes[0].update(bytes(samples))
        f.sample_rate, f.pts, f.time_base = self.rate, self.pts, fractions.Fraction(1, self.rate)
        self.pts += n
        return f


def pcm_level(frame) -> int:
    data = bytes(frame.planes[0])
    peak = 0
    for i in range(0, min(len(data), 1920), 2):
        peak = max(peak, abs(int.from_bytes(data[i:i + 2], "little", signed=True)))
    return peak


class FakeApp:
    def __init__(self, replies=("It is sunny in Agra.", "Twenty eight degrees.")):
        self.pc = RTCPeerConnection()
        self.events = []
        self.loud = 0
        self.frames = 0
        self.replies = replies
        self.closed = asyncio.Event()
        self.pc.addTrack(Tone())
        self.dc = self.pc.createDataChannel("oai-events")
        self.dc.on("message", self._on_msg)

        @self.pc.on("track")
        def _t(track):
            asyncio.ensure_future(self._listen(track))

    async def _listen(self, track):
        while True:
            try:
                f = await track.recv()
            except Exception:  # noqa: BLE001
                return
            self.frames += 1
            if pcm_level(f) > 500:
                self.loud += 1

    def _on_msg(self, raw):
        ev = json.loads(raw)
        self.events.append(ev)
        if ev.get("type") == "session.delegation.created":
            did = ev["delegation"]["id"]
            self.send({"type": "session.thinking.append", "delegation_id": did, "content": "Hermes is checking."})
            for r in self.replies:
                self.send({"type": "session.commentary.append", "delegation_id": did, "content": r})
        if ev.get("type") == "session.closed":
            self.closed.set()

    def send(self, ev):
        if self.dc.readyState == "open":
            self.dc.send(json.dumps(ev))

    async def offer(self):
        await self.pc.setLocalDescription(await self.pc.createOffer())
        return self.pc.localDescription.sdp

    async def answer(self, sdp):
        await self.pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="answer"))

    def types(self):
        return [e.get("type") for e in self.events]

    async def wait_for(self, pred, timeout):
        t = time.time()
        while time.time() - t < timeout:
            if pred():
                return True
            await asyncio.sleep(0.1)
        return False


# ------------------------------------------------------------------ fake upstreams
class FakeGemini:
    """Minimal BidiGenerateContent: setupComplete, hears audio, transcribes, calls ask_hermes, speaks."""

    def __init__(self):
        self.setup = None
        self.audio_bytes = 0
        self.tool_response = None
        self.history = None
        self.stream_end = False

    async def handler(self, ws):
        self.setup = json.loads(await ws.recv())
        await ws.send(json.dumps({"setupComplete": {}}))
        asked = False
        async for raw in ws:
            msg = json.loads(raw)
            if "clientContent" in msg and self.history is None:
                self.history = msg["clientContent"]
            audio = ((msg.get("realtimeInput") or {}).get("audio") or {}).get("data")
            if (msg.get("realtimeInput") or {}).get("audioStreamEnd"):
                self.stream_end = True
            if audio:
                self.audio_bytes += len(base64.b64decode(audio))
                if self.audio_bytes > 16000 and not asked:  # 0.5 s of 16 kHz s16
                    asked = True
                    await ws.send(json.dumps({"serverContent": {"inputTranscription": {"text": "What is the weather?"}}}))
                    await ws.send(json.dumps({"toolCall": {"functionCalls": [
                        {"id": "fc-1", "name": "ask_hermes", "args": {"request": "What is the weather?"}}]}}))
            if "toolResponse" in msg:
                self.tool_response = msg["toolResponse"]
                tone = bytearray()
                for i in range(24000):  # 1 s at 24 kHz
                    tone += int(8000 * math.sin(2 * math.pi * 330 * i / 24000)).to_bytes(2, "little", signed=True)
                for k in range(0, len(tone), 4800):
                    await ws.send(json.dumps({"serverContent": {"modelTurn": {"parts": [{"inlineData": {
                        "mimeType": "audio/pcm;rate=24000", "data": base64.b64encode(bytes(tone[k:k + 4800])).decode()}}]}}}))
                await ws.send(json.dumps({"serverContent": {"outputTranscription": {"text": "It is sunny in Agra."}}}))
                await ws.send(json.dumps({"serverContent": {"turnComplete": True}}))

    async def start(self, port):
        import websockets
        self.server = await websockets.serve(self.handler, "127.0.0.1", port)


class FakeCodex:
    """chatgpt.com/backend-api/codex/realtime/calls stand-in: answers the SDP, speaks frameless v3 events."""

    def __init__(self):
        self.request = None
        self.headers = {}
        self.got = []
        self.mic_loud = 0
        self.pcs = []

    async def handle(self, request):
        from aiohttp import web
        self.headers = dict(request.headers)
        body = await request.json()
        self.request = body
        pc = RTCPeerConnection()
        self.pcs.append(pc)
        pc.addTrack(Tone(hz=300))

        @pc.on("track")
        def _t(track):
            async def listen():
                while True:
                    try:
                        f = await track.recv()
                    except Exception:  # noqa: BLE001
                        return
                    if pcm_level(f) > 500:
                        self.mic_loud += 1
                        if self.mic_loud == 25:
                            send({"type": "input_transcript.added", "item": {"text": "What is the weather?"}})
                            send({"type": "delegation.created", "item": {"id": "item_d1", "type": "delegation", "target": "client",
                                  "content": [{"type": "input_text", "text": "What is the weather?"}], "handoff_id": "handoff_1"}})
            asyncio.ensure_future(listen())

        chan = {}

        def send(ev):
            dc = chan.get("dc")
            if dc is not None and dc.readyState == "open":
                dc.send(json.dumps(ev))

        @pc.on("datachannel")
        def _dc(dc):
            chan["dc"] = dc
            dc.send(json.dumps({"type": "session.started", "session": {"id": "sess_fake"}}))

            @dc.on("message")
            def _m(raw):
                ev = json.loads(raw)
                self.got.append(ev)
                if ev.get("type") == "delegation.context.append":
                    send({"type": "output_transcript.added", "item": {"text": "It is sunny in Agra."}})

        await pc.setRemoteDescription(RTCSessionDescription(sdp=body["sdp"], type="offer"))
        await pc.setLocalDescription(await pc.createAnswer())
        return web.Response(status=201, text=pc.localDescription.sdp, content_type="application/sdp",
                            headers={"Location": "/v1/realtime/calls/rtc_fake123"})

    async def start(self, port):
        from aiohttp import web
        app = web.Application()
        app.router.add_post("/realtime/calls", self.handle)
        runner = web.AppRunner(app)
        await runner.setup()
        await web.TCPSite(runner, "127.0.0.1", port).start()


# ------------------------------------------------------------------ conversation checks
async def conversation(app: FakeApp, kind: str, fake, label: str):
    ok = await app.wait_for(lambda: "session.started" in app.types(), 20)
    line(f"{label} session.started on the app's data channel", "ok" if ok else "fail", f"events={app.types()[:6]}")
    ok = await app.wait_for(lambda: "session.delegation.created" in app.types(), 25)
    line(f"{label} delegation reaches the app (session.delegation.created)", "ok" if ok else "fail",
         json.dumps([e for e in app.events if e.get("type") == "session.delegation.created"][:1]))
    ok = await app.wait_for(lambda: "session.input_transcript.delta" in app.types(), 5)
    line(f"{label} user transcript (session.input_transcript.delta)", "ok" if ok else "fail")
    if kind == "gemini":
        ok = await app.wait_for(lambda: fake.tool_response is not None, 10)
        line(f"{label} Hermes reply -> Gemini toolResponse", "ok" if ok and "It is sunny in Agra. Twenty eight degrees." in json.dumps(fake.tool_response) else "fail",
             json.dumps(fake.tool_response)[:300])
        line(f"{label} mic audio reached Gemini as 16 kHz PCM", "ok" if fake.audio_bytes > 16000 else "fail", f"{fake.audio_bytes} bytes")
        sr = (fake.setup or {}).get("setup", {})
        line(f"{label} setup: model/tool/transcription", "ok" if sr.get("model", "").startswith("models/") and sr.get("tools") and "inputAudioTranscription" in sr else "fail",
             json.dumps({k: sr.get(k) for k in ("model", "generationConfig")})[:300])
    else:
        ok = await app.wait_for(lambda: any(e.get("type") == "delegation.context.append" for e in fake.got), 10)
        ctx = [e for e in fake.got if e.get("type") == "delegation.context.append"]
        line(f"{label} Hermes reply -> delegation.context.append (frameless v3)", "ok" if ok and ctx[0].get("delegation_item_id") == "item_d1" else "fail", json.dumps(ctx[:2])[:300])
        line(f"{label} mic audio relayed to the Codex peer", "ok" if fake.mic_loud >= 25 else "fail", f"{fake.mic_loud} loud frames")
        line(f"{label} session request (model/delegation/headers)", "ok" if (fake.request or {}).get("session", {}).get("model") and
             fake.headers.get("OpenAI-Alpha") == "quicksilver=v2" and fake.headers.get("ChatGPT-Account-Id") else "fail",
             json.dumps({"session": {k: v for k, v in (fake.request or {}).get("session", {}).items() if k != "instructions"},
                         "alpha": fake.headers.get("OpenAI-Alpha")})[:300])
    ok = await app.wait_for(lambda: "session.output_transcript.delta" in app.types(), 10)
    line(f"{label} assistant transcript (session.output_transcript.delta)", "ok" if ok else "fail")
    ok = await app.wait_for(lambda: app.loud >= 20, 15)
    line(f"{label} voice audio reaches the app's remote track", "ok" if ok else "fail", f"{app.loud} loud of {app.frames} frames")
    app.send({"type": "session.input_audio.mute", "event_id": "mute_1"})
    await asyncio.sleep(0.8)
    if kind == "gemini":
        line(f"{label} mute -> audioStreamEnd", "ok" if fake.stream_end else "fail")
    else:
        line(f"{label} mute -> input_audio.pause", "ok" if any(e.get("type") == "input_audio.pause" for e in fake.got) else "fail")
    app.send({"type": "session.close"})
    ok = await app.wait_for(lambda: app.closed.is_set(), 10)
    line(f"{label} session.close -> session.closed", "ok" if ok else "fail",
         json.dumps([e for e in app.events if e.get("type") == "session.closed"][:1]))
    errs = [e for e in app.events if e.get("type") == "error"]
    if errs:
        line(f"{label} error events", "info", json.dumps(errs)[:300])


async def module_mode(kind: str, real: bool):
    import hermes_local_live as hl
    app = FakeApp()
    sdp = await app.offer()
    history = [{"type": "message", "role": "user", "content": [{"type": "input_text", "text": "Earlier turn"}]}]
    if real:
        try:
            if kind == "gemini":
                await asyncio.to_thread(hl.start_gemini, sdp, history, "AIzaSyHermesLocalDummyKey0000000000000")
            else:
                jwt = _stub_jwt()
                await asyncio.to_thread(hl.start_codex, sdp, {"model": hl.DEFAULT_CODEX_MODEL, "instructions": "x",
                                         "audio": {"output": {"voice": "cove"}}, "delegation": {"type": "client"}}, jwt, "stub")
            line(f"real {kind} rejects dummy credentials", "fail", "session was accepted?!")
        except RuntimeError as exc:
            msg = str(exc)
            good = ("API key not valid" in msg) if kind == "gemini" else ("HTTP 401" in msg or "HTTP 403" in msg)
            line(f"real {kind} rejects dummy credentials (provider's own auth error)", "ok" if good else "fail", msg)
        return
    port = 18765 if kind == "gemini" else 18766
    fake = FakeGemini() if kind == "gemini" else FakeCodex()
    await fake.start(port)
    if kind == "gemini":
        res = await asyncio.to_thread(hl.start_gemini, sdp, history, "fake-key", "gemini-3.8-live", "Kore", f"ws://127.0.0.1:{port}/ws")
    else:
        res = await asyncio.to_thread(hl.start_codex, sdp, {"model": "gpt-live-1-codex", "instructions": "x",
                                      "audio": {"output": {"voice": "cove"}}, "delegation": {"type": "client"}},
                                      "tok", "acct", f"http://127.0.0.1:{port}/realtime/calls")
    line(f"fake {kind} session answer", "ok" if res["transport"]["sdp"].startswith("v=0") else "fail", res["session"]["id"])
    await app.answer(res["transport"]["sdp"])
    await conversation(app, kind, fake, f"fake {kind}")
    if kind == "gemini":
        line("fake gemini history seeded as clientContent", "ok" if fake.history and "Earlier turn" in json.dumps(fake.history) else "fail")
    await app.pc.close()


def _stub_jwt():
    b = lambda o: base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip("=")  # noqa: E731
    return b({"alg": "none"}) + "." + b({"exp": int(time.time()) + 86400, "https://api.openai.com/auth": {"chatgpt_account_id": "stub"}}) + ".sig"


async def endpoint_mode(base: str, user: str, action: str, port: int):
    import httpx
    password = sys.stdin.readline().rstrip("\r\n")
    http = httpx.AsyncClient(base_url=base, timeout=120, trust_env=False, headers={"Origin": base})
    providers = (await http.get("/api/auth/providers")).json()
    names = [p.get("name") for p in (providers.get("providers") if isinstance(providers, dict) else providers) or [] if isinstance(p, dict)]
    pname = "basic" if "basic" in names or not names else names[0]
    r = await http.post("/auth/password-login", json={"provider": pname, "username": user, "password": password})
    if r.status_code != 200:
        line("login", "fail", f"{r.status_code}")
        return
    r = await http.get("/api/audio/voice-live/status")
    print(f"LIVE_STATUS {r.text}", flush=True)
    if action == "status":
        return
    fake = None
    if action.startswith("fake-"):
        kind = action[5:]
        fake = FakeGemini() if kind == "gemini" else FakeCodex()
        await fake.start(port)
    app = FakeApp()
    sdp = await app.offer()
    t = time.time()
    r = await http.post("/api/audio/voice-live/session", json={"sdp": sdp, "history": [
        {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "Earlier turn"}]}]}, timeout=90)
    print(f"LIVE_SESSION {r.status_code} {round(time.time() - t, 1)}s {r.text[:600]}", flush=True)
    if r.status_code == 200 and fake is not None:
        body = r.json()
        await app.answer(body["transport"]["sdp"])
        await conversation(app, action[5:], fake, f"endpoint {action[5:]}")
    await app.pc.close()


def main():
    mode = sys.argv[1]
    if mode in ("module-fake", "module-real"):
        asyncio.run(module_mode(sys.argv[2], mode == "module-real"))
    elif mode == "endpoint":
        asyncio.run(endpoint_mode(sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5]) if len(sys.argv) > 5 else 18770))
    print(f"LIVE_RESULT {'fail ' + ','.join(failed) if failed else 'ok'}", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
