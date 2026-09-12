/**
 * Design-preview data. **Not live data, and never presented as such.**
 *
 * Every name here is synthetic and comes from two places that are already
 * sanitized: the Product Owner's own design canvas (the `M3*` artboards) and
 * this repository's `tests/fixtures/uitest/` set. No real hostname, address,
 * serial or policy name appears — `AGENTS.md`'s sensitive identity law applies
 * to a mockup exactly as it applies to a report, because a screenshot of a
 * mockup travels further than a report does.
 *
 * The preview exists so the target screens can be reviewed while they are
 * being built. It is reachable only through an explicit `?preview=1`, and the
 * screen it renders says on its face that the values are synthetic. The
 * product's own Devices screen stays empty, because the database is empty:
 * showing seeded rows there would be fabricated certainty, which is a
 * different thing from a labelled design preview.
 */

export interface PostureCard {
  readonly title: string;
  readonly value: string;
  readonly sub: string;
  readonly tone: "neutral" | "ok" | "warn" | "bad";
}

export const POSTURE: readonly PostureCard[] = [
  { title: "Network inventory", value: "42 / 46", sub: "live · 4 stale, last-known-good", tone: "warn" },
  { title: "Configuration", value: "40 / 42", sub: "evidence · 2 partial, SSH timeout", tone: "warn" },
  { title: "Local overrides", value: "6", sub: "intentional · 5 expected member differences", tone: "neutral" },
  { title: "Effective drift", value: "2", sub: "unexplained · 1 Panorama out of sync", tone: "bad" },
];

export type AlignmentState =
  | "Aligned"
  | "Member-specific"
  | "Local override"
  | "Difference observed"
  | "Effective drift"
  | "Out of sync";

export interface AlignmentRow {
  readonly device: string;
  readonly setting: string;
  readonly expected: string;
  readonly effective: string;
  readonly state: AlignmentState;
}

export const ALIGNMENT: readonly AlignmentRow[] = [
  { device: "fw-ist-core-02", setting: "ntp.server[2]", expected: "ntp-pri.example.invalid", effective: "ntp-alt.example.invalid", state: "Effective drift" },
  { device: "pan-izm-edge-01", setting: "log.syslog.target", expected: "syslog-a.example.invalid:514", effective: "removed", state: "Effective drift" },
  { device: "fw-ist-core-01", setting: "ha.monitor.interface", expected: "eth1", effective: "eth1", state: "Member-specific" },
  { device: "pan-ank-edge-02", setting: "panorama.template", expected: "TPL-EDGE-V4", effective: "TPL-EDGE-V3", state: "Out of sync" },
  { device: "cp-edge-a", setting: "dns.resolver[1]", expected: "dns-pri.example.invalid", effective: "dns-pri.example.invalid", state: "Aligned" },
];

export const ALIGNMENT_TOTALS: readonly { readonly label: string; readonly n: number }[] = [
  { label: "Aligned", n: 31 },
  { label: "Member-specific", n: 5 },
  { label: "Local override", n: 6 },
  { label: "Difference observed", n: 3 },
  { label: "Effective drift", n: 2 },
  { label: "Out of sync", n: 1 },
];

export interface InventoryRow {
  readonly kind: "CP" | "VSX" | "PAN" | "MDS";
  readonly name: string;
  readonly detail: string;
  readonly liveness: "Live" | "Stale" | "No live data";
  readonly note: string;
}

export const INVENTORY: readonly InventoryRow[] = [
  { kind: "MDS", name: "cp-mds-01", detail: "Management server · 3 CMA · 24 gateways", liveness: "Live", note: "MDS" },
  { kind: "CP", name: "fw-ist-core-CLS", detail: "ClusterXL · fw-ist-core-01 · fw-ist-core-02", liveness: "Live", note: "2 members, 6 interfaces" },
  { kind: "CP", name: "fw-ist-core-01", detail: "Member · active · R81.20", liveness: "Live", note: "3 interfaces" },
  { kind: "VSX", name: "vsx-ist-CLS", detail: "VSX cluster · 12 virtual systems", liveness: "Live", note: "12 VS" },
  { kind: "VSX", name: "VS-PAYMENTS", detail: "Virtual system · vsx-ist-CLS", liveness: "Live", note: "4 interfaces" },
  { kind: "CP", name: "cp-edge-CLS", detail: "ClusterXL · cp-edge-a · cp-edge-b", liveness: "Stale", note: "6 d last live 09-02" },
  { kind: "PAN", name: "pan-ank-edge-01", detail: "PA-3420 · PAN-OS 11.1.4 · pano-ank-01", liveness: "Live", note: "5 interfaces" },
  { kind: "PAN", name: "pan-izm-edge-01", detail: "PA-1410 · PAN-OS 10.2.9 · pano-izm-01", liveness: "No live data", note: "management plane only" },
];

export const INVENTORY_FILTERS: readonly { readonly label: string; readonly n: number }[] = [
  { label: "All", n: 42 },
  { label: "Check Point", n: 24 },
  { label: "Palo Alto", n: 16 },
  { label: "Stale", n: 4 },
];
