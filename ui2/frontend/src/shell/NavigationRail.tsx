import { useState } from "react";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";
import { Icon, type IconName } from "./Icon";
import type { ScreenId } from "./types";
import { NexusMark } from "../brand/NexusMark";
import { NexusWordmark } from "../brand/NexusWordmark";
import { useSession } from "../auth/SessionContext";

/**
 * The M3 navigation from the design canvas's `M3Components` artboard: a
 * collapsed 88px rail (the state every other `M3*` frame uses) that expands
 * to a 360px drawer with the same destinations grouped under headers, plus
 * two sub-items per group the product does not yet serve.
 *
 * The canvas draws six collapsed rail destinations (Overview, Devices,
 * Config, Compliance, Operations, Admin) and, expanded, groups four of them
 * under section headers ("Devices", "Planes", "Operations",
 * "Administration"). Most hold two leaves; "Operations" holds three, because
 * "Backups & Recovery" belongs to the Operations plane it already gates on
 * rather than to "Planes", where it sat until NXS-LOCAL-0347. Two of those headers
 * (Operations, Administration) carry no leaf named after the group itself,
 * so the header doubles as that group's own destination -- exactly what the
 * collapsed rail already points at. "Devices" and "Planes" are pure section
 * labels in the canvas's own CSS (`.drawer-head`, never `.drawer-item`), so
 * their leaves navigate individually instead.
 */
export interface RailDestination {
  readonly id: string;
  readonly label: string;
  readonly icon: IconName;
  readonly screen: ScreenId;
  readonly requiredPlane?: string;
}

export const DESTINATIONS: readonly RailDestination[] = [
  { id: "overview", label: "Overview", icon: "grid", screen: "overview" },
  { id: "devices", label: "Devices", icon: "devices", screen: "inventory", requiredPlane: "Devices" },
  { id: "config", label: "Config", icon: "config", screen: "configuration", requiredPlane: "Config" },
  { id: "compliance", label: "Compliance", icon: "compliance", screen: "compliance", requiredPlane: "Compliance" },
  // The Product Owner's backup requirement asks for a left-hand Backup destination
  // outright, and RECOVER is its own stage of the product axis rather than a leaf of
  // another plane. The "backup" icon was already defined and unused, so the rail was
  // drawn for this destination before it was dropped.
  { id: "backups", label: "Backups", icon: "backup", screen: "backups", requiredPlane: "Operations" },
  { id: "operations", label: "Operations", icon: "operations", screen: "operations", requiredPlane: "Operations" },
  { id: "admin", label: "Admin", icon: "admin", screen: "administration", requiredPlane: "Admin" },
];

export interface DrawerLeaf {
  readonly label: string;
  readonly screen?: ScreenId;
  readonly requiredPlane?: string;
}
export interface DrawerGroup {
  readonly header: string;
  readonly screen?: ScreenId;
  readonly requiredPlane?: string;
  readonly leaves: readonly DrawerLeaf[];
}

export const DRAWER_GROUPS: readonly DrawerGroup[] = [
  {
    header: "Devices",
    requiredPlane: "Devices",
    leaves: [
      { label: "Inventory", screen: "inventory", requiredPlane: "Devices" },
      { label: "Discovery", requiredPlane: "Devices" },
    ],
  },
  {
    header: "Planes",
    leaves: [
      { label: "Configuration", screen: "configuration", requiredPlane: "Config" },
      { label: "Compliance", screen: "compliance", requiredPlane: "Compliance" },
    ],
  },
  {
    header: "Operations",
    screen: "operations",
    requiredPlane: "Operations",
    leaves: [
      { label: "HA & readiness", screen: "operations", requiredPlane: "Operations" },
      { label: "Backups & Recovery", screen: "backups", requiredPlane: "Operations" },
      { label: "Jobs", requiredPlane: "Operations" },
    ],
  },
  {
    header: "Administration",
    screen: "administration",
    requiredPlane: "Admin",
    leaves: [
      { label: "Device management", requiredPlane: "Admin" },
      { label: "Inventory exclusions", requiredPlane: "Admin" },
    ],
  },
];

function href(screen: ScreenId | undefined): string | undefined {
  return screen ? `?screen=${screen}` : undefined;
}

