# Backups screen: active major deviations (2026-10-23)

Status: DRAFT -- implemented on `lane/backup-major-deviations`, awaiting PO review
of the MAJOR definition and the V176 `kind` widening. PO request 2026-10-23: the
Backups card "Active major deviations" always showed UNKNOWN with "0 comparisons
run"; fix it, do not hide it.

## 1. What was there

- `GET /api/v2/backups/deviations` (BackupController) was a stub: always
  `active_major_deviations: []`, `total_deviations_checked: 0`. The card therefore
  could only ever say UNKNOWN.
- `backup_artefact.deviation_state` (V18) **is** filled, by the worker, at record
  time: `first` / `unchanged` / `changed` by comparing the archive's plaintext
  SHA-256 with the device's previous archive (`BackupJobExecutor
  .deviationStateAgainstPrevious`). It is a byte comparison of the archive, not a
  semantic one; a nightly archive changes bytes on most devices.
- `SemanticDeviationEngine` (worker, backup/diff) is constructed and called by the
  Palo Alto backup executor, but its outcome is never persisted or served. It is
  dead on the read path and is not used here.

### What the Configuration plane already computes

Every configuration read records one `device_configuration_run` per read kind
(V16). For the primary read kind of each device:

| Stored | Meaning |
|---|---|
| `change_state` | `first_run` / `unchanged` / `changed`: canonical hash against the newest earlier run of the same read kind (Check Point: SHA-256 over the `set` lines only; Palo Alto: raw hash of the effective-running document). |
| `device_configuration_index` | one row per (context, section, source) with an entry count. Check Point: first one or two tokens after `set` (`interface`, `static-route`, `user`, `cluster`, ...). Palo Alto effective-running: direct `entry` children of each vsys / `shared` / `network` / `mgt-config` category (`address`, `zone`, `virtual-router`, `users`, ...). |
| `configuration_run_deviation_summary` + `_entry` (V22, 14I DV-2) | for a `changed` run only: status `COMPUTED` or `NOT_COMPUTABLE`, and one entry per section that was `ADDED`, `REMOVED` or `RECOUNTED` (count changed). Names and counts only, never a value. |

The Configuration screens show exactly this: the header pill "Config changed · N
sections" / "Config unchanged", the device's Details > Changes tab (the entries
table), and the cluster "Members compared · N settings differ" (browser-side,
latest text across members, not between runs). The Operations change feed is a
different thing (who changed what, from device logs and policy history); it does
not compare snapshots.

Nothing categorised the sections as interfaces / routing / rules / NAT. That is
the piece added here.

### Measured on HOST-A (read-only, 2026-10-23 05:30 UTC, aggregates only)

- 124 devices have at least one primary configuration run; 120 have one in the
  last 7 days (Check Point 57, Palo Alto 38, ASA 12, FortiGate 9, ProxySG 4).
- Primary runs in the last 7 days: Check Point 392 unchanged / 5 changed; Palo
  Alto 56 / 210; ASA 73 / 10; FortiGate 60 / 3; ProxySG 0 / 28. Every changed
  run's summary is `COMPUTED`.
- The Palo Alto effective-running index has **no** rulebase, interface, NAT or
  HA rows: those elements have no direct `entry` children at the category level
  the stream processor counts. Most Palo Alto changes are `shared address` /
  `service` recounts (Panorama-pushed objects).
- Only the newest run of a read kind keeps its sanitized text (V50), so the
  previous run's text cannot be re-read later by the service.
- Expected first answer of the endpoint on that data (same rules, computed
  offline from the aggregate rows): 116 of 124 devices compared (4 without a
  read in 7 days, 4 ProxySG); 20 MAJOR (Palo Alto 7: admin users 6, zones 1;
  ASA 6: routing 6, interfaces 2, failover 2; Check Point 4: static routes;
  FortiGate 3: interfaces 2, static routes 1), 32 MINOR, 64 unchanged. The
  example in section 3 uses these numbers.

## 2. Design

### Comparison pair and window

Each stored run already *is* one comparison: the run against the newest earlier
run of the same read kind. The endpoint reads the device's primary runs collected
in the last **7 days** (the nightly backup rhythm; shorter than both the 14-day
backup and 30-day configuration retention, so both sides of every pair still
exist).

- A run is a **valid comparison** when `change_state = unchanged`, or `changed`
  with a `COMPUTED` summary. `first_run` and `NOT_COMPUTABLE` are not comparisons.
- A device is **compared** when it has at least one valid comparison in the
  window and its vendor is categorised (below).
- A device's class over the window: MAJOR if any valid comparison in the window
  is MAJOR, else MINOR if any is MINOR, else NONE.
- **Active** major deviation = a MAJOR comparison inside the window. It is not
  cleared by the next unchanged read: an unchanged read the next morning says
  nothing happened *since*, not that the change was undone. It ages out after 7
  days. (A "latest pair only" rule would make a real change disappear from the
  card after one nightly read.)

### MAJOR versus MINOR

A comparison is **MAJOR** when at least one of its entries falls in a major
category; **MINOR** when it changed but no entry is in a major category
(including a changed run with no entry at all: the content changed while every
section kept its count); **NONE** when unchanged. Categories follow the
architecture review section 4 (interfaces, routing, access rules, NAT) plus admin
users and cluster/HA, which `SemanticDeviationEngine` also classed as major.

| Category | Check Point (Gaia section) | Palo Alto (context / section) | Cisco ASA | FortiGate |
|---|---|---|---|---|
| interfaces | `interface`, `bonding group`, `bridging`, `interface-name` | `network/virtual-wire`, `network/vlan`, vsys `zone` | `Interfaces`, `Interface` | `system interface`, `system zone`, `system virtual-wire-pair` |
| routing | `static-route`, `bgp`, `ospf`, `ospf3`, `rip`, `ripng`, `pim`, `igmp`, `route-redistribution`, `inbound-route-filter`, `routemap`, `pbr` | `network/virtual-router`, `network/logical-router` | `Routing`, `Route`, `Route Map`, `Router` | `router static`, `router static6`, `router bgp`, `router ospf`, `router ospf6`, `router policy` |
| access_rules | not in Gaia (policy plane) | `rulebase`, `pre-rulebase`, `post-rulebase` (not indexed today) | `Access List`, `Access Group` | `firewall policy`, `firewall policy6`, `firewall security-policy` |
| nat | not in Gaia (policy plane) | not indexed today | `Nat` | `firewall vip`, `firewall vip6`, `firewall vipgrp`, `firewall ippool`, `firewall central-snat-map` |
| admin_access | `user`, `rba` | `mgt-config/users`, `admin-role` | `Username` | `system admin`, `system accprofile`, `system api-user` |
| high_availability | `cluster`, `vrrp`, `mcvr` | `high-availability` (not indexed today) | `Failover`, `Monitor Interface` | `system ha` |

Everything else (objects, DNS, NTP, SNMP, logging, banners, ...) is minor.
ProxySG (bluecoat) is **not categorised**: a proxy has no firewall-major
vocabulary; its devices are counted as "not comparable: vendor not categorised".

### Same-count edits (Check Point): new `MODIFIED` entry kind

A count-only summary cannot see an edit that keeps a section's count: an
interface address change, a next-hop change. For Check Point the worker now
compares, at record time, the previous run's sanitized `set` lines with the new
run's, section by section (the previous text is still on the newest stored run at
that moment). A section present in both runs with the same count but a different
multiset of lines gets one `MODIFIED` entry (counts only, no value). Interface
`comments` lines are ignored (an edited description is not a major change).
Secret-bearing lines are withheld in the sanitized text on both sides, so a
password-hash rotation alone stays MINOR. V176 widens the `kind` check to allow
`MODIFIED`. Runs recorded before this lane keep their count-only entries.

### UNKNOWN / not comparable

A device is not compared, with a reason counted in `not_compared`:

- `no_read_in_window`: no primary configuration run in 7 days;
- `first_read_only`: only first runs in the window (fewer than two snapshots);
- `not_computable`: changed, but the predecessor's index was never stored;
- `vendor_not_categorised`: ProxySG, or any vendor without a table above.

The card shows UNKNOWN only when no device was compared. Otherwise it shows the
number of devices with an active major deviation and "N of M devices compared",
where M = devices with any primary configuration run.

### Known coverage limits (stated in the response `coverage`)

- Palo Alto: rulebase, NAT, interfaces and HA are not in the stored index, so a
  Palo Alto comparison detects routing, zone, virtual-wire and admin-user
  changes only. Rule and NAT changes of Panorama-managed firewalls are on
  Policy > Changes. Follow-up: index `network/interface/*` and
  `rulebase/<type>/rules` one level deeper (needs a one-time "first index" guard
  so the new sections are not reported as ADDED on every device).
- Check Point gateways: access rules and NAT live in the management server's
  policy, not in Gaia; see Policy > Changes.
- ASA, FortiGate: count-only (no `MODIFIED`); same-count edits read as MINOR.

### `backup_artefact.deviation_state` is left as it is

It does not map cleanly. The column describes the backup archive (byte digest
against the previous archive, written when the archive is recorded); the
classification describes configuration runs, collected by a different job at a
different time. Writing MAJOR into it would mix two planes in one column and
change a vocabulary (`first` / `unchanged` / `changed`) the fleet table and its
tests read. Instead the fleet table shows the existing `MAJOR DEVIATION` word
("Major change") for a device listed by this endpoint, in place of the archive's
byte state; every other row keeps its byte state.

## 3. Response

`GET /api/v2/backups/deviations` (gate unchanged: `device_backup_read`):

```json
{
  "window_days": 7,
  "evaluated_at": "2026-10-23T06:00:00Z",
  "total_deviations_checked": 116,
  "devices_known": 124,
  "not_compared": {"no_read_in_window": 4, "first_read_only": 0, "not_computable": 0, "vendor_not_categorised": 4},
  "major_devices": 20, "minor_devices": 32, "unchanged_devices": 64,
  "active_major_deviations": [
    {"device_id": "...", "hostname": "...", "cluster_member_ref": null, "vendor": "check_point",
     "category": "routing", "changed_at": "...", "count": 1, "sections": ["static-route"]}
  ],
  "devices": [{"device_id": "...", "deviation": "MAJOR", "categories": ["routing"]}],
  "coverage": {"palo_alto": "...", "check_point": "..."}
}
```

One `active_major_deviations` row per (device, category): the newest change time
in the window, the number of comparisons in the window that hit the category, and
the section names (vendor vocabulary; no context names, which can be VDOM or vsys
names). `hostname` sits next to `device_id`, so the replay viewer (aiview) gets
the same device pseudonym the Devices screen shows through the standard masking
advice. `devices` gives every compared device's class, for the fleet table.

Read-only over stored rows, no device command. The Configuration endpoint's
deviation entries also carry `category` (null when minor), so the Changes tab
shows why a change counted as major.

## 4. Screen

- Card: the count of devices with an active major deviation; sentence "N of M
  devices compared · last 7 days"; UNKNOWN only when N = 0. The card keeps its
  one link (design language: at most one), "Which devices →", to a list right
  under the card row; each listed device links to
  `?screen=inventory&device_id=<id>&tab=configuration&config_tab=changes`, which
  opens the device's configuration Details > Changes tab.
- Changes tab: a "Major · routing" chip on entries in a major category.
</content>
</invoke>
