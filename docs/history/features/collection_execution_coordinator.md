# collection_execution_coordinator — Collection Execution Coordinator & Safety Policy

## summary

Per-device canonical identity lock, vendor/context concurrency budget, cooldown, coalescing, priority and job provenance across manual, scheduled and event-triggered collection.

## why

Required before recurring scheduling or event-driven triggers; prevents concurrent CP/VSX/config collection hitting the same physical device simultaneously. Extends the 0.6.1B.1.3 safety audit finding into the scheduled-execution world.

## criterion note (distributed_backend)

backlog.json distributed_endpoint_lock, AUTOMATED_VALIDATED 2026-08-30. Verified this session against a real local PostgreSQL 16: real cross-process exclusion (a real child OS process, SIGKILLed, endpoint reclaimed with no TTL/heartbeat) and a real pgbouncer transaction-pooling proxy correctly rejected by the startup preflight. Multi-container-against-a-real-MDS real-environment evidence still owed (server-blocked, DEPLOY.1).
