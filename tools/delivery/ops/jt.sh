#!/bin/bash
# Full Java + frontend test in a given worktree; prints totals and failures.
# Usage: jt.sh <worktree> [scope-json] [base-ref]
#   scope-json  from tools/delivery/pretest_scope.py; empty or "full" runs everything
#   base-ref    the ref the ship fetched main into (refs/shipq/<log>); the diff for the project/ check is taken against
#               THIS ref, never against origin/main, which can be stale (2026-11-24 review: ProjectPlanReaderTest was
#               skipped). No base ref (or an unknown one) = the check runs: fail closed.
# Review 2026-11-24 fixes: vitest that crashes before listing failed files fails the pretest (it passed before);
# Gradle runs with --no-daemon; only tests on flaky_allowlist.txt are ever re-run (jt_java_results.py, vitest_gate).
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
JT_TMP=${NEXUS_JT_TMP:-/tmp}

jt_gradle() { "${NEXUS_GRADLE:-./gradlew}" --no-daemon "$@"; }

jt_scope_tasks() { printf '%s' "$1" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin); print(" ".join(d["gradle_tasks"]) if d.get("scope")=="scoped" else "")
except Exception: print("")'; }
jt_scope_frontend() { printf '%s' "$1" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin); print("1" if d.get("scope")!="scoped" or d.get("frontend") else "0")
except Exception: print("1")'; }

# jt_project_changed <worktree> <base-ref>: 0 when project/ changed against the ref (or the ref is missing/unknown).
jt_project_changed() {
  local wt=$1 base=${2:-}
  if [ -z "$base" ] || ! git -C "$wt" rev-parse -q --verify "$base^{commit}" >/dev/null 2>&1; then return 0; fi
  git -C "$wt" diff --name-only "$base...HEAD" | grep -q '^project/'
}

jt_main() {
  local wt=$1 scope="${2:-}" base="${3:-${NEXUS_JT_BASE:-}}" tasks front name grc vo vrc
  name=$(basename "$wt")
  tasks=$(jt_scope_tasks "$scope"); front=$(jt_scope_frontend "$scope")
  # GitHub's validate runs the project-state checks; run them here too (seconds) so a budget or governance failure stops the
  # ship before the 8-minute preview (2026-11-23: six batches failed validate on project/build_history.json size).
  ( cd "$wt" && { python3 -m pytest -q -p no:cacheprovider tools/tests/test_live_project_governance.py tools/tests/test_project_files_budget.py tools/tests/test_cold_start_budget.py && python3 tools/delivery/build_history_index.py --check && python3 tools/delivery/project_queue.py check; } > "$JT_TMP/tools-$name.log" 2>&1 ) \
    && echo tools-ok || { echo tools-FAIL; grep -E "^(FAILED|ERROR)|Error" "$JT_TMP/tools-$name.log" | head -5; }
  # A project/ change is read by the Java ProjectPlanReaderTest (2026-11-23: an archive move broke main); run it.
  if jt_project_changed "$wt" "$base"; then
    ( cd "$wt/ui2" && jt_gradle -q :service:test --tests '*ProjectPlanReaderTest*' --offline > "$JT_TMP/ppr-$name.log" 2>&1 ) \
      && echo ppr-ok || echo "tools-FAIL ProjectPlanReaderTest"
  fi
  cd "$wt/ui2" || return 1
  find . -path '*/build/test-results/test/*.xml' -delete 2>/dev/null
  if [ -n "$tasks" ]; then jt_gradle $tasks --continue -q; else jt_gradle test --continue -q -x :integration-tests:test; fi > "$JT_TMP/jt-$name.log" 2>&1
  grc=$?
  grep -oE "Execution failed for task .:[a-z-]+:compile(Test)?Java." "$JT_TMP/jt-$name.log" | sed 's/^/FAIL COMPILE /'
  python3 "$OPS_DIR/jt_java_results.py" "$wt/ui2" "$grc"
  [ "$front" = 1 ] || { echo "frontend tests skipped (scoped: no frontend change)"; return 0; }
  cd frontend || return 1
  [ -d node_modules ] || npm ci --silent >/dev/null 2>&1
  # A failing vitest run gates the merge (376 reached the merge as a passing FAIL line, 2026-11-24). Fail closed: a vitest
  # that crashes before it lists a failed file is a failure too (it passed as "FLAKY" before). Only allowlisted files get
  # one re-run (vitest_gate in ops_lib.sh).
  vo=$JT_TMP/vitest-$name.log; npx vitest run > "$vo" 2>&1; vrc=$?
  grep -E "Tests |FAIL" "$vo" | head -20
  vitest_gate "$vo" "$vrc" "$PWD" jt
  npx tsc --noEmit -p . > "$JT_TMP/tsc-$name.log" 2>&1 && echo tsc-ok || { echo tsc-FAIL; head -20 "$JT_TMP/tsc-$name.log"; }
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then jt_main "$@"; fi
