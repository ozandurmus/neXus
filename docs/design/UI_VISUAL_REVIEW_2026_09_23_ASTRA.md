# neXus UI effectiveness review

## 1. Verdict in five lines

1. **Partly fit for purpose:** the interface supports inspecting the firewall estate, but several summaries still require expert interpretation before they can support a decision.
2. **Strongest foundations:** persistent device identity, cluster context, explicit evidence gaps, cross-module links, and a clearly explained restricted-access screen.
3. **Highest-value change 1:** give every headline a precise scope, denominator, time window, and evidence source; resolve ambiguous compliance, job, and backup summaries.
4. **Highest-value change 2:** make Configuration open on the actual member comparison, with expected differences separated from differences requiring review.
5. **Highest-value change 3:** turn Overview and wall display into concise exception briefings, while retaining technical evidence and every existing action in linked detail.

## 2. Basis and limits

This review covers the 19 supplied screenshots. It evaluates visible information design and operational usefulness, not implementation correctness.

The screenshots show different build identifiers and capture times. Cross-screen differences therefore identify reconciliation needs, not proven defects in a single running build. Screenshots `05` and `06` show effectively the same Configuration viewport; lower configuration sections are **UNKNOWN**.

Interaction behaviour, keyboard support, responsive layouts, collection execution, export contents, server-side RBAC, masking completeness, restore execution, and compliance calculation logic are **UNKNOWN**.

Every recommendation preserves existing fields, filters, links, actions, permissions, and masking. Where something leaves the initial viewport, its destination is specified. Proposed business context—such as affected service, owner, or operational impact—must remain **UNKNOWN** unless supported by recorded data.

## 3. The first minute, by audience

| Audience | First minute today | Questions still unanswered | Change |
|---|---|---|---|
| **Deputy GM** | Sees 45 failed jobs, 19 targets without archives, 38 clusters with differences, and 172 critical deficiencies. The screen presents urgency, but mixes workflow failures, evidence limitations, and configuration differences. | Which conditions represent confirmed exposure? Which are measurement gaps? What changed materially? Which business services are affected? Service impact is **UNKNOWN**. | Make Overview’s first viewport a briefing with **Action required**, **Review required**, and **Evidence gaps**. Each row needs a plain-language condition, affected population, observation time, and destination. Retain the full framework, failure-cause, and inventory distributions below. Do not invent business impact or a combined score. |
| **Manager** | Can identify collection failures and missing archives, then enter module-specific screens. There are several totals but limited visible explanation of their relationship. | Are failures concentrated on a few devices? Are they recurring? Does an archive meet the required recovery need? Which work has an assigned owner? Assignment support is **UNKNOWN**. | Show both **failed jobs** and **distinct affected devices** where available. Add a comparable-period definition to existing deltas. Keep ownership and recurrence in exception detail only when supported; otherwise show **UNKNOWN**. Link briefing rows to the corresponding filtered list. |
| **Operator / security admin** | Finds device identity, members, interfaces, configuration, backups, and jobs. However, Configuration spends its first viewport on identity, while Backups presents many actions at equal emphasis. | Which exact setting differs? Is the difference expected? Which collection produced it? What does `V1` establish? What does a readiness result permit? | Put comparison rows first in Configuration; retain identity in an expandable member panel. Use a stable selected-device context across modules. Group backup actions by task and explain validation levels. Keep raw errors and identifiers in job details, with readable summaries in lists. |
| **Auditor / compliance reviewer** | Sees framework totals, evidence coverage, control families, individual controls, and report export. This is a useful starting structure. | What population produces the headline percentages? Are deficiencies checks or unique controls? Which unavailable evidence is excluded or counted against the result? Can the exported report reproduce the screen? Export fidelity is **UNKNOWN**. | Put framework, population, calculation basis, and evaluation time beside the headline. Separate control counts from device-control checks. Make each result traceable to its observed evidence and evaluation. Preserve all framework mappings and export actions. |

## 4. Screen-by-screen assessment

### 01 — Overview: useful exception detection, insufficient decision context

**What works**

- The three-line briefing gives the page a purpose beyond displaying inventory totals.
- Counts include useful denominators: jobs, backup targets, clusters, and active devices.
- Missing evidence is explicitly distinguished from configuration change.
- Framework results and grouped job failures provide plausible next steps.

**What remains ineffective**

