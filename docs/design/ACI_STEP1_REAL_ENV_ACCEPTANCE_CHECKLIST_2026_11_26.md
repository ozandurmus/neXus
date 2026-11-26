# ACI Step 1 — real-environment acceptance checklist, one segment (2026-11-26)

**Status: DRAFT -- checklist for the PO's real-environment run; not a contract; decides nothing.** Written 2026-11-26;
it changes no contract, decision record or project state. Backlog item `aci_segmentation_step1` stays
`AUTOMATED_VALIDATED` until the Product Owner reports the completed run (this checklist itself is not real-environment
evidence).

Controlling decision: `docs/design/PO_DECISION_RECORD_2026_11_14_ACI_SEGMENTATION_SUPPORT.md` (RATIFIED; answers Q1–Q10
and item 6 target-from-path clarification). Design guidance: `docs/design/ACI_SEGMENTATION_SUPPORT_DESIGN_2026_11_13.md`
(DRAFT — guides investigation only and is not implementation authority per `AGENTS.md` Contract-status law; all shipped
routes, CLI forms, field names and refusal codes below are verified from repository source:
`CutoverCli.java`, `PathTopologyCli.java`, `PolicyCli.java`, `CutoverAssessmentController.java`,
`CutoverAssessmentService.java`, `CutoverAssessment.java`, `CutoverFlowRetention.java`, `CutoverSegmentCaveat.java`,
`CutoverPrivacy.java`, `CutoverAcceptanceCriteriaTest.java` and `CutoverCliParityTest.java`).

**Acceptance criteria covered (`ACI-1`..`ACI-8` and `ACI-16`).** Every step below cites the `ACI-*` row or ratified PO
decision it exercises in the real environment. `ACI-9`..`ACI-15` are APIC and cutover-watch-view steps and are OUT of
this checklist: they wait for the bank's sanitized old-path export column-header sample and the first Phase 2 cutover
date (`PO_DECISION_RECORD_2026_11_14_ACI_SEGMENTATION_SUPPORT.md` item 5, Q4). Note on `ACI-3` (amended by PO decision
Q8 and commit `3ea02652`): Cisco ASA (`ASA`) and FortiGate (`FGT`) redirect targets with stored policies are evaluated
through `PolicyLookup` (`CutoverAssessment.LOOKUP_VENDORS`); only a vendor outside `LOOKUP_VENDORS` (or without
collected policy) yields `NOT_EVALUABLE`, and any PAN, ASA or FortiGate target missing an arrival or exit
zone/interface yields `NOT_DECIDED`, never an allow.

**What this run touches.** No device, no fabric, no APIC contact. Overwatch reads only an old-path traffic-log export
CSV file the Product Owner already holds locally, stored policy snapshots, and (in default routed-path mode) the held
L3 topology snapshot. Report the relationship, not the values (`AGENTS.md`, Sensitive identity reporting law and
Mandatory AIView inspection law): every evidence token recorded from this run is an integer count, a closed enum word, a
boolean, a duration, `MATCH` / `MISMATCH`, or an `aiview` masked pseudonym — never a raw IP address, CIDR block,
hostname, serial, rule name, column header, flow row or file content. All examples in this document are synthetic
(`SEG-ALPHA`, `FW-TANGO-04`, `192.0.2.0/24`, `198.51.100.0/24`, `2001:db8::/32`). The CLI executable is `nexus-cli`
(`overwatch-cli` is the same program under its new name).

---

## 1. Preconditions (tick each Yes / No)

Verify the deployed build directly through the CLI routes below before starting.

