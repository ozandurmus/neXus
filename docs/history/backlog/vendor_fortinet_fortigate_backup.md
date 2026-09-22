# Fortinet FortiGate: full configuration backup over SSH incl. VDOMs

status: planned · target: 

Product Owner, 2026-09-22: add as P1 with the Backbox trail as the measured reference. Backbox reference (trail 23488971, 2024-04-24): 'Fortinet -> FortiGate -> 4.x and above -> SSH (VDOM)' -- SSH, 'config global' / 'config system console' / 'set output standard' / end, then 'show' for the full config; VDOM list parsed from the 'config vdom' block to iterate per-VDOM reads. Interactive shell (paging off) is the transport; the per-VDOM loop mirrors Check Point's per-VSID pass. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.

2026-09-22: measurement record written from the Backbox trail -- docs/design/VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md (transport, sequence, artefact, secret risk, neXus fit). Prerequisites before this vendor's contract: Add-device vendor value + endpoint kind, a confirm (identity) capability, the vendor CHECK constraints (V16/V17 allow only check_point/palo_alto), and for HTTPS vendors the generic HTTPS client. Proposed order in the record.
