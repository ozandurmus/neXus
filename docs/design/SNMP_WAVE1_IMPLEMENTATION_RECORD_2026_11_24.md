# SNMP device metrics, wave 1: implementation record (2026-11-24)

Status: IMPLEMENTED, AUTOMATED_VALIDATED. Not REAL_ENV_VALIDATED: M1 (the first live poll) runs with the PO session.
Contract: `SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md` (FROZEN), section 11 decisions; M0 in
`SNMP_M0_MEASUREMENT_RECORD_2026_11_24.md`.

## What was built

| Part | Where |
|---|---|
| Settings, overrides, poll state, samples, hourly rows | `V268__snmp_polling_settings_and_metric_samples.sql` |
| Nine `read` gate rows, transport kind `SNMP_V3` | `V269__snmp_v3_metric_read_gates.sql`, `gate_registry_fixture.yaml` |
| Store (claim, record, rollup, prune, health, daily) | `persistence/.../snmp/JooqSnmpPollingRepository.java` |
| Poller (general worker, idle while the switch is off) | `worker/.../snmp/` (`SnmpPoller`, `Snmp4jClient`, `SnmpMetricParser`, `SnmpPollPlan`) |
| API `GET/PUT /api/v2/config/snmp-polling` | `service/.../snmp/SnmpPollingSettingsService.java`, `api/SnmpPollingSettingsController.java` |
| Device health SNMP source | `service/.../systemlog/SystemHealthService.java` (`withSnmp`) |
| Administration > Device registry > SNMP polling | `frontend/src/components/settings/SnmpPollingPanel.tsx` |
| CLI | `snmp-polling`, `snmp-polling-set` |

Library: `org.snmp4j:snmp4j:3.13.2` (Apache 2, no runtime dependency, released 2026-11-02). The jar's sha256 in
`verification-metadata.xml` equals the Maven Central file.

## Decisions taken inside the frozen contract

- Fleet switch OFF by default; the seed puts both pilot vendors in scope, so turning it on polls every enrolled,
  not disabled Check Point and Palo Alto gateway (role `gateway`) unless a device row excludes it.
- Credential precedence: device row, then vendor row, then fleet default. Only an SNMPv3 credential at authPriv with
  SHA-224/256/384/512 and AES-128/192/256 is used; any other is stored with a warning and reported
  `SNMP_CREDENTIAL_UNSUITABLE` (warn, do not refuse). A credential named by polling cannot be deleted.
- Outcomes that send nothing: `SNMP_NO_CREDENTIAL`, `SNMP_CREDENTIAL_UNSUITABLE`, `SNMP_NO_ADDRESS`, `SNMP_GATE_MISSING`.
  They do not count as misses. `SNMP_NO_ANSWER` and `SNMP_AUTH_REFUSED` count; after 3 the device is polled every
  30 minutes until it answers.
- A poll is claimed by a conditional update of `snmp_poll_state.next_poll_at` (lease 30 min), so a second worker
  replica cannot poll the same device in the same interval.
- The address is the host of the device's first endpoint; the SNMP port is always 161.
- Device health keeps its day-based rule: a per-day nearest-rank p95 is computed from the answered samples and runs
  through the same CRITICAL/ATTENTION logic as the sar days (threshold 85 %). sar stays the Check Point fallback when a
  device has no answered SNMP day in the window.
- aiview: `snmp_polling_settings_read` joins the `UI2_AIVIEW_READONLY_ADMIN` set; the projection replaces credential
  names and references with `CRED-n`, shows no warnings, and `can_write` is false.

## Check Point memory OID label (resolved)

The design table first labelled `1.3.6.1.4.1.2620.1.6.7.4.4.0` "memFreeReal64 (used = total - free)". In CHECKPOINT-MIB
`.4.4` is memActiveReal64 (used memory); memFreeReal64 is `.4.5`. The design owner corrected the label on 2026-11-24
(same OID, no PO decision needed): memory % = memActiveReal64 / memTotalReal64. Some MIB copies give these objects
`DisplayString`, others `Counter64`; the parser accepts both.

## Review fixes (Neon review, `NEON_SNMP_W1_REVIEW_2026_11_24.md`, SHIP AFTER FIXES)

1. Retention prune deletes in batches of 5,000 rows, each its own transaction, at most 40 batches per table per run;
   rollup and prune run on their own `snmp-metric-maintenance` thread, never on the scheduling thread.
2. Backoff after 3 misses waits max(30 minutes, interval): it never polls sooner than the interval.
3. `Snmp4jLoopbackTest` runs the client against a real SNMPv3 agent (SNMP4J USM, authPriv SHA-256/AES-128) on loopback
   UDP: engine discovery, the Check Point GET, the Palo Alto multi-column GETBULK walk across two requests with the column
   boundary, wrong password and unknown user (net-snmp style unauthenticated report, and SNMP4J's own signed report),
   and a mute agent. It found two defects, now fixed: (a) a refusal report the client cannot authenticate was dropped and
   read as SNMP_NO_ANSWER; the client now watches its own USM counters for the poll; (b) a short GETBULK answer could
   re-ask a column; a column without a varbind in the answer now ends. PostgreSQL tests now also cover `daily()`,
   `lastOutcomeCounts()`, the credential in-use check and batched prune.
4. At most 16 polls are claimed and in flight (was 64 queued). Database: the general worker's pool has 10 connections
   shared with its 10 claim loops; it is not raised. Instead the poll threads hold at most 2 connections at once (a
   semaphore around the credential read and the result write; the network wait is outside it), plus the one scheduling
   thread and the one maintenance thread.

Also: V268 sets `lock_timeout` 10 s for its foreign keys; shutdown waits up to 60 s for polls in flight (the longest
Palo Alto poll is 9 requests of 3 s + 1 retry); the panel's "last poll, past 24 hours" counts only polls of the last
24 hours.

## Migration dry run (live database, BEGIN/ROLLBACK, 2026-11-24)

`~/Codo/ops-scripts/dryrun.sh`, run as the database owner:

- V268: every CREATE/GRANT/TRIGGER succeeded; seed `INSERT 0 1` (settings) and `INSERT 0 2` (vendor rows);
  `ROLLBACK`, `DRYRUN_ROLLED_BACK`, `psql_rc=0`.
- V269: `INSERT 0 9`; `ROLLBACK`, `DRYRUN_ROLLED_BACK`, `psql_rc=0`.

Both files were dry-run again in their final form (V268 gained two values in the `snmp_poll_state.last_outcome` CHECK
list after the first run): same result, `psql_rc=0`, rolled back. After the review fixes (V268 `lock_timeout`, V269 comment and
`unsupported_behavior_ref` wording) both were run a third time: same result, `psql_rc=0`, rolled back.

## UNKNOWN until M1

1. Whether Check Point answers `procUsage`, `memTotalReal64` and `memActiveReal64` at `.0`, and with which syntax.
2. Which `hrProcessorLoad` row is the Palo Alto management plane. The parser takes the lowest row index as the
   management plane (`cpu_pct`) and the highest of the others as the data plane (`cpu_dataplane_pct`).
3. Whether Palo Alto has exactly one `hrStorageRam` row; the parser takes the lowest-index one.
4. AES-192/AES-256 key localisation: SNMP4J uses the standard (Blumenthal) extension; a device that expects the Cisco
   3DES-style extension would refuse. AES-128 has no such ambiguity.
5. Engine discovery and time-window behaviour of both agents under SNMP4J (handled inside the library; not seen live).
