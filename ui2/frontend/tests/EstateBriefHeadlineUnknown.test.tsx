import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

// Regression tests for the Shift brief headline when some evidence is UNKNOWN (ESTATE_VIEW_EFFECTIVENESS_REVIEW
// 2026-11-21, fix 1 and fix 10). Synthetic FW-TANGO-04 style names and 192.0.2.x / 198.51.100.x addresses only.

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "X-Nexus-Masked": "true" },
  });
}

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => {
      const minMatch = query.match(/\(min-width:\s*(\d+)px\)/);
      const maxMatch = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
      let matches = false;
      if (minMatch) matches = widthPx >= Number(minMatch[1]);
      else if (maxMatch) matches = widthPx <= Number(maxMatch[1]);
      return {
        matches,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      };
    }),
  );
}

interface StubOptions {
  /** Inventory timestamp of the single device; null means the unit was never read. */
  inventoryCollectedAt: string | null;
  /** HTTP status of the overview read; anything other than 200 leaves the overview unreadable. */
  overviewStatus: number;
  /** HTTP status of the lifecycle read; a failed read is an UNKNOWN source and nothing else. */
  lifecycleStatus?: number;
}

function stubBriefFetch({ inventoryCollectedAt, overviewStatus, lifecycleStatus = 200 }: StubOptions) {
  const nowIso = new Date().toISOString();
  const twoHoursAgo = new Date(Date.now() - 2 * 3_600_000).toISOString();
  const tenHoursAgo = new Date(Date.now() - 10 * 3_600_000).toISOString();

  // A clean overview: every attention count is zero, so no act / review rows can come from it.
  const overviewBody = {
    generated_at: nowIso,
    masked: true,
    denominators: { active_devices: 1, enrolled_devices: 1, gateways: 1, clusters: 0, backup_targets: 1 },
    evidence: {
      inventory: { at: twoHoursAgo, state: "OK" },
      configuration: { at: twoHoursAgo, state: "OK" },
      compliance: { at: tenHoursAgo, state: "OK" },
      backup: { at: tenHoursAgo, state: "OK" },
      jobs: { at: twoHoursAgo, state: "OK" },
      platform_facts: { at: twoHoursAgo, state: "OK" },
    },
    attention: {
      failed_jobs_24h: { count: 0, terminal_24h: 12, previous: 0, state: "OK" },
      stale_inventory: { count: 0, of: 1, state: "OK" },
      cluster_diff: { count: 0, of: 0, previous: 0, state: "OK" },
      config_changed: { count: 0, of: 1, previous: 0, state: "OK" },
      backup_missing: { count: 0, of: 1, previous: 0, state: "OK" },
    },
    exceptions: {
      failed_jobs: { total: 0, rows: [] },
      config_changes: { total: 0, rows: [] },
      cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] },
    },
    inventory_age: { lt24h: 1, h24_72: 0, gt72h: 0, never: 0, of: 1, state: "OK" },
    compliance: {
      state: "OK",
      evaluated: 1,
      of_firewalls: 1,
      coverage_pct: 100,
      assured_pct: 100,
      observed_pct: 100,
      critical_deficiencies: 0,
      data_gaps: 0,
      frameworks: [],
    },
    platform: { devices: 1, check_point: 1, palo_alto: 0, clusters: 0, hotfix_levels: [], evidence_at: twoHoursAgo },
    policy_install: { state: "OK", of: 1, today: 0, yesterday: 0, days_2_7: 0, older: 0, unknown: 0, last_read_at: twoHoursAgo },
    nexus: {
      completed_24h: 12,
      running: 0,
      oldest_running_submitted_at: null,
      last_inventory: { check_point: twoHoursAgo, palo_alto: null },
    },
    estate: {
      compliance_state: "OK",
      no_archive: 0,
      counts: { critical: 0, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: 1 },
      facts: { critical: { k: 0, n: 1 }, backups: { a: 1, b: 1 }, evidence: { r: 1, d: 1, never: 0 } },
      devices: [
        {
          device_id: "dev-tango-04",
          hostname: "FW-TANGO-04",
          vendor: "check_point",
          model: "16200",
          condition: "clear",
          no_archive: false,
          critical_fail: 0,
          last_read_at: twoHoursAgo,
          backup_target: true,
          last_backup_at: tenHoursAgo,
        },
      ],
    },
  };

  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") {
      return jsonResponse(200, {
        devices: [
          {
            device_id: "dev-tango-04",
            vendor_hint: "check_point",
            role: "gateway",
            enrollment_state: "ENROLLED",
            hostname: "FW-TANGO-04",
            model: "16200",
            software_version: "R81.20",
            ha_role: null,
            cluster_member_ref: null,
            management_ip: "192.0.2.14",
            backup_target: true,
            inventory_collected_at: inventoryCollectedAt,
            policy_name: "POL-ALPHA-01",
            policy_installed_at: tenHoursAgo,
            policy_read_at: twoHoursAgo,
          },
        ],
      });
    }
    if (url === "/api/v2/overview") {
      return overviewStatus === 200
        ? jsonResponse(200, overviewBody)
        : jsonResponse(overviewStatus, { error: "overview unavailable" });
    }
    // Every other read is answered with an empty body of its own shape, so an empty source is read, not UNKNOWN.
    if (url === "/backups") return jsonResponse(200, { backups: [] });
    if (url === "/api/v2/backups/deviations") {
      return jsonResponse(200, { active_major_deviations: [], size_anomalies: [], total_deviations_checked: 1 });
    }
    if (url === "/api/v2/cp-failover/summary") return jsonResponse(200, []);
    if (url === "/api/v2/lifecycle") {
      return lifecycleStatus === 200
        ? jsonResponse(200, { as_of: nowIso, summary: {}, devices: [] })
        : jsonResponse(lifecycleStatus, { error: "lifecycle unavailable" });
    }
    if (url === "/compliance/controls") return jsonResponse(200, { controls: [] });
    if (url.startsWith("/api/v2/changes/feed")) return jsonResponse(200, { events: [] });
    if (url === "/api/v2/security/scan-summary/latest") {
      return jsonResponse(200, {
        available: true,
        mail: { state: "OK" },
        stale_after_hours: 26,
        generated_at: nowIso,
        age_hours: 0,
        stale: false,
        passed: true,
        scan_errors: 0,
        blocking: 0,
        findings_listed_in_summary: true,
        findings_total: 0,
        findings: [],
      });
    }
    if (url === "/api/v2/system-logs/health") {
      return jsonResponse(200, {
        computation: { state: "OK" },
        window_days: 7,
        cpu_p95_threshold_percent: 80,
        rules: { critical: [], attention: [], state_order: [], restart_rule: {} },
        kpis: {},
        devices: [],
      });
    }
    return jsonResponse(404, { error: "not stubbed" });
  });
}

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  window.localStorage.setItem("nx.view", "estate");
  window.history.replaceState(null, "", "/?screen=overview");
});

afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

async function headlineText(): Promise<string> {
  const headline = await screen.findByTestId("brief-headline");
  await waitFor(() => expect(headline.textContent?.length ?? 0).toBeGreaterThan(0));
  return headline.textContent ?? "";
}

describe("Shift brief headline with UNKNOWN evidence", () => {
  it("(a) overview read failed while /devices returns 200: the headline does not claim nothing needs action", async () => {
    vi.stubGlobal(
      "fetch",
      stubBriefFetch({ inventoryCollectedAt: new Date(Date.now() - 3_600_000).toISOString(), overviewStatus: 500 }),
    );
    stubViewport(1280);
    render(<App />);

    const text = await headlineText();
    expect(text).not.toMatch(/Nothing needs immediate action/i);
    expect(text).not.toMatch(/Nothing is proven to need action/i);
    expect(text).toMatch(/overview read failed/i);
    expect(text).toMatch(/UNKNOWN/);
  });

  it("(b) unknown source present but no act or review: the headline says nothing is proven to need action", async () => {
    // One source (lifecycle) cannot be read; with a clean overview it produces no act or review item.
    vi.stubGlobal(
      "fetch",
      stubBriefFetch({ inventoryCollectedAt: new Date(Date.now() - 3_600_000).toISOString(), overviewStatus: 200, lifecycleStatus: 500 }),
    );
    stubViewport(1280);
    render(<App />);

    const text = await headlineText();
    expect(text).toMatch(/^Nothing is proven to need action, but /);
    expect(text).toMatch(/1 source is UNKNOWN/);
    expect(text).not.toMatch(/Nothing needs immediate action/i);
    expect(text).not.toMatch(/act now|to review/);
  });

  it("(c) no act, no review, no unknown: the headline says nothing needs immediate action", async () => {
    vi.stubGlobal(
      "fetch",
      stubBriefFetch({ inventoryCollectedAt: new Date(Date.now() - 3_600_000).toISOString(), overviewStatus: 200 }),
    );
    stubViewport(1280);
    render(<App />);

    const text = await headlineText();
    expect(text).toMatch(/^Nothing needs immediate action across the evidenced estate\./);
    expect(text).not.toMatch(/Nothing is proven to need action/);
    expect(text).not.toMatch(/UNKNOWN/);
  });
});
