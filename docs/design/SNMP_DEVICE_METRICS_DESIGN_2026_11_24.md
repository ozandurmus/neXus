# SNMP device metrics (FROZEN, 2026-11-24)

Status: FROZEN (PO 2026-11-24: "Yes, B + freeze"). Measurement M0 is recorded in
`SNMP_M0_MEASUREMENT_RECORD_2026_11_24.md`. Section 11 lists the PO's decisions; the OIDs in section 4 are still each
verified against one real answer (M1) before code relies on them.

## 1. Why

PO 2026-11-24: "Cihaz metrikleri ssh'dan toplanmaz. SNMP'den poll edilmeli ki gerçek veri verilsin."

Today Operations > Device health reads CPU and memory only from Check Point `sar` day files fetched by the system-log
collection (`SystemHealthService`, 7-day window). Palo Alto, Cisco ASA and FortiGate show "No source". A one-shot
`show system resources` over the XML API (considered the same morning, then dropped) gives one moment, not a series.

SNMP polling gives a real time series for all four vendors through one read-only protocol. It does not open SSH
sessions on the devices or run commands on them.

## 2. Scope

In scope, wave 1:

- Firewalls and cluster members of Check Point, Palo Alto, Cisco ASA and FortiGate that are enrolled and not disabled.
- Three facts per device: CPU utilisation, memory utilisation, and active sessions (connections).
- SNMPv3 only, at `authPriv` (SHA-256 or stronger, AES-128 or stronger). No v1 or v2c community strings.
- GET of fixed scalar OIDs and a bounded walk of two fixed tables (section 4). No SET, ever.

Out of scope, wave 1: interface counters, traps, management servers (MDS, Panorama), Infoblox, Radware, Blue Coat.

## 3. Prerequisites (outside the product)

These are bank-side actions, so they belong to the PO or the network team:

1. An SNMPv3 user on each target device, read-only view, `authPriv`.
2. A firewall or ACL path for UDP 161 from HOST-A's egress address to each device management address.
3. The SNMPv3 credentials entered in Administration > Credentials by an admin. The credential store already holds
   the SNMPv3 posture enums (`snmp_security_level`, `snmp_auth_protocol`, `snmp_priv_protocol`). The live store holds
   no SNMPv3 credential yet (5 credentials, all `ssh_password`, counted 2026-11-24).

## 4. What is read (OIDs to verify in the measurement)

The OIDs below come from the vendors' published MIBs. Each is confirmed against the vendor MIB file and one real answer
in the measurement (section 8) before any code uses it. An OID that does not answer is UNKNOWN, never 0.

| Vendor | CPU | Memory | Sessions |
|---|---|---|---|
| Check Point (CHECKPOINT-MIB) | `procUsage` 1.3.6.1.4.1.2620.1.6.7.2.4.0 | `memTotalReal64` .1.6.7.4.3.0, `memActiveReal64` .1.6.7.4.4.0 (used % = active / total) | `fwNumConn` 1.3.6.1.4.1.2620.1.1.25.3.0 |
| Palo Alto (HOST-RESOURCES-MIB, PAN-COMMON-MIB) | `hrProcessorLoad` 1.3.6.1.2.1.25.3.3.1.2 (walk; management plane and data plane rows reported apart) | `hrStorageUsed` / `hrStorageSize` for the RAM row of `hrStorageTable` 1.3.6.1.2.1.25.2.3.1 (walk) | `panSessionActive` 1.3.6.1.4.1.25461.2.1.2.3.3.0 |
| Cisco ASA (CISCO-PROCESS-MIB, CISCO-MEMORY-POOL-MIB, CISCO-UNIFIED-FIREWALL-MIB) | `cpmCPUTotal5minRev` 1.3.6.1.4.1.9.9.109.1.1.1.1.8 | `ciscoMemoryPoolUsed` .9.9.48.1.1.1.5 and `ciscoMemoryPoolFree` .9.9.48.1.1.1.6, system pool | `cufwConnGlobalNumActive` 1.3.6.1.4.1.9.9.491.1.1.1.6 |
| FortiGate (FORTINET-FORTIGATE-MIB) | `fgSysCpuUsage` 1.3.6.1.4.1.12356.101.4.1.3.0 | `fgSysMemUsage` 1.3.6.1.4.1.12356.101.4.1.4.0 | `fgSysSesCount` 1.3.6.1.4.1.12356.101.4.1.8.0 |

Correction (2026-11-24, design owner): the Check Point memory cell first named `.1.6.7.4.4.0` "memFreeReal64 (used =
total - free)". In CHECKPOINT-MIB `.4.4` is `memActiveReal64` (used memory) and `.4.5` is `memFreeReal64`. The OID was
right and only its label was wrong, so no PO decision is needed: used % = memActiveReal64 / memTotalReal64.

