# UI review 2026-11-05: synthesis for PO decision

**Status: DRAFT -- awaiting PO decisions (morning 2026-11-05).**

## Inputs

- `UI_REVIEW_2026_11_05_ARGON.md`: Argon, all 42 aiview screenshots, 26 recommendations (R-01..R-26).
- `UI_REVIEW_2026_11_05_FABLE.md`: Fable, second opinion on the contested items and 16 representative screenshots, plus 8 additions (F-1..F-8). Fable answered in Turkish; the output is kept unaltered.

The engineer chose the contested items; the screenshots are the masked aiview tour of 2026-11-05.

## Agreement

Fable agrees with Argon's diagnosis: the problem is data priority, not data volume. Fable disagrees on three points, and the engineer sides with Fable on all three:

1. **Delivery plan stays.** It moves under its own "Product" heading, visible to PO and engineer roles only (not R-09).
2. **aiview keeps seeing every page.** Restricted pages render as locked rather than hidden, because aiview is the inspection persona. Write buttons are visibly locked (R-10, partly).
3. **Backup deviations stay on Backups.** The 21-row list becomes a drawer, and simultaneous identical events are grouped into one line (R-03, amended).

## A. Bug-class fixes (no design decision needed; engineer proceeds)

| ID | Item | Source |
|---|---|---|
| A1 | Stuck loaders: System health, Policy > Changes, Change feed | R-02 (Argon lane running) |
| A2 | "Unknown device" job targets; raw UUID failure text made readable | R-12 (Argon lane running) |
| A3 | Dead clicks (M1/M2, member cards); stale readiness shown green | R-04 |
| A4 | Right-edge clipping and vendor column collision (Device management, Identity) | R-05, R-06 (clipping part) |
| A5 | HA readiness chip counts contradict the 45-cluster heading | R-13 |
| A6 | Path diagram masks raw addresses client-side for raw-view roles: trust the server projection | follow-up |

## B. Quick design wins (small effort; PO approval asked as one batch)

| ID | Item | Source |
|---|---|---|
| B1 | Stale-evidence banner on Overview and Compliance, plus one product-wide freshness scale (one threshold set, every pill follows it) | R-01, F-3 |
| B2 | Backups: table above the fold, deviations in a drawer, grouped simultaneous events, "47 h ago" stale badge on the scheduled backup | R-03 amended |
| B3 | KPI cards and header buttons only on the tab they belong to | R-11 |
| B4 | Compliance headline: "N% of evidenced controls pass, M% have no evidence" instead of counting unevidenced as failed; primary framework order **(PO: Financial Baseline first?)** | F-1 |
| B5 | Cluster: merge Members and Identity; "differ" shown amber; Configuration opens on differences only when there are differences; compact Logs header | R-06, R-07, R-17, F-4 |
| B6 | Job Logs becomes a redirect to Operations > Jobs; Failed defaults to 7 days with the top 5 reasons | R-08, F-7 |
| B7 | Top-bar role label "Compliance Admin" for aiview corrected; build stamp and time zone move to the user menu or footer | R-10, F-6 |

## C. Larger changes (PO decision each)

| ID | Item | Effort | Source |
|---|---|---|---|
| C1 | Executive home replacing Overview: verdict word (HEALTHY / AT RISK / UNKNOWN) and freshness sentence; four pillars; at most 5 "needs attention" lines with age; framework bars and the 24 h pulse below the fold | M | R-15 + Fable |
| C2 | Policy from 7 tabs to 4: Rules & Containers with an installation column, Objects with cross-container search, Hygiene with a fleet summary when no container is selected, Changes. Keep a "search in this policy" link | M | R-16 |
| C3 | TRACE section: Path and Lookup as one "Trace" entry with two modes; one unified "Changes" entry (Change feed + Policy changes) | M | R-20, Fable on 2.2 |
| C4 | Information architecture: Diagnostics and Offline analysis move to Tools; Administration goes from 14 pages to 4 groups plus Product (Delivery plan); restricted pages shown locked | L | R-21, R-22 amended |
| C5 | Devices: ranked bars instead of donuts, covering all vendors, with a version verdict line; tree children collapsed by default; "problems only" filter; the header pill row reused in the tree and the HA table | M | R-18, F-4, F-5 |
| C6 | Executive deliverable: one-click weekly summary (PDF or e-mail) | M-L | F-2 (new feature) |
| C7 | Plain vocabulary on executive-level screens ("data freshness", "backup coverage", "policies") | S-M | F-8 |

