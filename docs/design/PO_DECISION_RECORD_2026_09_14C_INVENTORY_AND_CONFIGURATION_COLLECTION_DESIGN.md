# PO Decision Record — 2026-09-14 C — Inventory and configuration collection: two services, one code base, inventory first

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-14.** Records the Product
Owner's directions of 2026-09-14 for step 3 of the product sequence
(`PO_DECISION_RECORD_2026_09_13E` FROZEN: Discovery → GUI login →
**Inventory + Configuration collection** → Backup → Failover), together with
the Product Owner assistant's design derived from a complete read of the
earlier Python collectors (know-how only, never ported — `PO_DECISION_RECORD_
2026_09_12` §2). It sits under `PO_DECISION_RECORD_2026_09_13F` (FROZEN:
transport per vendor, identity-mismatch handling, configuration = one read
two outputs, inventory scope, cluster view) and narrows nothing there. Every
concrete device command or API route named below still needs its own
network-device command gate row before a live run; the two measurement
briefs (`CP_COLLECTION_MEASUREMENT_BRIEF_2026_09_13.md`,
`PAN_COLLECTION_MEASUREMENT_BRIEF_2026_09_13.md`, both DRAFT, not authority)
are reduced by §5 to the items the earlier product never proved.

## 1. Product Owner directives (2026-09-14)

- **IC-1. Inventory first.** IP addresses and routing tables, collected per
  device and shown in the common (cluster) view, are the priority; the
  configuration read follows in the same build.
- **IC-2. Two services, one code base allowed.** *Inventory collection*
  (IP/route) and *configuration collection* are two services in the product
  sense (independently runnable, separately scheduled later, `13E` SI-1..SI-4),
  and may be combined in code: one worker process, two job kinds, two
  capability sets. Splitting them into two Deployments later is a manifest
  change, not a code change.
- **IC-3. Inputs are what discovery and enrollment already hold:** vendor,
  management address, cluster membership (`cluster_member_ref`), VSX flag
  and virtual systems (`virtual_system_ref`), HA role — the `devices` /
  `endpoints` rows and their `C4` §4.2 target modifiers. Nothing is asked of
  the user beyond what the add flow already asked.
- **IC-4. The earlier Python is know-how:** its command payloads and
  output shapes inform the Java parsers; its channels, its shortcuts and its
  contradictions (listed in §4) are not carried.

## 2. Design

- **D-1. Job kinds.** `inventory_collect` and `configuration_collect`, both
  `C2` jobs admitted by `JobAdmissionService` only against `ENROLLED`,
  non-disabled devices (F4 refusal for `DRAFT` unchanged; `14B` EC-J1 stays
  the sole exception). One job per device per kind; cluster and VSX/vsys are
  target modifiers on the same job, never separate jobs (`C4` §4.2, `13F` CL-1).
- **D-2. Capabilities.** Per vendor and kind: `cp_inventory_collect`,
  `cp_configuration_collect`, `pan_inventory_collect`, `pan_configuration_collect`;
  `CAP_OFFLINE`, `FIXTURE_ONLY` until the §5 measurement lands, then a gate
  row per command promotes them. The VSX/vsys repetition is a per-context
  step group inside the same capability run.
- **D-3. Inventory read set (13F §4).** Version, serial/model, HA role
  (already observed at first contact, refreshed here); **interfaces with
  every address**, the address marked `member` or `cluster_virtual`; the
  routing table with destination, next hop, interface, protocol/source as the
  device reports it; per virtual system on VSX / per vsys on Palo Alto when
  the device has them. IPv6 addresses are kept when the device reports them.
- **D-4. Storage (C1 successor migration, additive).** `device_inventory_run`
  (one row per completed job: device, context set, collected_at, job id);
  `device_interface` (run, context, name, parent, kind, admin/oper state as
  reported); `device_interface_address` (interface, address with prefix,
  family, role `member|cluster_virtual`); `device_route` (run, context,
  destination, next hop nullable, interface nullable, source/protocol token,
  route table/virtual router when reported). Latest run per device is what
  the views read; earlier runs are kept for the later diff feature. No raw
  command output is stored (`AGENTS.md` raw-evidence law); the parser keeps
  only the columns above.
- **D-5. Common view (13F CL-2).** Computed in the service, not stored: for
  a cluster, member interfaces and routes are joined by (context, name) and
  (context, destination, next hop, interface); rows identical on every member
  appear once, rows present or different on a subset carry a member-difference
  marker naming which member. Cluster virtual addresses appear on the cluster
  row; member addresses under the member. Standalone devices show their own
  rows. VSX/vsys contexts are tabs or groups within the device.
- **D-6. Configuration (13F CF-1..CF-3, C7).** One read per device (and per
  virtual system on VSX); two outputs from the same bytes: a sanitized view
  copy (secret-bearing lines withheld and counted, sections projected) and
  the untouched copy stored encrypted under `C7` with its hash. The
  configuration service is built after the inventory service is merged; its
  read commands are named in §3 so the gate rows can be authored together.
- **D-7. Transport and identity.** Exactly `13F` §1–§2: Check Point over
  SSH to each member's own management address (never a VIP), Palo Alto over
  the XML API (direct, or Panorama `target=` where the estate prefers);
  identity mismatch warns and continues, strict refusal a posture switch off
  by default; credentials from the product store (`2026-09-14` CS-1..CS-5),
  never in a URL.

## 3. Command and request sets the Java capabilities will carry to the gate

Named here for the gate rows; **not approved by this record**. "Proven" means
the earlier product ran the payload against real devices and its output shape
is known; "channel differs" means it ran through the management server, which
`13F` forbids, so the same payload over a direct session is what §5 measures.

