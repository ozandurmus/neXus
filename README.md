# SecurityExpert / NetSecXpert (working name)

Current authoritative state is maintained in `CURRENT_STATE.md`. Current product baseline is **0.6.1B.1.2** and the active engineering checkpoint is **DEV.1 — Corporate Git + Copilot Development Foundation**.

`NetSecXpert` is the current working product name; the final marketing name is not frozen yet. The existing Network Inventory module remains the frozen Phase 0.5 operational view.

The platform collects and reconciles runtime firewall inventory from Check Point, VSX and Panorama/PAN-OS with run isolation, completeness telemetry, last-known-good snapshots, UI freshness state and privacy-preserving support bundles.

The final 0.5 release freezes the current collector methods and closes the inventory UI with vendor-aware presentation:

- Check Point ClusterXL keeps the SmartConsole-like interface matrix: Cluster VIP + physical member columns.
- Check Point and VSX routing can still be inspected by physical gateway where that distinction matters.
- VSX logical contexts are shown once under the physical cluster; identical member data is deduplicated and member-specific differences remain visible.
- Palo Alto HA pairs are shown as one logical cluster with VSYS children. Identical HA-member interface/route data is shown once; member tabs appear only when runtime divergence exists.
- Palo Alto VSYS labels include Virtual Router context, for example `VSYS 2 | PAYMENTS-RTR`.
- Persistent light/dark theme with system-aware first load.
- Material-inspired enterprise visual system with improved contrast, hierarchy, focus states, sticky table headers and denser operational ergonomics.
- Existing LIVE / OLD DATA / NO LIVE DATA semantics remain intact.

See `PHASE0_5_FINAL_UI_CLOSURE.md` and `UI_EXPERT_REVIEW.md` for the exact behavior, visual rationale and future UI direction.

## Install and run

```powershell
py.exe -m pip install -r requirements.txt
py.exe .\main.py
```

Full runs create a shareable support bundle under `output/`. Runtime output, last-known-good data, HMAC keys and future configuration backups are ignored by Git.

## Important security boundary

Do not commit `output/`, `data/state/`, `data/runs/`, `data/configs/`, `.env`, keys, support HMAC keys or real firewall configuration/inventory artifacts.

## Phase history

- `PHASE0_5_FAILURE_AWARE_SNAPSHOT_UI.md` — LIVE / OLD DATA / NO LIVE DATA and LKG.
- `PHASE0_5_1_CP_DIRECT_SSH_PROBE.md` — observe-only CP direct SSH capability probe.
- `PHASE0_5_2_CP_CLUSTERXL_AGGREGATE_VIEW.md` — initial runtime ClusterXL grouping.
- `PHASE0_5_3_FINAL_CLUSTER_HIERARCHY_UI.md` — cluster-first hierarchy and first matrix UI.
- `PHASE0_5_FINAL_UI_CLOSURE.md` — final vendor-aware UI behavior and theme closure.
- `UI_EXPERT_REVIEW.md` — consolidated design/security/management/UX review.
- `docs/SECURITYEXPERT_TARGET_ARCHITECTURE.md` — broader SecurityExpert direction.


## Phase 0.5 Final - Palo Alto Management IP Closure

Panorama managed-device `ip-address` is now preserved as `management_ip` and presented at the physical firewall/HA-member level. See `PHASE0_5_FINAL_PAN_MGMT_IP.md`.


## Phase 0.5 final closure

The final 0.5 build includes Panorama management-IP preservation and logical-first Check Point cluster routing. Identical ClusterXL/VSX physical routes are displayed once; per-member and Diff-only routing controls appear only when real divergence is detected. See `PHASE0_5_FINAL_CP_ROUTE_CONVERGENCE.md`.


## Phase 0.6.0A4 — Palo Alto Configuration Alignment & Provenance

A3 proved direct firewall API across the connected PAN fleet. A4 promotes direct `effective-running` to the primary configuration-evidence gate and reads Panorama's own active management config to map Template Stack and Device Group provenance.

After the validated A3 full-fleet run, A4 can be exercised from the management-reachable VM with three workers:

```powershell
py.exe .\main.py --only pan-config --pan-config-stage all --pan-config-workers 3
```

A4 writes a local-only method failure matrix:

```text
output/pan_config_failures_<run_id>.json
```

This file contains the actual local device identity and tells the operator whether a failure occurred in Panorama API, direct firewall HTTPS API, identity verification, a specific config query, or the local immutable evidence store. SSH is explicitly reported as not attempted; it is not silently used as a fallback.