- The briefing and six cards repeat much of the same information.
- “Act now” covers different situations without explaining the next decision.
- `38 of 39` clusters with member differences is visually alarming even though the card says expected member-specific settings have not yet been separated.
- “All evidence 4 h ago” sounds universal despite the page also reporting a device never read and substantial compliance data gaps.
- The full red bar under `172` has no stated denominator.
- `−24 since yesterday` and `−83 since yesterday` lack the comparison-window and population definition needed to interpret improvement.

**Concrete changes**

1. Replace the prose briefing with a compact exception table:

   | Attention | Condition | Scope | Evidence time | Destination |
   |---|---|---|---|---|
   | Action required | Backup targets without an archive | 19 / 102 targets | Time for this measure | Filtered Backups |
   | Review required | Failed collection or backup jobs | 45 / 1,016 jobs in 24 h | Latest failure | Filtered Jobs |
   | Review required | Clusters with observed member differences | 38 / 39 active clusters | Comparison time | Cluster comparisons |
   | Evidence gap | Active devices never read | 1 / 103 active devices | UNKNOWN | Filtered Devices |

   Preserve the critical-deficiency and configuration-change measures as additional rows or fully labelled tiles directly below. Attention wording should follow the actual condition, not a generic red treatment.

2. Keep all six metrics, but use a **three-column, two-row layout** if full labels do not fit. A metric title must not require hovering to identify the measure.
3. Rename the cluster measure **“Clusters with observed member differences”**, with **“Expected differences not fully classified”** immediately beside it until that classification is available.
4. Replace the universal freshness badge with **“Evidence times”** opening source-specific collection and evaluation times. The initial label should make its actual scope explicit.
5. Remove denominator-free progress bars. Retain numbers, denominators, timestamps, and links.
6. Put exact comparable periods and scope behind the existing daily deltas. If comparability cannot be established, write **UNKNOWN**.

### 02 — Software and hardware: good inventory facts, weak comparison efficiency

**What works**

- Vendor separation is appropriate.
- Counts, percentages, exact versions, hotfix takes, and models remain visible.
- A single PAN-OS major version is shown as text rather than an unnecessary chart.
- UNKNOWN has a distinct representation.

**What remains ineffective**

- Donuts duplicate their legends and make similar proportions harder to compare.
- The adjacent framework card inherits a large empty area from the much taller failure panel.
- “Other (8)” does not clearly say whether eight means models or devices.
- The chart colours could be mistaken for condition or severity, especially where green and orange appear.

**Concrete changes**

- Make ranked distribution lists the primary presentation: **Version / Devices / Share**, **Hotfix / Devices / Share**, and **Model / Devices / Share**.
- Each row retains the existing slice’s device-list click-through. Preserve exact values and vendor totals.
- Use neutral bars for magnitude, with printed counts and percentages. No version receives a favourable or adverse status colour.
- Rename grouped entries explicitly, for example **“Other models — 8 models, 17 devices”**, where those quantities are verified. Expanding the row reveals every original category and link.
- Keep **UNKNOWN** as its own row.
- Allow the software section to follow the shorter Overview content naturally; do not stretch a completed framework card to match a long failure list.

### 03 — Devices: cluster interfaces are useful; population labels need discipline

**What works**

- The side-by-side member cards and interface matrix support real cluster inspection.
- ACTIVE and STANDBY are written explicitly.
- Interface, VLAN, VIP, member addresses, network, and state appear together.
- Inventory, Configuration, Backups, and Readiness links maintain task continuity.
- VSX and VSYS children are visible in the device navigator.

**What remains ineffective**

- “105 devices enrolled” conflicts in wording with visible draft entries and Administration’s distinction between registered and enrolled devices.
- `Live`, `Active`, `Failed`, and `Stale` appear to describe different dimensions, but their definitions are not visible.
- `CLS > CLS-APOLLO-09` repeats type information instead of forming a useful breadcrumb.
- “The state chip shows only when a device is not Live” is an explanation of display behaviour rather than an operational fact.
- The navigator has its own scrollbar alongside the page scrollbar.

**Concrete changes**

- Use **“105 registered devices”** for the full registry population, and show enrolled, draft, and evidence-state counts separately.
- Define lifecycle, collection, freshness, and HA role as distinct dimensions. Do not silently use “active” for both estate inclusion and HA role.
- Replace the title with **“CLS-APOLLO-09”** and retain **Check Point ClusterXL** as the adjacent type label.
- Retain search, every vendor/scope/state filter, sorting, child systems, and selection in the left panel. Replace the implementation note with a concise count of the currently displayed population.
- Preserve the active-interface toggle and all rows. The count should explicitly state **“4 of 4 interfaces · Active only”**, as it already largely does.
- Keep member collection actions beside each member; keep Bulk Collect at page level. Their scopes should remain visually distinct.

