# Policy Insights — real-environment review checklist, phases P1 and P2a–P2c-1 (2026-11-26)

**Status: DRAFT -- checklist for the real-environment run; not a contract; decides nothing.** It changes no contract,
decision record or project state. Backlog item `policy_insights_p2` (and P1 real-environment validation) stays
`AUTOMATED_VALIDATED` until the Product Owner reports the completed run (this checklist itself is not real-environment
evidence).

Controlling documents: `docs/design/POLICY_INSIGHTS_DESIGN_2026_11_24.md` (FROZEN, PO 2026-11-24; sections 0, 3.1, 3.2,
4, 6.1, 6.4, 7 Check Point, 9 "Real-environment validation", and 10 Q2, Q3, Q7, Q8, Q9) and
`docs/design/PO_DECISION_RECORD_2026_11_24_POLICY_INSIGHTS.md` (RATIFIED). Implementation-scope notes:
`docs/design/POLICY_INSIGHTS_P1_NOTES.md` (DOCS, not a contract; sections "Insight rules", "Package counts (design
3.2)", "Not in P1 (open)", "P2a: G1 engine and union cover (I3)", "P2b: partial shadow (I4)", "P2c-1: safe reorder
(I7)", and "P2c-2b: reorder-only proposal (engine)"). Every step below cites the design/notes clause it proves. CLI
forms were copied from `ui2/cli/src/main/java/com/securityexpert/nexus/ui2/cli/PolicyCli.java` and `PolicyOpsCli.java`
on `origin/main` at `9cde5eca`; the executable is `nexus-cli` (`overwatch-cli` is the same program under its new name).

**What this run touches.** No device contact, stored policy only, `aiview` session (`role:replay_viewer`, `POLICY_READ`)
for all inspection steps (preconditions, P1-a, P1-b, P2-a, P2-c). Overwatch makes zero device writes (decision record
Q7). Only step P2-d involves a change on a firewall: the firewall team applies a few reviewed moves manually on their
vendor console inside their own approved change window (nothing automated), and after the next regular policy
collection completes, the reviewer runs read-only `aiview` CLI reads again to verify the updated snapshot and hit
counters. Report the relationship, not the values (`AGENTS.md`, Sensitive identity reporting law and Mandatory AIView
inspection law): every evidence token in this document is a boolean, a count, a ratio, a closed enum word, a masked
rule number (such as `#42`), or `MATCH` / `MISMATCH` / `NOT_CHECKABLE` — never a real hostname, IP address, serial,
rule name, object name, user name, comment or raw console output. All examples in this document are synthetic
(`FW-TANGO-04`, `192.0.2.0/24`).

**Scope check against `origin/main` (`9cde5eca`).** Verified before writing this checklist:
- `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyMerge.java` (I5 merge candidates) is
  **not** on `origin/main`. No I5 step is included.
- `SecurityWebMvcConfig.java` registers `GET /api/v2/policy/policies/*/insights` and
  `GET /api/v2/policy/policies/*/insight` (`ActionRegistry.POLICY_READ`), and **no** proposal route. `PolicyProposal`
  (P2c-2b) is engine-only in tests; step P2-c therefore reviews single-move `I7` insights against the stored order, and
  stacked-proposal review (`POLICY_INSIGHTS_P1_NOTES.md`, section P2c-2b) remains open until a proposal route ships.

---

## 1. Preconditions (tick each Yes / No)

Verify the deployed build carries phases P1, P2a, P2b and P2c-1 directly through the read-only routes below — never by
guessing from a tag name alone.

