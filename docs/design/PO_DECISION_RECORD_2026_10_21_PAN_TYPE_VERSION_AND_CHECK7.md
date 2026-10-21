# PO decision record — 2026-10-21: type=version for Palo Alto firewall identity, and failover check 7 from the HA state

**Status: RATIFIED — PO DECISION (form answers, 2026-10-21), relayed by the orchestrator.** Recorded by the engineering
session from the two answers below. It builds on `PO_DECISION_RECORD_2026_10_21_PAN_SHOW_SYSTEM_INFO_RETIRED.md` (the
firewall `show system info` read is retired) and answers two of the questions in
`PAN_OS12_COMMAND_ALTERNATIVES_2026_10_21.md` (DRAFT, research only; it approved nothing by itself).

## 1. The two PO answers

| Question (alternatives document) | PO answer, verbatim |
|---|---|
| Identity, model and PAN-OS version from the documented `type=version` request: for which PAN-OS versions? (GR-1) | "Tüm PAN-OS sürümleri" |
| Failover check 7 (version parity) from the HA state read neXus already sends? (GR-2, contract amendment) | "Evet, HA durumundan al" |

Nothing else in the alternatives document is approved by these answers: Panorama `show devices`, configuration-derived
interfaces, interface link state and the session-count alternatives (GR-3 to GR-8) stay DRAFT.

## 2. Decision 1 — `type=version` is the firewall identity read, for every PAN-OS version

neXus reads a Palo Alto firewall's serial, model and PAN-OS version with the documented XML API request type
`type=version` ("Show the PAN-OS version, serial number, and model number"). It is a read-only request type, not an
operational command, so it has no `cmd`.

**The exact request** (the only new device call):

```
POST /api/            Content-Type: application/x-www-form-urlencoded
X-PAN-KEY: <the run's key>
body:  type=version
```

Same transport (`PanXmlApiTransport`), same key from the run's one key-generation call (header, never the URL), 30 s
timeout, same output bound (a body over 262,144 characters is not parsed). No REST API, no SNMP, no other new command.

**Gate.** `V172__pan_firewall_type_version_identity.sql` adds the row `pan_identity_version` (vendor `palo_alto`,
platform scope `pan_firewall`, transport `PAN_XML_API`, canonical key `type=version`, class read, `SIGNED_OFF`, 30 s, no
retry, once per device per confirm, inventory or configuration run, one key per run). Its source pointer is this record.
The fixture (`gate_registry_fixture.yaml`) matches. V171's withdrawal of `pan_inventory_show_system_info` for firewalls
stands, and the Panorama row `pan_panorama_show_system_info` is unchanged: a Panorama keeps `show system info`.

**Documented response and what neXus maps** (`response/result/{sw-version, multi-vsys, model, serial}`):

| Field | Mapped to | Note |
|---|---|---|
| `serial` | identity check (compared exactly with the recorded baseline, whitespace stripped, opaque); the platform-facts serial; the enrollment baseline and peer-follow token; the Panorama override cross-check key | The only identity evidence. Missing or unusable: identity NOT_EVALUABLE, never a guessed match. |
| `model` | the device's observed model | Missing: UNKNOWN (the stored value is cleared, not kept as current). |
| `sw-version` | the device's observed PAN-OS version | Missing: UNKNOWN (same). |
| `multi-vsys` | the worker log line only (`on` / `off`) | No column stores it. Virtual systems still come from the interface and route reads. |

**Not in the response, so still UNKNOWN** (reason: "Not collected (command retired by PO 2026-10-21)"): hostname as
reported (the recorded name stays and is not refreshed), platform family, uptime, content versions (app, threat, AV,
WildFire, URL) and hotfix level. No other approved read was mapped onto them.

**Where it is sent and what each path does:**

