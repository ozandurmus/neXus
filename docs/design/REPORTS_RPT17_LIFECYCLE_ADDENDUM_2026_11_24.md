# Reports RPT-17 — Lifecycle and licensing addendum

**Status: DRAFT - 2026-11-24.** Authorizes the `RPT-17` predefined report implementation under Product Owner directive
2026-11-24, inheriting every rule of `docs/design/REPORTS_R1_CONTRACT.md` (FROZEN - 2026-11-05) without modifying that
frozen contract.

## Product Owner decision (2026-11-24)

> PO decision 2026-11-24: the Lifecycle screen stays where it is (Compliance and security > Lifecycle and security, and
> the Devices header button). Reports gains a predefined report built from the same stored lifecycle data, for audit
> and management hand-off.

## Template identifier

- **Template id:** `RPT-17`
  - `RPT-01`, `RPT-02`, `RPT-03` and `RPT-05` are the frozen R1 set (`docs/design/REPORTS_R1_CONTRACT.md`).
  - `RPT-04` and `RPT-06` through `RPT-16` are reserved in `docs/design/REPORTS_DESIGN_2026_11_05_ARGON.md` and
    `docs/design/REPORTS_R2_CONTRACT.md` (`RPT-09` there is hardware/software EoS/EoL catalog milestones).
  - `RPT-RC1` and `RPT-RC2` are reserved in `docs/design/RULE_RECERTIFICATION_CONTRACT.md`.
- **Title:** `Lifecycle and licensing`
- **Audience:** `Auditor`
- **Question:** *"Which licences, subscriptions and support contracts are expired or expiring within 30 or 90 days,
  which devices match exploited or critical/high advisories, what Live Patch and hotfix levels are stored, and which
  devices have no lifecycle read?"*
- **Domain read action:** `device_read` (the same gate as `GET /api/v2/lifecycle` and `GET /api/v2/lifecycle/advisories`).
- **Parameters:** standard date window (`from`, `to` in `Europe/Istanbul`); `framework_parameter = false`.
- **Formats:** on-screen JSON preview, `PDF`, `XLSX`, `CSV`.

## Inherited R1 invariants (`REPORTS_R1_CONTRACT.md`)

- **Stored evidence only:** reads only PostgreSQL via `LifecycleService.fleetSnapshot` / `LifecycleProjection` and
  `JooqReportReadRepository.newestInventory`. Zero network-device commands and zero new collection jobs.
- **Data age and coverage:** the report header and every section carry `PlaneAsOf` coverage (`lifecycle` and
  `inventory` planes) and explicit data-age / coverage statements. Stale evidence (`>24 h` per `Freshness.STALE_AFTER`)
  is labelled `STALE`.
- **Honest `UNKNOWN` separation:** missing reads, failed/unparsed reads, unparseable licence dates, and unevaluated
  advisory states (`NOT_EVALUATED`, `VERSION_UNKNOWN`, `NOT_COVERED`) are shown in their own categories and never
  folded into valid, covered, or unaffected counts.
- **Single masking pass & RBAC:** built once as an unprojected `ReportDocument`, projected once through
  `ReportDocumentMasker` (`TopologyNamePseudonymizer` + `SubnetPreservingIpMasker`, e.g. `FW-TANGO-04`,
  `CLS-ROMEO-01`, `192.0.2.x`), forced to `MASKED` for `aiview` (`role:replay_viewer`), and gated by `report_raw_view`
  for `RAW`.
- **Storage, audit & retention:** 30-day encrypted `FileArtefactStore` retention (`artefact_class = 'report'`), 25 MiB
  size cap, and one `audit_log` row per terminal generation and per download before streaming.
- **Schema (`V270__reports_rpt17_lifecycle_licensing.sql`):** additive widening of `report_run`'s `template_id` check
  constraint to `('RPT-01', 'RPT-02', 'RPT-03', 'RPT-05', 'RPT-17')` (`NOT VALID` + `VALIDATE CONSTRAINT`), preserving
  backward compatibility with earlier images. Dry-run inside `BEGIN/ROLLBACK` on the live database before rollout.

## Sections

1. **`licensing` — Licences, subscriptions and support contracts:**
   - KPIs: devices with licence/contract evidence (`covered of total`), expired items, items expiring within 30 days
     (`0..30` days), items expiring within 90 days (`0..90` days, cumulative), and devices `UNKNOWN` / not collected.
   - Per-device table (`licensing_items`) of expired items (`EXPIRED`, `EVAL_EXPIRED`), Smart Licensing problem states
     (`UNREGISTERED`, `OUT_OF_COMPLIANCE`), items expiring within 30 days (`WITHIN 30 DAYS`) or 31–90 days
     (`WITHIN 90 DAYS`), and items with `UNKNOWN` expiry/status. Per `LifecycleProjection.problems`, an expired earlier
     support contract on a device that holds a current (`DATED` or `PERMANENT`) support contract is historical and
     omitted.
2. **`advisories` — Advisory exposure (CISA KEV and critical/high):**
   - KPIs: devices evaluated (`evaluated of total`), devices affected by actively exploited advisories (CISA KEV),
     devices affected by critical/high advisories, devices `UNKNOWN` / not evaluated, and oldest advisory source age.
   - Table `advisory_affected`: evaluated devices matching CISA KEV (`EXPLOITED`) or critical/high (`CRITICAL_HIGH`)
     advisories for their stored software version (and Check Point Jumbo take), ordered with exploited devices first.
   - Table `advisory_unknown`: devices in `NOT_EVALUATED`, `VERSION_UNKNOWN`, `NOT_COVERED`, or `UNKNOWN`.
3. **`live_patch_hotfix` — Live Patch and hotfix currency:**
   - KPIs: devices with stored hotfix or Live Patch evidence, Check Point devices with Live Patch installed and not
     active (`ready > 0`), Check Point gateways missing a Live Patch package, and devices without stored hotfix /
     Live Patch evidence.
   - Table `live_patch_hotfix`: per Check Point device (`MISSING`, `NOT ACTIVE`, `COLLECTED`, `UNKNOWN`,
     `NOT_COLLECTED`) and per non-Check Point device carrying a stored `hotfix_level` (`STORED`).
4. **`coverage` — Lifecycle read coverage and freshness:**
   - KPIs: devices with a collected lifecycle read (`covered of total`), devices with no lifecycle read (`NO READ`),
     devices with a failed/unparsed read (`UNKNOWN`), age of the newest collected read across the estate, and count of
     stale (`>24 h`) reads.
   - Table `no_lifecycle_read`: devices with no lifecycle read (`NO READ`) or only non-collected attempts (`UNKNOWN`).
   - Table `lifecycle_read_freshness`: devices with collected lifecycle reads, collected read kinds, newest read
     timestamp, and read age (`CURRENT` vs `STALE`, e.g. `FW-TANGO-04`).
