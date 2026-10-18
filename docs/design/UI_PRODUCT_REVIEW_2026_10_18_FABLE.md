Tüm girdileri okudum; aşağıda istenen bütünsel değerlendirme belgesi yer alıyor (belge İngilizce, görev tanımındaki gibi).

---

# neXus — Holistic Product Evaluation (architecture and design)

**Date:** 2026-10-18 · **Build:** 2e38873 (aiview, Viewer, 1440 px) · **Inputs:** 73 captures of today, Argon 2026-10-17, Fable Part 1 and Part 2 2026-10-18, council synthesis 2026-09-25.
**Scope rule:** screen-level defects already listed in those reviews are referenced by ID, not repeated. Anything not visible in a capture is **UNKNOWN**.

**State as of today's build (so the gap statement is fair).** Since Part 2 the following landed and are visible: `Managed estate` as a tab on every manager (P0-2B done), certificate state as a header pill (P0-1B done), VS/vsys breadcrumb with a switcher and scoped tabs (P0-3B done), one-line Backups rows with pagination and `Target · locked` (F-4 done), archives-first Backup tab with `Show all N attempts` (P1-4 / P1-2B done), `No collection yet · next window 12:00` (F-6 partly), Policy Objects and Installation tables (P0-4B done), `MD-` prefix for managed-not-enrolled devices (P2-3B done), one HA statement on FortiGate and `36 interfaces · 24 in VDOMs` (P0-5B done), Overview and Backups agreeing on `123 of 130`. Still open and relevant to this document: rail (P2-1 / F-17), Devices landing pane (P1-1), read ages in the header (F-9), raw job and reason labels (F-11), HA on Overview (F-13), VSYS grouping (F-19), tree members (F-18), three job tables (F-11), long single pages (F-15, P2-2B, F-20).

---

## 1. Product vision

neXus should feel like **one calm ledger of evidence about the firewall estate**, read differently by four audiences. The deputy GM and the manager should open it once a day and, without scrolling, know three things: how many firewalls carry a proven critical failure, how many have no restorable backup, and how many clusters could fail over right now; everything else is one click deeper and every number names its denominator and its age. The team lead should see, at shift start, what failed in the last window and what is not ready, in the order they would phone someone about it. The operator should land on a device and have its identity, freshness, backup, readiness and compliance in the header before choosing a tab, with the peer member one click away. The auditor should be able to go from a control to a device to a timestamped platform fact and an export without leaving the trail of "observed on … by …". Today the product reads that way **in its data honesty** (UNKNOWN written as UNKNOWN, hatched "not assessed", `Role: not permitted`, denominators on every count) but **not in its shape**: it reads as five well-made screens joined by a drawer. **The single biggest gap is that the planes are not connected at the object:** a device page does not say whether that device is backed up, compliant or ready, and the rail does not show RECOVER and OPERATE at all, so the SEE → VERIFY → TRACE → RECOVER → OPERATE journey exists only in the menu, not in the user's path.

---

## 2. Information architecture

### 2.1 What the menus say today

| Surface | Today | Against the principle |
|---|---|---|
| Left rail | Overview · Devices · Policy · Compliance · Packets | SEE and VERIFY are present; RECOVER (Backups) and OPERATE (Operations) are hidden in the `≡` drawer; a forensic tool with one case holds a primary slot. No active state on four routes (P2-1). |
| Drawer `≡` | Backups, Operations, Administration, Sanitizer (inferred from routes; drawer contents **UNKNOWN**, not captured) | A second menu for the planes that operators use every morning. |
| Top bar | Global search; `aiview`, `GMT+3`, build pills; theme, drawer, external link, bell, AI, user | Fine. The search placeholder flips on Devices (P2-1 part 2). The bell and the "AI" entry are **UNKNOWN** (never opened). |
| Device tabs | Vendor-pruned (council decision 3 is now mostly real) | Good. The evidence planes (Configuration, Backup, Logs, Identity) are tabs; the fleet planes (Compliance, Readiness) are not even links on the firewall page, only on the cluster strip. |
| Policy tabs | Rules · Objects · Installation · Hygiene · Changes · Lookup · Offline analysis | Seven tabs of three kinds: browse (Rules, Objects, Installation), judge (Hygiene), trace (Changes), ask (Lookup, Offline analysis). The tab bar does not show that structure. |
| Operations tabs | HA & readiness · Jobs · Queue · History · Diagnostics | Jobs, Queue and History are one table with three state presets; Administration › Job Logs is the same table a fourth time. |
| Administration left nav | Registry · Access · Platform · Records | Sound grouping. `Delivery plan` (a roadmap) and `System` (pods first) sit beside audit records. |

