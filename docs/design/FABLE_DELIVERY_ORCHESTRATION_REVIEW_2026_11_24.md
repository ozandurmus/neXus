<!-- Fable consultation 2026-11-24T19:43:36Z, model claude-fable-5-2; unaltered output -->

# Delivery orchestration review: Overwatch build, ship and merge (2026-11-24)

Reviewer seat: independent, read-only. Inputs: the brief, the scripts in `docs/ops-snapshot-1124/`, `tools/delivery/pretest_scope.py`, `.github/workflows/validation.yml`, the 2026-11-23 council review, and public sources.

Limits: I did not see the runner logs, so every count comes from the brief. I did not read the internals of `scripts/standalone_orchestrate.py`; statements about it come from the brief and its wrapper scripts.

## Summary

1. The basic shape is sound: isolated worktrees, one serialized ship path, preview, auto-rollback. The proportions are wrong.
2. Root cause 1: a lane is "done" when the agent exits 0 with a commit. Nothing verifies its work before it enters the ship queue.
3. Root cause 2: the Mac pretest is not the same check set as GitHub CI, so some failures can only show up after a 20-minute wait.
4. Root cause 3: the runners decide state by grepping log text and lane names. This produces false failures, lost work and duplicates.
5. Root cause 4: work is generated faster than it can be integrated, with no ownership map and no duplicate ledger. Rework fills the gap.
6. Root cause 5: every exception ends at the PO session. Review BLOCKs, held paths, failed fixes and record syncing all queue for one agent.
7. Fix order: measure, make ship verdicts factual, add a runner-enforced lane harness, close the CI parity gap, then cap work in progress.
8. Replace the 13 state-sync lanes per day with per-lane change fragments and a deterministic fold. Keep one prose sync per day.
9. Keep Git authority and agents on the Mac. Do not put a CI runner on the production host. A non-production build box is the real fix.
10. Two-week targets: ship failure rate from 25% to under 10%, rework share from 35% to under 15%, and CI-only failures to zero.

## 1. Diagnosis

Baseline on 2026-11-24, from the brief's counts:

| Measure | Value |
|---|---|
| Ship failure rate | 18 of 72 attempts, 25% (40% on 11-23, 18% on 11-22) |
| Rework share of lane starts (fix + port) | 25 of 72, 35% |
| Record-sync share | 13 of 72, 18% |
| Lanes that write briefs, not product (refill) | 10 of 72, 14% |
| New product work | under 20% |

The ship pipeline is the constraint. 54 shipped batches at about 20 minutes each is about 18 hours of pipeline time. 18 failed batches add several more hours. Each failure costs a slot that a good change could have used.

### Root cause 1: "done" means "exit 0 and a commit"

- `claude_task.sh` reports success when the agent returns 0 and HEAD moved. `lane-runner.sh` then writes the ship entry.
- All test duties are prose in `lane-common-rules.md`. No script checks that the agent ran them or that they passed.
- The default worker is the smallest model at low effort (`claude_task.sh` defaults: haiku, low).
- The same file tells lanes to wait up to 20 minutes if another Gradle or vitest run is active. With six lanes on one Mac, skipping tests is the easy path.
- Effect: the ship pipeline is the first real test of most lanes. The 3 unit-test failures, the preview build failure and much of the 20 fix lanes follow from this.

### Root cause 2: the pretest and CI are different check sets

- `jt.sh` excludes `:integration-tests:test`. The workflow runs the PostgreSQL 16 suite for any non-frontend change under `ui2/`. A failure there is invisible locally.
- `jt.sh` runs three governance test files. The workflow's `validate` job also runs the privacy gate and the live tooling regression.
- The docs fast path skipped a governance test that CI then ran.
- Effect: 5 "CI check failed" and 2 "CI pending timeout" on 11-24. These are the most expensive failures, because they arrive last.
- Fix lanes cannot reproduce these failures locally. They guess from a 400-character log excerpt, and a second failure dead-ends ("no second fix").

### Root cause 3: state is inferred from text, not from facts

