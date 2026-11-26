import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { StatementShape } from "../src/screens/zonematrix/MatrixCellDetail";
import { cellSummaryLabel } from "../src/screens/zonematrix/MatrixTab";
import type {
  MatrixCellView, MatrixSeverity, MatrixStatement, SummaryCellCount, SummaryFinding, SummaryHeadline, SummaryPolicy,
  ZoneMatrixCan, ZoneMatrixSummaryResponse, ZoneMatrixView, ZoneVersionDetail, ZoneVersionSummary,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Contract 7.1 conformance pins for the Matrix tab (docs/design/ZONE_MATRIX_CONTRACT.md sections 7.1, 7.2, 3.3, 4.1).
 * Synthetic names and RFC 5737 documentation ranges only.
 */
const HASH_ACTIVE = "1".repeat(64);
const HASH_RETIRED = "2".repeat(64);
const HASH_DRAFT = "3".repeat(64);
const HASH_CELL = "4".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";

const EDITOR_CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true, evaluate: true };
const PUBLISHER_ONLY_CAN: ZoneMatrixCan = { view_blocks: false, edit: false, publish: true, confirm_reference: false, evaluate: false };
const VIEWER_CAN: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, confirm_reference: false, evaluate: false };

const ZONES_ACTIVE = [
  { zone_id: "z-alpha", name: "ZONE-ALPHA", description: "User tier", role: "USER", block_count: 1, blocks: null, effective_addresses: { IPV4: "256", IPV6: "0" } },
  { zone_id: "z-bravo", name: "ZONE-BRAVO", description: "Database tier", role: "DATABASE", block_count: 1, blocks: null, effective_addresses: { IPV4: "256", IPV6: "0" } },
];

const cell = (over: Partial<MatrixCellView> = {}): MatrixCellView => ({
  source: "z-alpha",
  destination: "z-bravo",
  source_name: "ZONE-ALPHA",
  destination_name: "ZONE-BRAVO",
  statement: "ALLOW_LISTED",
  services: [{ entry: "tcp/443", evaluable: true, not_evaluable_reason: null }],
  severity: "HIGH",
  rationale: "Synthetic rationale",
  regulation_reference: "SEC-POL-4.2",
  reference_status: "PROPOSED",
  confirmed_by: null,
  confirmed_at: null,
  cell_sha256: HASH_CELL,
  ...over,
});

const matrixView = (over: Partial<ZoneMatrixView> = {}): ZoneMatrixView => ({
  default_statement: "DENY_ALL",
  default_severity: "MEDIUM",
  unassigned_to_unassigned: "DENY_ALL unless an explicit cell says otherwise",
  template_origin: { name: "Overwatch segmentation template (proposal)", revision: "2026-11-13.2" },
  service_sets: [],
  cells: [
    cell(),
    cell({
      source: "z-bravo", destination: "z-alpha", source_name: "ZONE-BRAVO", destination_name: "ZONE-ALPHA",
      statement: "DENY_ALL", services: [], severity: "CRITICAL", reference_status: "CONFIRMED",
      confirmed_by: "USER-1", confirmed_at: "2026-11-14T08:00:00Z",
    }),
    cell({
      source: "z-alpha", destination: "z-alpha", source_name: "ZONE-ALPHA", destination_name: "ZONE-ALPHA",
      statement: "ALLOW_ANY", services: [], severity: "LOW", regulation_reference: "", reference_status: "EMPTY",
    }),
  ],
  cells_total: 3,
  cells_with_reference: 2,
  cells_confirmed: 1,
  ...over,
});

const activeSummary: ZoneVersionSummary = {
  version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH_ACTIVE,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z",
};

const retiredSummary: ZoneVersionSummary = {
  version_id: "v-retired", number: 2, state: "RETIRED", revision: 2, content_sha256: HASH_RETIRED,
  created_at: "2026-11-08T08:00:00Z", updated_at: "2026-11-09T08:00:00Z", published_by: "USER-1", published_at: "2026-11-09T08:00:00Z",
};

