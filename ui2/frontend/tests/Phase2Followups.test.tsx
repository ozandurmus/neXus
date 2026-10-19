import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { ComplianceScreen, deviceScopeOf } from "../src/screens/ComplianceScreen";
import { clusterCompareFact, complianceFact } from "../src/screens/HeaderStatePills";
import { ClusterDetailPanels, DeviceInventoryPanels } from "../src/screens/InventoryPanels";
import { DeviceConfigurationDetail } from "../src/screens/ConfigurationDetail";
import { FleetSummary, fleetQuickFilters } from "../src/screens/FleetSummary";
import { InventoryScreen, formFromUrl, matchesForm } from "../src/screens/InventoryScreen";
import { HttpsCertificateCard, hasCertificateRecord } from "../src/screens/HttpsCertificatePanel";
import { JobLogsPanel, resultWords } from "../src/screens/JobLogsPanel";
import { OperationsScreen, tabFromUrl } from "../src/screens/OperationsScreen";
import { AdministrationScreen } from "../src/screens/AdministrationScreen";
import type { DeviceSummary, OverviewView } from "../src/auth/adminApi";

/**
 * Phase 2 follow-ups from the post-release review of 2026-10-18 (Fable, lane P4-B): the Compliance per-device filter the
 * device header's pill lands on, the cluster header's "Members compared" pill, the Fleet summary quick filters, the
 * addressable job detail from every entry point, the certificate block on a management server's Identity tab, and the
 * stored line count on log-collect RESULT cells. Masked fixtures only (FW-TANGO-04, 192.0.2.x); nothing here is real.
 */
const H = 3_600_000;
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const mount = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

const device = (over: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: "dev-1", hostname: "FW-TANGO-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", model: "Quantum",
  software_version: "R81.20", ha_role: null, cluster_member_ref: null, ip_addresses: "192.0.2.10", backup_target: true, ...over,
});
const item = (controlId: string, displayStatus: string, severity = "HIGH") => ({ controlId, title: "t", severity, frameworks: [], verdict: displayStatus, reasonCode: "", displayStatus });

// ------------------------------------------------------------------------------------------------------------------
// 1. Compliance per-device filter
// ------------------------------------------------------------------------------------------------------------------

describe("the device header's Compliance pill lands on that device's checks (item 1)", () => {
  it("links to ?device=<id> when the device has checks, and keeps the vendor critical-failures list when it has none", () => {
    const checked = complianceFact({ items: [item("c1", "FAIL", "CRITICAL"), item("c2", "PASS")] }, { configurationRead: true, vendor: "check_point", deviceId: "dev-1" })!;
    expect(checked.href).toBe("?screen=compliance&device=dev-1");
    expect(checked.label).toBe("Compliance 1 critical failing · 2 checked");
    const none = complianceFact({ items: [item("c1", "NOT_APPLICABLE")] }, { configurationRead: true, vendor: "check_point", deviceId: "dev-1" })!;
    expect(none.href).toBe("?screen=compliance&result=fail&severity=critical&vendor=check_point");
    expect(none.label).toBe("Compliance · Not assessed");
    // no device id: the vendor list, as before
    expect(complianceFact({ items: [item("c1", "PASS")] }, { configurationRead: true, vendor: "palo_alto" })!.href).toBe("?screen=compliance&result=fail&severity=critical&vendor=palo_alto");
  });

  it("reads one device's checks by control id, with NOT_APPLICABLE left out and any non-verdict as a check without evidence", () => {
    const scope = deviceScopeOf("dev-1", { hostname: "FW-TANGO-04", items: [item("c1", "FAIL"), item("c2", "PASS"), item("c3", "DATA_UNAVAILABLE"), item("c4", "NOT_APPLICABLE"), item("c5", "SOMETHING")] });
    expect(scope.name).toBe("FW-TANGO-04");
    expect([...scope.statuses.entries()]).toEqual([["c1", "FAIL"], ["c2", "PASS"], ["c3", "DATA_UNAVAILABLE"], ["c5", "DATA_UNAVAILABLE"]]);
    expect(deviceScopeOf("dev-1", { hostname: "", items: [] }).name).toBe("name UNKNOWN");
  });
});