- `ship-runner.sh` classifies a failure by a regex over the last two matching log lines. The script's own comments record two misfires: a test title containing "SSH" paused the runner, and a process-timing test split a batch.
- The pattern "pull request not merged" counts as infrastructure. Two merges succeeded on GitHub while the API call errored. The runner called them failed and re-queued the items. They came back later as "nothing to ship" (5 cases).
- Behaviour is encoded in lane names. The names changed on 11-24. By my reading, the port/fix priority filter `/[0-9]+-(port|fix)-` no longer matches a name of the form `<date>-<time>-G..-D..-fix-...`. Ports and fixes would then have lost their priority silently. This needs a check against the log.
- Work can vanish quietly in at least six places: `failed/` after "no second fix", `failed/` after "no third port", `held/`, `lane-queue/hold/`, exit 3 moved to `done/`, and `FAILED_WORKTREE`.
- The batch is squashed into one commit. Blame is a filename heuristic. When it fails, the batch is retried whole or split into singles, at about 20 minutes per attempt.

### Root cause 4: generation outruns integration, with no ownership

- Six lanes run in parallel, and refill lanes write more briefs when the queue runs low.
- Duplicate avoidance is an instruction to an LLM: "read the titles in the queue". There is no registry of who works on what.
- Briefs "should touch different files". Nothing enforces it. The council's "declare touched files" item was not built; the `.meta` file has no path field.
- The two refill templates contradict the lane rules. `refill-p1.template` allows briefs that touch guarded paths. `lane-common-rules.md` forbids them. So held work is produced by design.
- Conflicts fell from 32 to 5 after the fresh-base change. The Estate stop happened at the same time, so the cause is not proven.

### Root cause 5: one judgement agent absorbs every exception

- A tier B BLOCK moves the item to `held/`. The author lane never gets the review back.
- Tier C paths, failed fixes, stuck reviews and pauses all become PO-session work.
- The delivery scripts themselves are patched live. Their comments are a log of about 40 incidents in five days. I saw no tests for them.

## 2. Target orchestration model

Keep the shape. Change the proportions and add two missing stages.

| Stage | Today | Target |
|---|---|---|
| Plan | 10 refill lanes per day, each inventing briefs | One planner run, twice a day. Output: 8 to 15 slices, each with area, declared paths, backlog id, acceptance commands |
| Work | 6 lanes, smallest model, unverified | 3 to 4 lanes, one per area, stronger model for product work, each must pass the harness |
| Gate | None before the ship queue | Deterministic harness run by the runner, then model review with one repair turn |
| Integrate | Batch of 3, squash, log-grep verdict | Same runner as a real merge queue: factual verdicts, ordered by declared overlap, bisect on failure |
| Exceptions | All to the PO session | Only tier C and decisions, in one attention list with ages |

Reasoning:

- **Fewer lanes.** The pipeline ships about three batches per hour at best. More than three or four verified lanes cannot be absorbed. Extra lanes only add conflicts and CPU contention.
- **Larger means complete, not bigger.** A lane delivers one vertical slice with tests and its record fragment. Keep production diffs under about 600 lines. The current review cap of 4,000 lines is too high for a useful review.
- **Per-area ownership.** One active lane per area. Shared hot files are locked or split, as the council proposed.
- **Raise the lane cap by one only after three days under 10% ship failure.**
- **Feature flags.** Use them only for P1 features that span several lanes, so partial work can merge dark. They are not needed for single-lane work.
- **Deploy trains are a PO decision.** Merging after pretest and preview, then deploying in hourly trains, would cut pipeline time per change. It makes rollback coarser. The PO asked for fast deploys on 11-22, so I only flag it.
- **A native GitHub merge queue is probably not available** for this repository type and plan. I could not confirm this from the documentation page. The local ship runner is the merge queue. Fix it rather than replace it.

## 3. Harness design

Yes, it can be built. Most parts already exist in `jt.sh`, `shipq3.sh` and the workflow. The change is where they run and who enforces them.

**Name and place:** `tools/delivery/lane_verify.py`, plus one shared check manifest `tools/delivery/checks.yaml`. Both are guarded paths, so they merge through the PO session.

**Who runs it:** the lane runner, after the agent exits and before a ship entry is written. The agent may also run it. The runner's run is the only one that counts.

**Inputs:** worktree path, base ref, the lane `.meta` (new fields `area=`, `paths=`, `backlog_id=`, `accept=`), and the current ship queue.

**Steps, cheapest first:**

