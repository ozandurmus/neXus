# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-02. Branch `claude/pan-ha-peer-identity-mvlndf`.
- Product baseline `0.7.7`; engineering `DEV.3.3` — both unchanged.
- This session: **`DEV.4` — AI engineering constitution & authority
  reconciliation** — `DOCS`/governance movement. Documentation/governance
  only; no product code, collector, schema, or readiness-verdict change.

## 2. What changed this session

Consolidated the AI bootstrap/governance surface to the three-file model
(`AGENTS.md` = constitution, `AI_START_HERE.md` = operating protocol,
`CURRENT_STATE.md` = hot state only) after a full audit found real
duplication and two live authority contradictions. Also trimmed
`.github/copilot-instructions.md` and all six `.github/instructions/*` files
to path-scoped deltas pointing at `AGENTS.md`; enriched
`PRIVACY_AND_DATA_HANDLING.md` with the full RFC 5737 range set so
`tests.instructions.md` has one detail-owner; fixed a stale
`PROJECT_VISION.md` "read-only" claim to taxonomy-aware language; and added
five governance-invariant tests to `tests/test_architecture_convergence.py`.
See the DEV.4 audit and session-close report in this session's transcript
for the complete before/after; durable content lives in the files
themselves now, not here.

## 3. Exact next action

Two independent threads, neither touched by DEV.4:

1. **PAN HA peer identity** — the `OP.0b.0` contract
   (`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`,
   `DRAFT — DO NOT FREEZE`) needs its blocking rows (D-V1…D-V7, D-V9)
   confirmed against official vendor documentation from an unblocked
   network, then the PAN runtime-serial `B2` real-env result. Do not freeze
   or implement it from this session.
2. **Project metadata catch-up** (flagged, not fixed, by DEV.4) — the PAN
   runtime peer-identity diagnostic slice and the `OP.0b.0` contract itself
   are not yet recorded in `project/roadmap.json`/`build_history.json`; see
   `CURRENT_STATE.md`'s checkpoint note.

## 4. Test delta

See `CURRENT_STATE.md` "Automated test baseline" for the authoritative
numbers — this file does not duplicate them.

## 5. New risks / debt

None introduced by DEV.4. Pre-existing, unresolved: the `OP.0b.0` bug/gap
register (`P0`/`P1` rows), and the project-metadata catch-up item above.

## 6. Continue or fresh chat

Either is fine. `AI_START_HERE.md` → `CURRENT_STATE.md` → this file is
sufficient either way; no code context from this session is required for
either next-action thread.

## 7. main.py / UI effect

None. This was a documentation/governance-only build; no CLI flag, payload,
schema, or UI surface changed.