const OVERVIEW = {
  total_firewalls: 105, evaluated_firewalls: 102, assured_compliance_pct: 28.9, evidence_coverage_pct: 82.5,
  observed_compliance_pct: 34.7, critical_deficiencies: 172, data_gaps: 428,
  frameworks: [{ framework: "CIS Benchmark", score_pct: 28.9, total_controls: 2448, pass_count: 708, fail_count: 1312, data_unavailable_count: 428 }],
};
const control = (id: string, status: "PASS" | "FAIL" | "DATA_UNAVAILABLE", counts: [number, number, number]) => ({
  control_id: id, title: `Control ${id}`, description: null, family: "AC", severity: "HIGH", category: "Authentication", frameworks: [{ framework: "CIS", reference: "2.1.1" }],
  status, compliance_pct: 40, target_device_count: counts[0] + counts[1] + counts[2], pass_count: counts[0], fail_count: counts[1], data_unavailable_count: counts[2],
  affected_devices: [], vendors: ["check_point"],
});
function stubCompliance() {
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    if (url.includes("/devices/d1/compliance")) return json({ device_id: "d1", hostname: "FW-TANGO-04", vendor: "check_point", items: [item("c1", "FAIL"), item("c2", "FAIL")] });
    if (url.includes("/devices/d9/compliance")) return json({ error: "NOT_FOUND" }, 404);
    if (url.includes("/compliance/overview")) return json(OVERVIEW);
    if (url.includes("/compliance/controls")) return json({ controls: [control("c1", "FAIL", [40, 22, 0]), control("c2", "PASS", [62, 0, 0]), control("c3", "DATA_UNAVAILABLE", [0, 0, 62])] });
    return json({}, 404);
  }));
}

describe("the Compliance screen with ?device= (item 1)", () => {
  it("shows a removable chip naming the device, lists its checks only, counts and words each outcome as the device's own", async () => {
    window.history.replaceState(null, "", "/?screen=compliance&device=d1");
    stubCompliance();
    mount(<ComplianceScreen />);
    const chip = await screen.findByTestId("device-filter-chip");
    await waitFor(() => expect(chip).toHaveTextContent("Device: FW-TANGO-04 · 2 checks"));
    expect(screen.getByText("Control c1")).toBeInTheDocument();
    expect(screen.getByText("Control c2")).toBeInTheDocument();
    expect(screen.queryByText("Control c3")).toBeNull();
    expect(screen.getByText("All · 2")).toBeInTheDocument();
    expect(screen.getByText("Failing · 2")).toBeInTheDocument();
    expect(screen.getByText("Passing · 0")).toBeInTheDocument();
    // c2 passes fleet-wide but fails on this device: the Outcome cell is the device's own word, its name on hover
    const row = screen.getByText("Control c2").closest("tr")!;
    expect(within(row).getByText("Non-Compliant").closest("[title]")).toHaveAttribute("title", "FW-TANGO-04: FAIL");
    expect(within(row).getAllByRole("cell")).toHaveLength(6);
    fireEvent.click(screen.getByRole("button", { name: "Passing · 0" }));
    expect(await screen.findByRole("status")).toHaveTextContent("No controls matching filter criteria.");
    // the chip's × removes the filter and the URL parameter; the fleet list is back
    fireEvent.click(screen.getByRole("img", { name: "Remove device filter" }));
    await waitFor(() => expect(screen.queryByTestId("device-filter-chip")).toBeNull());
    expect(new URLSearchParams(window.location.search).get("device")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "All · 3" }));
    expect(await screen.findByText("Control c3")).toBeInTheDocument();
  });

  it("writes UNKNOWN and shows no row when the device's checks could not be read, never the whole fleet as if filtered", async () => {
    window.history.replaceState(null, "", "/?screen=compliance&device=d9");
    stubCompliance();
    mount(<ComplianceScreen />);
    await waitFor(() => expect(screen.getByTestId("device-filter-chip")).toHaveTextContent("Device: UNKNOWN · its checks could not be read"));
    expect(screen.getByRole("status")).toHaveTextContent("The device's checks could not be read. Remove the device filter, or reload to try again.");
    expect(screen.queryByText("Control c1")).toBeNull();
  });
});

// ------------------------------------------------------------------------------------------------------------------
// 3. Members compared pill
// ------------------------------------------------------------------------------------------------------------------

