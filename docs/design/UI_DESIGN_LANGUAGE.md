# neXus UI design language

Status: `FROZEN` for presentation (Phase 3.1 of the UI product review of 2026-10-18, PO approved 2026-10-19).
It changes nothing a screen fetches, shows or allows; it fixes how one fact looks wherever it appears.

Source of truth in code: `ui2/frontend/src/theme/tokens.ts` (tokens), `ui2/frontend/src/theme/m3Theme.ts` (the
light and dark token sets and the MUI theme), `ui2/frontend/src/shell/` (the seven components). Tests:
`ui2/frontend/tests/DesignTokens.test.ts`, `ui2/frontend/tests/DesignLanguageComponents.test.tsx`.

## 1. Rules that never bend

- A state is a **word with a colour**, never a colour alone. Colour follows the word; the word is read.
- `UNKNOWN` is written UNKNOWN; absence is never `0` and never `—`. A zero that was counted is a grey `0`.
- Green only where the word would be read (`Live`, `Ready`, `Completed`); never on a count, never on an absence.
- No composite score, no judgement word (`healthy`, `outdated`). Only what was observed and when.
- Identity (vendor, HA role, hostname) is never a status colour; role chips stay black outline or fill.
- Number first, noun second, denominator third, age last: `123 of 130 targets · stored backup · 02:04 · 8 h ago`.
- Upper-case machine tokens become sentence case through the label map (`COMPLETED` → `Completed`,
  `ENROLLED` → `Enrolled`, `NOT EVALUATED` → `Not evaluated`); `UNKNOWN` and HA roles keep their case.
- aiview masking is presentation-independent: every fixture and preview renders `FW-TANGO-04` and `192.0.2.x`.

## 2. Tokens

### 2.1 Type scale: five steps, nothing else (`TYPE`)

| Step | Size / line | Weight | Used for |
|---|---|---|---|
| `pageTitle` | 22 / 28 | 600 | the screen title (`h2`), an object header's name |
| `section` | 16 / 22 | 600 | a card or section title (`h3`, `h4`, `h6`, `subtitle1`), a state card's title |
| `body` | 14 / 20 | 400 | running text (`body1`), sentences in cards |
| `label` | 12 / 16 | 500 | pills (sentence case), captions (`body2`, `caption`); `LABEL_UPPER` (600, 0.04 em, uppercase) for column and card labels |
| `mono` | 13 / 18 | 400 | addresses, serials, hashes, versions, timestamps; also the text size of every compact table cell, so a word and a machine value share one baseline in a row |

One numeric display size exists outside the text steps: `KPI_FIGURE` 28 / 32 semibold tabular, used only for a
KPI figure (`h1`). Every value of one kind uses one step: a model and a software version in the same row are both
mono.

### 2.2 Spacing and radius

`SPACE` is a 4 px scale (4 · 8 · 12 · 16 · 24 · 32): 8 px gutters in compact tables, 16 px in comfortable cards,
16 px card padding, 24 px between sections. `RADIUS`: pill and input 8, card 10, button 20 (a 40 px button is
fully round).

### 2.3 Colour roles (`ROLE_COLORS`, `roleValues(mode)`)

Each role is a `bg` / `fg` pair of CSS variables, so a screen never decides a colour; the one `data-theme`
attribute on `<html>` switches light and dark. Resolved values (light → dark):

| Role | Words | bg | fg |
|---|---|---|---|
| `bad` | Not ready, Failed, Certificate expired, critical failing | `#fbe7e7` → `#3a1d1f` | `#8f1f1f` → `#ffb4b4` |
| `warn` | stale, partial, not confirmed, Last collection failed | `#fdf1d6` → `#3a2f12` | `#8a5a00` → `#ffd98a` |
| `ok` | Live, Identity verified, Ready, Up, Completed, Compliant | `#e3f5e3` → `#16301a` | `#087a08` → `#7ee07e` |
| `neutral` | every count, every 0, Not applicable, Not read yet, locked | `#e3e7ec` → `#272e38` | `#5b6473` → `#a5adba` |
| `unknown` | UNKNOWN, Not assessed, Not evidenced, Not evaluated | neutral fill **hatched** (`UNKNOWN_HATCH`) | `#5b6473` → `#a5adba` |
| `role` | ACTIVE / STANDBY, PRIMARY / PASSIVE | transparent, outline `#9aa3b0` → `#5b6473`; ACTIVE filled `onSurface` | `onSurface` |
| `brand` | links, the one primary action, the selected tab | `#eaeefb` → `#1f2a4d` | `#1e2f7a` → `#c9d5ff` |
| `mem` | Member-specific (alignment) | `#fbf4dc` → `#33291a` | `#7a5c12` → `#f0d68e` |
| `attn` | Difference observed (alignment) | `#fce8df` → `#3a2419` | `#b0421a` → `#ffbfa3` |

