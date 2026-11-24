# Zone matrix: freeze contract, then deliver in steps

status: in_progress · target: Council 2026-11-13; bank inputs pending

2026-11-14: contract DRAFT on branch (zone-matrix-contract session). Council 2026-11-13 asked for rule-level evaluation with an upper-bound match function, NAT-aware destinations (no false COMPLIANT on unmapped VIPs) and multi-layer handling. Bank inputs still open: regulation, retention, staleness, compliance_admin team. Steps: freeze, step 1 rule-level evaluation, later flow view.

2026-11-14: contract FROZEN on main; step 1 (V216) in ship batch 1, step 2 (V220) and screen in batch 4.

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Step 2 built: 'Zone matrix step 2: cell edits, template import, reference confirmation, loosening diff', migration V220, 'Zone matrix screen: add Matrix tab and step-2 Versions diff and loosening rules'. Step 1 (V216) is on main. Open: bank inputs, live dry run of V220, steps 3 and later.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): on main and live: step 1 (V216), step 2 cell edits, template import, loosening diff and Matrix tab (V220), step 3 evaluation engine, on-demand Evaluate tab and witness audit kind (V225 then renumbered V228). Remaining: bank inputs (regulation, retention, staleness, compliance_admin team), real-environment counts-only measurement of step 3 on the largest stored policies, steps 4 and later.

Step 4b-1 (V267 durable runs and findings, own pool, startup recovery) shipped 1588f36f; step 4b-2 (lifecycle, triggers, routes, tabs) open.
