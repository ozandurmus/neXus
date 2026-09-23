# neXus UI · closing review of the improvement round · 2026-09-23

Scope: 19 post-redesign screenshots, my earlier 27 items, Astra's review. Screenshots show three builds (1c0a5dd, 0dc6360, 206da15); one visible regression between them is noted below. Anything not in the screenshots is UNKNOWN.

## 1. Implementation check, 27 items

| # | Item | Result | Evidence and remarks |
|---|---|---|---|
| 1 | Readiness checks as NOT EVALUATED, "0 of 40" | Done as meant | Operations card: "NOT EVALUATED · 40 clusters enrolled · 0 evaluated". Same word in every HA table row. |
| 2 | Major deviations as UNKNOWN | Done as meant | Backups card: "UNKNOWN · 0 comparisons run". |
| 3 | Duration neutral, Terminal Reason "—" | Done as meant on Operations › Jobs | Admin › Job Logs not pictured: UNKNOWN. |
| 4 | One empty, not evaluated, restricted, error component | Done differently, partly verified | Credentials shows the Restricted variant exactly as specified: lock, role named, no Retry. Local identities, Sessions, Roles, Audit Logs and the Operations cluster detail are not pictured: UNKNOWN. |
| 5 | Inventory exclusions copy fix | Not pictured | UNKNOWN. |
| 6 | One denominator language, one as-of time, reconciled counts | Partly done | "103 of 105 devices active · 39 of 40 clusters active" on Overview, "40 enrolled · 39 active" on Devices, "as of" stamp present. Failed jobs now agree at 45. Still open: Overview says 1016 jobs, Operations says 1018 submitted; Admin says 0 enrolled devices failed collection while Devices says "Failed · 2". |
| 7 | HA role chips neutral and identical | Done as meant | ACTIVE filled dark, STANDBY outlined, same on Devices, Configuration and Operations. |
| 8 | Six posture tiles, rewritten headline, donuts below fold | Done as meant, one regression | Headline text is verbatim. In build 1c0a5dd tile titles wrap; in 206da15 they truncate ("BACKUP TARGETS W…", "CLUSTERS WITH ME…"). Regression between builds. |
| 9 | One timestamp format with zone | Done as meant, one gap | "Times in GMT+3" in the top bar; `2026-09-22 22:57:54` in jobs, backups, registry with relative age beside it. Date pickers still read `dd.mm.yyyy, --:--`. |
| 10 | Failover button as explained control | Not pictured | UNKNOWN. |
| 11 | "Unknown" registry row as UNKNOWN hostname | Done as meant | "UNKNOWN hostname · hostname not reported". |
| 12 | Compliance stacked bars, checks versus controls, define Observed | Done as meant | "2448 control checks (control × firewall)", stacked pass, fail, unavailable, "observed 34.7% (pass among judged checks)", chips "All · 48". |
| 13 | Backups single table, compact rows, neutral V1, CHANGED attention, monogram, Not scheduled | Done as meant | One table with toggle, latest archive, size, V1 grey, CHANGED amber, "No archive" row with disabled actions, "Not scheduled" chip. A new "Snapshot" action appears on Check Point rows only, unexplained. |
| 14 | Configuration: VSYS collapse, Differences only default on, jump strip, accordion, labelled filters, Live chip only when not Live | Done differently | Filters labelled Vendor and State; Live chip removed with an explanatory line; Differences only defaults on; Export evidence added. The jump strip became a paragraph of 78 links, which is unreadable. Section accordion not pictured (05 and 06 are the same viewport): UNKNOWN. VSYS collapse cannot be judged on a non-VSX cluster: UNKNOWN. List rows still about 62 px. |
| 15 | Devices identity table, hidden tabs, "n of N shown", dots removed, member order | Done as meant, one part UNKNOWN | Identity tab holds serial, model, software, hotfix, uptime, role, address, VSX, enrolment with a horizontal scrollbar; first seen and evidence source are off-screen or absent: UNKNOWN. "4 of 4 shown · Active only", no dots, M1 before M2. Tab behaviour with nothing selected not pictured: UNKNOWN. |
| 16 | Operations HA table, hidden empty header, queue copy, one filter bar, shared job table | Partly done | HA table with cluster, vendor, active, standby, last evaluated UNKNOWN, readiness word: as meant. Job history link now inside the Failed card: as meant. Cluster detail, Queue and History tabs not pictured: UNKNOWN. |
| 17 | Admin four groups, header buttons only in Registry, Delete in overflow, registry columns, Delivery plan under Platform | Partly done | Groups and Delivery plan placement as meant. Delete replaced by a row overflow menu. Vendor, enrolment state and last collection added; transport and credential profile not added. "Import from manager" and "Add device" still appear on the restricted Credentials page. Rows still about 66 px, one card per row. |
| 18 | Single-slice donut to stat line, UNKNOWN hatch, collapsed freshness chip, row F widths | Done as meant | "11.1 · 40 of 40 devices", hatched UNKNOWN slices and legend glyph, "All evidence 4 h ago · details". Row F not pictured: UNKNOWN. |
| 19 | Ink tokens, hairline cards, flat background, monogram vendor chips, member token | Done differently | Flat background, hairline cards, outlined monogram chips with swatch: as meant. Status text appears darker than the fills; exact hex UNKNOWN. Member token not pictured. The peach edge tint is still present in some captures; whether it is the app remains UNKNOWN. |
| 20 | Dark and wall modes | Done as meant | Both present. Dark table scrollbar is bright; wall queue panel is mostly empty. |
| 21 | Compact density toggle | Not visible | The list icon beside the theme toggle may be it: UNKNOWN. |
| 22 | Cluster context strip | Done as meant | "This cluster in: Inventory · Configuration · Backups · Readiness" on Devices and Configuration. |
| 23 | Since-yesterday deltas | Done as meant | "−24 since yesterday", "−83", "−8", "no history" where none. |
| 24 | Control families table, export cover fields | Table done as meant | Families with controls, checks, stacked bar, coverage, findings. Export contents UNKNOWN. |
| 25 | Per-cluster evidence export with ORIGIN | Button done | "Export evidence (CSV)" on the cluster header. Whether ORIGIN is a column: UNKNOWN. |
| 26 | State vocabulary beyond LOCAL and MEMBER | Not done, declared | Tile says "expected member-specific settings not yet separated". Roadmap NEXT item. |
| 27 | Global search | Done as meant | Top-bar search with per-setting results, device counts and masked examples. Panel is narrow. |

