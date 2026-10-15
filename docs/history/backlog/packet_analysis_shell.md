# Packet Analysis shell IMPLEMENTED (engine stub): cases, streamed pcap upload, RBAC/aiview masking, 7-day retention, ui2-packet-analysis worker (V154); engine port pending

status: in_progress · target: docs/design/PO_DECISION_RECORD_2026_10_15_PACKETBREAKER_PORTED_INTO_NEXUS.md

2026-10-15 feature/packet-analysis-shell: V154 tables + self-owned packet_analysis module (claims packet_analysis_run only); actions packet_analysis_run/raw_view/read (aiview masked); 2 GB/file, 100 GB store cap, magic-byte check; hourly pruner; device windows not applied (stored data); deploy/ui2/58 (ui2-low, cpu 4/10Gi, replicas 1) + 36 PVC; release tooling keeps the module self-owned. Stub engine reports every stage unknown. Not REAL_ENV_VALIDATED; integration lane and live acceptance under aiview pending.
