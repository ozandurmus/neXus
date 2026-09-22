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

describe("Overview -- exception-and-evidence screen (OVERVIEW_EXCEPTION_SCREEN_CONTRACT)", () => {
  it("shows six posture tiles with denominators, status words, deltas and filtered links", async () => {
    stub(overview({ attention: { ...overview().attention, failed_jobs_24h: { count: 7, terminal_24h: 212, previous: 4, state: "OK" } } }));
    render(<OverviewScreen />);
    expect((await screen.findAllByText("Act now")).length).toBeGreaterThan(1);
    const backup = screen.getByText("Backup targets without archive").closest("a")!;
    expect(backup.getAttribute("href")).toBe("?screen=backups&artefact=none");
    expect(backup.textContent).toContain("19");
    expect(backup.textContent).toContain("of 102");
    const failedTile = screen.getByText("Failed jobs, 24 h").closest("a")!;
    expect(failedTile.getAttribute("href")).toBe("?screen=operations&tab=jobs&state=FAILED&since_hours=24");
    expect(failedTile.textContent).toContain("+3 since yesterday");
    expect(screen.getByText("Clusters with member differences").closest("a")!.textContent).toContain("8 not comparable");
    // zero is neutral: the stale-inventory tile says Clear, not a status colour word
    expect(screen.getByText("Devices without evidence, 24 h").closest("a")!.textContent).toContain("Clear");
    // no stored history for configuration changes
    expect(screen.getByText("Configuration changed").closest("a")!.textContent).toContain("no history");
  });

  it("writes the headline as three lines: Act now, Review, Evidence", async () => {
    stub(overview());
    render(<OverviewScreen />);
    expect(await screen.findByText(/7 of 212 jobs failed in 24 h/)).toBeInTheDocument();
    expect(screen.getByText(/19 of 102 backup targets have no archive/)).toBeInTheDocument();
    expect(screen.getAllByText("Review").length).toBeGreaterThan(1);
    expect(screen.getByText(/3 of 31 active clusters show member differences/)).toBeInTheDocument();
    expect(screen.getByText("Evidence")).toBeInTheDocument();
  });

  it("writes UNKNOWN, never 0, for what is not evidenced", async () => {
    stub(overview({ attention: { ...overview().attention, stale_inventory: { count: 0, of: 0, state: "UNKNOWN" } } }));
    render(<OverviewScreen />);
    expect(await screen.findByText(/Compliance has not been evaluated yet/)).toBeInTheDocument();
    expect(screen.getByText("Devices without evidence, 24 h").closest("a")!.textContent).toContain("UNKNOWN");
    expect(screen.getByText("Compliance deficiencies").closest("a")!.textContent).toContain("UNKNOWN");
    expect(screen.getByText(/1 UNKNOWN/)).toBeInTheDocument();
  });

  it("groups failed jobs by cause, shows the three latest and links to the whole list", async () => {
    stub(overview());
    render(<OverviewScreen />);
    expect(await screen.findByText("Show all · 7")).toBeInTheDocument();
    const cause = screen.getByText("connect_failed: palo alto key generation did not return a usable key").closest("a")!;
    expect(cause.textContent).toContain("3 devices");
    expect(cause.getAttribute("href")).toBe("?screen=operations&tab=jobs&state=FAILED&since_hours=24&q=connect_failed%3A+palo+alto+key+generation+did+not+return+a+usable+key");
    expect(screen.getByText("unhandled_exception: Java heap space").closest("a")!.textContent).toContain("cp_gateway_backup, pan_configuration_collect");
    expect(screen.getAllByText("connect_failed")).toHaveLength(3);
    expect(screen.getByText("No configuration change in the latest collections.")).toBeInTheDocument();
  });

  it("shows major, minor and hardware donuts per vendor, each slice linked to the filtered device list", async () => {
    stub(overview({ platform: { ...overview().platform, versions: {
      check_point: { major: [{ label: "R81.20", count: 55 }, { label: null, count: 1 }], minor: [{ label: "R81.20 Jumbo Take 119", count: 30 }],
        model: [{ label: "Check Point 29200 (RH-20-00)", count: 17 }, { label: null, count: 7 }] },
      palo_alto: { major: [{ label: "11.1", count: 40 }], minor: [{ label: "11.1.10-h7", count: 21 }], model: [{ label: "PA-5445", count: 10 }] },
    } } }));
    render(<OverviewScreen />);
    const appliance = (await screen.findByText("29200 (RH-20-00)")).closest("a")!;
    expect(appliance.getAttribute("href")).toBe("?screen=inventory&vendor=check_point&hw_model=Check+Point+29200+%28RH-20-00%29");
    expect(screen.getByText("R81.20 · Take 119").closest("a")!.getAttribute("href")).toBe("?screen=inventory&vendor=check_point&hotfix_level=R81.20+Jumbo+Take+119");
    expect(screen.getByText("PA-5445").closest("a")!.getAttribute("href")).toBe("?screen=inventory&vendor=palo_alto&hw_model=PA-5445");
    expect(screen.getByText("APPLIANCE")).toBeInTheDocument();
  });

  it("shows compliance evidence coverage as the stored one-decimal percentage", async () => {
    stub(overview({ compliance: { state: "OK", reason: null, evaluated: 102, of_firewalls: 105, observed_pct: 34.7, assured_pct: 28.9, coverage_pct: 82.5,
      critical_deficiencies: 172, data_gaps: 428, frameworks: [] } }));
    render(<OverviewScreen />);
    expect(await screen.findByText(/covers 82.5% of control checks: 172 critical deficiencies, 428 data gaps/)).toBeInTheDocument();
    expect(screen.getByText("Compliance deficiencies").closest("a")!.textContent).toContain("82.5% coverage");
    expect(document.body.textContent).not.toContain("of 100");
  });

  it("keeps an UNKNOWN row in the hotfix histogram and never calls a level outdated", async () => {
    stub(overview());
    render(<OverviewScreen />);
    expect(await screen.findByText("R81.20 · Take 119")).toBeInTheDocument();
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    expect(document.body.textContent).not.toMatch(/outdated/i);
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
