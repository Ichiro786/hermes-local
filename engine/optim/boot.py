#!/usr/bin/env python3
"""Boot the Hermes Local engine like the Android app does and measure it (research/optimize probe).

  boot.py --label L --rootfs R [--mode proot|chroot] [--flags app|lean] [--env K=V ...] [--cold]
          [--idle 60] [--settle 30] [--strace] [--snapshot] [--checks] [--timeout 600]

proot mode runs as the unprivileged user 'runner' (owner of the rootfs, like the app uid on the phone) with the
app's exact proot argv (ProotCommand.kt). Results: one JSON line appended to $OUT/boots.jsonl, the engine log in
$OUT/boot-<label>.log, per-process idle table in $OUT/idle-<label>.txt.
"""
import argparse, json, os, pwd, re, signal, subprocess, sys, threading, time, urllib.request, http.cookiejar

OUT = os.environ.get("OUT", "out")
PROOT = os.environ.get("PROOT", "proot")
USER = os.environ.get("RUN_USER", "runner")
GUEST_PATH = "/opt/hermes-local/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
PW = "probe-pass-123"
HZ = os.sysconf("SC_CLK_TCK")


def guest_env(extra=()):
    env = ["HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp", f"PATH={GUEST_PATH}",
           "HERMES_HOME=/root/.hermes", "HERMES_RUNTIME_DIR=/opt/hermes/tools", "AGY_CLI_DISABLE_AUTO_UPDATE=1"]
    return env + list(extra)


def proot_argv(r, flags, guest):
    if flags == "app":
        a = [PROOT, "--kill-on-exit", "--link2symlink", "--sysvipc", "-0", "-L"]
    elif flags == "lean":  # Empryo research: no fake root, no sysvipc, no -L
        a = [PROOT, "--kill-on-exit", "--link2symlink"]
    else:
        raise SystemExit("flags?")
    a += ["-r", r, "-w", "/root", "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/urandom:/dev/random",
          "-b", "/proc/self/fd:/dev/fd", "-b", "/proc/self/fd/0:/dev/stdin", "-b", "/proc/self/fd/1:/dev/stdout",
          "-b", "/proc/self/fd/2:/dev/stderr", "-b", f"{r}/tmp:/dev/shm"]
    fakes = os.path.join(r, "opt/hermes-local/proc")
    for name, g in (("max_user_watches", "/proc/sys/fs/inotify/max_user_watches"), ("cap_last_cap", "/proc/sys/kernel/cap_last_cap")):
        if os.path.isfile(os.path.join(fakes, name)):
            a += ["-b", f"{fakes}/{name}:{g}"]
    return a + ["/usr/bin/env", "-i"] + guest


def host_env(r, extra):
    e = {"PATH": "/usr/bin:/bin", "PROOT_TMP_DIR": os.environ.get("PROOT_TMP", "/tmp/prtmp"),
         "PROOT_L2S_DIR": os.path.join(os.path.realpath(r), ".l2s")}
    for kv in extra:
        k, v = kv.split("=", 1); e[k] = v
    os.makedirs(e["PROOT_TMP_DIR"], exist_ok=True)
    try:
        u = pwd.getpwnam(USER); os.chown(e["PROOT_TMP_DIR"], u.pw_uid, u.pw_gid)
    except Exception:
        pass
    os.makedirs(e["PROOT_L2S_DIR"], exist_ok=True)
    return e


def run_guest(r, mode, flags, argv, envx=(), stdin=None, timeout=600, hostenv=()):
    """Run one guest command to completion, return (rc, secs, output)."""
    if mode == "proot":
        cmd = proot_argv(r, flags, guest_env(envx) + argv)
        kw = dict(user=USER, group=USER, extra_groups=[], env=host_env(r, hostenv), cwd=r)
    else:
        cmd = ["chroot", r, "/usr/bin/env", "-i"] + guest_env(envx) + argv
        kw = dict(env={"PATH": "/usr/bin:/bin"})
    t = time.time()
    p = subprocess.run(cmd, input=stdin, capture_output=True, text=True, timeout=timeout, **kw)
    return p.returncode, time.time() - t, p.stdout + p.stderr