### 04 — Devices: Identity & provenance promises more provenance than is visible

**What works**

- Identity is recorded per member rather than borrowed from the cluster.
- Serial, model, software, hotfix, uptime, HA role, management address, VSX, and enrolment are available.
- The explanation that unread values are UNKNOWN is appropriate.

**What remains ineffective**

- Horizontal scrolling begins even at this wide desktop viewport.
- Serial values wrap awkwardly.
- The visible table primarily shows identity. Collection source, observation time, and evidence reference are not visible; their availability elsewhere is **UNKNOWN**.

**Concrete changes**

- Default to a compact table with **Member, Model, Software / Hotfix, HA role, Observed at**.
- Put serial, uptime, management address, VSX, and enrolment in an expandable member-details panel.
- Preserve the current full comparison table through **“Compare all identity fields”**; retain horizontal scrolling there with the member column pinned.
- Keep serials and addresses unbroken, with copy access that preserves the persona’s masking.
- Give the provenance area explicit fields: **Observed at, Collection job, Evidence reference**. If a value is unavailable, display **UNKNOWN**.
- Explain that uptime and HA role describe the observation, not necessarily the present instant.

### 05–06 — Configuration: the main task is below the fold

**What works**

- Cluster identity, member roles, export, and cross-module links are available.
- The interface distinguishes current observed configuration from change since a previous collection.
- The “Differences only” control and setting/value search are directly relevant.
- The page acknowledges expected member-specific values.

**What remains ineffective**

- A large identity table occupies the space needed for configuration comparison.
- The title is overloaded with vendor, cluster, identifiers, member names, roles, and the difference count.
- Seventy-eight differences are introduced through a dense cloud of links.
- Repeated-looking interface labels lack enough visible context to tell whether they are distinct settings.
- “353 settings across 2 members” does not establish whether 353 means distinct setting paths or member-setting observations.
- Screenshot `06` does not reveal the promised lower sections. Their comparison readability is **UNKNOWN**.

**Concrete changes**

1. Reduce the heading to **“CLS-APOLLO-09 · Configuration”**. Keep vendor/type, observation times, and the difference count in a second line.
2. Move the full identity table into **“Members · 2 — Show identity”** in the header. Every identity field remains available.
3. Start the main viewport with:

   | Setting | Member 1 value | Member 2 value | Classification |
   |---|---|---|---|
   | Fully qualified setting path | Observed value | Observed value | Difference requiring review / Expected member difference / UNKNOWN |

   Pin the setting column; show member names and observed HA roles in the column headers.

4. Replace the link cloud with a section navigator: **Section / Differences requiring review / Expected member differences / UNKNOWN classification**. Each count opens the exact corresponding rows.
5. Preserve “Differences only”, search, all settings, and existing anchors. Offer explicit access to expected member differences rather than removing them from view.
6. Do not deduplicate repeated-looking labels until their complete contexts are known. Include virtual-system and interface context where needed.
7. Label the total according to its real unit: **“353 distinct setting paths”** or **“353 member-setting observations”**. The correct interpretation is **UNKNOWN** from these images.
8. Use **“Observed configuration”** instead of “Current actual” unless freshness supports that stronger claim.

### 07 — Compliance summary: strong structure, ambiguous headline meaning

**What works**

- Passing, failing, and unavailable evidence are separated.
- Framework cards expose numerators and totals.
- Evidence coverage is distinct from passing results.
- Control-family grouping creates a useful route into detailed findings.

**What remains ineffective**

- The headline `28.9%` matches the CIS card, but its relationship to all four frameworks is not explained.
- `428` data gaps also matches CIS unavailable checks. Whether this is a selected framework, deduplicated measure, or another scope is **UNKNOWN**.
- “Critical deficiencies” is explained as “High priority failing controls”; severity and counting unit are ambiguous.
- The visible family rows total 2,448 checks, matching the CIS total, while the caption describes NIST-family grouping. Framework scope and grouping taxonomy need separate labels.
- The framework charts use red and green as status colour outside state words, contrary to the stated rule.

**Concrete changes**

