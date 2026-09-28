# SNMP monitoring

Status: **DRAFT -- DO NOT FREEZE** (2026-09-28)

SNMP would give neXus a frequent, lightweight view of device health between its SSH/API collections. An operator could see whether a device answered recently and whether its load, interfaces, or hardware health changed. This is a proposal for review, not permission to contact devices or a claim that any listed metric is already available on the fleet.

## 1. Purpose and non-goals

Poll only on devices where an administrator enables SNMP. Candidate health signals are response/reachability, CPU, memory, session count, interface up/down and traffic counters, HA state, and temperature/fan/PSU status where the device actually exposes them. SSH/API collection remains the evidence source for configuration, identity, and richer vendor state; an SNMP response alone cannot prove write-channel readiness or peer health.

An inbound trap receiver is a possible **P3**, after polling proves useful; no traps in P1/P2. **SNMP SET is never supported**: monitoring must not change device state, and accepting SET would create a separate write and authorization boundary.

## 2. Versions and security

The poller must support v1, v2c, and v3 for individually enabled devices. The company's intended mode is **v3 with USM `authPriv`**, SHA-2 family authentication and AES privacy where both endpoints support the selected algorithms. v1/v2c use a community string sent in clear text; the UI must warn before enabling either. v3 USM includes a user, authentication algorithm (SHA-2 family, SHA1, MD5), privacy algorithm (AES-128/192/256, DES), and security level (`noAuthNoPriv`, `authNoPriv`, `authPriv`). The UI must warn that MD5/DES are weak and that either no-privacy level exposes data. Algorithm availability and acceptable combinations are device-specific and require verification; the proposal is not a fleet-wide guarantee.

SNMP community strings and USM authentication/privacy secrets must live in the existing encrypted credential store, referenced by opaque ID and resolved only in the worker. The current model is **not yet sufficient**: `ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/CredentialStorePort.java` has only SSH/API kinds and Check Point/Palo Alto vendor flags; `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/credential/CredentialStoreComposition.java` supplies worker-side resolution. A later implementation needs reviewed SNMP kinds/fields and per-vendor access rules within that model. No secret, community, localized key, or raw response may enter logs, transcripts, job reasons, browser payloads, or shareable artifacts.

For v3, discover/bind the authoritative engine ID through the controlled SNMP transport and treat it as an opaque security identity; specify context name/engine ID explicitly when a vendor needs a virtual-device view. **UNKNOWN -- vendor confirmation required:** whether and how FortiGate VDOM, Check Point VSX VSID, and Palo Alto vsys metrics are exposed through distinct SNMP contexts, OID indexes, or only the physical device. Never infer virtual-device identity from a display name, a VSID's formatting, or the chassis response. A physical-device metric must not be relabeled as a per-VDOM/VS/vsys metric.

## 3. Candidate metric map

These are **candidate reads, not approved OID sets**. Column OIDs below require an interface index suffix (`.<ifIndex>`); `sysUpTime.0` is scalar. IF-MIB and SNMPv2-MIB identifiers are standard definitions, but support and semantics on each platform still need vendor MIB and pilot-output verification. `UNKNOWN -- verify against the vendor MIB` means neither a vendor OID nor its meaning is established here. Poll success, rather than an OID value, supplies the initial SNMP response signal; lack of response is not proof that the device is down.

### Check Point Gaia and VSX

| Metric | MIB | OID |
| --- | --- | --- |
| Agent uptime | SNMPv2-MIB `sysUpTime.0` | `1.3.6.1.2.1.1.3.0` |
| Interface operational state / 64-bit octets | IF-MIB `ifOperStatus`, `ifHCInOctets`, `ifHCOutOctets` | `1.3.6.1.2.1.2.2.1.8`, `1.3.6.1.2.1.31.1.1.1.6`, `1.3.6.1.2.1.31.1.1.1.10` |
| CPU, memory, sessions | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| ClusterXL/VSX per-VS HA state | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| Temperature, fans, PSU | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |

