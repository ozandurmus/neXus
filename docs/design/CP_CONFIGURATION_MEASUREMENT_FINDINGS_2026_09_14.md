# Check Point configuration — measurement findings (14C section 3 "Check Point, configuration")

## Status

**DRAFT — NOT implementation authority.** Records what the Product Owner
observed on 2026-09-14 running `scripts/measurement/cp_config_shape.sh` on
one VSX host (physical context and one virtual system, twice) and one
ClusterXL member. Shapes, counts, section keywords and hash behaviour only;
no configuration line, address, name or serial is recorded.

## 1. Identity reads

- `clish -c "show hostname"`: one line. `clish -c "show version all"`: four
  lines (`Product version …`, `OS build …`, `OS kernel version …`, one
  more). `clish -c "cpstat os -f hw_info"`: five lines including
  `Appliance SN:` and `Appliance Name:` — serial and model in one read,
  as the earlier product relied on. All run as typed from Expert.

## 2. `clish -c "show configuration"`

- Sizes: 264 lines / 235 `set` lines / 11 KB on the VSX host; 371 / 325 /
  16 KB on the cluster member. Header lines begin with `#` (an export
  banner, a hostname line, `# Language version: 14.2v1`).
- **Secret-bearing lines: 22 and 21** respectively (keyword match:
  password, secret, community, auth-key, private-key, psk, credential,
  token). The sanitized view withholds them and shows the count.
- Section keyword histogram (first two words after `set`) is a usable
  section index: `interface` (71 / 159), `ssh server` (36), `snmp traps`
  (20), `installer policy`, `static-route`, `ssl tls`, `password-controls`,
  `aaa radius-servers`, `user`, `ntp server`, `bonding group` (cluster
  member), `arp table`, `web`, `syslog`, `timezone`, `rip`, `proxy`.
- **Two consecutive reads differ in hash with identical byte counts** on
  both hosts (one VSX pair happened to match). The header banner carries a
  timestamp. **Canonical hash = SHA-256 over the `set` lines only**; the
  untouched bytes are the backup copy (C7) with their own hash.

## 3. VSX: no per-virtual-system Gaia configuration

- `bash -lc 'vsenv 3 && clish -c "show configuration"'` succeeds and
  returns **the same configuration as the physical context**: 235 `set`
  lines, identical section histogram; the only difference is the leading
  `Context is set to Virtual Device … (ID 3).` line (+1 line, +83 bytes).
- Consequence: Gaia `show configuration` is **host-level**; a virtual
  system's own configuration is not in it (it is provisioned from the
  management server). The Check Point configuration service reads one
  configuration per host and does not repeat it per virtual system. 14C
  D-6's "and per virtual system on VSX" is superseded for Check Point by
  this finding (recorded in the successor record).
- Feeding `set virtual-system 3` / `show configuration` to one `clish`
  process on stdin produced no configuration output (only `vsxid 3`); the
  earlier product's fallback method does not work non-interactively and is
  dropped.

## 4. Nothing further owed for Check Point configuration measurement.
