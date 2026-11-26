# Zone matrix — real-environment acceptance checklist (2026-11-26)

## Status

DRAFT -- checklist for the PO's real-environment run; not a contract; decides nothing. Checked against `origin/main` at `8a304479` (2026-11-26). It changes no contract, decision record or project state. Backlog items `zone_matrix_delivery` and `zone_matrix_step_4b2` stay `AUTOMATED_VALIDATED` until the Product Owner reports the completed run (this checklist itself is not real-environment evidence).

Controlling documents: `docs/design/ZONE_MATRIX_CONTRACT.md` (FROZEN, 2026-11-13, revision 2; sections 3.1, 3.4, 5.1, 5.9, 7.1, 7.2, 7.3, 8.1, 8.2, 8.4, 10.2 acceptance rows `ZM-8`, `ZM-10`, `ZM-26`, `ZM-30`, `ZM-33`, `ZM-34`, `ZM-35`, `ZM-36`, `ZM-37`, `ZM-39`, `ZM-40`, and Amendment 2026-11-23), `docs/design/PO_DECISION_RECORD_2026_11_13_ZONE_MATRIX.md` (RATIFIED), and `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` (RATIFIED, authorization ref `PO-2026-11-23-ayyas-midday`: 24-hour `STALE` interim threshold). Every step below cites the clause it proves. CLI forms match `ui2/cli/src/main/java/com/securityexpert/nexus/ui2/cli/ZonesMatrixCli.java` and `NexusSessionCli.java` on `origin/main` at `8a304479`; the executable is `nexus-cli` (`overwatch-cli` is the same program under its new name).

**Scale measurement cross-reference (not duplicated here).** The counts-only in-pod scale measurement for `ZM-12` and the additional-heap part of `ZM-39` (`scripts/measurement/zone_matrix_scale/`) is already documented in section 3 of `docs/design/P1_COUNTS_ONLY_RUN_SHEET_2026_11_26.md`. Execute and record that measurement from the run sheet; do not repeat it in this checklist (interactive-policy p95 and pool separation under load in `ZM-39` remain open outside that harness).

**What this run touches.** Read-only on network devices: **no device contact at all**, no device command, no schedule change, and no capability-gate change (`ZM-13`). Every evaluation reads stored Check Point and Palo Alto policy snapshots already held by `PolicyCatalog` and writes only Zone Matrix run, finding and access-audit rows (plus one oversize edit request refused with HTTP `413` before parsing, storing nothing). UI inspections, visual checks, Overview attention checks, and masked summary reads use the `aiview` session (`role:replay_viewer`, helper `aiv`). Because `aiview` never sees CIDR blocks, looked-up addresses or witness tuples (`ZONE_MATRIX_CONTRACT.md` §§3.4, 8.2, 8.4; `AGENTS.md` Mandatory AIView inspection law), `zone-matrix-lookup` and `zone-matrix-finding-witness` positive reads, `zone-matrix-run` submission, `GET /audit-logs` delta checks, and the `413` edit-limit probe use the `security_admin` CLI session (`adm`) only, while `aiview` calls against restricted routes check HTTP `403` refusal status only.

**Sensitive identity reporting rule (`AGENTS.md`).** Compare locally -> report the relationship, not the values. Every evidence token recorded from this run is a boolean, an integer count, a closed enum word, an HTTP status code, a duration (`ms` or `s`), `MATCH` / `MISMATCH` / `NOT_EVALUABLE`, or a pseudonymised/opaque token — never a raw IPv4/IPv6 address, CIDR block, witness range, hostname, serial number, package/device-group name, rule name, rule body, username or session token. All examples in this document are synthetic (`FW-TANGO-04`, `192.0.2.0/24`, `192.0.2.10`, `ZONE-1`).

**Evidence report scope note (`ZONE_MATRIX_CONTRACT.md` §7.3).** Although the pure content builder (`ZoneMatrixEvidenceReport`), stored-summary input assembler (`ZoneMatrixEvidenceReportInputs`) and blocks-appendix builder (`ZoneMatrixEvidenceBlocksAppendix`) are on `origin/main`, the Reports R1 catalogue (`ReportTemplate`: `RPT-01`, `RPT-02`, `RPT-03`, `RPT-05`, `RPT-17`, enforced by `report_run.template_id`) is closed and opening it for a Zone Matrix evidence report template is an open Product Owner question (`zone_matrix_step_6b_evidence_report_wiring`). No Zone Matrix evidence report route or CLI command exists on `origin/main`, so the evidence report route is **not** part of this acceptance run and no steps are written for it.

