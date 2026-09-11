# UI 2.0 B1 adjudication — 2026-09-12

## Status

**DECIDED. Published by the engineering session under a written Product
Owner authorization** given in chat on 2026-09-12: *"sen uygun görüyorsan
kontratları onaylayabilirsin. sadece bana seçenek sunacaksan awaiting you
da kalsın... aynı şekilde beğenmediklerini iptal de edebilirsin
kontratların ya da sınırsız revize edebilirsin."* Scope of that
authorization: approving, revising and rejecting UI 2.0 B1 implementation
contracts, and deciding the questions they left open where a decision
follows from frozen text rather than from product preference.

This document adjudicates twelve cross-contract findings and answers the
nine consolidated open questions raised by
`UI2_0_B1_02_SCHEMA_V1_CONTRACT.md`,
`UI2_0_B1_03_IDENTITY_SESSIONS_CONTRACT.md`,
`UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` and
`UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md`. Where it decides
something those contracts state differently, **this document wins** and
the contract is read as amended.

The four contracts were written independently by four workers, each
reading the frozen C-series alone. An adversarial cross-check then
compared them against each other. Three of its findings were gaps that no
contract owned; without this adjudication the first implementation
movement would have failed at runtime.

## 1. Findings and their adjudication

### F1 — nobody creates C2's job lifecycle objects (BLOCKER)

`B1-2` §2/§4 says the `jobs` lifecycle, lease and fencing columns,
`job_step_attempt` and `job_reconciliation` land in B1-4's own additive
migration. `B1-4` §1 says it issues no DDL. Both cannot be true, and
`B1-4` §4/§7 reads those very objects.

**Decision.** B1-4 owns them. `B1-4` §1 is read as *"issues no DDL except
its own migration"*. Deciding text: `C1` §2.3 — the movement that needs a
table ships its migration; `C1` §3.3 reserves the columns by reference for
C2 and names no other owner.

### F2 — circular ownership of the gate registry storage (BLOCKER)

`C1` §3.1 says C4 defines its own DDL; `C4` §3.2 says the row lives in a
Flyway-migrated table "C1's to place". B1-2 excludes it and B1-4 authors
neither DDL nor rows, yet B1-4 §3 resolves gates against those rows.

**Decision.** B1-4 owns the gate-registry table in its migration, seeded
from a version-controlled fixture committed alongside the capability
specs. Deciding text: `C4` §3.2 requires a queryable store, and `C4`
§7-2/§7-4 require it to exist before any gate-resolution test can run.
B1-4 is the only consumer.

### F3 — nobody sets the audit context (BLOCKER)

`C1` §3.5 requires every mutating transaction to carry an actor
fingerprint and action id, and fails closed without them. It names only
"the Java service's transaction interceptor". B1-3's scope covers the
authorization chain but never that interceptor; B1-4 asserts worker
mutations carry "the executor's own actor context", which no document
defines. Every worker write would fail `audit_context_missing`.

**Decision, in two parts.** B1-3 owns the `service` transaction
interceptor that sets the audit context for request-scoped mutations.
B1-4 defines a **reserved worker actor** and the transaction wrapper that
sets the context for executor-scoped mutations, modelled on `C3` §3.5's
existing reserved system actor for the session reconciler. Both are added
to those contracts' scopes.

### F4 — job admission is unowned (MAJOR)

`B1-4` §3 lets a job against an unresolved-gate capability sit in
`REQUESTED` forever. `C4` §7-3 requires the submission be refused
**before** `REQUESTED` is ever reached.

**Decision.** `C4` §7-3 governs. Admission — the execution-eligible-view
check plus the idempotency key of `C2` §2.3 — belongs to B1-4 and runs
before a job row reaches `REQUESTED`. B1-4's test 10 is aligned to assert
a pre-`REQUESTED` refusal.

### F5 — B1-2 wrongly forecloses enrollment columns (MAJOR)

`B1-2` §2 asserts B1-4b needs no column beyond `C1` §3.2. `C1` §3.2
explicitly grants B1-4b additional lifecycle columns for enrollment.

**Decision.** That sentence in `B1-2` §2 is struck. `C1` §3.2 decides.

### F6 — a draft device is refused in one place only (MAJOR)

B1-4b requires job admission to refuse a `DRAFT` target; B1-4's check
battery never mentions `enrollment_state`; `C2` §6 check 5 is claim-time,
not admission-time.