### 2.2 Overloaded, thin, duplicated

**Overloaded (too many jobs on one screen):**
- **Overview** below the first fold: `Software in service` is an inventory chart, not posture (council phase 3 still open); the `Configuration changed` and `Cluster members compared` lists are TRACE content.
- **Compliance**: KPI cards, four framework cards, control families, and the 64-row table in one 5,157 px page (F-14).
- **Devices › PAN interfaces** (04, 12): 56 flat rows (F-19).
- **Administration › System**: Kubernetes pods above the storage ledger (F-20).
- **Administration › Delivery plan**, **Devices › Lifecycle**: 11,110 px and 22,022 px single pages (F-20, P2-2B).

**Thin (a whole route for a sentence):**
- **Devices landing** right pane (P1-1): a prompt.
- **Policy › Hygiene, Lookup, Offline analysis** before selection: one sentence or an empty form.
- **Cluster members tab** (07): two rows, a UUID, a job status; this is a Cluster-level fact rendered on a member.
- **Operations › Queue**: honest, but a 4-word state does not need a tab.
- **Packets** and **Sanitizer**: one card each; correct as tools, wrong as rail peers of Devices.
- **Administration › Inventory exclusions**: one sentence (correct as content, but it is a registry setting, not a page).

**Duplicated (same fact, two homes):**
- Jobs ↔ History ↔ Queue ↔ Admin › Job Logs (one table, four routes).
- Platform identity grid (Configuration tab) ↔ Identity tab (same 13 fields plus the reference UUID; F-9).
- `This cluster in: Backups · Readiness` strip ↔ the planes those links go to; on members there is no strip at all.
- `Collect now` three times on a cluster page (header absent, one per member card) and twice on a member page (header + Configuration tab, P1-7B).
- Overview `Cluster members compared` ↔ the device Configuration comparison (where the `All →` leads is **UNKNOWN**).
- Vendor label sets (`CP/PAN/FTNT/ASA/RDW/IBX/BC` badges vs `check_point`, `bluecoat` strings) — one fact, three vocabularies (F-11).

### 2.3 Target menu tree

Principle applied literally: the rail is the five verbs, in order, always visible, with a label group so the order explains itself. Nothing is removed; every current route keeps a home.

```
RAIL (persistent, active state on every route)
  Overview                              ← posture for leadership and shift leads
  SEE
    Devices                             ← tree + object pages (unchanged tabs, see 2.4)
      sub-views: Fleet summary (landing pane) · Lifecycle (paginated)
  VERIFY
    Compliance                          ← frameworks, families, controls, control drawer
    Policy                              ← tab groups: Browse (Rules · Objects · Installation)
                                           Judge (Hygiene) · Trace (Changes) · Ask (Lookup · Offline analysis)
  RECOVER
    Backups                             ← fleet table, retention & policies
  OPERATE
    Operations                          ← tabs: HA & readiness · Jobs · Diagnostics
                                           (Queue and History become state pills on Jobs:
                                            All · Queued/Running · Finished · Failed)
    Tools                               ← one rail item with two entries: Packets · Sanitizer
  Administration (bottom of rail)
    Registry: Device management · Inventory exclusions · Credentials · SSH trust
    Access:   Local identities · Sessions · Roles & Permissions · LDAP settings
    Platform: System (Storage first, Pods collapsed) · Notifications · Delivery plan
    Records:  Audit logs · Job logs (same component as Operations › Jobs, with the note
              "Same records as Operations › Jobs"; kept because auditors look here)
```

