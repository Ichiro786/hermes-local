"""Hermes Local: live voice backends for the official Hermes apps' GPT-Live mode.

The Hermes apps' GPT-Live mode (desktop renderer apps/desktop/src/lib/voice-live.ts; the Android app
speaks the same host API) does: RTCPeerConnection + mic track + data channel "oai-events" -> POST the
SDP offer to /api/audio/voice-live/session -> setRemoteDescription(answer). Events on the data
channel follow the OpenAI Live API (session.started, session.input_transcript.delta,
session.delegation.created ...; the app answers delegations with session.commentary.append).

Hermes v0.23.0 can only hand that offer to api.openai.com/v1/live/sessions with an OpenAI API key.
This module adds two more backends behind the SAME host endpoint, so the app needs no change:

  codex   ChatGPT/Codex sign-in (no API key). OpenAI answers a Codex OAuth token on /v1/live/sessions
          with 403 "Voice session access denied" (NousResearch/hermes-agent#136326), but the Codex
          subscription route chatgpt.com/backend-api/codex/realtime/calls runs gpt-live-1-codex.
          That route speaks a different data-channel protocol ("frameless v3": input_transcript.added,
          delegation.created, delegation.context.append, input_audio.pause ...), and the official
          mobile apps fail on it (#136326 field report: 'Invalid value: session.input_audio.mute').
          So the host terminates the app's WebRTC itself (aiortc), opens its own WebRTC call to
          the Codex route, relays audio both ways and translates the events.
  gemini  Gemini API key. The host terminates the app's WebRTC, streams the mic as 16 kHz PCM to the
          Gemini Live API (gemini-3.8-live, BidiGenerateContent WebSocket) and plays Gemini's 24 kHz
          audio back on the app's track; Hermes delegation = a Gemini function call (ask_hermes),
          answered with what the app sends as session.commentary.append.
  openai  the stock Hermes path (direct OpenAI API key, app <-> OpenAI media directly).

Selection: voice.gpt_live.backend in config.yaml = auto (default) | openai | codex | gemini.
auto picks codex, then openai, then gemini - whichever has credentials. Optional: codex_model,
codex_voice, gemini_model, gemini_voice; codex_url / gemini_url only point the smoke tests at local fakes.

Installed by a two-line hook at the end of tools/voice_live.py (engine/build-rootfs.sh), which calls
install(globals()) to wrap resolve_gpt_live_status and create_webrtc_session.
"""
from __future__ import annotations

import asyncio
import base64
import fractions
import json
import logging
import os
import socket
import sys
import threading
import time
import uuid
from typing import Any, Callable, Optional

log = logging.getLogger("hermes_local.live")

