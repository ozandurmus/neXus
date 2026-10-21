# PO decision record — 2026-10-21: show system info is retired for Palo Alto firewalls

**Status: RATIFIED — PO DECISION (chat, 2026-10-21), relayed by the orchestrator.** Recorded by the engineering
session from the orchestrator's brief. The PO first asked to cancel the command on Palo Alto firewalls ("Palo Alto
firewall'larda show system info komutunu iptal edelim"). Told what it would cost, the PO chose to remove it completely
("Tamamen kaldır").

## 1. Decision

neXus never sends `<show><system><info/></system></show>` to a Palo Alto firewall. This applies to every job type and
code path.

- The firewall gate row `pan_inventory_show_system_info` (vendor `palo_alto`, platform scope `pan_firewall`) is
  withdrawn. Migration `V171__pan_firewall_command_retirement.sql` sets it to `BLOCKED`, the registry's own state
  for a row that may not be used. `GateResolver` resolves it `UNKNOWN` ("gate exists, BLOCKED pending") for every
  caller. The row is kept, not deleted, as the record of what was sent before. The committed fixture
  (`gate_registry_fixture.yaml`) matches.
- **Out of scope: Panorama.** The Panorama row `pan_panorama_show_system_info` (platform scope `panorama`, V83) is
  unchanged and stays `SIGNED_OFF`. Two code paths are shared between firewall and Panorama: the inventory executor and
  the enrollment confirm. Both now branch on the device role. A Panorama keeps its system info read, and a firewall
  never gets one.

## 2. Where the command was sent, and what happens now

| Path | Before | Now |
|---|---|---|
| Inventory (`pan_inventory_collect`) | step 1, identity refresh and serial check | not sent. HA state, interfaces and routes are still collected. Identity is NOT_EVALUABLE. Under the strict posture the contact is refused (fail closed). |
| Configuration (`pan_configuration_collect`) | step 1, serial check and the Panorama cross-check key | not sent. Identity is NOT_EVALUABLE, and the strict posture refuses. The Panorama cross-check gets no serial, so override sources are not named. |
| Enrollment confirm (`device_confirm_palo_alto`) | identity read (gate entry 3) for the serial baseline and the peer-follow token | a firewall's confirm is **refused before any contact**. The credential is not resolved and no key is generated. The job fails with the reason. A Panorama confirm is unchanged. |
| Failover readiness / execution (PAN) | check 7, version parity, before and after each phase | not sent. Check 7 is `UNKNOWN` with the reason `not_collected`. Readiness can at best end `UNKNOWN`. Execution stops at `CHECK_NOT_READY` (check 7) before any write. PAN execution routes stay unmapped by design. |
| Diagnostics (aiview CLASS_0 reads) | offered from the fixture's SIGNED_OFF rows | no longer offered, because the row is BLOCKED. |
| Capability step lists (worker and service mirrors, YAML specs) | listed | removed. The inventory and configuration capabilities stay execution-eligible without it. |
| `scripts/measurement/pan_inventory_shape.py` (human-run) | R-3 sent to any target | sent only with `--panorama`. |

Backup, syslog and discovery never sent it. Legacy Python under `history/` is not live and was left as it is.

## 3. What becomes UNKNOWN for a firewall (impact as stated to the PO)

Fields that only show system info provided are now "Not collected (command retired by PO 2026-10-21)". They are never
shown as a stale value presented as current, and never as 0.

- **Identity.** Direct-evidence identity rested on the system-info serial, so it is now NOT_EVALUABLE, never a
  guessed match. New firewalls cannot be enrolled, because the confirm needs the serial and is refused clearly.
  Failover keeps its own identity check: it compares the HA-state `local-info/serial-num` with the recorded baseline,
  which is the parse scope of an already-approved read.
- **Model, PAN-OS version, serial, content versions (app/threat/AV/WildFire/URL), uptime, platform family, hostname as
  reported.** Not collected. Each inventory run clears the stored model and version on the device row and replaces the
  platform-facts row with an empty record (source `pan_show_system_info_retired`). Until that first run, the API
  withholds them for firewalls. The hostname is kept as the device's recorded name, because it names the device
  everywhere. The screen says it is no longer refreshed.
- **Compliance version checks.** No current version is known, so checks that depend on it cannot evaluate.
- **Lifecycle (end-of-life / end-of-support by model and version).** It cannot evaluate a firewall, because model and
  version are UNKNOWN.
- **Panorama matching.** The configuration's Panorama cross-check was keyed by the device serial, so Template and
  Device Group override sources are no longer named.
- **Header and badge.** The platform pill (model and version) is gone for firewalls. The header shows "Platform facts:
  not collected (PO 2026-10-21)". The Platform identity grid says why once, and its tiles say "not collected".
- **Failover readiness.** Version parity (check 7) is UNKNOWN, so a PAN pair can no longer be READY.
- **Multi-vsys flag.** It was not read from system info. Virtual systems still come from the interface and route
  reads.

No other approved read on the same session was mapped onto these fields. The existing parsers read the HA state's
local serial only for failover identity, and no PAN parser reads a hostname from the effective-running configuration.
No new command was added.

## 4. Scope update, same day: three more reads behind a switch

