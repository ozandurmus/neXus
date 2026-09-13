# GOV.ORCH.11 — Closing a backlog item must not destroy its history

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-13.** Defect-correcting amendment to
`docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md`, which is FROZEN
and is not edited in place. It changes one behaviour of the single mandated
write path and nothing else.

## 1. The defect, measured

`scripts/project_queue.py` has two ways to write `docs/history/backlog/<id>.md`:

- `cmd_note` **appends** to the file when it exists.
- `cmd_status`, on a move to a terminal status, calls `_move_note_to_history`,
  which calls `path.write_text(header + note)` — an **overwrite**.

GOV.ORCH.5's own open-item sweep, `_migrate_note_to_history`, already does the
right thing: it appends under a dated heading when the file exists. Only
`_move_note_to_history` overwrites. The codebase therefore already contains the
correct pattern and one function that does not use it.

**The consequence is data loss, and it was observed, not theorised.** GOV.ORCH.5
migrated open items' narrative out of the JSON and left `note` holding the
literal pointer `see docs/history/backlog/<id>.md`. For every such item,
closing it now writes `header + "see docs/history/backlog/<id>.md"` over the
file that holds the narrative — replacing the history with a line that points
at itself.

Measured on 2026-09-13 across two movements that closed eight items: eight
history files lost their narrative. `ui2_b0_c1_platform_schema_contract.md`
lost its delivery record, its PR numbers, its acceptance self-check and **two
open items recorded for the Product Owner**.
`ui2_taxonomy_device_write_class_and_step_kind.md` lost the reasoning for why a
device-write action class is a security boundary, and an explicit instruction
that it must not be auto-dispatched without a `DECIDE` episode.

The failure is silent: the tool reports success, the queue check passes, and
the privacy gate passes. Only a diff review finds it.

## 2. The decision

- **N-1.** Closing a backlog item — any move to a terminal status — **must
  never reduce the information in its history file.** The history file is
  append-only from the tool's point of view.
- **N-2.** `_move_note_to_history` appends when the file exists, in the same
  shape `_migrate_note_to_history` already uses, and creates it with the
  standard heading only when it does not exist.
- **N-3.** A `note` value that is merely the pointer
  `see docs/history/backlog/<id>.md` carries no information and **must not be
  written to the history file at all**. Writing a file's own path into that
  file is never a record of anything.
- **N-4.** The status line in the heading may be updated in place, because it
  is a field and not narrative. Updating `status: in_progress` to
  `status: done` is not a reduction of information; replacing a paragraph
  with a pointer is.
- **N-5.** This rule binds the tool, not its callers. A worker must not have to
  know that closing an item can destroy history in order to avoid destroying
  it. GOV.ORCH.5 makes this tool the only write path precisely so that the
  guarantee lives in one place.

## 3. Restoration

The eight history files damaged on 2026-09-13 were not merged: the damage was
found in Product Owner review of two unmerged branches, and both branches are
discarded rather than corrected in place. The narrative therefore still stands
at `origin/main` and no restoration commit is required — but a movement that
re-applies those closures must do so with the corrected tool, and must verify
afterwards that each history file still contains its pre-existing narrative.

## 4. Acceptance

1. `_move_note_to_history` appends to an existing history file and never
   truncates one.
2. A note whose entire content is the pointer `see docs/history/backlog/<id>.md`
   is not appended.
3. A regression test closes an item whose history file already holds narrative
   and whose JSON `note` is that pointer, then asserts the narrative is still
   present **verbatim** afterwards. This is the test the defect would have
   failed; without it the fix is unproven.
4. A second test covers the ordinary case: an item with a real JSON note, whose
   note reaches the history file on closure without removing what was there.
5. A third test covers `cmd_note` followed by `cmd_status --set done`, and
   asserts the appended note survives. That is the sequence a worker naturally
   uses, and it is the sequence that lost data.
6. The heading's `status:` line reflects the new status after closure (N-4).
7. `python3 -m pytest tests/test_project_queue.py -q` passes.
8. `python3 scripts/project_queue.py check` passes.

## 5. Out of scope

Changing which statuses are terminal; changing the JSON `note` field's own
lifecycle or the pointer it is replaced with; the backlog split of GOV.ORCH.9;
redaction behaviour; and any backlog item's status or content.

## 6. Cross-references

- `docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md` — the single
  write path and the note-move design this corrects.
- `docs/design/GOV_ORCH_9_BACKLOG_ACTIVE_SET_AND_TERMINAL_RESERVE.md` — the
  terminal boundary a closure now also crosses; unchanged by this amendment.
- `AGENTS.md` "Project-state update rule", and "Do not silently rewrite
  historical outcomes" — the law this defect violated.
