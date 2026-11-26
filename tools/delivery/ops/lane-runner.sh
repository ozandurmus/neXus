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
# Merged from the live copy on 2026-11-25 (PO decision record 2026-11-24 D2, D3):
#  * an exit-0 lane reaches the ship queue only through harness-gate.sh (typed harness exit -> ship entry, one repair turn,
#    or held); a repair turn that changed nothing (NO_CHANGE) re-runs the gate once on the original lane (second chance).
#  * with lane-queue/NO_REFILL set, the only refill is the P1 planner (exactly 3 lane briefs), see planner_due.
# The file only defines functions when sourced (tests); it runs the loop when executed.
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
Q=$OPS_STATE/lane-queue; L=$OPS_STATE/logs; SQ=$OPS_STATE/ship-queue; REPO=$OPS_REPO
log() { ops_log "$@"; }
# AGY_ONLY mode (PO decision record 2026-11-26): while lane-queue/AGY_ONLY exists Claude is out of quota and Antigravity
# carries the pipeline alone. Never decided anywhere else: ask this function.
agy_only() { [ -e "$Q/AGY_ONLY" ]; }

running() { ps -axo command | grep -E "(^/.*Python .*argon_task_neon\.py|^/bin/bash .*claude_task\.sh)" | grep -v grep | wc -l | tr -d ' '; }
# Local Qwen lanes (engine=qwen in .meta) have their own slot: at most 1 (18 GB model on a 32 GB Mac), never
# counted against MAX, and never overridden by ENGINE_CLAUDE.
qwen_running() { ps -axo command | grep -E "^/bin/bash .*qwen_task\.sh" | grep -v grep | wc -l | tr -d ' '; }
# Engine: meta engine=claude|agy; a file lane-queue/ENGINE_CLAUDE forces Claude for every lane.
# Auto-switch: when a finished agy lane's log shows a quota/limit error, the runner creates ENGINE_CLAUDE itself.
# Only the engine's own error lines count (2026-11-26: a planner's report that merely mentioned quotas was read as a quota
# hit, switched every lane to Claude, then paused the runner): agy "error:"/"AGY_ERROR:" lines, a line that starts with
# RESOURCE_EXHAUSTED, and the Claude CLI's own limit/API-error lines. Free text written by the model never matches.
quota_hit() {
  grep -qiE '^(error: .*(quota|rate.?limit|credits? (exhausted|exceeded))|AGY_ERROR: .*(RESOURCE_EXHAUSTED|429|quota)|RESOURCE_EXHAUSTED|Claude AI usage limit reached|API Error: (429|529)|.*usage limit reached\|[0-9]+$)' "$1" 2>/dev/null
}
# Markers (looked for in the lane's log): a permission the headless agent was refused, and transient infrastructure errors
# near the end of the log (start errors, connection and gateway errors).
PERM_RE='auto-denied|\[argon\] FAILED: a permission'
INFRA_RE='failed to start conversation|empty conversation id|ECONNRESET|connection reset|connection refused|service unavailable|overloaded_error|bad gateway|network is unreachable|could not resolve host|TLS handshake timeout|^error: interrupted$'

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
  local s=$1 n out age
  n=$(basename "$s" .brief)
  out=$(lane_outcome "$L/q-$n.nohup")
  [ "$out" = RUNNING ] && return 0
  age=$(( $(date +%s) - $(file_mtime "$s") ))
  # AGY_ONLY planner: its briefs are in the worktree (planner-out/), not in the ops state; an agent that wrote them and made
  # no commit ends NO_CHANGE, which is as good as OK here.
  case "$out" in OK|NO_CHANGE) grep -q '^planner_out=1' "$Q/started/$n.meta" 2>/dev/null && collect_planner_out "$n";; esac
  case "$out" in
    QUOTA)
      # Every lane that hit the quota goes back to the queue once (2026-11-25: the second lane of the same agy quota window
      # was archived FAILED and lost); a second quota hit on the same lane is FAILED, so a Claude quota cannot loop.
      if [ ! -e "$Q/.quota-$n" ]; then
        # An agy 429 ("Individual quota reached ... Resets in 10m") is a short per-model limit, not an exhausted account
        # (PO 2026-11-25 "antigravity'de kota sorunu yok"): mark that model for NEXUS_AGY_MODEL_REST_S and let routing pick
        # the next Gemini model; only an unknown engine switch falls back to ENGINE_CLAUDE.
        am=$(grep -E '^agy_model=' "$Q/started/$n.meta" 2>/dev/null | cut -d= -f2-)
        if [ -n "$am" ] && { agy_only || grep -qiE 'Individual quota|RESOURCE_EXHAUSTED' "$L/q-$n.nohup" 2>/dev/null; }; then
          touch "$Q/.agy-limited-$am"; log "agy $am rate-limited in $n -> other Gemini models first for a while"
        elif agy_only; then
          log "agy quota hit in $n under AGY_ONLY -> no Claude fallback; the brief is re-queued"
        else
          [ -e "$Q/ENGINE_CLAUDE" ] || { touch "$Q/ENGINE_CLAUDE"; log "agy quota hit in $n -> switching new lanes to Claude"; }
        fi
        touch "$Q/.quota-$n"; log "quota hit in $n -> re-queueing it once"
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
      archive_lane "$n" "$s" "NO_CHANGE"
      second_chance "$n"; return 0;;
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
  # OK. PO 2026-11-22 "shipler arka arkaya girecek": a lane that ended exit=0 goes on towards the ship queue on its own;
  # meta ship=no keeps review-only or PO-decision lanes out. Harness gate (PO decision record 2026-11-24 D3, wired
  # 2026-11-25): the ship entry is written by harness-gate.sh from a typed harness exit, never here. It starts after the
  # brief and meta moved to done/ (the gate and its repair turn read them there).
  local gate=""
  if ! grep -q '^ship=no' "$Q/started/$n.meta" 2>/dev/null; then
    gate=1
  else
    # A lane that never ships (ship=no: refill, review-only) needs no worktree either.
    git -C "$REPO" worktree remove "$OPS_CODO/nexus-q-$n" 2>/dev/null && log "removed worktree of $n"
  fi
  archive_lane "$n" "$s" "OK"
  if [ -n "$gate" ]; then start_harness_gate "$n"; log "harness gate started for $n"; fi
}