| # | Precondition | How to check (route and schema facts only) | Yes / No |
|---|---|---|---|
| PR1 | Active `aiview` CLI session (`role:replay_viewer`, masked view) | `aiv session` (`GET /session/status`) exits `0` and reports the `replay_viewer` / masked session | |
| PR2 | At least one stored Check Point policy package exists | `aiv policy-devices` (`GET /api/v2/policy/devices`) exits `0` and lists at least one package with `"vendor": "CP"` and an opaque `id` (`POLICY_ID`); UI equivalent: Policy screen (`?screen=policy&tab=insights`) as `aiview` | |
| PR3 | Deployed build includes **P1** (`policy-insights` and `policy-insight` routes and P1 schema) | `aiv policy-insights "$POLICY_ID" 0 - - 90` (`GET /api/v2/policy/policies/{id}/insights?page=0&days=90`) exits `0`, returns `"supported": true`, `"vendor": "CP"`, `"counts"` with keys `rules`, `decided`, `upToWithLogs`, `logResolvable`, `collectionResolvable`, `notResolvable`, `notReadable`, `waiting`, `hitsCollected`, `hitsMissing`, `disabledRules`, `outsideSecurityRules`, and `"byType"` containing `I1, I2, I8, I9, I10, I11, I12`; one sample `aiv policy-insight "$POLICY_ID" <insightId> 90` (`GET /api/v2/policy/policies/{id}/insight?insightId=...&days=90`) exits `0` with `insight`, `rules`, `objects`, `ruleObjects` | |
| PR4 | Deployed build includes **P2a**, **P2b** and **P2c-1** measurement blocks and types | The same `policy-insights` response contains `"byType"` keys `I3`, `I4`, `I7`, plus top-level objects `"unionCover"` (`evaluated`, `covered`, `notProven`, `notEvaluable`), `"partialShadow"` (`evaluated`, `partial`, `differentAction`, `notProven`, `notEvaluable`), and `"safeReorder"` (`evaluated`, `safe`, `safeInspectionChanges`, `unsafeDifferentEffect`, `anchorBlocked`, `crossesSection`, `notProven`, `notEvaluable`) | |
| PR5 | Stored snapshot for `POLICY_ID` is rule-complete | The same `policy-insights` response has `"complete": true` (P2a/P2b/P2c-1 evaluate nothing when `complete` is `false`, design §3.1) | |
| PR6 | `policy-hygiene` route responds for the same snapshot | `aiv policy-hygiene "$POLICY_ID" 0 - - 90` (`GET /api/v2/policy/policies/{id}/hygiene?page=0&days=90`) exits `0` and returns `"coverage"` (`rules`, `shadowProven`, `shadowUnknown`, `hitsCollected`, `hitsMissing`) and `"counts"` | |
| PR7 | Firewall team has read-only access to the vendor management console for the chosen Check Point package | Agreed with the firewall team; console values stay on the team's screen and are never pasted into this record | |

Stop at any "No" in PR1, PR2, PR3 or PR6: the run cannot start. If PR4 is "No", run P1-a and P1-b only and mark P2-a,
P2-c and P2-d `NOT_RUN` (`p2_not_live`). If PR5 is "No", pick a complete Check Point package or mark P2-a, P2-c and
P2-d `NOT_RUN` (`snapshot_incomplete`). If PR7 is "No", only P1-a can run; mark P1-b, P2-a, P2-c and P2-d `NOT_RUN`
(`no_console_reviewer`).

---

## 2. Setup and read-only helpers

Run once in a scratch directory that is deleted at the end (step X01). Placeholders in angle brackets are filled only in
your local shell and never copied into the build record. All CLI arguments in `PolicyCli` are positional; `-` skips an
optional positional argument so the server's default applies.

```bash
WORK=$(mktemp -d)
aiv() { NEXUS_CLI_HOME="$WORK/aiview" nexus-cli "$@"; }       # aiview (replay_viewer) session only
aiv login <baseUrl> <aiviewAccount> local                      # password: NEXUS_CLI_PASSWORD or prompt
aiv session > "$WORK/session.json"
aiv policy-devices > "$WORK/devices.json"
# Pick one stored Check Point package id (opaque UUID from devices.json; never paste its real name into a record):
POLICY_ID="<policyId>"
```

