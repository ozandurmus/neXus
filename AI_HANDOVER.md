# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this conflicts with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

Read in this order: `AI_START_HERE.md`, `CURRENT_STATE.md`, `AI_HANDOVER.md`,
`project/QUEUE.md`, then the one governing record for the movement. Before
relying on an older frozen design record, consult
`docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md`.

You are the Product Owner assistant. `roles/PO.md` is your whole cold start —
§1b lists the authorities granted in session, including that you review and
merge ordinary work yourself and that governance changes need the Product
Owner's word first. Read it before acting on anything below.

## Snapshot

The Java product has authenticated local administration, encrypted credential
references, UI discovery, device add carrying a role, inventory and
configuration collection, encrypted artefacts with a structural deviation
summary, a device backup tab that lists what is retained and can request a
backup with a reason, and collection refused against a management server.
Restore and the scheduler stay disabled. Every live outcome is UNVERIFIED: no
feature has reached a real device and the backup pilot allowlist is empty.

## What this session did

**Found the product's writes were entirely broken and fixed it.** The CSRF
token gate E1 required was minted, stored on the session row, and never
returned to the client in any response or cookie. Reads worked, so the product
looked healthy while nothing could be saved — a credential the Product Owner
believed had vanished had never been stored at all. The frontend's own file
header had described the gap as "a disclosed, temporary limitation" and named a
movement that never closed it.

**Repaired device enrolment**, broken by a movement merged green: it made the
device role required and never took the caller that omits it into scope. The
lesson is now checklist item 5b.

**Made the backup line visible end to end** — the device tab, the trigger with
its reason, refusals shown in the server's own words, the structural deviation
summary comparing on `(context, section, source)` so a provenance move is not
swallowed, and the opaque-archive explanation.

**Audited the backup line against its four frozen records.** 55 clauses: 21
implemented and tested, 22 not implemented, 13 untested, 7 not evaluable. The
Tier 1 findings are unfixed and are the highest-value product work available:
a storage path reaching an HTTP response through `artefact_id`; the reason and
role disappearing between admission and execution; retrieval auditing that is
not fail-closed; one aggregate audit step for a whole class-1 sequence; and a
run that can report success while its bytes are unreachable.

**Concluded that failover is not close.** Only a read-only, fail-closed
readiness shell can ship before any of the fourteen open decisions is answered.

**Moved the environment, and wrote the rules for it.** Details below.

## Authorities, as exercised here

Merged ordinary product and tooling work after `verify.passed` and a diff read.
Held back every change to `AGENTS.md` and `roles/` for the Product Owner's word.
Closed a movement the Product Owner rejected on sight, with the lesson recorded.
Operated the live environment: rolled the image twice, created a Secret the
manifests declared and the cluster lacked, restarted a dead port-forward,
started a stopped VM, and took a PostgreSQL dump verified by reading it back.
Ran four movements in parallel on the Product Owner's instruction, above
`roles/PO.md` §3's standing limit of two — that conflict is open and named
below. Wrote small integration-time corrections into worker output (a docstring,
a misplaced constant), which `roles/PO.md` §1 forbids as written — also open.

## The environment moved, and why

The laptop cannot host it. With the corporate VPN up, `10/8`, `172.16/12` and
`192.168/16` all route to the tunnel — the cluster's own service and pod blocks
and the hypervisor's node address included — and that hypervisor's NAT subnet is
fixed and not configurable. The CGNAT range was measured not to be captured.
The Product Owner is the infrastructure authority and rules out both a VPN
exception and a separate machine.

`PO_DECISION_RECORD_2026_09_15A` now fixes the destination and the rules:
Kubernetes (`k3s`) on a registered host the Product Owner authorized in session,
service CIDR `100.64.32.0/20` and pod CIDR `100.65.0.0/16`, no host container
tool on any path, no `sudo`, privileged group or runtime socket for an agent,
and the incumbent product's data prohibited outright. `docs/design/HOST_REGISTER.md`
is the allowlist — a host absent from it authorizes no command, including a read.
`docs/operations/HOST_LEDGER_HOST-A.md` holds the baseline as entry zero.

A Docker Compose deployment was proposed by this assistant, contradicted `12B`
§1 and `01C` BP-2, and is refused. The decisive reason is not the rule: `k3s`
runs on containerd and never touches the incumbent's Docker daemon, whose
control is root-equivalent on the host.

## Exact next action

Measure, read-only, whether `k3s`'s CNI would collide with the rules Docker
already manages on `HOST-A`, and record it as a ledger entry. Then prepare the
installation commands for the human to run under `HOST_W2`. The agent performs
no host write.

Before the cluster is rebuilt, export the four hand-created secrets. A verified
dump exists — 40 tables, read back with `pg_restore --list` — but the dump alone
decrypts nothing.

Seats are vendor-neutral and the Product Owner assigns them per session
(`15A` §4a, `roles/PO.md` §1b): a worker never holds host reach, only one seat
holds it at a time, and the ceiling stays the register's whoever sits there.
The Antigravity bridge is now described by shape in
`docs/reference/PROVIDER_OPERATING_NOTES.md` -- it goes stale whenever the
desktop session restarts, and no part of it belongs in the repository.

## Open with the Product Owner

- Two changes to `roles/PO.md` that alter existing rules rather than add to
  them -- the two-movement parallelism limit, and the prohibition on the
  assistant writing any implementation -- were approved in session and are
  written. They are in the same pull request as the host record, which still
  needs the Product Owner's word because it touches `AGENTS.md` and `roles/`.
- The backup pilot device is still unnamed, so every backup is refused.
- `AUTH-PLACEMENT` (`13D` §3) is open and blocks any second authenticated
  service — which is what backup and failover would each become.
- Off-host key custody (`14I` AL-4) and the long-term artefact location
  (`14I` AL-2) stay open. Retention was decided: disk-bound, no fixed limit.
- The brand wordmark is unresolved. A worker's attempt was rejected on sight;
  the identity study at `docs/design/ui2_mockups/logo-identity-explorations.png`
  option D is the reference and candidates should be put to the Product Owner
  rather than specified to a worker in prose.

## Test delta

`tests/test_frozen_contract_shapes.py` is new: a frozen contract can no longer
drift in content while its status line still reads FROZEN.

## New risks

Namespace-scoped RBAC is not a blast radius: whoever can create a workload can
mount what that namespace can mount and select its service accounts. An agent
free to replace workloads therefore cannot also be promised not to reach live
credentials, which is why `PO_DECISION_RECORD_2026_09_15A` §6 splits `DEV` from
`LIVE` and makes `LIVE` something the agent may not switch itself into.
