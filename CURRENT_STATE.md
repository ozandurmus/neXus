# neXus — Current State

Hot checkpoint reconciled on 2026-10-20 against `origin/main` at `f05e7ced`.
`project/QUEUE.md` and its tool-managed data own delivery state; repository
source proves implementation, not the current deployment or device health.

## Product today

UI2 is the maintained Java/React product; repository schema is **V167**.
Inventory, configuration, compliance, encrypted backups, Operations, Policy,
Packet Analysis and device system logs use masked `aiview` projections. Restore
from the UI remains disabled. Check Point policy collection is chunked by
domain with incremental reuse, bounded history and rule hits; scheduled runs
are changed-only on every domain (V115–V126, V130/V132, V163). Management
policy is configured intent, never proof of gateway enforcement. NSPM wave 1
hygiene and the lifecycle catalog (V144) landed; unresolved objects/hits stay
UNKNOWN and missing catalog matches do not invent dates. The device header's
"Identity verified" badge follows the real pinned key or certificate comparison
(V165). Authority: [NSPM decision](docs/design/PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md).

## Active build

`NXS-LOCAL-0374` — **IN_PROGRESS**: device system logs phase B. Check Point and
Palo Alto collectors (V160/V161/V167), masked view (rule set `logmask-v3`),
settings route `PUT /api/v2/config/system-logs` and Spike Detective data
collection are merged. Spike data and management-server collection are off
until a `security_admin` switches them on from Administration › Platform.
Open: 512 MiB spike cap not enforced by the pruner, masking residual gaps,
no real-environment acceptance. Authority:
[phase B decision](docs/design/PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md),
[contract](docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md),
[Spike Detective layout review](docs/design/SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md).

Same period, one line each (detail in `project/build_history.json`):

- `NXS-LOCAL-0373` AUTOMATED_VALIDATED — policy completeness (V163), CP hit dates, Policy Changes speed, rule view redesign, permissiveness scoring ([decision](docs/design/PO_DECISION_RECORD_2026_10_20_POLICY_PERMISSIVENESS_SCORING.md)).
- `NXS-LOCAL-0372` AUTOMATED_VALIDATED — UI design language and Fable D1/D2/P2/P3 ([doc](docs/design/UI_DESIGN_LANGUAGE.md)).
- `NXS-LOCAL-0371` AUTOMATED_VALIDATED — Packet Analysis real engine, first production run fixed ([decision](docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md)).
- `NXS-LOCAL-0370` — last build before the September–October period record; the interval between it and 0371 has no per-build record.

Failover execution plan PRs 1–10 landed (V133–V143): persisted mutation
switch, request-bound approvals, durable dispatch intents, fenced
reconciliation. **AUTOMATED_VALIDATED, not REAL_ENV_VALIDATED; no real
failover trial has been performed.** A pilot-preparation status lane is in
progress (`failover_pilot_preparation`). The mutation switch in the live
manifest authorizes nothing by itself: typed request, approval, fresh
readiness, one fleet mutation and uncertain-outcome fencing still apply.
Authority: [2026-10-06 decision](docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md),
[execution plan](docs/design/FAILOVER_EXECUTION_PLAN.md).

## Runtime and scheduling

Module runtime/endpoint ownership landed (V128/V129/V131): policy,
configuration and compliance have dedicated pods; other job modules remain
under controlled general-worker ownership. Full module-per-pod migration is
still in progress under the [runtime contract](docs/design/MODULE_POD_RUNTIME_CONTRACT.md).
Pre-deploy release snapshots support guarded rollback; isolated pre-merge
preview e2e checks the candidate with masked evidence and fails on page/API 4xx.
Release also applies the committed manifests of the policy, service,
configuration and compliance modules (the worker is still patched at run time),
and a ship retries when the image build failed only on a base-image layer
download. Scheduled system-log collection is enabled for the fleet.
Device polling/admission windows begin at **00:00 / 06:00 / 12:00 / 18:00
Europe/Istanbul** (default 60-minute windows). Full scheduled fleet backups
and daily MDS export instead run **once daily at 02:00 Europe/Istanbul**
(PO 2026-10-10, PR #570; admission 02:00–02:59, no missed-day backlog).
Backup retention is **7 days** (PO 2026-10-12, supplied in this brief;
current stored policy was not queried). The scheduling/disk incident and
manual manifest-deletion deviation are recorded in the
[backup incident](docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md).

## Packet Analysis and Data Sanitizer

`packet_analysis_shell` — **AUTOMATED_VALIDATED**: cases, streamed upload, RBAC,
dedicated `ui2-packet-analysis` worker with the real engine, read-only DuckDB
detail views, CLI parity, React workspace. aiview may run analyses (masked,
never raw). The first production run (2026-10-18) failed saving its result
(engine version CHECK) and was fixed; no successful production run is recorded
since. Not REAL_ENV_VALIDATED. Authority:
[port contract](docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md).

`data_sanitizer` — **AUTOMATED_VALIDATED**: keyed engine for pcap/pcapng, text,
CSV/XLSX and JSON with an encrypted reversible vault (V156); its serial and
known-identity passes are shared with the masked system-log view. Packet
Analysis and log-export hooks are off by default. Not REAL_ENV_VALIDATED.
Authority: [contract](docs/design/DATA_SANITIZER_CONTRACT.md).

## Open items and validation boundaries

- NSPM wave 2 change report and wave 3 policy lookup/offline analyzer are
  written in local lanes but not merged into this baseline. Compliance guidance
  has already landed independently. Policy permissiveness cannot yet model
  Palo Alto object precedence (`pan_object_precedence_for_permissiveness`).
- Per-device job serialization is written but not merged; endpoint transport
  admission is a separate existing safety boundary.
- Masking residual gaps (single-word unknown hostnames, unmeasured principal
  formats, XLSX serial columns, pcap) are open: `masking_residual_gaps_system_log_and_sanitizer`.
- Worker manifest drift (`manifest_drift_service_worker_configuration_policy`)
  and the Check Point aiview readiness script client error
  (`cp_aiview_readiness_script_client_error`) are open follow-ups.
- No full Java integration result is claimed here; this checkpoint ran no
  Gradle suite.
- CVE exceptions remain time-boxed in `security/baseline.yaml`: entries with
  review date **2026-10-31** (UBI runtime packages, the Packet Analysis engine
  image) fall due first; the rest review **2026-12-31**. Review is per
  rule/location; acceptance is not remediation.
- First attended failover trial, full pod migration, retention-policy
  verification and capacity monitoring remain engineering-owner follow-ups.
  This reconciliation performed no deployment or live validation.
