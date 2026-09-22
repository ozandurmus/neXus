# Fortinet FortiGate: full configuration backup over SSH incl. VDOMs

status: planned · target: 

Product Owner, 2026-09-22: add as P1 with the Backbox trail as the measured reference. Backbox reference (trail 23488971, 2024-04-24): 'Fortinet -> FortiGate -> 4.x and above -> SSH (VDOM)' -- SSH, 'config global' / 'config system console' / 'set output standard' / end, then 'show' for the full config; VDOM list parsed from the 'config vdom' block to iterate per-VDOM reads. Interactive shell (paging off) is the transport; the per-VDOM loop mirrors Check Point's per-VSID pass. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.
