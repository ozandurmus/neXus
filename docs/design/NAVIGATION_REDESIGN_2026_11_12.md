# Navigation and Administration redesign 2026-11-12

**Status: FROZEN -- PRODUCT OWNER APPROVED, 2026-11-12** (every recommendation, relayed by the PO session; decision record
[PO_DECISION_RECORD_2026_11_12_NAVIGATION_AND_ADMINISTRATION_REDESIGN.md](PO_DECISION_RECORD_2026_11_12_NAVIGATION_AND_ADMINISTRATION_REDESIGN.md)).

Scope: the left navigation (wide sidebar, folded icons, narrow drawer) and the structure of the Administration area.
Design only. No source file is changed by this document. Regrouping and renaming only: no screen is added, no API,
schema, permission or device command changes.

Mockup (static, masked, no real names): `nav-redesign-mockup-1112.html` in the session notes folder.

## 1. Problem

Checked against the code (`ui2/frontend/src/shell/NavigationRail.tsx`, `screens/AdministrationScreen.tsx`).

| # | Finding | Evidence |
|---|---|---|
| 1 | Groups are verbs with one or two items each; Overview, Reports and Administration sit outside any group. The frozen rule D-NAV3 says "group by stable product domain, not by action". | `RAIL_ENTRIES`, `RailGroup` |
| 2 | Two lists are maintained by hand and disagree. The drawer has 8 entries the wide rail does not have, one of them a disabled placeholder (`Discovery`), which D-NAV6b forbids. | `RAIL_ENTRIES` vs `DRAWER_GROUPS` |
| 3 | Tools hides four screens behind a popup. | `destinations` on the Tools entry |
| 4 | Lifecycle and security has no entry on the wide layout. Its button inside Devices does not write the address, so the view cannot be bookmarked from there. | `InventoryScreen.tsx` line 1238 |
| 5 | Names differ between menu and screen (`Packets` / `Packet Analysis`, `Sanitizer` / `Data Sanitizer`) and mix three styles (`Device Registry`, `System health`, `Access & Identity` next to `Lifecycle and security`). | labels, `SCREEN_HEADINGS` |
| 6 | The active mark is coarse. With Operations > Jobs open, the rail and the drawer both light `Operations`; the drawer's `Jobs` row never lights. | drawer `on=` rule |
| 7 | Administration is four levels deep: rail entry, then a second 224 px side menu inside the screen (four pages plus a `Product` heading), then collapsible sections, then cards with their own headings inside a section. | `AdministrationScreen.tsx` |
| 8 | The two layouts gate Administration differently: the wide screen shows every page to every session, locked where not allowed (decision of 2026-11-05); the drawer hides the four page links without the Admin plane. | `DRAWER_GROUPS` `requiredPlane: "Admin"` |

One correction to the brief: no Administration section holds a real tab list. `Device registry capabilities` in
`DeviceRegistryPanel.tsx` is a row-actions menu, not tabs; `HttpsCertificatePanel` is used on the device page only.
The nesting the PO sees is finding 7.

## 2. The new structure

One list, nine top-level entries, in the order of the product principle (SEE, VERIFY, TRACE, RECOVER, OPERATE). The
verb words are no longer printed; the order still follows them. A domain with one screen is a direct link; a domain
with several is a group that opens in place (D-NAV3). One level of nesting, nowhere more.

| # | Entry | Kind | Children | The operator question it answers |
|---|---|---|---|---|
| 1 | Overview | link | | Is the estate all right at this moment? |
| 2 | Reports | link | | What do I hand to management or an auditor? |
| 3 | Devices | link | | What do we have, and what state is each device in? |
| 4 | Compliance and security | group | Compliance; Lifecycle and security | Are we configured to the standard, and are we supported, licensed and patched? |
| 5 | Policy and changes | group | Policy; Trace; Changes | What do the firewalls allow, can A reach B, and what changed? |
| 6 | Backups | link | | Can we restore a device today? |
| 7 | Operations | group | HA and readiness; Jobs; System health | What is running now, what failed, and is a cluster ready to fail over? |
| 8 | Tools | group | Packet analysis; Data sanitizer; Diagnostics; Offline analysis | I have a capture, a file or one device to examine by hand. |
| 9 | Administration | group | Device registry; Access and identity; Platform; Audit log; Delivery plan | Who and what may neXus reach, and how is the product itself set up? |

