# Industry navigation benchmark — NSPM / network-automation products

## Status

**RESEARCH APPENDIX — evidence record, not a contract.** Directly linked from
`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (`NAV.1`, DRAFT). Nothing
here authorizes an implementation; it exists so the navigation DRAFT's
recommendations can be traced to observed practice rather than to taste.

- **Access date for every source below: 2026-09-05.**
- **Movement:** `ARCHITECTURE` (research input).

---

## 0. Evidence grade — read this before using any row below

This session runs in a remote sandbox whose egress gateway enforces an
organization allowlist. Every vendor documentation host was attempted and
**denied at the CONNECT stage with HTTP 403 (policy denial)**, re-verified
twice:

```
forum.tufin.com:443            connect_rejected  gateway answered 403 to CONNECT
techdocs.algosec.com:443       EGRESS_BLOCKED
www.algosec.com:443            connect_rejected  gateway answered 403 to CONNECT
www.firemon.com:443            connect_rejected  gateway answered 403 to CONNECT
backbox.com:443                connect_rejected  gateway answered 403 to CONNECT
opinnate.com:443               connect_rejected  gateway answered 403 to CONNECT
docs.paloaltonetworks.com:443  connect_rejected  gateway answered 403 to CONNECT
sc1.checkpoint.com:443         connect_rejected  gateway answered 403 to CONNECT
en.wikipedia.org:443           connect_rejected  gateway answered 403 to CONNECT
```

`en.wikipedia.org` failing identically is the proof that this is a blanket
allowlist, not a vendor-specific or transient block. The direct-fetch tool was
then pointed at `docs.anthropic.com` as a control and returned the **same**
`EGRESS_BLOCKED`, which settles it: **this cloud environment has no direct web
fetch capability at all**, for any host. The search backend is the only channel
to the open web that this environment has.

For completeness of the record: a corporate proxy (`http://tekprxv1:80`) was
offered during the session and was **not** used for any result here — it is an
internal hostname that does not resolve from the cloud container
(`Could not resolve proxy: tekprxv1`). Every observation below came from this
environment's own search access.

**Consequence, stated plainly:** the only channel that reached the open web was
the search tool, which returns *excerpts and short quotations from the official
documentation pages linked below*. Therefore:

| Grade | What it means here |
| --- | --- |
| **DOC-EXCERPT** | a statement quoted or closely paraphrased by the search tool from the linked official documentation page. This is the strongest grade available in this environment. It is weaker than reading the page. |
| **URL-STRUCTURE** | a fact read off the official documentation URL/table-of-contents path itself (e.g. `.../ADMINISTRATION/DEVICE/Devices/About_Adding_Devices.htm`, `TocPath=Administration\|Device\|Devices\|Choose+a+Device+to+Onboard`). Structural, reliable, and narrow. |
| **VENDOR-MARKETING** | from a vendor product/marketing page. Supports visual or positioning observations only. **Never** treated as proof of backend semantics (`AGENTS.md` vendor-semantics law). |
| **PO-SCREENSHOT** | **directly observed product UI**, from screenshots supplied by the Product Owner on 2026-09-05 (§9). The strongest grade in this appendix. Observed layout and labels only — never backend semantics. |
| **INFERENCE** | this document's reading of the above. Always labelled. Never presented as observed behaviour. |
| **NOT ESTABLISHED** | the question was asked and the available evidence does not answer it. Recorded as unknown rather than filled in. |

**The Product Owner supplied five product screenshots after the fetch failures
above.** They are the primary evidence in this appendix and are recorded in
§9; several §1–§5 rows marked NOT ESTABLISHED there are answered in §9 and the
§7 table is written from the screenshots where they apply. The screenshots
themselves are **deliberately not committed to this repository**: they contain
third-party product UI plus lab hostnames, IP addresses and an operator email
address, and `AGENTS.md` "Privacy and DLP" keeps identifiers of that kind out
of repository files. Only pattern descriptions are recorded, with no identifier
reproduced.

No screenshot was retrievable. No product was run. **No behaviour below was
invented**; where a question could not be answered it says NOT ESTABLISHED.
A future session on a network that can reach these hosts should re-run this
appendix and upgrade the grades — that is a known, recorded gap, not a
completed research task.

---

## 1. Tufin SecureTrack

