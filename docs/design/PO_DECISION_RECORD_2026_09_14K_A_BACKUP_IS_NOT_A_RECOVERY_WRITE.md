# PO Decision Record — 2026-09-14 K — A backup creation is not a recovery write

## Status

**FROZEN — PRODUCT OWNER DIRECTIVE, 2026-09-14.** Successor clause to
`AGENTS.md`'s network action taxonomy, which states that class-1 controlled
recovery writes "are permitted only through their RB.x contracts and are
never console-submittable". Raised, correctly and without being silently
resolved, by movement `NXS-LOCAL-0175` while implementing
`PO_DECISION_RECORD_2026_09_14H` BK-12, which authorizes a manual,
operator-triggered backup. The two read as a contradiction. They are not:
the taxonomy's sentence was written for one kind of class-1 write and this
record separates the two kinds.

## 1. The distinction

- **BW-1. A recovery write changes what a device is or does** — it restores
  a configuration, pushes state, alters policy. It is irreversible in
  practice, it is the operation the taxonomy's sentence exists to keep out
  of a browser, and it stays **never console-submittable**. Restore remains
  disabled in this product (`UI2_0_C7` Status, amendment A-1); nothing here
  changes that.
- **BW-2. A backup creation writes a transient artefact and removes it.**
  It creates an archive in the device's own backup directory, the product
  reads it, verifies it on both sides and deletes the exact file it made.
  Afterwards the device is in the state it was in before. It consumes disk
  and vendor task time; it changes no configuration, no policy, no routing,
  no cluster state.
- **BW-3.** Both are class 1 and both need their own command gate, their
  own contract and their own audit. The class is about care, not about
  which surface may ask for it.

## 2. The decision

- **BW-4. A backup creation is console-submittable**, under every guard
  `14H` already fixed and no fewer: the device must be inside the pilot
  allowlist (BK-1), the operator must hold `role:backup_admin` and give a
  reason of at least eight characters (BK-12, `C7` §6.1), the run is one
  device per job, and every step is audited. Without a UI trigger, BK-12's
  "manual backup first" cannot exist at all, which is what the Product
  Owner chose as the first form of this feature.
- **BW-5. The refusal precedent stays.** The taxonomy's existing
  non-console-submittable example remains exactly as it is, as the
  demonstration that the gate chain refuses a browser-submitted recovery
  write unconditionally. A backup action registered as submittable does not
  weaken it; the two now sit side by side and a reader can see which is
  which and why.
- **BW-6.** Any future class-1 action must state, in its own record, which
  of BW-1 and BW-2 it is. Silence means BW-1, and therefore refusal: the
  default is closed.

## 3. Cross-references

- `AGENTS.md` — network action taxonomy, the sentence this clause narrows.
- `PO_DECISION_RECORD_2026_09_14H` BK-1, BK-12; `UI2_0_C7_…` §6.1 and its
  Status on restore.
