# PO decision record - 2026-11-24: delivery orchestration redesign

**Status: RATIFIED - PO DECISION (chat, 2026-11-24 ~23:40).** Direction and rules for how work is planned, built,
verified, merged and deployed. Implementation proceeds in lanes; each delivery change still merges through the PO
session because `tools/delivery/`, `scripts/` and `.github/` are guarded paths.

Inputs (independent reviews of the same brief, read as evidence, not authority):
[NEON_DELIVERY_ORCHESTRATION_REVIEW_2026_11_24.md](NEON_DELIVERY_ORCHESTRATION_REVIEW_2026_11_24.md) (Gemini Neon, high),
[FABLE_DELIVERY_ORCHESTRATION_REVIEW_2026_11_24.md](FABLE_DELIVERY_ORCHESTRATION_REVIEW_2026_11_24.md) (Claude Fable 5.2,
with public-source research), brief
[DELIVERY_ORCHESTRATION_REVIEW_BRIEF_2026_11_24.md](DELIVERY_ORCHESTRATION_REVIEW_BRIEF_2026_11_24.md).

## 1. Why

PO, translated: "We cannot orchestrate: every job keeps failing and getting delayed... I want more quality without
losing speed." Baseline 2026-11-24 (runner logs): new product work 32 % of lane starts, rework (fix + port) 37 %,
record syncing 17 %, refill 13 %; ship failure 23 %; 7 failures caught only by GitHub CI; median ship 22.6 min.
Both reviews found the same root causes: a lane is "done" when the agent exits 0 with a commit; the Mac pretest and
GitHub CI run different checks; work in progress outruns a serial ship path; review BLOCKs and exceptions all land on
the PO session; the runner scripts live outside Git with live bugs.

## 2. Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Parallel lanes | 3 (was 6). May be raised later on measured results. |
| D2 | Where work comes from | A planner run twice a day produces non-overlapping briefs with declared paths, area and backlog id; it also runs when the lane queue empties, so development does not stall. The rotating refill lanes stop. |
| D3 | Lane harness | Runner-enforced: no ship entry without a passing harness report bound to the tree. Checks run once: a PASS report replaces the ship pretest for the same tree, so delivery must not get longer. Heavy Java and PostgreSQL 16 integration tests run on HOST-A (D6). A failed check or a review BLOCK goes back to the author lane once; only a second failure reaches the PO session. |
| D4 | Merge vs deploy | Decoupling (merge queue + release trains) is to be evaluated, not adopted yet. |
| D5 | Records | Per-lane change fragments plus a deterministic daily fold replace the state-sync lanes; one prose sync per day. Delivery scripts move into Git with tests. |
| D6 | HOST-A use | HOST-A is used actively for test load to relieve the Mac: a separate test namespace with a priority below every production class, a resource quota, default-deny network policy (no production database, no devices, no internet) and no secrets; repository code and synthetic data only. Moving the deploy runner to HOST-A follows once the harness and the test namespace are stable. |
| D7 | Full regression | GitHub runs the full regression nightly and on manual dispatch, not per PR or push. |
| D8 | Measurement | Daily metrics compared with the 2026-11-23/24 baseline every day; the PO sees what was gained and lost. |
| D9 | Migrations | Flyway runs without `outOfOrder`: queued migrations merge in number order; the harness enforces it. |

## 3. Targets (14 days)

Ship failure under 10 %; rework under 15 % of lane starts; new product work over 50 %; CI-only failures 0; lane
first-time-right 80 %; PO-session time per shipped change halved.

## 4. Not decided here

Merge/deploy decoupling (D4); CodeQL and dependency-scan trigger changes; a non-production build host; raising the lane
cap. Each comes back to the PO with options.
