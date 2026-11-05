/**
 * Design-preview data. **Not live data, and never presented as such.**
 *
 * Every identity here uses the product's AIView pseudonym pattern and
 * documentation-only addresses/domains. `AGENTS.md`'s sensitive identity law applies
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

/**
 * The executive home preview (UI review 2026-11-05, C1): synthetic estate figures in the shape the Overview's reads
 * return, run through the same verdict, freshness and attention logic as the live screen. Counts only: the home names
 * no device, so no identity can leak from it; the one control title is a generic benchmark wording.
 */
export const EXEC_HOME = {
  devices: 132,
  clusters: 45,
  security: { failing: 97, of: 118 },
  backups: { archived: 126, of: 130 },
  ha: { total: 45, ready: 35, notReady: 2, unknown: 3, unsupported: 5 },
  freshness: { fresh: 1, of: 132, never: 0 },
  newestReadHoursAgo: 0.3,
  oldestReadHoursAgo: 31,
  backupRunHoursAgo: 47,
  complianceHoursAgo: 31,
  readinessHoursAgo: 31,
  topControl: { id: "PREVIEW-CTL-01", title: "Account lockout after failed logins", failing: 62, of: 62 },
  frameworks: [
    { name: "Financial Baseline", pass: 640, fail: 1010, unavailable: 625 },
    { name: "PCI-DSS v4.0.1", pass: 520, fail: 1040, unavailable: 600 },
    { name: "NIST SP 800-53", pass: 700, fail: 1140, unavailable: 455 },
    { name: "CIS Benchmark", pass: 749, fail: 1246, unavailable: 496 },
  ],
  pulse: [
    { label: "Configuration changed", hint: "since each device's previous read", value: "42 of 124 devices" },
    { label: "Cluster member differences", hint: "a review signal, not a fault", value: "3 of 45 clusters" },
    { label: "Policies installed today", hint: "as each gateway reports it", value: "1 of 122 gateways" },
  ],
} as const;

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
  { device: "FW-ROMEO-01-M2", setting: "ntp.server[2]", expected: "ntp-pri.example.test", effective: "ntp-alt.example.test", state: "Effective drift" },
  { device: "FW-JULIET-06-M1", setting: "log.syslog.target", expected: "syslog-a.example.test:514", effective: "removed", state: "Effective drift" },
  { device: "FW-ROMEO-01-M1", setting: "ha.monitor.interface", expected: "eth1", effective: "eth1", state: "Member-specific" },
  { device: "FW-OSCAR-04-M2", setting: "panorama.template", expected: "TPL-EDGE-V4", effective: "TPL-EDGE-V3", state: "Out of sync" },
  { device: "FW-BRAVO-02-M1", setting: "dns.resolver[1]", expected: "dns-pri.example.test", effective: "dns-pri.example.test", state: "Aligned" },
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
  { kind: "MDS", name: "FW-MIKE-08", detail: "Management server · 3 CMA · 24 gateways", liveness: "Live", note: "MDS" },
  { kind: "CP", name: "CLS-ROMEO-01", detail: "ClusterXL · FW-ROMEO-01-M1 · FW-ROMEO-01-M2", liveness: "Live", note: "2 members, 6 interfaces" },
  { kind: "CP", name: "FW-ROMEO-01-M1", detail: "Member · active · R81.20", liveness: "Live", note: "3 interfaces" },
  { kind: "VSX", name: "CLS-DELTA-03", detail: "VSX cluster · 12 virtual systems", liveness: "Live", note: "12 VS" },
  { kind: "VSX", name: "VS-DELTA-03-12", detail: "Virtual system · CLS-DELTA-03", liveness: "Live", note: "4 interfaces" },
  { kind: "CP", name: "CLS-BRAVO-02", detail: "ClusterXL · FW-BRAVO-02-M1 · FW-BRAVO-02-M2", liveness: "Stale", note: "6 d last live 09-02" },
  { kind: "PAN", name: "FW-OSCAR-04-M1", detail: "PA-3420 · PAN-OS 11.1.4 · FW-OSCAR-09", liveness: "Live", note: "5 interfaces" },
  { kind: "PAN", name: "FW-JULIET-06-M1", detail: "PA-1410 · PAN-OS 10.2.9 · FW-JULIET-09", liveness: "No live data", note: "management plane only" },
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
  { name: "FW-MIKE-08", detail: "Management · 3 CMA", state: "Aligned" },
  { name: "CLS-ROMEO-01", detail: "ClusterXL · 2 members", state: "Effective drift" },
  { name: "CLS-DELTA-03", detail: "VSX · 12 virtual systems", state: "Local override" },
  { name: "FW-OSCAR-04-M2", detail: "PA-3420 · FW-OSCAR-09", state: "Out of sync" },
  { name: "FW-ECHO-05", detail: "DR site · standalone", state: "Difference observed" },
];

