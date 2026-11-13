import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { OverviewScreen, estateDevicesLabel, relativeAge } from "../src/screens/OverviewScreen";
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

function stubWithControls(body: OverviewView, controls: unknown[] = [], extra: { lifecycle?: unknown; changes?: unknown; devices?: unknown; failover?: unknown } = {}) {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
    const payload = url.includes("/compliance/controls") ? { total_controls: controls.length, controls }
      : url === "/devices" ? (extra.devices ?? { devices: [] })
      : url.includes("/cp-failover/summary") ? (extra.failover ?? [])
      : url.startsWith("/api/v2/lifecycle") ? (extra.lifecycle ?? { as_of: NOW, devices: [], summary: {} })
      : url.startsWith("/api/v2/changes/feed") ? (extra.changes ?? { events: [] })
      : body;
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

describe("Overview -- what to look at today (OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md)", () => {
  it("puts the attention list first, naming masked devices, with one link per row", async () => {
    stubWithControls(overview({ estate: ESTATE } as Partial<OverviewView>));
    render(<OverviewScreen />);
    const list = await screen.findByTestId("attention-list");
    const backup = await within(list).findByText("1 backup target has no stored backup");
    const row = backup.closest("[data-attention]") as HTMLElement;
    expect(row.getAttribute("data-attention")).toBe("backup-missing");
    expect(within(row).getByText("FW-TANGO-02")).toBeInTheDocument();
    expect(within(row).getByText("never backed up")).toBeInTheDocument();
    const links = within(row).getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "?screen=backups&artefact=none");
    // the list comes before the fleet summary and the framework bars
    const fleet = screen.getByTestId("fleet-summary");
    expect(list.compareDocumentPosition(fleet) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("orders rows by severity: Act now before Review before Watch", async () => {
    stubWithControls(overview({ estate: ESTATE } as Partial<OverviewView>));
    render(<OverviewScreen />);
    const list = await screen.findByTestId("attention-list");
    await within(list).findByText(/jobs failed in the last 24 h/);
    const order = [...list.querySelectorAll("[data-attention]")].map((e) => e.getAttribute("data-attention"));
    // FW-TANGO-01 fails a critical check, FW-TANGO-02 has no backup (Act now); 7 jobs failed, FW-ROMEO-01 never read (Review)
    expect(order).toEqual(["backup-missing", "compliance-critical", "failed-jobs", "not-read"]);
    expect(within(list).getByTestId("attention-counts").textContent).toBe("2 Act now2 Review");
  });

  it("removes the counters that lead to no action: no verdict word, pillars, change pulse or estate map", async () => {
    stubWithControls(overview({ estate: ESTATE } as Partial<OverviewView>));
    render(<OverviewScreen />);
    await screen.findByTestId("attention-list");
    for (const gone of ["Estate map", "Security posture", "Change pulse", "Software in service", "Most widespread critical failures"]) {
      expect(screen.queryByText(gone)).toBeNull();
    }
    expect(screen.queryByTestId("executive-verdict")).toBeNull();
  });

  it("states the fleet in four facts, each opening its list", async () => {
    stubWithControls(overview({ estate: ESTATE } as Partial<OverviewView>));
    render(<OverviewScreen />);
    const fleet = await screen.findByTestId("fleet-summary");
    expect(fleet.querySelector('[data-fact="devices"]')?.textContent).toBe("105enrolled devices");
    expect(fleet.querySelector('[data-fact="backups"]')?.textContent).toBe("83 of 102backup targets backed up");
    expect(fleet.querySelector('[data-fact="freshness"]')?.textContent).toBe("98 of 105devices read in 24 h");
    expect(fleet.querySelector('[data-fact="backups"]')).toHaveAttribute("href", "?screen=backups");
  });

  it("names the most widespread failing critical control on the compliance row", async () => {
    stubWithControls(overview({ estate: ESTATE, compliance: { state: "OK", reason: null, evaluated: 102, of_firewalls: 105, observed_pct: 34.7,
      assured_pct: 28.9, coverage_pct: 82.5, critical_deficiencies: 172, data_gaps: 428,
      frameworks: [{ name: "CIS Benchmark", pass: 708, fail: 1312, unavailable: 404, total: 2424, score_pct: 29.2 }] } } as Partial<OverviewView>), [
      { control_id: "cp1", title: "Account lockout", severity: "CRITICAL", fail_count: 62, target_device_count: 62, data_unavailable_count: 0, vendors: ["check_point"], status: "FAIL" },
      { control_id: "h1", title: "High only", severity: "HIGH", fail_count: 80, target_device_count: 80, data_unavailable_count: 0, vendors: ["check_point"], status: "FAIL" },
    ]);
    render(<OverviewScreen />);
    expect(await screen.findByText("most widespread: Account lockout (62 of 62 firewalls)")).toBeInTheDocument();
    expect(screen.queryByText(/High only/)).toBeNull();
    // B4: 708 / (708 + 1312) = 35.0 % of evidenced checks pass; the assured 29.2 % is not the headline
    expect(screen.getByText("35%")).toBeInTheDocument();
    expect(screen.queryByText("29.2%")).toBeNull();
    expect(screen.getByText("Technical checks. Not an audit certification.")).toBeInTheDocument();
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

describe("Overview counts reconcile (UI review 2026-10-17)", () => {
  it("counts the devices on the map in the header and names registered drafts apart", () => {
    expect(estateDevicesLabel({ active_devices: 133, enrolled_devices: 135 })).toBe("133 enrolled devices (2 more registered, not enrolled)");
    expect(estateDevicesLabel({ active_devices: 133, enrolled_devices: 133 })).toBe("133 enrolled devices");
    expect(estateDevicesLabel({ active_devices: 105 })).toBe("105 enrolled devices");
  });

  it("shows the same framework shares the Compliance card computes from the same parts (B4)", async () => {
    // 749 / (749 + 1266) = 37.17 % -> 37.2 of evidenced checks pass; 500 / 2515 = 19.88 % -> 19.9 have no evidence
    stubWithControls(overview({ estate: ESTATE, compliance: { state: "OK", reason: null, evaluated: 119, of_firewalls: 123, observed_pct: 37.2,
      assured_pct: 29.8, coverage_pct: 80.1, critical_deficiencies: 166, data_gaps: 500,
      frameworks: [{ name: "CIS Benchmark", pass: 749, fail: 1266, unavailable: 500, total: 2515, score_pct: 29.8 }] } } as Partial<OverviewView>));
    render(<OverviewScreen />);
    expect(await screen.findByText("Compliance by framework")).toBeInTheDocument();
    const line = screen.getByText(/of evidenced controls pass/).textContent ?? "";
    expect(line).toBe("37.2% of evidenced controls pass · 19.9% have no evidence");
    expect(document.body.textContent).not.toMatch(/demonstrated pass/);
  });

  it("lists CIS and NIST first, whatever order the service returns (PO 2026-11-05)", async () => {
    const fw = (name: string) => ({ name, pass: 10, fail: 10, unavailable: 0, total: 20, score_pct: 50 });
    stubWithControls(overview({ estate: ESTATE, compliance: { state: "OK", reason: null, evaluated: 2, of_firewalls: 2, observed_pct: 50, assured_pct: 50,
      coverage_pct: 100, critical_deficiencies: 0, data_gaps: 0,
      frameworks: [fw("Financial Baseline"), fw("PCI-DSS v4.0.1"), fw("NIST SP 800-53"), fw("CIS Benchmark")] } } as Partial<OverviewView>));
    render(<OverviewScreen />);
    await screen.findByText("Compliance by framework");
    const order = [...document.querySelectorAll("[data-framework]")].map((e) => e.getAttribute("data-framework"));
    expect(order).toEqual(["CIS Benchmark", "NIST SP 800-53", "Financial Baseline", "PCI-DSS v4.0.1"]);
  });
});


describe("Overview stale data and empty state", () => {
  const HOURS_AGO = (h: number) => new Date(Date.now() - h * 3_600_000).toISOString();
  const staleEstate = { ...ESTATE, facts: { critical: { k: 0, n: 2 }, backups: { a: 102, b: 102 }, evidence: { r: 1, d: 105, never: 0 } },
    devices: ESTATE.devices.map((x, i) => ({ ...x, last_read_at: HOURS_AGO(30 + i) })) };

  it("shows the stale-data banner when under 80 % of active devices were read in 24 h (B1), and the ageing reads as a Watch row", async () => {
    stubWithControls(overview({ estate: staleEstate } as Partial<OverviewView>));
    render(<OverviewScreen />);
    const banner = await screen.findByRole("status", { name: "Data freshness warning" });
    expect(banner.textContent).toContain("104 of 105 active devices were last read more than 24 h ago");
    expect(banner.querySelector("a")).toHaveAttribute("href", "?screen=inventory&inventory_age=stale");
    const row = (await screen.findByText("3 devices last read 24–72 h ago")).closest("[data-attention]") as HTMLElement;
    expect(within(row).getByText("Watch")).toBeInTheDocument();
    expect(within(row).getByText("oldest read 32 h ago")).toBeInTheDocument();
  });

  it("says nothing needs attention, and when it was evaluated, when no rule fires", async () => {
    const clean = { ...ESTATE, devices: ESTATE.devices.map((x) => ({ ...x, critical_fail: 0, no_archive: false, last_read_at: NOW })) };
    stubWithControls(overview({ estate: clean, exceptions: { ...overview().exceptions, failed_jobs: { total: 0, rows: [], reasons: [] } } } as Partial<OverviewView>));
    render(<OverviewScreen />);
    const empty = await screen.findByTestId("attention-empty");
    expect(empty.textContent).toMatch(/^Nothing needs attention\.Evaluated \d\d:\d\d GMT\+3 from 5 sources\.$/);
    expect(screen.queryByRole("status", { name: "Data freshness warning" })).toBeNull();
  });

  it("shows a source that could not be read as an UNKNOWN row, never as nothing", async () => {
    const clean = { ...ESTATE, devices: ESTATE.devices.map((x) => ({ ...x, critical_fail: 0, no_archive: false, last_read_at: NOW })) };
    stubWithControls(overview({ estate: clean, exceptions: { ...overview().exceptions, failed_jobs: { total: 0, rows: [], reasons: [] } } } as Partial<OverviewView>),
      [], { lifecycle: { broken: true } });
    render(<OverviewScreen />);
    const row = (await screen.findByText("Lifecycle facts could not be read")).closest("[data-attention]") as HTMLElement;
    expect(row.getAttribute("data-attention")).toBe("unknown-lifecycle");
    expect(within(row).getByText("UNKNOWN")).toBeInTheDocument();
    expect(screen.queryByTestId("attention-empty")).toBeNull();
  });
});
