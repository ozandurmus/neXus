# ACI step 1: pre-cutover impact analysis, one segment

status: in_progress · target: PO started 2026-11-14

2026-11-14: design DRAFT merged (PR 855, docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md). PO decision 11-14: segment separate, logs first; ASA/FortiGate as targets go to the policy-model lane; APIC/Citrix/F5 are topology sources only. Step 1 = impact analysis on existing data for one Phase 2 segment (~8-10 engineer-days). Bank input pending: old-path log export.

2026-11-14: design DRAFT on main; step 1 (V217) in ship batch 2.

2026-11-15 10:00 backlog refresh: Step 1 backend (V217) is on main/in ship order; In the ship queue (batches b4..b9, not yet on main). 'feat(cutover): Cutover impact screen, step 1' adds the screen. Open: bank old-path log export.