The shareable `output/config_support_<run_id>.zip` pseudonymizes device and Panorama assignment identities and excludes raw configuration.

A4 does not yet assert exact `LOCAL_OVERRIDE`; it reports assignment/sync/provenance evidence and observed differences while the exact Template Stack / Device Group expected-intent compiler remains a later step. See `PHASE0_6_0A4_PAN_CONFIGURATION_ALIGNMENT_PROVENANCE.md` and `CONFIGURATION_ALIGNMENT_DESIGN.md`.

## Phase 0.6.0A4.1 — PAN Expected Configuration Compiler

A4.1 adds a conservative expected-state compiler on top of A4 Panorama intent collection. It compiles Template Stack scalar precedence and Device Group policy lineage while keeping direct firewall `effective-running` as the primary actual-state evidence.

Run from the management-reachable VM:

```powershell
py.exe .\main.py --only pan-config --pan-config-stage all --pan-config-workers 3
```

Local compiler diagnostics are written to `output/pan_expected_compiler_<run_id>.json`; the complete hashed expected manifest is kept under `data/derived/panorama_expected/<run_id>/`. Only the privacy-safe support ZIP should normally be shared.

See `PHASE0_6_0A4_1_PAN_EXPECTED_CONFIGURATION_COMPILER.md`.

## Phase 0.6.0A4.1.1 Unified Full Run

A normal `py.exe .\main.py` now runs inventory plus the PAN configuration evidence/expected compiler for all connected PAN devices, then emits both `support_bundle_<run_id>.zip` and `config_support_<run_id>.zip` with the same run ID. Use `--skip-config` to run the frozen inventory path without configuration collection. See `PHASE0_6_0A4_1_1_UNIFIED_FULL_RUN_ORCHESTRATION.md`.


## Phase 0.6.0A4.2 — PAN Setting-Level Alignment Engine

A4.2 consumes the A4.1 compiled Template-Stack scalar manifest and compares each alignment-ready setting with the direct firewall `effective-running` configuration. Direct local-active and merged configuration are corroborating evidence used to explain differences.

A4.2 uses evidence-bound classifications: `ALIGNED`, `LOCAL_OVERRIDE`, `EFFECTIVE_DRIFT`, `PANORAMA_OUT_OF_SYNC`, `EXPECTED_ONLY`, `LOCAL_ONLY`, and `UNKNOWN`. Expected-only and local-only observations are not automatically drift, and unresolved Template variables remain unknown. Device Group policy-value alignment remains deferred.

Normal unified execution remains:

```powershell
py.exe .\main.py
```

The detailed local alignment manifest is stored under `data/derived/panorama_alignment/<run_id>/setting-alignment.json`; a compact operator report is written to `output/pan_setting_alignment_<run_id>.json`. Neither is included in the shareable support bundle.

The known-noisy `pushed-template` probe is disabled in normal A4.2 CLI runs and can be enabled only for diagnostics with `--pan-probe-pushed-template`.

See `PHASE0_6_0A4_2_PAN_SETTING_LEVEL_ALIGNMENT.md`.

## Phase 0.6.0A4.2.1 — PAN Semantic Validation

A4.2.1 validates the meaning of A4.2 classifications before they become a user-facing Alignment feature. It creates a small deterministic local checklist for `LOCAL_OVERRIDE`, coverage-gap and unknown samples, and identifies conservative `POSSIBLE_SCHEMA_EQUIVALENT` hypotheses between `EXPECTED_ONLY` and `LOCAL_ONLY` paths. These hypotheses never auto-promote a setting to `ALIGNED`.

Normal execution remains:

```powershell
py.exe .\main.py
```

Local-only outputs:

```text
output/pan_semantic_validation_<run_id>.json
output/pan_semantic_validation_samples_<run_id>.csv
data/derived/panorama_semantic_validation/<run_id>/semantic-validation.json
```

The operator JSON/CSV may contain selected non-sensitive configuration values and must not be shared as a normal support artifact. Secret-like paths are redacted. The shareable `config_support_<run_id>.zip` contains only counts and contracts.

A4.2.1 separates engine readiness from human confirmation: `a4_2_1_engine_pass` may be true while `a4_2_1_stage_pass` remains `null` and `manual_confirmation_status=pending` until sampled findings are manually verified.

See `PHASE0_6_0A4_2_1_PAN_SEMANTIC_VALIDATION.md`.

## Phase 0.6.0A4.2.2 — PAN Semantic Policy & Provenance Hardening

