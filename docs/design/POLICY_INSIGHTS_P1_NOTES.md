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

## Not in P1 (open)

- Insight list CSV export (design section 8.2); ASA/FortiGate/Palo Alto insights.
- Real-environment validation: counts vs the Hygiene tab on one real package as aiview, ten insights checked against the vendor console (design section 9).
