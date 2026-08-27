# SecurityExpert — Current State

**Authoritative operational checkpoint:** 2026-08-27  
**Current engineering implementation:** `DEV.1 — Corporate Git + Copilot Development Foundation`  
**Product baseline:** `0.6.5 — PAN TLS/CA Trust Closure` (DONE)

## Current Active Build

`0.6.5 — PAN TLS/CA Trust Production Closure`
Status: **REAL_ENV_VALIDATED** (2026-08-27)
Build agreement: `PHASE0_6_5_PAN_TLS_CA_TRUST_PRODUCTION_CLOSURE.md`

Frozen scope:
- Close the production-admission contract for the existing opt-in PAN strict TLS
  CA verification mode using externally provisioned CA bundle material.
- No new PAN commands, collector target coverage, retries, timeouts, polling,
  concurrency, scheduler, CAS or UI behavior.
- Strict-mode TLS verification input failure must occur before HTTPS request;
  strict mode must never fall back to `verify=False`.
- Compatibility mode (`verify=False`) remains the default and is unchanged when
  strict mode is disabled.

Delivered and automated-validated:
- `utils/pan_tls_trust.py` — `PanTlsStrictPreflightError`, `preflight_pan_tls_ca_bundle()`: centralized CA bundle validation helper (mirrors 0.6.4 CP SSH trust pattern).
- `panorama/panorama_runtime_runner.py` — `_tls_verify_setting()` function; env var hierarchy `SECURITYEXPERT_PAN_CA_BUNDLE` > `SECURITYEXPERT_PAN_TLS_VERIFY` > False; conditional urllib3 warning suppression only in compat mode; production trust warning emitted when verify=False.
- `configuration/panorama_config_collector.py` — preflight validation for both Panorama and direct firewall paths before any `requests.get/post()` call; replaced `requests.packages.urllib3.disable_warnings()` with direct `urllib3` import.
- `tests/test_phase0_6_5_pan_tls_ca_trust_closure.py` — 14 targeted tests (AC-1…AC-11): compat mode, missing/unreadable bundle, env var routing, integration.

Automated evidence (2026-08-27):

```text
0.6.5 targeted tests (AC-1…AC-11):   14 passed
PAN config + regression suites:      28 passed
Repository privacy gate:              5 passed, 0 findings
```

Integration verified:
- Preflight raises `PanTlsStrictPreflightError` before any network call
- Error messages are value-free (no file paths, IP addresses, credentials)
- Default compat mode (verify=False) unchanged; production trust opt-in via env var

Real-environment evidence (2026-08-27) — value-free:

```text
R1 PASS strict mode + CA bundle path enabled
R2 PASS no insecure TLS fallback under strict mode
R3 PASS PAN collection contract preserved
R4 PASS shareable evidence remains value-free
R5 PASS no scheduler/polling/concurrency scope drift
SAFE SUMMARY PASS: success=5 partial=0 failed=0 management_down=0
```

`main` merge remains approved; deployment gate for 0.6.5 is **REAL_ENV_VALIDATED**.

## Next Build Contract (Frozen)

`DEPLOY.1 — Ubuntu + Docker Server Migration & Git Repository Foundation`
Status: **CONTRACT_FROZEN** (2026-08-27)
Handover agreement: `DEPLOY_1_CONTRACT_FREEZE_HANDOVER_2026_08_27.md`

Key planning note:
- Server availability is expected in about one week.
- 0.6.5 product track complete; awaiting DEPLOY.1 server setup to begin operational deployment.

## Previous Completed Build

`0.6.4 — CP SSH Host-Key Trust Production Closure`
Status: **REAL_ENV_VALIDATED** (2026-08-27)
Build agreement: `PHASE0_6_4_CP_SSH_HOST_KEY_TRUST_PRODUCTION_CLOSURE.md`

Frozen scope:
- Close the production-admission contract for the existing opt-in CP strict SSH
  host-key mode using locally provisioned trusted `known_hosts` material.
- No new CP commands, collection target coverage, retries, timeouts, polling,
  concurrency, scheduler, CAS or UI behavior.
