# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-06, `main` at merge commit `d363b17` (PR #90,
  `DEV.TEST.1` final CI trigger policy). The earlier `6ca67cc`/PR #88
  checkpoint line was stale by two merges and is corrected here.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**, contract
  DRAFT. `now_next.next` is `M4` (`local_control_plane_metadata_store`).
  `op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`, blocked on
  `DEPLOY.1`. `DEV.TEST.1`, `PCP.1`, `M1`, `M2` complete —
  build_history.json.
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` IMPLEMENTED; `OP.2.1` CP
  command gate DRAFTED — CLASS 2 still has **no member**, no adapter,
  unconditional `DENY`. `D-V7b`/`D-F3`/`D-F2` no longer block the readiness
  roll-up (`OP.2.1b`, see "Active build") — remaining `CLASS 2` blockers are
  authorization/trust/adapter/change-management, not readiness.
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

**`nav_3_capability_state_vocabulary`** (`M3`) — **IN_PROGRESS, revision 5.**
Contract: `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` —
**DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED**; authorizes no
implementation, not citable as design authority. Dimensions `D1`–`D7`, gates
`E1`–`E7`, inputs `I1`–`I20`, `AC-CS-1`…`97` (no id renumbered or deleted).

**Revision 5** — defects `X40`–`X49`. The result is now a **tagged union**:
`RESOLVED{primary_status, capability_qualifiers, evidence_presentation,
action_affordance}` or `OMITTED{reason, diagnostic}`, and `NOT_SHIPPED` is a
`SurfaceOmissionReason`, not a `CapabilityState` (nine resolved-only states).
The fourth output is a **presentation-time** `action_affordance[]`
(`AVAILABLE_FOR_SUBMISSION` / `DISABLED_KNOWN_BLOCKER` / `UNDETERMINED`) that
**never claims an `E7` check has passed** and is never an execution grant; both
`AC-ST-4` registry checks stay at their own phases. `E4` is **total** via
`NO_APPLICABLE_AUTHORITY` — non-blocking and **not a grant**. `CX1` is
subject-scoped, so targetless actions are unaffected. `RI-1` (was `CX2`)
comparability is total over `K1`–`K6`; `CX2`/`CX3` are renamed **bounded
inconsistencies**, so danger stays reserved for `CX1` and `AC-CS-47` needs no
parent amendment. H-cases use **real registry action ids** — verified that
`enroll_device` is **not** in `JOB_REGISTRY`, so the enrollment example now
produces no action entry rather than inventing an authority. The persistable
capability projection is separated from the composed presentation resolution.

`PO-NAV-7` resolved **in contract**: capability policy → `D5` `POLICY_DISABLED`
(`A5`, `M12`); "not enrolled" → `D4` `EVIDENCE_ONLY` (`A6`, `M10`). The job
lifecycle, `ActionState`, taxonomy, compliance, discovery-lifecycle and
registry vocabularies are **byte-unchanged**.

**Recorded, not applied:** nine frozen-parent corrections `FA-1`…`FA-9`
(§2.7), each owned by a named PO decision. **No frozen file was edited.**
**Six open PO decisions** `PO-M3-1`…`6` (§11.2) gate the freeze.

## `OP.0b.0` — FROZEN WITH REAL-ENV VALIDATION GATES

`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
is implementation authority for the bounded S0–S9 slice sequence it defines
— citable for command/schema/identity-model *interpretation*, but **still
authorizes no CLASS 2 action**. `D-V4`/`D-V7a` are `CLOSED_BY_DOCS`. Every
other row's minimal safe interpretation is frozen — `D-V1`/`D-V2`
(field-binding, fail-closed predicates); `D-V5a`/`D-V5b`; `D-V6` (pnote
via `-ia list`); `D-V9a`/`D-V9b`. **`D-V3a`/`D-V7b` stay `STILL_UNKNOWN`**
as vendor facts — `D-V3a` (PAN) still scoped as a CLASS-2-time blocker;
`D-V7b` (CP) no longer blocks the readiness roll-up at all (`OP.2.1b`,
2026-09-05: advisory-exempt, see "Active build") even though the
underlying vendor question is unchanged. `D-F3` (flap threshold) is
**DECIDED** (2026-09-05): no threshold invented, advisory-exempt
permanently, both vendors. `D-V8` remains open, non-blocking. Full
reasoning: `project/roadmap.json` `open_decisions`.

## PAN HA serial evidence

