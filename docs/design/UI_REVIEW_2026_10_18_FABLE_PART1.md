<!-- Fable consultation 2026-10-18 via scripts/consult_fable_ui_review_2026_10_17.py; the CLI wrote the review as a Claude Docs document; exported unaltered as Markdown -->

# neXus UI Review 2026-10-18 (Fable)

Oct 18, 2026 · @Ozan

Reviewer: Fable (product/UI design, network-security operations). Inputs: 38 aiview captures (build f62b7c6, 2026-10-18 06:45 to 06:50 GMT+3), the 2026-09-25 council synthesis, Argon's 2026-10-17 review. Argon's findings are referenced by ID and not repeated. Anything not visible in a capture is marked UNKNOWN.

## 1. Verdict

1. **What works.** The fix release landed: the Estate map is now honest (Cisco ASA and Fortinet hatched, Not assessed 35, No archive as red rings), Compliance arithmetic reconciles on every card (749 + 1266 + 500 = 2515, Overview percentages match), Policy opens on a 59-container table with expandable sources, refused actions say `Role: not permitted`, the Administration registry no longer collides or truncates, and the Sanitizer subtitle is English.
2. **What does not: identity trust.** Three new contradictions an auditor would catch in a minute: the registry lists `FW-FOXTROT-07-M2`, `FW-PHOENIX-04-M1` and `FW-QUEBEC-03-M2` twice each with different timestamps (30); the same Panorama source is `FW-JULIET-01` in the Policy tree and `FW-WHISKEY-08` in the Policy table (17); the Devices landing text says "135 devices are enrolled" under a header that says 133 enrolled (01).
3. **What does not: tables at 1440 px.** Backups regressed: every row is now about 170 px tall with four stacked controls clipped to `Backup Now · Role: not perm`, `Con`, `Dow`, 3.5 rows visible, no pagination (24). Palo Alto interface tables still clip `NETWORK` (04, 12), the Palo Alto routing tab renders 371 routes in one 16,915 px page (14), and the Identity tab clips `MANAGEMENT…` (11).
4. **Logs tab.** The Check Point member shows the empty state, but the Palo Alto pilot `FW-HELIOS-02` already shows a filled state (16): a developer view with raw JSON lines, a Turkish `Loglar` label, chunk pickers and jargon gap pills. The Jobs tab shows `cp_system_log_collect` COMPLETED for `FW-APOLLO-01` at 06:05:20 while that member's tab says "No collection yet" (13, 26). The tab needs its summary layer and a truthful "collected, 0 lines" state before collection reaches 133 devices.
5. **Three highest-value changes.** (a) Identity integrity: one source of truth for hostnames, source pseudonyms and the enrolled count (P0, S). (b) Backups fleet table: one-line rows, actions in a menu, pagination, locked toggles (P0, M). (c) Logs filled state: provenance strip, neutral severity counts, plain-English gaps, identical on Check Point and Palo Alto (P1, M).

## 2. Effectiveness per screen

Capture numbers in brackets. "Serves" = does the capture show the screen doing its job for its audience.

### Shell and navigation (all captures)

- **Purpose:** orient across planes, global search, persistent session, timezone and masking context.
- **Who, when:** every role, every page.
- **Serves: partly.** Rail still pins Overview, Devices, Policy, Compliance, Packets; Backups (24), Operations (25), Administration (30) and Sanitizer (37) show no active rail item. The Devices search placeholder still flips to "Filter this list, or search everything" (01). Argon P2-1 unchanged.
- **Fix:** P2-1 as written. Add a local filter box above the tree; keep the top bar global.

### Overview (00)

- **Purpose:** one-screen assurance posture for leadership and shift leads.
- **Who, when:** deputy GM, manager daily; team leads at shift start.
- **Serves: yes, with two gaps.** P0-1 fixed: ASA 12 and Fortinet 10 hatched, Not assessed 35, No archive 7 as red rings on the first Check Point tiles, legend counts sum (57 + 23 + 18 + 35 = 133). Compliance percentages match the Compliance screen exactly. Still missing: HA readiness (now 20 ready of 45 on Operations) and Fortinet/ASA in Software in service; the BACKUPS and EVIDENCE bars still draw only the defect sliver under a positive headline.
- **Fix:** add a fourth KPI row `HA READINESS 20 ready of 45 · 4 not ready · 16 unknown` linking to Operations. Label the sliver bars in-line (`7 missing`, `3 older than 24 h`) rather than converting to a filled bar; a 95 % filled bar would read as green-by-shape (see §5 on P2-4). Add Fortinet and ASA version rows to Software in service with `Unknown N` hatched where not read.

### Devices landing (01)

