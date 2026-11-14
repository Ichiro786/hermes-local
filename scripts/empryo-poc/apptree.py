#!/usr/bin/env python3
"""apptree.py <label> <snap0> <snap1> <seconds>: idle cost of every process of the app's uid on the
emulator, from two raw snapshots written by sample.sh (adb root). Prints per process and a METRIC line
for the Empryo processes (everything except the app's own Java process)."""
import os, sys

TCK = 100  # Android CLK_TCK


def load(path):
    procs, cur = {}, None
    for line in open(path, errors="replace"):
        line = line.rstrip("\r\n")
        tag, _, rest = line.partition(" ")
        if tag == "P":
            cur = procs.setdefault(rest.strip(), {"ctx": 0, "rss": 0, "cmd": "", "cpu": 0})
        elif cur is None:
            continue
        elif tag == "S" and ")" in rest:
            f = rest.rsplit(")", 1)[1].split()
            cur["cpu"] = int(f[11]) + int(f[12])
        elif tag == "T":
            try:
                cur["ctx"] += int(rest.split()[-1])
            except ValueError:
                pass
        elif tag == "R":
            try:
                cur["rss"] = int(rest.split()[1])
            except (ValueError, IndexError):
                pass
        elif tag == "C":
            cur["cmd"] = rest[:120]
    return procs


label, a, b, secs = sys.argv[1], load(sys.argv[2]), load(sys.argv[3]), float(sys.argv[4])
tot = {"cpu": 0.0, "ctx": 0.0, "rss": 0, "n": 0}
for pid, p in sorted(b.items(), key=lambda x: int(x[0])):
    if pid not in a:
        continue
    cpu = (p["cpu"] - a[pid]["cpu"]) / TCK / secs * 100
    ctx = (p["ctx"] - a[pid]["ctx"]) / secs
    app_java = p["cmd"].startswith("dev.syed.hermeslocal") or not p["cmd"]
    print(f"  pid {pid} rss={p['rss']}kB cpu={cpu:.2f}% ctx/s={ctx:.1f} {'[app java] ' if app_java else ''}{p['cmd']}")
    if not app_java:
        tot["cpu"] += cpu; tot["ctx"] += ctx; tot["rss"] += p["rss"]; tot["n"] += 1
print(f"METRIC {label} procs={tot['n']} rss_kb={tot['rss']} idle_window_s={secs:g} cpu_pct_one_core={tot['cpu']:.2f} ctx_switches_per_s={tot['ctx']:.1f}")
