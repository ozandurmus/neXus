# Navigation Information Architecture — left vertical product navigation

## Status

**FROZEN — 2026-09-05** (`NAV.1`). Movement: `UI`. This document is the
single owner of neXus' *navigation shape*: how the two delivery surfaces
(the exported static report `templates/index.html` and the Operator Console
`templates/console.html`) expose product domains, how a navigation entry
earns the right to exist, and how the sidebar and the device-detail tab strip
populate progressively as later builds ship real backends.

- **Authorizes:** the shell/CSS/JS navigation change described in §6, and
  nothing else. No payload builder, no collector, no vendor semantic, no
  network command, no credential path, no storage/schema change, no new
  action class, no authorization/RBAC implementation.
- **Preserves unchanged:** `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`
  (`CON.0`) §3/§4/§6/§7, `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`
  §13 (the console *is* the `CON.x` console; one UI source tree),
  `utils/action_taxonomy.py`, every payload builder's output, every existing
  route (`#overview` … `#project-plan`) and every render-harness contract
  (`.module-nav-item[data-module]` → `[data-module-panel]`).
- **Does not reopen:** `pcp_console_registry_write_gate`,
  `pcp_storage_engine`, `inventory_exclusions_management_ui_backend`, or any
  `OP.2` / `CLASS 2` question.

---

## 1. Problem

The two shells shipped a single horizontal `<nav class="module-nav">` strip in
the topbar with **one root button per module**: Overview, Network Inventory,
Configuration, Compliance, Discovery, Failover, Exclusions, Project Plan.

Three concrete problems:

1. **The strip is a flat action list, not an information architecture.** Eight
   sibling roots at the same rank say nothing about which of them are product
   domains (Configuration) and which are one view inside a domain
   (Exclusions). It also has no room left: below 900 px the existing CSS
   already collapses four of the eight labels to abbreviations and lets the
   other four fall off the readable end.
2. **It cannot absorb the Product Control Plane.** `PCP.4` adds a per-device
   experience (Overview · Inventory · Configuration · Backups · HA/Failover ·
   Jobs · Diagnostics) and `PCP.5` a typed job plane. Under one-root-per-view
   each of those becomes another top-level button, and device-scoped functions
   end up duplicated as unrelated roots.
3. **Nothing gates a navigation entry on a real backend.** An entry exists
   because someone wrote a `<button>`. There is no rule that stops a shell
   from advertising a capability it cannot serve.

## 2. Decisions

**D-NAV1 — Direction.** Primary product navigation is a **left vertical rail**,
not a horizontal topbar strip. The topbar keeps brand identity and
module-context controls (inventory/configuration filters, refresh, theme)
only.

**D-NAV2 — Collapsible, and collapse is presentation only.** The rail is
collapsible to an icon rail and expandable back, persisted per browser in
`localStorage` (`securityexpert-nav-collapsed`). Groups collapse
independently (`securityexpert-nav-groups`). **Collapsing changes presentation
density only** — it never removes an entry, never changes availability, and
never changes what the operator is permitted to do. The collapsed rail
renders exactly the same entry set as the expanded rail.

**D-NAV3 — Group by stable product domain, not by action.** Roots are product
domains that survive several builds. A view is a *child* of its domain. A
domain with exactly one shipped view today renders as a direct root link
(Configuration, Compliance) rather than a group with one child; it becomes a
group by gaining children, with no change to its route.

**D-NAV4 — Devices is a first-class root.** The device experience is a domain
of its own, never a sub-view of Configuration.

**D-NAV5 — "Add Device" is never a root.** Enrollment is an *action inside*
the Devices / Device Management surface, registered in the model as a
contextual action of the `devices` domain — never a navigation root, and
never rendered while its backend contract does not exist (§4).

**D-NAV6 — Availability rule (the anti-placeholder law).** A navigation entry
or a device tab is rendered **iff the shell it is running in actually ships
the surface it points at** — i.e. `[data-module-panel="<module>"]` (sidebar)
or the tab's panel element (device tabs) exists in the DOM. An entry whose
backend/surface does not exist is **omitted entirely**; it is never rendered
disabled, greyed, "coming soon", or as any other visual placeholder. A
capability whose backend contract *does* exist but is currently refused
reports its own honest state from that backend (the `CON.2` job-type
`BLOCKED` pill is the existing precedent) — that is a backend-sourced state,
not a navigation placeholder.

**D-NAV7 — Progressive population.** Shipping a capability into the
navigation is therefore exactly two mechanical steps: (1) add its
`[data-module-panel]` section to the shell that can serve it; (2) add its
entry to the model in `static/navigation_ui.js`. Nothing else changes — no
route table, no `switchModule` list, no CSS. A shell that does not ship the
panel simply does not show the entry. This is what lets the console expose a
surface the action-free exported report must not (§5), and what lets `PCP.4`
add device tabs without touching root navigation.

