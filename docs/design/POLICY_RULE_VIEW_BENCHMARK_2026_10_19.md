# Policy › Rules — rule view benchmark (Tufin, Opinnate, AlgoSec, FireMon)

**Status: DESIGN NOTE — evidence for the 2026-10-19 rule-view redesign; not a contract.**
Date: 2026-10-19. Trigger: Product Owner feedback that the Rules table "reads like a Cisco ACL,
not like a rule". Scope: how the four NSPM products the PO benchmarks against present one
firewall rule (row versus detail, objects, usage, permissiveness, action/disabled, sections,
history and documentation). Patterns only; no proprietary asset was copied. Observations are
from the vendors' public documentation; where a detail could not be read it is marked
*not verified*. FireMon's own docs portal is login-gated, so its rows come from indexed
snippets and marketing pages and carry less weight.

## Findings

All four products separate a **thin row** from a **rich drill-down**. Tufin's Rule Viewer lists
"rule cards" (vendor → device → policy order) whose row carries the rule's identity, the
source/destination/service/application objects, action, a single *Violations* tile showing the
highest severity, and small presence icons for documentation, tickets and optimizer eligibility;
everything else (Last Hit, Last Modified, Permissiveness, Shadowed, Comment, Tags, zones, log,
owner, certification, tickets) lives in the Rule Overview drill-down. Opinnate takes the same shape
but lets the user choose the card's fields per vendor from a long list that includes Hit Count,
Last Used, Permissiveness, Risky, Shadowed, Disabled, Expired, Layer / Inline Layer, Rule Owner,
Certification, Tag and Comment. AlgoSec's Policy tab keeps vendor-specific columns and adds a
documentation field per rule (owner, recertification, expiry as custom fields) behind a comment
icon; its risk is shown as coloured circles per severity in a Risks column. FireMon concentrates
on a Rule Page with KPIs (Hits in the last 30 days, control failures, changes, rule risk score)
and a Rule Documentation widget (owner, business justification, expiration, change-control
number). None of them prints a wall of raw addresses in the row: objects stay **named**, groups
expand on click/hover (Tufin), carry a type (IP, range, FQDN, group) and an IP count (Opinnate),
and negation is an explicit flag (`source.negated` in Tufin's query language, "Source Negate" in
Opinnate). Disabled rules are greyed **and** marked with a word or icon (Tufin), never grey alone.

**Usage** is always two facts, hit count and last used, with an honest empty state. AlgoSec's
unused-rules API returns `lastUseDate: "NA"` when there is no log evidence and states the analysis
window (`configuredLogAnalysisDays`); FireMon stores hits in 30/90/180/365-day increments and
excludes disabled and unlogged rules from "unused"; Opinnate's "No Hit Rules" card warns that a
never-hit rule may simply be new (check its creation date) and defaults the unused threshold to
90 days; Tufin shows Last Hit only "if available" because it depends on usage analysis and
syslog configuration. **Permissiveness** is a level word backed by a published rule: Tufin bands
a 1-100 IP/service-count score into Low (1-30) / Medium (31-40) / High (41-100) with N/A when
unsupported; Opinnate uses Critical (any field = Any), High (> 4096 addresses or > 20 ports),
Medium (> 512 or > 10), else Low; FireMon's Policy Analyzer flags "Any with Accept", high TCP
ports and too-broad networks. Hygiene is reached from the rule: Tufin's Violations tile links to
the policy violation, Opinnate's eye icon reveals the shadowing ("master") rule, AlgoSec renders
the covered rule in grey beneath its covering rules. History is a per-rule view (Opinnate six
months with revision diff, Tufin "Last Modified" buckets and per-field change notes).

## Comparison

| Aspect | Tufin SecureTrack | Opinnate NSPM | AlgoSec AFA | FireMon Security Manager |
|---|---|---|---|---|
| Row vs detail | Card: objects, action, Violations tile (highest severity), doc/ticket/optimizer icons; Rule Overview drill-down | Customisable card per vendor; History / stats buttons per card | Vendor-specific columns; comment icon for documentation; rule number drills down | Rule Page with KPIs and widgets (row columns *not verified*) |
| Objects | Named; click a group for members, hover for detail; `negated`, `isAny` in TQL | Named; Type (IP/range/FQDN/group), IP count per side, Negate flag | Named; host-group icons coloured inside/outside; per-object usage icons | *Not verified* |
| Usage | Last Hit date, "if available"; `object.notHit` | Hit Count + Last Used; "No Hit" card with new-rule caveat; 90-day default | `lastUseDate` ("NA" when none) plus the log window | Hits (30 d) KPI; 30/90/180/365 windows; unused excludes disabled/unlogged |
| Permissiveness / risk | Low/Medium/High bands of a 1-100 score, N/A | Critical/High/Medium/Low by address and port counts | Coloured circles with counts per severity | Device-level SCI; per-rule cumulative severity |
| Action / disabled | Greyed + disabled icon | Disabled flag/field | `isEnabled`; disabled listed in Policy Optimization | *Not verified* |
| Sections / layers | `sectionTitle`, `policy.name` | Layer, Inline Layer, Policy Package fields | `sectionHeader` | *Not verified* |
| Documentation / history | Owner, certification, expiry, tickets; Last Modified buckets | Rule Owner, certification, 6-month history, revision diff | Custom documentation fields (recertified by, expiry) | Owner, justification, expiration, change-control number |

