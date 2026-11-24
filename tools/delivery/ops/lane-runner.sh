#!/bin/bash
# Keeps N lanes busy without the PO session being awake (PO 2026-11-20: "gece iş yap").
# Queue: $NEXUS_OPS_STATE/lane-queue/<name>.brief (a brief) + <name>.meta (lines: base=<git ref>, model=<model>,
# adddir=<path, optional>). The runner takes the lowest-numbered brief, creates worktree $NEXUS_OPS_CODO/nexus-q-<name> on a
# new branch lane/q-<name> from <base>, runs the engine wrapper, and moves the brief to lane-queue/started/ (done/ after).
# Usage (launchd): lane-runner.sh <max-lanes> > $NEXUS_OPS_STATE/logs/lane-runner.log 2>&1 &
#
# Versioned copy (tools/delivery/ops, 2026-11-24 review). Differences from the unversioned original:
#  * every finished lane gets ONE distinct outcome (lane_outcome): OK, NO_CHANGE (exit 3, nothing to commit),
#    PERMISSION_DENIED (held with an ALERT), INFRA_ERROR (re-queued once, then held with an ALERT), QUOTA, FAILED. Exit 3 is
#    never again a catch-all that ends silently in done/.
#  * a worktree that cannot be created re-queues the brief once; the second failure becomes done/<name>.brief.FAILED_WORKTREE
#    with an ALERT line (watch-check lists it, status.sh shows its age).
#  * a lane brief carrying review_returned=1 (b-review.sh returned a BLOCK to the author) keeps that flag on its ship entry.
# The file only defines functions when sourced (tests); it runs the loop when executed.
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
Q=$OPS_STATE/lane-queue; L=$OPS_STATE/logs; SQ=$OPS_STATE/ship-queue; REPO=$OPS_REPO
log() { ops_log "$@"; }

running() { ps -axo command | grep -E "(^/.*Python .*argon_task_neon\.py|^/bin/bash .*claude_task\.sh)" | grep -v grep | wc -l | tr -d ' '; }
# Local Qwen lanes (engine=qwen in .meta) have their own slot: at most 1 (18 GB model on a 32 GB Mac), never
# counted against MAX, and never overridden by ENGINE_CLAUDE.
qwen_running() { ps -axo command | grep -E "^/bin/bash .*qwen_task\.sh" | grep -v grep | wc -l | tr -d ' '; }
# Engine: meta engine=claude|agy; a file lane-queue/ENGINE_CLAUDE forces Claude for every lane.
# Auto-switch: when a finished agy lane's log shows a quota/limit error, the runner creates ENGINE_CLAUDE itself.
quota_hit() { grep -qiE "quota|RESOURCE_EXHAUSTED|rate.?limit|usage limit|credits? (exhausted|exceeded)|429" "$1" 2>/dev/null; }
# Markers (looked for in the lane's log): a permission the headless agent was refused, and transient infrastructure errors
# near the end of the log (start errors, connection and gateway errors).
PERM_RE='auto-denied|\[argon\] FAILED: a permission'
INFRA_RE='failed to start conversation|empty conversation id|ECONNRESET|connection reset|connection refused|service unavailable|overloaded_error|bad gateway|network is unreachable|could not resolve host|TLS handshake timeout'

