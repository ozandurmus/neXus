# Infoblox Grid Manager: grid backup via WAPI fileop getgriddata (HTTPS)

status: planned · target: 

Product Owner, 2026-09-22: add as P1 with the Backbox trail as the measured reference. Backbox reference (trail 34411065, 2026-09-22): 'Infoblox Inc -> Grid Manager -> All -> HTTPS-2' -- read WAPI version from /wapidoc/, POST /wapi/v<ver>/fileop?_function=getgriddata (basic auth) -> token + download URL, GET the file with content-type application/force-download, then POST fileop?_function=downloadcomplete with the token. Basic-auth credential from the credential store; WAPI version discovered, never hardcoded. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.

2026-09-22: measurement record written from the Backbox trail -- docs/design/VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md (transport, sequence, artefact, secret risk, neXus fit). Prerequisites before this vendor's contract: Add-device vendor value + endpoint kind, a confirm (identity) capability, the vendor CHECK constraints (V16/V17 allow only check_point/palo_alto), and for HTTPS vendors the generic HTTPS client. Proposed order in the record.
