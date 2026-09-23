# neXus UI fit-for-purpose review

## Verdict

1. neXus is fit for a trained operator to find inventory, configuration, backup, job and compliance evidence; it is only partly fit for executive triage and HA decisions.
2. The redesign gives the estate a coherent navigation and makes important UNKNOWN states visible.
3. Highest-value change: make Overview a ranked path from exception to affected scope, evidence age and next investigation.
4. Highest-value change: separate observed cluster member differences from differences that actually need review, while keeping both member values accessible.
5. Highest-value change: make Operations explicit about unevaluated HA readiness and reconcile its 24-hour job totals.

## Audience: the first minute

The first-minute paths below are inferred from the visible navigation. Actual landing pages and interaction behavior are **UNKNOWN**.

| Audience | What they see today | What they still cannot answer from the pictured views | Change |
|---|---|---|---|
| Network-security deputy GM | Overview gives six prominent counts, a three-line narrative and framework bars. | Which exception is most consequential for the bank, who is handling it, and whether the situation improved across a comparable period. The 57.2% roadmap figure elsewhere could be mistaken for operational health. | Put a ranked exception register above supporting charts: affected scope, observed state, evidence time, responsible team if recorded, and a link to the underlying list. Keep the six counts and deltas in a compact strip. Never combine them into a score. |
| Network-security manager and team leads | Overview shows failures and differences; Operations exposes jobs and cluster readiness; Backups shows targets. | Which of 38 cluster differences require action, which failures repeat on the same devices, and whether any cluster has a usable readiness evaluation. | Provide review state and affected-member detail for differences; link each failure cause to filtered jobs and devices; show `0 of 40 evaluated` prominently and neutrally. |
| Operator / security admin | Devices, Configuration, Backups and Jobs contain the raw material needed to investigate. | From the first viewport, which values differ between cluster members and what to check next. Backup row actions are crowded, and `Backup Now` versus `Snapshot` is unexplained. | Make a selected exception open a focused side-by-side evidence view with collection time, member values and relevant job/archive history. Keep every existing action in a labelled row menu or detail panel. |
| Compliance / audit reviewer | Compliance separates assurance, evidence coverage, deficiencies and data gaps; frameworks and controls are listed. | How a headline count traces to a particular firewall, control result and evidence timestamp. The family `findings` counts and global `172 critical deficiencies` have different apparent units but no visible explanation. | Label each count’s unit and population. Preserve the control list and filters; make its detail view show the evidence trail and export scope. Whether the current detail view already does this is **UNKNOWN**. |

## Screen-by-screen assessment

### Overview — [top](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/01_overview_top.png>) and [software lists](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/02_overview_software_lists.png>)

**Works:** The page states its observation time, distinguishes action, review and evidence, and exposes the denominators behind most counts. Failure causes are more useful than a generic failed-jobs total. Framework bars show pass, fail and unavailable quantities.

**Ineffective:** The narrative repeats the six cards without identifying a first investigation. `38 of 39` member differences appears urgent although the card itself says expected member-specific settings are not yet separated. `All evidence 4 h ago` reads like universal freshness alongside `1 never read`. Card titles truncate, and `no history` occupies the place where a trend would help. The Check Point and Palo Alto donut groups demand a great deal of space for simple distributions; the Check Point card also leaves substantial empty space.

**Fix:** Lead with a short, ranked exception register: *issue → affected population → last supporting evidence → open filtered view*. Keep all six existing metrics, denominators, latest times, deltas and click-throughs immediately below it. Say `Latest collected evidence: 4 h ago; 102 of 103 active devices read in 24 h; 1 never read`, with the actual meanings verified against the data. Present software and hardware as labelled horizontal distributions with count, percentage, UNKNOWN category and the existing device-list links. Describe versions as observed distributions; make no “outdated” judgement.

### Devices — [cluster inventory](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/03_devices_cluster.png>) and [identity](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/04_devices_identity.png>)

**Works:** The left list makes clusters and virtual systems discoverable. The selected cluster shows both members, HA roles, interfaces, routes and links into its other product views. The identity tab says values came from each member and explicitly calls an unread value UNKNOWN.

**Ineffective:** The left column mixes a `105 devices` total with clusters and nested VSX objects; their different counting units take effort to decode. The note that the state chip only appears when a device is not Live is too far from the state filter. The identity table requires horizontal scrolling to reach fields such as enrollment, with little indication of what is off-screen.

**Fix:** Label list groups and filter counts by object type: *devices*, *clusters* and *virtual systems*. Keep the vendor, scope, state, search and sort controls. Keep the selected cluster header and member cards fixed while switching tabs; make the member name column sticky in the identity table and show a visible horizontal-scroll cue. Preserve every field, tab, collection action, masked identifier and cross-link.