**Drawer `≡`:** becomes the same list for widths under about 1100 px only; at 1440 px it is not needed. (If the drawer holds anything beyond those four routes, that content is UNKNOWN and must be re-homed before retiring it.)

**Top bar:** global search only (`Search devices, IPs, routes, policy…` on every route); the Devices tree gets its own `Filter this list` box. Bell and "AI" entry unchanged (contents UNKNOWN).

**What moves, and why**
- `Backups` and `Operations` → rail. They are two of the five verbs and the morning screens of operators and team leads.
- `Packets` → `Tools` with `Sanitizer`. Both are offline, file-in/file-out tools with a 7-day or 90-day store; neither is a plane of the estate.
- `SSH trust` → Registry group (it is a per-device trust setting, not an access-control setting). Keep the route.
- `Software in service`, `hardware models`, `policy installed`, `inventory evidence age` → the Devices landing pane as the **Fleet summary** (council phase 3). Overview keeps a one-line link `Estate details →`.
- `Configuration changed` list → stays on Overview as the TRACE card but its `All →` should land on Policy › Changes filtered to configuration (if the target today is elsewhere, UNKNOWN, keep both).
- Operations `Queue` and `History` → state pills on `Jobs`. All filters, CSV export and polling toggle stay on the one table.
- `Cluster members` tab on a **member** page → stays on the **cluster** page only; the member header already has the M1/M2 pills. Nothing is lost: the reference UUID lives in Identity (F-9), the last job status in the header freshness line.

### 2.4 Object page tab sets (target)

| Object | Tabs (left to right) | Note |
|---|---|---|
| Firewall, standalone | Interfaces · Routing · Configuration · Backup · Logs* · Identity | *Logs only where a collector exists (today CP, PAN). |
| Cluster member | same as firewall + member switcher pills in header | `Cluster members` tab removed from the member; reachable via the parent crumb. |
| Cluster / HA pair | Interfaces · Routing · Cluster members · Configuration · Failover · Logs (M1/M2 pills, F-21) · Identity | Header carries `Backups →` and `Readiness →` as pills; the `This cluster in:` strip goes. |
| VS / vsys | Interfaces · Routing | As shipped today (68, 69). Configuration per VS: UNKNOWN whether data exists; add only if it does. |
| Management server | Managed estate · Interfaces · Routing · Configuration · Backup · Logs* · Identity | As shipped today (38, 41, 44, 47). |
| Appliance (Radware, Infoblox, Blue Coat GW, ASA) | vendor-pruned as today | Correct; keep. |

---

## 3. Density and complexity

**Too much at once:** Overview rows 3–5; Compliance (everything on one scroll); PAN interface tables; Jobs (50 rows where `RESULT` and `TRANSCRIPT` are empty on 49 of them); Delivery plan; Lifecycle; System pods; Panorama Managed estate (38 gateways × 2 lines each, P2-5B).

**Too little at once:** Devices landing pane; device headers (identity only, no freshness, no RECOVER/VERIFY/OPERATE facts); Policy Hygiene / Lookup / Offline analysis before selection; Cluster members tab; Operations HA rows (status word, raw reason, no "what to do" column); Compliance control drawer (380 px for a 5-column table, P1-5B).

**Rule of thumb per screen type (above the fold at 1440 × 900):**

