import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { looseningText } from "../src/screens/zonematrix/VersionsTab";
import { zoneErrorLines, type ZoneMatrixCan, type ZoneMatrixView, type ZoneVersionDetail } from "../src/screens/zonematrix/zoneMatrixApi";

const HASH_ACTIVE = "1111111111111111111111111111111111111111111111111111111111111111";
const HASH_DRAFT = "2222222222222222222222222222222222222222222222222222222222222222";
const HASH_CELL_1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const HASH_CELL_2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

const sizes = (ipv4: string, ipv6 = "0") => ({ IPV4: ipv4, IPV6: ipv6 });

const matrixFixture: ZoneMatrixView = {
  default_statement: "ALLOW_LISTED",
  default_severity: "MEDIUM",
  unassigned_to_unassigned: "DENY_ALL",
  template_origin: { name: "Overwatch segmentation template (proposal)", revision: "2026-11-13.2" },
  service_sets: [
    {
      name: "web-tls",
      entries: [
        { entry: "tcp/443", evaluable: true, not_evaluable_reason: null },
        { entry: "icmp/8", evaluable: false, not_evaluable_reason: "ICMP is evaluated after hygiene model R1" },
      ],
    },
  ],
  cells: [
    {
      source: "z1",
      destination: "z2",
      source_name: "Z-APP",
      destination_name: "Z-DB",
      statement: "ALLOW_LISTED",
      services: [
        { entry: "tcp/5432", evaluable: true, not_evaluable_reason: null },
        { entry: "icmp/8", evaluable: false, not_evaluable_reason: "ICMP is evaluated after hygiene model R1" },
      ],
      severity: "HIGH",
      rationale: "Database queries from application tier",
      regulation_reference: "SEC-POL-4.2",
      reference_status: "PROPOSED",
      confirmed_by: null,
      confirmed_at: null,
      cell_sha256: HASH_CELL_1,
    },
    {
      source: "z3",
      destination: "z2",
      source_name: "Z-DMZ",
      destination_name: "Z-DB",
      statement: "DENY_ALL",
      services: [],
      severity: "CRITICAL",
      rationale: "DMZ never reaches database directly",
      regulation_reference: "SEC-POL-1.1",
      reference_status: "CONFIRMED",
      confirmed_by: "USER-1",
      confirmed_at: "2026-11-14T08:00:00Z",
      cell_sha256: HASH_CELL_2,
    },
    {
      source: "z1",
      destination: "z1",
      source_name: "Z-APP",
      destination_name: "Z-APP",
      statement: "ALLOW_ANY",
      services: [],
      severity: "LOW",
      rationale: null,
      regulation_reference: "",
      reference_status: "EMPTY",
      confirmed_by: null,
      confirmed_at: null,
      cell_sha256: HASH_ACTIVE,
    },
  ],
  cells_total: 3,
  cells_with_reference: 2,
  cells_confirmed: 1,
};

const baseZones = [
  { zone_id: "z1", name: "Z-APP", description: "Application tier", role: "APPLICATION", block_count: 1, blocks: null, effective_addresses: sizes("256") },
  { zone_id: "z2", name: "Z-DB", description: "Database tier", role: "DATABASE", block_count: 1, blocks: null, effective_addresses: sizes("256") },
  { zone_id: "z3", name: "Z-DMZ", description: "Perimeter tier", role: "DMZ", block_count: 1, blocks: null, effective_addresses: sizes("256") },
];

const activeVersion: ZoneVersionDetail = {
  version_id: "v-active",
  number: 3,
  state: "ACTIVE",
  revision: 4,
  content_sha256: HASH_ACTIVE,
  created_at: "2026-11-13T09:00:00Z",
  updated_at: "2026-11-13T10:00:00Z",
  published_by: "USER-2",
  published_at: "2026-11-13T10:00:00Z",
  zones: baseZones,
  unassigned_addresses: sizes("4294966528"),
  redundant_blocks: 0,
  revisions: [],
  matrix: matrixFixture,
};

const draftVersion: ZoneVersionDetail = {
  ...activeVersion,
  version_id: "v-draft",
  number: null,
  state: "DRAFT",
  revision: 5,
  content_sha256: HASH_DRAFT,
  published_by: null,
  published_at: null,
};

const templateFixture = {
  name: "Overwatch segmentation template (proposal)",
  revision: "2026-11-13.2",
  basis: "Overwatch proposal based on general segmentation practice, not on a BDDK clause",
  roles: ["DMZ", "APPLICATION", "DATABASE"],
  can: { view_blocks: true, edit: true, publish: true, confirm_reference: true },
  rows: [
    { row: "DMZ -> APPLICATION", statement: "ALLOW_LISTED", severity: "HIGH" },
    { row: "DMZ -> DATABASE", statement: "DENY_ALL", severity: "CRITICAL" },
  ],
};

