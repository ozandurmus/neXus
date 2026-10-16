# neXus — Current State

Hot checkpoint reconciled on 2026-10-14 against `origin/main` at `c29079fa`.
`project/QUEUE.md` and its tool-managed data own delivery state; repository
source proves implementation, not the current deployment or device health.

## Product today

UI2 is the maintained Java/React product; repository schema is **V144**.
Inventory, configuration, compliance, encrypted backups, Operations and Policy
use masked `aiview` projections. Restore from the UI remains disabled.
Check Point policy collection is chunked by domain, with incremental reuse,
bounded history and rule hits (V115–V126, V130/V132). Management policy is
configured intent, never proof of gateway enforcement.
NSPM wave 1 policy hygiene and the operator-imported lifecycle catalog landed
(V144); unresolved objects/hits remain UNKNOWN and missing catalog matches do
not invent dates. Compliance includes operator guidance and per-device reports;
guidance never applies remediation. Authority:
[NSPM decision](docs/design/PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md).

## Active build

`NXS-LOCAL-0370` remains the last recorded build in the queue, not the current
schema/release baseline. The September–October period record is still open;
this DOCS / STATE_UPDATE lane reconciles the hot checkpoint without rewriting
historical outcomes. Failover execution plan PRs 1–10 implementation landed,
including V133–V143: persisted mutation switch, request-bound authenticated
approvals, durable dispatch intents, fenced reconciliation and bond traffic
checks. Status: **AUTOMATED_VALIDATED**, not REAL_ENV_VALIDATED. **No real
failover trial has been performed yet.** The full Java integration gate remains
open; landed implementation does not satisfy the plan's real-trial closure.
Authority: [2026-10-06 decision](docs/design/PO_DECISION_RECORD_2026_10_06_FAILOVER_EXECUTION_ESTATE.md)
and its referenced [execution plan](docs/design/FAILOVER_EXECUTION_PLAN.md).
The live deployment manifest enables the mutation switch; preview disables it.
Neither setting authorizes execution: typed request, approval, fresh readiness,
one fleet mutation and uncertain-outcome fencing still apply.

## Runtime and scheduling

Module runtime/endpoint ownership landed (V128/V129/V131): policy,
configuration and compliance have dedicated pods; other job modules remain
under controlled general-worker ownership. Full module-per-pod migration is
still in progress under the [runtime contract](docs/design/MODULE_POD_RUNTIME_CONTRACT.md).
Pre-deploy release snapshots support guarded rollback; isolated pre-merge
preview e2e checks the candidate with masked evidence and fails on page/API 4xx.
Device polling/admission windows begin at **00:00 / 06:00 / 12:00 / 18:00
Europe/Istanbul** (default 60-minute windows). Full scheduled fleet backups
and daily MDS export instead run **once daily at 02:00 Europe/Istanbul**
(PO 2026-10-10, PR #570; admission 02:00–02:59, no missed-day backlog).
Backup retention is **7 days** (PO 2026-10-12, supplied in this brief;
current stored policy was not queried). The scheduling/disk incident and
manual manifest-deletion deviation are recorded in the
[backup incident](docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md).

## Packet Analysis (branch, not yet shipped)

`packet_analysis_shell` — **IMPLEMENTED, engine stub, pending ship** (`ship/rel-2-pa-shell`, after `ship/rel-1-integration`; unit and PostgreSQL integration suites green): cases, streamed
`packet_analysis_shell` — **AUTOMATED_VALIDATED** on `feature/packet-analysis-ui` (S14): cases, streamed
pcap/pcapng upload (2 GB per file, 100 GB store cap, 7-day retention with an hourly ledgered pruner), RBAC
(`packet_analysis_run` / `packet_analysis_raw_view` / `packet_analysis_read`, aiview masked only), dedicated
self-owned `ui2-packet-analysis` worker running `RealPacketAnalysisEngine` (ingest -> auto-assemble -> DuckDB engine ->
`report-<jobId>.json`, `findings-v11.json`, `project.duckdb`), read-only DuckDB detail views in `ui2-service` returning
`409 ANALYSIS_RUNNING` while queued/analyzing, CLI parity in `ui2/cli`, and operator React 18 workspace screens
(`echarts` 6.1.0, `@xyflow/react` 12.12.0). Not REAL_ENV_VALIDATED. Authority:
[decision record](docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md) and
[port contract](docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md).

## Data Sanitizer (branch, not yet shipped)

`data_sanitizer` — **IMPLEMENTED, pending ship** (`ship/rel-4-sanitizer`, after `ship/rel-3-pan-hit`): one keyed engine
(privacy HMAC key; prefix-preserving IP permutation, inventory pseudonyms) for pcap/pcapng (payload removed), text
logs, CSV/XLSX and JSON, with a reversible round trip through an encrypted per-job vault (V156, 90-day retention),
`/api/v2/sanitize`, CLI parity and the Sanitize screen (`data_sanitize_run` / `data_sanitize_revert`; aiview holds
neither). Packet Analysis and log-export hooks are off by default. Not REAL_ENV_VALIDATED. Authority:
[contract](docs/design/DATA_SANITIZER_CONTRACT.md).

## Open items and validation boundaries

- NSPM wave 2 change report and wave 3 policy lookup/offline analyzer are
  written in local lanes but not merged into this baseline. Compliance guidance
  has already landed independently.
- Per-device job serialization is written but not merged; endpoint transport
  admission is a separate existing safety boundary.
- `:integration-tests` does not compile on main because of dependency lock
  state (PO audit finding); not rerun here because this lane forbids Gradle.
  A green full Java regression is not claimed.
- CVE exceptions remain time-boxed in `security/baseline.yaml`: UBI runtime
  package entries review **2026-10-31**, Spring CVE-2026-47884 / CVE-2026-47890
  review **2026-11-05**, and older base-library acceptances review
  **2026-12-31**. Review is per rule/location; acceptance is not remediation.
- First attended failover trial, full pod migration, integration lock repair,
  retention-policy verification and capacity monitoring remain engineering-owner
  follow-ups. This reconciliation performed no deployment or live validation.
