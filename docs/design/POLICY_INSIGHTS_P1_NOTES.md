# Policy Insights P1: Check Point Insights tab — implementation notes

Status: DOCS (implementation notes, not a contract). Authority: `POLICY_INSIGHTS_DESIGN_2026_11_24.md` (FROZEN, PO 2026-11-24) and its decision record, section 9 phase P1 only. Masked names only.

## What P1 ships

- `GET /api/v2/policy/policies/{id}/insights?type&grade&page&days` and `GET .../insight?insightId=` (one wildcard per route, as the route tests require), `POLICY_READ`, GET-only, no device contact, nothing stored. A package of another vendor answers `supported=false`.
- `PolicyInsights` (service): reads `PolicyHygiene` verdicts and findings and the `PolicyDomainIndex` object rows. It runs no analysis of its own; no log intake, no reorder, no proposal (P2+).
- Policy tab **Insights** (`?screen=policy&tab=insights`), second tab of the Judge group: package counts, insight list with type/grade filters, detail view.

## Insight rules

| Type | Source in the existing engines | Grade |
| --- | --- | --- |
| I1 | `REDUNDANT` (one earlier rule covers all four selectors) | PROVEN |
| I2 | `CONFLICT` | PROVEN (never matches; intent is a human question) |
| I8 | `disabled` finding | PROVEN |
| I9 | `expired` finding; "Schedule evidence is incomplete" | PROVEN; NOT_EVALUABLE |
| I10 | `unused` finding (zero hits, old last hit) | INDICATED; SUPPORTED only with a measured window >= threshold |
| I11 | domain index `unused` (management report) / no stored reference and no report | PROVEN / INDICATED (lower bound) |
| I12 | domain index duplicate groups (equal type and values) | PROVEN |

- Every PROVEN insight carries `INSTALL_STATE_NOT_VERIFIED`.
- **SUPPORTED is not reachable in production in P1.** Stored hit counts carry no window start (design F9) and Check Point cluster aggregation is not established, so every I10 is INDICATED. `PolicyInsights.analyze` takes a per-rule measured window so the section 3.1 rule is one tested condition; phase P3 (counter samples) feeds it. Until then the service passes none.
- Missing or invalid counters (UNKNOWN findings) produce no I10; they are counted as `hitsMissing`.
- I11/I12 are domain-wide (scope `domain`): where-used counts span every package of the management domain.

## Package counts (design 3.2)

