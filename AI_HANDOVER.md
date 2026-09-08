# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `m10_1_registry_evidence_reconciliation_projection`
  (`M10`'s first slice) — the `D4` registry↔evidence reconciliation
  projection. Product/engineering, no UI/nav/payload change.
- New `utils/registry_evidence_reconciliation.py`: `resolve_d4()` derives
  the five frozen values from classified registry/evidence sides;
  `detect_ri2()` implements `RI-2`.
- Blocked, then resolved via relay: no canonical id spans
  `utils/device_registry.py`'s `device_id` and the merged evidence model's
  `entity_id`. `RELAY_DECISION` on relay **#11** (option 4): ship with no
  join input — `utils/device_identity_relationships.py` stays unread.
- `now_next.next` is now **unset**: the queued candidate (`m10_1`) is
  complete; sizing the next actionable build is a Product Owner function.
  `M8.3` stays deferred and `M7` stays blocked, unchanged by this build.

## 2. What changed

Product/engineering: one new module and its test file, plus the standard
project-state bookkeeping. No template, static, payload, schema, storage,
console, resolver or navigation change.

- `utils/registry_evidence_reconciliation.py` (new): `D4_VALUES`,
  `RegistrySide`/`EvidenceSide` enums, `ReconciliationResult`, `resolve_d4()`,
  `detect_ri2()`.
- `tests/test_m10_1_registry_evidence_reconciliation.py` (new, 56 tests):
  every `RegistrySide`×`EvidenceSide` combination, `REGISTRY_DISABLED`
  reported regardless of evidence-side value, today's default join input
  (`EvidenceSide.UNRESOLVABLE`) resolving `RECONCILIATION_UNKNOWN` rather
  than `REGISTRY_ONLY`, `RI-2` boundary cases, and vocabulary-separation
  against the job lifecycle / `OP.2` action-state / action-taxonomy tokens.
- `project/build_history.json` head record + `docs/history/INDEX.md`
  regenerated; `CURRENT_STATE.md` checkpoint/active-build/next rewritten
  and trimmed back inside its 200-line budget; `project/roadmap.json`
  `now_next` updated (`now` -> this build, `next` -> unset).
- Relay `ozandurmus/nexus-agent-relay#11`: `RELAY_QUESTION` (canonical-id
  gap), Product Owner `RELAY_DECISION` (option 4), `RELAY_ACK`.

## 3. Exact next action

1. Merge this PR — needs the same relay issue's `SESSION_CLOSE` posted and
   validated, per this movement's own `merge_gate`.
2. A Product Owner `PLAN`/`REVIEW` episode sizes and authorizes the next
   actionable build (`M10.2` is sketched in `upcoming` but explicitly not
   yet sized or authorized).
3. Separately (not blocking `M10.1`): decide whether
   `utils/device_identity_relationships.py`'s `mapping_scope` may ever
   widen beyond `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` to give `D4` a
   real join — `RELAY_DECISION` #11 left this explicitly undecided.

## 4. Test delta

New: `tests/test_m10_1_registry_evidence_reconciliation.py`, 56 tests, all
passing. Affected sweep (this file + `test_pcp1_device_registry.py` +
`test_m4_control_plane_metadata_store.py` + `test_con1_operator_console_
read_only.py` + `test_con2_console_job_engine.py` +
`test_m6_registry_keyed_job_targets.py` + `test_architecture_convergence.py`):
308 passed, `metadata_warnings` empty. Repository privacy gate PASS/0
findings (`data/`, `logs/` cleared first). `git diff --check` clean;
`compileall` clean. No full regression run: no schema/storage/console/UI
change, one new previously-unimported module plus its own test file. No
render harness: no template/static/payload change.

## 5. Risks / notes forward

- `RECONCILED`, `EVIDENCE_ONLY` and `REGISTRY_ONLY` are implemented and
  unit-tested but structurally unreachable in production today — nothing
  calls `resolve_d4()` with anything but `EvidenceSide.UNRESOLVABLE` for a
  real entity, by the `RELAY_DECISION`'s own design, until a future
  movement supplies an authorized canonical id. This is disclosed, not a
  defect: `AC-3` requires exactly this behavior given no join input.
- Concurrent working-tree note: this checkout also carried an unrelated,
  unstaged `GOV_PO_1_GATE_3` fix to `scripts/nexus_po_tool_gate.py` and
  `tests/test_gov_po_role.py` from a Product Owner assistant session
  running against the same working directory. Left untouched and out of
  this movement's commit; this movement's own diffs to shared files
  (`project/build_history.json`, `CURRENT_STATE.md`, `docs/history/INDEX.md`)
  were built against `origin/main`, not against that session's uncommitted
  edits, so they should merge the same way any two concurrent movements do.
- `now_next.next` is left unset rather than unilaterally promoted to
  `M10.2` — its own `upcoming` row already says it needs its own sizing
  decision, which is a Product Owner function this engineering build does
  not perform.
- `M8.3` stays deferred and `M7` stays blocked; nothing here changes either.
