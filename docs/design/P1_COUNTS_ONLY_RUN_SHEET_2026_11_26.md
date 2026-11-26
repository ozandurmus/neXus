# P1 counts-only PO-session run sheet (2026-11-26)

## Status

DRAFT — 2026-11-26. Run sheet only: decides nothing and authorizes nothing; a run is authorized only by the Product Owner session that starts it. Checked against `origin/main` at `77422e86`; section 3 is checked against `fcc6af14` plus the zone matrix scale harness port branch (the harness is not on `origin/main` until that branch merges).

Controlling text:

- `AGENTS.md` — Evidence laws (`Automated validation != real-environment validation`), Sensitive identity reporting law (`compare locally -> report the relationship, not the values`), Mandatory AIView inspection & post-parsing masking law (database inspections query counts and booleans only, never unmasked device rows), and Raw-evidence law.
- `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` (`RATIFIED`, ref `PO-2026-11-23-ayyas-midday`).
- The harness READMEs, migrations, and backlog history records cited in each section below, verified on `origin/main` at `77422e86`.

Every run on this sheet is an in-pod, read-only, counts-only Product Owner session run. None contacts a network device, none issues a device command, and none adds or changes a capability-gate row or schedule. Run them outside deploy rollout and end-to-end test windows (`measure_run.sh` stages into `/app/tmp`, which is lost on a rollout, and `probe.mjs` uses a machine session that an overlapping e2e run revokes).

---

## 1. PAN-OS below 12 stored zone sets

### Purpose

Feeds backlog item `policy_collection_gaps` and Product Owner answer 5 of `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` (section 1 item 5, governing `docs/design/PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md` section 6 "Limits") by measuring with counts only inside `ui2-service` whether enrolled Palo Alto firewalls not on PAN-OS 12 already hold parseable, fresh vsys zone sets in their stored effective-running documents.

### Preconditions

- `scripts/measurement/pan_vsys_zone_sets_pre12/` (`README.md`, `PanPre12ZoneSetMeasure.java`, `selftest.sh`, `measure_run.sh`, `run_in_pod.sh`, `show_safe.sh`) is present on `origin/main` at `77422e86`.
- The running `deploy/ui2-service` pod's `/app/service.jar` contains `PaloAltoConfiguredInterfaceParser` with `zoneNamesByVsys` and view `v_latest_effective_running_config` (`LatestEffectiveRunningReader`); per `scripts/measurement/pan_vsys_zone_sets_pre12/README.md`, if the running pod's parser is older and lacks `zoneNamesByVsys`, `measure_run.sh` fails at compile time and nothing runs.
- Operator host configuration (`~/.config/nexus/hosta`) and cluster `kubectl -n ui2` access exist on the operator workstation as read by `measure_run.sh`.
- No deploy rollout or `hosta_e2e.sh` run is active (`measure_run.sh` exits if `hosta_e2e.sh` is running, and `/app/tmp` is lost on a pod rollout).
- Explicit Product Owner session go-ahead before executing `measure_run.sh`.

### Steps

Copied verbatim from `scripts/measurement/pan_vsys_zone_sets_pre12/README.md`:

Local self-test (no pod, no database, no device; expects `show_safe	ok` and `selftest	ok`):

```sh
cd ui2 && ./gradlew :worker:classes :persistence:classes :platform-core:classes :job-engine:classes
bash scripts/measurement/pan_vsys_zone_sets_pre12/selftest.sh
```

PO-session in-pod run (`measure_run.sh` stages `run_in_pod.sh` and `show_safe.sh`, waits for `done` or `fatal`, writes `show_safe.sh`-filtered output to `pre12zs-<tag>.tsv`, and removes `/app/tmp/pre12zs-<tag>`):

```sh
MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/pan_vsys_zone_sets_pre12/measure_run.sh <tag>
```

### Evidence to record

From `pre12zs-<tag>.tsv` (tab-separated tokens matching `[a-z0-9_.]+` only):

- Fleet scope lines (`scope fleet <counter> <n>`): `total_pan_gateways`, `pan_os_12_gateways`, `measured_not_12_gateways`.
- Bucket totals (`bucket <v9|v10|v11|vother|vunknown|all> <counter> <n>`) and anonymous ordinal lines (`device pan_NN version_bucket <token>`, `device pan_NN <counter> <n>`): `firewalls_enrolled`, `with_stored_document`, `without_stored_document`, `age_le_24h`, `age_le_7d`, `age_older`, `age_unknown`, `parsed_ok`, `not_parseable`, `skipped_size`, `artefact_unreadable`, `with_vsys`, `vsys_total`, `vsys_with_zone`, `vsys_zero_zone`, `zone_total`, `max_zones_in_vsys`, `zone_set_candidate_le_24h`.
- Guard and completion lines: `filtered_lines` (expected `0`) and terminal token (`done` or `fatal	<lowercase exception class>`).

### How to read the result

Per `scripts/measurement/pan_vsys_zone_sets_pre12/README.md`, `docs/design/PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md` section 6, and `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` answer 5:

| Outcome in `pre12zs-<tag>.tsv` | Meaning | Follow-up it opens |
| --- | --- | --- |
| `done`, `filtered_lines` = `0`, and `bucket all zone_set_candidate_le_24h` > `0` (`age_le_24h`, `parsed_ok`, `with_vsys`, and `vsys_zero_zone` = `0`) | Stored effective-running documents on non-PAN-OS-12 firewalls already parse into fresh, non-empty vsys zone universes via `PaloAltoConfiguredInterfaceParser` | Write a MEASUREMENT RECORD and ask the Product Owner whether to widen the stored effective-running read beyond PAN-OS 12 (`PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md` section 6 "Limits") |
| `done`, `parsed_ok` > `0` and `vsys_with_zone` > `0`, but `age_le_24h` = `0` (`age_le_7d` / `age_older` dominate) | Non-PAN-OS-12 stored documents hold zone sets, but their `collected_at` age exceeds the 24-hour freshness bound at measurement time | Record counts in a MEASUREMENT RECORD; widening alone would still leave rules `UNKNOWN` whenever configuration collection is older than 24 hours |
| `done`, `without_stored_document` dominates or `not_parseable` / `vsys_zero_zone` / `skipped_size` / `artefact_unreadable` leave `zone_set_candidate_le_24h` = `0` | Widening the stored-document read would not supply usable zone universes (`vsys_zero_zone` is not a valid universe; `src="tpl"` and unreadable vsys entries are not exposed by `zoneNamesByVsys`) | Record counts in a MEASUREMENT RECORD; keep the PAN-OS 12 limit of `PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md` section 6 unchanged unless the PO authorizes a separate collection or parser lane |
| `compile failed` in `measure_run.sh`, terminal `fatal <exception_class>`, or `filtered_lines` > `0` | Pod jar predates `zoneNamesByVsys`, runtime DB/store read failed, or `show_safe.sh` dropped an unsafe field | Do not record as measured; verify the deployed build or open a harness fix item citing only the exception class token and `filtered_lines` count |

### Never record

Raw database rows, firewall hostnames, management or interface addresses, `device_id` values, serial numbers, vsys names, zone names, effective-running XML/configuration text, artefact-store keys, usernames, exception messages, bank identifiers, or any mapping from `pan_NN` ordinals back to devices.

