# Navigation benchmark and neXus preservation evidence

## Status

**RESEARCH APPENDIX — evidence record, not a contract.** Directly linked from
`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (DRAFT). Nothing here
authorizes an implementation.

- **Revision 2 — 2026-09-05. This revision withdraws a serious
  evidence-classification error.** See §0.2. Read that before using any row.
- **Access date for every external source below: 2026-09-05.**
- **Movement:** `ARCHITECTURE` (research input).

---

## 0. Evidence discipline

### 0.1 What this environment can and cannot reach

The remote sandbox's egress gateway enforces an organization allowlist. Every
vendor documentation host was attempted and denied at CONNECT with **HTTP 403
(policy denial)**, re-verified twice; `docs.anthropic.com` was then used as a
control and returned the **same** block, which proves this environment has **no
direct web-fetch capability for any host**. The only channel to the open web is
the search tool, which returns excerpts of the pages linked below.

**Consequence: no third-party product UI was directly observed in this session,
and no competitor screenshot was available.** Where a fact could not be
established from a retrievable or officially-sourced statement, this document
says **NOT ESTABLISHED**. It does not reconstruct a competitor UI from memory.

### 0.2 Evidence correction (revision 2) — withdrawn observations

Revision 1 of this appendix contained a **§9 "Directly observed screenshots
(Product Owner supplied)"** section that attributed a set of supplied
screenshots to Tufin, AlgoSec, FireMon, BackBox and OPNsense, and introduced a
`PO-SCREENSHOT` grade presented as "the strongest grade in this appendix".

**That classification is withdrawn in full.** The Product Owner has stated that
the screenshots supplied during the review were of the current/previous
neXus / SecurityExpert UI and its mock-data rendering, provided to identify
**neXus behaviours that must be preserved** — not screenshots of any third-party
product.

Everything derived *solely* from those images is therefore removed, not
softened:

| Withdrawn claim | Was cited as |
| --- | --- |
| Tufin's two-part icon rail + persistent device-tree pane; the `Vendors`/`Groups` tab pair; a four-level Panorama branch; the stacked risk/change/cleanup workspace | PO-SCREENSHOT |
| AlgoSec's `DEVICES` pane title, collapse chevron, wrench header control, brand filter, `Issues (n)` counter, VDOM-under-firewall nesting, the `OVERVIEW/POLICY/CHANGES/REPORTS/ALL REPORTS/MAP` tab strip, the workspace action toolbar, `Latest Report` freshness header | PO-SCREENSHOT |
| FireMon's horizontal top menu bar and its menu names, the KPI card row, `Devices Recently Revised`, the per-row `•••` overflow menu, the `Not enough historical data` chip, the `Topology ⚠` badge | PO-SCREENSHOT |
| BackBox's left rail with `OPERATIONS` / `ADMINISTRATIVE` uppercase group headings; `Schedules` as its own root; `Automations`/`Jobs`/`File Repository`/`Queue`/`History`/`Access` as Operations children; draggable dashboard widgets; the `Identical`/`Changed`/`N/A` and `Successful`/`Suspect`/`Failed` state vocabularies | PO-SCREENSHOT |
| OPNsense as a control sample in its entirety (it is not in the benchmark set and contributed only screenshot-derived observations) | PO-SCREENSHOT |

Three conclusions that revision 1 built on those observations are withdrawn with
them, because **an attractive conclusion whose evidence is invalid is not kept**:

1. *"Uppercase domain group headings over a left rail is a shipped, mature
   pattern, so D-NAV3 is externally corroborated."* — **withdrawn.** No
   retrievable source establishes any competitor's group-heading treatment.
   `D-NAV3` now rests on neXus' own product domains and on the Product Owner's
   accepted direction, which is sufficient and is stated as such.
2. *"Mature products ship explicit third states (`Not enough historical data`,
   `N/A`, `Suspect`), which is the external basis for the capability-state
   matrix."* — **withdrawn.** That matrix rests entirely on **this
   repository's own existing canonical states** and on `CON.0` §9's honest-
   affordance law. It never needed external support and no longer claims any.
3. *"BackBox separates Schedules (root) from Jobs/Automations/Queue/History,
   so the mature end-state is several execution surfaces."* — **withdrawn as an
   observation.** The *shape* survives only as a Product Owner decision
   (`PO-NAV-8`), not as benchmark evidence.

An honest correction makes this appendix **less detailed than revision 1**.
That is the correct outcome.

### 0.3 Grades used in revision 2

| Grade | Meaning |
| --- | --- |
| **PO-SOURCED-OFFICIAL** | a fact the Product Owner supplied *from the named official vendor page*, which this environment cannot retrieve independently. Recorded with the page named so a future session on an unrestricted network can verify it. |
| **DOC-EXCERPT** | quoted or closely paraphrased by the search tool from the linked official documentation page. |
| **URL-STRUCTURE** | read off the official documentation URL / table-of-contents path itself (e.g. `TocPath=Administration\|Device\|Devices\|Choose+a+Device+to+Onboard`). Structural, reliable, narrow. |
| **VENDOR-MARKETING** | from a vendor product/marketing/blog page. Positioning only. **Never** proof of navigation layout or backend semantics. |
| **REPO-VERIFIED** | a **neXus** behaviour verified directly in this repository's source, with the file and symbol cited. The strongest grade in this document. |
| **INFERENCE** | this document's reading of the above. Always labelled. |
| **NOT ESTABLISHED** | asked, and the available evidence does not answer it. |

No screenshot of any kind is committed to this repository, and no lab hostname,
IP address, user name or email address appearing in any supplied image is
reproduced here (`AGENTS.md` "Privacy and DLP").

---

## 1. neXus behaviours to preserve — REPO-VERIFIED

This is the section the supplied screenshots actually serve. Every row is
verified in this repository's own source, which is a **stronger and more
durable** record than an image: it can be re-checked at any commit.

| Behaviour | Verified at | Preservation criterion |
| --- | --- | --- |
| **Cluster interface comparison matrix** — `Interface \| Cluster VIP \| member… \| Network` | `static/inventory_ui.js::renderClusterInterfaceMatrix` (with `matrixAddressHtml`); headers `matrix-vip-header`, `matrix-member-header`, `matrix-network-cell` | The matrix renders for a ClusterXL/VSX entity, with the VIP column present when cluster VIPs exist |
| **Logical / member / diff route comparison** | `static/inventory_ui.js::renderRouteMemberTabs` — view ids `logical`, one per member, and `diff` ("Diff only"); consumed by `renderRouteTable` | All three comparison modes remain selectable on a divergent multi-member entity |
| **Member-specific difference emphasis** | `memberScope` / `sharedAcrossMembers` (`inventory_ui.js`); `.scope-chip.shared`, `.scope-chip.diff`, `.difference-row` (`static/style.css`) | Member-scoped rows stay visually distinguishable, and every state keeps a text label (`PO-NAV-6`) |
| **Interface / route divergence badges** | `.divergence-badge` rendered from `entry.interfaceDivergence` / `entry.routeDivergence` | Badge present when divergence exists |
| **VSX nesting — virtual systems under their physical parent** | hierarchy assembly in `static/inventory_ui.js` (`attachChildren`, `children`), `virtual_system` rows from the VSX parser | A VSID renders as a child of its VSX host/cluster, never as an unrelated peer device |
| **Compact failover / HA hierarchy** | `static/failover_readiness_ui.js::renderFailoverModule` → `renderUnitRow(unit, isChild)`, `.failover-child-row` / `.failover-child-cell` | HA units render with their virtual-system children indented beneath them |
| **Collapsible device details** | `static/configuration_ui.js::setConfigHeaderExpanded` (`configHeaderToggle`), `setConfigSidebarOpen` | Expand/collapse persists per viewer and does not change what data is available |
| **Left context pane + main workspace** | `.workspace` / `.sidebar` (Inventory), `.config-workspace` / `.config-sidebar` (Configuration), `.compliance-sidebar` | The three-column shape survives under the rail |
| **Current / stale / failure provenance** | `utils/snapshot.py` `data_state` ∈ `live` / `last_known_good` / `no_data` / `partial`, surfaced by every module | Provenance and freshness stay visible verbatim |
| **Vertical navigation prototype** | `static/navigation_ui.js` (rail + device tab strip, one model) | Behaviour unchanged while the architecture is DRAFT |

These rows are the same commitments recorded in the navigation DRAFT's
preservation matrix (§11 there); this appendix records **where each is verified
in source**, so the preservation criteria are checkable rather than asserted.

---

## 2. Tufin SecureTrack

Sources (2026-09-05):
- [Navigating SecureTrack — TOS R25-2](https://forum.tufin.com/support/kc/latest/Content/ST2/GettingStarted/Navigating-ST.htm)
- [Compare View](https://forum.tufin.com/support/kc/latest/Content/Suite/6985.htm)
- [SecureTrack Dashboard](https://forum.tufin.com/support/kc/aurora/Content/Suite/dashboard.htm)
- [Tufin Device Audit Report](https://forum.tufin.com/support/kc/latest/Content/Suite/3736.htm)

| Observation | Grade |
| --- | --- |
| The navigation bar "can be found on the **panel to the left of the screen**" and "consists of **six menus**". | DOC-EXCERPT |
| The six menus are **Dashboard, Browsers, Reports, Map, Monitoring, Admin**. | **PO-SOURCED-OFFICIAL** (Product Owner, from the *Navigating SecureTrack* page named above; not independently retrievable here) |
| Compare Revisions: "the **left-hand pane lists all the devices** monitored by SecureTrack **in this hierarchy**"; within domains, "**devices are divided according to device vendor**, and **management devices are shown with the devices that they manage**". | DOC-EXCERPT |
| The Dashboard "is the default opening page" and displays the number of devices monitored. | DOC-EXCERPT |
| Reports take a **Devices** selection and may be limited to specific devices within a domain. | DOC-EXCERPT |
| What each of the six menus contains; where device onboarding lives. | NOT ESTABLISHED |

**Pattern that survives the correction:** a **left-side navigation panel with a
small fixed number of top-level menus**, and — separately, inside a working
view — a **device hierarchy in which a management object contains the devices it
manages**.

**Recommendation for neXus:** both are directly relevant and both are already
the direction the DRAFT takes: a left rail with few roots, and a subject tree
whose hierarchy expresses the real management/topology relationship rather than
a flat list. Note that `Browsers`, `Map` and `Monitoring` have **no neXus
equivalent** and none is proposed — the six-menu count is not a target.

**Do not copy:** the multi-domain tenancy model. neXus has no tenancy concept.

---

## 3. AlgoSec (ASMS / Firewall Analyzer)

Sources (2026-09-05):
- [AFA — Manage devices](https://techdocs.algosec.com/en/asms/a33.00/asms-help/content/afa-admin/overview.htm)
- [Add Check Point devices](https://techdocs.algosec.com/en/asms/a32.00/asms-help/content/afa-admin/adding-a-check-point-provider.htm)
- [Enable data collection for Check Point devices](https://techdocs.algosec.com/en/asms/a33.10/asms-help/content/afa-admin/enabling-data-collection-for.htm)

| Observation | Grade |
| --- | --- |
| Adding a device goes through a **vendor and device selection page**, where the operator selects e.g. **"Check Point > Single CMA"**. | DOC-EXCERPT |
| Device management is documented under the **administration** area (`afa-admin`), titled "AFA — Manage devices". | URL-STRUCTURE |
| **"Enable data collection"** for a device is a **separate documented step** from adding the device. | URL-STRUCTURE + DOC-EXCERPT |
| Top-level menu names; the device pane's controls; how clusters, HA pairs or virtual contexts are drawn. | **NOT ESTABLISHED** |

**Pattern that survives:** enrolment is **vendor-declared then validated** — the
operator picks the vendor and the management-object kind from a closed list —
and **enrolment and collection are separate steps**.

**Recommendation for neXus:** external corroboration for two existing
repository laws rather than a new idea: the closed vendor/kind selection matches
`PCP.0` §7's "vendor hint is a hint, evidence classifies", and the separate
enable-collection step matches `PCP.0` §9's separation of enrolment from
collection. Keep both.

**Do not copy:** treating the operator's vendor selection as identity. In neXus
it is a routing hint recorded with a `classification_basis`; only positive
first-contact evidence may set `vendor`.

---

## 4. FireMon Security Manager

Sources (2026-09-05):
- [Onboarding Devices](https://docs.firemon.com/enterprise/Content/ADMINISTRATION/DEVICE/Devices/About_Adding_Devices.htm)
- [Palo Alto Firewall (device onboarding)](https://docs.firemon.com/feature/Content/ADMINISTRATION/DEVICE/Devices/Palo%20Alto/Firewall.htm)
- [Manual Retrieval](https://docs.firemon.com/feature/Content/ADMINISTRATION/DEVICE/Devices/Manual_Retrieval.htm)
- [About Security Manager](https://docs.firemon.com/feature/Content/SIP%20Topics/About%20Topics/About%20Security%20Manager.htm)

| Observation | Grade |
| --- | --- |
| Device onboarding lives under **Administration → Device → Devices → "Choose a Device to Onboard"**. | URL-STRUCTURE (`.../ADMINISTRATION/DEVICE/Devices/...`; `TocPath=Administration\|Device\|Devices\|Choose+a+Device+to+Onboard`) |
| **"Enable Scheduled Retrieval"** makes the product "retrieve the current configuration **at the scheduled interval that you specify**" — configured on the device's own page. | DOC-EXCERPT |
| An unchanged retrieved configuration is **discarded**; a changed one is stored and shown on the **All Revisions** page. | DOC-EXCERPT |
| **Manual Retrieval** is a separately documented action. | URL-STRUCTURE + DOC-EXCERPT |
| Security Manager is reached "from the menu on the **top left**"; the main Dashboard "gives an overview of your **Device Inventory**"; security rules are "found under the toolbar's **Policy** tab"; the **search bar** "is the fastest way to get to devices, device groups, rules". | DOC-EXCERPT |
| Device **groups** exist; devices within a domain must have unique IP addresses, and duplicates "must be separated into discrete device groups". | DOC-EXCERPT |
| The complete top-level menu list and its layout; how HA members are represented. | NOT ESTABLISHED |

**Pattern that survives, and the most decision-relevant row in this appendix:**
a mature NSPM places **the collection schedule on the device**, as a per-device
property with its own interval, alongside a **separate manual retrieval**
action, and stores a revision **only when the configuration actually changed**.

**Recommendation for neXus:** this is the external support for the runtime
DRAFT's schedule ownership — a schedule is a property of the device capability,
with "collect now" as a distinct action. `manual` vs `scheduled` (vs `console`)
provenance already exists in this repository (`C-D3`), and content-addressed
storage already gives the discard-if-unchanged behaviour
(`utils/config_storage.py`). Device onboarding under Administration is also
direct support for the enrolment location decision (`PO-NAV-1`).

**Do not copy:** policy authoring — `CLASS 3` in `utils/action_taxonomy.py`,
prohibited here.

---

## 5. BackBox

Sources (2026-09-05):
- [BackBox features](https://backbox.com/features/)
- [Configuration management](https://backbox.com/configuration-management/)
- [Introducing Network Vulnerability Manager](https://backbox.com/blog/introducing-network-vulnerability-manager/)

| Observation | Grade |
| --- | --- |
| "BackBox includes an **automation job for collecting inventory**, and each time this job is run, the **inventory is updated** to reflect any additions, moves, and changes in the network." | DOC-EXCERPT |
| The platform comprises a Network Automation Manager and a Network Vulnerability Manager. | VENDOR-MARKETING |
| Automations are presented "as the commands administrators are familiar with from the CLI or API", and new automations can be created by typing CLI/API commands into the interface. | VENDOR-MARKETING |
| A "Jobs menu" and a "Devices menu item" are referred to in a vendor blog. This establishes that such menus **exist by name**; it establishes **nothing** about sidebar layout, grouping, group labels or their children. | VENDOR-MARKETING |
| Sidebar layout, group headings, whether `Schedules` is a root, dashboard widget behaviour, any state/colour vocabulary. | **NOT ESTABLISHED** |

**Pattern that survives:** **inventory is the output of a job.** BackBox does
not model inventory as a separately maintained fleet list; it is refreshed by
running an automation.

**Recommendation for neXus:** this is already true here — `unified.json` is
produced by a collection run — and should be made *visible*: the Inventory view
should show which run produced it and when, rather than presenting itself as a
hand-maintained fleet.

**Do not copy, emphatically:** "type a CLI or API command into the interface to
create an automation." That is exactly the browser→device command channel that
`CON.0` §3/§4 and `SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §1
forbid. neXus' equivalent is a **closed, source-reviewed typed job registry**.
Recorded here so the pattern is explicitly *rejected with a reason*.

