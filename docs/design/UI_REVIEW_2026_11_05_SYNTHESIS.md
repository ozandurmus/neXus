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
