import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

const jsonResponse = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "X-Nexus-Masked": "true" } });

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal("matchMedia", vi.fn((query: string) => {
    const min = query.match(/\(min-width:\s*(\d+)px\)/);
    const max = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
    const matches = min ? widthPx >= Number(min[1]) : max ? widthPx <= Number(max[1]) : false;
    return { matches, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn() };
  }));
}

const activeVersion = {
  version_id: "v3", number: 3, state: "ACTIVE", revision: 4, content_sha256: "1".repeat(64),
  created_at: "2026-11-19T10:00:00Z", updated_at: null, published_by: null, published_at: new Date(Date.now() - 2 * 86_400_000).toISOString(),
  zones: [
    { zone_id: "z0", name: "ZONE-TANGO-A", description: null, role: null, block_count: 1, effective_addresses: { IPV4: "256", IPV6: "0" }, blocks: null },
    { zone_id: "z1", name: "ZONE-TANGO-B", description: null, role: null, block_count: 1, effective_addresses: { IPV4: "256", IPV6: "0" }, blocks: null },
  ],
  unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [],
  matrix: {
    default_statement: "DENY_ALL", default_severity: "LOW", unassigned_to_unassigned: "DENY_ALL", template_origin: null, service_sets: [],
    cells: [{ source: "z0", destination: "z1", source_name: "ZONE-TANGO-A", destination_name: "ZONE-TANGO-B", statement: "ALLOW_ANY", services: [], severity: "CRITICAL", rationale: null, regulation_reference: null, reference_status: "EMPTY", confirmed_by: null, confirmed_at: null, cell_sha256: "2".repeat(64) }],
    cells_total: 1, cells_with_reference: 0, cells_confirmed: 0,
  },
};

function stubFetch(zoneVersionsStatus: number) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (init?.method && init.method !== "GET" && !url.endsWith("/evaluate")) throw new Error(`write call ${url}`);
    if (url === "/api/v2/zone-matrix/policies") {
      return jsonResponse(200, { policies: [{ id: "p1", name: "POL-22", vendor: "PAN", source_id: null, source_name: null, container_id: null, container_name: null, collected_at: null, rule_count: 3, target_count: 1 }], not_evaluated: [], can: { view_blocks: false, edit: false, publish: false, evaluate: true } });
    }
    if (url === "/api/v2/zone-matrix/evaluate") {
      return jsonResponse(200, { evaluated: true, coverage: "COMPLETE", outcome_counts: { VIOLATION: 0 }, version_number: 3, collected_at: new Date().toISOString(), coverage_items: [],
        headline: { distinct_violating_rules: 0, violated_zone_pairs: 0, unknown_rules: 0, not_evaluable_rules: 0 } });
    }
    if (url === "/api/v2/zone-matrix/versions") {
      if (zoneVersionsStatus !== 200) return jsonResponse(zoneVersionsStatus, { error: "unavailable" });
      return jsonResponse(200, { versions: [{ ...activeVersion, zones: undefined, matrix: undefined }], can: { view_blocks: false, edit: false, publish: false } });
    }
    if (url === "/api/v2/zone-matrix/versions/v3?blocks=0") return jsonResponse(200, { ...activeVersion, can: { view_blocks: false, edit: false, publish: false } });
    throw new Error(`unexpected ${url}`);
  });
}

describe("EstateZoneMatrixGrid inside the Ask zones mode", () => {
  beforeEach(() => {
    resetSharedApiCache();
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace&mode=zones");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    resetSharedApiCache();
  });

  it("renders the grid read-only with only the standard-view link as an action", async () => {
    vi.stubGlobal("fetch", stubFetch(200));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    const grid = await within(ask).findByTestId("zone-matrix-grid");
    await waitFor(() => expect(within(grid).getByText("Version 3 · 2 d ago")).toBeInTheDocument());
    expect(within(grid).getByRole("table", { name: /Statement by source and destination zone/ })).toBeInTheDocument();
    expect(within(grid).getByText("Allow any")).toBeInTheDocument();
    expect(within(grid).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
    const buttons = within(grid).queryAllByRole("button");
    expect(buttons).toHaveLength(0);
    const link = within(grid).getByRole("link", { name: /Open in standard view/ });
    expect(link).toHaveAttribute("href", "?screen=zone_matrix&standard=1");
  });

  it("shows a failed state for the matrix while the Ask answer is still rendered", async () => {
    vi.stubGlobal("fetch", stubFetch(500));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    fireEvent.change(await within(ask).findByRole("combobox", { name: /Policy to evaluate/ }), { target: { value: "p1" } });
    await waitFor(() => expect(within(ask).getByRole("option", { name: /POL-22/ })).toBeInTheDocument());
    fireEvent.click(within(ask).getByTestId("ask-submit"));
    expect(await within(ask).findByTestId("ask-answer")).toHaveAttribute("data-ask-state", "ok");
    expect(await within(ask).findByTestId("zone-matrix-grid-failed")).toHaveTextContent("The active zone matrix could not be read");
    expect(within(ask).queryByTestId("zone-matrix-grid")).toHaveTextContent("The answer above is unaffected");
  });
});