- **Purpose:** pick one of 135 devices or clusters; fleet actions.
- **Who, when:** operators and admins all day; auditors for export.
- **Serves: partly.** `Add device · Role: not permitted` is now honest. New defect: the placeholder reads "135 devices are enrolled" while the header says "135 registered · 133 enrolled" and the tree says "135 devices · 133 Live". `Bulk Collect` and `Read configuration, all` now render as enabled pills for a Viewer with no window or role hint; whether a Viewer can run them is UNKNOWN. The placeholder copy still omits configuration and logs. Chevrons on `CLS-APOLLO-01` and `CLS-APOLLO-09` still open nothing. Right pane still 70 % empty.
- **Fix:** one count sentence from one query (`135 registered · 133 enrolled · 133 live`). Give the two collect pills the same `· Next window: 12:00` or `· Role: not permitted` suffix as the others. Fill the pane with the estate inventory summary (P1-1) and show members under non-VSX clusters (P1-2).

### Cluster view: Check Point (02), VSX (03), Palo Alto HA (04)

- **Purpose:** the cluster as one unit: roles, VIPs, interfaces, routing, config comparison, failover.
- **Who, when:** operators and team leads before maintenance and during incidents.
- **Serves: partly.** Check Point interface tables now fit (P0-3 part 2 fixed for CP). The Palo Alto cluster table still clips `NETWORK` to `252.2…` and its header to `NETWO` (04). Member cards show `Collect now` as plain enabled buttons with no window or role suffix, unlike the fleet buttons. No read age in the header. `CLS > CLS-APOLLO-01` and the `This cluster in:` strip remain. Stacked MACs still unlabelled. The PAN cluster lists 18 `Physical / VS0` rows with dashes before any addressed row.
- **Fix:** P0-3 for the PAN table (shrink `MAC`, `KIND`, `VLAN`); add `Inventory read 06:01 · Config read 00:02` to the header; make the member `Collect now` buttons carry the same suffix rule as everywhere else; P1-2 for breadcrumb and MAC tags; P2-2 for VSYS grouping.

### Check Point member: Interfaces (05), Routing (06), Cluster members (07)

- **Purpose:** verify one member's runtime state against its peer.
- **Who, when:** operators troubleshooting a member.
- **Serves: partly (Interfaces, Routing yes; Cluster members weak).** `Lifecycle: UNKNOWN (no lifecycle data imported)` replaces the raw enum (fixed). The tree drops the cluster highlight when a member opens; `Members:` line and the two member pills still duplicate. Routing is clear and the `Shared` column pairs colour with a word. Cluster members shows a raw UUID `DEVICE ID` and `COMPLETED (completed in 8038ms)` with no timestamp or job type.
- **Fix:** Cluster members: add `LAST READ 2026-10-18 06:01` and the job type, move the UUID behind a `Copy ID` link (the same ID is on Identity). Move the Lifecycle line into the Platform identity grid as a cell `LIFECYCLE: UNKNOWN`.

### Configuration tab: Check Point (08), Palo Alto (15)

- **Purpose:** audit-first identity facts, read freshness, parsed configuration.
- **Who, when:** security admins, auditors.
- **Serves: partly.** Contrast fixed: the tab-level `Collect now` is now white on blue, but it is a full primary button next to the header's secondary `Collect now`, so one screen shows two styles of the same action. Read timestamps still sit in the last grid cells. The Viewer notice still says "visible to onboarding administrators" and the filter box stays active over hidden content. Palo Alto adds a good `CONTENT VERSIONS` cell, but `URL filtering 0000.00.00.000 (as r` is clipped. `HA ROLE` appears three times on the PAN page (header badge, `HA role:` line, grid cell).
- **Fix:** keep one `Collect now` (header); promote the two read timestamps into the header as ages (keep the grid cells); let the content-versions cell wrap; delete the `HA role:` line under the tabs (the badge and the grid keep it); reword the notice to name the Viewer role and the roles that can see configuration text.

### Backup tab (09)

- **Purpose:** archives and attempt history for one device; on-demand request with reason.
- **Who, when:** operators before a change window.
- **Serves: partly.** `Request backup · Role: not permitted` plus the sentence under it is honest; the `Reason` box is still an enabled input. Attempts still sit above archives; `COMPLETED: COMPLETED` and `CANCELLED: cancelled:` persist; sizes are raw bytes; `V1` and grey `changed` here versus `Checked` and amber `Changed since last` on the fleet screen. Pagination (1, 2) is new and good.
- **Fix:** P1-4 unchanged. Disable the Reason input for Viewer.

### Logs tab (10, 13, 16)

See §4.

### Identity tab (11)

- **Purpose:** per-member identity as read from the device, plus the neXus reference ID.
- **Serves: partly.** Content is now visible: reference UUID with Copy, one row per member. The table clips at `MANAGEMENT…`, `MODEL` wraps to four lines, only M1 is listed although the header says MEMBER. The grid in Configuration already carries every field except the reference ID.
- **Fix:** add `REFERENCE` with Copy to the Platform identity grid, list both members there, and turn the Identity tab into an anchor that scrolls to that grid. Nothing is lost; the audit grid gains the ID.

### Palo Alto standalone: Interfaces (12), Routing (14)

