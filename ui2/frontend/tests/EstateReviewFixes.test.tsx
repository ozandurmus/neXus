import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";
import { UnitTile } from "../src/estate/EstatePrimitives";
import type { EstateUnit } from "../src/estate/estateModel";
import { buildRewindUnit } from "../src/estate/estateRewindModel";

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

const DEVICES = [
  {
    device_id: "dev-tango-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04", management_ip: "192.0.2.14", backup_target: true,
    inventory_collected_at: new Date(Date.now() - 3 * 60_000).toISOString(),
  },
];

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

describe("Estate view review fixes", () => {
  it("fix 1: a failed overview with readable /devices is UNKNOWN in the Brief headline, never 'nothing needs action'", async () => {
    window.history.replaceState(null, "", "/?screen=overview");
    vi.stubGlobal("fetch", stubFetch({}));
    stubViewport(1280);
    render(<App />);
    const headline = await screen.findByTestId("brief-headline");
    expect(headline).toHaveTextContent(/UNKNOWN/);
    expect(headline).not.toHaveTextContent(/Nothing needs immediate action/i);
    expect(screen.getByTestId("brief-overview-unreadable")).toBeInTheDocument();
    expect(screen.queryByText("All clear")).toBeNull();
  });

  it("fix 2: a unit tile states the age of its own lens evidence, not the inventory read age", () => {
    const sixDays = new Date(Date.now() - 6 * 86_400_000).toISOString();
    const unit = {
      deviceId: "dev-tango-04", name: "FW-TANGO-04", vendorName: "Check Point", role: "gateway", haRole: null, clusterRef: null,
      lastReadAt: new Date(Date.now() - 3 * 60_000).toISOString(),
      lenses: {
        backups: {
          tileState: "ok", badgeState: "ok", badgeWord: "Fine", summary: "Archive stored", detail: null,
          evidenceAt: sixDays, evidenceSource: "backup",
        },
      },
    } as unknown as EstateUnit;
    render(<UnitTile unit={unit} lens="backups" size={16} now={new Date()} />);
    const label = screen.getByLabelText(/FW-TANGO-04/);
    const text = label.getAttribute("aria-label") ?? "";
    expect(text).toMatch(/backup evidence/);
    expect(text).toMatch(/6 d|6 days/);
    expect(text).not.toMatch(/3 m/);
  });

  it("fix 4: a change feed with next_cursor announces that more events exist", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "/api/v2/changes/feed": () => ({
          events: [], next_cursor: "cursor-2", range_start: "", range_end: "", kinds: ["USER"],
          vendor: null, device_id: null, masked: true, ordering_note: "", coverage: [],
        }),
      }),
    );
    stubViewport(1280);
    render(<App />);
    const note = await screen.findByTestId("changes-feed-truncated");
    expect(note).toHaveTextContent(/more exist/i);
  });

  it("fix 5: capped Compliance lists print the remainder", async () => {
    window.history.replaceState(null, "", "/?screen=compliance");
    const controls = Array.from({ length: 30 }, (_, i) => ({
      control_id: `CIS-9.${i}`, title: `Control ${i}`, description: "", family: "IA", severity: "CRITICAL", category: "Authentication",
      frameworks: [{ framework: "CIS Benchmark", reference: `9.${i}` }], status: "FAIL", compliance_pct: 0,
      target_device_count: 1, pass_count: 0, fail_count: 1, data_unavailable_count: 0,
      affected_devices: ["FW-TANGO-04"], vendors: ["check_point"],
    }));
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "/compliance/controls": () => ({ total_controls: 30, controls }),
        "/compliance/overview": () => ({
          total_firewalls: 1, evaluated_firewalls: 1, assured_compliance_pct: 0, evidence_coverage_pct: 100,
          observed_compliance_pct: 0, critical_deficiencies: 30, data_gaps: 0, control_checks: 30, frameworks: [],
        }),
      }),
    );
    stubViewport(1280);
    render(<App />);
    const comp = await screen.findByTestId("estate-compliance-screen");
    const more = await within(comp).findByTestId("remediation-more");
    expect(more).toHaveTextContent("+5 more");
  });

  it("decision: Rewind applies the live 7-day backup-stale rule and states the archive age", () => {
    const asOf = "2026-11-20T12:00:00Z";
    const mk = (days: number) =>
      buildRewindUnit(
        {
          device_id: "dev-tango-04", hostname: "FW-TANGO-04", vendor: "check_point",
          backup: { verdict: "STALE", evidence_at: new Date(Date.parse(asOf) - days * 86_400_000).toISOString() },
        } as never,
        asOf,
      ).lenses.backups;
    expect(mk(3)).toMatchObject({ state: "ok", word: "Stored" });
    expect(mk(3).detail).toMatch(/3 d old/);
    expect(mk(8)).toMatchObject({ state: "watch", word: "Stale" });
  });
});
