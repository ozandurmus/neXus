# Policy hygiene and V144 lifecycle catalog landed; compliance guidance landed, Java/live acceptance pending

status: in_progress · target: docs/design/PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Policy hygiene and V144 lifecycle catalog landed; compliance guidance landed, Java/live acceptance pending

Target (full): docs/design/PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md

2026-10-20: policy view progress. Permissiveness scoring table ratified by the PO 2026-10-20 (Any source/destination/service 3 each, Any application 1 or 0 with a specific service, Large CIDR and Broad range 1 each; Low 0, Medium 1-3, High >= 4; predefined Palo Alto App-ID/service is specific, 0 points; 34d03799). The rule view was redesigned from an ACL line into a rule sentence with object chips, usage and permissiveness (d9c6fbd4, benchmark in docs/design/POLICY_RULE_VIEW_BENCHMARK_2026_10_19.md). Policy changes read without per-row probes (ea24c9b9, 7a2048ab). Open: PAN object precedence (pan_object_precedence_for_permissiveness); PO acceptance. Authority: docs/design/PO_DECISION_RECORD_2026_10_20_POLICY_PERMISSIVENESS_SCORING.md.
