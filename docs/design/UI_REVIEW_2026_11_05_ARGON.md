<!-- Argon consultation 2026-11-04T22:40:02Z, model gemini-4-argon-high, effort high; unaltered output -->

**Role:** `REVIEWER` (Senior Product & UI Designer · Enterprise Firewall Operations & Executive Reporting · Read-only `aiview` inspection)

# UI Review of neXus: Purpose Fit and Simplification

**Date:** 2026-11-05  
**Reviewer:** Argon (`REVIEWER`, read-only)  
**Persona & Environment:** `aiview` (`names masked`, `Times in GMT+3`, `Build cd096bb`), 42 live screen captures (`01-operations-ha-readiness.jpg` through `44-cluster-failover.jpg`, with `36` and `37` not in set).  
**Authority & Design Baseline:** [`AGENTS.md`](file:///Users/OzanDur/Codo/nexus-uireview-1105/AGENTS.md), [`roles/PO.md`](file:///Users/OzanDur/Codo/nexus-uireview-1105/roles/PO.md), [`docs/design/UI_DESIGN_LANGUAGE.md`](file:///Users/OzanDur/Codo/nexus-uireview-1105/docs/design/UI_DESIGN_LANGUAGE.md).

---

## Executive Synthesis: Answering the Product Owner's Question

> *"The UI is clean, OK. But next to the cleanliness there is a lot of data. Can it be simplified? Can something fit the goal and purpose? Can the UI design be corrected? Look with the eyes of an executive and of an expert. Is each menu fit for purpose? What can be simplified, tidied or fixed visually? What should be added to the product range, removed, or moved?"*

1. **Why the UI feels clean yet overwhelming:** Visual styling (cards, pills, typography, whitespace) follows a coherent token system, but **every screen prints its internal database accounting and parser rules onto the primary canvas**. Instead of one verdict per plane with drill-down on demand, screens stack 4 to 8 KPI cards, multi-line methodology disclaimers, and repeated tables before the operator reaches actionable rows.
2. **Executive lens (5-second test fails on trust and freshness):** When **131 of 132 devices have not been read in 24 hours**, `06-overview.jpg` still claims `data read 20 min ago` under `Estate map` (because 1 device was read 20 min ago) and colours 97 firewalls red for compliance and only 35 amber for `Evidence ageing` (`CRITICAL > AGEING` hides fleet-wide staleness). `08-compliance.jpg` shows `30.1% CHECKS PASSING` with **no staleness banner at all**, and `35`/`38` show a green `Readiness Ready · 31 h ago` pill even though `01-operations-ha-readiness.jpg` warns in amber that 30-hour-old readiness must not be relied upon.
3. **Expert lens (too many clicks to root cause, too much repetition):**
   - **Changes are fragmented across 5 screens:** `05-operations-change-feed.jpg`, `13-policy-changes.jpg`, `17-backups.jpg` (`MAJOR CONFIGURATION CHANGES`), `07-devices-fleet-summary.jpg` (`CHANGED SINCE LAST READ`), and `33-admin-audit-logs.jpg`.
   - **Jobs are duplicated:** `02-operations-jobs.jpg` and `34-admin-job-logs.jpg` are identical 42,415-row tables (`Same records as Operations · Jobs`).
   - **Cluster member identity is shown 3 times on the same screen:** in `ObjectHeader` (`38`–`44`), in `40-cluster-members.jpg`, and in `43-cluster-identity.jpg`.
   - **Policy tabs (`09`, `10`, `11`, `12`) repeat the same 59-container table 4 times** before selection, while `14-policy-lookup.jpg` and `16-path-analysis.jpg` split one question (*"Can A reach B?"*) across two menus.
   - **Dead affordances & stuck loaders:** `M1`/`M2` buttons and member cards on `35` and `38`–`44` look clickable but do nothing; `04-operations-system-health.jpg`, `05-operations-change-feed.jpg`, and `13-policy-changes.jpg` are stuck loading indefinitely; `aiview` sees active-looking primary write/collect buttons (`+ Add device`, `Run Fleet Backup`, `Bulk Collect`, `Import from manager`) alongside 7 bare `"Available to administrators"` pages.

*All recommendations below rearrange, filter, or aggregate data neXus already collects; **none require new network-device commands**.*

---

## 1. Per-Screen Review (All 42 Captures)

### 1.1 Operations (`01`–`05`)

#### `01-operations-ha-readiness.jpg` — Operations › HA & readiness
- **Purpose & Fit:** Verify high-availability failover readiness across all 45 enrolled clusters and trigger pre-checks. **Fit: Partly.**
  - *Executive:* Sees `35 ready of 45 clusters` in the KPI card, then immediately sees `Ready · 53` in the filter bar under `45 clusters enrolled`—the arithmetic appears broken (`53 > 45`) because cluster counts and Virtual System readiness-record counts (`58 readiness records`) are mixed without labelling.
  - *Expert:* Good table with `ACTIVE ↔ STANDBY MEMBERS` and `PRIMARY REASON`, but two identical `Run readiness check (39 clusters)` buttons sit 60 px apart.
- **Remove or hide:**
  - Remove the disabled top-right `Schedule collection · Schedule authoring is not available yet` pill button (unimplemented feature noise on `01`–`05`).
  - Remove the duplicate `Run readiness check (39 clusters)` button in the filter bar when the amber staleness banner above it already renders that exact button.
  - Hide the 3 non-HA KPI cards (`JOBS FINISHED (24 H)`, `SUCCESS RATE (24 H)`, `FAILED (24 H)`) from this tab; they belong on the `Jobs` tab (`02`), not above `HA & readiness`, `Diagnostics`, `System health`, and `Change feed`.
- **Move behind a "details" disclosure:**
  - Move the inline glossary sentence (`Not ready: a pre-check found a blocking condition · UNKNOWN: readiness could not be established...`) into a `?` popover or column header tooltip.
- **Merge with another screen:**
  - Keep fleet readiness here, but link each row directly to the cluster's `Failover` tab (`44-cluster-failover.jpg`) so pre-check details and execution live in one place.
- **Visual simplification:**
  - Make filter chip counts use the **same unit** as the section heading (`45 clusters enrolled`: `Ready · 35 clusters`, `Not ready · 2`, `UNKNOWN · 3`, `Not supported · 5`), with a toggle for `Include Virtual Systems (58 records)` when needed.
- **Concrete fixes:**
  - Deduplicate the double `Run readiness check (39 clusters)` button.
  - In the top-right user menu (`01`–`44`), replace the misleading subtitle `Compliance Admin` under `aiview` with `Viewer (masked)` so the displayed role matches `aiview`'s actual permissions.

#### `02-operations-jobs.jpg` — Operations › Jobs
- **Purpose & Fit:** Monitor, filter, and troubleshoot background collection and readiness jobs across the estate. **Fit: Partly.**
  - *Executive:* `88% SUCCESS RATE (24 H)` (`7 completed · 1 failed`) is clear, but `All · 42,415` lifetime jobs mixes historical noise with today's operations.
  - *Expert:* Eight of the ten visible rows show **`Unknown device`** under `TARGET DEVICE` (for `Check Point policy collection` and `Palo Alto policy collection`), and the failed row prints raw machine internals (`PARTIAL_SNAPSHOT preflight target=73688f00-b297-4ce9-94be-c1dd55280a79: SFS...`).
- **Remove or hide:**
  - Remove the `READINESS CHECKS` KPI card (`35 ready of 45 clusters`) from the `Jobs` tab; keep only job metrics (`Jobs (24 h)`, `Success rate`, `Failed (24 h)`).
  - Remove `counts every recorded job` caption next to the filter chips.
- **Move behind a "details" disclosure:**
  - Move raw error tokens and target UUIDs (`PARTIAL_SNAPSHOT preflight target=73688f00-...`) into a row-click drawer or `Details` popover; show a human sentence in `RESULT` (`Partial snapshot: preflight failed on container DOM-...`).
- **Merge with another screen:**
  - **Merge `34-admin-job-logs.jpg` (`Administration › Job Logs`) into this screen** (see `34`).
- **Visual simplification:**
  - Default the chip bar to `Last 24 h` instead of `All · 42,415`, and collapse the 6 filter inputs (`State`, `Type`, `Device`, `From`, `To`, `Search`) into one search bar + `State`/`Type` dropdowns + a `Date range` picker.
- **Concrete fixes:**
  - Resolve management-server / policy-container targets in `TARGET DEVICE` to their masked names (`FW-JULIET-01`, `FW-VICTOR-09`, or `DOM-...`) instead of rendering `Unknown device`.
  - Add pagination footer or virtual-scroll count indicator at the bottom of the table.

#### `03-operations-diagnostics.jpg` — Operations › Diagnostics
- **Purpose & Fit:** Execute an approved `CLASS_0` diagnostic read command on a single device and inspect its masked output. **Fit: Partly.**
  - *Executive:* Not an executive screen; should not sit under fleet-wide Operations KPI cards.
  - *Expert:* Useful tool, but the top 35% of the viewport is wasted on fleet job/readiness KPI cards (`JOBS FINISHED`, `SUCCESS RATE`, `READINESS CHECKS`, `FAILED`), squeezing the device selector and `Output` console into the bottom half of the page.
- **Remove or hide:**
  - Hide the 4 Operations KPI cards and the `1 failed · 8 finished...` subtitle when `Diagnostics` is active (or move `Diagnostics` out of `Operations`—see §2).
- **Move behind a "details" disclosure:**
  - Collapse `All devices history` into a drawer or sub-tab so the left device list starts higher.
- **Merge with another screen:**
  - Move `Diagnostics` under `Tools` in the left rail (alongside `Packets` and `Sanitizer`) **or** embed it as a `Diagnostics` action/drawer on the single-device screen (`Devices › <device>`), since it operates on one device at a time.
- **Visual simplification:**
  - Style the left `Devices (81 / 81)` list using the same compact tree component as `07-devices-fleet-summary.jpg` instead of unindented plain text (`CLS-ALPHA-01`, `FW-ALPHA-01-M1 Check Point`, `VS 2`, `VS 3`).
- **Concrete fixes:**
  - Clarify why only `81 / 81` devices and 3 vendor pills (`Check Point`, `Cisco ASA`, `Fortinet`—missing `Palo Alto`) appear in `Devices`, e.g., `81 devices with approved diagnostic commands (Palo Alto uses API reads only)`.

#### `04-operations-system-health.jpg` — Operations › System health
- **Purpose & Fit:** Surface gateway OS health anomalies (crash/dump files, sustained high CPU p95, `cpwd` process restarts, and log collection failures). **Fit: No (in current state).**
  - *Executive:* Sees **8 KPI cards** stacked in two rows, four of which say `Not evaluated` while a banner is stuck on `First computation running`.
  - *Expert:* Cannot use the screen at all because the computation never finishes; meanwhile, a dense 3-line paragraph of raw formula specs (`State word: worst fact wins (CRITICAL > ATTENTION > UNKNOWN > NORMAL)... p95: nearest rank over the day's samples of the sar "all" CPU row, busy = 100 - %idle. Restart rule cpwd-restart-v1 (PROVISIONAL)...`) clutters the canvas above an empty table.
- **Remove or hide:**
  - Remove the top row of 4 Operations KPI cards (`JOBS FINISHED`, `SUCCESS RATE`, `READINESS CHECKS`, `FAILED`) so `System health` has only its own 4 KPI cards.
  - Remove the 3-line formula/parser specification paragraph from the main canvas.
- **Move behind a "details" disclosure:**
  - Move the `sar` p95 and `cpwd-restart-v1` evaluation rules behind an `Evaluation rules` info popover.
- **Merge with another screen:**
  - Keep fleet rollup here (or as a `Health` filter in `Devices`), and link each device row directly to `Devices › <device> › Logs` (`42-cluster-logs.jpg`), which displays the same crash/dump, restart, and collection-health data per member.
- **Visual simplification:**
  - When `First computation running` is active, replace the 4 `Not evaluated` KPI cards and empty table header with a single centered `StatePanel` showing progress (`Computing health for N of 97 devices · elapsed 12 s`) instead of repeating `Not evaluated` 5 times.
- **Concrete fixes:**
  - **Fix stuck loader:** Add a bounded timeout, progress counter, and last-failure reason to the background `System health` computation so it never hangs indefinitely on `First computation running`.

#### `05-operations-change-feed.jpg` — Operations › Change feed
- **Purpose & Fit:** Provide a chronological, cross-estate audit stream of user changes, neXus jobs, and device events. **Fit: No (in current state).**
  - *Executive & Expert:* The screen is stuck on bare text `Loading changes...` below the filter bar, while the top half is occupied by static KPI cards from `01`.
- **Remove or hide:**
  - Remove the 4 top Operations KPI cards on this tab.
  - Remove the 2-line explanatory text (`Who changed what, when...`) or shorten to a 6-word subtitle (`Configuration diffs, jobs, and device events`).
- **Move behind a "details" disclosure:**
  - Keep `Custom` date range inputs hidden until `Custom` is clicked (already done), and move `Vendor` / `Device` into a single `Scope` filter.
- **Merge with another screen:**
  - **Unify estate change tracking:** Either make `Change feed` a top-level `TRACE` destination (`Changes`) that combines configuration changes (currently duplicated on `17-backups.jpg`), policy revisions (`13-policy-changes.jpg`), and job/device events, **or** merge `Change feed` with `Policy › Changes` so operators do not hunt across 5 screens to answer *"What changed in the last 24 hours?"*
- **Visual simplification:**
  - Replace the separate `Apply feed filters` button with instant filter application on chip/dropdown change (matching `01` and `02`).
- **Concrete fixes:**
  - **Fix stuck loader:** Replace bare `Loading changes...` with a bounded query (defaulting to `Last 24 h`, indexed limit 100 rows), a structured `StatePanel` on timeout/error, and a skeleton table during fetch.

---

### 1.2 Overview, Devices Fleet Summary & Compliance (`06`–`08`)

#### `06-overview.jpg` — Overview
- **Purpose & Fit:** Give executives and shift leads an immediate 5-second posture verdict across the 132 enrolled devices and 45 clusters. **Fit: Partly.**
  - *Executive (5-second test):*
    - *"Is the estate OK?"* Ambiguous: `Estate map` shows 97 red/pink tiles and says `data read 20 min ago`, hiding the fact that **131 of 132 devices have not been read in 24 h** (`EVIDENCE: 1 of 132 devices`). Because `CRITICAL > AGEING` in tile colouring, 97 stale devices render as fresh-looking red compliance failures while only the 35 unassessed/non-failing devices render amber (`Evidence ageing 35`).
    - *"What needs my decision?"* Missing: there is no banner warning that scheduled collection is off / 99% of evidence is stale, and no trend (`+0 / -4 failures since yesterday`).
  - *Expert:* Good one-click entry points (`Open →`, `All →`), but `HA READINESS` prints `2 not ready · 3 unknown` twice on consecutive lines, and `BACKUPS` (`126 of 130`) / `EVIDENCE` (`1 of 132`) invert their progress bars (headline states the positive count, bar graphs the negative defect fraction).
- **Remove or hide:**
  - Remove `data read 20 min ago` from the `Estate map` subtitle when it reflects only the single newest device (`1 of 132`); show the fleet median/oldest age (`1 of 132 read <24 h · 131 aged 24–72 h`) instead.
  - Remove the duplicate sub-caption in `HA READINESS` (`2 not ready · 3 unknown` appears above and again in `35 ready of 45 clusters · 2 not ready · 3 unknown · 5 not supported`).
- **Move behind a "details" disclosure:**
  - Collapse per-vendor non-firewall appliance wafers (`Infoblox 1`, `Radware 5`, `Blue Coat 5`) into an `Other appliances (11)` group unless they have active backup/collection failures.
- **Merge with another screen:**
  - Keep `Overview` as the Executive Home (redesigned in §3), and leave hardware/software inventory breakdowns on `07-devices-fleet-summary.jpg`.
- **Visual simplification:**
  - Add a **top-level Stale Evidence Banner** whenever `<80%` of active devices were read in the last 24 h (`Scheduled collection is off or delayed: 131 of 132 devices have evidence older than 24 h (31 h ago). Compliance and readiness figures below reflect the last stored reads.`).
  - Fix the `BACKUPS` and `EVIDENCE` progress bars so the filled bar length matches the headline numerator (`126/130` = 97% filled with 3% red tail; `1/132` = 1% filled with 99% amber tail).
  - Visually dim or cross-hatch `Estate map` tiles whose underlying evidence is `>24 h` old so stale red tiles are never mistaken for live observations.
- **Concrete fixes:**
  - Replace the subtle red outline ring on `No archive 4` tiles (top-left of `Check Point 60`) with a high-contrast badge or dedicated `Missing backup (4)` callout link.

#### `07-devices-fleet-summary.jpg` — Devices (Fleet summary default view)
- **Purpose & Fit:** Browse/filter the 134 registered devices and clusters in the left tree while inspecting fleet-wide inventory, evidence age, policy installation age, and software/hardware distributions on the right. **Fit: Partly.**
  - *Executive:* `Policy installed` (`Today 0`, `Yesterday 1`, `2–7 days ago 71`, `Earlier 21`, `Not read yet 29`) is informative, but **6 donut charts** at the bottom are visually noisy and omit 5 of the 7 vendors (`Fortinet 10`, `Cisco ASA 12`, `Blue Coat 5`, `Radware 5`, `Infoblox 1`).
  - *Expert:* Left tree + right Fleet Summary is a big improvement over an empty pane, but the top bar has 5 competing action buttons (`Lifecycle`, `Export inventory`, `Bulk Collect`, `Read configuration, all`, `+ Add device`) that appear enabled for read-only `aiview`, and the global top-bar search input changes its placeholder to `Filter this list, or search everything...` instead of providing a dedicated local tree filter input.
- **Remove or hide:**
  - Remove the 2-line prose paragraph under `Fleet summary` (`134 registered · 132 enrolled. Choose one on the left... Interface evidence is read over SSH or HTTPS; values are observed, never written.`).
  - Hide or visibly lock (`Role: not permitted`) `Bulk Collect`, `Read configuration, all`, and `+ Add device` for `aiview`.
- **Move behind a "details" disclosure:**
  - Collapse `Bulk Collect`, `Read configuration, all`, and `Export inventory` into the single `Fleet actions` dropdown already used on device detail screens (`35`–`44`)! Notice how `35` has a clean `Fleet actions` button while `07` spreads 5 separate buttons across the header.
- **Merge with another screen:**
  - Move `+ Add device` exclusively to `Administration › Device management` (`21`)—which itself states *"Enrollment is the one place a device enters the product"*!
- **Visual simplification:**
  - Replace the **6 donut charts** (`MAJOR VERSION`, `JUMBO HOTFIX`, `APPLIANCE`, `PAN-OS MAJOR`, `EXACT VERSION`, `MODEL`) with compact **horizontal stacked bars or ranked distribution lists** (one card per vendor, with a vendor tab/pill bar covering all 7 vendors). This eliminates the 6 circular donuts and stops version/model labels (`R81.20 · Take...`, `29200 (RH-20-...`) from truncating.
  - Consolidate the 4 rows of 16 grey chips (`VENDOR`, `FORM`, `EVIDENCE AGE`, `CHANGED SINCE LAST READ`) into 3 compact KPI cards (`Enrolled Estate: 132 live · 45 clusters`, `Evidence Freshness: 1 <24h · 131 stale`, `Config Changes: 42 of 124 changed`).
- **Concrete fixes:**
  - Restore a dedicated `Filter devices...` input directly above the left device tree (matching `01`, `03`, `09`) and keep the top-bar omnibox placeholder constant (`Search devices, IPs, routes, policy...`).
  - Fix truncated legend text under the Check Point `JUMBO HOTFIX` and `APPLIANCE` charts.

#### `08-compliance.jpg` — Compliance
- **Purpose & Fit:** Measure firewall adherence to security baselines (`CIS Benchmark`, `PCI-DSS v4.0.1`, `NIST SP 800-53`, `Financial Baseline`) and drill into failing controls by family. **Fit: Partly.**
  - *Executive:* Sees `30.1% CHECKS PASSING` and `164 CRITICAL FAILING CHECKS`, but **is not told that 131 of 132 devices have stale evidence (>24 h)** and `re-check time not recorded` is buried in muted 12 px subtitle text. Furthermore, the `CIS Benchmark` card in Row 2 (`30.1%`, `2491 control checks`, `749 pass · 1246 fail · 496 unavailable`) duplicates Row 1's totals verbatim (`every control maps to CIS, so the CIS card shows the same totals`).
  - *Expert:* Clear `Control families` table at the bottom, but two rows of 4 cards (8 cards total) + a 2-line scope disclaimer push the interactive table down to the bottom third of the viewport.
- **Remove or hide:**
  - Remove the 2-line `Scope: all 64 controls on 118 of 122 evaluated firewalls...` prose paragraph between the header and the KPI cards.
  - Remove the redundant 2-row card duplication between Row 1 (`CHECKS PASSING 30.1%`, `2,491 checks`, `496 data gaps`) and Row 2 (`CIS Benchmark 30.1%`, `2491 control checks`, `496 unavailable`).
- **Move behind a "details" disclosure:**
  - Move the methodology explanations inside each of the 4 top KPI cards (`Passing checks among all assigned checks (a check without evidence counts against)`, `Checks with collected evidence · observed 37.5% (pass among judged checks)`, etc.) into a single `Scoring & coverage rules` info popover.
- **Merge with another screen:**
  - Keep `Compliance` as a primary `VERIFY` screen, but make the 4 Framework cards act as a **selectable filter bar** directly above the `Control families` table instead of a separate static row below 4 KPI cards.
- **Visual simplification:**
  - Add a prominent **Amber Staleness Banner** at the top when underlying device evidence is `>24 h` old (`Evaluated against stored evidence from 31 h ago (131 of 132 devices >24 h old)`).
  - Combine Row 1 and Row 2 into **one compact posture strip**: 3 headline KPIs (`Overall Pass Rate: 30.1% (37.5% of evidenced)`, `Critical Failures: 164 across 97 firewalls`, `Evidence Gaps: 496 checks (19.9%)`) alongside a segmented framework selector (`All / CIS 30.1%` · `PCI-DSS 24.1%` · `NIST 800-53 30.5%` · `Financial 28.1%`).
- **Concrete fixes:**
  - Replace `re-check time not recorded` with the actual timestamp of the newest/oldest compliance evaluation (`Last evaluated: <timestamp> · <age>`), or `Evaluation timestamp UNKNOWN`.
  - Disable/lock `Re-check` for roles not permitted to trigger fleet re-evaluation, or label what `Re-check` does (re-evaluates stored evidence vs. contacts devices).

---

### 1.3 Policy & Path Analysis (`09`–`16`)

#### `09-policy-rules.jpg` — Policy › Rules (Container browser)
- **Purpose & Fit:** Select a management policy container (`Panorama` device group or `Check Point MDS` domain) to inspect its access rules. **Fit: Partly.**
  - *Executive:* Sees scale (`59 containers · 2 sources · 110,935 rules`), but no summary of policy risk or hygiene on arrival.
  - *Expert:* **The left pane (`Sources · 2`) and the right pane (`Stored policies`) show the exact same list of 59 containers (`DOM-PAPA-01`, `DOM-INDIA-04`, `DOM-MIKE-08`...) simultaneously!** Meanwhile, every container in the left tree has a bright green dot even when collected `11 h` or `26 h ago` (violating [`UI_DESIGN_LANGUAGE.md`](file:///Users/OzanDur/Codo/nexus-uireview-1105/docs/design/UI_DESIGN_LANGUAGE.md) §1 *"Green only where the word would be read (`Live`, `Ready`, `Completed`); never on a count"*).
- **Remove or hide:**
  - Remove the green status dots on every container row in the left tree (`DOM-PAPA-01`, etc.); a stored snapshot is a neutral fact unless it is stale (`warn`).
  - Hide `0 policies selected` and `Actions ▾` in the left pane when `aiview` has no bulk actions, or until at least 1 checkbox is ticked.
- **Move behind a "details" disclosure:**
  - Truncate `ASSIGNED GATEWAYS` cleanly to `3 gateways (FW-ORION-09 +2)` with the full list in a popover, rather than wrapping 2 lines of hostnames per row.
- **Merge with another screen:**
  - **Unify the unselected landing state of `09-policy-rules.jpg`, `10-policy-objects.jpg`, `11-policy-installation.jpg`, and `12-policy-hygiene.jpg`**: do not show 4 nearly identical 59-container tables under 4 different tabs. Either auto-select the first/most-recent container, or show **one** `Containers Overview` table with columns for `POLICIES`, `RULES`, `HYGIENE FINDINGS`, `INSTALLED TARGETS`, and `LAST COLLECTED`.
- **Visual simplification:**
  - Simplify the two-tier tab bar (`BROWSE / Rules`, `Objects`, `Installation`, `JUDGE / Hygiene`, `TRACE / Changes`, `ASK / Lookup`, `Offline analysis`) by removing the tiny uppercase super-labels (`BROWSE`, `JUDGE`, `TRACE`, `ASK`) above the tabs and reducing the tab count from 7 to 5 (see `10`–`15`).
- **Concrete fixes:**
  - Colour `LAST COLLECTED` timestamps older than 24 h (`oldest 26 h ago` in header) with the `warn` (`Stale`) pill so stale policy snapshots stand out in the table.

#### `10-policy-objects.jpg` — Policy › Objects (Unselected state)
- **Purpose & Fit:** Search network, service, and group objects within a policy container and see where each object is referenced. **Fit: No (in unselected state).**
  - *Executive:* No executive value in an unselected container prompt.
  - *Expert:* Clicking `Objects` shows an info box (`Select a policy container to see its objects`) above the **exact same 59-container table as `09-policy-rules.jpg`** (minus the `ASSIGNED GATEWAYS` column). Worse, an operator investigating an IP or object name often wants to search **across all 59 containers**, not guess which container holds the object first.
- **Remove or hide:**
  - Remove the duplicate 59-container table from `Objects`.
- **Move behind a "details" disclosure:**
  - Keep container-scoped object metadata inside the selected container view.
- **Merge with another screen:**
  - Allow **global cross-container object search** directly on `Objects` when no container is selected (a search bar `Search object name, IP/CIDR, or port across 59 containers...`), and scope it to one container only when a container is selected in the left tree.
- **Visual simplification:**
  - Replace the `Select a policy container to see its objects` banner + 59-row container list with an immediate object search bar and top object categories.
- **Concrete fixes:**
  - When a user clicks `Open` on a row in `10-policy-objects.jpg`, ensure it opens that container on the `Objects` tab (not resetting to `Rules`).

#### `11-policy-installation.jpg` — Policy › Installation (Unselected state)
- **Purpose & Fit:** Show which gateways each management policy package is targeted at and reported as installed on. **Fit: Partly.**
  - *Executive:* Needs to know *"Are any gateways running an un-installed or outdated policy?"*, which is shown on `07-devices-fleet-summary.jpg` (`Policy installed`), not here.
  - *Expert:* In the unselected state, `11-policy-installation.jpg` is **95% identical to `09-policy-rules.jpg`**—it is the exact same 59-container table with `ASSIGNED GATEWAYS` renamed to `INSTALLED ON (TARGETS)` plus a 2-line info banner (`Select a policy container to see where its packages are installed...`).
- **Remove or hide:**
  - Remove `Installation` as a separate top-level tab when no container is selected, since `09-policy-rules.jpg` already shows `ASSIGNED GATEWAYS` for all 59 containers.
- **Move behind a "details" disclosure:**
  - Show package installation targets in the container header strip (`Assigned to: FW-... · Installed: ...`) or as a sub-drawer when inspecting a container.
- **Merge with another screen:**
  - **Merge `11-policy-installation.jpg` with `09-policy-rules.jpg` (container header + gateway column)** and link gateway installation age from `07-devices-fleet-summary.jpg` (`Policy installed`).
- **Visual simplification:**
  - Eliminating the `Installation` tab reduces the `Policy` tab bar from 7 tabs to 6 immediately and removes a duplicate 59-row table.
- **Concrete fixes:**
  - Where package installation status is shown, display a state pill (`Installed` / `Out of sync` / `UNKNOWN`) rather than just listing target hostnames.

#### `12-policy-hygiene.jpg` — Policy › Hygiene (Unselected state)
- **Purpose & Fit:** Identify shadowed, conflicting, broad/permissive, expired, disabled, and unused firewall rules. **Fit: No (in unselected state).**
  - *Executive:* This should be a high-value risk screen (*"Which domains have shadowed, conflicting, or overly permissive rules?"*), yet the unselected screen shows **zero hygiene metrics**—only `Select a policy container to see its hygiene findings` above the same generic 59-container table (`POLICIES`, `RULES`, `LAST COLLECTED`).
  - *Expert:* Has to click into 59 containers one by one to find out which container has conflicts, shadowed rules, or broad rules!
- **Remove or hide:**
  - Remove the generic `Select a policy container to see its hygiene findings` prompt card.
- **Move behind a "details" disclosure:**
  - Move the `UNKNOWN` evidence caveat (`Where the stored evidence is insufficient the finding is UNKNOWN`) to a column header tooltip.
- **Merge with another screen:**
  - Keep `Hygiene` as a tab, but make its unselected view an **Estate Policy Hygiene Summary** across all 59 containers.
- **Visual simplification:**
  - On the unselected `Hygiene` table, replace generic container columns with **hygiene finding counts per container**: `CONTAINER`, `RULES`, `CONFLICTS / SHADOWED`, `BROAD / HIGH RISK`, `EXPIRED / DISABLED`, `UNUSED`, `NOT PROVABLE (UNKNOWN)`, sorted by highest proven risk first.
- **Concrete fixes:**
  - Add 4 top KPI cards on the unselected `Hygiene` view (`Conflicts & Shadowed`, `High-Permissiveness Rules`, `Expired / Disabled`, `Unused >90 d`) across the estate.

#### `13-policy-changes.jpg` — Policy › Changes
- **Purpose & Fit:** Audit rule additions, modifications, and removals across stored policy snapshots over time. **Fit: No (in current state).**
  - *Executive & Expert:* All 6 KPI cards (`CHANGES IN PERIOD`, `DISTINCT ACTORS`, `DISTINCT RULES`, `ADDED`, `MODIFIED`, `REMOVED`) are stuck on grey skeletons (`reading the period`) and the body is stuck on `Loading changes...`.
  - *Layout flaw:* Even though the subtitle says **`Estate-wide stored rule revisions`**, the left pane (`Sources · 2` container tree) stays open and consumes 22% of the horizontal width, forcing the 6 KPI cards and 8 filter controls into a cramped right pane!
- **Remove or hide:**
  - Reduce the 6 KPI cards to 3 (`Total Rule Changes` with `+Added / ~Modified / -Removed` breakdown, `Policies Changed`, `Distinct Actors`).
  - Remove the 2-line methodology disclaimer (`Estate-wide stored rule revisions. Default period: last 30 days. Missing actors are UNKNOWN...`) above the KPI cards; condense to a 1-line subtitle (`Last 30 days · initial baseline captures excluded`).
- **Move behind a "details" disclosure:**
  - Collapse secondary filters (`Actor`, `Change type`, `Changed field`, `Include initial captures`) behind a `More filters` toggle, keeping `Date range`, `Policy/Container`, and `Rule search` visible.
- **Merge with another screen:**
  - Cross-link or unify with `05-operations-change-feed.jpg` so policy revisions and device configuration diffs share one `Changes` experience.
- **Visual simplification:**
  - Hide or auto-collapse the left `Sources · 2` container tree when viewing estate-wide tabs (`Changes`, `Lookup`), or make selecting a container in the left tree automatically filter `Policy` in the `Changes` tab.
- **Concrete fixes:**
  - **Fix stuck loader:** Ensure the default 30-day revision query returns within a bounded timeout (e.g. 5–10 s) or paginates without hanging on `reading the period` / `Loading changes...`.

#### `14-policy-lookup.jpg` — Policy › Lookup
- **Purpose & Fit:** Test which rules in a stored policy snapshot match a given source, destination, service, and zone tuple. **Fit: Partly.**
  - *Executive:* Purely an engineer/operator tool.
  - *Expert:* Clean 8-field form, but: (1) the left `Sources · 2` tree stays visible while the form has its own `Lookup policy*` dropdown; (2) `Policy › Lookup` (`14`) and `Path analysis` (`16`) ask nearly the same tuple (`Source`, `Destination`, `Service/Port`) in two different places in the UI.
- **Remove or hide:**
  - Hide the redundant `Lookup policy*` dropdown if the user already selected a policy in the left tree (or hide the left tree if using the dropdown).
- **Move behind a "details" disclosure:**
  - Move the 4 optional fields (`Source zone`, `Destination zone`, `Install-on target`, `Device context`) behind an `Optional context (zones & target)` disclosure so the primary lookup is just 4 fields (`Policy`, `Source`, `Destination`, `Service`).
- **Merge with another screen:**
  - **Merge `14-policy-lookup.jpg` (`Policy › Lookup`) and `16-path-analysis.jpg` (`Path`) into one unified `Path & Policy Lookup` screen** (with a mode switch: `End-to-end topology path` vs `Single policy rule lookup`), or provide a 1-click `Lookup rules on this hop` transition between them.
- **Visual simplification:**
  - Shorten the disabled button label from `Look up policy intent · Needs a policy, a source, a destination` to `Look up matching rules` (with required-field helper text below).
- **Concrete fixes:**
  - Clicking a container/policy in the left `Sources · 2` tree should pre-fill `Lookup policy*` in the form.

#### `15-policy-offline-analysis.jpg` — Policy › Offline analysis
- **Purpose & Fit:** Upload an exported SIEM traffic CSV and evaluate flows offline against a stored policy snapshot. **Fit: Partly.**
  - *Executive:* Not an executive screen.
  - *Expert:* Honest, safe design (`No live ingestion or device changes. Files and raw rows are never saved`), but this is a specialized offline file-upload utility occupying a permanent tab in `Policy` with a mostly empty canvas.
- **Remove or hide:**
  - Remove `Previous analyses` and `Next analyses` links when `0 stored analyses` exist.
- **Move behind a "details" disclosure:**
  - Move `Offline analysis` out of the main `Policy` tab bar into `Tools` (alongside `Packets` and `Sanitizer`, which are also offline file-upload analyzers) or into an `Analyze CSV` button inside a selected policy.
- **Merge with another screen:**
  - Group with `Tools` (`19-tools-packet-analysis.jpg`, `20-tools-data-sanitizer.jpg`) so all offline file-upload utilities live in one place.
- **Visual simplification:**
  - When no policy is selected in the left tree, provide a `Select policy snapshot` dropdown directly inside the upload card instead of locking the dropzone with `Drop a SIEM CSV here · Select a stored policy first`.
- **Concrete fixes:**
  - Disable/lock the upload dropzone with an explicit `Role: not permitted` state if `aiview` cannot upload SIEM CSVs, matching `20-tools-data-sanitizer.jpg`.

#### `16-path-analysis.jpg` — Path analysis
- **Purpose & Fit:** Compute hop-by-hop routing, NAT, and firewall rule verdicts from Source to Destination across the stored topology and policies. **Fit: Yes (with minor simplification).**
  - *Executive:* Not an executive screen, though useful during major incident bridge calls.
  - *Expert:* Clear purpose and honest framing (`management intent, not observed traffic`), but the 6-column form wraps helper text awkwardly (`Optional · the unit the traffic enters, when the source could belong to several`) and duplicates `14-policy-lookup.jpg`.
- **Remove or hide:**
  - Condense the 2-line blue info box (`This session sees masked names and addresses...`) into a compact 1-line inline pill (`Masked session: enter masked names (FW-TANGO-04) or synthetic addresses`).
  - Remove the rate-limit caption `Up to 30 queries a minute. Each query is recorded with its verdict; the result itself is not stored` from next to the primary button (move to a tooltip).
- **Move behind a "details" disclosure:**
  - Move `Ingress unit`, `Ingress interface`, and the `Up to the edge (ALLOWED_TO_EDGE)` checkbox behind an `Advanced path options` disclosure so the primary bar has only `Source*`, `Destination*`, `Protocol`, `Port*`, and `Evaluate path`.
- **Merge with another screen:**
  - Combine with `14-policy-lookup.jpg` (`Single-policy lookup` as a secondary tab or mode on `Path analysis`) so all traffic-simulation queries live together under `VERIFY / TRACE`.
- **Visual simplification:**
  - Shorten the button label from `Evaluate path · Needs a source, a destination` to `Evaluate path`.
- **Concrete fixes:**
  - Fix the 3-line wrapped helper caption under `Ingress unit` by moving optional ingress inputs into the advanced row.

---

### 1.4 Backups & Tools (`17`–`20`)

#### `17-backups.jpg` — Backups
- **Purpose & Fit:** Verify that every backup target has a recent, restorable encrypted archive and trigger/manage fleet backups. **Fit: Partly.**
  - *Executive:* Sees `126 of 130 targets` and `02:00 daily`, but **missed the fact that the last scheduled backup ran `47 h ago`** (nearly 2 days ago on a daily schedule!) because `SCHEDULED FLEET BACKUP` has no `Stale` or `Missed window` warning badge.
  - *Expert:* **The actual Backups device table is pushed completely off the screen below the fold** by a huge 21-row list of `MAJOR CONFIGURATION CHANGES · LAST 7 DAYS` (`FW-HOTEL-09-M2 Admin access users · changed...` repeated 21 times)! When an operator opens `Backups`, they need to see the **4 targets without an archive** and the backup status table immediately—not 21 rows of `Admin access users` configuration diffs.
- **Remove or hide:**
  - Remove the uncollapsed 21-row `MAJOR CONFIGURATION CHANGES · LAST 7 DAYS` list from the middle of the `Backups` screen (above the backup table).
  - Remove the 2-line accounting footnote inside Card 1 (`127 devices hold a stored backup; 1 of them is not a backup target · Counted from the artefact store, not from inventory`).
- **Move behind a "details" disclosure:**
  - Make `ACTIVE MAJOR DEVIATIONS (36 devices)` open a drawer or filter the table when clicking `Which devices →` instead of rendering a 600 px-tall list above the backup table.
  - Move `Retention & Policies` details (`7 days`, Gaia snapshot rules) into the existing `Retention & Policies` button modal, freeing Card 3 for **`MISSING ARCHIVES: 4 targets`** (red KPI card that filters the table to the 4 missing targets on click!).
- **Merge with another screen:**
  - Move `MAJOR CONFIGURATION CHANGES · LAST 7 DAYS` to `Operations › Change feed` (`05`) / `Devices › Configuration` (`41`), where configuration changes belong.
- **Visual simplification:**
  - Replace the 4 KPI cards (`TARGETS WITH A STORED BACKUP`, `SCHEDULED FLEET BACKUP`, `RETENTION POLICY`, `ACTIVE MAJOR DEVIATIONS`) with:
    1. `Backup Coverage`: `126 of 130 targets`
    2. `Missing Archives`: **`4 targets`** (`bad` role, 1-click filter)
    3. `Last Scheduled Run`: `47 h ago` (`warn` `Stale — daily 02:00 missed`)
    4. `Storage & Retention`: `7 days · 396 GB`
  - Bring the **Backups device table** directly below the 4 KPI cards (above the fold), sorted by `Missing / Failed` first.
- **Concrete fixes:**
  - Disable/lock the primary blue `Run Fleet Backup` button for `aiview` (`Role: not permitted`).
  - Add an amber `Stale (47 h ago)` pill to `SCHEDULED FLEET BACKUP` whenever a daily job is `>26 h` old.

#### `18-tools-menu.jpg` — Left-rail `Tools` popover (`Packets`, `Sanitizer`)
- **Purpose & Fit:** Provide navigation to standalone forensic and data-handling utilities (`Packets`, `Sanitizer`). **Fit: Partly.**
  - *Executive:* Irrelevant to executives.
  - *Expert:* Works as a secondary menu so `Packets` and `Sanitizer` don't clutter primary rail slots, but two other ad-hoc utilities—`Diagnostics` (`03`) and `Offline analysis` (`15`)—are scattered inside `Operations` and `Policy`.
- **Remove or hide:**
  - Nothing to remove from the popover itself, or replace the popover with a single `Tools` workspace page with cards/tabs.
- **Move behind a "details" disclosure:**
  - Keep `Tools` at the bottom of the operational rail.
- **Merge with another screen:**
  - **Add `Diagnostics` (`03`) and `Offline SIEM Analysis` (`15`) into `Tools`** so all 4 ad-hoc/offline engineering utilities (`Diagnostics`, `Packet Analysis`, `Offline SIEM Analysis`, `Data Sanitizer`) live in one predictable menu.
- **Visual simplification:**
  - Show a lock icon next to `Sanitizer` in the `Tools` popover when the current role (`aiview`) is not permitted to use it.
- **Concrete fixes:**
  - Ensure the `Tools` rail icon stays highlighted whenever any tool route (`Packets`, `Sanitizer`, `Diagnostics`) is active.

#### `19-tools-packet-analysis.jpg` — Tools › Packet Analysis
- **Purpose & Fit:** Create an offline investigation case, upload `pcap`/`pcapng` captures, and inspect evidence-led stage analysis. **Fit: Yes.**
  - *Executive:* Not an executive screen.
  - *Expert:* Clean, calm workspace (`New case` + `No case yet`), and `aiview` is explicitly authorized to create and analyze its own masked cases per PO decision 2026-10-18.
- **Remove or hide:**
  - Shorten the 2-line subtitle and 2-line `No case yet` card so the same facts (`7-day retention`, `UNKNOWN when not established`, `offline only`) are not stated twice in 150 pixels.
- **Move behind a "details" disclosure:**
  - Move storage quota details (`Store: 0 B of 10.0 GB (0% used) · at most 2.0 GB per capture`) to the right edge of the `New case` card header.
- **Merge with another screen:**
  - Keep under `Tools`.
- **Visual simplification:**
  - Combine `New case` and `No case yet` into a single card when 0 cases exist (`Create your first packet analysis case`), showing the case table only when cases exist.
- **Concrete fixes:**
  - Enable `Create case` visually only when `Case title` is non-empty, with a clear placeholder (`e.g. FW-TANGO-04 TLS handshake drop`).

#### `20-tools-data-sanitizer.jpg` — Tools › Data Sanitizer
- **Purpose & Fit:** Tokenize sensitive identifiers in uploaded files (`pcap`, `log`, `CSV`, `XLSX`, `JSON`) or revert tokenized files via the encrypted vault. **Fit: Partly (for `aiview`).**
  - *Executive:* Not an executive screen.
  - *Expert:* Correctly blocks `aiview` with an explicit role explanation (`Your role does not allow this action · sanitize: Operator, Operation admin, Security admin...`), but leaves the `Sanitize` / `Revert` toggle active-looking inside a full-width card wrapped around a refusal message.
- **Remove or hide:**
  - When the user's role has neither `sanitize` nor `revert` permission (`aiview`), hide the interactive `Sanitize` / `Revert` toggle and the bottom file-size footer (`At most 2.0 GB per file...`).
- **Move behind a "details" disclosure:**
  - Move vault retention mechanics (`30 days, encrypted`, `60 minutes`) into a collapsible `How vault tokenization works` note for permitted users.
- **Merge with another screen:**
  - Keep under `Tools`.
- **Visual simplification:**
  - Render the standard [`UI_DESIGN_LANGUAGE.md`](file:///Users/OzanDur/Codo/nexus-uireview-1105/docs/design/UI_DESIGN_LANGUAGE.md) `StatePanel` (`restricted` variant) directly instead of an outer form card containing a dashed refusal box.
- **Concrete fixes:**
  - Disable the `Sanitize` / `Revert` segmented buttons when the user lacks permission.

---

### 1.5 Administration (`21`–`34`)

#### `21-admin-device-management.jpg` — Administration › Device management
- **Purpose & Fit:** Enrol, inspect, and manage the lifecycle and collection scope of all 134 devices in the registry. **Fit: Partly.**
  - *Executive:* Not an executive screen.
  - *Expert:* Functional registry table, but suffers from visual collision in the `VENDOR` column, tight right-edge alignment in the right-hand summary cards (`Enrollment`, `Collection scope`), repetitive "134" counts (printed 3 times in 80 px), and active-looking `Import from manager` + `+ Add device` buttons for read-only `aiview`.
- **Remove or hide:**
  - Remove the duplicate line `134 devices in the registry.` directly beneath `Device registry · 134 entries` (and `134 of 134` on the right of the search bar).
  - Hide or visibly lock (`Role: not permitted`) `Import from manager`, `+ Add device`, row checkboxes, and row `⋮` action menus for `aiview`.
- **Move behind a "details" disclosure:**
  - Move the `Collection scope` prose card (`Inventory and configuration are read from every enrolled device...`) into a 1-line subtitle or info tooltip.
- **Merge with another screen:**
  - **Merge `22-admin-inventory-exclusions.jpg` (`Inventory exclusions`) and `24-admin-ssh-trust.jpg` (`SSH trust`) into `Device management`** as filter chips / settings on the Registry page, eliminating two nearly empty sub-pages.
- **Visual simplification:**
  - Move the 3 `Enrollment` filter pills (`Enrolled · 132`, `Last collection failed · 0`, `Draft · not collected · 2`) from the right-hand sidebar card to **horizontal filter chips directly above the table** (matching `01` and `02`), and **delete the right-hand sidebar column entirely**! Removing that right sidebar gives the table 25% more horizontal width.
- **Concrete fixes:**
  - **Fix `VENDOR` / `DEVICE / HOSTNAME` column collision:** Drop the redundant short badge (`FTNT`, `ASA`) when the full vendor name (`Fortinet`, `Cisco ASA`) is already printed right next to it (or show only the coloured dot + `Cisco ASA` with a 120 px column width) so `ASA Cisco ASA FW-FOXTROT-05` stops colliding.
  - **Fix right-edge clipping:** Removing the right-hand `Enrollment` / `Collection scope` sidebar eliminates the right-edge crowding on `21-admin-device-management.jpg`.
  - **Fix global Admin header:** Scope `Import from manager` and `+ Add device` strictly to `Device management` (`21`), so they stop appearing on `22`–`34` (`LDAP Settings`, `System`, `Delivery plan`, `Audit Logs`, `Job Logs`).

#### `22-admin-inventory-exclusions.jpg` — Administration › Inventory exclusions
- **Purpose & Fit:** View and manage devices excluded from inventory collection while remaining enrolled for configuration evidence. **Fit: No (as a standalone page).**
  - *Executive & Expert:* 90% blank screen containing a single 2-line box (`No device excluded...`), while `Import from manager` and `+ Add device` sit in the top right.
- **Remove or hide:**
  - Remove `Inventory exclusions` from the Administration left sub-navigation.
- **Move behind a "details" disclosure:**
  - Explain exclusion semantics in a tooltip on the `Excluded` filter chip in `Device management` (`21`).
- **Merge with another screen:**
  - **Merge into `21-admin-device-management.jpg`** as a 4th filter chip (`Excluded from inventory · 0`) and a row action in the device registry.
- **Visual simplification:**
  - One less sub-page in Administration.
- **Concrete fixes:**
  - Remove `Import from manager` and `+ Add device` from non-registry pages.

#### `23-admin-credentials.jpg` — Administration › Credentials
- **Purpose & Fit:** Manage vault credential references and target bindings for device collection (admin-only). **Fit: No (for `aiview`).**
  - *Executive & Expert:* Renders only bare 13 px text `Available to administrators` in the top-left corner of an empty canvas, while `Import from manager` and `+ Add device` remain enabled-looking in the top right, and the `Administration` subtitle still says `Device registry, collection scope and delivery plan...`.
- **Remove or hide:**
  - **Hide or group under a collapsed/locked `Admin-only (7)` section** in the Administration left nav for `aiview` / non-admin roles (`23`, `25`, `26`, `27`, `28`, `30`, `31`, `33`)—notice that **8 of the 14 Administration pages** (7 access/platform pages + `33-admin-audit-logs.jpg`) show only `"Available to administrators"`!
- **Move behind a "details" disclosure:**
  - If a non-admin navigates via deep link to a restricted admin route, show a single `StatePanel` (`restricted` variant: lock icon, required role name `Security Administrator`, and what the screen governs).
- **Merge with another screen:**
  - For administrators, group `Credentials` and `SSH trust` (`24`) as tabs under one `Credentials & Trust` page.
- **Visual simplification:**
  - Do not expose 8 dead-end menu items in the left nav to a read-only viewer; either hide them from the nav for `aiview` or mark them inline with a `🔒` icon and disabled state so the user does not click through 8 blank pages.
- **Concrete fixes:**
  - Replace bare `Available to administrators` text with `StatePanel(variant="restricted")` and remove the top-right `+ Add device` / `Import from manager` buttons.

#### `24-admin-ssh-trust.jpg` — Administration › SSH trust
- **Purpose & Fit:** Configure SSH host-key verification policy (`WARN` vs strict) for device connections. **Fit: No (as a standalone page).**
  - *Executive & Expert:* An entire page in Administration dedicated to **one single dropdown** (`Trust mode: WARN`) occupying 5% of the screen, with no table of pinned SSH host keys or trust mismatches!
- **Remove or hide:**
  - Remove `SSH trust` as a separate left-nav page.
- **Move behind a "details" disclosure:**
  - Place the `SSH host-key trust mode` setting inside `Device management` (`21`) header settings (or alongside `Credentials` for admins).
- **Merge with another screen:**
  - **Merge into `21-admin-device-management.jpg`** (or `Credentials & Trust`), and surface per-device SSH key trust status (`Pinned` / `Mismatch` / `Missing`) as a column or filter in `Device management`.
- **Visual simplification:**
  - Eliminates a 95%-empty screen.
- **Concrete fixes:**
  - Disable the `Trust mode: WARN` select input for read-only `aiview` (`24` currently renders it as an interactive-looking dropdown).

#### `25-admin-local-identities.jpg` — Administration › Local identities
- **Purpose & Fit:** Manage local user accounts (admin-only). **Fit: No (for `aiview`).**
- **Remove / Merge / Fix:** Same as `23-admin-credentials.jpg`—shows bare `Available to administrators` with misplaced `Import from manager` / `+ Add device` buttons. For admins, merge `Local identities` (`25`), `Sessions` (`26`), `Roles & Permissions` (`27`), and `LDAP Settings` (`28`) into a single **`Access & Identity`** screen with 4 tabs, reducing 4 left-nav items to 1 (and locking/hiding that single item for `aiview`).

#### `26-admin-sessions.jpg` — Administration › Sessions
- **Purpose & Fit:** Inspect and revoke active user sessions (admin-only). **Fit: No (for `aiview`).**
- **Remove / Merge / Fix:** Same bare `Available to administrators` page as `23`/`25`. Merge as Tab 2 of `Access & Identity` and hide/lock for `aiview`.

#### `27-admin-roles-permissions.jpg` — Administration › Roles & Permissions
- **Purpose & Fit:** View and configure RBAC roles and action permissions (admin-only). **Fit: No (for `aiview`).**
- **Remove / Merge / Fix:** Same bare `Available to administrators` page. Merge as Tab 3 of `Access & Identity`. *(Optional improvement: allow read-only viewers to see a read-only matrix of their own current role's permissions instead of a blank refusal).*

#### `28-admin-ldap.jpg` — Administration › LDAP Settings
- **Purpose & Fit:** Configure enterprise LDAP/AD authentication integration (admin-only). **Fit: No (for `aiview`).**
- **Remove / Merge / Fix:** Same bare `Available to administrators` page. Merge as Tab 4 of `Access & Identity` and hide/lock for `aiview`.

#### `29-admin-system.jpg` — Administration › System
- **Purpose & Fit:** Monitor neXus platform storage consumption across encrypted backups, configuration CAS blobs, PostgreSQL tables, and free disk volume. **Fit: Partly.**
  - *Executive:* Useful capacity headline (`396 GB Backups`, `176 GB Free of 1006 GB`), though buried in Administration.
  - *Expert:* Informative storage ledger, but Card 4 (`ARTEFACT VOLUME FREE 176 GB`) prints internal Kubernetes plumbing notes (`of 1006 GB · the artefact volume is a host-path volume; its size is the host disk's, not the 5 Gi the claim declares`), and `Import from manager` / `+ Add device` still sit in the top-right header!
- **Remove or hide:**
  - Remove the Kubernetes PVC vs. host-path implementation comment (`the artefact volume is a host-path volume; its size is the host disk's, not the 5 Gi the claim declares`) from the `ARTEFACT VOLUME FREE` KPI card.
  - Remove `Import from manager` and `+ Add device` from the header.
- **Move behind a "details" disclosure:**
  - Collapse the 14-row per-vendor × kind breakdown table (`Blue Coat Backup`, `Blue Coat Configuration`, etc.) behind a `Storage breakdown by vendor & artefact kind` accordion, showing the 4 KPI cards, a visual capacity bar (`82% used · 830 GB of 1,006 GB`), and `Largest backup footprint` first.
- **Merge with another screen:**
  - Keep under `Administration › System` (and surface a disk-capacity warning on `Operations` only if free space drops below 20%).
- **Visual simplification:**
  - Add a simple usage bar (`82% used`) on `ARTEFACT VOLUME FREE` (`176 GB free of 1.0 TB`) so capacity risk is scannable at a glance.
- **Concrete fixes:**
  - Format `OLDEST` and `NEWEST` timestamps in the table with relative ages (`16 d ago`, `47 h ago`) instead of repeating `GMT+3` 28 times across 14 rows.

#### `30-admin-notifications.jpg` — Administration › Notifications
- **Purpose & Fit:** Configure alert and notification channels (admin-only). **Fit: No (for `aiview`).**
- **Remove / Merge / Fix:** Same bare `Available to administrators` page with misplaced `Import from manager` / `+ Add device` buttons. Hide/lock for `aiview`, or fold into `System` (`29`) as a tab for admins.

#### `31-admin-system-logs.jpg` — Administration › System logs
- **Purpose & Fit:** Configure device system-log collection and Spike Detective settings (admin-only). **Fit: No (for `aiview`).**
- **Remove / Merge / Fix:** Same bare `Available to administrators` page. Moreover, the label **`System logs`** under `PLATFORM` is highly confusing: operators think it means *neXus platform logs* (especially sitting above `Audit Logs` and `Job Logs`), when in fact (`CURRENT_STATE.md` line 27) it is the admin toggle for *device system-log collection*! Rename to **`Log collection settings`** (or merge into `System` / `Collection scope`) and hide/lock for `aiview`.

#### `32-admin-delivery-plan.jpg` — Administration › Delivery plan
- **Purpose & Fit:** Display the internal software-engineering roadmap, acceptance-criterion completion percentage, and backlog queue (`project/*.json`). **Fit: No (in a production enterprise UI).**
  - *Executive & Expert:* Shows internal developer git hashes (`Source revision: e17d0ab...`), internal task slugs (`NXS-LOCAL-0374`, `overview_acceptance_and_member_specific_tuning`), and **two nearly identical `58.1%` cards side by side** (`DECLARED ROADMAP COMPLETION 58.1%` and `CURRENT MAJOR TRACK UI2.x 58.1%`). A bank IT executive or firewall engineer has no use for internal engineering sprint tickets inside `Administration`.
- **Remove or hide:**
  - **Remove `Delivery plan` from `Administration` and from the customer-facing product navigation** (or gate it behind a development-only `/about/roadmap` route / build-pill click in the top bar; see §2).
- **Move behind a "details" disclosure:**
  - If retained in development mode under the top-bar `Build cd096bb` pill, collapse `Converted Python lessons — historical / derived` and raw git revision hashes.
- **Merge with another screen:**
  - Move to the top-bar `Build cd096bb · deployed 2h ago` dialog (`About / Build & Roadmap`) or the standalone developer workbench (`orchestrator.py dashboard`).
- **Visual simplification:**
  - Deduplicate the two identical `58.1%` progress cards (`DECLARED ROADMAP COMPLETION` and `CURRENT MAJOR TRACK UI2.x`).
- **Concrete fixes:**
  - Remove `Import from manager` and `+ Add device` from the header.

#### `33-admin-audit-logs.jpg` — Administration › Audit Logs
- **Purpose & Fit:** Inspect immutable security audit records of user and system actions (admin-only). **Fit: No (for `aiview`).**
  - *(Note: The prompt notes mention "seven Administration pages show only 'Available to administrators' to aiview", and `33-admin-audit-logs.jpg` is actually an **eighth** bare `"Available to administrators"` page alongside `23`, `25`, `26`, `27`, `28`, `30`, `31`!)*
- **Remove / Merge / Fix:** Hide/lock for `aiview` or render `StatePanel(variant="restricted")`, and remove `Import from manager` / `+ Add device` from the header.

#### `34-admin-job-logs.jpg` — Administration › Job Logs
- **Purpose & Fit:** View historical job execution records. **Fit: No (100% duplicate).**
  - *Executive & Expert:* The top of the screen literally admits: **`Same records as Operations · Jobs.`** It renders the exact same `42,415` jobs table as `02-operations-jobs.jpg`, including the `Unknown device` rows and `Import from manager` / `+ Add device` buttons in the header.
- **Remove or hide:**
  - **Delete `Job Logs` (`34`) from `Administration` completely.**
- **Move behind a "details" disclosure:**
  - None needed—`02-operations-jobs.jpg` already has full filtering and CSV export.
- **Merge with another screen:**
  - **Merged 100% into `02-operations-jobs.jpg` (`Operations › Jobs`).**
- **Visual simplification:**
  - Removes a duplicate 42,415-row table from the application.
- **Concrete fixes:**
  - Redirect any old link to `/admin/job-logs` straight to `Operations › Jobs`.

---

### 1.6 Device & Cluster Detail Screens (`35`, `38`–`44`)

#### `35-devices-virtual-system-detail.jpg` — Devices › `CLS-ALPHA-01` › `VS-ALPHA-01-13` (Virtual System detail)
- **Purpose & Fit:** Inspect the interfaces, routing, and per-member state of a single Check Point VSX Virtual System (`VS-ALPHA-01-13`). **Fit: Partly.**
  - *Executive:* Too granular for executives, though the header plane pills summarize compliance/backup/readiness.
  - *Expert:* Clean `Interfaces` table (`3 of 3 shown`), **but the ObjectHeader has three major issues**:
    1. **Dead clicks:** The `M1` / `M2` pill buttons in the top-right of the header AND the two large member cards (`FW-ALPHA-01-M1 ACTIVE`, `FW-ALPHA-01-M2 STANDBY`) look interactive but do nothing when clicked.
    2. **False-fresh green readiness pill:** The header displays a bright green `Readiness Ready · 31 h ago` pill, contradicting `01-operations-ha-readiness.jpg` where `30 h ago` readiness is flagged with an amber `Stale` warning (`run a readiness check before relying on Ready`).
    3. **Missing VS Failover visibility:** On `44-cluster-failover.jpg`, the chassis says *"each Virtual System below is failed over on its own"*, yet `VS-ALPHA-01-13` (`35`) has only `Interfaces` and `Routing` tabs—no `Failover` tab or link!
- **Remove or hide:**
  - Remove the non-functional `M1` and `M2` buttons in the top-right corner of the `ObjectHeader` (above `Collect now`).
  - Remove the repeated model/version string (`Check Point 29100 (RH-10-00) · R81.20`) inside both `FW-ALPHA-01-M1` and `FW-ALPHA-01-M2` cards when it is identical to the cluster header's pill (`Check Point 29100 (RH-10-00) · R81.20 · Gaia`) directly above them.
- **Move behind a "details" disclosure:**
  - Collapse the two member cards (`FW-ALPHA-01-M1 ACTIVE`, `FW-ALPHA-01-M2 STANDBY`) into compact inline chips (`M1: FW-ALPHA-01-M1 (ACTIVE) · M2: FW-ALPHA-01-M2 (STANDBY)`), saving 55 px of vertical height on every tab (`35`–`44`).
- **Merge with another screen:**
  - Make each plane pill in the header (`Compliance 2 critical failing`, `Members compared · differ`, `Backup M1 47 h ago`, `Readiness Ready · 31 h ago`) a **working deep-link** to the corresponding tab or screen (`Compliance` filtered to this device, `Configuration` tab, `Backups`, `Failover` tab).
- **Visual simplification:**
  - Highlight `Platform read 31 h ago · Config read 2 d ago` and `Backup M1 47 h ago` in `warn` (`Stale`) when `>24 h`, and change `Readiness Ready · 31 h ago` from `ok` (green) to `warn` (`Ready · Stale 31 h ago`) so header pills never contradict `Operations`.
- **Concrete fixes:**
  - **Fix dead clicks:** Either remove the top-right `M1`/`M2` header pills or wire them (and the `FW-ALPHA-01-M1` / `FW-ALPHA-01-M2` cards) to select/open that member in the tree/drawer.
  - Disable/lock `↓ Collect now` if outside the admission window or not permitted.

#### `38-cluster-interfaces.jpg` — Devices › `CLS-ALPHA-01` › Interfaces
- **Purpose & Fit:** View cluster-wide interfaces, shared VIPs, per-member IP addresses (`FW-ALPHA-01-M1`, `FW-ALPHA-01-M2`), and link states side by side. **Fit: Yes (with header & MAC fixes).**
  - *Executive:* N/A (operator detail).
  - *Expert:* Side-by-side `CLUSTER VIP`, `FW-ALPHA-01-M1`, and `FW-ALPHA-01-M2` columns make HA interface verification fast and clear.
- **Remove or hide:**
  - Remove the `MAC` column (or hide by default): under `aiview`, every row prints two stacked, unlabelled, identical masked strings (`00:1c:7f:**:**:**` / `00:1c:7f:**:**:**`), adding visual clutter with zero information.
- **Move behind a "details" disclosure:**
  - Move `MAC` (labelled `M1:` and `M2:`) into row hover/expansion if ever needed.
- **Merge with another screen:**
  - Keep as the primary default tab of a cluster/device.
- **Visual simplification:**
  - Apply the compact `ObjectHeader` cleanup described in `35` (remove dead `M1`/`M2` buttons, deduplicate model/version text, mark `31 h ago` reads as `Stale`).
- **Concrete fixes:**
  - Label any multi-line member cell with `M1` / `M2` prefixes, or omit masked duplicate MAC lines.

#### `39-cluster-routing.jpg` — Devices › `CLS-ALPHA-01` › Routing
- **Purpose & Fit:** Inspect the unified routing table across cluster members and spot asymmetric/member-specific routes (`Differences only`). **Fit: Yes.**
  - *Executive:* N/A (operator detail).
  - *Expert:* One of the cleanest tabs in the product—clear `All routes (3)` vs `Differences only` filter and `SAME ON BOTH MEMBERS` (`Shared`) column.
- **Remove or hide:**
  - Hide the pagination footer (`1–3 of 3 < >`) when the total row count fits on one page (`≤50 rows`).
- **Move behind a "details" disclosure:**
  - None needed.
- **Merge with another screen:**
  - Keep as a core device/cluster tab, and add a `Trace path from here →` row action that opens `Path analysis` (`16`) pre-filled with that route's destination/unit.
- **Visual simplification:**
  - Inherits the compact `ObjectHeader` cleanup from `35`/`38`.
- **Concrete fixes:**
  - None beyond the shared `ObjectHeader` fixes (`35`).

#### `40-cluster-members.jpg` — Devices › `CLS-ALPHA-01` › Cluster members
- **Purpose & Fit:** List the physical firewall members that form the cluster along with their HA roles, models, and read timestamps. **Fit: No (100% redundant tab).**
  - *Executive & Expert:* Look at `40-cluster-members.jpg`: the `ObjectHeader` directly above the tab bar already displays `FW-ALPHA-01-M1 · ACTIVE · Check Point 29100 (RH-10-00) · R81.20` and `FW-ALPHA-01-M2 · STANDBY · Check Point 29100 (RH-10-00) · R81.20`. Below the tab bar, `Cluster members` renders a 2-row table with the **exact same facts** (`MEMBER HOSTNAME`, `HA ROLE`, `MODEL / VERSION`, `STATUS`, `LAST READ`) plus a raw internal UUID column (`DEVICE ID: 0b153e5f-7066-48f8-a5e7-40c9e1a27299`)! And two tabs to the right, `43-cluster-identity.jpg` (`Identity`) renders another 2-row table of the exact same two members (`FW-ALPHA-01-M1`, `FW-ALPHA-01-M2`) with the exact same `MODEL`, `SOFTWARE VERSION`, `HA ROLE`, `ENROLLMENT`, and `LAST INVENTORY READ` columns!
- **Remove or hide:**
  - **Remove the `Cluster members` tab (`40`) completely**, or merge `Cluster members` (`40`) and `Identity` (`43`) into a single **`Members & Identity`** tab.
- **Move behind a "details" disclosure:**
  - Move raw internal `DEVICE ID` UUIDs (`0b153e5f-...`) into a copy icon (`Copy ID`) on each member row.
- **Merge with another screen:**
  - **Merge `40-cluster-members.jpg` and `43-cluster-identity.jpg` into one `Members & Identity` tab.**
- **Visual simplification:**
  - Reduces the cluster tab bar from 7 tabs to 6 and eliminates three-way duplication on the same page.
- **Concrete fixes:**
  - Make clicking a member row (or header chip) navigate to or filter member-specific views.

#### `41-cluster-configuration.jpg` — Devices › `CLS-ALPHA-01` › Configuration
- **Purpose & Fit:** Compare observed configuration settings across cluster members side by side and highlight unexpected drift vs. expected member-specific settings (`MEMBER`). **Fit: Yes (with default-filter fix).**
  - *Executive:* Sees `3 differences` in the summary line.
  - *Expert:* Excellent semantic distinction between `3 differences` (red) and `11 expected member-specific differences` (`MEMBER` badge), plus clickable jump links (`Differences: Interfaces · Interface eth5-02 · Link Speed...`). However, **the `Differences only` toggle is OFF by default**, so the screen opens on `System` (`0 diff`) and `Interface Mgmt - IPv4 Address EXPECTED`, forcing the engineer to toggle `Differences only` or scroll to see the 3 actual differences!
- **Remove or hide:**
  - Remove the redundant double heading `OBSERVED CONFIGURATION · MEMBERS SIDE BY SIDE` + `Cluster configuration` above the summary card (keep the controls inline with the summary card).
- **Move behind a "details" disclosure:**
  - Move the 2-line explanation (`Hostnames, member addresses, HA device priority and interface physical settings... may hold different values: shown per member as MEMBER, not counted...`) into an info tooltip next to `11 expected member-specific differences`.
- **Merge with another screen:**
  - Keep as a core cluster/device tab; clicking `Members compared · differ` in the `ObjectHeader` should jump straight to this tab with `Differences only` switched ON.
- **Visual simplification:**
  - **Default `Differences only` to ON whenever `differences > 0`** (and auto-collapse sections with `0 diff` like `System`), so the operator sees the 3 `Link Speed` drift rows immediately on arrival in 0 clicks.
- **Concrete fixes:**
  - Align the `FW-ALPHA-01-M1` and `FW-ALPHA-01-M2` column headers across accordion sections (`System` vs `Interfaces` currently have different horizontal column offsets).

#### `42-cluster-logs.jpg` — Devices › `CLS-ALPHA-01` › Logs
- **Purpose & Fit:** Inspect masked gateway system logs (`cpd.elg`, `cpwd.elg`, `messages`, `routed.elg`), error/warning counts, process restarts, and crash/dump evidence per member. **Fit: Partly.**
  - *Executive:* N/A (deep troubleshooting screen).
  - *Expert:* Rich diagnostic telemetry, **but only 3 lines of actual log output fit at the bottom of the 1440 px viewport** because `ObjectHeader` + `M1/M2` selector + 4 KPI cards + a 3-line wall of `File truncated on the device ×22 (messages 06:27, messages 00:27...)` + sub-tabs (`Lines`, `Collection health`) + filter bar consume **85% of the vertical screen height**!
  - Also notice: `42-cluster-logs.jpg` has its own working `M1` / `M2` toggle below the `Logs` tab while the `ObjectHeader` above it has a *second*, non-working `M1` / `M2` toggle!
- **Remove or hide:**
  - Remove the duplicate non-working `M1` / `M2` toggle in the `ObjectHeader` (or use one member selector in the header that drives `Logs`).
  - Remove the 22 repeated filename/timestamp items printed inline in `File truncated on the device ×22 (messages 06:27, messages 00:27, messages 00:27, cpwd.elg 18:27...)`.
- **Move behind a "details" disclosure:**
  - Collapse `File truncated on the device ×22` and `First collection: older content not backfilled...` into the existing `Collection health` sub-tab or `Run details ›` drawer, leaving only a 1-line compact summary pill (`22 truncated files · see Collection health`).
  - Condense the 4 large KPI cards (`LAST COLLECTION`, `ERROR / WARNING LINES`, `PROCESS RESTARTS`, `NEW CRASH / DUMP FILES`) into a **single 1-line status strip** (`Last read: 18:27 (31 h ago, Stale) · Errors/Warnings: 0 / 0 · Restarts: UNKNOWN · New crash/dumps: 0`), freeing ~180 px of vertical height for the log viewer!
- **Merge with another screen:**
  - Link from `04-operations-system-health.jpg` (`Operations › System health`) directly to this tab.
- **Visual simplification:**
  - Replacing the 4 tall KPI cards + 3-line truncation list with a 1-line summary bar lets the operator see **15–20 log lines** above the fold instead of 3.
- **Concrete fixes:**
  - Mark `LAST COLLECTION 18:27 · 31 h ago` with the `warn` (`Stale`) pill.

#### `43-cluster-identity.jpg` — Devices › `CLS-ALPHA-01` › Identity
- **Purpose & Fit:** Display per-member platform facts, serial tokens, software/hotfix versions, uptime, management addresses, and inventory read timestamps. **Fit: Partly (clipped & duplicates `40`).**
  - *Executive:* N/A.
  - *Expert:* Packs **12 columns** (`MEMBER`, `SERIAL`, `MODEL`, `SOFTWARE VERSION`, `HOTFIX / JUMBO`, `UPTIME`, `HA ROLE`, `MANAGEMENT ADDRESS`, `VSX`, `ENROLLMENT`, `PLATFORM FACTS OBSERVED`, `LAST INVENTORY READ`) into a single horizontal table inside the right pane:
    - **Right-edge clipping:** `LAST INVENTORY READ` is cut off at the right edge (`2026-11-03 18:01 G...`).
    - **Severe vertical wrapping:** `SERIAL`, `MODEL`, `HOTFIX / JUMBO`, and `UPTIME` wrap into 3 lines each.
    - **Missing security identity:** Despite the tab being named `Identity`, it does not show SSH host-key / TLS certificate verification status (`Identity verified` / pinned key match)!
- **Remove or hide:**
  - Remove `PLATFORM FACTS OBSERVED` (`2026-11-03 18:01 GMT+3 · 31 h ago`) when it is identical to the adjacent `LAST INVENTORY READ` column (`2026-11-03 18:01 G...`)! Printing two identical 24-character timestamp columns side by side is the direct cause of the right-edge clipping.
  - Remove `VSX` (`+ 2 · same as cluster`) from member rows when the cluster header and left tree already show the 2 VSX children.
- **Move behind a "details" disclosure:**
  - Put `Reference CLS-ALPHA-01 Copy` and member UUIDs (`from 40`) in the card header.
- **Merge with another screen:**
  - **Merge `40-cluster-members.jpg` (`Cluster members`) and `43-cluster-identity.jpg` (`Identity`) into a single `Members & Identity` tab.**
- **Visual simplification:**
  - Render the 2 cluster members as **2 side-by-side member comparison cards** (or a clean 7-column table: `MEMBER`, `HA ROLE`, `MODEL & VERSION (R81.20 · Take 119)`, `SERIAL`, `MGMT ADDRESS`, `UPTIME`, `TRUST & LAST READ`) instead of a 12-column table.
- **Concrete fixes:**
  - **Fix right-edge clipping on `43-cluster-identity.jpg`:** Combine `SOFTWARE VERSION` + `HOTFIX / JUMBO` into one column (`R81.20 · Jumbo Take 119`), merge the duplicate `PLATFORM FACTS OBSERVED` / `LAST INVENTORY READ` columns into one compact `LAST READ` column (`31 h ago` with full stamp on hover per [`UI_DESIGN_LANGUAGE.md`](file:///Users/OzanDur/Codo/nexus-uireview-1105/docs/design/UI_DESIGN_LANGUAGE.md) §3 #7), and include the member's `Identity verified` / SSH trust badge.

#### `44-cluster-failover.jpg` — Devices › `CLS-ALPHA-01` › Failover
- **Purpose & Fit:** Inspect HA failover readiness pre-checks and failover unit boundaries for the cluster and its Virtual Systems. **Fit: Partly.**
  - *Executive:* Cannot tell from `44-cluster-failover.jpg` whether `VS-ALPHA-01-13` or `VS-ALPHA-01-35` is `Ready`, `Not ready`, or `UNKNOWN`!
  - *Expert:* Correctly explains the Check Point VSX invariant (`VSX chassis failover is not enabled: its pre-checks run here; each Virtual System below is failed over on its own`), **but each of the 3 cards shows only 1 line of metadata (`Check Point · Mode: Virtual system load sharing · Recovery mode: Active Up · Readiness age: 31 h ago`)**:
    - No `Ready` / `Not ready` / `UNKNOWN` status pill on any of the 3 cards;
    - No indication of which member (`M1` vs `M2`) is `ACTIVE` vs `STANDBY` for `VS-ALPHA-01-13` vs `VS-ALPHA-01-35`;
    - No visible pre-check breakdown (`N pre-checks passed`), no `Stale (31 h ago)` warning badge, and no obvious expand chevron or `Run readiness check` button!
- **Remove or hide:**
  - Stop repeating `Check Point · Mode: Virtual system load sharing · Recovery mode: Active Up` identically on every child Virtual System card if it is inherited from the chassis; show only what is specific to that Virtual System (`Active on M1 ↔ Standby on M2`, `Readiness: Ready (Stale · 31 h ago)`).
- **Move behind a "details" disclosure:**
  - Add an explicit `Show pre-checks (N)` disclosure or drawer on each card so the operator can see the actual pre-check evidence.
- **Merge with another screen:**
  - Also surface a `Failover` tab (or link to this screen) on the Virtual System detail view (`35-devices-virtual-system-detail.jpg`), since the Virtual System is the actual failover unit in VSX!
- **Visual simplification:**
  - Add a right-aligned `StatusPill` (`Ready · Stale 31 h ago` or `Not ready` or `UNKNOWN`), active/standby member chips (`Active: M1 → Standby: M2`), and a `Run pre-check` action on each card.
- **Concrete fixes:**
  - Make the blue card titles (`VSX chassis · CLS-ALPHA-01`, `Virtual System · VS-ALPHA-01-13`) visually clear interactive accordions/links with a chevron (`›` or `▾`) if they expand pre-checks on click.

---

## 2. Information Architecture (`SEE → VERIFY → TRACE → RECOVER → OPERATE`)

### 2.1 Critique of the Current Left Rail & Sub-Menus

Today the left rail contains 9 entries across 5 headings:
- `Overview`
- `SEE`: `Devices`
- `VERIFY`: `Compliance`, `Policy`, `Path`
- `RECOVER`: `Backups`
- `OPERATE`: `Operations`, `Tools` (`Packets`, `Sanitizer`)
- Bottom: `Administration` (which contains 14 sub-pages!)

**What is wrong with this structure:**
1. **`TRACE` is missing from the rail despite `SEE → VERIFY → TRACE → RECOVER → OPERATE` being the product constitution:** Meanwhile, `TRACE` features are scattered across `Policy › Lookup` (`14`), `Path` (`16`), `Policy › Changes` (`13`), `Operations › Change feed` (`05`), and `Backups › Major configuration changes` (`17`).
2. **Duplicate and misplaced screens:**
   - **`Job Logs` (`34`)** in Administration is an exact duplicate of **`Operations › Jobs` (`02`)**.
   - **`Delivery plan` (`32`)** is an internal engineering backlog tracker sitting inside customer `Administration`.
   - **`Diagnostics` (`03`)** is a single-device ad-hoc CLI tool sitting inside fleet `Operations` underneath 4 fleet KPI cards.
   - **`Offline analysis` (`15`)** is an offline CSV upload tool sitting as Tab 7 of `Policy`.
   - **`Cluster members` (`40`)** duplicates `ObjectHeader` and `Identity` (`43`).
   - **`Policy › Installation` (`11`)** duplicates `Policy › Rules` (`09`) container table and `Devices › Fleet summary › Policy installed` (`07`).
3. **Administration is bloated (14 items) and hostile to non-admins:** 8 of its 14 items render bare `"Available to administrators"` text to `aiview`, and 2 others (`Inventory exclusions` `22`, `SSH trust` `24`) have only 1 sentence or 1 dropdown.

### 2.2 Target Information Architecture (What to Add, Remove, Move, Merge)

| Rail Section | Target Rail Item | Changes vs. Today & Rationale |
| :--- | :--- | :--- |
| *(Top)* | **`Overview`** | **Keep & redesign (§3):** True Executive Home answering *"Is the estate OK, what is at risk, and is our evidence fresh?"* in 5 seconds. |
| **`SEE`** | **`Devices`** | **Keep & tidy:** Fleet summary (`07`) + device/cluster/VS detail (`35`–`44`). Merge `Cluster members` (`40`) + `Identity` (`43`) into **`Members & Identity`**. Add `Failover` tab/link on VS detail (`35`). Remove `+ Add device` from `07` (belongs in Admin Registry). |
| **`VERIFY`** | **`Compliance`** | **Keep & simplify:** Combine Row 1 KPI cards and Row 2 Framework cards into one posture strip + framework selector above `Control families`. Add staleness warning banner. |
| **`VERIFY`** | **`Policy`** | **Simplify from 7 tabs to 4:** **`Rules & Containers`** (merging `Rules` `09` + `Installation` `11`), **`Objects`** (`10`, with global cross-container search), **`Hygiene`** (`12`, showing estate hygiene summary table when no container is selected), and **`Changes`** (`13`). Move `Lookup` (`14`) into `Path & Lookup` (`16`) and `Offline analysis` (`15`) into `Tools`. |
| **`TRACE`** *(New rail label)* | **`Path & Lookup`** | **Merge `Path` (`16`) + `Policy › Lookup` (`14`):** Both answer *"Can Source reach Destination on Service/Port, and which rule/hop decides?"* Mode 1: **Topology Path Analysis** (`16`); Mode 2: **Single-Policy Rule Lookup** (`14`). |
| **`RECOVER`** | **`Backups`** | **Keep & refocus on recovery:** Put the Backups table and `Missing Archives (4)` above the fold. Move the 21-row `MAJOR CONFIGURATION CHANGES` list (`17`) out of `Backups` into `Operations › Change feed`. |
| **`OPERATE`** | **`Operations`** | **Streamline to 4 operational tabs:** **`HA & Readiness`** (`01`), **`System Health`** (`04`), **`Change Feed`** (`05`, unified with config changes from `17`), and **`Jobs`** (`02`). Scope KPI cards to the active tab instead of pinning job KPIs above every tab. |
| **`OPERATE`** | **`Tools`** | **Consolidate all 4 ad-hoc/offline utilities here:** **`Diagnostics`** (moved from `03`), **`Packet Analysis`** (`19`), **`Offline SIEM Analysis`** (moved from `15`), and **`Data Sanitizer`** (`20`, showing `🔒` if role lacks permission). |
| *(Bottom)* | **`Administration`** | **Collapse from 14 sub-pages to 4:**<br>1. **`Device Registry`** (merges `Device management` `21` + `Inventory exclusions` `22` + `SSH trust` `24` + `Credentials` `23` tab for admins)<br>2. **`Access & Identity`** (merges `Local identities` `25` + `Sessions` `26` + `Roles & Permissions` `27` + `LDAP Settings` `28` as tabs; hidden or marked `🔒` for `aiview`)<br>3. **`Platform & Storage`** (merges `System` `29` + `Notifications` `30` + `Log collection settings` [renamed from `System logs` `31`])<br>4. **`Audit Log`** (`33`, hidden/locked for `aiview`). |

### 2.3 Explicit Placement of `Delivery plan`, `Job Logs`, and `Diagnostics`

- **Where `Delivery plan` (`32`) belongs:** **Remove from `Administration`.** It is an internal engineering roadmap/backlog (`NXS-LOCAL-0374`), not customer platform administration. Move it behind clicking the top-bar **`Build cd096bb · deployed 2h ago`** pill (as a developer/PO `Build & Delivery Status` modal/route) or keep it strictly in the local developer workbench (`orchestrator.py dashboard`).
- **Where `Job Logs` (`34`) belongs:** **Remove from `Administration` and merge 100% into `Operations › Jobs` (`02`).** `34` already states `Same records as Operations · Jobs.` Having two identical 42,415-row job tables in two menus is pure duplication.
- **Where `Diagnostics` (`03`) belongs:** **Move from `Operations › Diagnostics` to `Tools › Diagnostics`, with a contextual `"Run diagnostic..."` launcher inside `Devices › <device>`.** Running a single-device CLI command and reading its stdout is an interactive troubleshooting tool, not a fleet operations dashboard; moving it out of `Operations` also frees it from the 4 fleet KPI cards that currently crush its viewport.

---

## 3. An Executive Home (Wireframe in Words — At Most 6 Elements)

### Why `06-overview.jpg` Today Is Only Partly an Executive Home
Today's `Overview` (`06`) fails the 5-second executive test in three ways:
1. **It masks stale evidence as live posture:** `Estate map` says `data read 20 min ago` and colours 97 firewalls red when **131 of 132 devices have not been read in >24 hours**.
2. **It shows counts without actionability or trend:** A bank IT manager sees `97 of 118 firewalls` failing a critical check, `4 without an archive`, and `2 not ready`, but cannot see *which* 4 firewalls lack backups or *which* 2 clusters are not ready without hunting through sub-screens, nor whether risk improved or degraded since yesterday.
3. **Its visual encodings contradict the numbers:** Progress bars under `BACKUPS (126 of 130)` and `EVIDENCE (1 of 132)` graph the inverse defect count without saying so.

### Executive Home Wireframe (Top to Bottom, 6 Elements Maximum)

```
+--------------------------------------------------------------------------------------------------+
| 1. ESTATE VERDICT & FRESHNESS BANNER (Full width, 1 sentence + freshness pill)                   |
|    [ WARN: Evidence Stale ] 131 of 132 active devices were last read >24 h ago (31 h ago).       |
|    From last stored reads: 97 firewalls have critical compliance gaps · 4 backup targets have    |
|    no archive · 2 HA clusters are not ready for failover.                  [ View Job Status -> ]|
+--------------------------------------------------------------------------------------------------+
| 2. FOUR CORE ASSURANCE PILLARS (4 equal KPI cards, stacked bars matching numerator, +24h delta)  |
| +--------------------+ +--------------------+ +--------------------+ +-------------------------+ |
| | SECURITY POSTURE   | | BACKUP RECOVERY    | | HA FAILOVER READY  | | EVIDENCE COVERAGE       | |
| | 97 of 118          | | 126 of 130         | | 35 of 45 clusters  | | 1 of 132 devices        | |
| | firewalls w/ crit. | | targets archived   | | ready (Stale 31h)  | | read in last 24 h       | |
| | [========82%===  ] | | [=========97%===!] | | [=======78%==!!??] | | [!~~~~~~~99% stale~~~~] | |
| | 164 crit. checks   | | 4 missing archive  | | 2 Not ready · 3 Unk| | Last full read: 31h ago | |
| | Open Compliance -> | | View 4 missing ->  | | View 5 clusters -> | | View stale devices ->   | |
| +--------------------+ +--------------------+ +--------------------+ +-------------------------+ |
+--------------------------------------------------------------------------------------------------+
| 3. NEEDS ATTENTION NOW (Left 55%: Top actionable exceptions, 1 click to root cause)              |
|    - [RED]  2 HA Clusters Not Ready: CLS-KILO-04 (CP), CLS-SIERRA-04 (PAN)  [ Inspect HA -> ]    |
|    - [RED]  4 Backup Targets Without Archive: FW-..., FW-..., FW-..., FW-...[ Run/Check -> ]     |
|    - [WARN] Scheduled Fleet Backup overdue: last ran 47 h ago (02:00 daily) [ Job Logs -> ]      |
|    - [RED]  Top Critical Control Failing: <Control Name> (N firewalls)      [ Remediation -> ]   |
|                                                                                                  |
| 4. ESTATE MAP BY VENDOR (Right 45%: Compact wafer grid with honest stale hatching)               |
|    Check Point (60) · Palo Alto (39) · Cisco ASA (12) · Fortinet (10) · Other (11)               |
|    Legend: [Clear] [1+ Critical Failing] [Missing Backup dot] [Hatched = Stale >24h / Unassessed]|
|    (Clicking any vendor or legend state opens Devices pre-filtered to that cohort)               |
+--------------------------------------------------------------------------------------------------+
| 5. COMPLIANCE BY FRAMEWORK (Bottom-left 50%: 4 horizontal pass/fail/gap bars, 1 line each)       |
|    CIS Benchmark (30.1%) · PCI-DSS v4.0.1 (24.1%) · NIST 800-53 (30.5%) · Financial (28.1%)      |
|                                                                                                  |
| 6. 24-HOUR CHANGE & DRIFT PULSE (Bottom-right 50%: 3 single-line counters linking to TRACE)      |
|    - Cluster member config drift: 3 differences across 45 clusters          [ Compare -> ]       |
|    - Gateway configurations changed since last read: 42 of 124 devices      [ Change Feed -> ]   |
|    - Policy packages installed in last 24 h: 1 of 122 gateways              [ Policy -> ]        |
+--------------------------------------------------------------------------------------------------+
```

**Why this works for both lenses:**
- **Executive (5 seconds):** Element 1 gives the plain-English estate sentence and warns immediately if data is stale; Element 2 gives the 4 executive pillars (Security, Recovery, Continuity, Evidence Freshness); Element 3 names the exact 2 clusters and 4 firewalls at risk without scrolling or clicking.
- **Expert (1 click to root cause):** Every line in Element 3 and Element 6 deep-links straight to the filtered problem set (`CLS-KILO-04`, the 4 unarchived targets, the 3 cluster config differences).

---

## 4. Prioritised Recommendation Table (Ordered by Impact over Effort)

*All 26 recommendations below operate on existing collected data and require **no new device commands**.*

| ID | Recommendation | Screens | Lens | Impact | Effort | Risk or Trade-off |
| :--- | :--- | :--- | :--- | :---: | :---: | :--- |
| **R-01** | **Global Stale-Evidence Banner & Honest Map Aging:** Show a top-level amber banner on `Overview` and `Compliance` whenever `<80%` of active devices were read in 24 h (`131 of 132 stale`), replace `data read 20 min ago` on `Estate map` with fleet age distribution, and cross-hatch/dim stale wafer tiles. | `06-overview.jpg`, `08-compliance.jpg` | Executive | **H** | **S** | Makes development/paused-schedule environments look visibly amber, which is the honest truth required by `AGENTS.md`. |
| **R-02** | **Fix Stuck Loaders with Bounded Timeouts & Progress States:** Fix the infinite loading states on `System health` (`First computation running`), `Operations › Change feed` (`Loading changes...`), and `Policy › Changes` (`reading the period` / `Loading changes...`) with bounded queries, progress counters, and `StatePanel` timeout fallbacks. | `04-operations-system-health.jpg`, `05-operations-change-feed.jpg`, `13-policy-changes.jpg` | Both | **H** | **S** | If an underlying DB query is unindexed, bounding it requires pagination or a narrower default window (`Last 24 h` / `7 d`). |
| **R-03** | **Fix `Backups` Above-the-Fold Layout & Stale Schedule Warning:** Move the 21-row `MAJOR CONFIGURATION CHANGES · LAST 7 DAYS` list out of the middle of `Backups` (into `Change feed` or a drawer) so the Backups table and the 4 missing-archive targets appear above the fold; add a `Stale (47 h ago)` badge to `SCHEDULED FLEET BACKUP`. | `17-backups.jpg`, `05-operations-change-feed.jpg` | Both | **H** | **S** | Operators used to seeing `Admin access users` diffs on `Backups` will access them via the `36 devices · Which devices →` drawer or `Change feed`. |
| **R-04** | **Fix Dead Clicks & False-Green Readiness on Device/Cluster Headers:** Remove the non-functional top-right `M1`/`M2` buttons in `ObjectHeader`, make member cards (`FW-ALPHA-01-M1`/`M2`) and plane pills clickable links to their respective tabs/views, and mark `Readiness Ready · 31 h ago` as `warn` (`Stale`) instead of `ok` (green). | `35-devices-virtual-system-detail.jpg`, `38`–`44` | Expert | **H** | **S** | Requires defining whether clicking a member card selects the member in the left tree or filters the current tab. |
| **R-05** | **Fix `VENDOR` Column Collision & Right-Edge Clipping on `Device management`:** Move the 3 `Enrollment` filter pills above the table, remove the right-hand sidebar (`Enrollment` / `Collection scope`), and stop printing both the short code (`ASA`, `FTNT`) and full name (`Cisco ASA`, `Fortinet`) in the narrow `VENDOR` column. | `21-admin-device-management.jpg` | Expert | **H** | **S** | None; frees ~25% more horizontal width for the registry table. |
| **R-06** | **Fix Right-Edge Clipping on `Cluster › Identity` & Merge with `Cluster members`:** Merge `40-cluster-members.jpg` and `43-cluster-identity.jpg` into one `Members & Identity` tab, drop the duplicate timestamp column (`PLATFORM FACTS OBSERVED` vs `LAST INVENTORY READ`), combine `SOFTWARE VERSION` + `HOTFIX`, and show SSH/TLS trust verification status. | `40-cluster-members.jpg`, `43-cluster-identity.jpg` | Expert | **H** | **S** | Reduces cluster tabs from 7 to 6; removes redundant 2-row table. |
| **R-07** | **Default `Cluster › Configuration` to `Differences only = ON` when Differences Exist:** Automatically enable `Differences only` and collapse `0 diff` sections (`System`) when a cluster has observed configuration differences (`3 differences`), surfacing root-cause drift in 0 clicks. | `41-cluster-configuration.jpg` | Expert | **H** | **S** | Users wanting to browse all 231 identical settings must click `Differences only` off (1 click). |
| **R-08** | **Delete Duplicate `Administration › Job Logs` Screen:** Remove `34-admin-job-logs.jpg` (`Same records as Operations · Jobs`) from Administration and redirect to `02-operations-jobs.jpg`. | `34-admin-job-logs.jpg`, `02-operations-jobs.jpg` | Both | **H** | **S** | None; eliminates a 100% duplicate 42,415-row table. |
| **R-09** | **Remove `Delivery plan` from Customer Administration:** Move `32-admin-delivery-plan.jpg` out of `Administration` and attach it to the top-bar `Build cd096bb` developer/PO dialog (or workbench). | `32-admin-delivery-plan.jpg` | Executive | **H** | **S** | Keeps internal engineering sprint/backlog tracking accessible to PO/engineers without exposing it as an enterprise firewall admin page. |
| **R-10** | **Hide or Lock Admin-Only Pages & Write Buttons for `aiview`:** Collapse/hide the 8 bare `"Available to administrators"` pages (`23`, `25`–`28`, `30`, `31`, `33`) in the left nav for `aiview` (or render `StatePanel(variant="restricted")`), visually lock `+ Add device`, `Import from manager`, `Run Fleet Backup`, and `Bulk Collect`, and change the top-bar role subtitle from `Compliance Admin` to `Viewer (masked)`. | `07`, `17`, `21`–`34` | Both | **H** | **S** | Preview e2e tests that assert admin nav links under `aiview` may need selector updates. |
| **R-11** | **Scope `Administration` Header Buttons & Tab KPI Cards to Their Active View:** Show `Import from manager` and `+ Add device` only on `Device management` (`21`) (not on `22`–`34`), and show the 3 Job KPI cards only on `Operations › Jobs` (`02`) (not above `HA & readiness`, `Diagnostics`, `System health`, and `Change feed`). | `01`–`05`, `21`–`34` | Both | **H** | **S** | Reclaims ~180 px of vertical viewport height on `01`, `03`, `04`, and `05`. |
| **R-12** | **Resolve `Unknown device` & Raw UUID Errors in `Operations › Jobs`:** Map management-server and policy-container IDs to their masked display names (`FW-JULIET-01`, `FW-VICTOR-09`, `DOM-...`) in `TARGET DEVICE`, and format `PARTIAL_SNAPSHOT preflight target=<uuid>` into a human-readable summary with raw details in a drawer. | `02-operations-jobs.jpg` | Expert | **H** | **S** | Requires joining job target IDs against management servers/policy containers as well as the device registry. |
| **R-13** | **Reconcile Cluster vs. Virtual-System Readiness Counts on `Operations`:** Make the filter chips on `01-operations-ha-readiness.jpg` count the same 45 clusters as the `45 clusters enrolled` heading and KPI card (`35 ready · 2 not ready · 3 unknown · 5 not supported`), with an explicit toggle for Virtual System readiness rows (`58 records`). | `01-operations-ha-readiness.jpg` | Both | **H** | **S** | Prevents the apparent `53 Ready out of 45 clusters` arithmetic contradiction. |
| **R-14** | **Complete `Cluster › Failover` Cards & Add VS Failover Navigation:** Add `Ready` / `Not ready` / `UNKNOWN` status pills, active/standby member assignments (`M1 ↔ M2`), and an expandable pre-check list to each card on `44-cluster-failover.jpg`, plus a link/tab from `35-devices-virtual-system-detail.jpg`. | `44-cluster-failover.jpg`, `35-devices-virtual-system-detail.jpg` | Expert | **H** | **M** | None; surfaces data already computed by the readiness engine. |
| **R-15** | **Redesign `Overview` into the 6-Element Executive Home (§3):** Add the `Needs Attention Now` action list (naming the 2 not-ready clusters and 4 unarchived targets), fix inverted `BACKUPS`/`EVIDENCE` progress bars, and remove duplicate `HA READINESS` sub-captions. | `06-overview.jpg` | Executive | **H** | **M** | Replaces passive count reading with direct 1-click exception triage. |
| **R-16** | **Unify `Policy` Unselected Container Tables & Surface Estate Hygiene Summary:** Stop rendering 4 identical 59-container tables across `Rules` (`09`), `Objects` (`10`), `Installation` (`11`), and `Hygiene` (`12`). Merge `Installation` (`11`) into `Rules`, enable cross-container search on `Objects` (`10`), and show per-container hygiene finding counts on `Hygiene` (`12`). | `09`, `10`, `11`, `12` | Both | **H** | **M** | Requires backend/frontend summary query for container-level hygiene totals on `12`. |
| **R-17** | **Compact `Cluster › Logs` Header to Reclaim Log Viewer Height:** Replace the 4 tall KPI cards and the 3-line `File truncated on the device ×22 (...)` list on `42-cluster-logs.jpg` with a single 1-line status bar (moving the 22 truncated filenames into the `Collection health` sub-tab), expanding visible log lines from 3 to 15+. | `42-cluster-logs.jpg` | Expert | **H** | **S** | Truncated file details are 1 click away under `Collection health` / `Run details ›`. |
| **R-18** | **Replace the 6 Donut Charts on `Devices › Fleet summary` with Ranked Bars Across All Vendors:** Replace the 6 Check Point/Palo Alto donut charts on `07-devices-fleet-summary.jpg` with horizontal distribution bars covering all 7 enrolled vendors, and group header buttons into `Fleet actions`. | `07-devices-fleet-summary.jpg` | Both | **M** | **M** | Horizontal bars take less vertical space and never truncate version/appliance strings. |
| **R-19** | **Merge Row 1 & Row 2 Cards on `Compliance`:** Combine the 4 KPI cards and 4 Framework cards on `08-compliance.jpg` into one compact KPI strip + selectable framework filter bar (`All / CIS`, `PCI-DSS`, `NIST`, `Financial`), and move verbose scoring definitions into a popover. | `08-compliance.jpg` | Both | **M** | **S** | Removes duplicate `30.1% / 2,491 / 496` CIS numbers and brings `Control families` higher up the page. |
| **R-20** | **Merge `Path analysis` (`16`) and `Policy › Lookup` (`14`) under `TRACE`:** Combine end-to-end topology path evaluation and single-policy rule lookup into one `Path & Lookup` destination, collapsing optional ingress/zone fields behind `Advanced options`. | `14-policy-lookup.jpg`, `16-path-analysis.jpg` | Expert | **M** | **M** | Keep a deep-link button (`Lookup in this policy`) inside `Policy` that opens the unified lookup pre-scoped to that policy. |
| **R-21** | **Move `Diagnostics` (`03`) and `Offline analysis` (`15`) into `Tools`:** Group all 4 ad-hoc/offline engineering tools (`Diagnostics`, `Packet Analysis`, `Offline SIEM Analysis`, `Data Sanitizer`) under `Tools`, plus a contextual `Diagnostics` launcher on `Devices › <device>`. | `03`, `15`, `18`, `19`, `20` | Expert | **M** | **S** | Cleans up both the `Operations` and `Policy` tab bars. |
| **R-22** | **Consolidate `Administration` from 14 Sub-Pages to 4:** Merge `Inventory exclusions` (`22`) and `SSH trust` (`24`) into `Device Registry` (`21`); merge `25`–`28` into `Access & Identity`; merge `29`–`31` into `Platform & Storage` (renaming `System logs` to `Log collection settings`). | `21`–`31`, `33` | Both | **M** | **M** | Eliminates single-sentence (`22`) and single-dropdown (`24`) pages and clarifies `System logs`. |
| **R-23** | **Add Dedicated Local Tree Filter on `Devices` & Keep Global Search Constant:** Add a `Filter devices...` input directly above the left tree on `07` and `35`–`44` (matching `09-policy-rules.jpg`), keeping the top-bar omnibox placeholder consistent across all screens. | `07-devices-fleet-summary.jpg`, `35`–`44` | Expert | **M** | **S** | Prevents confusion between filtering the local tree vs. searching global IPs/routes/policies. |
| **R-24** | **Compact the `ObjectHeader` Member Strip on Cluster & VS Screens:** Replace the two large member cards (`FW-ALPHA-01-M1`, `FW-ALPHA-01-M2`) and repeated model/version strings in `ObjectHeader` with a 1-line interactive member strip (`M1: FW-ALPHA-01-M1 [ACTIVE] · M2: FW-ALPHA-01-M2 [STANDBY]`). | `35`, `38`–`44` | Expert | **M** | **S** | Saves ~55 px of vertical height across every device/cluster tab. |
| **R-25** | **Hide Unlabelled Duplicate Masked MAC Lines on `Cluster › Interfaces`:** Omit or collapse the `MAC` column on `38-cluster-interfaces.jpg` when `aiview` masks both member MACs to identical `00:1c:7f:**:**:**` strings, or prefix with `M1:` / `M2:`. | `38-cluster-interfaces.jpg` | Expert | **L** | **S** | Cleaner table with more room for `NETWORK` and member IP columns. |
| **R-26** | **Tidy `Administration › System` Storage Cards & Accordion:** Remove the Kubernetes PVC vs. host-path prose from `ARTEFACT VOLUME FREE`, add a visual capacity bar (`82% used`), and put the 14-row vendor × kind breakdown behind an expandable section. | `29-admin-system.jpg` | Both | **L** | **S** | Makes disk capacity risk immediately legible without K8s internals. |

---

## 5. Things We Are Unsure About (Questions, Not Findings)

Because static screenshots cannot show interactive or time-dependent behaviour, please clarify the following questions:

1. **Stuck loaders (`04-operations-system-health.jpg`, `05-operations-change-feed.jpg`, `13-policy-changes.jpg`):** Do these three screens eventually finish loading if left open for several minutes, or are their backend requests failing/timing out silently while the UI remains in its initial loading state?
2. **`Cluster › Failover` cards (`44-cluster-failover.jpg`):** Are the blue headings (`VSX chassis · CLS-ALPHA-01`, `Virtual System · VS-ALPHA-01-13`, `Virtual System · VS-ALPHA-01-35`) clickable accordions that expand pre-check tables and failover controls on click, or arethose two-line cards the entire rendered content of the tab?
3. **`Overview` below the fold (`06-overview.jpg`):** `06-overview.jpg` clips just below the titles `Compliance by framework` and `Most widespread critical failures`. What rows, charts, or empty states currently render inside those two bottom cards when scrolled down?
4. **`Backups` below the fold (`17-backups.jpg`):** Below the 21 rows of `MAJOR CONFIGURATION CHANGES · LAST 7 DAYS`, does the per-device Backups table render normally when scrolled, and does clicking `Which devices →` on `ACTIVE MAJOR DEVIATIONS` scroll to or filter that list?
5. **`Compliance` drill-down (`08-compliance.jpg`):** When clicking a row in `Control families` (e.g. `AC · Access Control`), does it expand inline, open a side drawer, or replace the table with the individual controls and affected firewalls—and can an engineer jump from a failing control directly to the failing firewall's `Configuration` tab?
6. **Selected container views in `Policy` (`09`, `10`, `11`, `12`):** Once a user clicks `Open` on a container (such as `DOM-PAPA-01`), how do the populated `Rules`, `Objects`, `Installation`, and `Hygiene` tables look in build `cd096bb`?
7. **`aiview` role label (`Compliance Admin` in top-right header across `01`–`44`):** Is the `aiview` account in this environment assigned a custom RBAC role named `Compliance Admin` in the database, or is `Compliance Admin` a hardcoded fallback display string in the top bar?
8. **Write/collect button behaviour for `aiview` (`+ Add device` on `07`/`21`, `Run Fleet Backup` on `17`, `Bulk Collect` on `07`):** When `aiview` clicks these active-looking blue/outlined buttons, does a modal open and then fail on submit with `403`, or does clicking them show a toast refusal immediately?
9. **Top-bar `AI` button and notification bell (`01`–`44`):** What opens when clicking the `AI` pill button or the bell icon in the top-right header, and are they intended to be active in production?
10. **Non-VSX cluster tree expansion (`07-devices-fleet-summary.jpg`):** In the left device tree on `07`, `CLS-APOLLO-01` and `CLS-APOLLO-09` show a downward `▼` chevron. Does clicking `▼` expand their physical cluster members (`M1`/`M2`) in the tree, or does it expand an empty node because they have no Virtual Systems?
