# PO Decision Record — 2026-09-14 D — Check Point inventory: the measured command forms

## Status

**FROZEN — PRODUCT OWNER MEASUREMENT APPLIED, 2026-09-14.**

**Amendment (2026-09-21, PO APPROVED, cp_vsx_interfaces_identical_to_physical).**
CF-3's interface/address reads (`ip -details -4 addr show` / `ip -6 addr
show` physical; `ip -4 addr show` per virtual system) are superseded by
`fw getifs` -- bare on a physical member, `vsenv <VSID> && fw getifs` per
virtual system. Measured live by the Product Owner: chaining multiple
`vsenv <vsid> ...` context switches for different VSIDs inside one shared
non-interactive shell does not reliably re-scope every later `ip addr show`
read to its own VSID (two different VSIDs on the same device returned the
same address sequence from the same base); `fw getifs` does not have this
failure mode. `fw getifs` also carries no up/down state column, so an
interface parsed from it is recorded `unknown`, not guessed. `cphaprob -a
if` (already amended once, see `InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF`'s
own history) and the route/HA/VSX-enumeration reads are unaffected. See
`docs/history/backlog/cp_vsx_interfaces_identical_to_physical.md` for the
measurement trail. Successor to
`PO_DECISION_RECORD_2026_09_14C_INVENTORY_AND_CONFIGURATION_COLLECTION_DESIGN.md`
§3 (Check Point inventory command set) and §5 (M-1, M-2), which are not
edited in place. The Product Owner ran the reduced measurement on one
ClusterXL member and two VSX hosts today; the observations are in
`CP_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` (DRAFT, not authority —
the record of what was seen). This record turns them into the forms the
Java capability carries to the gate. M-3/M-4 (Palo Alto) remain owed.

## 1. Command forms (replace 14C §3 "Check Point, inventory")

- **CF-1. Landing shell is Expert; reads run as typed.** No `clish -c`,
  no `expert` step.
- **CF-2. Context is never assumed.** A fresh SSH session can land in a
  non-zero virtual-system context. Every read is issued with its context
  made explicit through the login-shell form, which is the only
  non-interactive form that resolves `vsenv` (a shell function, not a
  binary): physical reads as `bash -lc 'vsenv 0 && <read>'` on a VSX host
  and bare on a non-VSX gateway (where `vsenv` does not exist); per
  virtual system as `bash -lc 'vsenv <VSID> && <read1> && <read2>'`. The
  sourced-profile form is not used (it does not define `vsenv`).
- **CF-3. The physical read set:** `ip -details -4 addr show`,
  `ip -6 addr show`, `ip -4 route show table all`, `cphaprob stat`,
  `cphaprob -a -m if`, `vsx stat -v`. **Per virtual system:**
  `ip -4 addr show`, `ip -4 route show`, `cphaprob -a -m if` (this one is
  new against 14C: it is where a virtual system's cluster addresses are),
  `cphaprob stat`.
- **CF-4. VSX detection is by text.** `vsx stat -v` prints
  `VSX is not supported on this platform` with exit code 0 on a non-VSX
  gateway; the VSID set comes only from its `Virtual Devices Status` rows
  (ID column, type letter kept), and differs per host.

## 2. Parser rules fixed by measurement

- **PR-1.** `proto` tokens are numeric on Gaia: `7` is the routing daemon's
  installed route (default and static) and maps to `static`; `kernel` maps
  to `connected`; the raw token is kept in the stored `protocol` field's
  companion column only if one exists, otherwise the mapping alone.
  Rows with `table local` (`broadcast` / `local`) are not routes. A
  connected row may read `dev X proto 7 scope link` without `src`.
- **PR-2.** Interface header names may carry `@parent` (`bond1.3841@bond1`)
  or `@ifNN` inside a virtual system (`eth3-01.3247@if34`); the part before
  `@` is the name; a `vlan protocol 802.1Q id <n>` detail line gives the
  VLAN id; `UP` is read from the flag list, not by substring.
- **PR-3.** `cphaprob -a -m if` output is headed `vsid <N>:` on VSX. The
  cluster-address block is `Virtual cluster interfaces: <n>` followed by
  `<n>` rows `name address [VMAC address: mac]`. The interface table rows
  are `name [(S)|(LS)|(HA)] UP|Non-Monitored|DOWN`. The VLAN table has two
  shapes (`Interface | Low VLAN | High VLAN` on ClusterXL, `Interface | VS
  ID | Monitored VLANs` on VSX) and is not parsed.
- **PR-4.** `cphaprob stat`: mode strings `High Availability (Active Up)
  with IGMP Membership` and `Virtual System Load Sharing (Active Up)`;
  local row `<id> (local)`; on VSLS the `Virtual Devices Status on each
  Cluster Member` table gives each virtual system's role on every member
  (`<VSID> | <weight> | <state m1> | <state m2>`, `[local]` marks the
  local column) and is parsed for the per-VS HA role.
- **PR-5.** Unsolicited syslog/kernel lines may appear inside any output;
  unknown lines are skipped and counted, never fatal.

## 3. Gate consequence

The closed command set the capability carries to the gate is CF-3 in the
forms of CF-2. Gate rows are authored per literal (the `bash -lc` wrapper
is part of the literal). The capability stays `CAP_OFFLINE` until those
rows exist; nothing here authorizes a live run.

## 4. Cross-references

- `PO_DECISION_RECORD_2026_09_14C` §2 D-3/D-4, §3, §5 — amended by §1–§2.
- `PO_DECISION_RECORD_2026_09_13F` §1, §4, §5 — unchanged.
- `CP_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` (DRAFT, not authority) — the observations.
