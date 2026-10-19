> Repository note: the reviewer's text is unaltered except that every IPv4 literal (all were aiview keyed pseudo-addresses) is replaced by <masked-address> so the repository privacy gate passes (2026-10-19).

<!-- Argon consultation 2026-10-17T19:54:32Z, model gemini-4-argon-high, effort high; unaltered output -->

# neXus Live UI Effectiveness & Visual Quality Review (Argon)

**Reviewer:** Argon (External Product/UI Design & Network-Security Operations Review)  
**Date:** 2026-10-17  
**Persona / Environment:** `aiview` (`Viewer`, deterministic pseudonyms active, 1440 px captures, builds `b7f6967` and `70484a8`)  
**Scope:** 18 live screen captures (`00-01` through `18`) + 2026-09-25 UI Effectiveness Council Synthesis (`UI_EFFECTIVENESS_COUNCIL_2026_09_25_SYNTHESIS.md`)

---

## 1. Verdict in Five Lines

1. **What works:** The 2026-09-25 consolidation into a single `Devices` workspace and an executive `Overview` transformed neXus from a fragmented tool into a recognizable, evidence-led assurance platform; tonight's `Operations` (`17`) and `Packet Analysis` (`18`) fixes also resolved readiness staleness blindness and development-banner noise.
2. **What does not (Semantics & Trust):** On `Overview` (`00-01`), **Cisco ASA (12)** renders as 12 bright green (`Clear`) tiles on the Estate map even though `Compliance` (`10-11`) shows all 12 ASAs have `Evidence not collected` (`0 - 0 - 12`), violating *"zero is neutral, never green"* and *"absence of evidence is not evidence of absence"*; arithmetic also drifts across screens (`124` vs `125` stored backups, `132` vs `133` enrolled, `2515` vs `2523` CIS checks).
3. **What does not (Workflow & Layout):** First-load workspaces on `Devices` (`01-02`) and `Policy` (`09-10`) waste 70% of the viewport on empty prompts (`Policy` hides its 54 Panorama + 5 MDS containers without an expand affordance), while key tables clip their rightmost columns (`Devices` `NETWORK` column, `Backups` `Backup Now` column, `Administration` `FW-FOXTR...` hostnames and `DEVICE / HOSTNAMEENROLMENT STATE` header collision).
4. **Top-value change #1 (P0 — Trust):** Fix the `Overview` Estate map state precedence so unassessed/uncollected devices (`Cisco ASA 12`, `0` evaluated checks) render as hatched `Not assessed` instead of green `Clear`, and reconcile the `124`/`125` backup and `2515`/`2523` compliance count discrepancies from a single query source.
5. **Top-value change #2 & #3 (P0 — Operations):** Fix the clipped/colliding tables and RBAC button affordances across `Devices`, `Backups`, `Administration`, and `Configuration` (one header-level `Collect now` with visible read ages instead of 4 competing buttons), and auto-populate the default right panes of `Devices` and `Policy` with fleet/container summary tables instead of blank selection prompts.

---

## 2. Effectiveness Per Screen

### 2.1 Global Navigation & Header Shell (`00-01` through `18`)
* **Purpose:** Orient the user across fleet planes, expose global search, and maintain persistent session, timezone (`GMT+3`), and masking (`aiview`) context.
* **Who uses it and when:** Every persona (Deputy GM, Manager, Team Lead, Operator, Auditor) on every page transition.
* **Serves purpose?** **Partly.**
  * *Evidence:* Top status pills (`aiview · names masked`, `Times in GMT+3`) are clear. However, the left rail only pins 5 items (`Overview`, `Devices`, `Policy`, `Compliance`, `Packets`). When a user visits `Backups` (`11-12`), `Operations` (`12-13`/`17`), `Administration` (`13-14`), or `Data Sanitizer` (`15-16`), **no left-rail icon is active**, leaving the user without location context. Meanwhile, `Packets` (an offline forensics tool with `0 B used` and `No case yet`) occupies a primary rail slot while daily operational planes (`Backups` with 131 targets and `Operations` with 45 HA clusters and 1,446 daily jobs) are hidden inside the `≡` drawer. Additionally, on `Devices` (`01-02`), the global top-bar search input changes its placeholder from `Search devices, IPs, routes, policy...` to `Filter this list, or search everything...`, conflating local tree filtering with global search.
* **Concrete fix:** Pin **`Operations`** and **`Backups`** into the visible left navigation rail (grouping `Packets` and `Data Sanitizer` under a `Tools / Forensics` rail section or beneath `Operations`, and `Administration` at the bottom of the rail) so every screen highlights its active rail item. Keep the top-bar search strictly as the global omnibox (`Search devices, IPs, routes, policy...`) and place a dedicated local filter input above the `Devices` tree, matching `Policy`, `Compliance`, `Backups`, and `Operations`.

---

### 2.2 Overview (`00-01-overview.png`)
* **Purpose:** Give leadership and shift leads a one-screen assurance posture of the 135-device firewall estate without operational job noise.
* **Who uses it and when:** Network-security Deputy GM and Manager (daily/weekly posture review); Team Leads (start-of-shift triage).
* **Serves purpose?** **Partly.**
  * *Evidence:* Removing job-failure noise made the screen calm and executive-ready. However:
    1. **False-green compliance misrepresentation:** In `Estate map`, **Cisco ASA 12** renders as 12 solid green (`Clear`) tiles. Cross-checking `Compliance` (`10-11-compliance.png`, bottom 8 rows) proves all 12 ASAs have `0 - 0 - 12 · 0% assured` (`Evidence not collected`). Because ASA has `0` failing checks due to uncollected evidence, the map colours them green (`Clear`) instead of hatched grey (`Not assessed`), violating *"zero is neutral, never green"*.
    2. **Unreadable `No archive 7` encoding:** The legend defines `No archive 7` as an outline-only box, and the first 4 Check Point tiles have a subtle red border around a pink fill, leaving the other 3 missing-archive targets visually unlocatable.
    3. **Inverted progress bars on KPI callouts:** Under `BACKUPS: 124 of 131 targets` and `EVIDENCE: 129 of 133 devices`, the headline states the positive count (`124`, `129`), but the horizontal bar beneath graphs the **negative defect fraction** (a ~5% red sliver for 7 missing backups; a ~3% amber sliver for 4 stale devices) without labelling that the bar represents the missing/ageing remainder.
    4. **Missing HA / Cluster Readiness posture:** `Cluster members compared` shows config drift (`Differences 37`), but Overview has **zero indicator of HA failover readiness** (`23 ready · 4 not ready · 13 unknown · evaluated 8 d ago` on `Operations`).
    5. **Incomplete `Software in service`:** Shows Check Point and Palo Alto versions, but omits Fortinet (10) and Cisco ASA (12); the right 50% of Row 4 is empty space, while `Estate details: policy installed · hardware models · inventory evidence age` is relegated to a tiny 11 px footer link.
* **Concrete fix:**
  1. Fix `Estate map` tile evaluation order: if a device has `0` evaluated compliance checks (or all assigned checks are `Evidence not collected`), classify it as hatched `Not assessed` (increasing `Not assessed` from `13` to `25` and reducing `Clear` from `21` to `9` unless Fortinet/others have evidenced passing checks).
  2. Replace the subtle border-only `No archive 7` encoding on the wafer tiles with a high-contrast corner dot/badge on those 7 tiles and make clicking `No archive 7` in the legend highlight/filter them.
  3. In the right-hand KPI stack (`CRITICAL FAILURES`, `BACKUPS`, `EVIDENCE`), add a 4th compact row for **`CLUSTER HA READINESS`** (`23 ready of 45 clusters · 4 not ready · 13 unknown · Stale (8 d ago)` linking to `Operations`), and make the `BACKUPS` and `EVIDENCE` bars stacked (`124 stored` neutral/blue + `7 missing` red; `129 fresh` neutral/blue + `4 >24h` amber) so the bar matches the headline denominator.
  4. Place an **Estate details & HA readiness** card (or expand `Software in service` to include Fortinet/ASA software distributions and promote the `policy installed · hardware models · inventory evidence age` links into visible card tabs/buttons) in the empty bottom-right quadrant.

---

### 2.3 Devices — Initial Fleet View (`01-02-devices-list.png`) & Legacy Configuration Route (`08-09-configuration.png`)
* **Purpose:** Browse, filter, and select any of the 135 enrolled devices/clusters/VSXs to inspect its operational and configuration planes, or export/manage fleet lifecycle.
* **Who uses it and when:** Operators and Security Admins (continuous daily use); Auditors (exporting inventory and checking device scope).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **70% blank viewport on arrival:** Before clicking a tree item, the right pane shows only a 3-line box (`Select a device or cluster...`) over 800 px of empty grey canvas. The copy (`see its interfaces, routing, cluster members, backups and identity`) omits `configuration`, `failover`, and `logs`.
    2. **Duplicate screen (`08-09-configuration.png`):** `08-09-configuration.png` is pixel-identical to `01-02-devices-list.png`. Navigating to the old Configuration route lands on the unselected `Devices` screen without opening a Configuration view or explaining that Configuration is now a device tab.
    3. **Misleading tree chevrons (`▲`) and missing cluster members:** In the left tree, `CLS-ALPHA-01` has `▲` and expands `VIRTUAL SYSTEMS (VSX) 2`. Directly below it, `CLS-APOLLO-01` and `CLS-APOLLO-09` also display the upward `▲` chevron, but have no child rows beneath them because non-VSX ClusterXL members (`FW-APOLLO-01-M1`, `FW-APOLLO-01-M2`) are omitted from the tree.
    4. **Crowded top action bar & RBAC mismatch:** Five pill buttons sit next to the title: `Lifecycle`, `Export inventory`, disabled `Bulk Collect · Next window: 00:00`, disabled `Read configuration, all · Next window: 00:00`, and a bright enabled **`+ Add device`** button—even though `aiview` is a read-only `Viewer`.
