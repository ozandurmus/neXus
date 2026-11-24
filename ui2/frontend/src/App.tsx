import { Suspense, lazy, useEffect, useRef, type ComponentType } from "react";
import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";

import { NavigationRail } from "./shell/NavigationRail";
import { TopAppBar } from "./shell/TopAppBar";
import { PreviewBanner } from "./preview/PreviewBanner";
import { PanelErrorBoundary } from "./shell/PanelErrorBoundary";
const OverviewScreen = lazyNamed(() => import("./screens/OverviewScreen"), "OverviewScreen");
const InventoryScreen = lazyNamed(() => import("./screens/InventoryScreen"), "InventoryScreen");
const PolicyScreen = lazyNamed(() => import("./screens/PolicyScreen"), "PolicyScreen");
const PolicyPreview = lazyNamed(() => import("./screens/PolicyScreen"), "PolicyPreview");
const TraceScreen = lazyNamed(() => import("./screens/TraceScreen"), "TraceScreen");
const ChangesScreen = lazyNamed(() => import("./screens/ChangesScreen"), "ChangesScreen");
const DiagnosticsScreen = lazyNamed(() => import("./screens/DiagnosticsScreen"), "DiagnosticsScreen");
const RuleUsageScreen = lazyNamed(() => import("./screens/RuleUsageScreen"), "RuleUsageScreen");
const ZoneMatrixScreen = lazyNamed(() => import("./screens/zonematrix/ZoneMatrixScreen"), "ZoneMatrixScreen");
const CutoverScreen = lazyNamed(() => import("./screens/CutoverScreen"), "CutoverScreen");
const ConfigurationScreen = lazyNamed(() => import("./screens/ConfigurationScreen"), "ConfigurationScreen");
const ComplianceScreen = lazyNamed(() => import("./screens/ComplianceScreen"), "ComplianceScreen");
const BackupScreen = lazyNamed(() => import("./screens/BackupScreen"), "BackupScreen");
const OperationsScreen = lazyNamed(() => import("./screens/OperationsScreen"), "OperationsScreen");
const PacketAnalysisScreen = lazyNamed(() => import("./screens/PacketAnalysisScreen"), "PacketAnalysisScreen");
const PacketAnalysisPreview = lazyNamed(() => import("./preview/PacketAnalysisPreview"), "PacketAnalysisPreview");
const SanitizerScreen = lazyNamed(() => import("./screens/SanitizerScreen"), "SanitizerScreen");
const SanitizerPreview = lazyNamed(() => import("./preview/SanitizerPreview"), "SanitizerPreview");
const SupportBundlesScreen = lazyNamed(() => import("./screens/SupportBundlesScreen"), "SupportBundlesScreen");
const SupportBundlesPreview = lazyNamed(() => import("./preview/SupportBundlesPreview"), "SupportBundlesPreview");
const ReportsScreen = lazyNamed(() => import("./screens/ReportsScreen"), "ReportsScreen");
const AdministrationScreen = lazyNamed(() => import("./screens/AdministrationScreen"), "AdministrationScreen");
const DeviceWorkspaceScreen = lazyNamed(() => import("./screens/DeviceWorkspaceScreen"), "DeviceWorkspaceScreen");
import { PRODUCT_NAME } from "./brand/product";
const OverviewPreview = lazyNamed(() => import("./preview/OverviewPreview"), "OverviewPreview");
const ReportsPreview = lazyNamed(() => import("./preview/ReportsPreview"), "ReportsPreview");
const InventoryPreview = lazyNamed(() => import("./preview/InventoryPreview"), "InventoryPreview");
const ConfigurationPreview = lazyNamed(() => import("./preview/ConfigurationPreview"), "ConfigurationPreview");
const CompliancePreview = lazyNamed(() => import("./preview/CompliancePreview"), "CompliancePreview");
const BackupPreview = lazyNamed(() => import("./preview/BackupPreview"), "BackupPreview");
const OperationsPreview = lazyNamed(() => import("./preview/OperationsPreview"), "OperationsPreview");
const AdministrationPreview = lazyNamed(() => import("./preview/AdministrationPreview"), "AdministrationPreview");
const PathAnalysisPreview = lazyNamed(() => import("./preview/PathAnalysisPreview"), "PathAnalysisPreview");
const EstateApp = lazyNamed(() => import("./estate/EstateApp"), "EstateApp");
import { resolveEstateRoute } from "./estate/estateRouting";
import { DisplayModeProvider, isWall, useDisplayMode } from "./shell/displayMode";
import { isScreenId, type ScreenId } from "./shell/types";
import { rewriteLegacyRoute } from "./shell/legacyRoutes";
import { reloadOnceForStaleChunk } from "./shell/chunkReload";
import { URL_CHANGE_EVENT, handleInAppLinkClick, useLocationPathname, useLocationSearch } from "./shell/urlParams";

