import { useState, type MouseEvent } from "react";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import useMediaQuery from "@mui/material/useMediaQuery";
import { m3 } from "../theme/m3Theme";
import { Icon, type IconName } from "./Icon";
import type { ScreenId } from "./types";
import { NexusMark } from "../brand/NexusMark";
import { NexusWordmark } from "../brand/NexusWordmark";
import { useSession } from "../auth/SessionContext";

/**
 * The rail is the product principle written down the left edge (UI product review 2026-10-18, §2.3 / roadmap 1.1):
 * Overview, then SEE Devices, VERIFY Compliance and Policy, RECOVER Backups, OPERATE Operations and Tools, with
 * Administration at the bottom. The group labels make the order explain itself. Every route keeps a home and an active
 * item; `Tools` is one rail entry whose two destinations (Packets, Sanitizer) open from a small menu, because both are
 * offline file-in/file-out tools rather than planes of the estate.
 *
 * Width rule: from {@link DRAWER_BELOW_PX} up the rail is persistent with its labels and groups and there is no drawer
 * at all. Below that width the rail shrinks to icons and a `≡` button opens the same list as a temporary drawer -- the
 * old 360 px expanded drawer is gone from the wide layout, where it only repeated the rail.
 */
export const DRAWER_BELOW_PX = 1100;

/** The product groups, in the order of the principle. `null` means the item sits outside every group (Overview). */
export type RailGroup = "SEE" | "VERIFY" | "RECOVER" | "OPERATE";

export interface RailDestination {
  readonly id: string;
  readonly label: string;
  readonly icon: IconName;
  readonly screen: ScreenId;
  readonly requiredPlane?: string;
}

export interface RailEntry {
  readonly id: string;
  readonly label: string;
  readonly icon: IconName;
  readonly group: RailGroup | null;
  /** A single destination ... */
  readonly screen?: ScreenId;
  readonly requiredPlane?: string;
  /** ... or a menu of destinations (Tools): active when any of them is the open screen. */
  readonly destinations?: readonly RailDestination[];
}

export const PACKETS: RailDestination = { id: "packet-analysis", label: "Packets", icon: "rows", screen: "packet_analysis", requiredPlane: "PacketAnalysis" };
export const SANITIZER: RailDestination = { id: "sanitizer", label: "Sanitizer", icon: "lock", screen: "sanitizer", requiredPlane: "Sanitizer" };

/** The rail, top to bottom; Administration is rendered last, at the bottom edge. */
export const RAIL_ENTRIES: readonly RailEntry[] = [
  { id: "overview", label: "Overview", icon: "grid", group: null, screen: "overview" },
  { id: "devices", label: "Devices", icon: "devices", group: "SEE", screen: "inventory", requiredPlane: "Devices" },
  { id: "compliance", label: "Compliance", icon: "compliance", group: "VERIFY", screen: "compliance", requiredPlane: "Compliance" },
  { id: "policy", label: "Policy", icon: "policy", group: "VERIFY", screen: "policy", requiredPlane: "Policy" },
  // The Product Owner's backup requirement asks for a left-hand Backup destination outright; RECOVER is its own verb.
  { id: "backups", label: "Backups", icon: "backup", group: "RECOVER", screen: "backups" },
  { id: "operations", label: "Operations", icon: "operations", group: "OPERATE", screen: "operations" },
  // PO decision record 2026-10-15 (Packet Analysis) and DATA_SANITIZER_CONTRACT.md §3.1 (Data Sanitizer).
  { id: "tools", label: "Tools", icon: "tools", group: "OPERATE", destinations: [PACKETS, SANITIZER] },
];

/**
 * Administration is on every route for every session (post-release review 2026-10-18, R4): it used to be gated on the
 * Admin plane, so a read-only session saw it only while already there and reached it from elsewhere only through `≡`.
 * The screen itself decides what a role may see (its registry, exclusions, trust and job-log tabs are readable); the
 * rail only has to say where it is. Its deep links in the drawer keep their plane gate.
 */
export const ADMINISTRATION: RailEntry = { id: "admin", label: "Administration", icon: "admin", group: null, screen: "administration" };

