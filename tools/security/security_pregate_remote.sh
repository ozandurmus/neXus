#!/usr/bin/env bash
# Host side of hosta_security_pregate.sh (PO 2026-11-23); sent as the ssh command text, never installed or run from the
# candidate. Arguments: <commit> <tree>; stdin: a Git bundle carrying refs/security-pregate/... for <commit>.
# Prints heartbeat lines ("."), then exactly one verdict line: PASS <commit> <tree> | FAIL | NOT_CONFIGURED |
# UNAVAILABLE, then at most one count summary line. Every error is FAIL; NOT_CONFIGURED / UNAVAILABLE only when the
# gate is provably absent (file does not exist / host tooling predates the pre-merge action) AND this host has never
# run a pre-merge gate (the sticky marker security_host.PREGATE_MARKER).
set -uo pipefail
# The start anchor, taken before the history bundle is drained: the preview runs in parallel from the same moment, so
# its image record may land while the bundle is still streaming (security_host.pregate --not-before).
session_started=$(date +%s)
export KUBECONFIG=$HOME/.kube/config
commit=${1:-}; tree=${2:-}
config=$HOME/.config/nexus/security.json
rules=$HOME/.config/nexus/security-rules
marker=$HOME/.local/state/nexus-security/pregate-enabled
driver=${NEXUS_PREGATE_DRIVER:-/opt/nexus/tools/security/security_host.py}
work=
child=
cleanup() {
  trap '' HUP INT TERM PIPE
  if [ -n "$child" ]; then kill -TERM "$child" 2>/dev/null; wait "$child" 2>/dev/null; fi
  if [ -n "$work" ]; then rm -rf -- "$work"; fi
}
trap cleanup EXIT
# A vanished ssh session shows up as SIGPIPE on the next heartbeat (or SIGHUP): stop, and the driver deletes its Job.
# (bash also runs the EXIT trap when a fatal SIGPIPE ends it; if SIGPIPE was IGNORED when the session started, bash
# cannot trap it at all, so the heartbeat below also stops on its own write error.)
trap 'exit 2' HUP INT TERM PIPE
verdict() { printf '%s\n' "$1"; exit 0; }
if [[ ! "$commit" =~ ^[0-9a-f]{40}$ ]] || [[ ! "$tree" =~ ^[0-9a-f]{40}$ ]]; then cat >/dev/null; verdict FAIL; fi
work=$(mktemp -d) || { cat >/dev/null; verdict FAIL; }
# Drain the whole bundle before any answer: an early exit must never leave the sender writing into a closed pipe.
cat > "$work/c.bundle" || verdict FAIL
state=$(python3 -I - "$config" "$driver" "$marker" <<'PY' 2>/dev/null
import os
import sys

config, driver, marker = sys.argv[1:4]


def present(path):
    try:
        os.stat(path)
        return True
    except FileNotFoundError:
        return False


try:
    enabled = present(marker)
    configured = present(config)
    if configured:
        with open(config, "rb") as stream:
            stream.read(1)
        with open(driver, encoding="utf-8") as stream:
            text = stream.read()
            capable = "\ndef pregate(" in text
            anchored = '"--not-before"' in text
except Exception:
    print("FAIL")
    raise SystemExit(0)
if not configured:
    print("FAIL" if enabled else "NOT_CONFIGURED")
elif not capable:
    print("FAIL" if enabled else "UNAVAILABLE")
else:
    # A driver from before the start anchor (an older /opt/nexus mid-rollout) runs without it, exactly as before.
    print("RUN_ANCHORED" if anchored else "RUN")
PY
) || state=FAIL
anchor=()
case "$state" in
  RUN) ;;
  RUN_ANCHORED) anchor=(--not-before "$session_started") ;;
  NOT_CONFIGURED|UNAVAILABLE) verdict "$state" ;;
  *) verdict FAIL ;;
esac
repo=$work/repo
# The bundle carries only the pre-gate ref, so fetch it explicitly (a plain clone would only take HEAD and branches).
git init -q "$repo" >/dev/null 2>&1 \
  && git -C "$repo" fetch -q --no-write-fetch-head "$work/c.bundle" "+refs/security-pregate/*:refs/security-pregate/*" >/dev/null 2>&1 \
  && git -C "$repo" checkout -q --detach "$commit" >/dev/null 2>&1 \
  && [ "$(git -C "$repo" rev-parse "HEAD^{tree}" 2>/dev/null)" = "$tree" ] || verdict FAIL
python3 -B "$driver" pregate --config "$config" --rules "$rules" --repo "$repo" --commit "$commit" --tree "$tree" \
  ${anchor[@]+"${anchor[@]}"} > "$work/out" 2>/dev/null </dev/null &
child=$!
while kill -0 "$child" 2>/dev/null; do
  printf '.\n' 2>/dev/null || exit 2
  sleep "${NEXUS_PREGATE_HEARTBEAT_S:-5}"
done
wait "$child"
rc=$?
child=
line=
if [ "$rc" = 0 ]; then
  line=$(grep -E "^PREGATE: PASS $commit $tree\$" "$work/out" | tail -1 | sed 's/^PREGATE: //')
fi
printf '%s\n' "${line:-FAIL}"
grep -E '^\{"passed":' "$work/out" | tail -1
exit 0
