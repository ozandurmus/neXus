# UI 2.0 per-screen fidelity notes

Each product screen was compared against its own `M3*` frame under
`docs/design/ui2_mockups/`. Every screen still renders its required empty
state (the database is empty); this pass brings the *structure* — headers,
actions, tabs, filter chips, the state-chip vocabulary, and the shared
component/drawer states from `M3Components` — to match the frame, without
inventing data the empty database does not have.

Shared components added under `ui2/frontend/src/shell/`: `StatusChip` (the
six-tone chip vocabulary), `M3Tabs`, `ToggleRow` (a disabled `m3-switch`),
`CapabilityMenu` (an overflow menu with a "shown, disabled, explained" item),
`M3Button` (filled/tonal/outlined/text emphases) and `AddDeviceDialogTrigger`
(the enrollment dialog). All are used by more than one screen.

**Correction (this pass).** Every earlier revision of this document said a
screen's tab strip was "matched" against its frame. That claim was wrong:
`M3Tabs` rendered a strip of labels only, wired to nothing, and every screen
underneath it rendered one fixed body regardless of which label was
selected — clicking a tab never changed what was on screen, and
Administration's "Project plan" tab rendered nothing at all. No test caught
it, because no test ever asserted that selecting a tab changed the rendered
output. `M3Tabs` now takes a `panel` per tab and mounts only the selected
one (ARIA `tablist`/`tab`/`tabpanel`, keyboard arrow navigation, only one
panel in the DOM at a time), and each screen below records what changed:
panels, not strips.

## Overview (`M3Overview`)
Matched: header with the two action buttons and the overflow capability
menu, the four metric cards, a "Configuration alignment" panel carrying the
same six-state chip row as the frame (all at zero), a two-panel right column
(Evidence trust / HA readiness).
Not matched: the frame's third row (Compliance coverage, Recovery artifacts,
Fleet composition) is omitted from the empty state. Each of those cards is a
trend line or a distribution bar with nothing to distribute when zero
frameworks are assigned and zero devices are enrolled; a row of chartless
zero-cards duplicated the same "nothing collected yet" message the metric
row and the alignment panel already carry, so it was left out rather than
padded with placeholder charts.

## Inventory (`M3Inventory`)
Matched: list/detail split, the search-field affordance, the vendor/stale
filter chips (now at zero), and the detail pane's four tabs (Interfaces /
Routing / Cluster members / Identity & provenance), each a real ARIA
tabpanel. Selecting a tab renders that tab's own empty-state panel and no
other tab's: Interfaces names the missing interface read, Routing the
missing routing-table read, Cluster members the missing per-peer identity-
verified read, Identity & provenance the missing direct device read. No two
tabs share a sentence.
Not matched: the frame's populated list rows and interface table are
product data that does not exist yet; showing them would be exactly the
regression the per-tab tests guard against, so they stay behind
`?preview=inventory`.

## Configuration (`M3Configuration`)
Matched: list/detail split, the drift/override filter chips, and the
detail pane's seven tabs (Overview / Current state / Alignment / Policy &
objects / History / Evidence / Backup), each a real ARIA tabpanel and
defaulted to Alignment as the frame shows. The frame itself only depicts
the populated Alignment tab; the other six panels each name the specific
evidence their tab needs and does not have (an intent/device summary for
Overview, a direct verified read for Current state, a configuration read
for Policy & objects, repeated collection for History, an export source for
Evidence, the class 1 write's own contract for Backup) rather than
inventing a populated look the frame never showed.
Not matched: the populated alignment table (CMA intent vs. per-member
evidence) — no intent snapshot and no device read exist yet, so there is
nothing to tabulate.

## Operations (`M3Operations`)
Matched: the top-level tabs (HA & readiness / Jobs / Queue / History),
header actions, and the four metric cards (kept visible across every tab,
since they summarize the whole screen rather than any one tab). Each tab is
now a real ARIA tabpanel with its own empty state — HA & readiness names
the missing enrolled cluster and health read, Jobs keeps the existing "no
jobs yet" statement, Queue and History are new and each name what has not
happened yet (nothing scheduled; nothing has run to have a history).
Not matched: the frame's per-cluster readiness cards and job history table
are populated evidence with no analogue in an empty database; each tab's
panel states the absence in prose instead of four or six zero-value rows
duplicating what a card grid would only re-state as zero.

## Compliance (`M3Compliance`)
Matched: header actions, the framework chip row (now reading "not
assigned" for each named framework instead of showing counts against
nothing), and the four metric cards.
Not matched: the control-family table and the findings list are both
populated evidence with no analogue when zero frameworks are assigned;
kept as the single empty panel already in place.

## Administration (`M3Administration`)
Matched: the four tabs, each now a real ARIA tabpanel, header actions
including the enrollment dialog, the device-registry panel with its
overflow capability menu (an enabled read action alongside "Collect now"
shown disabled and explained as console-only, per the canvas's own rule),
the enrollment summary now using the shared chip vocabulary, and three
collection-scope toggles shown off and disabled (no write path exists for
any of them in this movement) — all under the Device management tab, which
is what the frame depicts (its own tab strip shows only that tab selected).
Inventory exclusions, Credentials and Project plan have no populated view
in the frame either; each now gets its own panel naming what is missing
instead of falling through to Device management's body or, for Project
plan, rendering nothing. Credentials' panel carries the frame's own
"credentials are stored outside the repository" sentence, since that line
is specific to the credential column the frame shows empty.
Not matched: the registry table's populated rows, and the frame's
credential-profile column — no device is enrolled and no credential
profile is chosen yet.

## Deliberately unimplemented across every screen
The frame's `M3Components` snackbar ("Device … enrolled as a draft entry")
is not wired up. It fires only after a successful enrollment, and this
movement adds no enrollment write path — the dialog's "Enrol" button closes
the dialog and persists nothing, so showing a success snackbar afterward
would be fabricated certainty, not a rendered surface. The frame's FAB is
likewise a `M3Components` vocabulary entry only; no product frame places a
FAB on an actual screen, so it was not forced onto one.
