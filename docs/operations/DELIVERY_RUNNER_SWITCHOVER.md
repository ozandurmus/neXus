# Delivery runner switchover: launchd runs the versioned runners from Git

Status: OPERATIONS RUNBOOK (2026-11-25). Authority: PO decision record 2026-11-24
(`docs/design/PO_DECISION_RECORD_2026_11_24_DELIVERY_ORCHESTRATION.md`, D2, D3, D5). The launchd change itself is done by
the PO session; no lane touches launchd.

## What changes

Until now the launchd jobs `com.securityexpert.overwatch.lane-runner` and `com.securityexpert.overwatch.ship-runner`
executed the unversioned copies in `~/Codo/ops-scripts/`. The versioned copy in `tools/delivery/ops/` now carries every
live behaviour of 2026-11-25 plus the review fixes of 2026-11-24:

| Live behaviour merged | Where |
|---|---|
| P1 planner (D2): with `lane-queue/NO_REFILL` the only refill is `refill-p1` with "exactly 3 lane briefs", queued when no brief waits and fewer than 3 lanes run, or at 08:xx/16:xx when fewer than 2 wait and no refill was queued in 2 h | `lane-runner.sh` `planner_due`, `maybe_refill` |
| Harness gate (D3): an exit-0 lane reaches the ship queue only through `harness-gate.sh` (typed exit -> ship entry with `harness=`/`harness_report=`; one repair turn with `repair_of=`; held on a second failure; `--keep-going`; one run at a time; `--integration hosta` when the lane's `checks.yaml` uses `hosta_test_run.py`) | `harness-gate.sh`, `lane-runner.sh` `finish_lane` |
| Second chance: a repair lane that exits 3 (nothing changed) re-runs the gate once on the original lane | `lane-runner.sh` `second_chance` |
| Event waiter with the IDLE event (no lane and no brief for 10 min) | `ayyas-waiter.sh` |
| Ship pretest reuses a harness PASS for the exact shipped tree (PO 2026-11-25 option A); the ship out file says `PRETEST reused harness report` or `PRETEST ran` | `shipq3.sh`, `pretest_reuse.py`, `ship-runner.sh` (`NEXUS_SHIP_ITEMS`) |

Differences from the live copy that are deliberate (repo fixes, kept): distinct lane outcomes and ALERT lines
(`lane-runner.sh`), no state-sync lanes (`record_fold_hook`, D5), a first review BLOCK goes back to the author
(`b-review.sh`), only allowlisted flaky tests are re-run and a vitest crash fails closed (`jt.sh`, `main-green.sh`),
`status.sh`/`watch-check.sh` list work that left the pipeline, the gate's ship entry keeps `review_returned=1` and an
unexpected harness exit still gets the tier B review, and the waiter counts `argon_task_neon.py` lanes (the live pattern
missed them).

Paths: the scripts take every path from `ops_lib.sh`. Defaults match the live layout, so the plists need no new
environment: `NEXUS_OPS_STATE=~/Codo/ops-scripts` (queues, logs, counters, templates and the unversioned engine wrappers
`claude_task.sh`, `qwen_task.sh`, `argon_task_neon.py`, `rollback-now.sh`, `lane-common-rules.md`, `refill-*.template`),
`NEXUS_OPS_CODO=~/Codo` (lane worktrees), `NEXUS_OPS_REPO=~/Codo/nexus-ship` (the checkout the runners fetch into).
`NEXUS_OPS_DIR` defaults to the directory of the running script, so the runner checkout's own helpers are used.
Queues, logs, the harness lock (`~/Codo/ops-scripts/.harness-lock`) and the counters stay where they are: lanes and
ship entries made by the live runners are picked up unchanged (entries without `harness=` simply run the pretest).

## 1. Create the runner checkout (once)

A dedicated detached worktree of the ship checkout at `origin/main`, never edited by hand:

```bash
git -C ~/Codo/nexus-ship fetch -q origin main
git -C ~/Codo/nexus-ship worktree add --detach ~/Codo/nexus-ops-runner origin/main
chmod -R a-w ~/Codo/nexus-ops-runner/tools/delivery       # read-only: changes come only from main
bash -n ~/Codo/nexus-ops-runner/tools/delivery/ops/lane-runner.sh && bash -n ~/Codo/nexus-ops-runner/tools/delivery/ops/ship-runner.sh
```

Check before switching: the unversioned helpers listed above exist in `~/Codo/ops-scripts/`, and `python3 -c 'import yaml'`
works (the harness needs PyYAML).

## 2. Switch the plists (PO session)

Best done when `bash ~/Codo/nexus-ops-runner/tools/delivery/ops/status.sh` shows `inflight 0` and no harness gate is
running (`ls ~/Codo/ops-scripts/.harness-lock` fails). Running lanes and a running `shipq3.sh` survive the reload
(`AbandonProcessGroup`), and the new runners finish them from the shared queues, so an idle moment is safer, not required.

```bash
LA=~/Library/LaunchAgents; R=$HOME/Codo/nexus-ops-runner/tools/delivery/ops; D=$(date +%Y%m%d)
for job in lane-runner ship-runner; do
  P=$LA/com.securityexpert.overwatch.$job.plist
  cp "$P" "$P.bak-$D-switchover"                                            # rollback copy
  /usr/libexec/PlistBuddy -c "Set :ProgramArguments:1 $R/$job.sh" "$P"      # launchd needs an absolute path
  plutil -lint "$P"
done
U=gui/$(id -u)
for job in lane-runner ship-runner; do
  launchctl bootout "$U/com.securityexpert.overwatch.$job"; sleep 2
  launchctl bootstrap "$U" "$LA/com.securityexpert.overwatch.$job.plist"
done
```

The exact plist change, per job (everything else, including `ProgramArguments:2` = `3`, `StandardOutPath` and
`StandardErrorPath` under `~/Codo/ops-scripts/logs/`, stays as it is):

```xml
<key>ProgramArguments</key>
<array>
  <string>/bin/bash</string>
  <string>ABSOLUTE_HOME/Codo/nexus-ops-runner/tools/delivery/ops/lane-runner.sh</string>   <!-- was ABSOLUTE_HOME/Codo/ops-scripts/lane-runner.sh -->
  <string>3</string>
</array>
```

(`ship-runner.sh` likewise. `ABSOLUTE_HOME` is the account's home directory written out; launchd expands neither `~`
nor `$HOME`, which is why the commands above use PlistBuddy with `$HOME`.)

Optional, same pattern: `com.securityexpert.overwatch.watch-check` (`ProgramArguments:1` -> `$R/watch-check.sh`) and
`com.securityexpert.overwatch.main-green` (`$R/main-green.sh`). The supervising session's waiter becomes
`bash ~/Codo/nexus-ops-runner/tools/delivery/ops/ayyas-waiter.sh`.

Verify:

```bash
launchctl print gui/$(id -u)/com.securityexpert.overwatch.lane-runner | grep -A4 'arguments = {'
ps -axo command | grep -E '(lane|ship)-runner\.sh' | grep -v grep     # both show the nexus-ops-runner path
tail -5 ~/Codo/ops-scripts/logs/ship-runner.log                       # "ship-runner started (batch 3)"
```

After the first exit-0 lane: `lane-runner.log` shows `harness gate started for <lane>` and then `HARNESS PASS|FAIL|...`;
after the next ship the `ship-*.out` file carries a `PRETEST reused harness report:` or `PRETEST ran:` line.

## 3. Update the runner checkout after each ship

Only needed when the ship changed `tools/delivery/ops/`, `tools/delivery/lane_harness.py` or `tools/delivery/checks.yaml`:

```bash
O=~/Codo/nexus-ops-runner
git -C ~/Codo/nexus-ship fetch -q origin main
if ! git -C "$O" diff --quiet HEAD origin/main -- tools/delivery/ops tools/delivery/lane_harness.py tools/delivery/checks.yaml; then
  test -z "$(git -C "$O" status --porcelain)" || { echo "runner checkout was edited by hand; stop and look"; exit 1; }
  chmod -R u+w "$O/tools/delivery"
  git -C "$O" checkout -q --detach origin/main
  chmod -R a-w "$O/tools/delivery"
  U=gui/$(id -u)
  launchctl kickstart -k "$U/com.securityexpert.overwatch.ship-runner"
  launchctl kickstart -k "$U/com.securityexpert.overwatch.lane-runner"
fi
```

Scripts started per job (`harness-gate.sh`, `shipq3.sh`, `b-review.sh`, `lanename.sh`, `counter.sh`, `jt.sh`,
`pretest_reuse.py`) use the new version on their next start; the two runner loops load their code once, hence the
`kickstart -k`. Git replaces a changed file with a new one, so a `shipq3.sh` already running keeps reading its old copy.
`kickstart -k` does not re-read the plist; it is enough here because the plist did not change.

## 4. Rollback

Restore the backups and reload; the live copies in `~/Codo/ops-scripts/` are untouched by this switchover:

```bash
LA=~/Library/LaunchAgents; U=gui/$(id -u)
for job in lane-runner ship-runner; do
  P=$LA/com.securityexpert.overwatch.$job.plist
  cp "$(ls -t "$P".bak-*-switchover | head -1)" "$P"
  launchctl bootout "$U/com.securityexpert.overwatch.$job"; sleep 2
  launchctl bootstrap "$U" "$P"
done
```

Queue state written by the versioned runners stays readable by the live ones (same files, same fields; the live runner
ignores `done/<lane>.outcome`, and ship entries with `harness=` lines ship as before). Rolling back also brings back the
live behaviours listed as differences above (state-sync lanes, review BLOCK straight to held, the live pretest).

## Tests

`tools/tests/test_delivery_ops_harness_gate.py` (planner condition, the gate's exit-code mapping with a stub
`lane_harness.py`, repair turn and second chance, the waiter's IDLE rule, pretest reuse) and the existing
`tools/tests/test_delivery_ops_*.py`.
