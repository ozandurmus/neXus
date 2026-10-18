Tüm girdileri okudum (bu sabahki inceleme, Part 2 ve 70+70 ekran görüntüsü). Aşağıda istenen değerlendirme belgesi yer alıyor; belge, görev tanımındaki gibi İngilizce, çerçeve satırları Türkçe.

---

# neXus — Post-release check against the product review (D1, D2, Phase 2)

**Date:** 2026-10-18 · **Before:** build 2e38873 · **After:** build 55a6ab2 (aiview, Viewer, 1440 px) · **Inputs:** 70 before / 70 after captures, product review of this morning, Part 2.
**Rule:** anything not visible in an after-capture is **UNKNOWN**. Missing after-captures of 07 (member Cluster members), 27 (Queue) and 28 (History) are expected by design and are counted as "retired", not as regressions.

---

## 1. Verdict in five lines

1. **Yes, mostly.** The product now reads as one ledger connected at the object: the member header (after/05) carries identity, two read ages, `Compliance 1 critical failing · 24 checked`, `Config unchanged`, `Backup 14 h ago · Checked`, `Readiness Ready · 3 h ago`; the cluster header (after/02) carries both members' backups and one readiness; the rail shows all five verbs with group labels and an active item on every captured route.
2. **Improved most:** the shape. Overview gained the HA row (after/00), Operations lost two redundant tabs and gained words for reasons (after/25), Policy shows Browse · Judge · Trace · Ask with a container table under every browse tab (after/17–20, 77), the Logs tab went from "No collection yet" to a provenance strip with counts and gaps (before/10 → after/10), and the Identity tab now holds the audit grid, the reference ID and a sentence that explains the badge (after/11).
3. **Honesty held:** `Not verified yet` replaced an unproven green `Identity verified` on every device, `Certificate expired 7 d ago` is red with a word (after/59), `Compliance · Not assessed` on Blue Coat (after/65), `0 critical failing` in grey on ASA (after/56), `Backup · not a target` on FortiManager (after/44). No composite score appeared anywhere.
4. **Biggest remaining gap:** the new density broke four edges. Two tool pages overflow the viewport horizontally (after/36, 37 are 2050 px wide), the tree now truncates or drops device names when pills compete (after/12, 53), the Backups fleet table clips its last column (after/24), and the Administration rail item exists only while on an Administration route (compare after/30 with after/00, 01, 36).
5. **Still structurally open:** the header pills are visible but whether they link to the filtered fleet screens is UNKNOWN; RESULT is still `—` on every completed job; Offline analysis still has no disabled dropzone; Lifecycle and Delivery plan are still 22,022 px and 11,206 px single pages.

---

## 2. Roadmap check

Status key: **Done** = as designed · **Done differently** · **Partly** · **Not done** · **Regressed**. Evidence = after-capture numbers.

