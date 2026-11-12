import { useState, type MouseEvent } from "react";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Tooltip from "@mui/material/Tooltip";
import useMediaQuery from "@mui/material/useMediaQuery";
import { m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";
import type { ScreenId } from "./types";
import { NexusMark } from "../brand/NexusMark";
import { NexusWordmark } from "../brand/NexusWordmark";
import { useSession } from "../auth/SessionContext";
import { readPreference, writePreference } from "./preference";
import { useLocationSearch } from "./urlParams";
import {
  activeLeafId, formatGroupChoices, groupOpen, leafLocked, parseGroupChoices, visibleEntries,
  type NavEntry, type NavGroup, type NavLeaf, type NavSessionGrants,
} from "./navModel";

/**
 * The left navigation (NAVIGATION_REDESIGN_2026_11_12.md, PO approved 2026-11-12). One model, `navModel.ts`, three
 * renderings:
 *
 * - **Expanded sidebar**, {@link SIDEBAR_PX} px, from {@link DRAWER_BELOW_PX} px up: icon and label per top-level
 *   entry; a group opens and closes in place by its header. Every group starts open except Administration; the
 *   viewer's choice is remembered, and the group that holds the open page is always open.
 * - **Folded**, {@link FOLDED_PX} px: the same entries as icons, each named for the tooltip and the screen reader. A
 *   link icon navigates; a group icon opens a small menu of its children. The viewer folds and unfolds with
 *   `Collapse menu` / `Expand menu` (PO 2026-10-20, remembered as `nx.rail.collapsed`); between {@link DRAWER_BELOW_PX}
 *   and {@link EXPANDED_BY_DEFAULT_PX} px the sidebar starts folded unless the viewer chose otherwise.
 * - **Narrow**, below {@link DRAWER_BELOW_PX} px: the folded icons plus `Open navigation`, whose drawer renders the
 *   expanded list itself.
 *
 * The logo is shown once, at the top of whichever rendering is in view (brand area 2026-11-13): the wordmark on the
 * expanded sidebar and in the drawer, the X mark alone where only icons fit. The top bar carries none.
 *
 * Exactly one entry is the current page (`aria-current="page"`): the page itself, not its group. Folding changes
 * density only, never the entry set (D-NAV2). What is listed follows the session's plane list (the server decides
 * every request); Administration's pages are listed for every session and locked where it may not use them.
 */
export const DRAWER_BELOW_PX = 1100;
export const EXPANDED_BY_DEFAULT_PX = 1280;
export const SIDEBAR_PX = 248;
export const FOLDED_PX = 56;

/** The header band that holds the logo: as tall as the top app bar, so the logo and the search field share a centre line. */
const BRAND_BAND_PX = 64;

const COLLAPSED_KEY = "nx.rail.collapsed";
const GROUPS_KEY = "nx.nav.groups";

const LOCK_TITLE = "Restricted for this account";

function LockMark() {
  return (
    <Box component="span" data-nav-lock title={LOCK_TITLE} sx={{ ml: "auto", display: "inline-flex", color: "inherit", opacity: 0.8 }}>
      <Icon name="lock" size={14} />
    </Box>
  );
}

const ROW = { display: "flex", alignItems: "center", boxSizing: "border-box", width: "100%", textDecoration: "none", border: 0, cursor: "pointer",
  font: "inherit", textAlign: "left", whiteSpace: "nowrap", letterSpacing: "0.1px",
  "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: -2 } } as const;

/** The labelled list: the expanded sidebar's body and the drawer's body are this one component. */
function NavList({ entries, activeId, session, choices, onToggle }: {
  readonly entries: readonly NavEntry[];
  readonly activeId: string;
  readonly session: NavSessionGrants | null;
  readonly choices: Readonly<Record<string, boolean>>;
  readonly onToggle: (group: NavGroup, open: boolean) => void;
}) {
  const top = (on: boolean, strong: boolean) => ({ ...ROW, height: 36, gap: "10px", pl: 1.5, pr: 1.25, borderRadius: "18px", fontSize: 14,
    fontWeight: strong ? 600 : 500, bgcolor: on ? m3.secondaryContainer : "transparent",
    color: on ? m3.onSecondaryContainer : strong ? m3.onSurface : m3.onSurfaceVar,
    "&:hover": { bgcolor: on ? m3.secondaryContainer : m3.sc } });
  const child = (on: boolean) => ({ ...ROW, height: 32, gap: 1, pl: "42px", pr: 1.5, borderRadius: "16px", fontSize: 13.5, fontWeight: 500,
    bgcolor: on ? m3.secondaryContainer : "transparent", color: on ? m3.onSecondaryContainer : m3.onSurfaceVar,
    "&:hover": { bgcolor: on ? m3.secondaryContainer : m3.sc } });
  return (
    <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "2px" }}>
      {entries.map((entry) => {
        // Administration sits apart, after a hairline: it is about the product itself, not about the estate.
        const divider = entry.id === "administration"
          ? <Box component="li" key="divider" role="presentation" sx={{ height: "1px", bgcolor: m3.outlineVar, mx: 1.5, my: 1 }} /> : null;
        if (entry.kind === "link") {
          const on = entry.id === activeId;
          return (
            <Box component="li" key={entry.id}>
              <Box component="a" href={entry.href} aria-current={on ? "page" : undefined} sx={top(on, false)}>
                <Icon name={entry.icon} size={20} />
                <span>{entry.label}</span>
              </Box>
            </Box>
          );
        }
        const holdsActive = entry.children.some((c) => c.id === activeId);
        const open = groupOpen(entry, choices, activeId);
        const listId = `nav-group-${entry.id}`;
        return [divider, (
          <Box component="li" key={entry.id}>
            <Box component="button" type="button" aria-expanded={open} aria-controls={listId} data-holds-current={holdsActive ? "true" : undefined}
              onClick={() => onToggle(entry, !open)} sx={{ ...top(false, holdsActive), bgcolor: "transparent" }}>
              <Icon name={entry.icon} size={20} />
              <span>{entry.label}</span>
              <Box component="span" sx={{ ml: "auto", display: "inline-flex" }}><Icon name={open ? "chevron-down" : "chevron-right"} size={16} /></Box>
            </Box>
            {open && (
              <Box component="ul" id={listId} aria-label={entry.label} sx={{ listStyle: "none", m: 0, p: 0, pt: "2px", display: "flex", flexDirection: "column", gap: "2px" }}>
                {entry.children.map((leaf) => {
                  const on = leaf.id === activeId;
                  const locked = leafLocked(leaf, session);
                  return (
                    <Box component="li" key={leaf.id}>
                      <Box component="a" href={leaf.href} aria-current={on ? "page" : undefined} data-locked={locked ? "true" : undefined} sx={child(on)}>
                        <span>{leaf.label}</span>
                        {locked && <LockMark />}
                      </Box>
                    </Box>
                  );
                })}
              </Box>
            )}
          </Box>
        )];
      })}
    </Box>
  );
}