LIBS = os.environ.get("HL_LIVE_LIBS", "/opt/hermes-local/live-libs")
GEMINI_WS_URL = os.environ.get(
    "HL_GEMINI_LIVE_URL",
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
CODEX_LIVE_URL = os.environ.get(
    "HL_CODEX_LIVE_URL", "https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas")
DEFAULT_GEMINI_MODEL = "gemini-3.8-live"
DEFAULT_GEMINI_VOICE = "Kore"
DEFAULT_CODEX_MODEL = "gpt-live-1-codex"
DEFAULT_CODEX_VOICE = "cove"
BACKENDS = ("codex", "openai", "gemini")  # auto order
CONNECT_WATCHDOG_S = 45.0          # app never connected its WebRTC -> drop the upstream session
DELEGATION_TIMEOUT_S = 180.0       # Hermes never answered a Gemini function call
COMMENTARY_SETTLE_S = 0.7          # the app sends a reply as several append chunks back to back

GEMINI_PERSONA = (
    "You are Hermes, a calm and friendly voice assistant. Speak naturally at an unhurried pace, in the "
    "language the user speaks. Be clear and direct, not overly cheerful.\n\n"
    "You have one tool, ask_hermes: Hermes is a full AI agent with tools. It can run commands, read and edit "
    "files, browse the web, search, remember things across sessions, schedule tasks and reason carefully. "
    "It is the one who actually does work and knows facts.\n"
    "Call ask_hermes when the user asks a question that needs facts, current information or careful "
    "reasoning, or asks you to do, check, find, make, fix, run or remember anything, or corrects work "
    "already requested. Pass the request in the user's own words.\n"
    "Do not call it for greetings, small talk, or to repeat a result you already have; ask a short "
    "clarifying question yourself when needed.\n"
    "Before calling it, say briefly that you are checking. Never guess the result. When the result comes "
    "back, say it in a few plain conversational sentences: no markdown, no lists, no URLs read out.")

_ASK_HERMES = {
    "name": "ask_hermes",
    "description": "Hand the user's request to Hermes, the user's AI agent with tools, and get its answer.",
    "parameters": {"type": "OBJECT", "properties": {
        "request": {"type": "STRING", "description": "The user's request, in their own words."}},
        "required": ["request"]},
}


# ---------------------------------------------------------------- credentials and selection
def _env_value(name: str) -> str:
    try:
        from hermes_cli.config import get_env_value
        v = get_env_value(name)
    except Exception:  # noqa: BLE001
        v = os.environ.get(name)
    return str(v or "").strip()


def gemini_key() -> str:
    return _env_value("GEMINI_API_KEY") or _env_value("GOOGLE_API_KEY")


def codex_credentials(read_only: bool = True) -> tuple[str, str]:
    """(access token, ChatGPT account id) from Hermes' own Codex sign-in; ValueError when missing."""
    from hermes_cli.auth_codex import resolve_codex_runtime_credentials
    from hermes_cli.auth_constants import _decode_jwt_claims
    try:
        creds = resolve_codex_runtime_credentials(refresh_if_expiring=not read_only, read_only=read_only)
    except Exception:  # noqa: BLE001 - AuthError and friends: not signed in
        raise ValueError("not signed in to ChatGPT (Codex)") from None
    token = str(creds.get("api_key") or "").strip()
    claims = _decode_jwt_claims(token).get("https://api.openai.com/auth", {}) if token else {}
    account = claims.get("chatgpt_account_id") if isinstance(claims, dict) else None
    if not token or not isinstance(account, str) or not account.strip() or any(c in token + account for c in "\r\n"):
        raise ValueError("the Codex sign-in has no ChatGPT account identity")
    return token, account.strip()


def _codex_ok() -> Optional[str]:
    try:
        codex_credentials(read_only=True)
        return None
    except ValueError as exc:
        return str(exc)


def _selected(live: dict) -> str:
    v = str(live.get("backend") or "auto").strip().lower()
    return v if v in BACKENDS else "auto"


def install(g: dict) -> None:
    """Wrap tools.voice_live's status + session functions (g = that module's globals())."""
    if g.get("_hermes_local_live"):
        return
    orig_status: Callable[[], dict] = g["resolve_gpt_live_status"]
    orig_create: Callable[..., dict] = g["create_webrtc_session"]
    live_section: Callable[[], dict] = g["_live_section"]
    persona: str = g.get("LIVE_PERSONA", "")

    def availability() -> tuple[dict, dict, str]:
        live = live_section()
        base = orig_status()
        reasons = {
            "openai": None if base.get("available") else (base.get("reason") or "no OpenAI API key"),
            "codex": _codex_ok(),
            "gemini": None if gemini_key() else "no Gemini API key",
        }
        return base, reasons, _selected(live)

    def pick(reasons: dict, selected: str) -> tuple[Optional[str], Optional[str]]:
        if selected != "auto":
            return (selected, None) if reasons[selected] is None else (None, f"live voice is set to {selected}, but: {reasons[selected]}")
        for b in BACKENDS:
            if reasons[b] is None:
                return b, None
        return None, "no live voice credentials: sign in to ChatGPT (Codex), or add an OpenAI or Gemini API key in Hermes Local > Providers"

    def resolve_gpt_live_status() -> dict:
        base, reasons, selected = availability()
        backend, reason = pick(reasons, selected)
        live = live_section()
        out = dict(base)
        out.update({"available": backend is not None, "reason": reason, "backend": backend or "none",
                    "selected": selected, "backends": [b for b in BACKENDS if reasons[b] is None]})
        if backend == "codex":
            out.update(model=str(live.get("codex_model") or DEFAULT_CODEX_MODEL), voice=str(live.get("codex_voice") or DEFAULT_CODEX_VOICE))
        elif backend == "gemini":
            out.update(model=str(live.get("gemini_model") or DEFAULT_GEMINI_MODEL), voice=str(live.get("gemini_voice") or DEFAULT_GEMINI_VOICE))
        return out

    def create_webrtc_session(sdp_offer: str, history: Optional[list] = None, **kw) -> dict:
        _base, reasons, selected = availability()
        backend, reason = pick(reasons, selected)
        if backend is None:
            raise ValueError(reason)
        if backend == "openai":
            return orig_create(sdp_offer, history, **kw)
        live = live_section()
        if backend == "codex":
            token, account = codex_credentials(read_only=False)
            session = {
                "model": str(live.get("codex_model") or DEFAULT_CODEX_MODEL),
                "instructions": persona,
                "audio": {"output": {"voice": str(live.get("codex_voice") or DEFAULT_CODEX_VOICE)}},
                "delegation": {"type": "client", "ack_filler": True},
            }
            items = _history_items(history)
            if items:
                session["initial_items"] = items
            return start_codex(sdp_offer, session, token, account, url=str(live.get("codex_url") or CODEX_LIVE_URL))
        return start_gemini(sdp_offer, history, gemini_key(), str(live.get("gemini_model") or DEFAULT_GEMINI_MODEL),
                            str(live.get("gemini_voice") or DEFAULT_GEMINI_VOICE), url=str(live.get("gemini_url") or GEMINI_WS_URL))

    g["resolve_gpt_live_status"] = resolve_gpt_live_status
    g["create_webrtc_session"] = create_webrtc_session
    g["_hermes_local_live"] = True


def _history_items(history: Optional[list]) -> list:
    out = []
    for item in history or []:
        if not isinstance(item, dict) or item.get("role") not in ("user", "assistant", "developer"):
            continue
        texts = [c.get("text") for c in item.get("content") or [] if isinstance(c, dict) and isinstance(c.get("text"), str)]
        text = " ".join(t for t in texts if t).strip()
        if text:
            kind = "output_text" if item["role"] == "assistant" else "input_text"
            out.append({"type": "message", "role": item["role"], "content": [{"type": kind, "text": text[:1200]}]})
    return out[-24:]


# ---------------------------------------------------------------- event loop + libraries
_loop: Optional[asyncio.AbstractEventLoop] = None
_loop_lock = threading.Lock()
_sessions: dict[str, "_Session"] = {}


def _run(coro, timeout: float):
    global _loop
    with _loop_lock:
        if _loop is None:
            _loop = asyncio.new_event_loop()
            threading.Thread(target=_loop.run_forever, name="hermes-local-live", daemon=True).start()
    return asyncio.run_coroutine_threadsafe(coro, _loop).result(timeout)


_libs_ready = False


def _libs() -> None:
    """aiortc/av live in their own --target dir (engine build), appended so Hermes' packages win."""
    global _libs_ready
    if _libs_ready:
        return
    if os.path.isdir(LIBS) and LIBS not in sys.path:
        sys.path.append(LIBS)
    import aioice.ice as ice  # noqa: PLC0415
    orig = ice.get_host_addresses

    def host_addresses(use_ipv4: bool, use_ipv6: bool) -> list:
        # Android blocks RTM_GETLINK for apps (targetSdk >= 30), so glibc getifaddrs() inside proot can
        # fail or come back empty. Add the address the kernel routes from, plus loopback (app and host
        # share the phone).
        try:
            addrs = list(orig(use_ipv4, use_ipv6))
        except Exception:  # noqa: BLE001
            addrs = []
        if use_ipv4:
            for probe in ("8.8.8.8", "192.168.0.1", "10.0.0.1"):
                try:
                    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                        s.connect((probe, 9))
                        ip = s.getsockname()[0]
                    if ip and ip not in addrs and not ip.startswith("0."):
                        addrs.append(ip)
                        break
                except OSError:
                    continue
            if "127.0.0.1" not in addrs:
                addrs.append("127.0.0.1")
        return addrs

    ice.get_host_addresses = host_addresses
    _libs_ready = True


# ---------------------------------------------------------------- audio tracks
def _make_tracks():
    import av  # noqa: PLC0415
    from aiortc import MediaStreamTrack  # noqa: PLC0415
    from aiortc.mediastreams import MediaStreamError  # noqa: PLC0415

    class PcmTrack(MediaStreamTrack):
        """Paced 20 ms frames of mono s16 PCM from a byte buffer; silence when the buffer is empty."""
        kind = "audio"

        def __init__(self, rate: int, max_seconds: float):
            super().__init__()
            self.rate, self.buf, self.max_bytes = rate, bytearray(), int(rate * 2 * max_seconds)
            self._start: Optional[float] = None
            self._pts = 0
            self.frames_out = 0
            self.loud_frames = 0

        def write(self, data: bytes) -> None:
            self.buf += data
            if len(self.buf) > self.max_bytes:  # drift / runaway: keep the newest audio
                del self.buf[: len(self.buf) - self.max_bytes]

        def clear(self) -> None:
            self.buf.clear()

        async def recv(self):
            if self.readyState != "live":
                raise MediaStreamError
            n = self.rate // 50
            if self._start is None:
                self._start = time.time()
            else:
                wait = self._start + self._pts / self.rate - time.time()
                if wait > 0:
                    await asyncio.sleep(wait)
            chunk = bytes(self.buf[: 2 * n])
            del self.buf[: 2 * n]
            if chunk.strip(b"\0"):
                self.loud_frames += 1
            if len(chunk) < 2 * n:
                chunk += b"\0" * (2 * n - len(chunk))
            frame = av.AudioFrame(format="s16", layout="mono", samples=n)
            frame.planes[0].update(chunk)
            frame.sample_rate = self.rate
            frame.pts = self._pts
            frame.time_base = fractions.Fraction(1, self.rate)
            self._pts += n
            self.frames_out += 1
            return frame

    async def pump(track, rate: int, sink: Callable[[bytes], Any], stop: asyncio.Event, stats: dict) -> None:
        """Remote track -> mono s16 PCM at `rate` -> sink(bytes)."""
        rs = av.AudioResampler(format="s16", layout="mono", rate=rate)
        while not stop.is_set():
            try:
                frame = await track.recv()
            except MediaStreamError:
                break
            except Exception as exc:  # noqa: BLE001
                log.warning("live: audio pump stopped: %s", exc)
                break
            for out in rs.resample(frame):
                data = bytes(out.planes[0])[: out.samples * 2]
                stats["bytes"] = stats.get("bytes", 0) + len(data)
                if data.strip(b"\0"):
                    stats["loud"] = stats.get("loud", 0) + 1
                r = sink(data)
                if asyncio.iscoroutine(r):
                    await r

    return PcmTrack, pump


# ---------------------------------------------------------------- sessions
class _Session:
    def __init__(self, kind: str):
        self.kind = kind
        self.id = f"hl_{kind}_{uuid.uuid4().hex[:16]}"
        self.started = time.monotonic()
        self.stop = asyncio.Event()
        self.app_pc = None
        self.app_dc = None
        self.pending_app: list[dict] = []
        self.tasks: list[asyncio.Task] = []
        self.stats: dict = {"mic": {}, "speaker": {}}
        self.closed = False

    def ms(self) -> int:
        return int((time.monotonic() - self.started) * 1000)

    def to_app(self, event: dict) -> None:
        if self.app_dc is not None and self.app_dc.readyState == "open":
            try:
                self.app_dc.send(json.dumps(event))
            except Exception:  # noqa: BLE001
                pass
        else:
            self.pending_app.append(event)

    def spawn(self, coro) -> None:
        self.tasks.append(asyncio.ensure_future(coro))

    async def close(self, reason: str) -> None:
        if self.closed:
            return
        self.closed = True
        log.info("live: %s session %s closed (%s) stats=%s", self.kind, self.id, reason, self.stats)
        self.to_app({"type": "session.closed", "reason": reason,
                     "usage": {"seconds": round(time.monotonic() - self.started, 1)}})
        await asyncio.sleep(0.2)
        self.stop.set()
        await self.close_upstream()
        if self.app_pc is not None:
            await self.app_pc.close()
        for t in self.tasks:
            if not t.done():
                t.cancel()
        _sessions.pop(self.id, None)

    async def close_upstream(self) -> None:  # overridden
        return None

    def on_app_event(self, event: dict) -> None:  # overridden
        return None

    async def attach_app(self, offer_sdp: str, out_track, on_mic: Callable) -> str:
        """Answer the app's offer: our audio track out, its mic in, its oai-events channel."""
        from aiortc import RTCPeerConnection, RTCSessionDescription  # noqa: PLC0415
        pc = RTCPeerConnection()
        self.app_pc = pc
        pc.addTrack(out_track)

        @pc.on("track")
        def _on_track(track):
            if track.kind == "audio":
                self.spawn(on_mic(track))

        @pc.on("datachannel")
        def _on_dc(channel):
            self.app_dc = channel

            @channel.on("message")
            def _msg(message):
                try:
                    event = json.loads(message)
                except (TypeError, ValueError):
                    return
                if isinstance(event, dict):
                    if event.get("type") == "session.close":
                        asyncio.ensure_future(self.close("client_closed"))
                    else:
                        self.on_app_event(event)

            @channel.on("close")
            def _closed():
                asyncio.ensure_future(self.close("connection_lost"))

            self.to_app({"type": "session.started", "session": {"id": self.id}})
            pending, self.pending_app = self.pending_app, []
            for ev in pending:
                self.to_app(ev)

        @pc.on("connectionstatechange")
        async def _state():
            log.info("live: %s app peer %s", self.id, pc.connectionState)
            if pc.connectionState in ("failed", "closed"):
                await self.close("connection_lost")

        await pc.setRemoteDescription(RTCSessionDescription(sdp=offer_sdp, type="offer"))
        await pc.setLocalDescription(await pc.createAnswer())
        self.spawn(self._watchdog())
        return pc.localDescription.sdp

    async def _watchdog(self) -> None:
        await asyncio.sleep(CONNECT_WATCHDOG_S)
        if self.app_dc is None and not self.closed:
            await self.close("app_never_connected")


async def _replace_previous() -> None:
    # One live call at a time: a new call ends any older one (no orphaned upstream sessions).
    for s in list(_sessions.values()):
        await s.close("replaced")


# ---------------------------------------------------------------- Gemini Live
class _GeminiSession(_Session):
    def __init__(self):
        super().__init__("gemini")
        self.ws = None
        self.muted = False
        self.calls: dict[str, dict] = {}   # function call id -> {"name", "texts", "timer"}
        self.mic_buf = bytearray()

    async def open_upstream(self, key: str, model: str, voice: str, history: Optional[list], base_url: str) -> None:
        import websockets  # noqa: PLC0415
        url = f"{base_url}?key={key}"
        try:
            self.ws = await websockets.connect(url, open_timeout=20, max_size=None, proxy=None)
        except TypeError:  # websockets < 15 has no proxy argument
            self.ws = await websockets.connect(url, open_timeout=20, max_size=None)
        except Exception as exc:  # noqa: BLE001
            raise RuntimeError(f"Gemini Live connection failed: {type(exc).__name__}: {str(exc).replace(key, '***')[:300]}") from None
        setup = {"setup": {
            "model": f"models/{model}",
            "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {
                "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}}},
            "systemInstruction": {"parts": [{"text": GEMINI_PERSONA}]},
            "tools": [{"functionDeclarations": [_ASK_HERMES]}],
            "inputAudioTranscription": {},
            "outputAudioTranscription": {},
            "contextWindowCompression": {"slidingWindow": {}},
        }}
        await self.ws.send(json.dumps(setup))
        try:
            first = await asyncio.wait_for(self.ws.recv(), 20)
        except Exception as exc:  # noqa: BLE001 - ConnectionClosed carries Google's reason
            rcvd = getattr(exc, "rcvd", None)
            detail = f"{rcvd.code} {rcvd.reason}" if rcvd is not None else f"{type(exc).__name__}: {exc}"
            raise RuntimeError(f"Gemini Live rejected the session ({model}): {detail}".replace(key, "***")) from None
        msg = json.loads(first)
        if "setupComplete" not in msg:
            raise RuntimeError(f"Gemini Live: unexpected first message {str(msg)[:200]}")
        turns = []
        for item in _history_items(history):
            turns.append({"role": "model" if item["role"] == "assistant" else "user",
                          "parts": [{"text": item["content"][0]["text"]}]})
        if turns:
            await self.ws.send(json.dumps({"clientContent": {"turns": turns, "turnComplete": False}}))

    async def close_upstream(self) -> None:
        if self.ws is not None:
            try:
                await self.ws.close()
            except Exception:  # noqa: BLE001
                pass

    async def send_mic(self, pcm16k: bytes) -> None:
        if self.muted or self.ws is None or self.closed:
            return
        self.mic_buf += pcm16k
        if len(self.mic_buf) >= 1280:  # 40 ms
            data, self.mic_buf = bytes(self.mic_buf), bytearray()
            try:
                await self.ws.send(json.dumps({"realtimeInput": {"audio": {
                    "data": base64.b64encode(data).decode(), "mimeType": "audio/pcm;rate=16000"}}}))
            except Exception:  # noqa: BLE001
                pass

    async def upstream_loop(self, speaker) -> None:
        reason = "upstream_closed"
        try:
            async for raw in self.ws:
                msg = json.loads(raw)
                sc = msg.get("serverContent")
                if sc:
                    if sc.get("interrupted"):
                        speaker.clear()
                    for part in (sc.get("modelTurn") or {}).get("parts") or []:
                        data = (part.get("inlineData") or {}).get("data")
                        if data:
                            speaker.write(base64.b64decode(data))
                    for key, ev in (("inputTranscription", "session.input_transcript.delta"),
                                    ("outputTranscription", "session.output_transcript.delta")):
                        text = (sc.get(key) or {}).get("text")
                        if text:
                            now = self.ms()
                            self.to_app({"type": ev, "delta": text, "start_ms": now, "end_ms": now})
                for fc in (msg.get("toolCall") or {}).get("functionCalls") or []:
                    cid = fc.get("id") or uuid.uuid4().hex
                    self.calls[cid] = {"name": fc.get("name") or "ask_hermes", "texts": [], "timer": None}
                    self.calls[cid]["timeout"] = asyncio.get_running_loop().call_later(
                        DELEGATION_TIMEOUT_S, lambda c=cid: asyncio.ensure_future(self._answer(c, timed_out=True)))
                    self.stats["delegations"] = self.stats.get("delegations", 0) + 1
                    self.to_app({"type": "session.delegation.created",
                                 "delegation": {"id": cid, "type": "client", "target": "hermes"},
                                 "request": (fc.get("args") or {}).get("request", "")})
                if "goAway" in msg:
                    log.info("live: gemini goAway %s", msg["goAway"])
        except Exception as exc:  # noqa: BLE001
            rcvd = getattr(exc, "rcvd", None)
            reason = f"upstream_closed: {rcvd.code} {rcvd.reason}" if rcvd is not None else f"upstream_error: {exc}"
            if rcvd is None or rcvd.code != 1000:
                self.to_app({"type": "error", "error": {"type": "upstream", "message": f"Gemini Live: {reason}"[:300]}})
        else:
            rc = getattr(self.ws, "close_code", None)
            rr = getattr(self.ws, "close_reason", "")
            if rc and rc != 1000:
                reason = f"upstream_closed: {rc} {rr}"
                self.to_app({"type": "error", "error": {"type": "upstream", "message": f"Gemini Live: {rc} {rr}"[:300]}})
        await self.close(reason)

    async def _answer(self, cid: str, timed_out: bool = False) -> None:
        call = self.calls.pop(cid, None)
        if call is None or self.ws is None:
            return
        for h in (call.get("timer"), call.get("timeout")):
            if h is not None:
                h.cancel()
        text = " ".join(call["texts"]).strip()
        response = {"result": text} if text and not timed_out else {"error": "Hermes did not answer in time."}
        try:
            await self.ws.send(json.dumps({"toolResponse": {"functionResponses": [
                {"id": cid, "name": call["name"], "response": response}]}}))
            self.stats["answers"] = self.stats.get("answers", 0) + 1
        except Exception:  # noqa: BLE001
            pass

    def on_app_event(self, event: dict) -> None:
        kind = event.get("type")
        if kind == "session.commentary.append":
            text = str(event.get("content") or "").strip()
            cid = event.get("delegation_id")
            if not text:
                return
            call = self.calls.get(cid) if cid else None
            if call is None:  # a result for no open call: tell the model as a user-side note
                asyncio.ensure_future(self._client_text(f"[Result from Hermes]: {text}", True))
                return
            call["texts"].append(text)
            if call.get("timer") is not None:
                call["timer"].cancel()
            call["timer"] = asyncio.get_running_loop().call_later(
                COMMENTARY_SETTLE_S, lambda c=cid: asyncio.ensure_future(self._answer(c)))
        elif kind == "session.instructions.append":
            text = str(event.get("content") or "").strip()
            if text:
                asyncio.ensure_future(self._client_text(f"[Instruction for the voice assistant]: {text}", False))
        elif kind == "session.input_audio.mute":
            self.muted = True
            self.mic_buf = bytearray()
            if self.ws is not None:
                asyncio.ensure_future(self.ws.send(json.dumps({"realtimeInput": {"audioStreamEnd": True}})))
        elif kind == "session.input_audio.unmute":
            self.muted = False
        # session.thinking.append: progress notes; Gemini has no side channel for them, so they are dropped.

    async def _client_text(self, text: str, complete: bool) -> None:
        if self.ws is None:
            return
        try:
            await self.ws.send(json.dumps({"clientContent": {
                "turns": [{"role": "user", "parts": [{"text": text}]}], "turnComplete": complete}}))
        except Exception:  # noqa: BLE001
            pass


async def _start_gemini(offer_sdp: str, history, key: str, model: str, voice: str, url: str) -> dict:
    _libs()
    PcmTrack, pump = _make_tracks()
    await _replace_previous()
    s = _GeminiSession()
    try:
        await s.open_upstream(key, model, voice, history, url)
        speaker = PcmTrack(24000, max_seconds=300)

        async def on_mic(track):
            await pump(track, 16000, s.send_mic, s.stop, s.stats["mic"])

        answer = await s.attach_app(offer_sdp, speaker, on_mic)
    except Exception:
        await s.close("setup_failed")
        raise
    s.stats["speaker_track"] = speaker
    _sessions[s.id] = s
    s.spawn(s.upstream_loop(speaker))
    log.info("live: gemini session %s (%s, voice %s) answered", s.id, model, voice)
    return {"session": {"id": s.id}, "transport": {"type": "webrtc", "sdp": answer}, "backend": "gemini"}


def start_gemini(offer_sdp: str, history, key: str, model: str = DEFAULT_GEMINI_MODEL, voice: str = DEFAULT_GEMINI_VOICE,
                 url: str = GEMINI_WS_URL) -> dict:
    if not key:
        raise ValueError("Gemini Live needs a Gemini API key (Hermes Local > Providers)")
    return _run(_start_gemini(offer_sdp, history, key, model, voice, url), 60)


# ---------------------------------------------------------------- Codex (ChatGPT subscription) relay
class _CodexSession(_Session):
    def __init__(self):
        super().__init__("codex")
        self.up_pc = None
        self.up_dc = None
        self.up_pending: list[dict] = []

    def to_up(self, event: dict) -> None:
        if self.up_dc is not None and self.up_dc.readyState == "open":
            self.up_dc.send(json.dumps(event))
        else:
            self.up_pending.append(event)

    async def close_upstream(self) -> None:
        if self.up_dc is not None and self.up_dc.readyState == "open":
            try:
                self.up_dc.send(json.dumps({"type": "session.close"}))
                await asyncio.sleep(0.2)
            except Exception:  # noqa: BLE001
                pass
        if self.up_pc is not None:
            await self.up_pc.close()

    def on_up_event(self, event: dict) -> None:
        kind = event.get("type")
        if kind in ("session.started", "session.updated"):
            return  # the app got session.started from us when its channel opened
        if kind in ("input_transcript.added", "output_transcript.added"):
            text = ((event.get("item") or {}).get("text")) or event.get("delta") or ""
            if text:
                now = self.ms()
                self.to_app({"type": "session.input_transcript.delta" if kind.startswith("input") else "session.output_transcript.delta",
                             "delta": text, "start_ms": now, "end_ms": now})
        elif kind == "delegation.created":
            item = event.get("item") or {}
            if item.get("id"):
                self.stats["delegations"] = self.stats.get("delegations", 0) + 1
                req = " ".join(c.get("text", "") for c in item.get("content") or [] if isinstance(c, dict)).strip()
                self.to_app({"type": "session.delegation.created",
                             "delegation": {"id": item["id"], "type": "client", "target": item.get("target") or "client"},
                             "request": req})
        elif kind == "error":
            err = event.get("error") or {}
            self.to_app({"type": "error", "error": {"type": err.get("type"), "code": err.get("code"),
                                                    "message": err.get("message") or "GPT-Live error"}})
        elif kind == "session.closed":
            asyncio.ensure_future(self.close(event.get("reason") or "closed"))

    def on_app_event(self, event: dict) -> None:
        kind = event.get("type")
        if kind == "session.commentary.append":
            text = str(event.get("content") or "")
            did = event.get("delegation_id")
            if not text.strip():
                return
            data = text.encode()
            chunks = []
            while data:  # vendor cap: 500 bytes per context append (codex methods_frameless_bidi.rs)
                cut = min(len(data), 500)
                while cut < len(data) and (data[cut] & 0xC0) == 0x80:
                    cut -= 1
                chunks.append(data[:cut].decode())
                data = data[cut:]
            for chunk in chunks:
                if did:
                    self.to_up({"type": "delegation.context.append", "delegation_item_id": did,
                                "content": [{"type": "input_text", "text": chunk}]})
                else:
                    self.to_up({"type": "session.context.append", "content": [{"type": "input_text", "text": chunk}]})
            self.stats["answers"] = self.stats.get("answers", 0) + 1
        elif kind == "session.input_audio.mute":
            self.to_up({"type": "input_audio.pause"})
        elif kind == "session.input_audio.unmute":
            self.to_up({"type": "input_audio.resume"})
        # session.thinking.append / session.instructions.append have no frameless v3 counterpart that does
        # not start a new model turn; they are dropped.


def _post_codex_offer(url: str, sdp: str, session: dict, token: str, account: str) -> tuple[int, str, str]:
    import httpx  # noqa: PLC0415
    loopback = url.startswith(("http://127.0.0.1", "http://localhost"))  # smoke-test fakes: never via a proxy
    with httpx.Client(timeout=30, follow_redirects=False, trust_env=not loopback) as client:
        r = client.post(url, json={"sdp": sdp, "session": session}, headers={
            "Authorization": f"Bearer {token}", "ChatGPT-Account-Id": account, "OpenAI-Alpha": "quicksilver=v2"})
        return r.status_code, r.text, r.headers.get("Location", "")


async def _start_codex(offer_sdp: str, session: dict, token: str, account: str, url: str) -> dict:
    _libs()
    from aiortc import RTCPeerConnection, RTCSessionDescription  # noqa: PLC0415
    PcmTrack, pump = _make_tracks()
    await _replace_previous()
    s = _CodexSession()
    try:
        up = RTCPeerConnection()
        s.up_pc = up
        up_mic = PcmTrack(48000, max_seconds=0.4)       # app mic -> Codex
        app_speaker = PcmTrack(48000, max_seconds=0.4)  # Codex voice -> app
        up.addTrack(up_mic)
        s.up_dc = up.createDataChannel("oai-events")

        @s.up_dc.on("open")
        def _up_open():
            pending, s.up_pending = s.up_pending, []
            for ev in pending:
                s.to_up(ev)

        @s.up_dc.on("message")
        def _up_msg(message):
            try:
                ev = json.loads(message)
            except (TypeError, ValueError):
                return
            if isinstance(ev, dict):
                s.on_up_event(ev)

        @up.on("track")
        def _up_track(track):
            if track.kind == "audio":
                s.spawn(pump(track, 48000, app_speaker.write, s.stop, s.stats["speaker"]))

        @up.on("connectionstatechange")
        async def _up_state():
            log.info("live: %s codex peer %s", s.id, up.connectionState)
            if up.connectionState in ("failed", "closed"):
                await s.close("upstream_closed")

        await up.setLocalDescription(await up.createOffer())
        status, body, location = await asyncio.to_thread(_post_codex_offer, url, up.localDescription.sdp, session, token, account)
        if status >= 300 or not body.startswith("v=0"):
            detail = " ".join(body.replace(token, "***").split())[:300]
            raise RuntimeError(f"ChatGPT (Codex) live voice was rejected by chatgpt.com (HTTP {status}): {detail}")
        await up.setRemoteDescription(RTCSessionDescription(sdp=body, type="answer"))
        call_id = location.rstrip("/").rsplit("/", 1)[-1] if location else ""
        if call_id:
            s.id = f"hl_codex_{call_id}"[:96]

        async def on_mic(track):
            await pump(track, 48000, up_mic.write, s.stop, s.stats["mic"])

        answer = await s.attach_app(offer_sdp, app_speaker, on_mic)
    except Exception:
        await s.close("setup_failed")
        raise
    _sessions[s.id] = s
    log.info("live: codex session %s answered", s.id)
    return {"session": {"id": s.id}, "transport": {"type": "webrtc", "sdp": answer}, "backend": "codex"}


def start_codex(offer_sdp: str, session: dict, token: str, account: str, url: str = CODEX_LIVE_URL) -> dict:
    return _run(_start_codex(offer_sdp, session, token, account, url), 60)


def active_sessions() -> list:
    return [{"id": s.id, "kind": s.kind, "stats": {k: v for k, v in s.stats.items() if k != "speaker_track"}}
            for s in _sessions.values()]
