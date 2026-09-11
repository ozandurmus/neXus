# Per-vendor worker containers

status: deferred · target: DEV.3.4

DEFERRED. With concurrency-budget-of-1 per vendor and a stability-over-speed mandate the parallelism gain is small (CP was ~180s in the 0.6.1B.1.2 checkpoint) and it forces the distributed-lock problem. Do only if collection duration becomes a real operational pain, and only after distributed_endpoint_lock (AUTOMATED_VALIDATED 2026-08-30).
