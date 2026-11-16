# Lifecycle catalog from vendor publications (V214)

status: in_progress · target: Freeze + two hosts approved 11-14

2026-11-14: branch feat/lifecycle-catalog-1113, not shipped. PO 11-14: add the Check Point and Palo Alto hosts path-limited, freeze the contract; FortiGate stays manual rows; age limits 30 days online / 180 days manual. Migration V214 needs the live dry run before ship.

2026-11-14: V219 built, ship pending.

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Built: 'Lifecycle catalog from vendor publications (Cisco online, Check Point and Palo Alto by page upload)', 'Lifecycle catalog: Check Point and Palo Alto pages online (PO decisions D-L1..D-L4)', migration V219 (renumbered), audit-register entries. Not closed: ship pending and the V219 BEGIN/ROLLBACK dry run on the live DB has not been done.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): CLOSED. V219__lifecycle_vendor_catalog.sql is applied (schema is V242 on the live deploy of 2026-11-16 08:34); Cisco online and Check Point/Palo Alto page sources per PO decisions D-L1..D-L4 are on main. AUTOMATED_VALIDATED; catalog contents were not measured against the real fleet in this refresh.