const draftSummary: ZoneVersionSummary = {
  version_id: "v-draft", number: null, state: "DRAFT", revision: 6, content_sha256: HASH_DRAFT,
  created_at: "2026-11-13T08:00:00Z", updated_at: "2026-11-13T09:00:00Z", published_by: null, published_at: null,
};

const versionDetail = (summary: ZoneVersionSummary, over: Partial<ZoneVersionDetail> = {}): ZoneVersionDetail => ({
  ...summary,
  zones: ZONES_ACTIVE,
  unassigned_addresses: { IPV4: "4294966784", IPV6: "0" },
  redundant_blocks: 0,
  revisions: [],
  matrix: matrixView(),
  ...over,
});

const run = () => ({
  run_id: "run-1", state: "COMPLETE", failure_reason: null, version_id: "v-active", version_number: 3, content_sha256: HASH_ACTIVE,
  started_at: "2026-11-25T07:00:00Z", finished_at: "2026-11-25T07:05:00Z", snapshot_collected_at: "2026-11-25T06:00:00Z",
  coverage: "COMPLETE", counts: null,
});

const policy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-1", policy_name: "POLICY-ALPHA", vendor: "CP", source_id: "src-1", source_name: "FW-TANGO-04",
  container_id: null, container_name: null, collected_at: "2026-11-25T06:00:00Z", rule_count: 10,
  staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 3600, snapshot_age_known: true },
  coverage: "COMPLETE", coverage_items: [], collection_failures: 0, has_complete_run: true,
  latest_complete_run: run(), latest_run: run(), ...over,
});

const finding = (over: Partial<SummaryFinding> = {}): SummaryFinding => ({
  policy_source_id: "pol-1", policy_name: "POLICY-ALPHA", rule_uid: "uid-1", inline_parent_path: [], rule_id: "rule-1", rule_number: 1,
  rule_name: "RULE-ALPHA", target_device_ids: ["FW-TANGO-04"], outcome: "VIOLATION", reason: null, highest_severity: "HIGH", flags: [],
  covering_rule_id: null, undecided_parts: [],
  violated_pairs: [{
    source_zone_id: "z-alpha", destination_zone_id: "z-bravo", source_name: "ZONE-ALPHA", destination_name: "ZONE-BRAVO",
    statement: "ALLOW_LISTED", severity: "HIGH", not_allowed_services: ["tcp/22"],
  }],
  first_observed_at: "2026-11-20T08:00:00Z", first_observed_any_version: "2026-11-18T08:00:00Z", last_observed_at: "2026-11-25T07:05:00Z",
  run_id: "run-1", version_id: "v-active", version_number: 3, content_sha256: HASH_ACTIVE, on_active_version: true,
  policy_staleness: "CURRENT", snapshot_age_seconds: 3600, ...over,
});

const headline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active", version_number: 3, distinct_violating_rules: 2, violated_zone_pairs: 2, critical_rules: 0,
  covered_by_earlier_rule: 0, conditional_rules: 0, outside_headline_ordered_layer: 0, not_evaluable_findings: 0, unknown_findings: 0,
  became_undecided_findings: 0, stale_policies: 0, incomplete_policies: 0, older_version_policies: 0, not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 0, exact: true, count_text: "2 violating rules in 1 policy",
  text: "2 rules violate the zone matrix (v3) across 2 zone pairs", outside_headline_text: null,
  staleness_headline: { count: 0, exact: true, text: "All evaluated policies are current" }, ...over,
});

