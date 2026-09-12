# GOV.ORCH.5 — Project queue file and cold-start diet

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-11 (chat directive "hepsine ok"). Direction approved by
the Product Owner in chat, 2026-09-11 ("Bunu onaylıyorum").** Amends
`AI_START_HERE.md` reading order step 3 and `AGENTS.md` "Project-state
update rule" by amendment note; changes no product data schema.

## 1. Problem

`project/backlog.json` (270 KB, 126 items, 86 % of its words are `note`
narrative, 52 items open), `project/roadmap.json` (120 KB, `now_next`
5.5k words, 39 `open_decisions` half of them decided) and
`project/build_history.json` (626 KB) are render data for the HTML
Project Plan module (`utils/project_plan.py`, `project/README.md`). The
reading order (`AI_START_HERE.md` step 3) makes every agent load the
first two at cold start: roughly 60k tokens of the ~100k a session
spends before its first edit. They are also hand-edited: `backlog.json`
is invalid JSON on `origin/main` today.

## 2. Design

### 2.1 `project/QUEUE.md`, generated, the only planning file agents read

`scripts/project_queue.py render` writes `project/QUEUE.md`:

```
# Project queue (generated — do not edit; run: py scripts/project_queue.py render)
Build: <roadmap.current_build> · Track: <current_track> · Generated: <iso>

## Now
- <roadmap.now_next.now item: id — title (target)>          one line each
## Next
- ...
## Open backlog (P1 first, then P2, then P3; in_progress before planned)
- P1 in_progress  <id> — <title, first 100 chars> (target: <first 80 chars>)
- ...
## Open decisions
- <roadmap.open_decisions[status=open]: id — question, first 100 chars>
```

Rules: no `note` text, no closed/deferred items, no decided decisions,
no build history. Target size under 1,500 words. Line format is fixed so
a test can parse it back.

`scripts/project_queue.py check` re-renders in memory and exits 1 if
`project/QUEUE.md` differs (the `build_history_index.py --check`
pattern); CI runs it next to the existing index check.

### 2.2 Writes go through the tool

```
py scripts/project_queue.py add    --id <id> --title ... --priority P2 --category ... [--target ...]
py scripts/project_queue.py status --id <id> --set in_progress|done|deferred|automated_validated|real_env_validated [--target ...]
py scripts/project_queue.py note   --id <id> --text ...      # appends to docs/history/backlog/<id>.md, not to the JSON
py scripts/project_queue.py decide --id <decision-id> --decision ...   # roadmap open_decisions -> status decided, text to PRODUCT_DIRECTION_RECORD.md is a PO action, not automated here
```

Every write loads the JSON, validates it (json.loads, required keys
per item: id, title, status, priority, category), applies the change,
writes canonically (indent 2, sorted keys off, trailing newline), then
re-renders `QUEUE.md`. A JSON that fails to load is reported with line
and column and nothing is written. `add` refuses a duplicate id.

`nexus_po_tool_gate.py`'s interactive allowlist gains
`py scripts/project_queue.py *` and drops `Edit(project/backlog.json)`,
`Edit(project/roadmap.json)` if present (read the gate to confirm; if
the gate never allowed them, no change).

### 2.3 One-time repair and narrative move (same movement, separate commit)

1. Repair `project/backlog.json` so it loads (fix the delimiter at the
   reported position; change nothing else) and prove it with
   `python3 -c "import json; json.load(open('project/backlog.json'))"`.
2. For every item whose `status` is terminal (`done`,
   `automated_validated`, `real_env_validated`, `deferred`), move the
   `note` field verbatim to `docs/history/backlog/<id>.md` (heading:
   the item title; first line: `status`, `target`) and replace the JSON
   `note` with `"see docs/history/backlog/<id>.md"`. Open items keep
   their note in the JSON (the tool's `note` command appends to the
   history file from now on for all items).
3. `utils/project_plan.py` and the HTML render must produce the same
   output for open items and the same counts/percentages overall; the
   existing project-plan tests decide this (run them before and after).

### 2.4 Reading order and update rule

- `AI_START_HERE.md` step 3 becomes: "`project/QUEUE.md` — pull the
  task by id; never open `project/*.json` directly (render data, tool-
  written)". `PO.md` §4 lists `QUEUE.md`; `.nexus/WORKER.md` template's
  standing rules add `project/*.json` to the never-read list.
- `AGENTS.md` "Project-state update rule": one amendment note: state
  updates to backlog/roadmap go through `scripts/project_queue.py`; the
  rule text itself is not rewritten (tests grep it).

## 3. Scope

In: `scripts/project_queue.py` (new), `project/QUEUE.md` (generated,
committed), `project/backlog.json` (repair + note move), `docs/history/backlog/*.md`
(new, generated once), `AI_START_HERE.md` (step 3 text), `PO.md` (§4
line), `scripts/orchestrator.py` (WORKER.md template line only),
`scripts/nexus_po_tool_gate.py` (allowlist), `AGENTS.md` (amendment note
only), `.github/workflows/validation.yml` (add the check next to the
build-history index check; read it first), tests: new
`tests/test_project_queue.py`; existing project-plan tests must stay
green.

Out: `roadmap.json` restructuring beyond reading it; `build_history.json`;
`feature_registry.json`; any change to the HTML renderer; deleting any
data.

## 4. Acceptance criteria

