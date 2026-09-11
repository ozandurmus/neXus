# collection_execution_coordinator — Collection Execution Coordinator & Safety Policy

## summary

Per-device canonical identity lock, vendor/context concurrency budget, cooldown, coalescing, priority and job provenance across manual, scheduled and event-triggered collection.

## why

Required before recurring scheduling or event-driven triggers; prevents concurrent CP/VSX/config collection hitting the same physical device simultaneously. Extends the 0.6.1B.1.3 safety audit finding into the scheduled-execution world.
