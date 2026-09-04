# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-04, branch `main` (S8-A ClusterXL closure merged).
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op0b_s8a_clusterxl_execution_model_console_parity` —
  **REAL_ENV_VALIDATED** on the approved CP ClusterXL pair (see "Active
  build"). `now_next.next` = `op0b_s8_real_env_validation` — S8-A **PASS**
  (PO-accepted); S8-B VSX / S8-C PAN NOT EXECUTED, operator-run.
  `cp_remote_collection_done_marker_diagnostics` stays `now_next.upcoming`
  (`IN_PROGRESS`).
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

**`op0b_s8a_clusterxl_execution_model_console_parity`** —
**REAL_ENV_VALIDATED**, 2026-09-04. The live S8-A campaign proved the CP
preflight's remote execution primitive wrong — one non-interactive exec
channel per read, each dispatched through the Gaia CLI wrapper, so the five
bare Expert reads never executed — and corrected it: one persistent Expert
shell per member (existing `InteractiveSshSession`), reads framed by a
per-session `echo` of `$?`, `INTER_COMMAND_DELAY_SECONDS = 0.3` strictly
between completed reads (#55/#56). Real output shapes recognised
fail-closed: `fw stat` table, `cphaprob -a if` annotated rows, `cphaprob -ia
list` healthy sentence, A8 count wording (#52/#56/#60); an unrecognised
shape now reports a value-free layout skeleton (#58). The operator report is
regenerated from the SAME canonical readiness record the CLI prints — one
evaluation, two renderers, no snapshot persistence, no TTL, UI
projection-only (#59). Withdrawn: the predecessor's `$PATH`/PTY and
account-shell diagnoses and its exec-channel model. Detail: `project/build_history.json`.

**S8-A (CP ClusterXL pair): PASS, PO-accepted.** 8/8 reads success; four
checks PASS; `preemption_known` D-V7b and `flap_history` D-F3
INSUFFICIENT_EVIDENCE by decision; Operator Console identical to the CLI
for all seven checks, MODE the fresh ClusterXL mode. **S8-B VSX / S8-C
PAN** (`now_next.next`): NOT EXECUTED — operator-run, same seam, same
parity law.

**Stalled, moved to `now_next.upcoming`:**
`cp_remote_collection_done_marker_diagnostics` — still `IN_PROGRESS`,
independent subsystem, does not block `OP.0b`. Root cause of a real
`RuntimeError('CP remote collection ended without DONE marker')` remains
`UNKNOWN`; a stderr classifier (`_classify_stderr_sample`) now reports a
safe category token instead of a bare byte count. Resume when a real
recurrence report with the new diagnostic fields is available. Detail:
`project/build_history.json`.

**Predecessors:** `op0b_s75_preflight_entrypoint` through
`op0b_s1_preflight_fact_provenance_model` — all AUTOMATED_VALIDATED.
Detail: `project/build_history.json`.

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

## Exact next build

`cp_remote_collection_done_marker_diagnostics` (`now_next.upcoming`) needs a
real recurrence with the new diagnostic fields — independent of `OP.0b`.
`now_next.next` is **`op0b_s8_real_env_validation`** — S8-A done; remaining
**S8-B** (approved VSX pair: `--cp-ha-preflight-check`, B1 in the same
Expert shell, no reconnect per VSID) and **S8-C** (approved PAN pair:
`--pan-ha-preflight-check`, one API context per member, P1/P2/P4 only).
Each validates fresh backend readiness AND Operator-Console parity through
the same seam. Operator-executed, SAFE counts only. `OP.0b` closure law:
fresh CLI readiness must equal Operator-Console readiness for the same
invocation. `OP.0b` is **not DONE**; S7 is `REAL_ENV_VALIDATED` for
ClusterXL only. Backlog (PO request): `cp_preflight_ccp_tablestat_evidence`
— a NEW command, gate row + readiness mapping required first.

`D-V3a`/`D-V7b` stay **preserved unresolved CLASS-2 blockers** (`upcoming`).
Independent `upcoming` movements, any order: **A.** close `D-V3a`/`D-V7b` —
GitHub-mirror first, then human-assisted fetch (`Sonnet 5, extended thinking
high`). **B.** `D-F3` flap threshold — product-owner call (blocks check 7 for
both vendors). **C.** PAN serial identity closure — hardware-blocked.

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite, incl. `DEPLOY.1A` OIDC + an RBAC `OPERATE` role | multiple |
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
1630 passed / 24 skipped / 0 failed (2026-09-04,
  op0b_s8a_clusterxl_execution_model_console_parity, local sandbox, serial)
  -- over the op0b_s75_preflight_entrypoint baseline of 1468/24/0, from ten
  S8 regression files (trust, retry, A3 differential, session architecture,
  persistent-shell framing, real CLI path, real output shapes, layout
  diagnostic, capability-gap/pacing, console parity).
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
