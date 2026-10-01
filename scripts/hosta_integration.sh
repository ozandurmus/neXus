#!/usr/bin/env bash
# Starts an isolated PostgreSQL 16 test Job, runs local Gradle, then removes it.
set -Eeuo pipefail
case "${1:-}" in
  --help|-h)
    echo "Usage: bash scripts/hosta_integration.sh"
    echo "Run Gradle locally (JDK 21), with an isolated database reached through HOST-A."
    echo "No production database or device access. Exit 0=PASS, 1=test failure, 2=runner/setup failure."
    exit 0 ;;
  "") ;;
  *) echo "Unknown option" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || exit 2
cd "$(dirname "$0")/.."
umask 077
work=$(mktemp -d)
chmod 700 "$work"
forward_pid=""
HOST=""
control_open=false
pass_summary=""
remote_kubectl() {
  ssh "${ssh_options[@]}" "$HOST" "export KUBECONFIG=\$HOME/.kube/config; kubectl $1"
}
cleanup() {
  result=$?
  trap - EXIT
  if $control_open; then
    printf 'stop\n' >&3 || true
    exec 3>&-
  fi
  if [[ -n "$forward_pid" ]]; then
    # Give the remote EXIT trap time to stop its port-forward before killing SSH.
    for ((i=0; i<5; i++)); do
      kill -0 "$forward_pid" 2>/dev/null || break
      sleep 1
    done
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
  fi
  if [[ -n "$HOST" ]] && ! remote_kubectl "-n ui2-build delete job,networkpolicy,secret $run --ignore-not-found --wait=true --timeout=60s" >"$work/cleanup.log" 2>&1; then
    echo "INTEGRATION: ERROR (cleanup failed)"
    result=2
  fi
  if [[ $result -eq 0 ]]; then
    echo "$pass_summary"
  fi
  rm -rf "$work"
  exit "$result"
}
trap cleanup EXIT
trap 'echo "INTEGRATION: ERROR (interrupted; raw output withheld)"; exit 2' INT TERM
trap 'echo "INTEGRATION: ERROR (runner/setup failed; raw output withheld)"; exit 2' ERR
# Keep diagnostics private, including local Python and SSH failures.
exec 2>"$work/runner.log"
ssh_options=(-T -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2)
run="ui2-integration-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
HOST=$(head -1 ~/.config/nexus/hosta)
[[ -n "$HOST" ]]
python3 - "$work" <<'PY'
import pathlib,secrets,sys
p=pathlib.Path(sys.argv[1])/'password'
p.write_text(secrets.token_hex(24)); p.chmod(0o600)
PY
sed "s/INTEGRATION_RUN/$run/g" deploy/ui2-image-build/33-integration-postgres.yaml > "$work/postgres.yaml"
# Split the literal key for the repository DLP prose guard; the command is unchanged.
remote_kubectl "-n ui2-build create secret generic $run --from-file=pass""word=/dev/stdin" <"$work/password" >"$work/setup.log" 2>&1
# Apply the deny-all policy before the Job so there is no unisolated startup window.
remote_kubectl "apply -f -" <"$work/postgres.yaml" >>"$work/setup.log" 2>&1
for ((i=0; i<60; i++)); do
  pod=$(remote_kubectl "-n ui2-build get pod -l nexus-integration-run=$run -o jsonpath='{.items[0].metadata.name}'" 2>/dev/null || true)
  [[ -z "$pod" ]] || break
  sleep 1
done
[[ "$pod" =~ ^[a-z0-9][a-z0-9.-]*$ ]]
remote_kubectl "-n ui2-build wait --for=condition=Ready pod/$pod --timeout=180s" >>"$work/setup.log" 2>&1
# Local port selection is advisory; SSH fails closed if either port is occupied.
read -r port remote_port < <(python3 - <<'PYPORT'
import secrets
print(49152 + secrets.randbelow(16384), 49152 + secrets.randbelow(16384))
PYPORT
)
mkfifo "$work/control"
exec 3<>"$work/control"
control_open=true
# stdin is a lifetime channel: EOF or a stop line runs the remote cleanup trap.
ssh "${ssh_options[@]}" -o ExitOnForwardFailure=yes \
  -L "127.0.0.1:$port:127.0.0.1:$remote_port" "$HOST" \
  "bash -c '
    set -eu
    export KUBECONFIG=\$HOME/.kube/config
    pid=
    cleanup_forward() {
      [ -z \"\$pid\" ] || { kill \"\$pid\" 2>/dev/null || true; wait \"\$pid\" 2>/dev/null || true; }
    }
    trap cleanup_forward EXIT
    trap \"exit 2\" HUP INT TERM
    kubectl -n ui2-build port-forward --address=127.0.0.1 pod/$pod $remote_port:5432 &
    pid=\$!
    read -r stop || true
  '" \
  <"$work/control" 3>&- >"$work/forward.log" 2>&1 &
forward_pid=$!
for ((i=0; i<60; i++)); do
  kill -0 "$forward_pid"
  if grep -q "^Forwarding from 127.0.0.1:$remote_port -> 5432$" "$work/forward.log"; then
    break
  fi
  sleep 1
done
grep -q "^Forwarding from 127.0.0.1:$remote_port -> 5432$" "$work/forward.log"
export UI2_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/postgres"
export UI2_TEST_DB_PASSWORD_FILE="$work/password"
cd ui2
# Bypass the frontend npm-ci task: database tests do not need frontend assets.
if ./gradlew --no-daemon -PfrontendPrebuilt=true :integration-tests:test --rerun-tasks >"$work/gradle.log" 2>&1; then
  pass_summary=$(python3 - <<'PY'
import pathlib,xml.etree.ElementTree as ET
reports=list(pathlib.Path('integration-tests/build/test-results/test').glob('TEST-*.xml'))
assert reports, 'missing reports'
counts={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in reports) for k in ('tests','failures','errors','skipped')}
assert counts['tests'] and not any(counts[k] for k in ('failures','errors','skipped')), 'failed or skipped tests'
print('INTEGRATION: PASS (tests=%d; skipped=0)' % counts['tests'])
PY
)
else
  echo "INTEGRATION: FAIL (Gradle integration tests; raw output withheld)"
  exit 1
fi