- **Purpose:** 73 interfaces across 8 VSYS, 371 routes.
- **Serves: partly.** `ZONE` no longer wraps (fixed). `NETWORK` still clips (`246.39.10…`). The tree is scrolled to the top, so `FW-HELIOS-02` is not visible. Routing renders all 371 rows: the capture is 16,915 px tall with no pagination or virtual scroll.
- **Fix:** P0-3 for the table; paginate or virtualise routes at 50 per page with the same filter box and a `Differences only` style toggle for VSYS; auto-scroll the tree.

### Policy (17–22)

- **Purpose:** browse, compare and audit stored policies from Panorama and MDS.
- **Who, when:** security admins, auditors; daily lookup and change review.
- **Serves: Rules landing yes; other tabs no.** P0-4 fixed: 59 containers in a table with `Open`, sources expandable, batch actions behind `Actions ▾`. New defect: the Panorama source is `FW-JULIET-01` in the tree and `FW-WHISKEY-08` in the table (masking of two different raw identifiers, cause UNKNOWN). 18 is identical to 17, so the rule view after `Open` is UNKNOWN. Objects (19) is a blank card with no text. Hygiene (20) is one sentence. Changes (21) shows six `—` counters and `Loading changes…` with UTC date pickers while the rest of the product is GMT+3. Lookup (22) has a submit rendered as grey text and placeholders cut to `(optio…`.
- **Fix:** mask the source from one canonical field so both panes agree. Give Objects, Hygiene and Changes the same empty card as Rules (`Select a container` with the container table or a count). Replace `—` with `UNKNOWN` or a count once loaded, never a dash. Use GMT+3 pickers with a `(UTC)` toggle. Style `Look up policy intent` as a disabled primary button with the missing-field reason.

### Compliance (23)

- **Purpose:** proven failures versus evidence gaps per control and framework.
- **Who, when:** audit reviewers, manager, security admins.
- **Serves: yes, with triage gaps.** Every card sums (CIS 2515, PCI 1973, NIST 2476, Financial 1071, Data gaps 500). The new `Control families` block (coverage and failing checks per family) and the `All 64 · Failing 40 · Not yet checked 17 · Passing 7` pills are a real gain. Still no vendor filter; rows remain in ID order (`gaia_*`, `pan_*`, `fg_*`, `asa_*`); the page is 5,157 px. `Re-check` sits top-right; its Viewer state is UNKNOWN.
- **Fix:** P1-3 as written; add vendor pills next to the outcome pills; give `Re-check` the `· Role: not permitted` suffix if refused.

### Backups (24)

- **Purpose:** currency and validation of 131 targets.
- **Who, when:** backup and security admins every morning after 02:00.
- **Serves: no in this state.** Counts are reconciled and explained (`124 of 131 · 125 devices hold a stored backup: 1 of them is not a backup target`, `Backup-capable · 132`). Contrast fixed on `Run Fleet Backup · Role: not permitted`. But the table now stacks `Latest attempt`, `COMPLETED: COMPLETED`, `Backup Now`, `Snapshot`, `History ★ Contents Con…`, `Dow…` in a 170 px row whose right edge is cut off; 3.5 rows visible; no pagination; toggles still look live; deviation still amber for every row while the card says `UNKNOWN · 0 comparisons run`; KPI cards still at the bottom.
- **Fix:** one-line rows: `LATEST ATTEMPT` as its own column, the four actions in a `⋮` menu per row (every action and link kept), `Backup Now · Role: not permitted` as the menu's first disabled item. Pagination `1–50 of 131`. Toggle rendered as a read-only pill `Target · locked for Viewer`. Deviation pill neutral grey `Bytes changed` until a comparison has run. KPI cards above the table.

### Operations: HA & readiness (25)

- **Purpose:** failover readiness across 45 clusters.
- **Serves: yes, with naming gaps.** Readiness is fresh (just now to 48 min ago), so the staleness banner is gone; 20 + 4 + 16 + 5 = 45. `Schedule collection · Schedule authoring is not available yet` is a product-state reason, which is honest. Raw `CHECK_NOT_READY`, `INSUFFICIENT_EVIDENCE`, `COMMAND_UNAVAILABLE`, `TRUSTED_CONNECTION_REQUIRED` remain next to one plain-English reason ("the readiness job was cancelled before the run started"). `CLS-LIMA-09` is `Unknown` with `CHECK_NOT_READY` evaluated 26 min ago, the same reason as the four `Not ready` rows. `Mode: UNKNOWN` on Check Point clusters whose role is known. `50 readiness records` still unexplained. No `Run readiness check` button; checkboxes have no action bar.
- **Fix:** P1-5 parts 2 and 3. Define the Unknown rule on screen (`Unknown = no result in the last 24 h`) or reclassify. Add a selection bar when a box is ticked (`Run readiness on selected · Role: not permitted`).

### Operations: Jobs (26), Queue (27), History (28); Administration Job Logs (35)

