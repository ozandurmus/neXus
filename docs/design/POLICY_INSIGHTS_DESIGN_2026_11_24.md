<!-- Fable consultation 2026-11-24T08:56:18Z, model claude-fable-5-2; unaltered output -->

Reading is finished; the design document follows.

# Policy Insights: rulebase reorganisation advisor — design

Status: FROZEN (PO 2026-11-24, "Yes, accept all"; decisions in `PO_DECISION_RECORD_2026_11_24_POLICY_INSIGHTS.md`). Body below is Fable 5.2's unaltered draft; only this status line was changed.

Brief: `docs/design/POLICY_INSIGHTS_BRIEF_2026_11_24.md`. Masked names only. Sections 1-2 are verified repository facts; sections 3-10 are proposals; section 11 lists every UNKNOWN.

## 0. Answer in short

Yes: a stored package can be analysed and reorganised, and a useful part of the work needs no logs.

- **The PO's reorder idea is sound and provable.** A specific rule with zero hits is often unused only because an earlier broad rule with the same action takes its traffic. Moving it above the broad rule changes no verdict when no overlapping rule of a different action lies between the two positions. After the move the specific rules collect the hits, and the broad rule's counter shows what only it still carries.
- **The reorganisation is therefore staged:**
  1. promote specific rules, proven from the rulebase;
  2. observe the broad rule's residual traffic, by counters over time or by logs;
  3. narrow or remove the broad rule.
- **No percentage is invented.** Each insight carries an evidence grade from a closed vocabulary. The "X %" the PO asks for is a counted share per package: how many rules have an action proven from the policy alone, how many more could be decided with logs (an upper bound), and how many neither can decide, with reasons.
- **Logs raise only what logs can raise:** dated usage, narrowing of broad rules, and replay equivalence. They never prove a rule unused.

## 1. Verified repository facts

