# UI2 B1-10 -- acceptance scenario A: three admins on one device, single-session takeover/refuse, correct state throughout

status: planned · target: workflow §5 B1-10

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): UI2 B1-10 -- acceptance scenario A: three admins on one device, single-session takeover/refuse, correct state throughout

2026-11-14 backlog truth: still open (P3, size S). Today: No end-to-end three-admin test; components tested separately. Evidence: No named acceptance test (grep: only IdentitySessionsRbacDatabasePlaceholderTest); parts covered by SessionReconcilerTest, GateChainTest

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commits 'Add B1-10 acceptance test: three admins act on the same object at once' (ConcurrentAdminsAcceptanceTest) and 'Report ALREADY_REVOKED to losing revokers; refuse (not 500) the losing concurrent takeover; add takeover acceptance test'.