const diffOverview = (over: Partial<OverviewView["exceptions"]["cluster_diff"]> = {}, unknown = 0): Pick<OverviewView, "exceptions" | "attention"> => ({
  attention: { failed_jobs_24h: { count: 0, state: "OK" }, stale_inventory: { count: 0, state: "OK" }, cluster_diff: { count: 1, of: 3, unknown, state: unknown === 3 ? "UNKNOWN" : "OK" },
    config_changed: { count: 0, state: "OK" }, backup_missing: { count: 0, state: "OK" } } as OverviewView["attention"],
  exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] }, config_changes: { total: 0, rows: [] },
    cluster_diff: { total: 2, unknown, all_refs: ["CLS-APOLLO-01", "CLS-ZULU-09"], rows: [{ cluster_ref: "CLS-APOLLO-01", diff_section_count: 2, diff_setting_count: 7, diff_sections: ["DNS", "NTP"], computed_at: "2026-10-18T03:00:00Z" }], ...over } } as OverviewView["exceptions"],
});

describe("the cluster header's Members compared pill (item 3)", () => {
  it("words the setting count for a listed cluster, 'differ' for one named without its count, 'no differences' only when every cluster was comparable", () => {
    const listed = clusterCompareFact(diffOverview(), "CLS-APOLLO-01")!;
    expect(listed).toMatchObject({ label: "Members compared · 7 settings differ", tone: "neutral", href: "?screen=inventory&cluster_ref=CLS-APOLLO-01&tab=configuration" });
    expect(listed.title).toMatch(/^2 sections: DNS · NTP · compared 2026-10-18/);
    expect(clusterCompareFact(diffOverview({ rows: [{ cluster_ref: "CLS-APOLLO-01", diff_section_count: 1, diff_setting_count: 1, diff_sections: ["DNS"], computed_at: "2026-10-18T03:00:00Z" }] }), "CLS-APOLLO-01")!.label).toBe("Members compared · 1 setting differs");
    expect(clusterCompareFact(diffOverview(), "CLS-ZULU-09")).toMatchObject({ label: "Members compared · differ" });
    expect(clusterCompareFact(diffOverview(), "CLS-ROMEO-01")).toMatchObject({ label: "Members compared · no differences" });
    // one cluster was not comparable: a cluster the Overview does not name may be that one, so nothing is claimed
    expect(clusterCompareFact(diffOverview({}, 1), "CLS-ROMEO-01")).toBeNull();
    expect(clusterCompareFact(null, "CLS-APOLLO-01")).toBeNull();
    expect(clusterCompareFact({ exceptions: {} as OverviewView["exceptions"], attention: {} as OverviewView["attention"] }, "CLS-APOLLO-01")).toBeNull();
  });

  it("shows the pill on the cluster header after Config changed, from the Overview read, linking to the members' configuration", async () => {
    const members: DeviceSummary[] = ["m-1", "m-2"].map((id, i) => device({ device_id: id, hostname: `FW-APOLLO-01-M${i + 1}`, cluster_member_ref: "CLS-APOLLO-01", ha_role: i ? "STANDBY" : "ACTIVE" }));
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url === "/clusters/CLS-APOLLO-01/inventory") return json({ cluster_member_ref: "CLS-APOLLO-01", members: members.map((m) => ({ device_id: m.device_id, hostname: m.hostname })), contexts: [], virtual_systems: [] });
      if (url === "/api/v2/overview") return json(diffOverview());
      if (url.endsWith("/backups")) return json({ backups: [] });
      if (url.endsWith("/compliance")) return json({ items: [] });
      if (url.endsWith("/configuration")) return json({ device_id: "m", collected_at: ago(2 * H), change_state: "unchanged", index: [], overrides: [], supplementary_runs: [], withheld_line_count: 0, sanitized_text_available: false, text_readable: false });
      if (url === "/api/v2/cp-failover/summary") return json([]);
      return json({ error: "NOT_FOUND" }, 404);
    }));
    mount(<ClusterDetailPanels clusterRef="CLS-APOLLO-01" members={members} configuration={<div>cfg</div>} />);
    const row = await screen.findByTestId("header-state-row");
    await waitFor(() => expect(Array.from(row.querySelectorAll("[data-fact]")).map((p) => p.textContent)).toEqual([
      "Compliance · Not assessed", "Config unchanged", "Members compared · 7 settings differ", "Backup M1 · no archive", "Backup M2 · no archive", "Readiness · Not read yet",
    ]));
    expect(within(row).getByRole("link", { name: "Members compared · 7 settings differ" })).toHaveAttribute("href", "?screen=inventory&cluster_ref=CLS-APOLLO-01&tab=configuration");
  });
});

