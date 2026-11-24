#!/bin/bash
# Usage: shipq.sh <out-file> <wait-for-out-or-""> <branch> <title> <notes> <logname> [extra ship args...]
# Waits for the previous chain, avoids job windows, unit-tests the rebased branch, then ships it.
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
S=$OPS_STATE/logs; J=$OPS_STATE; REPO=$OPS_REPO
OUT=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); WAIT=$2; BR=$3; TITLE=$4; NOTES=$5; LOG=$6; shift 6
# Development mode (PO 2026-11-03): scheduled jobs are suspended, so ships no longer wait for job windows.
# Set NEXUS_SHIP_WINDOWS=1 to restore the 00/06/12/18 holds.
# Deploy hold (PO 2026-10-16 windows; narrowed 2026-11-22 "hızlı deploy şart"): only the MERGE and DEPLOY wait, never
# the preview or the unit tests. No merge in hours 00/02/06/12/18 (collection windows, 02:00 backup and nightly e2e) nor
# after :40 of the hour before one (a deploy takes about 12 minutes). The wait sits in the verdict writer, which gates
# the merge (--await-pretest), so the pretest timeout is raised to cover a whole hold.
# Window trial (PO 2026-11-23 "12:00'de kontrollü deneme"): hours listed in ship-queue/WINDOW_TRIAL (e.g. "12") are not
# held inside the hour, and the lead-in ends at :53 instead of :40: the merge then reaches the pod rollout (security
# gate ~5 min + images ~1.5 min) right at the window start, when the collection jobs run (p95 2 minutes).
trial() { grep -qw "$1" $OPS_STATE/ship-queue/WINDOW_TRIAL 2>/dev/null; }
hold() { [ "${NEXUS_SHIP_WINDOWS:-1}" = 1 ] || return 0; while :; do h=$(TZ=Europe/Istanbul date +%H); m=$(TZ=Europe/Istanbul date +%M); hn=$(( (10#$h + 1) % 24 ))
  trial $((10#$h)) && break; trial $hn && [ $((10#$m)) -ge 53 ] && break
  case $h in 00|02|06|12|18) sleep 60; continue;; esac
  case $hn in 0|2|6|12|18) [ $((10#$m)) -ge 40 ] && { sleep 60; continue; };; esac; break; done; }
export NEXUS_PRETEST_TIMEOUT_S=9000
[ -n "$WAIT" ] && until grep -qE "^DONE|^FAILED" "$WAIT" 2>/dev/null; do sleep 60; done
[ -n "$WAIT" ] && grep -q "^FAILED" "$WAIT" && ! grep -q "^SHIPPED" "$WAIT" && { echo "FAILED $BR: previous ship in the chain failed; stopped" >> $OUT; echo DONE >> $OUT; exit 1; }
# Wait for another ship/deploy, but never silently for hours (2026-11-04: a leftover process held a chain 01:30-06:50).
# Only real processes count: agy/claude prompts that merely mention these names do not (2026-11-15).
busy() { for p in $(pgrep -f "hosta_deploy.sh|module_deploy.py|standalone_orchestrate.py ship|tools/e2e/hosta_e2e.sh"); do ps -o comm= -p $p | grep -qE "agy|claude|[Pp]ython.*argon|node" && continue; ps -o command= -p $p | head -1 | grep -qE "argon_task|agy -p" && continue; return 0; done; return 1; }
# Pipelined (ship-runner, PO 2026-11-22): NEXUS_SHIP_AFTER names the previous ship's out file. That ship has already
# merged; this one runs its preview and unit tests during that ship's deploy, and its verdict file (which gates the
# merge) is written only after that ship ends. So no busy wait here.
AFTER=${NEXUS_SHIP_AFTER:-}
waited=0; while [ -z "$AFTER" ] && busy; do
  sleep 30; waited=$((waited + 30))
  if [ $waited -ge 900 ]; then echo "FAILED $BR: another ship/deploy still running after 15 min: $(pgrep -fl 'hosta_deploy.sh|module_deploy.py|standalone_orchestrate.py ship|tools/e2e/hosta_e2e.sh' | cut -c1-160 | tr '\n' ' ')" >> $OUT; echo DONE >> $OUT; exit 1; fi
done
# Fetch main into a ref of this ship only (--refmap= stops the opportunistic origin/main update). Two ships pipelined
# and main-green fetching into origin/main at once made one fetch fail "cannot lock ref" after a merge (375, 2026-11-24).
M=refs/shipq/$LOG
fetchm() { local i; for i in 1 2 3; do git -C "$REPO" fetch -q --refmap= origin "+refs/heads/main:$M" && return 0; sleep 5; done; return 1; }
W=$S/pretest-wt-$LOG; git -C "$REPO" worktree remove --force $W >/dev/null 2>&1
fetchm && git -C "$REPO" worktree add -q --detach $W "$BR" && git -C $W rebase -q $M >/dev/null 2>&1 \
  || { echo "FAILED $BR: does not rebase on main" >> $OUT; echo DONE >> $OUT; exit 1; }
# New migrations must be above the highest version already on main (Flyway refuses an older one; b13 2026-11-16).
mx=$(git -C "$REPO" ls-tree -r --name-only $M | grep -oE 'db/migration/V[0-9]+__' | grep -oE '[0-9]+' | sort -n | tail -1)
low=$(git -C $W diff --name-only --diff-filter=A $M HEAD | grep -oE 'db/migration/V[0-9]+__' | grep -oE '[0-9]+' | awk -v m="$mx" '$1<=m' | tr '\n' ' ')
[ -z "$low" ] || { echo "FAILED $BR: new migration version(s) $low not above main's V$mx" >> $OUT; echo DONE >> $OUT; exit 1; }
# Successor index first (cheap, and it may move the branch); the Java/vitest run then goes to the background and the
# ship waits for its verdict only just before the merge (--await-pretest), so it overlaps the 8-minute preview.
if ! (cd $W && python3 tools/delivery/successor_index.py --check >/dev/null 2>&1); then
  (cd $W && python3 tools/delivery/successor_index.py render >/dev/null && git add docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md \
    && git -c user.name="$(git config user.name)" -c user.email="$(git config user.email)" commit -q -m "Re-render the decision-record successor index on the current main" -m "Co-Authored-By: Claude Opus 5.5 <$OPS_NOREPLY>" \
    && git -C "$REPO" branch -f "$BR" "$(git -C $W rev-parse HEAD)") && echo "INDEX re-rendered for $BR" >> $OUT
fi
VERDICT=$S/pretest-$LOG.verdict; rm -f $VERDICT
# Scoped tests for a narrow diff (module + dependents + architecture-tests); anything unusual selects the full suite.
SCOPE=$(cd $W && python3 tools/delivery/pretest_scope.py --repo $W --base $M --branch HEAD 2>/dev/null)
[ "${NEXUS_PRETEST_FULL:-0}" = 1 ] && SCOPE='{"scope": "full"}'
( bash "$OPS_DIR/jt.sh" $W "$SCOPE" "$M" > $S/pretest-$LOG.out 2>&1
  if grep -q "failures 0" $S/pretest-$LOG.out && ! grep -qE "FAIL COMPILE|tsc-FAIL|tools-FAIL|vitest-FAIL" $S/pretest-$LOG.out; then
    [ -n "$AFTER" ] && until grep -q "^DONE" "$AFTER" 2>/dev/null; do sleep 15; done
    # The ship ahead was rolled back: main still holds its change until the queued revert ships, so do not merge on it.
    if [ -n "$AFTER" ] && grep -q "^ROLLED BACK" "$AFTER"; then echo "FAIL previous ship rolled back; not merged on top of it" > $VERDICT
    else hold; echo PASS > $VERDICT; fi
  else echo "FAIL unit tests ($(grep -E 'java tests|FAIL' $S/pretest-$LOG.out | head -4 | tr '\n' ' '))" > $VERDICT; fi ) &
PRETEST_PID=$!
cd "$REPO" && git checkout -q main && fetchm && git merge -q --ff-only $M
# A branch pushed by an earlier attempt makes the ship's own push non-fast-forward (pre-push hook); start clean.
gh pr list --head "$BR" --state open --json number -q '.[].number' 2>/dev/null | xargs -I{} gh pr close {} --comment "Superseded by a re-ship of the same branch." >/dev/null 2>&1
git push -q origin --delete "$BR" >/dev/null 2>&1
NEXUS_MODULE_E2E=${NEXUS_MODULE_E2E:-edges} python3 scripts/standalone_orchestrate.py ship --branch "$BR" --title "$TITLE" --notes "$NOTES" --await-pretest $VERDICT "$@" > $S/$LOG.log 2>&1
wait $PRETEST_PID; git -C "$REPO" worktree remove --force $W >/dev/null 2>&1
# Automatic rollback (PO 2026-11-22: "sorun varsa 5 dk da geri alabilmek"): the change reached production (merged and
# modules replaced) but the deploy failed or the production e2e did not pass -> restore the previous release, queue a
# revert of the merged commit as the next ship, and resume the runner. A failure before any module changed rolls nothing.
if grep -q '"merged": "' $S/$LOG.log && ! grep -q '"e2e": "pass"' $S/$LOG.log && ! grep -q '"deployed": false' $S/$LOG.log \
   && grep -qE '"e2e": "FAIL"|"module": "|deploy failed' $S/$LOG.log; then
  why=$(grep -E '"e2e": "FAIL"|deploy failed' $S/$LOG.log | tail -1 | cut -c1-160)
  if bash "$J/rollback-now.sh" --auto "$BR: $why" >> $S/$LOG.log 2>&1; then
    echo "ROLLED BACK $BR $(TZ=Europe/Istanbul date +%H:%M): $why" >> $OUT
    sha=$(grep -oE '"merged": "[0-9a-f]+"' $S/$LOG.log | tail -1 | grep -oE '[0-9a-f]{7,}')
    RW=$S/revert-wt-$LOG; fetchm && git -C "$REPO" worktree add -q --detach $RW $M \
      && (cd $RW && export GIT_AUTHOR_NAME="$(git -C "$REPO" config user.name)" GIT_AUTHOR_EMAIL="$(git -C "$REPO" config user.email)" \
          && export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME" GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL" \
          && p=$(git rev-list --parents -n1 "$sha" | wc -w); m=""; [ "$p" -gt 2 ] && m="-m 1"
          git revert --no-edit $m "$sha" >/dev/null \
          && git commit -q --amend -m "Revert $BR: production e2e or deploy failed and it was rolled back" -m "$why" -m "Co-Authored-By: Claude Opus 5.5 <$OPS_NOREPLY>") \
      && git -C "$REPO" branch -f "revert/$LOG" "$(git -C $RW rev-parse HEAD)" \
      && printf 'branch=revert/%s\nname=fix-revert-%s\nallow_paths=1\nsingle=1\ndeferred=1\n' "$LOG" "$LOG" > $OPS_STATE/ship-queue/000-fix-revert-$LOG.ship \
      && rm -f $OPS_STATE/ship-queue/PAUSED && echo "REVERT queued for $sha" >> $OUT \
      || echo "REVERT could not be prepared for $sha; runner stays PAUSED" >> $OUT
    git -C "$REPO" worktree remove --force $RW >/dev/null 2>&1
  else
    echo "ROLLBACK FAILED $BR: runner stays PAUSED; see logs/rollback-*.log" >> $OUT
  fi
fi
{ grep -q '"e2e": "pass"' $S/$LOG.log || { grep -q '"fast_path": "docs_only", "deployed": false' $S/$LOG.log && grep -q '"merged": "' $S/$LOG.log; }; } && echo "SHIPPED $BR $(TZ=Europe/Istanbul date +%H:%M)" >> $OUT || echo "FAILED $BR: $(grep -vE '^TIMING' $S/$LOG.log | tail -2 | cut -c1-240)" >> $OUT
echo DONE >> $OUT