export function NavigationRail({ active }: { readonly active: ScreenId }) {
  const [expanded, setExpanded] = useState(false);
  const session = useSession();
  const permissions = session?.permissions;

  const allowedDestinations = permissions && permissions.length > 0
    ? DESTINATIONS.filter((d) => !d.requiredPlane || permissions.includes(d.requiredPlane))
    : DESTINATIONS;

  const allowedDrawerGroups = permissions && permissions.length > 0
    ? DRAWER_GROUPS.filter((g) => !g.requiredPlane || permissions.includes(g.requiredPlane))
        .map((g) => ({
          ...g,
          leaves: g.leaves.filter((l) => !l.requiredPlane || permissions.includes(l.requiredPlane)),
        }))
        .filter((g) => g.screen || g.leaves.length > 0)
    : DRAWER_GROUPS;

  if (!expanded) {
    return (
      <Box
        component="nav"
        aria-label="Primary"
        sx={{
          width: 88,
          flex: "none",
          bgcolor: m3.surface,
          py: 2,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 1.5,
        }}
      >
        <Box sx={{ mb: 1 }}>
          <NexusMark size={44} />
        </Box>
        <Tooltip title="Expand navigation: every screen and its sections" placement="right">
          <IconButton
            aria-label="Expand navigation"
            onClick={() => setExpanded(true)}
            sx={{ width: 48, height: 48, borderRadius: "10px", bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`,
                  color: m3.primary, mb: 1.5 }}
          >
            <Icon name="menu" size={22} />
          </IconButton>
        </Tooltip>
        {allowedDestinations.map((d) => {
          const on = d.screen === active;
          return (
            <Box
              key={d.id}
              component="a"
              href={href(d.screen)}
              aria-current={on ? "page" : undefined}
              sx={{ width: 80, textDecoration: "none", display: "flex", flexDirection: "column",
                    alignItems: "center", gap: "4px", color: on ? m3.onSurface : m3.onSurfaceVar }}
            >
              <Box sx={{ width: 56, height: 32, borderRadius: "16px", display: "grid", placeItems: "center",
                         bgcolor: on ? m3.secondaryContainer : "transparent",
                         color: on ? m3.onSecondaryContainer : "inherit" }}>
                <Icon name={d.icon} size={20} />
              </Box>
              <Typography sx={{ fontSize: 12, fontWeight: 500, letterSpacing: "0.5px" }}>{d.label}</Typography>
            </Box>
          );
        })}
      </Box>
    );
  }

  return (
    <Box
      component="nav"
      aria-label="Primary"
      sx={{ width: 360, flex: "none", bgcolor: m3.scLow, borderRadius: "0 16px 16px 0", p: 1.5,
            display: "flex", flexDirection: "column", gap: "2px", overflow: "hidden" }}
    >
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 1, pt: 1, pb: 1.5 }}>
        <Box sx={{ display: "flex", flexDirection: "column", minWidth: 0, gap: "2px" }}>
          <NexusWordmark height={24} color={m3.onSurface} />
          <Typography variant="body2">Verified state</Typography>
        </Box>
        <IconButton aria-label="Collapse navigation" onClick={() => setExpanded(false)} sx={{ ml: "auto" }}>
          <Icon name="menu" size={20} />
        </IconButton>
      </Box>

      <DrawerItem label="Overview" on={active === "overview"} navHref="?screen=overview" />

      {allowedDrawerGroups.map((g) => (
        <Box key={g.header}>
          {g.screen ? (
            <DrawerItem label={g.header} on={active === g.screen} navHref={href(g.screen)} />
          ) : (
            <Typography sx={{ px: 2, pt: 2.25, pb: 1, fontSize: 14, fontWeight: 500,
                              letterSpacing: "0.1px", color: m3.onSurfaceVar }}>
              {g.header}
            </Typography>
          )}
          {g.leaves.map((leaf) => (
            <DrawerItem
              key={leaf.label}
              label={leaf.label}
              on={active === leaf.screen}
              navHref={href(leaf.screen)}
              disabled={!leaf.screen}
            />
          ))}
        </Box>
      ))}

      <Box sx={{ mt: "auto", mx: 2, mb: 1, pt: 1.5, borderTop: `1px solid ${m3.outlineVar}` }}>
        <Typography variant="body2">Read-only evidence · class 0</Typography>
      </Box>
    </Box>
  );
}

function DrawerItem({
  label,
  on,
  navHref,
  disabled,
}: {
  readonly label: string;
  readonly on: boolean;
  readonly navHref?: string;
  readonly disabled?: boolean;
}) {
  const content = (
    <Box sx={{ height: 48, display: "flex", alignItems: "center", gap: 1.5, px: 2, borderRadius: "24px",
               fontSize: 14, fontWeight: 500, letterSpacing: "0.1px",
               bgcolor: on ? m3.secondaryContainer : "transparent",
               color: on ? m3.onSecondaryContainer : m3.onSurfaceVar,
               opacity: disabled ? 0.38 : 1 }}>
      {label}
    </Box>
  );
  if (disabled || !navHref) {
    return <Box aria-disabled="true">{content}</Box>;
  }
  return (
    <Box component="a" href={navHref} aria-current={on ? "page" : undefined} sx={{ textDecoration: "none" }}>
      {content}
    </Box>
  );
}