- Put a visible **evaluation scope** above the four headline cards: framework, included population, and evaluation timestamp.
- Retain all four framework cards; do not create an overall blended compliance score.
- Replace unexplained headline language with explicit measures:
  - **Passing checks / All assigned checks**
  - **Checks with evidence / Assigned checks**
  - **Failing checks at [actual severity]**
  - **Checks without required evidence**
- Retain the existing “assured” and “observed” terminology in calculation details if those terms are required, but show each formula and denominator.
- Label the family section, for example, **“CIS checks grouped by NIST control family”** only if that is the actual scope. Otherwise use the verified scope.
- Preserve segment counts and chart interactions, but use non-status fills or patterns for chart segments. Colour may appear on the words **PASS**, **FAIL**, and **UNAVAILABLE**; numbers remain neutral.
- Make the audit export state its selected scope and evaluation time. Whether it currently does so is **UNKNOWN**.

### 08 — Compliance findings: useful detail, difficult count semantics

**What works**

- Control titles, technical IDs, framework mappings, severity, device population, results, and detail links are retained together.
- Search and severity/framework filters address meaningful reviewer questions.
- Evidence gaps are accessible separately from failing checks.

**What remains ineffective**

- `All · 48`, `Failing · 38`, and `Data gaps · 8` do not specify that these appear to be counts of controls rather than device-control checks.
- A control can potentially have passing, failing, and unavailable checks; the visible grouping rules are **UNKNOWN**.
- `30 · 32 · 0` requires users to remember the segment order.
- “Non-Compliant” can read as a statement about the whole estate despite mixed device outcomes.
- The small arrow is a weak entry point for the central audit task.

**Concrete changes**

- Label the filter unit explicitly: **“48 controls”**, with definitions for how mixed-result controls enter each group.
- Replace the numeric triplet with **“Pass 30 · Fail 32 · Unavailable 0”**. Zero remains neutral.
- Keep outcome wording scoped to the control: **“FAIL — 32 of 62 device checks”**, where that accurately reflects the evaluation.
- Make the control title the main detail link; retain the arrow and existing navigation.
- Keep technical IDs and every framework mapping in a secondary line. Replace `+1 more` with an accessible expandable mapping list.
- Detail should connect the control requirement to the observed value, device/member, evidence time, and collection/evaluation reference. Whether that chain exists today is **UNKNOWN**.

### 09 — Backups: archive administration is visible; recoverability is not established

**What works**

- Stored archives are explicitly distinguished from inventory assumptions.
- Unscheduled backups are plainly disclosed.
- Target switches, missing archives, timestamps, sizes, and actions are visible.
- First archives and changed archives are distinguished.

**What remains ineffective**

- `83 devices with a stored backup` describes possession, not usable recovery coverage.
- “Retention horizon 14 days” could be mistaken for fourteen days of available history rather than a policy setting.
- `V1` provides no understandable validation assurance.
- `CHANGED` rows coexist with `UNKNOWN — 0 comparisons run` in the major-deviation summary. The relationship between these measures is unexplained.
- “All enrolled · 102” and “Targets · 102” need explicit population definitions.
- Six or seven inline actions per row flatten the hierarchy.

**Concrete changes**

- Rename the first card **“Targets with at least one stored archive — 83 / 102”** if that is the verified population. Preserve any broader archive count separately.
- Rename retention to **“Retention policy — 14 days”**. Show actual oldest/newest retained archive times in history; retain snapshot depth as a separate policy field.
- Expand `V1` into its actual named validation stage. Show the exact checks in details. Restore readiness remains **UNKNOWN** unless supported by evidence.
- Separate **Archive change detected** from **Semantic comparison result**. Keep `FIRST`, `CHANGED`, and all comparison actions.
- Keep **Backup now** visible per row. Group History, Contents, Compare, and Download in an **“Archive actions”** menu. Retain Snapshot for the vendors where it is available, visibly separated as a different action.
- Preserve all filters and target switches. Give the archive-less view the explicit label **“Targets without any stored archive · 19”**.
- Keep disabled actions visible where useful, with the reason **“No stored archive”**.
- Add per-row last-attempt status and time if available, so “never stored” can be distinguished from “latest attempt failed”. Otherwise show **UNKNOWN**.

### 10 — Operations: HA readiness is honestly unknown, but the purpose is overstated

**What works**

- NOT EVALUATED is explicit.
- Observed active and standby members are available.
- Cluster-level access is the correct starting point for readiness inspection.

**What remains ineffective**

