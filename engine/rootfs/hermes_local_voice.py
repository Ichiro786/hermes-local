#!/usr/bin/env python3
"""Hermes Local: voice settings for the Hermes apps' Live Mode and GPT-Live mode (runs on Hermes' own python).

  defaults            once per HERMES_HOME (marker voice-defaults-v2 in /root/.hermes-local):
                      speech-to-text = the hermes-cloud command provider (hermes_local_stt.py: OpenAI or
                      Gemini key, whichever is saved; the engine has no local Whisper), language auto,
                      text-to-speech = Edge. Upgrades from v1 (stt.provider local = Whisper) are switched.
  status              one JSON line: STT/TTS providers, saved keys, live voice backend and readiness
  openai-key set      reads the key from stdin (never argv) and saves it as VOICE_TOOLS_OPENAI_KEY in
                      ~/.hermes/.env (mode 0600): GPT-Live (direct) and cloud speech-to-text.
  openai-key clear    removes it
  gemini-key set      reads the key from stdin, saves GEMINI_API_KEY (0600): Gemini Live behind GPT-Live
                      (hermes_local_live.py), Gemini speech-to-text, and Gemini TTS (GEMINI_TTS_MODEL, Kore).
  gemini-key clear    removes it; text-to-speech goes back to Edge
  live-backend set auto|codex|openai|gemini
                      which engine answers the app's GPT-Live mode (voice.gpt_live.backend); auto = ChatGPT
                      (Codex) sign-in, else OpenAI key, else Gemini key.

Why: without an STT provider /api/audio/transcribe answers 400 "No STT provider available" (the Hermes
Android app shows "That didn't come through: HTTP 400"); v1.0.0-test3 fixed that with a 1 GB local
Whisper, v1.0.0-test4 with cloud STT from the keys the user already has.
"""
import json
import os
import sys

