#!/usr/bin/env python3
"""Hermes Local: voice settings for the Hermes apps' Live Mode (runs on Hermes' own python).

  defaults            once per HERMES_HOME (marker in /root/.hermes-local): speech-to-text = the
                      preinstalled local faster-whisper model, language auto-detect, text-to-speech = Edge
  status              one JSON line: STT/TTS providers, model, whether an OpenAI key is saved, GPT-Live status
  openai-key set      reads the key from stdin (never argv) and saves it as VOICE_TOOLS_OPENAI_KEY in
                      ~/.hermes/.env (mode 0600). Hermes uses it only for voice: GPT-Live and OpenAI STT/TTS.
  openai-key clear    removes it

Why the defaults: Hermes v0.23.0 autodetects STT and finds nothing until the stt-whisper extra is
installed, so /api/audio/transcribe answers 400 "No STT provider available" (the Hermes Android app
shows "That didn't come through: HTTP 400", and its Voice screen labels the missing provider "Plugin").
"""
import json
import os
import sys

sys.path.insert(0, os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
try:
    import hermes_bootstrap  # noqa: F401
except ImportError:
    pass

STATE = os.environ.get("HL_STATE_DIR", "/root/.hermes-local")
MARKER = os.path.join(STATE, "voice-defaults-v1")
KEY_ENV = "VOICE_TOOLS_OPENAI_KEY"
MODEL_FILE = "/opt/hermes-local/whisper-model"  # name of the model the engine build preinstalled


def _preinstalled_model():
    try:
        return open(MODEL_FILE).read().strip() or "base"
    except OSError:
        return "base"


def defaults(force=False):
    if os.path.exists(MARKER) and not force:
        print(json.dumps({"ok": True, "applied": False, "reason": "already applied"}))
        return 0
    from hermes_cli.config import read_raw_config, save_config
    raw = read_raw_config()
    stt = raw.get("stt") if isinstance(raw.get("stt"), dict) else {}
    raw["stt"] = stt
    changed = {}
    # Keep a provider the user picked (any cloud one); only fill in what is missing or the old default.
    if str(stt.get("provider") or "").strip().lower() in ("", "local", "none"):
        stt["provider"] = "local"; changed["stt.provider"] = "local"
    stt["enabled"] = True
    local = stt.get("local") if isinstance(stt.get("local"), dict) else {}
    stt["local"] = local
    if not local.get("model") or local.get("model") == "base":
        local["model"] = _preinstalled_model(); changed["stt.local.model"] = local["model"]
    # Automatic language: Whisper detects it per recording ("" = auto; Hermes' default is "en").
    if str(stt.get("language", "en")).strip().lower() in ("en", ""):
        stt["language"] = ""; changed["stt.language"] = ""
    if str(local.get("language") or "").strip().lower() in ("en", ""):
        local["language"] = ""
    tts = raw.get("tts") if isinstance(raw.get("tts"), dict) else {}
    raw["tts"] = tts
    if not tts.get("provider"):
        tts["provider"] = "edge"; changed["tts.provider"] = "edge"
    save_config(raw)
    os.makedirs(STATE, exist_ok=True)
    with open(MARKER, "w") as fh:
        fh.write(json.dumps(changed) + "\n")
    print(json.dumps({"ok": True, "applied": True, "changed": changed}))
    return 0


def _key():
    from hermes_cli.config import get_env_value
    return str(get_env_value(KEY_ENV) or "").strip()


def status():
    out = {"ok": True}
    try:
        from tools import transcription_tools as tt
        cfg = tt._load_stt_config()
        out["stt_provider"] = tt._get_provider(cfg)
        out["stt_model"] = (cfg.get("local") or {}).get("model", "")
        out["stt_language"] = cfg.get("language", "")
        out["faster_whisper"] = bool(getattr(tt, "_HAS_FASTER_WHISPER", False))
    except Exception as exc:  # noqa: BLE001
        out["stt_error"] = str(exc)[:200]
    try:
        from tools import tts_tool
        out["tts_provider"] = tts_tool._get_provider(tts_tool._load_tts_config())
    except Exception as exc:  # noqa: BLE001
        out["tts_error"] = str(exc)[:200]
    k = _key()
    out["openai_key"] = 1 if k else 0
    out["openai_key_hint"] = ("…" + k[-4:]) if len(k) >= 8 else ""
    try:
        from tools.voice_live import resolve_gpt_live_status
        live = resolve_gpt_live_status()
        out["gpt_live"] = 1 if live.get("available") else 0
        out["gpt_live_reason"] = live.get("reason") or ""
    except Exception as exc:  # noqa: BLE001
        out["gpt_live"] = 0
        out["gpt_live_reason"] = str(exc)[:200]
    print(json.dumps(out))
    return 0


def openai_key(action):
    from hermes_cli.config import remove_env_value, save_env_value
    if action == "set":
        key = sys.stdin.readline().strip()
        if not key.startswith("sk-") or len(key) < 20 or any(c.isspace() for c in key):
            print(json.dumps({"ok": False, "error": "That does not look like an OpenAI API key (sk-…)."}))
            return 1
        save_env_value(KEY_ENV, key)
        if _key() != key:
            print(json.dumps({"ok": False, "error": "Hermes refused to save the key."}))
            return 1
    elif action == "clear":
        remove_env_value(KEY_ENV)
    else:
        print(json.dumps({"ok": False, "error": "usage: openai-key set|clear"}))
        return 2
    try:
        from hermes_cli.config import get_env_path
        os.chmod(get_env_path(), 0o600)
    except OSError:
        pass
    return status()


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "status"
    if cmd == "defaults":
        return defaults(force="--force" in argv)
    if cmd == "status":
        return status()
    if cmd == "openai-key":
        return openai_key(argv[2] if len(argv) > 2 else "")
    print(json.dumps({"ok": False, "error": f"unknown command {cmd}"}))
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