| # | Item | Status | Evidence and notes |
|---|---|---|---|
| 1.1 | Rail = five verbs + Tools + Administration, group labels, active state everywhere, drawer only < 1100 px | **Done differently** | 00–78: `SEE Devices · VERIFY Compliance, Policy · RECOVER Backups · OPERATE Operations, Tools`; active item on Devices, Policy, Compliance, Backups, Operations, Tools, Administration. Differences: (a) `Administration` is rendered only on Administration routes (30–35); absent on 00, 01, 36, 37 (see R4); (b) the `≡` drawer icon is still in the top bar at 1440 px; (c) search placeholder still flips to `Filter this list, or search everything…` on Devices (01). (a) is not acceptable; (b) and (c) are acceptable for now. Tools sub-entries (Packets · Sanitizer) not visible as a sub-menu: UNKNOWN. |
| 1.2 | `HA READINESS` row on Overview, inline bar labels | **Done** | 00: `25 of 45 clusters ready · 3 not ready · 12 unknown · 5 not supported`; bars labelled `7 without an archive`, `3 not read in 24 h`. Overview and Operations agree (25/3/12). |
| 1.3 | Header state row part 1: read ages, backup, certificate; remove second Collect now and loose lines | **Done** | 05: `Platform read 4 h ago · Config read 16 h ago`, `Backup 14 h ago · Checked`; 59: `Certificate expired 7 d ago`; 41: `Config read 25 d ago` amber; 38: `Config read 3 d ago` amber. Second Collect now gone from Configuration (08, 15, 54, 57). `Lifecycle:` line moved into the grid (11). Residual: `Open assigned policy` is still a loose button on every device including managers and appliances (R11). |
| 1.4 | One label map (job types, reasons, vendors, kinds); ISO timestamps and user-shaped actors on Diagnostics | **Partly** | Done: `Check Point readiness check`, `Palo Alto log collection` (26), `Pre-check failed`, `Insufficient evidence`, `Trusted connection required` (25), `Check Point` in drawer and tables (74, 30), `Host key mismatch ▸ Details` (40), timestamps `2026-10-17 06:16 GMT+3 · 34 h ago` (29). Not done: actor `SN-2D0959634B` (29), failed RESULT `partial:READ_SIZE_BELOW_OFFSET` (26), `COMPLETED` uppercase in Logs strip (10, 16), `NOT EVALUATED` on FortiManager (45). `Unknown device` → source name: UNKNOWN. |
| 1.5 | Devices landing = Fleet summary; `← Fleet summary` crumb; Overview keeps a one-line link | **Done differently** | 01: Fleet summary with `Policy installed` buckets, Software and hardware donuts, Inventory evidence age; crumb `← Fleet summary` on every device page (02–69). Differences: Overview still shows the full `Software in service` chart (00) instead of a link, so the chart lives twice; Fleet summary shows only Check Point and Palo Alto donuts (Fortinet, ASA, others absent or below fold: UNKNOWN); no quick-filter chips, only `60 devices` / `39 devices` links. Acceptable as an interim; 3.5 closes it. |
| 1.6 | Compliance: vendor pills, default sort Critical first, sticky filter bar, 560 px drawer with device links to Identity | **Done** (links UNKNOWN) | 23: `All vendors · Check Point 24 · Palo Alto 24 · Cisco ASA 9 · Fortinet 7`, `Sort: non-compliant, most severe first`, first rows are CRITICAL. 74: drawer ≈ 550 px, `Copy link`, `Check Point`, `Failing 58 · Passing 0 · Not yet checked 0`. Sticky bar and device-name links: UNKNOWN. |
| 1.7 | Page the long single pages (PAN routing, Lifecycle, Delivery plan) | **Partly** | 14: `Routing table · 371 routes · 1–50 of 371`. 70: still 22,022 px unpaginated. 34: still 11,206 px; a section index is UNKNOWN at review scale. |
| 1.8 | PAN/FortiGate interfaces grouped by VSYS/VDOM, physical collapsed, `With address` chip | **Done** | 12: `Physical / VS0 · 18 interfaces · 0 with an address · collapsed`, `VS-SEC-97 (VSID 1) · 11 interfaces · 7 with an address`, `With address only` chip; 04 same on a cluster. FortiGate (53) has the chip but no VDOM column (`24 in VDOMs` only in the header). |
| 2.1 | Header state row part 2: Compliance, Readiness, Config changed pills on every firewall, member, cluster, manager, each linking to the filtered fleet screen | **Done** (links UNKNOWN) | 05, 15, 53, 56: `Compliance n critical failing · n checked`, `Config changed` / `Config unchanged`; 02, 03, 04: cluster `Compliance 2 critical failing · 48 checked`, `Backup M1 … · Backup M2 …`, `Readiness Ready · 3 h ago`; 38: `Config changed · 7 sections`. Appliance without checks says `Compliance · Not assessed` (65), correct word. Gaps: cluster header has no `Platform read / Config read` line (02); `Config changed` on firewalls carries no section count (15, 65); whether pills are links: UNKNOWN. |
| 2.2 | Cluster page: Logs tab with M1/M2, Backups/Readiness header pills, strip retired, Cluster members tab off the member | **Done** | 02: tabs `Interfaces · Routing · Cluster members · Configuration · Logs · Identity · Failover`, M1/M2 switcher, no `This cluster in:` strip, member cards without Collect buttons. 05: member tabs without Cluster members; `Members:` line and parent link pill remain. |
| 2.3 | Tree: members under clusters, parent highlighted, auto-scroll, local filter box | **Partly** | 05: `FW-APOLLO-01-M1 ACTIVE` / `M2 STANDBY` under `CLS-APOLLO-01`, both highlighted; 12, 14, 53: selected device scrolled into view. Not done: no local filter box, top-bar placeholder still flips. New defects: names truncated to `FW-HEL…` and the selected FortiGate row shows no name at all (R2). |
| 2.4 | Operations: Queue/History → state pills; outcome word in RESULT; TRANSCRIPT hidden; polling switch; Admin Job Logs = same component with note | **Done differently** | 25, 26: tabs `HA & readiness · Jobs · Diagnostics`; pills `All 19,812 · Queued / Running 0 · Finished 16,652 · Failed 634 · Other outcome 2,526`; TRANSCRIPT column removed; `Live polling` switch; 35: `Same records as Operations › Jobs.` Not done: RESULT is `—` on every completed readiness and log-collect row. Acceptable; RESULT stays on the list. |
| 2.5 | Policy tab groups; shared `Select a container` card; Lookup real disabled button with reason; Offline analysis disabled dropzone with role note; GMT+3 pickers | **Partly** | 17–20, 77: `BROWSE · JUDGE · TRACE · ASK`, container table under Rules/Objects/Hygiene/Installation with `Installed on (targets)`. 22: `Look up policy intent · Needs a policy, a source, a destination`. 21: `From (GMT+3)` / `Until (GMT+3, exclusive)`. Not done: 76 has no dropzone, no role note, `Previous analyses` greyed without a count. 21 shows `Loading…` in all six cards and the list (R5). |
| 2.6 | Identity tab = grid + reference + certificate + badge explanation; Configuration shows a collapsed summary line | **Done** | 11: 17-cell grid incl. `REFERENCE … Copy`, `LAST CONFIGURATION READ`, `LAST INVENTORY READ`, `IDENTITY BADGE Not verified yet` with the four-state explanation and `Certificate expiry is not part of the check`. 08, 15: `PLATFORM IDENTITY … Full grid in Identity: serial, versions, uptime, … →`. Certificate block inside Identity on managers: UNKNOWN (tab not captured). |
| 2.7 | Admin › System storage first, pods collapsed; SSH trust under Registry; registry search, state pills, pagination | **Partly** | 33: `What the evidence takes` first, `Show service pods (13)`; 30: search box, `134 of 134`, `Per page 50 · 1–50 of 134`, vendor names spelled out, `Enrolled` pills. Not done: `SSH trust` still under ACCESS (32). |
| 3.1 | Design tokens, two density modes, unified components | **Partly / UNKNOWN** | Pills, tables and KPI cards look like one family; counter-examples remain (`COMPLETED`, `ENROLLED`, `NOT EVALUATED` uppercase; relative-only ages in header pills vs full timestamps in tables). Token work itself cannot be judged from captures. |
| 3.2 | Logs filled state: provenance strip, neutral counts, attention line, timestamp column, same on CP and PAN | **Done differently** | 10, 16: `Last collection 2026-10-18 12:30 · 3 h ago · COMPLETED · window …`, per-source line counts, `Sources in that run: no new data 1 · gap 1 · collected 7`, attention links (`Sequence gap suspected ×2`, `First collection: older content not fetched`), Gaps / Crash and dump / HCP cards. Differences: the source chips are rendered twice (strip and selector), CP detail has no timestamp column (raw lines), status word is uppercase. Acceptable; see R8. |
| 3.3 | Empty/refused/unknown card everywhere | **Partly** | Done: 78 `Restricted · Routes, addresses and policy are not searchable for the Viewer role` + `See all 4 devices`; 44 `Backup · not a target` pill. Not done: 37 dropzone says `Your role does not allow this action.` without role names; 46 still stacks `No backups` and `No backup attempts on record` under a `Stored archives (0)` title. |
| 3.4 | Panorama `n virtual systems ›`; Managed estate `table-layout: fixed` | **Partly** | 41: `7 virtual systems ›` per member. Not done: `DEPLOYME…` header clipped and `FULLY MAN…` pills truncated (44, 47). |
| 3.5 | Overview bottom half: posture and TRACE only; Software in service on Devices | **Not done** | 00: `Software in service` still on Overview (now duplicated with 01). |
| 3.6 | Addressable routes for control drawer and job detail | **Partly** | 74: `Copy link` in the control drawer. Job detail: not opened in 75 (same list as 26) — UNKNOWN; shareable job link UNKNOWN. |

