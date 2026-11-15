# module_e2e: wait for Complete or Failed and fail fast; Complete-only wait hangs for 900 s on Failed e2e

status: planned · target: tools/delivery/module_deploy.py

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): module_e2e: wait for Complete or Failed and fail fast; Complete-only wait hangs for 900 s on Failed e2e

2026-11-14 backlog truth: still open (P2, size S). Today: A failed e2e makes a release wait up to 15 min before reporting. Evidence: tools/delivery/module_deploy.py module_e2e still kubectl wait --for=condition=Complete --timeout=900s

2026-11-14: release e2e fail-fast in ship batch 3.

2026-11-15 10:00 backlog refresh: Shipped on main (batch 2/3). Commit 'Fail fast on failed module e2e jobs and sync worker manifest on deploy' (tools/delivery/module_deploy.py; tests test_manifest_sync, test_policy_module_runtime). Real-env confirmation waits for the next release that has a failing e2e.
