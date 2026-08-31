# RB.3c — CP management export + consistency groups (`migrate_server export` / `mds_backup`)

## Status

**PROPOSED CONTRACT — BLOCKED. Not frozen, not implemented.**

Blockers:

1. **`D3`** — the `operational-write` class decision. Same gate as RB.3b; this
   build cannot precede it.
2. **`D5`** — recovery-volume retention floor and total storage budget
   (architecture §13). Management exports are the largest artifacts in the
   recovery plane by an order of magnitude; holding them at the same GFS depth
   as a PAN device state is a storage decision, not an engineering default.
3. **`E1` (new, raised by this contract)** — the §7.6 classification of
   `migrate_server export` / `mds_backup` as `operational-write` is **owed
   verification**. See design decision C1. If either command stops or degrades
   management services, it is not the same class as `add backup local` and this
   contract must be re-cut against a stricter gate.
4. **`D4`** (backup credential identity) and **`D6`** (permanent taxonomy
   adoption) — shared with RB.3b.

RB.3c should be sequenced **after** RB.3b has a watched real-environment run.
The gateway case is the smaller blast radius and proves the `operational-write`
machinery; the management servers are where the platform's own evidence chain
lives.

## Objective

Two things RB.3b does not cover:

1. **The management database.** A Gaia system backup of a gateway restores that
   gateway. Nothing in RB.0–RB.3b captures the Security Management Server or
   Multi-Domain Server — the policy, objects, and the entire administrative
   estate. Losing an MDS is a larger event than losing a gateway, and the
   platform currently has no recovery evidence for it at all.
2. **Consistency groups.** Check Point's guidance is that management HA members
   are backed up **at the same time**; a scheduler that walks servers
   sequentially produces a mutually inconsistent set that looks like a backup
   and is not one (architecture §3.1). This is the first workload in the
   platform where per-device collection is structurally wrong.

## Scope

### In scope

1. **`checkpoint/checkpoint_management_export_collector.py`** — a
   `RecoveryCollector` producing `cp_mgmt_export` (SMS, `migrate_server export`)
   and `cp_mds_backup` (MDS, `mds_backup`). Both classes already exist in
   `utils/recovery_manifest.RMA_GRADE_BY_CLASS`.
2. **Consistency groups** — `utils/recovery_consistency_group.py`: group
   definition, bounded-window execution, `INCONSISTENT` marking, and the
   readiness rule that an `INCONSISTENT` group is **not** readiness evidence.
   The RB.1 store already reserves `groups/` (contracts §2).
3. **`manifest.consistency_group`** — `utils/recovery_manifest.build_manifest`
   already accepts a `consistency_group` parameter that nothing populates.
   This build populates it.
4. **Retention depth for management artifacts** — whatever `D5` decides,
   expressed in the RB.1 retention policy rather than hardcoded.
5. Contract amendments E1–E4.

### Explicitly out of scope

- Gateway backup — RB.3b.
- Attestation — RB.3a.
- Restore, of a gateway or of a management server — `RB.6`, `OP.2` bar.
  Restoring an MDS is the highest-blast-radius operation in this entire design
  and is not made closer by this build.
- Domain-level / per-CMA selective export. `mds_backup` is whole-MDS; a
  per-domain export is a separate artifact class and a separate contract.

## Design decisions

### C1 — The `operational-write` classification of these two commands is owed verification (`E1`)

Contracts §7.6 currently files `migrate_server export` and `mds_backup` under
`operational-write`, "same shape as §7.3 with larger bounds". That inheritance
is the part this contract will not accept on trust.

`add backup local` earns `operational-write` because it writes a file and
changes nothing else. The management commands are not obviously in the same
class:

- `mds_backup` operates on a running Multi-Domain Server and, depending on
  invocation, may **stop Multi-Domain Server processes** for the duration.
- `migrate_server export` runs against a live management database and may
  require, or benefit from, a quiesced management server.

If either command interrupts management services, it is **not**
`operational-write` under architecture §5's own definition — "creates/removes a
transient artifact, consumes a bounded resource; **no configuration change**;
reversible". A command that stops the management plane is a service-impacting
operation, and filing it beside `add backup local` would hollow out the
taxonomy the moment it was introduced.

