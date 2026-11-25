import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type {
  SummaryCellCount, SummaryFinding, SummaryHeadline, SummaryPolicy, ZoneMatrixSummaryResponse,
} from "../src/screens/zonematrix/zoneMatrixApi";

/** Zone matrix step 4b-2: per-cell open-violation counts, pair drilldown to Violations, resolved counts on Versions. Synthetic values only. */
const HASH = "a".repeat(64);
const OLD_HASH = "b".repeat(64);
const DRAFT_HASH = "c".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN = { view_blocks: false, edit: false, publish: false };
const CAN_EDIT = { view_blocks: false, edit: true, publish: false };

const version = (over: Record<string, unknown>) => ({
  version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z", ...over,
});
const ZONES = [
  { zone_id: "z1", name: "Zone-Alpha", description: null, role: "APPLICATION", block_count: 1, effective_addresses: { IPV4: "256", IPV6: "0" }, blocks: null },
  { zone_id: "z2", name: "Zone-Bravo", description: null, role: "DATABASE", block_count: 1, effective_addresses: { IPV4: "256", IPV6: "0" }, blocks: null },
];
const detail = (over: Record<string, unknown>) => ({
  ...version(over), zones: ZONES, unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [],
});
const activeSummary = version({});
const retiredSummary = version({ version_id: "v-old", number: 2, state: "RETIRED", content_sha256: OLD_HASH, revision: 3 });
const draftSummary = version({ version_id: "v-draft", number: null, state: "DRAFT", content_sha256: DRAFT_HASH, revision: 5, published_by: null, published_at: null });

const run = () => ({
  run_id: "run-1", state: "COMPLETE", failure_reason: null, version_id: "v-active", version_number: 3, content_sha256: HASH,
  started_at: "2026-11-25T07:00:00Z", finished_at: "2026-11-25T07:05:00Z", snapshot_collected_at: "2026-11-25T06:00:00Z",
  coverage: "COMPLETE", counts: null,
});
const policy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-1", policy_name: "POLICY-ALFA", vendor: "check_point", source_id: "src-1", source_name: "FW-TANGO-04",
  container_id: null, container_name: null, collected_at: "2026-11-25T06:00:00Z", rule_count: 12,
  staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 5400, snapshot_age_known: true },
  coverage: "COMPLETE", coverage_items: [], collection_failures: 0, has_complete_run: true,
  latest_complete_run: run(), latest_run: run(), ...over,
});
const finding = (over: Partial<SummaryFinding> = {}): SummaryFinding => ({
  policy_source_id: "pol-1", policy_name: "POLICY-ALFA", rule_uid: "uid-7", inline_parent_path: [], rule_id: "rule-7", rule_number: 7,
  rule_name: "RULE-ECHO", target_device_ids: [], outcome: "VIOLATION", reason: null, highest_severity: "HIGH", flags: [],
  covering_rule_id: null, undecided_parts: [],
  violated_pairs: [{ source_zone_id: "z1", destination_zone_id: "z2", source_name: "Zone-Alpha", destination_name: "Zone-Bravo",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [] }],
  first_observed_at: "2026-11-20T08:00:00Z", first_observed_any_version: "2026-11-18T08:00:00Z", last_observed_at: "2026-11-25T07:05:00Z",
  run_id: "run-1", version_id: "v-active", version_number: 3, content_sha256: HASH, on_active_version: true,
  policy_staleness: "CURRENT", snapshot_age_seconds: 5400, ...over,
});
const reverseFinding = () => finding({
  rule_uid: "uid-8", rule_id: "rule-8", rule_number: 8, rule_name: "RULE-FOXTROT",
  violated_pairs: [{ source_zone_id: "z2", destination_zone_id: "z1", source_name: "Zone-Bravo", destination_name: "Zone-Alpha",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [] }],
});
const headline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active", version_number: 3, distinct_violating_rules: 2, violated_zone_pairs: 2, critical_rules: 0,
  covered_by_earlier_rule: 0, conditional_rules: 0, outside_headline_ordered_layer: 0, not_evaluable_findings: 0, unknown_findings: 0,
  became_undecided_findings: 0, stale_policies: 0, incomplete_policies: 0, older_version_policies: 0, not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 0, exact: true, count_text: "2 violating rules in 1 policy",
  text: "2 rules violate the zone matrix", outside_headline_text: null,
  staleness_headline: { count: 0, exact: true, text: "All evaluated policies are current" }, ...over,
});
const cell = (over: Partial<SummaryCellCount> = {}): SummaryCellCount => ({
  source_zone_id: "z1", destination_zone_id: "z2", source_name: "Zone-Alpha", destination_name: "Zone-Bravo",
  headline_rules: 3, outside_headline_rules: 1, highest_severity: "HIGH", ...over,
});
const summary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  evaluated_at: "2026-11-25T08:00:00Z", state: "READY", state_text: "findings of the latest complete run of each policy",
  masked: false, policy_names_masked: false, witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: headline(), headline_absent_reason: null, policies: [policy()], not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 2, total_findings: 2, returned: 2, more: 0, capped: false,
  findings: [finding(), reverseFinding()],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  cell_counts: [cell()], cell_counts_exact: true, cell_counts_total: 1, cell_counts_returned: 1, cell_counts_more: 0, cell_counts_capped: false,
  ...over,
});

