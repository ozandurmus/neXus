# Estate view effectiveness review — 2026-11-21

Status: REVIEW (non-authoritative; read-only, no code changed).

## Scope and method

Purpose tested against: a security/network operations lead must see in one look which units are at risk (failover not
ready, backup missing or stale, compliance failing, recent changes) and act or drill down in two clicks.

Code reviewed: `ui2/frontend/src/estate/` on a throwaway merge of `lane/q-15-estate-rewind-ui` (Slices 1-2 + Rewind)
and `lane/q-11-estate-ask-workbench` (Ask); neither is on `origin/main` yet. The merge has three textual conflicts
(`EstateApp.tsx`, `estateRouting.ts`, `EstateAlternativeView.test.tsx`), all "both sides add a route/branch"; the
review worktree resolved them by keeping both sides. Paths below are relative to `ui2/frontend/src/estate/`.

Depth: the data hook, `estateModel.ts`, the shared primitives, Brief, Wall, Failover, Rewind and the Brief/Compliance/Changes
truncation paths were read closely. Dossier, Backups, Compliance, Changes and Ask were read through targeted searches
(links, caps, stamps, source states), not line by line; statements about them are limited to what is cited.
Nothing was run against a device or a deployed instance; no rendered-pixel judgement is made.

## Tests run

`npx vitest run tests/Estate tests/estate` on the merged tree: 9 files, 49 tests, 48 pass, 1 fails.

| Suite | Tests | Covers |
|---|---|---|
| `estateModel.test.ts` | 12 | lens derivation, UNKNOWN ranking, age buckets, HA join |
| `estateSlice2Model.test.ts` | 4 | failover board, changes, compliance, backups models |
| `estateAskModel.test.ts` | 6 | Ask answer building, "cannot prove" verdict |
| `estateRewindModel.test.ts` | 5 | as-of figures to answers, snapshot diff |
| `EstateAlternativeView.test.tsx` | 6 | View switch persistence, Brief, Wall at 1280 px light/dark, dossier open |
| `EstateSlice2Screens.test.tsx` | 5 | Failover board (no execution buttons), lens-bar navigation, fail-closed UNKNOWN on read failure |
| `EstateAskScreen.test.tsx` | 5 | Ask screen modes and states |
| `EstateRewindScreen.test.tsx` | 1 | one single-moment render |
| `EstateMap.test.tsx` | 5 | the standard Overview map (not the Estate view) |

The one failure, `EstateAlternativeView.test.tsx:672`, is a merge artefact: q-15 changed line 670 (Rewind link now
`?screen=inventory&view=rewind`) while q-11 still carries the old assertion `?screen=changes` on the next line. Delete
line 672 when merging; this is not a product defect.

Not covered by any test (found by reading): the Brief headline with a failed overview (finding 1), per-lens age on tiles
(2), the 200-event feed cap (4), the Compliance list caps (5), Rewind units as links (8). The Rewind screen has a single
test and no compare-mode test.

## Per screen

### Brief (`EstateBriefScreen.tsx`)
- Purpose: the one-look answer. Works: a sentence headline, UNKNOWN badge counts, a "checked and fine" block that states
  its own UNKNOWN count (`estateModel.ts` checkedFine), per-domain evidence stamps, every row carries a lens link and a
  standard-view twin.
- Hides/invents: (a) the headline counts attention *rows*, not units, so "3 items need action" can be 1 unit or 40
  (`EstateBriefScreen.tsx:450-470`). (b) UNKNOWN is folded into the good headline: with zero act and review rows it says
  "Nothing needs immediate action across the evidenced estate" even when `unknownRows` is non-empty (they only reach the
  badge), and when the overview read fails but `/devices` succeeds `model.attention` is null, `rows` becomes `[]`, and the
  same sentence shows (`:450`, `useEstateData.ts` fatal-error rule only trips when devices also fail). (c) The
  Inventory stamp falls back to the newest device read, not an overview timestamp (`:478`).