Side items from Part 2 that this release also closed: P0-5B one HA statement (`HA: peer named, not confirmed · observed role PRIMARY`, 53), interface denominators (`2 interfaces · 15 in other contexts`, 59; `36 interfaces · 24 in VDOMs`, 53), P1-2B ASA label (`Configuration text (running + startup) — certificates, VPN images and profiles not included · secret-bearing`, 58), P1-1B amber stale config read (38, 41), P2-5B VS counts (41), plural `1 site` (50), `1 interface · 1 route` (41). Still open from Part 2: P2-1B FortiManager `UP` amber and empty `DEPLOYMENT` column (44); Radware MC `0 clusters · 4 gateways` (50); DefensePro version pill unlabelled (62); Configuration refusal copy `onboarding administrators` with an active filter box (08, 15, 45, 54, 57, 66).

---

## 3. Regressions and new defects

| ID | Screen · element | What is wrong | Sev | Fix |
|---|---|---|---|---|
| R1 | Tools › Packet Analysis (36), Data Sanitizer (37) · page description | Page is wider than the viewport: the one-sentence contract runs unbroken to x ≈ 1990 px, so the whole page scrolls horizontally at 1440. **Regression** (before/36, 37 fit). | **P0** | `max-width` on the description container and normal wrapping; keep the two-sentence contract. |
| R2 | Devices tree (12, 14, 15, 53–55, 59, 62) · device row | With `STANDALONE` + `n VSYS` pills the name is cut to `FW-HEL…`, `FW-BR…`, `FW-OS…`; on the selected FortiGate the row shows `[FTNT] PRIMARY · HA peer named, not confirmed · 3 V` and **no name at all**. **Regression** (before rows showed full names). | **P1** | Name on its own line, never truncated; pills wrap to the second line beside vendor/model; drop `STANDALONE` and the HA sentence from tree rows (they belong to the header), keep role pill only for cluster members. |
| R3 | Backups fleet (24) · `LATEST ATTEMPT` column | After adding `GMT+3` to `LATEST ARCHIVE`, the last column is clipped at the card edge (`Comple…`). **Regression** (before/24 showed `Completed`). | **P1** | `table-layout: fixed`; print `02:06 · 14 h ago` with the full `2026-10-18 02:06 GMT+3` on hover, as the device tables do; give `LATEST ATTEMPT` a fixed 180 px. |
| R4 | Shell rail · `Administration` item | Present only on Administration routes (30–35, pinned at the bottom); absent on Overview, Devices, Tools (00, 01, 36, 37). From any other plane Administration is reachable only via `≡`. | **P1** | Render the item on every route, `position: sticky; bottom: 0` inside the rail column. |
| R5 | Policy › Changes (21) · six KPI cards and list | All read `Loading…` / `Loading changes…` in the capture. Transient or stuck: UNKNOWN. | **P1** (verify) | Re-capture; if the 30-day default query is slow, show a skeleton with a timeout state (`Could not load changes · Retry`) instead of permanent `Loading…`. |
| R6 | HA & readiness (25) · `ACTIONS` column | 40 identical disabled `Next window: 18:00` buttons, one per row, beside the header button `Run readiness check (32 clusters) · Next window: 18:00`. Noise, no information. | P2 | Row button only when enabled for the role and window; otherwise an empty cell. The header button already carries the window. |
| R7 | Logs tab (10, 16) · source chips | The same eight source chips with green `OK` appear twice (summary strip and run selector); status word `COMPLETED` uppercase. | P2 | One chip row (the selector) under the strip; the strip keeps the sentence and the counts; `Completed`. |
| R8 | Jobs (26, 35) · `RESULT`; Diagnostics (29) · `ACTOR` | `partial:READ_SIZE_BELOW_OFFSET` in red; actor `SN-2D0959634B`. | P2 | Label map: `Partial — read size below offset` with the raw code on hover; actor as user token or `service · neXus scheduler`. |
| R9 | FortiManager Configuration (45) · `Last configuration read NOT EVALUATED`; MDS Configuration (39) · `serial read · UNKNOWN`; MDS tree row and Managed estate gateways (38) · `UNKNOWN` HA pills | New word `NOT EVALUATED` for "never read"; HA role `UNKNOWN` on a management server and on standalone gateways (council decision 3). | P2 | `Not read yet`; omit the HA token in the summary line for managers; `Standalone` or no pill for gateways without a cluster. |
| R10 | Every device header · `Open assigned policy` | Shown on managers (38, 41, 44, 47, 50) and appliances without policy (59, 62). | P2 | Managers: `Open policies this server manages →`; appliances: hide. |
| R11 | Fleet summary (01) · evidence-age legend; Backups (24) · `Target · locked`; CP Routing (06) · `✓ Shared` | Green on a count (`under 24 h 129`), green on a neutral state (`locked`), 17 green pills in one column. | P2 | Legend dots slate/red; `Target · locked` neutral slate; `Shared` as plain text, colour only for `Differs`. |
| R12 | Compliance KPI cards (23) · `Improvement needed`, `Immediate action` | Judgement labels on top of numbers. | P2 | Replace with the fact: `29.9 % of 2,491 checks`, `164 critical · 97 firewalls`. |
| R13 | Member header (05) · pill count and fold | 3 rows, ≈ 15 pills; tab content starts at ≈ 650 px (target ≤ 420 px). | P2 | Move `Check Point 29200 (RH-20-00)` and `4 interfaces · 17 routes` into one identity pill; `Members:` line is now redundant with the M1/M2 pills; one action row. |
| R14 | SSH trust (32) · `Trust mode` floating label | Label overlaps the description sentence. Before: UNKNOWN (not captured at this scale). | P2 | 8 px top margin or label inside the select. |
| R15 | Device Backup tables (09, 40, 43 …) vs fleet (24) · timestamps | `2026-10-18 02:04:42` without zone on devices, `2026-10-18 02:06 GMT+3 · 14 h ago` on the fleet; header pills carry only the relative age. | P2 | One timestamp component everywhere (`YYYY-MM-DD HH:MM GMT+3 · n h ago`), exact on hover in pills. |
| R16 | FortiGate header (53) · `Fortinet · Standalone` + `HA: peer named, not confirmed` | Still two HA statements. | P2 | Form pill `Fortinet · FortiGate` only; HA sentence carries the state. |
| R17 | Managed estate tables (44, 47) · `DEPLOYME…`, `FULLY MAN…` | Column header and pills clipped (pre-existing, not fixed by table change). | P2 | `table-layout: fixed`, hide `DEPLOYMENT` when every value is `—`. |
| R18 | PAN cluster interfaces (04) · group header `VS-BRAVO-07-82 (…` | VSYS group label truncated. | P2 | Let the group row span the table. |
| R19 | Overview (00) ↔ Devices (01) · `Software in service` | Same chart in two homes. | P2 | 3.5: Overview keeps `Software in service · Devices →` as one line. |