def children_map():
    m = {}
    for d in os.listdir("/proc"):
        if d.isdigit():
            try:
                s = open(f"/proc/{d}/stat").read()
                ppid = int(s[s.rfind(")") + 2:].split()[1])
                m.setdefault(ppid, []).append(int(d))
            except Exception:
                pass
    return m


def tree(root):
    m = children_map(); out = []; st = [root]
    while st:
        p = st.pop(); out.append(p); st += m.get(p, [])
    return out


def sample(pids):
    res = {}
    for p in pids:
        try:
            s = open(f"/proc/{p}/stat").read()
            f = s[s.rfind(")") + 2:].split()
            ticks = int(f[11]) + int(f[12])
            comm = s[s.find("(") + 1:s.rfind(")")]
            st = open(f"/proc/{p}/status").read()
            vol = int(re.search(r"voluntary_ctxt_switches:\s+(\d+)", st).group(1))
            nvol = int(re.search(r"nonvoluntary_ctxt_switches:\s+(\d+)", st).group(1))
            rss = int((re.search(r"VmRSS:\s+(\d+)", st) or re.search(r"(0)", "0")).group(1))
            thr = int(re.search(r"Threads:\s+(\d+)", st).group(1))
            pss = 0
            try:
                pss = int(re.search(r"^Pss:\s+(\d+)", open(f"/proc/{p}/smaps_rollup").read(), re.M).group(1))
            except Exception:
                pass
            # per-thread wakeups: sum ctx switches over threads
            tv = tn = 0
            for t in os.listdir(f"/proc/{p}/task"):
                try:
                    ts = open(f"/proc/{p}/task/{t}/status").read()
                    tv += int(re.search(r"voluntary_ctxt_switches:\s+(\d+)", ts).group(1))
                    tn += int(re.search(r"nonvoluntary_ctxt_switches:\s+(\d+)", ts).group(1))
                except Exception:
                    pass
            cmd = open(f"/proc/{p}/cmdline", "rb").read().replace(b"\0", b" ").decode(errors="replace")[:160]
            res[p] = dict(comm=comm, cmd=cmd, ticks=ticks, ctx=tv + tn, rss=rss, pss=pss, thr=thr)
        except Exception:
            pass
    return res


def http_code(url, timeout=2):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def checks(port):
    base = f"http://127.0.0.1:{port}"
    res = {}
    try:
        prov = json.loads(urllib.request.urlopen(base + "/api/auth/providers", timeout=10).read())
        plist = prov.get("providers", prov) if isinstance(prov, dict) else prov
        pname = next((p.get("name") for p in plist if p.get("supports_password") or p.get("password")), "basic")
    except Exception:
        pname = "basic"
    cj = http.cookiejar.CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    try:
        rq = urllib.request.Request(base + "/auth/password-login", data=json.dumps({"provider": pname, "username": "admin", "password": PW}).encode(),
                                    headers={"Content-Type": "application/json"})
        res["login"] = op.open(rq, timeout=30).status
    except urllib.error.HTTPError as e:
        res["login"] = e.code
    except Exception as e:
        res["login"] = str(e)[:80]
    for ep in ["/api/status", "/api/sessions", "/api/skills", "/api/dashboard/plugins", "/api/tools/toolsets", "/api/cron/jobs",
               "/api/model/info", "/api/model/options", "/api/config", "/api/audio/voice-config", "/api/profiles", "/api/memory"]:
        t = time.time()
        try:
            c = op.open(base + ep, timeout=90).status
        except urllib.error.HTTPError as e:
            c = e.code
        except Exception:
            c = 0
        res[ep] = [c, round(time.time() - t, 2)]
    return res