---

## 1. Preconditions (tick each Yes / No)

Verify directly through the routes below that the deployed build carries the Zone Matrix migrations (`V216__zone_matrix_versions.sql`, `V220__zone_matrix_cells.sql`, `V228__zone_matrix_witness_access_log.sql`, and `V267__zone_matrix_runs_and_findings.sql`; per contract §8.1, `V267` requires its `BEGIN/ROLLBACK` dry-run on the live database before rollout) and the step 1–5 routes.

| # | Precondition | How to check (counts, booleans and enums only) | Yes / No |
|---|---|---|---|
| PR1 | Active `security_admin` CLI session (`adm`) with `zone_matrix_zones_read`, `zone_matrix_edit`, `zone_matrix_evaluate`, and `audit_log_read` | `adm session` (`GET /session/status`) exits `0`; `adm zone-matrix-draft` (`GET /api/v2/zone-matrix/draft`) exits `0` with `"can"` carrying `"view_blocks": true`, `"edit": true`, `"evaluate": true` | |
| PR2 | Active `aiview` CLI + browser session (`aiv`, `role:replay_viewer`, masked view) | `aiv session` (`GET /session/status`) exits `0` as `replay_viewer`; `aiv zone-matrix-draft` exits `0` with `"can"` carrying `"view_blocks": false`, `"edit": false`, `"publish": false`, `"confirm_reference": false`, `"evaluate": false` | |
| PR3 | Deployed build includes the Zone Matrix migrations (`V216`, `V220`, `V228`, `V267`) and routes (`/versions`, `/template`, `/policies`, `/runs`, `/summary`, `/findings/{id}/witness`) | `aiv zone-matrix-versions`, `aiv zone-matrix-template`, `aiv zone-matrix-policies`, and `aiv zone-matrix-summary` all exit `0`; `zone-matrix-summary` returns `"state"` (`NO_ACTIVE_VERSION`, `NO_EVALUABLE_POLICY`, `NO_COMPLETE_RUN`, or `READY`), `"masked": true`, `"witnesses_included": false`, and `"staleness_threshold"` | |
| PR4 | At least one stored Check Point or Palo Alto policy snapshot exists | `aiv zone-matrix-policies` (`GET /api/v2/zone-matrix/policies`) exits `0` and lists at least one entry in `"policies"` (`vendor` in `CP` or `PAN`, opaque `id` saved locally as `POLICY_ID`) | |
| PR5 | An active published Zone Matrix version exists | `aiv zone-matrix-versions` (`GET /api/v2/zone-matrix/versions`) lists one entry with `"state": "ACTIVE"` (`version_id` saved locally as `ACTIVE_VERSION_ID`) and `aiv zone-matrix-version "$ACTIVE_VERSION_ID"` exits `0`; if none exists (`"state": "NO_ACTIVE_VERSION"` in `zone-matrix-summary`), see rule below | |
| PR6 | No other operator is concurrently running `zone_matrix_zones_read` actions (`BLOCKS_VIEW`, `ADDRESS_LOOKUP`, `WITNESS_VIEW`) during step group `W` | Agreed with the team so the `GET /audit-logs` delta in `W02`–`W03` reflects this session alone | |

Stop at any "No" in PR1, PR2, PR3 or PR4: the run cannot start. If PR5 is "No" (`state=NO_ACTIVE_VERSION` because bank zone blocks / four-eyes publish under §3.4 and §6.2 have not been completed yet), record `pr5_active_version=false`, verify in `aiv zone-matrix-summary` and on `?screen=overview` as `aiview` that `"headline"` is `null` and no Zone Matrix attention row is rendered (§7.2: "No active version: no row"), mark active-version steps `R01`–`R03`, `H01`, `M02`, `W01`–`W02`, and `O01` `NOT_RUN` (`no_active_version`), and still execute `S01`, `S03`, `M01`, `M03`, `W03` (with synthetic `findingId` `4711` / synthetic lookup `192.0.2.10`), `L01`–`L02`, and `X01`.

---

## 2. Setup and local check helpers

Run once in a scratch directory that is deleted in step `X01`. Placeholders in angle brackets live only in your local shell and are never pasted into the build record.

