# Operations › HA & readiness says 'No HA pair or cluster enrolled' while 38 clusters exist (seen in the aiview tour 2026-09-22); the readiness panel reads a different source than the inventory clusters

status: planned · target: Point the readiness panel at the inventory's cluster list or state plainly what it needs

2026-09-22 fixed in the combined fix: the panel was demo scaffolding (two hardcoded cluster names, invented active/standby members, demo PASS checks when the API failed, an assumed NO_BLOCKING verdict). It now lists the enrolled clusters from the device list, shows only checks the preflight API returned, and says NOT_EVALUATED otherwise.
