import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";

/** Synthetic: documentation-only names. The real screen reads the server; a preview sends nothing. */
export function SupportBundlesPreview() {
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Support bundles" subtitle="Vendor support files · synthetic preview" />
      <table aria-label="Support bundles">
        <thead><tr><th>Device</th><th>State</th><th>Items</th><th>Expires</th></tr></thead>
        <tbody><tr><td>FW-TANGO-04</td><td>COMPLETED</td><td>Gaia details: COMPLETED 18 KiB e3b0c44298fc; Latest HCP health report: COMPLETED 4.2 MiB</td><td>2026-11-29</td></tr></tbody>
      </table>
    </ScreenRoot>
  );
}
