# neXus UI review · aiview build NXS-LOCAL-0368 · 2026-09-23

Scope: 31 live screenshots and 8 Material 3 artboards. The artboards were used for design elements only. Anything I could not judge from the screenshots is marked UNKNOWN.

## 1. Verdict

1. **What works:** the Overview is exception-first, every tile pairs a colour with a word and opens a filtered list, UNKNOWN is written out in most places, the side-by-side cluster configuration with an ORIGIN column is the strongest screen in the product, and the System tab shows what a dense evidence table should look like.
2. **What does not:** the first screen states the same three facts twice, counts disagree between screens, and status colour leaks into things that are not status: red job durations, green ACTIVE and yellow STANDBY role chips, blue vendor chips, a yellow chip on a zero.
3. **Also broken:** "0" and "—" stand in for NOT EVALUATED on Operations and Backups, refusals appear in five different styles including a raw `ACTION_REFUSED` code, timestamps come in four formats, and Admin has twelve tabs with a row-level Delete button on every device.
4. **Highest value, first:** one posture row and a rewritten headline on the Overview, so the manager reads action items in the first three lines and nothing twice.
5. **Then:** one state contract across all screens, where status colour is reserved for state, role and vendor are neutral, NOT EVALUATED and UNKNOWN are words, one timestamp format applies. **Then:** one empty, restricted and error component used by Admin and Operations.

## 2. Executive summary (Overview)

**Who reads it and how.** The deputy general manager and the manager read the first screen for under a minute. They need three answers in order: what must be acted on today, what is under review, and how much of the estate the evidence actually covers. The current screen answers all three but in the wrong order and twice.

**Duplication found on the first screen.** The four KPI tiles and the five attention tiles carry the same facts in two framings: evidence current and devices without inventory, backup targets protected and targets without archive, clusters in agreement and clusters with DIFF. Merging them loses nothing, because each merged tile keeps both the count and the percentage.

**Headline wording.** Three sentences, one per line, ordered Act now, Review, Evidence base. Numbers as "n of N", no adjectives, never lead with reassurance.

```
Act now   47 of 1019 jobs failed in 24 h, latest 2 h ago. 19 of 102 backup targets have no archive.
Review    38 of 39 active clusters show member differences. 2 devices changed configuration since the previous collection.
Evidence  Evidence read in 24 h for 102 of 103 active devices. Compliance evidence covers 82.5% of control checks: 172 critical deficiencies, 428 data gaps.
```

Each line starts with a bold word that is also the tile status word below it. The percentages stay on the tiles.

**Proposed first screen.** 12-column grid, 24 px gutter, content width as today.

| Row | Columns | Content | Height |
|---|---|---|---|
| A | 12 | Page title, subtitle "as of 2026-09-23 06:40 UTC · 103 of 105 devices active · 39 of 40 clusters active", mask chip, one freshness chip "All evidence 2 h ago" that expands to the six per-domain chips | 64 px |
| B | 12 | Headline card, three lines as above | 120 px |
| C | 6 × 2 | Posture tiles: Failed jobs 24 h, Backup targets without archive, Clusters with member differences, Configuration changed, Devices without evidence 24 h, Compliance deficiencies. Each: eyebrow, count "47" with "of 1019", status word chip, thin bar, one line of context, whole tile clickable | 150 px |
| D | 6 + 6 | Compliance by framework, stacked Pass, Fail, Unavailable bars with counts. Why jobs failed, grouped by cause | 320 px |

Rows A to D fit above the fold at 900 px. Everything else keeps its place but moves down:

| Row | Columns | Content | Note |
|---|---|---|---|
| E | 12 | Software and hardware, six donuts unchanged | Demoted: inventory facts, not exceptions |
| F | 4 + 4 + 4 | Configuration changes, Cluster member DIFF, Inventory evidence age | Fixes the empty card and wrapped names today |

