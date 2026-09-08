# PCP storage-engine decision — evaluating `X-2` against `PCP` §10

## Status

**DRAFT — evaluation only. Does not itself freeze `pcp_storage_engine`.**
Produced under movement `PCP_STORAGE_ENGINE_DECISION_DOCUMENT`
(relay `NXS-LOCAL-0035`) to check
`docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md`'s recommendation `X-2`
against every criterion `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`
(`PCP`, **FROZEN**) §10 already recorded, and to state plainly whether that
is enough to close the open backlog item `pcp_storage_engine_decision`. No
code, schema, or migration is authorized by this document. No frozen
document is amended.

## 1. What is being decided here

`project/backlog.json` id `pcp_storage_engine_decision` (P1) tracks one open
question: **which storage engine backs the Device Registry and the typed job
plane in production**, against the criteria `PCP` §10 recorded on
2026-09-05 when it deliberately deferred the answer. `PCP` §10 already
settled a *different*, narrower question — the **local** sequencing
decision (`PCP.1` registry stays filesystem JSON; new local control-plane
metadata goes to SQLite under `M4`) — and was explicit that this does not
select a production engine.

`UI2_0_ARCHITECTURE_REQUIREMENTS.md` (`UI2_0`, **DRAFT**, not frozen) §6.2
recommendation `X-2` is the first document to propose an answer:
**PostgreSQL as the production engine for UI 2.0's control-plane,
projection, and audit state; SQLite stays the local engine; CAS/recovery
blobs stay on the volume.** `X-2` says of itself: *"This resolves
`pcp_storage_engine` as a recommendation only; the decision itself stays
open exactly as `PCP` §10 records it."* This document takes that
recommendation at face value and checks it, criterion by criterion, rather
than re-deriving one from zero.

## 2. Criterion-by-criterion evaluation

`PCP` §10 records ten criteria the eventual decision must answer. `X-2`'s
own table addresses all ten by name — this section checks that mapping
rather than repeating it, and records where the mapping is solid versus
where it is incomplete.

| # | `PCP` §10 criterion | `X-2`'s answer | Assessment |
| --- | --- | --- | --- |
| 1 | Transactions — enrollment + relationship writes atomically | PostgreSQL: yes (multi-statement commit); SQLite: yes but single-writer | Sound. The registry-adjacent writes `X-2` targets (enrollment, audit, candidate consumption) are exactly the multi-row commits PostgreSQL is designed for; SQLite's single-writer lock is the reason it was never proposed for concurrent production use. |
| 2 | Migrations, versioned and deployment-controlled (`DEV.4.6` precondition) | PostgreSQL: mature tooling | Correct as far as it goes, but incomplete: `DEV.4.6` (the migration-discipline build itself) has not run. `X-2` names it as a precondition rather than a completed gate — this is the single largest concrete gap between "recommended" and "freezable" (see §4). |
| 3 | Concurrent workers (`DEV.3.4` deferred) | PostgreSQL: designed for it, coordinator backend already exists; SQLite: not multi-process across hosts | Sound and grounded — `utils/evidence_backend.py` already runs five (now seven, with the console-job and operational-write-ledger backends) storage concerns through byte-compatible Postgres implementations under `SECURITYEXPERT_EVIDENCE_BACKEND=postgres`. The registry would be the eighth, following the same seam, not a new mechanism. |
| 4 | Uniqueness / locking — one active record per endpoint per vendor; one run per definition in flight | PostgreSQL: constraints + row locks; SQLite: constraints only, single-writer | Sound. This is the same constraint shape the registry's in-memory duplicate detection (`utils/device_registry.py`) already reasons about; a relational unique constraint is a strict tightening, not a new invariant. |
| 5 | Append/history workloads vs. small mutable registry rows | PostgreSQL and SQLite: both fine at the stated scale; document store: append-friendly but poor at joins | Sound but the comparison undersells the actual need: the job plane mixes small mutable registry-shaped rows with append-only job-run/observation history in the *same* transactional boundary (a run must reference a live registry row). A pure document store's join weakness is disqualifying for that reason, not just a style preference — `X-2` states the fact but not why it is decisive here. |
| 6 | Retention | PostgreSQL: per-table policies | Sound, standard capability; not a discriminating criterion between the two real candidates (SQLite has no answer at production scale, which the table already shows). |
| 7 | JSON / structured evidence needs; payload blobs never move | PostgreSQL: `jsonb` | Sound and correctly bounded — `X-2`'s recommendation prose explicitly repeats "content-addressed blobs and the recovery store stay on the volume (unchanged)," matching `PCP` §10's own "payload blobs stay on the volume — CAS and recovery store never move" verbatim. No drift. |
| 8 | Deployment topology — laptop today, container volume, `DEPLOY.1` server | PostgreSQL: already opt-in in compose (`DEV.3.3`) | Sound and grounded in shipped infrastructure (`docker-compose.prod.yml` already has an opt-in Postgres path); this is real reuse, not a new dependency being introduced for this decision alone. |
| 9 | Backup / recovery of neXus's own state | PostgreSQL: "standard" | **Incomplete.** `PCP` §10 explicitly ties this criterion to `recovery_offhost_key_custody` — the registry is "product state worth restoring" with an off-host custody angle. `X-2`'s table cell ("standard") does not engage with off-host custody at all; it answers "can Postgres be backed up" (yes, trivially) without answering "does that backup satisfy the same off-host custody bar the rest of neXus's recoverable state must meet." This is a real gap, not a nitpick — it is the one row where `X-2` answers a easier question than `PCP` §10 actually asked. |
| 10 | Enterprise operation — role separation, TLS DSN, audit retention | PostgreSQL: roles, row-level security, TLS DSN already required by `PRIVACY_AND_DATA_HANDLING.md` "Distributed evidence store" | Sound and correctly cited — that section already mandates a dedicated non-multi-tenant instance, TLS DSN, and role-scoped access for the existing opt-in Postgres path; `X-2` extends the same rule rather than inventing a new one. This is also the row `X-2` itself calls out as UI 2.0's own added criterion (LDAP-scoped actors, per-role read grants), which `PCP` §10 did not originally anticipate but does not contradict either. |

