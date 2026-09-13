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
- **BOOT-3. The verifier is computed at boot and is never a literal; the
  initial password is a named constant and may be.** `C3A` §3 requires a
  random per-credential salt, so a verifier committed to version control
  pins that salt permanently and turns a published default into a
  precomputed one. **No migration, source file, resource or manifest may
  carry a verifier.**

  The initial password is a different case and the distinction matters. A
  shipped default administrator credential is public by construction — it is
  in the product's own documentation, the way an appliance's is — so keeping
  it out of the source buys nothing and would make a shipped default
  impossible. It is therefore a **named constant in one place**, referenced
  from there and never re-spelled. An earlier draft of this clause forbade
  both and was self-contradictory: it required a default the product could
  not carry. Corrected here.

- **BOOT-3a. The initial credentials, named.** `nexusadmin`'s initial
  password is `nexusadmin`; `claudeadmin`'s is `claudeadmin`. The Product
  Owner set these on 2026-09-13, in the appliance model they asked for —
  the product is administrable the moment it is deployed, with a credential
  its own documentation states.

  They are weak by design and published by design, which is what makes
  `C3A` §4's change path and `BOOT-2`'s idempotence the controls that
  matter: the credential is expected to be changed, and the product must
  never undo that change.
- **BOOT-4. Seeding is audited.** Each created identity produces an
  `audit_log` row through `C1` §3.5's existing trigger, attributed to a
  reserved bootstrap actor marker — the same posture `C3`'s own bootstrap
  clause already uses. No credential material reaches the row (`C3A` §8).
- **BOOT-5. First-boot seeding creates the role bindings too, and this
  satisfies `C3`'s Bootstrap clause rather than bending it.** A default
  administrator that can authenticate but cannot administer is not a default
  administrator. The Product Owner asked for the appliance model, where the
  product is usable the moment it is deployed.

  `C3`'s Bootstrap clause exists to stop two specific things: **no HTTP path
  may create the first `role:security_admin` binding** (there is no
  authenticated session to call it, and the self-grant refusal would block
  the first administrator's own binding anyway), and the creation must be a
  **deployment-controlled** action rather than a running service acting on a
  request. First-boot seeding is exactly that: it runs as part of deploying
  the product, from no request, with no session, and grants nothing to a
  caller. It is not an HTTP path and it is not a self-grant.

  An earlier draft of this clause read "CLI-only" as "a human must type a
  command" and left a freshly deployed product able to authenticate and
  unable to do anything. That was an over-reading of `C3`, not a requirement
  of it, and it produced a product the Product Owner did not ask for.
  Corrected here.

- **BOOT-5a. What is seeded.** `nexusadmin` receives full administrative
  capability (ROLE-1) and `claudeadmin` receives `role:viewer` and nothing
  else (ROLE-3), in the same first-boot transaction that creates the
  identities. Each binding is audited through `C1` §3.5 under the same
  reserved bootstrap actor marker as the identity itself.

- **BOOT-5b. The existing deployment-controlled CLI path stays.** It remains
  the way a binding is created or changed outside first boot, and every
  binding after the bootstrap ones goes through `C3`'s normal authorized
  path. Nothing about `C3`'s self-grant refusal, its audit requirement or its
  prohibition on browser-created bindings changes.

## 3. Roles

**PO directive, 2026-09-13**, option C of three that were put to the Product
Owner.

- **ROLE-1. `nexusadmin` is the default administrator and holds full
  administrative capability.** This is what a default administrator is, on
  this product as on any appliance that ships with one. It is not an
  exception to anything and needs no justification beyond being the account
  the product is first administered with.

- **ROLE-2. `C3` §5's separation of duties is an operational-role rule, not a
  rule about this account.** That clause exists so that, once distinct people
  hold distinct jobs, the person who administers authorization is not also the
  person who executes with it. The bootstrap administrator predates any such
  split and is outside the rule's subject. Recorded only so a later reader
  does not mistake ROLE-1 for an oversight.

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
3. No migration, source file, resource or manifest contains a **verifier**. A
   test asserts this. The initial password constant is permitted in exactly
   one place (BOOT-3) and a test asserts it is not re-spelled anywhere else,
   so there is one value to change and not several to hunt.
4. Seeding produces one `audit_log` row per identity, attributed to the
   reserved bootstrap marker, carrying no credential material.
5. First boot creates the two bootstrap role bindings, and a test asserts a
   freshly deployed product is **usable**: `nexusadmin` authenticates and
   resolves full administrative capability without any further deployment
   step (BOOT-5, BOOT-5a).
6. Immediately after first boot, with no further step, `nexusadmin` resolves
   full administrative capability and `claudeadmin` resolves `role:viewer`
   only.
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
