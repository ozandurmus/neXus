import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache, type ComplianceControlDetail } from "../src/auth/adminApi";
import { CONTROL_EXPECTED_CAP, CONTROL_FRAMEWORKS_CAP, projectControlDetail } from "../src/estate/estateComplianceDetailModel";

// Synthetic fixtures only (FW-TANGO-style names, 192.0.2.x addresses). No real hostnames, addresses or serials.
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

const pad = (i: number) => String(i).padStart(2, "0");
const gateways = (count: number) =>
  Array.from({ length: count }, (_, i) => ({
    device_id: `dev-tango-${pad(i + 1)}`, vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: `FW-TANGO-${pad(i + 1)}`, management_ip: `192.0.2.${i + 1}`, backup_target: true,
    inventory_collected_at: new Date(Date.now() - 3 * 60_000).toISOString(),
  }));

const CONTROL = {
  control_id: "CIS-7.0", title: "Control 0", description: "", family: "IA", severity: "CRITICAL", category: "Authentication",
  frameworks: [{ framework: "CIS Benchmark", reference: "7.0" }], status: "FAIL", compliance_pct: 0,
  target_device_count: 3, pass_count: 1, fail_count: 1, data_unavailable_count: 1,
  affected_devices: ["FW-TANGO-01"], vendors: ["check_point"],
};

const OVERVIEW = {
  total_firewalls: 3, evaluated_firewalls: 3, assured_compliance_pct: 0, evidence_coverage_pct: 100,
  observed_compliance_pct: 0, critical_deficiencies: 1, data_gaps: 0, control_checks: 3, frameworks: [],
};

/** Nine expected lines and nine framework mappings, so both caps (8) print a "+1 more" footer. */
const DETAIL: ComplianceControlDetail = {
  control_id: "CIS-7.0",
  title: "Control 0",
  severity: "CRITICAL",
  status: "FAIL",
  rationale: "Synthetic rationale: the example control keeps the management plane authenticated.",
  frameworks: Array.from({ length: 9 }, (_, i) => ({ framework: "CIS_Benchmark", reference: `7.${i}` })),
  expected: Array.from({ length: 9 }, (_, i) => ({ vendor: "check_point", text: `Synthetic expected line ${i}` })),
  devices: [
    { device_id: "dev-tango-01", hostname: "FW-TANGO-01", vendor: "check_point", status: "FAIL", observed_value: "synthetic-observed", message: null },
    { device_id: "dev-tango-02", hostname: "FW-TANGO-02", vendor: "check_point", status: "UNKNOWN", observed_value: null, message: "Evidence withheld" },
    { device_id: "dev-tango-03", hostname: "FW-TANGO-03", vendor: "check_point", status: "PASS", observed_value: "synthetic-pass", message: null },
  ],
};

type DetailMode = "ok" | "error" | "malformed";

/** Serves the compliance lens; the per-control detail route answers according to `detail`. */
function stubCompliance(opts: { detail: DetailMode; overview?: Record<string, unknown> }) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices: gateways(3) });
    if (url.startsWith("/api/v2/compliance/controls/")) {
      if (opts.detail === "error") return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
      if (opts.detail === "malformed") return jsonResponse(200, { control_id: "CIS-7.0" });
      return jsonResponse(200, DETAIL);
    }
    if (url === "/compliance/controls") return jsonResponse(200, { total_controls: 1, controls: [CONTROL] });
    if (url === "/compliance/overview") return jsonResponse(200, { ...OVERVIEW, ...(opts.overview ?? {}) });
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

async function openControl() {
  window.history.replaceState(null, "", "/?screen=compliance");
  stubViewport(1280);
  render(<App />);
  const comp = await screen.findByTestId("estate-compliance-screen");
  const row = await waitFor(() => {
    const el = comp.querySelector<HTMLElement>('[data-remediation-row="CIS-7.0"]');
    if (!el) throw new Error("remediation row CIS-7.0 not rendered yet");
    return el;
  });
  fireEvent.click(within(row).getByRole("button", { name: /^Show/ }));
  return { comp, row };
}