function serve(body: ZoneMatrixSummaryResponse, opts: { draft?: boolean } = {}) {
  const reply = (status: number, payload: unknown) => new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json" } });
  const can = opts.draft ? CAN_EDIT : CAN;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const path = url.split("?")[0];
    if (path === "/api/v2/zone-matrix/summary") return reply(200, body);
    if (path === "/api/v2/zone-matrix/draft") {
      return reply(200, { active: activeSummary, draft: opts.draft ? detail(draftSummary) : null, diff: null, publish_check: null, draft_exists: Boolean(opts.draft), can });
    }
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...detail(activeSummary), can });
    if (path === "/api/v2/zone-matrix/versions/v-old") return reply(200, { ...detail(retiredSummary), can });
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeSummary, retiredSummary], can });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
}

/** The cell's line once the stored summary has been read (until then it says UNKNOWN). */
const cellLine = async (source: string, destination: string) => {
  const line = await screen.findByTestId(`open-violations-${source}-${destination}`);
  await waitFor(() => expect(line).not.toHaveTextContent("UNKNOWN"));
  return line;
};

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

describe("Zone matrix Matrix tab: open-violation counts on the active version", () => {
  it("shows a lower bound and the outside-headline part, and never a plain 0, when the counts are not exact", async () => {
    serve(summary({ cell_counts_exact: false }));
    render(<ZoneMatrixScreen />);
    expect(await cellLine("z1", "z2")).toHaveTextContent("at least 3 open violations +1 outside headline");
    const absent = await cellLine("z2", "z1");
    expect(absent).toHaveTextContent("at least 0 open violations (coverage incomplete)");
    expect(absent.textContent).not.toMatch(/^0 open/);
    expect(screen.queryByText("0 open violations")).not.toBeInTheDocument();
  });

  it("prints an exact count, and 0 only for a pair absent from an exact, proven, uncapped list", async () => {
    serve(summary({ cell_counts: [cell({ headline_rules: 1, outside_headline_rules: 0 })] }));
    render(<ZoneMatrixScreen />);
    expect(await cellLine("z1", "z2")).toHaveTextContent(/^1 open violation$/);
    expect(await cellLine("z2", "z1")).toHaveTextContent(/^0 open violations$/);
  });

  it("does not say 0 for an exact list while a policy is stale", async () => {
    serve(summary({ policies: [policy({ staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 999999, snapshot_age_known: true } })] }));
    render(<ZoneMatrixScreen />);
    expect(await cellLine("z2", "z1")).toHaveTextContent("at least 0 open violations (coverage incomplete)");
  });

  it("says 'not in top N cells' for an unlisted pair of a capped list, and prints the cap above the grid", async () => {
    serve(summary({ cell_counts_capped: true, cell_counts_returned: 500, cell_counts_total: 620, cell_counts_more: 120 }));
    render(<ZoneMatrixScreen />);
    expect(await cellLine("z2", "z1")).toHaveTextContent("not in top 500 cells");
    expect(screen.queryByText(/^0 open violations?$/)).not.toBeInTheDocument();
    expect(screen.getByTestId("cell-counts-cap-note")).toHaveTextContent("Per-cell counts capped at 500 of 620 violating cells (+120 more).");
  });

  it("says UNKNOWN, never 0, while the stored summary is not READY", async () => {
    serve(summary({ state: "NO_COMPLETE_RUN", cell_counts: null }));
    render(<ZoneMatrixScreen />);
    const line = await screen.findByTestId("open-violations-z1-z2");
    await waitFor(() => expect(line).toHaveTextContent("Open violations: UNKNOWN (stored summary not available for this version)"));
    expect(line).not.toHaveTextContent(/\b0 open violations/);
  });
});

