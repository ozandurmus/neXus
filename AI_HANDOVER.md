# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

## Snapshot (2026-11-05)
Branch `cloud/reports-r1-1105` off `cloud-base/1105`: Reports R1 implemented per `docs/design/REPORTS_R1_CONTRACT.md`
(FROZEN), schema V190. Queue item `reports_r1` is IN_PROGRESS (AUTOMATED_VALIDATED in substance; REAL_ENV pending).
Nothing here is REAL_ENV_VALIDATED. `CURRENT_STATE.md` and `project/QUEUE.md` win over this file.

## What changed this session
- V190: `report_run`; `backup_artefact` admits class `report` (vendor `nexus`, 30-day `expires_at`); one audit row per generate (terminal-state trigger) and per download (`audit_report_download`, before the first byte).
- `report_read` / `report_generate` (CLASS_0, all roles incl. aiview) and `report_raw_view` (CLASS_1, security_admin, never aiview); domain reads checked per template.
- Builders RPT-01/02/03/05, one masking pass, OpenPDF 2.2.5 / POI / RFC 4180 CSV renderers, 25 MiB cap, bounded executor (2), worker retention step for expired reports, Overview backup freshness excludes reports.
- UI: Reports rail entry under Overview with Catalogue, Run & preview, History. Decisions and open items: `docs/history/backlog/reports_r1.md`.

## Exact next action
Engineer: dry-run V190 in BEGIN/ROLLBACK on the live DB, run the dependency/CVE scan on OpenPDF 2.2.5, deploy, then generate all four reports as aiview (REAL_ENV gate).

## Test delta
+47 service tests (builders, byte-level masking PDF/XLSX/CSV, golden renderers, RBAC, audit, retention, size cap), +1 worker, +5 frontend; rail/App tests and the aiview action allow-list updated for the new entry and actions.

## New risks
- `project/backlog.json` is 17 bytes under its GOV.ORCH.9 budget.
- Integration-test registers (audit classification) were updated but not run here (no Docker).
