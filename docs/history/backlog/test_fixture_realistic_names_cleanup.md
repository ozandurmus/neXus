# Frontend test fixtures still carry device-like hostnames and non-documentation IPv4 addresses (AddDeviceDialog, AdministrationScreen, ConfigurationPanels, ConfigurationScreen, InventoryPanels, InventoryScreen tests; locations in the Codex dark-mode worker report): replace with aiview pseudonyms and 192.0.2.x, and extend tests/previewMasking.test.ts to scan tests too

status: planned · target:

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Frontend test fixtures still carry device-like hostnames and non-documentation IPv4 addresses (AddDeviceDialog, AdministrationScreen, ConfigurationPanels, ConfigurationScreen, InventoryPanels, InventoryScreen tests; locations in the Codex dark-mode worker report): replace with aiview pseudonyms and 192.0.2.x, and extend tests/previewMasking.test.ts to scan tests too

2026-11-14 backlog truth: still open (P3, size S). Today: Test hygiene only; no runtime impact. Evidence: frontend tests (AddDeviceDialog, InventoryScreen, AdministrationScreen etc.) still carry device-like names and private IPs

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commits 'test(frontend): replace device-like fixture names with masked pseudonyms and add guard' (previewMasking.test.ts guard) and 'Batch 8 stacking fixes: BackupFleetTable fixtures use masked names'.
