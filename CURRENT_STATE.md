# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-03, branch `claude/vendor-semantics-confirmation-pt2iiq`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op0b_s1_preflight_fact_provenance_model` — AUTOMATED_VALIDATED. First
  bounded implementation slice against the FROZEN `OP.0b.0` contract:
  `utils/failover/preflight_model.py` (new) — pure fact/provenance
  dataclasses, evidence taxonomy, deterministic same-run coherence. Zero
  device I/O, zero network, no collector/parser/readiness-verdict/schema/UI
  change; CLASS 2 stays structurally unreachable (P4 invariant, unchanged).
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

**`op0b_s1_preflight_fact_provenance_model`** — AUTOMATED_VALIDATED
2026-09-03. First implementation slice against the FROZEN `OP.0b.0`
contract. New `utils/failover/preflight_model.py`: frozen `PreflightFact`/
`Provenance`/`PreflightMemberEvidence`/`PreflightSnapshot` dataclasses; the
13-category evidence taxonomy (A–M); `evaluate_coherence()` — deterministic
same-preflight-run coherence over categories D/E/F/G/J/K, member-skew
computed only when timestamps parse (never a fake zero). Encodes the frozen
contract's domain invariants without reinterpreting them; picks no
`D-F1`/`D-F2`/`D-F3` numeric threshold. 23 targeted tests, all passing.
Resolved the flagged S0-vs-S1 ordering question by proceeding — S1 needed
no device I/O and no S0 result. Two pre-existing structural tests (OP.0a
decision P5's `utils/failover/` file-count check) updated, narrowly, to
allow exactly this one contract-named addition; P5's actual boundary (no
write-capable surface) is unaffected and re-verified. Zero device I/O, zero
network, no collector/parser/readiness-verdict/schema/UI change; CLASS 2
stays structurally unreachable (P4 invariant, unchanged).

**Predecessor:** `op0b_0_final_semantic_blocker_closure_freeze` —
AUTOMATED_VALIDATED 2026-09-03, session 4 (`OP.0b.0` moved `DRAFT — DO NOT
FREEZE` → `FREEZE WITH REAL-ENV VALIDATION GATES`). Detail:
`project/build_history.json`.

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

Four independent movements, any order/parallel (full detail in
`project/roadmap.json` `now_next.next`/`upcoming`):

**A. `OP.0b` S1 — preflight fact + provenance model** (`now_next.next`) —
pure, no I/O; first implementation slice against the frozen contract.
Flagged not resolved: the contract lists `S0 → S1` but `S1` needs no device
I/O while `S0` is hardware-blocked — confirm with product owner whether `S1`
may go first. Recommended: `Sonnet 5, normal`.

**B. Close `D-V3a`/`D-V7b` before CLASS 2** (`now_next.upcoming`) — does not
block `S1`–`S9` or the freeze. GitHub-mirror search first, then
human-assisted fetch. Recommended: `Sonnet 5, extended thinking (high)`.

**C. `D-F3` numeric threshold** (`now_next.upcoming`) — product-owner call,
needed before check 7 computes a real verdict; doesn't block `S1`.

**D. PAN serial representation/identity evidence closure**
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
1099 passed / 24 skipped / 0 failed (2026-09-02, serial, after DEV.4) — last
  full-dependency-environment baseline. +23 targeted tests since
  (op0b_s1_preflight_fact_provenance_model, 2026-09-03) not yet folded into
  a full re-run: this container lacks lxml/cryptography/paramiko/fastapi, so
  only the targeted+convergence suite (42/42) and a tolerant full pass
  (510 passed / 17 skipped / 33 failed / 81 errors — failed/error counts
  unchanged from the prior session, all pre-existing missing-dependency
  gaps) could be run here.
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