**`E1` must be answered before this contract freezes**, from the Check Point
R81 Installation and Upgrade Guide and the `migrate_server` documentation
(already cited in architecture's Sources), and confirmed against the estate's
own MDS version. Three possible outcomes:

- **Non-disruptive invocation exists and is used** → `operational-write` stands,
  with the specific flags frozen in the gate entry, and the flags are part of
  what is signed off.
- **Disruption is unavoidable** → a fourth class, or an explicit
  maintenance-window-only capability with human-initiated execution and no
  scheduler path, ever. Not a variation on §7.3.
- **Version-dependent** → the platform must detect the version and refuse the
  disruptive path, never "try it and see".

This is a vendor-semantic question of exactly the kind `AGENTS.md` routes to
high reasoning and to explicit build scope. Recording it as an open decision is
the honest outcome of writing this contract; it is not a defect in §7.6, which
drafted the entry before anyone needed to execute it.

### C2 — A consistency group is scheduled as a unit, and a partial group is not evidence

Architecture §3.1 and §9. The group, not the member, is the unit of work.

- A group is a named set of `entity_id`s (management HA peers, or an MDS and
  its peer).
- Execution starts every member within a bounded window,
  `SECURITYEXPERT_CP_GROUP_START_WINDOW_SECONDS`. Exceeding the window marks
  the group `INCONSISTENT` — the members' artifacts are still stored (they are
  real files and may be useful), but the **group** carries the marking and the
  readiness model does not count it.
- Any member failing marks the group `INCONSISTENT`.
- `manifest.consistency_group` on each member's manifest names the group, so
  the marking is discoverable from any single artifact rather than only from
  the group record.

**The readiness consequence is the point:** an `INCONSISTENT` group must not
lift a device from `UNPROTECTED` to `READY`. A set of management backups taken
minutes apart during an active policy change can be mutually contradictory, and
counting it as readiness would be the "evidence mistaken for backup" failure
this whole design exists to prevent (architecture §1).

**Note the tension with the concurrency budget.** Starting group members within
a bounded window means running two management-server commands concurrently.
The admission coordinator's per-vendor concurrency budget is **1**, and
`CURRENT_STATE.md` standing priority 1 keeps it there. A consistency group
therefore either needs an explicit, narrow exemption for the group case, or the
window must be wide enough to accommodate serial execution — in which case
"simultaneous" is a claim the platform cannot make and the group should be
marked accordingly. **This is a real design conflict and must be resolved at
contract freeze, not at implementation.** The honest default, absent a
decision, is serial execution plus an explicit `SEQUENTIAL` group marking that
is weaker than `CONSISTENT` — never a silent concurrency increase.

### C3 — The MDS is the platform's own source of truth; self-interference is a first-class risk

Every other target in the recovery plane is a device the platform reads *about*.
The MDS and the SMS are where the platform reads *from* — discovery, topology,
intent and provenance all originate there (`AGENTS.md`: "Management plane =
discovery/topology/intent/provenance").

A management export that degrades the management server degrades the platform's
own collection at the same time, and the resulting evidence gap is
indistinguishable from a real outage in the inventory data. So:

- A management export never runs concurrently with a discovery or configuration
  collection against the same management endpoint. The admission coordinator's
  per-endpoint lock already gives this, provided the management export routes
  through it — which it must, with no exception.
- The run records a marker so that a subsequent evidence gap on that endpoint
  can be attributed rather than mistaken for a device failure.

### C4 — Timeouts are long enough to need explicit failure semantics

§7.6 sets 3600 s. An hour-long device command has failure modes a 60-second read
does not: the SSH session drops while the export continues on the device; the
platform restarts mid-export; the operator interrupts.

Frozen: **the platform never assumes an export it lost track of has stopped.**
A run that loses its session mid-export marks the endpoint's ledger entry (the
RB.3b `utils/recovery_operational_ledger.py`) as `IN_FLIGHT_UNKNOWN`, which
blocks further attempts until an operator clears it, and reports the on-device
artifact as possibly present. Cleanup (RB.3b §7.8) cannot run for an archive
whose name was never returned — the deletion contract's "only the name this run
created" rule (RB.3b B3 point 12) holds, and the honest outcome is
`CLEANUP_OWED`, surfaced to the operator, not a wildcard delete.