A4.2.2 converts A4.2.1 manual findings into conservative engine policy. HA peer-address differences are treated as member-relative rather than generic overrides; device-telemetry mismatches are held at `PROVENANCE_UNVERIFIED` until expected source semantics are verified; and VSYS friendly names are resolved against direct-firewall internal `vsysN` identities before path/value comparison.

A uniquely resolved VSYS display-name/internal-ID pair is `ALIGNED`, not `LOCAL_OVERRIDE`. The same per-device identity map is used to canonicalize VSYS selectors in expected paths, which can eliminate false `EXPECTED_ONLY`/`LOCAL_ONLY` coverage gaps.

Normal execution remains:

```powershell
py.exe .\main.py
```

The shareable config support bundle reports semantic-policy and identity-normalization counts only; raw VSYS names/IDs and the per-device identity map are not included.

See `PHASE0_6_0A4_2_2_PAN_SEMANTIC_POLICY_PROVENANCE_HARDENING.md`.


## Phase 0.6.0A4.3 — SecurityExpert Configuration UI Foundation

A4.3 adds the first multi-module application shell: **Overview**, frozen **Network Inventory**, and a new **Configuration** module with Overview / Alignment / Evidence / History / Backup views. The Configuration UI consumes the current full-run A4.2.2 evidence model and shows semantic states without converting coverage gaps or member-specific differences into failures.

The local HTML may include real device/assignment identities and bounded finding paths, but it excludes raw configuration values and value hashes. Shareable support bundle behavior is unchanged. The Backup tab is a roadmap placeholder for 0.6.0B and is deliberately separate from configuration-evidence snapshots.

Normal execution remains:

```powershell
py.exe .\main.py
```

See `PHASE0_6_0A4_3_SECURITYEXPERT_CONFIGURATION_UI_FOUNDATION.md`.


## Phase 0.6.0A4.3.1 — Vendor-Neutral Configuration Information Architecture

A4.3.1 separates current device configuration from expected/current Alignment before Check Point and VSX configuration adapters are added. Device views now expose vendor/model/software/management IP/serial/HA-role/VSYS count/policy scope, and a dedicated **Configuration** tab projects selected non-secret current PAN values from direct `effective-running`. Compact `PAN`, `LOCAL`, `OVERRIDE` and member-specific origin hints are shown next to current values; detailed comparison remains in Alignment. Policy & Objects is a separate future plane.

Because `output/index.html` can now contain real non-secret operational configuration values, treat it as a **sensitive local operator artifact**. Raw XML, value hashes and secret-bearing values remain excluded; shareable support bundles are unchanged.

See `PHASE0_6_0A4_3_1_VENDOR_NEUTRAL_CONFIGURATION_INFORMATION_ARCHITECTURE.md`.

## Phase 0.6.0A4.3.2 — Content-Addressed Configuration History & Storage

A4.3.2 stops repeated configuration runs from storing duplicate payload bytes. Snapshot history remains under `data/configs`, but immutable payloads are stored once by SHA-256 under `data/artifacts/config/sha256`. A `SAME` run therefore creates only metadata/reference history; a `CHANGED` run stores the new unique object while preserving older versions for future diff/history.

The storage API is vendor-neutral. PAN XML is wired now; future Check Point Gaia/Clish text evidence can use the same store without changing the storage model. Check Point collection itself is deliberately deferred to 0.6.1 so transport/evidence changes are not mixed into this storage migration.

Before cleaning an existing large `data/configs` tree:

```powershell
py.exe .\main.py --storage-analyze
py.exe .\main.py --storage-deduplicate
```

Both are credential-free; de-duplication is dry-run by default. Apply only after reviewing the generated migration manifest:

```powershell
py.exe .\main.py --storage-deduplicate --apply
```

See `PHASE0_6_0A4_3_2_CONTENT_ADDRESSED_CONFIGURATION_HISTORY_STORAGE.md`.

## Phase 0.6.0A4.3.3.2 — PAN HA Runtime Role + Development Workflow Modes

A4.3.3.2 keeps the normal `main.py` path as the full integration checkpoint and
adds faster development loops so UI or one vendor plane does not force a full
fleet collection on every iteration.

Recommended workflow modes:

```powershell
# 1) UI / projection only: no credentials, no network collection.
py.exe -B .\main.py --render-only

# 2) PAN configuration/evidence only. Defaults to the first 5 connected PAN devices.
py.exe -B .\main.py --only pan-config

# Full connected PAN configuration scope without CP/VSX collection.
py.exe -B .\main.py --only pan-config --pan-config-stage all

# 3) VSX collection + parser, then merge/render with the latest other planes.
py.exe -B .\main.py --only vsx

# 4) Physical Check Point only, explicitly excluding VSX hosts/members/Virtual Systems.
py.exe -B .\main.py --only cp

# 5) Full integration checkpoint. Required before closing a phase.
py.exe -B .\main.py
```