1. Preconditions: a commit exists and the tree is clean.
2. Rebase on fresh main. The author lane resolves a conflict while it still has context.
3. Path check: changed files must fall inside the declared `paths=`. Guarded paths are classified as tier C now, not at batch build time.
4. Derived files: re-render the successor index, the build-history index, the route decision table and the inventory rows. Auto-commit the result instead of failing.
5. Cheap static checks: whitespace, the privacy gate, secret scan, the forbidden-word rule, the migration-number rule.
6. Test integrity: flag deleted tests, removed assertions and new skips for the reviewer.
7. Tests from the manifest: the governance and tooling tests that `validate` runs, scoped Java via `pretest_scope.py`, architecture tests, tsc and vitest. Add the PostgreSQL suite when the workflow's own path rule would run it.
8. Acceptance: run each `accept=` command from the brief. Changed production code without a changed test is flagged.
9. Queue pre-check: a trial three-way merge against each queued and in-flight branch. An overlap sets `after=<lane>` on the ship entry.
10. Write `verify.json`: result, checks run, durations, tree id.

**Exit codes:**

| Code | Meaning | Runner action |
|---|---|---|
| 0 | Pass | Write the ship entry, start review if tier B |
| 10 | No commit or empty diff | Record as no-change, visible in the attention list |
| 20 | Rebase conflict | One repair turn to the same agent |
| 21 | Changed paths outside the declaration | One repair turn, then attention |
| 22 | A check failed | Up to two repair turns with the failing output |
| 23 | Tier C path | Straight to held, no pipeline time spent |
| 30 | Tool or infrastructure error | Retry once, never blame the lane |

**Supporting changes:**

- A two-slot test semaphore on the Mac, replacing the "wait up to 20 minutes" prose rule.
- A shared Gradle build cache, so scoped tests are cheap.
- `./gradlew --stop` before a worktree is removed, to end the daemon that recreates the directory.
- The ship pretest reads the same manifest. A check is added once and runs in three places.

**Cost and risk:** M. It adds about 3 to 8 minutes per lane. The risk is Mac load, which the lower lane cap offsets. The repair-turn mechanism depends on CLI resume behaviour that I did not verify.

## 4. Check placement

| Check | Today | Decision |
|---|---|---|
| Lane self-tests | Prose in the brief | Replace with `lane_verify`, enforced by the runner |
| Guarded-path check | At batch build | Move to the harness (exit 23) |
| Migration number above main | `shipq3.sh` | Move to the harness, keep at batch level |
| Derived-file render | `shipq3.sh`, batch builder | Harness auto-fixes, batch builder keeps its re-render |
| Tier B review | After lane exit, BLOCK goes to held | Keep, after the harness passes. BLOCK returns to the lane once. Second BLOCK goes to held |
| Governance and tooling tests | Three files locally, more in CI | One manifest. Docs-only changes still run them |
| Privacy gate | CI `validate` only | Add to harness and pretest |
| Scoped Java, architecture tests | Pretest | Keep, also in harness |
| vitest, tsc | Pretest, main-green | Keep, also in harness for frontend changes |
| PostgreSQL integration suite | GitHub only | Add to the pretest, in parallel with the preview |
| Flaky re-run | Any failed test, once | Allowlist only, never security-property tests (council item 9, not adopted) |
| Preview e2e | Every non-docs batch | Keep. Add a fast path for test-only and record-only diffs |
| GitHub required checks | Wait up to 45 minutes | Keep as confirmation. On a missing check, re-run that job once before failing |
| Dependency scan | Every PR | Path-filter to lockfile changes, plus nightly. PO decision, since it changes gating |
| CodeQL | Separate workflow | Do not block the merge on it if it does today. Run on main and nightly. PO decision |
| Full regression | Moving to nightly | Agree. A red nightly sets `MAIN_RED` and queues one fix lane |
| Merge call | Error means failed | Ask GitHub for the PR state before any verdict |
| Security gate, production e2e, auto-rollback | In deploy | Keep unchanged |
| main-green | Repeats pretest checks on main | Keep. Also read the result of the post-merge run on main, to catch test-selection misses the same day |

**Where the integration suite can run:** a throwaway database container on the Mac, or a Job in the build namespace on HOST-A with a resource quota. I do not know whether the Mac has a container runtime.

**The rule behind the table:** every required CI check has a local twin in the manifest. A CI-only failure is then a defect in the manifest, and it gets fixed there.

## 5. State and bookkeeping

Syncing after every two batches is not needed. The records are a projection of what merged, and a projection should be computed, not written by a model.

- Each lane writes one small file, `project/changes/<lane>.json`: backlog id, status change, one-line summary. One file per lane means no conflicts. The harness checks that it exists.
- The ship runner folds the fragments once a day through `tools/delivery/project_queue.py`, as GOV.ORCH.5 already requires. That is one deterministic commit on the docs fast path.
- One model lane per day writes the prose parts: the hot section of `CURRENT_STATE.md` and `AI_HANDOVER.md`.
- **Removes:** about 12 of 13 state-sync lanes per day, their ship slots, and the build-history size failures (six batches on 11-23).
- **Cost and risk:** M. The governance tests assert agreement between the records, so this changes a governance contract and needs PO approval. Records lag by up to a day.
- **Measure:** state-sync lanes per day, and governance-test failures in ships.

