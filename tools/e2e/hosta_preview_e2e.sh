#!/usr/bin/env bash
# Candidate code is archived from Git, not a mutable checkout. This never pushes or rolls out main.
set -euo pipefail
if [ "$#" != 4 ] || [ "$1" != --repo ] || [ "$3" != --commit ] || [[ ! "$4" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Usage: hosta_preview_e2e.sh --repo PATH --commit SHA' >&2; exit 2
fi
repo=$2
commit=$4
host=$(head -1 "$HOME/.config/nexus/hosta")
remote='set -euo pipefail
export KUBECONFIG=$HOME/.kube/config
work=$(mktemp -d)
child=
cleanup() {
  if [ -n "$child" ]; then kill -TERM "$child" 2>/dev/null || true; wait "$child" || true; fi
  rm -rf -- "$work"
}
trap cleanup EXIT
trap "exit 2" HUP INT TERM
tar -xf - -C "$work"
python3 "$work/tools/e2e/hosta_preview_e2e.py" --repo "$work" --commit "$1" &
child=$!
set +e
wait "$child"
result=$?
child=
exit "$result"'
# Only masked API diagnostics, timings and verdicts cross the boundary.
# pipefail preserves either streaming failure.
git -C "$repo" archive --format=tar "$commit" | \
  ssh -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
    "$host" "bash -c $(printf '%q' "$remote") -- $commit" 2>/dev/null | \
  awk '/^TIMING preview_[a-z_]+ [0-9.]+$/ || /^PREVIEW E2E: (PASS|FAIL)/ || /^PREVIEW STEP: /'
