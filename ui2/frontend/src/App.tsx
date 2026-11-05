import { Suspense, lazy, type ComponentType } from "react";
import Box from "@mui/material/Box";

import { NavigationRail } from "./shell/NavigationRail";
import { TopAppBar } from "./shell/TopAppBar";
import { PreviewBanner } from "./preview/PreviewBanner";
const OverviewScreen = lazy(() => import("./screens/OverviewScreen").then((m) => ({ default: m.OverviewScreen })));
const InventoryScreen = lazy(() => import("./screens/InventoryScreen").then((m) => ({ default: m.InventoryScreen })));
const PolicyScreen = lazy(() => import("./screens/PolicyScreen").then(m => ({ default: m.PolicyScreen })));
const PolicyPreview = lazy(() => import("./screens/PolicyScreen").then(m => ({ default: m.PolicyPreview })));
const TraceScreen = lazy(() => import("./screens/TraceScreen").then((m) => ({ default: m.TraceScreen })));
const ChangesScreen = lazy(() => import("./screens/ChangesScreen").then((m) => ({ default: m.ChangesScreen })));
const DiagnosticsScreen = lazy(() => import("./screens/DiagnosticsScreen").then((m) => ({ default: m.DiagnosticsScreen })));
const OfflineAnalysisScreen = lazy(() => import("./screens/OfflineAnalysisScreen").then((m) => ({ default: m.OfflineAnalysisScreen })));
const ConfigurationScreen = lazy(() => import("./screens/ConfigurationScreen").then((m) => ({ default: m.ConfigurationScreen })));
const ComplianceScreen = lazy(() => import("./screens/ComplianceScreen").then((m) => ({ default: m.ComplianceScreen })));
const BackupScreen = lazy(() => import("./screens/BackupScreen").then((m) => ({ default: m.BackupScreen })));
const OperationsScreen = lazy(() => import("./screens/OperationsScreen").then((m) => ({ default: m.OperationsScreen })));
const PacketAnalysisScreen = lazy(() => import("./screens/PacketAnalysisScreen").then((m) => ({ default: m.PacketAnalysisScreen })));
const PacketAnalysisPreview = lazy(() => import("./preview/PacketAnalysisPreview").then((m) => ({ default: m.PacketAnalysisPreview })));
const SanitizerScreen = lazy(() => import("./screens/SanitizerScreen").then((m) => ({ default: m.SanitizerScreen })));
const SanitizerPreview = lazy(() => import("./preview/SanitizerPreview").then((m) => ({ default: m.SanitizerPreview })));
const ReportsScreen = lazy(() => import("./screens/ReportsScreen").then((m) => ({ default: m.ReportsScreen })));
const AdministrationScreen = lazy(() => import("./screens/AdministrationScreen").then((m) => ({ default: m.AdministrationScreen })));
import { OverviewPreview } from "./preview/OverviewPreview";
import { ReportsPreview } from "./preview/ReportsPreview";
import { InventoryPreview } from "./preview/InventoryPreview";
import { ConfigurationPreview } from "./preview/ConfigurationPreview";
import { CompliancePreview } from "./preview/CompliancePreview";
import { BackupPreview } from "./preview/BackupPreview";
import { OperationsPreview } from "./preview/OperationsPreview";
import { AdministrationPreview } from "./preview/AdministrationPreview";
import { PathAnalysisPreview } from "./preview/PathAnalysisPreview";
import { DisplayModeProvider, isWall } from "./shell/displayMode";
import { isScreenId, type ScreenId } from "./shell/types";
import { rewriteLegacyRoute } from "./shell/legacyRoutes";

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

const PRODUCT_SCREENS: Record<ScreenId, ComponentType> = {
  overview: OverviewScreen,
  reports: ReportsScreen,
  inventory: InventoryScreen,
  configuration: ConfigurationScreen,
  policy: PolicyScreen,
  trace: TraceScreen,
  changes: ChangesScreen,
  compliance: ComplianceScreen,
  backups: BackupScreen,
  operations: OperationsScreen,
  administration: AdministrationScreen,
  diagnostics: DiagnosticsScreen,
  offline_analysis: OfflineAnalysisScreen,
  packet_analysis: PacketAnalysisScreen,
  sanitizer: SanitizerScreen,
};