**Net finding:** `X-2` explicitly addresses every one of `PCP` §10's ten
named criteria — none is skipped — and nine of the ten mappings are sound
and adequately grounded in code already in this repository. One (#9,
backup/recovery with off-host custody) answers a narrower question than
`PCP` §10 asked and needs to be closed explicitly, not assumed satisfied by
"Postgres can be backed up." One (#2, migrations) correctly names its own
precondition (`DEV.4.6`) as *not yet done* rather than claiming it is.

## 3. Reconciliation — this document contradicts neither prior conclusion

- **`M14` (LDAP authorization) persistence model.** `M14`'s own draft
  concludes in-memory, process-lifetime authorization state with nothing
  persisted (`LD-3`) — a deliberate choice for a single-operator, TTY-bound
  bind, unrelated to the registry/job-plane storage question this document
  evaluates. `X-2` does not fold `M14`'s state into its recommendation
  either: its own table lists "a queryable authorization audit store" as
  UI 2.0's *new* addition, not a retrofit onto `M14`. Nothing here proposes
  giving `M14`'s in-memory auth state a database, and nothing in `M14`
  bears on which engine backs the registry/job plane. No contradiction.
- **`PCP` §10's local sequencing decision.** Unaffected. The registry
  remains on its frozen filesystem JSON backend; new local control-plane
  metadata still goes to SQLite under `M4`. `X-2` is explicit that its
  PostgreSQL recommendation is a **production** answer layered on top of,
  not a reopening of, that already-decided local sequencing.
- **Invariants `PCP` §10 lists** (RuntimeRoot/repository separation,
  secrets never in product records, registry + mutation lock file as
  LOCAL-SENSITIVE data excluded from the support bundle and the
  repository). `X-2` does not touch any of these; a future PostgreSQL
  registry backend would still resolve credentials by reference only,
  exactly as the existing seven Postgres-backed concerns already do.

## 4. Is this sufficient to freeze `pcp_storage_engine`? No — three concrete gaps, not a re-litigation

`X-2` is a well-grounded recommendation and this document adopts its
conclusion (PostgreSQL) as the right direction. It is **not** sufficient by
itself to formally freeze `pcp_storage_engine_decision`, for reasons that
are all already on record rather than new objections invented here:

1. **`X-2` disclaims freeze authority for itself.** Its own closing line —
   *"the decision itself stays open exactly as `PCP` §10 records it"* — is
   a recommendation living inside a document whose own Status line says
   **DRAFT … DO NOT FREEZE without the Product Owner decisions named in
   §9.** A recommendation inside an unfrozen document cannot be the freeze
   of a different, older backlog item by inheritance.
2. **The recorded gate for this exact decision has not been reached.**
   `project/roadmap.json`'s own `pcp_storage_engine` entry records
   `"decide_by": "PCP.5 contract freeze / DEV.4.6 migrations and database
   roles"` and, as of the last update, `"recommendation": "Still defer."`
   Neither `PCP.5` nor `DEV.4.6` has happened. Freezing the engine choice
   before `DEV.4.6` exists would make "migrations, versioned and
   deployment-controlled" (criterion #2) a precondition promised on paper
   rather than a gate actually in place — the same distinction `AGENTS.md`
   draws between `DRAFT` recommendation and frozen authority generally.
3. **`UI2_0`'s own `C-1`…`C-4` remain open Product Owner decisions that can
   change *why* and *when* a production engine is even needed**, not just
   how it is chosen:
   - `C-1` decides whether UI 2.0 is a separate shell with its own read API
     at all, or is folded into the existing console/export payload model.
     If resolved toward option (2) (keep the existing shell), the
     multi-user, projection-heavy load `X-2` sizes PostgreSQL against
     shrinks or disappears as a near-term driver.
   - `C-4` decides whether a multi-user, browser-login production console
     is even in scope soon, or stays deferred as the `DEPLOY.1A` shape.
     `X-2`'s strongest tie-breaking row (#10, enterprise operation:
     "LDAP-scoped actors, per-role read grants") only matters once `C-4`
     resolves toward building that surface.
   - Neither `C-2` nor `C-3` bears on the engine choice directly, but both
     remain open PO calls inside the same not-yet-frozen document `X-2`
     lives in.

   None of `C-1`…`C-4` invalidates `X-2`'s technical mapping in §2 above —
   the engine comparison holds regardless of how they resolve. What they
   do determine is whether ratifying `X-2` now would be freezing an answer
   to a question ("what backs a production multi-user console") that may
   not be asked for as long as `C-1`/`C-4` imply.