## 6. The Mac as the only runner

| Item | Place | Reason |
|---|---|---|
| Git push, PR, merge credentials | Mac | PO controls Git |
| Lane agents, briefs, queues | Mac | Vendor CLI sessions and quotas live there |
| Lane harness | Mac | Must run before the ship queue |
| Deterministic code checks (unit, integration, full regression) | GitHub Actions | Code is already there; no bank data is involved |
| Preview build, preview e2e, deploy, production e2e | HOST-A | Already there |
| Integration Job | HOST-A build namespace, optional | Only with a resource quota |
| GitHub self-hosted runner | Not on HOST-A | GitHub's guidance warns against running arbitrary workflow code on a cluster shared with production |

Further points:

- A small non-production machine inside the network is the real fix. It would host the ship runner and the test load. The council proposed the same in item 10. This is a PO and bank decision.
- Until then, add one probe for VPN and host reachability. When it fails, pause without spending retries or counting failures.
- Put the delivery scripts under version control with tests for the pure logic, if they are not already. The priority-filter finding shows why.

## 7. Two-week plan

| Days | Step | Change | Cost | Removes | Target |
|---|---|---|---|---|---|
| 1 | Measure | Runners append one line per transition to `events.jsonl`; `status.sh` reports the five rates | S | Guessing | Daily numbers exist |
| 1 | Reduce load | Lane cap 3, refill stays off | S | Contention, overlap | Mac never above 2 test runs |
| 1-2 | Factual verdicts | Check PR state and tree-on-main before failing; keep lane kind in `.meta`, not in the name; fix the priority filter | S | False merge failures (2), "nothing to ship" (5) | Both at 0 |
| 1-2 | No silent ends | One attention list across `failed/`, `held/`, `hold/`, with ages | S | Lost lanes | 0 items older than 24 h unseen |
| 2-5 | Harness v1 | `lane_verify.py`, `checks.yaml`, runner enforcement, repair turns | M | Unit-test and build failures, many fix lanes | Lane first-time-right at 70% |
| 3-5 | CI parity | Integration suite and privacy gate in pretest; governance tests on the docs path | M | CI check failures (5) | CI-only failures at most 1 per day |
| 5-6 | Review loop | BLOCK returns to the lane once | S | Held items (4 to 7 per day) | Held from review at most 1 per day |
| 6-8 | Ownership | `areas.yaml`, `paths=` in `.meta`, runner refuses overlap, planner replaces refill | M | Duplicate lanes, ports | Conflicts at most 2 per day, duplicates 0 |
| 8-10 | Records | Change fragments and daily fold | M | 12 sync lanes per day | Sync share under 3% |
| 10-12 | CI tiers | Path-filtered scan, missing-check re-run, flaky allowlist | S | Pending timeouts (2) | Timeouts at 0 |
| 12-14 | Review | Compare with baseline; decide on lane cap, deploy trains, build box | S | — | See below |

End-state targets after 14 days:

| Measure | Baseline | Target |
|---|---|---|
| Ship failure rate | 25% | under 10% |
| Lane first-time-right (first ship attempt succeeds, no fix, port or hold) | not measured | 80% |
| Rework share of lane starts | 35% | under 15% |
| New product share of lane starts | under 20% | over 50% |
| CI-only failures | 5 per day | 0 |
| PO-session minutes per shipped change | not measured | halved from the day 1 to 3 average |

Every step that touches `tools/delivery/`, `scripts/` or `.github/` is a guarded path and merges through the PO session.

## 8. The 2026-11-23 council review

**What worked:** the vitest gate (it caught a real escape on 11-24), the fresh lane base, Sonnet for port and fix lanes, and the risk tiers as a safety measure.

**What did not work:**

- Tier B review has no return path, so it turned defects into held items for the PO session.
- Tier C plus the P1 refill template produce held work by design.
- "Declare touched files" and "no two lanes on one area" were not built.
- The flaky allowlist was not built. `jt.sh` still re-runs any failed test.

**What it missed:**