/** Kept for callers that list the rail's single destinations (tests, the shell): every entry with its own screen, Administration last. */
export const DESTINATIONS: readonly RailDestination[] = [
  ...RAIL_ENTRIES.flatMap((e) => (e.screen ? [{ id: e.id, label: e.label, icon: e.icon, screen: e.screen, requiredPlane: e.requiredPlane }] : e.destinations ?? [])),
  { id: ADMINISTRATION.id, label: ADMINISTRATION.label, icon: ADMINISTRATION.icon, screen: ADMINISTRATION.screen!, requiredPlane: ADMINISTRATION.requiredPlane },
];

export interface DrawerLeaf {
  readonly label: string;
  /** A deep link (`?screen=...&tab=...`); a leaf without one is a destination the shell cannot serve yet and is shown disabled. */
  readonly href?: string;
  readonly screen?: ScreenId;
  readonly requiredPlane?: string;
}
export interface DrawerGroup {
  readonly header: string;
  readonly screen?: ScreenId;
  readonly requiredPlane?: string;
  readonly leaves: readonly DrawerLeaf[];
}

/**
 * The narrow-width drawer: the rail's own list, with the sub-views the old drawer held re-homed under their screens
 * (`Jobs` is the Jobs tab of Operations; `Device management` and `Inventory exclusions` are Administration tabs). Nothing
 * the old drawer served is dropped; `Discovery`, which never had a screen, stays a disabled leaf under Devices.
 */
export const DRAWER_GROUPS: readonly DrawerGroup[] = [
  { header: "SEE", leaves: [
    { label: "Devices", screen: "inventory", href: "?screen=inventory", requiredPlane: "Devices" },
    { label: "Lifecycle", href: "?screen=inventory&view=lifecycle", screen: "inventory", requiredPlane: "Devices" },
    { label: "Discovery", requiredPlane: "Devices" },
  ] },
  { header: "VERIFY", leaves: [
    { label: "Compliance", screen: "compliance", href: "?screen=compliance", requiredPlane: "Compliance" },
    { label: "Policy", screen: "policy", href: "?screen=policy", requiredPlane: "Policy" },
  ] },
  { header: "RECOVER", leaves: [
    { label: "Backups", screen: "backups", href: "?screen=backups" },
  ] },
  { header: "OPERATE", leaves: [
    { label: "Operations", screen: "operations", href: "?screen=operations" },
    { label: "Jobs", href: "?screen=operations&tab=jobs", screen: "operations" },
    { label: "Packets", screen: "packet_analysis", href: "?screen=packet_analysis", requiredPlane: "PacketAnalysis" },
    { label: "Sanitizer", screen: "sanitizer", href: "?screen=sanitizer", requiredPlane: "Sanitizer" },
  ] },
  { header: "Administration", screen: "administration", leaves: [
    { label: "Device management", href: "?screen=administration&tab=device-management", screen: "administration", requiredPlane: "Admin" },
    { label: "Inventory exclusions", href: "?screen=administration&tab=inventory-exclusions", screen: "administration", requiredPlane: "Admin" },
    { label: "SSH trust", href: "?screen=administration&tab=ssh-trust", screen: "administration", requiredPlane: "Admin" },
    { label: "Job logs", href: "?screen=administration&tab=job-logs", screen: "administration", requiredPlane: "Admin" },
  ] },
];

function href(screen: ScreenId | undefined): string | undefined {
  return screen ? `?screen=${screen}` : undefined;
}

const GROUP_LABEL = { fontSize: 10, fontWeight: 700, letterSpacing: "0.12em", color: m3.onSurfaceVar, lineHeight: 1 } as const;

