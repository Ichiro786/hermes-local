#!/usr/bin/env python3
"""Disk accounting of an extracted rootfs: duplicate content, the two pm environments, metadata files.
  scan.py <rootfs> <out prefix>"""
import hashlib, json, os, sys, collections

R, P = sys.argv[1], sys.argv[2]
by = collections.defaultdict(list)
for dp, dns, fns in os.walk(R):
    rel = os.path.relpath(dp, R)
    if rel.split("/")[0] in ("proc", "sys", "dev"):
        dns[:] = []; continue
    for f in fns:
        p = os.path.join(dp, f)
        try:
            st = os.lstat(p)
        except OSError:
            continue
        if not os.path.isfile(p) or os.path.islink(p) or st.st_size < 4096:
            continue
        by[st.st_size].append(p)
groups = []
for size, ps in by.items():
    if len(ps) < 2:
        continue
    hs = collections.defaultdict(list)
    for p in ps:
        h = hashlib.sha1()
        with open(p, "rb") as fh:
            for b in iter(lambda: fh.read(1 << 20), b""):
                h.update(b)
        hs[h.hexdigest()].append(p)
    for h, l in hs.items():
        if len(l) > 1:
            groups.append((size * (len(l) - 1), size, [os.path.relpath(x, R) for x in l]))
groups.sort(key=lambda g: -g[0])
waste = sum(g[0] for g in groups)
# waste by pair of top dirs
pair = collections.Counter()
for w, s, l in groups:
    key = " + ".join(sorted({"/".join(x.split("/")[:4]) for x in l}))[:200]
    pair[key] += w
with open(P + "-dups.txt", "w") as f:
    f.write(f"duplicate-content waste (files >= 4 KiB): {waste} B = {waste/1048576:.1f} MiB in {len(groups)} groups\n\n-- by location set\n")
    for k, v in pair.most_common(30):
        f.write(f"{v/1048576:9.1f} MiB  {k}\n")
    f.write("\n-- biggest groups\n")
    for w, s, l in groups[:60]:
        f.write(f"{w/1048576:8.2f} MiB x{len(l)} {' | '.join(l[:3])}\n")
# pm environments: what each is for
meta = {}
inst = os.path.join(R, "root/.hermes/installs")
for dp, dns, fns in os.walk(inst):
    depth = os.path.relpath(dp, inst).count("/")
    for f in fns:
        if f.endswith((".json", ".cfg", ".toml", ".txt", "-managed")) and depth <= 3 and "site-packages" not in dp:
            p = os.path.join(dp, f)
            try:
                meta[os.path.relpath(p, R)] = open(p, errors="replace").read()[:3000]
            except OSError:
                pass
    if "site-packages" in dp or "/workspace/" in dp + "/":
        dns[:] = [d for d in dns if d in ("venv", "lib", "bin")] if depth < 2 else []
envs = {}
envroot = None
for dp, dns, fns in os.walk(inst):
    if os.path.basename(dp) == "environments":
        envroot = dp; break
if envroot:
    for e in os.listdir(envroot):
        sp = os.path.join(envroot, e, "venv")
        pkgs = set()
        for dp, dns, fns in os.walk(sp):
            for d in dns:
                if d.endswith(".dist-info"):
                    pkgs.add(d)
        envs[e] = sorted(pkgs)
        cfg = os.path.join(sp, "pyvenv.cfg")
        if os.path.exists(cfg):
            meta[os.path.relpath(cfg, R)] = open(cfg).read()
names = list(envs)
diff = {}
if len(names) >= 2:
    a, b = set(envs[names[0]]), set(envs[names[1]])
    diff = {names[0] + " only": sorted(a - b), names[1] + " only": sorted(b - a), "common": len(a & b)}
json.dump(dict(meta=meta, envs={k: len(v) for k, v in envs.items()}, diff=diff), open(P + "-pm.json", "w"), indent=1)
print(f"dups waste {waste/1048576:.1f} MiB; envs {[(k, len(v)) for k, v in envs.items()]}; diff {json.dumps(diff)[:600]}")