const summaryBody = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  can: EDITOR_CAN,
  evaluated_at: "2026-11-25T08:00:00Z", state: "READY", state_text: "findings of the latest complete run of each policy",
  masked: false, policy_names_masked: false, witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH_ACTIVE, published_at: "2026-11-12T08:00:00Z" },
  headline: headline(), headline_absent_reason: null, policies: [policy()], not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 2, total_findings: 2, returned: 2, more: 0, capped: false,
  findings: [
    finding(),
    finding({
      rule_uid: "uid-2", rule_id: "rule-2", rule_number: 2, rule_name: "RULE-UNASSIGNED",
      violated_pairs: [{
        source_zone_id: "UNASSIGNED", destination_zone_id: "z-bravo", source_name: "UNASSIGNED", destination_name: "ZONE-BRAVO",
        statement: "DENY_ALL", severity: "MEDIUM", not_allowed_services: [],
      }],
    }),
  ],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  cell_counts: [
    { source_zone_id: "z-alpha", destination_zone_id: "z-bravo", source_name: "ZONE-ALPHA", destination_name: "ZONE-BRAVO", headline_rules: 2, outside_headline_rules: 0, highest_severity: "HIGH" } satisfies SummaryCellCount,
    { source_zone_id: "UNASSIGNED", destination_zone_id: "z-bravo", source_name: "UNASSIGNED", destination_name: "ZONE-BRAVO", headline_rules: 1, outside_headline_rules: 0, highest_severity: "MEDIUM" } satisfies SummaryCellCount,
  ],
  cell_counts_exact: true, cell_counts_total: 2, cell_counts_returned: 2, cell_counts_more: 0, cell_counts_capped: false,
  ...over,
});

interface MockOpts {
  readonly can?: ZoneMatrixCan;
  readonly active?: ZoneVersionDetail | null;
  readonly draft?: ZoneVersionDetail | null;
  readonly draftExists?: boolean;
  readonly retired?: ZoneVersionDetail | (() => { readonly status: number; readonly body: unknown });
  readonly versions?: readonly ZoneVersionSummary[];
  readonly summary?: ZoneMatrixSummaryResponse;
}

