# Support bundle collection: S7 PO-attended pilot, S8 vendor upload, and the leftover work directory cleanup route (S1 core, S2-S5, S6 Check Point heavy items with the optional cpdata_collector run, SMS/MDS enabled with a warning, claim time HA peer / fleet cap / give-up, V238; design FROZEN 2026-11-15; ASA fallback A5-A7 not approved; cpsdc.rc has no approved poll literal)

status: planned · target: support-bundle

S6 built and automated-validated (V238). Open: leftover work directory cleanup route and UI button (C20 after a recorded work_dir_path), a PO decision on a C15 variant for cpsdc.rc, per-kind masked measurement in the S7 pilot.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): built and on main: S1 core (V230), S2 Check Point light, S3 Palo Alto, S4 ASA, S5 FortiGate (gate rows in V237), S6 Check Point heavy with the optional cpdata_collector run (V238), approved cpsdc.rc poll (V239), S8 vendor upload for Cisco CXD, Fortinet, Palo Alto and generic SFTP (V240) with the host key record audited and warn-and-continue on a host key mismatch, and the leftover work directory removal (C20 literal) in the screen and CLI. Remaining: S7 PO-attended pilot with per-kind masked measurement; vendor upload has not been tried against any real vendor host; ASA fallback A5-A7 not approved.
