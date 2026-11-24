# Brief for Neon (high): how Overwatch is built, shipped and merged -- and how to make it effective

You are an independent reviewer. Read-only: do not edit any file except your own output. The Product Owner (PO) says,
translated: "We cannot orchestrate: every job keeps failing and getting delayed. Explain our working logic from A to
Z, then propose improvements. How do orchestration, build, ship and merge work? Can a harness be built? How do we
become effective? We are not effective now. I keep seeing problems, errors, retries."

Be concrete and critical. Rank by effect on throughput and first-time-right rate, not by elegance. For every proposal
say: what changes (file/script), cost (S/M/L), risk, what it removes from the failure list below, and how we measure it.

## 1. The system as it runs today (A to Z)

People and agents:
- One human PO. One "PO session" (a Claude Opus agent) that orchestrates: writes briefs, reviews held work, fixes
  failures by hand, keeps the queues moving, answers the PO. It is the only agent with judgement over the whole flow.
- Worker agents ("lanes"): headless Claude (`claude -p`, Sonnet/Haiku/Opus via `claude_task.sh`) and Antigravity
  (`agy -p`, Gemini models carbon/neon/hydrogen, via `scripts/argon_task.py`). Each lane gets a brief file and a fresh
  git worktree (`~/Codo/nexus-q-<lane>`) on its own branch, works alone, commits, never pushes.
- Everything runs on one Mac (the PO's laptop): lanes, tests, builds of the preview, the ship runner. Production and
  the preview environment are a single-node k3s host (HOST-A) reached over VPN/SSH.

Queues and runners (snapshot of the scripts in `docs/ops-snapshot-1124/`; read them):
- `lane-runner.sh` (launchd, every minute): picks briefs from `lane-queue/`, routes them to a model by lane name
  (MODEL_ROUTING.md), caps parallel lanes (6, at most half on Antigravity), creates the worktree, starts the agent.
  When a lane ends with exit 0 it writes a ship entry to `ship-queue/`. Lanes that change production code get an
  automatic "tier B" review (`b-review.sh`: Sonnet, plus Neon for Claude-written code); BLOCK moves the entry to `held/`.
  When the lane queue runs low it queues "refill" lanes that themselves write new briefs (backlog, debt, review, UI, P1).
- `ship-runner.sh` (launchd): takes up to N ship entries (now 3), builds ONE batch branch, and runs `shipq3.sh`, which
  (a) starts the scoped unit-test pretest (`jt.sh`: Java scoped by `pretest_scope.py`, tools tests, tsc, all vitest)
  on the Mac, (b) runs `scripts/standalone_orchestrate.py ship`: rebase onto main, push, open a PR, build preview images
  on HOST-A, run an isolated preview e2e, wait for the pretest verdict (merge holds around the 00/06/12/18 collection
  windows), wait for required GitHub checks, merge, deploy to production (`run_build.sh` on HOST-A: image build,
  security gate, module rollout), run production e2e, auto-rollback on failure. Two batches may be pipelined.
  On failure: a conflicting item gets a "port" lane; a failed item gets a "fix" lane; infra-looking failures are retried;
  guarded paths (deploy, security, auth, etc.) go to `held/` for the PO session.
- `main-green.sh` (launchd): after each main commit runs governance tools tests, tsc and vitest; red stops ships.
- `watch-check.sh` (launchd, 30 min) and an event waiter keep the PO session awake to act.
- A state-sync lane is queued every 2 shipped batches to update project records (backlog, roadmap, CURRENT_STATE).
- GitHub Actions: validate, integration (PostgreSQL 16), regression-scope, targeted/full regression, CodeQL,
  dependency scan. Full regression moves to nightly-only tonight.

Rules that shape the flow: AGENTS.md (constitution), frozen contracts before new device commands or migrations, a live
BEGIN/ROLLBACK dry-run for every migration, masked data only, PO approval for device commands, no prod DB writes.

## 2. What happened (measured from the runner logs; counts)

Ship runner, per day:

| Day | Shipped batches | Failed | Held | B-review BLOCK | Conflicts (port) | Lanes queued by runner |
|---|---|---|---|---|---|---|
| 2026-11-22 | 23 | 5 | - | - | 22 | 22 |
| 2026-11-23 | 43 | 29 | 16 | 7 | 32 | 48 |
| 2026-11-24 | 54 | 18 | 7 | 4 | 5 | 28 |

Failure classes on 2026-11-24 (examples): CI check failed (5; e.g. integration audit-coverage tests the Mac pretest does
not run; a contract-authority governance test the docs fast path skipped), CI pending timeout (2; a check never
reported within 45 min), merge call failed on a GitHub API error after the merge had actually happened (2), preview image
build failed (1; a test referenced an export a debt lane had removed), unit tests (3), "nothing to ship" (5; branch
already merged).

Lanes started on 2026-11-24: 72 starts. By kind: fix 20, state-sync 13, p1 12, refill 10, debt 7, port 5, frontend 2,
ui 1, java 1, docs 1. So about 35 % are rework (fix + port) and 18 % are project-record syncing; under a fifth are new
product work. Lane-level problems seen today: Antigravity headless permission denials reported as "no change" (exit 3)
and silently dropped; transient Antigravity start errors; "interrupted" runs; a Gradle daemon recreating a removed
worktree directory; lane names not unique; two lanes doing the same fix; a refill naming scheme ignored.

Typical ship: median 19-22 min from batch start to production; worst 101 min (window hold). The PO session spends most of
its time diagnosing failures, re-queueing, porting and reviewing held items, not on product decisions.

Prior review: `docs/ops-snapshot-1124/COUNCIL_REVIEW_2026_11_23.md` (four reviewers, 2026-11-23). Some of its
process items were adopted (vitest gate, tier B review, fresh lane base, risk tiers). Say what it missed or what did
not work.

## 3. Questions

1. Diagnose: what are the 3-5 root causes behind the failure and rework numbers (not symptoms)? Use the scripts and
   the counts. Where does work get lost, duplicated or re-done?
2. Orchestration model: is "many parallel AI lanes -> batch ship runner -> PO session fixes the rest" the right shape for
   one PO, one laptop and one prod host? Propose the target model (fewer, larger lanes? a planner/integrator stage?
   per-area ownership? a merge queue? trunk + feature flags?), with the reasoning.
3. Harness: can we build a harness that makes a lane's output first-time-right before it reaches the ship runner (same
   checks as CI and the pretest, run inside the lane; contract and governance tests; conflict pre-check against the
   queue; a definition-of-done checklist the lane must prove)? Design it: inputs, steps, exit codes, where it runs.
4. Checks placement: list every check (lane, b-review, pretest, preview e2e, GitHub CI jobs, main-green, prod e2e) and
   say which to keep, move, merge or drop so that each failure is caught once, as early and cheaply as possible, and
   never only on GitHub after a 20-minute wait.
5. State and bookkeeping: 18 % of lanes only sync project records. Is that work needed at this rate? Propose a cheaper
   way.
6. The Mac as the only runner: what should move to HOST-A or GitHub, what must stay local (bank rules: data stays
   inside, PO controls Git)?
7. A 2-week improvement plan: ordered steps, each with its measurable target (e.g. first-time-right rate of lanes,
   ship failure rate, PO-session minutes per shipped change, rework share).

Output: one markdown review. Plain English, short sentences. Start with a 10-line summary. No raw hostnames, IPs or
serials (there are none in the inputs; keep it that way).
