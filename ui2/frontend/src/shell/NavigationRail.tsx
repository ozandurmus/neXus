import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";

/**
 * The M3 navigation rail from the design canvas: 80px items, a 56x32 pill
 * behind the active icon, label under it.
 *
 * Destinations are the canvas's own: Overview, Devices, Config, Compliance,
 * Operations, Admin. Only the destination the shell can actually serve is
 * enabled — a menu entry that navigates nowhere would be the "honest
 * affordance" rule broken in the first screen that states it.
 */
export interface RailDestination {
  readonly id: string;
  readonly label: string;
  readonly glyph: string;
  readonly enabled: boolean;
}

export const DESTINATIONS: readonly RailDestination[] = [
  { id: "overview", label: "Overview", glyph: "◎", enabled: false },
  { id: "devices", label: "Devices", glyph: "▤", enabled: true },
  { id: "config", label: "Config", glyph: "⚙", enabled: false },
  { id: "compliance", label: "Compliance", glyph: "✓", enabled: false },
  { id: "operations", label: "Operations", glyph: "⟳", enabled: false },
  { id: "admin", label: "Admin", glyph: "⛭", enabled: false },
];

export function NavigationRail({ active }: { readonly active: string }) {
  return (
    <Box
      component="nav"
      aria-label="Primary"
      sx={{
        width: 96,
        flex: "none",
        bgcolor: m3.surface,
        py: 2,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 1.5,
        borderRight: `1px solid ${m3.outlineVar}`,
      }}
    >
      <Box sx={{ width: 40, height: 40, borderRadius: "12px", bgcolor: m3.primary,
                 color: m3.onPrimary, display: "grid", placeItems: "center",
                 fontWeight: 600, mb: 1 }}>
        SX
      </Box>
      {DESTINATIONS.map((d) => {
        const on = d.id === active;
        return (
          <Box
            key={d.id}
            aria-current={on ? "page" : undefined}
            aria-disabled={!d.enabled || undefined}
            sx={{
              width: 80,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              gap: "4px",
              color: on ? m3.onSecondaryContainer : m3.onSurfaceVar,
              opacity: d.enabled ? 1 : 0.38,
            }}
          >
            <Box sx={{ width: 56, height: 32, borderRadius: "16px",
                       display: "grid", placeItems: "center", fontSize: 18,
                       bgcolor: on ? m3.secondaryContainer : "transparent" }}>
              {d.glyph}
            </Box>
            <Typography sx={{ fontSize: 12, fontWeight: 500, letterSpacing: "0.5px" }}>
              {d.label}
            </Typography>
          </Box>
        );
      })}
    </Box>
  );
}