| # | Fact | Source |
| --- | --- | --- |
| F1 | One normalised model serves all four vendors: `PolicySnapshot` with sections, rules (four cells, action, log, extras, hit counts), objects and collection failures. It is "parsed management intent, never evidence that a target installed this policy". | `ui2/platform-core/.../policy/PolicySnapshot.java:8-9,167-211` |
| F2 | Hygiene classes are `shadowed, conflict, disabled, unused, expired, broad, unknown`. Shadow status is `REDUNDANT` (same action), `CONFLICT` (different action), `NOT_SHADOWED` or `UNKNOWN` with a reason from a closed set. | `ui2/service/.../policy/PolicyHygiene.java:26-39,716-726` |
| F3 | Shadowing is proven only when **one** earlier, enabled, fully resolved rule contains all four selectors. "No claim is made about partial overlaps"; "Rules are never reordered". | `PolicyHygiene.java:436-469`; `docs/design/POLICY_HYGIENE_W1_A_NOTES.md:5` |
| F4 | A rule the analysis cannot resolve stays a constraint on later rules (partial prior or earlier unknown); it is never dropped. | `PolicyHygiene.java:234-245` |
| F5 | Sound analysis treats these as UNKNOWN: negation, unresolved or dynamic objects, IPv6, user/category/time/content constraints, non-terminal actions. An upper-bound "legacy" mode that drops those constraints exists but is "Not wired to the policy screen yet". | `POLICY_HYGIENE_W1_A_NOTES.md:7`; `PolicyHygiene.java:114-123` |
| F6 | Budgets: 1,000,000 work units per rule, 200,000,000 per snapshot; the 10,000-rule runtime is pinned by a test. | `PolicyHygiene.java:71-80` |
| F7 | Permissiveness scoring is fixed by a PO record (Any source/destination/service 3 points each, large CIDR and broad port range 1 each). | `POLICY_HYGIENE_W1_A_NOTES.md:11` |
| F8 | Hit counters: Check Point from management (`show-hits true`; value, first/last date, vendor level; no per-gateway breakdown). Palo Alto per firewall and virtual system, summed only when every target answered, joined by rule name, ambiguous names not joined. | `ui2/worker/.../policy/PolicyHitCounts.java:55-106` |
| F9 | `HitCounts` has no reset or window-start field. Rule creation time is only an upper bound for the window. Zero hits is worded "counter reset/window is unknown". | `PolicyHygiene.java:750-775`; `POLICY_HYGIENE_W1_A_NOTES.md:13` |
| F10 | Hit counters are excluded from rule history ("Operational counters are not policy configuration revisions"). I found no counter time series. | `ui2/service/.../db/migration/V121__policy_hit_counts.sql:9-12` |
| F11 | Cisco ASA and FortiGate hit counts are not collected; the read (R1) was deferred by the PO. | `docs/design/ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md:112,236,255`; `PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md:29` |
| F12 | Object where-used counts are a lower bound. Zero is proven only when Check Point's own unused-object report confirms it. Duplicate objects are listed per domain. | `ui2/service/.../policy/PolicyDomainIndex.java:8-28,139` |
| F13 | `PolicyLookup` is a first-match evaluator for `CP, PAN, ASA, FGT`, covering Check Point ordered and inline layers, ASA in/global/out lists and security levels, and the FortiGate per-VDOM table. A query address may be a CIDR; the answer is then one verdict or `PARTIAL`. | `PolicyLookup.java:32-44,78-81,125-143,209-231,401-429` |
| F14 | Lookup limits: IPv4 and TCP/UDP only; "NAT, routing and anti-spoofing are not evaluated"; a rule with an application is undecided (`application constraint`); Check Point implicit cleanup "not collected"; Palo Alto default-rule overrides "not collected"; an incomplete snapshot is UNKNOWN for every query. | `PolicyLookup.java:11-14,112,252,296-299` |
| F15 | Cutover impact reads uploaded CSV rows once, keeps only tuples, counts and times, and decides each distinct tuple through `PolicyLookup`. Limits: 50,000 tuples, 8 files, 2,000,000 rows and 100 MB per file. Coverage codes include `LOGGING_COMPLETENESS_UNKNOWN`, `UNLOGGED_FLOWS_INVISIBLE`, `NAT_NOT_EVALUATED`, `APPLICATIONS_NOT_EVALUATED`, `INSTALL_STATE_NOT_VERIFIED`. | `CutoverAssessment.java:17-20,26-28,55-59`; `OfflineCsv.java:9-10` |
| F16 | Cutover takes one zone pair per run as operator input for Palo Alto, ASA and FortiGate. Security profiles are not compared anywhere. | `CutoverAssessment.java:34-35,277-289` |
| F17 | Rule object usage (FROZEN) attributes rows by the log's rule identity when present, and reports USED / NOT_SEEN / NOT_AUDITABLE / UNKNOWN per object. Vendor presets exist only after a measurement record of a sanitised sample. The Data Sanitizer cannot be a pre-step because it permutes addresses. | `docs/design/RULE_OBJECT_USAGE_CONTRACT.md` §3-6 |
| F18 | Uploaded files are never stored. Stored cutover tuples are purged after 90 days by an hourly job. A CHECK constraint forbids raw keys in stored JSON. | `V217__cutover_assessment.sql:32-34,47-48`; `CutoverFlowRetention.java:11-25` |
| F19 | aiview masking is an allowlist by field name: addresses go through the subnet-preserving masker, rule views through the policy pseudonyms, everything else is withheld. | `CutoverPrivacy.java:8-12,36-96` |
| F20 | Rule history is stored per rule, keyed by rule uuid or by source/section/name. `policy_revision` covers Palo Alto and Check Point only. | `V120__policy_rule_history.sql:2-30`; `V163__policy_completeness.sql:91-118` |
| F21 | Palo Alto profile settings and log settings are stored in rule extras. Check Point stores track fields, layer name and an `auto-generated` flag. | `PanoramaPolicyMapper.java:188,206`; `CheckPointPolicyMapper.java:139,190-192` |
| F22 | Roles: `POLICY_READ` to read; `POLICY_OFFLINE_WRITE` (security_admin, onboarding_admin) to upload and delete. Policy tabs today are Rules & Containers, Objects, Hygiene, Changes. | `ActionRegistry.java:30-32,384,400`; `ui2/frontend/src/screens/PolicyScreen.tsx:246` |
| F23 | A framed AES-GCM cipher for stored artefacts exists. | `ui2/platform-core/.../platform/ArtefactStoreCipher.java:48-50` |

## 2. What can be reused and what is missing

**Reusable as is**

- `PolicyNormalization` (match, partial, upper bound, containment, disjointness).
- `PolicyHygiene` verdicts and reasons, `PolicyRuleMetrics` breadth, `PolicyDomainIndex` object usage and duplicates.
- `PolicyLookup.prepare` as the replay evaluator.
- The cutover tuple reader, coverage vocabulary and retention pattern; the masking projection.

**Missing (new work)**