### Closes

Satisfies the pre-12 zone-set measurement prerequisite of PO answer 5 (`docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` section 1 item 5 and section 5) for backlog item `policy_collection_gaps` once the PO session writes the MEASUREMENT RECORD (the widening decision itself remains a separate PO decision). Automated tests (`selftest.sh` and unit suites) and this live run are separate gates; `policy_collection_gaps` reaches `REAL_ENV_VALIDATED` only on clean live runs of both Section 1 and Section 2.

---

## 2. Hygiene gap engine attribution

### Purpose

Feeds backlog item `policy_collection_gaps` (and companion item `policy_gaps_engine_attribution_harness_run`) under Product Owner answer 5 of `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` by running `PolicyHygiene` (`sound` and `legacy` modes) and the Check Point gateway completeness classifier inside `ui2-service` over live stored snapshots to measure decided-versus-`UNKNOWN` rule counts after the D-1 gateway, V221 App-ID, and V229 zone-set fixes (`docs/design/POLICY_COLLECTION_GAPS_LIVE_MEASUREMENT_2026_11_17.md` section 5).

### Preconditions

- `scripts/measurement/policy_collection_gaps/` (`README.md`, `GapEngineMeasure.java`, `selftest.sh`, `measure_run.sh`, `run_in_pod.sh`, `show_safe.sh`) is present on `origin/main` at `77422e86`.
- All three gap fixes are deployed in the running `deploy/ui2-service` pod (`PolicyHygiene.analyze`, `PolicyLegacyView.of`, `ZoneUniverse`, `pan_vsys_zone_set` from V229, and `cp_policy_object_inventory` / `cp_policy_json_chunk`); per `scripts/measurement/policy_collection_gaps/README.md`, a pod older than the three fixes lacks `ZoneUniverse` and terminates with `fatal exceptionininitializererror`.
- Operator host configuration (`~/.config/nexus/hosta`) and cluster `kubectl -n ui2` access exist on the operator workstation as read by `measure_run.sh`.
- No deploy rollout or `hosta_e2e.sh` run is active (`measure_run.sh` refuses to start while `hosta_e2e.sh` runs, and `/app/tmp` is lost on a pod rollout).
- Explicit Product Owner session go-ahead before executing `measure_run.sh`.

### Steps

Copied verbatim from `scripts/measurement/policy_collection_gaps/README.md`:

Local self-test (no pod, no database, no device; expects `show_safe	ok` and `selftest	ok`):

```sh
cd ui2 && ./gradlew :service:classes :persistence:classes :platform-core:classes :worker:classes
bash scripts/measurement/policy_collection_gaps/selftest.sh
```

PO-session in-pod run (`measure_run.sh` stages `run_in_pod.sh` and `show_safe.sh`, runs `GapEngineMeasure` at `-Xmx2g` with a 1800 s timeout, filters `measure.out` through `show_safe.sh` into `gapeng-<tag>.tsv`, and removes `/app/tmp/gapeng-<tag>`):

```sh
MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/policy_collection_gaps/measure_run.sh <tag>
```

### Evidence to record

From `gapeng-<tag>.tsv` (four tab-separated fields `kind id key value`, every field matching `[a-z0-9_.]+`):

- Fleet scope lines (`scope fleet <counter> <n>`): `snapshots_total`, `snapshots_parse_failed`, `snapshots_with_failures_skipped`, `snapshots_analysed`, `snapshots_analysed_cp`, `snapshots_analysed_pan`, `snapshots_analysed_other`, `snapshots_analysis_failed`, `pan_zone_universe_known_snapshots`, `pan_zone_universe_unknown_snapshots`, `zone_set_rows`, `zone_set_rows_invalid`, `zone_sets_unreadable`, `inventory_gateway_rows`, `inventory_unreadable`, `cp_snapshots_gateway_census`, `cp_snapshots_with_gateway_objects`.
- Top-10 rank lines (`package p01..p10 vendor <cp|pan|other>`, `package p01..p10 security_rules <n>`).
- Engine verdict and attribution lines (`engine <label>.<sound|legacy> <counter> <n>` for `p01`..`p10`, `fleet.cp`, `fleet.pan`, `fleet.other`, `fleet.all`): `rules_evaluated`, `not_shadowed`, `redundant`, `conflict`, `unknown_total`, `gateway_own_usable`, `gateway_own_unmarked`, `gateway_blocker_usable`, `gateway_blocker_unmarked`, `gateway_any`, `app_default_ports`, `zone_list_universe_known`, `zone_list_universe_unknown`, `unknown_attributed`, `other_unknown`.
- Closed `UNKNOWN` reason slugs (`reason <label>.<sound|legacy> <slug> <n>`).
- Gateway completeness counts (`gateway <snapshot|inventory>.<type_slug|all> <reason|total> <n>` across slugs `simple_gateway`, `simple_cluster`, `cpmi_gateway_cluster`, `vsx_gateway`, `vsx_cluster`, `vs_cluster_netobj`, `other` and reasons `complete`, `not_in_inventory_or_stale`, `missing_or_invalid_main_ipv4`, `missing_interfaces`, `interface_missing_ip`, `cluster_missing_members`, `member_missing_ip_or_interfaces`, `empty_kept_addresses`, `incomplete_not_attributable`, `marker_present_unusable`).
- Guard and completion lines: `filtered_lines` (expected `0`) and terminal token (`done` or `fatal	<lowercase exception class>`).

### How to read the result

Per `scripts/measurement/policy_collection_gaps/README.md` and `docs/design/POLICY_COLLECTION_GAPS_LIVE_MEASUREMENT_2026_11_17.md` sections 3–5:

| Outcome in `gapeng-<tag>.tsv` | Meaning | Follow-up it opens |
| --- | --- | --- |
| `done`, `snapshots_analysis_failed` = `0`, `filtered_lines` = `0`; `sound` and `legacy` counts present for `p01`..`p10` and `fleet.all` | Live decided (`not_shadowed`, `redundant`, `conflict`) vs `unknown_total` rule counts are known; `legacy` minus `sound` gives the `defaultPortsHold` gain, and `zone_list_universe_known` vs `zone_list_universe_unknown` gives the V229 zone-set reach | Record the post-fix MEASUREMENT RECORD restating the 22,020 baseline groups (13,945 CP gateway, 4,928 PAN App-ID default ports, 481 PAN vsys zone lists) |
| `gateway *.all not_in_inventory_or_stale` > `0` | Snapshot gateway objects lack a matching inventory item or are older than the latest Check Point `gateways-and-servers` inventory | Re-check after a completed Check Point policy collection before attributing unmarked gateways to parser rules |
| `gateway *.all incomplete_not_attributable` > `0` (while the four raw-only reasons `missing_interfaces`, `interface_missing_ip`, `cluster_missing_members`, `member_missing_ip_or_interfaces` read `0`, as expected on stored projections) | Stored gateway projections kept an address with `completeness = false`; stored data cannot split the four raw `CpObjectInventoryParser.gatewayAddresses` conditions because raw replies are discarded | Only if the PO wants `incomplete_not_attributable` split further, propose a field-presence parser-diagnostic read under the `AGENTS.md` ad hoc parser-diagnostics gate (never run by this harness) |
| `zone_list_universe_unknown` dominates `zone_list_universe_known` | Most Palo Alto zone-list rules sit in snapshots whose targets have no fresh (< 24 h), non-empty `pan_vsys_zone_set` | Pair with Section 1's counts for the PO decision on widening stored effective-running reads vs keeping the PAN-OS 12 limit |
| `fatal exceptionininitializererror`, other `fatal <exception_class>`, `snapshots_analysis_failed` > `0`, or `filtered_lines` > `0` | Running pod predates `ZoneUniverse`, a read hit `statement_timeout = '120s'` or threw, or `show_safe.sh` dropped an unsafe field | Keep the item `in_progress`; verify pod build or open a targeted fix citing only the safe counter/class tokens |