const PREVIEW_SCREENS: Record<ScreenId, ComponentType> = {
  overview: OverviewPreview,
  reports: ReportsPreview,
  inventory: InventoryPreview,
  configuration: ConfigurationPreview,
  policy: PolicyPreview,
  // The design previews predate the Trace / Changes / Tools regrouping (UI review 2026-11-05): each new screen previews
  // through the synthetic preview of the screen its content came from.
  trace: PathAnalysisPreview,
  changes: OperationsPreview,
  compliance: CompliancePreview,
  backups: BackupPreview,
  operations: OperationsPreview,
  administration: AdministrationPreview,
  diagnostics: OperationsPreview,
  offline_analysis: PolicyPreview,
  packet_analysis: PacketAnalysisPreview,
  sanitizer: SanitizerPreview,
};

export function screenFromSearch(search: string): { readonly screen: ScreenId; readonly preview: boolean } {
  // Old addresses keep working (UI review 2026-11-05): an address written before the Trace / Changes / Tools /
  // Administration regrouping is rewritten to its new home, its own parameters kept, so the screen reads the new shape.
  const rewritten = rewriteLegacyRoute(search);
  if (rewritten !== null) {
    search = rewritten;
    if (typeof window !== "undefined") window.history.replaceState(null, "", `?${rewritten}`);
  }
  const params = new URLSearchParams(search);
  const preview = params.get("preview");
  if (isScreenId(preview)) return { screen: preview, preview: true };
  const screen = params.get("screen");
  // One device screen (PO 2026-09-25): Configuration is a tab of Devices. An old link lands there, its device,
  // cluster and filters kept, with the Configuration tab open.
  if (screen === "configuration" && typeof window !== "undefined") {
    params.set("screen", "inventory");
    if (params.get("device_id") || params.get("cluster_ref")) params.set("tab", "configuration");
    window.history.replaceState(null, "", `?${params.toString()}`);
    return { screen: "inventory", preview: false };
  }
  if (isScreenId(screen)) return { screen, preview: false };
  return { screen: "overview", preview: false };
}

import { DeviceWorkspaceScreen } from "./screens/DeviceWorkspaceScreen";

export function App({ search = typeof window === "undefined" ? "" : window.location.search, pathname = typeof window === "undefined" ? "" : window.location.pathname }: { readonly search?: string, readonly pathname?: string }) {
  if (pathname.startsWith("/devices/")) {
    const deviceId = pathname.substring("/devices/".length);
    if (deviceId !== "") {
      return (
        <DisplayModeProvider search={search}>
          <Box sx={{ display: "flex", minHeight: "100vh" }}>
            <NavigationRail active="inventory" />
            <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
              <TopAppBar />
              <Box component="main" sx={{ flex: 1, display: "flex", flexDirection: "column" }}>
                <DeviceWorkspaceScreen deviceId={deviceId} />
              </Box>
            </Box>
          </Box>
        </DisplayModeProvider>
      );
    }
  }

  const { screen: searchScreen, preview } = screenFromSearch(search);
  const screen = (pathname === "/compliance" || pathname === "/compliance/")
    ? "compliance"
    : (pathname === "/backups" || pathname === "/backups/")
    ? "backups"
    : searchScreen;
  const Product = PRODUCT_SCREENS[screen];
  const Preview = PREVIEW_SCREENS[screen];

  return (
    <DisplayModeProvider search={search}>
      <Box sx={{ display: "flex", minHeight: "100vh" }}>
        {isWall(search) ? null : <NavigationRail active={screen} />}
        <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
          <TopAppBar />
          {preview && <PreviewBanner />}
          <Box component="main" sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
            <Suspense fallback={null}>{preview ? <Preview /> : <Product />}</Suspense>
          </Box>
        </Box>
      </Box>
    </DisplayModeProvider>
  );
}
