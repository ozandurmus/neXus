#!/usr/bin/env bash
# Starts an isolated PostgreSQL 16 test Job, runs host Gradle, then removes it.
set -euo pipefail
case "${1:-}" in
  --help|-h)
    echo "Usage: bash scripts/hosta_integration.sh [--on-host]"
    echo "Default: stream committed source to HOST-A and run there. --on-host: run in the current host checkout."
    echo "No production database or device access. Exit 0=PASS, 1=test failure, 2=runner/setup failure."
    exit 0 ;;
  --on-host|"") ;;
  *) echo "Unknown option" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || exit 2
cd "$(dirname "$0")/.."
if [[ ${1:-} != --on-host ]]; then
  HOST=$(head -1 ~/.config/nexus/hosta)
  # Only tracked committed source is sent; credentials and runtime objects are excluded.
  git archive HEAD | ssh "$HOST" 'set -e; task_dir=$(mktemp -d); trap '\''rm -rf "$task_dir"'\'' EXIT; tar -xf - -C "$task_dir"; bash "$task_dir/scripts/hosta_integration.sh" --on-host' \
    2>&1 | grep -E '^INTEGRATION: (PASS|FAIL|ERROR)' || exit 2
  exit 0
fi
export KUBECONFIG=${KUBECONFIG:-$HOME/.kube/config}
work=$(mktemp -d)
chmod 700 "$work"
run="ui2-integration-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
forward_pid=""
cleanup() {
  result=$?
  trap - EXIT
  [[ -z "$forward_pid" ]] || { kill "$forward_pid" 2>/dev/null || true; wait "$forward_pid" 2>/dev/null || true; }
  if ! kubectl -n ui2-build delete job,networkpolicy,secret "$run" --ignore-not-found --wait=true --timeout=60s >"$work/cleanup.log" 2>&1; then
    echo "INTEGRATION: ERROR (cleanup failed)"
    result=2
  fi
  rm -rf "$work"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 2' INT TERM
trap 'echo "INTEGRATION: ERROR (runner/setup failed; raw output withheld)"' ERR
python3 - "$work" <<'PY'
import pathlib,secrets,sys
p=pathlib.Path(sys.argv[1])/'password'
p.write_text(secrets.token_hex(24)); p.chmod(0o600)
PY
sed "s/INTEGRATION_RUN/$run/g" deploy/ui2-image-build/33-integration-postgres.yaml > "$work/postgres.yaml"
kubectl -n ui2-build create secret generic "$run" --from-file="$work/password" >"$work/setup.log" 2>&1
# Apply the deny-all policy before the Job so there is no unisolated startup window.
kubectl apply -f "$work/postgres.yaml" >>"$work/setup.log" 2>&1
for ((i=0; i<60; i++)); do
  pod=$(kubectl -n ui2-build get pod -l "nexus-integration-run=$run" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  [[ -z "$pod" ]] || break
  sleep 1
done
[[ -n "${pod:-}" ]]
kubectl -n ui2-build wait --for=condition=Ready "pod/$pod" --timeout=180s >>"$work/setup.log" 2>&1
kubectl -n ui2-build port-forward --address=127.0.0.1 "pod/$pod" :5432 >"$work/forward.log" 2>&1 &
forward_pid=$!
for ((i=0; i<60; i++)); do
  port=$(sed -nE 's/^Forwarding from 127\.0\.0\.1:([0-9]+) -> 5432$/\1/p' "$work/forward.log" | head -1)
  [[ -z "$port" ]] || break
  kill -0 "$forward_pid"
  sleep 1
done
[[ -n "${port:-}" ]]
export UI2_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/postgres"
export UI2_TEST_DB_PASSWORD_FILE="$work/password"
cd ui2
# Bypass the frontend npm-ci task: database tests do not need frontend assets.
if ./gradlew --no-daemon -PfrontendPrebuilt=true :integration-tests:test --rerun-tasks >"$work/gradle.log" 2>&1; then
  python3 - <<'PY'
import pathlib,xml.etree.ElementTree as ET
reports=list(pathlib.Path('integration-tests/build/test-results/test').glob('TEST-*.xml'))
assert reports, 'missing reports'
counts={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in reports) for k in ('tests','failures','errors','skipped')}
assert counts['tests'] and not any(counts[k] for k in ('failures','errors','skipped')), 'failed or skipped tests'
print('INTEGRATION: PASS (tests=%d; skipped=0)' % counts['tests'])
PY
else
  echo "INTEGRATION: FAIL (Gradle integration tests; raw output withheld)"
  exit 1
fi
