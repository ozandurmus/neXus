# GOV.ORCH.9 — Backlog active set and terminal reserve

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-13.** Successor amendment to
`docs/design/GOV_ORCH_8_PROJECT_DATA_SPLIT_AND_SIZE_BUDGET.md` §2.1 and §2.4,
and to `docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md`, both
FROZEN. It amends exactly two things in them — the `backlog.json` row of §2.1's
field-split table, which GOV.ORCH.8 deliberately left "as GOV.ORCH.5 left it",
and the enforcement shape of §2.4's `backlog.json` budget. Nothing else in
either document is touched, and neither is edited in place: this document is
the amendment, per the repository's successor-contract precedent.

This freeze is a Product Owner act taken on measurement, not on a proposal. The
measurement is §1 and was taken at `b08a298`.

## 1. The measurement

`project/backlog.json` is **64,203 bytes** against a 64,512-byte regression
ceiling: **309 bytes of headroom**. It is also already **2,763 bytes above**
GOV.ORCH.8 §2.4's own 60 KiB contract limit, which the budget test records as a
known, pre-existing gap rather than enforcing.

Its 154 items divide by status as follows:

| Set | Items | Bytes |
| --- | --- | --- |
| Terminal (`done`, `deferred`, `automated_validated`, `real_env_validated`) | 82 | 29,988 |
| Open (`planned`, `in_progress`) | 72 | 26,040 |

**The terminal set is larger than the open set, and the cold start needs none
of it.** That is the whole finding: the file is not too large because the
backlog is large, it is too large because it carries a completed history the
reading session never reads.

## 2. The decision

- **B-1.** `project/backlog.json` carries the **active set only**: items whose
  status is `planned` or `in_progress`. It is the file the cold start loads.
- **B-2.** Terminal items move, row schema unchanged, to
  `project/archive/backlog_terminal.json`. This is the **reserve**. It is
  tracked, it is never loaded at cold start, and no role file, brief or reading
  order points a session at it.
- **B-3.** This is the pattern GOV.ORCH.8 §2.2 already established for
  `build_history.json` and its `project/archive/build_history_2026.json`,
  applied to the one file §2.1 exempted. It is deliberately not a new idea.
- **B-4. Raising the ceiling is not the fix and is not permitted here.** The
  60 KiB contract limit of GOV.ORCH.8 §2.4 stands unchanged. After the split
  the active set is expected at roughly 34 KiB, which is under that limit
  rather than merely under the regression ceiling — so this amendment
  **closes** §2.4's known pre-existing gap instead of perpetuating it.
- **B-5.** An item that becomes terminal moves to the reserve through the
  single write path (`scripts/project_queue.py`), never by hand. An item that
  reopens moves back the same way. `AGENTS.md`'s project-state update rule and
  GOV.ORCH.5's "never hand-edit `project/*.json`" both continue to apply, to
  both files.
- **B-6.** Nothing is deleted and no count changes. Every count, percentage and
  warning `utils/project_plan.py` produces must be identical before and after,
  which means anything that counts the backlog reads **both** files, exactly as
  GOV.ORCH.8 §2.2's `_load` helper does for builds.

## 3. Budgets, amended

`tests/test_project_files_budget.py`:

- `project/backlog.json` — **≤ 40 KiB**, enforced, with no known-gap escape
  clause. The existing 63 KiB regression ceiling and the conditional assertion
  that tolerates being above 60 KiB are both removed: after the split they
  describe a state that no longer exists, and leaving them would silently
  re-admit it.
- `project/archive/backlog_terminal.json` — **≤ 60 KiB**, enforced. The reserve
  is bounded too; an unbounded archive is how the original file got here.
- Every other budget in GOV.ORCH.8 §2.4 is unchanged.

## 4. Acceptance

1. `project/backlog.json` contains only `planned` and `in_progress` items, and
   is under 40 KiB.
2. `project/archive/backlog_terminal.json` contains exactly the 82 terminal
   items, row schema unchanged, and is under 60 KiB.
3. The union of the two files equals the original 154 items, item for item, by
   id — proved by a check that compares the id sets and each row's content, not
   by a count alone.
4. `utils.project_plan.build_project_plan_payload()` produces an **identical**
   payload before and after: every count, percentage, warning list and id set.
   This is GOV.ORCH.8 §2.5's render-equivalence rule, applied here.
5. `python3 scripts/project_queue.py check` passes, and `render` produces a
   `QUEUE.md` whose open-item content is unchanged.
6. `scripts/project_queue.py` reads both files wherever it counts or looks up by
   id, writes each item to the file its status selects, and moves an item
   between the two files when its status crosses the terminal boundary — with a
   test for each direction.
7. The repository privacy gate reports zero findings.
8. `python3 -m pytest tests/test_project_files_budget.py tests/test_architecture_convergence.py -q` passes.

## 5. Out of scope

Deleting any item, editing any note, changing any status, changing any other
project JSON, changing the cold-start reading order beyond not pointing at the
reserve, and every budget other than the two named in §3.

## 6. Cross-references

- `docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md` — the single
  write path and the cold-start diet this continues.
- `docs/design/GOV_ORCH_8_PROJECT_DATA_SPLIT_AND_SIZE_BUDGET.md` §2.1, §2.2,
  §2.4, §2.5 — the row this amends, the archive pattern it copies, the budgets
  it changes, and the render-equivalence rule it inherits.
- `AGENTS.md` — project-state update rule, authority hierarchy.
