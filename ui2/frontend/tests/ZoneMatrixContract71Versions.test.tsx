import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { REF_CELLS_CAP, buildResolvedByVersionGroups } from "../src/screens/zonematrix/VersionsTab";
import type {
  MatrixCellView, ReferenceStatus, SummaryResolvedFinding, ZoneDiff, ZoneMatrixCan, ZoneMatrixSummaryResponse,
  ZoneMatrixView, ZoneVersionDetail, ZoneVersionSummary,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Contract 7.1 conformance pins for the Versions tab (docs/design/ZONE_MATRIX_CONTRACT.md sections 7.1, 3.2, 3.4, 3.5, 6.1, 7.2).
 * Synthetic names and RFC 5737 documentation ranges only.
 */
const HASH_ACTIVE = "a".repeat(64);
const HASH_RETIRED = "b".repeat(64);
const HASH_DRAFT = "c".repeat(64);
const HASH_CELL_PROPOSED = "d".repeat(64);
const HASH_CELL_CONFIRMED = "e".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";

const COMPLIANCE_CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true };
const SECURITY_NO_CONFIRM_CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: false };
const VIEWER_CAN: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, confirm_reference: false };

const activeSummary: ZoneVersionSummary = {
  version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH_ACTIVE,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z",
};

const retiredMissingMetaSummary: ZoneVersionSummary = {
  version_id: "v-retired", number: 2, state: "RETIRED", revision: 2, content_sha256: "",
  created_at: "2026-11-08T08:00:00Z", updated_at: "2026-11-09T08:00:00Z", published_by: null, published_at: null,
};

const draftSummary: ZoneVersionSummary = {
  version_id: "v-draft", number: null, state: "DRAFT", revision: 5, content_sha256: HASH_DRAFT,
  created_at: "2026-11-13T08:00:00Z", updated_at: "2026-11-13T09:00:00Z", published_by: null, published_at: null,
};

const cell = (over: Partial<MatrixCellView> = {}): MatrixCellView => ({
  source: "z-alpha",
  destination: "z-bravo",
  source_name: "ZONE-ALPHA",
  destination_name: "ZONE-BRAVO",
  statement: "ALLOW_LISTED",
  services: [{ entry: "tcp/443", evaluable: true, not_evaluable_reason: null }],
  severity: "HIGH",
  rationale: "Application to database tier",
  regulation_reference: "SEC-POL-4.2",
  reference_status: "PROPOSED",
  confirmed_by: null,
  confirmed_at: null,
  cell_sha256: HASH_CELL_PROPOSED,
  ...over,
});

const matrixView = (over: Partial<ZoneMatrixView> = {}): ZoneMatrixView => ({
  default_statement: "DENY_ALL",
  default_severity: "MEDIUM",
  unassigned_to_unassigned: "DENY_ALL unless an explicit cell says otherwise",
  template_origin: null,
  service_sets: [],
  cells: [
    cell(),
    cell({
      source: "z-bravo", destination: "z-alpha", source_name: "ZONE-BRAVO", destination_name: "ZONE-ALPHA",
      statement: "DENY_ALL", services: [], severity: "CRITICAL", regulation_reference: "SEC-POL-1.1",
      reference_status: "CONFIRMED", confirmed_by: "USER-1", confirmed_at: "2026-11-14T08:00:00Z",
      cell_sha256: HASH_CELL_CONFIRMED,
    }),
  ],
  cells_total: 2,
  cells_with_reference: 2,
  cells_confirmed: 1,
  ...over,
});

const detail = (summary: ZoneVersionSummary, over: Partial<ZoneVersionDetail> = {}): ZoneVersionDetail => ({
  ...summary,
  zones: [
    { zone_id: "z-alpha", name: "ZONE-ALPHA", description: null, role: "APPLICATION", block_count: 1, blocks: null, effective_addresses: { IPV4: "256", IPV6: "0" } },
    { zone_id: "z-bravo", name: "ZONE-BRAVO", description: null, role: "DATABASE", block_count: 1, blocks: null, effective_addresses: { IPV4: "256", IPV6: "0" } },
  ],
  unassigned_addresses: { IPV4: "4294966784", IPV6: "0" },
  redundant_blocks: 0,
  revisions: [
    { revision: 5, actor: "USER-1", occurred_at: "2026-11-13T09:00:00Z", change_kind: "CELL_SET", zone_id: null, content_sha256: HASH_DRAFT, rationale_given: null },
  ],
  matrix: matrixView(),
  ...over,
});

