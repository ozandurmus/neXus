# Overview screen — implementation contract (first release)

> Frozen copy. The unaltered reviewer draft is `OVERVIEW_EXCEPTION_SCREEN_CONTRACT_FABLE_DRAFT.md`.

## 1. Status, scope, non-goals

**Status:** FROZEN — accepted by the Product Owner on 2026-09-23 ("I approve the contract; you can write it").
Drafted by Fable (`OVERVIEW_EXCEPTION_SCREEN_CONTRACT_FABLE_DRAFT.md`, kept unaltered); frozen by the
engineering session with the five corrections of `OVERVIEW_EXCEPTION_SCREEN_CONTRACT_ENGINEERING_NOTES.md`
and the population rule of §5.1 applied. Backlog `overview_exception_and_evidence_screen` (P0).
**Amended** A-2026-09-23 (§8, Product Owner directive: executive summary, version pie charts, colour) and
B-2026-09-23 (§9, Product Owner approval of the whole UI review: layout, posture tiles, deltas, wall mode).

**Basis:** `docs/design/OVERVIEW_COUNCIL_2026_09_22_ASTRA.md` and `docs/design/OVERVIEW_COUNCIL_2026_09_22_FABLE.md`, converged on the minimal first release both answers name.

**Scope:** replace the current Overview (four count cards, two text panels) with an exception-and-evidence screen fed by one aggregate endpoint reading stored PostgreSQL evidence and the existing compliance cache. One new stored table (cluster member DIFF summary). Click targets open existing screens with a filter applied.

**Non-goals (first release):** SLA, ownership/acknowledgement, BDDK attestation, trend against prior assessments, incident impact, "outdated" version judgments, backup policy-window (overdue) computation, HA role-change detection, Palo Alto content spread, top-N controls, reachability, any composite score, any device command, any collection triggered from the screen.

## 2. Screen layout (top to bottom)

Material 3, no gauges, no charts except one stacked bar. Every figure shows its evidence timestamp. Zero renders neutral, never green. `UNKNOWN` renders as the literal text `UNKNOWN` with a reason tooltip, never as `0`.

**Section 1 — title `Evidence`.** One row of six chips: `Inventory`, `Configuration`, `Compliance`, `Backup`, `Jobs`, `Platform facts`, each showing the latest stored evidence time as relative age plus absolute UTC on hover. Chip empty state: `UNKNOWN — no stored evidence`. Read-failure state: `READ FAILED`. An `aiview` badge sits right-aligned when the persona is masked.

**Section 2 — title `Needs attention`.** Five tiles, always visible, order fixed:
1. `Failed jobs, 24 h`
2. `Devices without inventory in 24 h`
3. `Clusters with member DIFF`
4. `Configuration changed since previous collection`
5. `Backup targets without an archive`

Tile body: `N of D` (or `N` for tile 1), one-line subtitle with the evidence time. Empty state: `0 of D`. Unknown state: `UNKNOWN` plus reason.

**Section 3 — title `Morning exceptions`.** Three lists, max five rows each, newest first, with link `Show all (N)`:
- `Failed jobs` — columns: masked target, job type, terminal reason, finished. Empty: `No failed jobs in the last 24 h.`
- `Configuration changes` — columns: masked device, sections changed, collected. Empty: `No configuration change in the latest collections.`
- `Cluster member DIFF` — columns: masked cluster, differing sections, computed. Empty: `No member differences in comparable clusters.` Unknown: `Member comparison not available for K clusters.`

**Section 4 — title `Inventory evidence age`.** One stacked bar, four segments, denominator = active devices: `under 24 h`, `24–72 h`, `over 72 h`, `never`. Legend shows count per segment.

**Section 5 — title `Compliance`.** Header line: `Evaluated firewalls E of G · Observed O% · Evidence coverage C% · Critical deficiencies K · Data gaps U`. Observed and coverage are never shown apart. Table, one row per framework: `Framework | Pass | Fail | Unavailable | Pass / total`. Empty/cold-cache state: `UNKNOWN — compliance not yet evaluated. Open Compliance to evaluate.`

**Section 6 — title `Platform`.** Line: `Devices T · Check Point C · Palo Alto P · Clusters L`. Histogram table `Check Point hotfix level`: one row per distinct `hotfix_level`, count of gateways, plus a final row `UNKNOWN` for gateways without a platform-facts record or a null level. No "outdated" wording anywhere.

