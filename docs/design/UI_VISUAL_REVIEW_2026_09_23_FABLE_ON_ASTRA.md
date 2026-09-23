# neXus UI · closing review, second pass · 2026-09-23

Scope: the same 19 post-redesign screenshots, my 27 items, and the real Astra review. The Product Owner reports eight fixes deployed after the screenshots were taken; those are marked "fixed per PO, not pictured" and are not re-listed. Anything not in the screenshots is UNKNOWN.

## 1. Implementation check, 27 items

| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | Readiness as NOT EVALUATED, "0 of 40" | Done as meant | Operations card and every HA row. |
| 2 | Major deviations as UNKNOWN | Done as meant | Backups card "UNKNOWN · 0 comparisons run". |
| 3 | Duration neutral, Terminal Reason "—" | Done as meant on Jobs | Admin › Job Logs not pictured: UNKNOWN. |
| 4 | One empty, not evaluated, restricted, error component | Partly verified | Credentials shows the Restricted variant exactly as specified. Local identities, Sessions, Roles, Audit Logs, Operations cluster detail not pictured: UNKNOWN. |
| 5 | Inventory exclusions copy | Not pictured | UNKNOWN. |
| 6 | Denominators, as-of time, reconciled counts | Done differently | "103 of 105 devices active · 39 of 40 clusters active", as-of stamp, failed jobs agree at 45. Job totals 1016 versus 1018 and Admin "0 failed" versus Devices "Failed · 2" were open in the screenshots; job totals fixed per PO. |
| 7 | HA role chips neutral and identical | Done as meant | ACTIVE filled, STANDBY outlined on Devices, Configuration, Operations. |
| 8 | Six tiles, rewritten headline, donuts below fold | Done as meant | Title truncation in build 206da15 fixed per PO. |
| 9 | One timestamp format with zone | Done as meant, one gap | "Times in GMT+3", `2026-09-22 22:57:54` with relative age. Date pickers still `dd.mm.yyyy`. |
| 10 | Failover button as explained control | Not pictured | UNKNOWN. |
| 11 | UNKNOWN hostname row | Done as meant | "UNKNOWN hostname · hostname not reported". |
| 12 | Compliance stacked bars, units, define Observed | Done as meant | Unit labels further refined per PO. |
| 13 | Backups single table, compact, neutral V1, CHANGED, monogram, Not scheduled | Done as meant | Snapshot action added on Check Point rows; meanings on hover per PO. |
| 14 | Configuration filters, Live chip, Differences only, jump strip, accordion, VSYS collapse | Done differently | Filters labelled, Live chip removed, Differences only on, export added. Jump strip became a 78-link paragraph. Accordion and VSYS collapse not pictured: UNKNOWN. List rows still about 62 px. |
| 15 | Devices identity table, "n of N shown", dots, member order, hidden tabs | Done as meant | Sticky member column per PO. Empty-selection state not pictured: UNKNOWN. |
| 16 | HA table, Job history link, empty header, queue copy, one filter bar | Partly verified | HA table and Job history link as meant. Cluster detail, Queue, History not pictured: UNKNOWN. |
| 17 | Admin groups, header buttons scoped, Delete in overflow, registry columns, Delivery plan | Partly done | Groups, overflow menu, vendor, enrolment, last collection: done. Transport and credential columns absent. Header buttons still on Credentials in the screenshots; whether "Import from manager wired" also scoped them is UNKNOWN. Rows still card-height. |
| 18 | Stat line, UNKNOWN hatch, collapsed freshness chip, row F | Done as meant | Row F not pictured: UNKNOWN. |
| 19 | Ink tokens, hairlines, flat background, monogram chips, member token | Done differently | Flat background, hairlines, monogram chips: done. Exact ink hex and member token: UNKNOWN. |
| 20 | Dark and wall modes | Done as meant | Bright dark scrollbar, white logo plate, empty wall queue remain. |
| 21 | Density toggle | Not visible | UNKNOWN. |
| 22 | Cluster context strip | Done as meant | On Devices and Configuration. |
| 23 | Since-yesterday deltas | Done as meant | "−24 since yesterday", "no history" where none. |
| 24 | Families table, export cover | Table done | Export contents UNKNOWN. |
| 25 | Config evidence export with ORIGIN | Button done | ORIGIN column UNKNOWN. |
| 26 | State vocabulary beyond LOCAL and MEMBER | Not done, declared | Tile says "not yet separated". |
| 27 | Global search | Done as meant | Widened per PO. |

Tally: 15 as meant, 6 differently or partly, 1 not done, 5 wholly or mostly UNKNOWN.

## 2. Astra's review