export function NavigationRail({ active }: { readonly active: ScreenId }) {
  // jsdom and server rendering have no matchMedia: the wide, expanded sidebar is the default there.
  const wide = useMediaQuery(`(min-width:${DRAWER_BELOW_PX}px)`, { defaultMatches: true, noSsr: true });
  const roomy = useMediaQuery(`(min-width:${EXPANDED_BY_DEFAULT_PX}px)`, { defaultMatches: true, noSsr: true });
  const [drawerOpen, setDrawerOpen] = useState(false);
  // The viewer's own fold choice, or none yet: without one the sidebar is folded where the screen is tight.
  const [foldChoice, setFoldChoice] = useState<boolean | null>(() => {
    const stored = readPreference(COLLAPSED_KEY);
    return stored === null ? null : stored === "1";
  });
  const collapsed = foldChoice ?? !roomy;
  const setCollapsed = (next: boolean) => { setFoldChoice(next); writePreference(COLLAPSED_KEY, next ? "1" : "0"); };
  const [choices, setChoices] = useState(() => parseGroupChoices(readPreference(GROUPS_KEY)));
  const onToggle = (group: NavGroup, open: boolean) => {
    const next = { ...choices, [group.id]: open };
    setChoices(next);
    writePreference(GROUPS_KEY, formatGroupChoices(next));
  };
  const [menu, setMenu] = useState<{ readonly anchor: HTMLElement; readonly groupId: string } | null>(null);

  const session = useSession();
  const search = useLocationSearch();
  const activeId = activeLeafId(active, search);
  const entries = visibleEntries(session?.permissions, activeId);
  // Icons without words: the narrow layout, or the wide one folded.
  const iconsOnly = !wide || collapsed;
  const menuGroup = menu ? entries.find((e): e is NavGroup => e.kind === "group" && e.id === menu.groupId) ?? null : null;

  const iconItem = (entry: NavEntry) => {
    const on = entry.kind === "link" ? entry.id === activeId : entry.children.some((c) => c.id === activeId);
    const sx = { width: 40, height: 32, borderRadius: "16px", display: "grid", placeItems: "center", border: 0, p: 0, cursor: "pointer", textDecoration: "none",
      bgcolor: on ? m3.secondaryContainer : "transparent", color: on ? m3.onSecondaryContainer : m3.onSurfaceVar,
      "&:hover": { bgcolor: on ? m3.secondaryContainer : m3.sc }, "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 } } as const;
    if (entry.kind === "link") {
      return (
        <Tooltip key={entry.id} title={entry.label} placement="right">
          <Box component="a" href={entry.href} aria-label={entry.label} aria-current={on ? "page" : undefined} sx={sx}>
            <Icon name={entry.icon} size={20} />
          </Box>
        </Tooltip>
      );
    }
    const isOpen = menu?.groupId === entry.id;
    return (
      <Tooltip key={entry.id} title={`${entry.label}: ${entry.children.map((c) => c.label).join(" · ")}`} placement="right">
        {/* The current page is a child inside the menu; the icon only says that the group holds it. */}
        <Box component="button" type="button" aria-label={entry.label} aria-haspopup="menu" aria-expanded={isOpen}
          aria-current={on ? "true" : undefined} data-holds-current={on ? "true" : undefined}
          onClick={(event: MouseEvent<HTMLElement>) => setMenu({ anchor: event.currentTarget, groupId: entry.id })} sx={sx}>
          <Icon name={entry.icon} size={20} />
        </Box>
      </Tooltip>
    );
  };

  const iconItems: React.ReactNode[] = [];
  for (const entry of entries) {
    if (entry.id === "administration") {
      iconItems.push(<Box key="divider" role="presentation" sx={{ width: 32, height: "1px", bgcolor: m3.outlineVar, my: 0.5 }} />);
    }
    iconItems.push(iconItem(entry));
  }

  const frame = { flex: "none", bgcolor: m3.surface, position: "sticky", top: 0, alignSelf: "flex-start", height: "100vh", boxSizing: "border-box",
    overflowY: "auto", overflowX: "hidden", display: "flex", flexDirection: "column" } as const;

  return (
    <>
      {iconsOnly ? (
        // The sidebar stays in view on a long page (sticky, one viewport tall); a window shorter than the list scrolls it.
        <Box component="nav" aria-label="Primary" data-nav-layout={wide ? "folded" : "narrow"}
          sx={{ ...frame, width: FOLDED_PX, pb: 2, alignItems: "center", gap: 0.75 }}>
          {/* The logo where there is no room for the word: the X alone, on the top bar's centre line. */}
          <Box data-brand-slot sx={{ height: BRAND_BAND_PX, flex: "none", display: "grid", placeItems: "center", mb: -0.75 }}><NexusMark size={28} /></Box>
          {wide && (
            <Tooltip title="Expand menu: show the labels" placement="right">
              <IconButton aria-label="Expand menu" aria-pressed onClick={() => setCollapsed(false)}
                sx={{ width: 32, height: 32, borderRadius: "8px", border: `1px solid ${m3.outlineVar}`, color: m3.onSurfaceVar, mb: 0.5 }}>
                <Icon name="chevron-right" size={18} />
              </IconButton>
            </Tooltip>
          )}
          {!wide && (
            <Tooltip title="Open navigation: every screen with its name" placement="right">
              <IconButton aria-label="Open navigation" onClick={() => setDrawerOpen(true)}
                sx={{ width: 40, height: 40, borderRadius: "10px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, color: m3.primary, mb: 0.5 }}>
                <Icon name="menu" size={20} />
              </IconButton>
            </Tooltip>
          )}
          {iconItems}
        </Box>
      ) : (
        <Box component="nav" aria-label="Primary" data-nav-layout="expanded" sx={{ ...frame, width: SIDEBAR_PX, px: 1, pb: 2 }}>
          {/* The one logo of the shell: the wordmark, its left edge on the entries' icons, on the top bar's centre line. */}
          <Box data-brand-slot sx={{ display: "flex", alignItems: "center", flex: "none", height: BRAND_BAND_PX, pl: 1.5, pr: 0.5 }}>
            <NexusWordmark height={22} />
            <Tooltip title="Collapse menu: icons only" placement="right">
              <IconButton aria-label="Collapse menu" aria-pressed={false} onClick={() => setCollapsed(true)}
                sx={{ ml: "auto", width: 32, height: 32, borderRadius: "8px", border: `1px solid ${m3.outlineVar}`, color: m3.onSurfaceVar }}>
                <Icon name="chevron-left" size={18} />
              </IconButton>
            </Tooltip>
          </Box>
          <NavList entries={entries} activeId={activeId} session={session} choices={choices} onToggle={onToggle} />
        </Box>
      )}

      <Menu anchorEl={menu?.anchor ?? null} open={Boolean(menu && menuGroup)} onClose={() => setMenu(null)}
        anchorOrigin={{ vertical: "top", horizontal: "right" }} transformOrigin={{ vertical: "top", horizontal: "left" }}
        MenuListProps={{ "aria-label": menuGroup?.label ?? "Navigation", dense: true }}>
        {(menuGroup?.children ?? []).map((leaf: NavLeaf) => {
          const on = leaf.id === activeId;
          return (
            <MenuItem key={leaf.id} component="a" href={leaf.href} selected={on} aria-current={on ? "page" : undefined}
              data-locked={leafLocked(leaf, session) ? "true" : undefined} sx={{ gap: 1.5, fontSize: 14 }}>
              {leaf.label}
              {leafLocked(leaf, session) && <LockMark />}
            </MenuItem>
          );
        })}
      </Menu>

      {!wide && (
        <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)} PaperProps={{ sx: { width: 300, bgcolor: m3.scLow, p: 1.5 } }}>
          <Box component="nav" aria-label="Primary navigation drawer" sx={{ display: "flex", flexDirection: "column", minHeight: "100%" }}>
            <Box data-brand-slot sx={{ display: "flex", alignItems: "center", flex: "none", height: 52, pl: 1.5, pr: 0.5, mb: 0.5 }}>
              <NexusWordmark height={22} />
              <IconButton aria-label="Close navigation" onClick={() => setDrawerOpen(false)} sx={{ ml: "auto" }}>
                <Icon name="close" size={20} />
              </IconButton>
            </Box>
            <NavList entries={entries} activeId={activeId} session={session} choices={choices} onToggle={onToggle} />
          </Box>
        </Drawer>
      )}
    </>
  );
}