**Tile details.** The compliance tile shows "172 critical deficiencies" as the count, "82.5% coverage · 428 data gaps" as context. The clusters tile shows "38 of 39 with differences · 1 in agreement" and, as soon as the Configuration screen's ORIGIN split is available at fleet level, "of which n LOCAL, n MEMBER". Until then, this tile will alarm every morning for reasons the Configuration screen already knows are expected.

**Small fixes on the Overview as it stands.**
- The Palo Alto "PAN-OS major" donut has one slice at 100%. A donut with one slice carries no information. Replace with a stat line "11.1 · 40 of 40" that keeps the click-through.
- "Other" and UNKNOWN both use the same grey. Keep both neutral but make UNKNOWN hatched or outlined so an executive can tell "many small groups" from "we do not know".
- The compliance card says "Observed 34.7%" while the headline and the Compliance screen say "28.9% assured". What "Observed" means is UNKNOWN. Either define both on hover or show one number.
- Cluster names in the DIFF card break over three lines. Give the cluster column a fixed 140 px and let the sections column wrap.
- The Configuration changes card is 90% empty. In row F it becomes a 4-column card and reads as a list, not a table.
- The top bar chip `0ec757dc8718 · 7m ago` has no label. What it identifies is UNKNOWN. Label it or move it under the avatar menu.

## 3. Screen by screen

### Compliance
- **Framework cards use a single orange bar** for pass share, while the Overview uses a stacked Pass, Fail, Unavailable bar for the same data. Use the stacked bar here too, same component, so the manager sees the same shape on both screens.
- **Two units share one word.** The cards count 2448 "controls" for CIS, the list below counts 48 controls. The 2448 are control checks, one per control per firewall. Label them "control checks" on the cards and "controls" in the list, and put "control × firewall" in the card subtitle.
- **Judgement chips beside numbers:** "Improvement Needed", "Immediate Action", "Missing Commands". The first two are fine as status words. "Missing Commands" is an internal cause. Replace with "Evidence not collected" and keep the cause on hover.
- **Page title** reads "Compliance & Security Posture" while the rail says "Compliance". Use one name.
- **Row layout is good.** Keep the framework reference chips, severity, firewall count, breakdown bar and outcome. Right-align the firewall count with tabular figures.
- **Add, from the M3 study:** a control families table between the cards and the list, with coverage per family and finding counts. This is additive and gives the audit reviewer a place to start.

### Backups
- **"Active Major Deviations: 0 · 0 comparisons run"** shows a zero where the truth is not evaluated. Write "UNKNOWN · 0 comparisons run". This is the rule already in force.
- **"Scheduled Fleet Backup: Off"** is the most important fact on the screen and is styled like any neutral value. Pair it with the warning colour and the word "Not scheduled", and keep the explanation line.
- **Two 102-row tables** on one screen: targets with toggles, archives with actions. Merge into one table per device: toggle, latest archive time and size, state chip, then the five actions. Nothing leaves the screen; the archive history stays behind the existing History link.
- **Archive rows are about 75 px tall** with a filled Backup Now button and four links per row. Set rows to 44 px, put actions in a fixed-width right column, keep all five. Backup Now can be a compact tonal button.
- **Timestamps** show microseconds: `2026-09-22T11:43:28.377470Z`. Show `2026-09-22 11:43:28 UTC` and keep the full value on hover and in export.
- **Vendor chip is accent blue.** Vendor is identity, not a link. Use the same monogram badge as the Configuration and Devices lists.
- **"V1" is a green chip.** What V1 means is UNKNOWN. If it is a format or schema version, it is not a status and should be neutral.
- **"FIRST" and "CHANGED" use the same grey-blue chip.** CHANGED is a signal. Give it the attention colour with the word; FIRST stays neutral.

