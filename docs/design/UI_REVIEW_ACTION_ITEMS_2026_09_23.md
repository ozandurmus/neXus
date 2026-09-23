# UI review — action items for the Product Owner (2026-09-23)

**Status: DRAFT — awaiting Product Owner decisions.** Sources: `UI_VISUAL_REVIEW_2026_09_23_FABLE.md` (27 items,
approved and implemented), `UI_VISUAL_REVIEW_2026_09_23_SOL.md` (independent review of the result -- run with gpt-6-sol and mislabelled "Astra"; the architect review with gpt-6-astra is `UI_VISUAL_REVIEW_2026_09_23_ASTRA.md`),
`UI_VISUAL_REVIEW_2026_09_23_FABLE_ON_ASTRA.md` (Fable's closing review and merged list). Standing authorization
(PO, 2026-09-23 night): visual bugs are fixed and deployed without asking; anything new (feature, logic, refactor)
waits for a decision.

## A. Already fixed and deployed overnight (visual / copy only)

| What | Build |
|---|---|
| Posture tiles overflowed the page (sixth tile off-screen, wall too); tile titles truncated | 5e9e6e1, 1c0a5dd |
| Backups actions clipped (Compare half-visible, Download invisible) — a capability loss | 1c0a5dd and the padding follow-up |
| Compliance "Control families" all in one "null" family → NIST SP 800-53 families; service no longer writes "null" | 5e9e6e1 |
| Admin registry header overlap, wrapped times, "never read" on a device that was read ("hostname not reported") | 5e9e6e1 |
| Delivery plan: deployed commit shown, Refresh not full width | 5e9e6e1 |
| Global search: one hit per setting with device count; wider panel | 5e9e6e1, latest |
| Times in GMT+3 everywhere; UTC on hover and in exports; cron labels show the GMT+3 equivalent | 206da15 |
| Identity table sticky member column; quiet scrollbars (dark mode); wall gives room to failure causes; System "observed at" and "<1m" | latest |
| Copy: "Latest evidence", compliance units, meaning of Backup Now / Snapshot / History ★ / FIRST / CHANGED | latest |

## B. Decisions needed (new logic or behaviour — not started)

| # | Priority (reviewers) | Item | Effort | Recommendation |
|---|---|---|---|---|
| 1 | P0 (both) | **Cluster member differences**: separate member-specific differences (per-interface auto-negotiation / link speed, SNMP, logging targets…) from the rest; label the remainder "unreviewed"; carry the split into the Overview tile. This is backlog `cluster_diff_member_specific_tuning`, already first in the queue. | L | Do first: measure which setting names differ in how many clusters (names and counts only), PO classifies, then apply in browser and Java (parity test). |
| 2 | P0 (both) | **Configuration difference list**: replace the paragraph of 78 links with per-section groups, count per section, expandable two-member value table. | M | Do together with #1. |
| 3 | P0 (both) | **24 h job arithmetic**: Operations says 1018 submitted, Overview 1016; 2 jobs sit in OUTCOME_UNKNOWN/REJECTED and are shown nowhere. Show submitted = completed + failed + outcome unknown + rejected + in flight, and the success-rate denominator; same total on both screens. | S | Do — small, and it is a trust issue. |
| 4 | P1 (Fable) | **Devices "Failed · 2" vs Admin "Last collection failed (enrolled) · 0"**: two different populations (Devices counts drafts too). Define one, label both. | S | Do with #3. |
| 5 | new (found during the check) | **"Import from manager" button does nothing** (no handler) on Administration. | S–M | Either wire it to the existing import flow (the Add device dialog has a discovery mode) or remove it until it exists — never a dead button. |
| 6 | P1 (both) | Backups: first card "19 of 102 targets without archive"; Operations: "Failed" quick chip on Jobs, Job history link lands on FAILED, one-line "HA readiness NOT EVALUATED · 0 of 40" panel. | S | Do. |
| 7 | P1 (Fable) | Admin: rows to 44 px, transport and credential-profile columns (needs those fields on `GET /devices`), masked id on the UNKNOWN-hostname row. Configuration list rows to 44 px. Date pickers to yyyy-mm-dd. | M | Do after #1–#3. |
| 8 | P1 (both) | Delivery plan: human task titles first, keys secondary; define Done vs Automated Validated; "Freshness" beside the %. | S | Do. |
| 9 | P2 (Astra) | Ranked exception register above the tiles; donuts → horizontal bars. | M | Fable disagrees (the headline + tiles are the register; donuts obey the rules). Recommend: only add a link per headline line; keep donuts. |
| 10 | P2 | Dark-mode token tuning (secondary text, borders, focus ring), restricted-page "where to request the role" line, export content verification (cover fields, ORIGIN column). | S–M | Do the export verification early — audit fitness is UNKNOWN until checked. |
| 11 | release gate | A screenshot check per build (the tile-title regression appeared between two builds). | S | Do: capture the fixed screen set after every deploy and compare. |

## C. Already in the backlog from this round

`aiview_masked_readonly_admin` (P1), `m3_design_alignment_review` (P2), `version_advisory_exposure` (P1),
`ui_effectiveness_review_after_refresh` (P1 — this round largely fulfils it; close or narrow after the decisions),
`test_fixture_realistic_names_cleanup` (P2), `cp_platform_facts_empty_on_nine_gateways` (P1),
`cp_backup_free_space_parse_refusal` (P1), `pan_keygen_no_usable_key_under_load` (P2).

## D. Order agreed with the PO for the day

Cluster DIFF control step (B1/B2) → backup for the other vendors → Script Execution → failover (read side first).
