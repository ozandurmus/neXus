# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-04, branch `claude/pan-real-env-validation-tum9pg`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op0b_s8c_pan_dedicated_ha1_real_env_correction` — **REAL_ENV_VALIDATED**
  (see "Active build"). `now_next.next` = `op0b_s9_ui_authority_reconciliation`,
  confirmed **NOT STARTED**. `cp_remote_collection_done_marker_diagnostics`
  stays `now_next.upcoming`.
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04** (PO, after the independent challenge
  review) — architecture authority only, not a build; CLASS 2 **not
  implemented, not reachable**, no command approved, no adapter, no member.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1`,
  `DEV.4` complete.
- **Product evidence baseline:** `0.6.1B.1.2` interactive CP config
  collection is REAL_ENV_VALIDATED.

## Reading this file

`project/roadmap.json` owns NOW / NEXT / AFTER / BLOCKED / DEFERRED.
`project/feature_registry.json` owns feature delivery state.
`project/backlog.json` owns debt. `project/build_history.json` owns history.
This file owns only the hot checkpoint above and the sections below, and it
must not contradict them — `utils/project_plan._cross_authority_warnings`
plus `tests/test_architecture_convergence.py` fail the build if it does.
Durable engineering/security law is never here — see `AGENTS.md`.

## Safety status — the action taxonomy

`utils/action_taxonomy.py` is the single source of truth; `AI_START_HERE.md`
carries the full table; `AGENTS.md` "Architectural invariants" carries the
test-enforced boundaries. Current numbers:

| Class | Permitted? | Where |
| --- | --- | --- |
| 0 — read | yes | everywhere; most of the product |
| 1 — controlled recovery write | yes, **only** under the `RB.x` contracts | CLI only; never console-submittable |
| 2 — operational state change (failover) | **no member exists**; architecture frozen (`OP.2.0`), not implemented | hard-gated, `FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1/§10.2 |
| 3 — configuration write | prohibited | — |
| 4 — policy / deployment / remediation | prohibited | — |

## Active build

**`op0b_s8c_pan_dedicated_ha1_real_env_correction`** —
**REAL_ENV_VALIDATED**, 2026-09-04. The approved PAN pair uses **dedicated
HA1 addressing**; the preflight's old pre-contact pairing gate
(`peer-ip == peer's management_ip`) never holds for that valid topology and
was circular (refused contact before the evidence that could prove
correspondence could be collected) — **REAL_ENV_DISPROVEN** as a universal
invariant. Corrected: bounded candidate resolution from the explicit
selector alone, then fresh post-contact management-plane correspondence
(`MATCH`/`MISMATCH`/`MISSING`/`NOT_EVALUABLE`/`AMBIGUOUS`) from already-
collected P1/P2 evidence — descriptive only, never gates a check, never
establishes PAN B2; `_derive_pan_units`'s stored-telemetry pairing
unchanged. Same-day UI fix: no more tripled PAN report row per invocation.
No new API operation, mutation, or readiness-contract change. Detail:
`docs/history/phase/OP_0B_S8C_PAN_DEDICATED_HA1_REAL_ENV_CORRECTION.md`.

**S8-A CP ClusterXL: PASS, PO-accepted. S8-B'' VSX (VSLS per-VSID) and S8-C
PAN: both REAL_ENV_VALIDATED** — S8-C: 2/2 candidates, P1/P2/P4 success
both members, 5/7 checks real PASS (the other two correctly
INSUFFICIENT_EVIDENCE by unconfigured feature / open D-F3, not defects),
pair correspondence `MATCH`, PAN B2 stays **NOT ESTABLISHED**.

**OP.0b closure: read-only S1–S8 scope CLOSED, 2026-09-04.** S9 (UI
authority reconciliation — `static/inventory_ui.js`, `utils/merge.py`,
`utils/config_ui.py`) is the one remaining slice, confirmed **NOT
STARTED** — now `now_next.next`. `D-V3a`/`D-V7b`/`D-F3`/PAN B2/CLASS-2
gates remain correctly, intentionally open — full classification in the
S8-C phase doc's "OP.0b closure assessment".

**Stalled, `now_next.upcoming`:** `cp_remote_collection_done_marker_diagnostics`
— independent, does not block `OP.0b`; resume on a real recurrence.

**Predecessors:** `op0b_s8a_clusterxl_execution_model_console_parity`
through `op0b_s1_preflight_fact_provenance_model` — REAL_ENV_VALIDATED /
AUTOMATED_VALIDATED; `project/build_history.json`.

## `OP.0b.0` — FROZEN WITH REAL-ENV VALIDATION GATES