| Screen type | Belongs above the fold | Does not |
|---|---|---|
| **Summary** (Overview, Operations header) | One "as of" sentence with denominators; at most **3 headline numbers** each with its own sentence; one map or one ranked list of at most 5 rows. Nothing that needs a legend longer than one line. | Charts of distribution (versions, models), second-order lists, anything a second audience needs. |
| **List** (Backups, Compliance controls, Jobs, Registry, HA) | Filter bar (≤ 5 controls, one row); one count sentence (`1–50 of 131 targets`); at most **4 KPI cards one row high**; first **10–12 rows** of a one-line table; pagination visible without scrolling. | Multi-line rows, inline action buttons wider than the data, cards below the table, empty columns. |
| **Object page** (device, cluster, VS, manager, policy container, control) | Breadcrumb + title; **two pill rows**: identity (vendor · form · model · version · parent) and state (health · freshness · RECOVER · VERIFY · OPERATE); **one** action; tab bar; the first tab's own summary line. Tab content must start within ~420 px of the top. | Loose lines between header and tabs (`Open assigned policy`, `Lifecycle: …`), repeated role/HA facts, second action buttons. |
| **Tool page** (Packets, Sanitizer, Diagnostics, Lookup, Offline analysis) | The contract in **two sentences** (what it does, what it never does); one input region with the refusal reason inline; the result or history table. | Marketing-style prose, the retention policy in three places, hidden submit buttons. |

A practical cap: **≤ 7 numeric facts** on a summary fold, **≤ 12 columns** on a list, **≤ 10 pills** in an object header (identity and state rows combined), **≤ 6 tabs** before a `More` overflow.

---

## 4. Summary and pill information ("hap bilgi")

### 4.1 What each audience must get in five seconds

| Audience | Screen | The five-second facts (wording style: *number · noun · denominator · age*) |
|---|---|---|
| Deputy GM | Overview | `97 of 118 firewalls · at least one critical check failing` · `7 of 130 backup targets · no stored archive` · `22 of 45 clusters · ready to fail over · 13 unknown` · `as of 2026-10-18 10:21 · 129 of 132 devices read in the last 24 h`. Nothing else is required above the fold. |
| Network-security manager | Overview + one click | The same four, plus `35 devices · not assessed` and `25 of 132 · configuration changed since previous read`. |
| Team lead | Operations (shift start) | `27 jobs failed · last 24 h` · `5 clusters · not ready` · `13 clusters · unknown` · `next window 12:00 · Europe/Istanbul (GMT+3)`. |
| Operator | Device header | see 4.2. |
| Security admin | Policy, Compliance | `59 containers · 2 sources · collected 4 h ago` · `164 critical failing checks · 496 data gaps` · `40 of 64 controls failing`. |
| Auditor | Compliance, Identity tab | `2491 checks · 118 of 123 firewalls · 80.1 % with evidence` · `Export audit report` · on a device: serial, model, version, hotfix, policy installed, `platform facts read 06:01`, `configuration read 00:02`. |

**Wording rules (apply everywhere):**
- Number first, noun second, denominator third, age last: `123 of 130 targets · stored backup · 02:04 · 8 h ago`.
- A state is a **word with a colour**, never a colour alone: `Ready`, `Not ready`, `Unknown`, `Not assessed`, `Not read yet`, `Not applicable`, `UNKNOWN` (P1-1B keeps the three words distinct).
- Zero is written as `0` in grey; absence of data is `UNKNOWN` or `Not read yet`, never `0` and never `—`.
- No adjective that implies judgement (`good`, `healthy`, `outdated`); only what was observed and when.
- Age in the unit a human says: `4 h ago`, `7 d ago`, `just now`; the ISO timestamp next to it or on hover.

### 4.2 Object page header, without a composite score

Two rows of pills under the name. The order inside row 2 is a **fixed rule, not a score**: the pills are always in the same plane order (SEE → VERIFY → TRACE → RECOVER → OPERATE), and a pill is tinted only when its own word is a problem word. The eye finds red by scanning a known position; no "overall" is computed.

```
[CP]  FW-APOLLO-01-M1   ✓ Live   ✓ Identity verified
Row 1 (identity):  Check Point ClusterXL · CLS-APOLLO-01 · ACTIVE | 29200 (RH-20-00) | R81.20 Jumbo Take 119 | 4 interfaces · 17 routes | Lifecycle not imported
Row 2 (state):     Inventory read 06:01 · 4 h ago | Config read 00:02 · 10 h ago | Compliance 3 critical failing · 59 checked | Config changed · 18 sections (TRACE) | Backup 02:04 · 8 h ago · Checked (V1) | Readiness Ready · 4 h ago | Certificate expires in 223 d
Action:            [ Collect now · Role: not permitted ]            Members: [M1 ✓ ACTIVE] [M2 ✓ STANDBY]   Open assigned policy →
```