Read-only capture helpers (`H` helpers). Each command writes JSON inside `$WORK` so counts and closed-vocabulary fields
can be inspected locally without pasting raw payloads into chat or tickets.

```bash
# H01  Capture the package overview (page 0, 90-day unused threshold) and Hygiene coverage for the same snapshot
aiv policy-insights "$POLICY_ID" 0 - - 90 > "$WORK/insights.json"
aiv policy-hygiene "$POLICY_ID" 0 - - 90 > "$WORK/hygiene.json"

# H02  Capture page 0 of each P1 insight type (I1, I2, I8, I9, I10, I11, I12)
aiv policy-insights "$POLICY_ID" 0 I1 - 90 > "$WORK/i1.json"
aiv policy-insights "$POLICY_ID" 0 I2 - 90 > "$WORK/i2.json"
aiv policy-insights "$POLICY_ID" 0 I8 - 90 > "$WORK/i8.json"
aiv policy-insights "$POLICY_ID" 0 I9 - 90 > "$WORK/i9.json"
aiv policy-insights "$POLICY_ID" 0 I10 - 90 > "$WORK/i10.json"
aiv policy-insights "$POLICY_ID" 0 I11 - 90 > "$WORK/i11.json"
aiv policy-insights "$POLICY_ID" 0 I12 - 90 > "$WORK/i12.json"

# H03  Capture page 0 of P2a (I3), P2b (I4), and P2c-1 (I7 all and I7 PROVEN offered moves)
aiv policy-insights "$POLICY_ID" 0 I3 > "$WORK/i3.json"
aiv policy-insights "$POLICY_ID" 0 I4 > "$WORK/i4.json"
aiv policy-insights "$POLICY_ID" 0 I7 > "$WORK/i7.json"
aiv policy-insights "$POLICY_ID" 0 I7 PROVEN > "$WORK/i7-proven.json"
```

---

## 3. Step tables

Evidence is recorded strictly as `name=value` where `value` is a boolean, integer count, ratio, closed enum word or
`MATCH` / `MISMATCH` / `NOT_CHECKABLE`. Never record raw rule names, object names, IP addresses, serials or gateway
hostnames (synthetic example only: package on `FW-TANGO-04` with `192.0.2.0/24`).

### Step P1-a — Package counts versus Hygiene coverage (same snapshot)

Check both the internal `counts` invariants of `policy-insights` and agreement with `policy-hygiene` `coverage` on the
exact same snapshot (verify `collectedAt` did not change during the two reads).

Exact source-verified identities (`PolicyInsights.java`, `PolicyQueryService.java`):
1. **All-rule total**: `insights.counts.rules + insights.counts.disabledRules + insights.counts.outsideSecurityRules == hygiene.coverage.rules`.
2. **Enabled-security partition**: `counts.decided + counts.logResolvable + counts.collectionResolvable + counts.notResolvable == counts.rules`.
3. **Log ceiling**: `counts.upToWithLogs == counts.decided + counts.logResolvable`.
4. **Undecided split**: `counts.notReadable + counts.waiting == counts.rules - counts.decided` (`== counts.logResolvable + counts.collectionResolvable + counts.notResolvable`).
5. **Hit-counter split**: `counts.hitsCollected + counts.hitsMissing == counts.rules` (and `hygiene.coverage.hitsCollected + hygiene.coverage.hitsMissing == hygiene.coverage.rules`).
6. **Shadow-decided reconciliation**: `counts.decided` equals `hygiene.coverage.shadowProven` adjusted only by the two
   known scope differences when non-zero: `+ (I3 PROVEN subjects whose single-rule shadowStatus was UNKNOWN)` minus
   `(disabled security rules whose single-rule shadowStatus != UNKNOWN)`. Any difference outside those exact two terms is
   a `MISMATCH`.