Partial modes intentionally produce a mixed-cycle development view. The HTML
marks these views as **not a checkpoint**. A full checkpoint re-collects all
normal inventory planes and PAN configuration under the existing run context.

PAN HA header role is evidence-first: Panorama managed-device `ha-state` is
used when present; otherwise SecurityExpert issues a read-only targeted
`show high-availability state` operational query and displays the returned
local runtime state such as `ACTIVE` or `PASSIVE`. Static HA Group ID is not
used to infer runtime role.

See `PHASE0_6_0A4_3_3_2_PAN_HA_RUNTIME_ROLE_DEVELOPMENT_WORKFLOW_MODES.md`.

## Phase 0.6.1A — Check Point Configuration Evidence Probe

0.6.1A validates Check Point actual-configuration collection before any CP configuration is promoted into the product. In this estate administrator SSH sessions start in **Expert shell**, so SecurityExpert explicitly invokes Gaia Clish from Expert rather than assuming a Clish login shell.

After at least one normal full checkpoint has established `output/cp_telemetry.json`, `output/cp.json` and `output/vsx.json`, run:

```powershell
py.exe -B .\main.py --cp-config-probe
```

The probe attempts a representative Standalone gateway, both members of one non-VSX ClusterXL pair, one VSX physical member, and one non-zero VS context. Physical Gaia reads use fixed read-only commands including `clish -c 'show configuration'`. VSX additionally compares interactive Clish `set virtual-system <VSID>` with Expert `vsenv <VSID>` context behavior.

Raw `show configuration` output is processed in memory only and is never persisted, printed, stored in CAS/history, or exposed in the UI. The generated `output/cp_config_probe_*.json` contains local identities/host-key fingerprints and is **LOCAL ONLY / NOT SHAREABLE**. Paste the console summary for review instead.

See `PHASE0_6_1A_CHECK_POINT_CONFIGURATION_EVIDENCE_PROBE.md`.

### Phase 0.6.1A.1 — CP Configuration Identity Gate + VSX Target Resolution
`py.exe -B .\\main.py --cp-config-probe` now applies the 0.6.1A.1 identity gate and can resolve a VSX member/context from mature VSX artifacts when CP object-name joining is unavailable. Expert-shell login remains the contract; Gaia reads use explicit `clish -c ...`. The probe remains read-only and does not persist raw configuration.

## Phase 0.6.1B — Check Point Configuration Collector + Secret-Aware Projection + UI Integration

0.6.1B promotes the read-only Expert-shell/Clish evidence path proven in 0.6.1A/A.1 into current Check Point Configuration for Standalone gateways, ClusterXL physical members, VSX hosts, and observed VSX Virtual System contexts.

The estate login-shell contract remains explicit:

```text
SSH -> Expert shell -> clish -c 'show configuration'
```

For VSX, the validated Expert `vsenv <VSID>` context is used before `clish -c 'show configuration'`, with the validated Clish context path retained as a fallback. Only VSIDs already observed by the mature VSX inventory are collected.

Raw Gaia configuration is never persisted. Secret-bearing lines are withheld before content-addressed storage and UI projection; only a sanitized configuration artifact and internal full-configuration fingerprint are retained so secret-only changes remain detectable without storing the secret value.

Development validation without a full inventory/PAN collection:

```powershell
py.exe -B .\main.py --cp-config-collect --cp-config-stage all
```

A smaller representative sample is available with `--cp-config-stage sample`. If `output/unified.json` exists, the command regenerates `output/index.html` using the newly collected Check Point configuration plus the latest other planes.

`output/cp_config_telemetry.json` is a **LOCAL OPERATOR SENSITIVE / NOT SHAREABLE** artifact because it contains real identities, management addresses and non-secret current configuration values. Use the console safe summary for review.

Check Point Management expected-vs-actual Alignment is deliberately not implemented in this phase. ClusterXL member differences are presented as member-specific current-state differences rather than drift.

See `PHASE0_6_1B_CHECK_POINT_CONFIGURATION_COLLECTOR_SECRET_AWARE_PROJECTION_UI_INTEGRATION.md`.

## Phase 0.6.1B.1 — Check Point Configuration Coverage + Device UX Hardening