export const CONFIG_SETTINGS: readonly AlignmentRow[] = [
  { device: "CLS-ROMEO-01", setting: "Cluster VIP", expected: "cluster-vip.example.test", effective: "cluster-vip.example.test", state: "Aligned" },
  { device: "CLS-ROMEO-01", setting: "Hostname", expected: "FW-ROMEO-01-M1 / FW-ROMEO-01-M2", effective: "FW-ROMEO-01-M1 · FW-ROMEO-01-M2", state: "Member-specific" },
  { device: "CLS-ROMEO-01", setting: "NTP · server 2", expected: "ntp-pri.example.test", effective: "ntp-alt.example.test", state: "Effective drift" },
  { device: "CLS-ROMEO-01", setting: "SNMP · trap receiver", expected: "snmp-a.example.test", effective: "snmp-b.example.test", state: "Local override" },
  { device: "CLS-ROMEO-01", setting: "Login banner", expected: "not in intent", effective: "Authorized use only", state: "Difference observed" },
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
  { control: "CIS 3.1.2 · time sync", device: "FW-ROMEO-01-M2", severity: "bad", note: "Effective drift on server 2" },
  { control: "CIS 2.4.1 · banner", device: "CLS-ROMEO-01", severity: "warn", note: "Unclassified difference" },
  { control: "CIS 4.2 · logging target", device: "FW-JULIET-06-M1", severity: "bad", note: "Syslog target removed" },
];

export interface OperationsJobRow {
  readonly job: string;
  readonly target: string;
  readonly status: "ok" | "warn" | "bad";
  readonly finished: string;
}

export const OPERATIONS_JOBS: readonly OperationsJobRow[] = [
  { job: "Collect evidence", target: "CLS-ROMEO-01", status: "ok", finished: "06:41 UTC" },
  { job: "Collect evidence", target: "FW-JULIET-06-M1", status: "bad", finished: "management plane only" },
  { job: "HA readiness assessment", target: "CLS-DELTA-03", status: "ok", finished: "06:58 UTC" },
  { job: "HA readiness assessment", target: "CLS-OSCAR-04", status: "warn", finished: "mismatch" },
];

export interface ReadinessRow {
  readonly cluster: string;
  readonly state: "ok" | "warn" | "unknown";
  readonly note: string;
}

export const OPERATIONS_READINESS: readonly ReadinessRow[] = [
  { cluster: "CLS-ROMEO-01", state: "ok", note: "Ready · sync OK" },
  { cluster: "CLS-DELTA-03", state: "ok", note: "Ready · 12 VS" },
  { cluster: "CLS-OSCAR-04", state: "warn", note: "Not ready · mismatch" },
  { cluster: "CLS-BRAVO-02", state: "unknown", note: "Undetermined" },
];

export interface AdminDeviceRow {
  readonly name: string;
  readonly vendor: string;
  readonly credentialProfile: string;
  readonly status: string;
}

export const ADMIN_DEVICES: readonly AdminDeviceRow[] = [
  { name: "FW-MIKE-08", vendor: "Check Point", credentialProfile: "profile-01", status: "Enrolled" },
  { name: "FW-ROMEO-01-M1", vendor: "Check Point", credentialProfile: "profile-01", status: "Enrolled" },
  { name: "FW-OSCAR-04-M1", vendor: "Palo Alto Networks", credentialProfile: "profile-02", status: "Enrolled" },
  { name: "FW-JULIET-06-M2", vendor: "Palo Alto Networks", credentialProfile: "profile-02", status: "Draft entry" },
];
