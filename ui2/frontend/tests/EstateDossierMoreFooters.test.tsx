import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

// Synthetic data only: FW-TANGO-04 style names and 192.0.2.x / 198.51.100.x addresses.
const headers = { "X-Nexus-Masked": "true" };
const DEVICE_ID = "dev-tango-04";

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

const DEVICES = [
  {
    device_id: DEVICE_ID, vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04", management_ip: "192.0.2.14", backup_target: true,
    inventory_collected_at: new Date(Date.now() - 3 * 60_000).toISOString(),
  },
];

function interfaces(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    name: `eth1-${String(i).padStart(2, "0")}`, parent: null, kind: "physical", state: "UP", vlan_id: null, zone: "external",
    addresses: [{ address: `192.0.2.${(i % 250) + 1}/24`, family: "ipv4", role: "member" }],
  }));
}

function routes(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    destination: `198.51.100.${i % 250}/32`, next_hop: "192.0.2.1", interface: "eth1-01", protocol: "static", table: "main",
  }));
}

function backups(count: number) {
  const tenHoursAgo = new Date(Date.now() - 10 * 3_600_000).toISOString();
  return Array.from({ length: count }, (_, i) => ({
    artefact_id: `bk-tango-04-${i}`, device_id: DEVICE_ID, collected_at: tenHoursAgo, size_bytes: 1024 * (i + 1),
    digest_prefix: "11223344", validation_level: "V3", deviation_state: "unchanged",
  }));
}

/** Every read answers 500 unless `routes` supplies a body; /devices always succeeds. */
function stubFetch(inventoryContexts: unknown[], backupList: unknown[]) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
    if (url === `/devices/${DEVICE_ID}/inventory`) {
      return jsonResponse(200, {
        device_id: DEVICE_ID,
        collected_at: new Date(Date.now() - 2 * 3_600_000).toISOString(),
        job: null,
        contexts: inventoryContexts,
      });
    }
    if (url === `/devices/${DEVICE_ID}/backups`) return jsonResponse(200, { backups: backupList });
    return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
  });
}

function context(interfaceCount: number, routeCount: number) {
  return {
    context: "0", vs_name: "gw-core",
    ha: { role: "ACTIVE", cluster_mode: "High Availability" },
    interfaces: interfaces(interfaceCount),
    routes: routes(routeCount),
  };
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

describe("Estate dossier capped lists print the remainder", () => {
  it("interfaces: 70 synthetic interfaces with a cap of 60 print +10 more interfaces", async () => {
    window.history.replaceState(null, "", `/?screen=inventory&device_id=${DEVICE_ID}`);
    vi.stubGlobal("fetch", stubFetch([context(70, 0)], []));
    stubViewport(1280);
    render(<App />);
    const dossier = await screen.findByTestId("estate-dossier-screen");
    expect(await within(dossier).findByTestId("interfaces-more")).toHaveTextContent("+10 more interfaces");
  });

  it("routes: 90 synthetic routes with a cap of 80 print +10 more routes", async () => {
    window.history.replaceState(null, "", `/?screen=inventory&device_id=${DEVICE_ID}`);
    vi.stubGlobal("fetch", stubFetch([context(1, 90)], []));
    stubViewport(1280);
    render(<App />);
    const dossier = await screen.findByTestId("estate-dossier-screen");
    const routesTab = await within(dossier).findByRole("tab", { name: /^Routes \(/ });
    routesTab.click();
    expect(await within(dossier).findByTestId("routes-more")).toHaveTextContent("+10 more routes");
  });

  it("backups: 16 synthetic backup artefacts with a cap of 14 print +2 more artefacts", async () => {
    window.history.replaceState(null, "", `/?screen=inventory&device_id=${DEVICE_ID}`);
    vi.stubGlobal("fetch", stubFetch([context(1, 0)], backups(16)));
    stubViewport(1280);
    render(<App />);
    const dossier = await screen.findByTestId("estate-dossier-screen");
    const backupsTab = await within(dossier).findByRole("tab", { name: /^Backups \(/ });
    backupsTab.click();
    expect(await within(dossier).findByTestId("backups-more")).toHaveTextContent("+2 more artefacts");
  });

  it("a list at or below its cap prints no footer", async () => {
    window.history.replaceState(null, "", `/?screen=inventory&device_id=${DEVICE_ID}`);
    vi.stubGlobal("fetch", stubFetch([context(60, 80)], backups(14)));
    stubViewport(1280);
    render(<App />);
    const dossier = await screen.findByTestId("estate-dossier-screen");
    await within(dossier).findByRole("table", { name: "Unit interfaces" });
    expect(within(dossier).queryByTestId("interfaces-more")).toBeNull();
    expect(within(dossier).queryByText(/more interfaces/)).toBeNull();
  });
});
