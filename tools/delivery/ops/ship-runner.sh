#!/bin/bash
# Ships finished lanes back to back without a Claude session awake (PO 2026-11-22: "shipler arka arkaya girecek").
# Queue: $NEXUS_OPS_STATE/ship-queue/<name>.ship, lines: branch=<ref>, name=<lane name>, optional allow_paths=1,
# single=1, retries=<n>. lane-runner.sh writes one for every lane that ends exit=0 with a commit.
# Each round: up to BATCH items (oldest first) become ONE commit on origin/main in $NEXUS_OPS_CODO/nexus-shiprun (each lane's
# own diff from its merge-base); a lane that conflicts is left out and gets a port lane; then shipq3.sh ships it
# (scoped unit tests, preview e2e, security gate, merge, deploy, prod e2e) and the runner waits for its DONE line.
# SHIPPED: lane worktrees removed (PO 2026-11-20), items to done/. FAILED: a multi-lane batch is split (single=1);
# a single lane goes to failed/ with one fix lane; an infrastructure failure (VPN, security gate, busy deploy) keeps
# the item queued and pauses the runner after the second one in a row (ship-queue/PAUSED; delete it to resume).
# Versioned copy (tools/delivery/ops, 2026-11-24 review): the state-sync trigger is replaced by record_fold_hook (a no-op);
# a lane the runner gives up on (no second fix, no third port) is logged as ALERT and listed by status.sh and watch-check.sh.
# The file only defines functions when sourced (tests); it runs the loop when executed.
# launchd: com.securityexpert.overwatch.ship-runner (KeepAlive), log $NEXUS_OPS_STATE/logs/ship-runner.log
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
SQ=$OPS_STATE/ship-queue; LQ=$OPS_STATE/lane-queue; L=$OPS_STATE/logs; J=$OPS_STATE
WT=$OPS_CODO/nexus-shiprun; REPO=$OPS_REPO
log() { ops_log "$@"; }
# Paths a lane must not change without a person looking (allow_paths=1 in the .ship file overrides).
# Tier C (PO 2026-11-23): security-relevant runtime code and its tests are held for a person too: device transport,
# credentials, failover, authorization, redaction/sanitizer, security, job claim/lease and endpoint admission.
GUARD='^(\.github/|\.githooks/|deploy/|scripts/|tools/delivery/|security/|ui2/service/src/main/resources/db/migration/|ui2/capability-registry/src/main/resources/capabilities/|ui2/sanitizer/)|^ui2/[^/]+/src/(main|test)/java/.*/(transport|credential|failover|authz|redaction|security|lease|admission)/|Allowlist\.java$|GateRegistry'
# Rendered files: on conflict take the current tree's copy and re-render after all lanes are applied.
GEN='^(docs/design/DECISION_RECORD_SUCCESSOR_INDEX\.md|ui2/service/src/test/resources/authorization/route-authorization-decision-table\.golden\.txt|tools/security/aiview_leak_audit/routes\.json)$'
# Another ship or deploy (a peer session's chain included); agent prompts that only mention the names do not count.
busy() {
  for p in $(pgrep -f 'shipq3[.]sh|ship-after[.]sh|hosta_deploy[.]sh|module_deploy[.]py|standalone_orchestrate[.]py ship|hosta_e2e[.]sh'); do
    ps -o comm= -p "$p" | grep -qE 'agy|claude|node' && continue
    ps -o command= -p "$p" | head -1 | grep -qE 'argon_task|agy -p|claude -p' && continue
    # A Claude shell whose command text merely names shipq3.sh blocked the runner 08:43-10:05 on 2026-11-23.
    ps -o command= -p "$p" | head -1 | grep -qE 'shell-snapshots|^/bin/zsh -c' && continue
    return 0
  done; return 1; }