Counts: 9 top-level entries (today 11 on the wide rail, plus 4 hidden in a popup; 22 rows in the drawer). 21
destinations in total, each with exactly one menu entry.

Rule for sub-views: a tab gets its own menu entry only when it answers a different question from its screen's first
tab (Jobs, System health, Lifecycle and security, the Administration pages). Trace modes, Changes views, Policy tabs
and Reports tabs stay tabs on their screen.

Naming rule: sentence case, "and" instead of "&", acronyms keep their case (HA, SSH, LDAP). A menu label is the same
text as the heading of the screen, or the tab on that screen, that it opens.

## 3. Before and after: navigation

Gate = the plane a session's permission list must hold for the entry to show. "none" = every signed-in session.
Gates do not change. An entry whose screen is the open screen is always shown, as today.

| Today (where) | New place | Route | Gate | Change |
|---|---|---|---|---|
| Overview (rail, drawer) | Overview | `?screen=overview` | none | kept |
| Reports (rail, drawer) | Reports | `?screen=reports` | none | kept |
| group label SEE | | | | removed |
| Devices (rail, drawer) | Devices | `?screen=inventory`, `/devices/<id>` | Devices | kept |
| Lifecycle and security (drawer only) | Compliance and security > Lifecycle and security | `?screen=inventory&view=lifecycle` | Devices | moved; new link on the wide layout |
| Discovery (drawer, disabled) | | | | removed (never had a screen; D-NAV6b) |
| group label VERIFY | group Compliance and security | | | renamed |
| Compliance | Compliance and security > Compliance | `?screen=compliance`, `/compliance` | Compliance | moved |
| Policy | Policy and changes > Policy | `?screen=policy` | Policy | moved |
| group label TRACE | group Policy and changes | | | renamed |
| Trace | Policy and changes > Trace | `?screen=trace` | none | moved |
| Changes | Policy and changes > Changes | `?screen=changes` | none | moved |
| group label RECOVER | | | | removed |
| Backups | Backups | `?screen=backups`, `/backups` | none | kept |
| group label OPERATE | group Operations | | | renamed |
| Operations | Operations > HA and readiness | `?screen=operations` | none | renamed (tab label `HA & readiness` too) |
| Jobs (drawer only) | Operations > Jobs | `?screen=operations&tab=jobs` | none | new link on the wide layout |
| System health (drawer only) | Operations > System health | `?screen=operations&tab=health` | none | new link on the wide layout |
| Tools (popup button) | group Tools, children shown in place | | | kept; popup removed |
| Packets | Tools > Packet analysis | `?screen=packet_analysis` | PacketAnalysis | renamed (heading `Packet Analysis` too) |
| Sanitizer | Tools > Data sanitizer | `?screen=sanitizer` | Sanitizer | renamed (heading `Data Sanitizer` too) |
| Diagnostics | Tools > Diagnostics | `?screen=diagnostics` | none | kept |
| Offline analysis | Tools > Offline analysis | `?screen=offline_analysis` | Policy | kept |
| Administration (rail, pinned at the bottom) | group Administration, last | `?screen=administration` opens Device registry | none | kept; becomes a group |
| Collapse menu / Expand menu button | same button, same preference `nx.rail.collapsed` | | | kept |
| Open navigation button (narrow) | same button | | | kept |

## 4. Before and after: Administration

Today: one screen titled Administration, a second side menu inside it, collapsible sections, cards inside sections.

After: the five pages are the children of the Administration menu group. The side menu inside the screen is
removed. Each page is one screen whose heading is its menu label, with at most one tab strip (the component
Operations already uses). One section is on screen at a time, and only that one is loaded, as today. A page with a
single section (Audit log, Delivery plan) has no tab strip at all. The `Product` heading is dropped; Delivery plan
is the last child.

Lock = how the section is restricted today. "admin read" is the server grant `canReadAdministration`; "Admin plane"
is the plane check. A restricted page or tab stays visible with a lock and opens to the Restricted state. Unchanged.

