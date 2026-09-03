# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-03, branch `main` (merged via PRs #34/#35/#36/#37).
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op0b_s3_cp_parse_scope_extension` — **AUTOMATED_VALIDATED** (PR #37's CI
  confirmed the full suite green against the exact merged head — see
  "Active build" below). Third bounded slice against the FROZEN `OP.0b.0`
  contract: extends
  `configuration/checkpoint_config_collector.py` to parse more of the
  already-fetched `cphaprob stat` buffer (opt-in, dormant by design —
  production invocation is S5/S6's job), plus a pure projection module.
  Zero device I/O, zero new command, no readiness-verdict/UI change;
  `D-F1`/`D-F2`/`D-F3`/`D-V3a`/`D-V7b` stay unresolved; CLASS 2 unreachable.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1`,
  `DEV.4` complete.
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point
  configuration collection is REAL_ENV_VALIDATED.

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

**`op0b_s3_cp_parse_scope_extension`** — **AUTOMATED_VALIDATED** 2026-09-03.
New `_parse_clusterxl_stat_preflight_fields()` reads non-local member-row
State (peer observation, address/name excluded) and a local failure/attention
boolean (reclassified role token — no free-text "Active Attention" reason
parsed, no safe vocabulary confirmed) from the **same** already-fetched
`cphaprob stat` buffer, behind `include_preflight_fields=False` on
`_collect_host` (dormant by design, S5/S6's job to invoke). Cluster-mode
parser gained `vsx_single_vs_failover` (sk112712), active immediately (not
opt-in). New pure `checkpoint/cp_preflight_projection.py`. Contract §25
updated; new §25b mirrors S2's §25a reconciliation. **PR #37's CI**
(https://github.com/ozandurmus/neXus/actions/runs/33753129757) ran the
`validation` workflow with the real dependency set against this exact head:
**conclusion=success**, mergeable_state=clean, zero open review threads.
Zero device I/O, zero new command; `D-F1`/`D-F2`/`D-F3`/`D-V3a`/`D-V7b`
unresolved; CLASS 2 unreachable.

**Predecessors:** `op0b_s2_pan_parse_scope_extension` (AUTOMATED_VALIDATED,
PR #36) and `op0b_s1_preflight_fact_provenance_model` (AUTOMATED_VALIDATED).
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
`project/roadmap.json` `open_decisions`; contract §"Final semantic blocker
closure — session 4".

## PAN HA serial evidence

The approved real PAN pair's S0 result: both exact selected PAN devices were
directly identity-gated successfully; runtime local serial evidence and
runtime peer serial claim were present on both. One member —
`self_identity_consistent = MATCH`, `runtime_peer_serial_state = MATCH`. The
other member — `self_identity_consistent = MISMATCH`,
`runtime_peer_serial_state = MISMATCH`. **B2 bidirectional corroboration:
NOT ESTABLISHED.** Root cause of the mismatching member: **UNKNOWN** — the
current persisted diagnostic cannot distinguish representation divergence, a
genuine runtime identity discrepancy, or another semantic mismatch.
Whitespace difference and parser numeric conversion are both ruled out by
source inspection. Leading-zero normalization is **not authorized**; the
identifier stays opaque (`AGENTS.md` opaque-identifier law). Tracked as
`project/backlog.json` `pan_serial_representation_identity_evidence_closure`.

## Exact next build

`OP.0b` S3 (above) needs PR CI evidence before it can advance to
`AUTOMATED_VALIDATED`; once that lands, three further independent
movements remain, any order/parallel (full detail in `project/roadmap.json`
`now_next.next`/`upcoming`):

**A. Close `D-V3a`/`D-V7b` before CLASS 2** (`now_next.next`) — does not
block `S1`–`S9` or the freeze. GitHub-mirror search first, then
human-assisted fetch. Recommended: `Sonnet 5, extended thinking (high)`.

**B. `D-F3` numeric threshold** (`now_next.upcoming`) — product-owner call,
needed before check 7 computes a real verdict; doesn't block `S1`/`S2`/`S3`.

**C. PAN serial representation/identity evidence closure**
(`now_next.upcoming`) — hardware-blocked, unchanged. `Sonnet 5, normal`
initially; escalate only if vendor identity semantics become architectural.

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| `OP.0b` preflight battery | its command gate is drafted but **not approved** — a product-owner/security call; the `OP.0b.0` evidence contract is now `FROZEN WITH REAL-ENV VALIDATION GATES` (see above), but the `OP.0b.1` gate package that actually approves any command is a separate, still-open step | decision |
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
1153 passed / 28 skipped / 0 failed (2026-09-03, GitHub CI, PR #36
  `validate` job, full dependency set incl. lxml/paramiko/cryptography/
  fastapi) -- current full-dependency-environment baseline, superseding the
  prior 1099/24/0 (2026-09-02). Includes S1 (23) and both S2 suites (20
  extraction + 14 projection) executed for the first time anywhere.
  Locally executable-here subset (no lxml/paramiko/fastapi in this
  container): S1+convergence 42/42, S2 projection 14/14.
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
canonicalization, PAN default-route classification — were converted to
passing regressions in `0.6.6A`; if either resurfaces, record it here with
the build that reintroduced it.)

## Production posture

Development-ready, **not** production-ready. The container runs as root by
design at this stage. Open before any production claim: OIDC/RBAC, trusted
TLS/SSH in production, database role separation, report-only publication
surface, secret management, off-host recovery custody with a restore drill,
audit retention. `.github/workflows/validation.yml` is the deterministic CI
gate; it runs no device, container or registry step.