| # | Gap | Why it matters |
| --- | --- | --- |
| G1 | **Union cover and residual.** Today a rule is shadowed only by one earlier rule. Partial shadowing, "covered by several earlier rules together" and "what this broad rule still matches alone" need the effective region: `match(r)` minus the union of earlier matches. | Core of partial shadow, reorder and policy-only equivalence. |
| G2 | **Pairwise overlap relation** (disjoint / contained / overlapping) between two resolved rules, exposed as a result. Today only containment and disjointness are answered. | Reorder safety. |
| G3 | **Effect signature** per rule: action, inline-layer reference, profile references, log setting. Today only the action is compared. | A reorder can keep the verdict and still change inspection or logging. |
| G4 | **Counter samples over time.** No series exists (F10). | Gives a product-observed window without logs (section 3.3). |
| G5 | **Per-row zones and rule identity in replay**, and CIDR rows. | Whole-package replay needs each row's own zones; the cutover run takes one pair. |
| G6 | **A proposed rulebase as a snapshot**: an in-memory `PolicySnapshot` built from the stored one plus edits, which the same evaluator can read. | Proposal and equivalence. |
| G7 | **Stored log sets.** Rule object usage discards rows; a proposal that is edited and re-checked needs the tuples kept. | Needs a retention contract (section 5.4). |

## 3. Confidence model

### 3.1 Grades, not percentages

Each insight has one grade, set by a fixed table of conditions and never by a weighted score:

| Grade | Meaning | Typical basis |
| --- | --- | --- |
| `PROVEN` | Follows from set semantics over fully resolved rules in a rule-complete snapshot. | Full shadow, safe reorder, exact duplicate. |
| `SUPPORTED` | Proven on the policy side, plus dated runtime evidence with a known window and no known gap. | Zero hits across a measured window; not seen in logs with stated coverage. |
| `INDICATED` | Runtime evidence exists but its window or completeness is unknown; or an upper-bound analysis that ignored a constraint. | Zero hits, window unknown; IP-level shadow under a user rule. |
| `NOT_ESTABLISHED` | The policy shows a candidate but nothing shows it is safe. | "Remove broad rule" from the policy alone. |
| `NOT_EVALUABLE` | The analysis cannot read the rule; the reason is named. | Unresolved object, IPv6, incomplete snapshot. |

Every `PROVEN` carries the same caveat the product already states: it is proven for stored management intent, not for what is installed (`INSTALL_STATE_NOT_VERIFIED`).

### 3.2 The package numbers (the PO's "X %")

Three counts per package over enabled security rules, shown as counts first and a ratio second:

- **Decided from the policy alone:** rules with a resolved match and a shadow status other than UNKNOWN.
- **Could be decided with logs (upper bound):** the rules above, plus rules whose only blocking reasons are log-resolvable. Worded "up to", because it is known before any log exists only as a ceiling.
- **Not decidable by either:** listed by reason.

A blocking reason is classed once, in code:

| Class | Reasons | What raises it |
| --- | --- | --- |
| Log-resolvable | Counter window unknown; counters not collected (ASA, FortiGate); broad rule residual; user / time / application rules when the log names the rule | Traffic logs |
| Collection-resolvable | Snapshot incomplete; hit counts missing for a target; unused-object report not collected | A new collection |
| Not resolvable | Dynamic or FQDN objects; unresolved references; negation; IPv6; inline-layer verdict | Nothing in this design |

So "X + 60-70 %" becomes, for a real package, something like "34 of 100 rules decided now; up to 81 with six months of logs; 19 not decidable, for these reasons". The numbers are whatever the package gives.

### 3.3 Inputs and how each is treated

- **Counter age and resets.** Proposal: store one small sample per collection per rule (opaque rule key, hits, last hit, collected at). Two samples with a non-decreasing counter give a product-observed window; a decrease marks a reset and restarts the window. This turns "window unknown" into "no hit between T1 and T2", with no logs and no new device command. It is storage of a derived counter, so it needs PO approval (Q4).
- **HA members.** Palo Alto members are separate targets; the existing "every target answered" rule stays. How Check Point management aggregates cluster members into one value is UNKNOWN in the repository, so a Check Point zero is `INDICATED` until measured.
- **Log coverage.** The result shows:
  - the window in days;
  - weekly buckets with no rows at all, as gaps;
  - the share of rules whose stored log setting says they log (F21);
  - which targets appear in the export against the package's target list.

  Whether logging was complete is UNKNOWN unless the export proves it, as in the frozen contract.
