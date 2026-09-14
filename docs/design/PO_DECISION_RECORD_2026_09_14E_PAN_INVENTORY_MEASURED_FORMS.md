# PO Decision Record — 2026-09-14 E — Palo Alto inventory: the measured forms and the vsys / virtual-router model

## Status

**FROZEN — PRODUCT OWNER MEASUREMENT APPLIED, 2026-09-14.** Successor to
`PO_DECISION_RECORD_2026_09_14C_INVENTORY_AND_CONFIGURATION_COLLECTION_DESIGN.md`
§3 (Palo Alto inventory requests) and §5 (M-3, M-4), not edited in place.
The Product Owner ran the reads against one multi-vsys PAN-OS firewall
today (observations: `PAN_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md`,
DRAFT, not authority) and confirmed the model below in session. Twin of
`PO_DECISION_RECORD_2026_09_14D` for Check Point.

## 1. Request forms

- **PF-1.** One `type=keygen` (credentials in the body), then per
  firewall: `show system info`, `show high-availability state`,
  `show interface all`, `show routing route` — each once, unscoped, key in
  the request body or header, never in the URL.
- **PF-2. No per-vsys requests.** `&vsys=<id>` narrows only the interface
  list and leaves the route list byte-identical; the unscoped interface
  read already carries a `vsys` leaf per logical interface. The
  `&vsys=` forms named in 14C §3 are dropped.
- **PF-3.** `advanced-routing` in `show system info` decides, later,
  whether logical-router reads replace `show routing route`; the measured
  firewall used virtual routers and this record covers that form only.

## 2. The one-box model (Product Owner, 2026-09-14)

- **PM-1.** A Palo Alto firewall is one device with one interface list and
  one route table; virtual systems are contexts derived from that data,
  not separate reads. This differs from VSX, where each virtual system has
  its own interfaces and routes and is read in its own context (14D).
- **PM-2. Interfaces belong to a vsys by the `vsys` leaf** of each
  `ifnet/entry`; `hw/entry` rows are physical ports of the box (context
  `physical`, no address).
- **PM-3. Routes belong to a virtual router** (`virtual-router` leaf, kept
  as the route table). **A virtual router maps to a vsys through its
  interfaces:** `ifnet/entry/fwd` names the virtual router (`vr:<name>`),
  and those interfaces' `vsys` leaves give the vsys. A virtual router whose
  interfaces span more than one vsys is shown under each; a virtual router
  with no interface is shown under `physical`.
- **PM-4.** The inventory contexts stored for a Palo Alto firewall are
  `physical` (ports, unattributed routes) plus one per vsys id seen.

## 3. Parser rules fixed by measurement

- **PP-1.** `ifnet/entry`: `name`, `id`, `ip` (primary, `a.b.c.d/len` or a
  text token such as `N/A`), every child of `addr` and `addr6` (secondary
  and IPv6 addresses when present), `tag` (VLAN id), `vsys`, `zone`,
  `fwd`. An interface without an address is kept. `hw/entry`: `name`,
  `state`, `speed`, `duplex`, `mac`, `type`, `mode`.
- **PP-2.** `result/entry` routes: `destination`, `nexthop` (`0.0.0.0`
  means none), `interface` (may be empty), `flags` tokenised on
  whitespace — `A` active, `C` connected, `S` static, `H` host, `R` rip,
  `O`/`Oi`/`Oo`/`O1`/`O2` ospf, `B` bgp, `E` ecmp, `M` multicast, `?`
  loose, `~` internal — `metric`, `route-table`, `virtual-router`. Rows
  are directly under `result`; the `result/flags` legend line is not a
  route.
- **PP-3.** HA floating addresses are not present in `show interface
  all`; inventory shows what the member reports, and the floating set
  comes with the configuration service (`deviceconfig/high-availability`).
- **PP-4.** HA state: `local-info/state`, `mode`, `priority`,
  `preemptive`, `state-sync`, `mgmt-ip`; `peer-info/state`, `mgmt-ip`,
  `conn-status`, `conn-ha1`/`conn-ha1-backup`/`conn-ha2` `conn-status`;
  `running-sync`. The `conn-ha1-backup/conn-status` nesting the earlier
  product guessed is confirmed present.

## 4. Gate consequence

The closed request set is PF-1 exactly; gate rows are authored per
request. `pan_inventory_collect` stays `CAP_OFFLINE` until they exist.

## 5. Cross-references

- `PO_DECISION_RECORD_2026_09_14C` §2 D-3/D-4, §3, §5 — amended by §1–§3.
- `PO_DECISION_RECORD_2026_09_14D` — the Check Point twin.
- `PO_DECISION_RECORD_2026_09_13F` §1, §4, §5 — unchanged.
- `PAN_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` (DRAFT, not authority) — the observations.