| # | Precondition | How to check (booleans and counts only) | Clause / Criterion | Yes / No |
|---|---|---|---|---|
| PR1 | Deployed build contains cutover migration `V217__cutover_assessment.sql` and `/api/v2/cutover/**` routes | `adm cutover-segments` and `aiv cutover-segments` (`GET /api/v2/cutover/segments`) both exit `0` and return `"segments"` plus `"canManage"` (`true` for `adm`, `false` for `aiv`) | `ACI-6`, `ACI-16` | |
| PR2 | Active write-capable admin session (`security_admin` or `onboarding_admin` for `cutover_assessment_write` and `policy_read`) and active `aiview` session (`role:replay_viewer` for masked reads and UI checks) | `adm session` and `aiv session` (`GET /session/status`) both exit `0`; browser is signed in exclusively as `aiview` (`?screen=cutover` and `?screen=trace&mode=path`) | `ACI-6`, `AGENTS.md` AIView law | |
| PR3 | Stored firewall policies exist, and for default routed-path evaluation (`PATH`) a held L3 topology snapshot exists | `aiv policy-devices` (`GET /api/v2/policy/devices`) exits `0` with stored snapshots; `aiv topology` (`GET /api/v2/topology`) exits `0` with a held snapshot (if no topology is held, rebuild it first on Trace or supply `targetOverride` in `spec.json`, which labels every result `OPERATOR_OVERRIDE`) | PO Q6/item 6, `ACI-1` | |
| PR4 | The Product Owner names ONE Phase 2 segment locally (segment name, canonical IPv4 CIDR `blocks`, canonical IPv4 CIDR `peerBlocks`, non-overlapping) | Kept in local shell variables only; only block counts and the `aiview` masked segment pseudonym enter the record — never raw blocks or the raw segment name | PO Q2, `ACI-6`, `ACI-7` | |
| PR5 | The Product Owner holds locally an old-path traffic-log export `.csv` file (UTF-8, non-empty, `<= 100 MB`, `<= 2,000,000` rows) and writes a local `spec.json` whose `mappings` match that file's column header | Bank's sanitized export column-header sample is still an open input (PO Q4): `spec.json` is written by the PO for the file in hand, stays in `$WORK`, and this checklist records only whether it parsed (`spec_parsed=<bool>`) | PO Q4, `ACI-5`, `ACI-7` | |

Stop at any "No" in PR1–PR5: the run cannot start.

---

## 2. Setup and local helpers

Run once in a scratch directory `$WORK` that is deleted at step X03. Placeholders in angle brackets are filled only in
the PO's local shell and never pasted into the build record.

```bash
WORK=$(mktemp -d)
adm() { NEXUS_CLI_HOME="$WORK/admin"  nexus-cli "$@"; }       # security_admin or onboarding_admin (create, assess, delete)
aiv() { NEXUS_CLI_HOME="$WORK/aiview" nexus-cli "$@"; }       # aiview / replay_viewer (masked reads and Trace check)
adm login <baseUrl> <adminAccount> local                      # password: NEXUS_CLI_PASSWORD or prompt
aiv login <baseUrl> <aiviewAccount> local
adm session > "$WORK/adm-session.json"
aiv session > "$WORK/aiv-session.json"
adm cutover-segments > "$WORK/segments-before.json"
aiv policy-devices > "$WORK/policy-devices.json"
aiv topology > "$WORK/topology.json"
```

Local helper functions (counts and opaque UUID extraction only; nothing raw printed):

```bash
# H01  Count stored segments (record baseline as SEG_BASE before S01)
seg_count() { adm cutover-segments | grep -o '"segmentId"' | wc -l; }

# H02  Extract opaque server-generated UUIDs from create responses
extract_segment_id() { grep -Eo '"segmentId" *: *"[^"]+"' | head -1 | cut -d'"' -f4; }
extract_assessment_id() { grep -Eo '"assessmentId" *: *"[^"]+"' | head -1 | cut -d'"' -f4; }
```

Synthetic shape of `$WORK/spec.json` (the PO writes `$WORK/spec.json` locally to match the real export file's column
headers; omit `targetOverride` for default routed-path evaluation, or populate `targetOverride` with `deviceId`,
`policyId`, and for `PAN`/`ASA`/`FGT` both `segmentZone` and `peerZone`; optional `oldPolicyId` / `oldDeviceId` adds
old-path rule comparison):