| Today: page > section | After: menu child > tab | Route (unchanged) | Lock | Change |
|---|---|---|---|---|
| Device Registry (page) | Device registry | `tab=registry` | none | renamed; page tab becomes menu child |
| > Device management | > Device management | `tab=registry&section=device-management` | none | section becomes tab |
| > Inventory exclusions | > Inventory exclusions | `...&section=inventory-exclusions` | none | section becomes tab |
| > Credentials | > Credentials | `...&section=credentials` | admin read | section becomes tab |
| > SSH trust | > SSH trust | `...&section=ssh-trust` | none to read; change needs admin read | section becomes tab |
| header buttons Import from manager, Add device | same, on Device registry only | | as today | kept |
| Access & Identity (page) | Access and identity | `tab=access` | page locked without admin read | renamed |
| > Local identities | > Local identities | `tab=access&section=local-identities` | admin read | section becomes tab |
| > Sessions | > Sessions | `...&section=sessions` | admin read | section becomes tab |
| > Roles & Permissions | > Roles and permissions | `...&section=roles-permissions` | admin read | renamed |
| > LDAP Settings | > LDAP settings | `...&section=ldap-settings` | admin read | renamed in the page list only; the form is untouched |
| Platform (page) | Platform | `tab=platform` | none | kept |
| > System | > System | `tab=platform&section=system` | none | section becomes tab |
| > Scheduled jobs | > Scheduled jobs | `...&section=scheduled-jobs` | none to read | section becomes tab |
| > Notifications (cards: Device names in messages, Remote logging (syslog), Mail relay, Notification types) | > Notifications | `...&section=notifications` | admin read | section becomes tab; cards unchanged |
| > Log collection settings | > Log collection settings | `...&section=system-logs` | admin read | section becomes tab |
| Audit (page) > Audit Logs | Audit log (no tabs) | `tab=audit` | page locked without admin read | renamed; one level removed |
| Product (heading) > Delivery plan | Delivery plan (no tabs) | `tab=product` | page locked without the Admin plane | heading removed |

Not part of Administration, listed so nothing is lost: the HTTPS certificate panel lives on the device page; the
SSH trust panel also appears on the device page and as a notice on Operations. Both stay where they are.

## 5. Behaviour

One model (`shell/navModel.ts`, new) holds the nine entries. The sidebar, the folded icons and the drawer are three
renderings of that one list, filtered by one function. The hand-kept `DRAWER_GROUPS` is deleted.

| Layout | Width | What it shows |
|---|---|---|
| Expanded sidebar | 248 px, viewport 1100 px and wider | Icon and label per top-level entry. A group opens and closes in place by its header (a button with `aria-expanded`). First visit: every group open except Administration. Open/closed is remembered per viewer. The group that holds the open screen is always open. |
| Folded | 56 px, same widths, preference `nx.rail.collapsed` | The nine icons, each with its name as tooltip and accessible name. A link icon navigates. A group icon opens a small menu of its children beside it. The entry set is identical (D-NAV2). |
| Narrow | 56 px icons plus the `Open navigation` button, below 1100 px | Same as folded. The button opens a 300 px drawer that renders the expanded sidebar. A 1000 px viewport keeps 944 px for the screen, the same as today. |

The sidebar is 160 px wider than today's 88 px rail. In return Administration loses its 224 px inner menu, so those
pages gain room. Between 1100 and 1279 px the sidebar starts folded unless the viewer has chosen otherwise.

Administration is the last entry, after a divider. It is no longer pinned to the bottom edge, because an open group
cannot be pinned; the sidebar scrolls on its own when the list is taller than the window.

### Active-state rules

Exactly one entry carries `aria-current="page"` in every layout.

| Open address | Lit entry |
|---|---|
| `screen=overview`, unknown or no screen | Overview |
| `screen=reports` | Reports |
| `screen=inventory` (any device, cluster or tab), `screen=configuration`, `/devices/<id>` | Devices |
| `screen=inventory&view=lifecycle` | Lifecycle and security |
| `screen=compliance`, `/compliance` | Compliance |
| `screen=policy` (any tab) | Policy |
| `screen=trace` (either mode) | Trace |
| `screen=changes` (either view) | Changes |
| `screen=backups`, `/backups` | Backups |
| `screen=operations`, no tab or `tab=ha` | HA and readiness |
| `screen=operations&tab=jobs`, `tab=queue`, `tab=history`, or `job=<id>` without a tab | Jobs |
| `screen=operations&tab=health` | System health |
| `screen=packet_analysis` / `sanitizer` / `diagnostics` / `offline_analysis` | that Tools child |
| `screen=administration`, no tab | Device registry |
| `screen=administration&tab=<page>` or an old tab slug | the page that address resolves to |

