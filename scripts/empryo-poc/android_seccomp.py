#!/usr/bin/env python3
"""Approximate Android's app (zygote) seccomp policy on a Linux host, to test glibc/Bun binaries
the way an Android app process sees them, without a phone.

  android_seccomp.py <aosp libc dir> <arch: arm64|x86_64> <out.c>   -> C source of `sec-android`
  android_seccomp.py --allowed <aosp libc dir> <arch>               -> print the allowed syscall names
  android_seccomp.py --check <aosp libc dir> <arch> <strace -c file>... -> syscalls the policy would trap

Allowed = SYSCALLS.TXT + SECCOMP_ALLOWLIST_COMMON + SECCOMP_ALLOWLIST_APP for this arch, minus the
APP/COMMON blocklists (bionic genseccomp.py does the same). Anything else gets SECCOMP_RET_TRAP
(SIGSYS), which is what Android does for app processes. Approximation: the real policy also has
per-argument rules (setresuid etc.) and priority ordering, which do not matter here.

`sec-android CMD ARGS...` sets no_new_privs, installs the filter and execs CMD, so CMD and every
child (proot included) run under it, like everything an Android app forks.
"""
import os, re, sys

ARCH_TAGS = {"arm64": {"all", "lp64", "arm64"}, "x86_64": {"all", "lp64", "x86_64"}}


def parse(path, arch):
    out = set()
    if not os.path.exists(path):
        return out
    for line in open(path):
        line = line.split("#")[0].strip()
        if not line or "(" not in line:
            continue
        head, _, rest = line.partition("(")
        tags = set(re.split(r"[\s,]+", rest.rsplit(")", 1)[-1].strip()))
        if not (tags & ARCH_TAGS[arch]):
            continue
        tok = head.split()[-1]
        names = tok.split(":", 1)[1] if ":" in tok else tok
        for n in names.split(","):
            n = n.strip("*").strip()
            if n:
                out.add(n)
    return out


def allowed(d, arch):
    a = set()
    for f in ("SYSCALLS.TXT", "SECCOMP_ALLOWLIST_COMMON.TXT", "SECCOMP_ALLOWLIST_APP.TXT"):
        a |= parse(os.path.join(d, f), arch)
    b = set()
    for f in ("SECCOMP_BLOCKLIST_APP.TXT", "SECCOMP_BLOCKLIST_COMMON.TXT"):
        b |= parse(os.path.join(d, f), arch)
    return sorted(a - b)


C_TMPL = r"""
#define _GNU_SOURCE
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
static const int allowed[] = {
%s
};
int main(int argc, char **argv) {
  if (argc < 2) { fprintf(stderr, "usage: sec-android CMD [ARGS...]\n"); return 2; }
  size_t n = sizeof(allowed) / sizeof(allowed[0]);
  struct sock_filter *f = calloc(2 * n + 6, sizeof(*f));
  size_t i = 0;
  f[i++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
  f[i++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, %s, 1, 0);
  f[i++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
  f[i++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
  for (size_t k = 0; k < n; k++) {
    f[i++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, allowed[k], 0, 1);
    f[i++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  }
  f[i++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
  struct sock_fprog prog = { .len = (unsigned short)i, .filter = f };
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) { perror("no_new_privs"); return 1; }
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog)) { perror("seccomp"); return 1; }
  execvp(argv[1], argv + 1);
  perror("execvp");
  return 127;
}
"""


def gen_c(names, arch):
    body = "\n".join(f"#ifdef __NR_{n}\n  __NR_{n},\n#endif" for n in names)
    audit = "AUDIT_ARCH_AARCH64" if arch == "arm64" else "AUDIT_ARCH_X86_64"
    return C_TMPL % (body, audit)


def strace_names(path):
    out = set()
    for line in open(path):
        p = line.split()
        if len(p) >= 4 and p[-1] not in ("syscall", "total") and re.match(r"^[0-9.]+$", p[0]):
            out.add(p[-1])
    return out


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[0] == "--allowed":
        print("\n".join(allowed(a[1], a[2])))
    elif a[0] == "--check":
        ok = set(allowed(a[1], a[2]))
        used = set()
        for f in a[3:]:
            used |= strace_names(f)
        bad = sorted(used - ok)
        print("used:", len(used), "trapped-by-android-app-policy:", " ".join(bad) if bad else "(none)")
    else:
        d, arch, out = a
        names = allowed(d, arch)
        open(out, "w").write(gen_c(names, arch))
        print(f"{len(names)} allowed syscalls -> {out}")
