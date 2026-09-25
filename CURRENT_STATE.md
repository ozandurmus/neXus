# neXus — Current State

Hot-path checkpoint only. Durable delivery state is in `project/QUEUE.md` and
the generated project data it represents; contract succession is in
`docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md`.

## Product today

neXus (UI2, Java) runs on HOST-A against the live estate — 110 devices in the
registry (108 enrolled) — and the Product Owner uses it daily under the `aiview`
persona. Inventory (with serial, version, hotfix / content versions, uptime),
configuration (section projection, cluster member DIFF, change detection),
compliance (four frameworks), backups (Check Point, Palo Alto, Check Point MDS
export, Radware through the Cyber Controller and the Cyber Controller's own
configuration; encrypted, downloadable under RBAC, listed and compared, orphan
backups deletable), jobs with filters and export, nexus-cli, notifications
(built, not yet pointed at real targets), a service and storage view, and the
Overview (exception-and-evidence screen, frozen contract) are deployed.
Restore is deliberately disabled. Backup scheduling exists; the fleet
schedule is not switched on. Evidence-weighted progress of the Java roadmap:
57 % (2026-09-23).

## Active build

`NXS-LOCAL-0370` — `automated_validated` (2026-09-25, deployed on HOST-A, schema 72):
the Infoblox Grid Manager enrolled, backed up and collected for real (grid
members and member facts, V71/V72), observed device facts refreshed on every
read (the upgraded MDS shows R82), `https_inventory_collect` for Infoblox and
the Radware Cyber Controller, backups only back up. Open gates: the Cyber
Controller Backup Now (stale known_hosts on the controller), DefensePro live
reads, configuration reads for HTTPS vendors. Predecessor `NXS-LOCAL-0369`
(Radware via Cyber Controller, HOST-A rebuilt): three of four rebuild
acceptance checks passed. Records in `project/build_history.json`.

## Open Product Owner decisions

The queue owns the complete decision list. The current material decisions are
the OP.2 operational-write limits and safeguards (`C-D4`–`C-D8`), failover
vendor semantics (`D-V1`–`D-V9b`), enrollment exposure, LDAP TLS trust, and
the Check Point backup asynchronous semantics. These decisions do not enable
restore or scheduling.

## Development environment

HOST-A is neXus's own host since its 2026-09-25 reinstall (Ubuntu 26.04.1,
k3s v1.36.4, namespaces `ui2` and `ui2-build`, the whole 1 TB disk; no other
product on it). Builds run in the cluster (kaniko) through the corporate proxy
and are deployed with `scripts/hosta_deploy.sh`, which pushes the code to the
host's own repository, stops at the first failure and replaces the worker only
when no job is running. The agent works there through its own account with
journal-logged sudo; deleting or irreversible commands need the Product Owner's
yes first, and the host is never a jump server
(`docs/design/PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md`).
The repository on GitHub is public; the Product Owner's decision on visibility
is open (`docs/design/LEGACY_PYTHON_SEPARATION_PLAN.md` §4).

## Production and real-environment posture

Development-ready, not production-ready. Production still needs OIDC/RBAC,
trusted TLS and SSH host keys, database role separation, NetworkPolicy, secret
management, audit retention, off-host recovery custody, and a restore drill.
Concurrency remains one per vendor pending real-environment evidence.