- Dead ends: the failed-jobs row has no Estate destination (`rowCategory` returns null href, `:96`); the Configuration and
  Jobs stamps open standard screens without saying so (`:479-482`).

### Wall (`EstateWallScreen.tsx`, `EstatePrimitives.tsx`)
- Purpose: scan all units by lens. Works: HA pairs joined, UNKNOWN hatched with a glyph (not colour alone), UNKNOWN sorts right after Act, detail
  panel shows the lens answer's own evidence time (`:697`), never-read stated explicitly.
- Hides: (a) tile tooltip/aria text says "evidence N ago" from the device's inventory read for *every* lens
  (`EstatePrimitives.tsx` UnitTile `ageText`), so on Backups or Controls a 3-minute-old inventory read masks a 6-day-old
  archive. (b) Lens source stamps show the newest timestamp across units (`EstateWallScreen.tsx:~160-200`, `.sort().at(-1)`),
  hiding the oldest; the Backups stamp falls back to inventory age. (c) The Age legend counts a 6-24 h read as "Fine".
- Dead ends: none structural; tiles open the dossier.

### Dossier (`EstateDossierScreen.tsx`)
- Purpose: everything about one unit. Works: per-lens rows carry their own href, peer link, change link. Compliance and
  backup sections offer both Estate and standard links.
- Dead ends / friction: lifecycle, policy, diagnostics and support-bundle rows (`:443-465`, `:623`, `:659`) leave the
  Estate view for standard screens with no marker on the link and no way back except the View switch.
- Not verified: per-section stamp handling beyond the lens rows.

### Failover board (`EstateFailoverScreen.tsx`)
- Purpose: which pairs would not survive a failover. Works best of all screens: Ready / Not ready / UNKNOWN / stale-Ready
  columns, per-check matrix, VSX chassis and VS units, source-failure banner ("every cluster stays UNKNOWN"), no execution
  buttons, readiness-is-not-authorization text, standard HA link. The test proves the read-failure path.
- Hides: `estateModel.ts` maps UNSUPPORTED (and a cluster missing from the failover rows) to `na` ("Not applicable"),
  both per unit and `pairState` (`failoverAnswer`, `pairState`). For a risk lead, "this vendor gives no readiness
  evidence" is not "not applicable"; those pairs fall out of every at-risk count.
- Dead ends: none found.

### Changes (`EstateChangesScreen.tsx`)
- Purpose: what changed recently and by whom. Works: source coverage strip lists unavailable/partial sources,
  unknown-actor handling, per-device and per-vendor entry links.
- Hides: the feed is read with `limit: 200` and `next_cursor` is never followed or shown (`:435`; Brief/hook read
  `kinds: ["USER"]` with the same cap, `useEstateData.ts`). Past 200 events the screen and the Brief's "unknown actor"
  count silently stop with no "more exist" notice.
- Dead ends: none found.

### Compliance (`EstateComplianceScreen.tsx`)
- Purpose: which controls fail where. Works: partly-assessed and not-assessed firewalls are UNKNOWN, critical failures
  Act, data gaps counted.
- Hides: (a) one fleet-level `complianceAt` is stamped on every control row (`:587-970`); per-unit evaluation times are
  not shown. (b) The un-assessed list says "listed, never dropped" yet renders `slice(0, 12)` with no remainder count
  (`:846`); remediation rows are capped at 25 (`:899`), affected units at 24 (`:953`), per-unit verdicts at 20 (`:1027`),
  all silently.

### Backups (`EstateBackupsScreen.tsx`, `estateSlice2Model.ts`)
- Purpose: which units have no or stale backup. Works: missing and failed-attempt are Act, stale (7 days), size anomaly and
  large-archive are Review, backup-target vs not-applicable split, stored archive age stamped.
- Hides: several panels stamp `backupEvidenceAt ?? lastFleetRunAt` or fall back to `model.newestEvidenceAt`
  (`:481`, `:527`, `:547`), i.e. inventory age under a backup heading. A stale backup is only Review, not Act (consistent
  with the Brief's "serious" severity, but the lead's request treats it as risk).