# lane_outcome <nohup-log>: one word for how a lane ended. RUNNING = no "[argon] exit=" line yet.
lane_outcome() {
  local f=$1 line rc
  line=$(grep '^\[argon\] exit=' "$f" 2>/dev/null | tail -1)
  [ -n "$line" ] || { echo RUNNING; return 0; }
  rc=${line#*exit=}; rc=${rc%% *}
  [ "$rc" = 0 ] && { echo OK; return 0; }
  # A refused permission also ends exit=3 ("no change"): lane 512 (fix-414, 2026-11-24) vanished into done/ silently.
  grep -qE "$PERM_RE" "$f" && { echo PERMISSION_DENIED; return 0; }
  quota_hit "$f" && { echo QUOTA; return 0; }
  tail -40 "$f" | grep -qiE "$INFRA_RE" && { echo INFRA_ERROR; return 0; }
  # exit=3 is a finished lane that found nothing to change (2026-11-22 lane 134): not an engine failure.
  [ "$rc" = 3 ] && { echo NO_CHANGE; return 0; }
  echo FAILED
}

lane_exit_line() { grep '^\[argon\] exit=' "$L/q-$1.nohup" 2>/dev/null | tail -1; }
# requeue_lane <name> <started-brief>: put the brief (and meta) back in the queue and drop its worktree and branch.
requeue_lane() {
  local n=$1 s=$2
  cp "$s" "$Q/$n.brief"; [ -e "$Q/started/$n.meta" ] && cp "$Q/started/$n.meta" "$Q/$n.meta"
  git -C "$REPO" worktree remove --force "$OPS_CODO/nexus-q-$n" 2>/dev/null; git -C "$REPO" branch -D "lane/q-$n" >/dev/null 2>&1
}
# archive_lane <name> <started-brief> <outcome>: brief and meta to done/ with the outcome written beside them.
archive_lane() {
  local n=$1 s=$2 out=$3
  mkdir -p "$Q/done"
  mv "$s" "$Q/done/"; mv "$Q/started/$n.meta" "$Q/done/" 2>/dev/null
  echo "$out" > "$Q/done/$n.outcome"
  log "done $n: $(lane_exit_line "$n") [$out]"
}
# hold_lane <name> <started-brief> <why>: brief and meta to hold/ with an ALERT; a person decides.
hold_lane() {
  local n=$1 s=$2 why=$3
  mkdir -p "$Q/hold"
  mv "$s" "$Q/hold/"; mv "$Q/started/$n.meta" "$Q/hold/" 2>/dev/null
  log "ALERT HELD $n: $why"
}

# finish_lane <started-brief>: handle one lane in started/ whose engine wrapper has ended.
finish_lane() {
  local s=$1 n out age bfiles
  n=$(basename "$s" .brief)
  out=$(lane_outcome "$L/q-$n.nohup")
  [ "$out" = RUNNING ] && return 0
  age=$(( $(date +%s) - $(file_mtime "$s") ))
  case "$out" in
    QUOTA)
      if [ ! -e "$Q/ENGINE_CLAUDE" ]; then
        touch "$Q/ENGINE_CLAUDE"; log "agy quota hit in $n -> switching new lanes to Claude; re-queueing $n"
        requeue_lane "$n" "$s"; archive_lane "$n" "$s" "QUOTA_REQUEUED"; return 0
      fi
      out=FAILED;;
    PERMISSION_DENIED)
      if [ ! -e "$Q/hold/$n.brief" ]; then
        hold_lane "$n" "$s" "agy permission auto-denied ($(grep -oE 'the "[a-z_]+" permission' "$L/q-$n.nohup" | head -1)); extend ~/.gemini/antigravity-cli/settings.json and re-queue"
        return 0
      fi
      out=FAILED;;
    INFRA_ERROR)
      # A transient start/connection error (2026-11-24: "backend returned an empty conversation id" paused all lanes for
      # 40 min) re-queues the lane once without pausing; a second one in a row holds it with an ALERT (and pauses the
      # runner when the lane died within 120 s, as a broken engine would).
      if [ ! -e "$Q/.transient-$n" ]; then
        touch "$Q/.transient-$n"; requeue_lane "$n" "$s"
        log "INFRA error in $n after ${age}s -> re-queued once: $(tail -2 "$L/q-$n.nohup" | head -1 | cut -c1-160)"
        archive_lane "$n" "$s" "INFRA_REQUEUED"; return 0
      fi
      rm -f "$Q/.transient-$n"
      [ "$age" -lt 120 ] && touch "$Q/PAUSED"
      hold_lane "$n" "$s" "second infrastructure error in a row ($(tail -2 "$L/q-$n.nohup" | head -1 | cut -c1-120))"; return 0;;
    NO_CHANGE)
      # Worktree leak (handover 2026-11-23: ~75 left behind): a lane that changed nothing needs no worktree; plain remove
      # refuses a dirty one, the branch stays.
      log "NO_CHANGE $n: finished with exit 3 and no permission or infrastructure error; nothing to ship"
      git -C "$REPO" worktree remove "$OPS_CODO/nexus-q-$n" 2>/dev/null && log "removed worktree of $n"
      archive_lane "$n" "$s" "NO_CHANGE"; return 0;;
  esac
  if [ "$out" = FAILED ]; then
    # Fast-fail guard (2026-11-20 incident): a lane that ended non-zero within 120 s pauses the runner and goes back to the
    # queue, so a broken engine never drains the queue.
    if [ "$age" -lt 120 ]; then
      touch "$Q/PAUSED"; requeue_lane "$n" "$s"
      log "FAST FAIL $n after ${age}s -> runner PAUSED, brief re-queued: $(tail -2 "$L/q-$n.nohup" | head -1 | cut -c1-160)"
      archive_lane "$n" "$s" "FAST_FAIL_REQUEUED"; return 0
    fi
    log "ALERT FAILED $n: $(lane_exit_line "$n")"
    archive_lane "$n" "$s" "FAILED"; return 0
  fi
  # OK. PO 2026-11-22 "shipler arka arkaya girecek": a lane that ended exit=0 goes to the ship queue on its own
  # (ship-runner.sh ships it); meta ship=no keeps review-only or PO-decision lanes out.
  if ! grep -q '^ship=no' "$Q/started/$n.meta" 2>/dev/null; then
    mkdir -p "$SQ"
    { echo "branch=lane/q-$n"; echo "name=$n"; grep -E '^(allow_paths|title|review_returned)=' "$Q/started/$n.meta" 2>/dev/null; } > "$SQ/$n.ship"
    # Tier B (PO 2026-11-23): production code outside the Estate UI gets a Sonnet review before batching.
    bfiles=$(git -C "$REPO" diff --name-only "$(git -C "$REPO" merge-base "lane/q-$n" origin/main)" "lane/q-$n" 2>/dev/null \
      | grep -E '^ui2/[^/]+/src/main/|^ui2/frontend/src/' | grep -vE '^ui2/frontend/src/estate/')
    if [ -n "$bfiles" ]; then echo "review=pending" >> "$SQ/$n.ship"
      ( nohup /bin/bash "$OPS_DIR/b-review.sh" "$n" > "$L/b-review-$n.nohup" 2>&1 & ); fi
    log "ship-queued $n${bfiles:+ (tier B review pending)}"
  else
    # A lane that never ships (ship=no: refill, review-only) needs no worktree either.
    git -C "$REPO" worktree remove "$OPS_CODO/nexus-q-$n" 2>/dev/null && log "removed worktree of $n"
  fi
  archive_lane "$n" "$s" "OK"
}

