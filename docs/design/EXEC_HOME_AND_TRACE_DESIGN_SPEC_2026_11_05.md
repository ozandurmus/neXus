# Executive home and Trace: visual and interaction specification

**Status: DRAFT -- design specification for synthesis items C1 and C3, awaiting PO decision (2026-11-05).**
Nothing here authorizes implementation. It fixes what the two screens look like and how they behave once the PO
approves C1 and C3; it adds no data source, no command and no permission. Where the spec needs a fact the inputs do
not give, it says `UNKNOWN` and lists the question in §6.

Written by the engineering session in the senior product/UI designer role from repository evidence only: the three
reviews of 2026-11-05, the frozen design language and the token and component sources in `ui2/frontend`. No live
system or device was reached. Every example uses the masked vocabulary (`FW-TANGO-04`, `CLS-ROMEO-01`, `192.0.2.x`);
counts are illustrative.

## 0. Inputs and authority

| Input | Status | What it fixes here |
|---|---|---|
| `docs/design/UI_DESIGN_LANGUAGE.md` | FROZEN (2026-10-19) | Type steps, spacing, radii, colour roles, density, the seven components. This spec adds nothing outside them except where §0.1 says so. |
| `ui2/frontend/src/theme/tokens.ts`, `theme/m3Theme.ts` | source of truth for the above | Token names used below (`TYPE`, `SPACE`, `RADIUS`, `ROLE_COLORS`, `m3.*`). |
| `ui2/frontend/src/shell/*` | the seven components | `StatusPill`, `KpiCard`/`KpiRow`, `StatePanel`, `Timestamp`, `ObjectHeader`, `DataTable`, `M3Tabs`, `M3Button`, `Meter`, `StackedBar`. |
| `UI_REVIEW_2026_11_05_ARGON.md` §1.2, §1.3, §3, R-15, R-20 | review (level 6) | The six-element home, the Path/Lookup merge, the field-level simplifications. |
| `UI_REVIEW_2026_11_05_FABLE.md` | review (level 6) | Verdict word, five attention lines with age, bars and pulse below the fold, two modes with distinct meaning, one freshness scale. |
| `UI_REVIEW_2026_11_05_SYNTHESIS.md` C1, C3, B1, B4, C7 | DRAFT, awaiting PO | The scope of this spec. |
| `PO_DECISION_RECORD_2026_11_03_PATH_ANALYSIS_PHASE_A.md` | RATIFIED | Path analysis boundaries (masked input, no stored result, audit before result). Unchanged here. |

### 0.1 Contradiction report (must be resolved by the PO, not by this spec)

**Resolved 2026-11-05.** The PO approved C1. `UI_DESIGN_LANGUAGE.md` now carries the "Amendment 2026-11-05 (PO
decision C1, UI review 2026-11-05)", which admits exactly `HEALTHY` / `AT RISK` / `UNKNOWN` on the executive home's
verdict banner and nowhere else. The verdict word may be implemented as §3.3 specifies. The original report follows
for the record.

`UI_DESIGN_LANGUAGE.md` §1 (FROZEN) says: *"No composite score, no judgement word (`healthy`, `outdated`). Only what
was observed and when."* Synthesis item C1 (DRAFT) asks for a verdict word `HEALTHY / AT RISK / UNKNOWN`. The two
disagree. This spec designs the verdict word as C1 asks, but in the narrowest form that stays closest to the frozen
rule: the word is **not a score**; it is a deterministic restatement of four observed facts whose rule is printed
beside it (§3.3). Approving C1 as specified here requires an amendment to §1 of the design language that permits
exactly these three words on the executive home and nowhere else. Until that amendment exists, the verdict word must
not be implemented. Everything else in this document is independent of that decision.

### 0.2 Decisions this spec waits on

**Resolved 2026-11-05.** The PO approved all B and C items of the synthesis: B1 (one freshness scale, §2.3
thresholds), B4 with CIS Benchmark and NIST first as the primary frameworks, and C3 with `Changes` in the `TRACE`
group. The "+24 h" delta stays optional and absent until a daily snapshot exists.

- **B1** one product-wide freshness scale (this spec proposes the thresholds in §2.3 and uses them throughout).
- **B4** honest compliance headline and primary framework order (Financial Baseline first?). §3.6 shows both orders.
- **C3 routing**: whether `Changes` joins the new `TRACE` rail group in the same build. §4.1 reserves the slot; the
  Changes screen itself is out of this spec's scope.
- **Daily snapshot for the "+24 h" delta** on the pillars: whether one is stored is `UNKNOWN` (Fable). §3.4 specifies
  the delta as optional and absent, never dashed, when no history exists.

## 1. Scope and non-goals

In scope: (1) the executive home that replaces Overview; (2) the Trace screen with its Path and Lookup modes, the path
diagram and the advanced fields. For both: layout grid, spacing, typography, state colours, empty/stale/error states,
behaviour at 1280, 1440 and 1920 px, accessibility, and the exact copy of every label.

Out of scope: Devices (C5), Policy tabs (C2), the unified Changes screen (C3 second half), the weekly executive
deliverable (C6), any API or payload change, any role change. The spec never widens what a role may see or do.

## 2. Shared foundations

### 2.1 Canvas, grid and spacing

The shell is unchanged: navigation rail on the left (persistent with labels from 1100 px, icons only and a `≡` drawer
below), top app bar above, the screen in `ScreenRoot` (`p: 4` = 32 px sides, `pt: 1` = 8 px top, section gap
`SPACE.xl` = 24 px). Both screens are `summary`/`tool` screen types: comfortable density (16 px gutters, 44 px rows).

Inside the canvas both screens lay out on a **12-column grid**, gutter `SPACE.lg` (16 px), no outer margin beyond the
root padding. Column widths follow from the canvas; nothing is pixel-fixed except maxima:

| Viewport | Rail | Canvas width (inner) | Column width | Content max width |
|---|---|---|---|---|
| 1280 | 232 px labelled rail (or 72 px folded) | 1280 - 232 - 64 = 984 | 67.3 px | full canvas |
| 1440 | 232 px | 1144 | 80.7 px | full canvas |
| 1920 | 232 px | 1624 | 120.7 px | **1400 px**, left-aligned; the right remainder stays empty surface |

The 1400 px cap keeps a 4-up pillar row readable (one card ≈ 338 px) and a line of attention copy under 90
characters. Nothing centres: the eye starts at the top-left on every neXus screen.

Vertical rhythm: section gap 24 px; inside a card 16 px padding and 12 px (`SPACE.md`) between rows; 8 px
(`SPACE.sm`) between a label and its value; 4 px (`SPACE.xs`) between pills in a row (`PillRow` uses 6 px column gap,
keep it). Cards: `scLowest` fill, 1 px `outlineVar` hairline, radius `RADIUS.card` (10 px), no shadow (`e1`).

### 2.2 Typography

Only the five steps and the one figure size of `tokens.ts`. No new size is introduced.

| Use | Step | Spec |
|---|---|---|
| Screen title (`Overview`, `Trace`) | `pageTitle` | 22/28, 600 |
| **Verdict word** | `pageTitle`, uppercase, letter-spacing 0.04em | 22/28, 600 (the same way `KpiCard` writes `UNKNOWN`) |
| Pillar figure (`126 of 130`) | `KPI_FIGURE` | 28/32, 600, tabular |
| Card and section titles, attention-line sentence lead | `section` | 16/22, 600 |
| Freshness sentence, attention sentences, helper copy | `body` | 14/20, 400 |
| Card labels (`BACKUP COVERAGE`), column heads | `LABEL_UPPER` | 12/16, 600, 0.04em, uppercase, `onSurfaceVar` |
| Pills, ages, links inside cards | `label` | 12/16, 500 |
| Addresses, ports, rule numbers, timestamps, codes | `mono` | 13/18, JetBrains Mono, tabular |

