<!-- Argon consultation 2026-11-12T20:58:05Z, model gemini-4-argon-high, effort high; unaltered output -->

**Role:** `REVIEWER` (read-only effectiveness review)

---

### 1. Nine top-level entries and children: operator purpose and question

| Entry / Child | Verdict | One-line reason |
|---|---|---|
| **1. Overview** | **CLEAR** | Single top-level `SEE` summary answering *"Is the estate all right at this moment?"* |
| **2. Reports** | **CLEAR** | Dedicated executive and audit output destination answering *"What do I hand to management or an auditor?"* |
| **3. Devices** | **CLEAR** | Primary inventory and device-state destination (`SEE`), though it also silently absorbs `?screen=configuration`. |
| **4. Compliance and security** | **CLEAR** | Coherent `VERIFY` domain pairing configuration standards with lifecycle, support, and vulnerability risk. |
| ↳ *Compliance* | **CLEAR** | Answers whether firewall configurations meet baseline and regulatory standards. |
| ↳ *Lifecycle and security* | **CLEAR** | Answers whether devices are supported, licensed, and patched (risk posture), though gated by the `Devices` plane rather than `Compliance`. |
| **5. Policy and changes** | **CLEAR** | Groups rulebase posture (`VERIFY`) with path and change investigation (`TRACE`), though its design question bundles three distinct tasks. |
| ↳ *Policy* | **CLEAR** | Answers what rules and objects the firewall policy currently allows. |
| ↳ *Trace* | **CLEAR** | Answers *"Can A reach B?"* via path analysis and policy lookup in one screen. |
| ↳ *Changes* | **CLEAR** | Answers *"What changed?"* across the change feed and policy rule revisions. |
| **6. Backups** | **CLEAR** | Single-purpose `RECOVER` destination answering *"Can we restore a device today?"* |
| **7. Operations** | **UNCLEAR** | Mixes firewall estate runtime operations (`HA and readiness`, `Jobs`) with neXus's own appliance health (`System health`). |
| ↳ *HA and readiness* | **CLEAR** | Answers whether firewall clusters/HA pairs are synchronized and ready to fail over. |
| ↳ *Jobs* | **CLEAR** | Answers what collection or operational jobs are queued, running, or failed. |
| ↳ *System health* | **MISPLACED** | Describes the neXus product's own state alongside firewall HA readiness, overlapping `Administration > Platform > System` ([NAVIGATION_REDESIGN_2026_11_12.md](file:///Users/OzanDur/Codo/nexus-adminia-1112/docs/design/NAVIGATION_REDESIGN_2026_11_12.md#L277-L278)). |
| **8. Tools** | **CLEAR** | Exposes the four ad-hoc/file-in/single-device utilities in place instead of hiding them in a popup (note: its design §2 prompt is a statement, not a question). |
| ↳ *Packet analysis* | **CLEAR** | Single purpose: inspect uploaded packet captures. |
| ↳ *Data sanitizer* | **CLEAR** | Single purpose: scrub sensitive fields from files before sharing. |
| ↳ *Diagnostics* | **CLEAR** | Single purpose: run bounded Class 0 diagnostic reads against a single device. |
| ↳ *Offline analysis* | **UNCLEAR** | The label hides *what* is analyzed (an offline policy/SIEM-export check gated by the `Policy` plane). |
| **9. Administration** | **CLEAR** | Cleanly separated below a divider and closed by default for product and enrollment setup. |
| ↳ *Device registry* | **CLEAR** | Single onboarding home for managed devices, exclusions, credentials, and SSH host trust. |
| ↳ *Access and identity* | **CLEAR** | Single home for local users, live sessions, RBAC roles/permissions, and LDAP settings. |
| ↳ *Platform* | **UNCLEAR** | Catch-all mixing runtime health (`System`), collection cadence (`Scheduled jobs`), alerting (`Notifications`), and log settings. |
| ↳ *Audit log* | **CLEAR** | Single-purpose, tabless record of actions performed in neXus. |
| ↳ *Delivery plan* | **MISPLACED** | Product build/roadmap tracking ("For the Product Owner and engineers") is engineering governance, not bank firewall or platform administration. |

---

### 2. Reachability, duplication, and hidden destinations