- Dead ends: none found.

### Ask (`EstateAskScreen.tsx`, `estateAskModel.ts`)
- Purpose: traffic-permission questions answered from stored policy and routing. Works: verdict first, "cannot prove"
  state when rules are undecidable, evidence age on the answer, says it never observes traffic or contacts a device,
  standard Trace link.
- Fit: it does not serve the at-risk-units purpose; it is a separate workbench in the top nav at the same level as Brief,
  Estate and Changes.
- Dead end: cutover mode with no stored assessment tells the user to run one on the standard screen (`:247`), correct but
  a hand-off.

### Rewind (`EstateRewindScreen.tsx`, `estateRewindModel.ts`)
- Purpose: "what did the estate look like at T, and what changed since". Works: honest UNKNOWN where history does not
  reach (`ENROLLED_AFTER_MOMENT`, unknown reason code), per-answer evidence time, newest and oldest evidence stamps per
  lens, diff shows only differences with worse/better direction, URL carries `at` and `compare`.
- Hides: backup "Stale" means older than 24 h in Rewind (`estateRewindModel.ts`) but 7 days live
  (`BACKUP_STALE_DAYS`), so the same unit can read differently live and in Rewind; the backend threshold was not
  checked here. Only three lenses are answered (stated in the code, not clearly to the user).
- Dead ends: unit swatches and diff rows are not links, so Rewind cannot drill to a dossier (`EstateRewindScreen.tsx:201`).

## Ranked fixes (at most 10)

1. Brief headline must not fold UNKNOWN or a failed overview into "Nothing needs immediate action": include
   UNKNOWN-source count and an "overview unreadable" state (`EstateBriefScreen.tsx:450-470`; add a test with a failed overview and 200 `/devices`).
2. Per-lens age on tiles/aria/tooltips: use `unit.lenses[lens].evidenceAt`, not `lastReadAt`
   (`EstatePrimitives.tsx` UnitTile `ageText`).
3. Lens and domain stamps: show oldest as well as newest, and never fall back to inventory age under a Backups or Compliance
   heading (`EstateWallScreen.tsx` `lensSourceStamp`, `EstateBackupsScreen.tsx:481,527,547`, `estateModel.ts` checkedFine `evidenceAt`).
4. Follow or announce `next_cursor` on the change feed; show "N shown, more exist" and stop the Brief unknown-actor count
   claiming completeness (`EstateChangesScreen.tsx:435`, `useEstateData.ts`).
5. Remove silent caps or print the remainder: "+N more" for un-assessed firewalls (12), remediation rows (25), affected units
   (24), per-unit verdicts (20) (`EstateComplianceScreen.tsx:846,899,953,1027`); the "never dropped" sentence is currently false.
6. Count units, not rows, in the Brief headline ("3 items on 11 units") (`EstateBriefScreen.tsx:~460`).
7. Show UNSUPPORTED/no-readiness-evidence pairs as their own "no evidence" state in the failover lens and HA counts rather
   than Not applicable (`estateModel.ts` `failoverAnswer`, `pairState`, `haCounts`).
8. Make Rewind drillable: link units and diff rows to the dossier with the moment, and reconcile its 24 h backup-stale
   rule with the live 7-day rule or label the difference (`EstateRewindScreen.tsx:201`, `estateRewindModel.ts` `backupAnswer`).
9. Give the failed-jobs Brief row an Estate-side destination or label it as leaving the Estate view; mark standard-screen
   links in the Dossier and Brief stamps (`EstateBriefScreen.tsx:96,479`, `EstateDossierScreen.tsx:443-465,623,659`).
10. Merge hygiene: delete the stale assertion at `EstateAlternativeView.test.tsx:672` and add tests for fixes 1, 2, 4 and 5.

## Open points for the PO

- Is a stale backup (7 days) meant to be Review rather than Act in the Estate view? The Brief and Backups lens agree
  today; the request wording treats it as risk.
- Is the Rewind 24 h backup-stale rule intentional (as-of snapshot semantics) or a divergence?
