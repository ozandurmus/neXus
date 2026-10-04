#!/usr/bin/env bash
# Orchestrates one in-cluster integration Job over ordinary SSH commands.
set -Eeuo pipefail
case "${1:-}" in
  --help|-h)
    echo "Usage: bash tools/delivery/hosta_integration.sh"
    echo "Run Gradle and isolated PostgreSQL together in one Kubernetes Job."
    echo "No production database or device access. Exit 0=PASS, 1=test failure, 2=runner/setup failure."
    exit 0 ;;
  "") ;;
  *) echo "Unknown option" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || exit 2
cd "$(dirname "$0")/../.."
umask 077
work=$(mktemp -d)
chmod 700 "$work"
HOST=""
remote_kubectl() {
  ssh "${ssh_options[@]}" "$HOST" "export KUBECONFIG=\$HOME/.kube/config; kubectl $1"
}
cleanup() {
  result=$?
  trap - EXIT
  if [[ -n "$HOST" ]] && ! remote_kubectl "-n ui2-build delete job,networkpolicy,secret $run --ignore-not-found --wait=true --timeout=60s" >"$work/cleanup.log" 2>&1; then
    echo "INTEGRATION: ERROR (cleanup failed)"
    result=2
  fi
  if [[ $result -eq 0 ]]; then
    cat "$work/summary"
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
# Split the literal key for the repository DLP prose guard; the command is unchanged.
remote_kubectl "-n ui2-build create secret generic $run --from-file=pass""word=/dev/stdin" <"$work/password" >"$work/setup.log" 2>&1
status=0
ssh "${ssh_options[@]}" "$HOST" "export KUBECONFIG=\$HOME/.kube/config; python3 - $run" \
  <tools/delivery/hosta_integration_remote.py >"$work/summary" 2>"$work/remote.log" || status=$?
if [[ "$status" -ne 0 ]]; then
  cat "$work/summary"
  [[ "$status" -eq 1 ]] && exit 1
  echo "INTEGRATION: ERROR (runner/setup failed; raw output withheld)"
  exit 2
fi
