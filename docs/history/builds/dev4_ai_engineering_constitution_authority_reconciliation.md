# dev4_ai_engineering_constitution_authority_reconciliation — DEV.4 - AI engineering constitution & authority reconciliation

## Summary

Collapsed the AI bootstrap/governance surface to a three-file authority model (AGENTS.md constitution, AI_START_HERE.md operating protocol, CURRENT_STATE.md hot checkpoint) after an audit found real duplication and two live authority contradictions (a stale DEV.1 pointer, and CURRENT_STATE.md naming a different active build than roadmap.json); both fixed by deferring to the higher authority rather than silently reconciling. Documentation/governance only -- no product code, collector, vendor command, schema, transport or UI behavior changed.

## Evidence

tests/test_architecture_convergence.py grew five governance tests (AI_HANDOVER.md non-authoritative banner; stale no-device-write-automation claim never reappearing; opaque-identifier law present in AGENTS.md; command gate + validation-tier vocabulary documented; evidence-identity/readiness-authorization phrasing present). This STATE_UPDATE added a sixth, generic one: a DRAFT/DO NOT FREEZE contract doc can never back a terminal-status build_history record. Full regression: 1099 passed / 24 skipped / 0 failed, serial. Repository privacy gate PASS / 0 findings.

## Risks forward

None to product/runtime/network behavior -- this build touched only governance docs and tests. OP.0b.0 remains DRAFT -- DO NOT FREEZE and untouched by this build; the PAN HA runtime-serial B2 investigation remains unresolved, also untouched. Rollback: revert 64c8d79.