```bash
WORK=$(mktemp -d)
adm() { NEXUS_CLI_HOME="$WORK/admin"  nexus-cli "$@"; }       # security_admin session (CLI-only steps that aiview cannot call)
aiv() { NEXUS_CLI_HOME="$WORK/aiview" nexus-cli "$@"; }       # aiview (role:replay_viewer) session
adm login <baseUrl> <securityAdminAccount> local              # password: NEXUS_CLI_PASSWORD or prompt
aiv login <baseUrl> <aiviewAccount> local
adm session > "$WORK/adm-session.json"
aiv session > "$WORK/aiv-session.json"
aiv zone-matrix-versions > "$WORK/versions.json"
aiv zone-matrix-policies > "$WORK/policies.json"

# Fill locally from versions.json ("state": "ACTIVE") and policies.json ("policies"[*]."id", preferring largest "rule_count"):
ACTIVE_VERSION_ID="<versionId>"
POLICY_ID="<policyId>"

# Build the synthetic >1 MiB body (1,048,646 bytes > 1,048,576-byte cap) for step L01 using 'a' padding only (no real data):
{ printf '{"revision":1,"statement":"DENY_ALL","severity":"MEDIUM","pad":"'; head -c 1048580 /dev/zero | tr '\0' 'a'; printf '"}'; } > "$WORK/oversize.json"
```

Read-only capture helpers (`K` helpers). Each command stores JSON inside `$WORK` or prints a single integer/token so relationships are checked locally without echoing policy names, zone names or addresses.

```bash
# K01  Full masked summary, single-policy filtered summary, violation sample, not-evaluable sample, and Overview limit=1 slice
aiv zone-matrix-summary > "$WORK/summary.json"
aiv zone-matrix-summary "$POLICY_ID" > "$WORK/summary-policy.json"
aiv zone-matrix-summary - VIOLATION 20 > "$WORK/summary-violations.json"
aiv zone-matrix-summary - NOT_EVALUABLE 200 > "$WORK/summary-not-evaluable.json"
aiv zone-matrix-summary - - 1 > "$WORK/summary-overview.json"

# K02  Count recent zone_matrix_zones_read audit entries in GET /audit-logs (security_admin only; bounded 100-row window)
adm api GET /audit-logs | grep -Ec '"action" *: *"zone_matrix_zones_read"'
```

---

## 3. Step tables

Record evidence strictly as `name=value` where `value` is a boolean, integer count, closed enum word, HTTP status code, duration (`ms` or `s`), or `MATCH` / `MISMATCH` / `NOT_EVALUABLE`.

### R — Durable run lifecycle (`ZONE_MATRIX_CONTRACT.md` §5.1, §9; `ZM-8`, `ZM-39`)

`POST /api/v2/zone-matrix/runs` (`zone-matrix-run`) requires `zone_matrix_evaluate` and refuses `aiview`, so `adm` triggers the run while `aiv` polls the masked summary (`GET /api/v2/zone-matrix/summary`). In `ZoneMatrixSummaryView`, each policy's `latest_run` carries `state` (`RUNNING`, `COMPLETE`, `FAILED`), `started_at`, and `finished_at` (compute `run_duration_ms` locally as `finished_at - started_at` in milliseconds; record `poll_duration_ms` from wall-clock poll start to `COMPLETE`). Pick the largest stored policy by `rule_count` so the immediate second submission in `R02` observes the in-flight run (`already_running=true`); if a small policy finishes before the second request arrives, `already_running` is legitimately `false` with a distinct `run_id`.

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| R01 | `adm zone-matrix-policies > "$WORK/adm-policies.json"`, pick opaque `POLICY_ID`, then run `adm zone-matrix-run "$POLICY_ID" > "$WORK/run1.json"` | Both exit `0` (`POST /api/v2/zone-matrix/runs` returns HTTP `202`); `run1.json` carries `"run_id"`, `"already_running"` (`false` when idle), and `"state"` (`RUNNING` or `COMPLETE`) | §5.1 (on-demand durable run on own bounded pool), §8.4 | `first_run_exit=0`, `first_state=<RUNNING\|COMPLETE\|FAILED>`, `first_already_running=<bool>` | |
| R02 | Immediately run `adm zone-matrix-run "$POLICY_ID" > "$WORK/run2.json"` a second time | Exits `0`; while the first run is still `RUNNING`, `"already_running"` is `true` and `"run_id"` equals `run1.json` (`second_same_run_id=true`, at most one `RUNNING` run per policy); if `run1` had already finished, `"already_running"` is `false` | §5.1, §9 (`ZM-39`: at most one running run per policy source) | `second_already_running=<bool>`, `second_same_run_id=<bool>`, `second_state=<RUNNING\|COMPLETE\|FAILED>` | |
| R03 | Poll `aiv zone-matrix-summary "$POLICY_ID" > "$WORK/summary-policy.json"` until the `policies[]` entry for `POLICY_ID` has `latest_run.state == "COMPLETE"` | `latest_run.state` reaches `COMPLETE`; `has_complete_run` is `true`; `latest_complete_run.counts` has integer keys `violation`, `compliant`, `not_evaluable`, `unknown`, `not_applicable`, `findings`; `run_duration_ms` (`finished_at - started_at`) <= `30000` | §5.1 (transactional `COMPLETE` + findings; screens read `COMPLETE` runs only), §9 | `terminal_state=<COMPLETE\|FAILED>`, `has_complete_run=<bool>`, `run_duration_ms=<n>`, `poll_duration_ms=<n>`, `counts_present=<bool>` | |

