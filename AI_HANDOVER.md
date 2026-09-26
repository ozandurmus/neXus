# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-26)
HOST-A: schema 87, 122 devices at the last count. V87 gated a FortiManager per-port NIC read and was dry-run before deployment. One FortiManager inventory job completed under aiview; all 12 NIC commands were rejected by the appliance CLI. Physical link remains `UNSUPPORTED/UNKNOWN`; the existing configured interface state is still displayed.

# What changed this session
- Gated `diagnose hardware info nic <port>` in V87 and the fixture; the first live measurement rejected it.
- Removed the probe from subsequent inventory reads. The gate and rejected result remain in the contract and `fmg_link_state_diagnose_nic` queue note.
- Targeted worker and gate tests passed. Broader service tests retain the known `ProjectPlanReaderTest` failure.

# Exact next action
Find a documented FortiManager 7.4.11 physical-link read surface. Gate the exact command before using a neXus job to measure its output shape; if no supported read exists, keep physical link `UNKNOWN` and decide the UI treatment with the PO. When Cisco ASA or Pulse Secure is enrolled, or SMC discovery runs, follow the first jobs through completion as specified in `docs/design/CODEX_HANDOVER_2026_09_26.md` §5.

# New risks
- FortiManager Interfaces currently presents configured enable/disable as up/down; this is not verified physical link.
- Device contact stays inside gated neXus jobs. HOST-A is never a jump host; no manual device SSH or browser access.
