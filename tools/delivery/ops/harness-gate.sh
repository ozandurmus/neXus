#!/bin/bash
# Harness gate (PO decision record 2026-11-24 D3, wired 2026-11-25): a lane that ended exit 0 reaches the ship queue only
# through a passing lane harness report bound to its tree. Started by lane-runner.sh in the background, after the lane's
# brief and meta moved to lane-queue/done/:
#   harness-gate.sh <lane-name>
# Outcome by the harness's typed exit code (tools/delivery/lane_harness.py):
#   0  PASS          -> ship-queue entry (harness=PASS, harness_report=..., then the tier B review as before)
#   10 CHECK_FAILED  -> one repair turn: the brief is re-queued under a standard lane name with the failing checks
#                       appended (base = the lane's branch, repair_of=<lane>); a second failure (the meta carries
#                       repair_of=) is held for the PO session (ship-queue/held, harness=FAIL)
#   12 CONFLICT      -> queued as usual (harness=CONFLICT): the ship runner's port path handles it
#   20 HELD_FOR_PO   -> ship-queue/held with the reason
#   30 NO_CHANGE     -> nothing to ship
#   50 harness error -> retried once, then queued with harness=ERROR (the ship pretest still runs; never blocks on us)
#   other            -> queued with harness=ERROR (same: the pretest gates it)
# Harness runs are serialized (one at a time, mkdir lock) so three finishing lanes do not load the Mac at once.
# Versioned copy (tools/delivery/ops) of the live 2026-11-25 script; paths come from ops_lib.sh. Differences: the ship
# entry keeps review_returned=1 (a returned review is reviewed again), and the file only defines functions when sourced.
# Test seams: NEXUS_LANE_HARNESS (the harness script; default: the lane worktree's own tools/delivery/lane_harness.py),
# NEXUS_HARNESS_LOCK_WAIT_S (lock poll interval, default 15).
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
Q=$OPS_STATE/lane-queue; SQ=$OPS_STATE/ship-queue; L=$OPS_STATE/logs; REPO=$OPS_REPO
GATE_LOCK=$OPS_STATE/.harness-lock
glog() { mkdir -p "$L"; ops_log "$@" >> "$L/lane-runner.log"; }

# gate_lock / gate_unlock: one harness run at a time; a lock older than 40 minutes belongs to a dead run.
gate_lock() {
  until mkdir "$GATE_LOCK" 2>/dev/null; do
    [ $(( $(date +%s) - $(file_mtime "$GATE_LOCK") )) -gt 2400 ] && [ -d "$GATE_LOCK" ] && rmdir "$GATE_LOCK" 2>/dev/null
    sleep "${NEXUS_HARNESS_LOCK_WAIT_S:-15}"
  done
}
gate_unlock() { rmdir "$GATE_LOCK" 2>/dev/null; return 0; }

# gate_integration <worktree>: hosta when the lane's own manifest hands integration to the ui2-test runner on HOST-A
# (PO 2026-11-25); older bases keep the harness's auto choice (local PostgreSQL).
gate_integration() { if grep -q 'hosta_test_run.py' "$1/tools/delivery/checks.yaml" 2>/dev/null; then echo hosta; else echo auto; fi; }

# gate_run_harness <name> <worktree> <meta> <report>: runs the harness once; prints its exit code.
gate_run_harness() {
  local n=$1 wt=$2 meta=$3 report=$4 allow="" harness
  harness=${NEXUS_LANE_HARNESS:-$wt/tools/delivery/lane_harness.py}
  grep -q '^allow_paths=1' "$meta" 2>/dev/null && allow=--allow-paths
  python3 "$harness" --worktree "$wt" --fetch --meta "$meta" --lane-name "$n" --queue-dir "$SQ" --report "$report" \
    --log-dir "$L/harness-$n" --jobs 3 --integration "$(gate_integration "$wt")" --keep-going $allow > "$L/harness-$n.out" 2>&1
  echo $?
}

# gate_ship_entry <name> <meta> <harness-tag> <report> <dir>: the ship entry, bound to the harness report.
gate_ship_entry() {
  local n=$1 meta=$2 tag=$3 report=$4 dir=$5
  mkdir -p "$dir"
  { echo "branch=lane/q-$n"; echo "name=$n"; grep -E '^(allow_paths|title|review_returned)=' "$meta" 2>/dev/null
    echo "harness=$tag"; echo "harness_report=$report"; } > "$dir/$n.ship"
}

# gate_failed_checks <report>: "- <check>: <detail>" for every failed or errored check of the report.
gate_failed_checks() {
  python3 -c "import json,sys
try:
    r=json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
print('\n'.join('- '+c.get('id','?')+': '+str(c.get('detail',''))[:300] for c in r.get('checks',[]) if c.get('status') in ('fail','error')))" "$1" 2>/dev/null
}