- **Seasonality.** The window is described by calendar facts, not statistics: does it contain a month end, a quarter end, a year end. An insight on a rule not seen in a window without a year end says so.
- **No probability of "unused" is shown.** Any such figure assumes steady traffic, which seasonal jobs break.

## 4. Insight catalogue

"Policy" is the grade from the rulebase alone; "With logs" is the ceiling.

| Insight | Detection from the policy | Depends on | Policy | With logs | What the user does |
| --- | --- | --- | --- | --- | --- |
| **I1 Fully shadowed, same action** | Existing `REDUNDANT` | First match; resolved selectors | PROVEN | PROVEN (logs add nothing) | Remove. |
| **I2 Fully shadowed, different action** | Existing `CONFLICT` | Same | PROVEN that it never matches; intent is a human question | Same | Decide which rule is right; never auto-proposed. |
| **I3 Shadowed by several rules together** | G1: effective region empty | Same; budgeted | PROVEN or NOT_EVALUABLE (budget) | Same | Remove; the covering set is listed. |
| **I4 Partly shadowed / hidden by a broad rule** | G1: effective region smaller than the match; the earlier rule has the same action | Same | PROVEN (the overlap); it explains a zero counter | SUPPORTED (hits by the earlier rule inside the overlap) | Promote (I7). |
| **I5 Exact duplicate / merge candidate** | Same effect signature and placement; cells equal, or differing in one cell only; adjacent, or movable to adjacent by I7 | G3; no rule of another effect between | PROVEN | Same | Merge; the merged cell is the union. |
| **I6 Overly broad** | Existing permissiveness level and reasons | F7 scoring | PROVEN that it is broad; NOT_ESTABLISHED for narrowing or removal | SUPPORTED: observed flows of the residual, with coverage | Narrow or replace (section 6.2). |
| **I7 Safe reorder (promote)** | G2 + G3: moving rule r from position j up to i is safe when every rule between i and j that overlaps r has the same effect signature | Rule order is the only precedence in the scope; same scope and placement | PROVEN, or "verdict-safe, effect changes" with the changed region listed | Same; logs show how many observed flows change deciding rule | Apply as stage 1. |
| **I8 Disabled** | Existing | Stored enabled flag | PROVEN | n/a | Remove after the team's holding period. |
| **I9 Expired / expiring schedule** | Existing | Stored schedule; Check Point time may be uncollected | PROVEN or NOT_EVALUABLE | n/a | Remove or renew. |
| **I10 Zero hits / old last hit** | Existing `unused`, plus counter samples (3.3) | Counter semantics per vendor | INDICATED; SUPPORTED with a measured window on all targets | SUPPORTED: not seen in N days with stated coverage | Review; removal goes into the proposal marked as not provable. |
| **I11 Unused object** | Existing where-used and Check Point unused report | F12 | PROVEN only with the vendor report; else INDICATED lower bound | Object-level `NOT_SEEN` from Rule object usage | Remove object; tighten group. |
| **I12 Duplicate objects** | Existing | Same values, same type | PROVEN | n/a | Consolidate. |
| **I13 Unused member inside a used rule** | None from policy | — | NOT_ESTABLISHED | SUPPORTED (frozen contract states) | Tighten the cell. |

Rules:

- **I1, I3 and I7 are the only insights the engine may put into a proposal as "no behaviour change".** Everything else enters as "behaviour may change" with its grade.
- **"Equal or stricter profile" (brief 1.1) is reduced to "equal".** Equality of profile references is provable by identity; "stricter" is not provable from a profile name. A reorder that changes the profile is offered, marked "verdict-safe, inspection changes", and the user decides.
- **An upper-bound verdict (F5) is at most `INDICATED`** and names the dropped constraint.

## 5. Logs as booster

### 5.1 The export

One aggregated CSV per package and month. Required columns are today's (`source, destination, port, protocol`), plus `count`, `time` (first seen), and the optional `rule`, `action`, `sourceZone`, `destinationZone` (`OfflineCsv.FIELDS`). New: `lastSeen`, `application`, `device`.

**Field names, indexes and sourcetypes are UNKNOWN for this bank's Splunk.** The frozen contract forbids writing a vendor preset from general knowledge. The queries below are templates over the Splunk Common Information Model; each becomes exact only after a measurement record of one sanitised sample row per vendor. If the data model is not accelerated or not populated, the same `stats` runs on the vendor sourcetype.