/**
 * Route-level code splitting: each screen and preview is its own chunk, fetched when first routed to. The modules use
 * named exports, so the loader maps the named export to the default `React.lazy` expects.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function lazyNamed<M extends Record<string, any>, K extends keyof M>(load: () => Promise<M>, name: K) {
  return lazy(async () => {
    try {
      return { default: (await load())[name] as ComponentType<any> };
    } catch (error) {
      // A chunk removed by a deploy: reload once to the new build instead of a dead section (PO 2026-11-24).
      if (reloadOnceForStaleChunk(error)) return new Promise<never>(() => undefined);
      throw error;
    }
  });
}

function ScreenFallback() {
  return (
    <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", p: 4 }}>
      <CircularProgress aria-label="Loading screen" />
    </Box>
  );
}

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
  support_bundles: SupportBundlesScreen,
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
  support_bundles: SupportBundlesPreview,
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
  // "devices" is the menu's own word for the inventory screen (2026-11-23: a typed ?screen=devices fell to Overview).
  if ((screen === "configuration" || screen === "devices") && typeof window !== "undefined") {
    params.set("screen", "inventory");
    if (screen === "configuration" && (params.get("device_id") || params.get("cluster_ref"))) params.set("tab", "configuration");
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
      <AppViewport
        search={search}
        pathname={pathname}
        screen={screen}
        preview={preview}
        isDeviceWorkspace={isDeviceWorkspace}
        deviceId={deviceId}
        Product={Product}
        Preview={Preview}
      />
    </DisplayModeProvider>
  );
}

function AppViewport({
  search,
  pathname,
  screen,
  preview,
  isDeviceWorkspace,
  deviceId,
  Product,
  Preview,
}: {
  readonly search: string;
  readonly pathname: string;
  readonly screen: ScreenId;
  readonly preview: boolean;
  readonly isDeviceWorkspace: boolean;
  readonly deviceId: string;
  readonly Product: ComponentType;
  readonly Preview: ComponentType;
}) {
  const { view } = useDisplayMode();
  const estateRoute = view === "estate" && !preview && !isWall(search)
    ? resolveEstateRoute(screen, pathname, search)
    : null;

  if (estateRoute !== null) {
    return (
      <Box
        sx={{ display: "flex", minHeight: "100vh" }}
        onClick={(e) => {
          handleInAppLinkClick(e);
        }}
      >
        <PanelErrorBoundary
          label={`estate ${estateRoute.kind}`}
          resetKey={estateRoute.kind === "dossier" ? `dossier:${estateRoute.deviceId}` : estateRoute.kind}
        >
          <Suspense fallback={<ScreenFallback />}>
            <EstateApp route={estateRoute} />
          </Suspense>
        </PanelErrorBoundary>
      </Box>
    );
  }

  return (
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
              <Suspense fallback={<ScreenFallback />}>
                <DeviceWorkspaceScreen deviceId={deviceId} />
              </Suspense>
            </PanelErrorBoundary>
          ) : (
            <PanelErrorBoundary label={`screen ${screen}`} resetKey={`${screen}:${preview ? "preview" : "product"}`}>
              <Suspense fallback={<ScreenFallback />}>{preview ? <Preview /> : <Product />}</Suspense>
            </PanelErrorBoundary>
          )}
        </Box>
      </Box>
    </Box>
  );
}
