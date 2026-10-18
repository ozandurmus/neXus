import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { OverviewScreen, haReadinessSentence, reportedVersions } from "../src/screens/OverviewScreen";
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
    return json(200, {});
  }));
}

describe("Overview HA readiness (UI review 2026-10-18, F-13)", () => {
  const fleet = [member("CLS-A", 1), member("CLS-A", 2), member("CLS-B", 1), member("CLS-B", 2), member("CLS-C", 1), member("CLS-C", 2),
    member("CLS-D", 1, "cisco_asa"), member("CLS-D", 2, "cisco_asa")];

  it("states how many clusters are ready, not ready and unknown, and links to Operations", async () => {
    stub(fleet, [unit("CLS-A", "READY", HOURS(1)), unit("CLS-B", "NOT_READY", HOURS(2)), unit("CLS-C", "UNKNOWN", HOURS(3))]);
    render(<OverviewScreen />);
    const link = await screen.findByLabelText("HA readiness: 1 ready of 4 clusters · 1 not ready · 1 unknown · 1 not supported");
    expect(link).toHaveAttribute("href", "?screen=operations");
    expect(within(link).getByText("HA readiness")).toBeInTheDocument();
    expect(within(link).getByText("of 4 clusters ready")).toBeInTheDocument();
    expect(within(link).getByText("1 ready of 4 clusters · 1 not ready · 1 unknown · 1 not supported")).toBeInTheDocument();
    expect(within(link).queryByText("Stale")).toBeNull();
  });

  it("names each sliver of the bar in-line and keeps the shortfall-only bar", async () => {
    stub(fleet, [unit("CLS-A", "READY", HOURS(1)), unit("CLS-B", "NOT_READY", HOURS(2)), unit("CLS-C", "UNKNOWN", HOURS(3))]);
    render(<OverviewScreen />);
    const link = await screen.findByLabelText(/^HA readiness: /);
    expect(within(link).getByText("not ready")).toBeInTheDocument();
    expect(within(link).getByText("unknown")).toBeInTheDocument();
    // BACKUPS and EVIDENCE name their slivers the same way
    expect(await screen.findByText("without an archive")).toBeInTheDocument();
    expect(screen.getByText("not read in 24 h")).toBeInTheDocument();
  });

  it("marks the row Stale when most evaluations are older than 24 h, and never changes the counts", async () => {
    stub(fleet, [unit("CLS-A", "READY", HOURS(80)), unit("CLS-B", "NOT_READY", HOURS(90)), unit("CLS-C", "UNKNOWN", HOURS(1))]);
    render(<OverviewScreen />);
    const link = await screen.findByLabelText("HA readiness: 1 ready of 4 clusters · 1 not ready · 1 unknown · 1 not supported");
    expect(within(link).getByText("Stale")).toBeInTheDocument();
  });

  it("writes UNKNOWN, never zero, when the readiness read fails", async () => {
    stub(fleet, { error: "x" }, 500);
    render(<OverviewScreen />);
    const link = await screen.findByLabelText("HA readiness: UNKNOWN");
    expect(within(link).getByText("UNKNOWN")).toBeInTheDocument();
    expect(link.textContent).not.toMatch(/\b0 ready/);
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

  it("shows the two vendors as rows next to Check Point and Palo Alto, with no outdated judgement", async () => {
    stub([dev("a", "fortinet", "7.2.8"), dev("b", "cisco_asa", "9.18(3)"), dev("c", "cisco_asa", null)], []);
    render(<OverviewScreen />);
    expect(await screen.findByText("Fortinet · Version as reported")).toBeInTheDocument();
    expect(screen.getByText("Cisco ASA · Version as reported")).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText("Unknown").length).toBeGreaterThan(0));
    expect(document.body.textContent).not.toMatch(/outdated|behind|newest|end of life/i);
  });

  it("draws no row for a vendor with no devices", async () => {
    stub([dev("a", "fortinet", "7.2.8")], []);
    render(<OverviewScreen />);
    expect(await screen.findByText("Fortinet · Version as reported")).toBeInTheDocument();
    expect(screen.queryByText("Cisco ASA · Version as reported")).toBeNull();
  });
});