### Operations
- **Readiness checks shows "—" on the HA tab and "0" on the other three tabs** with the text "not evaluated yet". Both violate the rule. Write "NOT EVALUATED" in the value slot and "40 clusters enrolled · 0 evaluated" beneath.
- **Duration is red on every completed job.** Red is reserved for failure. Duration becomes neutral mono with tabular figures. If long durations deserve attention, define a threshold and add the word "slow".
- **"Terminal Reason / Error" repeats "COMPLETED"** on completed rows. Show "—" there and keep the column for failures.
- **The cluster chip wall** on the HA tab lists 40 identical outlined chips with no state. Replace with a table: cluster, vendor, active member, standby member, last evaluated, readiness word. Every cluster stays; the chips' role as a picker moves into the table row.
- **Cluster detail shows an empty table header** with six column names and no rows under a NOT EVALUATED card. Hide the header until there are rows; the NOT EVALUATED card is the empty state.
- **"Authorize Failover (4-Eyes)" is drawn as a filled primary button** on a cluster that has not been evaluated, while the subtitle says no failover action exists in this build. Whether it is enabled is UNKNOWN. Render it as an explained control: outlined, with the line "Needs an evaluated readiness run" beneath, per the M3 rule that a capability which cannot run here is shown and explained.
- **Queue empty state says "No jobs matching filter"** while the KPI says 0 in flight. The user did not filter anything out. Say "Queue empty · 0 requested · 0 claimed · 0 executing" and hide the pager when there are zero rows.
- **Jobs and History filter rows differ:** Jobs puts Search inline and Live polling right; History wraps Search onto a second line and puts the buttons right. Use one filter bar.
- **The header button "Job history" duplicates the History tab.** Keep it as a text link inside the KPI card for Failed jobs instead.
- **The same job table appears three times:** Jobs, History, and Admin › Job Logs. Make it one shared component so the three stay identical.

### Configuration
- **Two "All 105" chips** sit in two rows for two filter dimensions. Label the rows: "Vendor: All 105 · Check Point 65 · Palo Alto 40" and "State: All 105 · Changed 2 · First run 0 · Not collected 3".
- **Every list item shows the same green "✓ Live" chip.** When all 105 are identical the chip is noise. Show the chip only when the state differs from Live, and put the count in the State filter row.
- **List rows are about 72 px tall,** so eight of 105 fit. Use 44 px rows; 18 fit.
- **The VSYS column repeats all eight names per member,** and the same list sits in the Platform identity line above. Row height reaches 150 px. In the member table show "8 · same as cluster" with a disclosure; if the two members differ, show the difference inline. The full list stays in the Platform identity line.
- **HA role chips:** in the header both "PASSIVE" and "ACTIVE" are yellow; in the member table PASSIVE is yellow and ACTIVE green. Role is not health. Use neutral role chips: ACTIVE filled dark, PASSIVE or STANDBY outlined, both with the word. Reserve colour for a member that is down or unreachable.
- **Content versions show "Threats 0 · WildFire 0 · URL filtering 0000.00.00.000".** Whether the device reports zero or the value was not collected is UNKNOWN. If reported, show "0 (as reported)"; if not collected, UNKNOWN.
- **"Differences only" is off by default** on a cluster with 299 settings and 3 differences. Default it on when differences exist, and add a strip above the sections listing the differences as jump links. The full view remains one toggle away.
- **Section cards in a two-column grid** leave uneven gaps. Use a single-column accordion: one row per section with "n settings · n diff", collapsed when diff is 0, expanded when not.
- **Adopt the M3 state vocabulary** for the ORIGIN column once the classifier supports it: Member-specific, Local override, Difference observed. Today's LOCAL and MEMBER stay as the first two.

### Devices
- **Tabs show while nothing is selected.** Hide Interfaces, Routing, Cluster members and Identity until a device is chosen.
- **"Interfaces · 2"** with "Active only" on, while the header says 7 interfaces. Write "2 of 7 shown · Active only".
- **Green dots after member addresses** carry meaning by colour alone. What they mean is UNKNOWN. Either add the word on hover and in the State column, or remove the dots since State exists.
- **Member column order** is M2 then M1 on ALPHA-01 but M1 then M2 on MIKE-07 in Configuration. The rule is UNKNOWN. Fix it to M1, M2 always, and let the role chip say which is active.
- **HA role colours** disagree with Configuration and with themselves: STANDBY yellow in the member card, grey in the member table. Same fix as above.
- **Identity & provenance holds one sentence.** Put the per-member identity table here: serial, model, software version, hotfix or content versions, uptime, HA role, management address, VS list, first seen, last read, evidence source. Configuration keeps its copy; both render the same component.
- **Cluster count chip "Clusters 40"** while the Overview says 39. Write "40 enrolled · 39 active" wherever the count appears.

