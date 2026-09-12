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
filter chips (now at zero), and the detail pane's four-tab strip
(Interfaces / Routing / Cluster members / Identity & provenance) shown
against the "no device selected" panel.
Not matched: the frame's populated list rows and interface table are
product data that does not exist yet; showing them would be exactly the
regression AC-4 guards against, so they stay behind `?preview=inventory`.

## Configuration (`M3Configuration`)
Matched: list/detail split, the drift/override filter chips, and the
detail pane's seven-tab strip (Overview / Current state / Alignment /
Policy & objects / History / Evidence / Backup), defaulted to Alignment as
the frame shows.
Not matched: the populated alignment table (CMA intent vs. per-member
evidence) — no intent snapshot and no device read exist yet, so there is
nothing to tabulate; the frame's populated tabs beyond Alignment imply
policy-object and backup-history views this build does not yet collect for
and are left as tab labels only, not implemented panels.

## Operations (`M3Operations`)
Matched: the top-level tab strip (HA & readiness / Jobs / Queue / History),
header actions, and the four metric cards.
Not matched: the frame's per-cluster readiness cards and job history table
are populated evidence; the empty state keeps its single "no jobs yet"
panel instead of four zero-value readiness cards, since a cluster card with
no member rows communicated nothing beyond what the metric row already
says.

## Compliance (`M3Compliance`)
Matched: header actions, the framework chip row (now reading "not
assigned" for each named framework instead of showing counts against
nothing), and the four metric cards.
Not matched: the control-family table and the findings list are both
populated evidence with no analogue when zero frameworks are assigned;
kept as the single empty panel already in place.

## Administration (`M3Administration`)
Matched: the four-tab strip, header actions including the enrollment
dialog, the device-registry panel with its overflow capability menu (an
enabled read action alongside "Collect now" shown disabled and explained as
console-only, per the canvas's own rule), the enrollment summary now using
the shared chip vocabulary, and three collection-scope toggles shown off
and disabled (no write path exists for any of them in this movement).
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