### Configuration — [cluster top](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/05_config_cluster.png>) and [pictured section](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/06_config_cluster_sections.png>)

**Works:** It identifies the two members, keeps identity alongside the configuration, offers evidence export, and distinguishes current actual state from a previous-collection change in the side filter.

**Ineffective:** `Config diff · 78` and `78 differences` visually imply 78 problems. The page says expected hostnames and member addresses are excluded, yet the remaining differences are still an unstructured chain of links. A reviewer cannot prioritize the 78 from this viewport or compare the corresponding member values without opening each item. `Changed · 2` means change since an earlier collection; it should not be confused with between-member differences. Screenshots 05 and 06 show essentially the same viewport, so the effectiveness of lower sections is **UNKNOWN**.

**Fix:** Above the setting detail, show two explicitly separate facts: `2 devices changed since previous collection` and `78 observed member differences across 353 settings`. Divide differences into *needs review*, *expected* and *unreviewed* only where evidence or a recorded review supports the distinction; otherwise use **UNKNOWN/unreviewed**. Replace the link paragraph with grouped sections such as System, Management and Interfaces, each with a count and an expandable two-member value table. Keep the search, `Differences only` control, every key/value, section link and CSV export. Collapse the duplicated identity table after a concise member header, leaving its full fields one click away.

### Compliance — [summary](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/07_compliance.png>) and [families and controls](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/08_compliance_families_list.png>)

**Works:** The page correctly separates assured compliance, observed results, evidence coverage, critical deficiencies and data gaps. It shows framework denominators and preserves both a family view and an individual-control list.

**Ineffective:** The viewer must infer that framework bars count *control × firewall checks*, while the filter chips (`All · 48`, `Failing · 38`) count *controls*. Family `findings` can be read as the same unit as `172 critical deficiencies`, though the screenshot does not establish that. Bars are too small to explain their proportions without reading nearby numbers. `Data gaps · 428` needs a visible route to the missing-evidence population.

**Fix:** Put the unit directly in each heading and chip: `checks`, `controls`, `firewalls`, or `critical failing checks`, as the data warrants. Add the population and evidence time to a selected family or control detail. Make family rows open the existing filtered control list, and make each unavailable count open the affected checks/firewalls. Retain all four framework cards, control filters, search, severity, outcome, detail links, re-evaluation and audit export. Use directly labelled quantities in the bars; state colour must appear with the state word, never carry meaning by itself.

### Backups — [fleet](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/09_backups.png>)

**Works:** Stored backups, backup targets, missing archives, retention and scheduled status are distinct. `UNKNOWN` for major deviations with `0 comparisons run` is honest. Per-device archive time, size, validation and deviation are visible.

**Ineffective:** The opening sentence explains data provenance at implementation level while the decision `19 targets without archive` is buried among cards and filters. Actions consume most of each table row. `V1`, `FIRST`, `CHANGED`, `Snapshot` and `Backup Now` need meanings at the point of use. The relationship between `83 devices with a stored backup`, `102 targets` and `19 targets without archive` appears plausible but is not explicitly defined.

**Fix:** Lead with `19 of 102 backup targets have no archive`, linking to the existing filtered fleet. Keep the `83` stored-backup fact, schedule, horizon and comparisons in a compact evidence strip with clear populations. Put primary `Backup Now` in the row and the other existing actions in a labelled menu or opened detail; retain target toggles, Snapshot, History, Contents, Compare, Download and all columns. Explain validation and deviation labels inline or on focus. Show schedule and archive timestamps, and UNKNOWN where a time is unavailable.

### Operations — [HA readiness](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/10_operations_ha.png>) and [jobs](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/11_operations_jobs.png>)

**Works:** Readiness is stated as observed, not inferred. The screen clearly says `40 clusters enrolled · 0 evaluated` and that no class 2 action exists in this build. Jobs has useful state, type, device, date and text filters plus export.

**Ineffective:** A long table repeating `NOT EVALUATED` makes an unevaluated estate look like an inventory task instead of the page’s central operational limitation. The displayed 24-hour figures say `1018 submitted`, `0 in flight`, `971 completed` and `45 failed`; two submissions have no stated outcome in those displayed categories. The success-rate denominator is not shown. The Jobs table opens on completed rows although failed jobs are the immediate work.

**Fix:** Put `HA readiness: NOT EVALUATED — 0 of 40 clusters` in a neutral, prominent state panel. Show the last evaluation time per cluster only when there is one, and provide the evaluation path if the product supports it; that action’s availability is **UNKNOWN** from these images. Keep every cluster row and member field. Reconcile submitted, completed, failed, running and any other terminal states visibly, then state the success-rate denominator. Add a `Failed` quick view that uses the existing filters; retain All jobs, Queue, History, live polling, refresh and CSV export.

