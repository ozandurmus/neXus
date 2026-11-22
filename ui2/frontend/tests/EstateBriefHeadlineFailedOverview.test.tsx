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

/** /devices answers 200; the overview answers `overview()` when given, otherwise 500 (a failed read). */
function stubFetch(overview?: () => unknown) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
    if (overview && (url === "/api/v2/overview" || url.startsWith("/api/v2/overview?"))) return jsonResponse(200, overview());
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
  it("a readable overview whose compliance source is UNKNOWN is not all-clear and says sources are UNKNOWN", async () => {
    window.history.replaceState(null, "", "/?screen=overview");
    vi.stubGlobal("fetch", stubFetch(() => quietOverview("UNKNOWN")));
    stubViewport(1280);
    render(<App />);
    const headline = await screen.findByTestId("brief-headline");
    // The harness leaves the other sources unread (500), so the count is not pinned here; the wording is.
    expect(headline).toHaveTextContent(/Nothing is proven to need action, but \d+ sources? (is|are) UNKNOWN/);
    expect(headline).not.toHaveTextContent(/Nothing needs immediate action/i);
  });
});
