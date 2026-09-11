# GOV.ORCH.8 — Project data split: state in JSON, narrative in history, size budgets

## Status

**DRAFT — FOR PRODUCT OWNER FREEZE (2026-09-11). Direction approved by
the Product Owner in chat, 2026-09-11 ("Hepsi go").** Depends on
GOV.ORCH.5 (`scripts/project_queue.py`, backlog note move, redaction on
write). Extends the same pattern to `build_history.json`,
`roadmap.json` and `feature_registry.json`, and caps their size.

## 1. Problem

`project/build_history.json` is 608 KB for 191 builds; `evidence`
(285 KB), `summary` (172 KB) and `risks_forward` (80 KB) are 88 % of
it. `roadmap.json` is 120 KB; `now_next` prose (49 KB) and
`open_decisions` (41 KB, 18 of 39 already decided) are 75 %.
`feature_registry.json` is 64 KB. `utils/project_plan.py` uses only
state fields for counts and percentages, but passes whole build rows to
the page. Anyone who edits these files trips the privacy gate on
pre-existing findings, and the files keep growing because narrative is
appended in place.

## 2. Design

### 2.1 Field split (what stays, what moves)

| File | Stays in JSON (state) | Moves to `docs/history/...` (narrative) |
|---|---|---|
| `build_history.json` `builds[]` | `build`, `title`, `status`, `movement`, `docs`, dates, `summary` truncated to its first sentence (≤ 240 chars) + `"detail": "docs/history/builds/<build>.md"` | full `summary`, `evidence`, `risks_forward` → `docs/history/builds/<build>.md` (heading: build + title; sections Summary / Evidence / Risks forward, verbatim after redaction) |
| `roadmap.json` | `schema_version`, `current_build`, `current_track`, `progress_contract`, `tracks`, `engineering_tracks`, `now_next` with each item reduced to `{build, title, status, contract_doc}`, `open_decisions` with `status == "open"` only, each reduced to `{id, question, blocks, since}` | `now_next` prose per item → `docs/history/roadmap/now_next_<build>.md`; decided decisions → appended to `docs/design/PRODUCT_DIRECTION_RECORD.md` under a new "Decisions migrated from roadmap.json (2026-09-11)" section, verbatim; `roadmap_notes` and `architecture_review_notes` → `docs/history/roadmap/notes.md` |
| `feature_registry.json` | everything (criteria are data), except free-text `description`/`value` longer than 400 chars, which are truncated with a `detail` pointer to `docs/history/features/<id>.md` | the full text |
| `backlog.json` | as GOV.ORCH.5 left it | as GOV.ORCH.5 left it |

Every moved text passes the same redaction `scripts/project_queue.py`
applies on write (IPv4 → `<PRIVATE_IP>`, device hostname patterns →
`<DEVICE_NAME>`, credential-looking literals → `<REDACTED>`); a moved
file may never carry a privacy finding.

### 2.2 Archive of terminal builds

`build_history.json` keeps the newest 20 builds plus every build whose
status is not terminal. All other builds move to
`project/archive/build_history_2026.json` (same row schema, already
split per §2.1). `utils/project_plan.py::_load` gains a helper that
loads current + archive rows for counting only; the page shows current
rows and one line "N builds archived (docs/history/INDEX.md)".
`scripts/build_history_index.py --check` reads both files.

### 2.3 One write path

`scripts/project_queue.py` gains `build add|status|note` and
`decision open|decide` subcommands mirroring the backlog ones, with the
same validation, canonical write, redaction, and history-file appends.
The `render` output (`QUEUE.md`) gains a "Recent builds" section (last 5,
one line each) so the PO never opens `build_history.json`.

### 2.4 Size budgets, enforced

`tests/test_project_files_budget.py`: `backlog.json` ≤ 60 KB,
`roadmap.json` ≤ 40 KB, `build_history.json` ≤ 80 KB,
`feature_registry.json` ≤ 70 KB, `project/QUEUE.md` ≤ 1,500 words;
plus "no field named `note`, `evidence`, `risks_forward` longer than
400 chars in any project JSON". Wired into CI next to the queue check.

### 2.5 Render equivalence

Before any data move, run `python3 scripts/render_sample.py` and save
the project-plan payload (`utils.project_plan.build_project_plan_payload()`)
to a fixture; after the move, every count, percentage, warning list and
build id set must be identical, and every field `static/project_plan_ui.js`
renders must still be present (a moved narrative field is replaced by
its first sentence plus the `detail` pointer; the UI shows the pointer
as a link text, no other UI change). The existing project-plan and
HTML-export tests must pass unchanged.

## 3. Scope

In: `scripts/project_queue.py`, `utils/project_plan.py` (load helper
and archive count only), `scripts/build_history_index.py` (read both
files), `static/project_plan_ui.js` (render pointer only),
`project/build_history.json`, `project/roadmap.json`,
`project/feature_registry.json`, `project/archive/` (new),
`docs/history/builds/`, `docs/history/roadmap/`, `docs/history/features/`
(new, generated once), `docs/design/PRODUCT_DIRECTION_RECORD.md`
(appended section), `project/README.md` (one paragraph: state vs
narrative, write path), tests (`tests/test_project_files_budget.py` new,
`tests/test_project_queue.py`, existing project-plan tests read-only),
`.github/workflows/validation.yml`.

Out: `backlog.json` (GOV.ORCH.5), deleting any data, changing any count
or percentage, any other UI change, relay tooling.

## 4. Acceptance criteria

- AC-1: after the move the four JSON files satisfy the §2.4 budgets and
  the budget test passes; every moved narrative exists verbatim
  (post-redaction) in its history file with a pointer back in the JSON.
- AC-2: the project-plan payload fixture comparison of §2.5 is
  byte-identical for counts/percentages/warnings/ids; project-plan and
  HTML-export tests pass unchanged.
- AC-3: `build_history.json` holds the newest 20 plus non-terminal
  builds; the archive holds the rest; `build_history_index.py --check`
  passes reading both.
- AC-4: `project_queue.py build` and `decision` subcommands round-trip
  on fixture copies with validation, redaction and history appends.
- AC-5: `python3 scripts/repository_privacy_check.py` reports zero
  findings under `project/` and `docs/history/{builds,roadmap,features}/`
  (record before/after totals).
- AC-6: `QUEUE.md` has the "Recent builds" section and stays under 1,500
  words.
- AC-7: CI runs the budget test.
- AC-8: `git diff --check` clean; commits ordered: tool changes, data
  move, archive, budgets (four commits so each is reviewable).

## 5. Validation plan (machine-readable)

```
python3 -m pytest -q tests/test_project_files_budget.py tests/test_project_queue.py tests/test_phase0_6_1b_1_2_interactive_project_plan.py tests/test_html_export_placeholder_integrity.py tests/test_architecture_convergence.py
python3 scripts/project_queue.py check
python3 scripts/build_history_index.py --check
python3 scripts/repository_privacy_check.py
git diff --check
```

## 6. Worker route

`Sonnet 5, normal (medium effort)`. Mechanical moves, but the render
equivalence proof and the archive/count seam in `project_plan.py` need
a careful pass; not low.
