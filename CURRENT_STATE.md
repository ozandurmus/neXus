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

`NXS-LOCAL-0285` — `done`: Check Point SSH inventory worker finished, hybrid topology contract signed.
`UI Updates` — `done`: deployed LDAP UI, Select All, Role Display to remote K3s cluster.

`NXS-LOCAL-0176` — `automated_validated`: expire a past-deadline session on
read so it cannot block login. The newest merged movement, its predecessor
records, and the current roadmap pointer are in `project/QUEUE.md` and
`project/build_history.json`.

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