- It never asked what "done" means for a lane. That is the largest gap.
- It did not compare the pretest with CI.
- It did not look at how the runners decide state.
- It did not cost the record syncing or the refill lanes.
- It set no measures or limits on work in progress. It reported 37% rework and proposed no target.
- It was mainly a product architecture review. Only one of its four questions was about process.

## 9. What the public sources say

This section reports the sources only. My judgement is in the other sections.

- **Cursor, "Scaling long-running autonomous coding" (2026-01-14).** Flat peer agents with locks failed: twenty agents had the throughput of two or three. Peers avoided hard tasks. Planners and workers with separate roles worked. An integrator role "created more bottlenecks than it solved".
- **Cursor, "Towards self-driving codebases" (2026-02-05).** A root planner owns the scope; workers finish tasks alone and hand off. A central integration gate was a bottleneck. They accepted a small steady error rate with a fix-up pass before release. "Constraints are more effective than instructions."
- **Anthropic, "Harness design for long-running application development" (2026-03-24).** Agents "reliably skew positive when grading their own work". A separate evaluator helps. Generator and evaluator agree a testable contract before coding. Each harness part encodes an assumption about the model and should be re-tested as models improve.
- **Böckeler, "Harness engineering for coding agent users" (2026-04-02).** Guides steer before the agent acts; sensors check after. Deterministic checks are cheap enough for every change; model review is costlier and variable. Fast checks belong before integration, expensive ones after. Recurring problems should change the harness.
- **OpenAI, "Harness engineering" (2026-02-11), search summary only.** A short agent instruction file used as a map, with detail in versioned docs.
- **GitHub merge queue documentation, search summary only.** A queued change is tested together with the changes ahead of it. A failing or timed-out entry is removed and the rest proceed. Checks must run on the queue's own event or the queue stalls.
- **Trunk and Mergify merge queue documentation, search summary only.** Batching saves CI time. On failure, bisect the batch and reuse earlier results. A "pending failure" state avoids bisecting on transient errors. These are vendor claims.
- **DORA, "State of AI-assisted Software Development" (2025), search summary only.** AI raises throughput and also instability. It amplifies what exists. Small batches, strong version control and fast feedback are named as needed capabilities.
- **Meta, "Predictive test selection" (2018), and AWS DevOps Guidance QA.FT.4, search summary only.** Test selection is a pre-merge cost tool. Run the full suite asynchronously as a safety net. AWS advises against excluding security tests by selection.
- **GitHub Actions Runner Controller documentation and Well-Architected guidance, search summary only.** Workflows run arbitrary code, so a cluster shared with production is a security risk. The controller adds no isolation beyond Kubernetes.
- **trunkbaseddevelopment.com, "Feature flags".** Unfinished work can ship dark behind a flag. Flags need tests and a removal date.
- **Practitioner posts on git worktrees for parallel agents (2025-2026).** One task, one branch, one worktree, one agent. Cleanup and file ownership are the common pain points. These are individual posts with little independent evaluation.

## 10. Good practice vs. what we do

| Practice | Source | Our state | Gap | Recommended change |
|---|---|---|---|---|
| An agent's own "done" is not trusted; a separate check decides | Anthropic; Böckeler | Exit 0 plus a commit | No verification before the ship queue | Runner-enforced `lane_verify` |
| Constraints over instructions | Cursor (Feb) | About 15 prose rules per brief | Rules are not enforced | Move each rule into a harness check |
| Cheap deterministic checks before integration | Böckeler | Most checks run at ship time or on GitHub | Late and costly feedback | One manifest, run in the lane |
| Contract agreed before coding | Anthropic | Brief says what "done" means in prose | Not executable | `accept=` commands in `.meta` |
| Planner owns scope; workers do not coordinate | Cursor (both) | Refill lanes invent work; LLM dedupe | Duplicates and overlap | Planner run plus area and path registry |
| Do not route everything through one integrator | Cursor (both) | PO session takes every exception | Single bottleneck | Repair turns to the author lane |
| Queue removes the failing entry, the rest proceed | GitHub; Trunk; Mergify | Squashed batch, filename blame, retry or split | Slow, heuristic isolation | Factual verdicts and bisect |
| Transient failures do not trigger blame | Trunk | Regex on log text | Misclassification | Infrastructure probe and re-run of the missing check |
| Recurring issues change the harness | Böckeler | Incidents patched as comments in live scripts | No tests on the scripts | Version and test the scripts |
| Small batches, fast feedback | DORA | Small changes, but 25% ship failure | Instability from volume | Work-in-progress cap tied to the failure rate |
| Selection before merge, full suite as net | Meta; AWS | Scoped pretest; full run moving to nightly | Nightly red has no automatic consequence | Red nightly sets `MAIN_RED` |
| Security tests are not excluded or retried away | AWS | Every failed test is re-run once | Real failures can pass as flaky | Allowlist only |
| No CI runner on a cluster shared with production | GitHub | Single production host | Tempting shortcut | Use a non-production build box |
| Unfinished multi-step work merges dark | trunkbaseddevelopment.com | No flag convention | Multi-lane features wait or conflict | Flags for multi-lane P1 work only |
| Re-test harness parts as models change | Anthropic | Two-family review on every tier B change | Cost and value not measured | Track BLOCK precision; drop the second reviewer if it adds little |

