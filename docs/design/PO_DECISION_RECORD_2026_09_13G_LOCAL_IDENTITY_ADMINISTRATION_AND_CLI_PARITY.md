# PO Decision Record — 2026-09-13 — Local identity administration from the product, and CLI parity

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-13.** Records a decision the
Product Owner gave in the 2026-09-13 local session. `AGENTS.md` "Authority
hierarchy" item 7 makes session chat non-authoritative; this file is the
durable record. It creates no new authority, it preserves authority the
Product Owner exercised.

It does not amend `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`,
`UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md` or
`UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md`, all FROZEN. It settles what
those documents leave unbuilt rather than changing what they fix, and it is
implementation authority for the local identity administration movement it
names in §5.

## 1. The gap, stated accurately

The product can authenticate a local identity, seed the two bootstrap
identities at first boot, and create or revoke a role binding over HTTP under
`C3` §4.3's four-eyes and self-grant rules. What it cannot do is **create a
local identity at all from anything but a deployment-controlled CLI step**:
no HTTP path writes a `local_credentials` row.

`C3` §4.3's prohibition is narrower than it has been read in this session:
it says that **before any `role:security_admin` binding exists**, no HTTP
path can create that first binding — a bootstrap chicken-and-egg problem
that `C3B`'s first-boot seeding already solves. It does not forbid creating
a local identity over HTTP, and no other frozen clause does either. The
absence is a gap in what was built, not a rule.

## 2. The decision

**PO directive, 2026-09-13, in the Product Owner's own framing:** *there is
no HTTP prohibition. This is an internal-use product. There will be some
constraints, of course, but there is no need to push everything to its
limit — I do not want extra burden. The CLI must remain an option, but I
must also be able to create this user from the interface. And in parallel
with these developments we must build our own CLI parameters.*

- **LIA-1. Local identity administration is reachable from the product's own
  authenticated surface.** Creating a local identity, listing identities,
  setting or resetting a password, disabling and re-enabling an identity, and
  assigning or revoking its role bindings are all normal authenticated
  actions of the existing `service`, not CLI-only operations.
- **LIA-2. The CLI keeps parity and stays supported.** Every action of LIA-1
  is also available as a `ui2` CLI command. The CLI is the deployment-controlled
  path and the recovery path when no administrator can sign in; it is an
  option, never the only way. Building out those CLI parameters is part of
  the same work, not a follow-on.
- **LIA-3. Proportionate constraints, named, and no more than these.** This is
  an internal product; the guardrails are the ones that prevent a real
  operational accident or a real credential leak, and nothing is added
  because it is theoretically stricter:
  1. Every mutation is audited through `C1` §3.5's existing trigger, under
     the acting identity.
  2. No password, verifier or salt is ever returned, logged, audited or
     shown — an administrator sets a password without the product retaining
     or displaying it, and `C3A`'s parameters and zeroing discipline carry
     over unchanged.
  3. A password an administrator sets for someone else is, by construction, a
     password that identity must change at its next sign-in.
  4. Role-binding mutation keeps `C3` §4.3's four-eyes and self-grant refusal
     exactly as frozen. Creating or disabling an identity is not a
     role-binding mutation and does not inherit four-eyes.
  5. The product refuses the action that would leave it with no enabled
     identity holding `role:security_admin`. This is the one lock-yourself-out
     case worth a hard refusal.
  6. A disabled identity cannot authenticate, and its active session, if any,
     ends.
- **LIA-4. What is deliberately NOT required.** Password complexity policy
  beyond what `C3A` already fixes, expiry, history, self-service reset,
  multi-factor, and an approval workflow for creating an identity. Each is a
  later decision if the Product Owner asks for it; none is a prerequisite.
- **LIA-5. Which role administers identities.** `C3` §4.1 gives
  `role:security_admin` role-binding administration, session administration
  and audit read, and gives it no device-facing action. Local identity
  administration is the same authority and belongs to `role:security_admin`;
  no new role token is created (`C3` §4.1's vocabulary is closed).
- **LIA-6. One authenticated surface, unchanged.** This adds capability to
  the existing `service`, the only authenticated surface until AUTH-PLACEMENT
  is decided (`PO_DECISION_RECORD_2026_09_13D` §3, §4). It creates no second
  surface.

## 3. What the operator sees

A list of local identities showing, per identity: its display name, whether
it is enabled, its role tokens, and whether it must change its password at
next sign-in. Never a verifier, a salt, a password, a group reference or a
session token. Role tokens are displayed and are never used to decide what
the interface renders (the existing architecture test).

## 4. Schema this needs

An additive migration — `C1` stays FROZEN and Flyway remains its sole
migration authority — adding to the local credential row: an
enabled/disabled state, the actor that created it, and the timestamp its
password was last set. Column names and types are the implementing
movement's, under `C1`'s existing conventions and its §3.5 audit trigger.

## 5. Sequencing

This work is step 2 of `PO_DECISION_RECORD_2026_09_13E` §1 — GUI login, local
user first — and runs alongside the session movement that adds forced
password change, the signed-in identity display and sign-out. Neither blocks
the other; they share the local credential table and must not both claim the
same migration number.

## 6. Cross-references

- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §3, §4.1, §4.3 — sessions,
  the closed role vocabulary, four-eyes and the bootstrap clause this record
  reads accurately rather than widens.
- `UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md` — verifier storage, lockout,
  the identical refusal, the self password-change path.
- `UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md` — first-boot seeding and
  BOOT-5b's deployment-controlled CLI paths, which LIA-2 keeps.
- `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.5, §6 — the audit trigger and the
  migration authority §4 stays inside.
- `PO_DECISION_RECORD_2026_09_13D` §3, §4 — one authenticated surface until
  AUTH-PLACEMENT; `..._13E` §1 — the build order.
- `AGENTS.md` — authority hierarchy item 7, identity law, privacy and DLP.
