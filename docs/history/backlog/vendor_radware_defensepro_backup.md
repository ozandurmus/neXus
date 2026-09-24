# Radware DefensePro: configuration backup (HTTPS)

status: planned · target: 

Product Owner, 2026-09-22: add as P1 with the Backbox trail as the measured reference. Backbox reference (trail 34095224, 2026-04-10): 'Radware -> DefensePro -> 8.x and above -> HTTPS' -- a single authenticated HTTPS POST to /dynamic/File/Configuration/ReceivefromDevice (wget --auth-no-challenge) returns the configuration text; stored as-is. First slice: read-only configuration export via HTTPS, evidence-graded as a device read. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.

2026-09-22: measurement record written from the Backbox trail -- docs/design/VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md (transport, sequence, artefact, secret risk, neXus fit). Prerequisites before this vendor's contract: Add-device vendor value + endpoint kind, a confirm (identity) capability, the vendor CHECK constraints (V16/V17 allow only check_point/palo_alto), and for HTTPS vendors the generic HTTPS client. Proposed order in the record.

NXS-LOCAL-0369: confirm and backup through the Cyber Controller REST API (V67/V68), discovery tree and import; Cyber Controller own backup via SFTP push (V69) completed live 37 MB. Open: a real DefensePro getcfg run (radware_defensepro_getcfg_measurement).
