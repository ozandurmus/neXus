# PO Decision Record — 2026-09-14 J — nexusadmin is the product's root identity

## Status

**FROZEN — PRODUCT OWNER DIRECTIVE, 2026-09-14.** Given in session after the
local administrator was refused every role-gated action on a local-only
install: "nexusadmin bu ürünün süperadmini. Her şeyde root yetkisine sahip.
Kimse buna engel olamaz. Benim ldap accountumdan daha yetkili olacak."
Successor clause to `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN,
not edited in place) and to `PO_DECISION_RECORD_2026_09_13G` LIA-1..LIA-6.

## 1. The decision

- **RT-1. The product has exactly one root identity: `nexusadmin`**, the
  identity the first boot seeds. It holds every authority in the product,
  above any directory-derived authority, and no configuration, role
  binding, directory state or posture switch can take that away.
- **RT-2. Authorization for the root identity is a permit, always.** The
  evaluator answers `PERMITTED` for every action, whatever the required
  role token, whether or not any binding exists, and whether or not a
  directory group set is present or fresh. The refusals that stranded it
  today — an unevaluated decision for a stale group set, an unbound role
  token — never apply to it.
- **RT-3. The root identity is recognised by its seeded identity id**, not
  by its name. A renamed, re-created or same-named second identity is not
  the root; the id the first boot recorded is. Nothing a client sends can
  make a session claim it.
- **RT-4. The root identity cannot be locked out of the product:** it
  cannot be disabled, its bindings cannot be revoked to nothing, and the
  four-eyes rule on role bindings never applies to the act of restoring its
  own access. The existing rule that the product never leaves zero enabled
  security administrators is subsumed by this one.
- **RT-5. Root is not invisibility.** Every action it takes is audited like
  any other, and the decision row records that the permit came from the
  **root authority** rather than from a binding or a directory, so an
  auditor can always separate "allowed because root" from "allowed because
  entitled". This is the price of RT-2 and it is not negotiable: an
  unblockable identity that left no trail would be worse than the lockout
  it fixes.
- **RT-6. Root outranks the directory.** When the same human holds both a
  directory account and the root identity, the root identity is the more
  privileged of the two, and a directory that denies, goes away or is never
  configured changes nothing about it.

## 2. What this does not change

Every other identity, local or directory-derived, is authorized exactly as
`C3` specifies: by its bindings, against a fresh group set where the
directory is the authority, with `AUTHZ_NOT_EVALUATED` preserved for a
stale one. `C3`'s outcome vocabulary, its reason codes and its audit
obligations stand. The root identity is one identity, not a role anyone
else can be granted.

## 3. The risk, stated

An account that cannot be blocked is also an account that cannot be
contained if its credential is taken. The mitigations that stay required:
its password is administrator-set and changeable, its sessions obey the
same lifetime rules as any other, its every action is audited under RT-5,
and a later movement may add an alert on root-authority use. The Product
Owner has weighed this and chosen it; the record exists so the choice is
visible rather than implicit.

## 4. Cross-references

- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` — the model this extends.
- `PO_DECISION_RECORD_2026_09_13G` LIA-1..LIA-6 — local identity
  administration and CLI parity.
- `UI2_0_C3B` (first-boot seeding) — where the root identity's id is set.