- **Purpose:** job execution state for 19,515 jobs.
- **Serves: partly.** Names are masked (council item 9 verified). Jobs, History and Job Logs are the same table with a different state preset; three places to learn. Job types mix raw IDs (`pan_failover_readiness`, `cp_system_log_collect`) with one human label (`Check Point · read inventory`). `RESULT` is `—` on every row and `TRANSCRIPT` is empty, so two of seven columns carry nothing. Management-server jobs show `Unknown device` although the policy source is known. `Live polling: ON` is styled as the primary call to action.
- **Fix:** one label map for job types (`Palo Alto · failover readiness`), raw ID on hover. Name the target for policy jobs (`FW-JULIET-01 · Panorama`). Hide `RESULT` and `TRANSCRIPT` when every row is empty and show `Result: not recorded for this job type` in the row detail. Render polling as a toggle, not a button. Keep Job Logs in Administration as the same component with a one-line note `Same records as Operations › Jobs`.

### Operations: Diagnostics (29)

- **Purpose:** run an approved read command, see output and history.
- **Serves: partly.** Device list (81) and history work. `ACTOR` shows `SN-2D0959634B`, a serial-style pseudonym for a person; times are `10/17/2026, 3:16:36 AM` (US locale) against ISO GMT+3 everywhere else; vendor chips are raw `check_point`, `cisco_asa`, `fortinet` and Palo Alto is absent (intended or not: UNKNOWN); the footer says `1–50 of more than 50`.
- **Fix:** mask actors with a user-shaped token (`USER-…`); ISO timestamps; vendor display names; state the Palo Alto scope in the list header; a real total in the footer.

### Administration: Device management (30)

- **Purpose:** the authoritative registry.
- **Serves: partly.** P0-3 part 1 fixed (full hostnames, `ENROLLMENT STATE` spaced, one spelling). `Import from manager` and `Add device` say `Role: not permitted`. New defect: three hostnames appear twice with different `LAST COLLECTION` times. Whether this is two registry rows, an HA pair sharing a pseudonym, or a masking collision is UNKNOWN, but a registry must never show two identical identities. Still no search, no state filter, no pagination; the last row is cut.
- **Fix:** show the serial pseudonym or the vendor device ID as a second line so rows are distinguishable, and investigate the duplicate. P2-3 for search, filter pills, pagination.

### Administration: Inventory exclusions (31), SSH trust (32)

- **Serves: yes.** Clean empty state with the rule explained; trust mode shown read-only. Minor: the `Trust mode` floating label overlaps the field border.

### Administration: System (33)

- **Purpose:** service health and storage footprint.
- **Serves: partly.** `What the evidence takes` (871 GB backups, 596 MB configuration evidence, per-vendor archive table, largest footprints) is exactly what a platform owner needs. Above it, a Kubernetes pod table with image digests, e2e test pods (`ui2-e2e-… Failed`, `aiview-shots-…`) is engineering noise for every audience that reaches this page. Vendor names mix `bluecoat`, `cisco_asa`, `fortinet` with `Check Point`, `Palo Alto`; kind `system_log` is raw.
- **Fix:** Storage first; Pods behind a disclosure `Service pods (14) ▸`; display names throughout.

### Administration: Delivery plan (34)

- **Serves: UNKNOWN.** An 11,110 px roadmap with a progress percentage and a long feature backlog; too dense to assess at capture scale. Observation only: it is reachable by a Viewer and has no table of contents.
- **Fix:** sticky section index; collapse completed items by default.

### Packet Analysis (36)

- **Serves: yes.** `+ New case` is present and disabled with `Your role does not allow this action`; the duplicate `Masked view` badge is gone (P0-5 part 3 fixed). Remaining: `No case yet` is a dead table row.
- **Fix:** turn the row into the two-sentence workflow explanation Argon proposed.

### Data Sanitizer (37)

- **Serves: yes.** English subtitle (P2-4 part 1 fixed). Refusal copy still generic; `Revert` tab greyed with no reason.
- **Fix:** name the role and permitted roles in the dropzone; tooltip on Revert.

## 3. Visual quality