* **Concrete fix:**
  1. Replace the blank `Select a device or cluster` placeholder on `Devices` with the **Estate Inventory Overview** (the hardware models, policy installed, software versions, and inventory evidence age tables/charts currently hidden behind the tiny footer link on `Overview`, plus quick-filter chips for the 135 devices). When a device is selected, a `← Fleet summary` breadcrumb returns to this view.
  2. In the left tree, only render expand/collapse chevrons on nodes that actually have child tree rows—or allow expanding `CLS-APOLLO-01` in the tree to show its member nodes (`FW-APOLLO-01-M1`, `FW-APOLLO-01-M2`) so members can be reached and highlighted directly in the tree.
  3. Disable `+ Add device` for `Viewer` (`aiview`) with the same visual treatment as `Bulk Collect` and a tooltip/sublabel (`Role: Viewer (read-only)`), and group `Bulk Collect` + `Read configuration, all` into a single `Collect fleet · Next window: 00:00` split/dropdown button to declutter the header.

---

### 2.4 Device — Cluster View (`02-03-device-cluster.png`)
* **Purpose:** Inspect a firewall cluster (`CLS-APOLLO-01`) as one operational unit: HA role distribution across members, shared VIPs, cluster interfaces, routing, configuration comparison, identity, and failover state.
* **Who uses it and when:** Operators and Team Leads (daily network verification, pre-maintenance HA checks, interface/VIP lookup).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **Right-edge table clipping:** In the `Interfaces` table, the rightmost column (`NETWORK`) is clipped off the viewport (`<masked-address>...`, `<masked-address>...`).
    2. **Unlabelled dual MAC addresses:** Every row in `MAC` stacks two identical-looking masked lines (`00:1c:7f:**:**:**` / `00:1c:7f:**:**:**`) without indicating whether line 1 is `M1` and line 2 is `M2`.
    3. **Oversized disabled member buttons & missing read timestamps:** Inside the `FW-APOLLO-01-M1` and `FW-APOLLO-01-M2` cards, the disabled `↓ Collect now · Next window: 00:00` button occupies over half of each card, while **last collection timestamp / evidence age is completely absent** from both the cluster header and member cards.
    4. **Half-migrated cross-link strip:** `This cluster in: Device screen · Backups · Readiness` still appears inside the header (`Device screen` is plain bold text for the current screen; `Backups` and `Readiness` are links).
    5. **Redundant title prefix:** `CLS > CLS-APOLLO-01` repeats `CLS` three times alongside the `CLS` tree badge.