7. **Finding-type totals**: `insights.byType.I1 == hygiene.counts.shadowed`, `insights.byType.I2 == hygiene.counts.conflict`,
   and on packages with no disabled NAT rules `insights.byType.I8 == hygiene.counts.disabled`.

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| P1-a1 | `aiv policy-devices` (select one Check Point `POLICY_ID`), then `aiv policy-insights "$POLICY_ID" 0 - - 90 > "$WORK/insights.json"` and `aiv policy-hygiene "$POLICY_ID" 0 - - 90 > "$WORK/hygiene.json"` | Both calls exit `0`, `"vendor": "CP"`, `"supported": true`; `collectedAt` unchanged; all seven count identities above hold | Design §3.2, §9 (P1); P1 notes "Package counts (design 3.2)", "Not in P1 (open)" | `counts_match=<MATCH\|MISMATCH>`, `mismatching_fields=<n>`, `enabled_rules=<n>`, `disabled_rules=<n>`, `outside_security_rules=<n>`, `decided=<n>`, `up_to_with_logs=<n>`, `log_resolvable=<n>`, `collection_resolvable=<n>`, `not_resolvable=<n>` | |

### Step P1-b — Ten P1 insights verified against the vendor console

Sample ten insights distributed across the seven P1 types (`I1`, `I2`, `I8`, `I9`, `I10`, `I11`, `I12`) from the
captured lists (`H02`). If a type has zero insights on the chosen package, record its count as `0` and draw the
remaining slots from the present P1 types so ten insights total are verified (or every P1 insight if fewer than ten
exist). Read each sampled insight's full detail via `policy-insight` (and optionally `policy-rule` for rule-scoped
insights) as `aiview`, then have the firewall team verify the claim against the Check Point management console.

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| P1-b1 | Run `H02` (`aiv policy-insights "$POLICY_ID" 0 I1 - 90` through `I12`) to pick ten `insightId` values across present P1 types; for each, run `aiv policy-insight "$POLICY_ID" <insightId> 90` (and when needed for cell detail, `aiv policy-rule "$POLICY_ID" <ruleId> 90`) | Every call exits `0`; names are pseudonymised in `aiview`; every `PROVEN` insight carries `INSTALL_STATE_NOT_VERIFIED`; every `I10` is `INDICATED` (window unknown in P1); firewall team records `MATCH`, `MISMATCH`, or `NOT_CHECKABLE` per insight below | Design §3.1, §4 (I1, I2, I8, I9, I10, I11, I12), §9 (P1); P1 notes "Insight rules", "Not in P1 (open)" | `p1b_checked=<n>`, `p1b_match=<n>`, `p1b_mismatch=<n>`, `p1b_not_checkable=<n>`, `p1b_types_covered=<n>` | |

Record the ten P1-b samples below. Use masked rule numbers only (e.g. `#42` covered by `#11`) or `domain (<n> objects)`
for `I11`/`I12` — never rule or object names.

| Sample | Type (`I1\|I2\|I8\|I9\|I10\|I11\|I12`) | `evidenceGrade` | Rule number(s) or domain object count (masked only) | Vendor-console verdict (`MATCH\|MISMATCH\|NOT_CHECKABLE`) |
|---|---|---|---|---|
| S01 | | | | |
| S02 | | | | |
| S03 | | | | |
| S04 | | | | |
| S05 | | | | |
| S06 | | | | |
| S07 | | | | |
| S08 | | | | |
| S09 | | | | |
| S10 | | | | |

### Step P2-a — Union cover (`I3`) and partial shadow (`I4`): measurements and 5 + 5 sample

