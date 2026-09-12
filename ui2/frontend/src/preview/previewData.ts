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

export interface ConfigDeviceRow {
  readonly name: string;
  readonly detail: string;
  readonly state: AlignmentState;
}

export const CONFIG_DEVICES: readonly ConfigDeviceRow[] = [
  { name: "cp-mds-01", detail: "Management · 3 CMA", state: "Aligned" },
  { name: "fw-ist-core-CLS", detail: "ClusterXL · 2 members", state: "Effective drift" },
  { name: "vsx-ist-CLS", detail: "VSX · 12 virtual systems", state: "Local override" },
  { name: "pan-ank-edge-02", detail: "PA-3420 · pano-ank-01", state: "Out of sync" },
  { name: "fw-brs-dr-01", detail: "DR site · standalone", state: "Difference observed" },
];

export const CONFIG_SETTINGS: readonly AlignmentRow[] = [
  { device: "fw-ist-core-CLS", setting: "Cluster VIP", expected: "cluster-vip.example.invalid", effective: "cluster-vip.example.invalid", state: "Aligned" },
  { device: "fw-ist-core-CLS", setting: "Hostname", expected: "fw-ist-core-{member}", effective: "fw-ist-core-01 · fw-ist-core-02", state: "Member-specific" },
  { device: "fw-ist-core-CLS", setting: "NTP · server 2", expected: "ntp-pri.example.invalid", effective: "ntp-alt.example.invalid", state: "Effective drift" },
  { device: "fw-ist-core-CLS", setting: "SNMP · trap receiver", expected: "snmp-a.example.invalid", effective: "snmp-b.example.invalid", state: "Local override" },
  { device: "fw-ist-core-CLS", setting: "Login banner", expected: "not in intent", effective: "Authorized use only", state: "Difference observed" },
];

export interface ComplianceFamilyRow {
  readonly framework: string;
  readonly covered: string;
  readonly note: string;
}

export const COMPLIANCE_FAMILIES: readonly ComplianceFamilyRow[] = [
  { framework: "CIS Benchmarks", covered: "148 / 169", note: "87.4% · +3.2 pts 30 days" },
  { framework: "ISO/IEC 27001", covered: "61 / 93", note: "65.6%" },
];

export interface ComplianceFindingRow {
  readonly control: string;
  readonly device: string;
  readonly severity: "info" | "warn" | "bad";
  readonly note: string;
}

export const COMPLIANCE_FINDINGS: readonly ComplianceFindingRow[] = [
  { control: "CIS 3.1.2 · time sync", device: "fw-ist-core-02", severity: "bad", note: "Effective drift on server 2" },
  { control: "CIS 2.4.1 · banner", device: "fw-ist-core-CLS", severity: "warn", note: "Unclassified difference" },
  { control: "CIS 4.2 · logging target", device: "pan-izm-edge-01", severity: "bad", note: "Syslog target removed" },
];

export interface OperationsJobRow {
  readonly job: string;
  readonly target: string;
  readonly status: "ok" | "warn" | "bad";
  readonly finished: string;
}

export const OPERATIONS_JOBS: readonly OperationsJobRow[] = [
  { job: "Collect evidence", target: "fw-ist-core-CLS", status: "ok", finished: "06:41 UTC" },
  { job: "Collect evidence", target: "pan-izm-edge-01", status: "bad", finished: "management plane only" },
  { job: "HA readiness assessment", target: "vsx-ist-CLS", status: "ok", finished: "06:58 UTC" },
  { job: "HA readiness assessment", target: "pan-ank-edge HA", status: "warn", finished: "mismatch" },
];

export interface ReadinessRow {
  readonly cluster: string;
  readonly state: "ok" | "warn" | "unknown";
  readonly note: string;
}

export const OPERATIONS_READINESS: readonly ReadinessRow[] = [
  { cluster: "fw-ist-core-CLS", state: "ok", note: "Ready · sync OK" },
  { cluster: "vsx-ist-CLS", state: "ok", note: "Ready · 12 VS" },
  { cluster: "pan-ank-edge HA", state: "warn", note: "Not ready · mismatch" },
  { cluster: "cp-edge-CLS", state: "unknown", note: "Undetermined" },
];

export interface AdminDeviceRow {
  readonly name: string;
  readonly vendor: string;
  readonly credentialProfile: string;
  readonly status: string;
}

export const ADMIN_DEVICES: readonly AdminDeviceRow[] = [
  { name: "cp-mds-01", vendor: "Check Point", credentialProfile: "nexus-cp-ro", status: "Enrolled" },
  { name: "fw-ist-core-01", vendor: "Check Point", credentialProfile: "nexus-cp-ro", status: "Enrolled" },
  { name: "pan-ank-edge-01", vendor: "Palo Alto Networks", credentialProfile: "nexus-api-ro", status: "Enrolled" },
  { name: "pan-izm-edge-02", vendor: "Palo Alto Networks", credentialProfile: "nexus-api-ro", status: "Draft entry" },
];