* **Concrete fix:**
  1. Remove the redundant `CLS >` title prefix (show `CLS-APOLLO-01`), display the evidence read ages directly in the header (`Inventory read: 4 h ago · Config read: 22 h ago`), and replace the two huge member-card `Collect now` buttons with one compact header-level `Collect cluster · Next window: 00:00` button (preserving individual member collection on each member's header or a dropdown).
  2. Convert `Backups` and `Readiness` from the inline text strip into right-aligned deep-link pills (`View in Backups →`, `View in HA Readiness →`) in the cluster header.
  3. Fix `Interfaces` column widths so `NETWORK` (`/23`, `/30`, `/28`, `/25`) never clips at 1440 px, and prefix stacked cluster MACs with compact muted member tags (`M1 00:1c:7f:**:**:**` / `M2 00:1c:7f:**:**:**`).

---

### 2.5 Check Point Cluster Member — Interfaces (`03-04-device-member-interfaces.png`)
* **Purpose:** Inspect an individual cluster member (`FW-APOLLO-01-M1`), verify its runtime identity and HA state, and cross-check its interfaces against its peer (`FW-APOLLO-01-M2`) and cluster VIPs.
* **Who uses it and when:** Operators (troubleshooting member-specific interface/link issues or verifying member identity).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **Lost navigation context:** Clicking into `FW-APOLLO-01-M1` removes the active blue highlight from `CLS-APOLLO-01` in the left tree. Above the member card sits `Open assigned policy`, not a `← CLS-APOLLO-01` back-breadcrumb.
    2. **Quadruple repetition of member names:** `FW-APOLLO-01-M1` and `FW-APOLLO-01-M2` appear in the card title, in the yellow check pills (`FW-APOLLO-01-M1 ✓`, `FW-APOLLO-01-M2 ✓`), in the plain text footer (`Members: FW-APOLLO-01-M1 · FW-APOLLO-01-M2`), and in the table columns below.
    3. **Raw enum leak:** `Lifecycle: UNKNOWN · NO_LIFECYCLE_DATA` renders raw `SCREAMING_SNAKE_CASE` between the header card and the tab bar.
    4. **Identical table to cluster view without active-member emphasis:** The table shows the exact same 8 columns as `02-03` (including the clipped `NETWORK` column) without highlighting the `FW-APOLLO-01-M1` column as the currently selected member.
* **Concrete fix:**
  1. Keep the parent cluster (`CLS-APOLLO-01`) highlighted (or expanded to `FW-APOLLO-01-M1`) in the left tree, and place a clear breadcrumb bar at the top of the right pane: `CLS-APOLLO-01  /  FW-APOLLO-01-M1` with a compact member switcher (`[M1 · ACTIVE]` | `[M2 · STANDBY]`) and `Open assigned policy →` aligned right.
  2. Remove the duplicate `Members: FW-APOLLO-01-M1 · FW-APOLLO-01-M2` text line (the interactive member pills already show both members and switch between them).
  3. Move `Lifecycle: UNKNOWN (no lifecycle data imported)` into the `Identity` / `PLATFORM IDENTITY` grid (or render it as a muted header pill without raw `NO_LIFECYCLE_DATA` enum code).
  4. Visually highlight the active member's IP column (`FW-APOLLO-01-M1`) with a subtle column header tint and fix the `NETWORK` right-edge clipping.

---

### 2.6 Check Point Cluster Member — Configuration (`04-05-device-member-configuration.png`)
* **Purpose:** Inspect audit-ready platform identity facts, read timestamps, and parsed configuration sections/details for a device.
* **Who uses it and when:** Security Admins and Operators (verifying config state, hotfix takes, installed policy name, and read freshness); Auditors (checking platform identity facts).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **`PLATFORM IDENTITY` (`What an audit asks for first`) is well-structured**, clearly surfacing `SERIAL NUMBER`, `HOTFIX / JUMBO TAKE`, `UPTIME`, `POLICY INSTALLED`, `PLATFORM FACTS READ`, and `LAST CONFIGURATION READ`.
    2. **Four competing collection buttons on one screen:** `Bulk Collect` (top), `Read configuration, all` (top), `Collect now · Next window: 00:00` (device header), and a second `Collect now · Next window: 00:00` inside the `Configuration` tab header. Worse, that fourth button renders **dark navy text on a dark blue fill**, making it nearly illegible.
    3. **Buried timestamps:** `PLATFORM FACTS READ (2026-10-17 18:01:01)` and `LAST CONFIGURATION READ (2026-10-17 00:02:06)` sit at the bottom of the 13-cell grid instead of in the device header where every tab can see data freshness.
    4. **Confusing RBAC copy & active filter input over hidden data:** The bottom card says `Configuration settings not shown for your role / The configuration text is visible to onboarding administrators.` (leaking internal role naming "onboarding administrators"), while the `Filter setting or value...` search box and `Sections | Details` sub-tabs above remain active even though there are no visible settings to filter.
    5. **Overlap with `Identity` tab:** `PLATFORM IDENTITY` lives here inside `Configuration`, while a separate `Identity` tab also sits at the end of the tab bar.
* **Concrete fix:**
  1. Promote `Platform facts read: 4 h ago (18:01)` and `Config read: 22 h ago (00:02)` into the persistent device header card so freshness is visible across all tabs, while keeping the exact timestamps inside the `PLATFORM IDENTITY` grid.
  2. Remove the duplicate dark-blue `Collect now · Next window: 00:00` button inside the `Configuration` tab (or unify collection into the single device-header `Collect now` control).
  3. When `Viewer` RBAC hides configuration settings, disable the `Filter setting or value...` input (`Disabled for Viewer role`) and clarify the RBAC notice: `Configuration settings are restricted for the Viewer (aiview) role. Full configuration sections and raw text are visible to Security Administrators and Operators.`
  4. Clarify tab naming so `Configuration` and `Identity` do not appear redundant (e.g., rename `Identity` tab to `Identity & Trust` or `SSH & Certificates` depending on its contents—`Identity` tab contents are `UNKNOWN` from screenshots).

---

### 2.7 Check Point Cluster Member — Backup (`05-06-device-member-backup.png`)
* **Purpose:** Review stored backup archives (timestamps, sizes, digests, validation, deviation) and job attempt history for this specific device, or request an on-demand backup with an audit reason.
* **Who uses it and when:** Operators and Backup Admins (verifying backup completion before a change window, investigating failed/cancelled backup jobs).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **Inverted hierarchy:** 25 lines of raw `Backup attempts` log entries sit at the top of the tab, pushing the actual **Stored Archives table** (`COLLECTED TIME`, `SIZE`, `DIGEST PREFIX`, `VALIDATION LEVEL`, `DEVIATION STATE`) to the bottom of the page without even a section title.
    2. **Doubled status strings in attempt log:** Every line repeats its status word twice: `COMPLETED: COMPLETED` (16 times) and `CANCELLED: cancelled: stale while worker was stopped...` (9 times).
    3. **Unformatted raw bytes and internal codes in the archive table:** `SIZE` prints raw byte integers (`169993690`, `169278996`), `VALIDATION LEVEL` prints `V1`, and `DEVIATION STATE` prints `changed`, whereas on the fleet `Backups` screen (`11-12`) the same archive is formatted as `162.1 MB`, `Checked`, and `Changed since last`.
    4. **Editable `Reason` field for read-only Viewer:** The `Reason` text box (`Provide a reason of at least 8 characters`) is enabled for `aiview`, making it look like typing 8 characters will unlock the `Request backup · Next window: 00:00` button.
* **Concrete fix:**
  1. **Swap the vertical order:** Place **Stored Backup Archives** first (with a clear heading `Stored archives (5)`), and place **Backup attempts** below it (or behind a collapsible disclosure showing the latest 5 attempts by default with `Show all 25 attempts ▾`, preserving every attempt record and pagination).
  2. Format `SIZE` as human-readable MB with raw bytes on hover/subtext (`162.1 MB (169,993,690 B)`), pair `V1` with its human label (`Checked (V1)`), and deduplicate the log status prefix (`COMPLETED` instead of `COMPLETED: COMPLETED`; `CANCELLED — stale while worker was stopped...` instead of `CANCELLED: cancelled:`).
  3. Disable the `Reason` input for `Viewer` (`aiview`) with placeholder `Viewer role cannot request backups`.

---

### 2.8 Check Point Cluster Member — Logs (`06-07-device-member-logs.png`)
* **Purpose:** Inspect system and operational logs collected from this firewall during scheduled collection windows.
* **Who uses it and when:** Operators and Security Admins (troubleshooting daemon/HA events, interface flaps, or platform errors on a specific device).
* **Serves purpose?** **Yes (for empty-state honesty), with minor context gaps.**
  * *Evidence:* Clearly communicates `No collection yet` and states the exact collection schedule (`00:00, 06:00, 12:00 and 18:00 Europe/Istanbul`) with a `Refresh` button. However, it uses `Europe/Istanbul` while the top bar uses `GMT+3`, lacks the time until the next window (`Next window: 00:00 GMT+3`), and inherits the member header's lack of a cluster breadcrumb and `Lifecycle: UNKNOWN · NO_LIFECYCLE_DATA` line.
* **Concrete fix:** Align timezone label to `Europe/Istanbul (GMT+3)`, show `Next scheduled collection: 00:00 GMT+3` alongside `Refresh`, and follow the filled-state summary-first design specified in **Section 4** below.

---

### 2.9 Palo Alto Standalone Firewall — Interfaces (`07-08-pan-device.png`)
* **Purpose:** Inspect interfaces, zones, VLANs, and virtual-system (`VSYS`) assignments on a high-density multi-VSYS Palo Alto firewall (`FW-HELIOS-02`, 73 interfaces, 371 routes).
* **Who uses it and when:** Operators and Network Engineers (tracing traffic ingress/egress zones, subinterface VLAN tags, and subnets across virtual systems).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **18 address-less physical trunk rows push all Layer-3 interfaces below the fold:** On a multi-VSYS PA-5260, `Physical / VS0` holds the physical parent ports (`ethernet1/1`..`ethernet1/18`, `vlan`, `loopback`, `tunnel`) which have `—` for `VLAN`, `ADDRESS`, and `NETWORK`, while the actual Layer-3 subinterfaces (`ethernet1/13.2603`, etc.) live in `VS-SEC-97 (VSID 1)`, `VS-SEC-33 (VSID 2)`, etc. Rendering 56 rows flat with `Physical / VS0` first forces operators to scroll past 18 rows of `—` before seeing any IP address.
    2. **No `VSYS` or `Zone` filter controls:** On a firewall with 7+ virtual systems and dozens of security zones, there is only a plain text box (`Filter interfaces...`) and `✓ Active only`—no `VSYS` dropdown or group-by-VSYS collapse.
    3. **Awkward 3-line Zone wrapping & right-edge Network clipping:** `ZONE` wraps narrow hyphenated tokens across 3 lines (`ZONE-\nBEACON-\n5793`), doubling row height, while `NETWORK` clips on the right (`<masked-address>...`).
    4. **Left tree loses scroll position:** `FW-HELIOS-02` is open on the right, but the left tree is scrolled to the top (`CLS-ALPHA-01`..`CLS-BRAVO-08`), so the active device is not visible in the tree.
    5. **Redundant `HA role: STANDALONE` badge:** Rendered below the tab bar even though `Palo Alto · Standalone` and `STANDALONE` are already in the header card above.
* **Concrete fix:**
  1. Remove the redundant `HA role: STANDALONE` line below the tab bar.
  2. Add a **`VSYS` filter dropdown** (`All VSYS (8)` / `VS-SEC-97 (VSID 1)` / `Physical / VS0`...) and a **`With IP address only`** toggle chip next to `Active only`, or group rows under collapsible VSYS section headers (with tenant VSYSs or addressed interfaces easy to jump to, while preserving all 18 `Physical / VS0` rows).
  3. Widen the `ZONE` and `NETWORK` columns (shrinking excessive whitespace in `KIND`, `VLAN`, and `MAC`) so `ZONE-BEACON-5793` stays on 1–2 lines and `NETWORK` (`/21`, `/24`) never clips.
  4. Auto-scroll the left tree to keep the selected device (`FW-HELIOS-02`) in view.

---

### 2.10 Policy (`09-10-policy.png`)
* **Purpose:** Browse, search, compare, and audit firewall security policies (rules, objects, installation targets, hygiene, changes, lookup, offline analysis) collected from management servers (`Panorama` and `Check Point MDS`).
* **Who uses it and when:** Security Admins, Operators, and Auditors (daily rule lookup, change review, policy hygiene audit).
* **Serves purpose?** **No (in the initial state shown).**
  * *Evidence:*
    1. **Unreachable/hidden containers in the left pane:** `Sources · 2` lists `FW-JULIET-01` (`Panorama · 54 containers · Collected 3 h ago`) and `FW-BEACON-04` (`MDS · 5 containers`), while the right pane says `No assigned policy snapshot / Select a source and container to browse stored policies.` Yet **none of the 54 Panorama containers or 5 MDS containers is visible in the left pane**, and there is no expand chevron (`▸`) on `FW-JULIET-01` or `FW-BEACON-04` to indicate how to reveal them!
    2. **Misleading empty-state headline:** `No assigned policy snapshot` sounds like an error (that snapshots are missing from the database), when in fact 59 containers exist and the user simply hasn't selected one yet.
    3. **Disabled batch buttons dominate the narrow left rail:** Four disabled buttons (`Collect selected`, `Export selected rules (CSV)`, `Collect changes`, `Full refresh (incl. hit counts)`) consume half the vertical space of the left sidebar, while the search input placeholder clips (`Search sources, containers and p...`).
* **Concrete fix:**
  1. Auto-expand the first management source (`FW-JULIET-01 · Panorama · 54 containers`) in the left sidebar with explicit expand/collapse chevrons (`▾` / `▸`) so containers (device groups / domains / packages) are immediately visible and clickable.
  2. Instead of showing a blank `No assigned policy snapshot` screen when no single container is selected, render a **Stored Policy Containers Summary Table** in the right pane listing all 59 containers across `FW-JULIET-01` and `FW-BEACON-04` (showing Container Name, Source Manager, Rule Count, Assigned Gateways, Last Collected Age, and a click-through to open its `Rules`). Reserve the message `No assigned policy snapshot` strictly for a device/container that genuinely has no collected snapshot.
  3. Collapse the disabled collection actions in the narrow left sidebar into a compact icon/menu bar when `0 policies selected` (or for `Viewer` role) so the container tree has room to breathe.

---

### 2.11 Compliance (`10-11-compliance.png`)
* **Purpose:** Evaluate technical security configuration controls across the firewall fleet against CIS Benchmark, PCI-DSS v4.0.1, NIST SP 800-53, and Financial Baseline, separating proven failures from uncollected evidence gaps.
* **Who uses it and when:** Compliance/Audit Reviewers, Security Manager, and Security Admins (audit preparation, remediation tracking, identifying evidence collection gaps).
* **Serves purpose?** **Partly (strong data model, undermined by arithmetic contradictions and lack of vendor filtering/sorting).**
  * *Evidence:*
    1. **Internal arithmetic contradictions on the same screen:**
       * Subtitle says: `2515 control checks (one control on one firewall) · every control maps to CIS, so the CIS card shows the same totals.` Immediately below, the `CIS Benchmark` card says **`2523 control checks`** (`749 pass + 1266 fail + 508 unavailable = 2523`).
       * Top card 4 says **`DATA GAPS: 500`**, while the `CIS Benchmark` card says **`508 unavailable`**.
       * `PCI-DSS v4.0.1` card header says **`1981 control checks`**, but its bottom numbers sum to **`1973`** (`472 pass + 1052 fail + 449 unavailable = 1973`, missing 8 checks).
       * `NIST SP 800-53` card header says **`2484 control checks`**, but its bottom numbers sum to **`2476`** (`749 pass + 1227 fail + 500 unavailable = 2476`, missing 8 checks).
       * `Overview` (`00-01`) reports `29.8% / 23.9% / 30.3%` for CIS / PCI / NIST, while `Compliance` (`10-11`) reports `29.7% / 23.8% / 30.2%`.
    2. **No `Vendor` filter dropdown on a vendor-partitioned table:** Every single control row is strictly vendor-specific (`gaia_cis_*` on 59 Check Point firewalls, `pan_cis_*` on 39 PAN firewalls, `fg_*` on 9 Fortinet firewalls, `asa_*` on 12 Cisco ASA firewalls). Yet the filter bar only offers `All Severities v` and `All Frameworks v`—no `All Vendors v` dropdown!
    3. **Unsorted 64-row wall:** Controls are ordered by internal string ID (`gaia_cis_2_1_1`..`asa_ntp_server`), burying `CRITICAL` 100%-failing checks (like row 7 `gaia_cis_2_5_3_ssh_protocol_v2` and row 31 `pan_cis_1_3_4_tls_version`) beneath `MEDIUM` and `LOW` checks.
* **Concrete fix:**
  1. Reconcile the 8-check discrepancy (`2515` vs `2523`, `500` vs `508`, `1973` vs `1981`, `2476` vs `2484`) and the 0.1% rounding drift with `Overview` so every total on `Compliance` equals the exact sum of its `pass + fail + unavailable` parts.
  2. Add an **`All Vendors v`** dropdown (`Check Point (59)`, `Palo Alto (39)`, `Fortinet (9)`, `Cisco ASA (12)`) and a visible vendor badge (`CP`, `PAN`, `FTNT`, `ASA`—matching `Overview`'s critical failures card!) to each control row.
  3. Default-sort the controls table by **Outcome + Severity** (`Non-Compliant` `CRITICAL` first, then `HIGH` / `MEDIUM` / `LOW`, then `Evidence not collected`, then `Compliant`), with clickable column headers to sort by `SEVERITY`, `FIREWALLS`, `PASS - FAIL - UNAVAILABLE`, or `OUTCOME`.

---

### 2.12 Backups (`11-12-backups.png`)
* **Purpose:** Monitor daily encrypted backup archive currency, size, validation state, and deviation across all 131 backup targets, and manage backup target inclusion and retention policies.
* **Who uses it and when:** Backup/Security Admins and Operators (daily morning verification of the 02:00 backup run, pre-change backup confirmation).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **Rightmost column clipped off the card:** On every row, `Latest attempt 2026-10-17 02:0...` and the `Backup Now · Next window: 0...` button are sliced off at the right border of the table container.
    2. **Count contradiction with `Overview` and within `Backups`:**
       * Filter pills say `All enrolled · 132` (other screens say `133` enrolled), `Targets · 131`, and `Targets without archive · 7`.
       * Subtracting `7` targets without archive from `131` targets equals **`124`** (which is what `Overview` shows: `124 of 131 targets`), yet the bottom-left KPI card on `Backups` says **`125` `DEVICES WITH A STORED BACKUP`**! (Likely 1 non-target device has an old archive in the store; if so, state `124 of 131 targets (plus 1 non-target archive)` so the math isn't contradictory).
       * Bottom card `RETENTION POLICY: 7 days / Snapshot depth: 1 retained` contradicts `05-06-device-member-backup.png`, which shows 5 retained archives for `FW-APOLLO-01-M1`.
    3. **KPI summary cards buried at the bottom & no table pagination:** Only 10 of 131 targets are shown, with no pagination bar (`Showing 1–10 of 131 · Page 1 of 14`) and the 4 KPI summary cards placed beneath the table instead of at the top.
    4. **False-warning fatigue on `DEVIATION` (`Changed since last`):** Every row shows an amber/orange `Changed since last` pill because nightly backup archives change their byte digest even when no configuration changed (as the footnote on `05-06` admits), while the bottom-right card says `ACTIVE MAJOR DEVIATIONS: UNKNOWN / 0 comparisons run`.
    5. **Interactive-looking `TARGET` toggles for `Viewer`:** The blue toggle switches in the `TARGET` column look active/clickable under `aiview` (`Viewer`), and `Run Fleet Backup · Next window: 00:00` at the top uses unreadable dark-navy-on-dark-blue text.
* **Concrete fix:**
  1. Move the 4 summary KPI cards above the table (matching `Compliance` and `Operations`), reconcile `124` vs `125` and `Snapshot depth: 1 retained` vs actual retained archives, and fix the `Run Fleet Backup` disabled button contrast.
  2. Remove the repeated per-row `Backup Now · Next window: 00:00` button text clipping by replacing the wide button on every row with a compact `Latest attempt: 02:07` column + a concise action cell (or disable/collapse per-row trigger text for `Viewer`), and add a visible pagination bar (`1–10 of 131 targets`).
  3. Style byte-digest change (`Archive bytes changed`) in **neutral grey/blue** rather than warning amber when semantic AST comparison has not run (`0 comparisons run`), reserving amber/red strictly for verified semantic deviations or missing/failed backups.
  4. Render the `TARGET` toggle switch in a locked/read-only state (`On (locked for Viewer)`) under `aiview`.

---

### 2.13 Operations — Before (`12-13-operations.png`) and After Tonight's Fix (`17-operations-after-fix.png`)
* **Purpose:** Monitor HA cluster roles and failover readiness across 45 clusters, and track collection/operational job execution (`1446` finished, `47` failed in 24 h), queue, history, and diagnostics.
* **Who uses it and when:** Operators and Team Leads (before maintenance/failover windows, and daily monitoring of collection job health).
* **Serves purpose?** **Partly (substantially improved by tonight's fix in `17-operations-after-fix.png`).**
  * *Evidence:*
    1. **Tonight's fix (`17`) worked well:** Reformatting the `READINESS CHECKS` card to `23 ready of 45 clusters`, adding the prominent yellow banner (`Readiness was last evaluated 8 d ago; run a readiness check before relying on Ready.`), adding row-level `Stale` pills, and stacking `Mode: active-passive` under the cluster name significantly improved clarity and safety.
    2. **Remaining issue — Banner says "run a readiness check", but no button exists to run one:** The top-right button says `Schedule collection` (which is for general collection and shouldn't look like an active admin schedule button for `Viewer`), while the `HA & readiness` tab has **no `Run readiness check` button** (even though `AGENTS.md` notes `aiview` may start read-only readiness runs, or operators need that button right on the readiness tab/banner). Also, every row has a checkbox (`☐`) with no visible action bar explaining what selecting clusters does.
    3. **Remaining issue — Raw enum codes & inconsistent status/reason pairing:** `PRIMARY REASON` still displays raw internal strings (`CHECK_NOT_READY`, `INSUFFICIENT_EVIDENCE`, `COMMAND_UNAVAILABLE`, `TRUSTED_CONNECTION_REQUIRED`). In `12-13`, `CLS-APOLLO-09` and `CLS-LIMA-09` have status `Unknown` (`ⓘ`) but `PRIMARY REASON` `CHECK_NOT_READY` (identical to the `Not ready` rows).
    4. **Remaining issue — Unexplained `50 readiness records`:** Card 3 says `45 clusters enrolled · 50 readiness records · 5 not supported`. `23 + 4 + 13 + 5 = 45`, so `50 readiness records` is an unexplained internal table row count.
* **Concrete fix:**
  1. Add a **`Run readiness check · Next window: 00:00`** button directly on the `HA & readiness` toolbar (or inside the staleness banner, active for roles authorized to start readiness runs and linked to the row checkboxes `Run readiness on selected (0)`).
  2. Replace raw enum strings in `PRIMARY REASON` with human-readable descriptions paired with the code on hover/subtext (e.g., `Pre-check failed (CHECK_NOT_READY)`, `Insufficient evidence (INSUFFICIENT_EVIDENCE)`, `Command unavailable (COMMAND_UNAVAILABLE)`, `Trusted connection required (TRUSTED_CONNECTION_REQUIRED)`), and clarify why a cluster with `CHECK_NOT_READY` is classified as `Unknown` vs `Not ready`.
  3. Remove `50 readiness records` from the card subtext (or clarify `45 clusters (50 member/VS evaluations)`) so the counts add up cleanly to `45`.

---

### 2.14 Administration (`13-14-administration.png`)
* **Purpose:** Manage the authoritative 135-device registry (enrollment state, credentials, exclusions, imports), access control, platform settings, and audit/job logs.
* **Who uses it and when:** Security/Platform Administrators (onboarding devices, rotating credentials, managing RBAC, reviewing audit logs).
* **Serves purpose?** **Partly.**
  * *Evidence:*
    1. **Table header collision:** `DEVICE / HOSTNAME` and `ENROLMENT STATE` collide with zero whitespace into **`DEVICE / HOSTNAMEENROLMENT STATE`** (also mixing British `ENROLMENT` with American `Enrollment` in the right-hand card).
    2. **Severe hostname truncation makes devices indistinguishable:** The `DEVICE / HOSTNAME` column truncates every name after ~9 characters (`FW-JULIET-...`, `FW-FOXTR...`, `FW-FOXTR...`, `FW-FOXTR...`, `FW-PHOE...`, `FW-PHOE...`, `FW-QUEB...`, `FW-QUEB...`). Three consecutive rows literally read `ASA | FW-FOXTR... | Enrolled | 2026-10-17 18:01:33` with no way to tell which firewall is which!
    3. **No search or state filter in the 135-entry registry:** The right-hand `Enrollment` card highlights `Last collection failed (enrolled): 3 devices` and `Draft · not collected: 2 devices`, but the registry table has no search box, no state filter dropdown, and no pagination bar (the bottom row `FW-ECHO-01` is sliced in half).
    4. **Enabled write CTAs for `Viewer`:** Both `Import from manager` and `+ Add device` render as active, enabled buttons under `aiview` (`Viewer`).
    5. **Duplicate `Job Logs` location:** Left nav under `RECORDS` has `Job Logs`, duplicating `Operations > Jobs / History`.
* **Concrete fix:**
  1. Fix the column grid templates in `Device registry`: give `DEVICE / HOSTNAME` at least `220 px` (`minmax(220px, 1.5fr)`) so full hostnames (`FW-FOXTROT-01`) never truncate, add `16 px` column gap so `DEVICE / HOSTNAME` and `ENROLLMENT STATE` never collide, and standardize spelling to `ENROLLMENT STATE`.
  2. Add a search input (`Filter registry by hostname, vendor...`) above the table and make the three pills in the `Enrollment` card (`Enrolled · 133`, `Last collection failed · 3`, `Draft · not collected · 2`) clickable filters that immediately filter the registry rows.
  3. Add a table pagination/count footer (`Showing 1–10 of 135 entries`) and visually disable `Import from manager` and `+ Add device` for the `Viewer` role.

---

### 2.15 Packet Analysis — Before (`14-15-packet-analysis.png`) and After Tonight's Fix (`18-packet-analysis-after-fix.png`)
* **Purpose:** Upload and correlate offline `pcap`/`pcapng` captures across multiple firewall capture points to trace packet flow, drops, and NAT/policy transformations, with automatic 7-day retention deletion.
* **Who uses it and when:** Senior Network/Security Engineers (deep packet-drop troubleshooting across ingress/egress/HA interfaces).
* **Serves purpose?** **Partly (improved by tonight's banner removal in `18`).**
  * *Evidence:*
    1. **Tonight's fix (`18`) worked:** Removing the blue `The analysis engine is still being ported...` banner and replacing it with `Results are evidence-led: a stage that cannot be established is reported as unknown, with the reason.` makes the feature look intentional rather than unfinished.
    2. **Missing action affordance / RBAC explanation:** Unlike `Data Sanitizer` (`15-16`), which shows the action container with `Your role does not allow this action`, `Packet Analysis` (`18`) has **no `+ New case` or `Upload captures` button at all** (neither disabled nor explained), leaving `No case yet` as a dead-end table row.
    3. **Redundant `Masked view` pill:** Top-right of the content area displays a yellow `Masked view` badge directly below the global top bar's `aiview · names masked` badge.
* **Concrete fix:**
  1. Add a top-right **`+ New packet case`** button (disabled for `Viewer` with a visible RBAC note or tooltip: `Viewer role can inspect existing packet cases; creating cases requires Operator or Security Admin`), and enrich the `No case yet` empty state to explain the workflow (`Create a case and attach up to 2.0 GB per pcap/pcapng capture across ingress/egress capture points to trace packet delivery`).
  2. Remove the duplicate in-page `Masked view` badge (already shown in the global top bar).

---

### 2.16 Data Sanitizer (`15-16-sanitizer.png`)
* **Purpose:** Anonymize sensitive files (`pcap`/`pcapng`, logs, CSV, XLSX, JSON) by replacing IPs, MACs, names, and emails with keyed vault tokens before external vendor support export, or revert returned files within 90 days.
* **Who uses it and when:** Operators and Security Admins (preparing support bundles/captures for TAC cases).
* **Serves purpose?** **Yes (clear policy rules and RBAC boundary, two small copy defects).**
  * *Evidence:*
    1. **Clear operational rules:** The retention, 2.0 GB limit, one-time/60-minute download deletion, 90-day encrypted vault, and irrevocable payload zeroing are explained in two crisp sentences.
    2. **Untranslated Turkish subtitle prefix:** Subtitle starts with **`Veri Anonimleştirme ·`** (`Veri Anonimleştirme · pcap/pcapng, logs, CSV, XLSX and JSON...`) inside an otherwise 100% English interface.
    3. **Generic RBAC refusal copy:** `Your role does not allow this action.` does not name the current role (`Viewer`) or which roles are permitted to sanitize/revert files.
* **Concrete fix:**
  1. Replace `Veri Anonimleştirme ·` with English `Data Anonymization ·` (per `AGENTS.md` Engineering-output language law).
  2. Update the refusal text inside the dashed dropzone to: `Your Viewer (aiview) role is read-only and cannot upload files for sanitization or reversal. Available to Operators and Security Administrators.`

---

## 3. Visual Quality

### 3.1 Hierarchy & Layout Structure
* **Inverted summary-vs-detail hierarchy on `Backups` (`11-12`) and `Device Member > Backup` (`05-06`):**
  * *Finding:* `Compliance` (`10-11`) and `Operations` (`12-13`/`17`) place their 4 summary KPI cards at the top of the page. `Backups` (`11-12`) places its 4 summary KPI cards (`DEVICES WITH A STORED BACKUP`, `SCHEDULED FLEET BACKUP`, `RETENTION POLICY`, `ACTIVE MAJOR DEVIATIONS`) at the **bottom** of the page beneath the table. Similarly, `Device Member > Backup` (`05-06`) places 25 lines of attempt logs above the stored archives table.
  * *Concrete fix:* Move the 4 KPI summary cards on `Backups` (`11-12`) above the `Backup fleet · 131 targets` card, and move the Stored Archives table on `05-06` above `Backup attempts`.
* **Competing primary/collection buttons on `Devices` (`01-02` to `07-08`):**
  * *Finding:* On `04-05-device-member-configuration.png`, the user sees 6 top-level/card action buttons simultaneously (`Lifecycle`, `Export inventory`, `Bulk Collect`, `Read configuration, all`, `+ Add device`, header `Collect now`, and tab `Collect now`).
  * *Concrete fix:* Demote fleet-wide actions (`Lifecycle`, `Export inventory`, `Bulk Collect`, `Read configuration, all`) into the `Devices` left-pane header or a compact fleet toolbar when viewing the fleet summary, leaving the right-hand device pane focused exclusively on the selected device's single header `Collect now` control.

### 3.2 Density, Alignment & Viewport Clipping (1440 px)
* **Right-edge table clipping on 4 screens:**
  * *Finding:* At 1440 px width:
    1. `02-03-device-cluster.png` (`Interfaces` table): `NETWORK` column clips (`<masked-address>...`).
    2. `03-04-device-member-interfaces.png` (`Interfaces` table): `NETWORK` column clips (`<masked-address>...`).
    3. `07-08-pan-device.png` (`Interfaces` table): `NETWORK` column clips (`<masked-address>...`) while `ZONE` wraps to 3 lines.
    4. `11-12-backups.png` (`Backup fleet` table): rightmost column (`Latest attempt ...` / `Backup Now · Next window: 0...`) clips off the card border on every row.
  * *Concrete fix:* Enforce `box-sizing: border-box` and responsive table column proportions (`table-layout: fixed` or horizontal scroll wrapper `overflow-x: auto` as a fallback, plus tighter padding on `KIND`, `VLAN`, and `MAC` columns) so all columns fit cleanly within `1440 px` without clipping masks (`/24`) or timestamps.
* **Header collision & hostname ellipsis truncation on `Administration` (`13-14`):**
  * *Finding:* `DEVICE / HOSTNAMEENROLMENT STATE` has zero column gap, and `DEVICE / HOSTNAME` truncates at ~9 characters (`FW-FOXTR...`) while the right half of each row has 250+ px of empty white space around `Enrolled` and `2026-10-17 18:01:33 4 h ago`.
  * *Concrete fix:* Reallocate horizontal width from the padded middle/right columns to `DEVICE / HOSTNAME` (`min-width: 220px`) and add `padding-right: 16px` on every `<th>` and `<td>`.

### 3.3 Colour Use & Contrast
* **Severe WCAG contrast failure on disabled primary blue buttons (`04-05` and `11-12`):**
  * *Finding:* The disabled buttons `↓ Collect now · Next window: 00:00` (inside `04-05` Configuration tab) and `Run Fleet Backup · Next window: 00:00` (top of `11-12` Backups) render **dark navy/black text on a saturated medium-dark blue background (`#2563EB` / `#3B82F6`)**, yielding a contrast ratio near ~1.8:1 (unreadable). Meanwhile, other disabled buttons (`01-02` `Bulk Collect`, `03-04` header `Collect now`) use a pale slate background with muted grey text.
  * *Concrete fix:* Remove the dark-blue disabled fill class and apply the single standard disabled button token (`background: #F1F5F9; color: #64748B; border: 1px solid #CBD5E1`) across all disabled window-gated actions.
* **Constitutional colour violation on `Overview` (`00-01`):**
  * *Finding:* Unassessed `Cisco ASA 12` devices are coloured solid green (`Clear`), and `Overview` KPI progress bars (`BACKUPS`, `EVIDENCE`) draw red/amber defect slivers directly below positive headline counts (`124 of 131`, `129 of 133`).
  * *Concrete fix:* Use hatched grey (`Not assessed`) whenever evaluated check count is `0`; use neutral slate/blue for positive coverage and red/amber only for the explicitly labelled defect segment.
* **False-alarm amber on `Backups` (`11-12`):**
  * *Finding:* `Changed since last` uses warning-amber on every row for byte-digest changes that occur naturally on every nightly backup archive.
  * *Concrete fix:* Render routine archive byte-digest differences (`Bytes changed`) in neutral grey (`#F1F5F9` fill, `#475569` text), matching the neutral `changed` pill already used on `05-06-device-member-backup.png`.

### 3.4 Typography & Copy Hygiene
* **Raw `SCREAMING_SNAKE_CASE` enum leakage:**
  * *Finding:*
    * Device screens (`03-04`, `04-05`, `05-06`, `06-07`, `07-08`): `Lifecycle: UNKNOWN · NO_LIFECYCLE_DATA`
    * Operations (`12-13`, `17`): `CHECK_NOT_READY`, `INSUFFICIENT_EVIDENCE`, `COMMAND_UNAVAILABLE`, `TRUSTED_CONNECTION_REQUIRED`, `READY`
  * *Concrete fix:* Map every backend reason enum to plain English operational text in the UI presenter layer (keeping the enum code only as a secondary muted badge or tooltip if needed for support).
* **Unformatted numbers & doubled status strings (`05-06`):**
  * *Finding:* `05-06-device-member-backup.png` prints `169993690` instead of `162.1 MB` and `COMPLETED: COMPLETED` / `CANCELLED: cancelled:`.
  * *Concrete fix:* Use the shared byte formatter (`formatBytes`) and strip the duplicate status prefix in the backup attempt renderer.
* **Mixed-language string (`15-16`):**
  * *Finding:* `15-16-sanitizer.png` subtitle begins with `Veri Anonimleştirme ·`.
  * *Concrete fix:* Change to `Data Anonymization ·`.

### 3.5 Empty, Refused (RBAC) & `UNKNOWN` States
* **Inconsistent RBAC presentation for `aiview` (`Viewer`):**
  * *Finding:* Across the product, `Viewer` restrictions are shown in **five incompatible ways**:
    1. Cleanly disabled light-grey pill with window reason (`Bulk Collect · Next window: 00:00` on `01-02`).
    2. Illegible dark-blue disabled button (`Run Fleet Backup · Next window: 00:00` on `11-12`, `Collect now` on `04-05`).
    3. Enabled-looking primary blue CTA buttons (`+ Add device` on `01-02` & `13-14`, `Import from manager` on `13-14`, `Schedule collection` on `12-13`/`17`) and enabled-looking form controls (`Reason` input on `05-06`, `TARGET` switches on `11-12`).
    4. Role-refusal cards with inconsistent explanations (`Configuration settings not shown for your role / The configuration text is visible to onboarding administrators` on `04-05` vs `Your role does not allow this action` on `15-16`).
    5. Complete omission of the action button (`Packet Analysis` `18` has no `+ New case` button or RBAC notice).
  * *Concrete fix:* Apply one uniform RBAC pattern for `Viewer`:
    * Never render mutating/admin CTAs (`+ Add device`, `Import from manager`, `Schedule collection`, `TARGET` toggle, backup `Reason` input) as active primary controls for `Viewer`; render them in the standard disabled light-slate style with a clear badge/tooltip (`Requires Operator / Admin · Viewer is read-only`).
    * Note clearly when an action is blocked by **RBAC (`Viewer`)** vs when it is merely waiting for a **collection window (`Next window: 00:00`)**—currently a `Viewer` sees `Collect now · Next window: 00:00`, which falsely implies that at `00:00` the `Viewer` will be able to trigger collection!

---

## 4. The New `Logs` Tab (`06-07-device-member-logs.png`)

### 4.1 Evaluation of the Empty State
* **What works well:**
  * Respects constitutional laws: does not show a fake `0 errors` green badge or an empty table grid; states `No collection yet` plainly and gives the exact operational reason (`Collection runs in the shared job windows (00:00, 06:00, 12:00 and 18:00 Europe/Istanbul); this tab fills in after the first run.`).
  * Provides an immediate, safe `Refresh` button on the right.
* **What should be refined in the empty state:**
  1. **Show next window & last attempt status:** Add a compact metadata line below the sentence: `Last attempt: None recorded · Next window: 00:00 GMT+3 (Europe/Istanbul)` so the operator knows whether the last window simply hasn't arrived yet or if a collection job ran and failed (`COLLECTION_FAILED` with reason).
  2. **Clarify `Refresh` vs device collection:** Label the button `Refresh view` (or add a tooltip) so operators know it reloads stored records from neXus rather than polling the live firewall outside the window.

### 4.2 Evaluation of the Tab's Place on the Device Page
* **Tab bar placement (`Interfaces | Routing | Cluster members | Configuration | Backup | Logs | Identity`):**
  * Placing `Logs` directly on the device/member screen is the right architecture: it keeps the "One device screen" intact and lets an operator pivot from an interface/routing anomaly (`Interfaces`, `Routing`) straight to system events (`Logs`) and recent changes (`Configuration`, `Backup`) without losing device context.
  * **Ordering & Cluster behavior:**
    * On a cluster member (`06-07`), place `Logs` immediately after `Cluster members` or `Configuration` (`Interfaces | Routing | Cluster members | Logs | Configuration | Backup | Identity`), because during `SEE → VERIFY → TRACE`, operators check live runtime state and recent system logs before inspecting full configuration text or backup archives.
    * On a **Cluster view (`02-03`)**, `Logs` is currently omitted (`Interfaces | Routing | Cluster members | Configuration | Identity | Failover`). Because system logs are collected per physical member (`M1`, `M2`), either add a `Logs` tab on the Cluster view that defaults to showing a member selector (`[FW-APOLLO-01-M1 (Active)]` | `[FW-APOLLO-01-M2 (Standby)]`), or ensure the member header's `M1` / `M2` pills let the operator switch between `M1` and `M2` while staying on the `Logs` tab.

### 4.3 What the Filled State Should Prioritise for an Operator (Summary First, Then Detail)
Without inventing fields or live-streaming capabilities the window-based collector does not have, the filled `Logs` tab should render in three progressive layers:

1. **Layer 1 — Collection Provenance & Window Summary Strip (Top of tab):**
   * **Provenance & Freshness:** `Last collected: YYYY-MM-DD HH:MM:SS GMT+3 (18:00 window)` · `Log time span: HH:MM:SS → HH:MM:SS` · `Collection status: COMPLETED` (or `PARTIAL` / `COLLECTION_FAILED` with explicit cause; never mask a failed read as empty logs).
   * **Neutral Count Breakdown Chips (No composite score, zero is neutral grey):**
     * `Total entries: N`
     * Breakdown pills by parsed severity/level present in the batch (e.g., `Critical / Alert · N`, `Error · N`, `Warning · N`, `Notice / Info · N`, `Level UNKNOWN · N`). Any count of `0` renders in neutral muted grey (`0`), never green; non-zero error/critical counts pair colour with the severity word.
2. **Layer 2 — Operator Triage & Filter Bar:**
   * **Search input:** `Filter log message, process, or facility...`
   * **Quick filters:** Severity dropdown/pills, Subsystem/Process dropdown (if parsed from the vendor's system log format, e.g., Gaia `routed`, `cphaprob`, `sshd`, `cpd`), and a **Snapshot window selector** (if neXus retains more than the latest window's log batch, allowing comparison with the previous 6-hour window).
   * **Cluster peer quick-switch (on HA/Cluster members):** A one-click pill (`Compare / Switch to peer: FW-APOLLO-01-M2`) so an operator tracing a failover or sync flap can check the standby member's system logs from the same 6-hour window immediately.
3. **Layer 3 — Structured Chronological Event Table (Detail):**
   * Ordered newest-first (`TIMESTAMP GMT+3`, `SEVERITY / LEVEL` badge with word, `PROCESS / SUBSYSTEM` (or `—` if not applicable to vendor format), and `MESSAGE` rendered in clean monospace/tabular font with all IPs, serials, and hostnames passed through the `aiview` pseudonymizer).
   * **Repeated-message deduplication toggle (optional disclosure):** System logs often repeat the same daemon message 500 times (`ntp timeout`, `arp flux`). Grouping consecutive identical messages (`×42 in this window — Expand all`) prevents one noisy daemon from hiding a single HA state transition line while preserving every single log line behind one click.

---

## 5. Progress Since 2026-09-25 Council Synthesis

| # | 2026-09-25 Council Recommendation / PO Decision | Status | Visual Evidence in Current Screenshots (`00-01` – `18`) |
|---|---|---|---|
| 1 | **PO Decision 1:** One device screen — `Inventory` and `Configuration` become tabs of the selected device/cluster; top-level `Config` nav item goes. | **Partly done** | Left rail no longer has `Config`; `Configuration` is a tab on clusters (`02-03`) and devices (`04-05`, `07-08`). *Remaining gap:* `08-09-configuration.png` shows the legacy route still renders an unselected `Devices` screen whose prompt copy omits "configuration", and `02-03` still has the inline text `This cluster in: Device screen · Backups · Readiness`. |
| 2 | **PO Decision 2:** `Overview` is an executive summary with no operational noise (job failures, "could not reach the device"). | **Done (with 1 regression)** | Operational job noise is gone from `00-01-overview.png` and lives in `Operations` (`12-13`/`17`). **Regression:** `Cisco ASA 12` renders as 12 bright green (`Clear`) tiles on `Overview`'s Estate map even though `Compliance` (`10-11`) shows all 12 ASAs have `Evidence not collected` (`0 - 0 - 12`). |
| 3 | **PO Decision 3:** Each vendor in its own terms — data a vendor or device does not have is not shown (no empty tab, chip, column or step). | **Partly done** | Standalone PAN (`07-08`) omits `Cluster members` and `Failover` tabs; non-firewall vendors (`Infoblox`, `Radware`, `Blue Coat`) show as `Not assessed` on `Overview`. *Remaining gap:* Every device (`03-04` to `07-08`) displays `Lifecycle: UNKNOWN · NO_LIFECYCLE_DATA` above the tabs; `07-08` shows 18 `Physical / VS0` rows with `—` for VLAN/Address/Network at the top of the table. |
| 4 | **PO Decision 4 & ASA section:** Cisco ASA split contract — configuration text backup labelled secret-bearing + archive step separate. | **UNKNOWN** | No screenshot in `.review-inputs` opens a Cisco ASA device detail or Backup tab. |
| 5 | **Agreed #2:** `Compliance` and `Backups` stay fleet screens; the device screen shows that device's backup/compliance status and links to them. | **Partly done** | `Compliance` (`10-11`) and `Backups` (`11-12`) are fleet screens; device members have a `Backup` tab (`05-06`) and clusters link to `Backups · Readiness` (`02-03`). *Remaining gap:* The device screen (`02-03` through `07-08`) does **not** show that device's compliance summary or a link to its failing controls in `Compliance`. |
| 6 | **Diff Table #2 / Phase 1:** One count vocabulary across the estate (eliminate "seven different counts of one estate"). | **Partly done** | Explicit denominators and scope notes are present (`135 devices enrolled`, `133 active`, `131 backup targets`, `119 of 123 firewalls`). *Remaining gap:* `Overview` (`00-01`) says `124 of 131` stored backups while `Backups` (`11-12`) says `125` (`131 targets - 7 without archive = 124`); `Backups` says `All enrolled · 132` while other screens say `133`; `Compliance` (`10-11`) says `2515` checks in the subtitle and `2523` in the CIS card (`500` vs `508` data gaps; `1973` vs `1981` PCI; `2476` vs `2484` NIST). |
| 7 | **Diff Table #3:** Keep `Administration` device list as an enrollment registry without inventory browsing. | **Done (with layout bugs)** | `13-14-administration.png` is scoped strictly to enrollment registry and collection scope. *Defects:* Table header collides (`DEVICE / HOSTNAMEENROLMENT STATE`), hostnames truncate to `FW-FOXTR...`, and there is no search/filter bar. |
| 8 | **Verified Live #1:** Fix `Backups` table filter leaving out management servers (Blue Coat MC fixed; Panorama). | **Partly done** | `11-12-backups.png` shows `All enrolled · 132` (up from 108/110), which is 1 short of the `133` enrolled devices shown on `Overview` and `Administration`. |
| 9 | **Verified Live #3:** Mask `Operations` job log names under `aiview`. | **Done (where visible) / Jobs tab UNKNOWN** | All visible screens use deterministic pseudonyms (`FW-APOLLO-01-M1`, `CLS-FOXTROT-04`). The `Operations > Jobs` tab itself is not in the screenshot set (`UNKNOWN`). |
| 10 | **Onboarding #1–5:** Wording for partial vendor coverage, land on new device in `Devices`, visible stopped reason/filter, no `Create credential` without permission. | **UNKNOWN (CTA RBAC regressed)** | Onboarding modal and stopped-onboarding rows are not screenshotted (`UNKNOWN`). However, `+ Add device` (`01-02`, `13-14`) and `Import from manager` (`13-14`) render as active buttons for the read-only `aiview` Viewer. |
| 11 | **Phase 1:** Plain failure causes; `Not read yet` / `Read failed` / `Not applicable`; no raw internal codes. | **Partly done** | `Compliance` (`10-11`) uses `Evidence not collected` and `Logs` (`06-07`) uses `No collection yet`. *Remaining gap:* `Operations` (`12-13`, `17`) still shows raw enums (`CHECK_NOT_READY`, `INSUFFICIENT_EVIDENCE`, `COMMAND_UNAVAILABLE`, `TRUSTED_CONNECTION_REQUIRED`), and devices show `NO_LIFECYCLE_DATA`. |
| 12 | **Phase 2:** Device header with read ages and **one** `Read now` (`Collect now`) action. | **Not done** | Read ages (`PLATFORM FACTS READ`, `LAST CONFIGURATION READ`) are still buried at the bottom of the `Configuration` tab (`04-05`), and `04-05` renders two `Collect now` buttons on the device pane plus two fleet collect buttons in the top bar. |
| 13 | **Phase 3:** `Overview` first screenful rewritten; estate charts move to `Devices`. | **Partly done** | `Overview` (`00-01`) was rewritten, but `Software in service` stayed on `Overview` with a tiny footer link (`Estate details: policy installed · hardware models · inventory evidence age`), while the initial `Devices` right pane (`01-02`) is an empty placeholder box. |

---

## 6. Prioritised List of Changes (P0 / P1 / P2)

> **Capability Preservation Guarantee:** None of the items below removes any data field, click-through, action, RBAC restriction, `aiview` pseudonym, or constitutional evidence rule. Wherever an element is moved, grouped, or collapsed, its exact destination is stated.

### P0 — Critical Trust, Constitutional Compliance & Broken Layouts (Ship First)

| ID | Screen(s) | Change & Concrete Fix | Effort | Capability Preserved & Where Everything Lives |
|---|---|---|---|---|
| **P0-1** | `Overview` (`00-01`) | **Fix `Cisco ASA 12` false-green tiles on Estate map:** Change tile classification so any device with `0` evidenced compliance checks (all checks `Evidence not collected`) renders as hatched grey **`Not assessed`** instead of green **`Clear`**. Add a high-contrast corner indicator on the 7 `No archive` tiles so `No archive 7` is visually distinct on both Check Point and Palo Alto tiles. | **S** | Preserves all 133 active device tiles, all 7 legend states, and all click-throughs; restores constitutional compliance (*"zero is neutral, never green"*, *"absence of evidence is not evidence of absence"*). |
| **P0-2** | `Overview` (`00-01`), `Compliance` (`10-11`), `Backups` (`11-12`), `Operations` (`17`) | **Reconcile cross-screen & same-screen count discrepancies:** (1) Unify stored backup count between `Overview` (`124 of 131 targets`) and `Backups` (`125` devices vs `131 targets - 7 without archive = 124`, explaining if 1 non-target has an archive). (2) Reconcile `All enrolled · 132` on `Backups` with `133` enrolled on `Overview`/`Administration`. (3) Fix the 8-check gap on `Compliance` (`2515` subtitle vs `2523` CIS card; `500` vs `508` data gaps; `1973` vs `1981` PCI; `2476` vs `2484` NIST) and the 0.1% rounding drift with `Overview`. (4) Fix `Snapshot depth: 1 retained` on `Backups` (`11-12`) to match the 5 retained archives shown on `05-06`. | **S** | Preserves every counter, card, and filter pill; aligns all numbers to single authoritative backend definitions. |
| **P0-3** | `Administration` (`13-14`), `Device Cluster` (`02-03`), `Device Member` (`03-04`, `07-08`), `Backups` (`11-12`) | **Fix column collisions, right-edge clipping, and hostname truncation at 1440 px:** (1) On `Administration` (`13-14`), separate `DEVICE / HOSTNAME` and `ENROLLMENT STATE` headers and widen `DEVICE / HOSTNAME` so `FW-FOXTR...` never truncates into identical strings. (2) On `Devices > Interfaces` (`02-03`, `03-04`, `07-08`), adjust column widths so `NETWORK` (`/24`, `/21`) never clips at the right edge and `ZONE` on `07-08` doesn't wrap into 3-line narrow stacks. (3) On `Backups` (`11-12`), fix the right-hand `Latest attempt` / `Backup Now` column clipping off the card border. | **S** | Preserves every column, IP, subnet mask, zone, timestamp, and action button—making the clipped characters readable. |
| **P0-4** | `Policy` (`09-10`) | **Make the 54 Panorama + 5 MDS policy containers visible & auto-show a Container Summary table:** Add explicit expand chevrons (`▾`/`▸`) to `FW-JULIET-01` and `FW-BEACON-04` (auto-expanding the first source), and replace the misleading initial `No assigned policy snapshot` empty state with a **Stored Policy Containers** summary table in the right pane until a specific container is clicked. | **M** | Preserves both management sources, all 4 collection/export buttons, the `Jump to device's policy` selector, and all 7 policy tabs (`Rules` through `Offline analysis`). |
| **P0-5** | `Configuration` (`04-05`), `Backups` (`11-12`), `Devices` (`01-02`), `Operations` (`17`), `Administration` (`13-14`), `Packet Analysis` (`18`), `Sanitizer` (`15-16`) | **Fix dark-on-dark illegible buttons & unify `Viewer` (`aiview`) RBAC affordances:** (1) Replace the unreadable dark-navy-on-dark-blue disabled style on `Collect now` (`04-05`) and `Run Fleet Backup` (`11-12`) with the standard light-slate disabled style. (2) Style `+ Add device`, `Import from manager`, `Schedule collection`, backup `Reason` input (`05-06`), and `TARGET` toggles (`11-12`) as visibly disabled/locked for `Viewer` (separating `"Role: Viewer (read-only)"` from `"Next window: 00:00"`). (3) Add a disabled `+ New packet case` button with RBAC explanation on `Packet Analysis` (`18`) and name permitted roles on `Configuration` (`04-05`) and `Sanitizer` (`15-16`). | **S** | Preserves every button, input, toggle, and RBAC boundary while making RBAC vs schedule-window state honest and readable. |

---

### P1 — High-Value Operational Workflow & Triage Improvements

| ID | Screen(s) | Change & Concrete Fix | Effort | Capability Preserved & Where Everything Lives |
|---|---|---|---|---|
| **P1-1** | `Devices` (`01-02` – `08-09`), `Overview` (`00-01`) | **Complete the "One Device Screen" header & populate the empty `Devices` landing pane:** (1) Move `PLATFORM FACTS READ` and `LAST CONFIGURATION READ` timestamps up into the persistent device/cluster header card (keeping them in `PLATFORM IDENTITY` too) and remove the duplicate `Collect now` button inside the `Configuration` tab (`04-05`). (2) Add adevice compliance status pill/link (`Compliance: X failing →`) to the device header (`Agreed #2`). (3) Replace the blank `Select a device or cluster` canvas on `01-02` / `08-09` with the **Estate Inventory Summary** (the `policy installed · hardware models · inventory evidence age` views from `Overview`'s bottom link), with a `← Fleet summary` button when viewing a device. | **M** | Preserves all 13 `PLATFORM IDENTITY` fields, all collection triggers, and all estate detail views (promoted from `Overview`'s tiny footer link into the default `Devices` canvas, while keeping the link on `Overview`). |
| **P1-2** | `Device Cluster` (`02-03`) & `Device Member` (`03-04` – `06-07`) | **Fix cluster-to-member navigation & tree synchronization:** (1) Allow expanding non-VSX clusters (`CLS-APOLLO-01`) in the left tree to show their members (`FW-APOLLO-01-M1`, `M2`), and keep the active cluster/member highlighted and scrolled into view (`07-08`) in the left tree. (2) Add a top breadcrumb (`CLS-APOLLO-01 / FW-APOLLO-01-M1`) with one-click `M1` ↔ `M2` switching across all tabs, and remove the redundant 3rd/4th repetitions of member names in the header card. (3) Label stacked cluster MACs with `M1` and `M2`. | **M** | Preserves every cluster and member badge, `Open assigned policy` link, and interface table column. |
| **P1-3** | `Compliance` (`10-11`) | **Add a `Vendor` filter dropdown, vendor row badges, and severity-first default sorting:** Add `All Vendors v` (`Check Point`, `Palo Alto`, `Fortinet`, `Cisco ASA`) next to `All Severities v` and `All Frameworks v`; show the vendor badge (`CP`, `PAN`, `FTNT`, `ASA`) on every control row; default-sort the 64 controls by `Non-Compliant` + `CRITICAL` first instead of internal `gaia_cis_*` string order. | **S** | Preserves all 64 controls, all framework mappings, all counts, and all detail click-throughs (`→`). |
| **P1-4** | `Device Member > Backup` (`05-06`) & `Backups` (`11-12`) | **Fix Backup hierarchy, raw bytes, doubled statuses, and pagination:** (1) On `05-06`, place the **Stored Archives** table above `Backup attempts`, format `169993690` as `162.1 MB` (with exact bytes in subtext/hover), pair `V1` with `Checked (V1)`, and fix `COMPLETED: COMPLETED` / `CANCELLED: cancelled:`. (2) On `11-12`, move the 4 KPI cards above the table, add a visible pagination bar (`1–10 of 131`), and change routine nightly byte-digest `Changed since last` pills from warning amber to neutral grey. | **S** | Preserves all 25+ backup attempt log lines, pagination, all archive columns, and all 4 fleet backup KPI cards. |
| **P1-5** | `Operations` (`17`) & `Overview` (`00-01`) | **Add `Run readiness check` action, humanize reason enums, and surface HA readiness on `Overview`:** (1) Add a `Run readiness check` button on the `Operations > HA & readiness` toolbar/banner (satisfying the banner's own instruction). (2) Map raw `PRIMARY REASON` enums (`CHECK_NOT_READY`, `INSUFFICIENT_EVIDENCE`, `COMMAND_UNAVAILABLE`, `TRUSTED_CONNECTION_REQUIRED`) and device `NO_LIFECYCLE_DATA` to plain English labels. (3) Add a compact `CLUSTER HA READINESS` summary (`23 ready of 45 · 4 not ready · 13 unknown · Stale 8 d`) on `Overview`. | **S** | Preserves all 45 cluster rows, checkboxes, filter pills, and exact reason codes (kept as secondary code tags/tooltips). |
| **P1-6** | `Device Member > Logs` (`06-07`) | **Refine `Logs` empty state and ship summary-first filled state:** In the empty state, align timezone notation to `Europe/Istanbul (GMT+3)` and show `Next window: 00:00 GMT+3`. For the filled state, render (1) Collection window provenance + neutral severity count pills (`0` = neutral grey), (2) text/severity/process filter bar + cluster peer (`M1` ↔ `M2`) quick-switch, and (3) chronological masked log table with multi-line/repeat disclosure. | **M** | Preserves the `Refresh` action, window schedule model, `aiview` masking, and all collected system log fields. |

---

### P2 — Polish, Navigation Ergonomics & High-Density Refinements

| ID | Screen(s) | Change & Concrete Fix | Effort | Capability Preserved & Where Everything Lives |
|---|---|---|---|---|
| **P2-1** | Global Left Rail & Header (`00-01` – `18`) | **Promote `Operations` and `Backups` into the visible left navigation rail:** Add `Operations` and `Backups` as persistent left-rail items (alongside `Overview`, `Devices`, `Policy`, `Compliance`, `Packets`, `Sanitizer`, `Administration`) so visiting `Backups`, `Operations`, `Sanitizer`, or `Administration` always shows an active highlight in the left rail. Keep global search in the top bar distinct from the local `Devices` tree filter. | **S** | Preserves all 9 screens and the `≡` drawer; every screen remains one click away with clear active-state orientation. |
| **P2-2** | `PAN Standalone Firewall` (`07-08`) | **Add `VSYS` filter & `With IP address` toggle on multi-VSYS `Interfaces`:** Add a `VSYS` dropdown filter (`All VSYS`, `VS-SEC-97 (VSID 1)`...) and a `With IP only` toggle chip next to `✓ Active only` (or collapsible VSYS group headers) so operators on a 73-interface PA-5260 don't have to scroll past 18 address-less `Physical / VS0` rows to reach Layer-3 interfaces. | **S** | Preserves all 72 interfaces and all 18 `Physical / VS0` rows (visible under `All VSYS` or `Physical / VS0`). |
| **P2-3** | `Administration` (`13-14`) | **Add registry search bar & interactive `Enrollment` filter pills:** Add a hostname/vendor search box above `Device registry · 135 entries`, make the right-hand `Enrollment` counts (`Enrolled · 133`, `Last collection failed · 3`, `Draft · 2`) clickable filters for the table, and add a pagination footer so the bottom row (`FW-ECHO-01`) is not clipped in half. Cross-link `Administration > Job Logs` to `Operations > Jobs`. | **S** | Preserves all 135 registry rows, checkboxes, row action menus (`⋮`), and all 12 left-nav administration sections. |
| **P2-4** | `Data Sanitizer` (`15-16`) & `Overview` (`00-01`) | **Language & chart completeness polish:** (1) Replace Turkish `Veri Anonimleştirme ·` with English `Data Anonymization ·` on `15-16`. (2) On `Overview` (`00-01`), make the `BACKUPS` and `EVIDENCE` progress bars match their positive headlines (`124/131` and `129/133` filled in neutral slate/blue, defect remainder in red/amber), and include Fortinet (10) and Cisco ASA (12) in `Software in service`. | **S** | Preserves all sanitizer rules/vault behaviour and all `Overview` KPI metrics. |