| Theme | Screen, element | Finding | Fix |
| --- | --- | --- | --- |
| Hierarchy | Backups (24), fleet table row | Six stacked items per row; the action buttons are visually heavier than the archive data. | One-line row; actions in a `⋮` menu; `LATEST ATTEMPT` as a column. |
| Hierarchy | Device Backup tab (09) | 25 attempt lines before the untitled archive table. | Archives first under `Stored archives (6)`; attempts behind `Show all attempts ▾` (P1-4). |
| Hierarchy | Configuration tab (08, 15) | Two `Collect now` buttons on one screen in two styles (white secondary in header, blue primary in tab). | Keep the header one; the tab's slot shows `Last read 00:02 · 6 h ago`. |
| Hierarchy | Administration System (33) | Pod table above the storage summary. | Storage first, pods collapsed. |
| Hierarchy | Devices landing (01) | 70 % of the pane is empty grey. | Estate inventory summary in the pane (P1-1). |
| Density | Palo Alto routing (14), Compliance (23), Delivery plan (34) | Single pages of 16,915 px, 5,157 px and 11,110 px with no paging or index. | Page routes at 50; keep the 64 controls but add a sticky filter bar; section index on the plan. |
| Density | Administration registry (30) | Card-per-row with 16 px gaps shows 9 of 135 entries in the viewport. | Plain table rows; pagination. |
| Density | PAN interfaces (04, 12) | 18 `Physical / VS0` rows of dashes lead the table. | Group by VSYS, physical group collapsed by default (P2-2); nothing removed. |
| Typography | Operations Jobs (26, 28, 35), Diagnostics (29), System (33) | Raw identifiers as labels: `pan_failover_readiness`, `check_point`, `cisco_asa`, `system_log`, `CHECK_NOT_READY`. | One presenter map; raw value on hover or as a muted suffix. |
| Typography | Backups (24), Backup tab (09), Cluster members (07) | `COMPLETED: COMPLETED`, `CANCELLED: cancelled:`, `COMPLETED (completed in 8038ms)`. | Strip the repeated word; `Completed · 8.0 s · 06:01`. |
| Typography | Logs PAN (16) | `Loglar · system logs collected…` mixes Turkish into an English screen. | `Logs · system logs collected…`. |
| Typography | Identity tab (11) | `MODEL` wraps to four lines, `SOFTWARE VERSION` is monospace while `MODEL` is proportional. | Fixed column widths; one font per value type. |
| Spacing, alignment | Policy Lookup (22) | Placeholders truncate at `(optio…`; the submit is grey text with no button shape. | Shorter labels above the fields; a real disabled button with reason. |
| Spacing, alignment | Cluster view (02), header | `CLS > CLS-APOLLO-01` plus `CLS` badge plus `CLS` tree tag. | Title `CLS-APOLLO-01`; the badge keeps the kind. |
| Spacing, alignment | Member header (05), `Open assigned policy` | A bare link floats above the card, unaligned with anything. | Right-aligned pill inside the header card. |
| Spacing, alignment | SSH trust (32) | Floating label `Trust mode` overlaps the select border. | Standard label above the field. |
| Colour | Logs PAN (16), `no new crash or dump files`, `collected 2` | Green pills for an absence and for a count: zero is neutral, never green. | Neutral grey pills; green only for `OK` paired with the word, which the `config OK` chips already do. |
| Colour | Logs PAN (16), Gaps panel | `initial baseline` in red-tinted pills for a normal first run. | Grey for informational, amber only for `gap suspected`. |
| Colour | Backups (24), `Changed since last` | Amber on every row for byte-digest change while the card says `0 comparisons run`. | Neutral until a semantic comparison exists (Argon P1-4, still open). |
| Colour | Overview (00), BACKUPS and EVIDENCE bars | Thin red and amber slivers under positive headlines with no legend. | In-line label on the sliver; see §5 P2-4 for why not a filled bar. |
| Colour | Operations Jobs (26), `Live polling: ON` | Primary blue button for a state toggle; it outranks `Refresh` and `Export CSV`. | Switch control with label. |
| Empty states | Policy Objects (19) | Blank white card, no text at all. | Same `Select a container` card as Hygiene, with the container count. |
| Empty states | Policy Changes (21) | Six counters show `—` while loading; no spinner, no error path visible. | `Loading…` in the counter, then a number or `UNKNOWN`. |
| Empty states | Packet Analysis (36) | `No case yet` as a table row. | Two-sentence workflow explanation in a card. |
| Refused states | Whole product | Now three patterns: `· Role: not permitted` suffix (01, 24, 30, 09), `Your role does not allow this action` sentence (36, 37, 09), `Configuration settings not shown for your role` card (08, 15). The suffix pattern is the clearest. | Suffix on every refused control; the sentence only where there is no control; the card names the role and the permitted roles. |
| Refused states | Devices (01, 02, 04), `Bulk Collect`, `Read configuration, all`, member `Collect now` | Enabled look with no suffix while `Add device` carries one. Whether Viewer may collect is UNKNOWN. | Same suffix rule: `· Next window: 12:00` or `· Role: not permitted`. |
| UNKNOWN states | Device headers (05–16) | `Lifecycle: UNKNOWN (…)` is a loose line between card and tabs on every device. | Cell in the Platform identity grid. |
| UNKNOWN states | Operations HA (25), `Mode: UNKNOWN` | Shown for Check Point clusters whose roles are known. | Write `Mode: not read` only when the reason differs from the status reason; otherwise omit the line. |
| Consistency | Timestamps | ISO GMT+3 on most screens, `UTC` pickers on Policy Changes (21), `10/17/2026, 3:16:36 AM` on Diagnostics (29), `Europe/Istanbul` in Logs and Backups copy, `GMT+3` in the top bar. | One format `2026-10-18 06:49 GMT+3`; relative age as a muted suffix. |
| Consistency | Vendor labels | `CP / PAN / FTNT / ASA` badges on Overview and Admin; `check_point` chips on Diagnostics; `bluecoat` and `Palo Alto` in one table on System. | One vendor label set. |
| Consistency | Pseudonyms | Policy source `FW-JULIET-01` versus `FW-WHISKEY-08`; registry duplicates; Diagnostics actor `SN-…`. | Mask one canonical field per entity type; actor tokens get their own prefix. |

