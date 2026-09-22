# NXS-LOCAL-0368 — Overview rebuilt as an exception-and-evidence screen; collision-free aiview pseudonyms

status: automated_validated · completed: 2026-09-23 · movement: IMPLEMENTATION

Implemented the frozen Overview contract (one aggregate endpoint, cluster DIFF summary ported to Java with a parity test, filtered links into every screen) and made cluster and device pseudonyms unique through a persisted registry. Deployed; aiview acceptance by the Product Owner pending.

## Authority and evidence

- `docs/design/OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md`
- `docs/design/OVERVIEW_EXCEPTION_SCREEN_CONTRACT_FABLE_DRAFT.md`
- `docs/design/OVERVIEW_COUNCIL_2026_09_22_ASTRA.md`
- `docs/design/OVERVIEW_COUNCIL_2026_09_22_FABLE.md`

## Real-environment note

Deployed to HOST-A with `scripts/hosta_deploy.sh` (watched, fail-fast). Screens inspected under the aiview persona; Product Owner acceptance of the Overview is the open gate.