A group header is never the current page. When a child is lit, its header is drawn in the stronger text colour; in
the folded state the group's icon carries the highlight and the child is marked inside the small menu. When a tab is
switched on the screen itself, the menu follows (the address helper announces the change).

### Role gating (unchanged)

- An entry with a plane shows only when the session holds that plane, or its screen is the open one.
- A group shows when at least one child shows (the Tools rule today).
- Example in the mockup: a session with Devices, Compliance and Policy but without PacketAnalysis and Sanitizer sees
  Tools with Diagnostics and Offline analysis only.
- Administration and its five children show to every session. A page the session may not use carries a lock and
  opens to the Restricted state. This is the wide-layout behaviour today; see amendment 6 for the drawer.
- Only plane checks and the server's administration-read grant are used; no role token is read
  (`NoRoleConditionalRenderingInFrontendTest` stays green).

## 6. Addresses and redirects

No new redirect is needed. Every address in use today stays the canonical address of the same content:
`?screen=<id>` for all fifteen screen ids, `/devices/<id>`, `/compliance`, `/backups`,
`?screen=inventory&view=lifecycle`, `?screen=operations&tab=ha|jobs|health`, and
`?screen=administration&tab=registry|access|platform|audit|product[&section=<slug>]`.

Existing rewrites in `shell/legacyRoutes.ts` stay as they are and keep working:

- `screen=path_analysis` to Trace; `screen=policy&tab=lookup|offline|offline-analysis|installation`.
- `screen=operations&tab=changes|diagnostics`; `tab=queue|history` (read by Operations itself).
- `screen=administration&tab=job-logs` and `screen=administration&job=<id>` to Operations > Jobs.
- The thirteen old Administration tab slugs (`device-management`, `inventory-exclusions`, `credentials`, `ssh-trust`,
  `local-identities`, `sessions`, `roles-permissions`, `ldap-settings`, `system`, `notifications`, `system-logs`,
  `delivery-plan`, `audit-logs`) to `tab=<page>&section=<slug>`.
- `screen=configuration` to Devices.

`section=<slug>` now selects a tab instead of opening a disclosure; the parameter and its values are the same.

## 7. Decisions on record

Respected without change: Devices is a first-class top-level entry (D-NAV4); Jobs is a child of Operations
(PO-NAV-8); Add device is not a menu entry (D-NAV5); no placeholder (D-NAV6b, the disabled Discovery row goes);
routes preserved (D-NAV8); folding changes density only (D-NAV2); Reports sits directly under Overview (Reports R1
contract); Tools holds the four engineering tools (C4); restricted pages are shown locked (review 2026-11-05).

Amended, each with a proposed line:

| # | Decision | Proposed amendment line |
|---|---|---|
| 1 | UI product review 2026-10-18, roadmap 1.1: the rail prints SEE / VERIFY / TRACE / RECOVER / OPERATE | "Amendment 2026-11-12: the navigation groups by product domain per D-NAV3. The verb words are not printed; the order of entries still follows the product principle." |
| 2 | UI review 2026-11-05, C3: a TRACE section holding Trace and Changes | "Amendment 2026-11-12: Trace and Changes sit with Policy in the group Policy and changes. The two screens are unchanged." |
| 3 | UI review 2026-11-05, C4: Administration is four pages plus a Product heading, chosen inside the screen | "Amendment 2026-11-12: the five Administration pages are the children of the Administration menu group; the Product heading is dropped and Delivery plan is the last child, still locked without the Admin plane. A page's sections are one tab strip." |
| 4 | Post-release review 2026-10-18, R4: Administration pinned to the bottom edge of the rail | "Amendment 2026-11-12: Administration is the last menu entry on every screen for every session; it is not pinned." |
| 5 | `NAVIGATION_INFORMATION_ARCHITECTURE.md` PO-NAV-8 / AC-NAV-1: six roots (Overview, Devices, Configuration, Operations, Compliance, Administration); PO-NAV-2: Recovery not rendered | Already overtaken by later PO decisions (Configuration became a tab of Devices 2026-09-25; Backups, Reports, Trace, Changes, Tools were added). "Amendment 2026-11-12: the root set is the nine entries of NAVIGATION_REDESIGN_2026_11_12.md section 2." |
| 6 | Drawer rule: the four Administration page links need the Admin plane | Conflicts with "shown locked, not hidden". With one model one rule must win. "Amendment 2026-11-12: Administration pages are listed for every session in every layout and locked where the session may not use them." Server access is not affected. See open question 2. |

