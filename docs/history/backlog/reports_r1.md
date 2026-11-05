# Reports R1: RPT-01/02/03/05 on screen and as PDF/XLSX/CSV (V190, masked by default) -- automated tests green, live aiview generation of all four pending

status: in_progress · target: docs/design/REPORTS_R1_CONTRACT.md

2026-11-05 implementation (cloud lane reports-r1, branch cloud/reports-r1-1105). Per REPORTS_R1_CONTRACT.md (FROZEN).
- Schema V190: report_run; backup_artefact admits class 'report' (vendor 'nexus', device 'platform:reports', expires_at required). Audit: trg_audit_report_run fires only on the terminal transition (one row per generate, with template/format/projection/parameters/artefact id); audit_report_download(run_id) writes one row per download before the first byte, and a failed audit refuses the download. The generate also writes the artefact store's own backup_artefact and retention-ledger audit rows.
- Actions: report_read and report_generate (CLASS_0, every role incl. replay_viewer); report_raw_view (CLASS_1, consoleSubmittable=false, never a route, checked by the controller; security_admin only in R1 because no R1 domain has a named raw-view action). Domain reads: RPT-01 device_read+device_backup_read+cp_failover_read; RPT-02 device_read; RPT-03 device_backup_read; RPT-05 cp_failover_read.
- One masking pass (ReportDocumentMasker via ReportProjectionMasker) before JSON/PDF/XLSX/CSV; renderers refuse an unprojected document.
- Renderers: OpenPDF 2.2.5 (3.x pulls brotli4j with native binaries, refused), POI XLSX (string cells, deterministic repack), CSV RFC 4180 with formula guard. 25 MiB cap fails the run (REPORT_TOO_LARGE), nothing stored. Bounded executor: 2 concurrent builds, queue 16. Stale threshold 24 h (Operations HA banner) until B1.
- Retention: worker RetentionPruningService step 4 prunes expired report artefacts. Overview backup freshness now excludes class 'report'.
- Open: V190 BEGIN/ROLLBACK dry-run on the live DB; OpenPDF CVE scan before merge; integration-test classification lists not run (no Docker); REAL_ENV: generate all four as aiview.

2026-11-05 REAL_ENV as aiview (masked): RPT-01/02/03/05 previewed and generated (PDF, XLSX, CSV) and downloaded; RAW refused to aiview; one audit row per generate and per download; files on the report volume (ui2-report-store, UI2_REPORT_STORE_ROOT), none on the recovery volume. Fixes on the way: machine-session report POSTs (PR 748), report files on their own volume (PR 749). RAW download by security_admin not exercised live.
