#!/bin/bash
# Compact pipeline status (PO 2026-11-23: keep the session context small). Read this, not the raw logs.
# 2026-11-24 review: failed/ and held/ (ship queue) and hold/ and FAILED_WORKTREE (lane queue) are listed with their
# ages, so work that left the pipeline is visible instead of silently sitting in a directory.
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
SQ=$OPS_STATE/ship-queue; LQ=$OPS_STATE/lane-queue; L=$OPS_STATE/logs/ship-runner.log; d=$(date +%F)
cnt() { ls "$@" 2>/dev/null | wc -l | tr -d ' '; }
# aged_list <label> <glob...>: "label N: name (age), ..." for the files that exist.
aged_list() {
  local label=$1 f n items="" now; shift; now=$(date +%s); n=0
  for f in "$@"; do [ -e "$f" ] || continue; n=$((n + 1))
    items="$items$(basename "$f" | sed -E 's/\.(ship|brief|meta)$//') ($(age_label $(( now - $(file_mtime "$f") )))), "
  done
  echo "$label $n${items:+: ${items%, }}"
}
if command -v launchctl >/dev/null 2>&1; then runners=$(launchctl list 2>/dev/null | grep -cE 'overwatch\.(lane|ship)-runner'); else runners=0; fi
echo "runners: $runners/2  paused: $(ls "$SQ/PAUSED" "$LQ/PAUSED" 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"
echo "lanes running $(cnt "$LQ"/started/*.brief)  lane briefs $(cnt "$LQ"/*.brief)  ship queue $(cnt "$SQ"/*.ship)  held $(cnt "$SQ"/held/*.ship)  failed $(cnt "$SQ"/failed/*.ship)  inflight $(cnt -d "$SQ"/inflight/*/)"
aged_list "ship failed" "$SQ"/failed/*.ship
aged_list "ship held" "$SQ"/held/*.ship
aged_list "lane hold" "$LQ"/hold/*.brief
aged_list "lane worktree failed" "$LQ"/done/*.FAILED_WORKTREE
gc() { grep -c "$1" "$L" 2>/dev/null || true; }
shipped=$(gc "^$d.*SHIPPED batch"); failed=$(gc "^$d.*FAILED batch")
echo "today: shipped ${shipped:-0} deploys, failed ${failed:-0}"
echo "priority queue: $(grep -l '^prio=1' "$SQ"/*.ship 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.ship//' | tr '\n' ' ')"
echo "last results:"; grep -E 'SHIPPED batch|FAILED batch|SHIPPING' "$L" 2>/dev/null | tail -5 | cut -c1-150