### S — Staleness, 24-hour PO interim threshold, and unknown-without-complete-run (`ZONE_MATRIX_CONTRACT.md` §5.9, §7.1, §7.2, Amendment 2026-11-23; `ZM-35`)

Per `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` (§1 item 1), the 24-hour (`86,400` s) snapshot `STALE` threshold is an **interim value set by the Product Owner, not a bank value** (`ZONE_MATRIX_CONTRACT.md` §6.2 item 9 remains an open bank input). Every surface that prints the threshold must label it as interim and never present it as confirmed by the bank.

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| S01 | Run `aiv zone-matrix-summary > "$WORK/summary.json"` and inspect `staleness_threshold` plus `?screen=zone_matrix` › Coverage (and Coverage strip › `Coverage details`) as `aiview` | `staleness_threshold.seconds == 86400`, `hours == 24`, `interim == true`, and `label` states it is the interim value set by the Product Owner (`2026-11-23`) until the bank answers, not the bank's value; Coverage tab and Coverage strip show the same interim note | §5.9, §6.2 item 9, Amendment 2026-11-23, `PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` §1.1 (`ZM-35`) | `threshold_seconds=86400`, `threshold_hours=24`, `threshold_interim=true`, `interim_label_shown=true` | |
| S02 | In `$WORK/summary.json` (`policies[]`) and on `?screen=zone_matrix` › Coverage and the Coverage strip as `aiview`, check `staleness.snapshot_age_seconds`, `staleness.snapshot_age_known`, and `staleness.verdict` | Every policy with a valid `collected_at` shows its snapshot evidence age (`snapshot_age_known=true` and `Snapshot age` rendered on screen; `UNKNOWN` if missing); every policy with `snapshot_age_seconds > 86400`, `LAST_RUN_FAILED`, or `NOT_ON_ACTIVE_VERSION` has `verdict="STALE"` | §5.9, §7.1, §7.2 (`ZM-35`) | `policies_total=<n>`, `age_known_count=<n>`, `current_count=<n>`, `stale_count=<n>`, `stale_verdict_consistent=<bool>` | |
| S03 | Check every policy in `policies[]` where `has_complete_run == false` (`latest_complete_run == null`) in `$WORK/summary.json` and on the Coverage tab as `aiview` (if all policies already have a `COMPLETE` run, verify zero `null` fields render as `0`) | Without a `COMPLETE` run, `collection_failures` is `null` in JSON and renders `UNKNOWN` on screen, `latest_complete_run` renders `UNKNOWN`, and `Coverage` prints `No complete run: coverage is not known` — never `0` and never `OK` | §5.9, §7.1, §7.2 (`AGENTS.md` UNKNOWN / fail-closed law) | `unrun_policies_count=<n>`, `unrun_policy_zero_or_ok_count=0` | |

### H — Headline honesty and BDDK wording guard (`ZONE_MATRIX_CONTRACT.md` §1, §6, §7.2; `ZM-26`, `ZM-30`)

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| H01 | Run `aiv zone-matrix-summary > "$WORK/summary.json"` and `aiv zone-matrix-summary - NOT_EVALUABLE 200 > "$WORK/summary-not-evaluable.json"`, and inspect `?screen=zone_matrix` (Violations, Coverage, Coverage strip) and `?screen=overview` as `aiview` | Count policies where `latest_complete_run.counts.not_evaluable > 0` (and check the fleet `headline` / `staleness_headline` when `headline.not_evaluable_findings > 0`): zero policies or headline surfaces show a bare `"0"` headline or green `"ok"` chip while `not_evaluable > 0` (`staleness_headline.text` is `"at least N"`, and when `N=0`, `headline.text` is `"no proven violations in evaluated rules"`). Zero surfaces print `"BDDK compliant"`, `"BDDK pass"`, or a BDDK score/percentage | §1, §6, §7.2 (`ZM-26`, `ZM-30`) | `policies_with_not_evaluable=<n>`, `fleet_not_evaluable_findings=<n>`, `bare_zero_with_not_evaluable_count=0`, `forbidden_bddk_claim_count=0` | |

