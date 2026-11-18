# Cluster view for Cisco ASA failover pairs and FortiGate HA clusters

status: planned · target: 

PO 2026-09-25: ASA failover pairs and FortiGate HA clusters grouped and compared like Check Point ClusterXL / Palo Alto HA (members, roles, member differences). Needs the pairing evidence from the devices once added (ASA show failover, FortiOS get system ha status).

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Cluster view for Cisco ASA failover pairs and FortiGate HA clusters

2026-11-14 backlog truth: still open (P2, size M). Today: ASA/FortiGate HA pairs are not grouped and compared like Check Point. Evidence: PR 835 (V209) pair identity + HA facts only; ASA/FortiGate pairs listed 'readiness not available' (PR 838); no member diff view; FortiGate pair not enrolled

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Partial. Commit 'Show Cisco ASA failover pairs and FortiGate HA clusters in the cluster view' adds haView (members, stored HA role, pair state, sync state always unknown). Still open: a member-difference comparison like Check Point/Palo Alto, and the FortiGate pair enrolment noted on 2026-11-14.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): the read-only member-difference tab (commit 'Add a member-difference view for cluster members') works on the generic cluster inventory, so it should cover ASA and FortiGate HA groups, and the haView lists their members and roles. Remaining: confirm on a real ASA pair and a FortiGate cluster (the FortiGate pair enrolment noted 2026-11-14 is a data step), and the HA sync state is still always unknown.

2026-11-17 (PO asked for FortiGate readiness next to ASA): lane `lane/fgt-readiness-1117` builds FortiGate (FGCP) failover READINESS only, switched off (`UI2_FGT_FAILOVER_READINESS`, default off, not in any deploy manifest). Reads run on the one enrolled cluster address (the primary) and the secondary is judged from what the primary reports (`basis: PRIMARY_REPORT`); design question Q2 (each member's own HA management address) stays open. Three new read literals approved by the PO in chat 2026-11-17 and signed off by V247 (`PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md`): `diagnose sys ha checksum cluster`, `diagnose sys ha dump-by group`, `execute ha failover status`. Not built: any switch or execution path (reset-uptime stays design-only), `diagnose sys session stat` (check 6 stays UNKNOWN, not evaluated). Check 7 (version parity) stays UNKNOWN: the primary reports no firmware build for its secondary, so a run cannot reach READY until the PO decides how parity is evidenced. Output shapes of the three new reads are unmeasured; parsers accept the documented forms only and log a masked shape otherwise.

2026-11-18: FortiGate member view landed in batch b37 (290e23e6) and FortiGate FGCP failover readiness is built (2dd1272f, readiness only); the Cisco ASA pair view is not a separate screen yet. Keep planned until both vendors have the cluster view.
