# The device-role vocabulary is a string literal in Java and again in the CHECK constraint

status: planned · target: 14I MS-1 successor

NXS-LOCAL-0187 added the device role with its two values written as string literals in DeviceRegistrationService and again in migration V21's CHECK constraint. Both enforce, which satisfies the invariant that the database is the authority and not only Java, but the two can drift: adding a third role in one place and not the other produces either a value the database refuses at insert time or a value Java accepts and never validates. A shared declaration crosses the persistence, service and SQL boundaries, so it needs a moment's design rather than a rename; recorded now so the next role addition does not discover it by hand.