| Path | Behaviour |
|---|---|
| Inventory (`pan_inventory_collect`) | step 1, before HA state, interfaces and routes. A matching serial: continue. A different serial: warn and continue, or refuse under the strict posture. No usable serial: identity NOT_EVALUABLE, continue, or refuse under the strict posture. The run no longer fails because the identity read failed (an unusable answer is a result, not a failed run). An unusable answer records the empty "not collected" platform-facts record and clears the stored model and version; an answer that lacks model or version clears the stale one and writes the other. |
| Configuration (`pan_configuration_collect`) | step 1, same identity rules. The serial keys the Panorama name-override cross-check again; without a usable serial it names no override source. |
| Enrollment confirm (`device_confirm_palo_alto`, firewall) | re-enabled: `type=version` for the serial (identity baseline, EC-5), model and PAN-OS version, then the HA state read unchanged. No usable serial: the confirm fails clearly (`ConnectFailed`), no baseline is recorded. A Panorama confirm is unchanged. |
| Failover | not used (failover identity stays the HA-state `local-info/serial-num`). |
| aiview diagnostics | not offered. `DiagnosticRead` offers `SSH_EXEC` rows only, so no PAN XML API row, including this one, is offered automatically. |
| API and screens | serial, model and PAN-OS version show again once the last inventory run recorded the read (`platform_facts_source` = `pan_type_version`); a value stored by the earlier `show system info` read is withheld until then. The header pill is model and version again. Content versions and uptime say "not collected"; the Platform identity note says the rest once. |

**Evidence limits (UNKNOWN until measured).** The response shape is from the vendor's API documentation, not from a
neXus measurement: the first live run reports presence of each field (log line `[PAN_TYPE_VERSION]`, flags only).
Whether `type=version` is affected by the PAN-OS 12 defect the PO reported is UNKNOWN; if it is, the identity check reads
NOT_EVALUABLE (not a failure), model and version read UNKNOWN, and enrollment of that firewall fails clearly. That the
`serial` is byte-identical to the one `show system info` returned is UNKNOWN until compared; a firewall whose recorded
baseline came from the old read could therefore warn once if the representations differ (warn and continue is the
default posture; the baseline is never re-baselined silently).

## 3. Decision 2 — failover check 7 takes the members' versions from the HA state

No new command, session, gate row, retry or cadence: the `show high-availability state` read neXus sends to each member
in every phase also carries that member's own versions. Check 7 ("Version parity") compares, between the two members:

- `local-info/build-rel` (the PAN-OS build; `version` is a separate HA-protocol counter and is not used),
- `local-info/app-version`,
- `local-info/threat-version`,

each read from that member's own `local-info` (never `peer-info`: a member's report about its peer is one-sided). PASS
when all three are present on both members and equal; FAIL when any present pair differs; UNKNOWN (blocks) when any field
is missing on either member, even beside a mismatch. The evidence names each field `MATCH` / `MISMATCH` / `MISSING` and
never carries a version value. The contract amendment is `FAILOVER_EXECUTION_PAN_CONTRACT.md` §9 (2026-10-21).

Field names are from the vendor's HA-state response as captured in `OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(2026-09-03 correction) and `PAN_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` §5. That `build-rel` equals the
`sw-version` of `type=version` is UNKNOWN and not needed: check 7 only compares the two members with each other, field by
field. The vendor's `*-compat` flags are not used.

**Consequence for readiness on the live estate.** The worker and policy manifests set
`NEXUS_PAN_FW_SHOW_SESSION_INFO=retired` (addendum of the retirement record), so check 6 (sessions carried) is UNKNOWN
with the reason `not_collected`. Check 7 can now pass, so PAN readiness no longer ends UNKNOWN at check 7, but it still
ends UNKNOWN at check 6 until the PO decides that read: an execution still stops at `CHECK_NOT_READY` (check 6) before any
write. That is expected. With the switch unset (every read on) a healthy pair can be READY, and the write-path tests in
`PanFailoverJobExecutorTest` that the retirement disabled run again.

## 4. Reversal

Withdrawing `type=version` needs a migration setting `pan_identity_version` to `BLOCKED` (the row is kept, as V171 kept
the system info row) and the steps removed; the identity paths then return to NOT_EVALUABLE and the firewall confirm to a
clear refusal. Check 7 returns to UNKNOWN by removing the HA-state version comparison.