**Section 7 — title `neXus`.** Footer line: `Completed 24 h X · Running Y · Oldest running Z ago · Last inventory: Check Point <age> · Palo Alto <age>`.

## 3. Figures

Common terms. `NOW` = server UTC at request. `ACTIVE` = devices whose `enrollment_state` is in the set the Inventory screen already treats as enrolled; the engineer reuses that predicate, does not define a new one, — that predicate is `enrollment_state = 'ENROLLED'`. `GATEWAYS` = `ACTIVE AND role = 'gateway'`. `LATEST_INV(d)` = `MAX(collected_at)` of `device_inventory_run` for device `d`. `LATEST_CFG(d)` = row of `device_configuration_run` with `is_primary AND device_id = d` and max `collected_at`. Cluster = distinct non-null cluster reference over `ACTIVE`, where the reference is the one the device list already serves (`JooqDeviceRepository` summary: parent candidate display name, else `devices.cluster_member_ref`, else the discovery candidate's cluster reference); a cluster's members are the devices sharing that value. Cluster counts are always computed on the raw references server-side; only labels are masked (pseudonyms may collide, backlog `aiview_masking_leak_audit`).

Click targets name the screen and the query filter it must accept (see §4.2).

### Section 1 — Evidence chips

| Chip | Definition | SQL source | UNKNOWN rule | Click |
|---|---|---|---|---|
| Inventory | `MAX(collected_at)` | `device_inventory_run` | no rows | Jobs, `job_type=inventory_collect` |
| Configuration | `MAX(collected_at)` | `device_configuration_run` | no rows | Jobs, `job_type=configuration_collect` |
| Compliance | max `collected_at` of the configuration runs the cached evaluation consumed | compliance cache metadata | cache cold | Compliance, no filter |
| Backup | `MAX(created_at)` | `backup_artefact` | no rows | Jobs, `job_type=backup` |
| Jobs | `MAX(finished_at)` | `jobs` | no terminal rows | Jobs, no filter |
| Platform facts | `MAX(observed_at)` | `device_platform_facts` | no rows | Inventory, no filter |

`READ FAILED` when the query for that chip raises; the endpoint still returns the other chips.

### Section 2 — Tiles

| Tile | Numerator | Denominator | SQL source | UNKNOWN rule | Click |
|---|---|---|---|---|---|
| Failed jobs, 24 h | `COUNT(*) WHERE state='FAILED' AND finished_at >= NOW-24h` | none shown; subtitle shows terminal jobs in 24 h: `state IN ('COMPLETED','FAILED') AND finished_at >= NOW-24h` | `jobs` | query failure only | Jobs, `state=FAILED&finished_since=24h` |
| Devices without inventory in 24 h | `ACTIVE` devices with `LATEST_INV(d)` null or `< NOW-24h` | `COUNT(ACTIVE)` | `devices`, `device_inventory_run` | query failure only; "never" devices count in numerator, not as UNKNOWN | Inventory, `inventory_age=stale` (sort by evidence age descending) |
| Clusters with member DIFF | clusters whose latest `cluster_member_diff` row has `comparable=true AND diff_section_count > 0` | clusters whose latest row has `comparable=true` | `cluster_member_diff` (§5) | if no row exists for a cluster, it counts in `K` non-comparable, shown as `UNKNOWN for K`; the ratio is still shown when the comparable denominator is > 0 | Configuration, `cluster_diff=present` |
| Configuration changed | `ACTIVE` devices where `LATEST_CFG(d).change_state = 'changed'` | `ACTIVE` devices with at least one primary configuration run | `device_configuration_run` | devices with `first_run` count neither; query failure only | Configuration, `change_state=changed` |
| Backup targets without an archive | `ACTIVE` devices with `backup_target = true` and no `backup_artefact` row | `COUNT(ACTIVE) WHERE backup_target = true` | `devices`, `backup_artefact` | query failure only | Backup, `artefact=none` |

Tile 5 deliberately does not use `backup_policy.schedule` (overdue deferred, §7).

### Section 3 — Exception lists (each capped at 5 rows, total count returned)

| List | Rows | Ordering | SQL source | Row click |
|---|---|---|---|---|
| Failed jobs | same predicate as tile 1; fields `job_id, job_type, target_device_id, terminal_reason, finished_at` | `finished_at DESC` | `jobs` | Jobs, `job_id=<id>` |
| Configuration changes | devices from tile 4; fields `device_id, run_id, collected_at`, `sections_changed = COUNT(*) FROM device_configuration_index WHERE run_id = latest run` | `collected_at DESC` | `device_configuration_run`, `device_configuration_index` | Configuration, `device_id=<id>&run_id=<id>` |
| Cluster member DIFF | clusters from tile 3; fields `cluster_ref, diff_section_count, computed_at` | `diff_section_count DESC, computed_at DESC` | `cluster_member_diff` | Configuration, `cluster_ref=<ref>&view=member_diff` |

`sections_changed` counts index entries of the changed run, not a section-level delta, and the column header says `Sections (latest)`; a true per-section delta is deferred.

### Section 4 — Inventory evidence age

Buckets over `ACTIVE` by `NOW - LATEST_INV(d)`: `<24h`, `24h–72h`, `>72h`, `never` (null). Source `devices`, `device_inventory_run`. Sum of buckets equals `COUNT(ACTIVE)`; the test asserts it. Click: Inventory, `inventory_age=<bucket>` with values `lt24h|24h_72h|gt72h|never`.

### Section 5 — Compliance

All six header figures and the per-framework rows are read verbatim from the existing service's overview figures (evaluated firewalls, observed %, evidence coverage %, critical deficiencies, data gaps, per-framework pass/fail/unavailable). The endpoint does not re-evaluate. Denominators are the compliance service's own; the contract does not redefine them. `G` = `COUNT(GATEWAYS)`. UNKNOWN rule: cache cold or older than its 10-minute TTL with no refresh yet → the whole section returns `UNKNOWN` with reason `not_evaluated`; the endpoint never blocks on evaluation. Click: framework row → Compliance, `framework=<id>`; critical deficiencies → Compliance, `severity=critical&result=fail`; data gaps → Compliance, `result=unavailable`.

### Section 6 — Platform

| Figure | Definition | SQL source | UNKNOWN | Click |
|---|---|---|---|---|
| Devices / Check Point / Palo Alto | `COUNT(ACTIVE)` grouped by `vendor_hint` | `devices` | none | Inventory, `vendor=<v>` |
| Clusters | `COUNT(DISTINCT cluster_member_ref) WHERE NOT NULL` over `ACTIVE` | `devices` | none | Inventory, `unit=cluster` |
| Hotfix level rows | `GATEWAYS WHERE vendor_hint='check_point'` joined to latest `device_platform_facts` per device (`MAX(observed_at)`), grouped by `hotfix_level` | `device_platform_facts` | gateways without a row or with null level form the `UNKNOWN` row | Inventory, `vendor=check_point&hotfix_level=<value>` |

`hotfix_level` is an opaque string; no parsing, ordering by string only.

### Section 7 — neXus footer

| Figure | Definition | Source | UNKNOWN | Click |
|---|---|---|---|---|
| Completed 24 h, Running | `completed_24h`, `running` | `GET /api/v2/jobs/stats` (called in-process, not over HTTP) | stats failure | Jobs, `state=EXECUTING` |
| Oldest running | `NOW - MIN(submitted_at) WHERE state IN ('CLAIMED','EXECUTING')`; labelled `since submitted` because no start time is stored | `jobs` | none running → `—` | Jobs, `state=EXECUTING` |
| Last inventory per vendor | `MAX(finished_at) WHERE state='COMPLETED' AND job_type = '<cp|pan>_inventory_collect'` | `jobs` | no rows | Jobs, `job_type=inventory_collect&vendor=<v>` |

## 4. API contract

### 4.1 `GET /api/v2/overview`

Auth: any authenticated persona. Response `200 application/json`:

```json
{
  "generated_at": "2026-09-22T06:00:00Z",
  "masked": true,
  "denominators": {"active_devices": 105, "gateways": 100, "clusters": 39, "backup_targets": 102},
  "evidence": {
    "inventory": {"at": "…Z" | null, "state": "OK|UNKNOWN|READ_FAILED"},
    "configuration": {...}, "compliance": {...}, "backup": {...}, "jobs": {...}, "platform_facts": {...}
  },
  "attention": {
    "failed_jobs_24h": {"count": 7, "terminal_24h": 212, "state": "OK|UNKNOWN", "reason": null},
    "stale_inventory": {"count": 4, "of": 105, "state": "OK"},
    "cluster_diff": {"count": 3, "of": 31, "unknown": 8, "state": "OK"},
    "config_changed": {"count": 5, "of": 103, "state": "OK"},
    "backup_missing": {"count": 19, "of": 102, "state": "OK"}
  },
  "exceptions": {
    "failed_jobs": {"total": 7, "rows": [{"job_id": "…", "job_type": "cp_configuration_collect",
        "device_id": "…", "label": "FW-TANGO-04", "terminal_reason": "…", "finished_at": "…Z"}]},
    "config_changes": {"total": 5, "rows": [{"device_id": "…", "label": "FW-JULIET-06",
        "run_id": "…", "sections_latest": 12, "collected_at": "…Z"}]},
    "cluster_diff": {"total": 3, "rows": [{"cluster_ref": "…", "label": "CLS-ROMEO-01",
        "diff_section_count": 2, "computed_at": "…Z"}]}
  },
  "inventory_age": {"lt24h": 98, "h24_72": 3, "gt72h": 1, "never": 3, "of": 105},
  "compliance": {"state": "OK|UNKNOWN", "reason": null, "evaluated": 92, "of_gateways": 100,
    "observed_pct": 29.1, "coverage_pct": 82.0, "critical_deficiencies": 172, "data_gaps": 428,
    "evidence_at": "…Z",
    "frameworks": [{"id": "cis", "name": "CIS", "pass": 0, "fail": 0, "unavailable": 0, "total": 0}]},
  "platform": {"devices": 105, "check_point": 65, "palo_alto": 40, "clusters": 39,
    "hotfix_levels": [{"level": "R81.20 Jumbo Take 119", "count": 22}, {"level": null, "count": 4}],
    "evidence_at": "…Z"},
  "nexus": {"completed_24h": 205, "running": 2, "oldest_running_submitted_at": "…Z" | null,
    "last_inventory": {"check_point": "…Z" | null, "palo_alto": "…Z" | null}}
}
```

Types: timestamps ISO-8601 UTC strings or `null`; counts non-negative integers; percentages numbers with one decimal; `state` enums as shown; `reason` string or `null`. Unknown numeric fields are `null` with `state: "UNKNOWN"`, never `0`.

**Masking.** `label` fields carry `observed_hostname` for unmasked personas and the `TopologyNamePseudonymizer` output for `role:replay_viewer`, applied server-side before serialization. `device_id`, `cluster_ref`, `job_id`, `run_id` are opaque identifiers and pass through unchanged. `terminal_reason` passes through the existing job-text sanitizer. No serial, management address, or raw configuration text appears in the response; `hotfix_level` is the only vendor string and is shared across devices.

**Caching and refresh.** Server-side cache 60 s per persona (masked and unmasked cached separately), keyed on nothing else; `ETag` from `generated_at`; `Cache-Control: private, max-age=60`. Queries for the seven sections run concurrently; a section failure yields `READ_FAILED`/`UNKNOWN` for that section only. Budget: 300 ms server time uncached on 105 devices / 39 clusters, measured in the API test. UI polls every 60 s and on window focus. The endpoint never triggers a job, a compliance evaluation, or a device command.

### 4.2 Existing routes the click targets need

Every click is a client-side navigation to an existing screen route with query parameters. Each screen must accept and apply the listed filters on load, ignoring unknown ones:

| Screen | Filters to accept |
|---|---|
| Jobs | `state`, `finished_since=24h`, `job_type` (`inventory_collect`, `configuration_collect`, `backup`, matched as suffix across `cp_`/`pan_`), `vendor`, `job_id` (opens detail) |
| Inventory | `vendor`, `inventory_age` (`stale`, `lt24h`, `24h_72h`, `gt72h`, `never`), `hotfix_level` (`unit=cluster` deferred: the Inventory tree already groups clusters) |
| Configuration | `change_state=changed`, `device_id`, `run_id`, `cluster_ref`, `cluster_diff=present`, `view=member_diff` |
| Backup | `artefact=none` |
| Compliance | `framework`, `severity=critical&result=fail`, `result=unavailable` |

The filter semantics must equal the endpoint's predicates; the API test in §6 asserts that each tile count equals the filtered list count for the same fixture.

## 5. Data to add

**5.1 `cluster_member_diff` (new table, required).** Cluster member DIFF is computed in the browser today and cannot feed a server-side count.

```sql
CREATE TABLE cluster_member_diff (
  cluster_ref        text        NOT NULL,
  computed_at        timestamptz NOT NULL,
  member_run_ids     text[]      NOT NULL,   -- primary configuration run per member used
  comparable         boolean     NOT NULL,   -- both members have a primary run of the same read_kind
  diff_section_count integer     NOT NULL DEFAULT 0,
  diff_sections      text[]      NOT NULL DEFAULT '{}',  -- section names only, no content
  PRIMARY KEY (cluster_ref, computed_at)
);
CREATE INDEX ON cluster_member_diff (cluster_ref, computed_at DESC);
```

Population: the service ports the browser comparison (`ui2/frontend/src/screens/configurationProjection.ts`: `projectCheckPoint`, `projectPaloAlto`, `projectCluster`) to Java with the same semantics — MEMBER-specific settings (hostnames, member addresses, HA link addresses) are never counted — and a shared fixture test proves the Java and TypeScript projections give the same DIFF count. A service task runs at startup and every 5 minutes: for each cluster whose members' latest sanitized configuration texts (the ones `GET /devices/{id}/configuration/text` serves) differ from the run ids recorded in the cluster's latest row, it recomputes and writes one row; texts are discarded after comparison (Raw-evidence law: only section names and counts persist). A DIFF therefore reaches the Overview at most 5 minutes after the collection that produced it. `comparable=false` when fewer than two members have a text. Rollback: drop the table; the browser path remains. Row identity for the endpoint: latest `computed_at` per `cluster_ref`.

**5.2 Index (required for the 300 ms budget).** `CREATE INDEX ON jobs (finished_at DESC) WHERE state IN ('COMPLETED','FAILED');` and `CREATE INDEX ON device_inventory_run (device_id, collected_at DESC);`, `CREATE INDEX ON device_configuration_run (device_id, collected_at DESC) WHERE is_primary;` if absent.

**Explicitly not added in the first release:** previous HA role retention, compliance assessment history, per-target collection schedule, backup due-time computation, acknowledgement/owner fields, change intent, per-control aggregates. The browser DIFF view stays as is; only the summary moves server-side.

## 6. Tests and acceptance

**Unit (service):** each predicate in §3 against a fixture of ≥6 devices covering: never-collected device; device at 23 h and 25 h; cluster with DIFF, cluster without, non-comparable cluster; `first_run` device; device with `backup_target` and no artefact; device without `backup_target` (must not count in any backup figure); job FAILED at 23 h and 25 h. Assert bucket sum equals active count, `cluster_diff.count + zero-diff + unknown = clusters`, no numeric `0` where state is `UNKNOWN`, and compliance section is `UNKNOWN` with cold cache and never invokes evaluation.

**API:** `GET /api/v2/overview` schema test against §4.1 (field names, types, enums); section-isolation test (one query patched to raise → that section `READ_FAILED`, HTTP 200); timing test on a generated 105-device / 39-cluster / 2 000-job fixture asserting < 300 ms uncached; cache test (second call within 60 s serves the cached body, `ETag` stable); tile-equals-list test for each tile against the filtered route of §4.2.

**UI (UI2 vitest, `ui2/frontend/tests/`; the legacy Python render harness does not apply to UI2):** Overview renders every section from a fixture, including one fixture with all-UNKNOWN sections and one with all-zero counts; no green state at zero; every tile and row has an `href` matching §4.2; exception lists cap at five with `Show all (N)`.

**aiview masking:** request as `role:replay_viewer` → every `label` matches the pseudonym pattern, no `observed_hostname` value appears anywhere in the body, `terminal_reason` passes the sanitizer, `masked: true`; unmasked persona → `masked: false`. Repository privacy gate green.

**Regression:** UI2 Gradle suites (service, persistence, worker, architecture-tests), UI2 vitest, `tests/test_architecture_convergence.py`, and the aiview browser tour on HOST-A.

**PO acceptance checklist (aiview persona, HOST-A build):**
1. Every figure shows an evidence time or `UNKNOWN`; none shows an unexplained `0`.
2. Each of the five tiles opens the named screen with the filter applied and the list count equals the tile.
3. Failed-job rows show a terminal reason and open the job.
4. A cluster DIFF row opens the member comparison on the differing section list.
5. Observed % and evidence coverage % appear on the same line; no score or traffic light anywhere.
6. Hotfix histogram carries an `UNKNOWN` row and no "outdated" wording.
7. No unmasked hostname anywhere on the screen or in the network response.
8. Page loads under 1 s in the browser on the live estate; endpoint timing logged < 300 ms.

## 7. Deferred (second release)

| Item | Data required |
|---|---|
| Backup overdue and retention exceptions | due-time derivation from `backup_policy.schedule` per target plus last successful backup job per target; a stored `next_due_at` per target |
| HA role change / two members same role | previous `device_inventory_ha.role` per member retained alongside the latest run, keyed by cluster and context |
| Failover readiness tile and list | a stored latest preflight verdict per cluster with timestamp; today the API is read-on-demand and may return no checks |
| Palo Alto content spread (app/threat/AV/WildFire/URL vs fleet-newest) | already in `device_platform_facts.content_versions`; deferred for scope only; needs an agreed version-ordering rule per key |
| Devices with uptime under 24 h | parse rule for `uptime_text` per vendor, gated as a semantic contract |
| Top five controls by critical deficiency | per-control aggregate from the compliance evaluator, or stored per-device results |
| Compliance trend | persisted assessment snapshots with stable definitions and run date |
| Acknowledgement / owner on jobs and findings | `owner`, `acknowledged_at` fields on `jobs` and on a findings record |
| Change intent (planned vs unplanned) | ticket/maintenance-window reference recorded by Script Execution |
| True changed-section delta per device | stored per-section hash per configuration run |
| Stale threshold per device instead of fixed 24 h | expected collection interval recorded by the scheduler per target |

## 8. Amendment A-2026-09-23 — executive visual pass (Product Owner directive)

**Source.** The Product Owner, 2026-09-23, after reviewing the first release under `aiview`: the Overview
"is acceptable but still a low-profile page; I expected it to be more appealing and an executive summary.
Per vendor, major and minor version information could be pie charts. This screen could use more colour."
Recorded here because the directive overrides a §2 rule; the first implementation of it (commit `bfa08bd`)
shipped before this record was written — that ordering was an engineering error, corrected by this amendment.

**What changes (supersedes the named §2 / §3 / §4 text only).**

1. §2 "no gauges, no charts except one stacked bar" is replaced by: part-to-whole **donuts** are permitted for
   the per-vendor software and hardware distribution and for inventory evidence age; horizontal **meters** and
   bars are permitted for ratios and counts. Each donut shows at most five named slices, the rest folded into
   `Other (n)`, and `UNKNOWN` as its own neutral slice — never dropped, never coloured as a value. Colours follow
   a fixed categorical order; status colours (good / warning / serious / critical) are used only for state and
   always paired with a word. Still prohibited: dial gauges, a composite score, "outdated" version judgments,
   green for zero, `0` for UNKNOWN.
2. **Executive band** above the tiles: one headline sentence composed only of figures already in this
   response, the six evidence chips, and four ratios — evidence current (fresh / active), backup targets
   protected (targets − without archive / targets), compliance evidence coverage (the stored percentage,
   as read), clusters in agreement (comparable − with DIFF / clusters). No new query.
3. **Software and hardware** (replaces the hotfix histogram): per vendor, three donuts.
   Check Point — major = `observed_software_version`, minor = `device_platform_facts.hotfix_level`,
   appliance = `device_platform_facts.platform_family`. Palo Alto — major = the leading `x.y` of the PAN-OS
   version, minor = the full version, model = `observed_model`. Values are opaque strings grouped by equality.
   Response: `platform.versions.{check_point|palo_alto}.{major|minor|model}: [{label|null, count}]`.
   Click: Inventory with `vendor` plus `sw_major`, `sw_version`, `hotfix_level` or `hw_model` (`unknown` for the
   UNKNOWN slice); the Inventory filter chip shows the resulting device count.
4. **Failed jobs** are grouped by cause before the list: `exceptions.failed_jobs.reasons` — at most six groups,
   largest first, each `{reason, count, devices, job_types, last_at}`. The group key is the reason code plus the
   head of its detail, digits folded to `N`, cut before ` (`, a nested `:` or a ` for <target>` tail, so no
   target name enters the key; `reason` passes `TopologyNamePseudonymizer.maskText` for `role:replay_viewer`,
   as does `terminal_reason`. The contract's list (§2 `Failed jobs`) follows as the three latest rows.
   The tile gains `attention.failed_jobs_24h.last_at` (latest failure) in its subtitle.

**Unchanged.** Every figure is a stored-evidence query with its evidence time; the endpoint, cache, masking,
population rule, click targets of §3 and the non-goals of §1 other than the chart rule stand as frozen.
Tests: `tests/OverviewScreen.test.tsx` (donut links, cause grouping, coverage as read),
`OverviewFailureReasonsTest` (grouping key, target tail dropped, cap of six).

## 9. Amendment B-2026-09-23 — layout from the UI review (Product Owner approval)

**Source.** `docs/design/UI_VISUAL_REVIEW_2026_09_23_FABLE.md` §2 and §6 (Fable, through
`scripts/consult_fable_ui_visual_review.py`, output unaltered). The Product Owner, 2026-09-23: "Let us make all the
changes" -- every P0, P1 and P2 item -- with the current state set aside first (`docs/operations/UI_BASELINE_2026_09_23.md`,
tag `ui-baseline-2026-09-23`). Hard constraint carried from that direction: no loss of capability.