in_hold() { [ "${NEXUS_SHIP_WINDOWS:-1}" = 1 ] || return 1; local h m hn; h=$(TZ=Europe/Istanbul date +%H); m=$(TZ=Europe/Istanbul date +%M); hn=$(( (10#$h + 1) % 24 ))
  case $h in 00|06|12|18|02) return 0;; esac
  case $hn in 0|6|12|18) [ $((10#$m)) -ge 20 ] && return 0;; esac; return 1; }
rm_lane_wt() { local n=$1; [ -d "$OPS_CODO/nexus-q-$n" ] && git -C "$REPO" worktree remove --force "$OPS_CODO/nexus-q-$n" 2>/dev/null; return 0; }
# Finish an in-flight batch whose shipq3 wrote DONE: SHIPPED items to done/, a failure back to the queue (retry, split,
# blame or a fix lane). In-flight items live in ship-queue/inflight/<ID>/ so a pipelined batch cannot pick them again.
finish_batch() {
  local ID=$1 D="$SQ/inflight/$1" OUT="$L/ship-$1.out" ok=() names=() f i why culprit names_in hits br mb b n
  grep -q '^DONE' "$OUT" 2>/dev/null || return 0
  for f in "$D"/*.ship; do [ -e "$f" ] || continue; ok+=("$f"); names+=("$(field name "$f")"); done
  [ ${#ok[@]} -eq 0 ] && { rm -rf "$D"; return 0; }
  if grep -q '^SHIPPED' "$OUT"; then
    infra=0; log "SHIPPED batch/$ID: ${names[*]}"
    for i in "${!ok[@]}"; do mv "${ok[$i]}" "$SQ/done/"; rm_lane_wt "${names[$i]}"; done
    # State sync used to queue a Sonnet lane here after every 2 shipped batches (13 lanes/day, 18 % of all lane starts on
    # 2026-11-24). The records are now folded deterministically from what merged (a later lane); until then this is a no-op.
    record_fold_hook "${names[*]}"
    rm -rf "$D"; return 0
  fi
  # Back into the queue (mv keeps the mtime, so the order holds); the decisions below annotate or move them there.
  for i in "${!ok[@]}"; do mv "${ok[$i]}" "$SQ/"; ok[$i]="$SQ/$(basename "${ok[$i]}")"; done
  rm -rf "$D"
  why=$(grep -E '^FAILED|not merged|did not pass|could not open the pull request|HTTP (429|5[0-9][0-9])|Too Many Requests' "$OUT" | tail -3 | tr '\n' ' ' | cut -c1-400); log "FAILED batch/$ID: $why"
  # A failed batch never merges under its own ID again (retry/split ship new IDs): close its PR so GitHub lists only live work (4 stale PRs 2026-11-23).
  ( cd "$REPO" && gh pr list --head "batch/$ID" --state open --json number -q ".[].number" 2>/dev/null | xargs -I{} gh pr close {} --comment "Batch failed; its changes are retried or split into later batches by the ship runner." >/dev/null 2>&1 )
  # A unit-test or compile failure is never infrastructure: its summary quotes test titles, and one title holding "SSH"
  # matched the ssh pattern and paused the runner (2026-11-24, 414 compile error).
  if ! echo "$why" | grep -q 'unit tests did not pass' && echo "$why" | grep -qiE 'preview e2e failed|security gate|still running|rebase|could not resolve|Connection|timed out|ssh|previous ship rolled back|GraphQL|api.github.com|pull request not merged|still pending|cancelled|could not open the pull request|HTTP (429|5[0-9][0-9])|Too Many Requests|Bad Gateway'; then
    infra=$((infra + 1))
    if [ "$infra" -ge 2 ]; then touch "$SQ/PAUSED"; log "PAUSED: two infrastructure failures in a row; items stay queued (rm $SQ/PAUSED to resume)"; fi
    return 0
  fi
  infra=0
  # Blame: when the failure names files (test or source) that exactly one lane of the batch touched, fail only that lane
  # and keep the others queued as they were (2026-11-22: one test word split a 3-lane batch into 3 single ships).
  culprit=""
  if [ ${#ok[@]} -gt 1 ]; then
    names_in=$( { echo "$why"; cat "$L/pretest-ship-$ID.verdict" 2>/dev/null; } | grep -oE '/[A-Za-z0-9_-]{6,}(\.(tsx?|java|kts?))?' | sed 's#^/##; s#\.[a-z]*$##' | sort -u)
    hits=()
    for i in "${!ok[@]}"; do
      br=$(field branch "${ok[$i]}"); mb=$(git -C "$REPO" merge-base "$br" origin/main)
      for b in $names_in; do git -C "$REPO" diff --name-only "$mb" "$br" | grep -q "/$b[^/]*\$" && { hits+=("$i"); break; }; done
    done
    [ ${#hits[@]} -eq 1 ] && culprit=${hits[0]}
  fi
  # No lane touched any file the failure names (2026-11-22 20:50: ScriptRunBrokerTest, a process-timing test, failed under
  # load): likely flaky. Retry the same batch once before splitting it.
  # A batch that was rolled back in production is never retried whole: it goes straight to single ships.
  if [ -z "$culprit" ] && [ ${#ok[@]} -gt 1 ] && [ ${#hits[@]} -eq 0 ] && ! grep -qs '^retried=1' "${ok[@]}" && ! grep -q '^ROLLED BACK' "$OUT"; then
    for f in "${ok[@]}"; do echo "retried=1" >> "$f"; done; log "no lane touched the failing files; retrying the batch once"; return 0
  fi
  if [ -n "$culprit" ]; then
    log "blame: ${names[$culprit]} (failure names its files); the other lanes stay queued"
    ok=("${ok[$culprit]}"); names=("${names[$culprit]}")
  fi
  if [ ${#ok[@]} -gt 1 ]; then
    for f in "${ok[@]}"; do echo "single=1" >> "$f"; done; log "batch split: lanes ship one by one"
  else
    f=${ok[0]}; n=${names[0]}; br=$(field branch "$f"); mv "$f" "$SQ/failed/"
    # Pipeline fix lanes (NN-fix-<lane>) get no second fix; review-fix lanes (NN-fix-review-*) are new work and may.
    case "$n" in *[0-9]-port-[0-9]*|*[0-9]-fix-[0-9]*) log "ALERT no second fix for $n; left in failed/";;
      *) migfix=""; [[ "$why" == *"not above main"* ]] && migfix=$'\n'"This failure is a migration number clash: rebase onto origin/main, get a new number with bash $OPS_DIR/reserve-migration.sh fix-$n, git mv the migration to that V number, replace every reference to the old number in this branch's files, run the migration/schema tests, commit."
         extra=""; grep -q '^allow_paths=1' "$SQ/failed/$(basename "$f")" && extra="allow_paths=1"   # a fix lane inherits the person's guarded-path approval (271 held 2026-11-23)
         lane_brief "fix-$n" "$br" "# Task: make lane $n pass the ship pipeline
This worktree is branch $br (finished lane $n). Shipping it failed: $why
Read the logs with tail/grep (they are long): $L/ship-$ID.log and $L/pretest-ship-$ID.out. Find the failing test or
check, fix it in this branch's changes (not by weakening the test), run that test and the module's tests, commit.${migfix}" ${extra:+"$extra"};;
    esac; rm_lane_wt "$n"
  fi
}
# record_fold_hook <names>: called once per SHIPPED batch. A no-op for now: the state-sync lanes it replaces are gone, and
# the deterministic record fold (build_history, backlog, CURRENT_STATE from the merged commits) comes in a later lane.
record_fold_hook() { :; }

infra=0
ship_main() {
  set -u
  BATCH=${1:-3}; BATCH0=$BATCH
  mkdir -p "$SQ/done" "$SQ/failed" "$SQ/held" "$SQ/inflight" "$J/batches"
  log "ship-runner started (batch $BATCH)"
  while true; do
    for d in "$SQ"/inflight/*/; do [ -d "$d" ] && finish_batch "$(basename "$d")"; done
    [ -e "$SQ/PAUSED" ] && { sleep 60; continue; }
    # Pipelined ships (PO 2026-11-22): at most two batches in flight; a new one starts only once the one in flight has
    # merged, so its preview and unit tests run during that batch's deploy and prod e2e, and it merges after it ends.
    inflight=$(ls -d "$SQ"/inflight/*/ 2>/dev/null | wc -l | tr -d ' ')
    [ "$inflight" -ge 2 ] && { sleep 30; continue; }
    AFTER=""
    if [ "$inflight" -eq 1 ]; then
      prev=$(basename "$(ls -d "$SQ"/inflight/*/)")
      # The orchestrator's "merged" line is block-buffered (seen 2026-11-22 23:01): ask GitHub for the PR state instead.
      pr=$(grep -oE 'https://github.com/[^"]+/pull/[0-9]+' "$L/ship-$prev.log" 2>/dev/null | tail -1)
      { grep -q '"merged": "' "$L/ship-$prev.log" 2>/dev/null || { [ -n "$pr" ] && [ "$(cd "$REPO" && gh pr view "$pr" --json state -q .state 2>/dev/null)" = MERGED ]; }; } || { sleep 30; continue; }
      AFTER=$L/ship-$prev.out
    fi
    # Port and fix lanes first (oldest first), then the rest oldest first: a port waits behind newer lanes, main moves on
    # and it clashes again (2026-11-22: ports 77/78 re-conflicted after queueing 90 minutes).
    # Deferred lanes lead: otherwise a stream of new fix lanes clashing with them starves them (2026-11-22: 131 deferred 4x).
    items=(); while IFS= read -r f; do items+=("$f"); done < <( { P=$(grep -l '^prio=1' "$SQ"/*.ship 2>/dev/null); [ -n "$P" ] && ls -tr $P 2>/dev/null; ls "$SQ"/*-state-sync*.ship 2>/dev/null; D=$(grep -l '^deferred=1' "$SQ"/*.ship 2>/dev/null); [ -n "$D" ] && ls -tr $D 2>/dev/null; ls -tr "$SQ"/*.ship 2>/dev/null | grep -vxF "$D" | grep -E '/([0-9]+-|[0-9]{8}-[0-9]{4}-G[0-9]+-D[0-9]+-)(port|fix)-'; ls -tr "$SQ"/*.ship 2>/dev/null | grep -vxF "$D" | grep -vE '/([0-9]+-|[0-9]{8}-[0-9]{4}-G[0-9]+-D[0-9]+-)(port|fix)-'; } | awk 'NF && !seen[$0]++' )
    [ ${#items[@]} -eq 0 ] && { sleep 60; continue; }
    [ -z "$AFTER" ] && busy && { sleep 60; continue; }
    # Do not build a batch inside a ship hold (shipq3 would sit on it while newer lanes finish): hours 00/02/06/12/18
    # and after :20 of the hour before 00/06/12/18 (job windows, PO 2026-10-16).
    # No batch hold here any more: shipq3 holds only the merge and deploy (2026-11-22), the preview runs in any hour.
    # Gather: with lanes still running, give a short batch up to 3 minutes to fill (was 10; PO 2026-11-22 fast fixes) before shipping it.
    age=$(( $(date +%s) - $(file_mtime "${items[0]}") ))
    if [ ${#items[@]} -lt "$BATCH" ] && [ "$age" -lt 180 ] && pgrep -f "claude_task[.]sh|argon_task_neon[.]py" >/dev/null; then sleep 60; continue; fi
    # PO 2026-11-24 "tek tek": while ship-queue/ONE_BY_ONE exists every ship carries one item.
    if [ -e "$SQ/ONE_BY_ONE" ]; then BATCH=1; else BATCH=$BATCH0; fi
    sel=()
    for f in "${items[@]}"; do
      grep -q '^review=pending' "$f" && continue   # tier B review still running (b-review.sh)
      # Main-green gate (main-green.sh, PO 2026-11-24): while main is red only the fix for main ships.
      [ -e "$SQ/MAIN_RED" ] && ! grep -q '^mainfix=1' "$f" && continue
      if grep -q '^single=1' "$f"; then [ ${#sel[@]} -eq 0 ] && sel=("$f"); break; fi
      sel+=("$f"); [ ${#sel[@]} -ge "$BATCH" ] && break
    done
    # Nothing eligible (main red and no mainfix item, or all under review): wait instead of exiting on an empty sel.
    [ ${#sel[@]} -eq 0 ] && { sleep 60; continue; }
    git -C "$REPO" fetch -q origin main || { log "fetch failed"; sleep 120; continue; }
    git -C "$REPO" worktree remove --force "$WT" >/dev/null 2>&1; git -C "$REPO" worktree prune
    git -C "$REPO" worktree add -q --detach "$WT" origin/main || { log "worktree add failed"; sleep 300; continue; }
    cd "$WT" || continue
    ok=(); names=(); subjects=(); body=$(mktemp); G=0
    for f in "${sel[@]}"; do
      br=$(field branch "$f"); n=$(field name "$f")
      git rev-parse -q --verify "$br" >/dev/null || { log "FAILED $n: branch $br missing"; mv "$f" "$SQ/failed/"; continue; }
      mb=$(git merge-base "$br" origin/main); files=$(git diff --name-only "$mb" "$br")
      [ -z "$files" ] && { log "nothing to ship for $n (already on main)"; mv "$f" "$SQ/done/"; rm_lane_wt "$n"; continue; }
      if ! grep -q '^allow_paths=1' "$f" && echo "$files" | grep -qE "$GUARD"; then
        log "HELD $n: touches guarded paths ($(echo "$files" | grep -E "$GUARD" | head -3 | tr '\n' ' ')); needs a person"; mv "$f" "$SQ/held/"; continue
      fi
      git diff --binary "$mb" "$br" > /tmp/shiprun.patch
      git apply -3 --index /tmp/shiprun.patch > /tmp/shiprun.err 2>&1; rc=$?
      uu=$(git diff --name-only --diff-filter=U)
      if [ -n "$uu" ] && ! echo "$uu" | grep -qvE "$GEN"; then
        for x in $uu; do git show HEAD:"$x" > "$x" 2>/dev/null; git add "$x"; done; G=1; rc=0
      fi
      if { [ "$rc" != 0 ] || [ -n "$(git diff --name-only --diff-filter=U)" ]; } && [ ${#ok[@]} -gt 0 ]; then
        # Clashes with a lane earlier in this batch, not with main: keep it queued; it leads the next batch on the new main.
        log "DEFER $n: clashes with ${names[*]} in this batch; next batch"
        grep -q '^deferred=1' "$f" || { touch -r "$f" /tmp/shiprun.mt; echo deferred=1 >> "$f"; touch -r /tmp/shiprun.mt "$f"; }
        git reset -q --hard HEAD; git clean -fdq; continue
      fi
      if [ "$rc" != 0 ] || [ -n "$(git diff --name-only --diff-filter=U)" ]; then
        log "CONFLICT $n with main: $( (git diff --name-only --diff-filter=U; head -2 /tmp/shiprun.err) | head -3 | tr '\n' ' ')"
        git reset -q --hard HEAD; git clean -fdq
        mv "$f" "$SQ/failed/"
        # Review-fix lanes (NN-fix-review-*) may be ported like any lane; pipeline fix lanes may not (265/267/268 dropped 2026-11-23).
        case "$n" in *estate*) log "no port for $n (PO 2026-11-23: Estate work stopped)";; *[0-9]-port-*[0-9]-port-*|*[0-9]-fix-[0-9]*) log "ALERT no third port for $n; left in failed/";;
          *) lane_brief "port-$n" "origin/main" "# Task: carry lane $n onto the current main
Branch $br holds finished work that no longer applies cleanly to origin/main. Re-apply its changes on this worktree
(\`git diff $mb $br\` shows them; \`git log $mb..$br\` explains them), resolving conflicts so both main's newer work and
the lane's change are kept. Run the tests the change touches, then commit with the lane's original message.";;
        esac; rm_lane_wt "$n"; continue
      fi
      git -c user.name="$(git config user.name)" -c user.email="$(git config user.email)" commit -q --no-verify -m "tmp $n"
      ok+=("$f"); names+=("$n"); subjects+=("$(git log -1 --format=%s "$br")")
      git log --reverse --format='%B' "$mb..$br" | grep -vE '^Co-Authored-By:' >> "$body"
    done
    [ ${#ok[@]} -eq 0 ] && { cd "$J"; continue; }
    git reset -q --soft origin/main
    if [ "$G" = 1 ]; then
      python3 tools/delivery/successor_index.py render >/dev/null 2>&1; git add -A docs
      (cd ui2 && UI2_DECISION_TABLE_RECORD=$PWD/service/src/test/resources/authorization/route-authorization-decision-table.golden.txt \
        ./gradlew -q :service:test --tests '*RouteAuthorizationDecisionTableTest*' --offline >/dev/null 2>&1)
      git add ui2/service/src/test/resources/authorization/route-authorization-decision-table.golden.txt 2>/dev/null
      [ -f tools/security/aiview_leak_audit/gen_routes.py ] && python3 tools/security/aiview_leak_audit/gen_routes.py >/dev/null 2>&1 && git add -A tools/security/aiview_leak_audit
    fi
    read -r ID DTOT < <(bash "$OPS_DIR/counter.sh" D "${names[*]}")
    if [ ${#ok[@]} -eq 1 ]; then TITLE=${subjects[0]}; else TITLE="Deploy $ID #$DTOT: $(IFS=';'; echo "${subjects[*]}" | sed 's/;/; /g')"; fi
    TITLE=$(echo "$TITLE" | cut -c1-120)
    { echo "$TITLE"; echo; [ ${#ok[@]} -gt 1 ] && echo "Lanes: ${names[*]}" && echo; sed '/^tmp /d' "$body" | cat -s
      echo; echo "Co-Authored-By: Claude Haiku 5.5 <$OPS_NOREPLY>"; echo "Co-Authored-By: Claude Opus 5.5 <$OPS_NOREPLY>"; } > "$J/batches/$ID.msg"
    git -c user.name="$(git config user.name)" -c user.email="$(git config user.email)" commit -q --no-verify -F "$J/batches/$ID.msg" \
      || { log "nothing left to commit for ${names[*]}"; for f in "${ok[@]}"; do mv "$f" "$SQ/done/"; done; cd "$J"; continue; }
    # The commit lives in this worktree: name it by its sha (a bare HEAD would resolve in the main checkout; 11-22 bug).
    git -C "$REPO" branch -f "batch/$ID" "$(git rev-parse HEAD)"
    [ "$(git -C "$REPO" rev-list --count origin/main..batch/$ID)" = 1 ] || { log "batch/$ID is not one commit on origin/main; items stay queued"; cd "$J"; sleep 300; continue; }
    cd "$J" || exit 1
    git -C "$REPO" worktree remove --force "$WT" >/dev/null 2>&1
    mkdir -p "$SQ/inflight/$ID"; for f in "${ok[@]}"; do mv "$f" "$SQ/inflight/$ID/"; done
    log "SHIPPING batch/$ID: ${names[*]}${AFTER:+ (pipelined behind $(basename "$AFTER" .out | sed 's/^ship-//'))}"
    OUT=$L/ship-$ID.out; rm -f "$OUT"
    # NEXUS_SHIP_ITEMS: shipq3.sh reuses a harness PASS for the exact shipped tree (PO 2026-11-25 option A).
    NEXUS_SHIP_AFTER="$AFTER" NEXUS_SHIP_ITEMS="$SQ/inflight/$ID" nohup bash "$OPS_DIR/shipq3.sh" "$OUT" "" "batch/$ID" "$TITLE" "Auto ship by ship-runner. Lanes: ${names[*]}" "ship-$ID" >/dev/null 2>&1 &
    sleep 60
  done
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then ship_main "$@"; fi
