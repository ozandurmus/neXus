# neXus — Current State

Hot checkpoint reconciled on 2026-11-23 against `origin/main` at `cb8277c3`; ships through 2026-11-22 are merged (live deploy not re-verified here).
`project/QUEUE.md` and its tool-managed data own delivery state; repository
source proves implementation, not the current deployment or device health.

## Product today

UI2 is the maintained Java/React product; repository schema is **V261** (live V242 at the last check).
Inventory, configuration, compliance, encrypted backups, Operations, Policy,
lifecycle and security checks, Packet Analysis and device system logs use
masked `aiview` projections. Restore from the UI remains disabled. Sign-in uses
Active Directory through a service bind (V204/V205). Check Point policy
collection is chunked by domain and changed-only on schedule; a partial
collection never replaces a complete snapshot. Management policy is configured
intent, never proof of gateway enforcement. License, contract and Live Patch
reads run fleet-wide, and advisories are matched from a fixed-source intake
through the proxy. Scheduled jobs run again since 2026-11-10, each family with
an audited switch (V203). Cisco ASA and FortiGate have identity, HA facts and failover readiness (V209/V210; readiness only). Authority: [NSPM decision](docs/design/PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md).

## Active build

`NXS-LOCAL-0391` — **IN_PROGRESS** (started 2026-11-13): delivering the
release queue below, one PR per lane after a live migration dry run.
`NXS-LOCAL-0383` (enterprise alignment phase 0) stays IN_PROGRESS: directory
sign-in, JSON logs, metrics, CodeQL and Spring Security steps 1-5 landed; SAML stays off until ADFS metadata exists.

Shipped through batch 14 (2026-11-16, live to V242; detail in `project/build_history.json`): ASA readiness, zone matrix 1-3, ACI step 1, lifecycle catalog, JSON logs, per-store quota, support bundles, Spring Security steps 1-3, vendor upload, SAML off by default, authorization map, database roles. Applied by hand 2026-11-16: database ingress NetworkPolicy, platform backup script S1.

Since 2026-11-17: FortiGate FGCP readiness, ASA readiness on for all five pairs (all READY 2026-11-18), Traffic and ARP check signed off (V249), batch b37, MDS export back on the nightly 02:30 schedule (first run 2026-11-19, unverified), Panorama change-actor trial on (commits logged Submitted are not joined), Policy installation P1 read-only monitoring FROZEN and live, ASA saved-state relation (V250, keyed by artefact V253). Drafts only: policy installation P2-P4, ASA failover execution.

Since 2026-11-18, on main and not live-verified: Script Execution slices 1-2 (V251, V258), static route R0 reads only (V252), VSX chassis failover (V255/V256), worker authority recheck (V260), database roles step 0 (V261), aiview read-only admin slice 1 (off), Overwatch rename R1, and the read-only alternative Estate view beside Standard.

Open PO decisions: product name; narrowing the ASA identity ACEs; the off-host recovery target; ADFS metadata; the deploy scan parallelism.

Recorded since 2026-11-10 (detail in `project/build_history.json`):

- `NXS-LOCAL-0390` COMPLETE — zone matrix contract FROZEN; ACI, recertification and topology designs; GOV.ORCH.9.
- `NXS-LOCAL-0384`-`0389` — fleet-wide lifecycle reads and advisory intake (V200-V205), ASA/FortiGate failover phase 0 (V209/V210), navigation redesign, `/opt/nexus` deploy tree, job switches (V203), directory sign-in.
- Earlier: `NXS-LOCAL-0374`–`0382`.

Failover execution plan PRs 1–10 landed (V133–V143). The Check Point and Palo
Alto pilot runs finished DONE/SUCCEEDED on 2026-10-22 (`failover_run`). The
mutation switch authorizes nothing by itself: typed request, approval, fresh
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
configuration, compliance and worker modules (the worker joined in batch 3),
and a ship retries when the image build failed only on a base-image layer
download. Development mode ended on 2026-11-10: the manifest carries
`UI2_SCHEDULING_ENABLED=true` and each job family has its own audited switch. Device polling/admission windows begin at **00:00 / 06:00 / 12:00 / 18:00
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

- NSPM wave 2 and 3 and per-device job serialization are not merged here.
- Support bundles: all vendors built; vendor upload untested against real vendor hosts; PO-attended pilot open.
- Database roles: V242 roles exist, no workload switched. Module pods: backup pod at 0 replicas, cutover not done.
- Fleet facts (2026-11-16): 136 devices, 40 clusters with daily failover readiness checks, about 1,450 automated jobs a day.
- Open follow-ups: nightly e2e fails on a legitimate Platform status warning; cisa_kev fetch refused during a deploy; Trivy cache claim; failover four-eyes check before the SCC handover.
- Masking residual gaps are closed in rules by logmask-v7/v8 (queued), not yet
  measured against a real masked sample (`masking_residual_gaps_system_log_and_sanitizer`);
  the raw-versus-masked aiview comparison is open (`aiview_masking_leak_audit`).
- Real-env validation of the worker manifest sync
  (`manifest_drift_service_worker_configuration_policy`) and the Check Point aiview readiness script client error
  (`cp_aiview_readiness_script_client_error`) are open follow-ups.
- No full Java integration result is claimed here; this checkpoint ran no
  Gradle suite.
- CVE exceptions remain time-boxed in `security/baseline.yaml`; the remaining
  UBI base entries review **2026-11-14**, the rest **2026-12-31**. glibc waits
  for a Red Hat fix (`ubi_base_glibc_fix_pending`). Acceptance is not remediation.
- Full pod migration, retention-policy verification and capacity monitoring
  remain engineering-owner follow-ups.
  This reconciliation performed no deployment or live validation.