### Administration: device registry — [registry](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/12_admin_registry.png>)

**Works:** Enrollment and collection scope are described near the registry. The side cards explain why enrolled-device counts and backup-target counts differ.

**Ineffective:** `UNKNOWN hostname` gives little distinguishing context in a 105-entry registry. Checkboxes and overflow menus are visible, but the result of selecting rows and the availability of registry search are **UNKNOWN**. The registry uses a large central panel for a small number of fields, leaving side guidance disconnected from the selected entry.

**Fix:** Give each row a stable masked identifier with `Hostname not reported` as an explicit field state. Show selection actions in a toolbar when rows are selected, if such actions exist; do not invent them. Place enrollment and scope explanations beside the relevant column/header, while keeping their full text available. Preserve checkboxes, overflow actions, import, Add device and all visible enrollment states.

### Administration: restricted credentials — [access boundary](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/13_admin_restricted_credentials.png>)

**Works:** The restricted page names the required Security Admin role and does not expose credential content to this persona.

**Ineffective:** The large blank remainder looks unfinished. The screenshot cannot establish whether the same RBAC boundary is enforced on direct routes, APIs or exports: **UNKNOWN**.

**Fix:** Keep the restricted state in place, with one sentence explaining who can grant the role or where the authorized process lives. Do not reveal credential names or metadata in that guidance. Preserve the visible navigation and the RBAC boundary.

### Administration: system — [service and storage](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/14_admin_system.png>)

**Works:** Pod state, readiness, restarts, age, resource use and image are together. Storage separates encrypted backups, configuration evidence, database and free artifact volume.

**Ineffective:** `Refreshed every 15 s` is an interval, not an observation timestamp. Rounded `0m` CPU can be mistaken for exactly zero. The free-volume note says the host disk size differs from the claimed volume size; that caveat needs to sit beside the number it qualifies.

**Fix:** Show `Observed at` with each refresh, use `<1m` where rounding would otherwise falsely show zero, and place the host-volume qualification directly under `157 GB free`. Keep every pod column, refresh action, storage figure and underlying table. A succeeded bootstrap pod should remain a named state, not be coloured as a failure solely because it is `0/1` ready.

### Administration: delivery plan — [roadmap](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/15_admin_delivery_plan.png>)

**Works:** The page distinguishes recorded product build, deployed version, source revision and declared roadmap completion. It explicitly states freshness is UNKNOWN and that 57.2% is acceptance-criterion completion, not an ETA.

**Ineffective:** A deputy GM could still mistake the prominent 57.2% for operational readiness. `overview_acceptance_and_member_specific_tuning` reads as an internal key rather than a task title. `Done` and `Automated Validated` need a visible definition if their counts are to be compared.

**Fix:** Keep this in Administration under Delivery plan, headed `Declared product roadmap`, with a persistent `Freshness: UNKNOWN` label next to the percentage. Display human titles first and retain internal keys in detail. Explain the status counts. Do not place roadmap completion in the Overview health area.

### Global search — [SNMP results](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/16_global_search.png>)

**Works:** Search reaches individual settings and gives a device count, example device and value context. The visible example names remain masked.

**Ineffective:** The narrow menu clips setting names, values and the context needed to choose among similar SNMP results. `55 devices` is useful, but the destination of a result is not evident. Keyboard behavior, full result coverage and masking in opened results are **UNKNOWN**.

**Fix:** Use a wider results panel grouped by result type, with a full setting path, affected-device count, short value summary and named destination such as `Open configuration results`. Keep every result and deep link. Preserve deterministic masking throughout search and its destinations; verify export and detail behavior separately.

### Dark mode — [Overview](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/17_dark_overview.png>) and [Configuration](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/18_dark_configuration.png>)

**Works:** Layout and navigation remain familiar, and state words remain visible.

**Ineffective:** Truncated metric titles are worse on dark cards; secondary text, borders and link colour have weak visual separation. The bright table scrollbar draws more attention than the evidence. Whether contrast and keyboard focus meet accessibility requirements is **UNKNOWN** from screenshots.

**Fix:** Apply the same typography and wrapping rules in both themes, then tune dark surface, border, secondary-text and focus tokens as a set. Keep all controls and data in identical positions; dark mode must not silently omit detail.

### Wall display — [operations wall](</private/tmp/claude-502/-Users-OzanDur-Codo/2e134e79-cb7e-41c1-9816-db152120468d/scratchpad/fable_in/after/19_wall_display.png>)