Family: Roboto (theme). Numbers are tabular everywhere (body rule in the theme). A figure in a sentence
(`131 of 132`) stays in the sentence's step; only the pillar figure uses `KPI_FIGURE`.

### 2.3 Colour semantics for states

A state is a word with a colour, never a colour alone (frozen rule). Both screens pick from `ROLE_COLORS` only.

| Meaning | Role | Light fill / ink | Dark fill / ink | Words that may carry it |
|---|---|---|---|---|
| OK | `ok` | `#e3f5e3` / `#087a08` | `#16301a` / `#7ee07e` | `Ready`, `Allowed`, `Archived`, `Passing`, `Live`; never a count, never an absence |
| Warn | `warn` | `#fdf1d6` / `#8a5a00` | `#3a2f12` / `#ffd98a` | `Stale`, `Partial`, `Asymmetric`, `Overdue`, `Not confirmed` |
| Critical | `bad` | `#fbe7e7` / `#8f1f1f` | `#3a1d1f` / `#ffb4b4` | `Not ready`, `Denied`, `Failed`, `No archive`, `Critical failing` |
| UNKNOWN | `unknown` (hatched) | `#e3e7ec` + hatch / `#5b6473` | `#272e38` + hatch / `#a5adba` | `UNKNOWN`, `Not evaluated`, `Not evidenced`, `Not assessed` |
| Attention (caveat, not fault) | `attn` | `#fce8df` / `#b0421a` | `#3a2419` / `#ffbfa3` | `With caveats`, `Allowed to the edge`, `Conditional`, `Differ` |
| Neutral | `neutral` | `#e3e7ec` / `#5b6473` | `#272e38` / `#a5adba` | every count, every 0, `Not applicable`, `Locked` |

**Stale is a freshness state, not a health state.** It never replaces the health word; it is appended to it with the
age: `Ready · Stale · 31 h ago` (the pill takes the `warn` role because the worst word in it is `Stale`). A health
pill whose evidence is stale is therefore never green. This fixes the false-green readiness of screenshots 35/38/40.

**One freshness scale (proposal for B1).** Every age in the product is read against one table:

| Band | Age of the evidence | Word | Role |
|---|---|---|---|
| Fresh | less than 24 h | no word; the age alone (`4 h ago`) | neutral |
| Stale | 24 h to 72 h | `Stale` | `warn` |
| Stale, long | more than 72 h | `Stale · 3 d` (days, rounded down) | `warn` |

Red is reserved for fault; an old read is a collection problem, so it stays amber however old, and the age itself
says how bad it is. The 24 h boundary is the one the product already applies (Overview evidence count, HA readiness
staleness). The 72 h boundary and the day-rounding are a proposal; **PO decision B1**.

Hatching (`UNKNOWN_HATCH`: 135°, 1 px `outlineVar` lines, 6 px period) is used for `UNKNOWN` fills and for the
"not read" share of a bar. Bars never use the saturated status colours (`m3.success`, `m3.warning`) as text; they
use them as fills only, with the `*Ink` tokens for any adjacent text.

### 2.4 Components reused and the three additions

Reused unchanged: `StatusPill`, `PillRow`, `KpiCard`, `KpiRow`, `StatePanel` (five variants), `RestrictedPanel`,
`Timestamp`, `M3Tabs`, `M3Button` (filled / tonal / outlined / text), `Meter`, `StackedBar`, `DataTable`,
`ScreenHeader`, `ScreenRoot`.

Three additions, each a composition of the above (no new primitive):

1. **Verdict banner** (§3.3): a full-width card with the verdict word, the freshness sentence and one link.
2. **Attention line** (§3.5): a 44 px row of `StatusPill` + sentence + age + one link; at most five in a list.
3. **Mode switch** (§4.2): two `M3Tabs` tabs at the top of Trace; the tab strip is the switch, nothing new.

A fourth pattern, the **disclosure** (`Advanced path options`, `Optional context`), is a text `M3Button` with a
chevron that toggles a region (`aria-expanded`, `aria-controls`); closed by default; the open state is remembered per
viewer in the browser only.

### 2.5 Responsive rules

| Rule | 1280 | 1440 | 1920 |
|---|---|---|---|
| Rail | persistent, labelled (viewer may fold) | same | same |
| Content max width | canvas | canvas | 1400 px |
| Pillar row (`KpiRow`, `minmax(230px, 1fr)`) | 4 across at 234 px each | 4 across | 4 across, capped by 1400 |
| Home: attention list / framework bars | stacked, full width each | side by side 7 + 5 columns | side by side 7 + 5 |
| Trace form fields (`minmax(220px, 1fr)`) | 4 primary fields on one row | one row | one row |
| Path diagram | full width, min height 320 px | full width, 400 px | full width, 480 px |
| Path hop list | one branch column; extra ECMP branches as tabs | up to 2 branch columns | up to 3 branch columns |

Below 1100 px the shell already swaps the rail for a drawer; the screens then stack every region to one column. No
horizontal page scroll at any width (post-release rule R1). Long masked names wrap with `overflowWrap: anywhere`.

### 2.6 Accessibility

**Contrast** (computed from the token hex values, WCAG 2.1 relative luminance):

| Pair | Light | Dark | Verdict |
|---|---|---|---|
| `onSurface` on `scLowest` | 17.3 | 15.4 | AAA |
| `onSurfaceVar` on `scLowest` (helper copy) | 5.6 | 7.7 | AA, AAA dark |
| `onWarningContainer` on `warningContainer` (Stale pill) | 5.3 | 9.7 | AA |
| `onErrorContainer` on `errorContainer` | 7.4 | 9.0 | AAA light, AAA dark |
| `onSuccessContainer` on `successContainer` | 4.9 | 8.7 | AA |
| `onAttentionContainer` on `attentionContainer` | 4.9 | 9.2 | AA |
| `neutralInk` on `scHigh` (counts, UNKNOWN) | 4.8 | 6.1 | AA |
| `primary` on `scLowest` (links) | 6.1 | 6.7 | AA |
| `onPrimary` on `primary` (filled button) | 6.1 | 7.2 | AA |
| `m3.warning` as **text** on `scLowest` | 1.8 | 11.0 | **fails light**: never use the saturated warning fill as text (the `warningInk` token exists for that: 5.9) |
| `m3.success` as text on `scLowest` | 3.4 | 7.3 | fails light for 14 px text: use `goodInk` (5.5) |
| `outline` as text | 2.6 | 2.9 | hairlines only, never text |

Every text/fill pair this spec uses is at or above 4.5:1 in both modes. Bar fills carry no text; their meaning is in
the adjacent words, so a 3:1 non-text contrast is enough and the hatch keeps `UNKNOWN` legible without colour.

**Keyboard.** Every link and pill with an action is a real `<a>` or `<button>` in DOM order: verdict link, pillar
links, attention links, framework rows, pulse lines, form fields, submit, disclosure, mode tabs (arrow keys, per
`M3Tabs`), hop cards (focusable, `Enter` expands), diagram nodes (§4.5). Focus ring: 2 px `m3.primary`, offset 2 px
(the `StatusPill` rule). No focus trap except an open drawer.