## 8. Size and risk of implementation

Frontend only. No API, migration, permission, worker or device command. Two pull requests, shipped one after the other.

| PR | Content | Size | Risk |
|---|---|---|---|
| 1. Navigation | New `shell/navModel.ts` (entries, gate filter, active-entry function; pure and unit-tested). `shell/NavigationRail.tsx` rewritten around it (sidebar, folded, drawer). `shell/urlParams.ts` announces address changes so the menu follows tab switches. `InventoryScreen.tsx`: the Lifecycle button and Back button write `view`. Renames: `OperationsScreen.tsx` tab label, `PacketAnalysisScreen.tsx` and `SanitizerScreen.tsx` headings, `shell/tabCoverage.ts`. | about 500 lines of source, 350 of tests | Medium. Every screen is touched visually by the wider sidebar; tables at 1100 to 1279 px are the thing to check (mitigated by the folded default there). |
| 2. Administration | `AdministrationScreen.tsx`: inner side menu removed, heading per page, sections rendered as one tab strip, lock shown on tab. `shell/tabCoverage.ts` lists the per-page tab strips. | about 200 lines of source, 300 of tests | Medium. The unit test file is large (547 lines) and written against disclosures. |

Tests that change:

- `tests/NavigationRail.test.tsx`: rewritten (order, groups, Tools popup, bottom pin, drawer list, fold). The fold
  preference tests keep their meaning.
- `tests/App.test.tsx`: the blocks that read `DESTINATIONS`, `DRAWER_GROUPS` and the disabled leaf (lines 58 to 97).
- `tests/AdministrationScreen.test.tsx`: the "tabs" block and the "restricted reads" block; the panel tests below
  them are unaffected.
- The tab coverage unit test follows `TAB_COVERAGE`.

End-to-end selectors that change:

- `e2e/every-tab.spec.ts`: tab lists `Administration pages` and `Product` are replaced by per-page lists (for example
  `Device registry sections`); the literal `HA & readiness`.
- `e2e/screens.spec.ts` line 97: `tabpanel` named `Device Registry`.
- `e2e/night-batch-1105.spec.ts` line 54: expected heading `Administration` becomes `Platform`.
- `SCREEN_HEADINGS`: `Packet Analysis`, `Data Sanitizer`, and Administration's default heading `Device registry`.
- Selectors that go away: menu `Tools` (wide layout), `data-testid="rail-administration"`, navigation
  `Administration navigation`. Kept: navigation `Primary`, `Primary navigation drawer`, buttons `Open navigation`,
  `Collapse menu`, `Expand menu`.

LDAP settings: `components/settings/DirectorySettingsPanel.tsx` is not edited in either PR. Only its label string in
the page list of `AdministrationScreen.tsx` changes, and the panel is mounted exactly as now. PR 2 is opened after
tonight's TLS fix has shipped and is rebased on it.

Also needed with the ship: the amendment lines of section 7 written into their documents, the usual vitest,
preview and production e2e runs, the architecture tests, and an aiview check of all 21 entries at 1000, 1100, 1280
and 1440 px.

## 9. Product Owner answers (2026-11-12)

Approved as recommended: Q1 Lifecycle and security beside Compliance; Q2 Administration pages listed and locked in every
layout, the narrow drawer included; Q3 one heading style (Packet analysis, Data sanitizer, HA and readiness, Audit log);
the six amendment lines of section 7. The questions as they were put follow.

### 9.1 Questions as asked

