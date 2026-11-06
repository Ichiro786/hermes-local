#!/usr/bin/env python3
"""Hermes Local self-test: the dashboard voice endpoints the Hermes apps' Live Mode uses.

  hermes_local_voice_check.py <base url> <username> <speech.wav> [expect word]     password on stdin

Signs in like the apps, then: GET /api/audio/voice-config, GET /api/audio/voice-live/status,
POST /api/audio/stt-lease (model warm-up), POST /api/audio/transcribe (the wav, as the app's data URL),
POST /api/audio/speak, WS /api/audio/speak-stream (text in, PCM out), WS /api/audio/transcribe-stream
and POST /api/audio/voice-live/session (GPT-Live SDP exchange). One "VOICE <name> ok|fail|info <detail>"
line each; exit 0 when transcription, speak and speak-stream work.
Env: HL_VOICE_EXTRA_WAVS="<path>[,<path>…]" transcribes more files (info only).
"""
import base64
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
wav = sys.argv[3]
expect = (sys.argv[4] if len(sys.argv) > 4 else "").lower()
password = sys.stdin.readline().rstrip("\r\n")
failed = []


def line(name, verdict, detail=""):
    print(f"VOICE {name}: {verdict} {detail}"[:600], flush=True)
    if verdict == "fail":
        failed.append(name)


def data_url(path):
    with open(path, "rb") as fh:
        return "data:audio/wav;base64," + base64.b64encode(fh.read()).decode()


http = httpx.Client(base_url=base, timeout=300, trust_env=False, headers={"Origin": base})
providers = http.get("/api/auth/providers").json()
names = [p.get("name") for p in (providers.get("providers") if isinstance(providers, dict) else providers) or []
         if isinstance(p, dict)]
pname = "basic" if "basic" in names or not names else names[0]
r = http.post("/auth/password-login", json={"provider": pname, "username": user, "password": password})
if r.status_code != 200:
    line("login", "fail", f"{r.status_code} {r.text[:200]}")
    sys.exit(1)

r = http.get("/api/audio/voice-config")
line("voice-config", "info", f"{r.status_code} {r.text[:300]}")
try:
    stt_reason = (r.json().get("stt") or {}).get("reason", "")
except ValueError:
    stt_reason = ""
# "command/plugin provider" is what the Hermes Android app shows as "HEARS WITH Plugin": no STT provider.
line("stt-provider-resolved", "fail" if stt_reason == "command/plugin provider" else "ok", stt_reason)
r = http.get("/api/audio/voice-live/status")
line("gpt-live-status", "info", f"{r.status_code} {r.text[:300]}")

t = time.monotonic()
r = http.post("/api/audio/stt-lease", json={"lease": "hl-voice-check", "active": True})
line("stt-lease", "info", f"{r.status_code} {time.monotonic() - t:.1f}s {r.text[:200]}")


def transcribe(path, name, want=""):
    t = time.monotonic()
    r = http.post("/api/audio/transcribe", json={"data_url": data_url(path), "mime_type": "audio/wav"})
    dt = time.monotonic() - t
    text = ""
    try:
        text = str(r.json().get("transcript") or "")
    except ValueError:
        pass
    ok = r.status_code == 200 and text.strip() and (not want or want in text.lower())
    line(name, "ok" if ok else ("fail" if want else "info"),
         f"POST /api/audio/transcribe {r.status_code} in {dt:.1f}s: {r.text[:260]}")


transcribe(wav, "stt", expect)
for extra in [p for p in os.environ.get("HL_VOICE_EXTRA_WAVS", "").split(",") if p]:
    transcribe(extra, "stt-" + os.path.basename(extra).rsplit(".", 1)[0])
http.post("/api/audio/stt-lease", json={"lease": "hl-voice-check", "active": False})

t = time.monotonic()
r = http.post("/api/audio/speak", json={"text": "Hello from Hermes Local. Live Mode can speak."})
dt = time.monotonic() - t
try:
    body = r.json()
except ValueError:
    body = {}
du = str(body.get("data_url") or "")
audio = base64.b64decode(du.split(",", 1)[1]) if du.startswith("data:audio") and "," in du else b""
line("tts", "ok" if r.status_code == 200 and len(audio) > 1000 else "fail",
     f"POST /api/audio/speak {r.status_code} in {dt:.1f}s, {len(audio)} bytes {body.get('mime_type', '')} "
     f"provider={body.get('provider')} {'' if audio else r.text[:200]}")


def ticket():
    r = http.post("/api/auth/ws-ticket")
    return r.json().get("ticket") if r.status_code == 200 else None


ws_base = base.replace("http", "ws", 1)
try:
    t = time.monotonic()
    with connect(f"{ws_base}/api/audio/speak-stream?ticket={ticket()}", open_timeout=30, max_size=None,
                 origin=base, proxy=None) as ws:
        ws.send(json.dumps({"text": "Speaking through the stream. "}))
        ws.send(json.dumps({"text": "Second sentence.", "done": True}))
        start, pcm, end, other = None, 0, False, []
        while time.monotonic() - t < 120:
            msg = ws.recv(timeout=120)
            if isinstance(msg, bytes):
                pcm += len(msg)
                continue
            frame = json.loads(msg)
            if frame.get("type") == "start":
                start = frame
            elif frame.get("type") == "end":
                end = True
                break
            else:
                other.append(frame)
                if frame.get("type") == "fallback":
                    break
    line("speak-stream", "ok" if start and pcm > 1000 and end else "fail",
         f"start={start} pcm={pcm} bytes end={end} other={other} in {time.monotonic() - t:.1f}s")
except Exception as exc:  # noqa: BLE001
    line("speak-stream", "fail", f"{type(exc).__name__}: {exc}")

try:
    with connect(f"{ws_base}/api/audio/transcribe-stream?ticket={ticket()}", open_timeout=30, max_size=None,
                 origin=base, proxy=None) as ws:
        ws.send(json.dumps({"sample_rate": 16000}))
        with open(wav, "rb") as fh:
            pcm = fh.read()[44:]
        for i in range(0, len(pcm), 6400):
            ws.send(pcm[i:i + 6400])
        ws.send(json.dumps({"eos": True}))
        frames = []
        while True:
            try:
                frame = json.loads(ws.recv(timeout=120))
            except Exception:  # noqa: BLE001
                break
            frames.append(frame)
            if frame.get("type") in ("final", "error"):
                break
    # Local whisper has no streaming session: the socket says so and the app falls back to POST transcribe.
    line("transcribe-stream", "info", json.dumps(frames)[:300])
except Exception as exc:  # noqa: BLE001
    line("transcribe-stream", "info", f"{type(exc).__name__}: {exc}")

sdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
r = http.post("/api/audio/voice-live/session", json={"sdp": sdp})
line("gpt-live-session", "info", f"POST /api/audio/voice-live/session {r.status_code} {r.text[:300]}")

print("VOICE_RESULT", "ok" if not failed else "fail " + ",".join(failed), flush=True)
sys.exit(1 if failed else 0)
