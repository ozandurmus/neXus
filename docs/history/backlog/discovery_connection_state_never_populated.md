# Check Point discovery never records the management plane's own connection state: the adapter passes an empty optional although the enumeration response carries it

status: planned · target: MgmtCliEnumerationAdapter candidate construction; discovery_candidate.connection_state

Measured against two real management-plane exports supplied by the Product Owner on 2026-09-20 (66 objects across two domains). The enumeration response carries sic-status on 23 of 27 and 35 of 39 objects, and the discovery_candidate table already has a connection_state column, but the adapter constructs every candidate with an empty optional in that position, so the column is null for all 157 rows. Carrying it is permitted: it is what the management plane itself reports, not a probe, and the discovery contract's LV-2 requires such an element to be shown under its own name. Two cautions for whoever implements it. First, LV-1 and LV-3 forbid turning it into a liveness label, a colour, a health score or a filter, and LV-5 forbids hiding a candidate because of it. Second, the measured distribution shows why a filter would be wrong anyway: sic-status is uninitialized on exactly the cluster objects (6 of 6 and 3 of 3) because a cluster has no SIC of its own, while every gateway that can hold SIC reports communicating. Filtering on it would drop the clusters, not the unreachable devices.

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Check Point discovery never records the management plane's own connection state: the adapter passes an empty optional although the enumeration response carries it

Target (full): MgmtCliEnumerationAdapter candidate construction; discovery_candidate.connection_state

2026-11-14 backlog truth: still open (P3, size S). Today: Management-plane connection state is not shown for Check Point candidates. Evidence: MgmtCliEnumerationAdapter.java:285 passes Optional.empty(); live: 0 of ~5600 CP candidates have connection_state

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commit 'Carry the Check Point management connection state into discovery candidates' (MgmtCliEnumerationAdapter, test added).