Template, all vendors (data model form):

```
| tstats summariesonly=true count earliest(_time) as first latest(_time) as last
    from datamodel=Network_Traffic.All_Traffic
    where All_Traffic.dvc IN (<firewalls of this package>) earliest=<month start> latest=<month end>
    by All_Traffic.src All_Traffic.dest All_Traffic.dest_port All_Traffic.transport All_Traffic.app
       All_Traffic.rule All_Traffic.action All_Traffic.src_zone All_Traffic.dest_zone All_Traffic.dvc
| rename All_Traffic.* as *
```

Sizing query, run first; it returns one number and no customer data leaves Splunk:

```
| tstats summariesonly=true count from datamodel=Network_Traffic.All_Traffic
    where All_Traffic.dvc IN (<firewalls>) earliest=-6mon
    by All_Traffic.src All_Traffic.dest All_Traffic.dest_port All_Traffic.transport All_Traffic.rule
| stats count as distinct_tuples
```

Per-vendor expectations, each to be confirmed by its measurement record:

| Vendor | Rule identity expected in the log | Zones | Notes (all UNKNOWN until measured) |
| --- | --- | --- | --- |
| Check Point | A rule uid and layer per matched layer | None needed | Whether one log row carries every ordered layer's rule. |
| Palo Alto | Rule name; a rule uuid on newer versions | From and to zone | The stored hit join is by name; ambiguous names stay unattributed. |
| Cisco ASA | ACL name and line only for entries configured to log | Interface names | Connection messages may not name the ACL; then attribution falls back to the engine. |
| FortiGate | Policy id | Source and destination interface, VDOM | Which stored field the policy id maps to must be measured. |

### 5.2 Size for six months

Size follows distinct tuples per month, not log volume. Internal east-west rulebases repeat the same tuples; internet-facing rules do not.

- A row is roughly 120-160 bytes of CSV, so 2,000,000 rows is roughly 250-320 MB plain, above today's 100 MB limit, and gzip is needed. The frozen contract already plans `.csv.gz` counted on decompressed size and requires a memory measurement before raising the row limit.
- Six monthly files fit the 8-file limit.
- The 50,000-tuple limit is a cutover-scope limit and is too small for a whole package; the Insights limit must be set by measurement (Q6).
- For high-cardinality sources the export may collapse the source to a prefix for rows of rules whose source is `any`. The evaluator already answers a CIDR query with one verdict or `PARTIAL` (F13), so a collapsed row is never silently treated as exact.

### 5.3 Upload, attribution, rule changes

- **Path.** The existing multipart path and bulkhead, `POLICY_OFFLINE_WRITE`, one upload at a time. Rows are parsed in memory into tuples; the file is never stored.
- **Attribution.** As frozen in Rule object usage §4: the log's rule identity wins when it resolves to exactly one rule; the engine's verdict is compared, and agreements, disagreements and unknowns are counted and never reconciled. Without rule identity, the engine's deciding rule is used and undecided rows stay UNKNOWN.
- **Rule changes during the period.** Each monthly file is attributed against the rule set valid in that month, rebuilt from `policy_rule_history` (F20). A rule modified inside the window gets its usage split at the change and is flagged `RULE_CHANGED_IN_WINDOW`; only usage after the last change supports a narrowing. A rule identity in the log that matches no stored rule is counted as `RULE_NOT_IN_HISTORY`. History starts when the product first collected the package; earlier months are attributed against the oldest known rule set and flagged.

### 5.4 Storage, retention, purge (new contract needed)

Proposal:

- Store the **tuple set** per package and month, not the file: tuple, count, first/last seen, rule key, action, zones, device as an opaque reference.
- Encrypt it at rest with the existing artefact cipher (F23); keep only counts and coverage in plain JSON with the existing "no raw keys" CHECK.
- Retention 90 days from upload, the cutover precedent (F18), with the same hourly purge and a "tuples expired" answer instead of an empty result. Delete on demand by `POLICY_OFFLINE_WRITE`. Insights and proposals keep their counts after the purge and say that the evidence expired.
- aiview sees masked addresses and pseudonymised rule and zone names through an allowlist projection; it may not upload.
- Agents never read an export. Measurement uses sanitised samples; real-data checks are run by an authorised operator, the agent reading masked aggregates only.

This is retained traffic metadata, so it needs its own FROZEN contract before implementation (AGENTS.md raw-evidence law).

### 5.5 What logs cannot prove

