import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
const headers = { "X-Nexus-Masked": "true" };

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => {
      const minMatch = query.match(/\(min-width:\s*(\d+)px\)/);
      const maxMatch = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
      const matches = minMatch ? widthPx >= Number(minMatch[1]) : maxMatch ? widthPx <= Number(maxMatch[1]) : false;
      return {
        matches, media: query, onchange: null,
        addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
      };
    }),
  );
}

const daysAgo = (days: number) => new Date(Date.now() - days * 86_400_000).toISOString();

/** One enrolled unit whose inventory read is fresh, so no unit-level attention row is raised. */
const DEVICES = [
  {
    device_id: "dev-tango-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04", management_ip: "192.0.2.14", backup_target: true,
    inventory_collected_at: daysAgo(0),
  },
];

/** A quiet overview: every source OK, the one unit archived and freshly read. */
function quietOverview(complianceState: "OK" | "UNKNOWN" = "OK") {
  const at = daysAgo(0);
  return {
    generated_at: at,
    masked: true,
    denominators: { active_devices: 1, enrolled_devices: 1, gateways: 1, clusters: 0, clusters_enrolled: 0, backup_targets: 1 },
    evidence: {
      inventory: { at, state: "OK" }, configuration: { at, state: "OK" }, compliance: { at, state: "OK" },
      backup: { at, state: "OK" }, jobs: { at, state: "OK" }, platform_facts: { at, state: "OK" },
    },
    attention: {
      backup_missing: { state: "OK", count: 0, of: 1 },
      failed_jobs_24h: { state: "OK", count: 0, terminal_24h: 0, other_terminal_24h: 0 },
      cluster_diff: { state: "OK", count: 0 },
      config_changed: { state: "OK", count: 0 },
    },
    estate: {
      compliance_state: complianceState,
      devices: [
        {
          device_id: "dev-tango-04", hostname: "FW-TANGO-04", no_archive: false, backup_target: true,
          last_backup_at: daysAgo(0), last_read_at: daysAgo(0), critical_fail: 0,
        },
      ],
    },
  };
}

/** Quiet, valid 200 bodies for every secondary read the Estate hooks make (synthetic, masked). */
const CLEAN_SOURCES: Record<string, unknown> = {
  "/api/v2/cp-failover/summary": [],
  "/backups": { backups: [] },
  "/api/v2/backups/deviations": { deviations: [] },
  "/api/v2/lifecycle": { devices: [] },
  "/compliance/controls": { controls: [] },
  "/api/v2/changes/feed": { events: [], next_cursor: null },
  "/api/v2/system-logs/health": {
    computation: { state: "READY", computed_at: daysAgo(0), duration_ms: 12, snapshot_ttl_seconds: 300, store_available: true },
    window_days: 7, cpu_p95_threshold_percent: 90, time_basis: "snapshot",
    rules: {
      critical: [], attention: [], normal: "OK", unknown: "UNKNOWN", state_order: ["OK", "ATTENTION", "CRITICAL", "UNKNOWN"],
      p95: "95th percentile CPU over the window", restart_rule: { id: "restart", status: "OK", text: "No restarts" },
    },
    kpis: { monitored: { devices: 1, by_vendor: {}, not_collected_listed: 0 }, crash_alerts: null, cpu_over_threshold: null, collection_not_ok: null },
    devices: [],
  },
  "/api/v2/security/scan-summary/latest": {
    available: true, mail: { route_enabled: false, smtp_configured: false, recipients_configured: false, delivering: false, last_sent_at: null, last_error: null },
    stale_after_hours: 36, generated_at: daysAgo(0), age_hours: 0, stale: false, passed: true, scan_errors: 0, blocking: 0, counts: {},
  },
};

/**
 * /devices answers 200; the overview answers `overview()` when given, otherwise 500 (a failed read).
 * With `clean`, every secondary read answers its quiet 200 body, so only the overview decides the headline.
 */
function stubFetch(overview?: () => unknown, clean = false) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const path = url.split("?")[0];
    if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
    if (overview && (url === "/api/v2/overview" || url.startsWith("/api/v2/overview?"))) return jsonResponse(200, overview());
    if (clean && path in CLEAN_SOURCES) return jsonResponse(200, CLEAN_SOURCES[path]);
    return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
  });
}

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  window.localStorage.setItem("nx.view", "estate");
});
afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("Estate Brief headline over a failed overview (review finding 1)", () => {
  it("an overview request that fails while /devices returns 200 never shows the all-clear sentence", async () => {
    window.history.replaceState(null, "", "/?screen=overview");
    vi.stubGlobal("fetch", stubFetch());
    stubViewport(1280);
    render(<App />);
    const headline = await screen.findByTestId("brief-headline");
    expect(headline).toHaveTextContent(/overview read failed/i);
    expect(headline).not.toHaveTextContent(/Nothing needs immediate action/i);
    expect(headline).not.toHaveTextContent(/Nothing is proven to need action/i);
  });
});

describe("Estate Brief headline with one UNKNOWN source (review finding 1)", () => {
  it("positive control: a quiet overview with every secondary read clean 200 shows the all-clear sentence", async () => {
    window.history.replaceState(null, "", "/?screen=overview");
    vi.stubGlobal("fetch", stubFetch(() => quietOverview("OK"), true));
    stubViewport(1280);
    render(<App />);
    const headline = await screen.findByTestId("brief-headline");
    expect(headline).toHaveTextContent("Nothing needs immediate action across the evidenced estate.");
  });

  it("a readable overview whose compliance source is UNKNOWN is not all-clear and says exactly one source is UNKNOWN", async () => {
    window.history.replaceState(null, "", "/?screen=overview");
    vi.stubGlobal("fetch", stubFetch(() => quietOverview("UNKNOWN"), true));
    stubViewport(1280);
    render(<App />);
    const headline = await screen.findByTestId("brief-headline");
    expect(headline).toHaveTextContent("Nothing is proven to need action, but 1 source is UNKNOWN.");
    expect(headline).not.toHaveTextContent(/Nothing needs immediate action/i);
  });
});