describe("Zone matrix Matrix tab: draft and retired versions", () => {
  it("shows no open-violation counts on a draft or on a retired version", async () => {
    serve(summary(), { draft: true });
    render(<ZoneMatrixScreen />);
    // The active version first: the counts are there and the controls to open them.
    expect(await cellLine("z1", "z2")).toHaveTextContent("3 open violations");
    fireEvent.click(screen.getByRole("button", { name: /Switch to draft r5/ }));
    await screen.findByRole("heading", { name: /Draft r5/ });
    expect(screen.queryByTestId("open-violations-z1-z2")).not.toBeInTheDocument();
    expect(screen.queryByText(/open violation/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Open violations for this pair" })).not.toBeInTheDocument();

    fireEvent.mouseDown(screen.getByRole("combobox", { name: /Version shown/ }));
    fireEvent.click(await screen.findByRole("option", { name: /Version 2 \(retired\)/ }));
    await screen.findByRole("heading", { name: /Version 2/ });
    expect(screen.queryByTestId("open-violations-z1-z2")).not.toBeInTheDocument();
    expect(screen.queryByText(/open violation/i)).not.toBeInTheDocument();
    expect(screen.queryByTestId("cell-counts-cap-note")).not.toBeInTheDocument();
  });
});

describe("Zone matrix: pair drilldown to the Violations tab", () => {
  it("opens the Violations tab with the Source and Destination zone filters set to the pair, and narrows the findings", async () => {
    serve(summary());
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByTestId("matrix-cell-z1-z2"));
    const detailPanel = await screen.findByTestId("cell-detail");
    expect(within(detailPanel).getByTestId("cell-open-violations")).toHaveTextContent("3 open violations +1 outside headline");
    fireEvent.click(within(detailPanel).getByRole("button", { name: "Open violations for this pair" }));

    const table = await screen.findByRole("table", { name: "Violation findings" });
    expect(screen.getByRole("tab", { name: "Violations" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("combobox", { name: "Source zone" })).toHaveTextContent("Zone-Alpha");
    expect(screen.getByRole("combobox", { name: "Destination zone" })).toHaveTextContent("Zone-Bravo");
    expect(within(table).getByText(/RULE-ECHO/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-FOXTROT/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clear zone filters" }));
    await waitFor(() => expect(within(screen.getByRole("table", { name: "Violation findings" })).getByText(/RULE-FOXTROT/)).toBeInTheDocument());
    expect(screen.getByRole("combobox", { name: "Source zone" })).toHaveTextContent("All source zones");
    expect(screen.getByRole("combobox", { name: "Destination zone" })).toHaveTextContent("All destination zones");
  });

  it("lists the per-cell counts with 'at least' and '+N more' when capped, and a row sets the zone filters", async () => {
    serve(summary({
      cell_counts: [cell(), cell({ source_zone_id: "z2", destination_zone_id: "z1", source_name: "Zone-Bravo", destination_name: "Zone-Alpha", headline_rules: 1, outside_headline_rules: 0, highest_severity: "LOW" })],
      cell_counts_exact: false, cell_counts_capped: true, cell_counts_returned: 2, cell_counts_total: 9, cell_counts_more: 7,
    }));
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
    const panel = await screen.findByTestId("violations-cell-counts");
    expect(within(panel).getByRole("button", { name: /^Zone-Alpha -> Zone-Bravo/ }))
      .toHaveTextContent("Zone-Alpha -> Zone-Bravo · at least 3 rules · +1 outside headline · High");
    expect(within(panel).getByRole("button", { name: /^Zone-Bravo -> Zone-Alpha/ }))
      .toHaveTextContent("Zone-Bravo -> Zone-Alpha · at least 1 rule · Low");
    expect(within(panel).getByTestId("cell-counts-more")).toHaveTextContent("+7 more cells not shown");

    fireEvent.click(within(panel).getByRole("button", { name: /^Zone-Bravo -> Zone-Alpha/ }));
    const table = await screen.findByRole("table", { name: "Violation findings" });
    expect(screen.getByRole("combobox", { name: "Source zone" })).toHaveTextContent("Zone-Bravo");
    expect(screen.getByRole("combobox", { name: "Destination zone" })).toHaveTextContent("Zone-Alpha");
    expect(within(table).getByText(/RULE-FOXTROT/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ECHO/)).not.toBeInTheDocument();
  });

  it("draws no per-cell panel when the stored summary has no counts", async () => {
    serve(summary({ cell_counts: [] }));
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
    await screen.findByRole("table", { name: "Violation findings" });
    expect(screen.queryByTestId("violations-cell-counts")).not.toBeInTheDocument();
  });
});

describe("Zone matrix Versions tab: resolved by matrix version", () => {
  it("shows each version's resolved count from the stored summary and names the resolving versions", async () => {
    serve(summary({
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 2, returned: 0, more: 2, capped: true, last_7_days: [],
        by_version: [{ version_id: "v-active", version_number: 3, count: 2 }],
      },
    }));
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Versions" }));
    const table = await screen.findByRole("table", { name: "Version history" });
    await waitFor(() => expect(within(table).getByTestId("resolved-by-v-active")).toHaveTextContent("2"));
    expect(within(table).getByTestId("resolved-by-v-old")).toHaveTextContent("0");
    expect(screen.getByTestId("resolved-by-version-note")).toHaveTextContent("2 findings are, in total (all time), counted as resolved by a matrix version change, by matrix version: version 3 (2).");
  });

  it("does not present the all-time per-version counts as a 7-day figure when the window total is smaller", async () => {
    serve(summary({
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 2, returned: 0, more: 2, capped: true, last_7_days: [],
        by_version: [{ version_id: "v-active", version_number: 3, count: 9 }],
      },
    }));
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Versions" }));
    const note = await screen.findByTestId("resolved-by-version-note");
    expect(note).toHaveTextContent("9 findings are, in total (all time)");
    expect(note).toHaveTextContent("version 3 (9)");
    expect(note).not.toHaveTextContent("7 days");
    expect(screen.getByRole("columnheader", { name: /all time/ })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: /last 7 days/ })).not.toBeInTheDocument();
  });

  it("shows a dash, not 0, until the stored summary is READY", async () => {
    serve(summary({ state: "NO_COMPLETE_RUN" }));
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Versions" }));
    const table = await screen.findByRole("table", { name: "Version history" });
    expect(within(table).getByTestId("resolved-by-v-active")).toHaveTextContent("—");
    expect(screen.queryByTestId("resolved-by-version-note")).not.toBeInTheDocument();
  });
});
