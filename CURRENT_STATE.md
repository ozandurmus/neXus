# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-03, branch `claude/op0b-s4-command-gate-6pyv2y`
  (base `main`).
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op0b_s4_command_gate_package` — **IN_PROGRESS**. `OP.0b` S4: the
  `OP.0b.1` network-device command-gate package, docs only — see "Active
  build" below. `cp_remote_collection_done_marker_diagnostics` moved to
  `now_next.upcoming` (still `IN_PROGRESS`, stalled pending a real-device
  recurrence; independent subsystem, does not block S4/S5/S6).
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
| 2 — operational state change (failover) | **no member exists** | hard-gated, `FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1 |
| 3 — configuration write | prohibited | — |
| 4 — policy / deployment / remediation | prohibited | — |

## Active build

**`op0b_s4_command_gate_package`** — **IN_PROGRESS**, 2026-09-03. `OP.0b`
S4: docs-only network-device command gate for the FROZEN `OP.0b.0`
candidate battery (CP `A4`–`A9`, PAN `P3`–`P5`) —
`docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` (new, status `DRAFT`
— **pending explicit product-owner approval**; this build does not
self-approve). CP `A4`/`A5`/`A6`/`A7`/`A8`/`B1` → `APPROVED_FOR_S5`;
`A10`/`A11` → `OPTIONAL_APPROVED`; `A9` (management-plane preemption
attribute) → `DEFERRED_UNKNOWN` (`D-V7b`, `CP-3`, P0 before `CLASS 2`).
PAN `P4` → `APPROVED_FOR_S6`; `P3` → `OPTIONAL_APPROVED`; `P5` →
`DEFERRED_UNKNOWN` (command syntax only `PARTIAL`ly confirmed). All 7
known mutating operations stay `REJECTED`. No command implementation, no
device I/O. New `tests/test_op0b_s4_command_gate.py` (6 tests) +
`tests/test_architecture_convergence.py` — 26 passed locally. Full detail
+ PO approval package: the gate doc above and `project/build_history.json`.
Stays `IN_PROGRESS` until PR/CI evidence lands **and** independently
cannot merge/finalize before the doc's own "Approval record" is signed.

**Stalled, moved to `now_next.upcoming`:**
`cp_remote_collection_done_marker_diagnostics` — still `IN_PROGRESS`,
independent subsystem, does not block `OP.0b`. Root cause of a real
`RuntimeError('CP remote collection ended without DONE marker')` remains
`UNKNOWN`; a stderr classifier (`_classify_stderr_sample`) now reports a
safe category token instead of a bare byte count. Resume when a real
recurrence report with the new diagnostic fields is available. Detail:
`project/build_history.json`.

**Predecessors:** `dev_kaizen_fast_pr_ci` (AUTOMATED_VALIDATED, PR #38, CI
split into `validate`/`full-regression`), `op0b_s3_cp_parse_scope_extension`
(AUTOMATED_VALIDATED, PR #37), `op0b_s2_pan_parse_scope_extension`
(AUTOMATED_VALIDATED, PR #36), `op0b_s1_preflight_fact_provenance_model`
(AUTOMATED_VALIDATED). Detail: `project/build_history.json`.

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
`project/roadmap.json` `open_decisions`; contract §"Final semantic blocker
closure — session 4".

## PAN HA serial evidence

The approved real PAN pair's S0 result: both devices were directly
identity-gated successfully; one member's `self_identity_consistent` and
`runtime_peer_serial_state` are `MATCH`, the other's are both `MISMATCH`.
**B2 bidirectional corroboration: NOT ESTABLISHED.** Root cause: **UNKNOWN**
— representation divergence, a genuine runtime discrepancy, and another
semantic mismatch are all still possible; whitespace/numeric-conversion
causes are ruled out by source inspection. Leading-zero normalization is
**not authorized** (`AGENTS.md` opaque-identifier law). Tracked as
`project/backlog.json` `pan_serial_representation_identity_evidence_closure`.

## Exact next build

`cp_remote_collection_done_marker_diagnostics` (`now_next.upcoming`) needs a
real recurrence with the new diagnostic fields — independent of `OP.0b`.

`now_next.next` is **`op0b_s5_cp_preflight_collector`** (`OP.0b` S5) —
**blocked** on explicit product-owner approval of
`docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` ("Approval record",
currently empty). Once approved: S5 (CP) and S6 (PAN) dedicated preflight
collectors run parallel, per dependency order `S0 → S1 → (S2, S3) → S4 →
(S5, S6) → S7 → S8; S9 independent after S7`. Recommended: `Sonnet 5,
extended thinking (high)` for collector session design; `Sonnet 5, normal`
for wiring the already-proven `S1`/`S2`/`S3` extraction/projection seams.

`D-V3a`/`D-V7b` stay **preserved unresolved CLASS-2 blockers** (`upcoming`,
not reopened/closed — gate only the PAN successor identity model and
CLASS 2, not S1–S9/S4/S5/S6). Three further independent `upcoming`
movements, any order: **A.** close `D-V3a`/`D-V7b` — GitHub-mirror first,
then human-assisted fetch (`Sonnet 5, extended thinking high`). **B.**
`D-F3` numeric flap threshold — product-owner call. **C.** PAN serial
identity closure — hardware-blocked (`Sonnet 5, normal` initially).

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| `OP.0b` preflight battery | its command gate (`docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`) is now drafted with a full per-command PO approval package but **not approved** — a product-owner/security sign-off, recorded in that doc's own "Approval record" section; `S5`/`S6` are blocked on it | decision |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite, incl. `DEPLOY.1A` OIDC + an RBAC `OPERATE` role | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-environment
evidence.

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
1215 passed / 24 skipped / 0 failed (2026-09-03, dev_kaizen_fast_pr_ci,
  local full-dependency container, serial); 1210/24/0 on this branch
  before the merge above (one file fewer, pre-#38). Both supersede the
  prior CI baseline of 1153/28/0 (PR #36).
Repository privacy gate: PASS / 0 findings, clean checkout.
Project-state consistency: metadata_warnings == [] under all cross-authority rules.
```

Run one-shot and read from file: `py -m pytest -q > pytest_result.log 2>&1`.

**Run the suite serially at least once before closing a build.** A green
parallel run is not evidence of an isolated suite. Delete gitignored
`data/`/`logs/` before the privacy gate — a test run recreates them and the
gate flags them.

## Known xfails

None currently known. (Two previously tracked — VSX network
canonicalization, PAN default-route classification — converted to passing
regressions in `0.6.6A`; record here if either resurfaces.)

## Production posture

Development-ready, **not** production-ready. The container runs as root by
design at this stage. Open before any production claim: OIDC/RBAC, trusted
TLS/SSH in production, database role separation, report-only publication
surface, secret management, off-host recovery custody with a restore drill,
audit retention. `.github/workflows/validation.yml` is the deterministic CI
gate (fast PR `validate` job + `full-regression` on main push/manual
dispatch — see "Active build" above); it runs no device, container or
registry step.
