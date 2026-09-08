# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `gov_po_1_step_6_plan_po2_boundary` — a `PLAN` episode:
  Product Owner direction to correct the PO assistant's own permission
  architecture after a run of small gate-fix movements (relay#11-#16, the
  local relay protocol) surfaced repeated friction.
- Two-movement plan: `gov_po_2_po_visibility_and_bounded_authorship`
  (ARCHITECTURE, one new DRAFT document, `next`) and
  `gov_po_2_implementation` (IMPLEMENTATION, `upcoming`/blocked until the
  first is FROZEN by the Product Owner).
- Predecessor `gov_po_1_local_relay_protocol` **MERGED** PR #120
  (relay#15, now closed).

## 2. What changed

- `project/roadmap.json`: `now_next.next` set to
  `gov_po_2_po_visibility_and_bounded_authorship`; `gov_po_2_implementation`
  added to `upcoming`, status `blocked`.
- `project/build_history.json`: new head record
  `gov_po_1_step_6_plan_po2_boundary`, status `complete_with_followup`.
- `CURRENT_STATE.md`: checkpoint/active-build sections rewritten, kept at
  or under 200 lines.
- `docs/history/INDEX.md`: regenerated.
- No code, test, gate, or agent-definition file touched — this episode is
  planning only, per `GOV_PO_ROLE_MIGRATION.md` §2's PO write boundary.
- Two SESSION_START packets validated
  (`scripts/gov_session_transfer.py validate`: true each) but only the
  first is opened as its own relay artifact:
  `relay/NXS-LOCAL-0002-gov-po-2-visibility.json` (next_actor: engineer).
  This PLAN episode's own record is at
  `relay/NXS-LOCAL-0001-plan-po2-boundary.json`.
- `gov_po_2_implementation`'s SESSION_START is fully drafted and validated
  but deliberately not yet opened as a relay artifact, since it cannot
  start until movement 1 is FROZEN.

## 3. Exact next action

1. An engineering session picks up
   `relay/NXS-LOCAL-0002-gov-po-2-visibility.json` and writes
   `docs/design/GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md`
   (DRAFT) per that SESSION_START's acceptance criteria — one new document
   only, no code.
2. The Product Owner reviews and FREEZES that document (a separate,
   explicit `DECIDE` episode; a `nexus-decision-council` round is
   recommended there per `GOV_PO_ROLE_MIGRATION.md` §7 trigger (b)).
3. Only then does `gov_po_2_implementation` open its own relay artifact and
   start — implementing exactly what the frozen document specifies in
   `scripts/nexus_po_tool_gate.py` plus the new
   `nexus-po-evidence-reviewer` agent.
4. This PLAN episode's own `gov/po-*` branch/PR still needs the Product
   Owner's explicit `RELAY_DECISION` before merge
   (`GOV_PO_ROLE_MIGRATION.md` §2 — unaffected by relay#13's
   engineer-movement merge carve-out).

## 4. Test delta

None — this episode is planning/drafting only, no code or test change.
`project/roadmap.json`/`build_history.json` JSON parse-validated;
`tests/test_architecture_convergence.py` re-run before merge is still owed.

## 5. Risks / notes forward

- Verified against the actual current gate/settings/agent state (not
  assumed): `READ_TOOLS` already gives the PO unrestricted file read; all
  five relay markers are already handled consistently by both the GitHub
  gate and `scripts/local_relay.py`; `RELAY_DECISION` already requires
  `authorized_by`/`scope`/`supersedes` in both transports. `gov_po_2_
  implementation` is scoped to the genuine remaining gaps only —
  `gh pr diff`/`gh run` read commands, the privacy-check mechanism,
  `docs/design/po_drafts/*.md` with status-line rejection,
  `docs/history/INDEX.md` made truly generator-only (currently direct-
  Edit/Write-reachable via `GOVERNANCE_PATHS`, a real gap this plan found),
  and the new evidence-reviewer agent.
- Movement 1's document must close, not defer, three real open design
  questions (privacy-check mechanism choice, exact FROZEN/RATIFIED
  status-line detection rule, evidence-reviewer agent's exact tool list) —
  if it leaves any ambiguous, `gov_po_2_implementation` must stop and post
  a `RELAY_QUESTION` rather than guess.
- Council was not invoked in this PLAN episode — no genuine
  `GOV_PO_ROLE_MIGRATION.md` §7 trigger applies to planning/drafting alone;
  the recommendation is recorded for movement 1's later freeze episode.