### C5 — Storage: `D5` decides depth, this contract only refuses to guess

A `cp_mds_backup` is large. Holding dailies → weeklies → monthlies at the same
depth as a PAN device state may exceed the recovery volume by a wide margin.

RB.3c does **not** pick a number. It requires that `D5` produce: the recovery
volume's total budget, and a per-class retention depth for `cp_mgmt_export` /
`cp_mds_backup`. Until then the collector refuses to run rather than filling a
volume — the same abort-on-unknown posture as RB.3b's free-space precondition,
one layer up.

RB.1's retention floor still binds: **retention may never delete the only
artifact for a device that is otherwise `UNPROTECTED`** (architecture §9). A
budget that cannot hold one management export per server is a budget problem,
not a retention problem, and must be reported as such.

## Correctness contract

1. A management export routes through the admission coordinator, per endpoint,
   with no exception (C3).
2. A group's artifacts each carry `manifest.consistency_group`; the group record
   carries the verdict (`CONSISTENT` / `SEQUENTIAL` / `INCONSISTENT`).
3. An `INCONSISTENT` group contributes **no** readiness evidence; its members do
   not reach `READY` on the strength of that group.
4. A lost session mid-export yields `IN_FLIGHT_UNKNOWN` and blocks retries
   until cleared (C4). It never yields "failed, try again".
5. `software_version` is real for both classes — RB.3b B8's rule applies
   verbatim; these are version-locked artifacts.
6. No export runs while `D5` is unanswered (C5).

## Privacy and safety invariants

Everything in RB.3b's list, plus:

- **A management export is the single most sensitive artifact this platform will
  ever hold** — the whole policy, object and administrator database. Architecture
  §10 rules 1–7 apply at maximum: encrypted at rest, never served by nginx,
  never in the support bundle, never reachable from an unauthenticated surface,
  never parsed for evidence (§3.3 invariant 1 — a recovery artifact is opaque).
- The temptation to parse a management export for policy evidence must be
  refused explicitly. Evidence comes from the evidence plane. An export is
  restore material, not a data source, and mining it would collapse the
  recovery plane into the evidence plane that architecture §4 deliberately
  separates.

## Contract amendments required (design docs)

- **E1 — architecture §13** gains open decision `E1` (C1): verify the
  `operational-write` classification of `migrate_server export` / `mds_backup`
  against vendor documentation and the estate's MDS version, before §7.6
  freezes.
- **E2 — contracts §7.6** gains the specific non-disruptive invocation flags
  (once `E1` answers), the `IN_FLIGHT_UNKNOWN` semantics (C4), and the C3
  no-concurrent-collection rule.
- **E3 — contracts §2 / §5** gain the group verdict vocabulary
  (`CONSISTENT` / `SEQUENTIAL` / `INCONSISTENT`) and the frozen rule that a
  non-`CONSISTENT` group is not readiness evidence.
- **E4 — architecture §9** records the concurrency-budget conflict in C2 and its
  resolution, so that a future reader does not discover it as a surprise while
  implementing.

## Implementation plan

1. `E1` answered; `D5` answered; §7.6 re-cut and signed off. **No code before
   this.** If `E1` lands on "disruption unavoidable", this plan is discarded and
   rewritten against whatever class the decision creates.
2. `utils/recovery_consistency_group.py` — group definition, verdicts, the
   readiness rule. Pure logic, offline, fully testable without a device.
3. Readiness + manifest wiring: populate `manifest.consistency_group`, teach
   `compute_restore_readiness` to discount non-`CONSISTENT` groups.