Read the `unionCover` and `partialShadow` measurement blocks from `$WORK/insights.json`, record every counter and the
budget-hit share (decision record Q8), then sample up to five `I3` insights and up to five `I4` insights and verify each
against the vendor console as `aiview`.

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| P2-a1 | `aiv policy-insights "$POLICY_ID"` (or `$WORK/insights.json` from `H01`); read `unionCover` (`evaluated`, `covered`, `notProven`, `notEvaluable` by `unionReason`) and `partialShadow` (`evaluated`, `partial`, `differentAction`, `notProven`, `notEvaluable` by `partialReason`) | Both blocks present with integer counts; `notProven` budget-hit share computed as `notProven / evaluated` (`n/a` if `evaluated=0`); `notEvaluable` reasons are only closed words (`unsupported rulebase`, `snapshot collection incomplete`, `earlier sections not analysed`, `rule not resolved`, `opaque selector`) | Design §4 (I3, I4), §10 (Q8); P1 notes "P2a: G1 engine and union cover (I3)", "P2b: partial shadow (I4)" | `i3_evaluated=<n>`, `i3_covered=<n>`, `i3_not_proven=<n>`, `i3_not_evaluable=<n>`, `i3_budget_hit_share=<ratio\|n/a>`, `i4_evaluated=<n>`, `i4_partial=<n>`, `i4_different_action=<n>`, `i4_not_proven=<n>`, `i4_not_evaluable=<n>`, `i4_budget_hit_share=<ratio\|n/a>` | |
| P2-a2 | `aiv policy-insights "$POLICY_ID" 0 I3` and for up to five sampled `I3` insights `aiv policy-insight "$POLICY_ID" <insightId>` | Every call exits `0`; each `PROVEN` `I3` lists 2+ covering rule numbers, `coverAction` (`SAME\|DIFFERENT\|INLINE_LAYER`), and `INSTALL_STATE_NOT_VERIFIED`; firewall team checks each covering set on the vendor console | Design §4 (I3), §10 (Q3, Q9); P1 notes "P2a: G1 engine and union cover (I3)" | `i3_checked=<n>`, `i3_match=<n>`, `i3_mismatch=<n>`, `i3_not_checkable=<n>` | |
| P2-a3 | `aiv policy-insights "$POLICY_ID" 0 I4` and for up to five sampled `I4` insights `aiv policy-insight "$POLICY_ID" <insightId>` | Every call exits `0`; each `PROVEN` `I4` lists overlapping earlier rule numbers all of the same action and `INSTALL_STATE_NOT_VERIFIED`; firewall team checks each overlap on the vendor console | Design §4 (I4), §10 (Q3, Q9); P1 notes "P2b: partial shadow (I4)" | `i4_checked=<n>`, `i4_match=<n>`, `i4_mismatch=<n>`, `i4_not_checkable=<n>` | |

Record the `I3` and `I4` samples (rule numbers and counts only, never rule or object names):

| Sample | Type (`I3\|I4`) | `evidenceGrade` | Subject rule # | Covering / overlapping rule #s (and `coverAction` for `I3` or `overlappingRules` for `I4`) | Vendor-console verdict (`MATCH\|MISMATCH\|NOT_CHECKABLE`) |
|---|---|---|---|---|---|
| U01 | I3 | | | | |
| U02 | I3 | | | | |
| U03 | I3 | | | | |
| U04 | I3 | | | | |
| U05 | I3 | | | | |
| O01 | I4 | | | | |
| O02 | I4 | | | | |
| O03 | I4 | | | | |
| O04 | I4 | | | | |
| O05 | I4 | | | | |

Also record the `notEvaluable` breakdown counts (by closed reason word only):
- `unionCover.notEvaluable`: `not_resolved=<n>`, `opaque=<n>`, `order_unproven=<n>`, `incomplete=<n>`, `unsupported=<n>`
- `partialShadow.notEvaluable`: `not_resolved=<n>`, `opaque=<n>`, `order_unproven=<n>`, `incomplete=<n>`, `unsupported=<n>`

### Step P2-c — Twenty safe-reorder (`I7`) insights reviewed against the vendor console

Read the `safeReorder` measurement block and review twenty offered `I7` insights (`PROVEN`, `reorderResult` in `SAFE`
or `SAFE_INSPECTION_CHANGES`; or all offered if fewer than twenty exist on the package) as `aiview`. For every move,
record the crossed rules, the engine's proof words (`equivalence`, `crossedReason`, `inspectionChanges`), the firewall
team's vendor-console verdict, and the P2c-1 open question: **whether the Check Point access rule or any crossed rule
carries an inspection or logging setting the mapper does not collect** (boolean per move).