- The subtitle advertises controlled failover operations while the page states that no class 2 action exists in this build.
- “Class 2” is internal terminology that does not explain what an operator can do.
- ACTIVE and STANDBY appear without visible observation times.
- Forty identical unevaluated rows occupy the screen without showing how to reach a readiness result.

**Concrete changes**

- Use **“Inspect observed HA roles and readiness evaluations”** as the subtitle for the visible capability.
- Replace the internal class statement with **“Failover is unavailable in this build.”**
- Keep every cluster row and member. Add **Observed at** for HA roles separately from **Last evaluated** for readiness.
- Provide a clearly labelled **“View readiness checks”** link on each cluster. If an evaluation action already exists, expose it in the detail panel with its existing RBAC and prerequisites; its existence is **UNKNOWN**.
- Retain NOT EVALUATED as a valid state and UNKNOWN for missing timestamps.
- Show evaluated/not-evaluated totals in a compact strip; keep zero neutral.

### 11 — Operations: Jobs has the right filters, but the summary must reconcile

**What works**

- State, type, device, date, and text filters are available.
- Duration, terminal reason, submission time, refresh, polling, and CSV export support investigation.
- Job identifiers remain accessible.

**What remains ineffective**

- `1,018 submitted`, `971 completed`, `45 failed`, and `0 in flight` leave two jobs unexplained in this screenshot. Their states are **UNKNOWN**.
- The `96%` success rate does not disclose its denominator.
- Long device UUIDs consume space needed for meaningful failure information.
- Internal job type strings dominate the list.
- The shared HA subtitle does not explain the Jobs tab.

**Concrete changes**

- Show a reconciled state breakdown containing every contributing state. Do not silently classify the missing two jobs.
- Label success as **“Completed / [defined population]”** and provide its time basis.
- Use a friendly job label, such as **“Collect configuration · Check Point”**, with the raw type retained in details, filtering, and export.
- Display the masked device name in the list; move its full UUID to copyable detail.
- Keep the short job ID visible and the complete ID accessible.
- Give terminal reason sufficient width. Show a readable summary first and preserve the exact raw error in an expandable detail.
- Keep all existing filters and polling controls. Add a visible last-refresh time; whether polling currently preserves scroll and selection is **UNKNOWN**.

### 12 — Administration: registry meaning is clearer here than elsewhere

**What works**

- Registry, access, platform, and records are sensibly separated.
- Enrolment is correctly described as granting read collection.
- Draft devices are explicitly separated.
- Collection scope explains the relationship between enrolment and backup targets.

**What remains ineffective**

- “UNKNOWN hostname” is the strongest visible identity for one row; distinguishing that device requires another stable identifier.
- Search, sorting, and bulk-selection consequences are not visible. Their availability is **UNKNOWN**.
- Each device is a rounded card inside a table, reducing the number of rows that can be scanned.
- `Last collection failed · 0` and the Devices `Failed · 2` label need distinct definitions; the screenshots do not establish whether they represent the same measure.

**Concrete changes**

- Use a conventional compact table with consistent rows, retaining checkboxes and all row menus.
- Show a secondary masked management address or stable registry identifier beneath UNKNOWN hostname; do not fabricate a hostname.
- Keep enrolment and collection outcome in separate columns.
- Define whether “last collection” means inventory, configuration, or another operation.
- Keep the enrolment summary above the table; move the longer collection-scope explanation into **“How collection scope works”** beside it.
- Retain Import from manager, Add device, and every existing bulk action.

### 13 — Restricted Credentials: fit for its visible purpose

**What works**

- The page names the required role and explains that the current account cannot view or change the resource.
- Restricted navigation remains discoverable.
- No sensitive fields are visible.

**Concrete changes**

- Keep the existing wording and boundary.
- Move registry-specific Import/Add actions out of the restricted Credentials content header and into Device management, retaining their authorised availability.
- Do not show masked credential placeholders that imply access to underlying values.
- Server enforcement and direct-route protection remain **UNKNOWN**.

### 14 — System: useful engineering detail, weak service-first presentation

**What works**

- Pod state, readiness, restarts, age, resources, and image references support technical diagnosis.
- Storage is separated by evidence category.
- The artefact-volume note explains the difference between the host disk and the declared claim.

**What remains ineffective**

- Pod names are the first-level information, although most users need to understand whether collection, evaluation, and evidence storage are available.
- CPU and memory percentages do not visibly identify whether the denominator is a request or limit.
- A completed bootstrap job’s `0/1` readiness can be mistaken for a faulty service.
- Host free space can be misread as space reserved for neXus.