**Decision.** Two-point enforcement. Admission refuses a `DRAFT` target
(F4's owner, B1-4), and `C2` §6 check 5 re-checks at claim time. A job may
therefore be refused at either point, and both paths are tested.

### F7 — mid-run device state change is unowned (MAJOR)

`C2` §6 re-checks only at claim. B1-4b defines the transition
`ENROLLED → UNREACHABLE` on a failed contact attempt but its flow is
registration-only; B1-4 never writes a device row.

**Decision.** A device state change never aborts a run already in
`EXECUTING`. The executor emits the transition **after** the attempt
record is written, inside `C2` §5.1's mutation boundary, and `C2` §3.5's
no-second-contact rule continues to apply. The write itself goes through a
device-state port owned by B1-4b and called by B1-4.

### F8 — the CLI cannot reach the table it must seed (MAJOR)

`B1-3` §6 makes the first `security_admin` binding a CLI-only action, but
`B1-1` §2 allows `cli` to depend on `platform-core` and `job-engine` only,
so it cannot reach `role_bindings` in `persistence`.

**Decision.** `B1-1` §2 stands; the module map is not widened for a
bootstrap convenience. The first-binding bootstrap is routed through a
port declared in `platform-core` and implemented in `persistence`, which
the `cli` module calls. No direction rule changes.

### F9 — an audit-coverage test that will fail by design (MAJOR)

`B1-2` §7-6 hardcodes "all eight tables", but `C1` §3.5's purpose is to
catch *future* tables, and `C3` §3.5 deliberately excludes two.

**Decision.** The test enumerates every mutation-bearing table **minus a
documented exclusion list** — `audit_log`, `authz_decisions`,
`actor_authz_state` — with the exclusion citing `C3` §3.5. A new table
added later without an audit trigger must fail this test; that is the
point.

### F10 — two conventions for where a port lives (MINOR)

B1-3 puts its LDAP port in the adapter module; B1-4 puts its transport
port with the consumer. Both satisfy the direction rules, but one project
needs one convention.

**Decision.** A port shared across modules lives in `platform-core`,
whose `B1-1` §2 responsibility is exactly "shared ports". The LDAP
operator-bind port moves there. A port used by exactly one module may stay
with that consumer.

### F11 — the connect timeout is circular (MINOR)

B1-4 delegates the default to B1-4b; B1-4b sets none.

**Decision.** The `connect` timeout default is **10 seconds**, owned by
B1-4 as adapter configuration and overridable per endpoint. It is a
tunable operational value, not a contract invariant; the Product Owner may
change it without amending a contract.

### F12 — B1-4b places no modules (MINOR)

**Decision.** Registration controller → `service`; device repositories →
`persistence`; the read port the engine uses → `job-engine`; the
device-state port of F7 → declared in `platform-core`, implemented in
`persistence`.

## 2. The nine open questions, answered

| # | Question | Decision |
|---|---|---|
| 1 | Does `V1` create `devices`, `endpoints`, `credential_references` in full? | **Yes**, with the minimal `C1` §3.2 column set. Foreign keys from `jobs` and the inventory projection require them; PostgreSQL cannot forward-reference. `C1` §3.1's owner column is read as owning the columns and the onboarding logic, not the migration. |
| 2 | Migration version allocation | **B1-2 = V1, B1-3 = V2, B1-4b = V3, B1-4 = V4.** This matches the implementation order in §3. Any later movement takes the next free number, allocated at dispatch and recorded in its packet. |
| 3 | Who creates C2's job lifecycle objects? | **B1-4**, in V4. See F1. |
| 4 | Where does the gate registry live? | **A Flyway table created by B1-4 in V4**, seeded from a committed fixture. See F2. |
| 5 | Capability spec serialization | **YAML.** The specs are human-authored and human-reviewed artifacts filled from the `C6` template; YAML keeps them reviewable in a diff. A spec is validated against the `C6` field set at load, and an unknown field is an error, not a silent ignore. |
| 6 | `connect` timeout default | **10 seconds**, tunable. See F11. |
| 7 | Enrollment vocabulary | **Approved as `DRAFT`, `ENROLLED`, `UNREACHABLE`, `DEGRADED`, with `disabled` as a separate boolean column.** This is the set the Administration mockup already shows, so the UI and the schema agree from the start. A later `C1`/`C4` review may extend it; it may not silently redefine an existing value. |
| 8 | Proposed numeric defaults | **Accepted as configuration defaults**, not invariants: session idle 30 minutes, absolute lifetime 10 hours, lease 60 seconds with a 20-second heartbeat, and the retry budgets each contract states. Each is overridable without a contract amendment. |
| 9 | A second manually registered endpoint | **REL-DISCOVERY.** B1-4b registers exactly one endpoint per device, which is what "minimal device model" means; multi-endpoint devices arrive with discovery. |

## 3. Implementation order, and what may not run concurrently

1. **B1-1** — merged. The module map and the migration location must exist first.
2. **B1-2** — `V1`. Every other migration is additive to it.
3. **B1-3** — owns the audit-context interceptor (F3) that B1-4b's registration and B1-4's worker writes both presuppose.
4. **B1-4b** — creates `devices.enrollment_state`, which B1-4 reads at claim time (F5, F6).
5. **B1-4** — needs devices, the gate registry (F2) and its own lifecycle migration (F1).

**Never concurrent.** B1-2 with any of the others: Flyway's version ledger
is a single global sequence and `V1` is immutable once applied (`C1`
§2.2). B1-4 with B1-4b: they form a cycle today — B1-4 reads
`enrollment_state`, B1-4b consumes B1-4's transport port and connect
default. Running them in the order above breaks the cycle, because B1-4's
port signature is frozen in `B1-4` §5 before B1-4b needs it.

## 4. Contract status

With this adjudication applied, the four contracts are **FROZEN** as of
2026-09-12. Each is read as amended by the decisions above. Their own open
items sections are superseded by §2 of this document; items §2 lists as
acknowledgements need no decision, and the pilot-allowlist question is
recorded as a `C7` gap for the movement that needs it.