*Note on stacked proposals (`POLICY_INSIGHTS_P1_NOTES.md`, P2c-2b):* Because `PolicyProposal` has no HTTP route on
`origin/main` (`9cde5eca`), this step evaluates single-move `I7` insights against the stored order. Each detail view
states `"One move against the stored order; moves are not combined. No command is generated"`. A follow-up stacked
proposal check will be added once a proposal route ships.

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| P2-c1 | `aiv policy-insights "$POLICY_ID" 0 I7` and `aiv policy-insights "$POLICY_ID" 0 I7 PROVEN`; record `safeReorder` counts | `safe + safeInspectionChanges + unsafeDifferentEffect + anchorBlocked + crossesSection + notProven == evaluated`; budget-hit share `notProven / evaluated` recorded | Design §4 (I7), §6.1, §10 (Q2, Q3, Q8, Q9); P1 notes "P2c-1: safe reorder (I7)" | `i7_evaluated=<n>`, `i7_safe=<n>`, `i7_safe_inspection=<n>`, `i7_unsafe=<n>`, `i7_anchor_blocked=<n>`, `i7_crosses_section=<n>`, `i7_not_proven=<n>`, `i7_budget_hit_share=<ratio\|n/a>` | |
| P2-c2 | For twenty offered `I7` insights (mix of `SAFE` and `SAFE_INSPECTION_CHANGES` when both exist): `aiv policy-insight "$POLICY_ID" <insightId>` (and `aiv policy-rule "$POLICY_ID" <ruleId>` as needed for subject/broad/crossed cell checks) | Every call exits `0`; every `SAFE` move has `equivalence=EQUIVALENT` and `"no behaviour change"` evidence; every `SAFE_INSPECTION_CHANGES` move says `"verdict-safe, inspection changes"` and never `"no behaviour change"`; firewall team verifies each move and answers the uncollected-setting boolean in the table below | Design §4 (I7), §6.1, §6.4, §7 (Check Point), §9 (P2), §10 (Q2, Q3, Q7, Q9); P1 notes "P2c-1: safe reorder (I7)" open question | `i7_checked=<n>`, `i7_match=<n>`, `i7_mismatch=<n>`, `i7_not_checkable=<n>`, `i7_uncollected_setting_true_count=<n>` | |

Record the twenty `I7` moves below (rule numbers, closed proof words and booleans only; never rule names or addresses):

| Move | Subject # → above # | Crossed rule #s (`crossedRules` / `moreCrossed`) | `reorderResult` (`SAFE\|SAFE_INSPECTION_CHANGES`) | `equivalence` (`EQUIVALENT\|CHANGES\|NOT_PROVEN`) | `crossedReason` counts (`disjoint` / `same verdict effect`) | Any `inspectionChanges` (`true\|false`) | Team verdict (`MATCH\|MISMATCH\|NOT_CHECKABLE`) | Uncollected inspection/log setting? (`true\|false`) |
|---|---|---|---|---|---|---|---|---|
| M01 | | | | | | | | |
| M02 | | | | | | | | |
| M03 | | | | | | | | |
| M04 | | | | | | | | |
| M05 | | | | | | | | |
| M06 | | | | | | | | |
| M07 | | | | | | | | |
| M08 | | | | | | | | |
| M09 | | | | | | | | |
| M10 | | | | | | | | |
| M11 | | | | | | | | |
| M12 | | | | | | | | |
| M13 | | | | | | | | |
| M14 | | | | | | | | |
| M15 | | | | | | | | |
| M16 | | | | | | | | |
| M17 | | | | | | | | |
| M18 | | | | | | | | |
| M19 | | | | | | | | |
| M20 | | | | | | | | |