- **Health** = the vendor-observed words already in the product (`Live`, `Identity verified`, `ACTIVE`, `Up`), each paired with a tick or the state word.
- **Freshness** = two read ages (inventory, configuration) promoted from the Platform identity grid (F-9) plus the backup age; a read older than the latest platform read gets the amber word `stale` next to its age (P1-1B).
- **Identity** = row 1; the full audit grid stays in the Identity tab.
- **Risk** = three counts, each with its own word and link: critical checks failing (VERIFY), days since last stored archive or `no archive` (RECOVER), readiness word (OPERATE). Three facts side by side; the reader does the weighing, the product does not.
- For a **manager** the row-2 pills are: `Managed 66 gateways · 6 not in neXus`, `Config read 23 d ago · stale`, `Backup …`, `Certificate …`. For a **cluster**: `Readiness`, `Members compared 68 settings differ`, `Backup M1 … · M2 …`.
- Pills a vendor does not have are **absent**, not `UNKNOWN` (decision 3); pills a vendor has but neXus has not read say `Not read yet`.

### 4.3 Headline facts per screen (first line under the title)

| Screen | One-line headline |
|---|---|
| Overview | `Estate assurance · 132 enrolled · 45 clusters · as of 10:21 GMT+3 · 129 read in 24 h` (as today; keep). |
| Devices (landing) | `134 registered · 132 enrolled · 132 live · 25 changed config since last read · 7 without archive` + the Fleet summary pane. |
| Compliance | `2491 checks · 118 of 123 firewalls · 164 critical failing · 496 data gaps · re-checked 4 h ago`. |
| Policy | `59 containers · 2 sources · 69,000 rules (sum) · collected 4 h ago` (sum is UNKNOWN from captures; use the real one). |
| Backups | `123 of 130 targets · 02:00 daily · 7 without archive · retention 7 d`. |
| Operations | `27 failed · 879 finished · 22 of 45 ready · 13 unknown · next window 12:00`. |
| Device Backup tab | `Stored archives (6) · retention 7 d · latest 02:04 · 8 h ago · Checked (V1)`. |
| Device Logs tab | `Last collection 06:13 · 4 h ago · Completed · 3,331 lines · 2 sources` (or the three empty sub-states, F-6). |
| Control drawer | `HIGH · Non-compliant · 28 of 59 failing · re-checked 4 h ago · CIS 2.1.1`. |

---

## 5. Navigation and workflow

Click counts are navigational actions (clicks or taps; typing counts as one). "Today" is the shipped build; "Target" is the tree in §2.3 with the header in §4.2.

