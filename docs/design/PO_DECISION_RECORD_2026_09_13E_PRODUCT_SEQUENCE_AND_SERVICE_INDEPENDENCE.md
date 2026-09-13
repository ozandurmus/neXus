# PO Decision Record — 2026-09-13 — Product sequence and per-feature service independence

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-13.** Records decisions the
Product Owner gave in the 2026-09-13 local session. `AGENTS.md` "Authority
hierarchy" item 7 makes session chat non-authoritative, so an unrecorded
verbal decision is lost at the session boundary. This file is the durable
record; it creates no new authority, it preserves authority the Product Owner
exercised.

It does not amend `PO_DECISION_RECORD_2026_09_12.md` §4 (device add →
discovery → import → collection), `PO_DECISION_RECORD_2026_09_13C` §2 (three
separately deployable services) or `PO_DECISION_RECORD_2026_09_13D` DS-1 to
DS-3 (independently deployable, set open). It extends that sequence past
collection, names the service set that results, and fixes the order in which
the Product Owner wants them built.

## 1. The sequence

**PO directive, 2026-09-13.** In this order, each step started only when the
one before it is complete:

| # | Step | State on 2026-09-13 |
| --- | --- | --- |
| 1 | **Discovery** — Check Point and Palo Alto candidate enumeration under the two FROZEN discovery contracts | Check Point domain core merged; Check Point transport (`NXS-LOCAL-0145`) and Palo Alto domain core (`NXS-LOCAL-0146`) in flight; Palo Alto transport follows |
| 2 | **GUI login** — the product's own login, **local user first** | Local authentication is delivered under `UI2_0_C3A`/`UI2_0_C3B` (FROZEN, implemented: login required even on localhost, `nexusadmin`/`claudeadmin` seeded at first boot). What remains is the login screen's fidelity to the Product Owner's frames and, later, directory login |
| 3 | **Collection from discovered and enrolled devices — Inventory and Configuration** | Not started. Two engines per `13C` §2: the configuration engine and the inventory/route engine. **Both stay behind the collection gate** until the Product Owner states, per vendor, collection type and methods (`PO_DECISION_RECORD_2026_09_12.md` §1) — the discovery lifts of 2026-09-13 do not cover them |
| 4 | **Backup** — in Java, directly | Not started in Java. `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (FROZEN) is the contract. **Manual backup first; the scheduler stays in the backlog.** The database exists (`UI2_0_C1`, PostgreSQL 16) |
| 5 | **Failover engine** — the first work after backup is complete | Not started in Java. `OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` (CONTRACT FROZEN 2026-09-04) and the Product Owner's failover rules of 2026-09-12 (`PO_DECISION_RECORD_2026_09_12.md` §3, backlog `failover_*`) are the inputs |

**PS-1.** The order is the Product Owner's and is not re-derived by an
implementing movement. A step is complete when its contract's acceptance
surface is discharged and the Product Owner has said so; "the next step is
more interesting" is not a reason to start it.

**PS-2.** Steps 3, 4 and 5 each contact devices or write to them and each
therefore needs, before any implementation: the per-vendor collection-gate
statement (step 3), and the network-device command gate for every command
introduced (all three). This record lifts no gate.

**PS-3.** `project/roadmap.json` `now_next.next` (`M12`, per-device schedules
on the Python line) no longer reflects the Product Owner's intent. The next
build after the discovery line is step 2's remaining work, then step 3. The
scheduler for step 4 is deliberately deferred by this record, which is the
same answer for `M12`.

## 2. Each feature is a microservice, independently developable

**PO directive, 2026-09-13.** *Each of these is a microservice. I want every
feature to be developable and writable independently of the others.*

**SI-1.** Discovery, inventory collection, configuration collection, backup
and failover are each a separately deployable service under `13D` DS-1, and
the set `13C` §2 opened (discovery, configuration engine, inventory/route
engine) is extended by **backup** and **failover**. Login remains, for now,
the existing `service`'s surface (`13D` §4, AUTH-PLACEMENT open).

**SI-2. Independence is a build property, not only a deployment one.** A
feature must be implementable by a movement that loads that feature's
contract and the platform contracts (`C1`–`C4`), and nothing of another
feature. Where one feature needs another's data, it reads it through the
shared schema (`C1`) or a stated interface — never by importing the other
feature's code. `13D` INTER-SERVICE-BOUNDARY remains open; until it is
decided, the shared database is the only inter-feature channel.

**SI-3. The device-identity constraint of `13D` §3 binds every one of them.**
One service owns device identity (import); discovery proposes, collection,
backup and failover reference. No two services acquire independent authority
over the same device or HA target.

**SI-4. Module placement today.** Until the skeleton successor `13D` §5 owes
is written, a feature's code lives in the existing eleven-module layout
(`ui2/settings.gradle.kts`, asserted by `Ui2ArchitectureTest`): domain core
in `platform-core` under its own package, transport adapters in `worker`,
with no cross-feature package dependency. Splitting a feature into its own
image is a contract clause of that successor, not an implementation
decision (`13D` DS-3).

## 3. Consequences for the backlog

Applied in the same change as this record, through `scripts/project_queue.py`
(never by hand): the Python-line feature items that this sequence will never
build are closed as superseded or moved to the reserve, with each
history file naming the UI 2.0 contract or item that carries the intent
forward; six rules and defects found on the Python line are re-cut as UI 2.0
items; `pan_discovery_java_implementation` is opened alongside
`cp_discovery_java_implementation`. The active backlog falls from 67 items to
44 and P0 from 13 to 7.

## 4. What this record does not decide

- Any collection-gate lift for inventory, configuration, backup or failover.
- AUTH-PLACEMENT, DATA-OWNERSHIP, BUILD-AND-RELEASE, INTER-SERVICE-BOUNDARY
  (`13D` §3) — each is named as a backlog item and stays open.
- Which service owns the operational unit after import (`13C` §5).
- Whether a scheduler, when it returns, is one service or part of each.

## 5. Cross-references

- `PO_DECISION_RECORD_2026_09_12.md` §1, §2, §3, §4 — the collection gate,
  Java from scratch, the failover semantics, the ordered sequence this
  extends.
- `PO_DECISION_RECORD_2026_09_13C_DISCOVERY_SERVICE_BOUNDARIES.md` §2 — the
  service set this extends.
- `PO_DECISION_RECORD_2026_09_13D_INDEPENDENTLY_DEPLOYABLE_SERVICES.md` — the
  deployability decision and its open questions.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md`, `PAN_DISCOVERY_CONTRACT.md` — step 1.
- `UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md`, `UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md` — step 2, delivered part.
- `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` — step 4.
- `docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md` — step 5.
- `AGENTS.md` — authority hierarchy item 7, evidence laws, network action taxonomy.