If `i7_uncollected_setting_true_count > 0`, note the count and open a backlog item to add the uncollected Check Point
setting class to `PolicyEffectSignature` before relying on `SAFE` ("no behaviour change"); do not paste raw rule or
profile names into the record.

### Step P2-d — Apply-and-recollect (firewall team's own change window; nothing automated)

This step tests the Product Owner's core hypothesis (design §0, §9 P2). Overwatch executes **no** change and generates
**no** device command (Q7). The firewall team selects a small subset of `MATCH` moves from P2-c (with
`uncollected_setting_present=false`) and applies them manually on SmartConsole during their own approved change window.
After traffic has passed and the next regular (or operator-authorized) policy collection finishes, verify the new stored
snapshot as `aiview`.

| Step | Action (CLI, as `aiview`) | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| P2-d1 | **Baseline before change window:** for each chosen promoted rule and its broad (`promoteAbove`) rule, run `aiv policy-rule "$POLICY_ID" <ruleId>` and `aiv policy-show "$POLICY_ID" 0`; record `collectedAt`, rule numbers and baseline `hits` locally in `$WORK` | All calls exit `0`; baseline `hits` and rule numbers captured for every rule to be moved and its broad rule | Design §9 (P2), §10 (Q7) | `applied_moves_planned=<n>`, `baseline_rules_recorded=<n>` | |
| P2-d2 | **Firewall team window (manual, outside Overwatch):** firewall team applies the chosen moves on the vendor console and installs policy per their own change control; after the next collection completes, check `aiv policy-sources` | `aiv policy-sources` exits `0` and shows a newer `collectedAt` timestamp after the change window | Design §9 (P2), §10 (Q7) | `applied_moves_count=<n>`, `collection_advanced=<true\|false>` | |
| P2-d3 | **Post-collection check:** re-run `aiv policy-show "$POLICY_ID" 0`, `aiv policy-rule "$POLICY_ID" <ruleId>` for each promoted and broad rule, `aiv policy-history "$POLICY_ID" <ruleId> 0`, and `aiv policy-insights "$POLICY_ID" 0 I7` | Same rules exist by opaque identity; stored rule order matches the applied order (each promoted rule now precedes its broad rule); hit counters moved from the broad rule to the promoted rules (if zero traffic matched during the window, record `NOT_EVALUABLE` rather than guessing) | Design §0, §9 (P2) | `same_rules_expected=<n>`, `same_rules_present=<n>`, `same_rules_match=<MATCH\|MISMATCH>`, `order_match_count=<n>`, `order_match=<MATCH\|MISMATCH>`, `promoted_with_hit_gain=<n>`, `promoted_hit_delta=<n>`, `broad_hit_delta=<n>`, `hits_moved_to_promoted=<true\|false\|NOT_EVALUABLE>` | |

### Step X — Cleanup (always, even after a FAIL)

| Step | Action | Expected observation | Clause | Evidence to record | PASS / FAIL / NOT_RUN |
|---|---|---|---|---|---|
| X01 | `aiv logout`; `rm -rf "$WORK"` | `logout` exits `0`; scratch directory removed; no raw or cached JSON left behind | `AGENTS.md` Raw-evidence law | `logout_exit=0`, `scratch_removed=true` | |

---

## 4. Result table for the build record

Paste one line per step in this exact shape; delete nothing and mark any skipped step `NOT_RUN` with its reason. No
hostname, IP address, serial, rule name, object name, user name or console line belongs in this table.