**Concrete changes**

- Put a service list first: **Inventory/configuration collection, Compliance evaluation, Job processing, Database, Evidence storage**.
- Show each service’s observed state and check time only where supported; otherwise **UNKNOWN**. Do not infer end-to-end service health from a Running pod.
- Retain the full pod table under **“Infrastructure details”**, including image references and refresh.
- Separate completed one-time jobs from continuously running services without hiding them.
- Label resource ratios **Usage / Request** or **Usage / Limit**, according to the actual denominator.
- Rename the storage measure **“Host filesystem free space”** and retain the shared-host explanation directly beneath it.
- Keep all archive/storage breakdowns and timestamps below the summary.

### 15 — Delivery plan: useful internal transparency, misplaced operational emphasis

**What works**

- Recorded build, deployed version, source revision, and freshness uncertainty are distinguished.
- Roadmap progress is not represented as a delivery-time estimate.
- Current, next, and upcoming work are accessible.

**What remains ineffective**

- Engineering identifiers and backlog prose dominate a product administration screen.
- `Open backlog · 92` is difficult to reconcile with the displayed status counts, including Done and Automated Validated.
- A large completion percentage can look like product readiness or security assurance.
- Technical task identifiers wrap poorly.

**Concrete changes**

- Retain this page under Administration, but label it **“Product delivery and release details”**.
- Lead with **Deployed release**, **Recorded release**, and **Freshness**.
- Place raw revisions, movement identifiers, and source timestamps in **“Release provenance”**.
- Keep roadmap completion in a separate section labelled **“Declared acceptance-criteria completion”**; show its calculation basis.
- Distinguish **All tracked items**, **Open items**, and **Completed items** using the actual status model. Reconciliation is currently **UNKNOWN**.
- Give each task a readable title while preserving its exact engineering identifier in details.
- Retain refresh, all status categories, and all roadmap entries.

### 16 — Global search: valuable entry point, cramped result explanation

**What works**

- Search reaches configuration settings rather than stopping at device names.
- Results expose affected-device counts and example context.
- Query highlighting helps scanning.

**What remains ineffective**

- Long paths and values truncate in the narrow popover.
- A result such as “SNMP · Mode” across 55 devices does not make the destination obvious.
- The example device can be mistaken for the sole target.
- Keyboard operation and destination behaviour are **UNKNOWN**.

**Concrete changes**

- Widen the result panel beyond the input, within the viewport.
- Give each result two stable lines: **Setting path**, then **“Found on 55 devices · Open matching settings”**.
- Keep example devices and values as secondary preview content, clearly labelled as examples.
- Preserve existing result destinations. An aggregate result should expose its matching-device set without discarding direct-device access.
- Add **“View all results for ‘snmp’”** while retaining the quick results.
- Require the same masking in previews, expanded results, copied identifiers, and destinations. Current end-to-end enforcement is **UNKNOWN**.

### 17 — Dark Overview: coherent surfaces, persistent readability problems

**What works**

- Page, cards, navigation, and text have a coherent dark hierarchy.
- Primary text remains visually legible in the supplied image.

**What remains ineffective**

- Several metric titles truncate.
- Small secondary text is difficult to inspect at a glance.
- Status colour still fills bars and chart segments.
- The white rectangle around the logo appears visually detached.

**Concrete changes**

- Apply the same full-label layout and metric semantics as the light Overview.
- Retain brand artwork but use an approved dark-compatible or transparent asset.
- Use separate dark-theme surface, border, and text tokens rather than applying opacity to light tokens.
- Measure contrast in the actual application; compliance with contrast targets is **UNKNOWN** from the screenshot alone.

### 18 — Dark Configuration: theme does not solve the task hierarchy

The same identity-first layout and link cloud remain the primary obstacles.

Apply the Configuration restructuring from `05–06` in both themes. Preserve the full identity comparison, expected differences, all settings, exports, filters, and cross-links. Ensure pinned table columns, selection, focus, and expanded rows have explicit dark-theme treatments. Their interaction states are **UNKNOWN**.

### 19 — Wall display: insufficiently adapted for distance viewing

**What works**

- Application navigation is removed.
- The timestamp and exit action remain available.
- Queue state is separated from failures.

**What remains ineffective**