**Check Point, inventory (Expert shell over the device's own SSH session).**
- `ip -details -4 addr show` — interfaces and addresses. Proven payload,
  channel differs. Java also issues `ip -6 addr show` (unproven, additive).
- `ip -4 route show table all` — routes. Proven payload, channel differs.
- `cphaprob -a -m if` — cluster virtual addresses; cluster members only.
  Proven payload, channel differs. Only the section after
  `virtual cluster interfaces:` is read; addresses from this read are
  `cluster_virtual`, addresses from `ip addr` are `member`; the two never mix.
- `cphaprob stat` — HA role and cluster mode (`(local)` row; VSLS / Load
  Sharing / HA new mode / VRRP vocabulary). Proven in the config path.
- `vsx stat -v` — virtual-system list (VSID column only; the status column
  is policy/SIC state, not HA state). Proven.
- VSX per context, one composite line per channel:
  `vsenv <VSID>; ip -4 addr show; ip -4 route show` and
  `vsenv <VSID>; cphaprob stat`. **`fw ctl set int vsid` is not used** (the
  earlier product both issued it and listed it as a forbidden mutating
  command; the contradiction is resolved by omission). VS0 is the physical
  context and is not re-entered.

**Check Point, configuration.** Shell detection first (`show hostname`
bare, else `clish -c 'show hostname'`); then `show version all`,
`cpstat os -f hw_info` (never `show asset all`, which hung Take 120
gateways), `cphaprob stat`, `show configuration`; VSX per context
`vsenv <VSID>; clish -c 'show configuration'`, fallback one Clish process
with `set virtual-system <VSID>` then `show configuration`. All proven.

**Palo Alto, inventory (POST, key in `X-PAN-KEY`, credentials in the body).**
- `type=op&cmd=<show><system><info/></system></show>` — proven.
- `type=op&cmd=<show><high-availability><state/></high-availability></show>`
  — proven (`result/group/local-info`, `peer-info`).
- `type=op&cmd=<show><interface>all</interface></show>` — payload written,
  **shape unproven** (hand-made fixtures only); the Java parser reads every
  address element under an interface entry, not the first, and keeps the
  `vsys` and forwarding/virtual-router leaves.
- `type=op&cmd=<show><routing><route/></routing></show>` — payload written,
  **shape unproven**; the Java parser anchors on the route container, keeps
  the `flags` letters as the protocol token (static, connected, OSPF, BGP,
  RIP, host) instead of collapsing them, keeps the virtual-router leaf.
- Per vsys, `&vsys=<id>` on the two reads above — unproven.

**Palo Alto, configuration.** `type=config&action=show&xpath=/config`,
`<show><config><effective-running/></config></show>`,
`<show><config><merged/></config></show>` (proven), Panorama own
`xpath=/config` for Template / Device Group provenance (proven), and
`target=<serial>` twins (accepted by `13F` §6(b)).

## 4. What the earlier product did that is not carried

Collection through the management server (CPRID, nested SSH typed into the
MDS shell); credentials and keys in URL query strings; selecting cluster
members by a `-1`/`-2` name suffix; deciding scope by a substring test on
the whole `cphaprob stat` buffer; one address per Palo Alto interface;
dynamic routes reported as static; unbounded waits on the remote script;
raw route lines and raw interface XML persisted to disk; `fw ctl set int
vsid`; per-command exec channels for bare Expert reads (the real-environment
finding is that those never reached an Expert shell — one interactive
session per device, paced, is the pattern the Java transport keeps).

## 5. The reduced measurement the Product Owner runs

Only what the earlier product never proved. Record field names, headers,
counts and shapes; never an address, hostname or serial.

| # | Where | What to type / send | Question |
|---|---|---|---|
| M-1 | one Check Point gateway, own SSH | `ip -details -4 addr show`; `ip -4 route show table all`; `cphaprob -a -m if` | which shell the session lands in (Expert or Clish); whether each runs as typed; if Clish, whether `expert` is needed |
| M-2 | one VSX host | `vsx stat -v`; then one line `vsenv <VSID>; ip -4 addr show; ip -4 route show`; then in a **new** SSH channel `ip -4 addr show` | whether the new channel is back in the physical context |
| M-3 | one Palo Alto firewall, direct | R-4 `show interface all`, R-5 `show routing route` | per interface: one or several address elements; whether a floating/HA address is a separate element or a flag; the `flags` letters seen in routes |
| M-4 | same firewall, only if it has more than one vsys | R-4 and R-5 with `&vsys=<id>` | whether the parameter is accepted and the output narrows |

Until these are recorded, the four capabilities stay `CAP_OFFLINE` and no
live run is dispatched; the parsers, storage, views and job plumbing are
built against fixtures in the meantime.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_13E` (build order), `…_13F` §1–§7 (the
  decisions this record implements), `…_13D` DS-1/DS-2, `…_2026_09_14`
  DA/PF/CS, `…_14B` EC-J1..J5.
- `UI2_0_C2` (jobs), `UI2_0_C4` §4 (target modifiers), `UI2_0_C7` (backup
  copy), `UI2_0_B1_04` (step executor, parser framework, evidence writer).
- `DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` (DRAFT, not
  authority) §3–§5, §8 — the behaviour map this record's §3–§4 rest on.
- `CP_COLLECTION_MEASUREMENT_BRIEF_2026_09_13.md`, `PAN_COLLECTION_
  MEASUREMENT_BRIEF_2026_09_13.md` (DRAFT, not authority) — reduced by §5.