// ------------------------------------------------------------------------------------------------------------------
// 4. Fleet summary quick filters
// ------------------------------------------------------------------------------------------------------------------

const FLEET = [
  device({ device_id: "d1", hostname: "FW-TANGO-04" }),
  device({ device_id: "d2", hostname: "FW-APOLLO-01-M1", cluster_member_ref: "CLS-APOLLO-01", ha_role: "ACTIVE" }),
  device({ device_id: "d3", hostname: "FW-APOLLO-01-M2", cluster_member_ref: "CLS-APOLLO-01", ha_role: "STANDBY" }),
  device({ device_id: "d4", hostname: "MGR-BRAVO-01", vendor_hint: "palo_alto", role: "management_server" }),
  device({ device_id: "d5", hostname: "DEV-ECHO-03", vendor_hint: "radware", role: "appliance" }),
];
const estate = (): Partial<OverviewView> => ({
  attention: { failed_jobs_24h: { count: 0, state: "OK" }, stale_inventory: { count: 0, state: "OK" }, cluster_diff: { count: 0, of: 1, unknown: 0, state: "OK" },
    config_changed: { count: 3, of: 5, state: "OK" }, backup_missing: { count: 0, state: "OK" } } as OverviewView["attention"],
  inventory_age: { lt24h: 2, h24_72: 1, gt72h: 1, never: 1, of: 5 },
});

describe("the Fleet summary quick filters (item 4)", () => {
  it("builds one chip row per dimension, each chip the filtered device list; UNKNOWN for the two Overview rows when it failed", () => {
    const groups = fleetQuickFilters(FLEET, estate() as OverviewView);
    expect(groups.map((g) => g.label)).toEqual(["Vendor", "Form", "Evidence age", "Changed since last read"]);
    expect(groups[0].chips).toEqual([
      { label: "Check Point · 3", href: "?screen=inventory&vendor=check_point" },
      { label: "Palo Alto · 1", href: "?screen=inventory&vendor=palo_alto" },
      { label: "Radware · 1", href: "?screen=inventory&vendor=radware" },
    ]);
    expect(groups[1].chips.map((c) => [c.label, c.href])).toEqual([
      ["1 cluster", "?screen=inventory&form=cluster"], ["1 standalone gateway", "?screen=inventory&form=standalone"],
      ["1 management server", "?screen=inventory&form=manager"], ["1 appliance", "?screen=inventory&form=appliance"],
    ]);
    expect(groups[2].chips.map((c) => [c.label, c.href])).toEqual([
      ["under 24 h · 2", "?screen=inventory&inventory_age=lt24h"], ["24–72 h · 1", "?screen=inventory&inventory_age=24h_72h"],
      ["over 72 h · 1", "?screen=inventory&inventory_age=gt72h"], ["never read · 1", "?screen=inventory&inventory_age=never"],
    ]);
    expect(groups[3].chips).toEqual([{ label: "Configuration changed · 3 of 5", href: "?screen=inventory&change_state=changed", title: expect.any(String) }]);
    const failed = fleetQuickFilters(FLEET, null);
    expect(failed[2]).toMatchObject({ chips: [], unknown: "UNKNOWN · the estate summary did not return the evidence ages" });
    expect(failed[3]).toMatchObject({ chips: [], unknown: "UNKNOWN · the configuration comparison was not read" });
    expect(fleetQuickFilters([], null).map((g) => g.label)).toEqual(["Evidence age", "Changed since last read"]);
  });

  it("renders the chips as links above the summary", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json({ error: "NOT_FOUND" }, 404)));
    mount(<FleetSummary countLabel="5 enrolled devices" fleet={FLEET} />);
    const filters = await screen.findByTestId("fleet-quick-filters");
    expect(within(within(filters).getByRole("group", { name: "Vendor" })).getByRole("link", { name: "Check Point · 3" })).toHaveAttribute("href", "?screen=inventory&vendor=check_point");
    expect(within(within(filters).getByRole("group", { name: "Form" })).getByRole("link", { name: "1 management server" })).toHaveAttribute("href", "?screen=inventory&form=manager");
    expect(within(filters).getByRole("group", { name: "Evidence age" })).toHaveTextContent("UNKNOWN");
  });

  it("opens the device list filtered by form from ?form=, with the forms as Scope options", async () => {
    expect(formFromUrl("manager", null)).toBe("manager");
    expect(formFromUrl("nonsense", "cluster")).toBe("cluster");
    expect(formFromUrl(null, null)).toBe("all");
    expect(FLEET.map((d) => matchesForm(d, "standalone"))).toEqual([true, false, false, false, false]);
    window.history.replaceState(null, "", "/?screen=inventory&form=manager");
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices" ? json({ devices: FLEET }) : json({ error: "NOT_FOUND" }, 404)));
    mount(<InventoryScreen />);
    expect(await screen.findByText("MGR-BRAVO-01")).toBeInTheDocument();
    expect(screen.queryByText("FW-TANGO-04")).toBeNull();
    expect(screen.queryByText("DEV-ECHO-03")).toBeNull();
    const scope = screen.getByRole("combobox", { name: "Scope" });
    expect(scope).toHaveValue("manager");
    expect(within(scope).getByRole("option", { name: "Standalone gateways · 1" })).toBeInTheDocument();
    expect(within(scope).getByRole("option", { name: "Appliances · 1" })).toBeInTheDocument();
    fireEvent.change(scope, { target: { value: "appliance" } });
    expect(await screen.findByText("DEV-ECHO-03")).toBeInTheDocument();
    expect(screen.queryByText("MGR-BRAVO-01")).toBeNull();
  });
});

