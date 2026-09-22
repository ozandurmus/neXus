# neXus — Current State

Hot-path checkpoint only. Durable delivery state is in `project/QUEUE.md` and
the generated project data it represents; contract succession is in
`docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md`.

## Product today

neXus (UI2, Java) runs on HOST-A against the live estate — 105 devices, 39
clusters — and the Product Owner uses it daily under the `aiview` persona.
Inventory (with serial, version, hotfix / content versions, uptime),
configuration (section projection, cluster member DIFF, change detection),
compliance (four frameworks over 102 firewalls), Check Point and Palo Alto
backups (encrypted, downloadable under RBAC, listed and compared), jobs with
filters and export, nexus-cli, notifications (syslog / SMTP relay, built but
not yet pointed at real targets), a service and storage view, and the
Overview (exception-and-evidence screen, frozen contract) are deployed.
Restore is deliberately disabled. Backup scheduling exists; the fleet
schedule is not switched on. Evidence-weighted progress of the Java roadmap:
57 % (2026-09-23).

## Active build

`NXS-LOCAL-0368` — `automated_validated` (2026-09-23, deployed on HOST-A):
Overview rebuilt per `docs/design/OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md`
(FROZEN), collision-free aiview pseudonyms (V53), and the menu-tour fixes of
`NXS-LOCAL-0367` (heap exhaustion, speed, notifications, service view). The
open gate is the Product Owner's aiview acceptance of the Overview. NEXT:
Overview acceptance and per-member settings not counted as drift (38 of 39
clusters show a member DIFF today). Records in `project/build_history.json`.

## Open Product Owner decisions

The queue owns the complete decision list. The current material decisions are
the OP.2 operational-write limits and safeguards (`C-D4`–`C-D8`), failover
vendor semantics (`D-V1`–`D-V9b`), enrollment exposure, LDAP TLS trust, and
the Check Point backup asynchronous semantics. These decisions do not enable
restore or scheduling.

## Development environment

HOST-A (k3s, namespace `ui2`) is the development and pilot host; builds run in
the cluster (kaniko) and are deployed with `scripts/hosta_deploy.sh`, which
refuses with jobs in flight and stops at the first failure. What an agent may
execute there is fixed by the host register and the 2026-09-19 amendment in
`AGENTS.md`. The repository on GitHub is public; the Product Owner's decision
on visibility is open (`docs/design/LEGACY_PYTHON_SEPARATION_PLAN.md` §4).

## Production and real-environment posture

Development-ready, not production-ready. Production still needs OIDC/RBAC,
trusted TLS and SSH host keys, database role separation, NetworkPolicy, secret
management, audit retention, off-host recovery custody, and a restore drill.
Concurrency remains one per vendor pending real-environment evidence.