# collect_planner_out <name>: move the briefs an AGY_ONLY planner wrote into planner-out/ of its worktree into the lane queue
# under standard names. At most 3 briefs; only plain meta keys survive (never allow_paths, adddir or pin_claude), so a model
# cannot widen its own briefs' authority.
collect_planner_out() {
  local n=$1 wt="$OPS_CODO/nexus-q-$1" b m short nn cnt=0 k v
  [ -d "$wt/planner-out" ] || { log "planner $n: no planner-out/ in its worktree"; return 0; }
  for b in "$wt"/planner-out/*.brief; do
    [ -e "$b" ] || continue
    [ "$cnt" -ge 3 ] && break
    m=${b%.brief}.meta
    short=$(basename "$b" .brief | tr -c 'A-Za-z0-9._\n-' '-'); nn=$(bash "$OPS_DIR/lanename.sh" "$short") || continue
    { for k in base engine model claude_model effort ship; do
        v=$(grep -E "^$k=" "$m" 2>/dev/null | tail -1 | cut -d= -f2-)
        [ -z "$v" ] && case "$k" in base) v=origin/main;; engine) v=agy;; esac
        [ -n "$v" ] && echo "$k=$v"
      done; } > "$Q/$nn.meta.tmp"
    mv "$Q/$nn.meta.tmp" "$Q/$nn.meta"; cp "$b" "$Q/$nn.brief.tmp"; mv "$Q/$nn.brief.tmp" "$Q/$nn.brief"
    cnt=$((cnt + 1)); log "planner $n: queued $nn from planner-out/"
  done
  [ "$cnt" -gt 0 ] || log "ALERT planner $n wrote no brief into planner-out/"
}

# start_harness_gate <name>: the gate runs in the background (serialized by its own lock).
start_harness_gate() { ( nohup /bin/bash "$OPS_DIR/harness-gate.sh" "$1" > "$L/harness-gate-$1.nohup" 2>&1 & ); }

# second_chance <name>: a harness repair turn (meta repair_of=<original>) that changed nothing -- the lane judged the
# failures load or flakes (2026-11-25 G601) -- re-runs the gate once on the original lane. The original's meta is marked
# repair_of= first, so a second failure there is held for the PO session instead of getting another repair turn.
second_chance() {
  local n=$1 orig
  orig=$(grep -E '^repair_of=' "$Q/done/$n.meta" 2>/dev/null | tail -1 | cut -d= -f2-)
  [ -n "$orig" ] && [ -e "$Q/done/$orig.meta" ] || return 0
  grep -q '^repair_of=' "$Q/done/$orig.meta" || echo "repair_of=$orig" >> "$Q/done/$orig.meta"
  start_harness_gate "$orig"
  log "repair turn $n changed nothing -> harness gate re-run for $orig (second chance)"
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

# lane_hour: the current hour (00-23); a function so tests can pin it.
lane_hour() { date +%H; }
# Lane cap the planner fills to (PO decision record 2026-11-24 D1: 3 parallel lanes).
PLANNER_LANES=${NEXUS_PLANNER_LANES:-3}

# planner_due <queued-briefs> <last-refill-epoch>: 0 when the P1 planner must be queued now (PO decision record 2026-11-24
# D2, enforced 2026-11-25 "beklemek tasarimda yok": the queue never waits for the PO session). Only with NO_REFILL set:
#  * no brief waits AND fewer than PLANNER_LANES lanes run (an empty queue with every slot busy is not starvation:
#    2026-11-25 the planner fired while 3 lanes ran and stacked 3 more briefs), or
#  * at 08:xx or 16:xx, when fewer than 2 briefs wait and no refill was queued in the last 2 hours.
planner_due() {
  local qn=$1 last=$2 run_n hh
  [ -e "$Q/NO_REFILL" ] || return 1
  run_n=$(ls "$Q"/started/*.brief 2>/dev/null | wc -l | tr -d ' ')
  [ "$qn" -eq 0 ] && [ "$run_n" -lt "$PLANNER_LANES" ] && return 0
  hh=$(lane_hour)
  { [ "$hh" = 08 ] || [ "$hh" = 16 ]; } && [ "$qn" -lt 2 ] && [ $(( $(date +%s) - last )) -gt 7200 ]
}

# agy_state_blob: the ops state a Claude refill/planner lane would read from outside its worktree, as inline text for an
# AGY_ONLY brief (headless agy cannot read outside its worktree). Bounded to ~40 KB: status.sh, the open brief names
# (queued, started, hold/) and project/QUEUE.md from origin/main.
agy_state_blob() {
  {
    echo "## Inlined ops state (AGY_ONLY: you cannot read files outside this worktree)"
    echo; echo "### status.sh"; bash "$OPS_DIR/status.sh" 2>&1 | head -c 6000
    echo; echo "### open briefs (queued / started / hold)"
    { ls "$Q"/*.brief 2>/dev/null | sed 's#.*/#queued: #; s#\.brief$##'
      ls "$Q"/started/*.brief 2>/dev/null | sed 's#.*/#started: #; s#\.brief$##'
      ls "$Q"/hold/*.brief 2>/dev/null | sed 's#.*/#hold: #; s#\.brief$##'; } | head -c 6000
    echo; echo "### project/QUEUE.md (origin/main)"; git -C "$REPO" show origin/main:project/QUEUE.md 2>&1
  } | head -c 40000
}