- Strict-mode trust input failure must occur before SSH connection; strict mode
  must never fall back to `AutoAddPolicy` or trust-on-first-use.
- Compatibility mode remains the default and is unchanged when strict mode is
  disabled.

Delivered and automated-validated:
- `utils/cp_ssh_trust.py` — `CpSshStrictPreflightError`, `apply_strict_host_key_policy()`: shared preflight helper used by all CP SSH paths.
- `checkpoint/cp_runner.py`, `checkpoint/vsx_runner.py`, `checkpoint/direct_ssh_probe.py`, `configuration/checkpoint_config_probe.py`, `utils/cleanup.py` — policy blocks replaced with helper call; preflight failure handled as `error_class="strict_host_key_preflight_failed"` before any `ssh.connect()` call.
- `configuration/checkpoint_config_collector.py` — `CpSshStrictPreflightError` caught and mapped to safe host_row result.
- `tests/test_phase0_6_4_cp_ssh_host_key_trust_closure.py` — 20 targeted tests (AC-1…AC-6).
- `tests/test_phase0_6_1b_1_4_cp_ssh_trust.py` — extended `FakeSSHClient` with `get_host_keys()` for B.1.4 backward compatibility.

Automated evidence (2026-08-27):

```text
0.6.4 targeted tests (AC-1…AC-6):    30 passed
CP collector + config UI regression: 17 passed
CAS + config UI regression:          18 passed, 1 skipped
CP SSH trust B.1.4 regression:        8 passed
Repository privacy gate:              5 passed, 0 findings
```

Real-environment evidence (2026-08-27) — value-free:

```text
R1 PASS  compat mode (strict=off) → 57/75 collection, HTML generated, no preflight error
R3 PASS  strict=on, known_hosts absent → CpSshStrictPreflightError before ssh.connect(),
         zero TCP connections, traceback confirms no network activity
```

`main` merge approved after R1+R3 evidence.

Frozen scope:
- Single source/entity/artifact timeline query from existing immutable CAS metadata.
- Safe normalized PAN diff (allowlisted structured projection only; in-memory, no network).
- CP: secret-aware timeline only; raw/redacted Gaia text diff is `INSUFFICIENT_EVIDENCE`.
- History payload attached as additive `history_v1` field to each configuration device.
- No new collectors, CAS writes, migrations, polling, concurrency, retention or network access.
- ClusterXL/VSX member differences remain `MEMBER_SPECIFIC`; never converted to drift by history.
- History payload: no sha256, object path, management_ip, raw config, Gaia line, credential.

Delivered and automated-validated:
- `utils/config_history.py` — `ConfigHistoryService`, `ArtifactTimeline`, PAN diff adapter, `build_history_payload()`.
- `utils/config_ui.py` — additive `history_v1` projection for PAN and CP devices; `history_service` optional parameter.
- `static/app.js` — `renderConfigHistoryPanel` updated with timeline and diff rendering; CP `INSUFFICIENT_EVIDENCE` explanation.
- `static/style.css` — `.history-timeline*` and `.diff-*` rules.
- `tests/test_phase0_6_3_history_diff_ux.py` — 14 targeted tests (AC-1…AC-9).

Automated evidence (2026-08-27):

```text
0.6.3 targeted tests:                   14 passed
Configuration UI + CAS + CP UI regression: 30 passed, 1 skipped
render-only (--render-only):             PASS — HTML generated, no errors
Repository privacy gate:                 5 passed, 0 findings
```

Release closure:
- Commit-scope review confirms no collector, CAS write/migration, polling,
    concurrency, network, native-backup or `main.py` semantic change.
- No real-environment validation was required because no network-facing
    behavior was introduced.
- `main` release commit `2a2d245` is pushed to `origin/main`.

## Previous Active Build — REAL_ENV_VALIDATED

`0.6.1C — Discovery Lifecycle + Capability Profile Foundation / Real-Environment Validation`
Status: **REAL_ENV_VALIDATED** (2026-08-27)
Validation agreement: `PHASE0_6_1C_REAL_ENVIRONMENT_VALIDATION.md`

