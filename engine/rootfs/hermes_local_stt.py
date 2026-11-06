#!/usr/bin/env python3
"""Hermes Local cloud speech-to-text: Hermes' `stt.providers.hermes-cloud` command provider.

  hermes_local_stt.py <input audio> <output transcript.txt>

Hermes runs this for POST /api/audio/transcribe (the apps' plain Live Mode) once
stt.provider = hermes-cloud (set by hermes-local-voice). The engine ships no local Whisper, so it
uses whichever credential is saved in ~/.hermes/.env:
  1. VOICE_TOOLS_OPENAI_KEY / OPENAI_API_KEY -> OpenAI /v1/audio/transcriptions (gpt-4o-mini-transcribe)
  2. GEMINI_API_KEY / GOOGLE_API_KEY          -> Gemini generateContent with the audio inline
     (gemini-3.5-flash-lite, verbatim-transcript prompt, language auto-detected)
The ChatGPT (Codex) sign-in has no transcription endpoint (the Codex CLI only uses it for realtime
voice), so it cannot be used here.

Exit codes: 0 transcript written; 3 no credential (clear message on stderr, shown by the app as the
error); 4 provider error; 5 "empty transcript" (no speech: Hermes answers ok with "" and the app
re-listens). Plain Python 3 + ffmpeg only, so it starts fast.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid

HOME = os.environ.get("HERMES_HOME") or "/root/.hermes"
OPENAI_MODEL = os.environ.get("HL_OPENAI_STT_MODEL", "gpt-4o-mini-transcribe")
GEMINI_MODEL = os.environ.get("HL_GEMINI_STT_MODEL", "gemini-3.5-flash-lite")
OPENAI_URL = os.environ.get("HL_OPENAI_STT_URL", "https://api.openai.com/v1/audio/transcriptions")
GEMINI_URL = os.environ.get("HL_GEMINI_STT_URL", "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent")
NO_CREDENTIAL = ("Live Mode has no speech-to-text: add an OpenAI or Gemini API key in Hermes Local > Providers "
                 "(the engine has no local Whisper).")
PROMPT = ("Transcribe this audio recording verbatim, in the language and script that is spoken. Output only the "
          "transcript text, with no labels, notes or translation. If there is no speech, output nothing.")


def env_file() -> dict:
    out = {}
    try:
        with open(os.path.join(HOME, ".env"), encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip().removeprefix("export ").strip()
                v = v.strip()
                if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
                    v = v[1:-1]
                out[k] = v
    except OSError:
        pass
    return out


def die(code: int, msg: str):
    print(msg, file=sys.stderr)
    sys.exit(code)


def to_wav(src: str, tmp: str) -> str:
    dst = os.path.join(tmp, "speech.wav")
    try:
        subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", src, "-ac", "1",
                        "-ar", "16000", "-c:a", "pcm_s16le", dst], check=True, capture_output=True, timeout=60)
        return dst
    except (OSError, subprocess.SubprocessError) as exc:
        detail = getattr(exc, "stderr", b"") or b""
        die(4, f"could not decode the recording with ffmpeg: {detail.decode(errors='replace')[-200:] or exc}")


def post(url: str, body: bytes, headers: dict) -> dict:
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail).get("error", {}).get("message") or detail
        except (ValueError, AttributeError):
            pass
        raise RuntimeError(f"HTTP {exc.code}: {' '.join(str(detail).split())[:300]}") from None
    except (urllib.error.URLError, OSError) as exc:
        raise RuntimeError(f"network error: {exc}") from None


def openai(wav: str, key: str) -> str:
    boundary = uuid.uuid4().hex
    with open(wav, "rb") as fh:
        audio = fh.read()
    parts = [f"--{boundary}\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n{OPENAI_MODEL}\r\n".encode(),
             f"--{boundary}\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n".encode(),
             f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
             "Content-Type: audio/wav\r\n\r\n".encode() + audio + b"\r\n", f"--{boundary}--\r\n".encode()]
    try:
        r = post(OPENAI_URL, b"".join(parts), {"Authorization": f"Bearer {key}",
                                               "Content-Type": f"multipart/form-data; boundary={boundary}"})
    except RuntimeError as exc:
        die(4, f"OpenAI transcription ({OPENAI_MODEL}) failed: {exc}")
    return str(r.get("text") or "")


def gemini(wav: str, key: str) -> str:
    with open(wav, "rb") as fh:
        audio = base64.b64encode(fh.read()).decode()
    body = {"contents": [{"role": "user", "parts": [{"text": PROMPT}, {"inlineData": {"mimeType": "audio/wav", "data": audio}}]}],
            "generationConfig": {"temperature": 0}}
    try:
        r = post(GEMINI_URL.format(model=GEMINI_MODEL), json.dumps(body).encode(),
                 {"x-goog-api-key": key, "Content-Type": "application/json"})
    except RuntimeError as exc:
        die(4, f"Gemini transcription ({GEMINI_MODEL}) failed: {exc}")
    texts = []
    for cand in r.get("candidates") or []:
        for part in (cand.get("content") or {}).get("parts") or []:
            if isinstance(part.get("text"), str) and not part.get("thought"):
                texts.append(part["text"])
    return " ".join(texts)


def main() -> None:
    if len(sys.argv) < 3:
        die(2, "usage: hermes_local_stt.py <input audio> <output.txt>")
    src, dst = sys.argv[1], sys.argv[2]
    env = env_file()
    pick = lambda *names: next((env.get(n) or os.environ.get(n) for n in names if env.get(n) or os.environ.get(n)), "")  # noqa: E731
    okey = pick("VOICE_TOOLS_OPENAI_KEY", "OPENAI_API_KEY")
    gkey = pick("GEMINI_API_KEY", "GOOGLE_API_KEY")
    if not okey and not gkey:
        die(3, NO_CREDENTIAL)
    with tempfile.TemporaryDirectory(prefix="hl-stt-") as tmp:
        wav = to_wav(src, tmp)
        text = openai(wav, okey) if okey else gemini(wav, gkey)
    text = " ".join(text.split())
    if not text:
        die(5, "empty transcript")
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(text + "\n")


if __name__ == "__main__":
    main()