**Where I agree.**
- **Compliance headline scope.** The 28.9% and the 428 both equal the CIS card. The headline is one framework's number presented as the page's number, and the families caption says NIST grouping while the check total is CIS. This is the strongest finding in Astra's review. P0.
- **"Critical deficiencies" unit.** The subtitle says "failing controls", but there are only 48 controls and the count is 172. The unit must be checks or control-firewall pairs. The PO's unit-label fix should be checked against exactly this.
- **Configuration first viewport.** Comparison rows first, identity behind "Show identity", a section navigator with review, expected and UNKNOWN counts, "Observed configuration" instead of "CURRENT ACTUAL", and the 353 unit stated. I earlier argued to keep the identity table expanded; with the Devices identity tab and the cross-link now in place, collapsing it on Configuration loses nothing. I withdraw that objection.
- **Operations wording.** The subtitle promises failover the build does not have, and "class 2" is internal. "Failover is unavailable in this build" is right. Add Observed at for roles, separate from Last evaluated.
- **Backups semantics.** Retention horizon reads as available history; call it "Retention policy · 14 days". "83 devices with a stored backup" needs its population stated against the 102 targets. CHANGED rows beside "0 comparisons run" need one sentence relating archive change to semantic comparison.
- **Delivery plan arithmetic.** Open backlog 92 against 10 + 73 + 5 + 9 = 97. Astra caught it; I did not.
- **Wall display** lacks the mask indicator; three short lines, two rows of three, readable failure categories with raw strings kept in Overview and Jobs.
- **Registry and Devices vocabulary.** "105 devices enrolled" on Devices against 103 enrolled + 2 draft in Admin; "registered" is the honest word. Define Live, Failed, Stale and HA role as separate dimensions.
- **Jobs list readability.** Friendly job labels with the raw type in detail, filter and export; masked device name in the list with the UUID in detail; last-refresh time.
- **System tab.** Usage against request or limit must be stated; "Host filesystem free space" is the right label.
- **Dark logo plate** and the metric contract: measure, population, window, ratio, source, with the last two behind a disclosure.

**Where I disagree, and why.**
- **Status colour banned from bars and charts.** The rule in force is "status colour always paired with a word", not "only on words". The pass, fail, unavailable bars carry both legend words and printed counts, so they comply. Patterned bars slow the two readers who need the fastest read: the deputy GM and the wall. Keep the bars. I do agree the full red bar under 172 has no denominator and should go, or state its denominator.
- **Replacing the three-line briefing with an exception table.** The tiles already are that table: condition, scope, evidence age, destination. A second table above them would duplicate them, which Astra itself flags as the current weakness. Keep the prose, link each measure in it, and add the evidence time to the tiles that lack it. Astra's own wall recommendation is three short lines, which supports the prose form.
- **Compact identity table by default on Devices.** Serial and management address are the fields an operator reaches for first in an incident. With the sticky member column now deployed, the full table works. Keep it full, stop serials breaking, and add Observed at, Collection job and Evidence reference as columns.
- **Donuts to ranked lists.** Same position as before: rule-compliant, below the fold, UNKNOWN now hatched. Optional P2. The point about green and orange categorical slots resembling status is real but is a rule in force; the mitigation is never to place a categorical chart in the same card as a status chart, which is already so.
- **14 px body minimum.** Dense evidence tables at 13 px are the product's standard and Astra's own table allows 13 for tables. Keep 13 there, enforce wrapping on headings.
- **Service-first list on System.** If the product records no service health checks, every row would read UNKNOWN and the list would be decoration. Do it only if checks exist. UNKNOWN today.
- **Double scrollbar on the Devices navigator.** An independent navigator scroller is standard master-detail and Astra's own rule permits it. No change.

**What Astra saw that I missed.** CIS-only headline scope and the families caption mismatch; the 172 unit contradiction; the backlog arithmetic; the missing mask indicator on the wall; "CURRENT ACTUAL" overclaiming freshness; retention policy versus available history; CHANGED versus no comparisons; usage denominator on System; the Operations subtitle and "class 2"; the dark logo asset; "105 enrolled" versus draft; the 353 unit.

**What I hold that Astra does not.** Verification of the four unpictured refused tabs and the failover control; date pickers; Configuration list and registry row height; density toggle; export contents; the between-build regression check in the release gate.

## 3. Final list

Effort: S under a day, M up to a week, L more. Source: F = carried from my closing list, A = Astra adds, F+A = both. Items the PO reports fixed are omitted.

