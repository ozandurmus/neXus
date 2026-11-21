import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { estateEntryHrefFor, screenIdFromLocation } from "../estate/estateRouting";
import { m3 } from "../theme/m3Theme";
import { useDisplayMode } from "./displayMode";
import { navigateTo, useLocationPathname, useLocationSearch } from "./urlParams";

/**
 * Per-viewer preference switch: "View: Standard / Estate" (PO 2026-11-19).
 * Persisted in localStorage (`nx.view`) via `DisplayModeProvider` alongside `nx.mode`.
 * Active choice is indicated by `aria-pressed`, font weight and filled container, never colour alone.
 */
export function ViewModeControl({ variant = "m3" }: { readonly variant?: "m3" | "estate" }) {
  const { view, setView } = useDisplayMode();
  const search = useLocationSearch();
  const pathname = useLocationPathname();
  const forcedStandard = new URLSearchParams(search).get("standard") === "1";
  const activeView = forcedStandard ? "standard" : view;

  const selectStandard = () => {
    setView("standard");
    if (forcedStandard) {
      const next = new URLSearchParams(search);
      next.delete("standard");
      const qs = next.toString();
      navigateTo(qs ? `?${qs}` : pathname || "?screen=overview", "replace");
    }
  };

  const selectEstate = () => {
    setView("estate");
    const target = estateEntryHrefFor(screenIdFromLocation(pathname, search), pathname, search);
    if (target) {
      navigateTo(target, "push");
    }
  };

  const isEstateSurface = variant === "estate";
  const borderCol = isEstateSurface ? "var(--ev-line-strong)" : m3.outlineVar;
  const surfaceBg = isEstateSurface ? "var(--ev-surface)" : m3.scLowest;
  const labelCol = isEstateSurface ? "var(--ev-ink-3)" : m3.onSurfaceVar;
  const activeBg = isEstateSurface ? "var(--ev-ink)" : m3.secondaryContainer;
  const activeFg = isEstateSurface ? "var(--ev-surface)" : m3.onSecondaryContainer;
  const inactiveFg = isEstateSurface ? "var(--ev-ink-2)" : m3.onSurfaceVar;

  const btnSx = (active: boolean) =>
    ({
      border: 0,
      borderLeft: "none",
      bgcolor: active ? activeBg : "transparent",
      color: active ? activeFg : inactiveFg,
      fontWeight: active ? 650 : 500,
      fontSize: "12px",
      lineHeight: 1,
      height: "26px",
      px: "9px",
      cursor: "pointer",
      fontFamily: "inherit",
      whiteSpace: "nowrap",
      display: "inline-flex",
      alignItems: "center",
      gap: "4px",
      "&:hover": {
        color: active ? activeFg : isEstateSurface ? "var(--ev-ink)" : m3.onSurface,
      },
      "&:focus-visible": {
        outline: `2px solid ${isEstateSurface ? "var(--ev-accent)" : m3.primary}`,
        outlineOffset: -2,
      },
    }) as const;

  return (
    <Box
      role="group"
      aria-label="View"
      data-testid="view-mode-switch"
      sx={{ display: "inline-flex", alignItems: "center", gap: "6px", flexShrink: 0 }}
    >
      <Typography
        component="span"
        sx={{
          fontSize: "12px",
          color: labelCol,
          whiteSpace: "nowrap",
          display: { xs: "none", md: "inline" },
        }}
      >
        View:
      </Typography>
      <Box
        sx={{
          display: "inline-flex",
          alignItems: "center",
          border: `1px solid ${borderCol}`,
          borderRadius: "7px",
          overflow: "hidden",
          bgcolor: surfaceBg,
        }}
      >
        <Box
          component="button"
          type="button"
          aria-pressed={activeView === "standard"}
          onClick={selectStandard}
          sx={btnSx(activeView === "standard")}
        >
          Standard
        </Box>
        <Box
          component="button"
          type="button"
          aria-pressed={activeView === "estate"}
          onClick={selectEstate}
          sx={{ ...btnSx(activeView === "estate"), borderLeft: `1px solid ${borderCol}` }}
        >
          Estate
        </Box>
      </Box>
    </Box>
  );
}
