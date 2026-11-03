#!/usr/bin/env bash
# Candidate code is archived from Git, not a mutable checkout. This never pushes or rolls out main.
set -euo pipefail
if { [ "$#" != 4 ] && [ "$#" != 6 ]; } || [ "$1" != --repo ] || [ "$3" != --commit ] || [[ ! "$4" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Usage: hosta_preview_e2e.sh --repo PATH --commit SHA [--tree TREE_SHA]' >&2; exit 2
fi
repo=$2
commit=$4
# The optional tree hash lets the host remember which images this preview verified (reused by the production deploy
# of an identical tree). It is validated here and again by the host-side controller.
tree=
if [ "$#" = 6 ]; then
  if [ "$5" != --tree ] || [[ ! "$6" =~ ^[0-9a-f]{40}$ ]]; then
    echo 'Usage: hosta_preview_e2e.sh --repo PATH --commit SHA [--tree TREE_SHA]' >&2; exit 2
  fi
  tree=$6
fi
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
python3 "$work/tools/e2e/hosta_preview_e2e.py" --repo "$work" --commit "$1" ${2:+--tree "$2"} &
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
    "$host" "bash -c $(printf '%q' "$remote") -- $commit $tree" 2>/dev/null | \
  awk '/^TIMING preview_[a-z_]+ [0-9.]+$/ || /^PREVIEW E2E: (PASS|FAIL)/ || /^PREVIEW STEP: / || /^PREVIEW IMAGES: tree=[0-9a-f]+ service=sha256:[0-9a-f]+ e2e=sha256:[0-9a-f]+$/'
