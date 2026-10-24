# PO decision record — 2026-10-24: replacement reads for the PAN-OS 12 defective commands

**Status: RATIFIED — PO DECISION (chat, 2026-10-24), relayed by the orchestrator.** Recorded by the engineering session
from the orchestrator's brief. The PO's words: "Ben sana buglu komutları attım, onların karşılığı başka komutları buldun
mu? Bulduysan PAN-OS 12 için bunları yenileri ile değiştir." ("I sent you the defective commands. Did you find other
commands to use instead? If you did, replace them with the new ones for PAN-OS 12.")

It builds on `PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md` (the PAN-OS 12 defect scope and the
`below_12` switch) and `PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md` (GR-1, GR-2). It approves two more
candidates from `PAN_OS12_COMMAND_ALTERNATIVES_2026_10_21.md`: GR-7 (session count) and GR-4 (configured interfaces).

## 1. The PO's defective-command list (2026-10-21) and what replaces each one on PAN-OS 12+

| Defective on PAN-OS 12 (PO list) | Sent by neXus before? | On PAN-OS 12 or later now | Below PAN-OS 12 |
|---|---|---|---|
| `show system info` | yes (identity, check 7) | already replaced for every version by `type=version` and the HA state (2026-10-21 records) | the same |
| `show session info` (failover check 6) | yes | **`<show><session><meter/></session></show>`** (GR-7), new gate row `pan_failover_session_meter` (V179) | `show session info`, as today |
| `show interface all` (inventory) | yes | **interfaces derived from the stored effective-running configuration** (GR-4); no device command | `show interface all`, as today |
| `show interface ha1-a`, `ha1-b`, `management` | **no** — no gate row, no code path | nothing to replace; HA link state comes from `show high-availability state` (`peer-info/conn-ha1`, `conn-ha1-backup`, `conn-ha2`, `conn-ha2-backup`), the read failover check 3 already uses | the same |
| `show counter interface all` | **no** — no gate row, no code path | nothing to replace; interface counters are not collected | the same |

## 2. The switch: `replace_12`

Both variables (`NEXUS_PAN_FW_SHOW_INTERFACE_ALL`, `NEXUS_PAN_FW_SHOW_SESSION_INFO`, read once at start by
`PanFirewallCommandSwitches`) accept the new value **`replace_12`** (alias `replace12`):

| PAN-OS major, read in the same job | `below_12` (unchanged) | `replace_12` (new) |
|---|---|---|
| below 12 | original read | original read (byte-identical behaviour and evidence) |
| 12 or later | not sent, UNKNOWN "PAN-OS 12 or later" | original read not sent; **its replacement is used** |
| unknown or unparseable | not sent, UNKNOWN "version unknown" | neither the read nor its replacement; UNKNOWN "version unknown" |

`below_12` keeps its meaning, so a rollback is one value. The worker and policy deployments
(`deploy/ui2/52-worker-deployment.yaml`, `57-policy-deployment.yaml`) set both variables to `replace_12`, guarded by
`tools/tests/test_ui2_deployment_manifests.py`. A restart is needed after the value changes.

## 3. Failover check 6 on PAN-OS 12+: `show session meter`

- **Exact request** (one per member per phase, readiness and execution, same session and key as the other reads):
  `POST /api/` with `type=op`, `cmd=<show><session><meter/></session></show>`, key in `X-PAN-KEY`.
- **Gate row** `pan_failover_session_meter` (V179, SIGNED_OFF, `pan_firewall`, read, 30 s, no retry), citing this record.
  `pan_failover_session_info` (V104) is unchanged.
- **Count.** The sum of `current` over every `entry` of the response (one entry per vsys; per vsys and dataplane on
  multi-dataplane models). An entry with no `vsys` leaf is counted (tolerated). UNKNOWN (blocks) — never a guessed or
  partial count — when: error envelope, not XML, no entry, any entry without exactly one non-negative integer `current`,
  two entries naming the same vsys and dataplane, or an overflow.
- **Expected XML shape.** Palo Alto documents only the CLI table (per vsys: Maximum per dataplane, Current, Throttled;
  PAN-OS CLI quick start, vsys cheat sheet) and publishes no XML field reference. The parser expects the usual XML API
  rendering of such a table, `<response status="success"><result><entry><vsys>1</vsys><maximum>0</maximum>
  <current>N</current><throttled>0</throttled></entry>…</result></response>`, and also accepts one wrapping element
  between `result` and the entries. The element names are **UNVERIFIED** until the first masked shape measurement; a
  different shape reads UNKNOWN (fail closed), and the readiness shape log records the element shape (never values).
- **Rule unchanged.** The frozen 80 % rule of `FAILOVER_EXECUTION_PAN_CONTRACT.md` §6 applies to the summed counts. Counts
  from two different reads are never compared: if the members (or the pre phase and a later phase) were read with
  different commands, check 6 is UNKNOWN with `mixed_sources`.
- **Evidence and view.** Derived JSON `{"count":N,"source":"session meter"}`; the readiness view says
  "N active sessions (source: session meter)". Below 12 the evidence stays `{"count":N}`, as before.
- **Evidence limit (recorded, not a blocker the PO asked for).** The alternatives document noted that the sum over vsys
  equalling the device's active-session count, and the meter's behaviour on a single-vsys firewall, are UNKNOWN (§3.6,
  M5). If the meter returns no usable entry on a firewall, check 6 is UNKNOWN there and readiness cannot be READY — the
  same outcome as `below_12` today, never a false PASS.

## 4. Inventory interfaces on PAN-OS 12+: from the stored configuration

- **Source.** The device's newest stored `effective_running` configuration run (`device_configuration_run`, its encrypted
  artefact read back through the artefact store), excluding an artefact the retention ledger records as removed. No new
  device command and no new gate row: a parse-scope extension of the approved `effective-running` read (GR-4).
- **Read.** Interface names and sub-interfaces with VLAN tag, literal static IPv4/IPv6 addresses, vsys (from
  `vsys/entry/import`), zone, virtual router (or advanced-routing logical router), and the configured HA ports
  (`deviceconfig/high-availability/interface/*/port`). Paths: `PaloAltoConfiguredInterfaceParser`.
- **State.** Every interface's link state is **UNKNOWN (not observed)**, except the configured HA ports: their state is
  the HA link's connection status from this run's `show high-availability state` (up or down; anything else unknown).
- **Shown as.** The Interfaces tab shows the table with "Source: configured (from configuration)" and what stays UNKNOWN;
  the API adds `interfaces_source: "configuration"`. The run records `pan_show_interface_all`, `pan_os_12_or_later` and
  `pan_interfaces_from_configuration` in `device_inventory_run.not_collected`.
- **No stored configuration** (`pan_interfaces_configuration_missing`) or **unreadable** (`pan_interfaces_configuration_unreadable`):
  the interfaces stay UNKNOWN with that reason, never "no interfaces". Routes and HA state are still read.

## 5. What stays UNKNOWN on PAN-OS 12+

- Interface runtime link state (except HA ports), MAC addresses, DHCP/PPPoE and address-object addresses.
- Interfaces of a firewall whose configuration has never been collected (or cannot be read back).
- Check 6 when the meter answer is unusable, when the version is unknown, or when the two members use different reads.
- Interface counters (never collected). Content versions, uptime and platform family (2026-10-21 records, unchanged).

## 6. Reversal

Set both variables back to `below_12` (or `retired`) and restart. The gate row stays as the record; withdrawing it would
need a migration to `BLOCKED`.