| Audience · journey | Today | Target | What shortens it |
|---|---|---|---|
| **Deputy GM · morning posture** (failures, backups, failover) | Overview (1) → `≡` (2) → Operations (3) → read HA card = **3**, with the HA fact off the first screen | Overview = **1** | `HA READINESS` row on Overview (F-13). |
| **Deputy GM · "which firewalls?"** | Overview → `All →` on critical failures (2) → Compliance list in ID order, scroll | Overview → card → Compliance pre-sorted Critical first (2) | Default sort and vendor pills (F-14). |
| **Manager · weekly evidence coverage** | Overview → footer link `inventory evidence age` (2) → page of UNKNOWN layout | Overview → Devices Fleet summary (2) or Compliance headline | Fleet summary pane (P1-1). |
| **Team lead · shift triage** | `≡` → Operations (2) → Jobs (3) → State = Failed (4) → scroll; Not-ready clusters on HA tab (5), reasons raw | Operations (1) → `Failed · 27` pill (2); HA reasons in words | Rail; state pills; label map (F-11, F-12). |
| **Team lead · "is cluster X safe to patch tonight?"** | Devices → cluster (2) → `Readiness` strip link (3) → find row → back → `Backups` link (5) → find row | Devices → cluster (2): header shows `Readiness Ready · 4 h ago` and `Backup M1 02:04 · M2 02:05` | Header state row (§4.2). |
| **Operator · incident on one device** | Search → device (2) → Interfaces; Routing (3); Logs (4); Backup (5); peer member: tree scroll or `Members:` link (6); readiness: not on member page (7+) | Search → device (2); tabs (3–5); peer via M1/M2 pill (same tab kept) ; readiness in header | Member switcher keeps the tab; header carries readiness and backup. |
| **Operator · PAN interface with address in vsys X** | Device (2) → scroll past 18 physical rows → filter text (3) | Device (2) → vsys group header or `With address` chip (3), no scroll | F-19, P1-3B. |
| **Operator · why did last night's backup fail?** | Backups (2 via `≡`) → find row (filter, 3) → device Backup tab (4) → `Show all 32 attempts` (5) | Backups (1) → row → attempt reason in the row's `Latest attempt` cell (2–3) | Rail; failure word in the fleet row (F-4 follow-up). |
| **Security admin · "which rule allows A→B?"** | Policy (1) → Lookup (2) → pick container (3) → fill 3 fields (4–6) → submit (7) | Same form, submit as a real button with the missing-field reason; `Jump to device's policy` pre-fills the container when arriving from a device (2 fewer) | F-10; device header `Open assigned policy →` carries the container. |
| **Auditor · evidence for one control** | Compliance (1) → row `→` (2) → drawer device list (no device link visible, UNKNOWN) → Devices (3) → find device (4) → Configuration (5) → read Platform identity | Compliance (1) → drawer (2) → device name links to Identity tab (3) → `Export audit report` from either | Drawer links (P1-5B follow-up); Identity tab holds the grid (F-9). |
| **Auditor · prove a device's identity and backup** | Device (2) → Configuration (3) grid → Identity (4) for the reference ID → Backup (5) | Device (2) → Identity (3) with grid + reference + certificate; backup age already in header | F-9, P0-1B. |

Two structural facts the tree alone fixes: every route gains an active rail item (today four routes have none), and every plane becomes reachable in **one click from anywhere**.

---

## 6. Visual language

The product already has one typeface, one blue, one pill shape and one card radius. What makes it feel like several products is **vocabulary and placement**, not style. A short direction:

**Type scale (5 steps, nothing else):** Page title 22/28 semibold · Section/card title 16/22 semibold · Body 14/20 · Label and pill 12/16 uppercase-tracked for labels, sentence-case for pills · Mono 13/18 for addresses, hashes, timestamps, versions. Rule: every value of a given kind uses one font (today `MODEL` is proportional and `SOFTWARE VERSION` is mono in the same row, Part 1 §3).

**Density modes:** two, chosen per screen type, not by user toggle: **Comfortable** (object headers, summary cards: 16 px gutters, 44 px rows) and **Compact** (all list screens and data tables: 8 px gutters, 32 px one-line rows, mono for machine values). The registry (card-per-row) and Jobs (50 × 40 px) are the two screens on the wrong mode.

**Colour roles (paired with a word, always):**
- `State-bad` red: `Not ready`, `Failed`, `Certificate expired`, `critical failing`.
- `State-warn` amber: `stale`, `partial`, `not confirmed`, `Last collection failed`.
- `State-ok` green: `Live`, `Identity verified`, `Ready`, `Up`, `Completed`, `Compliant`. Never on a count, never on an absence.
- `Neutral` slate: every count, every `0`, `Bytes changed`, `Not applicable`, `Not read yet`, `locked`.
- `Unknown` hatched grey: `UNKNOWN`, `Not assessed`, `Not evidenced`.
- `Role` black outline/fill: `ACTIVE/STANDBY`, `PRIMARY/PASSIVE` (keep as is).
- `Brand` blue: links, the one primary action, selected tab. Not for state toggles (`Live polling: ON`).