Every pair reads at 4.5:1 or better in both modes (tested). The vendor swatches `cp`, `vsx`, `pan` are identity,
not state, and stay in `m3Theme`. `isUnknownWord()` names the four hatched words so a caller that passes
`neutral` with `UNKNOWN` still gets the hatch; nothing ever softens `bad` or `warn`.

### 2.4 Density: two modes, per screen type (`DENSITY`, `SCREEN_DENSITY`)

| Mode | Gutter | Row | Where |
|---|---|---|---|
| `comfortable` | 16 px | 44 px | summary screens (Overview), object pages (device, cluster, VS, manager), tool pages (Packets, Sanitizer) |
| `compact` | 8 px | 32 px one-line rows, mono for machine values | list screens (Backups, Compliance, Operations, Policy, Administration, Lifecycle) and every data table wherever it sits |

Density is **not a user preference**: `ScreenRoot screenType="list"` (or `object`, `summary`, `tool`) wraps the
screen in a `DensityScope`; the MUI table rows and cell padding under it follow. The data table component is
compact on its own, so a table inside a comfortable object page is still 32 px per row. The former top-bar
rows toggle is gone.

## 3. The seven components (one implementation each, `src/shell/`)

| # | Component | File | Older names that now render it |
|---|---|---|---|
| 1 | **Status pill** `StatusPill` — `word · count · age · suffix`, colour from the role table; link with `href`, button with `onClick`; `· Role: not permitted` / `· Next window: HH:MM` suffix variant for controls | `StatusPill.tsx` | `StatusChip` (M3Widgets), `StatePill` (HeaderStatePills) |
| 2 | **Object header** `ObjectHeader` — title row (avatar, name, health badges, the one action), identity row (vendor · form · model · version · parent), state row (read ages, then plane pills in the fixed order SEE → VERIFY → TRACE → RECOVER → OPERATE), optional link | `ObjectHeader.tsx` | `InventoryEntityHeader` (InventoryPanels) |
| 3 | **KPI card** `KpiCard` / `KpiRow` — label, figure, denominator sentence, one link, optional badge; at most four per row, always above the table; `UNKNOWN` / `Not evaluated` as the figure, never 0 | `KpiCard.tsx` | `MetricCard`, `MetricGrid` (ScreenLayout), `ComplianceMetricCard` |
| 4 | **Data table** `DataTable` / `PaginationFooter` — fixed `table-layout`, column widths, one-line rows with the value on hover, mono columns, row `⋮` menu (`CapabilityMenu`), footer `1–50 of N` with `Go to previous page` / `Go to next page`, a `Per page` select only where a screen offers sizes | `DataTable.tsx` | MUI `TablePagination` on Backups, Routes, Registry, Audit log, Diagnostics |
| 5 | **Empty / refused / unknown card** `StatePanel` — icon, state word, one sentence of reason, one next step (`nextStep`); variants `empty`, `not_evaluated`, `unknown`, `restricted`, `error`; a refusal never offers Retry | `States.tsx` | `EmptyPanel` (ScreenLayout), `RestrictedPanel` |
| 6 | **Label map** `presenters.ts` — vendors, job types, reason codes, actors, outcome words, artefact classes and `statusWord()` (the sentence-case rule); raw value as a muted suffix or on hover through `<Presented>` | `presenters.ts` | — |
| 7 | **Timestamp** `Timestamp` — `2026-10-18 06:49 GMT+3 · 4 h ago` in the mono step, the UTC instant on hover; `compact` (age only, stamp on hover) for a fixed-width fleet column; `seconds` for audit, session and log rows; `UNKNOWN` for an absent value | `Timestamp.tsx` | `Ts` (States), `Stamp` (PresentedText) |

### How to choose

- A fact about state → status pill. A fact that is a number → KPI card (above a table) or a neutral pill (in a
  row). A fact that is an instant → timestamp. Never a bare coloured dot, never a bare number for an absence.
- A screen that lists → compact `DataTable` with `PaginationFooter`; the KPI row above it; the filter bar above
  that. A screen about one object → `ObjectHeader`, then tabs; the first tab's own summary line first.
- Nothing to show → `StatePanel`: say which of the five it is and what would change it (`Next window 12:00`,
  `Operators and Security Administrators can start a collection.`).
- A new vendor name, job type or reason code → add it to `presenters.ts`; never spell it inline.

## 4. Preview and test contract

- A `StatePanel` is `role="status"` (an error is `role="alert"`); a screen-level `EmptyPanel` carries no live
  region. The preview e2e treats any MUI `Alert` that is not info/success as a load error, so a notice is a
  `StatePanel`, not an `Alert`.
- Test hooks: `[data-pill="<role>"]`, `[data-fact]` on header pills, `[data-kpi]`, `[data-kpi-figure]`,
  `[data-kpi-word]`, `[data-state]`, `[data-state-word]`, `[data-next-step]`, `[data-stamp]`, `[data-density]`,
  `[data-testid="pagination-footer"]`, `object-title-row`, `object-identity-row`, `object-state-row`.