# gate_tier_b <name>: production code outside the Estate UI gets a review before batching (PO 2026-11-23).
gate_tier_b() {
  local n=$1 bfiles
  bfiles=$(git -C "$REPO" diff --name-only "$(git -C "$REPO" merge-base "lane/q-$n" origin/main 2>/dev/null)" "lane/q-$n" 2>/dev/null \
    | grep -E '^ui2/[^/]+/src/main/|^ui2/frontend/src/' | grep -vE '^ui2/frontend/src/estate/')
  [ -n "$bfiles" ] || return 1
  echo "review=pending" >> "$SQ/$n.ship"
  ( nohup /bin/bash "$OPS_DIR/b-review.sh" "$n" > "$L/b-review-$n.nohup" 2>&1 & )
  return 0
}

# gate_repair <name> <meta> <report> <fails>: queue the one repair turn on the lane's own branch.
gate_repair() {
  local n=$1 meta=$2 report=$3 fails=$4 g rn
  g=$(echo "$n" | grep -oE 'G[0-9]+' | head -1)
  rn=$(bash "$OPS_DIR/lanename.sh" fix)-harness-repair-${g:-lane}
  cp "$Q/done/$n.brief" "$Q/$rn.tmp" 2>/dev/null || cp "$Q/started/$n.brief" "$Q/$rn.tmp" || return 1
  printf '\n\n## Repair turn (lane harness, %s)\nThe harness rejected your commit. Fix exactly these checks on your branch, rerun them, commit:\n%s\nHarness report: %s (full logs in %s).\n' \
    "$(date '+%F %T')" "$fails" "$report" "$L/harness-$n" >> "$Q/$rn.tmp"
  { grep -vE '^(base|repair_of)=' "$meta"; echo "base=lane/q-$n"; echo "repair_of=$n"; } > "$Q/$rn.meta"
  mv "$Q/$rn.tmp" "$Q/$rn.brief"
  GATE_REPAIR_NAME=$rn
}

# gate_main <name>: run the harness on a finished lane and act on its typed exit code.
gate_main() {
  local n=$1 wt meta report t0 rc secs tag fails
  wt=$OPS_CODO/nexus-q-$n; meta=$Q/done/$n.meta; [ -e "$meta" ] || meta=$Q/started/$n.meta
  mkdir -p "$L" "$SQ"
  gate_lock; trap gate_unlock EXIT
  report=$L/harness-$n.json; t0=$(date +%s)
  rc=$(gate_run_harness "$n" "$wt" "$meta" "$report")
  [ "$rc" = 50 ] && { glog "HARNESS $n error 50, retrying once"; rc=$(gate_run_harness "$n" "$wt" "$meta" "$report"); }
  secs=$(( $(date +%s) - t0 ))
  case "$rc" in
    0|12|50)
      tag=PASS; [ "$rc" = 12 ] && tag=CONFLICT; [ "$rc" = 50 ] && tag=ERROR
      gate_ship_entry "$n" "$meta" "$tag" "$report" "$SQ"
      if gate_tier_b "$n"; then glog "HARNESS $tag $n in ${secs}s -> ship-queued (tier B review pending)"
      else glog "HARNESS $tag $n in ${secs}s -> ship-queued"; fi;;
    10)
      fails=$(gate_failed_checks "$report")
      if grep -q '^repair_of=' "$meta" 2>/dev/null; then
        gate_ship_entry "$n" "$meta" FAIL "$report" "$SQ/held"
        glog "HARNESS FAIL $n (after repair turn) in ${secs}s -> held for PO session: $(echo "$fails" | head -2 | tr '\n' ' ' | cut -c1-200)"
      elif gate_repair "$n" "$meta" "$report" "$fails"; then
        glog "HARNESS FAIL $n in ${secs}s -> repair turn queued ($GATE_REPAIR_NAME): $(echo "$fails" | head -2 | tr '\n' ' ' | cut -c1-200)"
      else
        gate_ship_entry "$n" "$meta" FAIL "$report" "$SQ/held"
        glog "ALERT HARNESS FAIL $n: no brief for a repair turn -> held for PO session"
      fi;;
    20)
      gate_ship_entry "$n" "$meta" HELD "$report" "$SQ/held"
      glog "HARNESS HELD $n in ${secs}s -> held for PO session: $(tail -3 "$L/harness-$n.out" 2>/dev/null | tr '\n' ' ' | cut -c1-200)";;
    30) glog "HARNESS NO_CHANGE $n: nothing to ship";;
    *)  # The live script skipped the tier B review here; an unexpected harness exit must not bypass it.
        gate_ship_entry "$n" "$meta" ERROR "$report" "$SQ"; gate_tier_b "$n"
        glog "HARNESS exit $rc $n in ${secs}s -> ship-queued (harness=ERROR; pretest still gates)";;
  esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then set -u; gate_main "${1:?lane name}"; fi