Tally: 15 done as meant, 6 done differently or partly, 1 not done, 5 wholly or mostly UNKNOWN.

## 2. Astra's review

**Where I agree.**
- **Job arithmetic.** 1018 submitted, 971 completed, 45 failed leaves 2 jobs in no pictured category, and the Overview says 1016. The History filter lists OUTCOME_UNKNOWN and REJECTED states, so those are the likely home. Show every terminal state and the success-rate denominator. P0.
- **The 78-link paragraph** on Configuration is the worst readability problem left. Most of the 78 are per-interface Auto Negotiation and Link Speed entries, which look member-specific; until the classifier separates them, the count reads as 78 faults. Group by section with expandable two-member tables. P0.
- **Tile titles truncate** in the newer build, worse on dark and at wall distance. Wrap, never ellipsize. P0, small.
- **"All evidence 4 h ago" beside "1 never read".** The chip means the latest run; say "Latest evidence 4 h ago". Small.
- **Backups vocabulary.** Snapshot, Backup Now, V1, FIRST, CHANGED and the star on the first History link need meaning at the point of use. Snapshot appears on Check Point rows only and nowhere is that explained.
- **Compliance units.** Family "findings" are failing checks; "172 critical deficiencies" are controls. Label both. Data gaps needs a click-through to the affected checks.
- **Devices identity table** needs a sticky member column and a scroll cue.
- **System tab.** "Refreshed every 15 s" is an interval; add "Observed at". Show "<1m" instead of a rounded "0m".
- **Delivery plan.** Human titles first, internal keys second; keep "Freshness: UNKNOWN" beside the percentage.
- **Search panel** is too narrow; group results and name the destination.
- **Dark and wall tuning.** Scrollbar, secondary text contrast, empty queue allocation.
- **Failed quick view** on Jobs; the Job history link should land on the FAILED filter.

**Where I disagree, and why.**
- **A new ranked exception register above the tiles.** The three-line headline and six tiles are that register: Act now, Review, Evidence, each tile with scope, evidence age and a click-through. Astra also asks for a responsible team per exception; the product records no owner, so that field would be invented. Add a link per headline line to its filtered list and stop there. P2.
- **Replacing the donuts with horizontal bars.** The donuts obey the rules in force, sit below the fold, and now separate UNKNOWN by hatch. The change is medium effort for a small gain. Optional P2.
- **A minimum of 14 px for ordinary evidence.** Astra's own rule allows 13 px in dense tables, which is where the evidence lives. Keep the 13 px table scale; enforce wrapping for headings instead.
- **Collapsing the identity table in Configuration.** It is the only place identity and configuration sit together for a diff, and the auditor needs both in one export. Keep it expanded.
- **The NOT EVALUATED column making the HA table look like inventory work.** The per-cluster row is required capability. Add a one-line state panel above the table; keep the column.

**What Astra saw that I missed.**
- The two unaccounted jobs and the 1016 versus 1018 mismatch.
- Snapshot appearing on only one vendor without explanation.
- The findings versus deficiencies unit ambiguity.
- The title truncation regression, caused by my own six-tile row at this width.
- Interval versus observation time on the System tab, and the "0m" rounding.
- The narrow search panel, the bright dark-mode scrollbar, the empty wall queue panel, and internal keys as titles on the Delivery plan.

