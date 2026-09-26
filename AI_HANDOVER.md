# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-26)
HOST-A: schema 89, 122 devices at the last count. V87's per-port NIC command was rejected on the real FortiManager. V88 and V89 documented diagnostics were gated, dry-run, deployed, and measured under aiview; neither supplied an explicit link field on this version. Physical link remains `UNSUPPORTED/UNKNOWN`; the existing configured interface state is still displayed.

# What changed this session
- Gated V87-V89 FortiManager diagnostics; the real CLI rejected V87, while V88/V89 returned an interface-information shape without a proven link field.
- Removed the diagnostic probes from subsequent inventory reads. Gate and measurements remain in the contract and `fmg_link_state_diagnose_nic` queue note.
- Targeted worker and gate tests passed. Broader service tests retain the known `ProjectPlanReaderTest` failure.

# Exact next action
Review `feature/approved-diagnostic-screen` against the PO's Debug/Parser requirement before merge. The branch implements one gated FortiManager command with typed input and a masked structural response of at most 64 safe line labels; it has not been deployed. Revised V90 passed a live `BEGIN/ROLLBACK` dry-run. Do not execute port5 until the PO separately approves the exact command, target and safe projection. After approval to merge, deploy `origin/main` only via `scripts/hosta_deploy.sh`, align `ui2-configuration` to the service digest, and watch the rollout to completion. When Cisco ASA or Pulse Secure is enrolled, or SMC discovery runs, follow the first jobs through completion as specified in `docs/design/CODEX_HANDOVER_2026_09_26.md` §5.

# New risks
- FortiManager Interfaces currently presents configured enable/disable as up/down; this is not verified physical link.
- Device contact stays inside gated neXus jobs. HOST-A is never a jump host; no manual device SSH or browser access.
- PO 2026-09-26: present exact code, command, target scope, and sanitized projection for each ad hoc parser diagnostic;
  obtain individual approval before neXus sends it. Never modify a device for verification or troubleshooting.
- UI2 frontend: 203 tests, production build and HTML render harness passed. Targeted diagnostic backend and role-architecture tests passed. Full Gradle regression remains red: integration tests cannot start in the local container environment, and the known `ProjectPlanReaderTest` fails.