### Never record

Raw `policy_snapshot` or `cp_policy_object_inventory` rows, policy IDs or hashes, `device_id` values, rule UIDs or rule names, gateway/cluster/object names, IPv4/IPv6 addresses, vsys or zone names, usernames, exception messages, bank identifiers, or any mapping from `p01`..`p10` ordinals to policy names.

### Closes

Closes the live engine-attribution measurement of `policy_collection_gaps` (and `policy_gaps_engine_attribution_harness_run`) once the PO session records the MEASUREMENT RECORD. Automated tests (`selftest.sh` and unit suites) and this live in-pod run are separate gates; `policy_collection_gaps` reaches `REAL_ENV_VALIDATED` only on a clean run (`done`, `snapshots_analysis_failed` = `0`, `filtered_lines` = `0`) alongside Section 1.

---

## 3. Zone matrix scale measurement for ZM-12 and the ZM-39 heap ceiling

### Purpose

Feeds backlog item `zone_matrix_delivery` acceptance criteria `ZM-12` (`docs/design/ZONE_MATRIX_CONTRACT.md` sections 9 and 10.2: performance budget on the 10 largest stored policies) and the heap part of `ZM-39` (sections 9 and 10.2). The harness measures the evaluation time and additional heap of the ten largest stored policies against a synthetic 500-zone, 200,000-block version built in memory. **It does not measure the interactive-policy p95 or the run-pool separation of `ZM-39`; those need a loaded pod and stay open.**

### Preconditions

- `scripts/measurement/zone_matrix_scale/` (`README.md`, `ZoneMatrixScaleMeasure.java`, `selftest.sh`, `measure_run.sh`, `run_in_pod.sh`, `show_safe.sh`) is present on `origin/main` once branch `lane/q-20261126-0804-G756-D37-p1-zm-scale-harness-port-to-main` has merged (it was not on `origin/main` at `77422e86`). Its harness was written and self-tested on lane G615 and has not been run against any environment.
- The running `deploy/ui2-service` pod includes the Zone Matrix evaluator (`ZoneMatrixEvaluator`, `ZoneMatrixContent`, `ZoneBlock`); per `scripts/measurement/zone_matrix_scale/README.md`, an older pod makes `measure_run.sh` stop at the compile step (exit 5) or the run end in `fatal noclassdeffounderror`.
- The harness does not merge Check Point domain objects into a snapshot (`ZoneMatrixPolicyBridge.withDomainObjects` is not applied), so a snapshot that depends on them can show more `NOT_EVALUABLE` rules than the screen does; read the verdict counts as how the evaluator reacts to a synthetic matrix, not as stored-policy compliance.
- Operator host configuration (`~/.config/nexus/hosta`) and cluster `kubectl -n ui2` access exist on the operator workstation as read by `measure_run.sh`.
- No deploy rollout or `hosta_e2e.sh` run is active (`measure_run.sh` refuses to start while `hosta_e2e.sh` runs, and `/app/tmp` is lost on a pod rollout).
- Explicit Product Owner session go-ahead before executing `measure_run.sh`.

### Steps

Copied verbatim from `scripts/measurement/zone_matrix_scale/README.md`:

Local self-test (no pod, no database, no device; expects `show_safe	ok` and `selftest	ok`):

```sh
cd ui2 && ./gradlew --no-daemon :service:classes :persistence:classes :platform-core:classes :worker:classes
bash scripts/measurement/zone_matrix_scale/selftest.sh
```

