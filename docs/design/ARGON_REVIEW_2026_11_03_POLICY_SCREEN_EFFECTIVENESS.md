<!-- Argon consultation 2026-11-03T17:49:26Z, model gemini-4-argon-high, effort high; unaltered output -->

**Role:** `REVIEWER` (independent read-only UI effectiveness reviewer)

---

### 1. Tab-by-Tab Purpose & Credibility Audit

| Surface / Tab | Serves Purpose? | Where Certainty Is Overstated, Understated, or Buried |
| :--- | :--- | :--- |
| **Header & Navigation** ([`PolicyScreen`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L295-L832), [`policyHeadline`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L243-L253), [`syncStatusWords`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/policyFirewalls.ts#L10-L19)) | **Mostly** | **Works:** [`policyHeadline`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L243-L253) exposes both newest and oldest snapshot age (`oldest 29 d ago`); [`SnapshotFreshnessChip`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L102-L110) warns in red past 7 days; [`syncStatusWords`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/policyFirewalls.ts#L10-L19) replaces false Check Point sync warnings with a hollow dot (`"Sync not reported by Check Point"`).<br>**Overstates:** Every container and policy in the left tree renders a hardcoded green dot (`bgcolor: m3.success`, `aria-label="Stored snapshot"` in [`PolicyScreen.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L674-L686)), even when 29 days old. [`PolicyContainerPrompt`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyContainerPrompt.tsx#L55) also omits [`freshnessColor`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L100) on `Last collected`. |
| **Rules** ([`PolicyRuleTable`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L485-L628), [`RuleDetailPanel`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L660-L702)) | **Partial** | **Buries real findings in badge noise:** [`PolicyHygiene.analyze`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyHygiene.java#L151) adds `finding("unknown", "LOW", "Shadowing: " + reason + ".")` whenever `shadowStatus === "UNKNOWN"`. Consequently, [`hygieneBadges`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L430-L445) emits a `"Hygiene UNKNOWN"` pill on **~97% of all rows**, making the clean fallback at [`PolicyRuleViewer.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L539-L540) (`"No findings · shadowing not provable"`) unreachable dead code and drowning real `Conflict`/`Shadowed`/`Broad` flags.<br>**Contradicts usage window:** [`usageWords`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L131) and [`PolicyQueryService.hitMatches`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyQueryService.java#L688) compare `lastHit` to wall-clock `now`, whereas [`PolicyHygiene.unused`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyHygiene.java#L318) compares `lastHit` to `hits.collectedAt`. On a 29-day-old snapshot, a rule hit 1 hour before collection shows `last 29 d ago`, and a rule hit 65 days before collection matches the `Unused 90 d` filter in `Rules` (`65 + 29 > 90`) while `Hygiene` says it is not unused. |
| **Hygiene** ([`PolicyHygieneTab`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L46-L137)) | **No (in default view)** | **Buries proven findings:** Default filter `All classes` includes `findingClass === "unknown"`, filling the table with ~100% of the policy's rules. **Understates proven work:** [`hygieneCoverage`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L18-L26) computes `proven = denominator - counts.unknown`, where `counts.unknown` includes missing hit counters; a policy with 100% proven shadow results but uncollected hit counters reports **`0 proven`**! (See §2.) |
| **Objects & Installation** ([`PolicyDomainTab`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyDomainTabs.tsx#L26-L127)) | **Mostly** | **Works:** [`whereUsedLabel`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyDomainTabs.tsx#L12-L16) fails closed (`UNKNOWN` / `at least X rules`) and surfaces `usageGaps`.<br>**Scope mismatch:** Both tabs are domain-wide (`DOM-TANGO-01`), but when a policy is selected, the banner directly above the tabs still displays the single policy's name and `Assigned to: FW-TANGO-04` ([`PolicyScreen.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L696-L729)). If opened via deep link without `policy_id` ([`lines 505–513`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L505-L513)), `headerMetadata` is `undefined` and no snapshot freshness chip appears at all. In `Installation`, `truth(row.installed)` ([`line 100`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyDomainTabs.tsx#L100)) prints bare `UNKNOWN` without the Check Point explanation shown in the header. |
| **Changes** ([`PolicyChangesTab`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyChangesTab.tsx#L36-L158)) | **Mostly** | **Works:** Collapses initial captures by default, marks `Observed at collection`, and surfaces 20 s timeout states.<br>**Buries staleness & scope:** Queries the whole estate by default (`draft.policy === ""`) while sitting under the selected policy's header; when no policy is selected, no freshness banner warns the engineer that "last 30 days" has 13–29 days of uncollected silence. |
| **Lookup** ([`PolicyLookupTab`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyLookupTab.tsx#L8-L86)) | **Yes** | **Works:** Clearly separates `"First proven match after uncertainty (not a verdict)"` ([`line 78`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyLookupTab.tsx#L78)) and attaches [`SnapshotFreshnessChip`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L102-L110) to each result card ([`line 62`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyLookupTab.tsx#L62))—though `sentence={true}` by default duplicates the full red staleness sentence already shown in the header above the tab bar. |
| **Offline analysis** ([`PolicyOfflineTab`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyOfflineTab.tsx#L19-L165)) | **Mostly** | **Works:** Honest sample/attribution caveats and locked dropzone explanations.<br>**Buries snapshot provenance:** `Sample summary` ([`lines 128–133`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyOfflineTab.tsx#L128-L133)) shows CSV `Uploaded` time and sample `Period`, but never records which policy snapshot (`collectedAt`) the flows were evaluated against. |

---

### 2. Hygiene Tab Effectiveness at ~97% "Not Provable"

With 102,661 of 105,710 rules having `shadowStatus === "UNKNOWN"`, **the Hygiene tab is still highly valuable—provided it separates proven findings from coverage diagnostics.** Because the analyzer fails closed, the **3,049 definite shadow/conflict results** (plus per-rule `expired`, `disabled`, `unused`, and `broad` findings, which do not depend on shadow reachability) are high-confidence findings an engineer can act on immediately.

Currently, three mechanics undermine that value:
1. **Default table flood:** [`PolicyQueryService.hygiene`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyQueryService.java#L604-L619) returns rules whose *only* finding is `findingClass === "unknown"` in the default unfiltered view. The engineer opens Hygiene and sees 200 `UNKNOWN` rows instead of the proven conflicts/shadows.
2. **Page-0 sampling bias in "Why not provable":** [`hygieneReasonBreakdown`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L31-L44) only aggregates `data.rows` (the **200 rules on the current page**, [`line 72`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L72)). If rule #2 in a 3,000-rule policy has an unsupported constraint, page 0 reports ~199 rules blocked by constraints and completely hides unresolved objects or layer issues on pages 1–14.
3. **Conflated `proven` denominator:** [`hygieneCoverage`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L18-L26) subtracts `counts.unknown` from `ruleCount`. Because [`PolicyHygiene.unused`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyHygiene.java#L298) emits `finding("unknown", ...)` whenever hit counts are uncollected, a policy with 100% proven shadow analysis and no hit counters displays `Analysed: 0 proven`.

**What Hygiene should lead with:**
- **Proven findings first:** Default the table to **proven findings only** (`conflict`, `shadowed`, `broad`, `expired`, `unused`, `disabled`), ordered by severity/risk. Move `not provable (UNKNOWN)` to the end of the chip bar as a dedicated coverage view.
- **Policy-wide coverage split by dimension:** Report **Shadow analysis coverage** (`X proven · Y not provable of N rules`) separately from **Hit-counter coverage** and **Permissiveness coverage**, computed across the full policy in [`PolicyQueryService.hygiene`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyQueryService.java#L604-L619).
- **Root-cause blocker vs. cascaded rules:** In [`PolicyHygiene.analyze`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyHygiene.java#L125-L147), any single enabled rule with `match.problem() != null` (e.g. an identity/access-role or negated rule) sets `earlier.unknown = true` and stamps every subsequent resolved rule in that layer with `"earlier rule has unsupported constraints"` (30,680 CP + 6,050 PAN rules). Lead with **which earlier rule(s)** triggered the cascade vs. how many rules inherited it, and separate **fixable/collection blockers** (uncollected hit counts, unresolved inventory objects, NAT section scope breaks) from **static model limits** (identity/access-role, PAN zone/install-on overlap).
- **Per-policy coverage in the container table:** Show proven/total counts per policy before the engineer clicks through 99 policies.

**What to de-emphasise:**
- Exclude `unknown`-only rows from the default table.
- Stop printing the same reason twice per row in [`PolicyHygieneTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L120-L129) (`UNKNOWN because Shadowing: <reason>.` in **Findings** and `UNKNOWN because <reason>` in **Shadow comparison**).

---

### 3. Hierarchy, Labels, Empty/Loading/Error States (`503 POLICY_BUSY`) & Misreads

- **`503 POLICY_BUSY` is misreported across every tab:** [`PolicyBulkhead.busy`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyBulkhead.java#L64-L67) returns HTTP `503` with `Retry-After: 5` and `{"error": "POLICY_BUSY"}`.
  - In [`PolicyLookupTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyLookupTab.tsx#L22-L24), only `429` is checked; a `503 POLICY_BUSY` tells the engineer: **`"Lookup could not be completed. Check the inputs and try again."`**—blaming valid inputs like `192.0.2.0/24` on a 5-second server queue timeout.
  - In [`PolicyOfflineTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyOfflineTab.tsx#L58-L61), a `503` prints **`"Upload rejected. POLICY_BUSY"`**, making a temporary bulkhead wait sound like a rejected CSV.
  - In [`PolicyScreen.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L396), [`PolicyHygieneTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L58), [`PolicyDomainTabs.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyDomainTabs.tsx#L43), and [`reportPolicyLoadError`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L11-L21), `503 POLICY_BUSY` falls into generic `"could not be loaded"` errors without mentioning that the server is temporarily busy.
- **Misleading constraint label on 36,730 cascaded rules:** [`unknownReasonGroup`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L148-L163) maps `"earlier rule has unsupported constraints"` to `"Unsupported rule constraints"`. In [`RuleMetrics`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L399-L400), an engineer inspecting rule #120 sees `"Not provable · Unsupported rule constraints"` and wastes time hunting for a constraint on rule #120 when rule #120 is a plain IPv4 rule and rule #4 had an access-role or unresolved object.
- **Label mismatch on zero-hit preset:** [`PolicyRuleViewer.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L126) carefully avoids `"Never"` in table cells (`"No hits recorded"`), but the filter dropdown in [`PolicyScreen.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L780) still labels `value="never"` as **`"Never hit"`**.
- **Duplicate sub-tabs in `RuleDetailPanel`:** In [`PolicyRuleViewer.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L676-L692), clicking `Shadowing` or `Violations` renders the exact same `<RuleMetrics>` 4-card grid already visible on `Overview`.

---

### 4. Top 5 Concrete Changes (Ranked by Value / Effort)

1. **Default Hygiene to proven findings and compute policy-wide coverage & blocker breakdowns (High value / Low-Medium effort)**
   - **Where:** [`PolicyQueryService.java`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyQueryService.java#L604-L619) (`PolicyQueryService.hygiene`) and [`PolicyHygieneTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L18-L131) (`PolicyHygieneTab`, `hygieneCoverage`, `hygieneReasonBreakdown`).
   - **Change:** Exclude rules whose only finding is `unknown` when `findingClass === ""` (keep them under the `not provable (UNKNOWN)` chip); return policy-wide `shadowProven` and `unknownReasonCounts` from `PolicyQueryService.hygiene` so page 0 doesn't skew the breakdown and missing hit counters don't report `0 proven`; remove duplicate `UNKNOWN because` text in the table row.

2. **Remove `"Hygiene UNKNOWN"` pill noise from 97% of `Rules` rows (High value / Low effort)**
   - **Where:** [`PolicyRuleViewer.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L430-L445) (`hygieneBadges`, `PolicyRuleTable` `Flags` column at [`lines 533–542`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L533-L542)).
   - **Change:** Filter `findingClass === "unknown"` out of row-level `hygieneBadges` so the `Flags` column only badges actionable findings (`Conflict`, `Shadowed`, `Broad`, `Unused`, `Expired`, `Disabled`, `Expiring`) and falls back to the muted text `"No findings · shadowing not provable"` when `shadowStatus === "UNKNOWN"`.

3. **Handle `503 POLICY_BUSY` explicitly across all Policy tabs (High value / Low effort)**
   - **Where:** [`PolicyRuleViewer.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L11-L21) (`reportPolicyLoadError`), [`PolicyLookupTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyLookupTab.tsx#L22-L25) (`PolicyLookupTab`), [`PolicyOfflineTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyOfflineTab.tsx#L56-L63) (`PolicyOfflineTab`), [`PolicyHygieneTab.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyHygieneTab.tsx#L58) (`PolicyHygieneTab`), [`PolicyDomainTabs.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyDomainTabs.tsx#L43) (`PolicyDomainTab`), and [`PolicyScreen.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L396) (`PolicyScreen`).
   - **Change:** Recognise `status === 503` / `error === "POLICY_BUSY"` and display `"Policy analysis is busy; retry in a few seconds"` instead of blaming user inputs or reporting `"Upload rejected"`.

4. **Distinguish cascaded `"Earlier rule in layer not provable"` (36,730 rules) from self-constraints and link the blocking rule (Medium-High value / Low-Medium effort)**
   - **Where:** [`PolicyRuleViewer.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L140-L164) (`REASON_GROUPS`, `unknownReasonGroup`, `RuleMetrics`) and [`PolicyHygiene.java`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyHygiene.java#L125-L147) (`PolicyHygiene.analyze`, `Earlier`).
   - **Change:** Split `"earlier rule has unsupported constraints"` into its own `ReasonGroup` (`"Earlier rule in layer not provable"`) and record the first rule in the scope that set `earlier.unknown = true` so engineers can jump straight to the blocker rule.

5. **Align staleness & scope cues: tree dots, collection-anchored hit windows, and `"No hits recorded"` filter wording (Medium value / Low effort)**
   - **Where:** [`PolicyScreen.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L674-L686) (`PolicyScreen` tree & [`line 780`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyScreen.tsx#L780) usage filter), [`PolicyRuleViewer.tsx`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L131) (`usageWords`, `RuleDetailPanel` at [`line 676`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/frontend/src/screens/PolicyRuleViewer.tsx#L676)), and [`PolicyQueryService.java`](file:///Users/OzanDur/Codo/wt-polreview-1103/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/PolicyQueryService.java#L688) (`hitMatches`).
   - **Change:** Colour tree snapshot dots by `freshnessColor(snapshotFreshness(...).state)` instead of `m3.success`; evaluate `hitMatches` against `hits.collectedAt` to match `PolicyHygiene.unused`; rename `"Never hit"` to `"No hits recorded (0 hits)"`; and remove the duplicate `Shadowing`/`Violations` nav buttons in `RuleDetailPanel`.

---

### Verbatim Instructions Received

1. **Repository rules (`AGENTS.md` and `roles/REVIEWER.md`):**
   - `/Users/OzanDur/Codo/wt-polreview-1103/AGENTS.md` (neXus Agent Constitution)
   - `/Users/OzanDur/Codo/wt-polreview-1103/roles/REVIEWER.md` (read-only reviewer procedure)
2. **User request verbatim:**
```text
/plan # Brief: effectiveness review of the Policy screens (2026-11-03)

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
```