// ------------------------------------------------------------------------------------------------------------------
// 2. Devices screen chrome while an object is open
// ------------------------------------------------------------------------------------------------------------------

describe("the Devices chrome while an object is open (item 2)", () => {
  it("folds the title, its count, the way back and the five fleet controls into one row, and restores the full header on the landing", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices" ? json({ devices: FLEET }) : url === "/devices/d1/inventory" ? json({ device_id: "d1", collected_at: ago(H), contexts: [] }) : json({ error: "NOT_FOUND" }, 404)));
    mount(<InventoryScreen />);
    fireEvent.click(await screen.findByText("FW-TANGO-04"));
    await screen.findByRole("tablist", { name: "Device detail" });
    const header = screen.getByTestId("compact-screen-header");
    expect(screen.queryByTestId("screen-header-title")).toBeNull();
    expect(within(header).getByRole("heading", { name: "Devices" })).toBeInTheDocument();
    expect(header).toHaveTextContent("5 enrolled devices");
    expect(within(header).getByRole("button", { name: "← Fleet summary" })).toBeInTheDocument();
    // nothing is lost: the five controls keep their names, order and gating behind Fleet actions
    fireEvent.click(within(header).getByRole("button", { name: "Fleet actions" }));
    const group = await screen.findByRole("group", { name: "Fleet actions" });
    expect(within(group).getAllByRole("button").map((b) => b.textContent)).toEqual(["Lifecycle", "Export inventory", "Bulk Collect", "Read configuration, all"]);
    expect(within(group).getByRole("link", { name: "Add device" })).toHaveAttribute("href", "?screen=administration");
    fireEvent.keyDown(group, { key: "Escape" });
    fireEvent.click(within(header).getByRole("button", { name: "← Fleet summary" }));
    expect(await screen.findByTestId("screen-header-title")).toBeInTheDocument();
    expect(screen.queryByTestId("compact-screen-header")).toBeNull();
    expect(screen.getByRole("button", { name: "Bulk Collect" })).toBeInTheDocument();
  });
});

// ------------------------------------------------------------------------------------------------------------------
// 5. Job detail as an addressable route, from every entry point
// ------------------------------------------------------------------------------------------------------------------