0.6.1B.1 hardens the production Check Point current-configuration integration after the first real 0.6.1B fleet run. It keeps the proven Expert-shell collection contract and adds platform-aware coverage diagnostics, Quantum Spark / Gaia Embedded classification, read-only runtime ClusterXL role evidence, improved Model/Serial parsing, logical VSX presentation, vendor fleet filters, and responsive/collapsible Configuration UX.

Key contracts:

```text
Expert login -> clish -c 'show ...'           primary Gaia read path
Clish-login appliance -> direct 'show ...'    show-only capability fallback
ClusterXL / VSX role -> cphaprob state        runtime evidence, never inferred
VSX config -> vsenv <VSID> -> clish -c ...    validated context path
```

Spark classification is conservative and evidence-driven. Unsupported Gaia Embedded command capability is reported separately from reachability/authentication/authorization/identity failures. The safe summary also exposes observed/planned entities, management-reported-down hosts, platform coverage, entity-type coverage, and detailed failure reasons.

Configuration UI now supports `All`, `Check Point`, and `Palo Alto` fleet filters. VSX member/context evidence remains member-specific in the backend, while the UI groups duplicated logical virtual systems under an authoritative cluster group or an explicitly presentation-only inferred VSX pair. Device facts can be collapsed, and small viewports use an off-canvas device drawer and one-column configuration sections.

Raw Check Point `show configuration` remains memory-only. CAS/history receive only secret-aware redacted evidence plus the non-browser canonical change fingerprint established in 0.6.1B.

Real-environment validation command:

```powershell
py.exe -B .\main.py --cp-config-collect --cp-config-stage all
```

Share the safe console summary and screenshots only; keep `output\cp_config_telemetry.json` local-sensitive.

## Phase 0.6.1B.1.1 — Adaptive CP Shell Detection + Metadata Coverage Recovery

Check Point configuration collection now detects the authenticated SSH command surface with read-only capability evidence instead of assuming every managed appliance begins in the same shell. Direct Gaia Clish and Expert->explicit-Clish profiles are supported, with the observed profile reused for subsequent reads. The build also hardens version fallback, model/serial identity extraction, ClusterXL runtime HA-role evidence, and VSX aggregate presentation metadata. See `PHASE0_6_1B_1_1_ADAPTIVE_CP_SHELL_DETECTION_METADATA_COVERAGE_RECOVERY.md`.

## Phase 0.6.1B.1.2 — Interactive CP SSH Session + Coverage Closure + Project Roadmap UI

0.6.1B.1.2 replaces the failed exec-channel-only adaptive-shell assumption with a PTY-backed interactive SSH session that mirrors a normal operator login. This is specifically intended to recover appliances such as the observed WiFi/Quantum Spark estate where manual SSH reaches a direct Clish prompt while SSH exec requests do not provide the same command surface.

The Check Point host handshake is capability-based, not prompt-classification-based:

```text
SSH authentication
  -> interactive PTY/shell
  -> direct `show hostname`
       success => interactive direct Clish
       failure => `clish -c 'show hostname'`
          success => interactive Expert -> explicit Clish
          failure => bounded legacy exec-channel compatibility fallback
```

Once a mode is proven it is reused for read-only Gaia commands. `show version all` may fall back to `show version`. `show configuration` remains the actual-configuration source. Platform classification is separate from collection capability: an authenticated endpoint with hostname evidence and a successful read-only configuration export may be collected at MEDIUM identity confidence even while its exact Check Point platform family remains unknown. No platform family is guessed from the prompt alone.

The interactive PTY requests a wide terminal to reduce line wrapping of long `set ...` commands. Raw `show configuration` remains memory-only; the secret-aware redaction/CAS contract from 0.6.1B is unchanged. VSX context collection retains the already-validated Expert `vsenv <VSID>` path rather than being rewritten around the new host handshake.

The same build adds a top-level **Project Plan** module backed by versioned metadata under `project/`. It shows current build/track, weighted roadmap progress, Now / Next / Upcoming, major-track mapping, backlog/technical debt, completed feature explanations, and build history. Progress is acceptance-criterion completion, not an ETA. Future major numbering is explicitly provisional until a phase checkpoint freezes it.

Project-plan metadata must be updated in every packaged build that changes scope/status. See `project/README.md`.

Real-estate CP validation remains intentionally partial-run capable:

```powershell
py.exe -B .\main.py --cp-config-collect --cp-config-stage all
```

Share only the SAFE console summary and screenshots. `output\cp_config_telemetry.json` remains local-sensitive.

See `PHASE0_6_1B_1_2_INTERACTIVE_CP_SSH_SESSION_COVERAGE_CLOSURE_PROJECT_ROADMAP_UI.md`.