| Step | PASS / FAIL / NOT_RUN | Evidence tokens |
|---|---|---|
| PR1–PR7 | `<PASS\|FAIL>` | `pr1_aiview=<bool>`, `pr2_cp_package=<bool>`, `pr3_p1_live=<bool>`, `pr4_p2_live=<bool>`, `pr5_complete=<bool>`, `pr6_hygiene_live=<bool>`, `pr7_console_reviewer=<bool>` |
| P1-a1 | `<PASS\|FAIL\|NOT_RUN>` | `counts_match=<MATCH\|MISMATCH>`, `mismatching_fields=<n>`, `enabled_rules=<n>`, `disabled_rules=<n>`, `outside_security_rules=<n>`, `decided=<n>`, `up_to_with_logs=<n>`, `log_resolvable=<n>`, `collection_resolvable=<n>`, `not_resolvable=<n>` |
| P1-b1 | `<PASS\|FAIL\|NOT_RUN>` | `p1b_checked=<n>`, `p1b_match=<n>`, `p1b_mismatch=<n>`, `p1b_not_checkable=<n>`, `p1b_types_covered=<n>` |
| P2-a1 | `<PASS\|FAIL\|NOT_RUN>` | `i3_evaluated=<n>`, `i3_covered=<n>`, `i3_not_proven=<n>`, `i3_not_evaluable=<n>`, `i3_budget_hit_share=<ratio\|n/a>`, `i4_evaluated=<n>`, `i4_partial=<n>`, `i4_different_action=<n>`, `i4_not_proven=<n>`, `i4_not_evaluable=<n>`, `i4_budget_hit_share=<ratio\|n/a>` |
| P2-a2 | `<PASS\|FAIL\|NOT_RUN>` | `i3_checked=<n>`, `i3_match=<n>`, `i3_mismatch=<n>`, `i3_not_checkable=<n>` |
| P2-a3 | `<PASS\|FAIL\|NOT_RUN>` | `i4_checked=<n>`, `i4_match=<n>`, `i4_mismatch=<n>`, `i4_not_checkable=<n>` |
| P2-c1 | `<PASS\|FAIL\|NOT_RUN>` | `i7_evaluated=<n>`, `i7_safe=<n>`, `i7_safe_inspection=<n>`, `i7_unsafe=<n>`, `i7_anchor_blocked=<n>`, `i7_crosses_section=<n>`, `i7_not_proven=<n>`, `i7_budget_hit_share=<ratio\|n/a>` |
| P2-c2 | `<PASS\|FAIL\|NOT_RUN>` | `i7_checked=<n>`, `i7_match=<n>`, `i7_mismatch=<n>`, `i7_not_checkable=<n>`, `i7_uncollected_setting_true_count=<n>` |
| P2-d1 | `<PASS\|FAIL\|NOT_RUN>` | `applied_moves_planned=<n>`, `baseline_rules_recorded=<n>` |
| P2-d2 | `<PASS\|FAIL\|NOT_RUN>` | `applied_moves_count=<n>`, `collection_advanced=<bool>` |
| P2-d3 | `<PASS\|FAIL\|NOT_RUN>` | `same_rules_expected=<n>`, `same_rules_present=<n>`, `same_rules_match=<MATCH\|MISMATCH>`, `order_match_count=<n>`, `order_match=<MATCH\|MISMATCH>`, `promoted_with_hit_gain=<n>`, `promoted_hit_delta=<n>`, `broad_hit_delta=<n>`, `hits_moved_to_promoted=<true\|false\|NOT_EVALUABLE>` |
| X01 | `<PASS\|FAIL>` | `logout_exit=0`, `scratch_removed=<bool>` |

Also record once, as plain facts: the deployed build/image digest or release tag, the date, `persona=aiview`, and
`executor=PO+firewall_team`.

**Rule.** Backlog item `policy_insights_p2` stays `AUTOMATED_VALIDATED` until the Product Owner reports the run. Any
`FAIL`, any `MISMATCH`, any `i7_uncollected_setting_true_count > 0`, or any required step left `NOT_RUN` keeps the item
at `AUTOMATED_VALIDATED` and opens a follow-up item for that finding. Automated validation (including the CLI
conformance test pinning this checklist) never replaces real-environment evidence, and recording the result table is
evidence for — never by itself — the Product Owner's decision to advance to `REAL_ENV_VALIDATED`.
