# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-26)
HOST-A schema 91; Debug Phase 1 code 655bc26 is deployed from main. Service, worker and configuration are ready 1/1; configuration matches the service image digest. The output store is accessible. Diagnostic job count is zero. FortiManager physical link remains UNKNOWN.

# What changed this session
- Added all-device selection, typed gated diagnostic reads and persistent actor/target/command/time/outcome history.
- Administrator output uses the existing encrypted artefact store after credential-secret scrubbing; AIView receives server-masked text. Old pilot summaries remain readable.
- Initial executable SSH reads cover FortiManager, FortiGate and Cisco ASA. Unsupported commands/transports are refused. No saved-command catalog, scripts, writes or failover work.

# Exact next action
Merge/deploy `feature/smc-inventory-visibility` after the pending PO Git approval, then verify under the already-open AIView session. Fixes: SMC opens on Managed devices; listed-child clicks select that child's stored facts including address; vendor filters cover the estate; configuration sections/text refresh after collection and failed collection is shown. All 207 frontend tests and the build passed. No device read was triggered. Live database and AIView show seven stored SMC members but no discovery run/candidates; Fortinet configuration evidence exists for only one of ten devices, so complete configuration coverage still requires real collection and validation.

# Test delta
203 frontend tests passed; focused Java diagnostic tests and Java architecture checks passed. Python render/architecture checks: 29 passed, 1 skipped. V91 live BEGIN/ROLLBACK and job insertion passed. Repository privacy passed. Full Gradle regression retains the previously observed container-environment failures and known ProjectPlanReaderTest failure; this build does not claim a green full Java suite.

# New risks
No real diagnostic read or authenticated live UI acceptance has been performed for Phase 1. Output masking is conservative for unknown tokens. No automatic history deletion was introduced. Device commands still require exact PO approval for agent use; HOST-A is never a jump host.