const resolvedFinding = (over: Partial<SummaryResolvedFinding> = {}): SummaryResolvedFinding => ({
  policy_source_id: "pol-1",
  policy_name: "POLICY-ALPHA",
  rule_uid: "uid-11",
  inline_parent_path: [],
  rule_id: "rule-11",
  rule_number: 11,
  rule_name: "RULE-ECHO",
  target_device_ids: ["FW-TANGO-04"],
  outcome: "VIOLATION",
  reason: null,
  highest_severity: "HIGH",
  flags: [],
  covering_rule_id: null,
  violated_pairs: [{
    source_zone_id: "z-alpha", destination_zone_id: "z-bravo", source_name: "ZONE-ALPHA", destination_name: "ZONE-BRAVO",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [],
  }],
  undecided_parts: [],
  first_observed_at: "2026-11-15T08:00:00Z",
  first_observed_any_version: "2026-11-10T08:00:00Z",
  last_observed_at: "2026-11-20T08:00:00Z",
  resolved_from_version_id: "v-retired",
  resolved_from_version_number: 2,
  resolved_in_version_id: "v-active",
  resolved_in_version_number: 3,
  resolved_at: "2026-11-24T10:00:00Z",
  ...over,
});

const summaryBody = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  can: COMPLIANCE_CAN,
  evaluated_at: "2026-11-25T08:00:00Z",
  state: "READY",
  state_text: "findings of the latest complete run of each policy",
  masked: false,
  policy_names_masked: false,
  witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH_ACTIVE, published_at: "2026-11-12T08:00:00Z" },
  headline: null,
  headline_absent_reason: "none",
  policies: [],
  not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 },
  unfiltered_findings: 0,
  total_findings: 0,
  returned: 0,
  more: 0,
  capped: false,
  findings: [],
  resolved_by_version_change: {
    window_days: 7,
    total_last_7_days: 1,
    returned: 1,
    more: 0,
    capped: false,
    last_7_days: [resolvedFinding()],
    by_version: [
      { version_id: "v-active", version_number: 3, count: 1 },
    ],
  },
  ...over,
});

interface VersionsWorld {
  readonly can?: ZoneMatrixCan;
  readonly active?: ZoneVersionDetail | null;
  readonly draft?: ZoneVersionDetail | null;
  readonly diff?: ZoneDiff | null;
  readonly publishCheck?: { readonly eligible: boolean; readonly reason: string | null };
  readonly versions?: readonly ZoneVersionSummary[];
  readonly summary?: ZoneMatrixSummaryResponse | (() => { readonly status: number; readonly body: unknown });
  readonly writes?: Record<string, { readonly status: number; readonly body: unknown }>;
}

function serveVersions(opts: VersionsWorld = {}) {
  const can = opts.can ?? COMPLIANCE_CAN;
  const active = opts.active === undefined ? detail(activeSummary) : opts.active;
  const draft = opts.draft === undefined ? detail(draftSummary) : opts.draft;
  const versions = opts.versions ?? [
    ...(draft ? [draftSummary] : []),
    ...(active ? [activeSummary] : []),
    retiredMissingMetaSummary,
  ];
  const calls: { readonly method: string; readonly path: string; readonly body: unknown }[] = [];
  const reply = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const path = url.split("?")[0];
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : null;
    calls.push({ method, path, body });

    const write = opts.writes?.[`${method} ${path}`];
    if (write) return reply(write.status, write.body);

    if (method === "GET" && path === "/api/v2/zone-matrix/summary") {
      if (typeof opts.summary === "function") {
        const res = opts.summary();
        return reply(res.status, res.body);
      }
      return reply(200, opts.summary ?? summaryBody({ can }));
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/draft") {
      return reply(200, {
        active: active ? activeSummary : null,
        draft_exists: Boolean(draft),
        draft,
        diff: opts.diff ?? null,
        publish_check: opts.publishCheck ?? { eligible: true, reason: null },
        can,
      });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions/v-active" && active) {
      return reply(200, { ...active, can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions") {
      return reply(200, { versions, can });
    }
    return reply(200, { version_id: "v-draft", revision: 6, content_sha256: HASH_DRAFT, zone_id: null });
  }));
  return calls;
}

