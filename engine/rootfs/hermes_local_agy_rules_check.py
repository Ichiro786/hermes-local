#!/usr/bin/env python3
"""Check that the installed antigravity-oauth plugin rewords the prompt lines agy answers with a fake 429.

agy >= 1.5.10 answers some lines of Hermes' own system prompt with a zero-usage RESOURCE_EXHAUSTED
(code 429) instead of running the turn (upstream neerazz/hermes-antigravity-oauth #27, #36; PR #38).
Hermes Local patches the plugin's soul.py (engine/patches/antigravity-oauth) to reword them in the
GEMINI.md it hands to agy. This feeds the real prompt text of the Hermes in this engine through the
installed plugin and fails if a known trigger survives. Offline: no agy run, no sign-in.

Prints HL_AGY_RULES_OK on success; exits 1 and names each surviving trigger otherwise.
"""
import importlib.util
import os
import re
import sys
import tempfile

sys.path.insert(0, os.environ.get("HL_HERMES_ROOT", "/opt/hermes/hermes-agent"))
import hermes_bootstrap  # noqa: E402,F401

PLUGIN_DIR = os.environ.get("HL_AGY_PLUGIN_DIR", "/root/.hermes/plugins/antigravity-oauth")

# Lines reported to draw the fake 429 (PR #38's six, plus the identity sentence: #27, decolua/9router#2964).
TRIGGERS = (
    "https://hermes-agent.nousresearch.com/docs",
    "# Google model operational directives",
    "<!-- End Hermes runtime environment -->",
    "the deliverable is a working artifact backed by real tool output",
    "Reporting a blocker honestly is always better than inventing a result.",
    "Mid-turn, the user can steer you: Hermes delivers their message",
    "You are Hermes Agent",
)
# "Hermes Agent" and "Nous Research" in one sentence.
PAIR = re.compile(r"Hermes Agent[^.\n]*Nous Research|Nous Research[^.\n]*Hermes Agent")


def hermes_prompt_parts():
    from agent import prompt_builder as pb
    from hermes_cli import default_soul as ds

    names = ("DEFAULT_AGENT_IDENTITY", "HERMES_AGENT_HELP_GUIDANCE", "HERMES_AGENT_HELP_GUIDANCE_NO_SKILLS",
             "TASK_COMPLETION_GUIDANCE", "GOOGLE_MODEL_OPERATIONAL_GUIDANCE", "STEER_CHANNEL_NOTE",
             "RUNTIME_ENVIRONMENT_END")
    parts = [getattr(pb, n) for n in names]  # AttributeError: Hermes renamed one; update this check
    parts += [ds.DEFAULT_SOUL_MD, *ds._LEGACY_TEMPLATE_SOULS]
    return parts


def load_soul():
    spec = importlib.util.spec_from_file_location("hl_agy_soul", os.path.join(PLUGIN_DIR, "soul.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    parts = hermes_prompt_parts()
    joined = "\n\n".join(parts)
    # The check is only meaningful while this Hermes still carries the triggers it looks for.
    present = [t for t in TRIGGERS if t in joined]
    if len(present) < len(TRIGGERS):
        print("HL_AGY_RULES_NOTE Hermes no longer carries:", [t for t in TRIGGERS if t not in joined])
    soul = load_soul()
    with tempfile.TemporaryDirectory() as home:  # no SOUL.md on disk: only the parts above
        os.environ["HERMES_HOME"] = home
        rules = soul.build_rules(parts, [])
    left = [t for t in TRIGGERS if t in rules] + [m.group(0) for m in PAIR.finditer(rules)]
    if left:
        for t in left:
            print("HL_AGY_RULES_TRIGGER_LEFT", t[:120])
        return 1
    print(f"HL_AGY_RULES_OK {len(present)} trigger lines reworded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