No lost capability found: Queue and History are covered by the Jobs pills; the member Cluster members tab is reachable from the cluster; TRANSCRIPT is hidden, not deleted (whether any job has a transcript: UNKNOWN). One fact to put in front of the PO, not a defect: with the badge now bound to a real comparison, **no captured device is `Identity verified`**; the green ✓ beside every device name (05, 56, 59 …) now has no stated meaning and should either be defined (`Live`) or removed.

---

## 4. Five-second test per audience (above the fold at 1440 × 900)

| Audience | Screen | Result | Evidence |
|---|---|---|---|
| **Deputy GM** | Overview (00) | **Pass.** | Four facts in the right column within ≈ 810 px: `97 of 118 firewalls · firewalls with at least one critical check failing`, `123 of 130 targets · 7 backup targets have no stored archive`, `129 of 132 devices · 3 not read in 24 h`, `25 of 45 clusters ready · 3 not ready · 12 unknown · 5 not supported`; headline `as of 2026-10-18 15:51 GMT+3`. The HA row is the last item before the fold; at 768 px height it drops out. |
| **Team lead** | Operations (25) | **Pass.** | Headline `30 failed · 1030 finished (24 h) · 25 of 45 clusters ready · 12 unknown · next window 18:00`; `Open failed jobs` link; first rows are `Not ready` with `Pre-check failed` in words and `3 h ago`. Caveat: the HA legend sentence and the 40 disabled buttons cost a row each (R6). |
| **Operator** | Member page (05) | **Pass, with a long header.** | Identity row, `Platform read 4 h ago · Config read 16 h ago`, compliance, config, backup, readiness pills and the peer `FW-APOLLO-01-M2 ✓` pill are all above the tab bar; the first tab's own summary (`Interfaces · 4 of 4 shown`) starts at ≈ 650 px, so the first data row is at the fold edge (R13). The PAN standalone page (12) passes the same way; the FortiGate page (53) passes but its tree row has no name (R2). |
| **Auditor** | Compliance (23, 74) and Identity (11) | **Pass on the device, partial on the trail.** | Identity: serial, model, version, hotfix, `POLICY INSTALLED 2026-10-16 22:11:59 · RULE-GOLF-6561`, `PLATFORM FACTS READ`, `LAST CONFIGURATION READ`, reference ID with `Copy`, and the badge definition. Compliance: `Export Audit Report`, drawer with `Copy link`, `How to fix`, `Caution`, R81.20 reference, `Check Point`. UNKNOWN: whether `FW-ZULU-04` in the drawer links to the device, and whether the control row's observed timestamp is exportable. |