PO-session in-pod run (`measure_run.sh` copies the pod's own service jar, compiles only the harness against it, stages `zmscale-<tag>` into `/app/tmp`, runs `run_in_pod.sh` (`java -Xmx2g` through `PropertiesLauncher`, 30 minute timeout), waits for `done` or `fatal` (up to 30 minutes), writes the `show_safe.sh`-filtered output into `zmscale-<tag>.tsv`, and removes the stage directory):

```sh
MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/zone_matrix_scale/measure_run.sh <tag>
```

### Evidence to record

`measure.out` is tab-separated, four fields per line (`kind id key value`), every field `[a-z0-9_.]+`; `show_safe.sh` drops any other line and reports `filtered_lines`. Record these tokens and nothing else:

- `scope fleet <counter> n`: `snapshots_total`, `snapshots_parse_failed`, `snapshots_with_failures_skipped`, `snapshots_vendor_not_evaluated`, `snapshots_analysable`, `snapshots_ranked` (at most 10), `ranked_reread_failed`, `evaluation_failed`.
- `version synthetic <key> n`: `zones`, `blocks`, `cells` (expect 500, 200000, 1200), `compile_ms`, `retained_heap_mb`.
- Per ranked policy `p01`..`p10` (ordinal by security-rule count, `p01` the largest): `package pNN vendor cp|pan`, `security_rules`, `rules_total`; `eval pNN duration_ms`, `hygiene_ms`, `heap_before_mb`, `heap_peak_mb`, `heap_additional_mb`, `coverage_complete`, `hygiene_budget_reached`; `outcome pNN violation|compliant|not_evaluable|unknown|not_applicable n`; `reason pNN not_evaluable.<slug>` and `unknown.<slug>` counts (closed vocabulary, zero included).
- `sum fleet evaluated_policies`, `security_rules`, `duration_ms`.
- `budget fleet largest_within_30s`, `largest_within_512mb`, `all_ten_within_180s`, each `true`, `false` or `not_measured`.
- The final `done` line (a `fatal <exception class>` line instead means the run failed) and the `filtered_lines` count.

### How to read the result

Per `docs/design/ZONE_MATRIX_CONTRACT.md` section 9 (`ZM-12`, heap part of `ZM-39`) and `scripts/measurement/zone_matrix_scale/README.md`:

| Outcome | Meaning | Follow-up it opens |
| --- | --- | --- |
| `largest_within_30s true` and `largest_within_512mb true` (`p01` at most 30,000 ms and at most 512 MB additional heap) and `all_ten_within_180s true` (the ten durations sum to at most 180,000 ms) | The section 9 budget holds on the ten largest stored policies against the 500-zone version at the block cap, for this one cold-ish single evaluation per policy | The PO session writes the counts-only MEASUREMENT RECORD; the real-environment half of `ZM-12` and the measured additional-heap input of `ZM-39` are then evidenced. `ZM-39` stays `PARTIAL` for its interactive-p95 and pool-separation parts, which this harness does not measure |
| Any budget token `false` | A stored policy shape exceeds the section 9 budget | Keep `ZM-12` / `ZM-39` `PARTIAL` and open an engine, pool or block-cap tuning lane (`ZONE_MATRIX_CONTRACT.md` section 9); repeat the run first if a figure is near a limit (the pod is shared, the JVM is cold) |
| Any budget token `not_measured`, or `snapshots_ranked` below 10, `ranked_reread_failed` or `evaluation_failed` above 0 | The run is incomplete (`all_ten_within_180s` is `not_measured` unless exactly ten policies were ranked, re-read and evaluated; `largest_*` is `not_measured` when `p01` could not be evaluated) | Nothing is evidenced; the matching `scope` counter says why. Do not record a budget claim |

Limits of the figures: heap is a 20 ms sampling estimate of the harness JVM's own used heap, not a profiler and not the serving JVM; wall-clock is shared with whatever else the pod and node are doing; the harness does not prove that the hard time limit or heap ceiling of section 5.1 are enforced in the service. The interactive p95 of `ZM-39` is **not** measured by this harness.

### Never record

Raw `policy_snapshot` or `zone_matrix_version` rows, policy IDs or package/device-group names, rule UIDs or rule names, zone names, CIDR blocks, witness tuples or NAT addresses, usernames, or bank identifiers. Only the counted tokens above, taken from `zmscale-<tag>.tsv`, are recorded.

### Closes

Closes nothing by itself and decides nothing. The harness is on `origin/main` after the merge and has been self-tested only; it has not been run, so `ZM-12` and `ZM-39` stay `PARTIAL`. After a clean, complete PO-session run (all three `budget` tokens `true`, a `done` line, no `fatal`), the PO session may record the real-environment measurement half of `ZM-12` and the heap part of `ZM-39` in `zone_matrix_delivery`; the interactive-p95 part of `ZM-39` remains open. Automated tests (`ZoneMatrixScaleMeasurementTest`, the harness self-test) and the in-pod stored-policy run are separate gates; `zone_matrix_delivery` reaches `REAL_ENV_VALIDATED` only after its remaining delivery steps and a clean live run complete.

---

## 4. ASA saved-state relation check

### Purpose

Feeds the open Cisco ASA saved-state half of backlog item `verify_first_scheduled_mds_export_and_asa_saved_state` (and companion item `asa_mask_token_real_unit_check`, while the scheduled MDS export half is already recorded in `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` section 4) by checking with a read-only aggregate on `asa_config_relation` whether post-V253 nightly ASA backups confirm the `*****` startup-config mask assumption (`docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md` section 3.3).

### Preconditions

- Migrations `ui2/service/src/main/resources/db/migration/V250__asa_config_relation.sql` and `ui2/service/src/main/resources/db/migration/V253__asa_config_relation_manifest_key.sql` and `AsaConfigRelation` (including the 2026-11-25 pairing-budget fix pinned by `AsaConfigRelationMaskShapesTest`) are on `origin/main` at `77422e86` and deployed live.
- At least one post-V253 scheduled nightly Cisco ASA backup has completed (`V253__asa_config_relation_manifest_key.sql` executed `DELETE FROM asa_config_relation;` so that Myers diff alignment and manifest `artefact_id` re-keying populate fresh rows).
- Read-only `psql` access to database `ui2` on `ui2-db-0` in an authorized Product Owner session.

### Steps

Numbered read-only aggregate procedure from `docs/history/backlog/verify_first_scheduled_mds_export_and_asa_saved_state.md` (2026-11-25 bullet), querying only the non-identifying columns verified in `V250__asa_config_relation.sql` (`relation`, `lines_differing`, `lines_equal_by_mask`, `derived_at`; `artefact_id` and `device_id` from V250 and `differing_keywords` from `V253__asa_config_relation_manifest_key.sql` are not selected; no dedicated shell wrapper script exists in the repository, so none is quoted):

1. Count rows per `relation` value (`MATCH`, `MISMATCH`, `NOT_EVALUABLE`).
2. Sum `lines_differing`.
3. Sum `lines_equal_by_mask`.
4. Count rows with `lines_equal_by_mask > 0` (and specifically `MATCH` rows with `lines_differing = 0` and `lines_equal_by_mask > 0`).
5. Count rows with `relation = 'NOT_EVALUABLE'`.
6. Read `max(derived_at)`.

Verified read-only SQL aggregate (needs no raw text, key, hostname, or identifier column):

```sql
BEGIN TRANSACTION READ ONLY;
SELECT
    COUNT(*)                                                  AS rows_total,
    COUNT(*) FILTER (WHERE relation = 'MATCH')                AS rows_match,
    COUNT(*) FILTER (WHERE relation = 'MISMATCH')             AS rows_mismatch,
    COUNT(*) FILTER (WHERE relation = 'NOT_EVALUABLE')        AS rows_not_evaluable,
    COALESCE(SUM(lines_differing), 0)                         AS sum_lines_differing,
    COALESCE(SUM(lines_equal_by_mask), 0)                     AS sum_lines_equal_by_mask,
    COUNT(*) FILTER (WHERE lines_equal_by_mask > 0)           AS rows_equal_by_mask_gt_0,
    COUNT(*) FILTER (WHERE relation = 'MATCH'
                       AND lines_differing = 0
                       AND lines_equal_by_mask > 0)           AS rows_match_zero_diff_mask_gt_0,
    MAX(derived_at)                                           AS newest_derived_at
FROM public.asa_config_relation;
ROLLBACK;
```

### Evidence to record

Only the aggregate counts/sums and single newest timestamp: `rows_total`, `rows_match`, `rows_mismatch`, `rows_not_evaluable`, `sum_lines_differing`, `sum_lines_equal_by_mask`, `rows_equal_by_mask_gt_0`, `rows_match_zero_diff_mask_gt_0`, and `newest_derived_at`.

### How to read the result

Per `docs/history/backlog/verify_first_scheduled_mds_export_and_asa_saved_state.md` (2026-11-25 bullet) and `docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md` section 3.3:

| Outcome | Meaning | Follow-up it opens |
| --- | --- | --- |
| `rows_total` > `0`, `rows_not_evaluable` = `0`, and `rows_match_zero_diff_mask_gt_0` > `0` (`lines_equal_by_mask > 0` on `MATCH` rows with `lines_differing = 0`) | Real ASA startup reads printed masked secrets matching `*****`, and `AsaConfigRelation` accepted them without misreporting saved units as `UNSAVED` or `UNKNOWN` | Record the counts to confirm the V250 `*****` mask assumption on live units |
| `rows_match` > `0`, all `MATCH` rows have `lines_equal_by_mask` = `0`, and `rows_mismatch` = `0` | Saved configurations matched without exercising a masked secret difference (no masked key present in startup reads) | Record counts; `*****` token is neither contradicted nor positively witnessed yet |
| `MATCH` rows all have `lines_equal_by_mask` = `0` while `rows_mismatch` > `0` | Either units genuinely hold unsaved running changes, or the startup mask token/line shape differs from `*****` so masked lines count toward `lines_differing` | Compare across consecutive nights: a `MISMATCH` population whose `sum_lines_differing` is small and stable across nights points to a secret-bearing line shape missing from `AsaConfigRelationMaskShapesTest` (7 of 18 pinned shapes are marked `assumed`) and opens a parser-shape lane |
| `rows_total` = `0` or `rows_not_evaluable` > `0` | No post-V253 nightly ASA backup has completed yet (`rows_total` = `0`), or a stored bundle lacked a valid running/startup ASA configuration (`rows_not_evaluable` > `0`) | Wait for the scheduled nightly backup if `rows_total` = `0`; if `rows_not_evaluable` > `0`, open a bounded backup/parser investigation without dumping configuration text |

### Never record

Per-device `asa_config_relation` rows, `artefact_id`, `device_id`, raw `running-config.txt` or `startup-config.txt` lines, masked or clear keys/secrets/hashes, hostnames, management or interface addresses, serial numbers, usernames, or bank identifiers.

### Closes

Together with the PO's recorded MDS export verification (`docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` section 4), a clean aggregate allows the PO session to mark `verify_first_scheduled_mds_export_and_asa_saved_state` (and `asa_mask_token_real_unit_check` when `rows_match_zero_diff_mask_gt_0` > `0`) validated. Automated tests (`AsaConfigRelationMaskShapesTest`) and this live database check are separate gates; `verify_first_scheduled_mds_export_and_asa_saved_state` reaches `REAL_ENV_VALIDATED` only on a clean live run.

---

## 5. aiview live counts-only leak probe

### Purpose

Feeds backlog item `aiview_masking_leak_audit` and engineering default 3(b) / open consequence 5 of `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` by walking the 157 permitted `role:replay_viewer` (`aiview`) GET routes in `tools/security/aiview_leak_audit/routes.json` inside the live cluster and counting salted SHA-256 token matches against raw database identity columns so the Product Owner can verify live masking and decide whether a separate masked read-model/database role is needed.

### Preconditions

- `tools/security/aiview_leak_audit/` (`README.md`, `probe.mjs`, `core.mjs`, `routes.json`, `export_reference.sql`, `probe-pod.yaml`, `gen_routes.py`) and the 2026-11-25 masking fixes (`ConfigurationController`, `TopologyNamePseudonymizer`, `SubnetPreservingIpMasker`, `PrivacyMaskingResponseBodyAdvice`) are on `origin/main` at `77422e86` and deployed live.
- `kubectl -n ui2` access exists from the repository root, `cronjob/ui2-e2e` and `ui2-db-0` exist in namespace `ui2`, and `routes.json` is current (`tools/tests/test_aiview_leak_audit_routes.py`).
- Per `tools/security/aiview_leak_audit/README.md`, **no deploy e2e or ship is running** (an overlapping run revokes the machine session).
- Explicit Product Owner session go-ahead before launching `pod/aiview-leak-audit`.

### Steps

Copied verbatim from `tools/security/aiview_leak_audit/README.md`:

```sh
E2E_IMAGE=$(kubectl -n ui2 get cronjob ui2-e2e -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}')
sed "s#SET_E2E_IMAGE#$E2E_IMAGE#" tools/security/aiview_leak_audit/probe-pod.yaml | kubectl apply -f -
kubectl -n ui2 wait --for=condition=Ready pod/aiview-leak-audit --timeout=180s

# 1. code (not sensitive) into the pod
tar -C tools/security/aiview_leak_audit -cf - probe.mjs core.mjs routes.json | kubectl -n ui2 exec -i aiview-leak-audit -- tar -C /work -xf -

# 2. random salt, generated in the pod
SALT=$(kubectl -n ui2 exec aiview-leak-audit -- sh -c 'head -c 16 /dev/urandom | od -An -tx1 | tr -d " \n"')

# 3. reference hashes, streamed from the database pod into the probe pod (never written to this Mac)
kubectl -n ui2 exec -i ui2-db-0 -- sh -c \
  'psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -At -v salt='"$SALT"' -f -' \
  < tools/security/aiview_leak_audit/export_reference.sql \
  | kubectl -n ui2 exec -i aiview-leak-audit -- sh -c 'cat > /work/ref.tsv'

# 4. the audit (about 320 requests; table to stdout, add --json for machine-readable)
kubectl -n ui2 exec aiview-leak-audit -- node /work/probe.mjs --ref /work/ref.tsv --salt "$SALT" --json > leak-audit-result.json

# 5. clean up
kubectl -n ui2 delete pod aiview-leak-audit
```

### Evidence to record

From `leak-audit-result.json`, counts, route templates (`synthetic-ref` placeholders), and value-class names only — **rule: any surviving match is reported strictly as its value class (`HOST`, `ADDR`, `IPV4`, `SERIAL`, `CLUSTER`, `USER`, `NAME_WORD`) plus route template and integer count, never as a value, hash, or payload excerpt**:

- Reference coverage summary: `reference.entries_by_category` (counts per class `HOST`, `ADDR`, `IPV4`, `SERIAL`, `CLUSTER`, `USER`, `NAME_WORD`), `reference.source_columns` (`category`, `column`, `rows`), `reference.request_budget`, `reference.requests_sent`.
- Fleet totals: `totals.requests`, `totals.ok`, `totals.bytes_scanned`, `totals.routes_with_matches`, and `totals.matches` per value class (`HOST`, `ADDR`, `IPV4`, `SERIAL`, `CLUSTER`, `USER`, `NAME_WORD`).
- Per-route template entries (`routes[<route_template>]`): `requests`, `ok`, `client_error`, `server_error`, `other`, `skipped`, `truncated`, `bytes_scanned`, `header_matches`, and `matches` per value class.
- Uncovered route lists/counts: `routes_without_ok_response` and `routes_not_probed`, plus cleanup confirmation (`pod_deleted=true`).

### How to read the result

Per `tools/security/aiview_leak_audit/README.md` ("Reading the result" and "Covered and not covered") and `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` sections 3(b) and 5:

| Outcome in `leak-audit-result.json` | Meaning | Follow-up it opens |
| --- | --- | --- |
| `totals.matches` = `0` for `HOST`, `ADDR`, `IPV4`, `SERIAL`, and `0` (or verified deliberate public labels only) for `USER` and `CLUSTER`, with non-zero `reference.entries_by_category` | Zero raw database identities from `export_reference.sql` survived masking across the probed live `aiview` GET routes and headers | Record the counts-only report and put engineering default 3(b) (`aiview_masked_readonly_admin`: whether to build or defer a separate masked read-model/DB role) to the Product Owner |
| Non-zero `totals.matches` in `HOST`, `ADDR`, `IPV4`, or `SERIAL`, or non-public `USER` / `CLUSTER` | Raw identity leak on the reported `routes[<route_template>]` (body or `header_matches`) | Report **route template + value class + count only** (never the value); re-run the route under `aiview` to identify the unmasked field name (never printing its value), ship a projection fix lane, and re-probe |
| Non-zero `NAME_WORD` only (`HOST`, `ADDR`, `IPV4`, `SERIAL` = `0`) | Low-confidence match: 4+ character words from user or cluster names can collide with ordinary words (`README.md`) | Use as candidate routes to inspect by field name, not as an automatic leak failure |
| Non-empty `routes_without_ok_response` or `routes_not_probed`, or a category with `0` entries in `reference.entries_by_category` | Those routes/categories were not exercised with a 2xx payload or had no reference data — **not covered, not clean** (`README.md`) | Record uncovered route templates/categories explicitly alongside clean counts, and keep separate visibility on documented out-of-scope classes (e.g. non-GET routes and the `UNCATALOGUED_PRINCIPAL` free-text class pinned in `AiviewFailoverIncidentsSweepTest`) |

### Never record

Any surviving raw value, `/work/ref.tsv` hashes, `$SALT`, machine tokens (`T`, `NEXUS_PROBE_MACHINE_TOKEN`, `NEXUS_E2E_MACHINE_TOKEN`), `ui2_session` cookies, resolved request paths carrying real UUIDs or refs, response body fragments or headers, hostnames, IPv4/IPv6 addresses, serial numbers, cluster references, usernames, or bank identifiers.

### Closes

Closes the live raw-versus-masked comparison run of `aiview_masking_leak_audit` and provides the input required for the Product Owner decision on engineering default 3(b) (`aiview_masked_readonly_admin`). Automated synthetic sweeps (`AiviewRawVersusMaskedSweepTest` and rounds 2–7) and this live cluster probe are separate gates; `aiview_masking_leak_audit` reaches `REAL_ENV_VALIDATED` only on a clean live run (`HOST`, `ADDR`, `IPV4`, `SERIAL` matches = `0` and no unresolved `USER`/`CLUSTER` leak) together with the PO's read-model disposition.

---

## 6. ASA and FortiGate stored-policy counts

### Purpose

Feeds backlog item `asa_fortigate_policy_model` (P1, in progress; design steps 0-8 are built, `docs/design/ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` section 8 leaves "real-environment validation, separate gate") by re-measuring, with counts only inside `ui2-service`, what the current ASA parser, mapper, hygiene and lookup make of the latest stored `show_configuration` artefacts, and what shapes the stored ASA and FortiGate configurations have. Authorization basis of the measurement kind: `docs/design/PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` item 2 (Q1: one in-pod, counts-only measurement over the stored artefacts, no device contact, no configuration text read by a person or an agent). Whether that item also covers a repeat run is UNKNOWN; the PO session that starts a run decides it.

### Harness verdicts

The three harnesses were written on a 2026-11-14 branch; the READMEs of the mapper-validation and evaluation harnesses say "this branch's" parser and mapper. Each was checked against this repository at `26753874` by reading the code and comparing every class and method it calls with `ui2/platform-core` and `ui2/service`, and by compiling it (`javac --release 21`, no run, no pod image build) against the worktree sources; nothing was executed against an environment and no harness was changed.

| Harness | Verdict | Basis |
| --- | --- | --- |
| `scripts/measurement/asa_fgt_policy_shape/` (`AsaFgtShapeMeasure.java`, `measure_run.sh`) | `RUNNABLE_AS_DOCUMENTED` | Calls no policy class. Its only repository dependencies are `ArtefactRef(String)`, `ArtefactStore.retrieve(ArtefactRef, byte[], boolean)`, `FileArtefactStore(Path, ArtefactStoreCipher)`, `ArtefactStoreCipher.fromBase64Key(String)` and `SecretFile.readRequired(Path, String)`; all exist with those signatures. The SQL names columns of `devices`, `device_configuration_run`, `configuration_artefact` and `backup_artefact`; none is renamed or dropped by any migration. |
| `scripts/measurement/asa_policy_mapper_validation/` (`AsaMapperValidation.java`, `validate_run.sh`) | `RUNNABLE_AS_DOCUMENTED` | `AsaConfigPolicyParser.parse(String)` and `AsaPolicyMapper.map(String, String, String, String, AsaPolicyConfig)` match; the `AsaPolicyConfig` accessors it reads (`acls`, `networkObjects`, `serviceObjects`, `objectGroups`, `names`, `interfaces`, `accessGroups`, `counters`) and the `PolicySnapshot` records (`Section`, `Rule`, `Cell`, `PolicyObject`, failures) compile against the current sources. `validate_run.sh` still finds `policy/asa/*.tsv`. |
| `scripts/measurement/asa_policy_evaluation/` (`AsaEvaluationMeasure.java`, `evaluation_run.sh`) | `RUNNABLE_AS_DOCUMENTED` | Same parse/map calls, plus `PolicyHygiene.analyze(PolicySnapshot, int, Instant, int)` with `PolicyHygiene.TOTAL_WORK`, `PolicyHygiene.REASONS`, `PolicyLookup.prepare(PolicySnapshot, int)`, `Prepared.evaluate(PolicyLookup.Query)` returning `PolicyResponse.body()`, the seven-argument `PolicyLookup.Query`, `PolicyLookup.REASONS`, `PolicyLookup.ASA_LIST_WORK`, `PolicyNormalization.Budget(int)` and its `left` field, `PolicyNormalization.Resolver(Map, Budget)` and `PolicyNormalization.normalize(PolicySnapshot, Section, Rule, Resolver, boolean)` with `Match.problem()`. All exist with those signatures; the package-private ones are used from the same package (`com.securityexpert.nexus.ui2.service.policy`) as before. The `evaluation_run.sh` source list (`PolicyNormalization`, `PolicyLookup`, `PolicyHygiene`, `PolicyRuleMetrics`) still exists. |

No harness is `NEEDS_PORT`, so no port lane is opened by this section. A harness that is later found to need one is not ported by the lane that discovers it; the port is a separate lane.

Limits of these verdicts (what a compile and a read do not prove):

- The compile was against the repository sources, not against the running pod's `service.jar`. `validate_run.sh` and `evaluation_run.sh` compile the checkout's parser, mapper and engine sources against the pod jar, and the compiled classes shadow the pod's own copies at run time (`-Dloader.path`). The measurement therefore describes the checkout it is started from; start it from a checkout of the deployed commit, or the counters describe code that is not live. `AsaFgtShapeMeasure` is independent of this.
- The harness reads mapper vocabulary by string (`"ASA access-group"` section source, `" in"` section-name suffix, `"asa-interfaces"` object type, `"nameif: "`, `"ip-netmask: "`, `"protocol: tcp"`, `"port: "` values, `"RESOLVED"` status, the extras keys `user`, `time`, `security-group`, `asa-unparsed`). Each string was found in `AsaPolicyMapper.java` at `26753874`; a later change to one would not fail compilation and would silently change a counter.
- Behaviour changed after 2026-11-14 (A0-ASA-16a identity narrowing, the 2026-11-23 `ACL_NOT_BOUND` rule). A new run is compared with the before/after tables of `ASA_POLICY_EVALUATION_MEASUREMENT_2026_11_14.md`, not read as a repeat of its first table.
- None of the three harnesses measures FortiGate policy evaluation. The FortiGate parser, mapper and lookup are exercised only by automated tests; the shape harness counts FortiGate configuration shapes only.

### Preconditions

- `scripts/measurement/asa_fgt_policy_shape/`, `scripts/measurement/asa_policy_mapper_validation/` and `scripts/measurement/asa_policy_evaluation/` are present on the checkout the PO session runs from, and that checkout is the deployed commit (see Limits above).
- Operator host configuration (`~/.config/nexus/hosta`) and cluster `kubectl -n ui2` access exist on the operator workstation as read by the run scripts; `javac` (release 21) is on the workstation path.
- No deploy rollout or `hosta_e2e.sh` run is active (each run script exits if `hosta_e2e.sh` is running, and `/app/tmp` is lost on a pod rollout).
- At least one ASA or FortiGate gateway is enrolled with a stored `show_configuration` artefact; otherwise the files hold only the terminal token.
- Explicit Product Owner session go-ahead before executing any script on this section.

### Steps

Commands exactly as the harness READMEs give them. Each harness is run once, by the PO session, in this order; all three are in-pod, read-only against stored artefacts, and contact no device.

1. Shape measurement (`scripts/measurement/asa_fgt_policy_shape/README.md`); writes `shape-<tag>.tsv`:

```sh
SHAPE_WORK_DIR=<empty scratch dir> bash scripts/measurement/asa_fgt_policy_shape/measure_run.sh <tag>
```

2. Parser and mapper validation (`scripts/measurement/asa_policy_mapper_validation/README.md`); writes `val-<tag>.tsv`:

```sh
VALIDATE_WORK_DIR=<scratch dir> bash scripts/measurement/asa_policy_mapper_validation/validate_run.sh <tag>
```

3. Hygiene and lookup evaluation (`scripts/measurement/asa_policy_evaluation/README.md`); writes `eval-<tag>.tsv`. The README gives the form below, run from the harness directory; the script finds its own directory, so the repository-relative path works as well. `<scratch dir holding podjar/>` is the work directory of step 1 or 2, which unpacks the pod's `service.jar` into `podjar/`; the script stops with "no podjar/ in work dir" otherwise:

```sh
EVAL_WORK_DIR=<scratch dir holding podjar/ (the pod's service.jar unpacked)> bash evaluation_run.sh <tag>
```

### Evidence to record

Every field of every recorded line matches `[a-z0-9_.]+` (`show_safe.sh` drops any other line and prints `filtered_lines <n>`; devices are ordinals `asa_NN` / `fgt_NN`, VDOMs `v0`, `v1`, ...; configuration-derived tokens come only from closed vocabularies). Record only counters and closed-vocabulary tokens, per ordinal or summed:

- Guard and completion lines for each file: `filtered_lines` (expected `0`), no `unsafe_field_dropped` line, and the terminal token `done` or `fatal <lowercase exception class>`.
- Shape file (`fgt`/`asa` lines of `vendor, device ordinal, VDOM ordinal, key, value`): policy and ACE counts, `access_group_*` and bound/unbound ACL counts, identity (`user`/`user-group`) and `time-range` ACE counts, port words outside Cisco's table, non-contiguous masks, NAT section counts, interface and security-level facts, `in_pair`, the per-family `family.<key>` match/mismatch and `policy_hash` pair verdicts, and for FortiGate `vips`, `vip_in_dstaddr`, `vip_portforward`, `match_vip_set_*`, zone and mode counters. The `software` value is recorded as a major.minor bucket only.
- Mapper validation file (`asa` lines of `device ordinal, key, value`): `parser.*` (parsed/unparsed ACEs, NAT rules, object-group children, unknown top-level lines, interface, ACL and access-group counts), `failure.*`, `sections.*` and `rules.*` (including the `asa_not_evaluated` source), `access.*` (action, unique ACE and resolved-cell counts, `access.rules_fully_resolved`), `objects.<type>.<status>`, `align.*`, and `error_*` if any.
- Evaluation file (`asa` lines of `device ordinal, key, value`, plus `all` lines): `failures`, `hygiene_budget_reached`, `shadow.<status>`, `shadow_reason.<reason slug>` (reasons not in `PolicyHygiene.REASONS` are `other`), `finding.<class>` (classes `shadowed`, `conflict`, `disabled`, `unused`, `expired`, `broad`, `unknown`), `lookup.queries`, `lookup.verdict_<verdict>`, `lookup.reason.<reason slug>`, `lookup.budget_*`, `lists.*`, the timings, and `all retained_heap_mb_all_snapshots` and `all snapshots`.

### How to read the result

Meanings are taken only from `docs/design/ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md`, `docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` (FROZEN, "A0 amendment"), `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` answer 2, and the three earlier measurement records (`ASA_FGT_POLICY_SHAPE_MEASUREMENT_2026_11_14.md`, `ASA_POLICY_MAPPER_VALIDATION_2026_11_14.md`, `ASA_POLICY_EVALUATION_MEASUREMENT_2026_11_14.md`). Where they do not say, the cell says UNKNOWN. A count is evidence about the stored artefacts at their collection time, never about the device now.

| Outcome | Meaning | Follow-up it opens |
| --- | --- | --- |
| Evaluation file: `finding.unused` > `0` | Count of ASA ACLs whose mapper use is `none`: no access-group binds the ACL, no parsed feature (NAT, crypto map, VPN filter, class-map, route-map, others the parser reads) uses it, and no configuration line the parser does not read names it as a whole token. This is the `ACL_NOT_BOUND` meaning of PO answer 2; the counter is per ACL name (the mapper emits one entry per ACL in the `ASA not evaluated` section) and the harness prints no counter literally named `ACL_NOT_BOUND` | Record the count; it is a hygiene candidate wording "no reference found by the scanned uses", never a statement that the ACL is unused on the device |
| Evaluation file: `shadow_reason.` + the slug of the reason "ACL is named by a configuration feature the parser does not read; whether it is used is UNKNOWN" (the slug is cut at 60 characters, `acl_is_named_by_a_configuration_feature_the_parser_does_not_`) > `0` | ASA ACLs named only by a line the parser does not interpret: the verdict is UNKNOWN, never `ACL_NOT_BOUND`, never OK (PO answer 2). `shadow.unknown` includes these | Record the count; a large share relative to `finding.unused` shows how much of the old "unused" population was an unread-feature reference, and which unparsed families to read next is UNKNOWN until `unparsed_sig.*` or a parser lane says |
| Evaluation file: `shadow.not_shadowed`, `shadow.redundant`, `shadow.conflict`, `shadow.unknown` | Shadow status of each rule in the evaluated lists: not covered by an earlier rule / fully covered by an earlier rule of the same action / covered with a different action / not decidable | Compare with the "after A0-ASA-16a" column of the 2026-11-14 evaluation record; `redundant` and `conflict` are proofs, `unknown` is not a finding and is never shown as OK |
| Evaluation file: `shadow_reason.<slug>` (UNKNOWN reasons: unsupported constraints of an earlier rule, user or category, `asa_not_evaluated`/unsupported rulebase, empty or dynamic group, time, unresolved object, unsupported service) | Why a rule is not decidable. A0-ASA-16 keeps an identity criterion UNKNOWN; A0-ASA-16a lets its address and port cells bound it only to prove a flow is outside it | Record counts; which reason dominates informs the next parser or PO question, not an authorization |
| Evaluation file: `lookup.verdict_allowed`, `lookup.verdict_denied`, `lookup.verdict_unknown` and `lookup.reason.<slug>` | Verdict and fixed reason of each synthetic in-memory lookup (a rule's own single-host destination and tcp port, from a documentation source address, leaving by another interface of the same unit). A `denied` carries the implicit deny of the list, the same-security-level rule or a first matching deny rule; `unknown` carries "earlier rule could match" or "analysis budget" | These are lookups the engine answers over the stored policy; they are not compared with the device's own answer (see Closes) |
| Evaluation file: `lookup.budget_in_list_with_128_plus_constrained` versus `lookup.budget_other_cause`; `lists.with_128_or_more_constrained_rules`, `lists.with_128_plus_undecidable`, `lists.normalization_budget_exhausted`; `hygiene_budget_reached` = `1` | A lookup that ended on "analysis budget" because its list holds 128 or more identity, time, security-group or unparsed rules, or for another cause; a list the normalization budget cannot hold; the hygiene work limit reached. `hygiene_budget_reached` = `1` means the hygiene counts of that unit are partial | Record counts; do not read a unit with `hygiene_budget_reached` = `1` or `lists.normalization_budget_exhausted` > `0` as complete |
| Evaluation file: `failures` > `0` for an ordinal, or `error_<exception class>` | The snapshot carried collection failures, so lookup and hygiene treat that policy as incomplete; or the unit's processing threw | Do not count that unit as measured; record the token only |
| Mapper file: `parser.unparsed_aces`, `parser.unparsed_nat`, `parser.unparsed_og_children`, `parser.unknown_top_level` > `0` | Lines in policy families the parser read but could not interpret; each unparsed ACE keeps its rule UNKNOWN (`extras` key `asa-unparsed`) | Record counts; a parser-shape lane needs a closed `unparsed_sig.*` signature, never a raw line |
| Mapper file: `objects.<type>.<status>` with a status other than `resolved`; `access.src_`, `access.dst_`, `access.svc_` leaf classes other than `resolved` | Objects and cells the mapper marked UNRESOLVED, UNSUPPORTED or UNKNOWN (fail closed). The 2026-11-14 validation record carries an open finding of ACE operands naming a network object with no definition in the stored text, cause UNKNOWN; this run is another reading of it, not its resolution | Record counts; the cause stays UNKNOWN unless a different evidence source (the startup configuration in the backup bundle, no device contact) is compared in a separate step |
| Mapper file: `access.unique_aces` against the shape file's bound-ACE count; `sections.asa_access_group` and `rules.asa_access_group` | Whether the mapper sees as many unique ACEs in access-group sections as the shape tokeniser counts in bound ACLs (they agreed on 2026-11-14) | A difference is recorded as a mismatch between two counters of the same artefacts; its cause is UNKNOWN until a lane isolates it |
| Shape file, FortiGate: `vips` = `0` and `vip_in_dstaddr` = `0` | No VIP object and no policy destination naming a VIP in that VDOM, so the VIP-dependent A0-FG-18/19/20 UNKNOWNs have no exposure there (design section 11: `match-vip` options are inert only in a VDOM without any VIP) | Record the counts |
| Shape file, FortiGate: `vips` > `0`, `vip_in_dstaddr` > `0` or `vip_portforward` > `0` | The stored configuration contains VIP-published destinations. Lookup then evaluates the VIP cell as its external address and its mapped address in parallel (A0-FG-19) and answers UNKNOWN where they differ or where an earlier ordinary rule and a later VIP rule both match (A0-FG-18, `FGT_VIP_PRIORITY_UNPROVEN`; A0-FG-20, `FGT_MATCH_VIP_UNPROVEN`). The exposure is real; **how many FortiGate lookups would be UNKNOWN is UNKNOWN: no harness runs FortiGate lookups, and "FortiGate VIP-published services UNKNOWN count" is not produced by any of the three** | Record the exposure counts; a FortiGate evaluation harness is a separate lane and is not on this sheet |
| Shape file: `in_pair`, `policy_hash`, `family.<key>` = `mismatch` for a pair | Per-policy-family comparison of the two members of an HA pair claim (remarks ignored, per decision record item 6). A `mismatch` is a claim about stored text only; pair existence is not pair health (AGENTS.md Evidence laws) | Record per-family tokens; whether the members are synchronized is not answered by this file |
| Any file: `done` absent, `fatal <exception class>`, `filtered_lines` > `0`, `compile failed` or `jar copy failed` from a run script | Run did not complete or an unsafe field was dropped | Do not record as measured; verify the deployed build and checkout and open a harness fix item citing only the exception class token and the `filtered_lines` count |

### Never record

Raw database rows, hostnames, management or interface addresses, `device_id` values, serial numbers, ACL, object, object-group, interface (`nameif`), VDOM, zone, policy or policy-name values, user and group names, certificate or key material, configuration text or any line of it (including the first words of a line), artefact references and artefact-store keys, usernames, exception messages, bank identifiers, and any mapping from `asa_NN` / `fgt_NN` / `v<n>` ordinals back to devices or VDOMs. If a diagnostic run ever emits a configuration-derived token that is not from a closed vocabulary, delete that output file, do not copy any of it, and record only that this happened.

### Closes

Gives the PO session fresh counts-only evidence for the stored-policy half of `asa_fortigate_policy_model`: the current parser and mapper over the stored ASA configurations, hygiene and lookup counts (including the `ACL_NOT_BOUND` and UNKNOWN split of PO answer 2), and the stored ASA and FortiGate shapes. A clean set of three files with `done` and `filtered_lines` = `0` supports an `AUTOMATED_VALIDATED` statement over stored artefacts and nothing more.

**It does not close** the real-environment validation of design section 8: the 20 masked lookups per vendor, chosen by the Product Owner, compared with the device's own answer (the ASA `packet-tracer`, R2; a FortiGate equivalent still to be researched and proposed). That needs a device read that has no approved gate row, so it requires the network-device command gate and a literal Product Owner decision (`AGENTS.md` "Network action taxonomy" and the 2026-09-26 ad hoc parser-diagnostics amendment); it stays a PO decision and is not on this sheet. Until it is recorded, `asa_fortigate_policy_model` does not reach `REAL_ENV_VALIDATED`; counts of lookups answered by the engine are not agreement with the device. It also does not close the FortiGate lookup validation (no harness exists), the open finding of undefined network-object references (cause UNKNOWN), or the Zone Matrix ASA/FortiGate amendment decision.

---

## Not on this sheet

- **Script Execution real-environment validation (`script_execution_module`):** governed by PO answer 6 (`docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md`) and covered by its own standalone checklist in `docs/design/SCRIPT_EXECUTION_REAL_ENV_VALIDATION_CHECKLIST_2026_11_23.md`.
- **Anything that issues a network-device command:** excluded; any new, changed, or ad hoc parser-diagnostic device read (for example splitting Check Point `incomplete_not_attributable` gateway objects on raw replies, `docs/design/FORTIGATE_POLICY_LOOKUP_VALIDATION_READ_PROPOSAL_2026_11_23.md`, or `docs/design/MC_DEVICE_HEALTH_READ_PROPOSAL_2026_11_24.md`) requires the network-device command gate and literal Product Owner approval (`AGENTS.md` "Network action taxonomy").
- **CyberArk and enterprise platform items (`cyberark_archive_key_custody`, `recovery_offhost_key_custody`, `enterprise_phase0_logging_monitoring_scans`):** on hold awaiting external/enterprise prerequisites rather than in-pod counts-only runs.
- **Product Owner decisions and councils (not runs):** convening `recertification_council` (`docs/design/RULE_RECERTIFICATION_COUNCIL_PACKET_2026_11_25.md`, PO answer 4), deciding whether to widen pre-12 PAN zone-set reads after Section 1, deciding the Zone Matrix ASA/FortiGate amendment (`docs/design/ZONE_MATRIX_ASA_FORTIGATE_AMENDMENT_PROPOSAL_2026_11_25.md`, default 3(a)), and deciding the `aiview` masked read-model/DB role after Section 5 (default 3(b)).