### Administration
- **Twelve tabs in one row.** Group into four: Registry (Device management, Inventory exclusions, Credentials), Access (Local identities, Sessions, Roles & Permissions, LDAP Settings), Platform (System, Notifications, Delivery plan), Records (Audit Logs, Job Logs). Use a left sub-navigation inside Admin, as in the M3 drawer. Every tab stays reachable.
- **"Import from manager" and "Add device" appear on all twelve tabs.** Show them only in the Registry group.
- **Device registry shows a filled Delete button on every row,** 105 times. Move Delete into the row overflow menu and into the bulk bar that appears on checkbox selection, with a confirmation dialog. The capability stays; the risk of a stray click goes.
- **Registry has only name and status.** Add vendor, transport, credential profile name, last contact, enrolment state, as in the M3 registry table. Additive.
- **A device named "Unknown"** is listed as Enrolled. Under the product's own rule this reads "UNKNOWN hostname" with a chip explaining why it has no name.
- **"Last collection failed: 0 devices" is a yellow chip.** Zero is neutral. And the Devices screen says "Failed 2 of 105". One of the two is wrong; which is UNKNOWN.
- **Inventory exclusions copy** says "no device has been excluded yet, because no device has been enrolled yet" while 105 are enrolled. Copy bug; remove the second clause.
- **Refusals appear in five styles:** Credentials shows a sentence plus Retry; Local identities shows `ACTION_REFUSED` plus Retry; Sessions and Audit Logs show `ACTION_REFUSED` with no Retry; Roles shows a red error banner, "No roles found", and two filled buttons the role cannot use; LDAP and Notifications show a blue information banner with a clear sentence. The LDAP pattern is the right one. See section 4 for the component.
- **Project plan** is a developer roadmap with a source hash and a full-width Refresh button, visible to the aiview role. Rename it "Delivery plan", place it under Platform, keep every field, and make Refresh a normal secondary button.
- **System tab** is the reference for density: 36 px rows, mono identifiers, bars with numbers. The artefact volume footnote is long but correct; keep it.

## 4. Consistency across screens

**Navigation and titles.** Rail labels and page titles disagree: Devices versus Network inventory, Config versus Configuration, Compliance versus Compliance & Security Posture, Backups versus Backups & Recovery, Admin versus Administration. Use the page title as the rail label, shortened only if it does not fit. The 80 px icon rail itself is good and matches the M3 rail; keep it. What the hamburger button at the top of the rail does is UNKNOWN.

**Header actions.** One filled button per page, for the audience's main task. Write actions are filled (Run Fleet Backup, Schedule collection, Add device, Re-evaluate). Exports are tonal, never filled, except on Compliance where the reviewer's task is the export. Collect All on Configuration is outlined today while Bulk Collect on Devices is outlined and Add device is filled; make Collect All and Bulk Collect the same tonal button.

**Filters and chips.** Three chip styles exist: Devices selected chip is filled blue, Configuration selected chip is green-tinted, Compliance uses "All (48)" with parentheses. Use one: selected is the accent-soft container with accent text, count written "Label · n". Enumerations up to six values are chip groups; longer lists use the select component seen on the Jobs tab. Label every chip row with its dimension.

**Tables.** Header casing is caps on Configuration and Admin, sentence case on Operations and Backups. Choose caps 11 px at 0.04 em tracking for every table. Rows 40 to 44 px, compact 36 px. Numbers right-aligned with tabular figures; today the settings counts in the DIFF card are left-aligned. Identifiers, addresses, hashes and timestamps in mono. Whether headers are sticky is UNKNOWN; they should be.

