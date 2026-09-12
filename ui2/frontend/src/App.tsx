import Box from "@mui/material/Box";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";

import { NavigationRail } from "./shell/NavigationRail";
import { DevicesEmptyState } from "./shell/DevicesEmptyState";
import { PreviewBanner } from "./preview/PreviewBanner";
import { OverviewPreview } from "./preview/OverviewPreview";
import { InventoryPreview } from "./preview/InventoryPreview";
import { m3Theme } from "./theme/m3Theme";

/**
 * UI 2.0 shell. React + MUI on the Material 3 scheme from the Product Owner's
 * design canvas.
 *
 * The default screen is the product's own and it is empty, because the
 * database is empty. `?preview=overview` and `?preview=inventory` render the
 * target screens with synthetic data so the design can be reviewed while it is
 * being built; both carry a banner saying so. Keeping the two apart is the
 * point: a labelled preview is a design artefact, while seeded rows on the
 * product screen would be fabricated certainty.
 */
export type Screen = "devices" | "overview-preview" | "inventory-preview";

export function screenFromSearch(search: string): Screen {
  const preview = new URLSearchParams(search).get("preview");
  if (preview === "overview") return "overview-preview";
  if (preview === "inventory") return "inventory-preview";
  return "devices";
}

export function App({ search = typeof window === "undefined" ? "" : window.location.search }: { readonly search?: string }) {
  const screen = screenFromSearch(search);
  const isPreview = screen !== "devices";
  const active = screen === "overview-preview" ? "overview" : "devices";

  return (
    <ThemeProvider theme={m3Theme}>
      <CssBaseline />
      <Box sx={{ display: "flex", minHeight: "100vh" }}>
        <NavigationRail active={active} />
        <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
          {isPreview && <PreviewBanner />}
          {screen === "devices" && <DevicesEmptyState />}
          {screen === "overview-preview" && <OverviewPreview />}
          {screen === "inventory-preview" && <InventoryPreview />}
        </Box>
      </Box>
    </ThemeProvider>
  );
}