- A rule is unused: only "not seen in this window with this coverage".
- Anything about rules that do not log, and flows dropped without logging.
- Implicit behaviour: Check Point implied rules and implicit cleanup, Palo Alto default rules unless logged, ASA and FortiGate implicit deny.
- Application and user rules on the policy side: the log can say which rule matched, but the engine cannot prove that a proposed rule would match the same session (F14).
- NAT: log addresses may be pre- or post-translation per vendor and field; UNKNOWN until measured.
- Traffic that did not occur: disaster-recovery paths, yearly jobs outside the window.

## 6. Reorganisation proposal

### 6.1 Ordering

Goal: make specific rules visible and broad rules measurable, not performance tuning.

1. Per evaluation scope, build the overlap relation between resolved rules (G2). A rule that is not fully resolved is a **fixed anchor**: nothing moves across it (the same fail-closed rule as F4).
2. For every I4 pair (specific rule below a broad rule of the same effect), try to promote the specific rule to just above the broad one. The move is accepted only if I7 holds for every rule crossed.
3. Rules never leave their scope: a Check Point layer or section, a Palo Alto pre / local / post phase, an ASA access list, a FortiGate table.
4. Each accepted move is recorded with its proof: the rules crossed and why each is disjoint or of equal effect.

Moves are applied one at a time against the current order, so the proof of a later move reads the result of the earlier ones.

### 6.2 Narrow rules from observed flows

Needs logs. For a broad rule B:

1. Take the tuples attributed to B after its last change, minus those that a promoted rule now decides.
2. **Reuse existing objects first.** For each cell, find stored objects and groups that contain the observed values, and prefer the smallest object that covers the most observed flows. Never create an object when an equal one exists (I12).
3. For values no object covers, aggregate to the smallest prefix or port set in which every address was observed, or use listed hosts. Aggregation never widens beyond B's own cell.
4. Group by destination and service first (the cutover "missing rule" grouping already works this way), then merge groups with equal source sets.
5. **Cap:** B may be replaced by at most N rules (default 10, per-proposal setting). Above the cap the engine stops and shows the residual as "too diverse to replace"; B stays.
6. The replacement keeps B's effect signature and position. B is then proposed as removed, or kept as a logged, temporary catch-all for one more window (Q5).

Every replacement is graded `SUPPORTED` at best and lists its window and coverage.

### 6.3 Merges and removals

- **Merge:** I5 only; same effect signature, same placement, adjacent after proven moves. The merged rule keeps the first rule's identity; names and comments are listed, not concatenated silently.
- **Remove with "no behaviour change":** I1 and I3.
- **Remove with "behaviour may change":** I8, I9, I10 and replaced broad rules, each with its grade.
- **I2 (conflict) is never proposed automatically.**

### 6.4 Equivalence check

The proposal is an in-memory `PolicySnapshot` (G6) with the same objects plus any proposed ones.

**Without logs (symbolic).** For each change, compute the region whose deciding rule differs between the old and new order, by interval-box arithmetic over source, destination, service and placement (G1). Result per change:

- `EQUIVALENT`: the region is empty or decided by rules of the same effect.
- `CHANGES`: the region is listed (ranges, old rule, new rule, old and new effect).
- `NOT_PROVEN`: with the reason — a crossed anchor, a rule with a user/application/time constraint, the budget, or an incomplete snapshot.

The package verdict is `EQUIVALENT` only if every change is.

**With logs (replay).** Each stored tuple is evaluated against the old snapshot and the proposal with `PolicyLookup.prepare`, with the tuple's own zones. Output per tuple: same deciding effect, changed (listed with both rules), or not decided on either side. The headline never says "safe"; it follows the cutover wording ("No changed flow among N evaluated flows. Coverage is incomplete: read the coverage list.").

The two checks are shown side by side. Replay is evidence about observed traffic; only the symbolic check covers traffic that was not observed.

## 7. Vendor specifics