**Timestamps.** Four formats today: "2 h ago", ISO with microseconds, "9/22/2026, 10:57:54 PM", and "dd.mm.yyyy" in date pickers. One format: `2026-09-22 22:57:54` with the zone declared once in the top bar, relative age in muted text after it where useful, full precision on hover and in export. Date pickers use the same order.

**Vendor identity.** Configuration and Devices use monogram badges CP, VSX and PAN in magenta, indigo and brown. Overview uses a coloured top border per vendor card. Backups uses an accent-blue chip. Use the monogram everywhere. Vendor is never a status colour.

**Role and status.** HA role is a role. ACTIVE, STANDBY and PASSIVE become neutral chips with distinct fill weights. Status colour is used for state words only: Live, Failed, Not collected, Changed, Unreachable.

**Empty, not evaluated, restricted and error states.** Six variants exist today. Replace with one component, four variants:

| Variant | When | Icon | Copy pattern | Action |
|---|---|---|---|---|
| Empty | Nothing exists yet | none | "No device excluded." plus one sentence on what would create one | The creating action if the role has it |
| Not evaluated | Evidence has not been produced | clock | "NOT EVALUATED · the preflight API returned no checks" | The evaluating action, explained if unavailable |
| Restricted | RBAC refusal | lock | "Restricted · needs the Security Admin role. This account can view nothing here." | None. No Retry. |
| Error | Transient failure | warning | Human sentence, error code in mono beneath | Retry |

The refusal is intended behaviour, so it must not look like a fault. Buttons the role cannot use are rendered outlined with the reason beneath, never as filled primaries.

**Typography scale.** The live font family is UNKNOWN from screenshots. Whatever it is, fix the scale: display 32/40 for page KPIs, headline 22/28 for page titles, title 16/24 for cards, body 13/20, label 11/16 caps, mono 12/18. Weights 400, 500, 600 only. Tabular numerals on.

**Density.** Configuration and Devices lists, Backups archive rows and the Admin registry all sit at 60 to 75 px per row. Operations and System sit at 36 to 40 px. Bring the first group down; add a compact toggle later that changes spacing only.

**Mask indicator.** "aiview · names masked" sits in the Overview header and "AIView Pseudonymized" appears once on the Operations cluster header. Show the global chip on every page header and put the per-record chip in exports only.

## 5. Palette and design system

The design folder contains two systems. The Palette artboard proposes low-chroma neutrals, one cobalt accent, hairline borders, IBM Plex Sans and JetBrains Mono. The M3 artboards use Roboto, tonal surfaces, 16 px radii and elevation. The live product sits between them: lavender-tinted page background, white cards with soft shadows, a royal-blue accent, and a soft peach vignette at the viewport edges. Whether that vignette is the application or the capture is UNKNOWN.

**Recommendation.** Take neutrals, hairlines and mono usage from the Palette study, the state vocabulary and explained-control rules from the M3 study, and keep the status and categorical palettes already in force. Do not adopt the M3 success and warning hex values, since they contradict the status palette rule. Do not adopt the M3 Overview's "reaches end of life" note; it violates the no-outdated-judgement rule.

**Contrast findings.** The status palette works for fills, bars and dots. Three of the five values fail as text on white. Values below are computed from sRGB relative luminance and should be confirmed in tooling.

| Colour | Hex | On white | Text use |
|---|---|---|---|
| good | #0ca30c | 3.4:1 | fails body text |
| warning | #fab219 | 1.9:1 | fails all text |
| serious | #ec835a | 2.6:1 | fails all text |
| critical | #d03b3b | 4.8:1 | passes |
| neutral | #94A3B8 | 2.6:1 | fails all text |

**Token changes.** Live hex values are read from screenshots and marked approximate.