---

## 6. Opinnate

Sources (2026-09-05):
- [The Opinnate platform](https://opinnate.com/the-opinnate-platform/)
- [Network security policy management](https://opinnate.com/network-security-policy-management/)

| Observation | Grade |
| --- | --- |
| Positions as end-to-end NSPM — analysis, optimization, automation — managing firewalls centrally across vendors. | VENDOR-MARKETING |
| Three editions: Lite, Standard, Enterprise, with increasing automation. | VENDOR-MARKETING |
| **Navigation structure, device grouping, onboarding location, job/schedule model, cluster/HA representation.** | **NOT ESTABLISHED** |

**Assessment:** Opinnate contributes **no navigation evidence**. The only
structurally interesting observation is edition tiering, which is a licensing
model and must not be imitated by hiding shipped capabilities behind a tier.

---

## 7. Secondary references — SmartConsole and Panorama

Not the primary NSPM comparison set. They matter because neXus already
implements their semantics, so their object model is a fairness check on the
DRAFT's logical-entity model.

### 7.1 Check Point SmartConsole

Sources (2026-09-05):
- [Monitoring cluster status in SmartConsole (R81.20 ClusterXL Admin Guide)](https://sc1.checkpoint.com/documents/R81.20/WebAdminGuides/EN/CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/Monitoring-cluster-status-in-SmartConsole.htm)
- [Changing the settings of a cluster object (R80.40)](https://sc1.checkpoint.com/documents/R80.40/WebAdminGuides/EN/CP_R80.40_ClusterXL_AdminGuide/Topics-CXLG/Changing-the-Settings-of-Cluster-Object-in-SmartConsole.htm)
- [Configuring the cluster object and members (R80.30)](https://sc1.checkpoint.com/documents/R80.30/WebAdminGuides/EN/CP_R80.30_ClusterXL_AdminGuide/7419.htm)

| Observation | Grade |
| --- | --- |
| **"Gateways & Servers"** is reached "from the **left navigation panel**". | DOC-EXCERPT |
| The operator **opens the cluster object**, and **cluster members are displayed inside it**, on a "Cluster Members" page. | DOC-EXCERPT |
| Inside the opened object there is a further **left tree** (General, ClusterXL, …). | DOC-EXCERPT |

**The decisive secondary finding.** The vendor whose cluster semantics neXus
already implements models the **cluster as the primary object with members
nested inside it**, and uses a left rail at product level plus a second left
tree inside the object. That matches the Product Owner's logical-entity-first
target and means the DRAFT's Layer 1 / Layer 2 split follows the operator's
existing mental model rather than inventing one.

### 7.2 Palo Alto Panorama

Sources (2026-09-05):
- [Manage device groups (11.0)](https://docs.paloaltonetworks.com/panorama/11-0/panorama-admin/manage-firewalls/manage-device-groups)
- [Device groups (10.2)](https://docs.paloaltonetworks.com/panorama/10-2/panorama-admin/panorama-overview/centralized-firewall-configuration-and-update-management/device-groups)

| Observation | Grade |
| --- | --- |
| Managed firewall **HA peers can be grouped in a device group, and only if both peers are in the same device group**. | DOC-EXCERPT |
| Device groups are a logical grouping by segmentation / geography / function. | DOC-EXCERPT |
| The exact `Panorama` tab hierarchy for managed devices. | NOT ESTABLISHED |

**Reading:** Panorama treats an HA pair as a unit that must not be split across
grouping boundaries — consistent with neXus' existing rule that the HA pair is
the PAN readiness domain (`PCP.0` §12).

---

## 8. Comparison table

Rebuilt on surviving evidence only. *NE* = NOT ESTABLISHED. The table is mostly
NE, which is the honest state of this research in this environment.

| Question | Tufin | AlgoSec | FireMon | BackBox | Opinnate | Recommendation for neXus |
| --- | --- | --- | --- | --- | --- | --- |
| **Primary navigation shape** | left panel, six menus | NE | menu at top left | NE | NE | Left rail. Supported by Tufin and SmartConsole; **not** claimed to be universal |
| **Global roots** | Dashboard, Browsers, Reports, Map, Monitoring, Admin | NE | Dashboard; a Policy tab; an Administration area | Jobs and Devices menus exist by name only | NE | Few roots. neXus' six come from its own product domains, not from a competitor's count |
| **Below Devices / Administration** | `domain → vendor → manager → managed device` | device management under the admin area | device groups; unique-IP constraint | NE | NE | **Devices** = working fleet + entity workspace; **Administration → Device management** = lifecycle |
| **Device add / onboarding** | NE | vendor-and-device selection page, in the admin area | `Administration → Device → Devices → Choose a Device to Onboard` | NE | NE | Both entry points, one contract (`PO-NAV-1`) |
| **Jobs / schedules / automation** | NE | separate "enable data collection" step | **per-device Enable Scheduled Retrieval + separate Manual Retrieval**; revisions on All Revisions | inventory is produced by a job | NE | Schedule = property of the device capability; a global schedule *view* under Operations |
| **Configuration / compliance / recovery** | compare-revisions workflow; compliance reporting | firewall analysis + compliance reporting | Policy tab; All Revisions | configuration management + backups | NE | Configuration and Compliance stay global roots **and** entity tabs; Recovery reserved |
| **Clusters / HA / members / VS** | manager **contains** managed devices | NE | NE | NE | NE | Cluster/pair is the object, members nested (**SmartConsole**, §7.1); HA pair stays one unit (**Panorama**, §7.2) |
| **Unsupported / unconfigured / unknown** | NE | NE | NE | NE | NE | **No external evidence. Do not copy anything.** Use this repository's canonical states and `CON.0` §9 |
| **Appropriate for neXus?** | left panel + manager-contains-managed: yes; tenancy: no | vendor-declared-then-validated enrolment and enrolment≠collection: yes; vendor-as-identity: no | per-device schedule + manual retrieval + onboarding under Administration: yes; policy authoring: no | inventory-as-job-output: yes; **CLI-in-browser: no** | nothing usable | — |

---

## 9. What this appendix does not establish

1. **No third-party product UI was observed** in this session. Every layout
   claim rests on an official-page excerpt, an official URL path, or a
   Product-Owner-sourced statement naming the official page.
2. **Four of the five benchmarked products have NOT ESTABLISHED navigation
   structure** beyond what §2–§6 record. AlgoSec, BackBox and Opinnate
   contribute no menu structure at all.
3. **No capability-state vocabulary** of any competitor is established. The
   DRAFT's capability-state matrix derives solely from this repository's
   canonical states plus `CON.0` §9.
4. **No screenshots, no running product, no backend semantics**, and no claim
   about any product's performance, scale or correctness.
5. Revision 1's screenshot-derived observations are withdrawn (§0.2) and are
   **not** replaced by reconstructions.

A future session on an unrestricted network should re-run this appendix, verify
the `PO-SOURCED-OFFICIAL` row, and upgrade the NOT ESTABLISHED rows. That is a
recorded gap, not finished research.

---

## 10. Consolidated recommendations after correction

1. **Left vertical rail with few roots** — supported by Tufin's left panel and
   SmartConsole's left navigation panel. Not claimed to be universal.
2. **Domain grouping in the rail** — a neXus product decision (`PO-NAV-8`),
   **not** externally corroborated. Revision 1's claim otherwise is withdrawn.
3. **A persistent subject tree whose hierarchy is the real management
   relationship** — Tufin's manager-contains-managed and SmartConsole's
   cluster-contains-members. neXus already ships context panes; preserve them.
4. **Cluster/pair as the primary object, members nested** — SmartConsole §7.1,
   Panorama §7.2, and neXus' own shipped hierarchy (§1).
5. **Enrolment: vendor-declared then evidence-validated; enrolment separate
   from collection** — AlgoSec §3.
6. **Schedule on the device capability, with a separate "collect now"** —
   FireMon §4, the strongest external row in this appendix.
7. **Onboarding under Administration, alongside a Devices-pane affordance** —
   FireMon §4 supports the Administration half; the Devices-pane half is a
   neXus product decision (`PO-NAV-1`), not an observed pattern.
8. **Capability-state presentation** — no external basis; use this repository's
   own states.
9. **Do not copy**: CLI-command-authoring in the browser (BackBox), multi-domain
   tenancy (Tufin), policy authoring (FireMon), edition-gating of shipped
   capability (Opinnate), or any vendor's branding, palette, icon set or label
   wording.
