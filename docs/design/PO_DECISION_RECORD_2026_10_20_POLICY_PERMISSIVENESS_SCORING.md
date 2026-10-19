# PO decision record — 2026-10-20: policy permissiveness scoring

**Status: RATIFIED — PO APPROVED (relayed by the coordinator session, 2026-10-20).** Recorded by the engineering
session (lane `lane/policy-hits-perm`) from the Product Owner's decisions as relayed; it amends the scoring paragraph of
[POLICY_HYGIENE_W1_A_NOTES.md](POLICY_HYGIENE_W1_A_NOTES.md), whose authority is
[PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md](PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md).

## 1. Context

PO report 2026-10-19: permissiveness did not look right. Under the W1-A model every Any selector, application included,
scored 2 points, so a Palo Alto rule with application `any` but a specific service, source and destination was Medium,
and every rule naming a predefined App-ID or service (not in the collected configuration) was Unknown.

## 2. Decisions

1. **Scoring table.**

   | Selector | Points |
   |---|---|
   | Any source | 3 |
   | Any destination | 3 |
   | Any service | 3 |
   | Any application | 1, but 0 when the service is specific (anything but Any service, including `application-default`) |
   | Large CIDR (IPv4 prefix <= 16), per field | 1 |
   | Broad service range (>= 1024 ports) | 1 |

   Levels: score 0 Low, 1–3 Medium, >= 4 High. An unassessable selector still makes the verdict Unknown irrespective of
   the score, and the reasons stay specific (fixed vocabulary, `PolicyRuleMetrics.FIXED_REASONS`).

2. **Palo Alto predefined objects.** A named, non-`any` Palo Alto application or service that is not in the collected
   configuration (a predefined App-ID or predefined service) counts as a specific selection, 0 breadth points, with the
   reason `Predefined App-ID/service, ports not collected`, instead of Unknown. This is a Product Owner scoring decision,
   not a vendor-semantic proof: the ports of predefined objects remain uncollected. Unresolved address objects and
   Check Point dictionary misses are not covered and stay Unknown with their own reasons.

## 3. Confirmed alongside (no change needed)

Palo Alto `lastHit` nulls are exactly the never-hit rules (live aggregate, aiview, first pages of 54 policies: hits = 0
for 4,326 rules, all with `lastHit` null; hits > 0 with `lastHit` set for 3,998; 2 rules with hits unknown). There is no
Palo Alto parse gap; the UI now shows "Never" for a collected zero counter without dates.

## 4. Implementation

`ui2/service/.../policy/PolicyRuleMetrics.java` (`permissiveness`, vendor-aware for decision 2), tests in
`PolicyPermissivenessCausesTest` (`scoringTableFollowsThePoDecision`, `paloAltoPredefinedApplicationsAndServicesAreSpecific`).
Verdicts are computed when a policy is read, so the change shows after the service is deployed; no re-collection.