| Vendor | Unit of analysis | Honoured today | Limits for Insights |
| --- | --- | --- | --- |
| **Check Point** | Policy package → ordered layers → sections → inline layers | Scope per parent rule; ordered layers must all allow; an inline layer is entered through its Apply Layer rule | Implied rules are not collected and cleanup is "not collected", so the proposal never touches or reasons about the last rule's fall-through. Moves stay inside one layer; sections are kept. A move across a section header is offered only as a user choice. Install-on must be equal. Access roles and time objects are anchors. Auto-generated rules are excluded. |
| **Palo Alto** | Device group chain: pre, local, post per target | Phase order; zone supersets; rule types; application-default as an opaque token | A rule never moves between pre and post, or between device groups (inheritance changes other firewalls). Application rules are anchors for symbolic proof. With logs they get usage by rule identity. Targeted rules are anchors. Profile group and log setting are part of the effect signature. |
| **Cisco ASA** | One access list per interface and direction, plus global | In, global, out order; security levels; an ACL with unknown use stays UNKNOWN | An ACL used by several bindings, or by a feature the parser does not read, is never reorganised. Object-group reuse is the main narrowing lever. No hit counters (F11), so I10 is NOT_EVALUABLE without logs. |
| **FortiGate** | One policy table per VDOM | Printed order is evaluation order | Policy id is identity; sequence is order. A proposal changes sequence and never ids, and the export is a move list (`move <id> before <id>`), not a renumbering. VIP and central NAT are outside evaluation. Zone intrazone fall-through is UNKNOWN. No hit counters. |

All four: NAT rulebases are stored but not analysed, and a proposal never changes them. Because NAT may reference the same objects, object removal (I11) is `INDICATED` wherever NAT references are not counted.

## 8. Screens

**Placement.** A fifth tab of the Policy screen, **Insights** (`?screen=policy&tab=insights`), per selected package. Hygiene stays the per-rule finding list; Insights is the per-package advisor and links into it. Rule object usage keeps its own screen and gains a link, "use this export for Insights".

1. **Package overview.** The three counts of section 3.2 as one stacked bar with counts on it. Below it, "what would raise this": one line per class (logs, a new collection, not resolvable), each with its rule count. Snapshot age and completeness on top.
2. **Insight list.** One row per insight: type, rules, suggestion in one sentence, grade, "raise to" (the grade reachable and what it needs). Filters by type and grade. CSV export through the masked projection.
3. **Insight detail.** The rules involved, side by side; the evidence (covering rules, overlap region, counter samples with window, log usage with coverage); the fixed caveats; "add to proposal".
4. **Proposal.** Old order left, new order right, moved rules connected, removed rules struck, new rules marked. Each change has its grade and its equivalence result. A summary row: rules before and after, changes by grade.
5. **Replay result.** The cutover result layout: headline, coverage list, changed flows first.
6. **Exports.**
   - Proposal as PDF or CSV for the change board.
   - A per-vendor change list in human-readable steps: move, merge, remove, add.
   - No device-ready command script in the first phases (Q7).

**Roles.** Read with `POLICY_READ`. Upload logs and delete log sets with `POLICY_OFFLINE_WRITE`. Create and edit proposals with a new `POLICY_INSIGHT_WRITE` action (security_admin, onboarding_admin). aiview reads everything masked (pseudonymised rule, object and zone names, masked addresses, closed vocabularies) and cannot upload or edit.

## 9. Phasing

| Phase | Content | Useful without logs | Gate |
| --- | --- | --- | --- |
| **P1** | Insights tab for **Check Point**: I1, I2, I8, I9, I10, I11, I12 from existing engines; package counts; grades. No new engine. | Yes | Contract frozen; render harness; aiview check on HOST-A. |
| **P2** | G1-G3: union cover, partial shadow, safe reorder (I3, I4, I5, I7), symbolic equivalence, reorder-only proposal. Check Point, then Palo Alto. | Yes — the PO's reorder case | Differential tests against the existing shadow engine; scale test at 10,000 rules. |
| **P3** | Counter samples (3.3), if approved. | Yes | Storage contract; one month of samples before any claim. |
| **P4** | Log intake: generic CSV first, stored tuple sets, attribution by rule identity, history-aware attribution, coverage, booster grades. | — | Retention contract FROZEN; memory measurement for limits; one sanitised sample and measurement record per vendor preset. |
| **P5** | Narrowing (6.2), full proposal, replay equivalence, exports. ASA and FortiGate join here; they have no counters and gain little before logs. | — | — |

Check Point is first because its counters are already collected with first/last dates, the layer model is evaluated, and the unused-object report exists. If the PO's first real package is a Palo Alto device group, swap the order; nothing in P1 is vendor-specific beyond wording.

**Real-environment validation** (automated tests do not replace it):

