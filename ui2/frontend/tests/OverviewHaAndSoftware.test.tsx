import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { EstateDetails, OverviewScreen, haReadinessSentence, reportedVersions } from "../src/screens/OverviewScreen";
import type { DeviceSummary, OverviewView } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());

const NOW = new Date().toISOString();
const HOURS = (h: number) => new Date(Date.now() - h * 3600_000).toISOString();

function overview(): OverviewView {
  return {
    generated_at: NOW, masked: true,
    denominators: { active_devices: 105, gateways: 103, clusters: 39, backup_targets: 102 },
    evidence: { inventory: { at: NOW, state: "OK" }, configuration: { at: NOW, state: "OK" }, compliance: { at: null, state: "UNKNOWN" },
      backup: { at: NOW, state: "OK" }, jobs: { at: NOW, state: "OK" }, platform_facts: { at: NOW, state: "OK" } },
    attention: {
      failed_jobs_24h: { count: 0, terminal_24h: 10, state: "OK" }, stale_inventory: { count: 0, of: 105, state: "OK" },
      cluster_diff: { count: 0, of: 31, unknown: 0, state: "OK" }, config_changed: { count: 0, of: 103, state: "OK" },
      backup_missing: { count: 7, of: 102, state: "OK" },
    },
    exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] }, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } },
    inventory_age: { lt24h: 98, h24_72: 3, gt72h: 1, never: 3, of: 105 },
    compliance: { state: "UNKNOWN", reason: "not_evaluated" },
    platform: { devices: 105, check_point: 65, palo_alto: 40, clusters: 39, hotfix_levels: [], evidence_at: NOW },
    nexus: { completed_24h: 5, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: NOW, palo_alto: NOW } },
    estate: {
      devices: [{ device_id: "d1", hostname: "FW-TANGO-01", vendor: "check_point", model: "Model A", condition: "clear", no_archive: true, critical_fail: 0, last_read_at: NOW }],
      counts: { critical: 0, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: 1 }, no_archive: 1, compliance_state: "OK",
      facts: { critical: { k: 0, n: 2 }, backups: { a: 95, b: 102 }, evidence: { r: 98, d: 105, never: 3 } },
    },
  } as OverviewView;
}

const member = (ref: string, n: number, vendor = "check_point"): DeviceSummary => ({
  device_id: `${ref}-${n}`, vendor_hint: vendor, enrollment_state: "ENROLLED", hostname: `FW-${ref}-M${n}`, model: null, software_version: null,
  ha_role: n === 1 ? "active" : "standby", cluster_member_ref: ref,
});
const unit = (ref: string, status: "READY" | "NOT_READY" | "UNKNOWN", observedAt: string) => ({
  clusterId: ref, unitId: ref, cluster_member_ref: ref, vendor: "check_point", masked: true, activeWindow: false, lastRunState: null, lastRunOutcome: null,
  lastRunAt: null, canRunReadiness: true, readiness: { status, observedAt, failedCheck: "", checks: [] },
});

function stub(devices: DeviceSummary[], summary: unknown, summaryStatus = 200) {
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    const json = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status }));
    if (url === "/api/v2/overview") return json(200, overview());
    if (url.includes("/compliance/controls")) return json(200, { total_controls: 0, controls: [] });
    if (url === "/devices") return json(200, { devices });
    if (url === "/api/v2/cp-failover/summary") return json(summaryStatus, summary);
    if (url.startsWith("/api/v2/lifecycle")) return json(200, { as_of: NOW, devices: [], summary: {} });
    if (url.startsWith("/api/v2/changes/feed")) return json(200, { events: [] });
    return json(200, {});
  }));
}