`0.6.1C.1 — Runtime Admission + Scheduler-One-Shot Wiring Closure` is
implemented and real-environment validated. `main.py` now owns one
process-lifetime runtime services object, admits CP/VSX/CP config/probe and
PAN/config orchestration before collector invocation, exposes an explicit
default-disabled `--scheduler-once` path, records value-free `RunContext`
metadata and passes real coordinator/policy/store objects to Discovery UI
projection. Admission is conservatively keyed by the known management-plane
orchestration endpoint; downstream per-device/distributed admission remains
deferred to DEPLOY.1.

Automated evidence (2026-08-26):

```text
0.6.1C + 0.6.1C.1 targeted: 102 passed, 1 skipped
R06 bounded same-process coalesce probe: 6 passed (2026-08-26)
Full regression after R06 probe: 368 passed, 3 skipped, 2 xfailed, 0 failed
Repository privacy gate: PASS — 0 findings, 239 files scanned
```

Real-environment evidence (2026-08-27) — value-free 24/24:

```text
R01 PASS  policy absent     → terminal_jobs=0, no network
R02 PASS  enabled=false     → terminal_jobs=0, no network
R03 PASS  malformed policy  → exit 2 before credential prompt, no network
R04 PASS  enabled policy    → terminal_jobs=1 (cp workflow)
R05 PASS  manifest          → provenance=scheduled, coordinator_decision=admitted,
                               effective_scope=cp, scheduler_result=completed
R06 PASS  real collector    → first_request_admitted=true, second_request_decision=coalesced,
                               second_operation_called=false, active_jobs_after_probe=0,
                               r06_real_env_pass=true; collection DEGRADED 57/75
R07 PASS  collector contract→ DEGRADED semantics (success=57 partial=0 failed=13
                               management_down=5 / 75 discovered) identical to
                               manual baseline; status enum and summary preserved
R08 PASS  interaction safety→ frequency_increased=false, concurrency_increased=false,
                               cooldown_reduced=false; single one-shot run
D01 PASS  value-free outputs→ no credential, endpoint, hostname or raw config in
                               manifests, safe summaries or committed report
D02 PASS  runtime outside git→ all runtime artifacts remain under LOCALAPPDATA
```

Value-free report: `project/validation/0_6_1c_real_env_report_template.json` — 24/24, `main_merge=approved`.

## Previous Build — Awaiting Real-Environment Validation

`0.6.1C — Discovery Lifecycle + Capability Profile Foundation`  
Status: **AUTOMATED_VALIDATED** (2026-08-26)  
Build agreement: `PHASE0_6_1C_DISCOVERY_LIFECYCLE_CAPABILITY_PROFILE_FOUNDATION.md`

Delivered and automated-validated:
- Discovery lifecycle state machine (`utils/discovery_lifecycle.py`) — DISCOVERED/VALIDATED/STABLE/EXCLUDED/REMOVED with confidence, evidence-plane, transition-reason.
- Capability registry + collection planner (`utils/capability_registry.py`) — Expert/direct-Clish/unknown shell; ClusterXL standby suppress; PAN VSYS context; identity-failure defers to safe plan.
- Collection execution coordinator (`utils/collection_executor.py`) — per-device physical lock, vendor/context concurrency budget, coalescing, bounded cancellation, timeout-only retry, cooldown compatibility.
- Limited default-disabled scheduler — RuntimeRoot policy only; allowlisted read-only workflows; malformed policy fails before network access; provenance=scheduled.
- UI: additive Discovery module (`utils/discovery_capability_ui.py`, `templates/index.html`, `static/app.js`, `static/style.css`) — lifecycle/capability/coordinator/scheduler observability.
- `RunContext` manifest extended with `job_id`, `provenance`, `effective_scope`, `coordinator_decision`, `coalesced_to` (no secrets).

Automated regression evidence (2026-08-26):

```text
Targeted 0.6.1C tests: 91 passed, 1 skipped
```