**Components to unify (one implementation each):**
1. **Status pill** = word + optional count + optional age; colour from the role table; `· Role: not permitted` / `· Next window: HH:MM` suffix variant for controls.
2. **Object header** (§4.2) used by device, cluster, VS, manager, policy container, compliance control, packet case.
3. **KPI card** = label, number, denominator sentence, one link; always above the table; max four per row.
4. **Data table** = one-line rows, fixed column widths at 1440, mono for machine values, row `⋮` menu for actions, pagination footer `1–50 of N`.
5. **Empty / refused / unknown card** = icon, state word, one sentence of reason, one next step (`next window 12:00`, `Operators and Security Administrators can …`).
6. **Label map** = single presenter for vendor names, job types, reason codes, kinds (`system_log` → `System log`); raw value as a muted suffix or on hover.
7. **Timestamp** = `2026-10-18 06:49 GMT+3 · 4 h ago` everywhere; pickers in GMT+3 with a `(UTC)` toggle.

**Density of colour:** a 36-row table of green `Up` pills carries no information; the rule "green only where the word would be read" plus the `With address` chip solves it without changing the palette.

---

## 7. Phased roadmap

No item removes a field, action, RBAC boundary or masking rule; each names where things go. Effort: S under a day, M a few days, L a week or more. IDs refer to the earlier reviews where the item is already specified.

### Phase 1 — quick wins (words, positions, rail)

| # | Item | Screens | Effort | Capability preserved |
|---|---|---|---|---|
| 1.1 | Rail = Overview · Devices · Compliance · Policy · Backups · Operations · Tools (Packets, Sanitizer) · Administration, with group labels SEE/VERIFY/TRACE/RECOVER/OPERATE and an active state on every route; drawer only under 1100 px (P2-1, F-17). | shell | S | Every route stays; drawer contents (UNKNOWN) re-homed first. |
| 1.2 | `HA READINESS` row on Overview; in-line labels on the two defect bars (F-13). | Overview | S | Existing cards untouched. |
| 1.3 | Header state row, part 1: promote `Inventory read` and `Config read` ages, `Backup <age> · <validation>`, `Certificate` into row 2 of every device header; remove the second `Collect now` and the loose `Lifecycle:` / `Open assigned policy` lines into the grid and header respectively (F-9, P1-7B). | all device pages | M | Grid cells and exact timestamps remain in Identity. |
| 1.4 | One label map for job types, readiness reasons, vendors, kinds; ISO timestamps and user-shaped actor tokens on Diagnostics; `Unknown device` → source name (F-11). | Operations, Admin › System, Jobs, Job Logs | M | Raw values on hover / sub-text; CSV export unchanged. |
| 1.5 | Devices landing pane = Fleet summary (Software in service, hardware models, policy installed, evidence age) with quick-filter chips; `← Fleet summary` crumb; Overview keeps `Estate details →` (P1-1 part 3, council phase 3). | Devices, Overview | M | Charts move, link on Overview stays. |
| 1.6 | Compliance: vendor pills, default sort Critical-failing first, sticky filter bar; control drawer 560 px with device names linking to the device Identity tab (F-14, P1-5B). | Compliance | S | All 64 rows, families and cards stay. |
| 1.7 | Page long single pages: PAN routing, Lifecycle, Delivery plan (section index) (F-15, P2-2B, F-20). | Devices, Admin | S | All rows reachable. |
| 1.8 | Group PAN/FortiGate interfaces by VSYS/VDOM, physical group collapsed, `With address` chip (F-19, P1-3B). | Devices | S | Chip off restores every row. |

### Phase 2 — structural (connect the planes at the object)