const JOBS = [
  { job_id: "job-logs", target_device_id: "opaque-001", device_name: "FW-TANGO-04", job_type: "cp_system_log_collect", state: "COMPLETED", outcome: "PARTIAL", submitted_at: "2026-10-18T03:05:00Z", has_transcript: false, lines_stored: 1234 },
  { job_id: "job-clean", target_device_id: "opaque-001", device_name: "FW-TANGO-04", job_type: "pan_system_log_collect", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: "SYSTEM_LOG_COLLECTED", submitted_at: "2026-10-18T03:04:00Z", has_transcript: false, lines_stored: 1 },
  { job_id: "job-norow", target_device_id: "opaque-001", device_name: "FW-TANGO-04", job_type: "cp_system_log_collect", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: "SYSTEM_LOG_COLLECTED", submitted_at: "2026-10-18T03:03:00Z", has_transcript: false, lines_stored: null },
  { job_id: "job-failed", target_device_id: "opaque-001", device_name: "FW-TANGO-04", job_type: "cp_gateway_backup", state: "FAILED", outcome: "FAILURE", terminal_reason: "COLLECTION_FAILED", submitted_at: "2026-10-18T02:00:00Z", has_transcript: false },
];
function stubJobs() {
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith("/api/v2/jobs/stats")) return json({ total: 4, total_24h: 4, completed_24h: 3, failed_24h: 1, running: 0, by_state: { COMPLETED: 3, FAILED: 1 } });
    if (url.startsWith("/api/v2/jobs/facets")) return json({ states: ["COMPLETED", "FAILED"], job_types: ["cp_system_log_collect"] });
    if (url.startsWith("/api/v2/jobs")) {
      const q = new URL(url, "http://x").searchParams.get("q");
      return json({ items: q ? JOBS.filter((j) => j.job_id.includes(q)) : JOBS, page: 1, page_size: 50, total: JOBS.length });
    }
    if (url === "/devices") return json({ devices: [device({ device_id: "opaque-001" })] });
    if (url === "/api/v2/cp-failover/summary") return json([]);
    return json({ error: "NOT_FOUND" }, 404);
  }));
}

describe("the addressable job detail (item 5)", () => {
  it("lands on Jobs when only ?job= is given, and keeps the tab slug when both are", () => {
    expect(tabFromUrl(null, "job-1")).toEqual({ tab: 1, pill: null });
    expect(tabFromUrl("ha", "job-1")).toEqual({ tab: 0, pill: null });
    expect(tabFromUrl("diagnostics", "job-1")).toEqual({ tab: 2, pill: null });
    expect(tabFromUrl(null, null)).toEqual({ tab: 0, pill: null });
  });

  it("Operations: ?job= opens the Jobs tab with that job open on load, Back closes it, Copy link is there", async () => {
    window.history.replaceState(null, "", "/?screen=operations&job=job-failed");
    stubJobs();
    mount(<OperationsScreen />);
    const row = await screen.findByRole("row", { expanded: true });
    expect(row).toHaveAttribute("data-job-id", "job-failed");
    expect(screen.getByRole("tab", { name: "Jobs", selected: true })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy link to this job" })).toBeInTheDocument();
    act(() => { window.history.replaceState(null, "", "/?screen=operations&tab=jobs"); window.dispatchEvent(new PopStateEvent("popstate")); });
    await waitFor(() => expect(screen.queryByRole("row", { expanded: true })).toBeNull());
  });

  it("Administration › Job Logs: ?job= lands on Job Logs with that job open, from a link naming no tab", async () => {
    window.history.replaceState(null, "", "/?screen=administration&job=job-logs");
    stubJobs();
    mount(<AdministrationScreen />);
    const row = await screen.findByRole("row", { expanded: true });
    expect(row).toHaveAttribute("data-job-id", "job-logs");
    expect(screen.getByText("Same records as Operations › Jobs.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy link to this job" })).toBeInTheDocument();
  });
});

// ------------------------------------------------------------------------------------------------------------------
// 6. Identity tab certificate block on a management server
// ------------------------------------------------------------------------------------------------------------------

