# Pulse Secure (Ivanti) Secure Access: system/users/IVS config export (HTTPS)

status: planned · target: 

Product Owner, 2026-09-22: add as P1 with the Backbox trail as the measured reference. Backbox reference (trail 34411066, 2026-09-22): 'Pulse Secure -> Secure Access -> All -> WGET' -- browser-style admin login on /dana-na (form POST with username/password, session cookie, xsauth CSRF token from login.txt, 'continue the session' step), then exports from /dana-admin: sysinfo.cgi, config.cgi type=system, type=user, type=ivs, and an XML export (exportxml). Secrets must come from the credential store, never a url_pass file; the CSRF/xsauth dance is the vendor-semantic to measure. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.

2026-09-22: measurement record written from the Backbox trail -- docs/design/VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md (transport, sequence, artefact, secret risk, neXus fit). Prerequisites before this vendor's contract: Add-device vendor value + endpoint kind, a confirm (identity) capability, the vendor CHECK constraints (V16/V17 allow only check_point/palo_alto), and for HTTPS vendors the generic HTTPS client. Proposed order in the record.
