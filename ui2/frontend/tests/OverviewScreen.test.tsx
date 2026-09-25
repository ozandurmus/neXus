import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { OverviewScreen, relativeAge } from "../src/screens/OverviewScreen";
import type { OverviewView } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());

const NOW = new Date().toISOString();

function overview(partial: Partial<OverviewView> = {}): OverviewView {
  return {
    generated_at: NOW,
    masked: true,
    denominators: { active_devices: 105, gateways: 103, clusters: 39, backup_targets: 102 },
    evidence: {
      inventory: { at: NOW, state: "OK" }, configuration: { at: NOW, state: "OK" }, compliance: { at: null, state: "UNKNOWN" },
      backup: { at: NOW, state: "OK" }, jobs: { at: NOW, state: "OK" }, platform_facts: { at: NOW, state: "OK" },
    },
    attention: {
      failed_jobs_24h: { count: 7, terminal_24h: 212, state: "OK" },
      stale_inventory: { count: 0, of: 105, state: "OK" },
      cluster_diff: { count: 3, of: 31, unknown: 8, state: "OK" },
      config_changed: { count: 5, of: 103, state: "OK" },
      backup_missing: { count: 19, of: 102, state: "OK" },
    },
    exceptions: {
      failed_jobs: {
        total: 7,
        rows: Array.from({ length: 5 }, (_, i) => ({ job_id: `job-${i}`, job_type: "cp_inventory_collect", device_id: `dev-${i}`, label: `FW-TANGO-0${i}`, terminal_reason: "connect_failed", finished_at: NOW })),
        reasons: [
          { reason: "connect_failed: palo alto key generation did not return a usable key", count: 5, devices: 3, job_types: ["pan_inventory_collect"], last_at: NOW },
          { reason: "unhandled_exception: Java heap space", count: 2, devices: 2, job_types: ["cp_gateway_backup", "pan_configuration_collect"], last_at: NOW },
        ],
      },
      config_changes: { total: 0, rows: [] },
      cluster_diff: { total: 3, unknown: 8, all_refs: ["CLS-ROMEO-01"], rows: [{ cluster_ref: "CLS-ROMEO-01", diff_section_count: 2, diff_setting_count: 9, diff_sections: ["Interfaces", "AAA"], computed_at: NOW }] },
    },
    inventory_age: { lt24h: 98, h24_72: 3, gt72h: 1, never: 3, of: 105 },
    compliance: { state: "UNKNOWN", reason: "not_evaluated" },
    platform: { devices: 105, check_point: 65, palo_alto: 40, clusters: 39, hotfix_levels: [{ level: "R81.20 Jumbo Take 119", count: 30 }, { level: null, count: 9 }], evidence_at: NOW },
    nexus: { completed_24h: 205, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: NOW, palo_alto: NOW } },
    ...partial,
  };
}

function stub(body: OverviewView) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 })));
}

function stubWithControls(body: OverviewView, controls: unknown[] = []) {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
    const payload = url.includes("/compliance/controls") ? { total_controls: controls.length, controls } : body;
    return Promise.resolve(new Response(JSON.stringify(payload), { status: 200 }));
  }));
}

const ESTATE = {
  devices: [
    { device_id: "d1", hostname: "FW-TANGO-01", vendor: "check_point", model: "Check Point 28000", condition: "critical", no_archive: false, critical_fail: 3, last_read_at: NOW },
    { device_id: "d2", hostname: "FW-TANGO-02", vendor: "check_point", model: "Check Point 28000", condition: "clear", no_archive: true, critical_fail: 0, last_read_at: NOW },
    { device_id: "d3", hostname: "FW-ROMEO-01", vendor: "palo_alto", model: "PA-5440", condition: "ageing", no_archive: false, critical_fail: null, last_read_at: null },
  ],
  counts: { critical: 1, ageing: 1, partly_assessed: 0, not_assessed: 0, clear: 1 },
  no_archive: 1,
  compliance_state: "OK",
  facts: { critical: { k: 1, n: 2 }, backups: { a: 83, b: 102 }, evidence: { r: 98, d: 105, never: 3 } },
};

