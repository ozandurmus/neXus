# Platform identity facts on the Configuration screen — contract

## Status

**FROZEN for §1 Palo Alto column and §2 (Product Owner directive 2026-09-22:
"I definitely want serial number and version information on the Configuration
screen; whatever industry leaders collect and whatever is meaningful must all
be there"). §3 Check Point gate rows: PENDING PO sign-off** — the Check Point
serial, hotfix level and uptime are not read until then. Backlog
`platform_identity_facts_on_configuration` (P1).

## 1. The facts

What Backbox, the vendors' own managers and a compliance auditor ask for
first, per device:

| fact | Check Point (Gaia) | Palo Alto (PAN-OS) |
|---|---|---|
| vendor, model | `show version all` / `cpstat os -f hw_info` (already read) | `show system info` `<model>`, `<family>` (already read) |
| serial number | **`show asset system`** (new gate, §3) | `<serial>` (already read as identity) |
| software version / build | `show version all` (already read) | `<sw-version>` (already read) |
| hotfix level | **jumbo take** (new gate, §3) | n/a -- PAN-OS carries it in the version |
| content / signature versions | n/a | `<app-version>`, `<threat-version>`, `<av-version>`, `<wildfire-version>`, `<url-filtering-version>` (parse-scope extension of `show system info`) |
| uptime | **`show uptime`** (new gate, §3) | `<uptime>` (parse-scope extension) |
| HA role | `cphaprob stat` (already read) | `show high-availability state` (already read) |
| virtual systems | already read | already read |
| management address | already recorded | already recorded |

Per AGENTS.md "Network action taxonomy": a parse-scope extension of a command
already issued (same command, session, timeout, frequency) is **not** a
command addition -- every Palo Alto fact above ships without a new gate row.
Every Check Point fact marked *new gate* needs its row signed off first.

## 2. Storage and exposure

- New table `device_platform_facts` (V46): `device_id`, `serial_number`,
  `hotfix_level`, `content_versions` (jsonb: app, threat, av, wildfire,
  url), `uptime_seconds`, `observed_at`, `source_read`. Written by the
  configuration job's identity step (the reads already run before the
  configuration read) and by the inventory job.
- `GET /devices` and `GET /devices/{id}` carry `serial_number`,
  `hotfix_level`, `content_versions`, `uptime_seconds`.
- **Shown in the clear** on Configuration › Platform identity (PO decision
  2026-09-22: "these values need not be hidden"); the aiview persona sees
  `serial_number` masked (`SN-` + 8-hex HMAC, same keying as hostnames) and
  the rest as is. The sensitive-identity reporting law still governs chat,
  commits and docs: no serial is ever echoed there.
- Cluster view: one members row per member, DIFF where software version or
  hotfix level differ between members (that *is* a compliance finding).

## 3. New Check Point gate rows (PO sign-off required)

| gate_id | command | shell | class | timeout | frequency | secret risk |
|---|---|---|---|---|---|---|
| `cp_identity_show_asset_system` | `clish -c 'show asset system'` | expert | read | 30 s | once per device per run | none |
| `cp_identity_jumbo_take` | `cpinfo -y all` **or** `installed_jumbo_take` -- to be measured on a real gateway first (which one exists on every estate version, output shape) | expert | read | 30 s | once per device per run | none (version text only) |
| `cp_identity_show_uptime` | `clish -c 'show uptime'` | expert | read | 15 s | once per device per run | none |

Measurement record (`docs/design/CP_PLATFORM_IDENTITY_MEASUREMENTS.md`) is
written before the parser: exact command, exact output lines, fields
extracted, on one gateway and one VSX member.

## 4. Slices

1. Palo Alto parse-scope extension + V46 + API + UI card (no new gate).
2. Check Point gate rows signed off → measurement → parser → same table/UI.
3. Cluster DIFF on version / hotfix.
