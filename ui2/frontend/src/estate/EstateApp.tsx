import { PanelErrorBoundary } from "../shell/PanelErrorBoundary";
import { EstateAskScreen } from "./EstateAskScreen";
import { EstateBackupsScreen } from "./EstateBackupsScreen";
import { EstateBriefScreen } from "./EstateBriefScreen";
import { EstateCasesScreen } from "./EstateCasesScreen";
import { EstateChangesScreen } from "./EstateChangesScreen";
import { EstateComplianceScreen } from "./EstateComplianceScreen";
import { EstateDossierScreen } from "./EstateDossierScreen";
import { EstateFailoverScreen } from "./EstateFailoverScreen";
import { EstateLifecycleScreen } from "./EstateLifecycleScreen";
import type { EstateRoute } from "./estateRouting";
import { EstateReportsScreen } from "./EstateReportsScreen";
import { EstateRewindScreen } from "./EstateRewindScreen";
import { EstateShell } from "./EstateShell";
import { EstateSystemScreen } from "./EstateSystemScreen";
import { EstateWallScreen } from "./EstateWallScreen";
import { EstateDataProvider } from "./useEstateData";

export function EstateApp({ route }: { readonly route: EstateRoute }) {
  return (
    <EstateDataProvider>
      <EstateShell route={route}>
        {route.kind === "brief" ? (
          <PanelErrorBoundary label="estate brief" resetKey="brief">
            <EstateBriefScreen />
          </PanelErrorBoundary>
        ) : route.kind === "wall" ? (
          <PanelErrorBoundary label="estate wall" resetKey={`wall:${route.lens}`}>
            <EstateWallScreen initialLens={route.lens} initialClusterRef={route.clusterRef} />
          </PanelErrorBoundary>
        ) : route.kind === "dossier" ? (
          <PanelErrorBoundary label="estate dossier" resetKey={`dossier:${route.deviceId}`}>
            <EstateDossierScreen deviceId={route.deviceId} fromRewind={route.fromRewind ?? null} />
          </PanelErrorBoundary>
        ) : route.kind === "failover" ? (
          <PanelErrorBoundary label="estate failover" resetKey={`failover:${route.clusterRef ?? "all"}`}>
            <EstateFailoverScreen initialClusterRef={route.clusterRef} />
          </PanelErrorBoundary>
        ) : route.kind === "changes" ? (
          <PanelErrorBoundary label="estate changes" resetKey={`changes:${route.deviceId ?? "all"}`}>
            <EstateChangesScreen initialDeviceId={route.deviceId} initialVendor={route.vendor} />
          </PanelErrorBoundary>
        ) : route.kind === "compliance" ? (
          <PanelErrorBoundary label="estate compliance" resetKey={`compliance:${route.deviceId ?? "all"}`}>
            <EstateComplianceScreen initialDeviceId={route.deviceId} initialFamily={route.family} />
          </PanelErrorBoundary>
        ) : route.kind === "ask" ? (
          <PanelErrorBoundary label="estate ask" resetKey={`ask:${route.mode}`}>
            <EstateAskScreen initialMode={route.mode} />
          </PanelErrorBoundary>
        ) : route.kind === "rewind" ? (
          <PanelErrorBoundary label="estate rewind" resetKey={`rewind:${route.at ?? ""}:${route.compare ?? ""}`}>
            <EstateRewindScreen initialAt={route.at} initialCompare={route.compare} />
          </PanelErrorBoundary>
        ) : route.kind === "cases" ? (
          <PanelErrorBoundary label="estate cases" resetKey="cases">
            <EstateCasesScreen />
          </PanelErrorBoundary>
        ) : route.kind === "lifecycle" ? (
          <PanelErrorBoundary label="estate lifecycle" resetKey="lifecycle">
            <EstateLifecycleScreen />
          </PanelErrorBoundary>
        ) : route.kind === "reports" ? (
          <PanelErrorBoundary label="estate reports" resetKey="reports">
            <EstateReportsScreen />
          </PanelErrorBoundary>
        ) : route.kind === "system" ? (
          <PanelErrorBoundary label="estate system" resetKey="system">
            <EstateSystemScreen />
          </PanelErrorBoundary>
        ) : (
          <PanelErrorBoundary label="estate backups" resetKey={`backups:${route.query}`}>
            <EstateBackupsScreen initialQuery={route.query} />
          </PanelErrorBoundary>
        )}
      </EstateShell>
    </EstateDataProvider>
  );
}
