# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-26)
HOST-A: schema 89, 122 devices at the last count. V87's per-port NIC command was rejected on the real FortiManager. V88 and V89 documented diagnostics were gated, dry-run, deployed, and measured under aiview; neither supplied an explicit link field on this version. Physical link remains `UNSUPPORTED/UNKNOWN`; the existing configured interface state is still displayed.

# What changed this session
- Gated V87-V89 FortiManager diagnostics; the real CLI rejected V87, while V88/V89 returned an interface-information shape without a proven link field.
- Removed the diagnostic probes from subsequent inventory reads. Gate and measurements remain in the contract and `fmg_link_state_diagnose_nic` queue note.
- Targeted worker and gate tests passed. Broader service tests retain the known `ProjectPlanReaderTest` failure.

# Exact next action
Implement the now-FROZEN UI2 diagnostic screen and shared CLI contract in `docs/design/FMG_SINGLE_COMMAND_DIAGNOSTIC_CLI.md` through bounded, reviewed movements. PO decisions are settled: existing `role:security_admin`, no second product approval for a human super admin, exact PO approval before agent commands, one per target per minute, missing Status -> physical link UNKNOWN. Do not execute port5 until exact code/command/target/safe projection is reviewed and separately approved. Luna 6 architecture movement `NXS-LOCAL-0367` was cancelled after an out-of-scope feature-branch push and legacy-console confusion; its branch was not integrated. When Cisco ASA or Pulse Secure is enrolled, or SMC discovery runs, follow the first jobs through completion as specified in `docs/design/CODEX_HANDOVER_2026_09_26.md` §5.

# New risks
- FortiManager Interfaces currently presents configured enable/disable as up/down; this is not verified physical link.
- Device contact stays inside gated neXus jobs. HOST-A is never a jump host; no manual device SSH or browser access.
- PO 2026-09-26: present exact code, command, target scope, and sanitized projection for each ad hoc parser diagnostic;
  obtain individual approval before neXus sends it. Never modify a device for verification or troubleshooting.
