# Radware DefensePro: configuration backup (HTTPS)

status: planned · target: 

Product Owner, 2026-09-22: add as P1 with the Backbox trail as the measured reference. Backbox reference (trail 34095224, 2026-04-10): 'Radware -> DefensePro -> 8.x and above -> HTTPS' -- a single authenticated HTTPS POST to /dynamic/File/Configuration/ReceivefromDevice (wget --auth-no-challenge) returns the configuration text; stored as-is. First slice: read-only configuration export via HTTPS, evidence-graded as a device read. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.