1. **In-screen route state in `AdministrationScreen` is initialized only once on mount:**
   In [AdministrationScreen.tsx](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/screens/AdministrationScreen.tsx#L163-L165), `route` and `tab` are stored via `useState(() => resolveAdminRoute(urlParam("tab"), urlParam("section")))` without subscribing to `useLocationSearch()`. In contrast, [NavigationRail.tsx](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/shell/NavigationRail.tsx#L150-L151) reacts live to `useLocationSearch()`. Whether clicking between Administration children remounts `AdministrationScreen` via a full page load or parent `key` is **UNKNOWN** from the supplied files, but browser Back/Forward (`popstate`) between `?screen=administration&tab=...&section=...` states will update the sidebar highlight without updating `AdministrationScreen` unless the parent shell forces a remount.
2. **Duplicated / split concepts across two places:**
   - **neXus health in two menus:** `Operations > System health` (`?screen=operations&tab=health`) and `Administration > Platform > System` (`?screen=administration&tab=platform&section=system`) both describe the product's own state.
   - **Jobs split across two menus:** `Operations > Jobs` (live/historical runs) vs `Administration > Platform > Scheduled jobs` (schedules).
   - **Lifecycle view reachable two ways:** `Compliance and security > Lifecycle and security` (`?screen=inventory&view=lifecycle`) and the in-screen button inside `Devices`.
   - **Device trust split:** Fleet-wide `SSH trust` is a tab in `Administration > Device registry` (and on device pages / Operations notices), whereas `HttpsCertificatePanel` is reachable only on an individual device page (`/devices/<id>`), not in `Device registry`.
3. **Reachable only by address or in-screen controls (by design):**
   - `?screen=configuration` has no menu entry; it resolves to `Devices` ([navModel.ts](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/shell/navModel.ts#L112-L113)).
   - Trace modes, Changes views, Policy tabs, Reports tabs, and Jobs sub-tabs (`tab=queue|history`) remain in-screen tabs.
4. **Hidden on first visit at medium widths:**
   - Between `1100 px` and `1279 px` ([NavigationRail.tsx](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/shell/NavigationRail.tsx#L41-L42)), the sidebar starts folded to 9 unlabeled icons (`collapsed = foldChoice ?? !roomy`), and `Administration` starts closed (`startsClosed: true`). Clicking the `Administration` group header in the expanded sidebar toggles the list rather than navigating directly to `?screen=administration`.

---

### 3. Administration: page purpose and tab splits

- **Device registry** (`Device management`, `Inventory exclusions`, `Credentials`, `SSH trust`): **One clear purpose** (device onboarding and collection trust) and the right four tabs. However, the header buttons `Import from manager` and `Add device` ([AdministrationScreen.tsx](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/screens/AdministrationScreen.tsx#L181-L186)) render on all four tabs of `Device registry`, even when viewing `Credentials` or `SSH trust`.
- **Access and identity** (`Local identities`, `Sessions`, `Roles and permissions`, `LDAP settings`): **One clear purpose** and the right four-tab split.
- **Platform** (`System`, `Scheduled jobs`, `Notifications`, `Log collection settings`): **Does not have a single cohesive purpose.**
  - `System` belongs with (or should replace) `Operations > System health`.
  - `Scheduled jobs` is tightly coupled to `Operations > Jobs`.
  - `Notifications` contains a `Remote logging (syslog)` card ([NAVIGATION_REDESIGN_2026_11_12.md](file:///Users/OzanDur/Codo/nexus-adminia-1112/docs/design/NAVIGATION_REDESIGN_2026_11_12.md#L122)) that belongs conceptually with `Log collection settings` (`section=system-logs`) rather than mail/alert notifications.
- **Audit log** (no tabs): **One clear purpose**; removing the single-tab wrapper is an improvement.
- **Delivery plan** (no tabs): **Single purpose, but misplaced in Administration.** Dropping the `Product` heading makes an internal engineering roadmap (`ProjectPlanPanel`) appear as a peer of `Device registry` and `Audit log`.

---

### 4. Naming: labels a bank network-security operator could misread

1. **`Operations > System health` vs `Platform > System`:** Next to `HA and readiness`, a firewall operator will read `System health` as **firewall gateway hardware/OS health** (CPU, memory, disk, PSU), not neXus server health; meanwhile `System` under `Platform` is too generic to tell them apart.
2. **`Tools > Offline analysis`:** Does not indicate *what* is analyzed offline (firewall config file, backup archive, or SIEM policy export).
3. **`Platform > Log collection settings` (`section=system-logs`):** In a Check Point / Palo Alto team, "Log collection" implies firewall traffic/threat log ingestion (Log Exporter / Panorama), whereas the slug `system-logs` (`SystemLogSettingsPanel`) indicates platform/system log settings.
4. **`Administration > Delivery plan`:** Without the former `Product` heading, "Delivery plan" inside `Administration` can be misread as change-window or report delivery scheduling rather than the software build roadmap.
5. **`Device registry > Device management`:** Easily confused with day-to-day device operations under top-level `Devices`; *Enrolled devices* or *Registered devices* is clearer.
6. **`Compliance and security > Lifecycle and security`:** Repeats "and security" from its parent group instead of naming what distinguishes it (*Software lifecycle and vulnerabilities* or *Support and lifecycle*).

---

### 5. Role gating and locks as built

- **Plane gates:** **Unchanged** from `c1fdc4d6^` across every entry (`Devices`, `Compliance`, `Policy`, `PacketAnalysis`, `Sanitizer`).
- **Lost entries:** **None.** Every session retains all functional entries it previously had (only the permanently disabled `Discovery` placeholder in the narrow drawer was removed). Sessions on the wide layout *gain* direct links to `Lifecycle and security`, `Jobs`, `System health`, and the five `Administration` pages; sessions without the `Admin` plane on the narrow drawer *gain* the five `Administration` pages (shown locked where restricted, per Amendment 6).
- **Cases where a session sees an entry it cannot use without a lock:**
  1. **Partially restricted Administration pages (`Device registry`, `Platform`):** Listed without a lock in the left menu ([navModel.ts](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/shell/navModel.ts#L83-L85)) because their first two tabs are open to all signed-in roles; the restricted tabs (`Credentials`, `Notifications`, `Log collection settings`) properly display lock icons inside the page tab strip ([AdministrationScreen.tsx](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/screens/AdministrationScreen.tsx#L196-L198)).
  2. **Direct URL navigation to a ungranted plane ("you are here" rule):** In [navModel.ts](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/shell/navModel.ts#L139-L142), `leafShown` returns `true` when `leaf.screen === activeScreen` even if the session lacks `leaf.requiredPlane`. Because `leaf.lock` is only set on Administration pages, a session without `Compliance`, `Policy`, `PacketAnalysis`, `Sanitizer`, or `Devices` that opens that URL directly sees the menu entry lit **without a lock icon** (and for `screen === "inventory"`, *both* `Devices` and `Lifecycle and security` appear unlocked because both share `screen: "inventory"`).
  3. **Action triggers on readable tabs:** `Import from manager` and `Add device` render on `Device registry` for all sessions ([AdministrationScreen.tsx](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/screens/AdministrationScreen.tsx#L180-L186)); whether those triggers internally lock or hide for read-only sessions is **UNKNOWN** from the supplied files.

---

### 6. Top 5 follow-ups (priority order)

1. **Make `AdministrationScreen` reactive to URL search changes (`tab` and `section`)** — **Impact: High | Effort: S**
   Derive the active page and section from `useLocationSearch()` (or synchronize state on search updates) in [AdministrationScreen.tsx](file:///Users/OzanDur/Codo/nexus-adminia-1112/ui2/frontend/src/screens/AdministrationScreen.tsx#L163-L165) so browser Back/Forward and in-app link handling never desynchronize the rendered Administration page/tab from the lit sidebar item (shell remount behaviour in `App.tsx` is **UNKNOWN** from the supplied files).
2. **Resolve the `Operations > System health` vs `Administration > Platform > System` and `Jobs` vs `Scheduled jobs` split** — **Impact: High | Effort: M**
   Consolidate or explicitly cross-link platform runtime status and job scheduling so operators do not confuse neXus health with firewall health or hunt across two top-level groups to manage collection jobs.
3. **Rename ambiguous labels (`Offline analysis`, `Log collection settings`, `Device management`, `Lifecycle and security`)** — **Impact: Medium | Effort: S**
   Use operator-explicit names (*Offline policy analysis*, *Platform log settings*, *Registered devices*, *Lifecycle and vulnerabilities*) so bank firewall engineers can predict each screen before clicking.
4. **Scope `Import from manager` / `Add device` to the `Device management` tab and visually separate `Delivery plan`** — **Impact: Medium | Effort: S**
   Render enrollment action buttons only when `tab === 0` (`device-management`) on `Device registry`, and either restore a subtle separator/caption for `Delivery plan` or move build-roadmap tracking out of operator Administration.
5. **Show a lock badge when `leafShown` exposes an ungranted plane-gated leaf via `leaf.screen === activeScreen`, and tighten the `inventory` match** — **Impact: Low–Medium | Effort: S**
   Match `activeLeafId` instead of `leaf.screen === activeScreen` so visiting `?screen=inventory` without the `Devices` plane does not expose both `Devices` and `Lifecycle and security`, and render `<LockMark />` whenever an entry is shown solely because its screen is active.
