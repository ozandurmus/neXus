# Data Sanitizer IMPLEMENTED: keyed anonymization of pcap/pcapng, text logs, CSV/XLSX and JSON with a reversible encrypted vault (V156), /api/v2/sanitize, CLI parity, Sanitize screen; hook points off by default

status: in_progress · target: docs/design/DATA_SANITIZER_CONTRACT.md

2026-10-16 IMPLEMENTED, pending ship as ship/rel-4-sanitizer (after ship/rel-3-pan-hit): new ui2/sanitizer module, V156 vault (sanitize_job / sanitize_vault_entry / sanitize_revert_job / sanitize_ledger; wrapped key, lookup key and sealed original tier-1 redacted), actions data_sanitize_run / data_sanitize_revert (aiview holds neither), Packet Analysis upload and log-export hooks off by default. Rebased on the V152..V155 chain; V156 tables classified in AuditPresentationAllowlist and the exact V153 redaction register. Not REAL_ENV_VALIDATED.

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Data Sanitizer IMPLEMENTED: keyed anonymization of pcap/pcapng, text logs, CSV/XLSX and JSON with a reversible encrypted vault (V156), /api/v2/sanitize, CLI parity, Sanitize screen; hook points off by default