```json
{
  "mappings": [
    {
      "source": "src",
      "destination": "dst",
      "port": "port",
      "protocol": "proto",
      "time": "time",
      "count": "count",
      "action": "action"
    }
  ]
}
```

Synthetic shape of `$WORK/trace-in-segment.json` for step T01 (the PO substitutes one IPv4 host inside the segment's
own `blocks` and one peer IPv4 host locally in `$WORK`; never copy those addresses into the record):

```json
{
  "src": "192.0.2.8",
  "dst": "198.51.100.8",
  "protocol": "tcp",
  "port": 443
}
```

---

## 3. Step tables

Evidence is recorded strictly as `name=value` where `value` is a boolean, integer count, closed enum word, `MATCH` /
`MISMATCH`, or an `aiview` pseudonym. Never record raw IP addresses, CIDR blocks, hostnames, rule names, column headers
or flow rows.

### S — Segment creation, observed IPv6 refusal, and masked read

| Step | Action (CLI) | Expected observation | Criterion | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| S01 | Record `SEG_BASE` via `adm cutover-segments`; run `adm cutover-segment-create "<segName>" "<ipv4Blocks>" "<ipv4PeerBlocks>"` and capture `SEGMENT_ID`; then run `adm cutover-segments`, `aiv cutover-segments`, and `aiv cutover-segment "$SEGMENT_ID"` | Create exits `0` (HTTP `201`) with opaque `segmentId`; segment count equals `SEG_BASE + 1`; `revision` is `1`; in `aiv` responses `name`, `blocks` and `peerBlocks` are pseudonymised / subnet-masked (`CutoverPrivacy`) | PO Q2, `ACI-6`, `ACI-7`, `ACI-16` | `segment_created=<bool>`, `segments_delta_after_create=1`, `segment_revision=1`, `segment_block_count=<n>`, `peer_block_count=<n>`, `aiview_segment_masked=<bool>`, `masked_segment_label=<pseudonym>` | |
| S02 | Run `adm cutover-segment-create "zz-ipv6-probe" "2001:db8::/32" "198.51.100.0/24"`, then check `adm cutover-segments` | Server refuses with HTTP `400` (CLI exit `1`) and `"SEGMENT_BLOCK_INVALID: blocks takes IPv4 CIDR blocks such as 192.0.2.0/24"`; segment count stays `SEG_BASE + 1`. **Open PO question** (`aci_segmentation_step1.md`): record this refusal as an observed refusal (`PASS` when refused with `SEGMENT_BLOCK_INVALID`), **not** as a failure | `ACI-7`, `ACI-16` (open IPv6 question) | `ipv6_refusal_observed=<bool>`, `ipv6_error_code=SEGMENT_BLOCK_INVALID`, `segments_unchanged_after_ipv6=<bool>` | |

### A — Assessment upload, flow and missing-rule counts, parser rejections, and `NOT_DECIDED` fail-closed

