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
  it("shows the five attention tiles with their denominators and filtered links", async () => {
    stub(overview());
    render(<OverviewScreen />);
    expect(await screen.findByText("Needs attention")).toBeInTheDocument();
    const tile = screen.getByText("Backup targets without an archive").closest("a")!;
    expect(tile.getAttribute("href")).toBe("?screen=backups&artefact=none");
    expect(tile.textContent).toContain("19");
    expect(tile.textContent).toContain("of 102");
    expect(screen.getByText("Failed jobs, 24 h").closest("a")!.getAttribute("href")).toBe("?screen=operations&tab=jobs&state=FAILED&since_hours=24");
    expect(screen.getByText("Clusters with member DIFF").closest("a")!.textContent).toContain("UNKNOWN for 8 cluster(s)");
  });

  it("writes UNKNOWN, never 0, for what is not evidenced", async () => {
    stub(overview({ attention: { ...overview().attention, stale_inventory: { count: 0, of: 0, state: "UNKNOWN" } } }));
    render(<OverviewScreen />);
    expect(await screen.findByText(/UNKNOWN — compliance not yet evaluated/)).toBeInTheDocument();
    expect(screen.getByText("Devices without inventory in 24 h").closest("a")!.textContent).toContain("UNKNOWN");
    expect(screen.getByText("UNKNOWN — no stored evidence")).toBeInTheDocument();
  });

  it("caps an exception list at five rows and links to the whole list", async () => {
    stub(overview());
    render(<OverviewScreen />);
    expect(await screen.findByText("Show all (7)")).toBeInTheDocument();
    expect(screen.getAllByText("connect_failed")).toHaveLength(5);
    expect(screen.getByText("No configuration change in the latest collections.")).toBeInTheDocument();
  });

  it("keeps an UNKNOWN row in the hotfix histogram and never calls a level outdated", async () => {
    stub(overview());
    render(<OverviewScreen />);
    expect(await screen.findByText("R81.20 Jumbo Take 119")).toBeInTheDocument();
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    expect(document.body.textContent).not.toMatch(/outdated/i);
  });

  it("says the read failed instead of rendering figures when the endpoint fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{}", { status: 500 })));
    render(<OverviewScreen />);
    expect(await screen.findByText("Overview unavailable")).toBeInTheDocument();
  });

  it("ages evidence in plain words", () => {
    const now = new Date("2026-09-23T12:00:00Z");
    expect(relativeAge("2026-09-23T11:30:00Z", now)).toBe("30 min ago");
    expect(relativeAge("2026-09-22T12:00:00Z", now)).toBe("24 h ago");
    expect(relativeAge(null, now)).toBe("UNKNOWN");
  });
});