| Pri | Screen | Change | Effort | Source | Capability preserved |
|---|---|---|---|---|---|
| P0 | Compliance | State the headline scope above the four cards: framework, population, evaluation time. Make the 28.9% and 428 say "CIS" or compute what the page claims. Label the families section with its real framework scope and grouping taxonomy | M | A | All four frameworks, families, filters, export |
| P0 | Compliance | Correct the 172 unit: "critical failing checks" or "control × firewall", never "controls" | S | A | Count and click-through |
| P0 | Configuration, Overview | Separate member-specific from other differences; "unreviewed" until classified; carry the split to the Overview tile | L | F+A | Every setting, both member values, all filters |
| P0 | Configuration | Comparison rows first; identity behind "Members · 2 · Show identity"; section navigator with review, expected, UNKNOWN counts replacing the link paragraph; "Observed configuration" wording; 353 unit stated | M | F+A | Full identity table, all links, search, Differences only, CSV |
| P0 | Operations | Subtitle "Inspect observed HA roles and readiness evaluations"; "Failover is unavailable in this build"; Observed at per role beside Last evaluated | S | A | All rows, Schedule collection |
| P0 | Admin, Operations | Verify unpictured refused tabs use the Restricted component; verify cluster detail failover control, empty header, Queue copy | S | F | Every RBAC boundary, 4-Eyes flow |
| P0 | Overview | Remove or give a denominator to the red bar under 172 | S | A | Count, context, link |
| P1 | Backups | "Retention policy · 14 days" with oldest and newest retained in History; state the population of the 83 against the 102; one sentence relating CHANGED to semantic comparison; group History, Contents, Compare, Download under "Archive actions" with Backup now and Snapshot visible | M | F+A | Toggle, all six actions, all columns |
| P1 | Operations Jobs | Friendly job label with raw type in detail, filter, export; masked name in list with UUID in detail; last-refresh time; Failed quick chip | M | F+A | Every job, filter, polling, export |
| P1 | Devices, Admin | "105 registered devices" with enrolled, draft and evidence-state counts; define Live, Failed, Stale, HA role as dimensions; reconcile "Failed · 2" with "0 failed collection"; title "CLS-APOLLO-09" with type as a chip | S | F+A | All filters, tree, counts |
| P1 | Devices identity | Keep full table; serials unbroken with masked copy; add Observed at, Collection job, Evidence reference columns, UNKNOWN where absent | M | A, scoped | All identity fields |
| P1 | Admin | Header buttons only on Device management (verify against "wired" fix); rows to 44 px; transport and credential profile columns; masked ID and address under UNKNOWN hostname; define which collection "last collection" means | M | F+A | Import, Add device, overflow, all states |
| P1 | Wall | Mask indicator persistent; three short lines; two rows of three; readable failure categories with raw strings in Overview and Jobs; viewport-fitting; refresh time shown | M | F+A | Every measure and route |
| P1 | Admin Delivery plan | Reconcile Open backlog 92 with status counts; lead with deployed, recorded, freshness; human titles with keys in detail | S | F+A | All fields and statuses |
| P1 | Admin System | Label ratios Usage / Request or Usage / Limit; "Host filesystem free space"; Observed at; "<1m" for rounded zero | S | F+A | All pod and storage figures |
| P1 | Configuration | List rows to 44 px | S | F | Every list item |
| P1 | All | Date pickers to yyyy-mm-dd | S | F | Date filters |
| P1 | Overview | Each headline measure links to its filtered list; comparable-period definition behind the deltas; evidence time on the two tiles that lack it | S | F+A | Headline, tiles, deltas |
| P2 | Dark mode | Dark-compatible logo asset; muted scrollbar; separate dark tokens for border and secondary text; focus ring | S | F+A | Identical layout |
| P2 | Global search | Two-line results with "Open matching settings"; "View all results" | S | A | Every result and link |
| P2 | Compliance findings | "Pass 30 · Fail 32 · Unavailable 0" spelled out; outcome scoped "FAIL · 32 of 62 device checks"; title as the detail link; "+1 more" expandable | S | A | Every control, mapping, filter |
| P2 | Admin Restricted | One sentence on where to request the role, no credential metadata | S | F | RBAC boundary |
| P2 | All | Density toggle, spacing only | M | F | Everything |
| P2 | Compliance, Configuration | Verify export cover fields and ORIGIN column | M | F | Exports |
| P2 | Admin System | Service-first list only if health checks exist; UNKNOWN today | M | A, conditional | All diagnostics |
| P2 | Overview | Donuts to ranked lists, optional | M | A, disagree on priority | Every category and link |

**Not adopted from Astra:** patterned or neutral pass, fail, unavailable bars; an exception table above the briefing; compact-by-default identity table; 14 px table body; navigator scrollbar removal. Reasons are in section 2.

## 4. Fit for purpose

The deputy GM and the manager now read an ordered briefing with denominators, deltas and destinations, and the job arithmetic is reconciled, so the first minute works. What still stands between them and a decision is trust in two numbers: the Compliance headline is one framework's figure presented as the page's, and 38 of 39 cluster differences will alarm every morning until member-specific settings are separated. The operator is well served by Devices, Backups, Operations and the cross-links; the one remaining obstacle is that Configuration still opens on identity and a 78-link paragraph rather than on the comparison. The auditor has separated, defined quantities, families, controls and an export, but the CIS-only headline, the 172 unit and the unverified export contents mean an audit reviewer cannot yet trace a headline number to evidence without asking someone. Close the four Compliance and Configuration P0 items and verify the export, and the product is fit for all four audiences.