### M — Matrix, Violations, and Coverage tabs (`ZONE_MATRIX_CONTRACT.md` §7.1, §8.2; `ZM-10`, `ZM-26`, `ZM-34`, `ZM-35`)

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| M01 | Signed in to the browser as `aiview`, open `?screen=zone_matrix` and switch through all six tabs (`Matrix`, `Violations`, `Zones`, `Versions`, `Evaluate`, `Coverage`) | The `Zone matrix coverage` strip (`CoverageStrip`) stays visible above the tab bar on every tab; zone names are pseudonymised (`ZONE-1`, `ZONE-2`, ...), policy/rule names are masked, and raw CIDR blocks, address lookup and witness buttons are absent | §7.1 ("Coverage on every tab"), §8.2 (`ZM-10`, `ZM-33`) | `coverage_strip_on_all_tabs=true`, `aiview_names_masked=true`, `aiview_raw_controls_count=0` | |
| M02 | Capture `aiv zone-matrix-version "$ACTIVE_VERSION_ID" > "$WORK/active-version.json"` and `aiv zone-matrix-summary > "$WORK/summary.json"`, and compare against the `Matrix`, `Violations`, and `Coverage` tabs on screen for the same `evaluated_at` | `Matrix` tab shows active-version statements (shape + text) and per-cell open violation counts matching `cell_counts` (`"at least"` when `cell_counts_exact=false`); `Violations` tab headline, per-cell counts, `total_findings` / `returned` (`+N more` if `capped`), and all 11 §7.1 filters (`outcome`, `source zone`, `destination zone`, `severity`, `vendor`, `policy source`, `device`, `flag`, `reason`, `first observed`, `state`, `staleness`) match `summary.json`; `Coverage` tab counts (`policies.length`, `CURRENT`/`STALE`, `COMPLETE`/`INCOMPLETE`, `not_evaluated.length`) match `summary.json` | §7.1, §7.2 (`ZM-20`, `ZM-26`, `ZM-34`, `ZM-35`, `ZM-36`) | `matrix_counts_match=<MATCH\|MISMATCH>`, `violations_counts_match=<MATCH\|MISMATCH>`, `coverage_counts_match=<MATCH\|MISMATCH>`, `filters_present_count=11` | |
| M03 | Across `Matrix`, `Violations`, and `Coverage` tabs as `aiview`, inspect all `NOT_EVALUABLE`, `UNKNOWN`, `BECAME_UNDECIDED`, and `not_evaluated` (`ASA` / `FGT`) entries | `NOT_EVALUABLE` renders with warning tone (`Not evaluable`), `UNKNOWN` renders as `Outcome unknown` / `UNKNOWN` chip, `BECAME_UNDECIDED` stays open and highlighted, and `ASA`/`FGT` sources render under `Policies not evaluated` (`not evaluated: policy not collected`) — none is ever shown as `OK`, `COMPLIANT`, `0`, or green | §5.4, §5.8, §5.9, §7.1 (`ZM-4`, `ZM-34`) | `undecided_shown_as_ok_count=0`, `not_evaluated_sources_count=<n>` | |

### W — Audited witness read, synthetic lookup audit, and `aiview` refusal (`ZONE_MATRIX_CONTRACT.md` §3.4, §7.1, §8.1, §8.4; `ZM-33`)

Positive reads of `zone-matrix-finding-witness` and `zone-matrix-lookup` require `zone_matrix_zones_read` and are executed **only** in the `security_admin` session (`adm`). Inspect `$WORK/witness.json` locally to verify structure; never copy `source_range` or `destination_range` out of `$WORK`.