**What changes (supersedes the named §2 / §4 text only).**

1. **Layout.** Row A: title `Overview`, subtitle `as of <UTC> · <active> of <enrolled> devices active · <active> of
   <enrolled> clusters active`, one freshness chip (`All evidence <oldest age>`) that expands to the six §2 chips.
   Row B: the headline in three lines, `Act now` (failed jobs, backup targets without archive), `Review` (cluster
   member differences, configuration changes), `Evidence` (inventory read in 24 h, compliance coverage with critical
   deficiencies and data gaps); numbers as "n of N", no adjectives. Row C: six posture tiles replacing the four KPIs and
   five attention tiles (the same facts, once): Failed jobs 24 h, Backup targets without archive, Clusters with member
   differences, Configuration changed, Devices without evidence 24 h, Compliance deficiencies -- each with count, "of N",
   status word, bar, one context line, a since-yesterday delta, one click target (unchanged from §3). Row D: compliance
   by framework | why jobs failed. Row E: software and hardware (below the fold; a one-slice donut becomes a stat line
   with the same link). Row F: configuration changes | cluster member DIFF | inventory evidence age.
2. **Since-yesterday deltas.** `attention.<tile>.previous` -- the same figure one day earlier, only where stored
   history computes it: failed jobs (the 24 h window before this one), devices without evidence 24 h (latest inventory
   read as of 24 h ago), backup targets without archive (today's targets against archives created before 24 h ago).
   Cluster differences, configuration changes and compliance keep no history: the tile says `no history`, never 0.
3. **Denominators.** `denominators.enrolled_devices` (every registry entry) and `denominators.clusters_enrolled`
   beside the active counts, so every screen can write "n active of N enrolled".
4. **Wall display** (`?wall=1`): dark tokens, no rail, no header actions, an as-of clock; rows B and C, then the
   failure causes beside the queue. Nothing new is computed. Masking follows the signed-in role, as everywhere.
5. **Mask indicator.** Every masked response carries `X-Nexus-Masked: true`; the top bar shows `aiview · names masked`
   on every screen from that header (the browser never infers it from a role token).

**Unchanged.** Every figure, percentage, click target and evidence time of §3; UNKNOWN written out; zero neutral;
no composite score; no "outdated" judgement; the endpoint, cache and masking rules.

## Amendment C (Product Owner, 2026-09-25) — executive summary
Supersedes the layout rows B–F for the normal (non-wall) view. The Overview is a manager's summary of five facts, each
a big number, one plain sentence, at most three named items and one link:
1. **Recoverability** — backup targets holding a stored backup, of all targets; how many have none.
2. **Compliance** — share of checks passing (assured), open critical findings, the three most failed critical/high
   checks by plain title.
3. **Configuration changes** — devices changed since their previous read; the latest three.
4. **Cluster consistency** — clusters whose members carry different settings; the top three.
5. **Versions and patches** — devices behind the newest build of their own line, in each vendor's terms (Check Point
   hotfix take within a major; Palo Alto maintenance build within a feature release). Shown only when a vendor has
   such facts; a vendor without them adds nothing.
Job failures, connection errors and raw reasons are not on the Overview (Operations owns them). Software/hardware
distributions, policy install ages and evidence age stay under a collapsed "Fleet details". The `?wall=1` view is
unchanged. Council record: `UI_EFFECTIVENESS_COUNCIL_2026_09_25_SYNTHESIS.md`.
