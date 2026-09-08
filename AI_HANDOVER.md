# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `m10_2_capability_state_resolver_core` — implements the
  capability-state resolver core (stages 0-3 of
  `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` section 5):
  the `RESOLVED`/`OMITTED` union tag, the `CX1`/`RI-1`/`RI-2`
  contradiction/inconsistency gate, the twelve-rank primary ladder, and the
  seven capability qualifiers — as pure, fully tested functions with no I/O,
  no device contact, and no consumer wired yet.
- Ran in its own worktree (`relay/NXS-LOCAL-0008`), in parallel with
  GOV.PO.3 and OP.1; branch was already even with `origin/main` at the time
  of this session's own bookkeeping (no merge conflict to resolve).

## 2. What changed

- `utils/capability_state_resolver.py` (new): `resolve_union_tag()` (stage
  0) returns one of two structurally distinct types, `Resolved` (no fields)
  or `Omitted` (`reason` + optional `diagnostic`, never a `None`-filled
  capability output). `evaluate_stage1()` (stage 1) plus
  `evaluate_ri1_comparability()` implement `CX1` (from an explicit
  `IdentityConflict`, `I10`), the `K1`-`K6` `RI-1` comparability precedence
  table (5.1.1, six rows, first match wins), and `RI-2` by calling
  `utils.registry_evidence_reconciliation.detect_ri2` (`M10.1`) directly —
  not reimplemented. `resolve_primary_status()` (stage 2) implements the
  primary ladder, ranks 0-11, first-match-wins, over typed `LadderInputs`;
  malformed/absent fields simply match no rank and fall through to rank 11
  rather than being specially coerced. `resolve_qualifiers()` (stage 3)
  derives each of the seven `CapabilityQualifier` values from exactly one
  input facet. `to_wire()` serializes only under the `capability_state`/
  `capability_qualifiers` keys (4.4 namespacing rule).
- `tests/test_m10_2_capability_state_resolver.py` (new, 134 tests): stage-0
  cases V-A..V-D plus `Omitted`/`Resolved` structural checks; vocabulary
  size + lexical-disjointness parametrized tests against
  `console/jobs.py::TERMINAL_STATES`, `utils/operate/states.py::ActionState`
  and `utils/action_taxonomy.py::ACTION_CLASSES`; the serializer's
  namespacing; the full `K1`-`K6` table one row at a time plus two mixed-
  ordering cases; `RI-2` reuse; one test per ladder rank 0-11 plus the
  rank-8-vs-9 ordering case and a `POLICY_UNKNOWN`-never-reaches-rank-11
  case; one test per qualifier plus the four 4.1.5 inference-ban negative
  tests; an AST-based purity check (no `requests`/`paramiko`/`socket`/
  `subprocess` import, no `utils.capability_registry` import).
- `project/build_history.json`: new `m10_2_capability_state_resolver_core`
  record (`automated_validated`), newest-first.
- `project/roadmap.json`: `current_build`/`now_next.now` →
  `m10_2_capability_state_resolver_core`; removed its own now-stale
  `upcoming` row; `now_next.next` (`gov_po_2_implementation`) unchanged.
- `CURRENT_STATE.md`: checkpoint updated; the stale `## Active build`
  section (still describing a much older predecessor,
  `gov_po_1_step_6_plan_po2_boundary`, in full prose) compressed to
  pointer form to stay within the enforced 200-line ceiling
  (`test_current_state_stays_a_checkpoint_not_a_history`).
- `docs/history/INDEX.md`: regenerated via `scripts/build_history_index.py`.

## 3. What was deliberately not done

- Stages 4-5 (evidence presentation, action affordance) — explicitly the
  next slice; nothing here computes them.
- `D2`/`D3`/`D5` producers — `M10.3`/`M12`; this module only consumes
  already-resolved values of those dimensions via typed inputs
  (`EntityApplicability`, `VendorSupport`, `CapabilityPolicy`).
- The stage-0 diagnostic pass (4.1.4) — explicitly `OPTIONAL`;
  `Diagnostic` is a plain passthrough type a future caller may populate.
- No wiring into `html_export`, the console, any payload or navigation
  module. `utils/capability_registry.py` (the `0.6.1C` collection planner)
  is neither extended nor imported — proven by a dedicated test.
- `project/feature_registry.json` — not touched, consistent with `M10.1`'s
  own precedent; no feature-registry entry names this resolver.

## 4. Exact next action

1. Commit this movement's changes on `feature/m10-2-capability-state-
   resolver-core`, push, open a PR against `main`, confirm the standing
   `relay#13` merge gate (green tests) and merge; report via `RELAY_NOTE`
   on the relay file per the SESSION_START's `merge_gate`.
2. `M10.3` (D2/D3 producers) and `M12` (D5 producer) remain `planned`,
   not authorized by this movement — each needs its own SESSION_START.
   Once available, a later slice wires stages 4-5 and assembles the full
   `CapabilityResolution` this module's stage 0-3 functions feed into.
3. `gov_po_2_implementation` stays `now_next.next`, unrelated to and
   unblocked independently of this movement.

## 5. Test delta

New: `tests/test_m10_2_capability_state_resolver.py` (134, all passed).
Affected regression: `tests/test_m10_1_registry_evidence_reconciliation.py`
+ `tests/test_architecture_convergence.py` — 78 passed,
`project_metadata_has_no_cross_authority_contradictions` clean. Full
one-shot regression not re-run (`DEV.TEST.1`: backend-only pure-function
addition with no shared-core change; last full-suite evidence holds).
`git diff --check` clean.

## 6. Risks / notes forward

- This module has zero callers today — it is inert with respect to any
  running behavior, product output, or UI. No render-harness or privacy-
  gate implication beyond the standard PR-time check.
- The rank-10 `AVAILABLE` conjunction's "every required input well-formed"
  clause is implemented as membership checks against each dimension's
  closed domain; a future caller must supply real domain values (not raw
  strings) for this to fail closed correctly — documented in the module's
  own `LadderInputs` docstring.
