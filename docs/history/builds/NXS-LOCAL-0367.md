# NXS-LOCAL-0367 — Menu-tour fixes: heap exhaustion, speed, notifications and remote logging, service and storage view

status: automated_validated · completed: 2026-09-23 · movement: IMPLEMENTATION

Released 645 MB of unread configuration text that exhausted the service and worker heap, sped up aiview masking and the per-request gate, fixed demo data on HA readiness, and added Administration > Notifications (syslog, SMTP relay, two triggers) and > System (pods, storage) with CLI counterparts; memory limits raised to 2 GiB. Deployed and inspected under aiview.

## Authority and evidence

- `docs/design/SCRIPT_EXECUTION_CONTRACT.md`

## Real-environment note

Deployed to HOST-A with `scripts/hosta_deploy.sh` (watched, fail-fast). Screens inspected under the aiview persona; Product Owner acceptance of the Overview is the open gate.