### Palo Alto PAN-OS

| Metric | MIB | OID |
| --- | --- | --- |
| Agent uptime | SNMPv2-MIB `sysUpTime.0` | `1.3.6.1.2.1.1.3.0` |
| Interface operational state / 64-bit octets | IF-MIB `ifOperStatus`, `ifHCInOctets`, `ifHCOutOctets` | `1.3.6.1.2.1.2.2.1.8`, `1.3.6.1.2.1.31.1.1.1.6`, `1.3.6.1.2.1.31.1.1.1.10` |
| CPU, memory, sessions | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| HA and per-vsys state | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| Temperature, fans, PSU | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |

### FortiGate

| Metric | MIB | OID |
| --- | --- | --- |
| Agent uptime | SNMPv2-MIB `sysUpTime.0` | `1.3.6.1.2.1.1.3.0` |
| Interface operational state / 64-bit octets | IF-MIB `ifOperStatus`, `ifHCInOctets`, `ifHCOutOctets` | `1.3.6.1.2.1.2.2.1.8`, `1.3.6.1.2.1.31.1.1.1.6`, `1.3.6.1.2.1.31.1.1.1.10` |
| CPU, memory, sessions | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| HA and per-VDOM state | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| Temperature, fans, PSU | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |

### Cisco ASA

| Metric | MIB | OID |
| --- | --- | --- |
| Agent uptime | SNMPv2-MIB `sysUpTime.0` | `1.3.6.1.2.1.1.3.0` |
| Interface operational state / 64-bit octets | IF-MIB `ifOperStatus`, `ifHCInOctets`, `ifHCOutOctets` | `1.3.6.1.2.1.2.2.1.8`, `1.3.6.1.2.1.31.1.1.1.6`, `1.3.6.1.2.1.31.1.1.1.10` |
| CPU, memory, sessions | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| Failover pair state | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| Temperature, fans, PSU | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |

### Radware DefensePro (pilot candidate only)

| Metric | MIB | OID |
| --- | --- | --- |
| Agent uptime | SNMPv2-MIB `sysUpTime.0` | `1.3.6.1.2.1.1.3.0` |
| Interface operational state / 64-bit octets | IF-MIB `ifOperStatus`, `ifHCInOctets`, `ifHCOutOctets` | `1.3.6.1.2.1.2.2.1.8`, `1.3.6.1.2.1.31.1.1.1.6`, `1.3.6.1.2.1.31.1.1.1.10` |
| CPU, memory, sessions | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| HA / mitigation state | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |
| Temperature, fans, PSU | UNKNOWN -- verify against the vendor MIB | UNKNOWN -- verify against the vendor MIB |

## 4. Poller design

Run a bounded poll scheduler in the **existing worker deployment**, using its job/credential and failure controls; a new deployment adds another credential and operational boundary before scale shows a need. Default interval: **60 seconds**, with a per-device override subject to the approved minimum. Spread starts with stable per-device jitter (proposal: up to 10 seconds) so the fleet does not burst on the minute. Pilot proposal: one attempt plus one retry after a 2-second timeout, at most one in-flight poll per device and a global cap of 10; tune only after vendor interaction-safety evidence. Prefer GETBULK for approved bounded tables on v2c/v3; v1 uses GET/GETNEXT. Cap returned OID count and walk depth. **Stability is more important than collection speed.** Do not increase polling or concurrency until each vendor gate allows it.

