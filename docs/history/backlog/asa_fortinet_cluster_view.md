# Cluster view for Cisco ASA failover pairs and FortiGate HA clusters

status: planned · target: 

PO 2026-09-25: ASA failover pairs and FortiGate HA clusters grouped and compared like Check Point ClusterXL / Palo Alto HA (members, roles, member differences). Needs the pairing evidence from the devices once added (ASA show failover, FortiOS get system ha status).

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Cluster view for Cisco ASA failover pairs and FortiGate HA clusters

2026-11-14 backlog truth: still open (P2, size M). Today: ASA/FortiGate HA pairs are not grouped and compared like Check Point. Evidence: PR 835 (V209) pair identity + HA facts only; ASA/FortiGate pairs listed 'readiness not available' (PR 838); no member diff view; FortiGate pair not enrolled

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Partial. Commit 'Show Cisco ASA failover pairs and FortiGate HA clusters in the cluster view' adds haView (members, stored HA role, pair state, sync state always unknown). Still open: a member-difference comparison like Check Point/Palo Alto, and the FortiGate pair enrolment noted on 2026-11-14.