## What the neXus rule view takes from this

1. Row = identity (number + name), the rule sentence (FROM → TO · SERVICES/APPLICATIONS → ACTION) as
   named chips with a kind glyph and "+N", one Usage cell (hits + last hit), one Permissiveness word
   with its reasons, and small flags; everything else in the detail (Tufin, Opinnate).
2. Usage states are words, never zero: UNKNOWN (field missing), Not collected (no counters stored),
   Never (0 hits), last hit UNKNOWN (count without a time, today's Check Point case), relative age with
   the exact stamp on hover; the provenance (device/management counters, collected at) in the detail
   (AlgoSec "NA", FireMon windows). Presets: Unused 90 d, Unused N days, Never hit, Hit in last 30 d.
3. Permissiveness stays the backend's level word plus its specific reasons as chips; UNKNOWN carries
   its reason (Tufin N/A, Opinnate formulas). No composite score is invented in the UI.
4. Disabled rules are dimmed, struck through and carry the word "Disabled" next to the name (Tufin).
5. Flags (Shadowed, Conflict, Broad, Unused, Expired, Expiring) link to the Hygiene tab with the class
   preselected; the shadowing (covering) rule stays one click away (Opinnate eye icon, Tufin tile).
6. Documentation (owner, ticket, expiry) is shown when the source carries it and stated as
   "not recorded by this source" when it does not — the PO's Tufin/Opinnate evaluation lists rule
   owner and rule history among the valued capabilities; the data lane decides what is collected.

## Sources

- Tufin Rule Viewer: https://forum.tufin.com/support/kc/tos5/Content/ST2/RuleViewer/RuleViewer.htm
- Tufin Rule Overview (detail fields, documentation block): https://forum.tufin.com/support/kc/R25-1/Content/ST2/RuleViewer/RuleViewer_Overview.htm
- Tufin permissiveness level bands: https://forum.tufin.com/support/kc/latest/MicroContent/ST2/TOS2MicroContent/permissiveness-level.htm
- Tufin policy optimization / APG score: https://forum.tufin.com/support/kc/tos5/Content/UseCases/SecurityPolicyVisibility/security_policy_optimization.htm
- Tufin Rule Optimizer: https://forum.tufin.com/support/kc/tos5/Content/ST2/RuleViewer/RuleOptimizer.htm
- Tufin TQL rule fields: https://forum.tufin.com/support/kc/R25-1/Content/ST2/TQL/TQLFieldsRuleViewer.htm
- Tufin legacy Policy Browser: https://forum.tufin.com/support/kc/R21-3/Content/Suite/2981.htm
- Opinnate User Guide v5.2.0: https://docs.opinnate.com/default/opinnate-user-guide-v5-2-0/
- Opinnate permissive rules: https://docs.opinnate.com/default/finding-out-permissive-rules/
- Opinnate unused rules: https://docs.opinnate.com/default/finding-out-unused-rules/
- Opinnate expired-rule alerting: https://docs.opinnate.com/default/expired-rule-alerting-to-the-owner/
- AlgoSec view policy data: https://techdocs.algosec.com/en/asms/a33.10/asms-help/content/afa-ug/view-policy-data.htm
- AlgoSec device report pages (risky rules, policy optimization): https://techdocs.algosec.com/en/asms/a33.10/asms-help/content/afa-ug/device-report-pages.htm
- AlgoSec unused rules API: https://techdocs.algosec.com/en/asms/latest/asms-help/content/api-guide/policyrest_unusedrules.htm
- FireMon Rule Page: https://docs.firemon.com/feature/Content/SECURITY%20MANAGER/POLICY/Security%20Rules/Rule%20Page.htm
- FireMon rule documentation: https://docs.firemon.com/feature/Content/SECURITY%20MANAGER/POLICY/Security%20Rules/About%20Rule%20Documentation.htm
- FireMon unused rules report: https://docs.firemon.com/feature/Content/ADMINISTRATION/SYSTEM/Reports/Create%20Reports/Unused%20Rules%20Report.htm
- FireMon usage-based clean-up: https://supportcenter.firemon.com/hc/en-us/articles/9408275662995-Usage-Based-Rule-Clean-up
- FireMon SCI: https://docs.firemon.com/enterprise/Content/SECURITY%20MANAGER/Widgets/What%20is%20the%20SCI%20Calculation.htm
- FireMon Policy Analyzer: https://www.firemon.com/policy-analyzer-tool/