**D-NAV8 — Routes are preserved, and derived.** Existing hash routes
(`#overview`, `#inventory`, `#configuration`, `#compliance`, `#discovery`,
`#failover`, `#exclusions`, `#project-plan`) and the
`securityexpert-module` localStorage key keep working byte-for-byte. The set
of valid module ids is now *derived* from the rendered navigation instead of
being hard-coded twice — which also fixes a pre-existing inconsistency where
`savedModule()` honoured only five of the eight modules from the URL hash
while honouring all eight from localStorage.

**D-NAV9 — Authorization-aware, not authorization-implementing.** The model
carries the seam and nothing else: `navigationAuthorizationContext()` returns
`{"model": "none"}` with the reason. No role, permission, scope or claim is
read, simulated, stubbed or invented anywhere in the navigation path; there
is no "hidden because you lack access" state, because there is no
authorization model to be hidden by. When `DEPLOY.1A` ships the OIDC/RBAC
boundary, the availability predicate gains one additional conjunct
(authorization) and this document gets its `NAV.2` amendment. Until then,
**availability is a shell/backend fact only** — a deliberate refusal to
pre-simulate RBAC (`AGENTS.md` UNKNOWN/fail-closed law: absence of an
authorization model is not a permissive authorization model; it is *no*
model, and nothing in the UI may imply otherwise).

## 3. The information architecture (evaluated, not assumed)

The brief named seven candidate domains: Overview, Devices, Configuration,
Operations, Jobs, Compliance, Administration. Each was evaluated against what
the repository can actually serve today.

| Root | Kind | Children shipped today | Evaluation |
| --- | --- | --- | --- |
| **Overview** | link → `overview` | — | Kept as a root. It is the landing surface, not a domain with views. |
| **Devices** | group | Inventory (`inventory`), Discovery (`discovery`) | Kept, first-class (D-NAV4). Inventory is the device workspace (device list → interfaces/routing); Discovery is management-led lifecycle + observed collection capability over the same objects. Both answer "what devices exist and what do we know about them", so they are one domain, not two roots. |
| **Configuration** | link → `configuration` | (device tabs, §4) | Kept as a root. It is a genuine product plane that `AGENTS.md` forbids collapsing into Inventory. Its *device-scoped* views (Configuration, Alignment, Policy & Objects, History, Evidence, Backup) are tabs inside the device experience, never roots. |
| **Operations** | group | HA & readiness (`failover`), Jobs (`jobs`, console shell only) | Kept. "What is running / what can be run against the fleet." |
| **Jobs** | *not a root* | — | **Evaluated and rejected as a root for this movement.** A Jobs root today would carry exactly one surface — the `CON.2` console job list, which the exported report must never carry (`CON.0` §6: the report stays action-free). A root whose only member is absent from one of the two shells is a worse fact than a child under Operations. It is registered as a child of Operations and promotes to a root by moving one line, once `PCP.5`'s typed job plane gives it definitions, runs and schedules to own. |
| **Compliance** | link → `compliance` | — | Kept as a root. Its own posture/framework plane; no second view exists. |
| **Administration** | group | Inventory exclusions (`exclusions`), Project plan (`project-plan`) | Kept. Exclusions is *local policy applied before polling* — an administrative policy surface, not a device view; Project Plan is delivery/product metadata. Neither is fleet evidence, so neither belongs under Devices. |

Result: **six roots** — Overview, Devices, Configuration, Operations,
Compliance, Administration — over the same eight (report) / nine (console)
module panels that existed before. No module was removed, renamed out of
existence, or newly invented.

### Device-scoped functions

