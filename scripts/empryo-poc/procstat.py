#!/usr/bin/env python3
"""procstat.py <label> <root pid> [seconds]
Idle cost of a process tree: RSS (sum + per process), CPU % of one core and context switches
(voluntary + involuntary, a proxy for wakeups) over a window, plus the TCP/UDP ports it listens on.
Prints one 'METRIC <label> key=value ...' line for the report.
"""
import os, sys, time

label, root = sys.argv[1], int(sys.argv[2])
secs = float(sys.argv[3]) if len(sys.argv) > 3 else 60
TCK = os.sysconf("SC_CLK_TCK")


def children():
    kids = {}
    for p in os.listdir("/proc"):
        if p.isdigit():
            try:
                ppid = int(open(f"/proc/{p}/stat").read().rsplit(")", 1)[1].split()[1])
                kids.setdefault(ppid, []).append(int(p))
            except Exception:
                pass
    return kids


def tree():
    kids, out, todo = children(), [], [root]
    while todo:
        p = todo.pop(); out.append(p); todo += kids.get(p, [])
    return out


def sample(pids):
    """(cpu ticks of the processes incl. reaped children's? no: utime+stime), {tid: ctx switches}"""
    cpu, ctx = 0, {}
    for p in pids:
        try:
            st = open(f"/proc/{p}/stat").read().rsplit(")", 1)[1].split()
            cpu += int(st[11]) + int(st[12])
            for t in os.listdir(f"/proc/{p}/task"):
                n = 0
                for line in open(f"/proc/{p}/task/{t}/status"):
                    if "ctxt_switches" in line:
                        n += int(line.split()[1])
                ctx[t] = n
        except Exception:
            pass
    return cpu, ctx


def status(p, key):
    try:
        for line in open(f"/proc/{p}/status"):
            if line.startswith(key + ":"):
                return line.split()[1]
    except Exception:
        return "?"
    return "?"


pids = tree()
rss = 0
for p in pids:
    try:
        cmd = open(f"/proc/{p}/cmdline", "rb").read().replace(b"\0", b" ").decode(errors="replace")[:110]
    except Exception:
        cmd = "?"
    r = status(p, "VmRSS"); rss += int(r) if r.isdigit() else 0
    print(f"  pid {p} rss={r}kB threads={status(p, 'Threads')} {cmd}")
c0, x0 = sample(pids); time.sleep(secs); pids2 = [p for p in pids if os.path.exists(f"/proc/{p}")]
c1, x1 = sample(pids2)
cpu_pct = (c1 - c0) / TCK / secs * 100
dctx = sum(v - x0.get(t, 0) for t, v in x1.items())  # threads born in the window count from 0
print(f"METRIC {label} procs={len(pids)} rss_kb={rss} idle_window_s={secs:g} cpu_pct_one_core={cpu_pct:.2f} ctx_switches_per_s={dctx / secs:.1f}")

inodes = {}
for p in pids2:
    try:
        for fd in os.listdir(f"/proc/{p}/fd"):
            link = os.readlink(f"/proc/{p}/fd/{fd}")
            if link.startswith("socket:["):
                inodes[link[8:-1]] = p
    except Exception:
        pass
for f in ("tcp", "tcp6", "udp", "udp6"):
    try:
        lines = open(f"/proc/net/{f}").readlines()[1:]
    except Exception:
        continue
    for line in lines:
        x = line.split()
        if x[9] in inodes and (x[3] == "0A" or f.startswith("udp")):
            port = int(x[1].split(":")[1], 16)
            print(f"  listen {f} port {port} addr {x[1].split(':')[0]} pid {inodes[x[9]]}")