*Audit-count note:* `K02` (`adm api GET /audit-logs`) reads the 100 most recent `audit_log` events (`AuditLogQueryService.recent()`), where `tr_audit_zone_matrix_access_log` (`V216` / `V228`) writes `action_id = 'zone_matrix_zones_read'` on every `WITNESS_VIEW`, `ADDRESS_LOOKUP`, or `BLOCKS_VIEW` insert into `zone_matrix_access_log`. If an authorized PO session also wants the exact `WITNESS_VIEW` table count independent of the 100-row window, `SELECT COUNT(*) FROM public.zone_matrix_access_log WHERE kind = 'WITNESS_VIEW';` inside `BEGIN TRANSACTION READ ONLY; ... ROLLBACK;` selects `COUNT(*)` only (never rows or actor strings) and gives the identical delta of `1`.

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| W01 | Run `adm zone-matrix-summary - VIOLATION 20 > "$WORK/adm-violations.json"`, pick one opaque integer `FINDING_ID` (`finding_id`, digits without leading zero; verify `"witnesses_included": false` and zero `"witness"` keys in `adm-violations.json`), take baseline `K02` (`audit_before`), then run `adm zone-matrix-finding-witness "$FINDING_ID" > "$WORK/witness.json"` | `zone-matrix-finding-witness` exits `0` (HTTP `200`, `Cache-Control: no-store`); `witness.json` has `"witnesses_included": true`, `"audited": true`, `"cells_total"` >= `1`, `"witnesses_stored"` >= `1`, and `cells[].witness` with `source_range`, `destination_range`, `protocol`, `port`, `service` (if a finding stored no witness on any cell, HTTP `409` `WITNESS_NOT_STORED` is returned — pick one with a stored witness) | §3.4, §7.1, §8.1 (`ZM-33`: summary carries no witness; witness read returns stored ranges) | `summary_witnesses_included=false`, `witness_exit=0`, `witnesses_included=true`, `audited=true`, `witnesses_stored=<n>` | |
| W02 | Immediately take `K02` again (`audit_after_witness`); then take `K02`, run `adm zone-matrix-lookup 192.0.2.10 > "$WORK/lookup.json"` (RFC 5737 synthetic documentation address only), and take `K02` again (`audit_after_lookup`) | `audit_after_witness - audit_before == 1` (one `WITNESS_VIEW` audit row written before the response); `zone-matrix-lookup 192.0.2.10` exits `0` and `audit_after_lookup - audit_after_witness == 1` (one `ADDRESS_LOOKUP` audit row written) | §3.2, §3.4, §3.5, §8.1 (`ZM-33`, `ZM-37`) | `witness_audit_delta=1`, `lookup_exit=0`, `lookup_audit_delta=1` | |
| W03 | As `aiview`, run `aiv zone-matrix-finding-witness "$FINDING_ID"` and `aiv zone-matrix-lookup 192.0.2.10`, then check `K02` (`audit_after_aiview`) | Both `aiv` commands fail with CLI exit `3` and stderr `HTTP 403` (`READ_ONLY_PERSONA`), emit zero `"cells"`, `"witness"`, or zone-block fields, and `audit_after_aiview - audit_after_lookup == 0` | §3.4, §8.2, §8.4 (`ZM-10`, `ZM-33`: `aiview` never receives witness or lookup data) | `aiview_witness_http_status=403`, `aiview_witness_exit=3`, `aiview_lookup_http_status=403`, `aiview_lookup_exit=3`, `aiview_audit_delta=0` | |

### O — Overview attention row (`ZONE_MATRIX_CONTRACT.md` §7.2; `ZM-26`, `ZM-35`, `ZM-36`)

**Engineering-default note (not a PO decision).** The Overview attention-row severity mapping in `ui2/frontend/src/screens/overview/attentionRules.ts` (`critical` / `"Act now"` when `headline.critical_rules > 0`, `serious` / `"Review"` when other rules violate or findings were resolved by a version change in the last 7 days, `unknown` / `"UNKNOWN"` when zero violations are proven but staleness, incomplete coverage or undecided findings remain; never `"warning"`) is an engineering default awaiting Product Owner confirmation (backlog item `zone_matrix_attention_severity_po`), not a frozen contract rule.

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| O01 | Run `aiv zone-matrix-summary - - 1 > "$WORK/summary-overview.json"` and open `?screen=overview` in the browser as `aiview` | When `headline.distinct_violating_rules > 0` (or undecided/stale/incomplete/7-day-resolved items exist on an active version), one `"zone-matrix-violations"` row (`Open Zone matrix`) appears — never split into separate rows — matching `headline.text` (`"at least N"` unless `headline.exact=true`), splits (`critical_rules`, `covered_by_earlier_rule`, `conditional_rules`, `outside_headline_ordered_layer`), undecided/staleness/coverage counts, and 7-day version-change resolutions (`if distinct_violating_rules == 0` on every policy in the estate, record whether the row shows for undecided/stale items or `NOT_EVALUABLE` for the open-violation case) | §7.2 (`ZM-26`, `ZM-35`, `ZM-36`; severity mapping is engineering default awaiting PO) | `open_violating_rules=<n>`, `overview_row_present=<bool>`, `overview_headline_match=<MATCH\|MISMATCH>`, `overview_severity_shown=<Act_now\|Review\|UNKNOWN\|none>` | |