describe("Overview -- executive (EXEC_OVERVIEW_DESIGN_2026_09_25_FABLE.md)", () => {
  it("draws every device on the estate map, grouped by vendor, each opening its device", async () => {
    stubWithControls(overview({ estate: ESTATE } as Partial<OverviewView>));
    render(<OverviewScreen />);
    expect(await screen.findByText("Estate map")).toBeInTheDocument();
    expect(screen.getByLabelText("FW-TANGO-01: Critical finding").getAttribute("href")).toBe("?screen=inventory&device_id=d1");
    expect(screen.getByLabelText("FW-ROMEO-01: Evidence ageing")).toBeInTheDocument();
    expect(screen.getByText("No archive")).toBeInTheDocument();
  });

  it("states the three facts over their own populations", async () => {
    stubWithControls(overview({ estate: ESTATE } as Partial<OverviewView>));
    render(<OverviewScreen />);
    expect(await screen.findByText("Critical failures")).toBeInTheDocument();
    expect(screen.getByText("of 2 firewalls")).toBeInTheDocument();
    expect(screen.getByText("19 backup targets have no stored archive")).toBeInTheDocument();
    expect(screen.getByText("98 of 105 active devices read in the last 24 h · 3 never read")).toBeInTheDocument();
  });

  it("keeps job failures and connection errors off the executive summary", async () => {
    stubWithControls(overview({ estate: ESTATE } as Partial<OverviewView>));
    render(<OverviewScreen />);
    await screen.findByText("Estate map");
    expect(document.body.textContent).not.toContain("connect_failed");
    expect(document.body.textContent).not.toMatch(/jobs failed/);
  });

  it("ranks critical failures per vendor, never merging two vendors' checks by title", async () => {
    stubWithControls(overview({ estate: ESTATE, compliance: { state: "OK", reason: null, evaluated: 102, of_firewalls: 105, observed_pct: 34.7,
      assured_pct: 28.9, coverage_pct: 82.5, critical_deficiencies: 172, data_gaps: 428,
      frameworks: [{ name: "CIS Benchmark", pass: 708, fail: 1312, unavailable: 404, total: 2424, score_pct: 29.2 }] } } as Partial<OverviewView>), [
      { control_id: "cp1", title: "Account lockout", severity: "CRITICAL", fail_count: 62, target_device_count: 62, data_unavailable_count: 0, vendors: ["check_point"], status: "FAIL" },
      { control_id: "pan1", title: "Account lockout", severity: "CRITICAL", fail_count: 39, target_device_count: 39, data_unavailable_count: 0, vendors: ["palo_alto"], status: "FAIL" },
      { control_id: "h1", title: "High only", severity: "HIGH", fail_count: 80, target_device_count: 80, data_unavailable_count: 0, vendors: ["check_point"], status: "FAIL" },
    ]);
    render(<OverviewScreen />);
    expect(await screen.findAllByText("Account lockout")).toHaveLength(2);
    expect(screen.getByText("CP")).toBeInTheDocument();
    expect(screen.getByText("PAN")).toBeInTheDocument();
    expect(screen.queryByText("High only")).toBeNull();
    expect(screen.getByText("29.2%")).toBeInTheDocument();
    expect(screen.getByText("Technical checks. Not an audit certification.")).toBeInTheDocument();
  });

  it("shows software in each vendor's own terms without 'behind' language", async () => {
    stubWithControls(overview({ estate: ESTATE, platform: { ...overview().platform, versions: {
      check_point: { major: [{ label: "R81.20", count: 36 }], minor: [{ label: "R81.20 Jumbo Take 119", count: 30 }, { label: null, count: 6 }] },
      palo_alto: { major: [{ label: "11.1", count: 40 }], minor: [{ label: "11.1.10-h7", count: 21 }] },
    } } } as Partial<OverviewView>));
    render(<OverviewScreen />);
    expect(await screen.findByText("Check Point · Jumbo Hotfix take")).toBeInTheDocument();
    expect(screen.getByText("Palo Alto Networks · PAN-OS version")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/behind|on newest/i);
  });

  it("says the read failed instead of rendering figures when the endpoint fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{}", { status: 500 })));
    render(<OverviewScreen />);
    expect(await screen.findByText(/Overview unavailable/)).toBeInTheDocument();
  });

  it("ages evidence in plain words", () => {
    const now = new Date("2026-09-23T12:00:00Z");
    expect(relativeAge("2026-09-23T11:30:00Z", now)).toBe("30 min ago");
    expect(relativeAge("2026-09-22T12:00:00Z", now)).toBe("24 h ago");
    expect(relativeAge(null, now)).toBe("UNKNOWN");
  });
});

describe("display time zone", () => {
  it("shows Turkey time (GMT+3) and converts a UTC cron hour for the label", async () => {
    const { formatUtc, utcHourToLocal } = await import("../src/shell/time");
    expect(formatUtc("2026-09-22T22:57:54Z")).toBe("2026-09-23 01:57:54");
    expect(utcHourToLocal(2)).toBe("05:00 GMT+3");
  });
});
