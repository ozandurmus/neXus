# Blue Coat / Symantec ProxySG: configuration backup

status: planned · target: 

Product Owner, 2026-09-22: ProxySG backup as P1. The Backbox trail supplied (30241437, 2025-02-27) is for 'Blue Coat -> SSL Visibility -> All -> cURL' (JSON-RPC on :8082 with a csrf cookie, timestamped export request, then /download/<token> archives) -- a sibling appliance, not ProxySG. ProxySG's own reference (typically 'show configuration' / archive-configuration over SSH or the HTTPS archive URL) still has to be measured before the gate entry is written; the SSL Visibility trail is kept here as the second Blue Coat shape to cover. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.

2026-09-22: measurement record written from the Backbox trail -- docs/design/VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md (transport, sequence, artefact, secret risk, neXus fit). Prerequisites before this vendor's contract: Add-device vendor value + endpoint kind, a confirm (identity) capability, the vendor CHECK constraints (V16/V17 allow only check_point/palo_alto), and for HTTPS vendors the generic HTTPS client. Proposed order in the record.