A miss makes that sample `UNKNOWN`, never immediately `DOWN`. Propose `N = 3` consecutive missed polls before an **SNMP-unreachable** alert; a later successful poll resolves it. This does not overwrite SSH/API reachability or established operational identity. Feed the transition into the existing `device_health` notification category (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/notification/NotificationMailRouter.java`), with deduplication and masked identity; its current implementation observes job failures, so SNMP transitions require integration rather than an assumption that this already works.

## 5. Network path

The worker pod on HOST-A sends UDP/161 toward each enabled device through the node's egress path. Before any allowlist is requested, the network owner must verify which **actual source address** the device-side firewall sees after pod routing/NAT; this draft cannot infer it from a pod or node address. Firewall/ACL owners then allow that source to only the pilot device addresses on UDP/161, with return traffic, and expand per approved fleet scope. HOST-A's current nftables policy is inbound-focused; outbound path and intermediate ACLs still need verification. No packet is sent for this document.

P3 traps would add inbound UDP/162 from approved device sources to a dedicated receiver and require a reviewed HOST-A nftables inbound rule (plus upstream ACL, service exposure, authentication, rate limits, and spoofing controls). No UDP/162 opening is part of P1/P2.

## 6. Data and retention

Start with a narrow, partitioned PostgreSQL time-series table keyed by opaque device reference, metric ID, interface/context reference where applicable, and sample time, with numeric value plus quality/status. Keep raw varbinds and identity-bearing strings out of it. A separate 5-minute rollup can hold count/min/max/average for gauges and rates derived from counter deltas; counter resets/wraps must be detected before rate calculation. Proposed retention: raw **7 days**, 5-minute rollups **90 days**, with partition expiry. Migration would add the sample/rollup tables, indexes, grants for `ui2_app`, retention job, and bounded read API; **no SQL or schema change is authorized by this draft**.

Capacity assumption: 100–200 devices × 50 scalar metric samples × 1,440 minutes = **7.2–14.4 million raw rows/day**, or **50.4–100.8 million raw rows/7 days**. Five-minute rollups at the same cardinality are 100–200 × 50 × 288 buckets × 90 days = **129.6–259.2 million rows**. At an illustrative 100 bytes/row before indexes, WAL, replicas, and partition overhead, retained sample+rollup data alone is **18–36 GB**; actual sizing must be measured. PostgreSQL reuses the existing datastore for a pilot, but this row volume requires capacity and query-latency checks before fleet rollout. Move to a dedicated TSDB only if those checks fail; reduce rollup cardinality to the metrics operators actually graph if needed.

Interface descriptions, `sysName`, and context labels can reveal topology. Treat them as sensitive identity, avoid storing them in the metric table, and render any needed labels through the existing `aiview` masking projection. No production names or addresses belong in screenshots or reports.

## 7. Screens

- Device detail **Health** tab: a few sparklines and last-good-sample time, with `UNKNOWN` and missing-data gaps visible; purpose: diagnose a single device's recent change.
- Fleet overview tiles: count healthy/unknown/SNMP-unreachable by enabled device, with stale-data indication; purpose: prioritize investigation without equating SNMP loss to device failure.
- Thresholds and alert hooks: per-metric thresholds with sustained breach and recovery transitions, linked to the device health route; purpose: notify on actionable changes without duplicate minute-by-minute alerts.

## 8. Governance and phases

SNMP polling is a **new network-device read path**, not a free extension of SSH/API or a diagnostic shortcut. Before implementation or any pilot packet, the Product Owner must approve a per-vendor gate entry and security review covering the **exact OID set and context/index semantics, versions and algorithms allowed, credential storage/resolution model, target scope, 60-second/default and minimum per-device intervals, timeout/retry/concurrency/bulk limits, expected response size, unsupported behavior, and sanitized telemetry**. The network-device command gate and Diagnostic-path law apply. The listed standard OIDs are not approved reads. No gate row is added by this task because the brief approves no exact endpoint/command.

| Phase | Scope | Exit evidence |
| --- | --- | --- |
| P1: read-only pilot | 3–5 named devices across 1–2 vendors, after per-target approval | Vendor MIB and real-output semantics, resource load, source address/ACL, masking, miss/alert behavior |
| P2: fleet | Opt-in rollout in batches of ~20 devices, up to the approved 100–200-device capacity | Stable polling, Postgres capacity, query latency, alert noise, per-vendor limits |
| P3: traps | Optional 3–5-device pilot only after separate approval | Authenticated/rate-limited receiver, inbound rule/ACL, deduplication and spoofing review |

## 9. Open questions for the Product Owner

1. Are v1/v2c allowed on production devices at all, or only in non-production exceptions?
2. Which 3–5 masked device references and 1–2 vendors should form P1?
3. Are 7 days raw and 90 days at 5-minute resolution acceptable retention targets?
4. Is an inbound trap receiver wanted after fleet polling, or should P3 be dropped?
5. What minimum per-device polling interval may an administrator set?

## 10. Product Owner answers (2026-09-28)
1. **Versions:** v1, v2c and v3 are all selectable per device (a setting, with the §2 warnings); the PO's own devices
   use **v3**.
2. **Pilot (P1):** the enabled devices whose name contains "test" (measured 2026-09-28: 8 devices, Check Point, Palo
   Alto and one proxy vendor; the list is resolved in neXus, never written here).
3. **Retention:** **raw samples 90 days** (replaces the 7-day proposal; 5-minute rollups become optional). The
   **OID set is entered per vendor/device by an administrator** -- neXus does not poll "everything".
4. **Traps:** not needed now (P3 dropped from the plan until asked).
5. **Interval:** **60 seconds**.

Sizing for 90 days raw at 60 s (129,600 samples per series; ~120 bytes per row with its index, to be measured):
150 devices x 15 scalar OIDs = 2,250 series -> ~290 M rows -> **~35 GB**. Interface tables multiply: 20 interfaces x
3 counters on every device adds 9,000 series -> ~1.2 B rows -> **~140 GB**. HOST-A has ~670 GB free (2026-09-28),
so both fit; to keep it small, status-type values (e.g. `ifOperStatus`, HA state) are stored on change only, and
interface counters only for interfaces an administrator selects.

## 11. Positioning (PO, 2026-09-28): a signal layer for HA and failover, OIDs by role
PO: "OID dinamik olmalı ... HA status için OID kontrolü yaparız, bununla aktif standby konumlarını besleriz ...
failover kısmına hizmet edecek şekilde ... CPS, HA, state ... failover sonrası kontrollerde kullanılabilir." Approved
with the engineering objection below ("ok ilerletelim").
- **OID profiles.** A `security_admin` maintains an OID profile per vendor (optionally per device): each entry is an
  OID, a **role tag** (`ha_state`, `cps`, `connections`, `cpu`, `memory`, `if_oper_status`, ...), a value map (e.g.
  `1 -> active`, `2 -> standby`) and a table/scalar kind. Features read **roles, never OIDs**. Every profile change is
  audited; saving a profile is the approval of what neXus reads from devices (RBAC over prohibition), replacing a
  per-OID code change. The §3 table becomes the seed profile, UNKNOWN rows left empty.
- **Consumers.** (1) HA role on device/cluster views, minute-fresh, labelled with its source and age ("SNMP: active,
  1 min ago"); it never overwrites the SSH-established identity or role evidence. (2) Failover pre-check: HA state
  stable over the last N minutes (no role flaps) from the time series. (3) During a failover run: the unit's
  `ha_state` polled every few seconds (burst mode, only for that unit, only during the run). (4) Failover post-check:
  `cps`, `connections` and traffic compared with the pre-failover per-minute baseline -- a candidate for the deferred
  CP check 7 (CPS) **if** a vendor OID for it is confirmed in the pilot.
- **Objection accepted:** SNMP results are **additional evidence** in the failover check table first; the approved
  SSH checks keep deciding pass/stop. Promotion of an SNMP check to a deciding check is a later PO decision after the
  pilot shows SNMP and SSH agree.
- **Credential store:** extended with SNMP kinds (v1/v2c community; v3 user, auth protocol + secret, priv protocol +
  secret, security level) -- approved to build now, independent of the rest.