| Token | Current (live, approx.) | Proposed | Why |
|---|---|---|---|
| bg | lavender gradient ~#eef1fb | #f5f6f8 flat | Gradient and vignette reduce print and screenshot legibility; neutral bg lets status colours own attention |
| surface | #ffffff with shadow | #ffffff, 1 px #e3e7ec border, no shadow | Hairlines scale to dense tables and dark mode; shadows do not |
| radius | ~16 px | 10 px cards, 6 px chips | Less air per card, more rows per screen |
| accent | royal blue ~#2d5bd9 | #3457d5 | Palette study value, 6.0:1 with white, separable from categorical slot 1 #2a78d6 by hue and darkness |
| accent-soft | ~#e8edfb | #eaeefb | Selected chips, active nav |
| text | ~#111827 | #171b22 | 15.6:1 on white |
| muted | ~#6b7280 | #5f6978 | 5.9:1, safe for 12 px table text |
| good-fill | #0ca30c | keep | Rule in force |
| good-ink | #0ca30c used as text | #087a08 | 5.5:1, status text on white |
| warning-fill | #fab219 | keep | Rule in force |
| warning-ink | UNKNOWN | #8a5a00 | 5.9:1 |
| serious-fill | #ec835a | keep | Rule in force |
| serious-ink | UNKNOWN | #b0421a | 5.8:1 |
| critical-fill | #d03b3b | keep | Rule in force |
| critical-ink | #d03b3b | keep, 4.8:1 | Passes |
| neutral-fill | #94A3B8 | keep | Rule in force |
| neutral-ink | UNKNOWN | #5b6473 | 6.0:1, for UNKNOWN and Other text |
| unknown-pattern | none | 45° hatch over neutral-fill | Separates UNKNOWN from Other in donuts without a new colour |
| member | yellow chip ~#fef3c7 | #7a5c12 on #fbf4dc | Member-specific is its own token, decoupled from warning, per Palette study |
| vendor-cp, vendor-vsx, vendor-pan | magenta, indigo, brown badges | #c2338a, #4a3aa7, #d9731a as 7 px swatch in an outlined monogram chip | Identity, not health; passes CVD pairs per Palette study |
| mono | used for IDs and values | JetBrains Mono or equivalent 12/18 | Evidence looks like evidence |

**Dark mode.** Recommended, for the NOC wall and for night shifts. Tokens: bg #0f1216, surface #161a20, surface-2 #1c2129, line #272e38, text #eef1f5, muted #a5adba, accent #7b9cff. Status fills keep their hue and lift lightness by about 10%; exact values to be validated for 3:1 against #161a20. Vendor swatches per Palette study dark row.

## 6. Fit-for-purpose placement

**Morning review, manager and team leads, five minutes.** Overview rows B and C, then Why jobs failed, then Cluster DIFF. Add to each posture tile a "since yesterday" delta as text, for example "+3". Whether history is stored to compute it is UNKNOWN.

**NOC wall, 1920 × 1080, unattended.** A wall mode of the Overview: dark tokens, no rail, no header actions, three rows only: headline, six posture tiles, failed causes beside the queue. Refresh every 60 s with a visible "as of" clock. Masking follows the logged-in role, as today. Nothing new is computed; it is a layout of existing tiles.

**Audit evidence export, compliance reviewers.** Export Audit Report exists. The file needs a cover with the as-of time, evidence run identifier, frameworks evaluated, device scope with active and enrolled counts, and the mask state. Body: per control, per firewall, pass, fail or unavailable with the evidence timestamp; a data-gap list with the reason per gap. Whether the current export contains these is UNKNOWN. A per-cluster configuration evidence export with the ORIGIN column is not visible anywhere; add it as a secondary button on the cluster header.

**Incident, an operator on one cluster.** Today the operator picks the same cluster four times: in Devices, Configuration, Backups and Operations. Add a context strip on every cluster header with links "Inventory · Configuration · Backups · Readiness" that carry the selection. Layout per screen is unchanged.

**Compliance reviewer, weekly.** Compliance screen with the families table from section 3, then the control list filtered to Failing, then export.

**Administrator, onboarding.** Admin › Registry group. Add device, Import from manager, Credentials, Exclusions are one group and the header buttons live only there.

## 7. Prioritised changes

Effort: S under a day, M up to a week, L more.