**One place where I depart from the sources.** Cursor accepted a steady error rate on the shared branch. That fits a research build with a fix-up pass. Here every merge deploys to production, so main must stay green. The part that transfers is narrower: send conflicts and review findings back to the worker, not to a central agent.

## Sources

Read in full:

- "Scaling long-running autonomous coding", Cursor, 2026-01-14, https://cursor.com/blog/scaling-agents
- "Towards self-driving codebases", Cursor, 2026-02-05, https://cursor.com/blog/self-driving-codebases
- "Harness design for long-running application development", Anthropic Engineering, 2026-03-24, https://www.anthropic.com/engineering/harness-design-long-running-apps
- "Harness engineering for coding agent users", Birgitta Böckeler, martinfowler.com, 2026-04-02, https://martinfowler.com/articles/harness-engineering.html
- "Feature flags", trunkbaseddevelopment.com, undated, https://trunkbaseddevelopment.com/feature-flags/

Search summary only, not read in full:

- "Harness engineering: leveraging Codex in an agent-first world", OpenAI, 2026-02-11, https://openai.com/index/harness-engineering/
- "Managing a merge queue", GitHub Docs, undated, https://docs.github.com/en/enterprise-cloud@latest/pull-requests/how-tos/merge-and-close-pull-requests/merging-a-pull-request-with-a-merge-queue
- "Batching", Trunk Merge Queue docs, undated, https://docs.trunk.io/merge-queue/concepts-and-optimizations/batching
- "Pending failure depth", Trunk Merge Queue docs, undated, https://docs.trunk.io/merge-queue/optimizations/pending-failure-depth
- "Merge queue batching", Mergify, undated, https://mergify.com/learn/merge-queue/batching
- "State of AI-assisted Software Development", DORA / Google Cloud, 2025, https://services.google.com/fh/files/misc/2025_state_of_ai_assisted_software_development.pdf
- "Predictive test selection", Meta Engineering, 2018-11-21, https://engineering.fb.com/developer-tools/predictive-test-selection/
- "QA.FT.4 Balance developer feedback and test coverage using advanced test selection", AWS DevOps Guidance, undated, https://docs.aws.amazon.com/wellarchitected/latest/devops-guidance/qa.ft.4-balance-developer-feedback-and-test-coverage-using-advanced-test-selection.html
- "Deploying runner scale sets with Actions Runner Controller", GitHub Docs, undated, https://docs.github.com/en/actions/how-tos/manage-runners/use-actions-runner-controller/deploy-runner-scale-sets
- "Deploying Actions Runner Controller", GitHub Well-Architected, undated, https://wellarchitected.github.com/library/architecture/recommendations/deploying-actions-runner-controller/
- "The Code Agent Orchestra", Addy Osmani, 2026, https://addyosmani.com/blog/code-agent-orchestra/
- "Git Worktrees for AI Development", KDnuggets, 2026, https://www.kdnuggets.com/git-worktrees-for-ai-development

All search queries were generic. No product, organisation, person, host or device name was sent.

## Reviewer notes

- **Language.** A session hook asked for Turkish chat replies. This reply is the review artifact itself, so it is in English under the engineering-output language law. A Turkish summary can be a separate artifact.
- **Role file.** `roles/REVIEWER.md` forbids retrieving evidence beyond what the brief supplies. Section 4 of the brief orders web research, so I followed the brief. I report the conflict instead of reconciling it silently.
- **Instructions received.** The brief as supplied, titled "Brief for an independent reviewer: how Overwatch is built, shipped and merged -- and how to make it effective" (sections 1 to 4). Then the closing line: "You are Fable, acting as the neXus product and systems architect. The repository is your working directory; read what you need. Write the whole design document as your reply text; do not create or edit files." I do not reproduce the brief in full here because of its length.
- **Files.** I created and edited none.