sys.path.insert(0, os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
try:
    import hermes_bootstrap  # noqa: F401
except ImportError:
    pass

STATE = os.environ.get("HL_STATE_DIR", "/root/.hermes-local")
MARKER = os.path.join(STATE, "voice-defaults-v2")
CLOUD_STT = "hermes-cloud"
CLOUD_STT_CONFIG = {
    "type": "command",
    "command": "/usr/bin/python3 /opt/hermes-local/bin/hermes_local_stt.py {input_path} {output_path}",
    "format": "txt",
    "timeout": 120,
}
LIVE_BACKENDS = ("auto", "codex", "openai", "gemini")
KEY_ENV = "VOICE_TOOLS_OPENAI_KEY"
GEMINI_ENV = "GEMINI_API_KEY"
# Google's current TTS models (ai.google.dev speech-generation, Nov 2026): gemini-3.8-flash-tts and
# gemini-3.8-flash-lite-tts; Flash-Lite is the one Google recommends for conversational voice agents.
# Hermes' own default (gemini-2.5-flash-preview-tts) is closed to new API users.
GEMINI_TTS_MODEL = os.environ.get("HL_GEMINI_TTS_MODEL", "gemini-3.8-flash-lite-tts")
GEMINI_TTS_VOICE = "Kore"
def defaults(force=False):
    if os.path.exists(MARKER) and not force:
        print(json.dumps({"ok": True, "applied": False, "reason": "already applied"}))
        return 0
    from hermes_cli.config import read_raw_config, save_config
    raw = read_raw_config()
    stt = raw.get("stt") if isinstance(raw.get("stt"), dict) else {}
    raw["stt"] = stt
    changed = {}
    providers = stt.get("providers") if isinstance(stt.get("providers"), dict) else {}
    stt["providers"] = providers
    if providers.get(CLOUD_STT) != CLOUD_STT_CONFIG:
        providers[CLOUD_STT] = dict(CLOUD_STT_CONFIG); changed[f"stt.providers.{CLOUD_STT}"] = "command"
    # Keep a cloud provider the user picked (groq, mistral, ...); replace nothing/none and the old local Whisper
    # (v1 default; the engine no longer ships faster-whisper).
    want = _stt_choice(stt.get("provider"))
    if want and want != stt.get("provider"):
        stt["provider"] = want; changed["stt.provider"] = want
    stt["enabled"] = True
    if str(stt.get("language", "en")).strip().lower() in ("en", ""):
        stt["language"] = ""; changed["stt.language"] = ""
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


def _stt_choice(current):
    """The STT provider Hermes Local manages: Hermes' own OpenAI provider when an OpenAI key is saved (the apps
    then show "OpenAI"), else the hermes-cloud command provider (Gemini key, or a clear no-key error). None =
    leave a provider the user picked themselves alone."""
    cur = str(current or "").strip().lower()
    if cur not in ("", "local", "local_command", "none", CLOUD_STT, "openai"):
        return None
    return "openai" if (_key() or _key("OPENAI_API_KEY")) else CLOUD_STT


def _reconcile_stt():
    from hermes_cli.config import read_raw_config, save_config
    raw = read_raw_config()
    stt = raw.get("stt") if isinstance(raw.get("stt"), dict) else {}
    providers = stt.get("providers") if isinstance(stt.get("providers"), dict) else {}
    want = _stt_choice(stt.get("provider"))
    if want is None or (want == stt.get("provider") and providers.get(CLOUD_STT) == CLOUD_STT_CONFIG):
        return
    providers[CLOUD_STT] = dict(CLOUD_STT_CONFIG)
    stt["providers"] = providers
    stt["provider"] = want
    raw["stt"] = stt
    save_config(raw)


def _key(env=KEY_ENV):
    from hermes_cli.config import get_env_value
    return str(get_env_value(env) or "").strip()


def _hint(k):
    return ("…" + k[-4:]) if len(k) >= 8 else ""


def status():
    out = {"ok": True}
    try:
        from tools import transcription_tools as tt
        cfg = tt._load_stt_config()
        out["stt_provider"] = tt._get_provider(cfg)
        out["stt_language"] = cfg.get("language", "")
        out["faster_whisper"] = bool(getattr(tt, "_HAS_FASTER_WHISPER", False))
    except Exception as exc:  # noqa: BLE001
        out["stt_error"] = str(exc)[:200]
    try:
        from tools import tts_tool
        tcfg = tts_tool._load_tts_config()
        out["tts_provider"] = tts_tool._get_provider(tcfg)
        out["tts_model"] = (tcfg.get(out["tts_provider"]) or {}).get("model", "") if isinstance(tcfg, dict) else ""
    except Exception as exc:  # noqa: BLE001
        out["tts_error"] = str(exc)[:200]
    k = _key()
    out["openai_key"] = 1 if k else 0
    out["openai_key_hint"] = _hint(k)
    g = _key(GEMINI_ENV)
    out["gemini_key"] = 1 if g else 0
    out["gemini_key_hint"] = _hint(g)
    out["codex"] = 1 if _codex_signed_in() else 0
    # What hermes_local_stt.py will use (same order as the script).
    if out.get("stt_provider") == CLOUD_STT:
        out["stt_backend"] = "openai" if (k or _key("OPENAI_API_KEY")) else ("gemini" if (g or _key("GOOGLE_API_KEY")) else "none")
    else:  # Hermes' own provider (openai when a key is saved), or "none"
        out["stt_backend"] = out.get("stt_provider", "")
    try:
        from tools.voice_live import resolve_gpt_live_status
        live = resolve_gpt_live_status()
        out["gpt_live"] = 1 if live.get("available") else 0
        out["gpt_live_reason"] = live.get("reason") or ""
        out["live_backend"] = live.get("backend") or ""
        out["live_selected"] = live.get("selected") or "auto"
        out["live_backends"] = live.get("backends") or []
        out["live_model"] = live.get("model") or ""
    except Exception as exc:  # noqa: BLE001
        out["gpt_live"] = 0
        out["gpt_live_reason"] = str(exc)[:200]
    print(json.dumps(out))
    return 0


def _codex_signed_in():
    try:
        from hermes_local_live import codex_credentials
        codex_credentials(read_only=True)
        return True
    except Exception:  # noqa: BLE001
        return False


def _set_live_backend(value, only_if=None):
    from hermes_cli.config import read_raw_config, save_config
    raw = read_raw_config()
    voice = raw.get("voice") if isinstance(raw.get("voice"), dict) else {}
    live = voice.get("gpt_live") if isinstance(voice.get("gpt_live"), dict) else {}
    if only_if is not None and str(live.get("backend") or "auto") != only_if:
        return
    if value == "auto":
        live.pop("backend", None)
    else:
        live["backend"] = value
    voice["gpt_live"] = live
    raw["voice"] = voice
    save_config(raw)


def live_backend(argv):
    if len(argv) < 2 or argv[0] != "set" or argv[1] not in LIVE_BACKENDS:
        print(json.dumps({"ok": False, "error": "usage: live-backend set auto|codex|openai|gemini"}))
        return 2
    _set_live_backend(argv[1])
    return status()


def live_url(argv):
    """Test hook for the engine smoke test: point the codex/gemini live backend at a local fake (or clear)."""
    if len(argv) < 2 or argv[0] not in ("codex", "gemini"):
        print(json.dumps({"ok": False, "error": "usage: live-url codex|gemini <url>|clear"}))
        return 2
    from hermes_cli.config import read_raw_config, save_config
    raw = read_raw_config()
    voice = raw.get("voice") if isinstance(raw.get("voice"), dict) else {}
    live = voice.get("gpt_live") if isinstance(voice.get("gpt_live"), dict) else {}
    if argv[1] == "clear":
        live.pop(f"{argv[0]}_url", None)
    else:
        live[f"{argv[0]}_url"] = argv[1]
    voice["gpt_live"] = live
    raw["voice"] = voice
    save_config(raw)
    print(json.dumps({"ok": True}))
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
        _set_live_backend("auto", only_if="openai")
    else:
        print(json.dumps({"ok": False, "error": "usage: openai-key set|clear"}))
        return 2
    _reconcile_stt()
    try:
        from hermes_cli.config import get_env_path
        os.chmod(get_env_path(), 0o600)
    except OSError:
        pass
    return status()


def _set_tts(provider):
    from hermes_cli.config import read_raw_config, save_config
    raw = read_raw_config()
    tts = raw.get("tts") if isinstance(raw.get("tts"), dict) else {}
    raw["tts"] = tts
    if provider == "gemini":
        tts["provider"] = "gemini"
        gem = tts.get("gemini") if isinstance(tts.get("gemini"), dict) else {}
        gem.setdefault("model", GEMINI_TTS_MODEL)
        gem.setdefault("voice", GEMINI_TTS_VOICE)
        tts["gemini"] = gem
    elif tts.get("provider") == "gemini":
        tts["provider"] = "edge"
    save_config(raw)


def gemini_key(action):
    from hermes_cli.config import remove_env_value, save_env_value
    if action == "set":
        key = sys.stdin.readline().strip()
        if len(key) < 30 or any(c.isspace() for c in key):
            print(json.dumps({"ok": False, "error": "That does not look like a Gemini API key (AIza…)."}))
            return 1
        save_env_value(GEMINI_ENV, key)
        if _key(GEMINI_ENV) != key:
            print(json.dumps({"ok": False, "error": "Hermes refused to save the key."}))
            return 1
        _set_tts("gemini")
    elif action == "clear":
        remove_env_value(GEMINI_ENV)
        _set_tts("edge")
        _set_live_backend("auto", only_if="gemini")
    else:
        print(json.dumps({"ok": False, "error": "usage: gemini-key set|clear"}))
        return 2
    _reconcile_stt()
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
    if cmd == "gemini-key":
        return gemini_key(argv[2] if len(argv) > 2 else "")
    if cmd == "openai-key":
        return openai_key(argv[2] if len(argv) > 2 else "")
    if cmd == "live-backend":
        return live_backend(argv[2:])
    if cmd == "live-url":
        return live_url(argv[2:])
    print(json.dumps({"ok": False, "error": f"unknown command {cmd}"}))
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