| # | Item | Screens | Effort | Capability preserved |
|---|---|---|---|---|
| 2.1 | Header state row, part 2: `Compliance n critical failing · n checked`, `Readiness <word> · <age>`, `Config changed · n sections` as pills on every firewall, member, cluster and manager header, each linking to the filtered fleet screen (council agreed #2). | all device pages, Compliance, Operations, Backups | L | Fleet screens unchanged; the device page gains links. |
| 2.2 | Cluster page: `Logs` tab with M1/M2 pills; `Backups →` and `Readiness →` as header pills; `This cluster in:` strip retired; `Cluster members` tab leaves the member page (reached via crumb) (F-21). | cluster, member | M | All tabs and links stay, once. |
| 2.3 | Tree: members under non-VSX clusters, parent kept highlighted, auto-scroll to the open device; local filter box above the tree, global search stays global (F-18, P2-1 part 2). | Devices | M | Tree grows; nothing removed. |
| 2.4 | Operations: `Queue` and `History` become state pills on `Jobs`; outcome word in `RESULT` for readiness and log-collect jobs; empty `TRANSCRIPT` hidden until data exists; polling as a switch; Admin › Job Logs = same component with a one-line note (F-11, P1-4B). | Operations, Administration | M | All filters, export, 19,524 rows, both routes. |
| 2.5 | Policy tab bar grouped Browse · Judge · Trace · Ask (visual grouping only); shared `Select a container` card for Hygiene; Lookup submit as a real disabled button with reason; Offline analysis disabled dropzone with role note; GMT+3 pickers (F-10, P0-4B remainder). | Policy | S | Seven tabs, all filters stay. |
| 2.6 | Identity tab = Platform identity grid + reference ID + certificate block + `Identity verified` explanation; Configuration tab shows the grid as a collapsed summary line linking to Identity (F-9, P0-1B remainder). | all device pages | M | Every field once, audit grid complete. |
| 2.7 | Admin › System: Storage first, pods behind `Service pods (14) ▸`; SSH trust under Registry; registry search, state pills, pagination (F-20, P2-3, F-22). | Administration | S | Every pod and row stays. |

### Phase 3 — polish (one product)

| # | Item | Screens | Effort | Capability preserved |
|---|---|---|---|---|
| 3.1 | Design tokens: 5-step type scale, two density modes, colour-role table, the seven unified components (§6); replace per-screen variants. | all | L | Pure presentation. |
| 3.2 | Logs filled state summary layer (provenance strip, neutral counts, attention line, timestamp column), identical on CP and PAN (F-8). | device Logs | M | All chunks, lines, gaps, side cards stay. |
| 3.3 | Empty/refused/unknown card everywhere: Packets `No case yet` workflow text, Sanitizer role names, Global search `Restricted` scope sentence, FortiManager backup single state (F-22, P1-6B, P2-1B). | Tools, search, device Backup | S | Same results, explained. |
| 3.4 | Panorama Managed estate: `n virtual systems ›` on expand; Managed estate tables `table-layout: fixed` (P2-5B, P2-1B). | managers | S | All names reachable. |
| 3.5 | Overview bottom half: keep only posture and TRACE cards; `Software in service` lives on Devices Fleet summary (from 1.5) with a one-line link. | Overview | S | Chart kept, on Devices. |
| 3.6 | Routes for drawers: control drawer and job detail as addressable routes (deep-linkable for audit evidence). Job detail content is UNKNOWN (never opened). | Compliance, Operations | M | Same content, shareable URL. |

**UNKNOWN items that block a judgement:** drawer contents; the bell and "AI" entries; job detail; the `All →` targets of the Overview TRACE cards; where the compliance drawer device names lead; whether a Viewer may ever trigger `Collect now` in-window (Part 2 open question); the Lifecycle page content at readable scale; ASA Backup label after today's build (not re-captured).

---

Belge yukarıdadır: vizyon, hedef menü ağacı (rail'e Backups ve Operations, Tools altında Packets/Sanitizer, Administration altta), yoğunluk kuralları, beş saniyelik "hap bilgi" ve nesne başlığı tasarımı (kompozit skor olmadan, sabit sırada pill'ler), üç kitle için tıklama sayıları ve üç fazlı yol haritası. Ekran görüntüsünden hükmedilemeyen noktalar UNKNOWN olarak işaretlendi.