**ARIA.** The verdict banner is `role="status"` (`aria-live="polite"`) so a screen reader hears the sentence after
load; it is never `alert`. The attention list is an `<ol>` with `aria-label="Needs attention"`; each line's pill
carries `aria-label` with the full sentence (`Not ready: 2 clusters, oldest evidence 31 hours ago`). Pillars expose
the figure and sentence as one `aria-label` (`Backup coverage: 126 of 130 targets archived, 4 without an archive`).
Bars are `role="img"` with the same label; the hatch and colour add nothing a reader needs. Form fields keep MUI's
label/helper wiring; the submit button's disabled reason is in its text (existing rule). Error and refusal panels keep
`StatePanel`'s `alert`/`status` split. The path diagram is `role="group"` with a text alternative: the hop list is the
accessible primary (§4.5).

**Motion.** Bar widths animate 300 ms (existing `Meter`); under `prefers-reduced-motion` they do not animate.

## 3. Executive home (C1)

### 3.1 Purpose and audience

Answers, in five seconds and without a click: *Is the estate OK? Is that answer fresh? What needs a decision?* The
viewer is an IT executive or shift lead, then an expert who wants one click to the rows. The screen keeps the rail
slot and the `Overview` label (R-15 keeps the destination; the content changes). Visible to every signed-in role;
aiview sees it masked and complete, with any restricted drill-down link rendered locked (`Role: not permitted`
suffix), never hidden.

Plain vocabulary on this screen (C7): `data freshness` not `evidence`, `backup coverage` not `artefact`,
`failover readiness` not `HA readiness`, `policies` not `containers`. Expert words appear one click away.

### 3.2 Wireframes

Above the fold at 1440 × 900 (canvas 1144 px; 12 columns):

```
+------------------------------------------------------------------------------------------------------+
| Overview                                                                   132 devices · 45 clusters |
| Estate assurance · as of 2026-11-05 09:12 GMT+3                                                      |
+------------------------------------------------------------------------------------------------------+
| [VERDICT BANNER]  col 1-12                                                                           |
| +--------------------------------------------------------------------------------------------------+ |
| |  AT RISK                                                                                         | |
| |  From stored reads: 97 of 118 firewalls fail a critical check, 4 backup targets have no archive, | |
| |  2 clusters are not ready for failover.                                                          | |
| |  [ Stale · 31 h ago ]  131 of 132 devices were last read more than 24 h ago. Scheduled            | |
| |  collection is off or delayed; the figures below reflect the last stored reads.                   | |
| |                                                                              Why this verdict →  | |
| +--------------------------------------------------------------------------------------------------+ |
+------------------------------------------------------------------------------------------------------+
| [PILLARS]  4 × col 3                                                                                 |
| +-----------------------+ +-----------------------+ +-----------------------+ +---------------------+ |
| | SECURITY POSTURE      | | BACKUP COVERAGE       | | FAILOVER READINESS    | | DATA FRESHNESS      | |
| | 97 of 118             | | 126 of 130            | | 35 of 45    [Stale]   | | 1 of 132            | |
| | firewalls fail a      | | targets archived      | | clusters ready        | | devices read in 24 h| |
| | critical check        | |                       | |                       | |                     | |
| | [■■■■■■■■■■■■■■■■□□□] | | [■■■■■■■■■■■■■■■■■■▪] | | [■■■■■■■■■■■■■▪▪░░░░] | | [▪░░░░░░░░░░░░░░░░░░] | |
| | 164 critical checks   | | 4 without an archive  | | 2 not ready · 3       | | oldest read 3 d ago |
| | failing               | |                       | | UNKNOWN · 5 not       | |                     | |
| |                       | |                       | | supported             | |                     | |
| | Open Compliance →     | | Show the 4 →          | | Show the 5 →          | | Show stale devices →| |
| +-----------------------+ +-----------------------+ +-----------------------+ +---------------------+ |
+------------------------------------------------------------------------------------------------------+
| [NEEDS ATTENTION]  col 1-7                              | [COMPLIANCE BY FRAMEWORK]  col 8-12         |
| Needs attention · 5 of 9                 Show all 9 →   | Compliance by framework           Open →   |
| ---------------------------------------------------------| Technical checks, not an audit certificate.|
| [Not ready]   2 clusters cannot fail over         31 h  | Financial baseline  37.5% pass · 20% no ev.|
|               CLS-ROMEO-01, CLS-TANGO-04      Inspect → | [■■■■■■■■□□□□□░░░░░░░]                      |
| [No archive]  4 backup targets have no stored     47 h  | CIS Benchmark       30.1% pass · 20% no ev.|
|               archive                      Show the 4 → | [■■■■■■□□□□□□□░░░░░░░]                      |
| [Overdue]     Scheduled fleet backup last ran     47 h  | PCI DSS v4.0.1      24.1% pass · 18% no ev.|
|               47 h ago (daily at 02:00)       Open job → | [■■■■■□□□□□□□□░░░░░░░]                      |
| [Critical]    Top failing critical control:       31 h  | NIST SP 800-53      30.5% pass · 21% no ev.|
|               Admin access users (41 firewalls) Open →  | [■■■■■■□□□□□□□░░░░░░░]                      |
| [Differ]      3 clusters: members' configurations 2 d   | ■ Pass  □ Fail  ░ Not evidenced            |
|               differ                      Compare →     |                                            |
+---------------------------------------------------------+--------------------------------------------+
 - - - - - - - - - - - - - - - - - - - -  fold at ~900 px  - - - - - - - - - - - - - - - - - - - - - -
```

Below the fold at 1440:

```
+---------------------------------------------------------+--------------------------------------------+
| [24-HOUR PULSE]  col 1-7                                | [ESTATE MAP]  col 8-12                     |
| Last 24 hours                                           | Estate map                      Devices → |
| ---------------------------------------------------------| Every device, worst evidenced condition.  |
| Configuration changed on 42 of 124 gateways   Changes → | 1 of 132 read in 24 h · 131 stale          |
| Policy installed on 1 of 122 gateways          Policy → | Check Point 60  ▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦   |
| Jobs failed: 1 of 8                          Jobs →     | Palo Alto 39    ▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦▦         |
| Cluster members compared: 42 of 45, 3 differ  Compare → | Cisco ASA 12    ▦▦▦▦▦▦▦▦▦▦▦▦                |
|                                                         | Fortinet 10     ▦▦▦▦▦▦▦▦▦▦                  |
|                                                         | Other 11        ▦▦▦▦▦▦▦▦▦▦▦   [show]        |
|                                                         | ■ Clear  ■ Critical failing  • No archive  |
|                                                         | ▦ hatched = stale or not assessed          |
+---------------------------------------------------------+--------------------------------------------+
```

At 1280 (canvas 984 px) the two-column regions stack: attention list full width, then framework bars, then pulse,
then estate map. Pillars stay four across (234 px each, the `KpiRow` minimum is 230). The verdict sentence wraps to
three lines; the fold then lands after the attention list's third line, which is acceptable: the verdict and the
pillars are the five-second content and are always above it.

At 1920 the layout is the 1440 layout inside a 1400 px cap. The pillars grow to ≈ 338 px; nothing else changes.

### 3.3 Verdict banner

One card, `scLowest`, hairline, 16 px padding, three rows:

1. **Verdict word**, `pageTitle` uppercase tracked, in a pill of its role (`AT RISK` → `bad`; `HEALTHY` → `ok`;
   `UNKNOWN` → `unknown` hatched). The pill is the only coloured element in the row.