**What I saw that Astra missed.**
- Header buttons still on restricted Admin pages.
- Configuration list and registry rows still at card height.
- Date pickers still in a different date order.
- Registry still lacks transport and credential profile columns.
- "Failed · 2" on Devices versus "0 devices" on Admin, still unreconciled.
- The regression is between builds, not a single design choice, so it needs a screenshot check in the release gate.

## 3. Final list

Effort: S under a day, M up to a week, L more.

| Pri | Screen | Change | Effort | Capability preserved |
|---|---|---|---|---|
| P0 | Operations, Overview | Show submitted = completed + failed + outcome unknown + rejected + in flight; state the success-rate denominator; same total on both screens | S | Every job state, filter, polling, export |
| P0 | Configuration | Replace the difference link paragraph with per-section groups: count per section, expandable two-member value table; keep every link | M | All 353 settings, both member values, search, Differences only, CSV |
| P0 | Configuration, Overview | Separate member-specific differences from the rest; label the remainder "unreviewed" until classified; carry the split into the Overview tile | L | LOCAL and MEMBER kept; every difference still listed |
| P0 | Overview, dark, wall | Tile titles wrap; add a screenshot check per build so the regression cannot return | S | All six tiles |
| P0 | Admin | Verify Local identities, Sessions, Roles, Audit Logs use the Restricted component (UNKNOWN today) | S | Every RBAC boundary |
| P0 | Operations | Verify cluster detail: failover buttons explained, empty header hidden, Queue copy (UNKNOWN today) | S | 4-Eyes flow, all tabs |
| P1 | Backups | Explain Snapshot, Backup Now, V1, FIRST, CHANGED, History star inline or on focus; state which vendors support Snapshot; add "Targets without archive 19 of 102" as the first card | S | Toggle and all six actions |
| P1 | Compliance | Unit labels: "failing checks" on families, "critical failing controls" on the 172 card; data gaps chip opens the affected checks; family row opens the filtered list | S | Four framework cards, families, filters, export |
| P1 | Operations | "Failed" quick chip on Jobs; Job history link lands on FAILED; one-line "HA readiness NOT EVALUATED · 0 of 40" panel above the table | S | All jobs, all clusters |
| P1 | Devices | Sticky member column and scroll cue on the identity table; one line stating "105 devices · 40 clusters · n virtual systems" | S | All identity fields, tree, filters |
| P1 | Admin | Header buttons only in the Registry group; rows to 44 px; transport and credential profile columns; masked device ID and address on the UNKNOWN hostname row | M | Import, Add device, overflow actions, all states |
| P1 | Admin, Devices | Reconcile "Failed · 2" with "Last collection failed 0"; define both populations on the chip | S | Both counts kept |
| P1 | Admin System | "Observed at" timestamp; "<1m" for rounded zero; host-volume caveat directly under "157 GB" | S | All pod and storage figures |
| P1 | Overview | "Latest evidence 4 h ago" wording; absolute time on tile hover; state whether "as of" is collection, evaluation or refresh time | S | Freshness detail chips |
| P1 | Global search | Wider panel grouped by result type, full setting path, named destination | M | Every result, masking |
| P1 | Configuration | List rows to 44 px | S | Every list item |
| P1 | All | Date pickers to yyyy-mm-dd | S | Date filters |
| P1 | Admin Delivery plan | Human task titles first, keys secondary; "Freshness: UNKNOWN" beside the percentage; define Done versus Automated Validated | S | All roadmap fields |
| P2 | Dark mode | Muted scrollbar, tuned secondary text and border tokens, visible focus ring | S | Identical layout and data |
| P2 | Wall | Compact queue when empty, more room for causes, larger state words | S | All metrics, queue, exit |
| P2 | Admin Restricted | One sentence on where to request the role, no credential metadata | S | RBAC boundary |
| P2 | All | Compact density toggle, spacing only | M | Everything |
| P2 | Compliance, Configuration | Verify export cover fields and ORIGIN column in CSV (UNKNOWN today) | M | Exports |
| P2 | Overview | Each headline line links to its filtered list; no owner field unless the product records one | S | Headline and tiles |
| P2 | Overview | Horizontal distributions instead of donuts, optional | M | Every category and link |

## 4. Fit for purpose

The deputy general manager and the manager now get what they need from the first screen: three ordered sentences, six tiles with denominators, deltas and click-throughs, and a wall view for the room. What still stands between them and confident triage is one number: the 38 of 39 cluster differences will alarm every morning until member-specific settings are separated, and the two-job gap in the 24-hour totals undermines trust in the rest. The operator is well served on Devices, Backups and Operations, and the cross-links remove the four-times-selection problem; the 78-link paragraph on Configuration is the one place the operator still cannot start work from the first viewport. The auditor has assured, observed, coverage, deficiencies and gaps as separate, defined quantities, framework and family views and an export, but cannot yet see from the pictures whether the export carries the as-of time, run identifier, per-firewall results and mask state, so audit fitness stays UNKNOWN until that file is checked. With the six P0 items done and the export verified, the product is fit for all four audiences.
