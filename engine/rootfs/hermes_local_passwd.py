#!/usr/bin/env python3
# Helper for hermes-local-passwd (runs on Hermes' own venv python).
import secrets, sys
mode, username = sys.argv[1], sys.argv[2]
import os
sys.path.insert(0, os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
# The Hermes launcher imports hermes_bootstrap first; it puts the dependency environment on sys.path.
try:
    import hermes_bootstrap  # noqa: F401
except ImportError:
    pass
from hermes_cli.config import load_config, save_config
cfg = load_config()
basic = (cfg.get("dashboard") or {}).get("basic_auth") or {}
if mode == "check":
    sys.exit(0 if basic.get("username") and (basic.get("password_hash") or basic.get("password")) else 1)
if mode == "username":
    print(basic.get("username") or ""); sys.exit(0)
password = sys.stdin.readline().rstrip("\r\n")
if len(password) < 6:
    print("password too short (min 6)", file=sys.stderr); sys.exit(2)
from plugins.dashboard_auth.basic import hash_password
try:
    from hermes_cli.plugins_cmd import ensure_basic_auth_plugin_enabled_in_config
except Exception:
    ensure_basic_auth_plugin_enabled_in_config = None
basic = cfg.setdefault("dashboard", {}).setdefault("basic_auth", {})
basic["username"] = username
basic["password_hash"] = hash_password(password)
basic["password"] = ""
basic["secret"] = secrets.token_urlsafe(32)  # new secret: old sessions end on password change
if ensure_basic_auth_plugin_enabled_in_config:
    ensure_basic_auth_plugin_enabled_in_config(cfg)
save_config(cfg)
print("ok")