| Step | Action (CLI) | Expected observation | Criterion | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| A01 | Run `adm cutover-assess "$SEGMENT_ID" "$WORK/spec.json" "$WORK/export.csv"` (pass `$WORK/export.csv` pointing to or symlinked to the PO's export file; up to 8 CSV files if `spec.json` has matching `mappings`), and capture `ASSESSMENT_ID` | Exits `0` (HTTP `201`) and returns `assessmentId`, `status` (`COMPLETE\|INCOMPLETE`), and `readiness` (`BLOCKERS_FOUND\|NOT_DECIDED\|NO_BLOCKERS_IN_EVALUATED_FLOWS\|NO_EVIDENCE`). CLI prints only the server JSON and never echoes file content (`CutoverCliParityTest`). If `spec.json` fails header/schema parsing, record `spec_parsed=false` and the fixed error code prefix only | PO Q4, `ACI-5`, `ACI-6`, `ACI-7`, `ACI-16` | `spec_parsed=<bool>`, `assessment_status=<COMPLETE\|INCOMPLETE>`, `readiness=<BLOCKERS_FOUND\|NOT_DECIDED\|NO_BLOCKERS_IN_EVALUATED_FLOWS\|NO_EVIDENCE>` | |
| A02 | Run `aiv cutover-assessment "$ASSESSMENT_ID" > "$WORK/assessment.json"` and inspect `counts`, `uploads`, `coverage` and keys locally | Exits `0`; zero raw-content keys (`rows`, `raw`, `file`, `files`, `csv`, `mapping`, `mappings`, `headers`, `header`, `content`, `line`, `lines`); `uploads[*]` holds only `index`, `rowCount`, `invalidRows`, `inScopeFlows`, `periodStart`, `periodEnd` and `parseStats` (`statReason` closed set: `column count mismatch`, `invalid count`, `invalid time (use ISO-8601 UTC or epoch seconds/milliseconds)`, `invalid IP address`, `invalid destination port`, `invalid protocol`, `invalid zone`) | `ACI-1`, `ACI-5`, `ACI-6`, `ACI-7`, `ACI-16` | `target_source=<PATH\|OPERATOR_OVERRIDE>`, `tuples=<n>`, `flows=<n>`, `already_dropped_tuples=<n>`, `missing_rule_groups=<n>`, `would_drop=<n>`, `not_decided=<n>`, `not_evaluable=<n>`, `would_allow=<n>`, `invalid_rows=<n>`, `parse_reason_classes=<csv_of_statReason:count\|none>`, `ipv6_flows_set_aside=<n>`, `non_tcp_udp_flows=<n>`, `raw_keys_count=0` | |
| A03 | Run `aiv cutover-flows "$ASSESSMENT_ID" FLOW - 0`, `aiv cutover-flows "$ASSESSMENT_ID" MISSING_RULE - 0`, `aiv cutover-flows "$ASSESSMENT_ID" FLOW WOULD_DROP 0`, `aiv cutover-flows "$ASSESSMENT_ID" FLOW NOT_DECIDED 0`, `aiv cutover-flows "$ASSESSMENT_ID" FLOW NOT_EVALUABLE 0`, and `aiv cutover-flows "$ASSESSMENT_ID" FLOW WOULD_ALLOW 0` (redirecting each into `$WORK`) | Every call exits `0`; `FLOW - 0` `total == counts.tuples`; `MISSING_RULE - 0` `total == counts.missingRuleGroups`; the four decision totals match `counts.<DECISION>` and sum to `counts.tuples`; addresses are subnet-masked; any flow with a missing zone or interface (PAN/ASA/FGT override without both zones/interfaces, or routed hop missing placement) is `NOT_DECIDED` (`zone or target required` on override), **never** `WOULD_ALLOW`; `CONDITIONAL_RULE` appears only on `NOT_DECIDED` flows. Copy zero flow rows into the record | `ACI-2`, `ACI-3` (amended by Q8), `ACI-4`, `ACI-5`, `ACI-6`, `ACI-7`, `ACI-16` | `flow_rows_total=<n>`, `missing_rule_rows_total=<n>`, `decision_totals_match=<MATCH\|MISMATCH>`, `not_decided_rows=<n>`, `missing_zone_or_if_never_allowed=<bool>`, `conditional_rule_only_on_not_decided=<bool>` | |

### V — Verdict honesty and 20-flow PO judgement sample

| Step | Action (CLI and `aiview` UI) | Expected observation | Criterion | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| V01 | Inspect `aiv cutover-assessment "$ASSESSMENT_ID"` and open `?screen=cutover&segment=<segmentId>&assessment=<assessmentId>` as `aiview` | `UNKNOWN` / `NOT_DECIDED` / `NOT_EVALUABLE` is never counted as covered or allowed: if non-dropped `WOULD_DROP == 0` and `openTuples > 0`, `readiness` is `NOT_DECIDED`, never `NO_BLOCKERS_IN_EVALUATED_FLOWS`. Even `NO_BLOCKERS_IN_EVALUATED_FLOWS` prints `"Coverage is incomplete: read the coverage list."` with `LOG_PERIOD`, `LOGGING_COMPLETENESS_UNKNOWN` and `UNLOGGED_FLOWS_INVISIBLE`, and zero occurrences of `safe`, `ready`, `pass`, `healthy`, `symmetric` or green status pills | `ACI-1`, `ACI-2`, `ACI-6` | `undecided_never_counted_covered=<bool>`, `coverage_non_empty=<bool>`, `forbidden_words_count=0`, `ui_aiview_masked=<bool>` | |
| V02 | Sample 20 evaluated flows (or all evaluated flows if `tuples < 20`) across the populated decision pages from A03; where needed inspect the cited policy via `aiv policy-rule "$POLICY_ID" <ruleId>` or `aiv policy-show "$POLICY_ID" 0`, and compare Overwatch's `decision` against the PO's judgement of the target policy | PO records `MATCH` or `MISMATCH` for each sampled flow below (`verdict_mismatch_count` = number of flows whose verdict the PO disagrees with). **No IP address, CIDR block, port, hostname or rule name is written anywhere** | `ACI-1`, `ACI-2`, `ACI-3` (amended by Q8), `ACI-4`, `ACI-6` | `sample_size=<n>`, `verdict_match_count=<n>`, `verdict_mismatch_count=<n>` | |

Record the 20-flow judgement sample using ordinal, closed decision enum and `MATCH` / `MISMATCH` only (never tuples or
rule names):

| Sample | Engine `decision` (`WOULD_DROP\|NOT_DECIDED\|NOT_EVALUABLE\|WOULD_ALLOW`) | PO judgement (`MATCH\|MISMATCH`) |
|---|---|---|
| F01 | | |
| F02 | | |
| F03 | | |
| F04 | | |
| F05 | | |
| F06 | | |
| F07 | | |
| F08 | | |
| F09 | | |
| F10 | | |
| F11 | | |
| F12 | | |
| F13 | | |
| F14 | | |
| F15 | | |
| F16 | | |
| F17 | | |
| F18 | | |
| F19 | | |
| F20 | | |

### R — Retention (`Q7`) and freshness (`ACI-8`)

*Source-verified field note (`CutoverAssessmentController.java`):* `cutover-assessment` (`GET /api/v2/cutover/assessments/{id}`)
returns `"stale": false` and `"staleReasons": []` for a fresh assessment (`ACI-8`), whereas the Q7 `flowsExpired` flag is
served on `cutover-flows` (`GET /api/v2/cutover/assessments/{id}/flows`) — omitted while flows are within retention so
`Boolean(flowsExpired)` is `false` (`"flowsExpired": true` count is `0`), and emitted as `"flowsExpired": true` with
`"retentionDays": 90` only when `total == 0` after the hourly janitor (`CutoverFlowRetention`) has deleted expired flow
rows. Both reads are checked below. The 90-day hourly deletion itself (bounded passes of 50 assessments and 50,000 rows,
verdict and summary counts kept) is proven by the PostgreSQL database test
`CutoverRepositoryDatabaseTest#flowsOlderThanNinetyDaysAreRemovedInBoundedPassesAndTheVerdictStays` (and
`CutoverFlowRetentionTest`) and is **not** re-run live.

| Step | Action (CLI and `aiview` UI) | Expected observation | Criterion | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| R01 | Run `aiv cutover-assessment "$ASSESSMENT_ID"` and `aiv cutover-flows "$ASSESSMENT_ID" FLOW - 0`; check the assessment page in UI as `aiview` | `cutover-assessment` reports `"stale": false` and `"staleReasons": []`; neither `cutover-assessment` nor `cutover-flows` contains `"flowsExpired": true` (`flowsExpired` is `false` / absent on a fresh assessment), and the UI shows the flow table rather than the `Flow rows were removed after 90 days` notice | PO Q7, `ACI-8`, `ACI-16` | `flows_expired=false`, `stale=false`, `stale_reasons_count=0` | |

### T — Trace caveat (`Q9`) and Cutover flow prefill link

| Step | Action (CLI and `aiview` UI) | Expected observation | Criterion | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| T01 | Write `$WORK/trace-in-segment.json` with one IPv4 host inside the stored segment's `blocks` (as `src` or `dst`), a peer IPv4 host, `"protocol": "tcp"` and `"port": 443`; run `aiv path-query "@$WORK/trace-in-segment.json" > "$WORK/trace.json"` | Exits `0`; `caveats` contains `"FABRIC_REDIRECT_NOT_MODELLED"` and `"plain"` is `false`, while zero segment names, segment ids or raw CIDR blocks appear in the `aiview` output (`CutoverSegmentCaveat`, `PathQueryCutoverCaveatTest`). Same caveat appears on `?screen=trace&mode=path` as `aiview` | PO Q9, `ACI-1`, `ACI-6`, `ACI-16` | `trace_caveat_present=<bool>`, `trace_plain=<bool>`, `segment_identity_leaked=false` | |
| T02 | On `?screen=cutover&segment=<segmentId>&assessment=<assessmentId>` as `aiview` (when `target_source=PATH`), click `Open Trace for this tuple (...)` on one flow row | Opens `?screen=trace&mode=path#tuple=<src>,<dst>,<tcp\|udp>,<port>` with `src`, `dst`, `protocol` and `port` prefilled from the URL fragment (never sent to the server in the query string) and **does not auto-run** (no result shown and zero `POST /api/v2/path-analysis/query` requests until Run is clicked). If `target_source=OPERATOR_OVERRIDE` (no path-flow link rendered), mark `NOT_RUN` (`reason=target_source_override`) or test prefill with a synthetic `#tuple=192.0.2.8,198.51.100.8,tcp,443` fragment | PO Q9, `ACI-6` (`traceTupleLink.ts`, `PathAnalysisScreen.tsx`) | `trace_link_prefilled=<bool>`, `trace_link_auto_ran=<bool>` | |

### X — Clean-up (always run unless the PO explicitly keeps the segment)

The validation run must not leave data behind unless the Product Owner explicitly chooses to keep the segment and
assessment (`kept_by_po=true`, in which case mark X01–X02 `NOT_RUN` with `reason=kept_by_po` and still run X03).

| Step | Action (CLI) | Expected observation | Criterion | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| X01 | Run `adm cutover-assessment-delete "$ASSESSMENT_ID"`, then `aiv cutover-segment "$SEGMENT_ID"` | Delete exits `0` (`{"deleted":true}`); `assessments` count on the segment is `0` | `ACI-16` | `assessment_deleted=<bool>`, `assessments_remaining=0` | |
| X02 | Run `adm cutover-segment-delete "$SEGMENT_ID"`, then `adm cutover-segments` | Delete exits `0` (`{"deleted":true}`); stored segment count returns to `SEG_BASE` (`segments_delta=0`, leaving `0/0` assessment/segment rows from this run) | `ACI-16` | `segment_deleted=<bool>`, `segments_delta=0` | |
| X03 | Run `adm logout` and `aiv logout`; then `rm -rf "$WORK"` | Both `logout` calls exit `0`; `$WORK` (`spec.json`, `trace-in-segment.json` and captured JSON) is removed | `AGENTS.md` Raw-evidence law | `logout_exit=0`, `scratch_removed=<bool>` | |

---

## 4. Result table for the build record

Paste one row per step in this exact shape; delete nothing and mark any skipped step `NOT_RUN` with its reason. No IP
address, CIDR block, hostname, serial, rule name, header name, user name or flow line belongs in this table.

| Step | PASS / FAIL / NOT_RUN | Evidence tokens |
|---|---|---|
| PR1–PR5 | `<PASS\|FAIL>` | `pr1_routes_live=<bool>`, `pr2_sessions_ready=<bool>`, `pr3_policies_and_topology=<bool>`, `pr4_one_segment_named=<bool>`, `pr5_local_spec_and_csv=<bool>` |
| S01 | `<PASS\|FAIL\|NOT_RUN>` | `segment_created=<bool>`, `segments_delta_after_create=<n>`, `segment_revision=<n>`, `segment_block_count=<n>`, `peer_block_count=<n>`, `aiview_segment_masked=<bool>`, `masked_segment_label=<pseudonym>` |
| S02 | `<PASS\|FAIL\|NOT_RUN>` | `ipv6_refusal_observed=<bool>`, `ipv6_error_code=SEGMENT_BLOCK_INVALID`, `segments_unchanged_after_ipv6=<bool>` |
| A01 | `<PASS\|FAIL\|NOT_RUN>` | `spec_parsed=<bool>`, `assessment_status=<COMPLETE\|INCOMPLETE>`, `readiness=<BLOCKERS_FOUND\|NOT_DECIDED\|NO_BLOCKERS_IN_EVALUATED_FLOWS\|NO_EVIDENCE>` |
| A02 | `<PASS\|FAIL\|NOT_RUN>` | `target_source=<PATH\|OPERATOR_OVERRIDE>`, `tuples=<n>`, `flows=<n>`, `already_dropped_tuples=<n>`, `missing_rule_groups=<n>`, `would_drop=<n>`, `not_decided=<n>`, `not_evaluable=<n>`, `would_allow=<n>`, `invalid_rows=<n>`, `parse_reason_classes=<csv_of_statReason:count\|none>`, `ipv6_flows_set_aside=<n>`, `non_tcp_udp_flows=<n>`, `raw_keys_count=<n>` |
| A03 | `<PASS\|FAIL\|NOT_RUN>` | `flow_rows_total=<n>`, `missing_rule_rows_total=<n>`, `decision_totals_match=<MATCH\|MISMATCH>`, `not_decided_rows=<n>`, `missing_zone_or_if_never_allowed=<bool>`, `conditional_rule_only_on_not_decided=<bool>` |
| V01 | `<PASS\|FAIL\|NOT_RUN>` | `undecided_never_counted_covered=<bool>`, `coverage_non_empty=<bool>`, `forbidden_words_count=<n>`, `ui_aiview_masked=<bool>` |
| V02 | `<PASS\|FAIL\|NOT_RUN>` | `sample_size=<n>`, `verdict_match_count=<n>`, `verdict_mismatch_count=<n>` |
| R01 | `<PASS\|FAIL\|NOT_RUN>` | `flows_expired=<bool>`, `stale=<bool>`, `stale_reasons_count=<n>` |
| T01 | `<PASS\|FAIL\|NOT_RUN>` | `trace_caveat_present=<bool>`, `trace_plain=<bool>`, `segment_identity_leaked=<bool>` |
| T02 | `<PASS\|FAIL\|NOT_RUN>` | `trace_link_prefilled=<bool>`, `trace_link_auto_ran=<bool>` |
| X01–X03 | `<PASS\|FAIL\|NOT_RUN>` | `assessment_deleted=<bool>`, `assessments_remaining=<n>`, `segment_deleted=<bool>`, `segments_delta=<n>`, `kept_by_po=<bool>`, `logout_exit=<n>`, `scratch_removed=<bool>` |

Also record once, as plain facts: the deployed build/image digest or release tag, the date, `read_persona=aiview`, and
`executor=PO`.

**Rule.** Backlog item `aci_segmentation_step1` stays `AUTOMATED_VALIDATED` until the Product Owner reports the
completed run. Any `FAIL` (note: S02 observing `SEGMENT_BLOCK_INVALID` on `2001:db8::/32` is the expected observed
refusal, not a `FAIL`), any unexplained `verdict_mismatch_count > 0` in V02, or any required step left `NOT_RUN`
(except T02 when `target_source=OPERATOR_OVERRIDE`, or X01–X02 when `kept_by_po=true`) keeps `aci_segmentation_step1`
at `AUTOMATED_VALIDATED` and opens a follow-up backlog item via `tools/delivery/project_queue.py`. Automated validation
(including the CLI conformance test pinning this checklist) never replaces real-environment evidence, and recording the
result table is evidence for — never by itself — the Product Owner's decision to advance `aci_segmentation_step1` to
`REAL_ENV_VALIDATED`.