## 4. The new Logs tab

The captures show two states, not one. `FW-APOLLO-01-M1` (10, 13) shows the empty state. `FW-HELIOS-02` (16) shows a filled state from a pilot run at 06:13:03, and Administration › System (33) counts 34 Check Point and 6 Palo Alto `system_log` archives over 5 devices. So the brief's "no collection has run yet" is true for the fleet, not for the pilot.

### Empty state (10, 13)

- **Honest and calm:** `No collection yet`, the four windows named, a `Refresh` button, no fake zero. This is the right pattern and Argon 2.8 stands.
- **One contradiction:** Operations › Jobs (26) lists `cp_system_log_collect` for `FW-APOLLO-01` as COMPLETED at 06:05:20 in 3.3 s. Either the job collected nothing or the tab on the member does not find data stored under the cluster name. Cause UNKNOWN. In both cases "No collection yet" is wrong: the state must be `Last collection 06:05:20 · COMPLETED · 0 lines stored` so the operator sees that a run happened.
- **Fix:** three sub-states on one card: `No collection yet · next window 12:00`, `Collected · 0 lines`, `Collection failed · <reason>`. Timezone written once as `Europe/Istanbul (GMT+3)`.

### Place on the device page

- The tab belongs on the device screen; the ordering `Interfaces · Routing · Cluster members · Configuration · Backup · Logs · Identity` is acceptable. I disagree with Argon 4.2 on moving Logs before Configuration: logs are a per-member evidence plane like Backup, and keeping the two evidence tabs adjacent matches how the platform already separates runtime from evidence.
- The cluster view (02, 03, 04) has no Logs tab, so an operator on a cluster must guess which member to open. Add `Logs` on the cluster view with the M1 / M2 pills already used in the member header; the per-member stores stay as they are.

### Filled state (16): what is there and what it should become

What exists today, all of it kept: last collection time and device-time window, `Sources in that run` chip, stored totals (`4 chunks · 3,331 lines · 2.2 MiB`), crash and dump file check, per-source status chips (`config OK`, `system OK`), day and chunk pickers, line filter, masked raw lines with a 256 KiB page, a Gaps panel, an HCP reports card.

What an operator needs first, using only fields the product already shows:

1. **Summary strip (top).** `Last collection 06:13 · 34 min ago · COMPLETED · window 00:15–06:11 device time` on one line; then per source: `config · OK · N lines`, `system · OK · N lines`. Counts per source are derivable from the chunk list and are not shown today. Severity counts are **UNKNOWN** from the capture: the lines are raw PAN-OS JSON and no parsed severity field is visible, so no severity chips until the parser exists; do not invent them.
2. **Attention line.** Gaps and crash checks move up into one neutral sentence: `2 sequence gaps suspected (config 00:15, system 00:15) · 2 initial baselines · no new crash or dump files` in grey, each a link to the panel below. Today they sit in a right column with red-tinted pills for the harmless `initial baseline`.
3. **Picker row.** Day and chunk pickers stay but read as a sentence: `Day 2026-10-18 · run 2 of 4 · 06:13 · 13 lines · 7.5 KiB`. The `Masked view` pill stays next to the line filter.
4. **Lines.** The raw viewer stays as the detail layer. Give it a fixed column for the device timestamp (`receive_time` already present in every line) so the eye can scan time without reading JSON; keep the full line on expand. `13 lines loaded · End of chunk` footer stays.
5. **Side cards.** `Crash and dump files` and `HCP reports` keep their explanatory sentences; they move below the lines on narrow widths.

Language: `Loglar` becomes `Logs`; `seq gap suspected` becomes `Sequence gap suspected`; `initial baseline` becomes `First collection: older content not fetched`. Colour: `no new crash or dump files` and `collected 2` in neutral grey, `OK` chips keep green with the word.

Same layout on Check Point once its collector returns lines; the only vendor-specific element is the source set, which follows PO decision 3 (no chip for a source the vendor does not have).

## 5. Argon cross-check

Status against the 2026-10-18 captures. "Disagree" explains why.

