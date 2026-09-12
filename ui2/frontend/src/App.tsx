import Box from "@mui/material/Box";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";

import { NavigationRail } from "./shell/NavigationRail";
import { TopAppBar } from "./shell/TopAppBar";
import { PreviewBanner } from "./preview/PreviewBanner";
import { OverviewScreen } from "./screens/OverviewScreen";
import { InventoryScreen } from "./screens/InventoryScreen";
import { ConfigurationScreen } from "./screens/ConfigurationScreen";
import { ComplianceScreen } from "./screens/ComplianceScreen";
import { OperationsScreen } from "./screens/OperationsScreen";
import { AdministrationScreen } from "./screens/AdministrationScreen";
import { OverviewPreview } from "./preview/OverviewPreview";
import { InventoryPreview } from "./preview/InventoryPreview";
import { ConfigurationPreview } from "./preview/ConfigurationPreview";
import { CompliancePreview } from "./preview/CompliancePreview";
import { OperationsPreview } from "./preview/OperationsPreview";
import { AdministrationPreview } from "./preview/AdministrationPreview";
import { m3Theme } from "./theme/m3Theme";
import { isScreenId, type ScreenId } from "./shell/types";

/**
 * UI 2.0 shell. React + MUI on the Material 3 scheme from the Product Owner's
 * design canvas.
 *
 * All six product screens route through `?screen=<id>`, and each renders its
 * own empty state because the database is empty. `?preview=<id>` renders the
 * same screen's target, populated look with synthetic data so the design can
 * be reviewed while it is being built; every preview carries a banner saying
 * so. Keeping the two apart is the point: a labelled preview is a design
 * artefact, while seeded rows on a product screen would be fabricated
 * certainty.
 */
export type { ScreenId } from "./shell/types";

const PRODUCT_SCREENS: Record<ScreenId, () => JSX.Element> = {
  overview: OverviewScreen,
  inventory: InventoryScreen,
  configuration: ConfigurationScreen,
  compliance: ComplianceScreen,
  operations: OperationsScreen,
  administration: AdministrationScreen,
};

const PREVIEW_SCREENS: Record<ScreenId, () => JSX.Element> = {
  overview: OverviewPreview,
  inventory: InventoryPreview,
  configuration: ConfigurationPreview,
  compliance: CompliancePreview,
  operations: OperationsPreview,
  administration: AdministrationPreview,
};

export function screenFromSearch(search: string): { readonly screen: ScreenId; readonly preview: boolean } {
  const params = new URLSearchParams(search);
  const preview = params.get("preview");
  if (isScreenId(preview)) return { screen: preview, preview: true };
  const screen = params.get("screen");
  if (isScreenId(screen)) return { screen, preview: false };
  return { screen: "overview", preview: false };
}

export function App({ search = typeof window === "undefined" ? "" : window.location.search }: { readonly search?: string }) {
  const { screen, preview } = screenFromSearch(search);
  const Product = PRODUCT_SCREENS[screen];
  const Preview = PREVIEW_SCREENS[screen];

  return (
    <ThemeProvider theme={m3Theme}>
      <CssBaseline />
      <Box sx={{ display: "flex", minHeight: "100vh" }}>
        <NavigationRail active={screen} />
        <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
          <TopAppBar />
          {preview && <PreviewBanner />}
          {preview ? <Preview /> : <Product />}
        </Box>
      </Box>
    </ThemeProvider>
  );
}
