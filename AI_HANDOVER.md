# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this conflicts with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

Read in this order: `AI_START_HERE.md`, `CURRENT_STATE.md`, `AI_HANDOVER.md`,
`project/QUEUE.md`, then the one governing record for the movement. Before
relying on an older frozen design record, consult
`docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md`.

## Snapshot

The Java product has authenticated local administration, encrypted credential
references, UI discovery, device add with a role, inventory and configuration
collection, encrypted configuration artefacts with a structural deviation
summary, a device backup tab that lists retained artefacts and can request a
backup with a reason, and collection refused against a management server.
Restore and the scheduler stay disabled. Live device outcomes remain UNVERIFIED:
no feature has reached a real device and the pilot allowlist is still empty.

## What changed this session

- **Every write in the product was failing.** The CSRF token the gate required
  was minted, stored and never returned to the client. Reads worked, so nothing
  looked wrong. A credential the Product Owner believed had vanished had never
  been stored. Fixed; the token now rides the authenticated session-status
  response, `no-store`.
- Device enrolment was broken by a movement that made the role required without
  taking its caller into scope. Fixed, and the dialog now names the role in the
  product's words.
- The backup line became visible end to end: the device tab, the trigger with
  its reason, the refusal shown in the server's own words, the structural
  deviation summary comparing on `(context, section, source)` so a provenance
  move is not silently swallowed, and the opaque-archive explanation.
- The workbench stopped calling a handed-over movement a dead process; the
  ledger stopped printing a fabricated zero where it has no measurement.
- An audit of the backup line against its four frozen records found 55 clauses:
  21 implemented and tested, 22 not implemented, 13 untested, 7 not evaluable.
  Tier 1 findings include a storage path reaching an HTTP response through
  `artefact_id`, and the reason and role disappearing before execution.
- A failover analysis concluded that only a read-only, fail-closed readiness
  shell can ship before any of the fourteen open decisions is answered.

## The environment moved, and why

The local cluster is unusable: the corporate VPN captures `10/8`, `172.16/12`
and `192.168/16`, including the cluster's own blocks and the hypervisor's node
address, and that hypervisor's NAT subnet is fixed. The CGNAT range was measured
not to be captured.

`PO_DECISION_RECORD_2026_09_15A` now fixes the destination, the runtime and the
rules: Kubernetes (`k3s`) on a registered host, CGNAT service and pod blocks, no
host container tool on any path, no `sudo` or container socket for an agent, and
the incumbent workload untouchable. `docs/design/HOST_REGISTER.md` is the
allowlist; `docs/operations/HOST_LEDGER_HOST-A.md` carries the evidence and
already holds the baseline as entry zero.

A verified PostgreSQL dump of the current cluster exists outside it, read back
with `pg_restore --list`: 40 tables. The four hand-created secrets have **not**
yet been exported and must be, before the cluster is rebuilt — the dump alone
cannot decrypt anything.

## Exact next action

Measure whether `k3s`'s CNI would collide with the rules Docker already manages
on `HOST-A`, read-only, and record it as a ledger entry. Then prepare the
installation commands for the human to run under `HOST_W2`, including the
CGNAT service and pod CIDRs. The agent performs no host write.

## Test delta

`tests/test_frozen_contract_shapes.py` is new: a frozen contract can no longer
drift in content while its status line still reads FROZEN.

## New risks

`AUTH-PLACEMENT` (`13D` §3) is still open and blocks any second authenticated
service, which is what backup and failover would each become. Namespace-scoped
RBAC is not a blast radius: whoever can create a workload can mount what that
namespace can mount, so an agent free to replace workloads cannot also be
promised not to reach live credentials — the `DEV`/`LIVE` split exists for that
and `LIVE` is not a switch the agent may flip.
