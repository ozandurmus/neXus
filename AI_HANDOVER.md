# Snapshot
UI2 is updated with Enterprise Guardrails (max 5 inventory jobs, PREFLIGHT via DEVICE_CONFIRM), Audit Log UI, Bulk Collect API, and Role Display Fix.
All NXS-LOCAL-0283, 0288, 0289, 0290 are fully merged to main.

# Recent session changes
- Merged NXS-LOCAL-0288 (Inventory Auto Trigger + Bulk Collect).
- Merged NXS-LOCAL-0289 (Role Display UI fix).
- Merged NXS-LOCAL-0283 (Audit Logs API & Screen).
- Merged NXS-LOCAL-0290 (Enterprise Guardrails, Job Concurrency, and Live Job UI).
- K3s deployment script (`run_build.sh`) patched on remote host to track `main` instead of the old feature branch, and triggered.

# Exact next action
- Validate the live features on K3s once Kaniko build and rollout finish.

# Test delta
- `InventoryCollectServiceTest`, `AuditLogQueryServiceTest`, `ClaimIsAtomicNoReadThenWriteTest` (for lease), `GateChainTest` updated and passing.

# Risks
- None currently.