Sources (2026-09-05):
- [Navigating SecureTrack — TOS R25-2](https://forum.tufin.com/support/kc/latest/Content/ST2/GettingStarted/Navigating-ST.htm)
- [Compare View](https://forum.tufin.com/support/kc/latest/Content/Suite/6985.htm)
- [SecureTrack Dashboard](https://forum.tufin.com/support/kc/aurora/Content/Suite/dashboard.htm)
- [Tufin Device Audit Report](https://forum.tufin.com/support/kc/latest/Content/Suite/3736.htm)
- [SecureTrack User Guide R21-3 (PDF)](https://forum.tufin.com/support/downloads/SecureTrack_R21-3_User_Guide.pdf)

| Observation | Grade |
| --- | --- |
| "SecureTrack's navigation bar can be found on the **panel to the left of the screen**." | DOC-EXCERPT |
| The navigation bar "consists of **six menus**", each giving insight into network connectivity and security policy changes. | DOC-EXCERPT |
| The **individual menu names were not retrievable** in this environment. | NOT ESTABLISHED |
| Compare Revisions: "the **left-hand pane lists all the devices** monitored by SecureTrack **in this hierarchy**." | DOC-EXCERPT |
| In that hierarchy, "all domains are listed with their devices. Within the domains, **devices are divided according to device vendor**, and **management devices are shown with the devices that they manage**." | DOC-EXCERPT |
| The Dashboard "is the default opening page" and displays the number of devices monitored. | DOC-EXCERPT |
| Reports take a **Devices** selection; a report may be limited to specific devices within a domain. | DOC-EXCERPT |
| Where "add device" lives in the six-menu structure. | NOT ESTABLISHED |

**Directly observed navigation pattern:** left vertical navigation with a small
fixed number of top-level menus; a dashboard as the default landing surface.

**Directly observed object grouping:** a persistent left-hand *device hierarchy*
pane inside a working view (Compare), where the hierarchy is
`domain → vendor → management device → the devices it manages`. The manager is
not a sibling of the devices it manages; it **contains** them.

**Directly observed operational workflow:** revision comparison is a first-class
view with its own device-tree pane, not a per-device afterthought.

**Inference:** a six-menu ceiling suggests deliberate domain grouping rather than
one root per feature — the same pressure `NAV.1` responded to.

**Recommendation for neXus:** adopt the *shape* — left rail, few roots, and a
persistent subject tree inside working views whose hierarchy expresses the real
management relationship (manager contains managed). This directly supports the
Product Owner's logical-entity-first model and the `PCP.0` §12 rule that a
device and a failover unit are different objects.

**Do not copy:** the domain/multi-domain tenancy model. neXus has no tenancy
concept, and inventing one to mirror a competitor's tree depth would create an
identity layer the repository has no authority for.

---

## 2. AlgoSec (ASMS / Firewall Analyzer)

Sources (2026-09-05):
- [AFA — Manage devices](https://techdocs.algosec.com/en/asms/a33.00/asms-help/content/afa-admin/overview.htm)
- [Add Check Point devices](https://techdocs.algosec.com/en/asms/a32.00/asms-help/content/afa-admin/adding-a-check-point-provider.htm)
- [Enable data collection for Check Point devices](https://techdocs.algosec.com/en/asms/a33.10/asms-help/content/afa-admin/enabling-data-collection-for.htm)
- [AFA components](https://techdocs.algosec.com/en/asms/a33.00/asms-help/content/afa-admin/algosec-firewall-analyzer_1.htm)
- [Welcome to AlgoSec Firewall Analyzer](https://techdocs.algosec.com/en/asms/a32.60/asms-help/content/afa-admin/about-algosec-firewall-analyzer.htm)
- [Best practices for ongoing maintenance in ASMS](https://techdocs.algosec.com/en/asms/a33.20/asms-help/content/afa-admin/ongoing-maintenance.htm)

| Observation | Grade |
| --- | --- |
| Adding a device goes through a **"vendor and device selection page"**, where the operator selects e.g. **"Check Point > Single CMA"**. | DOC-EXCERPT |
| Device management is documented as an **administration** area ("AFA — Manage devices", under `afa-admin`). | URL-STRUCTURE |
| **"Enable data collection"** for a device is a **separate documented step** from adding the device. | URL-STRUCTURE + DOC-EXCERPT |
| The AFA top-level menu names. | NOT ESTABLISHED |
| How cluster members / HA pairs are drawn in the device list. | NOT ESTABLISHED |

**Directly observed operational workflow:** enrolment is **vendor-declared then
validated** — the operator picks the vendor and the management-object kind
(a CMA, not "a firewall") from a closed list, rather than the product guessing
the vendor from the endpoint.

**Directly observed grouping:** the unit of onboarding can be a *management
object* (CMA), not only a firewall.

**Recommendation for neXus:** this is strong external corroboration for two
existing repository laws rather than a new idea — the closed vendor/kind
selection matches `PCP.0` §7's "vendor hint is a hint, evidence classifies",
and the separate *enable collection* step matches this repository's insistence
that **enrolment and collection are separate** (`PCP.0` §9). Keep both.

**Do not copy:** treating the operator's vendor selection as identity. In neXus
the selection is a routing hint; only positive first-contact evidence may set
`vendor` with a `classification_basis` (`PCP.0` §7, `utils/device_registry.py`).

---

## 3. FireMon Security Manager

Sources (2026-09-05):
- [Onboarding Devices](https://docs.firemon.com/enterprise/Content/ADMINISTRATION/DEVICE/Devices/About_Adding_Devices.htm)
- [Palo Alto Firewall (device onboarding)](https://docs.firemon.com/feature/Content/ADMINISTRATION/DEVICE/Devices/Palo%20Alto/Firewall.htm)
- [Manual Retrieval](https://docs.firemon.com/feature/Content/ADMINISTRATION/DEVICE/Devices/Manual_Retrieval.htm)
- [About Security Manager](https://docs.firemon.com/feature/Content/SIP%20Topics/About%20Topics/About%20Security%20Manager.htm)
- [Security Manager settings](https://docs.firemon.com/feature/Content/ADMINISTRATION/SETTINGS/SM/Security%20Manager.htm)

| Observation | Grade |
| --- | --- |
| Device onboarding lives under **Administration → Device → Devices → "Choose a Device to Onboard"**. | URL-STRUCTURE (`.../ADMINISTRATION/DEVICE/Devices/...`, `TocPath=Administration\|Device\|Devices\|Choose+a+Device+to+Onboard`) |
| Security Manager is reached "from the menu on the **top left**"; the main Dashboard "gives an overview of your **Device Inventory**". | DOC-EXCERPT |
| Security rules are "found under the toolbar's **Policy** tab". | DOC-EXCERPT |
| "Security Manager's **search bar** is the fastest way to get to devices, device groups, rules, etc." | DOC-EXCERPT |
| **"Enable Scheduled Retrieval"** makes the product "retrieve the current configuration **at the scheduled interval that you specify**" — a **per-device** setting on the device's own configuration page. | DOC-EXCERPT |
| If the retrieved configuration is unchanged it is **discarded**; if it differs it is stored and "displays it on the **All Revisions** page". | DOC-EXCERPT |
| **Manual Retrieval** is a separately documented action (collect now). | URL-STRUCTURE + DOC-EXCERPT |
| Device **groups** exist; devices in a domain must have unique IP addresses, and duplicates "must be separated into discrete device groups". | DOC-EXCERPT |
| Whether HA members appear as one object or two. | NOT ESTABLISHED |

**Directly observed operational workflow:** the most decision-relevant finding
in this appendix. A mature NSPM product places **the collection schedule on the
device**, as a per-device property with its own interval, alongside a separate
**manual retrieval** action — and stores a **revision** only when the
configuration actually changed.

**Directly observed grouping:** device *groups* as an organising layer, with a
documented uniqueness constraint driving the grouping.

**Recommendation for neXus:** this is the external corroboration for Part I of
the DRAFT: per-device / per-capability schedules belong to the **selected device
capability**, surfaced in the device workspace, with a global schedule *view*
for fleet-wide orientation — not a schedule editor bolted onto navigation.
"Collect now" and "scheduled retrieval" being different documented things
matches this repository's `manual` vs `scheduled` (and `console`) provenance
vocabulary exactly (`C-D3`). Content-addressed dedup already gives neXus the
"discard if unchanged" behaviour (`utils/config_storage.py`).

**Do not copy:** Security Manager's editing depth (policy authoring) — that is
`CLASS 3` in `utils/action_taxonomy.py` and prohibited here. Also do not copy
"Administration owns devices" *wholesale*: see §7's recommendation for the
split neXus should adopt instead.

---

## 4. BackBox

Sources (2026-09-05):
- [BackBox features](https://backbox.com/features/)
- [Device lifecycle and access management](https://backbox.com/device-lifecycle-access-management/)
- [Introducing Network Vulnerability Manager](https://backbox.com/blog/introducing-network-vulnerability-manager/)
- [Configuration management](https://backbox.com/configuration-management/)
- [BackBox 6.0 datasheet (PDF)](https://www.vrcomm.net/resource/BackBox-6.0-Datasheet.pdf)

| Observation | Grade |
| --- | --- |
| There is a **Jobs menu with tabs**, and a **Devices menu item** that also carries tabs (e.g. a "Vulnerability Intelligence" tab). | DOC-EXCERPT (vendor blog describing the product UI) |
| **"BackBox includes an automation job for collecting inventory, and each time this job is run, the inventory is updated to reflect any additions, moves, and changes in the network."** | DOC-EXCERPT |
| The platform is composed of a **Network Automation Manager** and a **Network Vulnerability Manager**. | VENDOR-MARKETING |
| "Most customers are up and running, with device backups initiated, in under an hour." | VENDOR-MARKETING |
| Automations are presented "as the commands administrators are familiar with from the CLI or API", and new automations can be created by typing CLI/API commands into the interface. | VENDOR-MARKETING |
| Full top-level menu structure. | NOT ESTABLISHED |

**Directly observed navigation pattern:** **Jobs is a top-level menu**, and both
Jobs and Devices use an inner tab strip. This is the single clearest external
data point for the open `NAV.1` question of whether Jobs deserves a root.

**Directly observed operational workflow:** **inventory is the output of a job.**
BackBox does not model inventory as a separately maintained fleet list; it is a
projection refreshed by running an automation.

**Recommendation for neXus:** two things. (1) Jobs earning a root in a product
whose whole value is scheduled collection supports *promoting* Jobs from an
Operations child to a root — but only when it owns definitions, runs, schedules
and history (`PCP.5`), which is exactly the promotion trigger the DRAFT
records. (2) "Inventory is a job output" is already true in neXus
(`unified.json` is produced by a collection run) and should be made *visible*:
the Inventory view should show which job produced it and when, not present
itself as a hand-maintained fleet.

**Do not copy, emphatically:** "type a CLI or API command into the interface to
create an automation." That is precisely the browser→device command channel
that `CON.0` §3/§4 and
`SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §1 forbid. neXus'
equivalent is a **closed, source-reviewed typed job registry**. This row exists
in the appendix specifically so the pattern is recorded as *rejected with a
reason*, not silently absent.

---

## 5. Opinnate

Sources (2026-09-05):
- [The Opinnate platform](https://opinnate.com/the-opinnate-platform/)
- [Network security policy management](https://opinnate.com/network-security-policy-management/)
- [Firewall policy management](https://opinnate.com/firewall-policy-management/)
- [Opindesk](https://opinnate.com/opindesk/)
- [Gartner Peer Insights — Opinnate NSPM](https://www.gartner.com/reviews/product/opinnate-network-security-policy-manager)

| Observation | Grade |
| --- | --- |
| Positions as end-to-end NSPM: analysis, optimization, automation; "managing all firewalls centrally no matter how many vendors or devices". | VENDOR-MARKETING |
| Three editions — **Lite** (rule analysis + compliance-based reports), **Standard** (automatic rule optimization via workflows), **Enterprise** (end-to-end rule automation). | VENDOR-MARKETING |
| Has a built-in policy assistant/copilot for compliance and governance. | VENDOR-MARKETING |
| **Navigation structure, device grouping, onboarding location, job/schedule model, cluster/HA representation.** | **NOT ESTABLISHED** — no administrator/user guide was reachable, and no navigation claim can be made. |

**Assessment:** Opinnate contributes **no navigation evidence** to this
benchmark. The only structurally interesting observation is edition-tiering
(capability sets sold separately), which is a licensing model, not an IA — and
which neXus must not imitate by hiding shipped capabilities behind a fake tier.

Recorded as an explicit research gap for a session with unrestricted egress.

---

## 6. Secondary references — SmartConsole and Panorama

These are **not** the primary NSPM comparison set. They matter because neXus
already implements their semantics, so their own object model is a fairness
check on the DRAFT's logical-entity model.

### Check Point SmartConsole

Sources (2026-09-05):
- [Monitoring cluster status in SmartConsole (R81.20 ClusterXL Admin Guide)](https://sc1.checkpoint.com/documents/R81.20/WebAdminGuides/EN/CP_R81.20_ClusterXL_AdminGuide/Content/Topics-CXLG/Monitoring-cluster-status-in-SmartConsole.htm)
- [Changing the settings of a cluster object (R80.40)](https://sc1.checkpoint.com/documents/R80.40/WebAdminGuides/EN/CP_R80.40_ClusterXL_AdminGuide/Topics-CXLG/Changing-the-Settings-of-Cluster-Object-in-SmartConsole.htm)
- [Configuring the cluster object and members (R80.30)](https://sc1.checkpoint.com/documents/R80.30/WebAdminGuides/EN/CP_R80.30_ClusterXL_AdminGuide/7419.htm)
- [Configuring gateway and cluster objects (SMB R81.10.x)](https://sc1.checkpoint.com/documents/SMB_R81.10.X/AdminGuides_Centrally_Managed/EN/Content/Topics/Configuring-Gateway-and-Cluster-Objects.htm)

| Observation | Grade |
| --- | --- |
| **"Gateways & Servers"** is reached "from the **left navigation panel**". | DOC-EXCERPT |
| The operator **opens the cluster object**, and **cluster members are displayed inside it on a "Cluster Members" page**. | DOC-EXCERPT |
| Inside the opened object there is a further **left tree** (General, ClusterXL, …). | DOC-EXCERPT |

**This is the decisive secondary finding.** The vendor whose cluster semantics
neXus already implements models the **cluster as the primary object with members
nested inside it**, and uses a left rail at the product level plus a second left
tree inside the object. That is exactly the Product Owner's logical-entity-first
target, and it means the DRAFT's Layer 1 / Layer 2 split (product rail +
entity workspace) matches the operator's existing mental model rather than
inventing one.

### Palo Alto Panorama

Sources (2026-09-05):
- [Manage device groups (11.0)](https://docs.paloaltonetworks.com/panorama/11-0/panorama-admin/manage-firewalls/manage-device-groups)
- [Device groups (10.2)](https://docs.paloaltonetworks.com/panorama/10-2/panorama-admin/panorama-overview/centralized-firewall-configuration-and-update-management/device-groups)
- [Add a device group (11.0)](https://docs.paloaltonetworks.com/panorama/11-0/panorama-admin/manage-firewalls/manage-device-groups/add-a-device-group)
- [Panorama Administrator's Guide (10.1)](https://docs.paloaltonetworks.com/panorama/10-1/panorama-admin)

| Observation | Grade |
| --- | --- |
| Managed firewall **HA peers can be grouped in a device group, and only if both peers are in the same device group**. | DOC-EXCERPT |
| Device groups are a logical grouping by segmentation / geography / function requiring similar policy. | DOC-EXCERPT |
| Templates / template stacks configure network and server-profile settings separately from policy. | DOC-EXCERPT |
| The exact `Panorama` tab hierarchy for managed devices. | NOT ESTABLISHED |

**Reading:** Panorama treats an HA pair as a unit that must not be split across
grouping boundaries — consistent with neXus' existing rule that the HA pair is
the PAN readiness domain (`PCP.0` §12) and that VSYS is subordinate context.

---

## 7. Comparison table (the eight questions)

Written from §9's directly observed screenshots where they apply, and from
§1–§6 otherwise. *NE* = NOT ESTABLISHED; nothing is guessed.

| Question | Tufin SecureTrack | AlgoSec AFA | FireMon SM | BackBox | OPNsense (control) | Recommendation for neXus |
| --- | --- | --- | --- | --- | --- | --- |
| **Primary navigation shape** | narrow left **icon rail** + wide persistent device-tree pane | collapsible left **"DEVICES"** pane; product nav at pane foot (`HOME`, `DEVICES`) | **horizontal top menu bar** of dropdowns | **left vertical rail with uppercase group headings** | left vertical rail with expandable groups | Left vertical rail. Note honestly that FireMon does not — see §10 |
| **What appears as a global root?** | icon rail (labels not visible) | `HOME`, `DEVICES` | `Overview, Policy, Compliance, Change, Topology, Risk Analyzer` + `Reports, Tools, Help` | `Dashboard, Devices, Schedules, Notifications`; **OPERATIONS**: `Automations, Jobs, File Repository, Queue, History, Access`; **ADMINISTRATIVE**: `Authentication, Administration` | `Lobby, Reporting, System, Interfaces, Firewall, VPN, Services, Help` | Few roots + uppercase domain group headings — the BackBox pattern, which is what `NAV.1` independently built |
| **What sits below Devices / Administration?** | `vendor → manager → managed device`, with a **`Vendors` / `Groups`** tab pair switching the tree's organising axis | firewall → **its virtual contexts (VDOMs) nested beneath it** | device groups; per-row `•••` menus | `Devices ▶` submenu; `Administration ▶` submenu | grouped children per domain | **Devices** = operator working fleet + entity workspace; **Administration → Device management** = registry lifecycle/profiles |
| **Where is device add / management?** | NE | **wrench icon in the `DEVICES` pane header**; a device-selection page picks vendor + object kind | `Administration → Device → Devices → Choose a Device to Onboard` | `Devices` root; `Administration` group | — | **Both, one contract**: primary contextual action in the Devices pane header (AlgoSec pattern), also reachable from Administration → Device management (FireMon pattern). Never a root |
| **How are jobs / schedules / automation grouped?** | NE | separate "enable data collection" step | per-device **Enable Scheduled Retrieval** + separate **Manual Retrieval**; revisions on **All Revisions** | **`Schedules` is its own root**, while `Automations`, `Jobs`, `Queue`, `History` are **four siblings under OPERATIONS** | — | Definitions/schedules, queue, executions and history are **distinct surfaces**, not one "Jobs" page. Keep Jobs under Operations now; the mature end-state is several Operations children (§10) |
| **Where do configuration / compliance / recovery live?** | Risk / Change / Cleanup panels stacked in one workspace | workspace tabs `OVERVIEW, POLICY, CHANGES, REPORTS, ALL REPORTS, MAP` | top menus `Policy`, `Compliance`, `Change` | `Config Changes` and `Backup Status` are **dashboard widgets**; backups under Operations/Schedules | — | Configuration and Compliance stay global roots **and** entity tabs; Recovery earns a root when its fleet surfaces ship |
| **How are clusters / HA / members / VS shown?** | manager **contains** managed devices; PAN device groups nested several levels | **virtual contexts nested under their physical firewall** | flat device list with a group selector | flat device list | — | **Confirmed externally**: nest virtual systems under the physical parent; treat the manager/cluster as the container. Exactly the DRAFT's logical-entity model |
| **How is unsupported / unconfigured / unknown communicated?** | NE | per-device **`Issues (17)`** counter; `Analysis ●` status pill | **`Not enough historical data`** rendered as its **own distinctly-coloured chip**, beside good/bad scores | **`Identical / Changed / N/A`** for config; **`Successful / Suspect / Failed`** for backups | `Online`/`Offline` and running/stopped as **colour + text** | **Directly validated**: mature products ship an explicit third/fourth state rather than collapsing to pass/fail or blank. This is the external basis for the DRAFT's capability-state matrix |
| **Appropriate for neXus?** | tree shape yes; tenancy no | pane header action + VDOM nesting yes; vendor-as-identity no | schedule model + explicit insufficient-data chip yes; top-nav and policy editing no | rail grouping, Operations children, explicit middle states yes; **CLI-in-browser absolutely not** | colour+text pattern yes | — |

---

## 8. What this appendix does *not* establish

1. **Tufin's six menu names** were never retrievable, and the icon rail in the
   screenshot shows glyphs without labels. The `NAV.1` root *set* is therefore
   justified by neXus' own product domains, with BackBox's grouped rail as the
   closest observed structural analogue.
2. **Product versions are mixed and partly old.** The AlgoSec and OPNsense
   screenshots carry mid-2010s dates, BackBox is a 2022 build, FireMon is a
   recent demo tenant. Patterns that appear in several products across a decade
   are treated as durable; anything seen once is treated as a single data point.
3. **No backend semantics** are established by any screenshot. A visible label
   proves a label, never a contract (`AGENTS.md` vendor-semantics law).
4. **Opinnate contributed no navigation evidence** at all.
5. No claim about performance, scale, or correctness of any product listed.

These gaps are part of why the navigation contract is **DRAFT**.

---

## 9. Directly observed screenshots (Product Owner supplied, 2026-09-05)

Five screenshots were supplied by the Product Owner. Layout, grouping and
visible state labels are recorded; **no branding, colour palette, icon set or
label wording is to be copied** into neXus (`NAV.1` §1). Identifiers visible in
the images (hostnames, IP addresses, an operator email) are deliberately not
reproduced here.

### 9.1 Tufin SecureTrack — PO-SCREENSHOT

- **Two-part left navigation**: a narrow **icon-only rail** at the window edge,
  plus a **wide, always-present device-tree pane** beside it. The workspace is
  the third column.
- The tree pane has a **`Vendors` / `Groups` tab pair** — the same fleet, two
  organising axes, switched by the operator.
- Tree shape observed: `All Devices → site → vendor → management object → the
  devices it manages`, with a Panorama branch nested **four levels deep**
  (Panorama → device group → child group → child group).
- Workspace: several stacked analysis panels (risk / change / cleanup), each
  with its own `Show:` scope selector and a rotated section label on its left
  edge. A change table carries `Device | # | Changed on | Received on | Admin |
  Action | Policy | Installed On | Ticket`.
- **Reading for neXus:** the icon rail + persistent subject tree is precisely
  the "left context pane plus main workspace" neXus already ships in Inventory
  and Configuration. It is the mature form of the same idea, and it survives a
  fleet with deep management hierarchies. The `Vendors`/`Groups` axis switch is
  a strong future candidate for the neXus device tree (vendor vs logical
  grouping) — recorded as a design candidate, not a recommendation for now.

### 9.2 AlgoSec Firewall Analyzer — PO-SCREENSHOT

- Left pane is titled **`DEVICES`**, is **collapsible** (chevron), and carries a
  **wrench icon in its header** — device configuration/management reached from
  the pane that lists devices.
- Pane contents: a **search box**, an **`All Brands` vendor filter**, an
  **`Issues (17)`** counter, and **`Collapse All` / `Expand All`**.
- Tree: `ALL_FIREWALLS` → physical firewalls → **their virtual contexts (VDOMs)
  nested directly beneath the physical device**.
- Product-level navigation sits at the **foot of the pane** as `HOME` and
  `DEVICES`.
- Workspace header shows the selected subject's identity, **`Latest Report
  <timestamp>`** freshness, **`Number of devices: 32`**, and an `Analysis ●`
  status pill.
- **The tab strip is identical for a group selection and a device selection**:
  `OVERVIEW | POLICY | CHANGES | REPORTS | ALL REPORTS | MAP`.
- Below the tabs, a **contextual action toolbar**: analyse, traffic-simulation
  query, locate object, compare, topology, trusted traffic, delete.
- **Readings for neXus** (three, all load-bearing):
  1. **Virtual contexts nest under their physical parent** — direct external
     precedent for VSX host → virtual system, and for PAN vsys as subordinate
     context.
  2. **Tab identity is stable across selection levels** — the fleet node and a
     device node share one tab vocabulary. This is the external support for
     `NAV.1` D-NAV13 and for a global module and an entity tab sharing a name
     while keeping separate data authority.
  3. **Contextual actions are a toolbar inside the workspace, and device
     management hangs off the Devices pane header** — never navigation roots.
     This is the single clearest external answer to "where does Add Device go".

### 9.3 FireMon Security Manager — PO-SCREENSHOT

- **Horizontal top menu bar**, not a left rail: `Enterprise ▾`, then
  `Overview | Policy | Compliance | Change | Topology ⚠ | Risk Analyzer`, with
  `Reports | Tools | Help` right-aligned. A **global search by name or IP**
  scoped by an `All ▾` selector sits above it.
- Landing surface is an **Overview Dashboard** of KPI cards, one of which is
  `Device Inventory` with a deep-link arrow.
- A `Devices Recently Revised` table with `Device Name | Last Revision | SCI |
  % Change (Trend)`, a per-row `•••` overflow menu, and a numeric score shown as
  **a coloured square plus its number**.
- One row reads **`Not enough historical data`** in a **distinct colour**,
  sitting in the same column as good and bad scores.
- A `Topology` menu carries a **⚠ badge** in the menu bar itself.
- **Readings for neXus:**
  1. **A mature NSPM does not use a left rail.** Recorded honestly: left-rail
     navigation is common but not universal. It does not invalidate `NAV.1`'s
     direction — the vertical rail wins on room to grow and on the deep device
     tree these products all need — but the DRAFT must not claim the industry is
     unanimous.
  2. **`Not enough historical data` as its own visual state** is the strongest
     external evidence in this appendix for the DRAFT's core correction: an
     absent measurement is neither good nor bad and must not be blank.
  3. Per-row `•••` and a badge on a nav item are both patterns worth adopting
     later — contextual actions on the row, attention count on the domain.

### 9.4 BackBox — PO-SCREENSHOT

- **Left vertical rail with uppercase group headings** — the closest observed
  analogue to what `NAV.1` built independently:
  - ungrouped roots: `Dashboard ▾` (children `Status`, `Reports`), `Devices ▶`,
    `Schedules`, `Notifications`
  - **`OPERATIONS`**: `Automations`, `Jobs`, `File Repository`, `Queue`,
    `History`, `Access ▶`
  - **`ADMINISTRATIVE`**: `Authentication ▶`, `Administration ▶`
- Roots expand/collapse individually (`▾` / `▶`) — the accordion `NAV.1` built.
- Dashboard workspace is a grid of removable, draggable widgets with a
  `Dashboard Layout` selector.
- Observed state vocabularies in those widgets:
  - config changes → **`Identical` / `Changed` / `N/A`**
  - backup status and integrity-check status → **`Successful` / `Suspect` /
    `Failed`**
- **Readings for neXus** (the most decision-relevant screenshot):
  1. **Uppercase domain group headings over a left rail is a shipped, mature
     pattern**, not an invention of this prototype. `NAV.1` D-NAV3 is
     externally corroborated.
  2. **`Schedules` is a root of its own, and `Jobs` is one of several
     Operations children** alongside `Automations`, `Queue` and `History`. This
     reframes the neXus question: the mature end-state is not "does Jobs become
     a root" but "Operations grows several execution children, and schedules may
     deserve their own surface". Recorded as an open decision rather than a
     recommendation, because neXus has none of those surfaces yet.
  3. **`N/A` and `Suspect` are first-class states** in a shipped product's
     summary widgets. Again: mature products refuse to collapse to pass/fail.

### 9.5 OPNsense — PO-SCREENSHOT (control sample, not an NSPM)

- Left vertical rail with an expandable first group and single-level domains.
- A services table renders state as **an icon plus the word** (`Online` /
  `Offline`, running / stopped), with **per-row start/restart/stop controls
  beside the state** they act on.
- **Reading for neXus:** state is carried by colour **and** text together, and
  the action that changes a state sits next to that state, not in navigation.
  Both are directly reusable and both are already `NAV.1` D-NAV14 / Layer 3.

---

## 10. Consolidated recommendations, after the screenshots

1. **Keep the left vertical rail with domain group headings.** Corroborated by
   BackBox (uppercase groups), Tufin and OPNsense. Report honestly that FireMon
   ships a horizontal top nav — the pattern is dominant, not universal.
2. **Add a persistent subject tree to the device experience.** All three
   device-centric products (Tufin, AlgoSec, and FireMon's device tables) put the
   fleet where the operator can keep it in view. neXus already has this in
   Inventory and Configuration and should preserve it, not replace it with rail
   navigation.
3. **Nest virtual contexts under their physical parent** (AlgoSec VDOMs, Tufin's
   manager→managed). Directly supports the DRAFT's VSX → VS and manager → member
   model.
4. **Keep one tab vocabulary across selection levels** (AlgoSec's identical tab
   strip for the fleet node and a device node).
5. **Put device management on the Devices pane header** (AlgoSec wrench) **and**
   in Administration (FireMon) — one contract, two entry points.
6. **Ship explicit third states.** `Not enough historical data`, `N/A`,
   `Suspect` are all shipped external precedent for the DRAFT's refusal to
   collapse unsupported / unconfigured / stale / unknown into one grey.
7. **Do not copy**: BackBox's "type a CLI or API command to create an
   automation" (a browser→device command channel `CON.0` §3/§4 forbids),
   Tufin's multi-domain tenancy, FireMon's policy authoring (`CLASS 3`), or any
   vendor's branding, palette, icon set or label wording.
