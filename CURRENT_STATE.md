# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-03, branch `claude/vendor-semantics-confirmation-pt2iiq`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op0b_0_official_vendor_semantics_confirmation_pass2` — IN_PROGRESS.
  Third documentation-only vendor-semantics confirmation pass against the
  `OP.0b.0` draft, this one closing two rows outright; no product,
  collector, vendor-command, schema, transport or UI behavior changed; no
  device contacted.
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

**`op0b_0_official_vendor_semantics_confirmation_pass2`** — Source Pack 2 —
IN_PROGRESS 2026-09-03. Third session against `OP.0b.0`'s blocking rows.
`pan.dev`/`sc1.checkpoint.com`/`support.checkpoint.com` stayed
`EGRESS_BLOCKED` again, but `github.com` is reachable — reading the official
PaloAltoNetworks `pan-os-upgrade-assurance` source **verbatim** closed
`D-V4`; Check Point search snippets closed `D-V7a` — first two full closures
across three sessions. `D-V1`/`D-V2`/`D-V6` strengthened; `D-V5`/`D-V7` split
into `a`/`b`; a source-pack hypothesis on `D-V6` was found **contradicted**
and reported as such, not forced. Contract stays `DRAFT — DO NOT FREEZE`:
`D-V3a`/`D-V7b` remain safety-critical `STILL_UNKNOWN`. Document-only, no
device contacted. Full result:
`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
§"Official vendor semantics confirmation pass — Source Pack 2".

**Predecessor:** `op0b_0_official_vendor_semantics_confirmation_pass1` —
session 2's pass (`D-V1`/`D-V2`/`D-V4`/`D-V5` narrowed to `PARTIALLY_CLOSED`;
none fully closed). Before that, `DEV.4` — AUTOMATED_VALIDATED 2026-09-02,
three-file AI-authority consolidation. Detail: `project/build_history.json`.

## `OP.0b.0` — DRAFT, DO NOT FREEZE

`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md` is
structurally complete (command surface table, configuration/runtime field
trace table, bug/gap register) but is **not** implementation authority — it
must not be cited as approving any command, schema or identity model, and its
`UNKNOWN`s must not be reinterpreted as decided. **`D-V4` and `D-V7a` are now
`CLOSED_BY_DOCS`** (first full closures, session 3). Still blocking: `D-V1`,
`D-V2`, `D-V3a`, `D-V3b`, `D-V5a`, `D-V5b`, `D-V6`, `D-V7b`, `D-V9a`, `D-V9b`
(`project/roadmap.json` `open_decisions`). `D-V1`/`D-V2` are `PARTIALLY_CLOSED`
with field-binding now `CONFIRMED` for most fields (an official PANW source
read verbatim, not name-matching); `D-V5` split into `D-V5a`
(`PARTIALLY_CLOSED`, strong) / `D-V5b` (`OPEN`, VSX applicability); `D-V6`
upgraded to `PARTIALLY_CLOSED` (register/unregister + pnote-enumeration
semantics confirmed — **contradicting**, and correcting, this session's own
source-pack hypothesis about a problem-filtered `-ia list`); `D-V7` split
into `D-V7a` (closed) / `D-V7b` (`STILL_UNKNOWN`, now with documented
context: the Simple Cluster API doesn't expose every cluster-object
feature). **`D-V3a` and `D-V7b` are the actual remaining freeze blockers** —
safety-critical authoritative sources still unknown, not merely real-env
measurements pending. `D-V3b`/`D-V5b`/`D-V9b` `REQUIRE_REAL_ENV` regardless.
`D-V8` is open but non-blocking.

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

Three independent movements — any order, any in parallel (full detail in
`project/roadmap.json` `now_next.next`/`upcoming` and the contract's own
"Next movement" section):

**A. `OP.0b.0` close the two remaining safety-critical blockers**
(`now_next.next`) — `D-V3a` (official PAN-OS/Panorama serial-field semantics
inside `show high-availability state`) and `D-V7b` (an official, safe,
machine-readable read surface for the CP cluster recovery-method setting).
Try an official GitHub mirror first (the technique that closed `D-V4`/
`D-V7a` this session); fall back to a human fetching the named pages.
Recommended: `Sonnet 5, extended thinking (high)`.

**B. PAN serial representation/identity evidence closure**
(`now_next.upcoming`) — hardware-blocked, unchanged by this session.
Recommended: `Sonnet 5, normal reasoning` initially; escalate only if vendor
identity semantics become architectural.

**C. Real-env residuals** (`D-V5a`/`D-V5b` schema/VSX parity, `D-V9a`/`D-V9b`
estate applicability, `D-V1`/`D-V2` exhaustive-vocabulary gaps) — folded into
the contract's existing S0→S8 slice sequence; not separately schedulable
before `FREEZE`.

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
