#!/bin/bash
# Names lanes and deployments by date + daily sequence, plus an all-time counter (PO 2026-11-23).
# Usage: counter.sh L|D <item-name>   -> prints e.g. "L20261123-07 470"  (label, all-time number)
# Registry: $NEXUS_OPS_STATE/counters/registry.tsv  (time, label, all-time number, item name)
set -e
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
k=${1:?L or D}; item=${2:-}; C=$OPS_STATE/counters; mkdir -p "$C"; d=$(date +%Y%m%d)
exec 9>"$C/.lock"; while ! mkdir "$C/.lockdir" 2>/dev/null; do sleep 0.2; done; trap 'rmdir "$C/.lockdir"' EXIT
t=$(( $(cat "$C/$k.total" 2>/dev/null || echo 0) + 1 )); echo $t > "$C/$k.total"
n=$(( $(cat "$C/$k-$d" 2>/dev/null || echo 0) + 1 )); echo $n > "$C/$k-$d"
lbl=$(printf '%s%s-%02d' "$k" "$d" "$n")
printf '%s\t%s\t%s\t%s\n' "$(date '+%F %T')" "$lbl" "$t" "$item" >> "$C/registry.tsv"
echo "$lbl $t"
