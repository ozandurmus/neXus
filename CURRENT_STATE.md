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

## Production and real-environment posture

Development-ready, not production-ready. Production still needs OIDC/RBAC,
trusted TLS and SSH host keys, database role separation, NetworkPolicy, secret
management, audit retention, off-host recovery custody, and a restore drill.
Concurrency remains one per vendor pending real-environment evidence.