**Works:** It removes navigation, enlarges the main summary and retains an as-of time, failure causes, queue and exit route.

**Ineffective:** Several card headings truncate at wall distance. `0 running` uses a large, mostly empty queue panel while important failure causes are compressed. The long narrative requires reading paragraphs rather than spotting a change.

**Fix:** Use full, wrapping metric labels and larger state words. Keep all six metrics, failure causes, queue figures and timestamps, but allocate space by current information density: compact queue when empty, more room for ranked causes and affected counts. Make the as-of time and any stale/UNKNOWN state legible at distance. Preserve `Exit wall display` as the route back to every interactive control.

## Consistency and design system

| Rule | Concrete application |
|---|---|
| Population and unit | Write `103 active devices of 105 enrolled`, `102 backup targets`, `102 firewalls evaluated`, `40 clusters enrolled` and `39 active clusters` with their own labels. Do not let adjacent cards imply a common denominator. |
| State language | Reserve status colour for a word-bearing state label such as `FAILED`, `PASS`, `UNAVAILABLE`, `NOT EVALUATED` or `UNKNOWN`. Show actual zero neutrally. Use `—` only for not applicable; use **UNKNOWN** when a value was not read or cannot be established. |
| Charts | Keep counts and percentages beside every segment. Use direct words and distinct patterns or shapes so colour is never the sole explanation. Do not invent a combined health or risk score. |
| Typography and density | Use at least 14 px for ordinary evidence and 13 px for dense tables; reserve smaller text for secondary metadata. Use tabular numerals for aligned counts and timestamps, monospace for IDs and configuration values. Wrap meaning-bearing headings instead of ellipsizing them. |
| Layout | Use one 4/8/12/16/24/32 px spacing scale. Keep page titles, observation times, filters and primary actions in consistent locations. Make long tables’ object-name column sticky and indicate horizontal overflow. |
| Time and history | Pair relative age with an absolute timestamp and GMT+3 context. If history is absent, state `History: UNKNOWN` or `No history recorded`, without a decorative trend. Distinguish collection time, evaluation time and page refresh time. |
| Masking and access | Keep the `aiview · names masked` indicator visible. Retain pseudonyms and pseudo-addresses in search, detail, export and wall routes. The screenshots establish visible masking only; enforcement across downloads and APIs is **UNKNOWN**. |

## Prioritised changes

Effort is a UI/product estimate: **S** small, **M** medium, **L** large. It is not an implementation commitment.

| Priority | Screen and change | Effort | Capability preserved |
|---|---|---:|---|
| P0 | Overview: ranked exception-to-evidence register with scope and evidence age | M | All six metrics, denominators, deltas, framework visuals, failure causes and existing destinations |
| P0 | Configuration: group 78 member differences and identify review state without inferring safety | L | Every setting and member value, search, `Differences only`, section links and CSV export |
| P0 | Operations: state `0 of 40` readiness evaluations prominently and neutrally | S | Full HA cluster/member table, schedule collection, Jobs, Queue and History |
| P0 | Operations: reconcile 24-hour job outcomes and display the success-rate denominator | M | Every job state, row, filter, polling control, refresh and export |
| P1 | Compliance: label units and populations; connect summary, family, control and evidence detail | M | Four framework views, family rows, all control filters, re-evaluation and audit export |
| P1 | Backups: lead with missing archives and move crowded row actions into a labelled detail/menu | M | Target toggle, Backup Now, Snapshot, History, Contents, Compare, Download and every fleet field |
| P1 | Devices: distinguish device/cluster/VSX counts and keep identity columns readable | M | All filters, sorting, tabs, member fields, collection actions and cross-links |
| P1 | Global search: widen and group results with explicit destinations | M | Every setting result, device context, click-through and masking |
| P2 | Overview: replace donut-heavy version lists with compact labelled distributions | M | Every version/model category, count, percentage, UNKNOWN and device-list link |
| P2 | Administration: clarify registry unknown-hostname rows and selected-row context | S | Enrollment states, selections, overflow actions, import and Add device |
| P2 | Administration: label roadmap as declared progress and qualify system observations | S | Roadmap fields/statuses, pod metrics, storage figures and refresh |
| P2 | Dark and wall modes: fix title truncation, contrast hierarchy and empty-panel allocation | M | All displayed metrics, causes, queue fields, controls and exit route |

**Review boundary:** This assessment uses the supplied screenshots only. Click behavior, responsive layouts, keyboard access, export masking, RBAC enforcement and lower Configuration sections are **UNKNOWN**. No source, configuration, project state, Git history, deployment, live device or production data was changed; no live system was contacted.
