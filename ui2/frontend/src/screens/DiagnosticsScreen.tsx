import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { DiagnosticPanel } from "./DiagnosticPanel";

/**
 * Diagnostics under Tools (UI review 2026-11-05, C4; Argon R-21): the single-device diagnostic reads were a tab of
 * Operations, which is about the estate; they sit with the other ad hoc engineering tools now. The panel and its device
 * command gate are unchanged. `?screen=operations&tab=diagnostics` lands here (see `legacyRoutes`).
 */
export function DiagnosticsScreen() {
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Diagnostics" subtitle="Run an approved read-only diagnostic on one device and keep its result. Single-device and on demand; estate health is under Operations." />
      <DiagnosticPanel />
    </ScreenRoot>
  );
}
