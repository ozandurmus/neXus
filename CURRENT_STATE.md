# neXus — Current State

Hot checkpoint reconciled on 2026-11-10 against `origin/main` at `0d13c450`.
`project/QUEUE.md` and its tool-managed data own delivery state; repository
source proves implementation, not the current deployment or device health.

## Product today

UI2 is the maintained Java/React product; repository schema is **V197**.
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

`NXS-LOCAL-0383` — **IN_PROGRESS** (started 2026-11-10): enterprise stack
alignment phase 0 (Spring Security sign-in via ADFS SAML with LDAP and a
break-glass account, JSON logs, monitoring endpoints, file-based secrets, SAST
in CI); nothing implemented yet. Phase 1 waits for the PO's access-request
flows. Authority: [decision](docs/design/PO_DECISION_RECORD_2026_11_10_ENTERPRISE_STACK_ALIGNMENT.md).
Proposed NEXT (PO to confirm): finish the failover pilot.

Recorded since 2026-10-20 (detail in `project/build_history.json`):

- `NXS-LOCAL-0382` REAL_ENV_VALIDATED — UBI9 bases (Java 21.0.13), CVE baseline, ship/CI speed; HOST-A k3s v1.36.6, kernel -43 with its own AppArmor profile, rwsafe removed, registry pruned; e2e 80/80.
- `NXS-LOCAL-0381` AUTOMATED_VALIDATED — CP management facts: identity panel, hotfix levels (V195), MDS HA ACTIVE (V194/V196), per-Domain placement (V197, no live check yet), VSX VIP prefixes (54 -> 30).
- `NXS-LOCAL-0380` AUTOMATED_VALIDATED — policy credibility and hygiene (V191/V192; earlier-rule UNKNOWN 60,217 -> 56,085).
- `NXS-LOCAL-0379` AUTOMATED_VALIDATED — UI review integration, executive home, Reports R1 (REAL_ENV_VALIDATED), security fixes, masked notifications (V193).
- `NXS-LOCAL-0378` REAL_ENV_VALIDATED — path analysis phase A (V184–V188; 20/20 masked queries, first A6 `MATCH`).
- `NXS-LOCAL-0377` AUTOMATED_VALIDATED — backup deviations and size alerts (V176/V180/V189), local-log diagnostics (V181–V183).
- `NXS-LOCAL-0376` AUTOMATED_VALIDATED — failover pilot P0 closure; first CP pilot run 2026-10-21 stopped before the up step.
- `NXS-LOCAL-0375` AUTOMATED_VALIDATED — Change feed, System health, device Logs, Device events, `logmask-v4`.
- `NXS-LOCAL-0374` AUTOMATED_VALIDATED — system logs phase B; scheduled collection live (97 devices); spike cap open.

Failover execution plan PRs 1–10 landed (V133–V143): persisted mutation
switch, request-bound approvals, durable dispatch intents, fenced
reconciliation. **AUTOMATED_VALIDATED, not REAL_ENV_VALIDATED; no completed
real failover is recorded.** The first Check Point pilot run (2026-10-21)
stopped before the up step and was fixed (`NXS-LOCAL-0376`). The mutation switch in the live
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
download. **Development mode** (PO 2026-11-03): every scheduled ui2-service
task is off (`UI2_SCHEDULING_ENABLED=false`) except the system-log collection,
which has its own switch. Device polling/admission windows begin at **00:00 / 06:00 / 12:00 / 18:00
Europe/Istanbul** (default 60-minute windows). Superseded in part (PO
2026-10-20, [record](docs/design/PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md)):
manual jobs of `security_admin`/`operation_admin` are never held to the windows
and ask for a confirmation inside business hours (08:00–20:00); operator,
aiview and every scheduled job keep the windows. Full scheduled fleet backups
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
  has already landed independently.
- Per-device job serialization is written but not merged; endpoint transport
  admission is a separate existing safety boundary.
- Masking residual gaps (single-word hosts without a keyword, unmeasured
  principal formats, pcap) are open: `masking_residual_gaps_system_log_and_sanitizer`.
- Worker manifest drift (`manifest_drift_service_worker_configuration_policy`)
  and the Check Point aiview readiness script client error
  (`cp_aiview_readiness_script_client_error`) are open follow-ups.
- No full Java integration result is claimed here; this checkpoint ran no
  Gradle suite.
- CVE exceptions remain time-boxed in `security/baseline.yaml`; the remaining
  UBI base entries review **2026-11-14**, the rest **2026-12-31**. glibc waits
  for a Red Hat fix (`ubi_base_glibc_fix_pending`). Acceptance is not remediation.
- First attended failover trial, full pod migration, retention-policy
  verification and capacity monitoring remain engineering-owner follow-ups.
  This reconciliation performed no deployment or live validation.
