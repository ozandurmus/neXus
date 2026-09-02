# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-02, branch `claude/pan-ha-peer-identity-mvlndf`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op0a_pan_ha_peer_pairing_identity_closure` (OP.0a.P7 revision) —
  AUTOMATED_VALIDATED. Real-environment confirmation owed (see
  `project/roadmap.json` `now_next.next`).
- **Since this checkpoint, not yet recorded in `project/*.json`:** this
  branch also carries a bounded, opt-in PAN HA runtime peer-identity
  diagnostic (commits `1d97cd6`/`d0f8e31`/`a1a3882` — no new device command,
  no pairing/readiness change) and a `DRAFT — DO NOT FREEZE` vendor
  failover preflight evidence contract
  (`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`).
  Recording these in `project/roadmap.json`/`build_history.json` is owed as
  its own `STATE_UPDATE`, deliberately not folded into this governance
  checkpoint.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1` complete.
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

**`op0a_pan_ha_peer_pairing_identity_closure`** (OP.0a.P7 revision) — PAN HA
peer-pairing identity closure — AUTOMATED_VALIDATED 2026-09-02.
Sourced `peer_ip`/`peer_ipv6` from the running-config XML the PAN config
collector already fetches (previously dead code against real telemetry),
closed a hostname-normalization divergence between
`panorama_runtime_runner.py` and `panorama_config_collector.py`, and
requires mutual (both-directions) configuration agreement before
`_derive_pan_units` forms a pair. Read-only, no new device command.
`peer_ip` is explicitly configuration intent (Grade A) for this pairing
only — never sufficient for a future CLASS 2 authorization decision. Detail:
`docs/history/phase/OP_0A_PAN_HA_PEER_PAIRING_IDENTITY_CLOSURE.md`.

**Predecessor:** `op_vsx_real_env_retry_fixes` — AUTOMATED_VALIDATED
2026-09-02 (three post-merge VSX defects found and fixed against a live
physical pair; confirmed by the product owner on the real
`FW-CKP-EXTRA-LL`/`FW-CKP-ARKTEST` pairs). Detail:
`project/build_history.json`.

## Exact next build

**Failover readiness real-environment closure** (no new build contract —
see `project/roadmap.json` `now_next.next`). Confirm `OP.0a`'s
`ha_cluster_mode` resolution against a real CP/PAN HA pair and eyeball the
Failover module against that real evidence; a product-owner/security
decision on the `OP.0b` command gate is the other open item on this path.
`OP.0b` (the preflight battery) remains blocked on that gate — its evidence
surface is now audited in the `OP.0b.0` contract above (`DRAFT — DO NOT
FREEZE`, real-environment vendor-semantic confirmations still owed).

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| `OP.0b` preflight battery | its command gate is drafted but **not approved** — a product-owner/security call; the `OP.0b.0` evidence contract is `DRAFT — DO NOT FREEZE` pending vendor-doc confirmation | decision |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite, incl. `DEPLOY.1A` OIDC + an RBAC `OPERATE` role | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` by design until `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-environment
evidence.

## Real-environment validation owed

- **`CON.2`** — trigger a `read`-class job from the console against a real
  device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — one real-device confirmation that `ha_cluster_mode`
  resolves rather than falling back to `"unknown"`. Fixture-drift check, not
  a safety gate.
- **PAN HA peer identity (`OP.0a.P7`/`OP.0b.0`)** — the runtime peer-serial
  bidirectional-corroboration result (`B2` evidence grade) against the same
  approved real PAN pair; one member currently self-consistent, the other's
  configured/runtime relationship is `INCONSISTENT` — see the `OP.0b.0`
  contract's bug register. Not yet resolved; do not resolve it as a side
  effect of an unrelated build.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-a-real-MDS evidence for the
  Postgres advisory-lock path. Server-blocked.

## Automated test baseline

```
As of op0a_pan_ha_peer_pairing_identity_closure's own closure:
  1074 passed / 26 skipped / 0 failed, serial.
  Repository privacy gate: PASS / 0. Project-state consistency: metadata_warnings == [].
Later, not-yet-recorded session work on this branch (see checkpoint note above)
has since run 1099 passed / 24 skipped / 0 failed with no regressions (the
`+5` over the previously recorded 1094 is `DEV.4`'s own new governance tests
in `tests/test_architecture_convergence.py`, not a scope change to this
build). Repository privacy gate re-run PASS / 0 findings.
```

Run one-shot and read from file: `py -m pytest -q > pytest_result.log 2>&1`.
Run the suite serially at least once before closing a build — a parallel
run has previously hidden a real shared-state leak (`AI_START_HERE.md`
validation ladder). Delete gitignored `data/`/`logs/` before the privacy
gate — a test run recreates them and the gate flags them.

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