describe("the certificate block on a management server (item 6)", () => {
  const pinned = { trust_entry_id: "t-1", status: "ACTIVE" as const, fingerprint_sha256: "a".repeat(64), subject_cn: "MGR-BRAVO-01", issuer_cn: "Synthetic CA", not_after: ago(-200 * 24 * H) };

  it("says No certificate on record when the manager has no HTTPS endpoint or no observed certificate, and shows the block when it has one", () => {
    expect(hasCertificateRecord({ available: false })).toBe(false);
    expect(hasCertificateRecord({ available: true, certificates: [] })).toBe(false);
    expect(hasCertificateRecord({ available: true, certificates: [pinned] })).toBe(true);
    const noEndpoint = mount(<HttpsCertificateCard deviceId="d4" data={{ available: false }} error={null} refresh={() => {}} manager />);
    expect(screen.getByRole("status")).toHaveTextContent("No certificate on record");
    expect(screen.getByText(/No HTTPS certificate is recorded for this management server/)).toBeInTheDocument();
    noEndpoint.unmount();
    const noneYet = mount(<HttpsCertificateCard deviceId="d4" data={{ available: true, certificates: [] }} error={null} refresh={() => {}} manager />);
    expect(screen.getByRole("status")).toHaveTextContent("No certificate on record");
    expect(screen.getByText(/no certificate has been observed and pinned for it yet/)).toBeInTheDocument();
    noneYet.unmount();
    mount(<HttpsCertificateCard deviceId="d4" data={{ available: true, strict: true, certificates: [pinned] }} error={null} refresh={() => {}} manager />);
    expect(screen.getByText("Subject CN: MGR-BRAVO-01")).toBeInTheDocument();
    expect(screen.queryByText("No certificate on record")).toBeNull();
  });

  it("keeps a gateway without an HTTPS endpoint as before: nothing is shown", () => {
    const { container } = mount(<HttpsCertificateCard deviceId="d1" data={{ available: false }} error={null} refresh={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows it on the manager's Identity tab", async () => {
    const manager = device({ device_id: "d4", hostname: "MGR-BRAVO-01", vendor_hint: "palo_alto", role: "management_server" });
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url === "/devices/d4/inventory") return json({ device_id: "d4", collected_at: ago(H), contexts: [] });
      if (url === "/devices/d4/https-certificate") return json({ available: false });
      if (url === "/devices/d4/backups") return json({ backups: [] });
      if (url === "/devices/d4/configuration") return json({ device_id: "d4", collected_at: ago(H), change_state: "unchanged", index: [], overrides: [], supplementary_runs: [], withheld_line_count: 0, sanitized_text_available: false, text_readable: false });
      return json({ error: "NOT_FOUND" }, 404);
    }));
    mount(<DeviceInventoryPanels device={manager} configuration={<DeviceConfigurationDetail device={manager} embedded />} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Identity" }));
    expect(await screen.findByRole("status")).toHaveTextContent("No certificate on record");
  });
});

// ------------------------------------------------------------------------------------------------------------------
// 7. RESULT line counts for log-collect jobs
// ------------------------------------------------------------------------------------------------------------------

describe("RESULT line counts for log-collect jobs (item 7)", () => {
  it("adds the stored line count to the outcome words, and nothing when the run has no chunk row (never 0)", () => {
    expect(resultWords({ state: "COMPLETED", outcome: "PARTIAL", lines_stored: 1234 })).toEqual({ outcome: "Partial", reason: null, lines: "1,234 lines" });
    expect(resultWords({ state: "COMPLETED", outcome: "SUCCESS", terminal_reason: "SYSTEM_LOG_COLLECTED", lines_stored: 1 })).toEqual({ outcome: "Collected", reason: null, lines: "1 line" });
    expect(resultWords({ state: "COMPLETED", outcome: "SUCCESS", terminal_reason: "SYSTEM_LOG_COLLECTED", lines_stored: null })).toEqual({ outcome: "Collected", reason: null });
    expect(resultWords({ state: "COMPLETED", outcome: "SUCCESS", terminal_reason: "SYSTEM_LOG_COLLECTED", lines_stored: 0 })).toEqual({ outcome: "Collected", reason: null, lines: "0 lines" });
    expect(resultWords({ state: "FAILED", outcome: "FAILURE", terminal_reason: "COLLECTION_FAILED" })).toEqual({ outcome: null, reason: "COLLECTION_FAILED" });
  });

  it("renders `Partial · 1,234 lines` in the RESULT cell and leaves the other rows as they were", async () => {
    stubJobs();
    mount(<JobLogsPanel />);
    const table = await screen.findByRole("table", { name: "Job logs" });
    const cells = within(table).getAllByRole("cell").filter((c) => c.hasAttribute("data-result")).map((c) => c.textContent);
    expect(cells).toEqual(["Partial · 1,234 lines", "Collected · 1 line", "Collected", "Collection failed"]);
  });
});