finish_pass() { local s; for s in "$Q"/started/*.brief; do [ -e "$s" ] || continue; finish_lane "$s"; done; }

# wt_failed <queued-brief-path> <queued-name>: the worktree could not be created. First time: the brief stays queued (the
# next pass tries again). Second time: done/<name>.brief.FAILED_WORKTREE and an ALERT, never a silent drop.
wt_failed() {
  local next=$1 n=$2
  if [ ! -e "$Q/.wtretry-$n" ]; then
    touch "$Q/.wtretry-$n"; log "worktree failed for $n; brief stays queued for one retry"; return 0
  fi
  rm -f "$Q/.wtretry-$n"; mkdir -p "$Q/done"
  mv "$next" "$Q/done/$n.brief.FAILED_WORKTREE"; [ -e "$Q/$n.meta" ] && mv "$Q/$n.meta" "$Q/done/$n.meta"
  log "ALERT worktree failed twice for $n ($(tail -1 "$L/lane-runner.err" 2>/dev/null | cut -c1-120)); brief kept as done/$n.brief.FAILED_WORKTREE"
}

# Refill (PO 2026-11-22: work runs back to back): with fewer than 2 briefs queued and no refill lane queued or running,
# queue one refill lane (UI parity and backlog in turn), at most every 10 minutes. NO_REFILL (PO 2026-11-24) disables it.
maybe_refill() {
  local qn last sq prev kind rn
  qn=$(ls "$Q"/*.brief 2>/dev/null | wc -l | tr -d ' ')
  last=$(file_mtime "$Q/.last-refill")
  sq=$(ls "$SQ"/*.ship 2>/dev/null | wc -l | tr -d ' ')
  # Do not outrun the ship pipeline (about 3-5 lanes per 20-minute ship): no refill while 30 or more lanes wait to ship.
  if [ ! -e "$Q/NO_REFILL" ] && [ "$qn" -lt 4 ] && [ "$sq" -lt 10 ] && ! ls "$Q"/*-refill-*.brief "$Q"/started/*-refill-*.brief 2>/dev/null | grep -q . && [ $(( $(date +%s) - last )) -gt 300 ]; then
    prev=$(cat "$Q/.last-refill-kind" 2>/dev/null); case "$prev" in p1) kind=debt;; debt) kind=backlog;; backlog) kind=review;; *) kind=p1;; esac
    rn=$(bash "$OPS_DIR/lanename.sh" "refill-$kind")  # standard name at queue time (PO 2026-11-24)
    printf 'base=origin/main\nengine=claude\nship=no\nadddir=%s\n' "$OPS_STATE" > "$Q/$rn.meta"
    cp "$OPS_STATE/refill-${kind%b}.template" "$Q/$rn.brief"
    case "$kind" in p1*) printf 'claude_model=sonnet\neffort=high\n' >> "$Q/$rn.meta";; esac
    touch "$Q/.last-refill"; echo "$kind" > "$Q/.last-refill-kind"
    log "queued refill $rn"
  fi
}

# Pick the lowest-numbered brief whose engine has a free slot (qwen: 1 own slot; others: MAX) and start it.
start_next_lane() {
  local b next="" n meta base model adddir engine agy_now desc tot lbl n2 wt cmodel ceffort
  for b in $(ls "$Q"/*.brief 2>/dev/null | sort); do
    if grep -q '^engine=qwen' "${b%.brief}.meta" 2>/dev/null; then
      [ "$(qwen_running)" -lt 1 ] && { next=$b; break; }
    else
      [ "$(running)" -lt "$MAX" ] && { next=$b; break; }
    fi
  done
  [ -n "$next" ] || return 0
  n=$(basename "$next" .brief); meta="$Q/$n.meta"
  base=$(grep -E '^base=' "$meta" 2>/dev/null | cut -d= -f2-); base=${base:-origin/main}
  model=$(grep -E '^model=' "$meta" 2>/dev/null | cut -d= -f2-); model=${model:-gemini-4-argon-high}
  adddir=$(grep -E '^adddir=' "$meta" 2>/dev/null | cut -d= -f2-)
  engine=$(grep -E '^engine=' "$meta" 2>/dev/null | cut -d= -f2-); [ -e "$Q/ENGINE_CLAUDE" ] && [ "$engine" != qwen ] && engine=claude
  # Model routing by job kind (MODEL_ROUTING.md, PO 2026-11-23): spread lanes over the Gemini 4 models on Antigravity's own
  # quota. Not when the meta names a Claude model, ENGINE_CLAUDE is set (agy quota) or AGY_OFF exists.
  # Half and half (PO 2026-11-23 "yarı yarıya Claude ve antigravity"): at most half of the slots on Antigravity.
  agy_now=$(ps -axo command | grep -E "^/.*Python .*argon_task_neon\.py" | grep -v grep | wc -l | tr -d ' ')
  # A refill-written claude_model=haiku default no longer pins a lane to Claude (2026-11-24: debt lanes ran on Haiku while
  # Antigravity slots sat idle); only pin_claude=1 in the meta does.
  if ! grep -qE '^pin_claude=1' "$meta" 2>/dev/null && [ "$engine" != qwen ] && [ ! -e "$Q/ENGINE_CLAUDE" ] && [ ! -e "$Q/AGY_OFF" ] \
     && [ "$agy_now" -lt $(( (MAX + 1) / 2 )) ]; then
    # PO 2026-11-24: Antigravity back, 3 parallel lanes; Carbon / Neon / Hydrogen by difficulty (MODEL_ROUTING.md).
    case "$n" in
      *-state-sync*) ;;  # stays on Claude Sonnet (project records; tier B rule)
      *-debt-*|*-test*|*refill-backlog*|*refill-debt*) engine=agy; model=gemini-4-hydrogen-high;;
      *-neon-review-findings*) engine=agy; model=gemini-4-neon-high;;  # fixes on delivery/security scripts: keep high
      *refill-review*|*-review-*) engine=agy; model=gemini-4-neon-high;;
      *-port-*|*-fix-*|*refill-ui*) engine=agy; model=gemini-4-neon-medium;;  # PO 2026-11-24: medium is enough here
      *-p1-*|*refill-p1*) engine=agy; model=gemini-4-carbon-high;;
    esac
  fi
  # Antigravity cap (PO 2026-11-24: 3 parallel lanes there, Carbon/Neon/Hydrogen only): a lane that is not routed to a
  # Gemini model because the cap is full runs on Claude Sonnet instead of falling back to the Argon default.
  if [ "${engine:-agy}" = agy ] && { [ "$agy_now" -ge $(( (MAX + 1) / 2 )) ] || ! echo "$model" | grep -qE 'carbon|neon|hydrogen'; }; then
    engine=claude; grep -qE '^claude_model=' "$meta" 2>/dev/null || printf 'claude_model=sonnet\neffort=medium\n' >> "$meta"
  fi
  # Lane names (PO 2026-11-24): <yyyymmdd>-<HHMM>-G<all-time no>-D<day no>-<short name>. Since 2026-11-24 evening the name is
  # given when the lane is QUEUED (lanename.sh); such a name is kept as is.
  desc=$(echo "$n" | sed -E 's/^([0-9]{8}-[0-9]{4}-G[0-9]+-D[0-9]+-|[0-9]{1,5}-)//')
  if [[ "$n" =~ ^([0-9]{8})-[0-9]{4}-G([0-9]+)-D([0-9]+)- ]]; then
    n2=$n; tot=${BASH_REMATCH[2]}; lbl="L${BASH_REMATCH[1]}-${BASH_REMATCH[3]}"
  else
    read -r lbl tot < <(bash "$OPS_DIR/counter.sh" L "$n")
    n2="$(date +%Y%m%d)-$(date +%H%M)-G${tot}-D${lbl##*-}-${desc}"
  fi
  wt=$OPS_CODO/nexus-q-$n2
  # Fresh base (council 2026-11-23: stale bases caused most port lanes). A fetch can collide with the ship chain's fetch on the
  # ref lock (2026-11-20), so retry briefly and fall back to the refs already there.
  if [ "$base" = origin/main ]; then for _ in 1 2 3; do git -C "$REPO" fetch -q origin main 2>/dev/null && break; sleep 5; done; fi
  # A leftover dir (a Gradle daemon wrote ui2/.gradle after the worktree was removed, 2026-11-24 G521) blocks the add;
  # remove it only when git does not list it as a worktree.
  if [ -d "$wt" ] && ! git -C "$REPO" worktree list --porcelain | grep -qx "worktree $wt"; then rm -rf "$wt"; fi
  if git -C "$REPO" worktree add -q -b "lane/q-$n2" "$wt" "$base" 2>>"$L/lane-runner.err"; then
    rm -f "$Q/.wtretry-$n"
    [ -d "$REPO/ui2/frontend/node_modules" ] && cp -cR "$REPO/ui2/frontend/node_modules" "$wt/ui2/frontend/" 2>/dev/null
    mv "$next" "$Q/started/$n2.brief"; [ -e "$meta" ] && mv "$meta" "$Q/started/$n2.meta"; [ "$n" != "$n2" ] && log "renamed $n -> $n2"; n=$n2
    touch "$Q/started/$n.brief"  # start time for the fast-fail guard
    printf 'label=%s\nall_time=%s\nqueued_as=%s\n' "$lbl" "$tot" "$desc" >> "$Q/started/$n.meta"
    if [ "${engine:-agy}" = qwen ]; then
      ( nohup /bin/bash "$OPS_STATE/qwen_task.sh" "$wt" "$Q/started/$n.brief" "$model" "$adddir" > "$L/q-$n.nohup" 2>&1 & )
      log "started $lbl #$tot $n (local qwen, base $base)"
    elif [ "${engine:-agy}" = claude ]; then
      cmodel=$(grep -E '^claude_model=' "$Q/started/$n.meta" 2>/dev/null | cut -d= -f2-); cmodel=${cmodel:-haiku}
      ceffort=$(grep -E '^effort=' "$Q/started/$n.meta" 2>/dev/null | cut -d= -f2-); ceffort=${ceffort:-low}
      ( nohup /bin/bash "$OPS_STATE/claude_task.sh" "$wt" "$Q/started/$n.brief" "$cmodel" "$adddir" "$ceffort" > "$L/q-$n.nohup" 2>&1 & )
      log "started $lbl #$tot $n (claude $cmodel effort $ceffort, base $base)"
    else
      ( cd "$REPO" && nohup python3 "$OPS_STATE/argon_task_neon.py" --worktree "$wt" --brief "$Q/started/$n.brief" \
          --model "$model" --effort "${model##*-}" ${adddir:+--add-dir "$adddir"} --log "$L/q-$n.log" > "$L/q-$n.nohup" 2>&1 & )
      log "started $lbl #$tot $n ($model, base $base)"
    fi
  else
    wt_failed "$next" "$n"
  fi
}

lane_main() {
  set -u
  MAX=${1:-3}
  mkdir -p "$Q/started" "$Q/done" "$Q/hold" "$L"
  while true; do
    finish_pass
    # PAUSED: no new lane starts (2026-11-20: agy quota exhausted and headless claude auth expired drained the queue).
    if [ -e "$Q/PAUSED" ]; then sleep 60; continue; fi
    maybe_refill
    start_next_lane
    sleep 60
  done
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then lane_main "$@"; fi