---

## 5. Next work, prioritised

| Pri | Effort | Item | Source |
|---|---|---|---|
| **P0** | S | Fix horizontal overflow on Packet Analysis and Sanitizer. | R1 |
| **P1** | S | Tree rows: name never truncated, pills wrap, no `STANDALONE`/HA sentence in the tree. | R2, 2.3 |
| **P1** | S | Backups fleet: fixed column widths, `LATEST ATTEMPT` visible. | R3 |
| **P1** | S | `Administration` rail item on every route, sticky bottom. | R4, 1.1 |
| **P1** | S | Verify Policy › Changes; add timeout/error state. | R5 |
| **P1** | M | Header pills become links to the filtered fleet screens (Compliance → device filter, Backup → fleet row, Readiness → cluster row); confirm or add. | 2.1 (UNKNOWN) |
| **P1** | S | RESULT outcome word for readiness and log-collect jobs (`Ready`, `Not ready`, `n lines · gap`); label map for failed results and actors. | 2.4, R8 |
| **P1** | S | Offline analysis: disabled dropzone with `Role: not permitted`, `0 stored analyses`. Sanitizer: name the roles. | 2.5, 3.3 |
| **P1** | S | Lifecycle paginated at 50 with `+N more`; Delivery plan section index. | 1.7 |
| **P1** | S | Three words, three states: `Not read yet` (FortiManager), no HA token on managers, `Standalone` for gateways; remove `Open assigned policy` from managers/appliances. | R9, R10 |
| **P1** | S | Define or remove the green ✓ beside device names now that the badge reads `Not verified yet`; decide with the PO whether the fleet-wide unverified state needs an Overview fact (`0 of 132 identities verified`). | §3 note |
| **P2** | S | HA & readiness: row action only when enabled; one legend line. | R6 |
| **P2** | S | Logs tab: single chip row, sentence-case status, timestamp column on CP detail. | R7, 3.2 |
| **P2** | S | Colour roles: neutral for counts, `locked`, `Shared`; Compliance KPI labels as facts. | R11, R12 |
| **P2** | M | Member header to two pill rows (≤ 10 pills), tab content within 420 px; cluster header gains read ages. | R13, 2.1 |
| **P2** | S | One timestamp component (`GMT+3 · n h ago`, exact on hover) across device tables and header pills. | R15 |
| **P2** | S | FortiGate single HA statement; VDOM column; PAN VSYS group header width. | R16, R18, 1.8 |
| **P2** | S | Managed estate `table-layout: fixed`, hide empty `DEPLOYMENT`, `UP` neutral; Radware `4 appliances`. | R17, 3.4, P2-1B |
| **P2** | S | Overview: `Software in service` → one-line link to Devices Fleet summary; Fleet summary adds Fortinet, ASA, Blue Coat, Radware, Infoblox slices. | 3.5, 1.5 |
| **P2** | S | SSH trust under Registry; fix the floating-label overlap. | 2.7, R14 |
| **P2** | S | FortiManager Backup tab: one empty state `Not a backup target`. | 3.3 |
| **P2** | S | Configuration refusal: name the roles, disable the filter when settings are hidden. | P1-7B |
| **P2** | M | Job detail as an addressable route with the shareable link; re-capture with a job open. | 3.6 (UNKNOWN) |
| **P2** | L | Design tokens and the seven unified components (after the fixes above, so they are specified from the shipped screens). | 3.1 |

**UNKNOWN items that still block a judgement:** header pill link targets; compliance drawer device links; job detail content and link; Tools sub-navigation; Changes tab after load; Lifecycle and Delivery plan content at readable scale; Identity tab on a device with a certificate; whether any job carries a transcript; sticky filter bar on Compliance.

---

Belge yukarıdadır: ürün artık nesnede birleşen tek bir deftere büyük ölçüde dönüşmüş durumda; en büyük kazanım başlık durum satırı, rail ve Operations/Policy sadeleşmesi, en büyük açık ise yeni yoğunluğun kırdığı dört kenar (araç sayfalarında yatay taşma, ağaçta kaybolan cihaz adları, Backups son sütun kırpması, yalnızca Administration rotasında görünen Administration rail öğesi) ile hâlâ `—` kalan RESULT sütunu. Ekran görüntüsünden hükmedilemeyen noktalar UNKNOWN olarak işaretlendi.