Key gates before DONE:
- Human real-environment validation: scheduler policy absent → no job produced; opt-in scheduled job runs with provenance=scheduled; concurrent request to same endpoint coalesces; collector results preserved; no concurrency/frequency increase.
- Privacy boundary: no raw config, credential or real identity in lifecycle/capability/coordinator payloads.

## Previous Completed Build — Awaiting Real-Environment Validation

`0.6.1B.1.6 — Compliance Control Pack v1`  
Status: **AUTOMATED_VALIDATED** (2026-08-25)

Delivered and automated-validated in repository:

- Compliance control pack expanded to 10 deterministic subject controls under a vendor-neutral control taxonomy with CP/PAN evidence adapters.
- Added deterministic evaluation for shared benchmark signals using existing normalized evidence (hostname non-default, DNS/NTP pairs, AAA provider presence, telnet disable posture, HTTP/HTTPS management posture, session timeout threshold, SNMP v3 signal, update-identity signal, management audit logging presence).
- Compliance payload schema advanced to `0.6.1B.1.6` with explicit control traceability fields (`scope`, `control_lifecycle`, `benchmark`, `benchmark_reference`, `evidence_fields`, `evidence_plane`, `evidence_coverage`, planned-gap metadata).
- Compliance card UI now surfaces control traceability and planned evidence-gap rationale while preserving the existing master-detail interaction model.
- Existing fleet/platform/global partition and privacy boundary are preserved:
    - no raw configuration blobs,
    - no credentials/secrets,
    - no real device/network identity in compliance payload fields,
    - no certification claim semantics.

Automated regression evidence (2026-08-25):

```text
Targeted compliance tests: 4 passed
Impacted configuration UI/export contract tests: 9 passed
Total targeted validation for this build: 13 passed
```

Local render integration evidence (2026-08-25):

```text
python -B main.py --render-only: PASS
Runtime output marker verification (master-detail compliance IDs/functions): PASS
```

Promotion gate to `DONE` remains pending: human real-environment validation of control-card outcomes on representative CP/PAN evidence payloads.

## 0.7.x Early Foundation

Compliance posture and control-pack work (`0.6.1B.1.5`, `0.6.1B.1.6`) delivered the early VERIFY foundation ahead of the formal 0.7.x track:
- Evidence-backed compliance state model (PASS/FINDING/UNKNOWN/NOT_APPLICABLE/PLANNED) with CIS/PCI-DSS/BDDK framework area mappings.
- 10 deterministic vendor-neutral benchmark controls with CP/PAN evidence adapters and full traceability metadata.
- No-certification-claim semantics and privacy boundary preserved.

Formal `0.7.x — Verification, Compliance & Expected State` track remains planned. Next milestone in that track: `compliance_posture_rulepack_transition` (promote current controls to versioned rule packs with stronger framework governance).

## Previous Build — Superseded

`0.6.1B.1.5 — Compliance Posture Foundation` — AUTOMATED_VALIDATED (2026-08-25). Superseded by B.1.6 control-pack expansion.

## Previous Build — Awaiting Real-Environment Validation

`0.6.1B.1.4 — CP SSH Host-Key Trust Foundation` — AUTOMATED_VALIDATED (2026-08-25). Pending production strict-known_hosts rollout evidence before DONE.

This is the small authoritative checkpoint. New AI sessions must prefer this
over historical PHASE documents or the full Continuation Pack.

## Product evidence baseline

`0.6.1B.1.2` interactive Check Point configuration collection is
**REAL-ENVIRONMENT VALIDATED**.

Authoritative CP configuration checkpoint:

```text
Physical hosts:           92
Configuration entities:  122 observed / 122 planned
Successful:               101
Unavailable:              21
Operational failures:     21
Identity accepted:        103
Standalone gateways:      44
ClusterXL members:        34
VSX hosts:                14
VSX virtual systems:      30
HA role coverage:         42
SSH shell profiles:       interactive_direct_clish=18, interactive_expert_explicit_clish=55, unknown=19
Collection duration:      181.922 seconds
```

Validated contracts:

