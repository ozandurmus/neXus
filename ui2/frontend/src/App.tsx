import { useEffect, useRef, type ComponentType } from "react";
import Box from "@mui/material/Box";

import { NavigationRail } from "./shell/NavigationRail";
import { TopAppBar } from "./shell/TopAppBar";
import { PreviewBanner } from "./preview/PreviewBanner";
import { PanelErrorBoundary } from "./shell/PanelErrorBoundary";
import { OverviewScreen } from "./screens/OverviewScreen";
import { InventoryScreen } from "./screens/InventoryScreen";
import { PolicyScreen, PolicyPreview } from "./screens/PolicyScreen";
import { TraceScreen } from "./screens/TraceScreen";
import { ChangesScreen } from "./screens/ChangesScreen";
import { DiagnosticsScreen } from "./screens/DiagnosticsScreen";
import { RuleUsageScreen } from "./screens/RuleUsageScreen";
import { ZoneMatrixScreen } from "./screens/zonematrix/ZoneMatrixScreen";
import { CutoverScreen } from "./screens/CutoverScreen";
import { ConfigurationScreen } from "./screens/ConfigurationScreen";
import { ComplianceScreen } from "./screens/ComplianceScreen";
import { BackupScreen } from "./screens/BackupScreen";
import { OperationsScreen } from "./screens/OperationsScreen";
import { PacketAnalysisScreen } from "./screens/PacketAnalysisScreen";
import { PacketAnalysisPreview } from "./preview/PacketAnalysisPreview";
import { SanitizerScreen } from "./screens/SanitizerScreen";
import { SanitizerPreview } from "./preview/SanitizerPreview";
import { ReportsScreen } from "./screens/ReportsScreen";
import { AdministrationScreen } from "./screens/AdministrationScreen";
import { DeviceWorkspaceScreen } from "./screens/DeviceWorkspaceScreen";
import { PRODUCT_NAME } from "./brand/product";
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
import { URL_CHANGE_EVENT, handleInAppLinkClick, useLocationPathname, useLocationSearch } from "./shell/urlParams";

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
  rule_usage: RuleUsageScreen,
  zone_matrix: ZoneMatrixScreen,
  cutover: CutoverScreen,
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
  rule_usage: PolicyPreview,
  zone_matrix: PolicyPreview,
  cutover: PolicyPreview,
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

export function App({
  search: searchProp,
  pathname: pathnameProp,
}: {
  readonly search?: string;
  readonly pathname?: string;
}) {
  if (typeof document !== "undefined" && document.title !== PRODUCT_NAME) {
    document.title = PRODUCT_NAME;
  }
  const liveSearch = useLocationSearch();
  const livePathname = useLocationPathname();
  const initialRef = useRef({
    search: liveSearch,
    pathname: livePathname,
    searchProp,
    pathnameProp,
    navigated: false,
  });
  if (initialRef.current.searchProp !== searchProp || initialRef.current.pathnameProp !== pathnameProp) {
    initialRef.current = {
      search: liveSearch,
      pathname: livePathname,
      searchProp,
      pathnameProp,
      navigated: false,
    };
  }
  if (liveSearch !== initialRef.current.search || livePathname !== initialRef.current.pathname) {
    initialRef.current.navigated = true;
  }
  useEffect(() => {
    const onNavigate = () => {
      initialRef.current.navigated = true;
    };
    window.addEventListener("popstate", onNavigate);
    window.addEventListener(URL_CHANGE_EVENT, onNavigate);
    return () => {
      window.removeEventListener("popstate", onNavigate);
      window.removeEventListener(URL_CHANGE_EVENT, onNavigate);
    };
  }, []);

  const search = !initialRef.current.navigated && searchProp !== undefined ? searchProp : liveSearch;
  const pathname = !initialRef.current.navigated && pathnameProp !== undefined ? pathnameProp : livePathname;

  const deviceId = pathname.startsWith("/devices/") ? pathname.substring("/devices/".length) : "";
  const isDeviceWorkspace = deviceId !== "";

  const { screen: searchScreen, preview } = isDeviceWorkspace
    ? { screen: "inventory" as ScreenId, preview: false }
    : screenFromSearch(search);
  const screen: ScreenId = isDeviceWorkspace
    ? "inventory"
    : pathname === "/compliance" || pathname === "/compliance/"
      ? "compliance"
      : pathname === "/backups" || pathname === "/backups/"
        ? "backups"
        : searchScreen;
  const Product = PRODUCT_SCREENS[screen];
  const Preview = PREVIEW_SCREENS[screen];

  return (
    <DisplayModeProvider search={search}>
      <Box
        sx={{ display: "flex", minHeight: "100vh" }}
        onClick={(e) => {
          handleInAppLinkClick(e);
        }}
      >
        {!isDeviceWorkspace && isWall(search) ? null : <NavigationRail active={screen} />}
        <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
          <TopAppBar />
          {!isDeviceWorkspace && preview && <PreviewBanner />}
          <Box component="main" sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
            {isDeviceWorkspace ? (
              <PanelErrorBoundary label="device workspace" resetKey={deviceId}>
                <DeviceWorkspaceScreen deviceId={deviceId} />
              </PanelErrorBoundary>
            ) : (
              <PanelErrorBoundary label={`screen ${screen}`} resetKey={`${screen}:${preview ? "preview" : "product"}`}>
                {preview ? <Preview /> : <Product />}
              </PanelErrorBoundary>
            )}
          </Box>
        </Box>
      </Box>
    </DisplayModeProvider>
  );
}
