#!/usr/bin/env bash
# Runs only inside a ui2-test Job pod (deploy/ui2-test/40-test-run-job.yaml), started by tools/delivery/hosta_test_run.py
# (docs/design/HOSTA_TEST_NAMESPACE_2026_11_24.md, DRAFT). The pod has no network: Gradle runs --offline from the
# image's read-only dependency cache, vitest from the image's node_modules, PostgreSQL 16 is a loopback sidecar.
#
# Environment (set by the host side, validated there and again here):
#   TEST_JAVA         none | scoped | full     scoped runs TEST_GRADLE_TASKS; full runs every module's test task
#   TEST_GRADLE_TASKS ":worker:test :architecture-tests:test ..."   (scoped only; each token is :<module>:test)
#   TEST_INTEGRATION  0 | 1                    :integration-tests:test against the sidecar
#   TEST_VITEST       0 | 1                    vitest run in ui2/frontend
# stdout carries exactly one line: TESTRUN_SUMMARY {json} (TestRunSummary.java). All raw Gradle/vitest output stays in
# /tmp, an emptyDir that dies with the pod. Exit: 0 PASS, 1 FAIL, 2 ERROR, 3 IMAGE_STALE.
#
#   test_runner_pod.sh --fingerprint <root>   prints the dependency fingerprint of the tree at <root> (also used once,
#                                             by ui2/Containerfile.test-runner, to record what the image was seeded from)
set -uo pipefail

fingerprint() {
  local hasher=(sha256sum)
  command -v sha256sum >/dev/null 2>&1 || hasher=(shasum -a 256)
  (
    cd "$1" || exit 2
    for f in ui2/*.gradle.kts ui2/*/build.gradle.kts ui2/*/gradle.lockfile ui2/settings-gradle.lockfile \
             ui2/gradle.properties ui2/gradle/libs.versions.toml ui2/gradle/verification-metadata.xml \
             ui2/gradle/wrapper/gradle-wrapper.properties ui2/frontend/package.json ui2/frontend/package-lock.json; do
      if [ -f "$f" ]; then printf '%s\n' "$f"; fi
    done | LC_ALL=C sort -u | while IFS= read -r f; do "${hasher[@]}" "$f"; done
  )
}

if [ "${1:-}" = --fingerprint ]; then
  fingerprint "${2:-.}"
  exit $?
fi

workspace=${TEST_WORKSPACE:-/workspace}
seed=${TEST_RUNNER_SEED:-/opt/test-runner/seed.sha256}
summary_java=$workspace/.runner/TestRunSummary.java
# Overridable only so tools/tests/test_hosta_test_run.py can run this script off-cluster; the Job never sets them.
scratch=${TEST_SCRATCH:-/tmp}
gradle_bin=${TEST_GRADLE_BIN:-/opt/gradle/bin/gradle}
node_modules=${TEST_NODE_MODULES:-/opt/test-runner/frontend/node_modules}
results=$scratch/results
exec 2>"$scratch/runner-setup.log"

error_line() { printf 'TESTRUN_SUMMARY {"status":"%s","reason":"%s","phases":[]}\n' "$1" "$2"; }

case "${TEST_JAVA:-none}" in none|scoped|full) ;; *) error_line ERROR bad_scope; exit 2 ;; esac
for flag in "${TEST_INTEGRATION:-0}" "${TEST_VITEST:-0}"; do
  case "$flag" in 0|1) ;; *) error_line ERROR bad_scope; exit 2 ;; esac
done
tasks=()
if [ "${TEST_JAVA:-none}" = scoped ]; then
  for task in ${TEST_GRADLE_TASKS:-}; do
    if [[ ! "$task" =~ ^:[a-z0-9-]+:test$ ]] || [ "$task" = :integration-tests:test ]; then
      error_line ERROR bad_scope; exit 2
    fi
    tasks+=("$task")
  done
  [ "${#tasks[@]}" -gt 0 ] || { error_line ERROR bad_scope; exit 2; }
elif [ "${TEST_JAVA:-none}" = full ]; then
  tasks=(test -x :integration-tests:test)
fi

# The image must have been seeded from exactly these dependency files; otherwise nothing runs.
if [ ! -s "$seed" ] || ! fingerprint "$workspace" | cmp -s - "$seed"; then
  error_line IMAGE_STALE dependency_files_changed
  exit 3
fi

mkdir -p "$results" "${GRADLE_USER_HOME:-/tmp/gradle-home}"
gradle=("$gradle_bin" --offline --no-daemon --console=plain -PfrontendPrebuilt=true)
phases=()

# A failed phase's reason token, read from its own raw log (which never leaves the pod).
reason_of() {
  if grep -qE "available for offline mode|Could not resolve all (files|dependencies)|Could not resolve [a-z]" "$1"; then
    echo offline_miss
  elif grep -qE "Execution failed for task ':[a-z0-9-]+:compile(Test)?Java'|error: " "$1"; then
    echo compile
  else
    echo none
  fi
}

run_phase() {  # name log results... -- command...
  local name=$1 log=$2; shift 2
  local paths=()
  while [ "$1" != -- ]; do paths+=("$1"); shift; done
  shift
  local start rc=0
  start=$(date +%s)
  "$@" >"$log" 2>&1 </dev/null || rc=$?
  local reason=none
  [ "$rc" = 0 ] || reason=$(reason_of "$log")
  local joined
  joined=$(IFS=,; printf '%s' "${paths[*]}")
  phases+=("$name=$rc:$(( $(date +%s) - start )):$reason:$joined")
}

cd "$workspace/ui2" || { error_line ERROR no_source; exit 2; }
if [ "${#tasks[@]}" -gt 0 ]; then
  java_dirs=()
  for module in */build.gradle.kts; do
    module=${module%%/*}
    [ "$module" = integration-tests ] || java_dirs+=("$workspace/ui2/$module/build/test-results/test")
  done
  run_phase java "$scratch/java.log" "${java_dirs[@]}" -- "${gradle[@]}" --continue "${tasks[@]}"
fi
if [ "${TEST_INTEGRATION:-0}" = 1 ]; then
  run_phase integration "$scratch/integration.log" "$workspace/ui2/integration-tests/build/test-results/test" -- \
    "${gradle[@]}" :integration-tests:test
fi
if [ "${TEST_VITEST:-0}" = 1 ]; then
  if [ -d "$node_modules" ] && [ ! -e "$workspace/ui2/frontend/node_modules" ]; then
    ln -s "$node_modules" "$workspace/ui2/frontend/node_modules"
  fi
  cd "$workspace/ui2/frontend" || { error_line ERROR no_source; exit 2; }
  run_phase vitest "$scratch/vitest.log" "$results/vitest-junit.xml" -- \
    ./node_modules/.bin/vitest run --reporter=junit --outputFile="$results/vitest-junit.xml"
fi
[ "${#phases[@]}" -gt 0 ] || { error_line ERROR empty_scope; exit 2; }

java "$summary_java" "${phases[@]}" || exit $?
