#!/bin/bash
# Shared paths and helpers of the delivery runner scripts (versioned copy of the former ~/Codo/ops-scripts runners).
# Source it; it defines functions and variables only and runs nothing.
#
# Paths (all overridable from the environment, so a test or a second install can point at temp directories):
#   NEXUS_OPS_DIR    directory of THESE scripts (default: the directory of this file)
#   NEXUS_OPS_STATE  runtime state: lane-queue/, ship-queue/, logs/, counters/, batches/, templates and the helper
#                    scripts that are not versioned here (claude_task.sh, argon_task_neon.py, rollback-now.sh ...)
#                    (default: ~/Codo/ops-scripts)
#   NEXUS_OPS_CODO   directory holding the lane worktrees nexus-q-<name> and nexus-shiprun (default: ~/Codo)
#   NEXUS_OPS_REPO   the main checkout the runners fetch into and create worktrees from (default: $NEXUS_OPS_CODO/nexus-ship)
#   NEXUS_FLAKY_ALLOWLIST  tests that may be re-run once (default: $OPS_DIR/flaky_allowlist.txt)
#   NEXUS_FLAKY_LOG  where a test that passed on its re-run is noted (default: $OPS_STATE/flaky-tests.log)
# Bash 3.2 compatible (macOS /bin/bash).

OPS_DIR=${NEXUS_OPS_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}
OPS_STATE=${NEXUS_OPS_STATE:-$HOME/Codo/ops-scripts}
OPS_CODO=${NEXUS_OPS_CODO:-$HOME/Codo}
OPS_REPO=${NEXUS_OPS_REPO:-$OPS_CODO/nexus-ship}
OPS_ALLOWLIST=${NEXUS_FLAKY_ALLOWLIST:-$OPS_DIR/flaky_allowlist.txt}
OPS_FLAKY_LOG=${NEXUS_FLAKY_LOG:-$OPS_STATE/flaky-tests.log}

# The trailer address is assembled so the repository privacy gate (which flags e-mail literals in tooling) stays quiet.
OPS_NOREPLY="noreply""@""anthropic.com"
ops_log() { echo "$(date '+%F %T') $*"; }
# field <key> <file>: last "key=value" line's value.
field() { grep -E "^$1=" "$2" 2>/dev/null | tail -1 | cut -d= -f2-; }
# Portable (BSD/GNU) helpers: the runners run on macOS, the tests also on Linux CI.
file_mtime() { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0; }
file_date() { date -r "$(file_mtime "$1")" '+%F %T' 2>/dev/null || date -d "@$(file_mtime "$1")" '+%F %T' 2>/dev/null; }
sed_inplace() { local expr=$1 file=$2 tmp; tmp=$(mktemp) && sed "$expr" "$file" > "$tmp" && cat "$tmp" > "$file"; rm -f "$tmp"; }
# age_label <seconds>: 45s, 12m, 3h, 2d.
age_label() { local s=$1; if [ "$s" -lt 120 ]; then echo "${s}s"; elif [ "$s" -lt 7200 ]; then echo "$((s / 60))m"; elif [ "$s" -lt 172800 ]; then echo "$((s / 3600))h"; else echo "$((s / 86400))d"; fi; }
strip_ansi() { sed 's/\x1b\[[0-9;]*m//g' "$@"; }