| Argon ID | Status | Evidence, reason |
| --- | --- | --- |
| P0-1 Estate map false green, No archive encoding | Fixed | ASA 12 and Fortinet 10 hatched, Not assessed 35, Clear count gone from legend, seven red rings on Check Point tiles (00). |
| P0-2 Count reconciliation | Fixed (3 of 4), partly (1) | Compliance sums exact and match Overview (00, 23). Backups `124 of 131` with the 125 explained; `Backup-capable · 132` relabelled (24). Retention copy now `7 days · Gaia snapshots: newest 1 kept per gateway` (24). Still open: Devices landing says 135 enrolled (01). |
| P0-3 Clipping, collision, truncation | Partly | Admin fixed (30); Check Point interface tables fit (02, 03, 05). Not fixed: PAN `NETWORK` (04, 12), Identity tab (11). Backups is worse (24). |
| P0-4 Policy containers and summary table | Fixed | 59-container table, chevrons, `Actions ▾` (17). |
| P0-5 Contrast and Viewer affordances | Partly | Dark-on-dark gone; `Role: not permitted` on Add device, Import, Request backup, Run Fleet Backup, New case (01, 09, 24, 30, 36). Still open: Reason input enabled (09), target toggles live (24), `Bulk Collect` / `Read configuration, all` / member `Collect now` enabled with no suffix (01, 02, 04), Configuration notice unchanged (08, 15), Sanitizer refusal generic (37). |
| P1-1 Device header ages, one Collect now, Devices landing summary | Not fixed | Timestamps still in the grid (08, 15); two Collect now buttons; pane still empty (01). |
| P1-2 Tree and member navigation | Not fixed | Highlight lost on member open (05); no members under `CLS-APOLLO-01` (01); tree not scrolled to `FW-HELIOS-02` (12); MACs unlabelled (02). |
| P1-3 Compliance vendor filter and sort | Partly, by another route | No vendor dropdown or vendor badges; ID order kept. Added instead: Control families block and outcome pills `Failing 40 · Not yet checked 17 · Passing 7` (23). Vendor filter still worth doing. |
| P1-4 Backup hierarchy, bytes, doubled status, fleet KPIs | Not fixed | All four defects visible (09, 24). Pagination exists on the device tab only. |
| P1-5 Run readiness, humanise reasons, HA on Overview | Partly; disagree on part 1 priority | Staleness resolved by scheduled runs (`just now` to `48 min ago`, no banner, 25). Raw enums remain; Overview has no HA row. Disagree: a manual `Run readiness check` button is P2 now that the scheduler keeps readiness fresh; the Unknown-versus-Not-ready rule matters more. |
| P1-6 Logs empty state and filled state | Partly | Empty state unchanged (10, 13). A filled state exists on PAN (16) but as raw lines without a summary layer; see §4. |
| P2-1 Rail items for Operations and Backups | Not fixed | Rail unchanged (24, 25, 30, 37). |
| P2-2 VSYS filter on PAN interfaces | Not fixed | Zone wrapping fixed; filter and grouping absent (12). |
| P2-3 Registry search, filter pills, pagination | Not fixed | None present (30). Job Logs confirmed as the same 19,516-job table (35). |
| P2-4 Sanitizer language, Overview bars and vendors | Partly; disagree on bars | `Data Anonymization` fixed (37). Fortinet and ASA still absent from Software in service (00). Disagree with the stacked-fill bar: a 95 % filled bar reads as "good" by shape even in slate blue, which is the same signal the green rule forbids. Keep the defect-only bar and label it in-line. |
| 2.3 (2) Legacy Configuration route | UNKNOWN | Not in this capture set. |
| 2.6 (4) Rename Identity tab | Disagree | Its contents are now visible (11): the same fields as the Configuration grid plus the reference ID. Merge into the grid instead of renaming (§2 Identity). |
| 4.2 Logs before Configuration | Disagree | See §4; keep evidence tabs adjacent. |

### 2026-09-25 council items still open

| Council item | Status on 2026-10-18 | Where it shows |
| --- | --- | --- |
| PO 1: one device screen, header with read ages, one Read now | Partly | Tabs exist; ages still in the grid; two Collect now controls (08, 15); `This cluster in:` strip remains (02). |
| PO 3: show nothing a vendor lacks | Partly | PAN standalone drops Cluster members and Failover (15). `Lifecycle: UNKNOWN` line on every device (05–16); 18 dash-only physical rows lead PAN tables (04, 12). |
| PO 4 / ASA contract | UNKNOWN | No ASA device capture. |
| Agreed 2: device screen shows its compliance and backup status with links | Partly | Backup tab exists; no compliance summary or link in any device header (05–16). |
| Phase 1: one count vocabulary | Partly | Overview, Compliance, Backups agree; Devices landing text disagrees (01); Operations header `1 in flight` versus Queue `2 in flight` is capture timing, not a defect. |
| Phase 1: plain failure causes, no raw codes | Partly | Device lifecycle fixed; Operations reasons and job types still raw (25, 26); gap pills jargon (16). |
| Phase 3: estate charts move to Devices | Not done | Software in service still on Overview; Devices pane empty (00, 01). |
| Onboarding 1–5 | UNKNOWN | No onboarding capture; the write CTAs are now disabled with reason (01, 30). |

## 6. Prioritised changes

No item removes a field, click-through, action, RBAC boundary or masking rule; where something moves, its new home is named. Effort: S under a day, M a few days, L a week or more.