2. **Posture sentence**, `body`, `onSurface`: one sentence that lists the red facts in pillar order (security,
   backup, failover) with their numbers, or states that none exist. Always prefixed with where the facts come from
   (`From stored reads:` when any pillar's evidence is stale, `From today's reads:` when all are fresh).
3. **Freshness sentence**, `body`, `onSurfaceVar`, led by a `StatusPill` of the freshness band: the count of devices
   read in 24 h, the oldest age, and what it means for the figures. Right-aligned link `Why this verdict →` opens an
   inline disclosure under the banner that prints the derivation rule and the four inputs (no new screen).

**Derivation rule (deterministic, printed in the disclosure):**

```
AT RISK   if any pillar reports a red fact:    critical check failing ≥ 1
                                                OR backup target without archive ≥ 1
                                                OR cluster not ready ≥ 1
UNKNOWN   otherwise, if any pillar is UNKNOWN (no evidence, read failed, not evaluated)
          OR fewer than 80% of active devices were read in the last 24 h
HEALTHY   otherwise (every pillar evidenced, no red fact, ≥ 80% read in 24 h)
```

Precedence is `AT RISK` > `UNKNOWN` > `HEALTHY`: a red fact from a stale read is still a red fact (the stored evidence
proves the gap existed), while fresh-looking green is never claimed from stale data. The 80 % line is Argon's banner
threshold (R-01); it is a proposal. There is no weighting, no score and no fourth word. A pillar that is itself
`Stale` contributes its last stored facts and makes the banner's freshness pill `Stale`; it does not change the word.

The verdict word is the item §0.1 flags. If the PO declines the amendment, the banner keeps rows 2 and 3 and drops
row 1; the design holds without it.

### 3.4 Four pillars

`KpiRow` with four `KpiCard`s, in this fixed order. Each card: `LABEL_UPPER` label (optional `StatusPill` badge on the
right of the label row), `KPI_FIGURE` figure `N of M`, `label`-step unit, a `Meter` bar, one `label`-step detail line,
one link. Rules:

- **The bar fills the numerator.** `126 of 130 archived` fills 97 % green; the 3 % tail is red (`No archive`). `1 of 132
  read` fills 1 % neutral; the 99 % tail is hatched (`Stale`). This fixes the inverted bars of screenshot 06. A bar
  is `StackedBar` with parts in the order good → attention → bad → unknown; the parts carry tooltips with the word
  and count.
- **No colour on the figure.** The figure is `onSurface`. Colour lives in the bar and in the badge pill.
- **Stale badge.** When the pillar's evidence crosses the 24 h band the label row carries `Stale · 31 h ago`
  (`warn`). The figure is still shown: it is the last stored read, and the sentence says so.
- **UNKNOWN figure.** When nothing is evidenced the figure is the hatched word `UNKNOWN` (`KpiCard` state `unknown`),
  the bar is omitted, the detail line says why (`No compliance evaluation has run`), and the link still goes to the
  screen that would produce it. Never `0 of 0`, never a dash.
- **24 h delta.** A fourth line `+2 since yesterday` / `-4 since yesterday` (`label`, `neutralInk`) appears only when a
  stored daily snapshot exists for that figure. When none exists the line is absent. Whether the product stores one
  is `UNKNOWN` (§6).

| # | Label | Figure and unit | Bar parts | Detail line | Link |
|---|---|---|---|---|---|
| 1 | `SECURITY POSTURE` | `97 of 118` · `firewalls fail a critical check` | failing `bad` / passing `ok` / not assessed hatched | `164 critical checks failing` | `Open Compliance →` (Compliance, critical, fail) |
| 2 | `BACKUP COVERAGE` | `126 of 130` · `targets archived` | archived `ok` / no archive `bad` | `4 without an archive` or `Every target holds an archive` | `Show the 4 →` (Backups, no artefact) or `Open Backups →` |
| 3 | `FAILOVER READINESS` | `35 of 45` · `clusters ready` | ready `ok` / not ready `bad` / UNKNOWN hatched / not supported neutral | `2 not ready · 3 UNKNOWN · 5 not supported` (one line, never repeated) | `Show the 5 →` (Operations, not ready + unknown) |
| 4 | `DATA FRESHNESS` | `1 of 132` · `devices read in 24 h` | read neutral / stale hatched | `oldest read 3 d ago` plus `· 2 never read` when any | `Show stale devices →` (Devices, age stale) |

The pillar for failover readiness counts clusters, never readiness records (A5): the detail line never mixes the
two.

### 3.5 Needs attention

Title row: `Needs attention · 5 of 9` (`section`) and a right link `Show all 9 →` that opens the same list expanded
in place (not a new screen). When nine or fewer exist, the link says `Show all →` only when more than five exist.

At most **five lines**, each one row of 44 px (comfortable), hairline between rows:

```
[ pill ]  sentence (section lead + body)                    age (mono, right)   link →
```

- **Pill**: the state word of the finding (`Not ready`, `No archive`, `Overdue`, `Critical`, `Differ`, `Failed`,
  `UNKNOWN`) in its role. The pill is not a link.
- **Sentence**: `section` for the count and noun (`2 clusters cannot fail over`), `body` beneath for the names,
  masked, at most three names then `and 1 more` (`CLS-ROMEO-01, CLS-TANGO-04`). Names are plain text, not links; the
  one link is at the right.
- **Age**: the age of the newest evidence behind the line, `Timestamp compact` (mono), with the exact stamp on
  hover. Stale ages take no extra pill here: the banner already said it once.
- **Link**: one text link to the filtered rows (`Inspect →`, `Show the 4 →`, `Open job →`, `Compare →`).

**Ranking** (deterministic, in this order): role (`bad` before `warn` before `attn` before `unknown`), then the
count of affected objects (descending), then age (newest evidence first). Identical events at the same instant are
one line (`21 devices changed Admin access users at the same time`), never 21 lines.

**Sources** of lines, in the vocabulary of the pillars: clusters not ready; backup targets without archive; scheduled
fleet backup overdue (`> 26 h` for a daily schedule, Argon R-03); the most widespread failing critical control;
clusters whose members' configurations differ (`attn`, not red: a difference is a finding, not a fault); jobs failed
in 24 h grouped by reason; devices never read; readiness evaluations `UNKNOWN`.

**Empty**: a `StatePanel` `empty` inside the card: title `Nothing needs attention`, body `No red or amber finding in
the last stored reads.`, and, when the freshness pill is stale, next step `Figures reflect reads older than 24 h; a
new collection may change this.` The empty panel is never green.

### 3.6 Below the fold

**Compliance by framework** (right column above the fold at 1440, below it at 1280): one row per framework, each a
name link, the honest headline (`37.5% of evidenced controls pass · 20% have no evidence`, B4), and a `StackedBar`
of pass `ok` / fail `bad` / not evidenced hatched, 14 px, 2 px gaps. Legend once under the bars. The headline never
counts unevidenced controls as failed. Order: **PO decision B4**. Default pending that decision: the order the
compliance payload returns. If the PO chooses Financial Baseline first, the list is `Financial baseline`, then the
rest alphabetically. Subtitle: `Technical checks, not an audit certificate.`

**Last 24 hours** (the pulse): a card titled `Last 24 hours` with three to four one-line counters, each
`sentence · link`, 44 px rows, no bars, no charts:

| Line | Copy | Link |
|---|---|---|
| Configuration | `Configuration changed on 42 of 124 gateways since the last read` | `Changes →` (Operations › Change feed until C3's Changes exists) |
| Policy | `Policy installed on 1 of 122 gateways in the last 24 h` | `Policy →` |
| Jobs | `Jobs failed: 1 of 8 in the last 24 h` or `No job failed in the last 24 h` | `Jobs →` |
| Members | `Cluster members compared: 42 of 45, 3 differ` | `Compare →` (Devices, differ filter) |

A counter with no data is the word `UNKNOWN` in place of the number, hatched, with the reason on hover.

**Estate map**: the existing `EstateMap` grid, ranked by vendor count, with two changes: the subtitle is the fleet
freshness distribution (`1 of 132 read in 24 h · 131 stale`), never the newest single read; tiles whose evidence is
older than 24 h or not assessed are hatched over their colour so a stale red is never a live red. `Other 11` groups
vendors with five or fewer devices behind a `show` toggle unless one of them has a red fact. Legend copy:
`Clear`, `Critical failing`, `No archive` (dot), `hatched = stale or not assessed`.

### 3.7 States of the whole screen

| State | Rendering | Copy |
|---|---|---|
| Loading | Header, then four `KpiCard` skeletons (grey blocks at figure and bar height), banner skeleton; no words | subtitle `Reading the fleet…` |
| No devices enrolled | Banner replaced by `StatePanel empty`, pillars `UNKNOWN`, lists empty | title `No devices are enrolled yet`, body `The estate is empty, so there is nothing to assess.`, next step `Administrators add devices under Administration › Device management.` |
| Partial (one pillar failed to read) | That pillar `UNKNOWN` with reason; banner `UNKNOWN` unless another pillar is red | pillar detail `The read failed: <code>` in mono |
| Stale (any pillar older than 24 h) | Banner freshness pill `Stale · 31 h ago`, pillar badges, hatched map tiles; verdict word still derived | see §3.3 freshness sentence |
| Error (payload unreadable) | `StatePanel error` with Retry in place of the whole body | title `The overview could not be read`, body `No figure is shown because the server's answer could not be read.`, code in mono |
| Restricted drill-down | The link is rendered as a `StatusPill` neutral with suffix | `Open Compliance · Role: not permitted` |

### 3.8 Copy: every label on the executive home

| Where | Exact copy |
|---|---|
| Rail entry | `Overview` |
| Screen title | `Overview` |
| Screen subtitle | `Estate assurance · as of 2026-11-05 09:12 GMT+3` |
| Header right (neutral pill) | `132 devices · 45 clusters` |
| Verdict words | `HEALTHY` / `AT RISK` / `UNKNOWN` |
| Posture sentence, red facts | `From stored reads: 97 of 118 firewalls fail a critical check, 4 backup targets have no archive, 2 clusters are not ready for failover.` |
| Posture sentence, none | `From today's reads: no critical check fails, every backup target holds an archive, every supported cluster is ready for failover.` |
| Posture sentence, unknown | `From stored reads: compliance has not been evaluated; 4 backup targets have no archive.` (each UNKNOWN pillar stated as `has not been evaluated` / `was not read`) |
| Freshness sentence, fresh | `All 132 devices were read in the last 24 h.` |
| Freshness sentence, stale | `131 of 132 devices were last read more than 24 h ago (oldest 3 d ago). Scheduled collection is off or delayed; the figures reflect the last stored reads.` |
| Freshness pill | `Stale · 31 h ago` / `Read 4 h ago` |
| Verdict link | `Why this verdict →` |
| Disclosure title | `How the verdict is derived` |
| Pillar labels | `SECURITY POSTURE` · `BACKUP COVERAGE` · `FAILOVER READINESS` · `DATA FRESHNESS` |
| Pillar units | `firewalls fail a critical check` · `targets archived` · `clusters ready` · `devices read in 24 h` |
| Pillar details | `164 critical checks failing` · `4 without an archive` / `Every target holds an archive` · `2 not ready · 3 UNKNOWN · 5 not supported` · `oldest read 3 d ago · 2 never read` |
| Pillar links | `Open Compliance →` · `Show the 4 →` / `Open Backups →` · `Show the 5 →` / `Open Operations →` · `Show stale devices →` |
| Pillar delta | `+2 since yesterday` / `-4 since yesterday` / `No change since yesterday` |
| Pillar stale badge | `Stale · 31 h ago` |
| Attention title | `Needs attention · 5 of 9` |
| Attention expand | `Show all 9 →` / `Show fewer` |
| Attention pills | `Not ready` · `No archive` · `Overdue` · `Critical` · `Differ` · `Failed` · `UNKNOWN` |
| Attention sentences | `2 clusters cannot fail over` · `4 backup targets have no stored archive` · `Scheduled fleet backup last ran 47 h ago (daily at 02:00)` · `Top failing critical control: Admin access users (41 firewalls)` · `3 clusters: members' configurations differ` · `21 devices changed Admin access users at the same time` · `2 devices were never read` |
| Attention names overflow | `CLS-ROMEO-01, CLS-TANGO-04, CLS-JULIET-06 and 1 more` |
| Attention links | `Inspect →` · `Show the 4 →` · `Open job →` · `Open →` · `Compare →` |
| Attention empty | `Nothing needs attention` / `No red or amber finding in the last stored reads.` |
| Framework card | `Compliance by framework` / `Technical checks, not an audit certificate.` / `Open →` |
| Framework headline | `37.5% of evidenced controls pass · 20% have no evidence` / `Not assessed` |
| Framework legend | `Pass` · `Fail` · `Not evidenced` |
| Pulse card | `Last 24 hours` |
| Pulse lines | see §3.6 table |
| Estate map | `Estate map` / `Every device, worst evidenced condition.` / `1 of 132 read in 24 h · 131 stale` / `Devices →` / `Other 11` / `show` |
| Map legend | `Clear` · `Critical failing` · `No archive` · `hatched = stale or not assessed` |
| Locked link suffix | `· Role: not permitted` |

## 4. Trace (C3)

### 4.1 Place in the information architecture

A new rail group `TRACE` between `VERIFY` and `RECOVER`, in the order of the product principle. Its first entry is
`Trace` (icon `route`), screen id `trace`, replacing the `Path` entry under VERIFY and the `Lookup` tab under
Policy. A second entry `Changes` is reserved in the group for the unified change screen; it is not specified here.
Drawer leaves: `Trace` and, under it, `Path` and `Lookup` as deep links (`?screen=trace&mode=path`,
`?screen=trace&mode=lookup`).

Two deep links stay in the product (Fable's condition on R-16 and Argon's on R-20):

- Policy › Rules & Containers keeps a `Look up in this policy →` pill link on a selected container, opening Trace in
  Lookup mode with the policy pre-selected and locked to that container.
- Devices › Routing keeps `Trace path from here →` on a route row, opening Trace in Path mode with the destination
  and ingress unit pre-filled.

The old URLs (`?screen=path_analysis`, `?screen=policy&tab=lookup`) redirect to the new mode.

### 4.2 Mode switch

The screen title row is `Trace` with a subtitle that states the one question both modes answer. Under it an
`M3Tabs` strip with two tabs is the mode switch; the two modes carry distinct meaning and the tab labels say so:

```
Trace
Can A reach B, and which rule or hop decides? Computed from stored evidence: management intent, never observed traffic.

 Path · hop by hop across the topology    Lookup · rules of one stored policy
 ─────────────────────────────────────
```

The selected mode is in the URL (`mode=`), so a link and the browser's back button carry it. Switching modes keeps
the shared fields (`Source`, `Destination`, `Protocol`/`Service`, `Port`) by copying them across: a value typed in
Path appears in Lookup and the reverse. A result belongs to its mode: switching hides it, switching back shows it
again unless the fields changed.

### 4.3 Wireframes

Path mode at 1440, before a result:

```
+------------------------------------------------------------------------------------------------------+
| Trace                                                                                                |
| Can A reach B, and which rule or hop decides? Computed from stored evidence: management intent,       |
| never observed traffic.                                                                              |
+------------------------------------------------------------------------------------------------------+
|  Path · hop by hop across the topology  |  Lookup · rules of one stored policy                        |
|  ───────────────────────────────────────                                                             |
+------------------------------------------------------------------------------------------------------+
| [QUERY]  col 1-12                                                                                     |
| +--------------------------------------------------------------------------------------------------+ |
| | [ Masked session · type names and addresses as shown here (FW-TANGO-04, 192.0.2.10) ]  (?)       | |
| |                                                                                                  | |
| | Source *             Destination *         Protocol        Port *                                 | |
| | [192.0.2.10       ]  [192.0.2.80        ]  [TCP        v]  [443      ]                            | |
| | IPv4 host            IPv4 host             TCP or UDP      1–65535                                | |
| |                                                                                                  | |
| | ▸ Advanced path options                                                                          | |
| |                                                                                                  | |
| | ( Evaluate path )   (?) Up to 30 queries a minute · the query and its verdict are recorded,      | |
| |                         the result is not stored                                                 | |
| +--------------------------------------------------------------------------------------------------+ |
+------------------------------------------------------------------------------------------------------+
| [EMPTY]                                                                                              |
| +--------------------------------------------------------------------------------------------------+ |
| | (i)  No path evaluated yet                                                                       | |
| |      Enter a source, a destination and a port, then evaluate. The verdict comes first, then       | |
| |      every unit on the path with its route, NAT, matched rule and reasons. Nothing is sent to a   | |
| |      device.                                                                                     | |
| +--------------------------------------------------------------------------------------------------+ |
+------------------------------------------------------------------------------------------------------+
```

Advanced path options, open:

```
| | ▾ Advanced path options                                                                          | |
| |   Ingress unit                 Ingress interface              [ ] Accept the answer at the edge  | |
| |   [FW-TANGO-04           ]     [eth1                   ]          of the known topology          | |
| |   Optional · when the source   Optional · needs the              (Allowed to the edge instead    | |
| |   could belong to several      ingress unit                      of UNKNOWN)                      | |
```

Path mode with a result (1440):

```
+------------------------------------------------------------------------------------------------------+
| [VERDICT]  col 1-12                                                                                   |
| +--------------------------------------------------------------------------------------------------+ |
| | [ Denied ]  192.0.2.10 → 192.0.2.80 · tcp/443                              evaluated 09:14 GMT+3 | |
| | A unit on the path blocks this traffic; the blocking hop and every hop before it are decided.    | |
| | [ Blocked at #3 FW-TANGO-04 ]  [ Equal-cost branches · 2 ]  [ With caveats ]                     | |
| |                                                  [ Diagram | List ]      ( Verify on devices )   | |
| +--------------------------------------------------------------------------------------------------+ |
+------------------------------------------------------------------------------------------------------+
| [DIAGRAM]  col 1-12, 400 px                                                                          |
| +--------------------------------------------------------------------------------------------------+ |
| |  (src)        [#1 FW-ROMEO-01]═══►[#2 FW-JULIET-06]═══►[#3 FW-TANGO-04]╳    (dst)                | |
| | 192.0.2.10     Allowed             Allowed             Denied            192.0.2.80              | |
| |  .0/24          │                                                                                | |
| |                 └──────────►[#2' FW-BRAVO-02]──────►[#3' edge ○]  Allowed to the edge            | |
| |                             UNKNOWN                                                              | |
| |                                                                                                  | |
| |  ● Allowed  ● Denied  ◌ UNKNOWN  ◐ Conditional  ○ edge of known topology   ─ ─ return path       | |
| |                                                                     [ Show neighbours ] [+][-][⌂]| |
| +--------------------------------------------------------------------------------------------------+ |
+------------------------------------------------------------------------------------------------------+
| [HOPS]  branch columns, col 1-6 and 7-12                                                              |
| Branch A · Denied at #3                           | Branch B · Allowed to the edge                    |
| (1) FW-ROMEO-01          [Allowed]      ▸         | (1) FW-ROMEO-01          [Allowed]      ▸         |
| (2) FW-JULIET-06         [Allowed]      ▸         | (2) FW-BRAVO-02          [UNKNOWN]      ▾         |
| (3) FW-TANGO-04          [Denied]       ▾         |     Ingress → egress  eth1 → eth3                 |
|     Ingress → egress  eth2 → eth0                 |     Route used        192.0.2.0/24 via 192.0.2.1  |
|     Packet in → out   192.0.2.10 → 192.0.2.80     |     NAT               UNKNOWN                     |
|     Route used        0.0.0.0/0 via 192.0.2.254   |     Matched rule      Policy binding unproven     |
|     NAT               No translation              |     Why: No stored policy is proven to be this    |
|     Matched rule      #41 · RULE-DENY-01 · deny   |     unit's own.                                   |
|     Why: Decided by the first matching rule.      | (3) edge                 [Allowed to the edge]    |
|     Return path  Symmetric  Show return hops · 3  |                                                   |
+---------------------------------------------------+---------------------------------------------------+
```

Lookup mode at 1440, with one result:

```
+------------------------------------------------------------------------------------------------------+
|  Path · hop by hop across the topology  |  Lookup · rules of one stored policy                        |
|                                            ───────────────────────────────────                        |
+------------------------------------------------------------------------------------------------------+
| +--------------------------------------------------------------------------------------------------+ |
| | [ Masked session · type names and addresses as shown here ]                                      | |
| | Policy *                      Source *              Destination *         Service *               | |
| | [DOM-PAPA-01 · Standard   v]  [192.0.2.10        ]  [192.0.2.80        ]  [tcp/443        ]       | |
| | Stored snapshot · 26 h ago    IPv4 or CIDR          IPv4 or CIDR          tcp/443, udp/53 or any  | |
| | [ Stale · 26 h ago ]                                                                             | |
| | ▸ Optional context · zones and target                                                            | |
| | ( Look up matching rules )   (?) NAT, routing and anti-spoofing are not evaluated; IPv6 is not   | |
| |                                  supported.                                                      | |
| +--------------------------------------------------------------------------------------------------+ |
| [RESULT]                                                                                             |
| +--------------------------------------------------------------------------------------------------+ |
| | [ Allowed ]  192.0.2.10 → 192.0.2.80 · tcp/443      Snapshot DOM-PAPA-01 · Standard [Stale · 26 h]| |
| | Decided by the first matching rule.                                                              | |
| | Deciding rule   #12 · ALLOW-WEB · Network · accept                              Open rule →      | |
| | Rules that could also match but could not be decided: none                                       | |
| | Limitations: NAT, routing and anti-spoofing are not evaluated.                                   | |
| +--------------------------------------------------------------------------------------------------+ |
| Recent lookups · this session                                                                        |
| ...                                                                                                  |
+------------------------------------------------------------------------------------------------------+
```

At 1280 the forms keep four fields on one row (220 px minimum each in 984 px); the diagram is 320 px high; the hop
list shows one branch column with the others as tabs (`Branch A · Denied`, `Branch B · Allowed to the edge`) above
it. At 1920 the diagram is 480 px high and up to three branch columns sit side by side within the 1400 px cap.

### 4.4 Query forms

**Path mode.** Primary row: `Source *`, `Destination *`, `Protocol`, `Port *` (Argon's five-element bar; the button
is the fifth). Grid `repeat(auto-fill, minmax(220px, 1fr))`, 16 px gap. Labels above the field (never in the border),
the format in the helper line. Validation is the existing `pathFormMissing` syntax; the button text carries the
reason while disabled (`Evaluate path · needs a destination`), in sentence case after the dot, and reads `Evaluate
path` when ready, `Evaluating…` while busy. Submit on `Enter` from any field.

`Advanced path options` disclosure, closed by default, holds `Ingress unit`, `Ingress interface` and the edge
checkbox. When any advanced value is set and the disclosure is closed, a neutral pill summarises it beside the
disclosure title: `Ingress FW-TANGO-04 · eth1` / `Edge accepted`. The helper text under `Ingress unit` is one line.

The masked-session notice is one `StatusPill` (`brand` role, 22 px) above the fields, not a two-line box; the full
rule (the server resolves masked values against the current topology; raw, unknown and ambiguous values are refused
alike) is in its tooltip and in the `Query refused` state. In an unmasked session the pill is replaced by the plain
one-line input hint in `onSurfaceVar`.

The rate-limit and audit sentence moves to an info icon tooltip beside the button (`(?)`), reachable by keyboard
(the icon is a button with `aria-describedby`).

**Lookup mode.** Primary row: `Policy *` (select of stored snapshots, each option `name · age`), `Source *`,
`Destination *`, `Service *`. Under the policy select a `StatusPill` shows the snapshot's freshness in the one scale
(`Stale · 26 h ago` or the plain age). When Lookup was opened from a Policy deep link the select is pre-filled and
shows a lock glyph with tooltip `Chosen in Policy; change it there or clear the link` and a `Change policy` text
button that unlocks it. `Optional context · zones and target` disclosure holds `Source zone`, `Destination zone`,
`Install-on target`, `Device context`. Button: `Look up matching rules`, disabled reason in the same pattern.

### 4.5 Path diagram

The diagram is the existing React Flow canvas (`PathDiagram`) with a fixed visual contract:

- **Layout**: left to right, one column per hop index (`COL_STEP` 230 px), one lane per branch (`LANE_STEP` 190 px),
  source endpoint in column 0, destination endpoint in the last column. Auto-fit on load with 24 px padding; the user
  may pan and zoom (controls bottom-right: `+`, `-`, `⌂` fit); the wheel zooms only with `Ctrl`, so the page still
  scrolls.
- **Nodes**: a unit is a card 200 × 64 px: line 1 the hop number in a 24 px circle and the masked unit name
  (`section` step, truncated with ellipsis and full name on hover); line 2 the verdict `StatusPill` dense. A 4 px
  left border carries the verdict colour (`HOP_VERDICT_SIDE`); the pill is the word that gives the border meaning.
  An endpoint is a 160 × 56 rounded node with the masked address in mono and its segment prefix beneath. The edge of
  the known topology is a dashed-outline circle 40 px labelled `edge`. Neighbour segments (`Show neighbours`, capped
  at 40) are 120 × 36 neutral nodes at 60 % opacity.
- **Edges**: forward path 2 px solid `onSurfaceVar` with an arrowhead; the edge into a `Denied` hop ends in a 12 px
  `✕` glyph in `error` beside the arrowhead; equal-cost branches fork with a 2 px solid line into their lane; the
  return path is a 1.5 px dashed line under the forward path in `outline`, shown only when `Show return path` is on
  (default off, remembered per viewer). An edge never carries colour alone: the hop node's pill is the state.
- **Branch labels**: at the start of each lane, `Branch A · Denied at #3` (`label`, pill for the branch verdict).
- **Legend**: one row under the canvas, `label` step: `Allowed`, `Denied`, `UNKNOWN` (hatched swatch), `Conditional`,
  `edge of known topology`, `return path`. Swatches are 10 px squares beside words.
- **Masking**: labels pass through `safeAddressLabel` and the server projection; the client never unmasks (A6 says
  trust the server projection, so the client-side masker is a belt only, never a different answer).
- **Keyboard and screen reader**: the canvas is `role="group"` with `aria-label="Path diagram: 2 branches, 5
  units"` and `aria-describedby` pointing at the hop list, which is the accessible primary. Nodes are in the tab
  order left to right, lane by lane; `Enter` on a node expands the matching hop card in the list and scrolls it into
  view; the focused node shows the 2 px `primary` ring. The `Diagram | List` toggle lets a keyboard user skip the
  canvas entirely.
- **Sizes**: height 320 / 400 / 480 px at 1280 / 1440 / 1920; when a result has more than four hops or three branches
  the canvas keeps its height and the fit zooms out; a `Fit` press returns to it.
- **Empty and failure**: the diagram region does not render at all without a result; failures render as `StatePanel`
  in its place (§4.7).

### 4.6 Result cards

**Path verdict card**: `StatusPill` of the verdict word (`verdictTone`: Allowed `ok`, Denied `bad`, UNKNOWN hatched,
Partial and Asymmetric `warn`, Allowed to the edge and Conditional `attn`), the tuple in mono, the evaluation
timestamp right-aligned (`Timestamp`), the verdict sentence (`VERDICT_SENTENCE`), then a `PillRow` of qualifiers:
`Blocked at #3 FW-TANGO-04` (link to the hop), `UNKNOWN at #2 FW-BRAVO-02`, `Equal-cost branches · 2`, `Branches
truncated`, `Asymmetric return`, `With caveats`, `Ingress inferred`. Below: the `Diagram | List` toggle (segmented,
`role="group"`) and the one action `Verify on devices` (filled `M3Button`, with the `· Role: not permitted` suffix
when the role may not). Warnings and caveats are a `CodeList` with the one-line meaning of each code and the code in
mono beside it.

**Hop card** (unchanged fields, fixed order): `Ingress → egress`, `Packet in → out`, `Route used`, `NAT`, `Matched
rule` (or `Rule (pre-NAT address)` + `Rule (post-NAT address)`), `Context`, `Why`, then `Return path` with `Show
return hops · 3`. A withheld rule reads `Rule withheld by the server` in `neutralInk`; a stale policy on a hop adds a
`Stale policy` `warn` pill and the sentence `The stored policy is stale or out of sync; the last-known rule is
context, not a verdict.`

**Lookup result card**: verdict pill, tuple, snapshot name with its freshness pill, the lookup reason sentence, the
deciding rule as a link (`#12 · ALLOW-WEB · Network · accept`, `Open rule →` opens the rule viewer), the undecidable
rules as `UNKNOWN: rule #7 could match (reason) · View rule →` lines, the limitations sentence. The verdict pill uses
the same roles as Path (no MUI `Chip` colours). `Recent lookups · this session` lists up to ten previous cards
collapsed to one line each (`tuple · verdict pill · snapshot age`), expandable.

### 4.7 States

| State | Mode | Panel | Title / body / next step |
|---|---|---|---|
| Nothing evaluated | Path | `empty` | `No path evaluated yet` / `Enter a source, a destination and a port, then evaluate. The verdict comes first, then every unit on the path with its route, NAT, matched rule and reasons. Nothing is sent to a device.` |
| Nothing looked up | Lookup | `empty` | `No lookup yet` / `Choose a stored policy, then enter a source, a destination and a service. The answer is the rule that decides, from the stored snapshot only.` |
| No stored policy | Lookup | `empty` | `No stored policy to look up in` / `No policy snapshot has been collected yet.` / `Operators can start a policy collection from Policy.` |
| Busy (bulkhead) | both | `error` + Retry | `Trace is busy` / `Every analysis slot is taken by a policy or path computation. This is a wait, not a fault of the inputs: retry in a few seconds.` |
| Rate limited | both | `error` + Retry | `Query limit reached` / `At most 30 queries a minute per user. Retry after one minute; the inputs are unchanged.` |
| Topology not built | Path | `not_evaluated` | `Topology not built` / `No topology is held in memory, so no path can be evaluated. A query never builds one.` / `A Security Administrator can rebuild it from the stored inventory (no device is contacted); then evaluate again.` |
| Masked value refused | both | `empty` | `Query refused: a value was not recognised` / `In a masked session the query takes only the masked values this product shows: unit names like FW-TANGO-04, addresses like 192.0.2.10. A raw value, an unknown one and an ambiguous one are refused alike.` / `Check the values against Devices and Policy as they appear in this session, then try again.` |
| Audit unavailable | Path | `error` + Retry | `Result withheld: the query record could not be written` / `Every query is recorded (the masked query and its verdict) before a result is shown. The record could not be written, so no result is shown.` |
| Invalid input | both | `error`, `status` | `The query was not accepted` / `Source and destination are IPv4 hosts without a prefix, the protocol TCP or UDP, the port 1–65535; an ingress interface needs its unit.` |
| Restricted | both | `restricted` | `Trace queries` / `This account can not run them.` (never Retry) |
| Offline | both | `error` + Retry | `The request did not complete` / `No answer arrived from the server in time. Check the connection and retry.` |
| Unknown failure | both | `error` + Retry | `The path could not be evaluated` or `The lookup could not be completed` / `The server answered with a failure this screen does not know. No verdict is claimed.` + code in mono |
| Stale snapshot | Lookup | pill, not a panel | `Stale · 26 h ago` on the policy select and on the result; the result is still shown |
| Stale policy on a hop | Path | pill on the hop | `Stale policy` + the sentence in §4.6 |

### 4.8 Copy: every label on Trace

| Where | Exact copy |
|---|---|
| Rail group | `TRACE` |
| Rail entry | `Trace` |
| Drawer leaves | `Trace` · `Path` · `Lookup` |
| Screen title | `Trace` |
| Screen subtitle | `Can A reach B, and which rule or hop decides? Computed from stored evidence: management intent, never observed traffic.` |
| Mode tabs | `Path · hop by hop across the topology` · `Lookup · rules of one stored policy` |
| Masked pill | `Masked session · type names and addresses as shown here (FW-TANGO-04, 192.0.2.10)` |
| Masked tooltip | `This session sees masked names and addresses. The server resolves the values you type against the current topology; a raw value, an unknown one and an ambiguous one are refused alike.` |
| Unmasked hint | `IPv4 hosts only, no prefix; the source port is not modelled.` |
| Path fields | `Source *` (`IPv4 host`) · `Destination *` (`IPv4 host`) · `Protocol` (`TCP or UDP`) · `Port *` (`1–65535`) |
| Advanced disclosure | `Advanced path options` |
| Advanced fields | `Ingress unit` (`Optional · when the source could belong to several`) · `Ingress interface` (`Optional · needs the ingress unit`) |
| Edge checkbox | `Accept the answer at the edge of the known topology (Allowed to the edge instead of UNKNOWN)` |
| Advanced summary pill | `Ingress FW-TANGO-04 · eth1` · `Edge accepted` |
| Path button | `Evaluate path` · `Evaluate path · needs a destination` · `Evaluating…` |
| Path info tooltip | `Up to 30 queries a minute. The query and its verdict are recorded; the result itself is not stored.` |
| Lookup fields | `Policy *` (`Stored snapshot · 26 h ago`) · `Source *` (`IPv4 or CIDR`) · `Destination *` (`IPv4 or CIDR`) · `Service *` (`tcp/443, udp/53 or any`) |
| Policy lock | `Chosen in Policy; change it there or clear the link` · `Change policy` |
| Optional disclosure | `Optional context · zones and target` |
| Optional fields | `Source zone` (`Optional · zone or interface`) · `Destination zone` (`Optional · zone or interface`) · `Install-on target` (`Optional · target reference`) · `Device context` (`Optional`) |
| Lookup button | `Look up matching rules` · `Look up matching rules · needs a policy` · `Looking up…` |
| Lookup info tooltip | `NAT, routing and anti-spoofing are not evaluated; IPv6 is not supported. Use addresses and zones as the rule viewer shows them.` |
| Verdict words | `Allowed` · `Denied` · `UNKNOWN` · `Partial` · `Asymmetric` · `Allowed to the edge` · `Conditional` |
| Verdict qualifiers | `Blocked at #3 FW-TANGO-04` · `UNKNOWN at #2 FW-BRAVO-02` · `Equal-cost branches · 2` · `Branches truncated` · `Asymmetric return` · `With caveats` · `Ingress inferred` |
| View toggle | `Diagram` · `List` |
| Verify action | `Verify on devices` · `Verify on devices · Role: not permitted` |
| Diagram controls | `Show neighbours` · `Show return path` · `Zoom in` · `Zoom out` · `Fit` |
| Diagram legend | `Allowed` · `Denied` · `UNKNOWN` · `Conditional` · `edge of known topology` · `return path` |
| Branch label | `Branch A · Denied at #3` · `Branch B · Allowed to the edge` |
| Hop facts | `Ingress → egress` · `Packet in → out` · `Route used` · `NAT` · `Matched rule` · `Rule (pre-NAT address)` · `Rule (post-NAT address)` · `Context` · `Why` · `Return path` |
| Hop extras | `Show return hops · 3` · `Hide return hops` · `Rule withheld by the server` · `Stale policy` |
| Lookup result | `Snapshot DOM-PAPA-01 · Standard` · `Deciding rule` · `Open rule →` · `First proven match after uncertainty (not a verdict)` · `Rules that could also match but could not be decided: none` · `UNKNOWN: rule #7 could match (reason) · View rule →` · `Limitations:` |
| Recent list | `Recent lookups · this session` |
| Deep links elsewhere | `Look up in this policy →` (Policy) · `Trace path from here →` (Devices › Routing) |
| States | see §4.7 |

## 5. What changes against the current implementation (for the engineer, not for the PO)

- `OverviewScreen`: hero facts become the four pillars with numerator-filled bars; the `Estate map` moves below the
  fold and gets the fleet-age subtitle and hatching; a verdict banner and the attention list are new; `Compliance by
  framework` gets the honest headline; the pulse card replaces `Failed jobs, 24 h` and friends.
- `NavigationRail`: new `TRACE` group and `trace` screen; `path` entry and Policy `Lookup` tab removed; redirects.
- `PathAnalysisScreen` + `PolicyLookupTab` fold into one `TraceScreen` with `M3Tabs` as the mode switch; advanced
  fields move behind a disclosure; the masked box becomes a pill; the rate-limit caption becomes a tooltip; the
  lookup `Chip` colours become `StatusPill` roles; the lookup result gets the snapshot freshness pill.
- `PathDiagram`: legend, branch labels, keyboard order, `Show return path` toggle, fixed heights.
- Fixtures under `tests/fixtures/uitest/` and the HTML render harness must follow (project-state update rule).

## 6. UNKNOWN and open questions

1. Is a daily snapshot of the four pillar figures stored, so a `since yesterday` delta can be shown? `UNKNOWN`.
2. Does the PO amend `UI_DESIGN_LANGUAGE.md` §1 to permit the three verdict words on the executive home (§0.1)?
3. Freshness scale thresholds (24 h and 72 h) and day-rounding (§2.3): PO decision B1.
4. Framework order on the home (Financial Baseline first?) and the honest headline wording: PO decision B4.
5. Does `Changes` join the `TRACE` group in the same build as `Trace` (C3), or follow with C4?
6. The exact set of attention-line sources: the eight in §3.5 come from the reviews; the compliance payload may
   offer more (`UNKNOWN` until the payload is inspected in the implementation build).
7. Whether `Verify on devices` stays on the Path verdict card for aiview as a locked control or is hidden: this spec
   renders it locked, following the synthesis's "locked, not hidden" rule for aiview.
