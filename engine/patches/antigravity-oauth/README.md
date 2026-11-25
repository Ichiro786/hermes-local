# antigravity-oauth patches

`engine/build-rootfs.sh` applies these, in order, to the antigravity-oauth plugin it installs
(pinned at `e4d0752`, which is upstream `main` as of 2026-11-25). The build then runs
`hermes_local_agy_rules_check.py`, and fails if a patch stops applying or a known trigger survives.

The problem: agy >= 1.5.10 answers some lines of Hermes' own system prompt with a zero-usage
`RESOURCE_EXHAUSTED (code 429)` instead of running the turn. The error is the same on Gemini and
Claude models, while quota remains. agy then retries silently for about 140 s.
See upstream issues #27 and #36, and decolua/9router#2964.

| Patch | Source | What it does |
|---|---|---|
| 0001, 0002 | upstream PR #38 (taylorwtf), unmerged, as is | Rewords six trigger lines in the GEMINI.md the plugin writes |
| 0003 | upstream PR #35 (maxuellstalin), unmerged, log-poll part only | Raises agy's silent 429 as soon as it is logged, instead of after ~140 s |
| 0004 | Hermes Local | Rewords "You are Hermes Agent, … Nous Research." to "You are Hermes, an agent built by Nous Research." |

Left out of PR #35: the default `--print-timeout 240s` argv and its result mapping. agy 1.5.15
describes the flag only as a "time limit for print mode", and does not say whether that limit
applies per turn or to the whole resident worker. The mapping's message is also Portuguese UI text.

Remove a patch here once upstream ships the same fix and the pin moves past it.
The rewording works around a Google-side filter that misfires on these lines. Keep it to the lines
that are known to trigger it, and keep the integration on the official agy CLI.
