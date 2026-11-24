#!/bin/bash
# Main-green gate (PO 2026-11-24 "Go"): after every change of origin/main, run the fast checks on main itself
# (project-state tools tests, frontend unit tests, tsc). Red main -> ship-queue/MAIN_RED: ship-runner then ships only
# items marked mainfix=1 (the fix for main), so nothing else lands on a broken main. Green again -> MAIN_RED removed.
# Review 2026-11-24: a failed vitest file is re-run alone ONLY when it is on flaky_allowlist.txt (never a security test);
# a vitest crash that lists no failed file is RED, not "flaky" (vitest_gate in ops_lib.sh).
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
REPO=$OPS_REPO; SQ=$OPS_STATE/ship-queue; LOG=$OPS_STATE/logs/main-green.log
WT=${NEXUS_MAIN_GREEN_WT:-$OPS_CODO/wt-main-green}; STATE=$OPS_STATE/counters/main-green.last
MG_TMP=${NEXUS_MAIN_GREEN_TMP:-/tmp}
log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

# mg_check_sha <sha>: run the checks on the worktree already at <sha>; prints the failed check names (empty = green).
mg_check_sha() {
  local sha=$1 fails="" vrc verdict
  ( cd "$WT" && python3 -m pytest -q -p no:cacheprovider tools/tests/test_live_project_governance.py tools/tests/test_project_files_budget.py \
      tools/tests/test_cold_start_budget.py && python3 tools/delivery/build_history_index.py --check && python3 tools/delivery/project_queue.py check ) \
      > "$MG_TMP/main-green-tools.log" 2>&1 || fails="$fails tools"
  ( cd "$WT/ui2/frontend" && npx tsc --noEmit -p . ) > "$MG_TMP/main-green-tsc.log" 2>&1 || fails="$fails tsc"
  ( cd "$WT/ui2/frontend" && npx vitest run --reporter=dot ) > "$MG_TMP/main-green-vitest.log" 2>&1; vrc=$?
  verdict=$(vitest_gate "$MG_TMP/main-green-vitest.log" "$vrc" "$WT/ui2/frontend" "main ${sha:0:8}")
  case "$verdict" in
    vitest-FLAKY*) log "FLAKY ${sha:0:8}: ${verdict#*: }";;
    vitest-FAIL*) fails="$fails vitest"; log "vitest ${sha:0:8}: $verdict";;
  esac
  echo "$fails"
}

mg_once() {
  local sha fails first
  # Own ref, no origin/main update: a concurrent ship fetch must never see "cannot lock ref" (375, 2026-11-24).
  git -C "$REPO" fetch -q --refmap= origin +refs/heads/main:refs/main-green/main 2>/dev/null
  sha=$(git -C "$REPO" rev-parse refs/main-green/main 2>/dev/null)
  if [ -n "$sha" ] && [ "$sha" != "$(cat "$STATE" 2>/dev/null)" ]; then
    [ -d "$WT" ] || git -C "$REPO" worktree add -q --detach "$WT" "$sha"
    git -C "$WT" checkout -q --detach "$sha" && git -C "$WT" clean -qfdx -e ui2/frontend/node_modules >/dev/null
    [ -e "$WT/ui2/frontend/node_modules" ] || ln -s "$REPO/ui2/frontend/node_modules" "$WT/ui2/frontend/node_modules"
    fails=$(mg_check_sha "$sha")
    mkdir -p "$(dirname "$STATE")"; echo "$sha" > "$STATE"
    if [ -n "$fails" ]; then
      first=$(grep -hE "^ FAIL |^FAILED |error TS" "$MG_TMP"/main-green-*.log | head -3 | cut -c1-200 | tr '\n' ';')
      log "RED ${sha:0:8}:$fails | $first"
      [ -e "$SQ/MAIN_RED" ] || log "MAIN_RED set: ship-runner ships only mainfix=1 items"; echo "${sha:0:8}:$fails" > "$SQ/MAIN_RED"
    else
      log "GREEN ${sha:0:8}"
      [ -e "$SQ/MAIN_RED" ] && { rm -f "$SQ/MAIN_RED"; log "MAIN_RED cleared"; }
    fi
  fi
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  mkdir -p "$(dirname "$LOG")" "$SQ"
  while true; do mg_once; sleep 120; done
fi
