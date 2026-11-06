#!/usr/bin/env python3
"""Hermes Local engine diagnostics: would a Hermes launch try to finish a "source update"?

Run on Hermes' own python from /opt/hermes/hermes-agent (build-rootfs.sh and smoke-test.sh do).
Prints the inputs of hermes_cli.venv_sync.prepare_launch (Hermes v0.23.0) without running it.
"""
import json
import os
import sys
from pathlib import Path

ROOT = Path(os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
sys.path.insert(0, str(ROOT))


def show(name, fn):
    try:
        print(f"diag-update: {name} = {fn()}")
    except Exception as exc:  # noqa: BLE001
        print(f"diag-update: {name} -> {type(exc).__name__}: {exc}")


from hermes_cli.steward import read_install_stamp  # noqa: E402

stamp = read_install_stamp(ROOT)
show("updateMechanism", lambda: stamp.get("updateMechanism"))
show("distribution", lambda: stamp.get("distribution"))
show("git checkout", lambda: (ROOT / ".git").exists())
show("HERMES_DISABLE_LAZY_INSTALLS", lambda: os.environ.get("HERMES_DISABLE_LAZY_INSTALLS"))
from pm.environments import install_state_dir  # noqa: E402

state = install_state_dir(ROOT)
show("install_state_dir", lambda: str(state))
show("install_state entries", lambda: sorted(p.name for p in state.iterdir()))
from hermes_cli.venv_sync import completion_pending_path  # noqa: E402

show("completion pending marker", lambda: completion_pending_path(ROOT).is_file())
import pm  # noqa: E402

show("venv_is_current", lambda: pm.venv_is_current(project_root=ROOT))
show("update lock files", lambda: [str(p) for p in Path(os.environ.get("HERMES_HOME", "/root/.hermes")).glob("*update*")])
show("image provenance", lambda: Path("/etc/hermes/image-provenance.json").read_text().strip()
     if Path("/etc/hermes/image-provenance.json").exists() else None)
launch_skips = stamp.get("updateMechanism") != "self" or not (ROOT / ".git").exists()
print(f"diag-update: launch-time source-update completion {'DISABLED' if launch_skips else 'ARMED (updateMechanism=self)'}")
print("diag-update: json " + json.dumps({"updateMechanism": stamp.get("updateMechanism"), "disabled": launch_skips}))

# Why venv_is_current is what it is: recorded stamp vs the stamp of this tree, and its inputs.
def _stamp_parts():
    from pm.environments import runtime_facts_path
    from pm.lock import Facts, Lockfile
    from pm.packages import Venv, _uv_lock_digest
    from pm.paths import lockfile_path
    from pm.store import current_target
    from pm.workspace import build_member_dirs, members_stamp
    fact = Facts(runtime_facts_path(ROOT), strict=True).get("venv") or {}
    venv = Venv(ROOT)
    extras = sorted(fact.get("extras") or [])
    return {
        "recorded_stamp": str(fact.get("stamp"))[:16], "recorded_extras": extras,
        "recorded_inputs": {k: (str(v)[:120]) for k, v in fact.items() if k not in ("stamp", "extras")},
        "expected_stamp": venv.expected_stamp(extras)[:16],
        "uv_lock_digest": _uv_lock_digest(ROOT / "uv.lock").hex()[:16] if isinstance(_uv_lock_digest(ROOT / "uv.lock"), bytes) else str(_uv_lock_digest(ROOT / "uv.lock"))[:16],
        "target": str(current_target()),
        "python_lock": str(Lockfile(lockfile_path()).version("python")),
        "members": members_stamp(build_member_dirs(extras))[:200],
    }


show("stamp parts", _stamp_parts)
