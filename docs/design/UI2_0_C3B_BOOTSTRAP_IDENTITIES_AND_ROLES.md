# UI2 C3b — Bootstrap identities, their creation, and their roles

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-13.** Successor amendment to
`docs/design/UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md`, closing its §11
`U-2` (how the two bootstrap accounts are created) and adding the role
assignment `C3A` §7 left open. Neither `C3A` nor
`docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` is edited in place,
and nothing either fixes about sessions, RBAC evaluation or audit changes.

## 1. What was open

`C3A` §11 `U-2` recorded the seeding mechanism as `UNKNOWN` — "a Flyway seed
migration versus a first-boot bootstrap routine is an implementation choice
this contract's scope does not reach". The implementing movement chose a
deployment-controlled CLI action and cited that `UNKNOWN`, which was a correct
reading. The Product Owner then stated the product requirement it did not
meet: **the accounts must exist on the product's first deployment, the way a
firewall ships with its default administrator.**

## 2. Creation

- **BOOT-1. First-boot seeding, only when the table is empty.** On startup,
  when `local_credentials` holds **no rows**, the service creates
  `nexusadmin` and `claudeadmin` with their documented initial passwords.
  When the table holds any row, the routine does nothing at all.
- **BOOT-2. Idempotence is a safety rule, not a convenience.** The
  empty-table condition exists so that **a restart can never reset a password
  an operator has changed.** A seeding routine that rewrote an existing row
  would silently restore a documented credential on every restart, which is
  worse than having no seeding at all. This is the clause to test first.
- **BOOT-3. The verifier is computed at boot, never stored as a literal.**
  No migration, source file or manifest carries a password or a precomputed
  verifier. `C3A` §3 requires a random per-credential salt; a verifier
  committed to version control pins that salt permanently and turns a
  documented default into a precomputed one. The initial password is a
  documented constant; its verifier is not.
- **BOOT-4. Seeding is audited.** Each created identity produces an
  `audit_log` row through `C1` §3.5's existing trigger, attributed to a
  reserved bootstrap actor marker — the same posture `C3`'s own bootstrap
  clause already uses. No credential material reaches the row (`C3A` §8).
- **BOOT-5. Role binding stays where `C3` put it.** `C3`'s Bootstrap clause
  fixes that the first `role:security_admin` binding is created by a
  **CLI-only, deployment-controlled** action, never by a running service and
  never from the browser. BOOT-1 creates **identities**, not bindings, and
  does not touch that clause. A freshly deployed product therefore has two
  identities that can authenticate and, until the deployment step runs, no
  authorization — which is the correct fail-closed order.

## 3. Roles

**PO directive, 2026-09-13**, option C of three that were put to the Product
Owner.

- **ROLE-1. `nexusadmin` holds the full set of roles.**
- **ROLE-2. Separation of duties is waived for `nexusadmin`, deliberately and
  with its cost recorded.** `C3` §5 refuses `role:security_admin` "any
  device-facing action", stating the reason: *the actor who administers
  authorization does not also execute*. A single identity holding both
  administers the authorization it then uses. The Product Owner was shown
  this clause and chose the full set anyway, for a product that today has one
  human administrator.

  **Named REVISIT CONDITION.** The waiver holds while there is **one** human
  administrator. It is revisited when a second human administrator exists, or
  before any deployment where authorization administration and operational
  execution are meant to be held by different people — whichever comes first.
  This document does not re-argue the ruling; it records the cost beside it.

- **ROLE-3. `claudeadmin` holds `role:viewer` and nothing else.** It reads
  every projection the product ships and its own audit rows; it submits no
  job and performs no mutation.

  The reason is capability, not caution: the assistant does not operate the
  product through its browser. It changes the product by dispatching a
  movement whose diff the Product Owner reviews and merges. Write access in
  the running product would add risk without adding any capability the
  assistant actually uses — while read access is exactly what it needs to
  confirm that what was built is what was asked for.

- **ROLE-4. The distinction survives in audit.** `claudeadmin` exists so that
  automation-caused rows carry their own actor fingerprint (`C3A` §6.1).
  ROLE-3 sharpens that: an `audit_log` mutation row can never legitimately
  carry `claudeadmin`'s fingerprint, so one that does is a finding, not a
  routine entry.

## 4. Acceptance

1. On a database whose `local_credentials` table is empty, first boot creates
   exactly two identities and no more.
2. On a database whose `local_credentials` table holds any row, first boot
   creates nothing and changes nothing. **A test changes a password, restarts,
   and asserts the changed password still authenticates and the documented
   initial one does not.** This is BOOT-2 and it is the decisive check.
3. No migration, source file, resource or manifest contains a password or a
   verifier. A test greps the tracked tree for the documented initial values
   and asserts zero matches outside documentation prose.
4. Seeding produces one `audit_log` row per identity, attributed to the
   reserved bootstrap marker, carrying no credential material.
5. First boot creates no `role_bindings` row; a test asserts the table is
   still empty afterwards (BOOT-5).
6. After the deployment-controlled binding step, `nexusadmin` resolves the
   full role set and `claudeadmin` resolves `role:viewer` only.
7. A test asserts `claudeadmin`'s resolved role set permits no mutation
   path — it is refused, visible-but-refused per `C3` §5.1, never a silent
   404.

## 5. Out of scope

Password policy (`C3A` §4 and the Product Owner's 2026-09-13 deferral stand);
any forced password change; LDAP, RADIUS or TACACS; user-management screens;
changing `C3`'s bootstrap clause, its RBAC evaluation, or its session model;
and the deployment question `PO_DECISION_RECORD_2026_09_13D` leaves open as
AUTH-PLACEMENT.

## 6. Cross-references

- `UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md` §3, §6, §7, §8, §11 `U-2` —
  the contract this completes.
- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §5 (the role table and the
  separation-of-duties refusal ROLE-2 waives), and its Bootstrap clause
  (unchanged by BOOT-5).
- `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.5 — the audit trigger BOOT-4 uses.
- `AGENTS.md` — identity law, authority hierarchy.