`Inventory · Configuration · Backups · HA/Readiness · Jobs · Diagnostics` are
device-scoped functions. They are **not** navigation roots. Today they live
as: the Configuration workspace's device tab strip (Overview · Configuration
· Alignment · Policy & Objects · History · Evidence · Backup), the Inventory
workspace's device tabs (Interfaces · Routing), the fleet-level `failover`
readiness view, and the console job list. `PCP.4` consolidates them into one
per-device tab strip; when it does, it adds tabs under D-NAV6/D-NAV7 and adds
**no** root. `OP.2.D`'s controlled-failover flow is expected on that device
HA tab (`PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §16/§20) — again, not a root.

## 4. Contextual actions (where "Add Device" lives)

Contextual actions are declared in the same model as navigation, against a
domain, and are subject to the same availability rule.

| Action | Domain | Surface | Backend contract | Rendered today |
| --- | --- | --- | --- | --- |
| Add device (enroll) | `devices` | Devices / Device Management surface toolbar | **does not exist for any browser surface.** Enrollment is CLI-only (`PCP.1` `--registry-enroll`); whether a registry write may ever originate in the browser is the open `pcp_console_registry_write_gate` decision, and the `inventory_exclusions_management_ui_backend` precedent says the answer waits for `DEPLOY.1A`'s authorization boundary | **No** — omitted entirely, per D-NAV5 + D-NAV6 |

`navigationContextualActions("devices")` therefore returns `[]` in both
shells today, and a test asserts that no shell, in any state, renders an
enrollment affordance. The declaration exists so the *location* of the future
action is decided (and reviewable) without a placeholder being drawn.

## 5. Surface parity

`CON.0` §6 payload parity is unchanged: both shells run the identical
composed script and the identical model. They differ only in **which panels
the shell ships**, which is precisely D-NAV6's input:

| Module | Report (`index.html`) | Console (`console.html`) | Why |
| --- | --- | --- | --- |
| overview, inventory, configuration, compliance, discovery, failover, exclusions, project-plan | yes | yes | evidence projections; identical on both |
| `jobs` | **no** | yes | the `CON.2` job engine (`/api/job-types`, `/api/jobs`, `/api/jobs/{id}/events`) exists only behind the authenticated loopback console. The exported report is action-free by contract, so it ships no jobs panel and, by D-NAV6, shows no Jobs entry — not a disabled one |

The console's job surface moves out of the Discovery module (where it was an
unrelated card) into its own `jobs` panel under Operations. No job type, no
route, no submission path and no class boundary changes:
`static/console_actions.js` is untouched and still no-ops when its containers
are absent.

## 6. Implementation surface (exhaustive)

| File | Change |
| --- | --- |
| `static/navigation_ui.js` | **new** — the navigation model, the availability rule, the authorization seam, the rail renderer + delegated dispatch, the device-tab reconciler |
| `static/app_bootstrap.js` | route/module-id derivation from the model; `jobs` render dispatch; nav click binding delegated to `navigation_ui.js` |
| `templates/index.html` | topbar `.module-nav` removed; `.app-body` shell (rail + canvas) wraps the existing, otherwise unmodified module sections |
| `templates/console.html` | same shell change; the console job card becomes the `jobs` module panel |
| `static/style.css` | left-rail styles; the horizontal `.module-nav` rules and their per-id `::before` abbreviation hacks removed |
| `utils/html_export.py` | `navigation_ui.js` added to `SCRIPT_MODULE_FILENAMES` (position 2 — after `app_core.js`, before every feature module) |
| `tests/test_navigation_information_architecture.py` | **new** — AC-1…AC-12 |

No Python behavior other than the module list changes. No payload field
changes, so `tests/fixtures/uitest/` needs no update
(`AGENTS.md` "Project-state update rule" trigger not met).

## 7. Acceptance criteria

| id | Criterion |
| --- | --- |
| AC-1 | Neither shell contains a horizontal `<nav class="module-nav">`; both contain exactly one `[data-primary-nav]` rail inside `.app-body`, before the module canvas. |
| AC-2 | The rail renders six roots in the §3 order; every rendered leaf is a `.module-nav-item[data-module]` whose `[data-module-panel]` exists in that shell. |
| AC-3 | No rendered entry, in either shell, points at a module panel the shell does not ship (anti-placeholder, D-NAV6). |
| AC-4 | "Add Device"/enrollment appears in no root, no child, and no rendered contextual action in either shell; `navigationContextualActions("devices") == []`. |
| AC-5 | Collapsing the rail changes no entry: the rendered `data-module` set is identical collapsed and expanded, and the collapse state is persisted, not derived from authorization. |
| AC-6 | Every pre-existing route still resolves: `#overview`, `#inventory`, `#configuration`, `#compliance`, `#discovery`, `#failover`, `#exclusions`, `#project-plan` each activate their panel; unknown hashes fall back to `overview`. |
| AC-7 | The render-harness contract holds unchanged: clicking every `.module-nav-item` activates its `[data-module-panel]` and the button itself, with zero console errors, in both harnesses. |
| AC-8 | The report shell ships no `jobs` panel and renders no Jobs entry; the console shell ships one and renders it under Operations. |
| AC-9 | Device-scoped functions appear as device tabs, not roots: no root's `data-module` is one of the device-tab ids, and a device tab whose panel is missing is removed rather than left dead. |
| AC-10 | No authorization simulation: `navigationAuthorizationContext().model == "none"`, and no role/permission/scope/claim identifier gates any navigation decision. |
| AC-11 | Composition order holds — `navigation_ui.js` references no identifier a later module owns (existing `tests/test_frontend_module_composition.py` gate). |
| AC-12 | The exported report stays action-free: no submit/mutate affordance is introduced by the navigation change. |

## 8. Out of scope (explicit)

`PCP.4`'s per-device tab consolidation; any registry write from a browser;
any RBAC/OIDC behavior; `PCP.5` job definitions/schedules; a second console;
any payload builder change; any change to what any module renders inside its
own panel.
