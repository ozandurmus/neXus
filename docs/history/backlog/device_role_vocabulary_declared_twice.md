# The device-role vocabulary is a string literal in Java and again in the CHECK constraint

status: planned · target: 14I MS-1 successor

NXS-LOCAL-0187 added the device role with its two values written as string literals in DeviceRegistrationService and again in migration V21's CHECK constraint. Both enforce, which satisfies the invariant that the database is the authority and not only Java, but the two can drift: adding a third role in one place and not the other produces either a value the database refuses at insert time or a value Java accepts and never validates. A shared declaration crosses the persistence, service and SQL boundaries, so it needs a moment's design rather than a rename; recorded now so the next role addition does not discover it by hand.

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): The device-role vocabulary is a string literal in Java and again in the CHECK constraint

2026-11-14 backlog truth: still open (P3, size S). Today: Next device role can again be refused by DB or accepted unvalidated. Evidence: 'management_server' literal in 10+ Java files plus CHECK chk_devices_role (V65 had to widen it after enrolment was refused)

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commit 'One Java declaration of the device-role vocabulary, with a drift test against the role check migration and the frontend type' (DeviceRoleVocabularyTest).