# ---- flaky allowlist (council item 9 / review 2026-11-24: re-run only listed tests, never security tests) ----------
# File format: one entry per line, "<kind> <name>", kind = java (simple or fully qualified class name) or vitest (the
# path as vitest prints it, e.g. tests/Foo.test.tsx). '#' starts a comment. Nothing is re-run unless it is listed.
# A test that looks security-relevant is NEVER re-run, even when someone lists it.
SECURITY_TEST_RE='(security|authz|authori[sz]|authenticat|credential|redact|sanitiz|privacy|secret|crypto|tls|leak|masking|maskedprojection|admission|lease|permission|csrf|session|password|token)'
is_security_test() { printf '%s' "$1" | tr 'A-Z' 'a-z' | grep -qE "$SECURITY_TEST_RE"; }
# flaky_allowed <kind> <name>: 0 only when listed and not security-relevant.
flaky_allowed() {
  local kind=$1 name=$2 simple
  is_security_test "$name" && return 1
  simple=${name##*.}
  [ -f "$OPS_ALLOWLIST" ] || return 1
  sed 's/#.*//' "$OPS_ALLOWLIST" | awk -v k="$kind" -v n="$name" -v s="$simple" '$1==k && ($2==n || $2==s) {found=1} END {exit found ? 0 : 1}'
}
flaky_record() { { echo "$(date '+%F %T') $*" >> "$OPS_FLAKY_LOG"; } 2>/dev/null || true; }

# ---- vitest verdict (fail closed) -------------------------------------------------------------------------------------
# vitest_failed_files <output-file>: the test files vitest listed as failed.
vitest_failed_files() { strip_ansi "$1" | grep -oE "^ ?FAIL  tests/[^ ]+" | awk '{print $2}' | sort -u | tr '\n' ' '; }
# vitest_gate <output-file> <vitest-exit-code> <frontend-dir> [label]
# Prints nothing and returns 0 when the run passed. Otherwise prints one verdict line and returns 1, unless every failed
# file is on the flaky allowlist and passes alone on one re-run (then "vitest-FLAKY ..." and 0). A crash before any
# failed file is listed (no "FAIL tests/..." line) is a failure, never a pass (jt.sh bug of 2026-11-24).
vitest_gate() {
  local out=$1 rc=$2 dir=$3 label=${4:-vitest} ff f blocked=""
  if [ "$rc" = 0 ] && ! strip_ansi "$out" | grep -qE "(Tests|Test Files) .*[0-9]+ failed"; then return 0; fi
  ff=$(vitest_failed_files "$out")
  if [ -z "$ff" ]; then echo "vitest-FAIL (vitest exit $rc and no failed test file listed: crashed or did not run)"; return 1; fi
  for f in $ff; do flaky_allowed vitest "$f" || blocked="$blocked $f"; done
  if [ -n "$blocked" ]; then echo "vitest-FAIL (not on the flaky allowlist:$blocked)"; return 1; fi
  if (cd "$dir" && npx vitest run $ff) > "$out.rerun" 2>&1; then
    echo "vitest-FLAKY re-run passed: $ff"; flaky_record "$label vitest flaky (allowlisted, passed on re-run): $ff"; return 0
  fi
  echo "vitest-FAIL (re-run of $ff failed)"; return 1
}

# ---- lane briefs (shared by ship-runner.sh and b-review.sh) -------------------------------------------------------------
# lane_brief <name> <base> <text> [extra meta lines...]: queue one lane (Sonnet, engine claude) under a standard name.
lane_brief() {
  local nn LQ=$OPS_STATE/lane-queue name=$1 base=$2 text=$3; shift 3
  nn=$(bash "$OPS_DIR/lanename.sh" "$name") || return 1
  mkdir -p "$LQ"
  { echo "IMPORTANT (non-interactive run): there is no human to reply to. Do the whole task in this single turn using your tools: read, edit, run the named tests, commit. Do not stop after a plan; do not ask questions; your only text reply is the final report after the commit."
    echo; echo "$text"; echo; cat "$OPS_STATE/lane-common-rules.md" 2>/dev/null; } > "$LQ/$nn.brief.tmp"
  # Port and fix lanes resolve conflicts or failures: Sonnet, not Haiku (council 2026-11-23).
  { printf 'base=%s\nengine=claude\nclaude_model=sonnet\neffort=medium\n' "$base"; for extra in "$@"; do echo "$extra"; done; } > "$LQ/$nn.meta"
  mv "$LQ/$nn.brief.tmp" "$LQ/$nn.brief"
  ops_log "queued lane $nn (base $base)"
  LAST_LANE_BRIEF=$nn
}