- The long opening paragraph requires close reading.
- Card headings truncate even in a dedicated wall view.
- Raw exception strings occupy prominent space.
- Several important annotations remain desktop-sized.
- The visible wall header does not carry the `aiview · names masked` indicator.
- The page extends vertically; automatic rotation or refresh behaviour is **UNKNOWN**.

**Concrete changes**

- Replace the opening paragraph with three short lines for **Action required**, **Review required**, and **Evidence gaps**, each with a few explicit measures.
- Use six fully named metrics in two rows of three, without truncation.
- Show readable failure categories such as **Archive transfer failed**. Retain raw errors in the standard Overview and linked Jobs detail.
- Show queue counts with neutral numerals, including zero. Keep state words explicit.
- Display **Dashboard refreshed at**, **Evidence observation times**, and **aiview · names masked** persistently.
- Create a viewport-fitting briefing. Retain deeper panels through deliberate page navigation or a secondary detail view; do not depend on unattended vertical scrolling.
- Target-distance legibility is **UNKNOWN** until checked on the intended display.

## 5. Consistency and design system

### Information vocabulary

Use one vocabulary across screens, with separate dimensions rather than one overloaded “state”.

| Dimension | Required presentation |
|---|---|
| Registry lifecycle | Registered, Enrolled, Draft; define the relationship between their populations. |
| Collection outcome | Completed, Failed, Running, and other actual job states. |
| Evidence availability | Available or UNKNOWN, with a reason where known. |
| Evidence freshness | Observed timestamp; age categories only with an explicit threshold. |
| HA role | ACTIVE, STANDBY, or the actual observed role; include observation time. |
| Evaluation state | EVALUATED / NOT EVALUATED, separately from the evaluation result. |
| Configuration comparison | Observed difference, expected member difference, or UNKNOWN classification. |
| Backup condition | Stored archive, validation stage, comparison result, and recovery evidence as separate facts. |

A valid state such as NOT EVALUATED should remain explicit. Its missing evaluation time is **UNKNOWN**. A measured zero remains `0`; absence of measurement must never become zero.

### Metric contract

Every summary component should retain five accessible properties:

**Measure · Population · Time window · Numerator/denominator · Evidence or calculation source**

For example:

> **Failed jobs — 45 / 1,016**  
> Last 24 hours · Counted by [actual timestamp basis] · Open matching jobs

Do not force all provenance into the visible tile. Keep the measure and scope visible; put calculation and source details in a consistent disclosure.

### Colour and states

The supplied charts repeatedly conflict with the stated rule that status colour belongs only to state words.

- Use status colour on words such as **FAIL**, **FAILED**, **PASS**, and **UNKNOWN** where a defined semantic treatment applies.
- Keep numbers, zero counts, chart bars, icons, and magnitude tracks neutral.
- Use patterns or non-status categorical fills for PASS / FAIL / UNAVAILABLE charts, with explicit labels and printed values.
- Use the primary blue for navigation, links, selection, and actions.
- Keep vendor markers categorical; avoid making them resemble health indicators.
- Show differences through a labelled classification column and a neutral row marker, not red values.
- Preserve ACTIVE/STANDBY words in both themes; neither should imply overall cluster health.

### Typography, spacing, and density

These are proposed tokens, not measurements of the current implementation.

| Token | Proposed use |
|---|---|
| Page title: 24/32 px | Module heading |
| Section title: 18/26 px | Comparison, findings, framework, or registry section |
| Body/table: 14/20 px | Operational information and controls |
| Secondary: 12/18 px | Supporting metadata; never the only place a critical state appears |
| KPI: 32/40 px, tabular numerals | Headline quantities |
| Identifier: 13/20 px monospace | Addresses, serials, job IDs, exact values |
| Spacing: 4, 8, 12, 16, 24, 32 px | Shared layout rhythm |
| Standard table row: 44 px minimum | Routine scanning; increase for wrapped content |
| Wall display: 24 px minimum essential text | Validate against actual viewing distance |

Use monospace only where exact characters matter. Friendly job names, explanations, and ordinary labels should use the main UI typeface.

Keep full metric titles visible. Long identifiers may shorten visually only when their full masked value remains accessible. Addresses and serials should not break arbitrarily across lines.

### Shared component behaviour

