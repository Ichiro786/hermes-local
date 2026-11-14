#!/system/bin/sh
# sample.sh <uid>  (runs on the emulator as root) raw /proc snapshot of every process of one uid;
# apptree.py turns two snapshots into CPU %, context switches/s and RSS.
for d in /proc/[0-9]*; do
  [ "$(stat -c %u "$d" 2>/dev/null)" = "$1" ] || continue
  echo "P ${d#/proc/}"
  echo "S $(cat "$d/stat" 2>/dev/null)"
  echo "C $(tr '\0' ' ' <"$d/cmdline" 2>/dev/null)"
  grep VmRSS "$d/status" 2>/dev/null | sed 's/^/R /'
  cat "$d"/task/*/status 2>/dev/null | grep ctxt_switches | sed 's/^/T /'
done
