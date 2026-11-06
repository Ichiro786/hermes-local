#!/usr/bin/env python3
"""Hermes Local: provider state and model choice for the app's Providers screen.

Runs on Hermes' own python (see hermes-local-auth). Uses the same code paths as the dashboard's
/api/model/options and /api/model/set, so a choice made here is what chat uses.

  status                 one JSON object: codex/antigravity sign-in, accounts, current model
  models <provider>      JSON {"provider", "models": [...], "recommended"}
  use <provider> [model] make <provider> (and <model>, default: Hermes' recommended one) the main model
  ensure <provider>      `use` only when no main provider is configured yet
"""
import json
import os
import sys

sys.path.insert(0, os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
try:
    import hermes_bootstrap  # noqa: F401  (puts Hermes' dependency environment on sys.path)
except ImportError:
    pass

CODEX = "openai-codex"
AGY = "antigravity-oauth"
TOKEN_FILES = ("jetski-standalone-oauth-token", "antigravity-oauth-token")


def _config():
    from hermes_cli.config import load_config
    return load_config()


def _current():
    cfg = _config()
    m = cfg.get("model") or {}
    if not isinstance(m, dict):
        return {"provider": "", "model": str(m or "")}
    return {"provider": str(m.get("provider") or ""), "model": str(m.get("default") or m.get("name") or "")}


def _codex_entries():
    try:
        from agent.credential_pool import load_pool
        return list(load_pool(CODEX).entries())
    except Exception:  # noqa: BLE001
        return []


def _codex_accounts():
    return [str(e.label or e.id) for e in _codex_entries()]


def prune(provider):
    """Keep only the newest Codex sign-in: a second login of the same OpenAI account revokes the
    older token family (Hermes warns about this), and the pool would keep trying the dead one."""
    import subprocess
    entries = _codex_entries() if provider == CODEX else []
    for e in entries[:-1]:
        subprocess.run(["hermes", "auth", "remove", CODEX, str(e.id)], stdin=subprocess.DEVNULL,
                       capture_output=True, timeout=120)
    print(json.dumps({"ok": True, "kept": _codex_accounts()}))
    return 0


def fallback(provider):
    """After signing out of the main provider, move chat to the other one when it is signed in."""
    if _current()["provider"] != provider:
        print(json.dumps({"ok": True, "kept": True, **_current()}))
        return 0
    other = AGY if provider == CODEX else CODEX
    signed = _agy_signed_in() if other == AGY else bool(_codex_entries())
    if signed:
        return use(other)
    print(json.dumps({"ok": True, "signed_out": provider}))
    return 0


def _agy_signed_in():
    base = os.path.expanduser("~/.gemini/antigravity-cli")
    return any(os.path.isfile(os.path.join(base, n)) and os.path.getsize(os.path.join(base, n)) > 10 for n in TOKEN_FILES)


def _active_provider():
    try:
        from hermes_cli.auth import _load_auth_store
        return str(_load_auth_store().get("active_provider") or "")
    except Exception:  # noqa: BLE001
        return ""


def status():
    import shutil
    accounts = _codex_accounts()
    out = {
        "codex": 1 if accounts else 0,
        "codex_accounts": accounts,
        "antigravity": 1 if _agy_signed_in() else 0,
        "agy": 1 if shutil.which("agy") else 0,
        "active_provider": _active_provider(),
        **{f"model_{k}": v for k, v in _current().items()},
    }
    print(json.dumps(out))


def _model_ids(provider):
    """The picker's list for a signed-in provider (live catalog), else Hermes' curated list."""
    ids = []
    try:
        from hermes_cli.inventory import build_models_payload, load_picker_context
        payload = build_models_payload(load_picker_context(), include_unconfigured=True)
        for row in payload.get("providers", []):
            if str(row.get("slug", "")).lower() == provider:
                ids = [str(m) for m in (row.get("models") or [])]
    except Exception:  # noqa: BLE001
        ids = []
    if not ids:
        from hermes_cli.models import provider_model_ids
        ids = [str(m) for m in provider_model_ids(provider)]
    return ids


def _recommended(provider, ids=None):
    from hermes_cli.models import pick_silent_default_model
    ids = _model_ids(provider) if ids is None else ids
    return pick_silent_default_model(ids, provider=provider) if ids else ""


def models(provider):
    ids = _model_ids(provider)
    print(json.dumps({"provider": provider, "models": ids, "recommended": _recommended(provider, ids)}))
    return 0


def use(provider, model=""):
    model = model or _recommended(provider)
    if not model:
        print(f"no model known for {provider}", file=sys.stderr)
        return 2
    from hermes_cli.web_server_config import _apply_model_assignment_sync, _prepare_main_assignment
    from hermes_cli.config import load_config
    prepared = _prepare_main_assignment(load_config(), provider, model, "", "")
    _apply_model_assignment_sync("main", provider, model, "", "", prepared=prepared)
    try:
        from hermes_cli.auth import _auth_store_lock, _load_auth_store, _save_auth_store
        with _auth_store_lock():
            store = _load_auth_store()
            if store.get("active_provider") != provider:
                store["active_provider"] = provider
                _save_auth_store(store)
    except Exception:  # noqa: BLE001
        pass
    cur = _current()
    print(json.dumps({"ok": True, **cur}))
    return 0


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "status"
    if cmd == "status":
        status()
        return 0
    if cmd == "models" and len(argv) > 2:
        return models(argv[2])
    if cmd == "use" and len(argv) > 2:
        return use(argv[2], argv[3] if len(argv) > 3 else "")
    if cmd == "prune" and len(argv) > 2:
        return prune(argv[2])
    if cmd == "fallback" and len(argv) > 2:
        return fallback(argv[2])
    if cmd == "ensure" and len(argv) > 2:
        if _current()["provider"] not in ("", "auto"):
            print(json.dumps({"ok": True, "kept": True, **_current()}))
            return 0
        return use(argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as exc:  # noqa: BLE001
        detail = getattr(exc, "detail", None) or str(exc) or type(exc).__name__
        print(json.dumps({"ok": False, "error": str(detail)}))
        sys.exit(1)