Every request also reads `sysUpTime` 1.3.6.1.2.1.1.3.0, so a reboot between two polls is detected and a counter is not
read across it.

## 5. Polling

- One poll every 5 minutes per device; the per-device start time is spread across the interval.
- One request in flight per device. Timeout 3 s, one retry. At most 16 devices are polled at a time.
- The poller does not open SSH or XML API sessions, so it does not take the device collection claim. It reads only and
  runs outside the job windows that gate device writes.
- A device that does not answer three polls in a row is UNKNOWN with reason `SNMP_NO_ANSWER`. A device whose answer is
  refused for authentication is UNKNOWN with reason `SNMP_AUTH_REFUSED`. Polling of that device backs off to every 30
  minutes until it answers again.
- Polling can be switched off fleet-wide (admin setting) and per device.

## 6. Storage

- New table `device_metric_sample`: device id, sampled at, sysUpTime, cpu percent, memory percent, sessions, and a
  source (`SNMP`). Values that did not answer are null. No device name or address is stored with a sample.
- Size: about 136 devices × 288 samples per day × ~60 bytes ≈ 2.4 MB per day.
- Retention: 35 days raw. After that, one hourly row (p95 and maximum) is kept for 400 days.
- A new V-number migration with a BEGIN/ROLLBACK dry run on the live database before rollout.

## 7. Screens

- Operations > Device health: CPU p95 and memory p95 over 7 days come from SNMP samples where they exist. For Check
  Point the `sar` source stays as a fallback. The column states its source (SNMP or sar), and "No source" remains only
  for devices that have neither.
- Device > Health: a 24-hour and a 7-day line for CPU, memory and sessions.
- The state rule (CRITICAL › ATTENTION › UNKNOWN › NORMAL) is unchanged. The thresholds stay as they are (CPU p95
  above 85 %).

## 8. Measurement (before any code)

Goal: prove, for one device per vendor, that UDP 161 is reachable from HOST-A and that the agent answers.

Step M0, no credential needed: an SNMPv3 engine-discovery request. The agent answers with its engine id and a
`usmStatsUnknownUserNames` report. This proves that the path and the agent work, without a user. Exact literal, run
once per device from HOST-A with net-snmp:

    snmpget -v3 -l noAuthNoPriv -u overwatch-probe -r 0 -t 3 <management address> 1.3.6.1.2.1.1.3.0

Expected answers:

- a report (`Unknown user name`) means reachable;
- `Timeout` means no path or the agent is off.

Step M1, after section 3 is done: the same GET as the real user at `authPriv`, followed by a single GET of the CPU OID
for that vendor.

Tooling: net-snmp is not installed on HOST-A (checked 2026-11-24). M0 needs either `apt install snmp` on HOST-A or a
one-off pod with net-snmp. Both are reversible.

Results are recorded in a MEASUREMENT RECORD as counts and answer kinds only, never addresses or names.

## 9. Delivery after the measurement

1. Poller module in the worker and the migration, with tests and synthetic answers.
2. Pilot on one vendor's devices.
3. Then the whole fleet, then the screen changes.

## 10. Open PO questions (answered, see section 11)

1. Approve the exact M0 literal in section 8 and the tool route (net-snmp on HOST-A, or a one-off pod).
2. Which devices for M0: one per vendor, chosen by the PO session?
3. Polling interval of 5 minutes and raw retention of 35 days: accept?

## 11. PO decisions (2026-11-24)

1. M0 literal and tool route: approved (net-snmp on HOST-A); run and recorded.
2. The credential lives in the existing credential store (kind `snmp_v3`, entered by an admin in Administration >
   Credentials). Its use is set on a separate **Administration > SNMP polling** section (option B):
   - fleet switch, off by default;
   - the interval;
   - a fleet default credential, with a per-vendor or per-device override;
   - the scope: vendors or selected devices.

   The credential store gains no polling fields.
3. Interval 5 minutes; raw retention 35 days; hourly p95/max rows kept 400 days.
4. Pilot first on Check Point and Palo Alto (the two vendors whose agents answered M0). Cisco ASA and FortiGate wait for
   the network path and the manager allow-list.
5. M1 runs from the product's own poller, which reads the credential from the store. No secret is typed on a command
   line or seen by an agent.
6. Only SNMP GET and GETNEXT/GETBULK of the section 4 OIDs, gated as `read` entries in the command-gate registry under a
   new transport kind `SNMP_V3`. No SET, ever.
