# POST /discovery/runs with an empty JSON body answers 500 instead of a 400 validation error

status: planned · target: 

2026-11-14 backlog truth: still open (P3, size S). Today: Empty request gives 500 instead of 422 validation error. Evidence: DiscoveryRunService.start: START_CAPABILITY_BY_VENDOR is Map.of; get(null vendor) throws NullPointerException on an empty body

2026-11-14: fix (400) in ship batch 2.

2026-11-15 10:00 backlog refresh: Shipped on main (batch 2/3). Commit 'Return 400 on empty or malformed POST /discovery/runs body'; DiscoveryControllerStartTest asserts 400 VALIDATION_FAILED for empty, missing, wrong-type and malformed bodies. The title (400) is correct: the 422 answer applies only to service-level validation failures. The stacked 422 replay test was dropped in 'Batch 7 stacking fixes'. In the ship queue (batches b4..b9, not yet on main). adds only a test and the EmptyObjectBodySweepTest 422 sweep for other endpoints.