interface CallRecord {
  readonly method: string;
  readonly path: string;
  readonly body: unknown;
}

const FULL_CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true };
const READ_CAN: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, confirm_reference: false };

function mockServer(opts: {
  readonly can?: ZoneMatrixCan;
  readonly active?: ZoneVersionDetail | null;
  readonly draft?: ZoneVersionDetail | null;
  readonly diff?: unknown;
  readonly publishCheck?: { readonly eligible: boolean; readonly reason: string | null };
  readonly writes?: Record<string, { readonly status: number; readonly body: unknown }>;
} = {}): CallRecord[] {
  const can = opts.can ?? FULL_CAN;
  const active = opts.active === undefined ? activeVersion : opts.active;
  const draft = opts.draft === undefined ? (can.edit || can.publish ? draftVersion : null) : opts.draft;
  const calls: CallRecord[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    const rawBody = typeof init?.body === "string" ? JSON.parse(init.body) : null;
    calls.push({ method, path, body: rawBody });
    const writeReply = opts.writes?.[`${method} ${path}`];
    if (writeReply) {
      return new Response(JSON.stringify(writeReply.body), {
        status: writeReply.status,
        headers: { "Content-Type": "application/json" },
      });
    }
    if (method === "GET" && path.startsWith("/api/v2/zone-matrix/draft")) {
      return new Response(JSON.stringify({
        active: active ? {
          version_id: active.version_id,
          number: active.number,
          state: active.state,
          revision: active.revision,
          content_sha256: active.content_sha256,
          published_by: active.published_by,
          published_at: active.published_at,
          zone_count: active.zones.length,
        } : null,
        draft,
        diff: opts.diff ?? null,
        publish_check: opts.publishCheck ?? { eligible: true, reason: null },
        can,
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    if (method === "GET" && path.startsWith("/api/v2/zone-matrix/versions/")) {
      return new Response(JSON.stringify({ ...(active ?? draftVersion), can }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions") {
      return new Response(JSON.stringify({
        versions: active ? [{
          version_id: active.version_id,
          number: active.number,
          state: active.state,
          revision: active.revision,
          content_sha256: active.content_sha256,
          published_by: active.published_by,
          published_at: active.published_at,
          zone_count: active.zones.length,
        }] : [],
        can,
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/template") {
      return new Response(JSON.stringify({ ...templateFixture, can }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }
    return new Response(JSON.stringify({
      version_id: "v-draft",
      revision: 6,
      content_sha256: HASH_DRAFT,
      zone_id: null,
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  }));
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Zone matrix step 2: Matrix tab and Versions additions", () => {
  it("renders statements as shape and text with default marks and UNASSIGNED -> UNASSIGNED as DENY_ALL", async () => {
    mockServer();
    render(<ZoneMatrixScreen />);
    const header = await screen.findByTestId("matrix-header");
    expect(within(header).getByText(/1 of 3 cells confirmed by compliance/)).toBeInTheDocument();
    expect(within(header).getByTestId("template-origin")).toHaveTextContent("Overwatch segmentation template (proposal) (2026-11-13.2)");

    const allowListedCell = screen.getByTestId("matrix-cell-z1-z2");
    expect(allowListedCell).toHaveAttribute("data-statement", "ALLOW_LISTED");
    expect(allowListedCell).toHaveAttribute("data-explicit", "true");
    expect(allowListedCell.querySelector("[data-shape='list-mark']")).not.toBeNull();
    expect(within(allowListedCell).getByText("Allow listed (2)")).toBeInTheDocument();
    expect(within(allowListedCell).getByText(/High · explicit \(proposed\)/)).toBeInTheDocument();

    const denyAllCell = screen.getByTestId("matrix-cell-z3-z2");
    expect(denyAllCell.querySelector("[data-shape='closed-bar']")).not.toBeNull();
    expect(within(denyAllCell).getByText("Deny all")).toBeInTheDocument();

    const allowAnyCell = screen.getByTestId("matrix-cell-z1-z1");
    expect(allowAnyCell.querySelector("[data-shape='open-mark']")).not.toBeNull();
    expect(within(allowAnyCell).getByText("Allow any")).toBeInTheDocument();

    const defaultCell = screen.getByTestId("matrix-cell-z1-z3");
    expect(defaultCell).toHaveAttribute("data-explicit", "false");
    expect(within(defaultCell).getByText(/Medium · default/)).toBeInTheDocument();

    const unassignedSelf = screen.getByTestId("matrix-cell-UNASSIGNED-UNASSIGNED");
    expect(unassignedSelf).toHaveAttribute("data-statement", "DENY_ALL");
    expect(unassignedSelf.querySelector("[data-shape='closed-bar']")).not.toBeNull();
    expect(within(unassignedSelf).getByText("Deny all")).toBeInTheDocument();
    expect(within(unassignedSelf).getByText(/Medium · default/)).toBeInTheDocument();
  });

  it("shows ZONE-n, SET-n, USER-n and 'hidden in the masked view' in the masked view", async () => {
    const maskedVersion: ZoneVersionDetail = {
      ...activeVersion,
      zones: [
        { zone_id: "z1", name: "ZONE-1", description: null, role: "APPLICATION", block_count: 1, blocks: null, effective_addresses: sizes("256") },
        { zone_id: "z2", name: "ZONE-2", description: null, role: "DATABASE", block_count: 1, blocks: null, effective_addresses: sizes("256") },
      ],
      matrix: {
        ...matrixFixture,
        service_sets: [
          { name: "SET-1", entries: [{ entry: "tcp/443", evaluable: true, not_evaluable_reason: null }] },
        ],
        cells: [
          {
            source: "z1",
            destination: "z2",
            source_name: "ZONE-1",
            destination_name: "ZONE-2",
            statement: "ALLOW_LISTED",
            services: [{ entry: "set:SET-1", evaluable: true, not_evaluable_reason: null }],
            severity: "HIGH",
            rationale: null,
            regulation_reference: null,
            reference_status: "CONFIRMED",
            confirmed_by: "USER-1",
            confirmed_at: "2026-11-14T08:00:00Z",
            cell_sha256: HASH_CELL_1,
          },
        ],
      },
    };
    mockServer({ can: READ_CAN, active: maskedVersion, draft: null });
    render(<ZoneMatrixScreen />);
    await screen.findByTestId("matrix-cell-z1-z2");
    expect(screen.getAllByText("ZONE-1").length).toBeGreaterThan(0);
    expect(screen.getAllByText("ZONE-2").length).toBeGreaterThan(0);
    expect(screen.getByText("SET-1")).toBeInTheDocument();

    const detail = screen.getByTestId("cell-detail");
    expect(within(detail).getAllByText("hidden in the masked view").length).toBe(2);
    expect(within(detail).getByText("USER-1")).toBeInTheDocument();
  });

  it("hides all edit and confirmation controls when can.edit and can.confirm_reference are false", async () => {
    mockServer({ can: READ_CAN, draft: null });
    render(<ZoneMatrixScreen />);
    const detail = await screen.findByTestId("cell-detail");
    expect(within(detail).getByText(/ICMP is evaluated after hygiene model R1/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save cell" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Use the version default" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save default" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save service set" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Confirm reference" })).not.toBeInTheDocument();
    expect(screen.queryByText(/draft, not evaluated/i)).not.toBeInTheDocument();
  });

  it("edits a cell, shows per-line service errors on INVALID_SERVICES, and clears a cell to the version default", async () => {
    const calls = mockServer({
      writes: {
        "POST /api/v2/zone-matrix/draft/cells": {
          status: 400,
          body: {
            error: "INVALID_SERVICES",
            reason: "one or more services are invalid",
            errors: [
              { index: 0, reason: "must be lowercase canonical form" },
              { index: 1, reason: "single port must not be a range" },
            ],
          },
        },
      },
    });
    render(<ZoneMatrixScreen />);
    const detail = await screen.findByTestId("cell-detail");
    expect(within(detail).getByText("Changing the statement, services, severity or reference resets a confirmed reference to proposed.")).toBeInTheDocument();

    fireEvent.change(within(detail).getByLabelText("Services"), { target: { value: "TCP/443\ntcp/443-443" } });
    fireEvent.click(within(detail).getByRole("button", { name: "Save cell" }));
    const alert = await within(detail).findByRole("alert");
    expect(within(alert).getByText("Line 1 (TCP/443): must be lowercase canonical form")).toBeInTheDocument();
    expect(within(alert).getByText("Line 2 (tcp/443-443): single port must not be a range")).toBeInTheDocument();

    fireEvent.click(within(detail).getByRole("button", { name: "Use the version default" }));
    expect(await screen.findByText("Cell Z-APP -> Z-DB now uses the version default.")).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v2/zone-matrix/draft/cells/clear")?.body).toEqual({
      revision: 5,
      source: "z1",
      destination: "z2",
    });
  });

  it("offers only DENY_ALL and ALLOW_LISTED for the version default and saves service sets", async () => {
    const calls = mockServer({
      writes: {
        "POST /api/v2/zone-matrix/draft/service-sets/delete": {
          status: 409,
          body: { error: "SERVICE_SET_IN_USE", reason: "a cell uses this service set; change the cell first" },
        },
      },
    });
    render(<ZoneMatrixScreen />);
    const defaultSection = await screen.findByRole("region", { name: "Version default" });
    fireEvent.mouseDown(within(defaultSection).getByLabelText("Default statement"));
    const listbox = await screen.findByRole("listbox");
    expect(within(listbox).getByRole("option", { name: /Deny all/i })).toBeInTheDocument();
    expect(within(listbox).getByRole("option", { name: /Allow listed/i })).toBeInTheDocument();
    expect(within(listbox).queryByRole("option", { name: /Allow any/i })).not.toBeInTheDocument();
    fireEvent.click(within(listbox).getByRole("option", { name: /Deny all/i }));
    fireEvent.click(within(defaultSection).getByRole("button", { name: "Save default" }));
    expect(await screen.findByText("Version default saved as Deny all (Medium).")).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v2/zone-matrix/draft/default")?.body).toEqual({
      revision: 5,
      statement: "DENY_ALL",
      severity: "MEDIUM",
    });

    const setsSection = screen.getByRole("region", { name: "Service sets" });
    fireEvent.change(within(setsSection).getByLabelText("Service set name"), { target: { value: "db-ports" } });
    fireEvent.change(within(setsSection).getByLabelText("Service set entries"), { target: { value: "tcp/5432" } });
    fireEvent.click(within(setsSection).getByRole("button", { name: "Save service set" }));
    expect(await screen.findByText("Service set db-ports saved to the draft.")).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v2/zone-matrix/draft/service-sets")?.body).toEqual({
      revision: 5,
      name: "db-ports",
      entries: ["tcp/5432"],
    });

    fireEvent.click(within(setsSection).getByRole("button", { name: "Delete set web-tls" }));
    expect(await within(setsSection).findByRole("alert")).toHaveTextContent("A cell uses this service set; change the cell first.");
  });

  it("asks 'Replace all N cells?' on TEMPLATE_REPLACES_CELLS and resends with replace: true", async () => {
    const calls: CallRecord[] = [];
    let importCalls = 0;
    mockServer();
    const baseFetch = globalThis.fetch;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const method = (init?.method ?? "GET").toUpperCase();
      if (method === "POST" && path === "/api/v2/zone-matrix/draft/template-import") {
        const body = typeof init?.body === "string" ? JSON.parse(init.body) : null;
        calls.push({ method, path, body });
        importCalls += 1;
        if (importCalls === 1) {
          return new Response(JSON.stringify({
            error: "TEMPLATE_REPLACES_CELLS",
            reason: "the draft already has cells; importing the template replaces them all",
          }), { status: 409, headers: { "Content-Type": "application/json" } });
        }
        return new Response(JSON.stringify({
          version_id: "v-draft",
          revision: 6,
          content_sha256: HASH_DRAFT,
          zone_id: null,
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return baseFetch(input, init);
    }));

    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Segmentation template" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByTestId("template-basis")).toHaveTextContent(
      "Overwatch proposal based on general segmentation practice, not on a BDDK clause",
    );
    expect(within(dialog).getByText("DMZ -> APPLICATION")).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: "Import template" }));
    expect(await within(dialog).findByText("Replace all 3 cells?")).toBeInTheDocument();
    expect(calls[0]?.body).toEqual({ revision: 5, replace: false });

    fireEvent.click(within(dialog).getByRole("button", { name: "Replace all cells" }));
    expect(await screen.findByText("Segmentation template imported into the draft. Every reference field is empty.")).toBeInTheDocument();
    expect(calls[1]?.body).toEqual({ revision: 5, replace: true });
  });

  it("confirms a proposed reference with cell_sha256 and shows every refusal sentence", async () => {
    const calls = mockServer({
      writes: {
        "POST /api/v2/zone-matrix/draft/cells/confirm-reference": {
          status: 403,
          body: { error: "COMPLIANCE_ROLE_REQUIRED", reason: "only a compliance administrator confirms a reference" },
        },
      },
    });
    render(<ZoneMatrixScreen />);
    const confirmBox = await screen.findByTestId("confirm-reference-box");
    expect(within(confirmBox).getByText("Confirming makes you an editor of this draft; another person must publish it.")).toBeInTheDocument();
    fireEvent.click(within(confirmBox).getByRole("button", { name: "Confirm reference" }));

    const detail = screen.getByTestId("cell-detail");
    expect(await within(detail).findByRole("alert")).toHaveTextContent("Only a compliance administrator confirms a reference.");
    expect(calls.find((c) => c.path === "/api/v2/zone-matrix/draft/cells/confirm-reference")?.body).toEqual({
      revision: 5,
      source: "z1",
      destination: "z2",
      cell_sha256: HASH_CELL_1,
    });

    expect(zoneErrorLines({ status: 403, body: { error: "COMPLIANCE_ROLE_REQUIRED" } })).toEqual([
      "Only a compliance administrator confirms a reference.",
    ]);
    expect(zoneErrorLines({ status: 409, body: { error: "REFERENCE_EMPTY" } })).toEqual([
      "There is no reference to confirm.",
    ]);
    expect(zoneErrorLines({ status: 409, body: { error: "ALREADY_CONFIRMED" } })).toEqual([
      "This reference is already confirmed.",
    ]);
    expect(zoneErrorLines({ status: 409, body: { error: "REQUEST_CHANGED" } })).toEqual([
      "The draft changed since you loaded it. The latest draft is loaded now; check it and try again.",
    ]);
    expect(zoneErrorLines({ status: 404, body: { error: "CELL_NOT_FOUND" } })).toEqual([
      "This pair has no explicit cell.",
    ]);
  });

  it("lists loosening changes first on Versions tab and makes rationale mandatory when rationale_required is true", async () => {
    const step2Diff = {
      against_version_id: "v-active",
      against_number: 3,
      empty: false,
      zones: [],
      moves: [],
      default: {
        statement_before: "DENY_ALL",
        statement_after: "ALLOW_LISTED",
        severity_before: "HIGH",
        severity_after: "MEDIUM",
      },
      cells: [
        {
          source: "z1",
          destination: "z2",
          source_name: "Z-APP",
          destination_name: "Z-DB",
          change: "CHANGED",
          before: { statement: "DENY_ALL", services: [], severity: "CRITICAL", reference_status: "CONFIRMED", explicit: true },
          after: { statement: "ALLOW_LISTED", services: ["tcp/5432"], severity: "HIGH", reference_status: "PROPOSED", explicit: true },
        },
      ],
      loosening: [
        { kind: "DEFAULT_CHANGED" },
        { kind: "STATEMENT_RELAXED", source_name: "Z-APP", destination_name: "Z-DB" },
        { kind: "SERVICES_ADDED", source_name: "Z-APP", destination_name: "Z-DB" },
        { kind: "SEVERITY_DOWNGRADED", source_name: "Z-APP", destination_name: "Z-DB" },
        { kind: "ADDRESSES_MOVED", source_name: "Z-DB", destination_name: "Z-APP", family: "IPV4", addresses: "256" },
        { kind: "UNASSIGNED_SHRUNK", destination_name: "Z-DMZ", family: "IPV4", addresses: "128" },
      ],
      rationale_required: true,
    };
    mockServer({ diff: step2Diff });
    render(<ZoneMatrixScreen />);
    await screen.findByTestId("matrix-header");
    fireEvent.click(screen.getByRole("tab", { name: "Versions" }));

    const list = await screen.findByRole("list", { name: "Loosening changes" });
    expect(within(list).getByText(/the version default changed/i)).toBeInTheDocument();
    expect(within(list).getByText(/Z-APP -> Z-DB: statement relaxed/i)).toBeInTheDocument();
    expect(within(list).getByText(/Z-APP -> Z-DB: services added/i)).toBeInTheDocument();
    expect(within(list).getByText(/Z-APP -> Z-DB: severity lowered/i)).toBeInTheDocument();
    expect(within(list).getByText(/256 addresses move from Z-DB to Z-APP/i)).toBeInTheDocument();
    expect(within(list).getByText(/128 unassigned addresses move into Z-DMZ, which is less strict/i)).toBeInTheDocument();

    const rationale = screen.getByLabelText(/Rationale/);
    expect(rationale).toBeRequired();
    expect(screen.getByRole("button", { name: "Publish draft" })).toBeDisabled();
    fireEvent.change(rationale, { target: { value: "Approved segmentation update" } });
    expect(screen.getByRole("button", { name: "Publish draft" })).toBeEnabled();

    expect(looseningText({ kind: "DEFAULT_CHANGED" })).toBe("the version default changed");
  });

  it("never renders compliant, BDDK, pass, score, percent or green in the Matrix tab", async () => {
    mockServer();
    const { container } = render(<ZoneMatrixScreen />);
    await screen.findByTestId("matrix-header");
    expect(container.textContent ?? "").not.toMatch(/compliant|bddk|\bpass(ed)?\b|score|%|green/i);
  });
});