export function NavigationRail({ active }: { readonly active: ScreenId }) {
  // jsdom and server rendering have no matchMedia: the wide rail is the default there.
  const wide = useMediaQuery(`(min-width:${DRAWER_BELOW_PX}px)`, { defaultMatches: true, noSsr: true });
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [toolsAnchor, setToolsAnchor] = useState<HTMLElement | null>(null);
  const session = useSession();
  const permissions = session?.permissions;

  const restricted = Boolean(permissions && permissions.length > 0);
  // The open screen always keeps its item (and so its active state), whatever the plane list says: the server decides
  // what the screen may show, the rail never leaves the reader without a "you are here".
  const granted = (plane: string | undefined, screen: ScreenId | undefined) =>
    !restricted || !plane || permissions!.includes(plane) || (screen !== undefined && screen === active);
  const entryShown = (e: RailEntry) => e.destinations
    ? e.destinations.some((d) => granted(d.requiredPlane, d.screen))
    : granted(e.requiredPlane, e.screen);
  const entryActive = (e: RailEntry) => e.destinations ? e.destinations.some((d) => d.screen === active) : e.screen === active;

  const entries = RAIL_ENTRIES.filter(entryShown);
  const admin = ADMINISTRATION;
  const toolsDestinations = (RAIL_ENTRIES.find((e) => e.id === "tools")?.destinations ?? []).filter((d) => granted(d.requiredPlane, d.screen));

  const allowedDrawerGroups = DRAWER_GROUPS
    .filter((g) => granted(g.requiredPlane, g.screen))
    .map((g) => ({ ...g, leaves: g.leaves.filter((l) => granted(l.requiredPlane, l.screen)) }))
    .filter((g) => g.screen || g.leaves.length > 0);

  const openTools = (event: MouseEvent<HTMLElement>) => setToolsAnchor(event.currentTarget);

  const railItem = (e: RailEntry) => {
    const on = entryActive(e);
    const shared = {
      "aria-current": on ? ("page" as const) : undefined,
      sx: { width: wide ? 80 : 48, textDecoration: "none", display: "flex", flexDirection: "column", alignItems: "center", gap: "4px",
        color: on ? m3.onSurface : m3.onSurfaceVar, cursor: "pointer", border: 0, bgcolor: "transparent", p: 0, font: "inherit" },
    } as const;
    const body = (
      <>
        <Box sx={{ width: wide ? 56 : 40, height: 32, borderRadius: "16px", display: "grid", placeItems: "center",
                   bgcolor: on ? m3.secondaryContainer : "transparent", color: on ? m3.onSecondaryContainer : "inherit" }}>
          <Icon name={e.icon} size={20} />
        </Box>
        {wide && <Typography sx={{ fontSize: 12, fontWeight: 500, letterSpacing: "0.5px" }}>{e.label}</Typography>}
      </>
    );
    if (e.destinations) {
      return (
        <Tooltip key={e.id} title={wide ? "" : `${e.label}: ${e.destinations.map((d) => d.label).join(" · ")}`} placement="right">
          <Box component="button" type="button" aria-haspopup="menu" aria-expanded={Boolean(toolsAnchor)} aria-label={e.label} onClick={openTools} {...shared}>
            {body}
          </Box>
        </Tooltip>
      );
    }
    return (
      <Tooltip key={e.id} title={wide ? "" : e.label} placement="right">
        <Box component="a" href={href(e.screen)} {...shared}>{body}</Box>
      </Tooltip>
    );
  };

  // Group labels sit above the first entry of each group; the wide rail only (the icon strip has no room for words).
  let lastGroup: RailGroup | null | undefined;
  const items: React.ReactNode[] = [];
  for (const e of entries) {
    if (wide && e.group && e.group !== lastGroup) {
      items.push(<Typography key={`group-${e.group}`} component="div" aria-hidden sx={{ ...GROUP_LABEL, mt: 0.5 }}>{e.group}</Typography>);
    }
    lastGroup = e.group;
    items.push(railItem(e));
  }

  return (
    <>
      <Box
        component="nav"
        aria-label="Primary"
        // The rail stays in view on a long page (sticky, one viewport tall) so Administration, pinned at its bottom edge,
        // is always one click away (R4); a viewport shorter than the list scrolls the rail itself.
        sx={{ width: wide ? 88 : 56, flex: "none", bgcolor: m3.surface, pt: 2, display: "flex", flexDirection: "column", alignItems: "center", gap: 1.25,
              position: "sticky", top: 0, alignSelf: "flex-start", height: "100vh", boxSizing: "border-box", overflowY: "auto", overflowX: "hidden" }}
      >
        <Box sx={{ mb: 1 }}>
          <NexusMark size={wide ? 44 : 36} />
        </Box>
        {!wide && (
          <Tooltip title="Open navigation: every screen and its sections" placement="right">
            <IconButton aria-label="Open navigation" onClick={() => setDrawerOpen(true)}
              sx={{ width: 40, height: 40, borderRadius: "10px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, color: m3.primary, mb: 1 }}>
              <Icon name="menu" size={20} />
            </IconButton>
          </Tooltip>
        )}
        {items}
        <Box data-testid="rail-administration" sx={{ mt: "auto", pt: 2, pb: 2, width: "100%", display: "flex", flexDirection: "column", alignItems: "center",
                   position: "sticky", bottom: 0, bgcolor: m3.surface }}>
          {railItem(admin)}
        </Box>
      </Box>

      <Menu anchorEl={toolsAnchor} open={Boolean(toolsAnchor)} onClose={() => setToolsAnchor(null)}
        anchorOrigin={{ vertical: "center", horizontal: "right" }} transformOrigin={{ vertical: "center", horizontal: "left" }}
        MenuListProps={{ "aria-label": "Tools", dense: true }}>
        {toolsDestinations.map((d) => (
          <MenuItem key={d.id} component="a" href={href(d.screen)} selected={d.screen === active} aria-current={d.screen === active ? "page" : undefined}
            sx={{ gap: 1.5, fontSize: 14 }}>
            <Icon name={d.icon} size={18} />
            {d.label}
          </MenuItem>
        ))}
      </Menu>

      {!wide && (
        <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)} PaperProps={{ sx: { width: 300, bgcolor: m3.scLow, p: 1.5 } }}>
          <Box component="nav" aria-label="Primary navigation drawer" sx={{ display: "flex", flexDirection: "column", gap: "2px", minHeight: "100%" }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 1, pt: 1, pb: 1.5 }}>
              <Box sx={{ display: "flex", flexDirection: "column", minWidth: 0, gap: "2px" }}>
                <NexusWordmark height={24} color={m3.onSurface} />
                <Typography variant="body2">Verified state</Typography>
              </Box>
              <IconButton aria-label="Close navigation" onClick={() => setDrawerOpen(false)} sx={{ ml: "auto" }}>
                <Icon name="close" size={20} />
              </IconButton>
            </Box>

            <DrawerItem label="Overview" on={active === "overview"} navHref="?screen=overview" />

            {allowedDrawerGroups.map((g) => (
              <Box key={g.header}>
                {g.screen ? (
                  <DrawerItem label={g.header} on={active === g.screen} navHref={href(g.screen)} />
                ) : (
                  <Typography sx={{ ...GROUP_LABEL, px: 2, pt: 2, pb: 1 }}>{g.header}</Typography>
                )}
                {g.leaves.map((leaf) => (
                  <DrawerItem
                    key={leaf.label}
                    label={leaf.label}
                    // A leaf that opens the same screen as its group header, or a deep link into a screen, is not marked a second time.
                    on={active === leaf.screen && g.screen !== leaf.screen && leaf.href === href(leaf.screen)}
                    navHref={leaf.href}
                    disabled={!leaf.href}
                    nested={Boolean(g.screen)}
                  />
                ))}
              </Box>
            ))}

            <Box sx={{ mt: "auto", mx: 2, mb: 1, pt: 1.5, borderTop: `1px solid ${m3.outlineVar}` }}>
              <Typography variant="body2">Read-only evidence · class 0</Typography>
            </Box>
          </Box>
        </Drawer>
      )}
    </>
  );
}

function DrawerItem({ label, on, navHref, disabled, nested }: {
  readonly label: string;
  readonly on: boolean;
  readonly navHref?: string;
  readonly disabled?: boolean;
  readonly nested?: boolean;
}) {
  const content = (
    <Box sx={{ height: nested ? 40 : 48, display: "flex", alignItems: "center", gap: 1.5, px: 2, ml: nested ? 2 : 0, borderRadius: "24px",
               fontSize: nested ? 13.5 : 14, fontWeight: 500, letterSpacing: "0.1px",
               bgcolor: on ? m3.secondaryContainer : "transparent",
               color: on ? m3.onSecondaryContainer : m3.onSurfaceVar,
               opacity: disabled ? 0.38 : 1 }}>
      {label}
    </Box>
  );
  if (disabled || !navHref) {
    return <Box aria-disabled="true" title="Not served by this build yet">{content}</Box>;
  }
  return (
    <Box component="a" href={navHref} aria-current={on ? "page" : undefined} sx={{ textDecoration: "none" }}>
      {content}
    </Box>
  );
}
