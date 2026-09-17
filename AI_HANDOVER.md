# Snapshot
UI2 is updated with Enterprise Guardrails (max 5 inventory jobs, PREFLIGHT via DEVICE_CONFIRM), Audit Log UI, Bulk Collect API, and Role Display Fix.
All NXS-LOCAL-0283, 0288, 0289, 0290 are fully merged to main.

# Recent session changes
- Merged NXS-LOCAL-0288 (Inventory Auto Trigger + Bulk Collect) and fixed missing `collect-all` backend API route.
- Merged NXS-LOCAL-0289 (Role Display UI fix).
- Merged NXS-LOCAL-0283 (Audit Logs API & Screen).
- Merged NXS-LOCAL-0290 (Enterprise Guardrails, Job Concurrency, and Live Job UI).
- K3s deployment script (`run_build.sh`) patched on remote host to track `main` instead of the old feature branch, and triggered.
- Fixed UI bug where `InventoryPanels.tsx` swallowed `FAILED` or `REJECTED` job states silently without displaying an error to the user.

# Exact next action
- Validate Check Point SSH connectivity within the K3s cluster. Currently, the devices return `EXPECTATION_UNMET` due to SSH timeouts or credential issues since the physical host is unresponsive in the remote network. The UI will now correctly display this failure instead of spinning indefinitely.

# Test delta
- `InventoryCollectServiceTest`, `AuditLogQueryServiceTest`, `ClaimIsAtomicNoReadThenWriteTest` (for lease), `GateChainTest` updated and passing.

# Risks
- None currently.