The approved real PAN pair's S0 result: one member's `self_identity_
consistent`/`runtime_peer_serial_state` are `MATCH`, the other's both
`MISMATCH`. **B2 bidirectional corroboration: NOT ESTABLISHED**, root cause
**UNKNOWN** (representation divergence / genuine discrepancy / another
mismatch all still possible; whitespace/numeric-conversion ruled out).
Leading-zero normalization **not authorized** (opaque-identifier law).
Tracked as `pan_serial_representation_identity_evidence_closure`. A manual
2026-09-04 `show high-availability all` observation conflicts with the
`MISMATCH` above — not reconciled, B2 stays NOT ESTABLISHED; S8-C separately
established fresh **management-plane** (not serial) correspondence = `MATCH`,
a narrower question never promoted toward B2.

## Exact next build

**Product Owner review of the corrected `M3` draft.** Close `PO-M3-1`…`6`
(§11.2) and rule on the nine prepared frozen-parent corrections `FA-1`…`FA-9`
(§2.7), each of which names its owning decision. `FA-*` amend a FROZEN document
and cannot be applied by an agent; if approval is withheld the named acceptance
criteria are **blocked** and the authority conflict stands — implementers are
not sent back to semantics the draft demonstrated false, and the draft is not
silently rewritten. `M3` stays `in_progress`.

`now_next.next` is **`local_control_plane_metadata_store`** (`M4`): local
SQLite control-plane metadata only, additively, inside the companion contract's
§6.4 ownership boundary and §6.5 engine contract (`AC-ST-1`…`AC-ST-8` frozen).
Independent of `M3`'s outcome; needs its own separate authorization to start.

Deferred detail remains **implementation-contract work inside a frozen
direction**: SQLite schema at `M4`, trust mechanics at `M8`, enrollment schemas
at `M9`. `M5` stays the critical path — **every collection job type today is
`target_mode="none"`**. `op2_c_cp_clusterxl_adapter_scoping` stays
`upcoming`/blocked with its notes in `project/roadmap.json`; `OP.2.D`'s console
flow is expected on the `PCP.4` device/HA tab — one console, never two.

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | architecture FROZEN; readiness no longer blocks (`OP.2.1b`); CP ClusterXL adapter (`OP.2.C`), its real `ClusterXLMemberSession` transport, and its real `PreflightProvider`/`EligibilityEvaluator` now all IMPLEMENTED + unit-tested, all unwired — change-management/network-security review now DRAFTED but unsigned (`docs/history/phase/OP_2_C_CHANGE_MANAGEMENT_NETWORK_SECURITY_REVIEW.md`) — blocked on `DEPLOY.1A`/`OPERATE`, SSH trust hardening, this review's sign-off, a protected entry point | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-env evidence.

## Real-environment validation owed

- **`CON.2`** — trigger a `read`-class job from the console against a real device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial evidence" above; its own next technical movement, not folded in here.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence, server-blocked.

## Automated test baseline

```
M3 revision 5 focused (contract/docs-only movement):
  tests/test_architecture_convergence.py            20 passed, 0 failed
  tests/test_navigation_information_architecture.py 16 passed, 4 skipped
  combined                                          36 passed, 4 skipped, 0 failed
  metadata_warnings == []; build-history index --check clean; privacy gate
  PASS / 0 findings; git diff --check clean; source/test/script/workflow/
  dependency trees untouched; rendered-structure audit 0 failures;
  stale-token audit clean; AC continuity and PO-to-FA mapping verified.
  These validate repository consistency only -- no resolver exists yet, so
  every resolver acceptance criterion stays unexercised until its owning
  movement implements it. No full local suite, no GitHub full regression.
Last full parallel suite (DEV.TEST.1, unchanged by M3): local `-n auto --dist
  worksteal` (4 CPUs) = 1957 passed, 24 skipped, 0 failed, two clean runs.
  GitHub Actions one-time proof (run 34020356372): 1944 passed / 38 skipped /
  0 failed, 275.74s vs 11m56s serial. Detail: project/build_history.json.
Repository privacy gate: PASS / 0 findings.
```
## Known xfails

None currently known (the two tracked earlier became passing regressions in
`0.6.6A`; record here if either resurfaces).

## Production posture

Development-ready, **not** production-ready. The container runs as root by
design at this stage. Open before any production claim: OIDC/RBAC, trusted
TLS/SSH in production, database role separation, report-only publication
surface, secret management, off-host recovery custody with a restore drill,
audit retention. `.github/workflows/validation.yml` is the deterministic CI
gate (fast PR `validate`, automatic; parallel `full-regression` via
`workflow_dispatch` only, `DEV.TEST.1`); it runs no device, container or
registry step.
