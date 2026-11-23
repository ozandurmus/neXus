# NXS-LOCAL-0397 — Policy chunk garbage collection (V262), narrowed telemetry audit, Flyway expand/contract fence

## Summary

PolicyChunkGarbageCollector deletes unreferenced policy chunk generations after 24 h and the telemetry audit skips pure worker liveness writes (685910d2, V262). Batch auto-1123-0103 adds the Flyway expand/contract fence above V261 and Estate System transcript and credential-count sections (df5817c8).