describe("Overview HA readiness (UI review 2026-10-18, F-13)", () => {
  const fleet = [member("CLS-A", 1), member("CLS-A", 2), member("CLS-B", 1), member("CLS-B", 2), member("CLS-C", 1), member("CLS-C", 2),
    member("CLS-D", 1, "cisco_asa"), member("CLS-D", 2, "cisco_asa")];

  it("counts ready clusters in the fleet summary, linking to Operations", async () => {
    stub(fleet, [unit("CLS-A", "READY", HOURS(1)), unit("CLS-B", "NOT_READY", HOURS(2)), unit("CLS-C", "UNKNOWN", HOURS(3))]);
    render(<OverviewScreen />);
    const fact = await screen.findByText("1 of 4");
    expect(fact.closest("[data-fact]")).toHaveAttribute("href", "?screen=operations");
    expect(fact.closest("[data-fact]")?.textContent).toBe("1 of 4clusters ready for failover");
  });

  it("names the not-ready cluster as an Act now row and the unknown one as a Watch row, with the evaluation age", async () => {
    stub(fleet, [unit("CLS-A", "READY", HOURS(1)), unit("CLS-B", "NOT_READY", HOURS(2)), unit("CLS-C", "UNKNOWN", HOURS(3))]);
    render(<OverviewScreen />);
    const notReady = (await screen.findByText("1 HA cluster not ready for failover")).closest("[data-attention]") as HTMLElement;
    expect(within(notReady).getByText("CLS-B")).toBeInTheDocument();
    expect(within(notReady).getByText("Act now")).toBeInTheDocument();
    expect(within(notReady).getByText("checked 2 h ago")).toBeInTheDocument();
    expect(within(notReady).getByRole("link")).toHaveAttribute("href", "?screen=operations&readiness=NOT_READY");
    const unknown = screen.getByText("1 HA cluster has UNKNOWN readiness").closest("[data-attention]") as HTMLElement;
    expect(within(unknown).getByText("CLS-C")).toBeInTheDocument();
    expect(within(unknown).getByText("Watch")).toBeInTheDocument();
    // a cluster whose vendor has no readiness (Cisco ASA) is not a finding
    expect(screen.queryByText(/CLS-D/)).toBeNull();
  });

  it("writes UNKNOWN, never zero, when the readiness read fails", async () => {
    stub(fleet, { error: "x" }, 500);
    render(<OverviewScreen />);
    const row = (await screen.findByText("HA readiness could not be read")).closest("[data-attention]") as HTMLElement;
    expect(row.getAttribute("data-attention")).toBe("unknown-failover");
    const fact = document.querySelector('[data-fact="ha"]') as HTMLElement;
    expect(fact.textContent).toBe("UNKNOWNclusters ready for failover");
  });

  it("shows nothing about HA, not even UNKNOWN, when the server refuses the readiness read for this role", async () => {
    stub(fleet, { error: "ACTION_REFUSED" }, 403);
    render(<OverviewScreen />);
    await screen.findByTestId("attention-list");
    await screen.findByText(/backup target has no stored backup/);
    expect(screen.queryByText("HA readiness could not be read")).toBeNull();
  });

  it("builds the sentence from parts that add up", () => {
    expect(haReadinessSentence({ total: 45, ready: 20, notReady: 4, unknown: 16, unsupported: 5 })).toBe("20 ready of 45 clusters · 4 not ready · 16 unknown · 5 not supported");
    expect(haReadinessSentence({ total: 45, ready: 20, notReady: 4, unknown: 21, unsupported: 0 })).toBe("20 ready of 45 clusters · 4 not ready · 21 unknown");
  });
});

describe("Overview Software in service: Fortinet and Cisco ASA", () => {
  const dev = (id: string, vendor: string, version: string | null, extra: Partial<DeviceSummary> = {}): DeviceSummary => ({
    device_id: id, vendor_hint: vendor, enrollment_state: "ENROLLED", hostname: `FW-${id}`, model: null, software_version: version, ha_role: null, cluster_member_ref: null, ...extra,
  });

  it("groups the versions the devices report, counts a missing version as Unknown and leaves out managers and drafts", () => {
    const rows = reportedVersions([
      dev("a", "fortinet", "7.2.8"), dev("b", "fortinet", "7.2.8"), dev("c", "fortinet", "7.4.3"), dev("d", "fortinet", null),
      dev("e", "fortinet", "7.0.0", { role: "management_server" }), dev("f", "fortinet", "6.0.0", { enrollment_state: "DRAFT" }),
      dev("g", "cisco_asa", "9.18(3)"),
    ], "fortinet");
    expect(rows).toContainEqual({ label: "7.2.8", count: 2 });
    expect(rows).toContainEqual({ label: "7.4.3", count: 1 });
    expect(rows).toContainEqual({ label: null, count: 1 });
    expect(rows.reduce((n, r) => n + r.count, 0)).toBe(4);
  });

  it("shows the vendors as rows next to Check Point and Palo Alto on the Fleet summary, with no outdated judgement (3.5 / 1.5)", async () => {
    render(<EstateDetails data={overview()} onDevicesScreen fleet={[dev("a", "fortinet", "7.2.8"), dev("b", "cisco_asa", "9.18(3)"), dev("c", "cisco_asa", null)]} />);
    const card = (await screen.findByText("Fortinet · 1 device")).closest("[data-reported-versions]") as HTMLElement;
    expect(within(card).getByText("Cisco ASA · 2 devices")).toBeInTheDocument();
    await waitFor(() => expect(within(card).getAllByText("Unknown").length).toBeGreaterThan(0));
    expect(card.textContent).not.toMatch(/outdated|behind|newest|end of life/i);
  });

  it("draws no row for a vendor with no devices, and the Overview itself carries no version charts or links", async () => {
    const { unmount } = render(<EstateDetails data={overview()} onDevicesScreen fleet={[dev("a", "fortinet", "7.2.8")]} />);
    expect(await screen.findByText("Fortinet · 1 device")).toBeInTheDocument();
    expect(screen.queryByText(/^Cisco ASA/)).toBeNull();
    unmount();
    stub([dev("a", "fortinet", "7.2.8")], []);
    render(<OverviewScreen />);
    await screen.findByTestId("fleet-summary");
    expect(screen.queryByText("Software in service")).toBeNull();
    expect(screen.queryByText(/^Fortinet ·/)).toBeNull();
  });
});
