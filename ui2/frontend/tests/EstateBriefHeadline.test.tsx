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

/** One enrolled firewall whose inventory read is five days old (a stale unit). */
const DEVICES = [
  {
    device_id: "dev-tango-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04", management_ip: "192.0.2.14", backup_target: true,
    inventory_collected_at: daysAgo(5),
  },
];

/** Overview body whose estate block flags the same unit as having no stored archive. */
function overviewBody() {
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
      backup_missing: { state: "OK", count: 1, of: 1 },
      failed_jobs_24h: { state: "OK", count: 0, terminal_24h: 0, other_terminal_24h: 0 },
      cluster_diff: { state: "OK", count: 0 },
      config_changed: { state: "OK", count: 0 },
    },
    estate: {
      compliance_state: "OK",
      devices: [
        {
          device_id: "dev-tango-04", hostname: "FW-TANGO-04", no_archive: true, backup_target: true,
          last_backup_at: null, last_read_at: daysAgo(5), critical_fail: 0,
        },
      ],
    },
  };
}

/** Every read answers 500 unless `routes` supplies a body; /devices always succeeds. */
function stubFetch(routes: Record<string, () => unknown>) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
    for (const [prefix, body] of Object.entries(routes)) {
      if (url === prefix || url.startsWith(`${prefix}?`)) return jsonResponse(200, body());
    }
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

describe("Estate Brief headline (review fixes 1 and 6)", () => {
  it("a failed overview with readable /devices never reads as all-clear and says the overview is unreadable", async () => {
    window.history.replaceState(null, "", "/?screen=overview");
    vi.stubGlobal("fetch", stubFetch({}));
    stubViewport(1280);
    render(<App />);
    const headline = await screen.findByTestId("brief-headline");
    expect(headline).toHaveTextContent(/overview read failed/i);
    expect(headline).toHaveTextContent(/UNKNOWN/);
    expect(headline).not.toHaveTextContent(/Nothing needs immediate action/i);
    expect(screen.getByTestId("brief-overview-unreadable")).toHaveTextContent(/not "all clear"/);
    expect(screen.queryByText("All clear")).toBeNull();
  });

  it("two attention rows on one unit are counted as items on 1 unit, not as two units", async () => {
    window.history.replaceState(null, "", "/?screen=overview");
    vi.stubGlobal("fetch", stubFetch({ "/api/v2/overview": overviewBody }));
    stubViewport(1280);
    render(<App />);
    const headline = await screen.findByTestId("brief-headline");
    // One unit carries a critical backup-missing row and a serious not-read row: 2 items, 1 unit.
    expect(headline).toHaveTextContent(/2 items on 1 unit: 1 act now, 1 to review\./);
    expect(headline).not.toHaveTextContent(/on 2 units/);
    expect(headline).not.toHaveTextContent(/Nothing needs immediate action/i);
  });
});
