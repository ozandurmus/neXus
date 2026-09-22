# neXus — Current State

Hot-path checkpoint only. Durable delivery state is in `project/QUEUE.md` and
the generated project data it represents; contract succession is in
`docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md`.

## Product today

The Java product requires local login. An authenticated operator can administer
local identities, manage encrypted credential references, discover Check Point
or Palo Alto candidates from the UI, see already-imported candidates, add a
device, and run the enrollment confirm. Inventory collection persists the
inventory and exposes its cluster view. Configuration collection exposes an
index view and stores encrypted artefacts. Check Point gateway backup is gated
to the pilot allowlist. The Administration screen reads the project-plan
payload from the repository’s project data.

Discovery command gates are approved; the repository records vendor measurement
findings for inventory and configuration. Field bindings, live discovery,
inventory, configuration, and backup outcomes remain **UNVERIFIED** until
their specified real-device runs. Automated tests do not replace those runs.
Restore is deliberately disabled. The scheduler is deliberately deferred and
has no enabled product path.

## Active build

`NXS-LOCAL-0366` — `automated_validated` (2026-09-22, deployed on HOST-A):
the day's four builds — backup download with RBAC, archive listing and
compare, jobs screen and nexus-cli (`NXS-LOCAL-0363`); Configuration screen
rebuilt with the cluster DIFF view (`0364`); platform identity facts —
serial, version, hotfix, content versions, uptime (`0365`); automation,
Script Execution and scheduled device-write decisions plus vendor contracts
(`0366`). NEXT: `script_execution_module` slice 1. Records in
`project/build_history.json`, detail under `docs/history/builds/`.

`NXS-LOCAL-0347` — `in_progress`: real-environment validation, under the
`aiview` persona, of the Palo Alto HA cluster presentation that
`NXS-LOCAL-0346` was graded on but never received, plus the delivery-grade
correction and machine-authority backfill for `NXS-LOCAL-0331`–`0346`.
Movement record: `relay/NXS-LOCAL-0347-real-environment-validation-of-pan-ha-cl.json`.

`NXS-LOCAL-0346` — `automated_validated`: Palo Alto PAN-OS HA clustering
reconciliation per official discovery API evidence, reciprocal serial
pairing, jOOQ lateral-join fix, and deterministic `<base>-CLS` title
derivation for presentation only.

Predecessor builds carry one structured record each in
`project/build_history.json`; `project/QUEUE.md` is the cold-start view. No
predecessor detail is repeated here.

## Open Product Owner decisions

The queue owns the complete decision list. The current material decisions are
the OP.2 operational-write limits and safeguards (`C-D4`–`C-D8`), failover
vendor semantics (`D-V1`–`D-V9b`), enrollment exposure, LDAP TLS trust, and
the Check Point backup asynchronous semantics. These decisions do not enable
restore or scheduling.

## Development environment

Moving off the Product Owner's laptop: the corporate VPN captures every RFC1918
range the local cluster needs. The destination is a registered host that also
carries another product's production workload, on Kubernetes, on CGNAT blocks.
What an agent may execute there is a separate authority from the network action
taxonomy and is fixed by
`docs/design/PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md`,
with the allowlist in `docs/design/HOST_REGISTER.md`, the step order in
`docs/operations/HOST_A_MIGRATION.md` and the record of what was done in
`docs/operations/HOST_LEDGER_HOST-A.md`. The migration is at phase A. No host
write has been performed.

## Production and real-environment posture

Development-ready, not production-ready. Production still needs OIDC/RBAC,
trusted TLS and SSH host keys, database role separation, NetworkPolicy, secret
management, audit retention, off-host recovery custody, and a restore drill.
Concurrency remains one per vendor pending real-environment evidence.
