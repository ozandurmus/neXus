# The service cannot start against an empty database: a bean reads gate_registry during context refresh, before the ApplicationRunner that runs Flyway

status: planned · target: MigrationStartupRunner vs DeviceCompositionConfiguration.deviceCapabilityRegistry; observed HOST-A 2026-09-15

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): The service cannot start against an empty database: a bean reads gate_registry during context refresh, before the ApplicationRunner that runs Flyway

Target (full): MigrationStartupRunner vs DeviceCompositionConfiguration.deviceCapabilityRegistry; observed HOST-A 2026-09-15

2026-11-14 backlog truth: DONE. Evidence: PR #374, commit 71624229: MigrationStartupRunner is a BeanFactoryPostProcessor at HIGHEST_PRECEDENCE. Fresh DB migrates before any bean reads gate_registry