| ID | Pri | Screen | Change | Effort | Capability preserved, where it lives |
| --- | --- | --- | --- | --- | --- |
| F-1 | P0 | Administration registry (30) | Resolve the three duplicated hostnames; until then show the serial pseudonym as a second line on every row so identities are distinguishable. | S | All 135 rows, menus and checkboxes stay. |
| F-2 | P0 | Policy (17) | Mask the management source from one canonical field so the tree and the table show the same name. | S | Both sources, all 59 containers, all actions stay. |
| F-3 | P0 | Devices landing (01) | One count sentence from one query: `135 registered · 133 enrolled · 133 live`; copy lists all seven tabs. | S | Nothing removed. |
| F-4 | P0 | Backups (24) | One-line rows; `LATEST ATTEMPT` column; `Backup Now`, `Snapshot`, `History`, `Contents`, `Compare`, `Download` in a per-row `⋮` menu with the refused ones disabled and suffixed; pagination `1–50 of 131`; target toggle as read-only pill; deviation neutral until a comparison ran; KPI cards above the table. | M | Every action and link moves into the row menu; every card stays. |
| F-5 | P0 | PAN cluster and standalone interfaces (04, 12), Identity (11) | Column widths so `NETWORK` and `MANAGEMENT ADDRESS` never clip at 1440 px; wrap `CONTENT VERSIONS` (15). | S | All columns readable. |
| F-6 | P0 | Logs tab (10, 13) | Three empty sub-states: no collection yet, collected with 0 lines, collection failed with reason; reconcile with the completed `cp_system_log_collect` job. | S | Refresh and windows stay. |
| F-7 | P0 | Devices (01, 02, 04), Configuration (08, 15) | One suffix rule on every gated control: `· Next window: HH:MM` or `· Role: not permitted`; Reason input disabled for Viewer (09); one `Collect now` per device, the tab slot shows the last read age. | S | Every trigger stays, once. |
| F-8 | P1 | Logs filled state (16) | Summary strip, attention line, sentence-style pickers, timestamp column on raw lines, English labels, neutral colours; same layout on Check Point. | M | All chunks, lines, gaps, crash and HCP cards stay. |
| F-9 | P1 | Device header (05–16) | Read ages in the header; `LIFECYCLE` and `REFERENCE` cells in the Platform identity grid; Identity tab becomes an anchor to the grid; `HA role:` line removed where the badge and grid already show it. | M | All identity fields in the grid, exact timestamps kept. |
| F-10 | P1 | Policy tabs (19–22) | Shared empty card for Objects, Hygiene, Changes; `UNKNOWN` or numbers instead of `—`; GMT+3 pickers; disabled-button styling on Lookup submit. | S | All tabs and filters stay. |
| F-11 | P1 | Operations (25, 26, 28), Diagnostics (29), System (33) | One label map for job types, reasons, vendors; actors masked with a user-shaped token; ISO timestamps; `Unknown device` replaced by the management source name; hide empty `RESULT` and `TRANSCRIPT` columns with a row-detail note. | M | Raw values on hover; every column available via row detail. |
| F-12 | P1 | Operations HA (25) | On-screen rule for Unknown versus Not ready; `Mode` line only when it adds information; selection action bar for the checkboxes. | S | All rows and filters stay. |
| F-13 | P1 | Overview (00) | `HA READINESS` row; in-line labels on the sliver bars; Fortinet and ASA rows in Software in service. | S | Existing cards untouched. |
| F-14 | P1 | Compliance (23) | Vendor pills beside the outcome pills; default sort Non-compliant Critical first; sticky filter bar. | S | All 64 rows, families and cards stay. |
| F-15 | P1 | PAN routing (14) | Page at 50 routes with the existing filter. | S | All 371 routes reachable. |
| F-16 | P1 | Device Backup tab (09) | Archives first with a title; attempts collapsed; MB with bytes on hover; `Checked (V1)`; single status word. | S | All 25+ attempts and 6 archives stay. |
| F-17 | P2 | Rail (all) | Pin Operations and Backups; Sanitizer and Administration under the drawer with an active state. | S | All routes stay. |
| F-18 | P2 | Devices tree (01, 05, 12) | Members under non-VSX clusters; keep the parent highlighted; auto-scroll to the open device. | M | Tree content grows, nothing removed. |
| F-19 | P2 | PAN interfaces (04, 12) | Group by VSYS with the physical group collapsed; `With address only` chip. | S | All rows under their group. |
| F-20 | P2 | Administration System (33), Delivery plan (34) | Storage first, pods behind a disclosure; section index on the plan. | S | Every pod and plan item stays. |
| F-21 | P2 | Cluster view (02) | Logs tab with M1 / M2 pills; title without `CLS >`; `Backups` and `Readiness` as pills in the header. | M | Both links and all tabs stay. |
| F-22 | P2 | Registry (30), Packet Analysis (36), Sanitizer (37) | Search and state pills with pagination; workflow text in the empty case row; role names in refusal copy. | S | Nothing removed. |