### L — Request size limit (`ZM-40`) and on-demand run authorization (`ZONE_MATRIX_CONTRACT.md` §3.1, §3.4, §8.4)

`ZoneMatrixRequestSizeFilter` enforces a `1,048,576`-byte (`1 MiB`) limit on every `POST`, `PUT`, `PATCH`, and `DELETE` under `/api/v2/zone-matrix/*`, refusing over-limit bodies with HTTP `413` (`ZONE_MATRIX_REQUEST_TOO_LARGE`) before Jackson parses a byte and storing nothing.

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| L01 | Run `adm zone-matrix-draft > "$WORK/draft-before.json"` (record `draft_exists` and `draft.revision`, or `0` if none), send the 1,048,646-byte payload via `adm zone-matrix-default-set "@$WORK/oversize.json"`, then run `adm zone-matrix-draft > "$WORK/draft-after.json"` | `zone-matrix-default-set` fails with CLI exit `1` and stderr `HTTP 413` (`ZONE_MATRIX_REQUEST_TOO_LARGE`); nothing of the body is echoed; `draft_exists` and `draft.revision` in `draft-after.json` equal `draft-before.json` (`draft_revision_delta=0`) | §3.1, §8.5 (`ZM-40`: oversize edit refused with `413`, never truncated, nothing stored) | `oversize_http_status=413`, `oversize_exit=1`, `draft_exists_unchanged=true`, `draft_revision_delta=0` | |
| L02 | Take `runs_before` on `POLICY_ID` from `$WORK/summary-policy.json` (`latest_run.run_id`), run `aiv zone-matrix-run "$POLICY_ID"` and `aiv zone-matrix-evaluate "$POLICY_ID"`, then re-run `aiv zone-matrix-summary "$POLICY_ID" > "$WORK/summary-after-aiv.json"` | Both `aiv` commands fail with CLI exit `3` and stderr `HTTP 403` (`READ_ONLY_PERSONA`); `latest_run.run_id` in `summary-after-aiv.json` is unchanged (`aiview_runs_started_delta=0`) | §3.4 (`zone_matrix_evaluate`), §8.4 (`aiview` never starts an evaluation or run) | `aiview_run_http_status=403`, `aiview_run_exit=3`, `aiview_evaluate_http_status=403`, `aiview_evaluate_exit=3`, `aiview_runs_started_delta=0` | |

### X — Cleanup (always, even after a FAIL)

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| X01 | `adm logout`; `aiv logout`; `rm -rf "$WORK"` | Both `logout` commands exit `0`; scratch directory holding local JSON captures (`witness.json`, `summary*.json`, `oversize.json`) is deleted | `AGENTS.md` Raw-evidence law | `adm_logout_exit=0`, `aiv_logout_exit=0`, `scratch_removed=true` | |

---

## 4. Result table for the build record

Paste one row per step in this exact shape; delete nothing and mark any skipped step `NOT_RUN` with its reason token. Never include a raw IP address, CIDR block, witness range, hostname, serial, policy/package name, rule name, rule body or username.

