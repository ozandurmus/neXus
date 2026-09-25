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

`NXS-LOCAL-0369` — `automated_validated` (2026-09-23..25, deployed on HOST-A; schema 72 after the 2026-09-25 night:
Infoblox backup fixes, grid members and member facts (V71/V72), observed facts refreshed on every read, the HTTPS
inventory job -- see `AI_HANDOVER.md`; a build record for these is still to be written):
Radware backup and discovery through the Cyber Controller and the controller's
own SFTP-pushed backup (V65–V70), device-list / backup / add-device rework,
deploys that never replace the worker under a running job, and HOST-A
reinstalled for neXus alone and restored from a verified export
(`docs/design/HOST_A_REBUILD_RUNBOOK.md`). Open gate: the Product Owner's aiview
acceptance on the rebuilt host (backlog `hosta_rebuild_real_env_acceptance`):
on 2026-09-25 three of its four checks passed (pseudonyms unchanged, Check
Point and Palo Alto Collect, an old backup's Contents); the Cyber Controller's
SFTP push to HOST-A fails because the controller pins the host's pre-reinstall
SSH host key (backlog `cyber_controller_known_hosts_after_hosta_rebuild`).
Predecessor `NXS-LOCAL-0368` (Overview, collision-free pseudonyms): Overview
acceptance still open. Records in `project/build_history.json`.

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
