# PO decision record - 2026-11-12: navigation and Administration redesign

**Status: RATIFIED - PO DECISION (2026-11-12 evening, every recommendation approved, relayed by the PO session).** This
record amends [NAVIGATION_INFORMATION_ARCHITECTURE.md](NAVIGATION_INFORMATION_ARCHITECTURE.md) (root set, section 4 and
`AC-NAV-1`) and the information-architecture items C3 and C4 of
[UI_REVIEW_2026_11_05_SYNTHESIS.md](UI_REVIEW_2026_11_05_SYNTHESIS.md). The design it approves is
[NAVIGATION_REDESIGN_2026_11_12.md](NAVIGATION_REDESIGN_2026_11_12.md). It approves frontend presentation only: no API,
schema, permission, server-side authorization or device command changes.

## 1. Why

The PO found the left navigation tangled: verb groups (SEE, VERIFY, TRACE, RECOVER, OPERATE) with one or two entries
each, a wide rail and a narrow drawer kept as two lists that disagreed, four screens hidden in a Tools popup, sub-views
without an entry on the wide layout, and inconsistent names. Administration was four levels deep (rail, an inner side
menu, collapsible sections, cards).

## 2. PO answers

| # | Question (design doc, section 9) | PO answer |
| --- | --- | --- |
| Q1 | Where does Lifecycle and security live? | Beside Compliance, in the group Compliance and security. Its gate stays the Devices plane. |
| Q2 | Administration pages in the narrow drawer for a session without the Admin plane? | Listed and locked everywhere, in every layout. No session gains access; the server decides what a page shows. |
| Q3 | One heading style? | Yes: Packet analysis, Data sanitizer, HA and readiness, Audit log. |
| - | The six amendment lines of design section 7 | Approved as written (section 3 below). |

## 3. Amendments

1. UI product review 2026-10-18, roadmap 1.1 (verb group labels). The navigation groups by product domain per D-NAV3.
   The verb words are not printed; the order of entries still follows the product principle.
2. UI review 2026-11-05, C3 (a TRACE section). Trace and Changes sit with Policy in the group Policy and changes. The
   two screens are unchanged.
3. UI review 2026-11-05, C4 (Administration as four pages plus a Product heading chosen inside the screen). The five
   Administration pages are the children of the Administration menu group; the Product heading is dropped and Delivery
   plan is the last child, still locked without the Admin plane. A page's sections are one tab strip.
4. Post-release review 2026-10-18, R4 (Administration pinned to the bottom edge of the rail). Administration is the last
   menu entry on every screen for every session; it is not pinned.
5. `NAVIGATION_INFORMATION_ARCHITECTURE.md` PO-NAV-8 / `AC-NAV-1` (six roots). Already overtaken by later PO decisions;
   the root set is the nine entries of design section 2: Overview, Reports, Devices, Compliance and security, Policy and
   changes, Backups, Operations, Tools, Administration.
6. The drawer rule that hid the Administration page links without the Admin plane. Administration pages are listed for
   every session in every layout and locked where the session may not use them. Server access is not affected.

## 4. What stays

- Devices is a first-class entry (D-NAV4); Jobs is a child of Operations (PO-NAV-8); Add device is not a menu entry
  (D-NAV5); no placeholder entries (D-NAV6b); folding the menu changes density only (D-NAV2).
- Every existing address keeps working (D-NAV8), including every rewrite in `ui2/frontend/src/shell/legacyRoutes.ts`.
- Plane gates are unchanged; the frontend reads no role token.
- The LDAP settings form (`DirectorySettingsPanel.tsx`) is not edited; only its menu label changes.

## 5. Delivery

Two frontend pull requests, shipped in order: PR 1 navigation (branch `ui/nav-ia-1112`), PR 2 Administration, stacked on
PR 1 and on main after the LDAP TLS fix. Design and implementation by Fable, per the PO's standing rule for UI lanes.