1. **Where does Lifecycle and security live?** Proposed: beside Compliance, in Compliance and security, because end
   of support, licences, vulnerabilities and Live Patch are a risk question, not an inventory question. The
   alternative is a Devices group (Devices; Lifecycle and security). Recommended: beside Compliance. The gate stays
   the Devices plane either way.
2. **Administration pages in the narrow drawer for a session without the Admin plane.** Today the drawer hides them
   and the wide screen shows them locked. Recommended: show them locked everywhere (amendment 6); it matches the
   2026-11-05 decision and gives no session any new access.
3. **Renames that touch screen headings and one tab.** `Packet Analysis` to `Packet analysis`, `Data Sanitizer` to
   `Data sanitizer`, `HA & readiness` to `HA and readiness`, `Audit` to `Audit log`. Recommended: yes, so one style
   holds everywhere. If refused, the menu takes the existing headings as they are and the rest is unaffected.

Noted, not proposed here: Operations > System health and Administration > Platform > System both describe the
product's own state. Whether they should be one place is a screen change and outside this regrouping.

## Amendment 2026-11-13 (PO approved, follow-ups F1, F2, F4 and the Platform rename)

From the external effectiveness review of 2026-11-12. Frontend only; no API, permission or device command changes.

| # | Change | Before | After |
|---|---|---|---|
| F1 | Two health subjects, two entries | Operations > System health (the firewalls) and Administration > Platform > System (neXus' own storage and service pods) | Operations > **Device health**: the firewall fleet panel, at the address it always had, `?screen=operations&tab=health`. Operations > **Platform status**: neXus' own storage and service pods, `?screen=operations&tab=platform-status`. |
| F2 | Scheduled jobs | Administration > Platform > Scheduled jobs | Operations > Scheduled jobs, `?screen=operations&tab=schedules`. Every signed-in role reads it; who may switch a family is decided by the server as before. |
| F4 | Rename | Log collection settings | Log collection. The address slug `system-logs` stays. |
| | Rename | Administration > Platform | Administration > **Notifications and logging** (menu label, heading, tab list). The address slug stays `platform`. |

F1 was first approved as one merged tab. Reading the two panels showed they are different subjects (the firewalls'
CPU, restarts and crash files; neXus' own disk use and services), with no fact in common, so the PO decided on the
split the same day.

Not changed: Device management keeps its name (F5); Delivery plan stays the last Administration child (F6).

Resulting structure (this replaces the Operations and Administration rows of section 2 and the Platform rows of section 4):

- Operations: HA and readiness; Jobs; Scheduled jobs; Device health; Platform status. The order is "act on the fleet,
  what ran, what will run, how the firewalls are, how neXus itself is": the two job entries sit together and the two
  health entries sit together, neXus' own last. 23 destinations in total.
- Administration: Device registry; Access and identity; Notifications and logging (Notifications; Log collection);
  Audit log; Delivery plan.

Access, unchanged for every session:

- Platform status carried no lock under Administration (every session could open it and the server decides what it
  returns). It carries none in the menu or on the tab now.
- Notifications and Log collection both need the administration-read grant, as before. Because nothing else is left
  on that page, its menu entry now shows the lock for a session without the grant, like Access and identity and
  Audit log. The page is still listed and opens to the Restricted state.

Why the page slug stays `platform`: a new slug would change two working addresses and add three redirects for no
gain to the reader, who sees the label, not the slug. The Operations tab uses `platform-status`, so the two addresses
cannot be confused.

New redirects (in `shell/legacyRoutes.ts`, tested):

| Old address | Lands on |
|---|---|
| `?screen=administration&tab=platform&section=system` | `?screen=operations&tab=platform-status` |
| `?screen=administration&tab=system` (older slug) | `?screen=operations&tab=platform-status` |
| `?screen=administration&tab=platform&section=scheduled-jobs` | `?screen=operations&tab=schedules` |
| `?screen=operations&tab=health&focus=platform` (the merged form of the first commit) | `?screen=operations&tab=platform-status` |

Unchanged addresses: `?screen=operations&tab=health` (Device health), `?screen=administration&tab=platform` with or
without `section=notifications` or `section=system-logs`, and the older `tab=notifications` and `tab=system-logs`.

Lit entries: `tab=schedules` lights Scheduled jobs, `tab=health` Device health, `tab=platform-status` Platform status.
