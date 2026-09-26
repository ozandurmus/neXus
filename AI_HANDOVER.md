# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-26)
HOST-A: schema 90, 122 devices at the last count. The Debug/Parser screen is deployed; service, worker and configuration are ready 1/1 on one image digest. V87 was rejected on the real FortiManager; V88/V89 lacked an explicit link field. Physical link remains `UNSUPPORTED/UNKNOWN`; no diagnostic command was run in this build.

# What changed this session
- Gated V87-V89 FortiManager diagnostics; the real CLI rejected V87, while V88/V89 returned an interface-information shape without a proven link field.
- Removed the diagnostic probes from subsequent inventory reads. Gate and measurements remain in the contract and `fmg_link_state_diagnose_nic` queue note.
- Targeted worker and gate tests passed. Broader service tests retain the known `ProjectPlanReaderTest` failure.
- Merged and deployed the one-command Debug/Parser screen; live V90 `BEGIN/ROLLBACK` passed, configuration image aligned, and diagnostic job count remained zero.

# Exact next action
Deploy Debug Phase 1 from `feature/debug-operations-contract`, following `docs/design/DEBUG_OPERATIONS_SUCCESSOR_DECISION_2026_09_26.md` (FROZEN). The PO requested persistent actor/command/target/time/result history. All-device selection, typed gated reads, retained administrator output and masked AIView output are implemented; V91 passed live BEGIN/ROLLBACK including job insertion. First SSH read scopes are FortiManager, FortiGate and Cisco ASA. No worker was delegated and no device command was run. Saved commands are Phase 2; automation and failover stay outside scope. Device commands still require separate exact PO approval.

# New risks
- FortiManager Interfaces currently presents configured enable/disable as up/down; this is not verified physical link.
- Device contact stays inside gated neXus jobs. HOST-A is never a jump host; no manual device SSH or browser access.
- PO 2026-09-26: present exact code, command, target scope, and sanitized projection for each ad hoc parser diagnostic;
  obtain individual approval before neXus sends it. Never modify a device for verification or troubleshooting.
- UI2 frontend: 203 tests, production build and HTML render harness passed. Targeted diagnostic backend and role-architecture tests passed. Full Gradle regression remains red: integration tests cannot start in the local container environment, and the known `ProjectPlanReaderTest` fails.