| Pri | Screen | Change | Effort | Capability preserved |
|---|---|---|---|---|
| P0 | Operations | Readiness checks "0" and "—" become NOT EVALUATED with "0 of 40 evaluated" | S | Count appears once evaluated |
| P0 | Backups | Active Major Deviations "0" becomes "UNKNOWN · 0 comparisons run" | S | Count appears once compared |
| P0 | Operations, Admin Job Logs | Duration neutral mono; Terminal Reason "—" on completed rows | S | All columns kept |
| P0 | Admin, Operations | One empty, not evaluated, restricted, error component; drop Retry on refusals; humanise `ACTION_REFUSED`; render unusable filled buttons as explained outlined controls | M | Every RBAC boundary and refusal kept |
| P0 | Admin | Fix Inventory exclusions copy | S | none affected |
| P0 | All | One denominator language "n active of N enrolled"; one as-of time; reconcile 39/40 clusters, 46/47 failed, 63/65 Check Point, 0/2 failed collections | M | All counts kept, now labelled |
| P0 | Devices, Configuration | HA role chips neutral and identical on both screens | S | Role shown on every member |
| P0 | Overview | Merge KPI and attention tiles into six posture tiles; rewrite headline; move donuts below the fold | M | Every figure, percentage and click-through kept |
| P0 | All | One timestamp format with zone, full precision on hover and export | M | Microsecond precision kept in export |
| P0 | Operations | Authorize Failover rendered as explained control on NOT EVALUATED clusters | S | Button and 4-Eyes flow kept |
| P0 | Admin | "Unknown" registry row shown as UNKNOWN hostname with reason chip | S | Row kept |
| P1 | Compliance | Stacked framework bars shared with Overview; "control checks" versus "controls"; define or unify "Observed 34.7%" | M | All counts kept |
| P1 | Backups | One per-device table; 44 px rows; V1 neutral; CHANGED attention; vendor monogram; Not scheduled warning word | M | Toggle, five actions, history all kept |
| P1 | Configuration | VSYS column collapsed with disclosure; Differences only default on with jump strip; section accordion; labelled filter rows; Live chip only when not Live | M | Full list one toggle away; all settings visible |
| P1 | Devices | Identity table on the Identity tab; tabs hidden until selection; "2 of 7 shown"; dots labelled or removed; fixed member order | M | All identity fields shown, plus evidence timestamps |
| P1 | Operations | HA cluster table replaces chip wall; empty header hidden; queue empty copy; one filter bar; shared job table | M | All 40 clusters and all filters kept |
| P1 | Admin | Four groups with sub-navigation; header buttons only in Registry; Delete in overflow and bulk bar with confirmation; registry columns added; Delivery plan under Platform | M | Every tab, every field, Delete kept |
| P1 | Overview | Single-slice donut to stat line; UNKNOWN hatch; freshness chips collapsed; row F column widths | S | Every slice and click-through kept |
| P1 | Design system | Ink tokens for status text; hairline cards; flat background; monogram vendor chips; member token | M | Status palette unchanged |
| P2 | Overview | Dark and wall modes | L | Same tiles |
| P2 | All | Compact density toggle | M | Spacing only |
| P2 | Devices, Configuration, Backups, Operations | Cluster context strip carrying selection across screens | M | Each screen's list kept |
| P2 | Overview | "Since yesterday" deltas on posture tiles, if history is stored (UNKNOWN) | M | Additive |
| P2 | Compliance | Control families table; export cover sheet fields | M | Additive |
| P2 | Configuration | Per-cluster evidence export with ORIGIN | M | Additive |
| P2 | Configuration | M3 state vocabulary beyond LOCAL and MEMBER once classified | L | LOCAL and MEMBER kept as first two states |
| P2 | All | Global search across devices, settings and evidence, per M3 study | L | Additive |

Items marked UNKNOWN above need one answer each from the product before they can be finalised: what the top-bar hash chip and the V1 chip denote, whether history is stored for deltas, what the current audit export contains, whether the failover button is truly enabled, and whether the edge vignette is part of the application.
