# POST /discovery/runs with an empty JSON body answers 500 instead of a 400 validation error

status: planned · target: 

2026-11-14 backlog truth: still open (P3, size S). Today: Empty request gives 500 instead of 422 validation error. Evidence: DiscoveryRunService.start: START_CAPABILITY_BY_VENDOR is Map.of; get(null vendor) throws NullPointerException on an empty body
