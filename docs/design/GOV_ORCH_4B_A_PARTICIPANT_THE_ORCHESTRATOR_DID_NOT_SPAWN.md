# GOV.ORCH.4-B — A participant the orchestrator did not spawn is not a dead process

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-15.** Correcting amendment to
`docs/design/GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md`
§3.3 (FROZEN, not edited in place). Every other clause of GOV.ORCH.4, and
`GOV_ORCH_4A_COST_FROM_THE_REQUESTED_MODEL_WHEN_THE_STREAM_IS_SILENT.md`,
stand unchanged.

## 1. The measurement

§3.3 defines a closed six-value health field — `healthy`, `silent`,
`exited_without_close`, `awaiting_po`, `failed`, `done` — and
`scripts/orchestrator_dashboard.py::derive_health` returns exactly one of
them, enforced by that section's own AC-5 test. Its precedence is: a
terminal outcome wins; then `awaiting_po`, because an engineer that posts a
question ends its process on purpose; then a dead-but-not-terminal process
is `exited_without_close`.

Every value was defined when each movement was a process the orchestrator
spawned. `roles/PO.md` now carries a second path: a movement handed to a
participant the orchestrator cannot spawn, prepared exactly as a dispatch up
to the moment the process would be started. Such a movement **has no process
at all**. Its `pid` is absent by design, so it falls through to
`exited_without_close` and the workbench reports a failure where none
occurred. Observed on `NXS-LOCAL-0183` and `NXS-LOCAL-0184`, both of which
completed successfully while being shown as exited.

## 2. The decision

- **HB-1. A seventh value, `handed_over`,** joins the closed set: the
  movement has a prepared worktree and an open relay and is with a
  participant the orchestrator does not supervise. It is a normal working
  state, not a problem state, and the workbench must not present it as one.
- **HB-2. It is selected only from an explicit record field** — the state
  record's own `external_participant` — and **never inferred from a missing
  `pid`**. A spawned movement whose process died still reports
  `exited_without_close`, unchanged, and a record without the flag behaves
  exactly as it does today. Default-closed, as GOV.ORCH.10 R-2b is.
- **HB-3. Precedence is otherwise unchanged.** A terminal outcome still
  wins, `awaiting_po` still comes next; `handed_over` sits immediately
  before the dead-process rule, and replaces it only for a movement carrying
  the flag.
- **HB-4. Idle time is not a stuck signal for such a movement.** There is no
  `.nexus/engineer.log` to grow, so the silence threshold measures nothing.
  The workbench shows the lane's most recent commit instead, which is the
  only progress evidence that exists for it.

## 3. What this does not change

The six existing values and their meanings; the stuck detector for spawned
movements; the usage and cost fields of §3.1 and §3.2; `GOV.ORCH.4-A`'s
cost-source honesty; any merge gate. A `handed_over` movement is verified,
reviewed and merged exactly like any other — this record changes what the
workbench *says*, not what the loop *requires*.

## 4. Acceptance

`derive_health` returns `handed_over` for a record whose
`external_participant` is set, whose relay is open and whose phase is not
terminal; returns `exited_without_close` unchanged for a record without the
flag; still returns exactly one value from the closed set, now of seven, for
every input in the generated matrix; and a terminal phase or a closed relay
still wins over the flag. Each case is a unit test over the pure function,
not a live dispatch.

## 5. Cross-references

- `GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md` §3.3, AC-5.
- `roles/PO.md` §3, "Handing a movement to a participant the orchestrator cannot spawn".
- `docs/reference/PROVIDER_OPERATING_NOTES.md` — which participants take that path.