`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md` is
now cleared as implementation authority for the bounded S0–S9 slice sequence
it already defines — citable for command/schema/identity-model
*interpretation*, but **still authorizes no CLASS 2 action** (P4 invariant
unchanged; `OP.0b.1` command-gate package still separately required before
any command is approved). `D-V4`/`D-V7a` are `CLOSED_BY_DOCS`. Every other
row's minimal safe interpretation is explicitly frozen — `D-V1`/`D-V2`
(field-binding confirmed, fail-closed predicates); `D-V5a` (minimal
count/reason/time contract) / `D-V5b` (not load-bearing, dropped); `D-V6`
(pnote problem/no-problem via `-ia list`); `D-V9a` (already-frozen non-VS0
rule) / `D-V9b` (real-env, non-blocking either way). **`D-V3a`/`D-V7b` stay
genuinely `STILL_UNKNOWN`** but were already scoped by the contract's own
pre-existing text as **CLASS-2-time blockers, not architecture blockers**.
New non-blocking decision: `D-F3` (flap/failover threshold, parallel to
`D-F1`/`D-F2`). `D-V8` remains open, non-blocking. Full reasoning:
`project/roadmap.json` `open_decisions`; contract §"Final semantic blocker closure — session 4".

## PAN HA serial evidence

The approved real PAN pair's S0 result: both devices were directly
identity-gated successfully; one member's `self_identity_consistent` and
`runtime_peer_serial_state` are `MATCH`, the other's are both `MISMATCH`.
**B2 bidirectional corroboration: NOT ESTABLISHED.** Root cause: **UNKNOWN**
— representation divergence, a genuine runtime discrepancy, and another
semantic mismatch are all still possible; whitespace/numeric-conversion
causes are ruled out by source inspection. Leading-zero normalization is
**not authorized** (opaque-identifier law). Tracked as `project/backlog.json`
`pan_serial_representation_identity_evidence_closure`.

**2026-09-04 addendum:** a manual (non-authoritative) `show
high-availability all` observation appears to conflict with the `MISMATCH`
above — not reconciled, B2 stays NOT ESTABLISHED. S8-C separately
established genuine fresh **management-plane** (not serial) correspondence
= `MATCH`, a narrower question never promoted toward B2 — see the S8-C
phase doc.

## Exact next build

`cp_remote_collection_done_marker_diagnostics` (`now_next.upcoming`) needs a
real recurrence with the new diagnostic fields — independent of `OP.0b`.
`now_next.next` is **`op0b_s9_ui_authority_reconciliation`** — client-side
PAN/CP pairing + HA vocabulary heuristics (`static/inventory_ui.js`,
`utils/merge.py`, `utils/config_ui.py`) still bypass the canonical
`compute_ha_readiness` evaluator; confirmed NOT STARTED. `OP.0b`'s
read-only S1–S8 scope is **CLOSED**; the whole build is not DONE until S9
lands. Backlog (PO request): `cp_preflight_ccp_tablestat_evidence` — a NEW
command, gate row + readiness mapping required first.

`D-V3a`/`D-V7b` stay preserved unresolved CLASS-2 blockers, permitted open
by the frozen contract. Independent `upcoming` movements, any order:
**A.** `D-V3a`/`D-V7b` closure (GitHub-mirror then human-fetch). **B.** `D-F3`
flap threshold — product-owner call. **C.** PAN serial identity closure —
hardware-blocked, in tension with a manual 2026-09-04 observation (see
above), not reconciled. **D.** S9 (`now_next.next`).

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | architecture FROZEN 2026-09-04 (`OP.2.0`); implementation blocked on every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite incl. `OP.2.1`, `DEPLOY.1A` OIDC + `OPERATE` role | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-environment evidence.

## Real-environment validation owed

- **`CON.2`** — trigger a `read`-class job from the console against a real
  device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — one real-device confirmation that `ha_cluster_mode`
  resolves rather than falling back to `"unknown"`. Fixture-drift check, not
  a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial
  evidence" above; tracked as its own next technical movement (B), not
  folded into an unrelated build.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-a-real-MDS evidence for the
  Postgres advisory-lock path. Server-blocked.

## Automated test baseline

```
1681 passed / 26 skipped / 0 failed (2026-09-04,
  op0b_s8c_pan_dedicated_ha1_real_env_correction, serial) -- over the
  1630/24/0 baseline, from the PAN dedicated-HA1 correction (20 new/changed
  tests, tests/test_op0b_s7_readiness_v2.py + tests/test_op0b_s75_preflight_entrypoint.py).
Repository privacy gate: PASS / 0 findings, clean checkout.
Project-state consistency: metadata_warnings == [] under all cross-authority rules.
```

Run one-shot and read from file: `py -m pytest -q > pytest_result.log 2>&1`;
serially at least once before closing a build; delete gitignored
`data/`/`logs/` before the privacy gate (`AI_START_HERE.md` "Validation ladder").

## Known xfails

None currently known (the two tracked earlier became passing regressions in
`0.6.6A`; record here if either resurfaces).

## Production posture

Development-ready, **not** production-ready. The container runs as root by
design at this stage. Open before any production claim: OIDC/RBAC, trusted
TLS/SSH in production, database role separation, report-only publication
surface, secret management, off-host recovery custody with a restore drill,
audit retention. `.github/workflows/validation.yml` is the deterministic CI
gate (fast PR `validate` job + `full-regression` on main push/manual
dispatch — see "Active build" above); it runs no device, container or
registry step.