- AC-1: `render` on the repaired JSON produces `QUEUE.md` under 1,500
  words with the four sections and only open items / open decisions.
- AC-2: `check` exits 0 after `render`, 1 after any manual edit of
  `QUEUE.md`, and 1 when `backlog.json` does not load (with line/col).
- AC-3: `add`, `status`, `note`, `decide` each round-trip on a fixture
  copy; `add` refuses a duplicate id; `note` writes to the history file
  and never to the JSON note field.
- AC-4: `backlog.json` loads; terminal items carry the pointer note;
  every moved note exists verbatim in its history file.
- AC-5: project-plan tests and HTML render tests green before and after
  the note move; open-item counts and percentages unchanged.
- AC-6: `AI_START_HERE.md`, `PO.md`, WORKER.md template and `AGENTS.md`
  carry exactly the text in §2.4; `tests/test_worker_brief.py` golden
  file updated.
- AC-7: CI workflow runs `project_queue.py check`.
- AC-8: `git diff --check` clean; privacy gate 0 new findings (the
  three pre-existing findings in `project/*.json` may move to history
  files; they are pre-existing, not new).

## 5. Validation plan (machine-readable)

```
python3 -m pytest -q tests/test_project_queue.py tests/test_worker_brief.py tests/test_gov_po_role.py tests/test_phase0_6_1b_1_2_interactive_project_plan.py tests/test_html_export_placeholder_integrity.py
python3 scripts/project_queue.py check
python3 scripts/build_history_index.py --check
python3 scripts/repository_privacy_check.py
git diff --check
```

## 6. Worker route

`Sonnet 5, normal (low effort)`. JSON in, Markdown out, a fixed line
format, a repair whose location the parser reports. The only care point
is the note move (verbatim, reversible), which AC-4 pins.

## 7. Open items for the PO at freeze

- Whether `roadmap.json` `now_next` prose (5.5k words) should also move
  to a history file in a follow-up movement (proposed: yes, GOV.ORCH.6).
- Retention for `docs/history/backlog/`: keep forever (proposed) or
  prune by age.

## 8. Amendment A-2026-09-12 — open-item note exemption relaxed

**Status: PROPOSED — PENDING PRODUCT OWNER APPROVAL.** This section does
not change the FROZEN status line above, and does not itself authorize
anything; it records a measured contradiction and a proposed resolution
for the Product Owner to decide. Nothing in this repository may treat it
as approved until a Product Owner decision says so.

### 8.1 The measured contradiction

§2.3 point 2 of this document reads: "Open items keep their note in the
JSON." `docs/design/GOV_ORCH_8_PROJECT_DATA_SPLIT_AND_SIZE_BUDGET.md`
(FROZEN) §2.4 sets `backlog.json ≤ 60 KB`, and its own §3 "Out" line
marks `backlog.json`'s field split as unchanged from GOV.ORCH.5 —
i.e. it imports this document's open-item exemption unmodified while
also imposing a budget the exemption prevents this file from meeting.
Per `AGENTS.md` Authority hierarchy, this is a same-level (both FROZEN)
disagreement this movement must report, not silently reconcile.

Measured before this movement's change (`project/backlog.json`,
2026-09-12): 153 items, file size 148,302 bytes. Every terminal item
(`done`/`automated_validated`/`real_env_validated`/`deferred`) already
carries its GOV.ORCH.5 §2.3-point-2 pointer, holding 4,361 note bytes
between them — the first diet already did everything the open-item
exemption's complement allows. The remaining 87,022 note bytes sit on
42 note-bearing `in_progress`/`planned` items out of 72 open items —
exactly the population §2.3 point 2 exempts. Those bytes are 59% of the
file and the only remaining lever between 148,302 bytes and the
GOV.ORCH.8 §2.4 target: the exemption and the budget cannot both hold
against this data.

### 8.2 Proposed resolution

Relax §2.3 point 2 for note *content* only, not for status: an open
item's `note` field moves to `docs/history/backlog/<id>.md` the same way
a terminal item's already does, verbatim, leaving the JSON pointer form
`"see docs/history/backlog/<id>.md"` in its place. No item's `status`,
`priority`, `title`, `target` or id changes as part of this move, and no
note byte is deleted — every byte removed from `backlog.json` is
findable, verbatim after redaction, in its item's history file. Nothing
about §2.3 point 1 (repair) or the terminal-item mechanism changes.

Because this reaches back into an already-open item's live note (rather
than only firing on a status transition, as the terminal-item path
does), it is implemented as its own explicit, opt-in operation —
`py scripts/project_queue.py migrate-open-notes --include-open` — not as
a default behavior of `status` or `note`, and not automatic. Going
forward, `py scripts/project_queue.py note --id <id> --text ...` keeps
appending directly to the history file for every item regardless of
status, per §2.3 point 2's second clause, so an open item's note does
not re-accumulate inline in the JSON between now and its eventual
terminal transition.

### 8.3 What this does not decide

This amendment does not raise or waive the GOV.ORCH.8 §2.4 60 KB budget
for `backlog.json`, does not change GOV.ORCH.8's own "Out: `backlog.json`
(GOV.ORCH.5)" line, and does not decide retention policy for
`docs/history/backlog/` (§7, still open). If, after the move described
in §8.2, `backlog.json` still exceeds 60 KB, that residual gap is a
separate, subsequent measurement for the Product Owner and is not
resolved by relaxing this exemption alone.