- direct-Clish interactive collection: PASS,
- Expert → explicit Clish collection: PASS,
- VSX Expert `vsenv` context mechanism: validated,
- identity-failure recovery: PASS,
- CP configuration coverage remains PARTIAL (101/122),
- direct-Clish capability must not be equated with Spark platform identity.

## Engineering foundation completed before DEV.1

DEV.0 repository readiness is complete except the intentionally deferred
pre-server storage checkpoint:

- `DEV.0.1` runtime management endpoint decoupling — DONE / real-env validated.
- `DEV.0.2` repository sanitization — DONE.
- `DEV.0.3A` Runtime Path Foundation — DONE / real-env bootstrap validated.
- `DEV.0.3B` normal runtime artifact migration — DONE.
- `DEV.0.3B.1` direct-SSH runtime-path closure — DONE / real-env failure point closed.
- `DEV.0.3C` History/CAS Runtime Boundary — DEFERRED / PRE-SERVER; not a Corporate Git blocker.
- `DEV.0.4` Local Repository Privacy Gate — DONE; clean candidate 0 findings.
- `DEV.0.4.1` runtime inventory exclusion policy — DONE / real-env policy activation validated.
- `DEV.0.5A/B/B.1/B.2` authentication boundary + consumer/canonical Config + repository-wide enterprise DLP closure — DONE.

Current automated baseline before DEV.1 documentation/instruction changes:

```text
227 passed / 2 known xfail
Repository privacy gate: 0 findings / PASS
```

Known xfails remain:

- VSX network canonicalization.
- PAN default-route classification.

## Corporate Copilot validation

After DEV.0.5B.2, a clean Corporate Copilot session successfully performed the
previously blocked broad authentication consumer audit across Check Point,
VSX, Panorama and configuration source. Repository-native cross-file reasoning
is therefore available for approved source context.

Copilot audit follow-up debt:

- environment authentication overrides remain explicit operational compatibility paths; do not remove implicitly,
- PAN authentication transport behavior is not fully converged across old/new paths; track under explicit security hardening,
- production CP SSH host-key trust and PAN TLS corporate-CA trust remain production gates.

## Current engineering build — DEV.1

Goal: establish Corporate Git + Copilot as the normal full-scale development
workflow and make repository state sufficient for new-chat continuation.

DEV.1 repository operating model includes:

- `AGENTS.md` global agent/build laws,
- `.github/copilot-instructions.md` Copilot-native workflow,
- path-specific `.github/instructions/*`,
- reusable `.github/prompts/*` for build start, architecture, implementation,
  root cause, handover and build close,
- `AI_DEVELOPMENT_PROTOCOL.md`,
- `PROJECT_VISION.md`,
- `START_HERE_COPILOT.md`,
- living `project/*` metadata.

DEV.1 status is **AUTOMATED_VALIDATED / CORPORATE GIT BASELINE ACTIVE**.

Acceptance evidence (local):

- Corporate Git baseline active: `origin=https://github.com/OzanDur_garanti/securityexpert`, `main`, `ead9f1d`.
- Runtime-path migration closure (6 module `OUTPUT_DIR` bindings): IMPLEMENTED.
- Targeted migration validation: `20 passed, 1 skipped`.
- Full regression validation (one-shot log-based run): `226 passed, 2 skipped, 2 xfailed`.

Git push/merge remains human-controlled.

## Immediate sequence

1. Complete the human-approved `0.6.1C` R01–R08 real-environment gate and value-free 24/24 report.
2. Complete human real-environment validation for `0.6.1B.1.6` on representative CP/PAN payloads.
3. Keep collection coordinator/safety gate mandatory before any recurring scheduling or concurrency increase.

Do not increase recurring polling frequency or concurrency before the CP safety
audit closes.

## Development infrastructure

Corporate GitHub Copilot is available in VS Code and broad repository reasoning
has been validated after the DLP cleanup. Internal Bitbucket Data Center,
Jenkins, SonarQube and Artifactory are also visible in the organizational
ecosystem. The exact Corporate Git remote/branch workflow must be confirmed by
the human before first push.
