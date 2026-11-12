# Shell brand area redesign 2026-11-13

**Status: FROZEN -- PRODUCT OWNER APPROVED, 2026-11-13** (every recommendation, relayed by the PO session): B1 = A,
the wordmark only in the expanded sidebar and the X mark alone when folded and on the narrow strip; B2 = yes, the navy
tile stays only on the sign-in screen and the browser tab icon; B3 = both fixed drawer texts ("Verified state" and
"Read-only evidence · class 0") are removed. Frontend presentation only. Builds on
[NAVIGATION_REDESIGN_2026_11_12.md](NAVIGATION_REDESIGN_2026_11_12.md).

The proposal as it was put to the Product Owner follows. The mockup (`brand-area-mockup-1112.html`) was kept in the
session notes, outside the repository.

Design only. No source file was changed. Base: worktree `nexus-navia-1112`, branch `ui/nav-ia-1112`.
Mockup: `brand-area-mockup-1112.html` in this folder (before/after, light and dark, three layouts, sign-in reference).

## What is wrong today (checked in the code)

| Finding | Where |
| --- | --- |
| The brand is shown three times in one row: tile above the rail, "SX" badge, wordmark. With the drawer open, four times. | `NavigationRail.tsx`, `TopAppBar.tsx` |
| "SX" is a leftover. It is the initials of "SecurityExpert", a placeholder product name in the first design mockups (`docs/design/ui2_mockups/Operator.dc.html`), copied into the top bar on 2026-09-19 (commit `0a6bdda9`). Nothing depends on it: no test, no e2e selector, no other source file. | `TopAppBar.tsx` lines 232-250 |
| The wordmark is an `<img>` of the light-theme file (navy letters), so its `color` prop does nothing. To stay readable in dark mode it gets a pale box behind it (`brandBackdrop`, `#eef1f5`): a light slab on a near-black bar. In light mode the box is white on a grey bar. | `brand/NexusWordmark.tsx` |
| The app mark is a navy tile with the same pale 2 px pad. In dark mode the tile itself has 1.08:1 contrast against the sidebar, so only the pad outlines it. | `brand/NexusMark.tsx` |
| The theme's `brandTeal` / `brandBlue` tokens (`#1f8f7c`, `#3b63d1`) are not the colours of the approved artwork and are the same in both modes. Their only reader, `NexusXStrokes.tsx`, is imported nowhere. | `theme/m3Theme.ts`, `brand/` |
| The sign-in screen does it right: light-on-dark artwork straight on the background, one ink colour, colour only in the X. The repo already holds that artwork (`wordmark-light-on-dark.svg`); the shell just never uses it. | `auth/LoginScreen.tsx` |

## What changes

1. **One logo, in the sidebar header.** Expanded sidebar (248 px): the wordmark, 22 px high, no box behind it. Its left edge
   lines up with the menu icons; the header is 64 px high so the wordmark sits on the same centre line as the search field.
2. **Folded rail (56 px): the X alone**, 28 px. It is the app mark's X, four nodes and hub without the navy tile, same
   geometry as `app-mark.svg`. No new external asset; the paths are already in the repo.
3. **Narrow layout (below 1100 px):** the 56 px strip shows the X exactly as the folded rail does, with `Open navigation`
   under it. The drawer header shows the same wordmark as the expanded sidebar, plus the close button.
4. **Top bar: search and account controls only.** Search moves to the left edge of the page content (24 px).
5. **Colours follow the theme, taken from the approved artwork:** light `#0c1a2b` lettering, `#0a7f96` teal, `#1f5fe0`
   blue; dark `#eaf1f8`, `#56c7dc`, `#6f9bff` (identical to sign-in).

Contrast against the surfaces (WCAG asks 3:1 for graphics): light sidebar 16.2 / 4.3 / 5.2; light drawer 16.9 / 4.5 / 5.4;
dark sidebar 16.5 / 9.5 / 7.0; dark drawer 14.9 / 8.6 / 6.3 (lettering / teal / blue). All pass.

## What is removed, and why