def du_new(r, marker):
    tot = 0; n = 0; pyc = 0; pycb = 0; by = {}
    for dp, dns, fns in os.walk(r):
        if dp.startswith(os.path.join(r, "proc")) or dp.startswith(os.path.join(r, "sys")) or dp.startswith(os.path.join(r, "dev")):
            dns[:] = []; continue
        for f in fns:
            p = os.path.join(dp, f)
            try:
                st = os.lstat(p)
            except Exception:
                continue
            if st.st_mtime > marker or st.st_ctime > marker and not os.path.islink(p):
                b = st.st_blocks * 512; tot += b; n += 1
                rel = os.path.relpath(p, r).split("/")
                k = "/".join(rel[:4]); by[k] = by.get(k, 0) + b
                if f.endswith(".pyc"):
                    pyc += 1; pycb += b
    top = sorted(by.items(), key=lambda x: -x[1])[:25]
    return dict(new_bytes=tot, new_files=n, new_pyc=pyc, new_pyc_bytes=pycb, top=top)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True); ap.add_argument("--rootfs", required=True)
    ap.add_argument("--mode", default="proot"); ap.add_argument("--flags", default="app")
    ap.add_argument("--env", action="append", default=[]); ap.add_argument("--cold", action="store_true")
    ap.add_argument("--idle", type=int, default=60); ap.add_argument("--settle", type=int, default=30)
    ap.add_argument("--strace", action="store_true"); ap.add_argument("--snapshot", action="store_true")
    ap.add_argument("--checks", action="store_true"); ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--setpw", action="store_true")
    a = ap.parse_args()
    r = os.path.realpath(a.rootfs) if a.mode == "chroot" else a.rootfs
    os.makedirs(OUT, exist_ok=True)
    rec = dict(label=a.label, mode=a.mode, flags=a.flags, env=a.env, cold=a.cold)
    mounts = []
    if a.mode == "chroot":
        for d in ("proc", "sys", "dev", "dev/pts"):
            subprocess.run(["mount", "--bind", f"/{d}", f"{r}/{d}"], check=False); mounts.append(f"{r}/{d}")
    try:
        if a.setpw:
            rc, secs, out = run_guest(r, a.mode, a.flags, ["/bin/bash", "/opt/hermes-local/bin/hermes-local-passwd", "admin"], stdin=PW + "\n", hostenv=a.env)
            rec["setpw"] = [rc, round(secs, 2)]; print("setpw", rc, round(secs, 1), out[-400:])
        os.makedirs(f"{r}/root/.hermes-local", exist_ok=True)
        open(f"{r}/root/.hermes-local/settings.env", "w").write("HL_HOST=127.0.0.1\nHL_PORT=9119\nHL_API_SERVER=0\nHL_API_PORT=8642\n")
        if a.mode == "proot":
            u = pwd.getpwnam(USER); os.chown(f"{r}/root/.hermes-local/settings.env", u.pw_uid, u.pw_gid)
        if a.cold:
            subprocess.run(["sync"]); open("/proc/sys/vm/drop_caches", "w").write("3\n")
        marker = time.time(); time.sleep(1.1)
        logf = open(f"{OUT}/boot-{a.label}.log", "w", buffering=1)
        argv = ["/bin/bash", "/opt/hermes-local/bin/hermes-local-start"]
        if a.mode == "proot":
            cmd = proot_argv(r, a.flags, guest_env() + argv)
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, user=USER, group=USER,
                                 extra_groups=[], env=host_env(r, a.env), cwd=r, start_new_session=True)
        else:
            cmd = ["chroot", r, "/usr/bin/env", "-i"] + guest_env() + argv
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True,
                                 env={"PATH": "/usr/bin:/bin"})
        ts = time.time()

        def pump():
            for raw in iter(p.stdout.readline, b""):
                logf.write(f"{time.time() - ts:8.2f} {raw.decode(errors='replace')}")
        threading.Thread(target=pump, daemon=True).start()
        t0 = time.time(); t_dash = t_ready = None; failed = None
        while time.time() - t0 < a.timeout:
            if t_dash is None and http_code("http://127.0.0.1:9119/api/auth/providers", 1) in range(200, 500):
                t_dash = time.time() - t0
            txt = open(f"{OUT}/boot-{a.label}.log", errors="replace").read()
            if "HERMES_LOCAL_READY" in txt:
                t_ready = time.time() - t0; break
            if "HERMES_LOCAL_FAILED" in txt:
                failed = txt[txt.find("HERMES_LOCAL_FAILED"):][:200]; break
            if p.poll() is not None:
                failed = f"exited {p.returncode}"; break
            time.sleep(0.1)
        rec.update(t_dash=t_dash and round(t_dash, 2), t_ready=t_ready and round(t_ready, 2), failed=failed)
        print(a.label, "dash", rec["t_dash"], "ready", rec["t_ready"], "failed", failed, flush=True)
        if t_ready and a.checks:
            rec["checks"] = checks(9119)
        if t_ready and a.idle:
            time.sleep(a.settle)
            pids = tree(p.pid); s0 = sample(pids); t1 = time.time()
            st_procs = []
            if a.strace:
                for pid, v in s0.items():
                    if v["comm"].startswith("python") or "python" in v["cmd"].split(" ")[0]:
                        role = "dashboard" if " dashboard" in v["cmd"] else ("gateway" if " gateway" in v["cmd"] else "py")
                        f1 = f"{OUT}/strace-c-{a.label}-{role}-{pid}.txt"; f2 = f"{OUT}/strace-t-{a.label}-{role}-{pid}.txt"
                        st_procs.append(subprocess.Popen(["timeout", str(a.idle), "strace", "-f", "-c", "-p", str(pid), "-o", f1]))
                        st_procs.append(subprocess.Popen(["timeout", "20", "strace", "-f", "-tt", "-e",
                                                          "trace=epoll_wait,epoll_pwait,epoll_pwait2,poll,ppoll,select,pselect6,clock_nanosleep,nanosleep,futex,accept4,recvfrom,connect",
                                                          "-p", str(pid), "-o", f2]))
            # the app's health loop: GET /api/auth/providers every 5 s while Running (EngineService.healthLoop)
            end = time.time() + a.idle
            while time.time() < end:
                http_code("http://127.0.0.1:9119/api/auth/providers", 2); time.sleep(min(5, max(0, end - time.time())))
            for sp in st_procs:
                try: sp.wait(timeout=30)
                except Exception: sp.kill()
            s1 = sample(tree(p.pid)); dt = time.time() - t1
            rows = []; tot = dict(cpu=0.0, wake=0.0, rss=0, pss=0)
            for pid, v1 in s1.items():
                v0 = s0.get(pid)
                if not v0: continue
                cpu = (v1["ticks"] - v0["ticks"]) / HZ / dt * 100; wk = (v1["ctx"] - v0["ctx"]) / dt
                rows.append((pid, v1["comm"], round(cpu, 2), round(wk, 1), v1["rss"] // 1024, v1["pss"] // 1024, v1["thr"], v1["cmd"]))
                tot["cpu"] += cpu; tot["wake"] += wk; tot["rss"] += v1["rss"]; tot["pss"] += v1["pss"]
            with open(f"{OUT}/idle-{a.label}.txt", "w") as f:
                f.write("pid comm cpu% ctxsw/s rssMiB pssMiB threads cmd\n")
                for row in sorted(rows, key=lambda x: -x[5]): f.write(" ".join(map(str, row)) + "\n")
            rec["idle"] = dict(secs=round(dt, 1), cpu_pct=round(tot["cpu"], 2), ctxsw_s=round(tot["wake"], 1),
                               rss_mib=tot["rss"] // 1024, pss_mib=tot["pss"] // 1024, procs=len(rows))
            print(a.label, "idle", rec["idle"], flush=True)
        # stop like the app: SIGTERM, 8 s grace, SIGKILL
        t = time.time()
        try: os.killpg(p.pid, signal.SIGTERM)
        except Exception: pass
        try: p.wait(timeout=8)
        except Exception:
            try: os.killpg(p.pid, signal.SIGKILL)
            except Exception: pass
            p.wait()
        rec["stop_secs"] = round(time.time() - t, 2)
        subprocess.run(["pkill", "-KILL", "-u", USER, "-f", "/opt/hermes"], check=False)
        time.sleep(1)
        if a.snapshot:
            rec["growth"] = du_new(r, marker)
    finally:
        for m in reversed(mounts):
            subprocess.run(["umount", "-l", m], check=False)
    with open(f"{OUT}/boots.jsonl", "a") as f:
        f.write(json.dumps(rec) + "\n")


if __name__ == "__main__":
    main()
