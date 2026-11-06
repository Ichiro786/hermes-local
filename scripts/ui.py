#!/usr/bin/env python3
"""Find a node in a `uiautomator dump` by text/content-desc (e2e helper).

  ui.py <dump.xml> <regex> [--after <regex>] [--before <regex>] [--nth N]

Prints "cx cy enabled text" for the match (top to bottom, nth from 0) whose centre lies below the
first node matching --after and above the first node matching --before; exit 1 when none.
"""
import re
import sys
import xml.etree.ElementTree as ET

args = sys.argv[1:]
path, pat = args[0], re.compile(args[1])
opt = {k: args[i + 1] for i, k in enumerate(args) if k.startswith("--")}
try:
    root = ET.parse(path).getroot()
except Exception:  # noqa: BLE001
    sys.exit(1)
nodes = []
for n in root.iter("node"):
    t = (n.get("text") or "") or (n.get("content-desc") or "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
    if not t or not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    nodes.append(((y1 + y2) // 2, (x1 + x2) // 2, n.get("enabled"), t))
nodes.sort()


def first_y(rx):
    r = re.compile(rx)
    return next((y for y, _, _, t in nodes if r.search(t)), None)


lo = first_y(opt["--after"]) if "--after" in opt else -1
hi = first_y(opt["--before"]) if "--before" in opt else None
if lo is None:
    sys.exit(1)
hits = [(y, x, e, t) for y, x, e, t in nodes if pat.search(t) and y > lo and (hi is None or y < hi)]
n = int(opt.get("--nth", 0))
if len(hits) <= n:
    sys.exit(1)
y, x, e, t = hits[n]
print(x, y, e, t.replace("\n", " "))