describe("Estate Controls lens: expanded control detail", () => {
  it("renders rationale, expected vendor lines, framework mappings, verdict counts and withheld evidence", async () => {
    vi.stubGlobal("fetch", stubCompliance({ detail: "ok" }));
    const { row } = await openControl();

    expect(await within(row).findByTestId("control-detail-rationale")).toHaveTextContent(
      "Synthetic rationale: the example control keeps the management plane authenticated.",
    );
    expect(within(row).getByText("Why it matters")).toBeInTheDocument();

    const expected = within(row).getByTestId("control-detail-expected");
    expect(within(expected).getByText("What it should be")).toBeInTheDocument();
    expect(within(expected).getByText("Check Point: Synthetic expected line 0")).toBeInTheDocument();

    const frameworks = within(row).getByTestId("control-detail-frameworks");
    expect(within(frameworks).getByText("Framework mappings")).toBeInTheDocument();
    expect(within(frameworks).getByText("CIS Benchmark 7.0")).toBeInTheDocument();

    expect(within(row).getByTestId("control-verdict-counts")).toHaveTextContent(
      "1 failing unit · 1 unit without evidence · 1 passing unit",
    );
    expect(within(row).getByText("withheld")).toBeInTheDocument();
    expect(within(row).getAllByText("Check Point").length).toBeGreaterThan(0);
    expect(within(row).getAllByText("FW-TANGO-01").length).toBeGreaterThan(0);
    expect(within(row).queryByTestId("control-detail-failed")).toBeNull();
  });

  it("caps expected lines and framework mappings with a MoreFooter remainder", async () => {
    expect(CONTROL_EXPECTED_CAP).toBe(8);
    expect(CONTROL_FRAMEWORKS_CAP).toBe(8);
    vi.stubGlobal("fetch", stubCompliance({ detail: "ok" }));
    const { row } = await openControl();

    expect(await within(row).findByTestId("control-expected-more")).toHaveTextContent("+1 more expected rules");
    expect(within(row).queryByText("Check Point: Synthetic expected line 8")).toBeNull();
    expect(within(row).getByTestId("control-frameworks-more")).toHaveTextContent("+1 more framework mappings");
  });

  it("shows UNKNOWN and keeps the unit links when the detail read fails", async () => {
    vi.stubGlobal("fetch", stubCompliance({ detail: "error" }));
    const { row } = await openControl();

    const failed = await within(row).findByTestId("control-detail-failed");
    expect(within(failed).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(row).queryByTestId("control-detail-rationale")).toBeNull();
    expect(within(row).queryByTestId("control-detail-expected")).toBeNull();
    expect(within(row).getAllByText("FW-TANGO-01").length).toBeGreaterThan(0);
  });

  it("treats a 200 body without a devices list as a failed read, never as a clean detail", async () => {
    vi.stubGlobal("fetch", stubCompliance({ detail: "malformed" }));
    const { row } = await openControl();

    expect(await within(row).findByTestId("control-detail-failed")).toBeInTheDocument();
    expect(within(row).queryByTestId("control-verdict-counts")).toBeNull();
  });

  it("shows the Scope re-evaluation line when pending_reevaluation is above zero", async () => {
    vi.stubGlobal("fetch", stubCompliance({ detail: "ok", overview: { pending_reevaluation: 2 } }));
    window.history.replaceState(null, "", "/?screen=compliance");
    stubViewport(1280);
    render(<App />);
    const comp = await screen.findByTestId("estate-compliance-screen");
    const line = await within(comp).findByTestId("compliance-pending-reevaluation");
    expect(line).toHaveTextContent("Re-evaluating");
    expect(line).toHaveTextContent("2 firewalls shown with previous evaluation while re-evaluation runs");
  });

  it("omits the re-evaluation line when pending_reevaluation is zero", async () => {
    vi.stubGlobal("fetch", stubCompliance({ detail: "ok", overview: { pending_reevaluation: 0 } }));
    window.history.replaceState(null, "", "/?screen=compliance");
    stubViewport(1280);
    render(<App />);
    const comp = await screen.findByTestId("estate-compliance-screen");
    await within(comp).findByText("Scope");
    expect(within(comp).queryByTestId("compliance-pending-reevaluation")).toBeNull();
  });
});

describe("projectControlDetail", () => {
  it("projects loading, restricted, failed and malformed reads to non-ok states", () => {
    expect(projectControlDetail(undefined).state).toBe("loading");
    expect(projectControlDetail("loading").state).toBe("loading");
    expect(projectControlDetail({ state: "restricted" }).state).toBe("restricted");
    expect(projectControlDetail({ state: "failed" }).state).toBe("failed");
    expect(projectControlDetail({ state: "skipped" }).state).toBe("failed");
    expect(projectControlDetail({ state: "ok", value: { ...DETAIL, devices: undefined } as unknown as ComplianceControlDetail }).state).toBe("failed");
    expect(projectControlDetail({ state: "ok", value: undefined as unknown as ComplianceControlDetail }).state).toBe("failed");
  });

  it("counts verdicts across every device and keeps only non-PASS rows", () => {
    const projected = projectControlDetail({ state: "ok", value: DETAIL });
    expect(projected.state).toBe("ok");
    if (projected.state !== "ok") return;
    expect(projected.verdictCounts).toEqual({ fail: 1, unknown: 1, pass: 1, total: 3 });
    expect(projected.verdicts.map((v) => v.hostname)).toEqual(["FW-TANGO-01", "FW-TANGO-02"]);
    expect(projected.verdicts[1].observedText).toBe("withheld");
    expect(projected.verdicts[1].messageText).toBeNull();
    expect(projected.totalExpected).toBe(9);
    expect(projected.expectedShown).toHaveLength(CONTROL_EXPECTED_CAP);
    expect(projected.frameworksShown[0]).toBe("CIS Benchmark 7.0");
  });
});
