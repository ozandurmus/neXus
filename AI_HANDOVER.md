# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

## Snapshot (2026-11-14)
main is at e70dbdf1, schema V211. Scheduled jobs are on (per-family switches, V203).
NOW is NXS-LOCAL-0391: four ship batches (V212-V220) are built and tested but not on main.
`CURRENT_STATE.md` and `project/QUEUE.md` win over this file.

## What changed this session
- State reconciled from 0d13c450 to e70dbdf1: builds NXS-LOCAL-0384..0391 recorded through the project tool.
- CURRENT_STATE: product today, release queue (not live), open PO decisions; stale development-mode and failover-trial lines corrected.
- README rewritten as today's product overview; backlog notes for queued work, seven new items; roadmap Next aligned.

## Exact next action
Ship batches 1-4 in order, each after its live migration dry run; then refresh this state.
PO decides: product name, ASA identity ACE narrowing, DB NetworkPolicy conflict, off-host recovery target.
Next after the queue: off-host backup copy and key custody (roadmap and Java plan agree).

## Test delta
None in product code; tools tests and the Java ProjectPlanReaderTest run for the plan data.

## New risks
Migration numbers V212-V220 are reserved by queue order; Flyway runs without outOfOrder, so a lane shipped out of order must be renumbered.