The PO later gave the cause: a PAN-OS 12+ defect. The same scope names four firewall commands: `show system info`,
`show interface all` (and the per-interface `ha1-a`, `ha1-b` and `management` forms), `show session info` and
`show counter interface all`. A separate research lane is designing alternatives, which need PO command approval
first: Panorama `show devices connected`, the firewall `type=version` API, configuration-derived interfaces, and
others. Until then:

- `show system info`: removed, as described above.
- The other reads stay enabled. Each one that neXus sends can be turned off with a single worker setting
  (`PanFirewallCommandSwitches`, an environment variable read at start, like `NEXUS_FAILOVER_MUTATION_ENABLED`). The
  value `retired` (or `false` / `off`) turns it off. Anything else, including unset, leaves it on. Turning a read off
  needs no code change, only a restart. Turned off, the read is never sent, and what it provided is UNKNOWN with the
  same reason, "Not collected (command retired by PO 2026-10-21)".

| Read | Switch | Path when off |
|---|---|---|
| `show interface all` (gate `pan_inventory_show_interface_all`) | `NEXUS_PAN_FW_SHOW_INTERFACE_ALL` | Firewall inventory sends no interface read. The run records `pan_show_interface_all` in `device_inventory_run.not_collected` (column added by V171). The API returns `not_collected` and `not_collected_reason`, and the Interfaces tab says UNKNOWN with the reason, never "no interfaces". Routes and HA state are still read. Routes are no longer attributed to a vsys, because that attribution comes from interfaces. |
| `show session info` (gate `pan_failover_session_info`) | `NEXUS_PAN_FW_SHOW_SESSION_INFO` | Failover check 6 (sessions carried) is UNKNOWN, with the reason `not_collected`. |
| `show counter interface all` | none | neXus never sends it: there is no gate row and no code path. |
| `show interface ha1-a` / `ha1-b` / `management` | none | neXus never sends them: there is no gate row and no code path. HA link state comes from the HA state read. |

## 5. Reversal

Re-enabling the command would need a new migration back to `SIGNED_OFF`, and the steps and branches restored. The
withdrawn row, the disabled write-path tests in `PanFailoverJobExecutorTest` and this record mark what that would
involve.

## Addendum 2026-10-21 (PO, same day): two more reads off for now

The PO chose not to run a test read and asked that the PAN-OS 12 reads be "commented out for a short while"
until the defect is clarified. The worker and policy deployments therefore set `NEXUS_PAN_FW_SHOW_INTERFACE_ALL`
and `NEXUS_PAN_FW_SHOW_SESSION_INFO` to `retired` (guarded by `test_ui2_deployment_manifests.py`). Interfaces and
failover check 6 read UNKNOWN with the reason. Removing the two entries (and restarting) sends them again. The PO also
approved, in the same form: `type=version` as the identity/model/version read for all PAN-OS versions, and failover
check 7 taking versions from the HA state read (contract amendment to follow with the implementation).

Implemented and recorded in `PO_DECISION_RECORD_2026_10_21_PAN_TYPE_VERSION_AND_CHECK7.md` (V172, `FAILOVER_EXECUTION_PAN_CONTRACT.md` §9): serial, model and PAN-OS version are read again for firewalls with `type=version`, which makes the identity check evaluable and firewall enrollment possible again, and check 7 is no longer UNKNOWN. Sections 2 and 3 above describe the state between the retirement and that change; where they differ, the newer record governs. Content versions, uptime, platform family and the hostname as reported stay not collected.

## Addendum 2026-10-21 (PO, late): the two reads run again on firewalls below PAN-OS 12

The PO, on the same day: "cihaz panos 12 değilse bu kontrol çalışsın" (run this check when the device is not on
PAN-OS 12). The defect that retired the two reads concerns PAN-OS 12 and later, so both switched reads
(`show interface all` and `show session info`) are sent again to firewalls that are below PAN-OS 12.

- New switch value `below_12` (alias `pre12`) in `PanFirewallCommandSwitches`; `retired` (never sent) and
  `enabled` / unset (always sent) are unchanged. The worker and policy deployments set both variables to `below_12`
  (guarded by `test_ui2_deployment_manifests.py`).
- Rule: the read is sent only when the device's PAN-OS major version, observed IN THE SAME JOB, is known and below 12
  (`PanOsVersion.major`: the leading number of "11.1.4-h7", "10.2.9", "12.1.2"). **An unknown or unparseable version
  is treated like 12 or later: the read is not sent.** No stored version is consulted and no new device command is sent.
- Version source: inventory uses the `sw-version` of the `type=version` read made earlier in the same run. Failover
  decides per member from that member's own HA-state `local-info/build-rel` read in the same phase. Check 6 is
  evaluated only when both members' reads were sent; if either was skipped, check 6 is UNKNOWN (the evidence names each
  member's reason).
- Reasons shown to the operator: "Not collected: PAN-OS 12 or later (PO 2026-10-21)" and "Not collected: PAN-OS
  version unknown". An inventory run that skips the read still records `pan_show_interface_all` in
  `device_inventory_run.not_collected`, plus one extra token (`pan_os_12_or_later` or `pan_os_version_unknown`) that
  the API turns into `not_collected_reason`. Sections 2 and 3 above describe the retired state; this addendum governs
  where they differ.
