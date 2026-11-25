#!/bin/bash
# Event waiter for the supervising session: returns when the pipeline needs it (a FAILED/HELD/BLOCK/CONFLICT in the ship
# log, a lane that ended non-zero, HELD or PAUSED in the lane log, or IDLE capacity) or after max_seconds, then prints the
# watch-check output.
#   ayyas-waiter.sh [max_seconds]       (default 1800; WAKE=<regex> adds ship-log patterns that end the wait)
# IDLE (2026-11-25: lanes sat empty 03:00-08:10 while the session waited on ship events): no lane running and no brief
# waiting for 10 minutes in a row is an event too -> refill (planner / P1 briefs).
# Versioned copy (tools/delivery/ops) of the live script; paths come from ops_lib.sh. Fixes against the live copy: the
# running-lane count matches the engine wrappers lane-runner.sh starts (argon_task_neon.py, claude_task.sh, qwen_task.sh;
# the live pattern "argon_task.py" never matched argon_task_neon.py), and missing logs do not break the wait.
# Test seams: NEXUS_WAITER_TICK (poll seconds, default 30), NEXUS_WAITER_IDLE_S (default 600), NEXUS_WATCH_CHECK.
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
J=$OPS_STATE; L=$OPS_STATE/logs; LQ=$OPS_STATE/lane-queue
WAITER_TICK=${NEXUS_WAITER_TICK:-30}
WAITER_IDLE_S=${NEXUS_WAITER_IDLE_S:-600}

# lanes_running: engine wrappers alive (the same processes lane-runner.sh counts, plus the local Qwen slot).
lanes_running() { ps -axo command | grep -E "(^/.*[Pp]ython.* .*argon_task(_neon)?\.py|^/bin/bash .*(claude|qwen)_task\.sh)" | grep -v grep | wc -l | tr -d ' '; }
briefs_waiting() { ls "$LQ"/*.brief 2>/dev/null | wc -l | tr -d ' '; }

# idle_next <idle-seconds-so-far>: the idle counter after one tick (reset as soon as a lane runs or a brief waits).
idle_next() {
  if [ "$(lanes_running)" -eq 0 ] && [ "$(briefs_waiting)" -eq 0 ]; then echo $(( $1 + WAITER_TICK )); else echo 0; fi
}
# idle_event <idle-seconds>: the IDLE event line once the idle time reaches the threshold.
idle_event() {
  [ "$1" -ge "$WAITER_IDLE_S" ] && echo "IDLE: no lane running and no brief waiting for $((WAITER_IDLE_S / 60)) min -> refill (planner / P1 briefs)"
  return 0
}

log_lines() { if [ -e "$1" ]; then wc -l < "$1" | tr -d ' '; else echo 0; fi; }
# new_lines <file> <lines-seen-at-start>: what was appended since the wait began.
new_lines() { [ -e "$1" ] && tail -n +$(( $2 + 1 )) "$1"; return 0; }

waiter_main() {
  local max=${1:-1800} t0 s1 s2 n1 n2 idle=0
  t0=$(date +%s); s1=$(log_lines "$L/ship-runner.log"); s2=$(log_lines "$L/lane-runner.log")
  while [ $(( $(date +%s) - t0 )) -lt "$max" ]; do
    n1=$(new_lines "$L/ship-runner.log" "$s1" | grep -E "FAILED|HELD|B-REVIEW BLOCK|CONFLICT${WAKE:+|$WAKE}" | head -5)
    n2=$(new_lines "$L/lane-runner.log" "$s2" | grep -E ' done .*exit=[^0]| done .*mc-device-health|HELD|PAUSED' | grep -vE 'refill-[a-z0-9]+: .*exit=3' | head -5)
    idle=$(idle_next "$idle")
    [ -z "$n2" ] && n2=$(idle_event "$idle")
    if [ -n "$n1$n2" ]; then echo "EVENT:"; echo "$n1"; echo "$n2"; break; fi
    sleep "$WAITER_TICK"
  done
  echo "--- watch-check"; bash "${NEXUS_WATCH_CHECK:-$OPS_DIR/watch-check.sh}" 2>&1 | tail -25
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then waiter_main "$@"; fi