4. `IN_FLIGHT_UNKNOWN` ledger state (extends RB.3b's ledger).
5. The collector itself, against fixture transports.
6. Metadata, `CURRENT_STATE.md`, design-doc amendments.

## Acceptance criteria

- **AC-1** Group verdicts: all members succeed within the window →
  `CONSISTENT`; serial execution → `SEQUENTIAL`; any member fails or the window
  is exceeded → `INCONSISTENT`.
- **AC-2** `INCONSISTENT` and `SEQUENTIAL` groups do not produce `READY` for
  their members.
- **AC-3** Each member manifest carries `consistency_group`; the verdict is
  reachable from any single artifact.
- **AC-4** A lost session mid-export → `IN_FLIGHT_UNKNOWN`, further attempts
  blocked, `CLEANUP_OWED` reported, **no wildcard deletion attempted**.
- **AC-5** Admission: a management export and a configuration collection against
  the same endpoint never overlap.
- **AC-6** `D5` unanswered → the collector refuses to run, by name (C5).
- **AC-7** Retention floor: a policy that would delete the only management
  export for a server reports a floor violation rather than deleting.
- **AC-8** `software_version` unresolvable → artifact not stored.
- **AC-9** Privacy: an export never appears in `output/index.html`, the support
  bundle, or a log line; the repository privacy gate stays PASS / 0.
- **AC-10** No code path parses an export's contents (C-level assertion: the
  bytes go from transport to `write_artifact` and are never decoded).

## Validation and merge gate

- Full suite one-shot; baseline **788 / 3 / 2** (PostgreSQL) or **763 / 11 / 2**
  (without). Privacy gate PASS / 0.
- **Real-environment validation is mandatory and is the strictest in this
  track.** It must be a scheduled maintenance window, on a management server the
  product owner nominates, with Check Point support's guidance available, and
  with the management service's health confirmed before and after. Nothing about
  this build reaches `AUTOMATED_VALIDATED` on fixtures alone, and it does not
  reach `DONE` without that window.

## Risks

- **`E1` may invalidate this contract.** If the management commands are
  service-impacting, RB.3c is not an RB.3b variant and must be re-cut. Writing
  it now is still worthwhile — it surfaced `E1`, which was not visible from
  §7.6's inherited "same shape as §7.3".
- **The concurrency conflict in C2 has no free answer.** Either the platform
  raises concurrency for a specific case, against a standing priority, or it
  accepts that "simultaneous" is a claim it cannot make and labels groups
  `SEQUENTIAL`. The second is safer and weaker. Decide it explicitly.
- **Self-interference (C3).** The one target class whose degradation corrupts
  the platform's own view of the estate.
- **Storage.** Without `D5`, a working implementation could fill the recovery
  volume in days and, by RB.1's retention floor, be unable to delete its way out
  without dropping a device to zero coverage.
- **An unrestorable management backup is worse than none**, because it will be
  counted. RB.4's V1–V3 battery validates transport and structure, not
  restorability; only `D7`'s restore-proof lab produces V4. Until then every
  management artifact is `RESTORE_UNPROVEN` and the UI must say so without
  hedging (architecture §6).

## Rollback

The collector, the consistency-group module and the readiness discount are all
additive. Removing them leaves stored artifacts readable by RB.1/RB.4 —
`manifest.consistency_group` is an optional field that predates this build in
`build_manifest`'s signature, so manifests written by RB.3c remain valid without
it. No schema version changes.

## Definition of done

1. `E1`, `D3`, `D4`, `D5` answered; §7.6 re-cut and signed off with the exact
   invocation flags frozen.
2. AC-1 … AC-10 green against fixture transports.
3. Amendments E1–E4 landed.
4. Full suite at or above baseline; privacy gate PASS / 0.
5. Status `IMPLEMENTED` until the maintenance-window real-environment run; only
   then `REAL_ENV_VALIDATED`.

## Next movement / model

`ARCHITECTURE` at **Sonnet 5, extended thinking** — but not yet, and not on the
implementation. The next movement on RB.3c is answering `E1`, which is a
vendor-documentation question with a real safety consequence, and resolving the
C2 concurrency conflict. Both are decisions, and the second one needs the
product owner because it trades a standing safety priority against a
correctness claim.

Do not open RB.3c implementation before RB.3b has had its watched
single-gateway real-environment run. The gateway case is where the
`operational-write` machinery earns trust; the management servers are not the
place to discover a flaw in it.