# Refill (PO 2026-11-22: work runs back to back): without NO_REFILL, with fewer than 4 briefs queued and no refill lane
# queued or running, queue one rotating refill lane (p1 -> debt -> backlog -> review), at most every 5 minutes. With
# NO_REFILL (PO 2026-11-24) the rotating refill stops and only the P1 planner runs (planner_due).
maybe_refill() {
  local qn last sq prev kind rn pd=0
  qn=$(ls "$Q"/*.brief 2>/dev/null | wc -l | tr -d ' ')
  last=$(file_mtime "$Q/.last-refill")
  sq=$(ls "$SQ"/*.ship 2>/dev/null | wc -l | tr -d ' ')
  planner_due "$qn" "$last" && pd=1
  # Do not outrun the ship pipeline: no refill while 10 or more lanes wait to ship.
  if { [ "$pd" = 1 ] || { [ ! -e "$Q/NO_REFILL" ] && [ "$qn" -lt 4 ]; }; } && [ "$sq" -lt 10 ] \
     && ! ls "$Q"/*-refill-*.brief "$Q"/started/*-refill-*.brief 2>/dev/null | grep -q . && [ $(( $(date +%s) - last )) -gt 300 ]; then
    prev=$(cat "$Q/.last-refill-kind" 2>/dev/null); case "$prev" in p1) kind=debt;; debt) kind=backlog;; backlog) kind=review;; *) kind=p1;; esac
    [ "$pd" = 1 ] && kind=p1
    rn=$(bash "$OPS_DIR/lanename.sh" "refill-$kind")  # standard name at queue time (PO 2026-11-24)
    # pin_claude=1: the planner reads the ops state outside its worktree, which headless agy refuses (read_file auto-denied,
    # 2026-11-25 G706); it is short, so it always runs on Claude.
    if agy_only; then
      # AGY_ONLY (PO 2026-11-26): no Claude, no adddir; the state is inlined below, a planner writes into planner-out/.
      printf 'base=origin/main\nengine=agy\nship=no\n' > "$Q/$rn.meta"; [ "$kind" = p1 ] && echo 'planner_out=1' >> "$Q/$rn.meta"
    else
      printf 'base=origin/main\nengine=claude\npin_claude=1\nship=no\nadddir=%s\n' "$OPS_STATE" > "$Q/$rn.meta"
    fi
    cp "$OPS_STATE/refill-${kind%b}.template" "$Q/$rn.brief.tmp" || { rm -f "$Q/$rn.meta" "$Q/$rn.brief.tmp"; log "ALERT refill template refill-${kind%b}.template missing"; return 0; }
    if [ "$pd" = 1 ]; then
      # The planner writes exactly as many briefs as the lane cap (PO 2026-11-24), whatever the template says.
      sed_inplace 's/Write 3 to 6 lane briefs/Write exactly 3 lane briefs (lane cap 3, PO 2026-11-24)/' "$Q/$rn.brief.tmp"
      grep -q 'exactly 3 lane briefs' "$Q/$rn.brief.tmp" || printf '\nWrite exactly 3 lane briefs (lane cap 3, PO 2026-11-24).\n' >> "$Q/$rn.brief.tmp"
    fi
    if agy_only; then
      { echo; agy_state_blob
        if [ "$kind" = p1 ]; then
          printf '\n## AGY_ONLY output location\nWrite the lane briefs as files in the directory planner-out/ of THIS worktree: <short-name>.brief and\n<short-name>.meta (meta lines such as base=origin/main, engine=agy). Do not write into the ops state directory; the lane\nrunner moves planner-out/*.brief and *.meta into the lane queue when this lane ends. Do not commit planner-out/.\n'
        fi; } >> "$Q/$rn.brief.tmp"
    fi
    mv "$Q/$rn.brief.tmp" "$Q/$rn.brief"
    agy_only || case "$kind" in p1*) printf 'claude_model=sonnet\neffort=high\n' >> "$Q/$rn.meta";; esac
    touch "$Q/.last-refill"; echo "$kind" > "$Q/.last-refill-kind"
    if [ "$pd" = 1 ]; then log "queued refill $rn (planner)"; else log "queued refill $rn"; fi
  fi
}

# agy_limited <model>: a 429 on this Gemini model within NEXUS_AGY_MODEL_REST_S (default 15 min).
agy_limited() {
  local f="$Q/.agy-limited-$1"
  [ -e "$f" ] && [ $(( $(date +%s) - $(file_mtime "$f") )) -lt "${NEXUS_AGY_MODEL_REST_S:-900}" ]
}

# Pick the lowest-numbered brief whose engine has a free slot (qwen: 1 own slot; others: MAX) and start it.
start_next_lane() {
  local b next="" n meta base model adddir engine agy_now desc tot lbl n2 wt cmodel ceffort explicit_model
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
  engine=$(grep -E '^engine=' "$meta" 2>/dev/null | cut -d= -f2-)
  # An explicit model=gemini-4-* in the meta of an agy lane is kept (its effort is the model name's suffix); without it the
  # lane name picks the model below.
  explicit_model=""; if [ "${engine:-agy}" = agy ] && echo "$model" | grep -qE '^gemini-4-'; then
    grep -qE '^model=' "$meta" 2>/dev/null && explicit_model=$model
  fi
  # AGY_ONLY: Claude is out of quota, so ENGINE_CLAUDE and engine=claude are ignored (qwen keeps its own slot).
  if agy_only; then
    [ "$engine" = claude ] && engine=agy
  else
    [ -e "$Q/ENGINE_CLAUDE" ] && [ "$engine" != qwen ] && engine=claude
  fi
  # Model routing by job kind (MODEL_ROUTING.md, PO 2026-11-23): spread lanes over the Gemini 4 models on Antigravity's own
  # quota. Not when the meta names a Claude model, ENGINE_CLAUDE is set (agy quota) or AGY_OFF exists.
  # Half and half (PO 2026-11-23 "yarı yarıya Claude ve antigravity"): at most half of the slots on Antigravity.
  agy_now=$(ps -axo command | grep -E "^/.*Python .*argon_task_neon\.py" | grep -v grep | wc -l | tr -d ' ')
  # A refill-written claude_model=haiku default no longer pins a lane to Claude (2026-11-24: debt lanes ran on Haiku while
  # Antigravity slots sat idle); only pin_claude=1 in the meta does.
  # AGY_ONLY lifts the half-and-half cap, pin_claude, ENGINE_CLAUDE and AGY_OFF: every one of the MAX slots may run on agy.
  if { agy_only || { ! grep -qE '^pin_claude=1' "$meta" 2>/dev/null && [ ! -e "$Q/ENGINE_CLAUDE" ] && [ ! -e "$Q/AGY_OFF" ] \
     && [ "$agy_now" -lt $(( (MAX + 1) / 2 )) ]; }; } && [ "$engine" != qwen ]; then
    [ -n "$explicit_model" ] && engine=agy
    # PO 2026-11-24: Antigravity back, 3 parallel lanes; Carbon / Neon / Hydrogen by difficulty (MODEL_ROUTING.md).
    [ -z "$explicit_model" ] && case "$n" in
      *-state-sync*) agy_only && { engine=agy; model=gemini-4-neon-high; };;  # Claude Sonnet normally (project records; tier B rule)
      *-debt-*|*-test*|*refill-backlog*|*refill-debt*) engine=agy; model=gemini-4-hydrogen-high;;
      *-neon-review-findings*) engine=agy; model=gemini-4-neon-high;;  # fixes on delivery/security scripts: keep high
      *refill-review*|*-review-*) engine=agy; model=gemini-4-neon-high;;
      *-port-*|*-fix-*|*refill-ui*) engine=agy; model=gemini-4-neon-medium;;  # PO 2026-11-24: medium is enough here
      *-p1-*|*refill-p1*) engine=agy; model=gemini-4-carbon-high;;
    esac
    # AGY_ONLY: a lane the names above did not route still goes to agy (Neon high), never to Claude.
    if agy_only && [ -z "$explicit_model" ] && ! echo "$model" | grep -qE 'carbon|neon|hydrogen'; then engine=agy; model=gemini-4-neon-high; fi
    agy_only && [ "$engine" != agy ] && engine=agy
    # A model marked rate-limited by a recent 429 is skipped: next in carbon -> neon -> hydrogen (high), else Claude.
    if [ "$engine" = agy ] && agy_limited "$model"; then
      local alt picked=""
      for alt in gemini-4-carbon-high gemini-4-neon-high gemini-4-hydrogen-high; do
        [ "$alt" = "$model" ] && continue
        agy_limited "$alt" || { picked=$alt; break; }
      done
      if [ -n "$picked" ]; then log "agy $model rate-limited -> $n on $picked"; model=$picked
      elif agy_only; then log "every agy model rate-limited under AGY_ONLY -> $n stays queued (no Claude fallback)"; return 0
      else engine=claude; fi
    fi
  fi
  # Antigravity cap (PO 2026-11-24: 3 parallel lanes there, Carbon/Neon/Hydrogen only): a lane that is not routed to a
  # Gemini model because the cap is full runs on Claude Sonnet instead of falling back to the Argon default.
  if ! agy_only && [ "${engine:-agy}" = agy ] && { [ "$agy_now" -ge $(( (MAX + 1) / 2 )) ] || ! echo "$model" | grep -qE 'carbon|neon|hydrogen'; }; then
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
      echo "agy_model=$model" >> "$Q/started/$n.meta"   # which Gemini model a 429 belongs to (agy_limited)
      ( cd "$REPO" && nohup python3 "$OPS_STATE/argon_task_neon.py" --worktree "$wt" --brief "$Q/started/$n.brief" \
          --model "$model" --effort "${model##*-}" ${adddir:+--add-dir "$adddir"} --log "$L/q-$n.log" > "$L/q-$n.nohup" 2>&1 & )
      log "started $lbl #$tot $n ($model, base $base)"
    fi
  else
    wt_failed "$next" "$n"
  fi
}

# script_sum <file>: a content hash for the self-update check.
script_sum() { cksum < "$1" 2>/dev/null | awk '{print $1 "-" $2}'; }
# lane_reexec: replace this process with the new script; the lanes run in their own nohup processes and are never touched.
lane_reexec() { exec /bin/bash "$LANE_SELF" "$MAX"; }
# lane_self_check: re-exec when the script file changed since this process loaded it (PO 2026-11-26: a shipped runner change
# takes effect without a person restarting the runner).
lane_self_check() {
  [ -n "${LANE_SELF:-}" ] && [ -n "${LANE_SELF_SUM:-}" ] || return 0
  [ "$(script_sum "$LANE_SELF")" = "$LANE_SELF_SUM" ] && return 0
  log "lane-runner.sh changed on disk -> re-executing (running lanes keep running)"; lane_reexec
}

lane_main() {
  set -u
  MAX=${1:-3}
  LANE_SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}"); LANE_SELF_SUM=$(script_sum "$LANE_SELF")
  mkdir -p "$Q/started" "$Q/done" "$Q/hold" "$L"
  while true; do
    lane_self_check
    finish_pass
    # PAUSED: no new lane starts (2026-11-20: agy quota exhausted and headless claude auth expired drained the queue).
    if [ -e "$Q/PAUSED" ]; then sleep 60; continue; fi
    # An EMPTY ENGINE_CLAUDE is the runner's own quota switch (a person's switch carries a reason line): agy quotas reset
    # within minutes ("Resets in 10m"), so after NEXUS_AGY_QUOTA_BACKOFF_S (default 20 min) agy takes lanes again.
    if [ -e "$Q/ENGINE_CLAUDE" ] && [ ! -s "$Q/ENGINE_CLAUDE" ] \
       && [ $(( $(date +%s) - $(file_mtime "$Q/ENGINE_CLAUDE") )) -gt "${NEXUS_AGY_QUOTA_BACKOFF_S:-1200}" ]; then
      rm -f "$Q/ENGINE_CLAUDE"; log "agy quota back-off over -> agy takes lanes again"
    fi
    maybe_refill
    start_next_lane
    sleep 60
  done
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then lane_main "$@"; fi