const openVersionsTab = async () => {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name: "Versions" }));
  return screen.findByRole("table", { name: "Version history" });
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Contract 7.1 Versions tab conformance", () => {
  it("history: shows immutable published versions with hashes and renders UNKNOWN (never 'Not published') when a published version lacks metadata", async () => {
    serveVersions();
    const history = await openVersionsTab();

    const activeRow = within(history).getByText("Active").closest("tr")!;
    expect(within(activeRow).getByText("3")).toBeInTheDocument();
    expect(within(activeRow).getByText("aaaaaaaaaaaa")).toHaveAttribute("title", HASH_ACTIVE);
    expect(within(activeRow).getByText("USER-2")).toBeInTheDocument();

    const draftRow = within(history).getByText("Draft r5").closest("tr")!;
    expect(within(draftRow).getAllByText("Not published")).toHaveLength(2);

    // Retired version with missing hash, published_by and published_at renders UNKNOWN, never blank or 'Not published'.
    const retiredRow = within(history).getByText("Retired").closest("tr")!;
    expect(within(retiredRow).getByText("2")).toBeInTheDocument();
    expect(within(retiredRow).getAllByText("UNKNOWN")).toHaveLength(3);
  });

  it("diff with loosening changes and moved addresses: lists loosening changes, moved addresses, effective sizes and UNKNOWN on unrecognised cell statements", async () => {
    const diff: ZoneDiff = {
      zones: [
        {
          zone_id: "z-bravo", change: "CHANGED", name_before: "ZONE-BRAVO", name_after: "ZONE-BRAVO",
          role_before: "DATABASE", role_after: "DATABASE", blocks_added: ["192.0.2.128/25"], blocks_removed: 0,
          effective_before: { IPV4: "256", IPV6: "0" }, effective_after: { IPV4: "384", IPV6: "0" },
        },
      ],
      moves: [
        { from: "ZONE-ALPHA", to: "ZONE-BRAVO", family: "IPV4", addresses: "128", leaves_a_zone: true },
      ],
      cells: [
        {
          source: "z-alpha", destination: "z-bravo", source_name: "ZONE-ALPHA", destination_name: "ZONE-BRAVO", change: "CHANGED",
          before: { explicit: true, statement: "DENY_ALL", services: [], severity: "CRITICAL", reference_status: "CONFIRMED" },
          after: { explicit: true, statement: "BOGUS_STMT" as never, services: ["tcp/443"], severity: "BOGUS_SEV" as never, reference_status: "PROPOSED" },
          loosening: ["STATEMENT_RELAXED"],
        },
      ],
      loosening: [
        { kind: "STATEMENT_RELAXED", source_name: "ZONE-ALPHA", destination_name: "ZONE-BRAVO" },
        { kind: "ADDRESSES_MOVED", source_name: "ZONE-ALPHA", destination_name: "ZONE-BRAVO", family: "IPV4", addresses: "128" },
      ],
      rationale_required: true,
      unassigned_before: { IPV4: "4294966784", IPV6: "0" },
      unassigned_after: { IPV4: "4294966784", IPV6: "0" },
    };
    serveVersions({ diff });
    await openVersionsTab();

    expect(screen.getByTestId("draft-summary")).toHaveTextContent(
      "0 zones added, 0 zones removed, 1 zone changed, 1 address move, 1 cell changed; 2 loosening changes.",
    );
    const looseningList = screen.getByRole("list", { name: "Loosening changes" });
    expect(within(looseningList).getByText("ZONE-ALPHA -> ZONE-BRAVO: statement relaxed")).toBeInTheDocument();

    const movesList = screen.getByRole("list", { name: "Address moves" });
    expect(within(movesList).getByText("128 IPv4 addresses move from ZONE-ALPHA to ZONE-BRAVO")).toBeInTheDocument();

    const changedCells = screen.getByRole("table", { name: "Changed cells" });
    expect(within(changedCells).getByText(/UNKNOWN \(UNKNOWN, explicit\): tcp\/443/)).toBeInTheDocument();

    const changedZones = screen.getByRole("table", { name: "Changed zones" });
    expect(within(changedZones).getByText("256 to 384")).toBeInTheDocument();
  });

  it("publish: enforces rationale on loosening changes and binds publish to reviewed revision and content_sha256", async () => {
    const calls = serveVersions({
      diff: {
        zones: [],
        moves: [{ from: "ZONE-ALPHA", to: "ZONE-BRAVO", family: "IPV4", addresses: "64", leaves_a_zone: true }],
        loosening: [{ kind: "ADDRESSES_MOVED", source_name: "ZONE-ALPHA", destination_name: "ZONE-BRAVO", family: "IPV4", addresses: "64" }],
        rationale_required: true,
        unassigned_before: { IPV4: "0", IPV6: "0" },
        unassigned_after: { IPV4: "0", IPV6: "0" },
      },
      writes: {
        "POST /api/v2/zone-matrix/draft/publish": {
          status: 200,
          body: { version_id: "v-draft", number: 4, content_sha256: HASH_DRAFT },
        },
      },
    });
    await openVersionsTab();

    const publishBtn = screen.getByRole("button", { name: "Publish draft" });
    expect(publishBtn).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: "Approved tier boundary update" } });
    expect(publishBtn).toBeEnabled();

    fireEvent.click(publishBtn);
    fireEvent.click(screen.getByRole("button", { name: "Confirm publish" }));
    expect(await screen.findByText("Published as version 4.")).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v2/zone-matrix/draft/publish")?.body).toEqual({
      revision: 5,
      content_sha256: HASH_DRAFT,
      rationale: "Approved tier boundary update",
    });
  });

  it("reference confirmation: lists explicit cells on Versions tab, confirms proposed references only for compliance_admin, masks references in masked views, and prints '+N more' when capped", async () => {
    // 1. Compliance administrator sees confirmation counts, four-eyes note, and confirms a proposed cell on Versions tab.
    const calls = serveVersions({
      can: COMPLIANCE_CAN,
      draft: detail(draftSummary, {
        matrix: matrixView({
          cells: [
            cell(),
            cell({
              source: "z-bravo", destination: "z-alpha", source_name: "ZONE-BRAVO", destination_name: "ZONE-ALPHA",
              statement: "DENY_ALL", services: [], severity: "CRITICAL", regulation_reference: "SEC-POL-1.1",
              reference_status: "CONFIRMED", confirmed_by: null, confirmed_at: null, cell_sha256: HASH_CELL_CONFIRMED,
            }),
            cell({
              source: "z-alpha", destination: "z-alpha", source_name: "ZONE-ALPHA", destination_name: "ZONE-ALPHA",
              statement: "ALLOW_ANY", services: [], severity: "LOW", regulation_reference: "",
              reference_status: "MYSTERY_STATUS" as ReferenceStatus,
            }),
          ],
          cells_total: 3,
          cells_with_reference: 2,
          cells_confirmed: 1,
        }),
      }),
    });
    await openVersionsTab();

    const refSection = screen.getByTestId("versions-reference-confirmation");
    expect(within(refSection).getByTestId("versions-reference-summary")).toHaveTextContent(
      "1 of 3 explicit cells confirmed by compliance (2 with regulation reference, 1 proposed).",
    );
    expect(within(refSection).getByText("Confirming makes you an editor of this draft; another person must publish it.")).toBeInTheDocument();

    const refTable = within(refSection).getByRole("table", { name: "Cell reference confirmations" });
    // Confirmed cell missing confirmed_by renders UNKNOWN, never blank.
    const confirmedMissingWho = within(refTable).getByText("ZONE-BRAVO -> ZONE-ALPHA").closest("tr")!;
    expect(within(confirmedMissingWho).getByText("Confirmed")).toBeInTheDocument();
    expect(within(confirmedMissingWho).getByText("UNKNOWN")).toBeInTheDocument();

    // Unrecognised reference_status renders UNKNOWN.
    const mysteryRow = within(refTable).getByText("ZONE-ALPHA -> ZONE-ALPHA").closest("tr")!;
    expect(within(mysteryRow).getAllByText("UNKNOWN").length).toBeGreaterThanOrEqual(1);

    // Proposed cell offers Confirm reference and sends revision + cell_sha256.
    const proposedRow = within(refTable).getByText("ZONE-ALPHA -> ZONE-BRAVO").closest("tr")!;
    fireEvent.click(within(proposedRow).getByRole("button", { name: "Confirm reference" }));
    expect(await screen.findByText("Reference confirmed for ZONE-ALPHA -> ZONE-BRAVO.")).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/api/v2/zone-matrix/draft/cells/confirm-reference")?.body).toEqual({
      revision: 5,
      source: "z-alpha",
      destination: "z-bravo",
      cell_sha256: HASH_CELL_PROPOSED,
    });
    document.body.innerHTML = "";
    vi.unstubAllGlobals();

    // 2. Non-compliance editor (`confirm_reference: false`) and masked viewer see the table without any Confirm button; masked reference shows 'hidden in the masked view', and > REF_CELLS_CAP prints '+N more'.
    const bulkCells: readonly MatrixCellView[] = Array.from({ length: REF_CELLS_CAP + 4 }, (_, i) => cell({
      source: `z-${i}`,
      destination: "z-bravo",
      source_name: `ZONE-${i + 1}`,
      destination_name: "ZONE-2",
      regulation_reference: null,
      reference_status: "PROPOSED",
    }));
    serveVersions({
      can: SECURITY_NO_CONFIRM_CAN,
      draft: null,
      active: detail(activeSummary, {
        matrix: matrixView({ cells: bulkCells, cells_total: bulkCells.length, cells_with_reference: bulkCells.length, cells_confirmed: 0 }),
      }),
    });
    await openVersionsTab();

    const cappedSection = screen.getByTestId("versions-reference-confirmation");
    expect(within(cappedSection).queryByRole("button", { name: "Confirm reference" })).not.toBeInTheDocument();
    expect(within(cappedSection).getAllByText("hidden in the masked view").length).toBeGreaterThan(0);
    expect(within(cappedSection).getByTestId("versions-reference-more")).toHaveTextContent("+4 more explicit cells not shown.");
  });

  it("resolved-by-version lists: groups resolved findings per resolving version, prints '+N more' when capped or outside the 7-day detail window, and renders UNKNOWN when summary is not READY or fails", async () => {
    const groups = buildResolvedByVersionGroups({
      window_days: 7,
      total_last_7_days: 2,
      returned: 1,
      more: 1,
      capped: true,
      last_7_days: [resolvedFinding({ resolved_in_version_id: "v-active", resolved_in_version_number: 3 })],
      by_version: [
        { version_id: "v-retired", version_number: 2, count: 4 },
        { version_id: "v-active", version_number: 3, count: 3 },
      ],
    });
    expect(groups.map((g) => ({ key: g.versionKey, num: g.versionNumber, total: g.allTimeCount, shown: g.findings.length }))).toEqual([
      { key: "v-active", num: 3, total: 3, shown: 1 },
      { key: "v-retired", num: 2, total: 4, shown: 0 },
    ]);

    // 1. Summary read failure -> UNKNOWN panel with Retry summary read, then recovers to per-version lists.
    let summaryCalls = 0;
    serveVersions({
      can: VIEWER_CAN,
      draft: null,
      summary: () => {
        summaryCalls += 1;
        if (summaryCalls === 1) return { status: 500, body: { error: "INTERNAL", reason: "summary store unavailable" } };
        return {
          status: 200,
          body: summaryBody({
            can: VIEWER_CAN,
            resolved_by_version_change: {
              window_days: 7,
              total_last_7_days: 3,
              returned: 1,
              more: 2,
              capped: true,
              last_7_days: [
                resolvedFinding({
                  rule_number: null,
                  rule_name: "RULE-HOTEL",
                  violated_pairs: [],
                  resolved_at: null,
                  resolved_from_version_number: 2,
                  resolved_in_version_id: "v-active",
                  resolved_in_version_number: 3,
                }),
              ],
              by_version: [
                { version_id: "v-active", version_number: 3, count: 5 },
                { version_id: "v-retired", version_number: 2, count: 2 },
              ],
            },
          }),
        };
      },
    });
    const { container } = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Versions" }));

    const section = await screen.findByTestId("versions-resolved-lists");
    expect(await within(section).findByText("Resolved-by-version lists cannot be read")).toBeInTheDocument();

    fireEvent.click(within(section).getByRole("button", { name: "Retry summary read" }));
    await waitFor(() =>
      expect(within(section).getByTestId("versions-resolved-window-count")).toHaveTextContent(
        "3 findings resolved by a matrix version change in the recent 7-day window (1 returned, +2 more not shown).",
      ),
    );

    const v3Group = within(section).getByTestId("resolved-version-group-v-active");
    expect(v3Group).toHaveTextContent("Version 3: 5 findings resolved in total (all time); 1 returned from the recent 7-day window.");
    expect(within(v3Group).getByRole("list", { name: "Findings resolved by version 3" })).toHaveTextContent(
      "#? RULE-HOTEL · POLICY-ALPHA · UNKNOWN pairs · version 2 -> version 3 · UNKNOWN",
    );
    expect(within(v3Group).getByTestId("resolved-version-more-v-active")).toHaveTextContent("+4 more resolved by version 3");

    const v2Group = within(section).getByTestId("resolved-version-group-v-retired");
    expect(v2Group).toHaveTextContent("Version 2: 2 findings resolved in total (all time); 0 returned from the recent 7-day window.");
    expect(within(v2Group).getByTestId("resolved-version-more-v-retired")).toHaveTextContent("+2 more resolved by version 2");
    expect(container.textContent ?? "").not.toMatch(/compliant|bddk|\bpass(ed)?\b|score|%/i);
  });
});
