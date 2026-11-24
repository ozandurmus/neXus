#!/bin/bash
# 30-minute pipeline check (PO 2026-11-23 "askıda kalma; 30 dakikada bir kontrol et ve hataları düzelt").
# Prints a short status and FIXES the stuck states it can fix safely; prints ACTION lines for what needs me.
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
LAUNCHCTL=${NEXUS_LAUNCHCTL:-launchctl}
J=$OPS_STATE; SQ=$J/ship-queue; LQ=$J/lane-queue; L=$J/logs
now=$(date +%s)
echo "time $(date '+%F %T')"
echo "lanes running: $(ps -axo command | grep -E '^/bin/bash .*claude_task\.sh' | grep -v grep | wc -l | tr -d ' ') claude, $(ps -axo command | grep -E '^/.*Python .*argon_task_neon\.py' | grep -v grep | wc -l | tr -d ' ') agy; briefs waiting: $(ls $LQ/*.brief 2>/dev/null | wc -l | tr -d ' ')"
echo "ship queue: $(ls $SQ/*.ship 2>/dev/null | wc -l | tr -d ' '); inflight: $(ls $SQ/inflight 2>/dev/null | tr '\n' ' '); held: $(ls $SQ/held 2>/dev/null | tr '\n' ' ')"
# Self-healing (PO 2026-11-23 "kontrol askıda kalırsan diye; hat kendi kendine devam etmeli"): this script runs from
# launchd every 30 minutes, independent of any Claude session, and resumes what it safely can.
for P in "$SQ/PAUSED" "$LQ/PAUSED"; do
  [ -e "$P" ] || continue; age=$(( now - $(file_mtime "$P") ))
  # A pause the PO asked for (PO_HOLD beside it, e.g. 2026-11-24 "tek tek") is never lifted here.
  if [ -e "$(dirname "$P")/PO_HOLD" ]; then echo "HOLD $P kept: PO_HOLD ($(head -1 "$(dirname "$P")/PO_HOLD"))"
  elif [ "$age" -gt 3600 ]; then echo "FIX $P older than 60 min -> removed (a repeated failure pauses again)"; rm -f "$P"
  else echo "ACTION $P since $(file_date "$P")"; fi
done
[ -e "$LQ/ENGINE_CLAUDE" ] && echo "NOTE ENGINE_CLAUDE set (agy quota hit at $(file_date "$LQ/ENGINE_CLAUDE"))"
pgrep -f 'ship-runner\.sh' >/dev/null || { echo "FIX ship-runner not running -> kickstart"; $LAUNCHCTL kickstart gui/$(id -u)/com.securityexpert.overwatch.ship-runner; }
pgrep -f 'lane-runner\.sh' >/dev/null || { echo "FIX lane-runner not running -> kickstart"; $LAUNCHCTL kickstart gui/$(id -u)/com.securityexpert.overwatch.lane-runner; }
# Tier B review stuck (pending > 45 min and no reviewer running): run it again.
for f in $SQ/*.ship; do
  [ -e "$f" ] || continue; grep -q '^review=pending' "$f" || continue
  n=$(basename "$f" .ship); age=$(( now - $(file_mtime "$f") ))
  if [ "$age" -gt 2700 ] && ! pgrep -f "b-review\.sh $n" >/dev/null; then
    echo "FIX review stuck for $n ($((age/60)) min) -> rerun b-review"; touch "$f"
    ( nohup /bin/bash "$OPS_DIR/b-review.sh" "$n" > "$L/b-review-$n.nohup" 2>&1 & )
  fi
done
# In-flight batch whose shipq3 died without DONE.
for d in $SQ/inflight/*/; do
  [ -d "$d" ] || continue; id=$(basename "$d")
  if ! grep -q '^DONE' "$L/ship-$id.out" 2>/dev/null && ! pgrep -f "shipq3\.sh .*ship-$id" >/dev/null; then
    # Give it 10 minutes (a runner restart races with a fresh start), then end it so the runner re-queues its lanes.
    if [ $(( now - $(file_mtime "$d") )) -gt 600 ]; then
      echo "FIX inflight $id: no shipq3 and no DONE -> marked FAILED so its lanes are re-queued"
      { echo "FAILED batch/$id: ship process died (watch-check)"; echo DONE; } >> "$L/ship-$id.out"
    else echo "ACTION inflight $id has no shipq3 yet"; fi
  fi
done
# Lanes running longer than 90 minutes.
for m in $LQ/started/*.brief; do
  [ -e "$m" ] || continue; age=$(( now - $(file_mtime "$m") ))
  [ "$age" -gt 5400 ] && echo "ACTION lane $(basename $m .brief) running $((age/60)) min"
done
# Work that left the pipeline (review 2026-11-24: it vanished quietly in six places): every item in failed/, held/, a lane
# hold/ or FAILED_WORKTREE is an ACTION line with its age, until someone moves or removes it.
for f in "$SQ"/failed/*.ship "$SQ"/held/*.ship "$LQ"/hold/*.brief "$LQ"/done/*.FAILED_WORKTREE; do
  [ -e "$f" ] || continue
  echo "ACTION $(basename "$(dirname "$f")")/$(basename "$f") waiting $(age_label $(( now - $(file_mtime "$f") )))"
done
echo "last ship events:"; grep -E 'SHIPPED|FAILED|ROLLED|HELD|B-REVIEW|PAUSED|CONFLICT|REVERT' $L/ship-runner.log | tail -5 | cut -c1-160
echo "last lane events:"; grep -E 'FAST FAIL|quota|worktree failed|ALERT|HELD|INFRA|NO_CHANGE' $L/lane-runner.log | tail -3 | cut -c1-160
