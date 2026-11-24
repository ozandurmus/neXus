#!/usr/bin/env bash
# Pre-merge security gate (PO 2026-11-23): stream the candidate's Git history to the host and run the deploy gate's Job
# on it (security_host.py pregate), beside the preview. The host side is security_pregate_remote.sh from THIS checkout
# (the ship runs main's copy, never the candidate's); the driver it calls is the host's installed main copy, and the
# Job judges the candidate with the host's installed scanner scripts. Prints exactly one verdict line:
# PASS <commit> <tree> | FAIL | NOT_CONFIGURED | UNAVAILABLE (plus at most one count summary line).
# A HUP/INT/TERM stops the whole pipeline (bundle, ssh); the host side notices the closed session and deletes its Job.
set -uo pipefail
if [ "$#" != 6 ] || [ "$1" != --repo ] || [ "$3" != --commit ] || [ "$5" != --tree ] \
   || [[ ! "$4" =~ ^[0-9a-f]{40}$ ]] || [[ ! "$6" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Usage: hosta_security_pregate.sh --repo PATH --commit SHA --tree TREE_SHA' >&2; exit 2
fi
repo=$2; commit=$4; tree=$6
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd) || exit 1
remote=$(cat "$here/security_pregate_remote.sh") || exit 1
host="${NEXUS_HOST:-$(head -1 "$HOME/.config/nexus/hosta" 2>/dev/null)}"
[ -n "$host" ] || exit 1
# One ref per run: a parallel or orphaned run of the same commit never deletes this run's ref.
ref="refs/security-pregate/run-$(date +%s)-$$-$RANDOM-$commit"
child=
cleanup() {
  if [ -n "$child" ]; then kill -TERM "$child" 2>/dev/null; wait "$child" 2>/dev/null; fi
  git -C "$repo" update-ref -d "$ref" 2>/dev/null || true
}
trap cleanup EXIT
# The ship kills this process group; an interactive stop kills it here. Children inherit no ignore: they were started
# before the dispositions change.
trap 'trap "" HUP INT TERM; kill -TERM 0 2>/dev/null; exit 143' HUP INT TERM
git -C "$repo" update-ref "$ref" "$commit" || exit 1
# Only the verdict line and the validated count summary cross the boundary (heartbeat dots are dropped).
(
  set -o pipefail
  git -C "$repo" bundle create - "$ref" 2>/dev/null | \
    ssh -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=8 \
      "$host" "bash -c $(printf '%q' "$remote") -- $commit $tree" 2>/dev/null | \
    { grep -E '^(PASS [0-9a-f]{40} [0-9a-f]{40}|FAIL|NOT_CONFIGURED|UNAVAILABLE)$|^\{"passed":' || true; }
) &
child=$!
wait "$child"
rc=$?
child=
exit "$rc"
