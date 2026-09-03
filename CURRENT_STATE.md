# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-02, branch `claude/dev4-state-reconciliation-w3kdl3`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `dev4_ai_engineering_constitution_authority_reconciliation` (`DEV.4`) —
  AUTOMATED_VALIDATED. Documentation/governance only; no product,
  collector, vendor-command, schema, transport or UI behavior changed.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1` complete.
  `DEV.4` (this checkpoint) — AUTOMATED_VALIDATED.
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

**`dev4_ai_engineering_constitution_authority_reconciliation`** (`DEV.4`) —
AI engineering constitution & authority reconciliation — AUTOMATED_VALIDATED
2026-09-02. Collapsed the AI bootstrap/governance surface to a three-file
authority model (`AGENTS.md` constitution, `AI_START_HERE.md` operating
protocol, this file as hot checkpoint) after an audit found real duplication
and two live authority contradictions; fixed by deferring to the higher
authority, never by silently reconciling. Six governance regression tests now
pin the invariants in `tests/test_architecture_convergence.py` — the newest,
added by this checkpoint, machine-enforces that a `DRAFT`/`DO NOT FREEZE`
contract can never back a terminal-status `build_history` record. Detail:
commit `64c8d79`; no dedicated phase doc.

This checkpoint also records, as its own separate `build_history` entries,
two pieces of prior-session work that had been sitting unrecorded in git
only: a bounded, opt-in **PAN HA runtime peer-identity diagnostic**
(`pan_ha_runtime_peer_identity_diagnostic`, AUTOMATED_VALIDATED — no new
device command, no pairing/readiness change) and the **`OP.0b.0` vendor
failover preflight evidence surface contract**
(`op0b_0_vendor_failover_preflight_evidence_surface_contract_draft`,
`in_progress` — the contract document itself is `DRAFT — DO NOT FREEZE`, see
below).

**Predecessor:** `op0a_pan_ha_peer_pairing_identity_closure` (OP.0a.P7
revision) — AUTOMATED_VALIDATED 2026-09-02 (PAN HA peer-pairing identity
closure). Detail: `project/build_history.json`.

## `OP.0b.0` — DRAFT, DO NOT FREEZE

`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md` is
structurally complete (command surface table, configuration/runtime field
trace table, bug/gap register) but is **not** implementation authority — it
must not be cited as approving any command, schema or identity model, and its
`UNKNOWN`s must not be reinterpreted as decided. Blocking its freeze:
`D-V1`, `D-V2`, `D-V3`, `D-V4`, `D-V5`, `D-V6`, `D-V7`, `D-V9`
(`project/roadmap.json` `open_decisions`) — vendor-semantic confirmations
reachable only from an unblocked network (official documentation hosts
returned `CONNECT 403` from the drafting environment's egress proxy) or a
scheduled real-environment measurement. `D-V8` is open but non-blocking.

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

Two independent next technical movements — either order, either in parallel:

**A. `OP.0b.0` OFFICIAL VENDOR SEMANTICS CONFIRMATION** (`now_next.next`) —
from an unblocked network, fetch the official Check Point / Palo Alto
documentation the contract's Open decisions table names for `D-V1`…`D-V7`,
`D-V9`, resolve each `UNKNOWN` as far as documentation allows, then re-run the
contract's own freeze check. Recommended: `Sonnet 5, extended thinking
(high)` (vendor-semantic calls).

**B. PAN SERIAL REPRESENTATION / IDENTITY EVIDENCE CLOSURE**
(`now_next.upcoming`) — determine *why* one member's independently sourced
serial representations do not compare equal, without weakening
opaque-identifier semantics. Not "just obtain B2." Blocked on access to the
same approved real PAN pair (hardware, not a decision gate). Recommended:
`Sonnet 5, normal reasoning` initially; escalate to `extended thinking (high)`
only if vendor identity semantics become architectural.

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| `OP.0b` preflight battery | its command gate is drafted but **not approved** — a product-owner/security call; the `OP.0b.0` evidence contract is `DRAFT — DO NOT FREEZE` pending vendor-doc confirmation (see above) | decision |
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
1099 passed / 24 skipped / 0 failed (2026-09-02, serial, after DEV.4).
  Prior: 1074 / 26 / 0 (op0a_pan_ha_peer_pairing_identity_closure) — the +25
  spans the PAN HA peer-identity diagnostic, the OP.0b.0 draft (doc only, no
  new tests) and DEV.4's six governance tests (five original + this
  checkpoint's draft-authority gate).
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