- Use one table treatment across Devices, Compliance, Backups, Jobs, and Registry.
- Pin identity columns in wide comparison tables.
- Keep filters and selected scope visible while browsing long lists.
- Confine horizontal scrolling to the table; avoid an additional vertical scroller unless it serves an independent navigator.
- Separate page-wide actions from selected-row actions.
- Keep restricted items discoverable with their required role; preserve server-side boundaries.
- Specify loading, empty, filtered-empty, failed-load, UNKNOWN, and restricted states separately. Their current implementation is **UNKNOWN**.
- Keep focus indicators, keyboard navigation, chart alternatives, and tooltip access available without a pointer. Current accessibility behaviour is **UNKNOWN**.

## 6. Prioritised changes

**P0:** resolve before using the affected summary as a decision input.  
**P1:** highest-value workflow and comprehension improvements.  
**P2:** consistency and presentation refinement.

Effort is a relative design/implementation estimate: **S** local component or wording change; **M** coordinated screen/data-presentation change; **L** cross-module or evidence-model work. Actual engineering effort is **UNKNOWN** without source inspection.

| Priority | Screen | Change | Effort | Capability preserved |
|---|---|---|---|---|
| **P0** | Overview, Devices, Operations, Registry | Define populations and reconcile job totals, enrolled/registered labels, and collection-state meanings. | M | Every count, state, filter, and list destination remains available. |
| **P0** | Compliance | Declare headline framework/population; explain calculation denominators, deficiency units, and family-grouping scope. | M | All frameworks, checks, control mappings, filters, findings, and exports. |
| **P0** | Configuration, Overview | Separate observed member differences from expected differences and UNKNOWN classification; stop implying all differences are drift. | L | Every setting, member value, difference, filter, and comparison link. |
| **P0** | Backups | Distinguish stored archives, validation, policy retention, semantic comparison, and recovery evidence. | M | Target switches, archives, validation detail, snapshots, history, contents, comparisons, downloads, and backup actions. |
| **P0** | Overview, Operations | Scope freshness claims and align readiness/failover wording with available capabilities. | S | Evidence details, cluster views, evaluation access, and all existing authorised actions. |
| **P0** | All charts and KPI panels | Restrict status colour to state words; remove denominator-free progress bars. | M | All values, distributions, category meanings, and chart/list click-throughs. |
| **P1** | Configuration | Put member-setting comparison first; move identity into an expandable header panel; replace link cloud with section navigation. | M | Full identity table, all settings, expected differences, export, search, and anchors. |
| **P1** | Overview | Create a concise exception briefing with scope, evidence time, and exact filtered destinations. | M | All six metrics, trends, framework summaries, failure causes, and inventory distributions. |
| **P1** | Wall display | Create a distance-readable, viewport-fitting briefing with persistent freshness and masking labels. | M | Every underlying measure and detailed route remains in the standard view or secondary wall detail. |
| **P1** | Devices, Identity, HA readiness | Separate observed role from readiness; expose provenance and simplify default identity columns. | M | All member, network, platform, enrolment, role, and evidence fields. |
| **P1** | Compliance findings | Name count units, label mixed results, and strengthen control-to-evidence navigation. | M | Every control, mapping, severity, result, filter, and detail link. |
| **P1** | Backups, Jobs | Clarify action hierarchy and replace raw list-level identifiers/errors with readable summaries plus exact detail. | M | Every action, UUID, raw type, error, filter, polling control, and export. |
| **P1** | Registry, Credentials | Standardise registry rows and identity; scope header actions to the relevant administration page. | S | Bulk selection, row menus, enrolment, import/add actions, RBAC, and restricted-page discoverability. |
| **P1** | Global search | Widen results, clarify aggregate destinations, and preserve complete masked context. | M | All result types, matching-device sets, previews, and direct links. |
| **P2** | Software and hardware | Replace donut-led presentation with ranked count/share lists and explicit grouped categories. | S | Every version, take, model, UNKNOWN value, percentage, and filtered-device link. |
| **P2** | System | Lead with observed services; retain pod and storage diagnostics below. | M | All infrastructure metrics, image references, storage breakdowns, timestamps, and refresh. |
| **P2** | Delivery plan | Separate release provenance, acceptance progress, and reconciled backlog status. | S | Every revision, movement, roadmap item, status, percentage, and refresh action. |
| **P2** | All screens, both themes | Apply shared typography, table density, full labels, focus states, and dark-theme tokens. | M | All content, interaction states, accessibility functions, and navigation. |

**Review boundary:** no source, configuration, project state, Git state, deployment, device, or production data was changed. Operational correctness and production readiness remain **UNKNOWN** from screenshots alone.
