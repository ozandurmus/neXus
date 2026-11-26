# ACI step 1: pre-cutover impact analysis, one segment

status: in_progress · target: PO started 2026-11-14

2026-11-14: design DRAFT merged (PR 855, docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md). PO decision 11-14: segment separate, logs first; ASA/FortiGate as targets go to the policy-model lane; APIC/Citrix/F5 are topology sources only. Step 1 = impact analysis on existing data for one Phase 2 segment (~8-10 engineer-days). Bank input pending: old-path log export.

2026-11-14: design DRAFT on main; step 1 (V217) in ship batch 2.

2026-11-15 10:00 backlog refresh: Step 1 backend (V217) is on main/in ship order; In the ship queue (batches b4..b9, not yet on main). 'feat(cutover): Cutover impact screen, step 1' adds the screen. Open: bank old-path log export.

2026-11-23: Q7 flow retention enforced (PO decision record 2026-11-14 item 5): CutoverFlowRetention removes cutover_assessment_flow rows of assessments older than 90 days hourly in bounded passes (50 assessments, 50,000 rows), verdict and counts kept; the flows read answers flowsExpired and the assessment page says why the table is empty. ui2.cutover.flow-retention.enabled (default true). Watch-record retention waits for watch records. Real-PostgreSQL test pending a database-capable runner.

ASA and FortiGate old-path policies and operator-override targets evaluated through PolicyLookup; missing zone or interface gives NOT_DECIDED (3ea02652).

2026-11-25: ACI-2 and ACI-3 (as amended by PO Q8) pinned as labelled tests in CutoverAcceptanceCriteriaTest (five tests, both PROVEN; tests and comments only, the DRAFT design text is untouched and its ACI-3 sentence is read as superseded by Q8 and 3ea02652).

- 2026-11-26: Cutover flow Trace link carries the tuple (fragment, prefill, no auto-run).
