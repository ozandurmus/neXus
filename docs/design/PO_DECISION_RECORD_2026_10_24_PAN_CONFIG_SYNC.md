# PO decision record — 2026-10-24: Palo Alto HA configuration sync from the active member

**Status: RATIFIED — PO DECISION (chat, 2026-10-24, relayed by the coordinating session).**
Recorded by the engineering session on `lane/pan-config-sync-remediation`. Design:
[PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md](PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md). It is backed by automated
validation only. Nothing here has been validated in the real environment yet.

## 1. Context

The Palo Alto failover readiness check 4, "Configuration sync", is FAIL when a member reports its running
configuration as not synchronized while the peer link is up and running-config sync is enabled. A failover is refused
while it is FAIL. Until now an operator had to fix it on the firewall itself.

## 2. The PO's answer (verbatim)

**"Onaylıyorum (Önerilen akış)"** ("I approve (the proposed flow)").

## 3. Decisions

| # | Decision | Status |
| --- | --- | --- |
| 1 | While check 4 is FAIL, a remediation button runs `request high-availability sync-to-remote running-config` on the ACTIVE member, sent as PAN XML API `type=op` with the `cmd` in §4. | RATIFIED. V178 seeds gate row `pan_failover_config_sync` SIGNED_OFF. |
| 2 | The button is visible only while check 4 is FAIL and only to security_admin and operation_admin (never aiview, operator or viewer). | RATIFIED. Action `pan_failover_config_sync`; the server computes the button's visibility. |
| 3 | The command goes only to the active member; never from the passive to the active. | RATIFIED. The job re-reads the HA state of both members immediately before sending and refuses if the roles changed. |
| 4 | A confirmation dialog names the source (active) and the target (passive) member. | RATIFIED. |
| 5 | Inside business hours (08:00-20:00 daily) the existing business-hours confirmation applies (`X-Nexus-Business-Hours-Ack`). | RATIFIED. |
| 6 | Every request and outcome is audited. | RATIFIED. `pan_config_sync` table with audit trigger; audited transactions. |
| 7 | Afterwards readiness for that unit re-runs automatically and the result is shown. | RATIFIED. |

## 4. Approved literal (verbatim)

| Field | Value |
| --- | --- |
| Command | `request high-availability sync-to-remote running-config` |
| Transport | PAN XML API, `type=op` |
| `cmd` | `<request><high-availability><sync-to-remote><running-config/></sync-to-remote></high-availability></request>` |
| Target | the member that is active in the job's own fresh HA-state read, and only that member |
| Class | `configuration-write`; timeout 60 s; never retried; once per job |

## 5. What this does not change

The FROZEN `FAILOVER_EXECUTION_PAN_CONTRACT.md` (its unit, checks, commands, approvals and runs) is unchanged.
Check Point has no such action. `NEXUS_FAILOVER_MUTATION_ENABLED` gates this write like the failover writes.
