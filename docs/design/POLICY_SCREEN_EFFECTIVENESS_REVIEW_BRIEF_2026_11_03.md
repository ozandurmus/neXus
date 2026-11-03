# Brief: effectiveness review of the Policy screens (2026-11-03)

Reviewer role: independent UI effectiveness reviewer. Read-only. Judge by purpose served, not feature count.

## Users and purpose
A firewall/security engineer (Check Point and Palo Alto estates) opens Policy to answer: which rules are risky,
redundant, unused or broken; how far can I trust these numbers; how fresh is this data; which gateways does a policy
reach. The Product Owner reported the screens had "UI and credibility problems": people could not tell how far to
trust the numbers.

## What changed today (merged to main)
Frontend: `ui2/frontend/src/screens/PolicyScreen.tsx`, `PolicyRuleViewer.tsx`, `PolicyHygieneTab.tsx`,
`PolicyLookupTab.tsx`, `policyFirewalls.ts` (tests in `ui2/frontend/tests/Policy*.test.tsx`):
snapshot freshness chip and stale warning on every tab; hygiene "analysis coverage" (proven vs not provable, reason
groups in plain words, analysis-limit marker); Check Point sync shown as "not reported by Check Point"; honest
zero-hit and zero-violation wording.
Backend (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/`): non-cascading shadow budget,
CP install-on as opaque placement token, layer-identity scoping, read-path parse cache, bulkhead.

## Measured facts (aiview masked API, counts only, 99 policies, 105,710 rules)
- Shadow analysis definite results rose from about 580 to 3,049 today; about 97% of rules are still UNKNOWN, with
  reasons: CP "earlier rule has unsupported constraints" 30,680; CP "unresolved object" (mostly identity/access-role)
  14,921; PAN "zone or install-on coverage is unproven" 14,844; CP "earlier sections not analysed" 12,650 (a fix
  is queued: NAT sections no longer break the access scope); PAN "earlier rule has unsupported constraints" 6,050.
- Snapshots are 13-29 days old until tonight's 00:00 collection (a scheduler fault, fixed today).

## Questions
1. Does each Policy tab now serve its purpose for that engineer? Where does it still overstate or understate
   certainty, or bury the answer?
2. With ~97% "not provable", is the hygiene tab still useful? What should it lead with (e.g. what IS proven, the
   biggest fixable blocker, per-policy coverage), and what should be de-emphasised?
3. Information hierarchy, labels, empty/loading/error states (including the new 503 POLICY_BUSY), and anything a
   security engineer would misread.
4. Top 5 concrete changes, ranked by value/effort, each naming file and component.
Constraints: masked fixtures only in any example (FW-TANGO-04, 192.0.2.0/24). English. Under 1,200 words.
