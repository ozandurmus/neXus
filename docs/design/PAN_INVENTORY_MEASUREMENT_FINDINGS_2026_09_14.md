# Palo Alto inventory — measurement findings (14C section 5, M-3)

## Status

**DRAFT — NOT implementation authority.** Records what the Product Owner
observed on 2026-09-14 running `scripts/measurement/pan_inventory_shape.py`
against one PAN-OS firewall (direct, HA pair member). Element paths, value
shapes and counts only; no value. M-4 (per-vsys scoping) is owed only if the
firewall reports `multi-vsys` on.

## 1. Session

- `type=keygen` with credentials in the POST body succeeds; the key is a
  132-character token. Every read below: POST, key in the body, response
  `<response status="success">`.
- The firewall presents a self-signed certificate; the measurement script
  ran with verification disabled. The product transport keeps verification
  (trust rule) and this is a deployment-time trust decision, not a code one.

## 2. `<show><interface>all</interface></show>` (R-4)

Two lists under `result`:

- `hw/entry` (14 rows, physical ports): `name`, `id`, `mac`, `state`,
  `speed` (int or text), `duplex`, `type` (int), `mode`, `st`, `fec`.
- `ifnet/entry` (46 rows, logical interfaces): `name`, `id`, `ip`
  (`a.b.c.d/len` on 38 rows, a text token such as `N/A` on 8), `addr`
  (empty container on every row here — the place secondary addresses would
  appear as child entries), `addr6` (empty), `dyn-addr` (empty), `tag`
  (VLAN id, int), `vsys` (int), `zone` (text on 36, empty on 10), `fwd`
  (text, carries the virtual-router reference).
- **One primary address per logical interface in `ip`; secondary and
  IPv6 addresses live in `addr` / `addr6` children when present.** The
  parser reads `ip` plus every child of `addr` and `addr6`.
- **HA floating addresses are not distinguishable in this read** (no flag
  or separate element). They are configuration (`deviceconfig/high-
  availability/group/…/ip` floating entries) and are collected by the
  configuration service, not inventory; until then inventory shows what the
  member reports.

## 3. `<show><routing><route/></routing></show>` (R-5)

- `result/entry` (120 rows): `destination` (`a.b.c.d/len`), `nexthop`
  (address; `0.0.0.0` on connected rows), `interface` (text on 85, empty on
  35 — empty on host and some static rows), `flags` (**space-separated
  letters**, observed `A C`, `A H`, `A S`), `metric` (int), `route-table`
  (text), `virtual-router` (text), `age` (empty).
- `result/flags` carries the legend: `A:active, ?:loose, C:connect, H:host,
  S:static, ~:internal, R:rip, O:ospf, B:bgp, Oi/Oo/O1/O2 ospf subtypes,
  E:ecmp, M:multicast`. The parser tokenises on whitespace; `A` is a state,
  the remaining tokens give the protocol (`C`→connected, `S`→static,
  `H`→host, `R`→rip, `O*`→ospf, `B`→bgp), `E`/`M` are qualifiers.
- Rows are directly under `result`, not nested per virtual router; the
  `virtual-router` leaf scopes them (stored as the route table).

## 4. `<show><system><info/></system></show>` (R-3)

Identity fields present: `hostname`, `devicename`, `serial` (digits),
`model`, `family`, `platform-family`, `sw-version`, `app-version`,
`ip-address`, `netmask`, `default-gateway`, `mac-address`,
`operational-mode`, `multi-vsys` (text on/off — decides M-4),
`advanced-routing` (text — decides logical-router vs virtual-router
reads), `uptime`, `time`, content versions, `plugin_versions/entry@name/@version`.

## 5. `<show><high-availability><state/></high-availability></show>`

- `result/enabled`; `result/group/mode`; `running-sync`,
  `running-sync-enabled`.
- `local-info`: `state`, `mode`, `priority`, `preemptive`, `state-sync`,
  `state-sync-type`, `mgmt-ip` (`a.b.c.d/len`), `mgmt-ipv6` (empty),
  `ha1-ipaddr`/`ha1-backup-ipaddr`/`ha2-ipaddr` (`a.b.c.d/len`), their
  `-macaddr` and `-port`, `serial-num`, `platform-model`, `build-rel`,
  content versions and compatibility flags, hold/flap counters.
- `peer-info`: `state`, `mode`, `priority`, `preemptive`, `mgmt-ip`,
  `ha1-ipaddr`/`ha1-backup-ipaddr`/`ha2-ipaddr` (no prefix), `conn-status`,
  `conn-ha1` / `conn-ha1-backup` / `conn-ha2` each with `conn-status` and
  `conn-desc`, `serial-num`, `platform-model`, `build-rel`, versions.
- `link-monitoring` (enabled, failure-condition, groups/entry with
  interface/entry name+status — 8 here) and `path-monitoring` (enabled,
  failure-condition, empty vr/vwire/vlan containers).
- This confirms the earlier product's field family and answers the
  `conn-ha1-backup/conn-status` nesting it had marked as a guess: present.

## 6. M-4 — per-vsys scoping (multi-vsys firewall)

- `&vsys=<id>` is accepted on both reads.
- **Interfaces narrow:** `ifnet/entry` went from 46 to 26 rows for the
  second vsys; the `hw/entry` list (14 physical ports) is unchanged. The
  unscoped read already carries a `vsys` leaf on every logical interface,
  so one unscoped read plus that leaf gives the per-vsys view without
  per-vsys calls.
- **Routes do not narrow:** the scoped and unscoped route reads were
  byte-identical (120 rows). Routes are scoped by `virtual-router`, not by
  vsys. Per-vsys route attribution therefore goes virtual-router →
  interfaces in that virtual router (from `ifnet/entry/fwd`) → those
  interfaces' `vsys`; a virtual router whose interfaces span vsys is shown
  under each.
- Consequence for 14C §3: the `&vsys=<id>` forms are not needed for
  inventory; one interface read and one route read per firewall.

## 7. Nothing further owed for inventory measurement.
