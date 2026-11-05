# Reports R1 contract

**Status: FROZEN - 2026-11-05.** Frozen by the engineer under the PO's freeze delegation
(`PO_DECISION_RECORD_2026_11_04_DEV_MODE_DELEGATIONS.md` §3). Design evidence: `REPORTS_DESIGN_2026_11_05_ARGON.md`
and its appended engineer decisions. Where this contract and the design differ, this contract wins.
The design's §4.7 SQL is **not** authority: its `notification_routes` column and type names are wrong.

## Scope (R1 only)
The four R1 reports below, on-screen, as PDF, XLSX or CSV. No schedules, no e-mail and no other reports; those are
R2 and R3.

- **RPT-01 Executive weekly summary:**
  - an estate verdict (HEALTHY / AT RISK / UNKNOWN) with data freshness;
  - four pillars: security posture, backup coverage, HA readiness, evidence freshness;
  - up to 5 attention items;
  - compliance headline: CIS and NIST, shown as the passing share of evidenced controls plus the share with no
    evidence.
- **RPT-02 Framework compliance and evidence coverage:**
  - per framework: CIS and NIST first, then PCI-DSS and Financial Baseline;
  - per control family: pass, fail, no evidence;
  - the top failing controls and the firewalls affected.
- **RPT-03 Backup coverage and anomalies:**
  - targets with and without an archive;
  - last archive age;
  - size anomalies: own history, fleet-large, firewall logs inside the backup (`backup_deviation_record` classes
    `size_anomaly`, `fleet_large`, `firewall_logs`).
- **RPT-05 HA and failover readiness:**
  - clusters ready, not ready or unknown, with the readiness age;
  - the reasons for not ready;
  - stale readiness flagged as such, never as ready.

## Data and honesty rules
- **Data sources:** reports read only the neXus database and the existing artefact store. No device command and no
  new collection.
- **Data age and coverage:** every report and every section carries "data as of" (the per-plane newest and oldest
  evidence time) and coverage (how many devices or clusters have evidence). UNKNOWN or no evidence is a shown
  category, never folded into pass or fail.
- **Stale data:** the staleness thresholds are the single product-wide freshness scale from UI review item B1. Until
  B1 lands, the existing Operations HA-readiness banner threshold applies. Stale data is labelled stale.

## Masking and RBAC
- **One masking pass:** each report builds one structured document model. One masking pass runs over that document
  before any renderer (on-screen JSON, PDF, XLSX, CSV). It uses the existing `TopologyNamePseudonymizer` and
  `SubnetPreservingIpMasker` through the existing report projection masker.
- **aiview (replay_viewer):** always masked.
- **RAW reports:** only roles granted the new action `report_raw_view`. That is `security_admin`, plus roles that
  already hold a raw view of the underlying data; never `replay_viewer`.
- **New actions in `ActionRegistry`:**
  - `report_read` (CLASS_0): catalogue, preview, history;
  - `report_generate` (CLASS_0): create and download a file;
  - `report_raw_view` (CLASS_1).

  Generating a report also requires the read action of each underlying domain the report uses.
- **Audit:** every generate and every download writes one audit row with template, format, projection, parameters
  and artefact id. A RAW download is audited before the first byte is streamed.

## Storage and execution
- **Storage:** generated files go to the existing encrypted artefact store, as a new artefact class `report`.
  - Retention is 30 days.
  - A file is capped at 25 MiB; a larger report fails with a clear error and nothing is truncated silently.
  - The retention janitor removes expired report artefacts like the other classes.
- **Schema:** one new migration, the next free V number, with one table `report_run`. Columns:
  - `run_id` (uuid, primary key);
  - `template_id` (closed list RPT-01/02/03/05);
  - `state` (QUEUED/RUNNING/SUCCEEDED/FAILED);
  - `projection_mode` (MASKED/RAW);
  - `format` (JSON/PDF/XLSX/CSV);
  - `parameters_json`;
  - `data_as_of_json`;
  - `artefact_id`;
  - `plaintext_bytes`;
  - `error_code`;
  - `requested_by`;
  - `requested_at`;
  - `finished_at`;
  - `expires_at`.

  No `report_schedule` table and no `notification_routes` change in R1. The migration is dry-run in
  BEGIN/ROLLBACK on the live DB before deploy (engineer step).
- **Execution:** reports run in ui2-service on a bounded executor (at most 2 concurrent builds) inside read-only
  transactions with a statement timeout. They need no job window or endpoint admission, because they touch no device.
- **Renderers:**
  - PDF uses OpenPDF (one pure-Java dependency; it must pass the existing dependency and CVE scan);
  - XLSX uses the existing Apache POI;
  - CSV is RFC 4180 with formula-injection escaping.

  No headless browser, no native binaries.

## UI
- A top-level "Reports" rail entry directly under Overview.
- Three tabs:
  - **Catalogue:** four cards with audience and question.
  - **Run & preview:** parameters (date range; framework for RPT-02); an on-screen preview; Generate
    PDF/XLSX/CSV.
  - **History:** the runs list, with download (within retention), state, data-as-of and projection.
- Masked fixtures and previews only (FW-TANGO-04, 192.0.2.x).

## Tests (definition of done for code)
- Unit tests per report builder against synthetic data, including UNKNOWN and stale cases.
- Masking tests: no raw hostnames, IPs or serials in the bytes of MASKED PDF, XLSX and CSV output.
- RBAC tests: aiview cannot get RAW; a missing domain read is refused.
- Audit row tests; retention and size-cap tests; renderer golden tests.
- `:service:test :persistence:test :architecture-tests:test`, frontend vitest and tsc, and the repository privacy
  gate: all green.

The status moves to REAL_ENV_VALIDATED only after the engineer generates all four reports on the live estate as
aiview.
