# Packet Analysis shell IMPLEMENTED (engine stub): cases, streamed pcap upload, RBAC/aiview masking, 7-day retention, ui2-packet-analysis worker (V154); engine port pending

status: in_progress · target: docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md

2026-10-15 feature/packet-analysis-shell: V154 tables + self-owned packet_analysis module (claims packet_analysis_run only); actions packet_analysis_run/raw_view/read (aiview masked); 2 GB/file, 100 GB store cap, magic-byte check; hourly pruner; device windows not applied (stored data); deploy/ui2/58 (ui2-low, cpu 4/10Gi, replicas 1) + 36 PVC; release tooling keeps the module self-owned. Stub engine reports every stage unknown. Not REAL_ENV_VALIDATED; integration lane and live acceptance under aiview pending.

2026-10-16 IMPLEMENTED, pending ship as ship/rel-2-pa-shell (after ship/rel-1-integration): rebased on origin/main + V152/V153; V154 keeps V151's pan_syslog_measure mapping; V154 tables classified in AuditPresentationAllowlist and the exact V153 redaction register. Unit suite green (one fence test fixed for V151); PostgreSQL integration suite PASS. Not REAL_ENV_VALIDATED.

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Packet Analysis shell IMPLEMENTED (engine stub): cases, streamed pcap upload, RBAC/aiview masking, 7-day retention, ui2-packet-analysis worker (V154); engine port pending

Target (full): docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md

2026-10-20: status moved to automated_validated. The real engine replaced the stub (S17, 45d85275), the dedicated worker image rolled (c4e28001), aiview may run (not view raw; PO 2026-10-18). The first production run (2026-10-18) finished in the engine but failed saving its result: the engine version string did not satisfy the run table CHECK (V154); fixed with a short version id (53836c85) plus a value-free failure diagnosis and an architecture test that every worker-stored CHECKed value is admitted by the latest migration. Hop markers in the expert report added (709bba17; PO decision 2026-10-18) and flows outside a neighbour's capture scope are no longer counted as loss (91dd32a2). Not REAL_ENV_VALIDATED: no successful production run is recorded after the fix. Authority: docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md, docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md.
