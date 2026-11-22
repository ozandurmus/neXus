import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";
import { MoreFooter } from "../src/estate/EstatePrimitives";

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
      if (url === prefix || url.startsWith(`${prefix}?`) || url.startsWith(prefix)) return jsonResponse(200, body());
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

describe("MoreFooter", () => {
  it("renders nothing when total <= shown", () => {
    const { container } = render(<MoreFooter shown={12} total={12} noun="firewalls" />);
    expect(container).toBeEmptyDOMElement();
    const { container: c2 } = render(<MoreFooter shown={12} total={3} noun="firewalls" />);
    expect(c2).toBeEmptyDOMElement();
  });

  it("prints the true remainder with the noun", () => {
    render(<MoreFooter shown={12} total={13} noun="firewalls" />);
    expect(screen.getByText("+1 more firewalls")).toBeInTheDocument();
  });
});

describe("Estate capped lists print the remainder", () => {
  it("Compliance: 30 synthetic controls with a cap of 25 print +5 more controls", async () => {
    window.history.replaceState(null, "", "/?screen=compliance");
    const controls = Array.from({ length: 30 }, (_, i) => ({
      control_id: `CIS-7.${i}`, title: `Control ${i}`, description: "", family: "IA", severity: "CRITICAL", category: "Authentication",
      frameworks: [{ framework: "CIS Benchmark", reference: `7.${i}` }], status: "FAIL", compliance_pct: 0,
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
    expect(more).toHaveTextContent("+5 more controls");
  });

  it("Changes: 45 synthetic change sets with a cap of 40 print +5 more change sets", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    const events = Array.from({ length: 45 }, (_, i) => ({
      key: `pan_cfg:${i}`,
      kind: "USER",
      source: "pan_config",
      time: new Date(Date.now() - (i + 1) * 60_000).toISOString(),
      time_basis: "UTC",
      device_time: "",
      vendor: "palo_alto",
      device_id: "dev-tango-04",
      device_name: "FW-TANGO-04",
      actor: `USR-SYN-${String(i).padStart(2, "0")}`,
      what: "edit system ntp-servers",
      detail: "client Web · result Submitted",
      tone: "NEUTRAL",
      diff: [{ op: "+", text: "ntp-server 192.0.2.5" }],
      links: { device: null, policy_id: null, rule_id: null, job_id: null, job_type: null },
    }));
    vi.stubGlobal(
      "fetch",
      stubFetch({
        "/api/v2/changes/feed": () => ({
          events, next_cursor: "", range_start: "", range_end: "", kinds: ["USER"],
          vendor: null, device_id: null, masked: true, ordering_note: "", coverage: [],
        }),
      }),
    );
    stubViewport(1280);
    render(<App />);
    const changes = await screen.findByTestId("estate-changes-screen");
    expect(await within(changes).findByText(/\+5 more change sets/)).toBeInTheDocument();
  });
});

describe("Estate Compliance capped lists print the exact remainder", () => {
  const pad = (i: number) => String(i).padStart(2, "0");
  const gateways = (count: number) =>
    Array.from({ length: count }, (_, i) => ({
      device_id: `dev-tango-${pad(i + 1)}`, vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
      hostname: `FW-TANGO-${pad(i + 1)}`, management_ip: `192.0.2.${i + 1}`, backup_target: true,
      inventory_collected_at: new Date(Date.now() - 3 * 60_000).toISOString(),
    }));
  const overview = {
    total_firewalls: 26, evaluated_firewalls: 0, assured_compliance_pct: 0, evidence_coverage_pct: 0,
    observed_compliance_pct: 0, critical_deficiencies: 0, data_gaps: 0, control_checks: 0, frameworks: [],
  };

  /** Serves a given device roster; the per-control detail route is the /api/v2 path the screen reads. */
  function stubRoster(devices: readonly unknown[], routes: Record<string, () => unknown>, detail?: unknown) {
    const base = stubFetch(routes);
    return vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") return jsonResponse(200, { devices });
      if (detail !== undefined && url.startsWith("/api/v2/compliance/controls/")) return jsonResponse(200, detail);
      return base(input);
    });
  }

  it("un-assessed firewalls: 13 gateways list the first 12 and print +1 more firewalls", async () => {
    window.history.replaceState(null, "", "/?screen=compliance");
    vi.stubGlobal(
      "fetch",
      stubRoster(gateways(13), {
        "/compliance/controls": () => ({ total_controls: 0, controls: [] }),
        "/compliance/overview": () => overview,
      }),
    );
    stubViewport(1280);
    render(<App />);
    const comp = await screen.findByTestId("estate-compliance-screen");
    expect(await within(comp).findByTestId("unassessed-more")).toHaveTextContent("+1 more firewalls");
    expect(within(comp).getAllByText("FW-TANGO-01").length).toBeGreaterThan(0);
    expect(within(comp).getAllByText("FW-TANGO-12").length).toBeGreaterThan(0);
    expect(within(comp).queryAllByText("FW-TANGO-13")).toHaveLength(0);
  });

  it("remediation rows, affected units and per-unit verdicts print their remainders", async () => {
    window.history.replaceState(null, "", "/?screen=compliance");
    const devices = gateways(25);
    const affectedAll = devices.map((d) => d.hostname);
    const controls = Array.from({ length: 26 }, (_, i) => ({
      control_id: `CIS-7.${i}`, title: `Control ${i}`, description: "", family: "IA", severity: "CRITICAL", category: "Authentication",
      frameworks: [{ framework: "CIS Benchmark", reference: `7.${i}` }], status: "FAIL", compliance_pct: 0,
      target_device_count: 25, pass_count: 0, fail_count: 1, data_unavailable_count: 0,
      affected_devices: i === 0 ? affectedAll : [affectedAll[0]], vendors: ["check_point"],
    }));
    // 21 non-PASS verdicts on the first control: the screen shows 20 and prints +1 more.
    const verdictDevices = devices.slice(0, 21).map((d) => ({
      device_id: d.device_id, hostname: d.hostname, vendor: "check_point", status: "FAIL",
      observed_value: "synthetic-observed", message: null,
    }));
    vi.stubGlobal(
      "fetch",
      stubRoster(
        devices,
        {
          "/compliance/controls": () => ({ total_controls: 26, controls }),
          "/compliance/overview": () => ({ ...overview, evaluated_firewalls: 25, control_checks: 26, critical_deficiencies: 26 }),
        },
        {
          control_id: "CIS-7.0", title: "Control 0", severity: "CRITICAL", status: "FAIL", rationale: null,
          frameworks: [], expected: [], devices: verdictDevices,
        },
      ),
    );
    stubViewport(1280);
    render(<App />);
    const comp = await screen.findByTestId("estate-compliance-screen");

    // Remediation: 26 rows, first 25 render, +1 more controls.
    expect(await within(comp).findByTestId("remediation-more")).toHaveTextContent("+1 more controls");
    expect(comp.querySelectorAll("[data-remediation-row]")).toHaveLength(25);

    const row = comp.querySelector('[data-remediation-row="CIS-7.0"]') as HTMLElement;
    expect(row).not.toBeNull();
    // Affected units: 25 units, first 24 render, +1 more units.
    expect(await within(row).findByTestId("affected-more")).toHaveTextContent("+1 more units");

    // Verdicts: expand the row; 21 non-PASS, 20 shown, +1 more units.
    fireEvent.click(within(row).getByRole("button", { name: /^Show/ }));
    expect(await within(row).findByTestId("verdicts-more")).toHaveTextContent("+1 more units");
    expect(within(row).getAllByText("FW-TANGO-01").length).toBeGreaterThan(0);
    expect(within(row).getAllByText("FW-TANGO-20").length).toBeGreaterThan(0);
    expect(within(row).queryAllByText("FW-TANGO-21")).toHaveLength(0);
  });
});