function serveMatrix(opts: MockOpts = {}) {
  const can = opts.can ?? EDITOR_CAN;
  const active = opts.active === undefined ? versionDetail(activeSummary) : opts.active;
  const draft = opts.draft === undefined ? versionDetail(draftSummary) : opts.draft;
  const versions = opts.versions ?? [
    ...(draft ? [draftSummary] : []),
    ...(active ? [activeSummary] : []),
    retiredSummary,
  ];
  const summary = opts.summary ?? summaryBody({ can });
  const reply = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const path = url.split("?")[0];
    if (path === "/api/v2/zone-matrix/summary") return reply(200, summary);
    if (path === "/api/v2/zone-matrix/draft") {
      return reply(200, {
        active: active ? activeSummary : null,
        draft_exists: opts.draftExists ?? Boolean(draft),
        draft,
        diff: null,
        publish_check: { eligible: true, reason: null },
        can,
      });
    }
    if (path === "/api/v2/zone-matrix/versions/v-active" && active) return reply(200, { ...active, can });
    if (path === "/api/v2/zone-matrix/versions/v-retired") {
      if (typeof opts.retired === "function") {
        const res = opts.retired();
        return reply(res.status, res.body);
      }
      const retired = opts.retired ?? versionDetail(retiredSummary, {
        zones: [{ zone_id: "z-alpha", name: "ZONE-ALPHA", description: null, role: "USER", block_count: 1, blocks: null, effective_addresses: { IPV4: "128", IPV6: "0" } }],
        matrix: matrixView({ default_statement: "ALLOW_LISTED", default_severity: "LOW", cells: [], cells_total: 0, cells_with_reference: 0, cells_confirmed: 0 }),
      });
      return reply(200, { ...retired, can });
    }
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions, can });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Contract 7.1 Matrix tab conformance", () => {
  it("zones x zones grid of the active version: shows the active version by default with every zone and UNASSIGNED on both axes", async () => {
    serveMatrix();
    render(<ZoneMatrixScreen />);

    expect(await screen.findByRole("heading", { name: "Version 3" })).toBeInTheDocument();
    const grid = screen.getByRole("table", { name: "Zone matrix grid" });
    expect(within(grid).getAllByText("ZONE-ALPHA").length).toBeGreaterThanOrEqual(2);
    expect(within(grid).getAllByText("ZONE-BRAVO").length).toBeGreaterThanOrEqual(2);
    expect(within(grid).getAllByText("UNASSIGNED").length).toBeGreaterThanOrEqual(2);

    expect(screen.getByTestId("matrix-cell-z-alpha-z-bravo")).toHaveAttribute("data-explicit", "true");
    expect(screen.getByTestId("matrix-cell-z-alpha-UNASSIGNED")).toHaveAttribute("data-explicit", "false");
    expect(within(screen.getByTestId("matrix-cell-z-alpha-UNASSIGNED")).getByText(/Medium · default/)).toBeInTheDocument();
    expect(screen.getByTestId("matrix-cell-UNASSIGNED-UNASSIGNED")).toHaveAttribute("data-statement", "DENY_ALL");
  });

  it("statement (shape plus text, not colour alone): pairs every statement with its distinct shape and renders UNKNOWN on unrecognised values", async () => {
    expect(cellSummaryLabel("DENY_ALL", 0)).toBe("Deny all");
    expect(cellSummaryLabel("ALLOW_LISTED", 0)).toBe("Allow listed (empty list)");
    expect(cellSummaryLabel("ALLOW_LISTED", 3)).toBe("Allow listed (3)");
    expect(cellSummaryLabel("ALLOW_ANY", 0)).toBe("Allow any");
    expect(cellSummaryLabel("CORRUPT_STATEMENT", 2)).toBe("Statement UNKNOWN");

    const { container: shapeBox } = render(<StatementShape statement={"CORRUPT_STATEMENT" as MatrixStatement} />);
    expect(shapeBox.querySelector("[data-shape='unknown-mark']")).not.toBeNull();
    expect(shapeBox.querySelector("[data-shape='open-mark']")).toBeNull();

    const corruptMatrix = matrixView({
      cells: [cell({ statement: "UNRECOGNISED_STMT" as MatrixStatement })],
      cells_total: 1,
    });
    serveMatrix({ active: versionDetail(activeSummary, { matrix: corruptMatrix }), draft: null });
    render(<ZoneMatrixScreen />);

    const badCell = await screen.findByTestId("matrix-cell-z-alpha-z-bravo");
    expect(badCell.querySelector("[data-shape='unknown-mark']")).not.toBeNull();
    expect(within(badCell).getByText("Statement UNKNOWN")).toBeInTheDocument();

    const detail = screen.getByTestId("cell-detail");
    expect(within(detail).getByText(/Statement:/)).toHaveTextContent("UNKNOWN");

    fireEvent.click(screen.getByRole("button", { name: "Explicit cells" }));
    const listTable = await screen.findByRole("table", { name: "Explicit matrix cells" });
    expect(within(listTable).getByText("Statement UNKNOWN")).toBeInTheDocument();
  });

  it("severity: renders cell and default severities in grid, explicit list and detail, and renders UNKNOWN when unrecognised", async () => {
    const corruptSev = matrixView({
      default_severity: "MEDIUM",
      cells: [cell({ severity: "UNREADABLE_SEV" as MatrixSeverity })],
      cells_total: 1,
    });
    serveMatrix({ active: versionDetail(activeSummary, { matrix: corruptSev }), draft: null });
    render(<ZoneMatrixScreen />);

    const explicitCell = await screen.findByTestId("matrix-cell-z-alpha-z-bravo");
    expect(within(explicitCell).getByText(/UNKNOWN · explicit/)).toBeInTheDocument();

    const defaultCell = screen.getByTestId("matrix-cell-z-bravo-z-alpha");
    expect(within(defaultCell).getByText(/Medium · default/)).toBeInTheDocument();

    const detail = screen.getByTestId("cell-detail");
    expect(within(detail).getByText(/Severity:/)).toHaveTextContent("UNKNOWN (UNKNOWN)");
  });

  it("number of open violations: says at least N when a policy is stale even if cell_counts_exact is true, and appears in both grid and list views", async () => {
    serveMatrix({
      draft: null,
      summary: summaryBody({
        cell_counts_exact: true,
        policies: [policy({ staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 120_000, snapshot_age_known: true } })],
      }),
    });
    render(<ZoneMatrixScreen />);

    const line = await screen.findByTestId("open-violations-z-alpha-z-bravo");
    await waitFor(() => expect(line).toHaveTextContent("at least 2 open violations"));

    fireEvent.click(screen.getByRole("button", { name: "Explicit cells" }));
    const listTable = await screen.findByRole("table", { name: "Explicit matrix cells" });
    expect(within(listTable).getByTestId("open-violations-z-alpha-z-bravo")).toHaveTextContent("at least 2 open violations");
  });

  it("a cell opens its violations: opens the Violations tab narrowed to an UNASSIGNED source cell or an inspected explicit cell", async () => {
    serveMatrix({ draft: null });
    render(<ZoneMatrixScreen />);

    fireEvent.click(await screen.findByTestId("matrix-cell-UNASSIGNED-z-bravo"));
    const detail = await screen.findByTestId("cell-detail");
    await waitFor(() => expect(within(detail).getByTestId("cell-open-violations")).toHaveTextContent("1 open violation"));
    fireEvent.click(within(detail).getByRole("button", { name: "Open violations for this pair" }));

    const violationsTable = await screen.findByRole("table", { name: "Violation findings" });
    expect(screen.getByRole("tab", { name: "Violations" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("combobox", { name: "Source zone" })).toHaveTextContent("UNASSIGNED");
    expect(screen.getByRole("combobox", { name: "Destination zone" })).toHaveTextContent("ZONE-BRAVO");
    expect(within(violationsTable).getByText(/RULE-UNASSIGNED/)).toBeInTheDocument();
    expect(within(violationsTable).queryByText(/RULE-ALPHA/)).not.toBeInTheDocument();
  });

  it("any published version can be shown: renders a retired version read-only, and shows UNKNOWN with Retry instead of active cells if reading fails", async () => {
    let attempts = 0;
    serveMatrix({
      draft: null,
      retired: () => {
        attempts += 1;
        if (attempts === 1) return { status: 500, body: { error: "INTERNAL", reason: "retired version read failed" } };
        return {
          status: 200,
          body: {
            ...versionDetail(retiredSummary, {
              zones: [{ zone_id: "z-alpha", name: "ZONE-ALPHA", description: null, role: "USER", block_count: 1, blocks: null, effective_addresses: { IPV4: "128", IPV6: "0" } }],
              matrix: matrixView({ default_statement: "ALLOW_LISTED", default_severity: "LOW", cells: [], cells_total: 0, cells_with_reference: 0, cells_confirmed: 0 }),
            }),
            can: EDITOR_CAN,
          },
        };
      },
    });
    render(<ZoneMatrixScreen />);
    await screen.findByRole("heading", { name: "Version 3" });

    fireEvent.mouseDown(screen.getByRole("combobox", { name: /Version shown/ }));
    fireEvent.click(await screen.findByRole("option", { name: /Version 2 \(retired\)/ }));

    // Failed retired read never substitutes the active version's grid or open violations.
    expect(await screen.findByText("Selected published version could not be read")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Zone matrix grid" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("open-violations-z-alpha-z-bravo")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Retry version read" }));
    expect(await screen.findByRole("heading", { name: "Version 2" })).toBeInTheDocument();
    expect(screen.getByText("222222222222")).toHaveAttribute("title", HASH_RETIRED);
    expect(screen.queryByTestId("matrix-cell-z-alpha-z-bravo")).not.toBeInTheDocument();
    expect(screen.getByTestId("matrix-cell-z-alpha-UNASSIGNED")).toHaveAttribute("data-statement", "ALLOW_LISTED");
    expect(screen.queryByRole("button", { name: "Save cell" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save default" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Open violations for this pair" })).not.toBeInTheDocument();
  });

  it("the draft only to editors, labelled 'draft, not evaluated': shows the draft with its label to editors and hides it from non-editors", async () => {
    // 1. Non-editor (even with publish: true and a populated draft in data.draft) never sees the draft on the Matrix tab.
    const { unmount } = render(<ZoneMatrixScreen />);
    unmount();

    serveMatrix({ can: PUBLISHER_ONLY_CAN, draft: versionDetail(draftSummary) });
    const first = render(<ZoneMatrixScreen />);
    await screen.findByRole("heading", { name: "Version 3" });
    expect(screen.queryByRole("button", { name: /draft, not evaluated/i })).not.toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole("combobox", { name: /Version shown/ }));
    const nonEditorOptions = await screen.findByRole("listbox");
    expect(within(nonEditorOptions).queryByRole("option", { name: /draft/i })).not.toBeInTheDocument();
    first.unmount();

    // When no active version is published yet, a non-editor still sees the empty published-version state, never the draft.
    serveMatrix({ can: PUBLISHER_ONLY_CAN, active: null, draft: versionDetail(draftSummary), versions: [draftSummary] });
    const second = render(<ZoneMatrixScreen />);
    expect(await screen.findByText("No version has been published")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Zone matrix grid" })).not.toBeInTheDocument();
    second.unmount();

    // 2. Editor gets the draft option and switch button, both labelled "draft, not evaluated".
    serveMatrix({ can: EDITOR_CAN, draft: versionDetail(draftSummary) });
    const { container } = render(<ZoneMatrixScreen />);
    await screen.findByRole("heading", { name: "Version 3" });
    const switchBtn = screen.getByRole("button", { name: "Switch to draft r6 (draft, not evaluated)" });
    fireEvent.click(switchBtn);

    expect(await screen.findByRole("heading", { name: "Draft r6 (draft, not evaluated)" })).toBeInTheDocument();
    expect(screen.getByText(/Draft, not evaluated\. Changes apply after another person publishes the draft\./)).toBeInTheDocument();
    expect(screen.queryByTestId("open-violations-z-alpha-z-bravo")).not.toBeInTheDocument();
    expect(container.textContent ?? "").not.toMatch(/compliant|bddk|\bpass(ed)?\b|score|%/i);
  });

  it("shows only what the API returns in the masked view without draft, blocks or forbidden wording", async () => {
    const maskedActive = versionDetail(activeSummary, {
      zones: [
        { zone_id: "z-alpha", name: "ZONE-1", description: null, role: "USER", block_count: 1, blocks: null, effective_addresses: { IPV4: "256", IPV6: "0" } },
      ],
      matrix: matrixView({
        cells: [cell({
          source: "z-alpha", destination: "UNASSIGNED", source_name: "ZONE-1", destination_name: "UNASSIGNED",
          rationale: null, regulation_reference: null, reference_status: "CONFIRMED", confirmed_by: "USER-1", confirmed_at: "2026-11-14T08:00:00Z",
        })],
        cells_total: 1, cells_with_reference: 1, cells_confirmed: 1,
      }),
    });
    serveMatrix({ can: VIEWER_CAN, active: maskedActive, draft: null, versions: [activeSummary], summary: summaryBody({ can: VIEWER_CAN, masked: true }) });
    const { container } = render(<ZoneMatrixScreen />);

    await screen.findByTestId("matrix-cell-z-alpha-UNASSIGNED");
    expect(screen.getAllByText("ZONE-1").length).toBeGreaterThan(0);
    expect(screen.queryByText("ZONE-ALPHA")).not.toBeInTheDocument();
    expect(screen.queryByText(/draft, not evaluated/i)).not.toBeInTheDocument();
    expect(container.textContent ?? "").not.toMatch(/compliant|bddk|\bpass(ed)?\b|score|%/i);
  });
});
