# UI2 v1 scope and service-topology brief

## Status and authority

**DRAFT — technical contract amendments pending.** The product direction below
records the user's explicit scope clarification on 2026-09-11. It requires no
repeat approval of that intent. This brief does not itself supersede FROZEN
contracts, enable a device action, or declare B1-1 complete.

Owner movement: `ui2_b1_01m_feature_service_topology`, immediately after B1-1
and before B1-2 schema implementation. Existing B1-1 runtime topology remains
the current implementation target.

## Requested product acceptance

UI2 is one living operator product. Its first product release integrates:

1. Discovery and authorized manual device enrollment.
2. Device inventory/interface evidence and configuration collection, preserving
   the distinction between configured intent and observed runtime state.
3. Compliance/check evaluation using applicable, sufficiently fresh evidence.
4. Reliable backup collection for selected registered targets, approved schedules,
   artefact validation, and secure storage/retention. Restore execution is not a
   required v1 release criterion; its existing design is preserved for later work.
5. Project Plan showing this product's development status and milestones, not a
   new operator project-management application.
6. Existing jobs, scheduling, and checks/runbook functionality. Reuse the existing
   design vocabulary; "Intellicheck" is a comparison, not a newly adopted name.
7. Product administration: identity/LDAP, web/TLS settings, product telemetry
   (SNMP/syslog), audit/log/debug, and scoped product restart/upgrade management.
   Exact acceptance and privileged-operation boundaries still require design.
8. Real controlled failover through the product on an explicitly supported and
   validated device/version/topology matrix. Readiness or dry-run alone does not
   satisfy this requested v1 outcome. The initial matrix is not yet selected.

Later candidates: firewall-rule consolidation/sanitation, custom script
execution, interactive device SSH, and device-log collection. Scheduling these
does not authorize their commands, access paths, or data retention.

Backup acceptance must distinguish collection success, artefact integrity,
storage protection, retention, and operator retrieval from proven device
recoverability. Deferring product-driven restore does not prove an artefact can
restore a device, and must not remove backup quality or custody requirements.

## Historical roadmap mapping

`project/roadmap.json` currently names `1.0` as **Production Platform Foundation**
(`GOVERN`) and controlled failover as a separate `OP.x` (`OPERATE`) track. The
product baseline is `0.7.7`. These planning labels are not percentage progress.
The requested product v1 combines relevant platform and feature outcomes into
one release acceptance map; retain the historical tracks and completed evidence
instead of relabeling them as completed UI2 capabilities.

## Independence objective

The user wants feature-specific development and deployment: a backup-only change
should not require rebuilding/restarting Operations, and an unavailable backup
service should not make unrelated usable product areas unavailable.

Design candidates are Devices/Discovery, Configuration, Compliance/Checks,
Backup/Recovery, Operations, Jobs/Scheduling, and Platform/Administration.
These are responsibility candidates, not an approved deployment count. Project
Plan and interface views do not inherently require standalone services.

The topology movement must define independent versioned artifacts, API/event
compatibility, data and migration ownership, and feature-worker ownership.
Preserve common device identity and device/HA-target execution coordination;
backup and failover cannot acquire independent, conflicting authority over the
same target. Shared infrastructure failures remain shared failure domains.

## Explicit contract changes to prepare

| Existing authority | Requested change / required reconciliation |
| --- | --- |
| `UI2_0_BASELINE_CONTRACT.md` section 2 D-7 and section 4 | Replace mandatory v1 restore and later-release failover with the requested backup/storage and controlled-failover acceptance. |
| `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` section 5.1 | Remove restore as a v1 exit condition without deleting its specification or weakening backup/artefact controls. |
| `UI2_0_DEVELOPMENT_WORKFLOW.md` B1/B2/REL/S | Map the eight product areas into release acceptance; reconcile `REL-FAILOVER-READINESS` with the requested execution scope. |
| B1-1 packaging; C1/C2/C3/C4; baseline section 1 | Review image/process/data ownership, the one-executor rule, service identity, and job coordination before approving feature-service splits. |
| `AGENTS.md`, `utils/action_taxonomy.py`, frozen OP.2 contracts | CLASS 2 remains unavailable until its existing authorization, command, trust, concurrency, approval, and real-environment gates are satisfied through an explicitly owned amendment/implementation movement. Product scope is not execution authorization. |

Use independent High-tier security/architecture review for the concrete failover
and distributed execution amendments. Do not replace the existing gates with a
feature flag or infer that deployment readiness supplies action permission.

## Validation boundary

The CRC evidence proves skeleton processes start and remain alive during the
reported observation window. The current `migrate` entry point checks a secret
file and exits; its completed Job is not evidence of a real Flyway migration or
database readiness. Re-audit B1-1 acceptance before closure rather than assuming
the outstanding Testcontainers run is its only unverified requirement.
