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
    { zone_id: "z0", name: "ZONE-TANGO-A", description: null, role: "DMZ", block_count: 1, effective_addresses: { IPV4: "256", IPV6: "0" }, blocks: null },
    { zone_id: "z1", name: "ZONE-TANGO-B", description: null, role: null, block_count: 3, effective_addresses: { IPV4: "1024", IPV6: "64" }, blocks: null },
  ],
  unassigned_addresses: { IPV4: "4096", IPV6: "128" }, redundant_blocks: 0, revisions: [],
  matrix: {
    default_statement: "DENY_ALL", default_severity: "LOW", unassigned_to_unassigned: "DENY_ALL", template_origin: null, service_sets: [],
    cells: [{ source: "z0", destination: "z1", source_name: "ZONE-TANGO-A", destination_name: "ZONE-TANGO-B", statement: "ALLOW_ANY", services: [], severity: "CRITICAL", rationale: null, regulation_reference: null, reference_status: "EMPTY", confirmed_by: null, confirmed_at: null, cell_sha256: "2".repeat(64) }],
    cells_total: 1, cells_with_reference: 0, cells_confirmed: 0,
  },
};

function stubFetch(zoneVersionsStatus: number, versionOverride: Record<string, unknown> = {}) {
  const versionDetail = { ...activeVersion, ...versionOverride };
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
      return jsonResponse(200, { versions: [{ ...versionDetail, zones: undefined, matrix: undefined }], can: { view_blocks: false, edit: false, publish: false } });
    }
    if (url === "/api/v2/zone-matrix/versions/v3?blocks=0") return jsonResponse(200, { ...versionDetail, can: { view_blocks: false, edit: false, publish: false } });
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
    expect(grid.querySelectorAll("button")).toHaveLength(0);
    const link = within(grid).getByRole("link", { name: /Open in standard view/ });
    expect(link).toHaveAttribute("href", "?screen=zone_matrix&standard=1");
  });

  it("renders each zone's role, block count, IPv4/IPv6 effective address counts, and the unassigned space line", async () => {
    vi.stubGlobal("fetch", stubFetch(200));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    const grid = await within(ask).findByTestId("zone-matrix-grid");
    await waitFor(() => expect(within(grid).getByText("2 zones in this version")).toBeInTheDocument());

    const inventoryTable = within(grid).getByRole("table", { name: /Zones in this version/ });
    const rows = within(inventoryTable).getAllByTestId("zone-inventory-row");
    expect(rows).toHaveLength(2);

    expect(within(rows[0]).getByText("ZONE-TANGO-A")).toBeInTheDocument();
    expect(within(rows[0]).getByText("DMZ")).toBeInTheDocument();
    expect(within(rows[0]).getByText("1 block")).toBeInTheDocument();
    expect(within(rows[0]).getByText("256")).toBeInTheDocument();
    expect(within(rows[0]).getByText("0")).toBeInTheDocument();

    expect(within(rows[1]).getByText("ZONE-TANGO-B")).toBeInTheDocument();
    expect(within(rows[1]).getByText("3 blocks")).toBeInTheDocument();
    expect(within(rows[1]).getByText("1,024")).toBeInTheDocument();
    expect(within(rows[1]).getByText("64")).toBeInTheDocument();

    expect(within(grid).getByTestId("zone-unassigned-space")).toHaveTextContent("Unassigned space: IPv4 4,096 · IPv6 128");
  });

  it("renders UNKNOWN for a zone with role: null, never OK or a blank cell", async () => {
    vi.stubGlobal("fetch", stubFetch(200));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    const grid = await within(ask).findByTestId("zone-matrix-grid");
    const inventoryTable = await within(grid).findByRole("table", { name: /Zones in this version/ });
    const rows = within(inventoryTable).getAllByTestId("zone-inventory-row");

    const nullRoleRow = rows[1];
    const cells = nullRoleRow.querySelectorAll("th, td");
    expect(cells[1]).toHaveTextContent("UNKNOWN");
    for (const c of Array.from(cells)) {
      expect((c.textContent ?? "").trim()).not.toBe("");
      expect(c.textContent ?? "").not.toMatch(/\bOK\b|\bNone\b/);
    }
  });

  it("caps the zone inventory list at 12 when there are more than 12 zones and prints +N more zones", async () => {
    const manyZones = Array.from({ length: 15 }, (_, i) => ({
      zone_id: `z${i}`,
      name: `ZONE-TANGO-${String.fromCharCode(65 + i)}`,
      description: null,
      role: i % 2 === 0 ? "USER" : null,
      block_count: i + 1,
      effective_addresses: { IPV4: "256", IPV6: "0" },
      blocks: null,
    }));
    vi.stubGlobal("fetch", stubFetch(200, { zones: manyZones }));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    const grid = await within(ask).findByTestId("zone-matrix-grid");
    await waitFor(() => expect(within(grid).getByText("15 zones in this version")).toBeInTheDocument());

    const inventoryTable = within(grid).getByRole("table", { name: /Zones in this version/ });
    const rows = within(inventoryTable).getAllByTestId("zone-inventory-row");
    expect(rows).toHaveLength(12);
    expect(within(grid).getByTestId("zone-inventory-more")).toHaveTextContent("+3 more zones");
  });

  it("renders the overlap warning with a watch badge when redundant_blocks is non-zero and no warning when zero", async () => {
    vi.stubGlobal("fetch", stubFetch(200, { redundant_blocks: 2 }));
    stubViewport(1280);
    const { unmount } = render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    const grid = await within(ask).findByTestId("zone-matrix-grid");
    const warning = await within(grid).findByTestId("zone-overlap-warning");
    expect(warning).toHaveTextContent("Overlap warning: 2 block(s) repeat or sit inside another block of the same zone");
    expect(warning.querySelector('[data-state-badge="watch"]')).not.toBeNull();
    expect(grid.querySelectorAll("button")).toHaveLength(0);
    unmount();

    resetSharedApiCache();
    vi.stubGlobal("fetch", stubFetch(200, { redundant_blocks: 0 }));
    render(<App />);
    const askZero = await screen.findByTestId("estate-ask-screen");
    const gridZero = await within(askZero).findByTestId("zone-matrix-grid");
    await waitFor(() => expect(within(gridZero).getByText("Version 3 · 2 d ago")).toBeInTheDocument());
    expect(within(gridZero).queryByTestId("zone-overlap-warning")).toBeNull();
    expect(within(gridZero).queryByText(/Overlap warning:/)).toBeNull();
    expect(gridZero.querySelectorAll("button")).toHaveLength(0);
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

  it("renders confirmation coverage, UNASSIGNED -> UNASSIGNED policy, template origin, ALLOW_LISTED cell services/confirmed actor, and capped service sets without buttons", async () => {
    const serviceSets = Array.from({ length: 12 }, (_, idx) => ({
      name: `SET-TANGO-${idx + 1}`,
      entries:
        idx === 0
          ? [
              { entry: "tcp/443", evaluable: true, not_evaluable_reason: null },
              { entry: "app:pan:ssl", evaluable: false, not_evaluable_reason: "APP_ID_DYNAMIC" },
            ]
          : [{ entry: "udp/53", evaluable: true, not_evaluable_reason: null }],
    }));
    const richMatrix = {
      default_statement: "DENY_ALL",
      default_severity: "LOW",
      unassigned_to_unassigned: "DENY_ALL unless an explicit cell says otherwise",
      template_origin: { name: "PCI-DSS-BASELINE", revision: "2026.2" },
      service_sets: serviceSets,
      cells: [
        {
          source: "z0",
          destination: "z1",
          source_name: "ZONE-TANGO-A",
          destination_name: "ZONE-TANGO-B",
          statement: "ALLOW_LISTED",
          services: [
            { entry: "tcp/443", evaluable: true, not_evaluable_reason: null },
            { entry: "app:pan:ssl", evaluable: false, not_evaluable_reason: "APP_ID_DYNAMIC" },
          ],
          severity: "HIGH",
          rationale: null,
          regulation_reference: null,
          reference_status: "CONFIRMED",
          confirmed_by: "SN-0123456789",
          confirmed_at: "2026-11-20T09:00:00Z",
          cell_sha256: "2".repeat(64),
        },
      ],
      cells_total: 2,
      cells_with_reference: 1,
      cells_confirmed: 1,
    };

    vi.stubGlobal("fetch", stubFetch(200, { matrix: richMatrix }));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    const grid = await within(ask).findByTestId("zone-matrix-grid");
    await waitFor(() =>
      expect(within(grid).getByTestId("zone-matrix-confirmation")).toHaveTextContent(
        "1 of 2 cells confirmed by compliance (1 cell with reference)",
      ),
    );
    expect(within(grid).getByTestId("zone-unassigned-policy")).toHaveTextContent(
      "UNASSIGNED -> UNASSIGNED: DENY_ALL unless an explicit cell says otherwise",
    );
    expect(within(grid).getByTestId("zone-template-origin")).toHaveTextContent(
      "Template origin: PCI-DSS-BASELINE (2026.2)",
    );
    expect(within(grid).getByTestId("zone-cell-services")).toHaveTextContent(
      "2 services: tcp/443, app:pan:ssl (not evaluable: APP_ID_DYNAMIC)",
    );
    expect(within(grid).getByTestId("zone-cell-confirmed")).toHaveTextContent("Confirmed by Masked actor");

    const setsBlock = within(grid).getByTestId("zone-service-sets");
    expect(setsBlock).toHaveTextContent("12 service sets in this version");
    const setRows = within(setsBlock).getAllByTestId("zone-service-set-row");
    expect(setRows).toHaveLength(10);
    expect(setRows[0]).toHaveTextContent("SET-TANGO-1");
    expect(setRows[0]).toHaveTextContent("2 services");
    expect(setRows[0]).toHaveTextContent("tcp/443, app:pan:ssl (not evaluable: APP_ID_DYNAMIC)");
    expect(within(setsBlock).getByTestId("zone-service-sets-more")).toHaveTextContent("+2 more service sets");
    expect(grid.querySelectorAll("button")).toHaveLength(0);
  });
});
