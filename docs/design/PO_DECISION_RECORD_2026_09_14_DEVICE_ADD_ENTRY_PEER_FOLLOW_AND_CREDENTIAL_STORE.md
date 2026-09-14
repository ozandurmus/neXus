# PO Decision Record — 2026-09-14 — Device add entry, peer follow, and the credential store

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-14.** Records decisions the
Product Owner gave in the 2026-09-14 local session after signing in to the
running product for the first time and opening the device-add dialog.
`AGENTS.md` "Authority hierarchy" item 7 makes session chat non-authoritative;
this file is the durable record. It creates no new authority, it preserves
authority the Product Owner exercised, and it is implementation authority for
the credential store movement §3 names.

It restates `PO_DECISION_RECORD_2026_09_13C` §3 (one device-add entry point)
where the shell had drifted from it, extends `13F` §5 with a peer-follow rule,
and settles how device credentials are held — the point `13C` SB-3 left as
"the store behind the reference is still missing".

## 1. One entry, three inputs, then a fork — nothing else asked

**PO directive.** *Not two menus. One menu: IP, vendor, then whether this is a
single device or a discovery run. No device-name field — I enter the IP and
the vendor; the rest is read from the device.*

- **DA-1.** The device-add dialog asks for exactly: the **address**, the
  **vendor**, and the **credential** (selected from the store, §3). Then one
  choice: **management server** (run discovery against it, `13C` §3's two
  management types) or **single device** (register it). No display-name
  field. `UI2_0_B1_04B` §4's "device name" input is superseded for the
  product's own dialog; a display label may be edited later and is never an
  identity (`AGENTS.md` identity law, unchanged).
- **DA-2.** A single device's facts — hostname, model, software version,
  HA/cluster role — are **read from the device on first contact**, never typed.
  This first contact is the enrollment confirm of
  `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` §4 (DRAFT): connect with the
  selected credential, perform the one identity read per vendor, record the
  presented identity, and land the row `ENROLLED` — under `13F` §2's
  warn-and-continue rule on any later mismatch. The two identity reads are
  class-0 commands and each needs its network-device command gate row before
  a movement issues it.
- **DA-3.** The management-server path is the discovery transport already
  merged for each vendor, driven from the same dialog: enumerate, show the
  candidate set (cluster as parent, `13C` SB-9), the operator multi-selects,
  import creates the rows.

## 2. Peer follow — a single member pulls its cluster in

**PO directive.** *If I add one device and it is a cluster member — even one
Panorama does not manage — the product should find the second member itself
and bring it into the cluster view.*

- **PF-1.** When the first-contact identity read of a single device reports
  that it is a member of a high-availability unit and names a peer, the
  product **contacts the peer too**, with the same credential, and performs
  the same identity read there.
- **PF-2. The unit forms only on corroboration** (`AGENTS.md` "Evidence laws":
  a member's report about its peer is one-sided until the peer independently
  confirms it in the same pass; `13C` SB-7). The peer's own read must name the
  first device back. Then both rows are created and the cluster is shown as
  their parent. A one-sided claim is shown as such and forms no unit.
- **PF-3.** If the peer cannot be reached with that credential, or its address
  is not carried by the first device's HA report, the product records
  "peer named, not confirmed" against the first device and stops; it never
  guesses an address and never retries with another credential on its own.
- **PF-4. What is `UNKNOWN` until measured.** Palo Alto's HA state carries the
  peer's management address; Check Point's `cphaprob` output names members by
  cluster interface and may not carry a management address at all. Which is
  the case on this estate is settled by the collection measurement briefs,
  not assumed here.
- **PF-5.** Peer follow is one additional device contact, triggered by the
  operator's own add action; it is not a scan, and it never widens past the
  named peer.

## 3. The credential store — typed once in the product, never read back

**PO directive.** Credentials are selected in the dialog from a store the
product itself holds; the operator must be able to create them in the
interface (the same reasoning as `13G` LIA-1/LIA-2 for local identities).

- **CS-1.** The product holds device and management-plane credentials in its
  own database, **encrypted at rest** under an envelope key supplied to the
  service as a file, exactly the mechanism `role_bindings.group_reference_
  encrypted` already uses (`UI2_0_C3` §4.2, `GroupReferenceCipher`). This is
  a new `secrets_metadata.backend_kind`, recorded as a successor clause
  `UI2_0_C1` §6 owes: `C1` §6.2's file/env mechanism stays the rule for the
  product's *own* secrets (database DSN, envelope keys); device credentials
  are data the operator manages, and they live behind the envelope.
- **CS-2.** A credential has: a display name, a **kind** (SSH password, SSH
  private key with optional passphrase, API password; SB-15 keeps key-based
  auth an option, never the default), the username, the secret, and the
  vendors it may be used for. The secret is written once and is **never
  returned** by any API, screen, CLI command, log, audit row or exception
  (`13G` LIA-3.2 carried over). Replacing it is the only edit of the secret.
- **CS-3.** `credential_references.backend_pointer` (`C1` §3.2, `B1_04B` §6)
  points at a store row; the value is resolved **only in the worker at
  execution time**, replacing the environment-variable stand-ins the two
  discovery runners carry today (`EnvironmentCredentialAndTrustResolvers`,
  `EnvironmentPanCredentialAndTrustResolvers`). `SB-16` stands: a reference
  that cannot be resolved refuses before any contact.
- **CS-4.** One credential may back many devices and both vendors (`SB-14`).
  Deleting a credential still referenced by a device is refused; the device
  must be re-pointed first.
- **CS-5.** Administration: creating, listing (names and kinds only),
  replacing the secret, and deleting are `role:security_admin` actions on the
  existing authenticated surface, with CLI parity (`13G` LIA-2), every
  mutation audited. No approval workflow, no rotation policy, no complexity
  rule — a production posture switch may add those later (`13G` LIA-4 spirit).

## 4. Sequencing

The credential store is the prerequisite of both branches of §1: neither a
single-device add nor a management-server discovery can run from the product
until a credential can be selected. It is dispatched first, without waiting
for the import contract's freeze. The single-device add (DA-2, PF-1..5) needs
`DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` frozen and its two identity-read
gate rows approved; the management-server path (DA-3) needs the discovery
runs' `UNVERIFIED` bindings confirmed on the live servers. All three are step
2→3 work of `13E` §1.

## 5. Cross-references

- `PO_DECISION_RECORD_2026_09_13C` §3 (one entry point), SB-3, SB-7, SB-9,
  SB-12–SB-16; `13E` §1; `13F` §2, §5; `13G` LIA-1..LIA-4.
- `UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §2, §4, §6 — the
  records and the dialog this narrows.
- `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.2, §6 — `credential_references`,
  `secrets_metadata`, the secret mechanism and the successor clause CS-1 names.
- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §4.2 — the envelope pattern.
- `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` (DRAFT) §4 — the first contact.
- `AGENTS.md` — identity law, evidence laws, network action taxonomy.
