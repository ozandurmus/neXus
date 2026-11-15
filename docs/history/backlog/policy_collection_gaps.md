# Policy collection gaps: CP gateway objects (D-1), PAN App-ID

status: in_progress · target: D-1 decision + App-ID read literal

2026-11-14: measurement on branch feat/policy-collection-gaps-1113 attributes 22,020 both-UNKNOWN rules: 13,945 behind CP gateway/cluster objects (data already read; blocked on PO semantics decision D-1), 4,928 behind PAN application default ports (new read, literal needs PO approval), 481 PAN vsys zone lists (cross-plane join review).

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Two of three gaps built: 'Policy hygiene: Check Point gateway objects bound by their management addresses (PO decision D-1)' and 'Policy hygiene: Palo Alto application default ports from the predefined App-ID catalogue' (V221 gate). Open: the 481 PAN vsys zone lists (cross-plane join review) and live measurement.

2026-11-15: the 481 PAN vsys zone lists were checked against the code: the vsys zone set is not stored anywhere (policy reads carry no zones, the configuration index keeps counts only, device_interface.zone is not a sound universe), so they stay UNKNOWN and no engine change was made. What a sound fix needs (persist zone names from the existing effective-running read, migration, service join, zoneHolds universe argument) is in docs/design/PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md (DRAFT, awaiting approval).