| Removed | Why |
| --- | --- |
| "SX" badge | Placeholder initials of a name the product never had; reads as a second logo. |
| Wordmark in the top bar | The sidebar header beside it carries it; the bar keeps to search and account. |
| Navy tile in the shell | Icon-in-a-tile next to a boxed wordmark is the clash. The tile stays where it works: sign-in and the browser tab icon. |
| Pale box behind logos (`brandBackdrop` token) | Only existed because the artwork could not change colour. |
| "Verified state" caption in the drawer header | Looks like a status but is fixed text; the sidebar header has no such line (question 3). |

Side effect, wanted: the wall display's own bar also uses the wordmark and today shows the same pale slab (wall is always
dark). It picks up the fix with no extra work.

## Files to touch (frontend only)

- `ui2/frontend/src/brand/NexusWordmark.tsx`: draw the wordmark as inline SVG (paths from `assets/wordmark.svg`), colours
  from theme tokens, no background. Keep the `height` prop. The `tagline` variant is not used anywhere in the app; leave it
  as it is.
- `ui2/frontend/src/brand/NexusMark.tsx`: inline SVG of the X without the tile; keep the `size` prop.
- `ui2/frontend/src/theme/m3Theme.ts`: add `brandInk`; set `brandTeal` / `brandBlue` per mode to the artwork values;
  delete `brandBackdrop`.
- `ui2/frontend/src/brand/colors.ts`, `NexusXStrokes.tsx`: delete (unused).
- `ui2/frontend/src/shell/NavigationRail.tsx`: three headers (expanded: wordmark + collapse; folded/narrow: X in a 64 px
  band; drawer: wordmark + close, caption removed).
- `ui2/frontend/src/shell/TopAppBar.tsx`: delete the SX box and the wordmark from the normal bar; left padding 8 to 24 px;
  drop the search's extra left margin. The wall bar is untouched.
- Not touched: `auth/LoginScreen.*`, `public/favicon.svg`, `assets/*.svg`, any backend file.

Implementation notes for whoever builds it: each inline SVG needs a unique mask id (React `useId`), because the wordmark
can be on the page twice; token colours are CSS variables, so a theme switch needs no re-render.

## Size and risk

- **Size:** small. About 90 lines of source across 6 files, about 50 lines of tests. One PR.
- **Risk:** low. Presentation only; no route, permission, API or navigation-model change. The main thing to watch is the
  two unit tests below that look for an `<img>`.

## Tests and selectors affected

- `tests/brand.test.tsx`: all five cases assert an `<img>` with `src` containing `wordmark.svg` / `app-mark.svg`. Rewrite
  to assert `role="img"`, accessible name `neXus`, and the `height` / `size` attributes.
- `tests/App.test.tsx` line 72: `within(rail).getByAltText("neXus")` becomes `getByRole("img", { name: "neXus" })`.
- `tests/NavigationRail.test.tsx`: no existing assertion breaks (`data-nav-layout`, `Collapse menu`, `Expand menu`,
  `Open navigation`, `Close navigation` all stay). Add: one `neXus` image in each of expanded, folded, narrow, drawer.
- `tests/AccountMenu.test.tsx` renders `TopAppBar`; it does not look at the brand. Add one assertion that the bar holds
  no `neXus` image and no "SX" text.
- `tests/LoginScreen.test.tsx`: unchanged.
- End-to-end (`e2e/*.spec.ts`, `browser-tests/`): no selector refers to the logo, the wordmark or "SX"; there are no
  screenshot baselines. Nothing to update.
- Not run here (design only): vitest, architecture tests, preview e2e. They are needed before the ship as usual.

## Questions for the PO (3)

1. **Expanded sidebar: wordmark only, or icon plus wordmark?** The wordmark already contains the X, so an icon beside it
   draws the X twice. Mockup section 5 shows A (wordmark only), B (X + wordmark), C (sign-in tile + wordmark).
   *Recommended: A.*
2. **Keep the navy tile for the sign-in screen and the browser tab icon only?** It reads well on the sign-in's dark
   background and as a tab icon; in the shell it is the clash. *Recommended: yes, keep it there and nowhere else.*
3. **Remove the fixed texts in the drawer: "Verified state" under the logo, and the footer "Read-only evidence · class 0"?**
   Both are constant labels, not live status, and the footer appears outdated now that the product also runs changes
   (failover). *Recommended: remove both; the footer can go in the same PR (one line).*