| Step | PASS / FAIL / NOT_RUN | Evidence tokens |
|---|---|---|
| PR1–PR6 | `<PASS\|FAIL>` | `pr1_adm=<bool>`, `pr2_aiview=<bool>`, `pr3_routes_live=<bool>`, `pr4_stored_policy=<bool>`, `pr5_active_version=<bool>`, `pr6_quiet_audit_window=<bool>` |
| R01 | `<PASS\|FAIL\|NOT_RUN>` | `first_run_exit=0`, `first_state=<RUNNING\|COMPLETE\|FAILED>`, `first_already_running=<bool>` |
| R02 | `<PASS\|FAIL\|NOT_RUN>` | `second_already_running=<bool>`, `second_same_run_id=<bool>`, `second_state=<RUNNING\|COMPLETE\|FAILED>` |
| R03 | `<PASS\|FAIL\|NOT_RUN>` | `terminal_state=<COMPLETE\|FAILED>`, `has_complete_run=<bool>`, `run_duration_ms=<n>`, `poll_duration_ms=<n>`, `counts_present=<bool>` |
| S01 | `<PASS\|FAIL\|NOT_RUN>` | `threshold_seconds=86400`, `threshold_hours=24`, `threshold_interim=<bool>`, `interim_label_shown=<bool>` |
| S02 | `<PASS\|FAIL\|NOT_RUN>` | `policies_total=<n>`, `age_known_count=<n>`, `current_count=<n>`, `stale_count=<n>`, `stale_verdict_consistent=<bool>` |
| S03 | `<PASS\|FAIL\|NOT_RUN>` | `unrun_policies_count=<n>`, `unrun_policy_zero_or_ok_count=<n>` |
| H01 | `<PASS\|FAIL\|NOT_RUN>` | `policies_with_not_evaluable=<n>`, `fleet_not_evaluable_findings=<n>`, `bare_zero_with_not_evaluable_count=<n>`, `forbidden_bddk_claim_count=<n>` |
| M01 | `<PASS\|FAIL\|NOT_RUN>` | `coverage_strip_on_all_tabs=<bool>`, `aiview_names_masked=<bool>`, `aiview_raw_controls_count=<n>` |
| M02 | `<PASS\|FAIL\|NOT_RUN>` | `matrix_counts_match=<MATCH\|MISMATCH>`, `violations_counts_match=<MATCH\|MISMATCH>`, `coverage_counts_match=<MATCH\|MISMATCH>`, `filters_present_count=<n>` |
| M03 | `<PASS\|FAIL\|NOT_RUN>` | `undecided_shown_as_ok_count=<n>`, `not_evaluated_sources_count=<n>` |
| W01 | `<PASS\|FAIL\|NOT_RUN>` | `summary_witnesses_included=<bool>`, `witness_exit=0`, `witnesses_included=<bool>`, `audited=<bool>`, `witnesses_stored=<n>` |
| W02 | `<PASS\|FAIL\|NOT_RUN>` | `witness_audit_delta=<n>`, `lookup_exit=0`, `lookup_audit_delta=<n>` |
| W03 | `<PASS\|FAIL\|NOT_RUN>` | `aiview_witness_http_status=<n>`, `aiview_witness_exit=<n>`, `aiview_lookup_http_status=<n>`, `aiview_lookup_exit=<n>`, `aiview_audit_delta=<n>` |
| O01 | `<PASS\|FAIL\|NOT_RUN>` | `open_violating_rules=<n>`, `overview_row_present=<bool>`, `overview_headline_match=<MATCH\|MISMATCH>`, `overview_severity_shown=<Act_now\|Review\|UNKNOWN\|none>` |
| L01 | `<PASS\|FAIL\|NOT_RUN>` | `oversize_http_status=<n>`, `oversize_exit=<n>`, `draft_exists_unchanged=<bool>`, `draft_revision_delta=<n>` |
| L02 | `<PASS\|FAIL\|NOT_RUN>` | `aiview_run_http_status=<n>`, `aiview_run_exit=<n>`, `aiview_evaluate_http_status=<n>`, `aiview_evaluate_exit=<n>`, `aiview_runs_started_delta=<n>` |
| X01 | `<PASS\|FAIL>` | `adm_logout_exit=0`, `aiv_logout_exit=0`, `scratch_removed=<bool>` |

Also record once, as plain facts: the deployed build/image digest or release tag, the date, `ui_persona=aiview`, and `executor=PO`.

**Rule.** Backlog items `zone_matrix_delivery` and `zone_matrix_step_4b2` stay `AUTOMATED_VALIDATED` until the Product Owner reports the completed run (alongside the in-pod scale measurement in section 3 of `docs/design/P1_COUNTS_ONLY_RUN_SHEET_2026_11_26.md` for `ZM-12` and the heap part of `ZM-39`, while step 6b-2 evidence-report wiring stays tracked under `zone_matrix_step_6b_evidence_report_wiring`). Any `FAIL`, any `MISMATCH`, or any required step left `NOT_RUN` (including `no_active_version` when PR5 is "No") keeps the items at `AUTOMATED_VALIDATED` and opens or retains the corresponding follow-up item. Automated validation (including `ZoneMatrixChecklistCliConformanceTest`) never substitutes for real-environment evidence, and recording the result table is input to — never by itself — the Product Owner's decision to advance status to `REAL_ENV_VALIDATED`.