- **P1:** an operator opens one real package as aiview. The counts must equal the Hygiene tab's for the same snapshot. Ten insights are checked against the vendor console by the firewall team; result reported as MATCH / MISMATCH counts.
- **P2:** the firewall team reviews twenty proposed moves on one package and applies a few in their own change window. After the next collection the product must show the same rules, the new order, and hits moving from the broad rule to the promoted rules. That is the PO's hypothesis, measured.
- **P4:** one month of real export uploaded by an operator. Agreement rate between log rule identity and engine verdict is reported per vendor; a low rate blocks the preset.
- **P5:** one broad rule replaced on a non-critical package; replay before, counters after.

## 10. Open PO questions

**Q1. First vendor.** Recommendation: **Check Point** (reasons in section 9).
- Alternative: Palo Alto first. Pro: per-firewall counters with rule creation time. Con: App-ID rules reduce what is provable.

**Q2. Profile changes on reorder.** Recommendation: **offer the move, marked "verdict-safe, inspection changes", never inside "no behaviour change"**.
- Alternative: forbid such moves. Pro: simpler. Con: blocks most promotions on Palo Alto, where broad rules often carry a different profile group.

**Q3. Rules the engine cannot read (user, application, time).** Recommendation: **fixed anchors; nothing moves across them**.
- Alternative: use the upper-bound view to move across them at `INDICATED`. Pro: more suggestions. Con: a wrong move changes real traffic; this is where an advisor loses trust.

**Q4. Counter samples over time.** Recommendation: **yes**. A few numbers per rule per collection, no new device command; it turns the weakest evidence into a measured window and reduces how often logs are needed.
- Con: a new table and retention rule.
- Alternative: logs only.

**Q5. After narrowing, what happens to the broad rule?** Recommendation: **keep it as the last rule of its group with logging on for one more window, then remove**. Slower, but the residual is measured instead of assumed.
- Alternative: propose direct removal. Pro: fewer steps. Con: any flow outside the window breaks.

**Q6. Retention and limits for stored log tuples.** Recommendation: **90 days, encrypted, delete on demand; limits set after a memory measurement on one real export**.
- Alternative: no storage, re-upload for every check. Pro: no retained traffic data. Con: six files re-uploaded for each proposal edit.

**Q7. Export form.** Recommendation: **human-readable change list first; vendor command scripts later, as a separate decision**. A script is one step from a policy write, which is not approved, and a wrong generated command is worse than none.

**Q8. Engine for G1.** Recommendation: **interval-box arithmetic with a budget**, like the existing engines; exhaustion is `NOT_PROVEN`.
- Alternative: a decision-diagram or solver library. Pro: handles more complex shapes. Con: a new dependency and a second source of truth beside `PolicyNormalization`.
- Decide after P2 measures how often the budget is hit on real packages.

**Q9. Section and layer boundaries.** Recommendation: **never cross a layer; cross a section only as an explicit user choice**. Sections often mirror team ownership.

## 11. UNKNOWN and contradictions

- **Contradiction to report.** `PolicyHygiene.java:115` and `PolicyLegacyView.java` cite `POLICY_HYGIENE_MODES_AND_RESOLUTION_DESIGN_2026_11_13.md`; no such file exists under `docs/design/`. The upper-bound mode therefore has no readable contract; this design does not rely on it beyond `INDICATED`.
- **Stale figure.** `POLICY_HYGIENE_W1_A_NOTES.md:9` states a work budget of 250,000 units; `PolicyHygiene.java:80` uses 1,000,000 per rule and 200,000,000 per snapshot. The source is taken as current.
- **Check Point counter aggregation.** How management combines cluster members and when it resets counters is not established in the repository.
- **Splunk.** Index, sourcetype and field names at the bank, per vendor; whether the data model is accelerated; whether rule identity is present per vendor; NAT form of logged addresses.
- **Check Point implied rules and cleanup action.** Not collected.
- **Palo Alto default-rule overrides.** "Not collected" per the lookup wording; local default sections exist for non-Panorama firewalls.
- **`policy_revision` for ASA and FortiGate.** The table's vendor check allows Palo Alto and Check Point only; whether `policy_rule_history` is written for ASA and FortiGate snapshots was not verified.
- **Memory cost** of a whole-package tuple set, and the real distinct-tuple count for six months.
- **Database volume encryption** in this deployment (`PRIVACY_AND_DATA_HANDLING.md:153` says "expected").
- **Not read in this pass:** `CutoverAssessmentService.java`, `PolicyRuleMetrics.java`, `PolicyLegacyView.java`, the frontend beyond the tab list, and the ACI segmentation design. Claims above rest only on the files cited.