**What it would take to actually freeze `pcp_storage_engine`:** a Product
Owner `DECIDE` episode that (a) closes gap #9 above by stating whether
Postgres's standard backup posture satisfies the off-host custody bar
`recovery_offhost_key_custody` sets, or names the additional control that
would; (b) either accepts `DEV.4.6` as a precondition to sequence before
any schema exists, or brings `DEV.4.6` forward explicitly; and (c) is timed
at or after `C-1`/`C-4` resolve, so the freeze is not answering a "which
engine, for what workload" question before the workload itself is decided.
This is exactly the kind of decision `AGENTS.md` "Authority hierarchy"
reserves to the Product Owner rather than an engineering movement, and
matches `UI2_0` §12's own next-step statement ("the next step is a Product
Owner `DECIDE` episode on `C-1`…`C-4`").

## 5. Proposed decision (recommendation, not a freeze)

Adopt `X-2`'s direction — **PostgreSQL as the eventual production engine
for the Device Registry and typed job plane, SQLite unchanged for local
control-plane metadata, CAS/recovery blobs unchanged on the volume** — as
the standing recommendation carried forward in `pcp_storage_engine`'s
backlog/roadmap record, superseding the plain "still defer, no candidate"
posture recorded on 2026-09-05. Do not mark the backlog item `done`: the
recommendation is now well-evidenced, but the freeze itself is a
Product-Owner-only act pending the `DECIDE` episode in §4.

## 6. Cross-references

- `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §10 (criteria,
  FROZEN) and §19/§20 (classification, sequencing)
- `docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md` §6.2 `X-2`, §8, §9
  (`C-1`…`C-4`), §12 (DRAFT)
- `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md` (in-memory
  persistence model, unaffected)
- `PRIVACY_AND_DATA_HANDLING.md` "Distributed evidence store"
- `utils/evidence_backend.py` (seven existing filesystem/Postgres storage
  concerns; the registry would be the eighth)
- `project/roadmap.json` id `pcp_storage_engine`;
  `project/backlog.json` id `pcp_storage_engine_decision`
