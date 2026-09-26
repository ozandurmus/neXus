# neXus — Current State

Hot-path checkpoint only. Durable delivery state is in `project/QUEUE.md` and
the generated project data it represents; contract succession is in
`docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md`.

## Product today

neXus (UI2, Java) runs on HOST-A against the live estate — 122 devices under
management (2026-09-26) — and the Product Owner uses it daily under the
`aiview` persona. Vendors read for real: Check Point (gateways, ClusterXL, VSX,
MDS), Palo Alto (firewalls, HA, Panorama), Infoblox Grid Manager and members,
Radware Cyber Controller and DefensePro, Symantec Management Center and its
ProxySGs, FortiManager and FortiGates (discovery, inventory, configuration
plane). Implemented and awaiting a first device: Cisco ASA, Pulse Secure.
Screens: executive Overview (estate map), one device screen (inventory,
configuration, backup and identity as tabs), Compliance (four frameworks),
Backups (encrypted, downloadable under RBAC, listed and compared), Operations,
Administration. Adding a device runs a server-side onboarding flow (identity →
inventory → configuration, V77). Restore is deliberately disabled; the fleet
backup schedule is not switched on.

## Active build

Continuous deploys 2026-09-25/26 on HOST-A (schema 90); the next tool starts
from `docs/design/CODEX_HANDOVER_2026_09_26.md`. Predecessor build record
`NXS-LOCAL-0370` (Infoblox, observed facts, HTTPS inventory). A build record
for V73–V89 is still to be written. Open gates: first real runs of Cisco ASA,
Pulse Secure, ProxySG discovery import; FortiManager physical link remains
`UNSUPPORTED/UNKNOWN` after V87's per-port CLI form was rejected and V88/V89 lacked an explicit link field on the real appliance.
The approved Debug/Parser screen is deployed but awaits a first PO-approved device read
(`docs/design/FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md`, FROZEN). It accepts one V89 FortiManager
read and shows up to 64 masked structural line labels. Arbitrary commands are refused;
physical link and the first real port5 run remain `UNKNOWN`/unvalidated.
Debug Phase 1 is automated-validated: all-device selection, typed gated reads,
persistent execution history and role-aware output, with prior PO approval for agent runs
(`docs/design/DEBUG_OPERATIONS_SUCCESSOR_DECISION_2026_09_26.md`). Saved commands
are deferred to Phase 2; automation and failover are outside this build.

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
