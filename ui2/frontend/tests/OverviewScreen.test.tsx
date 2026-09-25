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

describe("Overview -- executive summary (PO 2026-09-25: five facts)", () => {
  it("shows recoverability, changes and cluster consistency as plain facts with one link each", async () => {
    stubWithControls(overview());
    render(<OverviewScreen />);
    expect(await screen.findByText("Recoverability")).toBeInTheDocument();
    expect(screen.getByText(/19 have none yet/)).toBeInTheDocument();
    expect(screen.getByText("Devices without a backup →").getAttribute("href")).toBe("?screen=backups&artefact=none");
    expect(screen.getByText("83")).toBeInTheDocument(); // 102 targets - 19 without
    expect(screen.getByText(/clusters whose two members carry different settings/)).toBeInTheDocument();
    expect(screen.getByText("CLS-ROMEO-01").getAttribute("href")).toBe("?screen=configuration&cluster_ref=CLS-ROMEO-01");
  });

  it("keeps job failures and connection errors off the executive summary", async () => {
    stubWithControls(overview());
    render(<OverviewScreen />);
    await screen.findByText("Recoverability");
    expect(document.body.textContent).not.toContain("connect_failed");
    expect(document.body.textContent).not.toMatch(/jobs failed/);
  });

  it("writes UNKNOWN, never 0, for what is not evidenced", async () => {
    stubWithControls(overview({ attention: { ...overview().attention, backup_missing: { count: 0, of: 0, state: "UNKNOWN" } } }));
    render(<OverviewScreen />);
    expect(await screen.findByText(/Compliance has not been evaluated yet/)).toBeInTheDocument();
    expect(screen.getByText(/Backup coverage has not been read/)).toBeInTheDocument();
  });

  it("names the most failed critical checks under compliance", async () => {
    stubWithControls(overview({ compliance: { state: "OK", reason: null, evaluated: 102, of_firewalls: 105, observed_pct: 34.7, assured_pct: 28.9,
      coverage_pct: 82.5, critical_deficiencies: 172, data_gaps: 428, frameworks: [] } }), [
      { control_id: "c1", title: "Enforce SSH Protocol Version 2 Only", severity: "CRITICAL", fail_count: 62, status: "FAIL" },
      { control_id: "c2", title: "Enforce Minimum Password Length", severity: "HIGH", fail_count: 32, status: "FAIL" },
      { control_id: "c3", title: "Telnet disabled", severity: "CRITICAL", fail_count: 0, status: "PASS" },
    ]);
    render(<OverviewScreen />);
    expect(await screen.findByText("Enforce SSH Protocol Version 2 Only")).toBeInTheDocument();
    expect(screen.getByText("28.9%")).toBeInTheDocument();
    expect(screen.getByText(/172 critical findings are open/)).toBeInTheDocument();
    expect(screen.queryByText("Telnet disabled")).toBeNull();
  });

  it("counts devices behind the newest build in each vendor's own terms", async () => {
    stubWithControls(overview({ platform: { ...overview().platform, versions: {
      check_point: { major: [{ label: "R81.20", count: 36 }], minor: [{ label: "R81.20 Jumbo Take 119", count: 30 }, { label: "R81.20 Jumbo Take 161", count: 6 }] },
      palo_alto: { major: [{ label: "11.1", count: 40 }], minor: [{ label: "11.1.10-h7", count: 21 }, { label: "11.1.10-h4", count: 19 }] },
    } } }));
    render(<OverviewScreen />);
    expect(await screen.findByText("Versions and patches")).toBeInTheDocument();
    expect(screen.getByText("Check Point 30 on R81.20 below Take 161")).toBeInTheDocument();
    expect(screen.getByText("Palo Alto 19 on 11.1 below 11.1.10-h7")).toBeInTheDocument();
  });

  it("leaves out the versions fact when no vendor has build facts", async () => {
    stubWithControls(overview());
    render(<OverviewScreen />);
    await screen.findByText("Recoverability");
    expect(screen.queryByText("Versions and patches")).toBeNull();
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