Over enabled security rules (NAT rulebase and disabled rules left out and reported as `outsideSecurityRules` / `disabledRules`, so the Hygiene tab's all-rule coverage is their sum with `rules`).

- `decided`: shadow status other than UNKNOWN.
- `upToWithLogs` = `decided` + rules whose only blocking reason is log-resolvable. A ceiling.
- The rest by reason, each reason classed once in `PolicyInsights.reasonClass`: LOGS (user/category, time/content, application overlap, application default ports), COLLECTION (snapshot incomplete, time/zone/install-on evidence not collected), everything else NOT_RESOLVABLE. A reason nobody classified is NOT_RESOLVABLE. A rule behind an earlier unreadable rule takes that rule's own reason.

## AIView

New response keys are projected by `PolicyPrivacy` through closed vocabularies (`insightType`, `evidenceGrade`, `insightNeeds`, `insightCaveats`, `insightScope`, `insightRole`, `reasonClass`); rule names go through the existing rule pseudonyms; evidence strings are `PolicyHygiene.EVIDENCE`.

## Detail panel and change list (PO 2026-11-25)

- A row (click, Enter, Space) opens the detail in a right-side drawer, full width on a small screen; Esc / Close returns focus to the row, which stays highlighted.
- The panel shows one headline sentence per type (I1, I2, I3, I4, I8, I9, I10, I11, I12), the involved rules side by side (every covering rule marked "Decides first", every overlapping earlier rule "Overlapping earlier rule", the shadowed rule "Never reached" or "Partly shadowed") and the suggested actions. Highlighted cells are the ones the engine's verdict rests on: position, source, destination, service / application, install-on and action for I1/I2/I3/I4 (when proven); the enabled flag for I8; the schedule for I9.
- `GET .../insight` carries one more key, `ruleObjects`: the objects the involved rules' cells and reference extras name (id, name, type, status; no members or values), the same projection the rule detail carries, masked by `PolicyPrivacy` the same way. No new route and no new action id.
- Actions by type: I1 remove / disable instead; I2 review intent / move above the covering rule (never a remove; the move is shown as not verdict-safe); I3 like I1 when PROVEN with `coverAction=SAME` (remove / disable instead with "No behaviour change"), or review intent / disable instead when a covering rule has a different action or calls an inline layer; I4 narrow rule / reorder above overlapping rules (never a one-click remove, always "Behaviour may change" until I7 verifies reorder safety); I8 remove; I9 remove / extend schedule; I10 disable for one window with logging on (Q5); I11 remove object; I12 merge. The grade never hides an action: PROVEN makes the strong action the main button, any other grade shows it with that grade's warning. Only a PROVEN I1 or PROVEN same-action I3 is labelled "No behaviour change" (design section 4).
- **No action writes to a device** (Q7). A button adds one line to the package's change list (one planned change per insight; another choice replaces it). The list is browser state (`localStorage`, key per package and per masked / unmasked view so names written by one view never show in the other), with a text and a CSV export.

## Not in P1 (open)

- Insight list CSV export (design section 8.2); ASA/FortiGate/Palo Alto insights.
- Real-environment validation: counts vs the Hygiene tab on one real package as aiview, ten insights checked against the vendor console (design section 9).

## P2a: G1 engine and union cover (I3)

Implementation notes, not a contract. Authority: the same FROZEN design (sections 3, 4 I3, 6.1, 6.4, 7, 9 phase P2) and decision record (Q3, Q8, Q9). Check Point only; steps b (partial shadow I4) and c (safe reorder I5/I7, reorder-only proposal) are not started.

- **G1 engine** `PolicyRegion` (service, pure). A box is the product of the four selectors the shadow engine resolves (source, destination, service, application). A dimension is a union of ranges per family, or everything except such a union (`any` = everything). Families are opaque keys; the universe holds every family, so a distinct key never makes a region smaller than it is. `residual(subject, earlier, budget)` returns the effective region "match minus the union of earlier matches" as disjoint boxes plus the earlier boxes that took part. It is PROVEN (exact) or NOT_PROVEN with "analysis budget". Opaque tokens (Palo Alto application-default and application identities) have no box.
- **I3** `PolicyUnionCover`. Subjects are enabled rules the shadow engine did not find REDUNDANT or CONFLICT.
  - Earlier rules come from the shadow engine's own `EvaluationScope`, so nothing crosses a layer (Q9).
  - Only readable rules with a compatible placement (same install-on) take part. Unreadable rules (user, application, time, unresolved) are fixed anchors (Q3): they never cover and are never subjects. They cannot weaken a proven cover.
  - COVERED needs two or more earlier rules. A set where one rule alone covers is I1/I2 and is left to the shadow engine.
  - A snapshot with collection failures evaluates nothing (design 3.1 "rule-complete").
  - Budgets: the shadow engine's (`RULE_WORK` 1,000,000 per rule, `TOTAL_WORK` 200,000,000 per snapshot).
- **Grades.** COVERED gives a PROVEN I3 that lists the covering set and carries `INSTALL_STATE_NOT_VERIFIED`. The action relation goes into the evidence string and `counter.coverAction`: `SAME`, `DIFFERENT` (intent is a human question, as for I2) or `INLINE_LAYER`. NOT_PROVEN gives an I3 graded NOT_EVALUABLE with `counter.unionReason` "analysis budget", never a cover.
- **Package counts are unchanged.** They stay the shadow engine's, so the P1 check "counts equal the Hygiene tab" still holds. An I3 on a rule the shadow engine left UNKNOWN is not counted as decided. This is open for a PO decision.
- **Routes and UI.** No new route, no new action id. `GET .../insights` adds `unionCover` (evaluated, covered, notProven, notEvaluable by reason) to measure how often the budget is hit (Q8). The service caches the I3 pass for each snapshot instance. The Insights tab gains the I3 type filter, a union-cover line, and detail with every covering rule and the action relation.
- **AIView.** New evidence strings are `PolicyInsights.EVIDENCE`; `coverAction` and `unionReason` are closed vocabularies in `PolicyPrivacy`.
- **Tests.**
  - `PolicyRegionDifferentialTest`: residuals against brute-force enumeration over a tiny universe; small budgets give NOT_PROVEN and never a wrong PROVEN.
  - `PolicyUnionCoverDifferentialTest`: random Check Point rulebases with anchors and disabled rules. I3 must equal the brute-force union cover exactly, and every single-rule cover must be REDUNDANT/CONFLICT in the shadow engine.
  - `PolicyUnionCoverTest`: fixtures for actions, anchors, layers, install-on, an incomplete snapshot, budget, route and AIView.
  - `PolicyUnionCoverScaleTest`: 10,000 rules, about 1.4 s warm on a laptop JVM, no budget hit.
- **Real-environment validation (open).** No real package has been analysed. The design's P2 check (the firewall team reviews proposed moves) comes with step c. Before then, a sample of I3 insights on one real package should be checked as aiview against the vendor console.

## P2b: partial shadow (I4)

Implementation notes, not a contract. Authority: the same FROZEN design (sections 3, 4 row I4, 7, 9 phase P2) and decision record (Q3, Q8, Q9). Check Point only. Step c (I5 merge, I7 safe reorder, the reorder-only proposal) is not started. Chosen as the next P2 step because no further self-contained P1 step is left.

- **`PolicyPartialShadow`** (service, pure) runs on the G1 engine of step a and shares `PolicyUnionCover.Earlier`, the earlier-rule bucketing (now package-private, not copied). Subjects are enabled, readable rules of a rule-complete snapshot that the shadow engine did not mark REDUNDANT or CONFLICT and that I3 did not find COVERED. Earlier rules are the readable rules of the same `EvaluationScope` with a compatible placement; nothing crosses a layer (Q9) and unreadable rules are fixed anchors that never take part (Q3). Budgets are I3's (`RULE_WORK`, `TOTAL_WORK`).
- **PARTIAL** needs an earlier rule whose box really shares a point with the subject (a span test alone is not enough) and a proven non-empty rest from `PolicyRegion.residual`. A rule the earlier rules cover wholly has no I4. The overlapping list is every intersecting earlier rule, including one another earlier rule already hides, in rule order.
- **Decided here: same action only.** The insight exists only when EVERY intersecting earlier rule has the subject's action. One intersecting rule with another action means a promote there could change a verdict (I7 territory): no I4, and the rule is counted in `partialShadow.differentAction`, never shown. An Apply Layer rule, on either side, is never the same action. Rules that do not intersect the subject do not matter.
- **Budget (Q8).** Running out of work is NOT_PROVEN ("analysis budget"), shown as an I4 graded NOT_EVALUABLE with `counter.partialReason`, never as a partial. If an earlier rule of a scope ran out of its own work window before it was read, a later subject of that scope that would be PARTIAL is NOT_PROVEN instead: the unread rule might cover it or have another action. A rule left unread for being unreadable (an anchor) is not such a gap.
- **Insight.** `I4`, PROVEN, scope "package", `INSTALL_STATE_NOT_VERIFIED` like I3. Rules: the subject, then the overlapping earlier rules (role `overlapping`), capped at `PolicyInsights.MAX_OVERLAPPING` (10); `counter.overlappingRules` is the total and `counter.moreOverlapping` the rest. The fixed evidence string says the overlap is proven and says nothing about moving, safety or behaviour. The Insights tab shows the fixed sentence "Part of this rule's match is decided by earlier rules with the same action; its zero or low counter can be explained by them."
- **Package counts are unchanged** (the shadow engine's), so the P1 check "counts equal the Hygiene tab" still holds. I4 is not counted as decided; this is the same open PO note as for I3.
- **Routes and UI.** No new route, no new action id, no device command, no migration. `GET .../insights` gains `partialShadow` (evaluated, partial = the I4 insights, differentAction, notProven, notEvaluable by reason) to measure how often the budget is hit. The service caches the I4 pass for each snapshot instance, as for I3. The Insights tab gains the I4 type filter, the measurement line and the detail.
- **AIView.** Nothing is free text: the evidence strings are `PolicyInsights.EVIDENCE`; `partialReason` (closed set shared with `unionReason`) and the role `overlapping` are closed vocabularies in `PolicyPrivacy`; the rest are references and counts.
- **Source-port families.** `PolicyRegion` treats two distinct family keys as disjoint, which is conservative for covers but not for overlaps. Source-port families exist only in the Palo Alto resolver, so this does not affect Check Point. It must be revisited before I4 is extended to Palo Alto.
- **Tests.**
  - `PolicyPartialShadowDifferentialTest`: random Check Point rulebases in one or two layers with anchors, disabled rules and mixed actions. The PARTIAL set, the overlapping lists and the same-action flag equal a brute-force enumeration exactly; small budgets give NOT_PROVEN and never a wrong PARTIAL; no I1/I2/I3 rule and no different-action overlap has an I4.
  - `PolicyPartialShadowTest`: fixtures for one and two overlaps, a hidden overlap, a different-action overlap, Apply Layer, anchors, layers, install-on, an incomplete snapshot, another vendor, the cap, budget, the route and AIView.
  - `PolicyPartialShadowScaleTest`: 10,000 rules, about 1.1 s warm on a laptop JVM, no budget hit.
- **Real-environment validation (open).** No real package has been analysed; nothing here is REAL_ENV_VALIDATED. The firewall team reviews a sample of I4 insights on one real package as aiview against the vendor console before the grade is relied on.