## Engineer's recommended order

1. A (now).
2. B as one batch (one Argon lane each, shipped one at a time).
3. C1 and C2.
4. C3 and C4 together, since they share the routing and e2e selector changes.
5. C5.
6. C6 and C7 after the executive home settles.

## Implementation record: C2, C3, C4, B6, B7 (branch `cloud/ia-restructure-1105`)

PO approved C2, C3, C4, B6 and B7 (cloud brief `docs/cloud-briefs/ia.md`). Frontend only; no API, schema or device command changed. Automated validation only: the screens are not yet checked in the real environment under the `aiview` persona.

| ID | What shipped | Where |
|---|---|---|
| C2 | Policy has four tabs: Rules & Containers (Installation is two columns of the container table, targets and status, and an on-demand section once a container is open), Objects (search across containers, a bounded client-side fan-out over the existing per-container read), Hygiene (fleet summary before a container is open; finding counts are **Not evaluated** there, never 0), Changes. "Search in this policy" opens Trace in lookup mode scoped to the policy. | `PolicyScreen.tsx`, `PolicyObjectSearch.tsx`, `PolicyHygieneFleet.tsx` |
| C3 | Rail section TRACE: **Trace** (modes Topology path and Policy rule lookup; advanced fields collapsed) and **Changes** (views Timeline and Policy rule revisions). | `TraceScreen.tsx`, `ChangesScreen.tsx`, `NavigationRail.tsx` |
| C4 | Diagnostics and Offline analysis are Tools destinations. Administration is four pages (Device Registry, Access & Identity, Platform, Audit) plus a Product heading holding Delivery plan; each former page is a section that mounts only while open. A section the session may not read keeps its place and opens to the Restricted state; it is never hidden. | `AdministrationScreen.tsx`, `DiagnosticsScreen.tsx`, `OfflineAnalysisScreen.tsx` |
| B6 | Job Logs is gone; its address lands on Operations > Jobs. The Failed pill opens on the last 7 days (a visible "From" window, "Show all time" drops it) with the top 5 failure reasons. | `JobLogsPanel.tsx`, `legacyRoutes.ts` |
| B7 | The `aiview` persona is worded "aiview · read-only", not "Compliance Admin". The build stamp and the time-zone note moved into the account menu. | `roleLabel.ts`, `TopAppBar.tsx` |

Old addresses keep working through one pure rewrite (`shell/legacyRoutes.ts`, tested): `path_analysis`, `policy&tab=lookup|offline|installation`, `operations&tab=changes|diagnostics`, `administration&tab=job-logs` and every former Administration tab slug.

Interpretations and open points for the PO:

1. **Product heading visibility.** The frontend may not branch on a role token (`NoRoleConditionalRenderingInFrontendTest`), so "visible to PO and engineer roles" is the presentation-only Admin-plane check the other role affordances use. Every session sees the Product heading; a session without the Admin plane gets the Restricted panel instead of the plan. The server still allows `GET /project-plan` to any signed-in session. A dedicated plane for the delivery plan would make this exact.
2. **Cross-container object search** is a client-side fan-out (3 at a time, at most 100 containers, 5 matches shown per container); a container that cannot be read is counted as not searched, never as "no match". A server endpoint would replace it without changing the screen's words.
3. **Hygiene fleet summary** states only what the loaded catalog proves (containers, packages, stale snapshots, containers without a gateway). Per-container finding counts need a server aggregate and are not shown.
4. **Failure reasons** are counted over the newest 200 failed jobs of the window and the card says so when there are more.
5. **Design previews** (`?preview=`) of the new screens reuse the preview of the screen their content came from; the dedicated previews were not redrawn.
6. Not done here: `project/*.json` and `CURRENT_STATE.md` were not updated (the delivery queue tool is not part of this cloud task); the Product Owner or the next engineering session records the build there.
