import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { headlineProven } from "../src/screens/zonematrix/ViolationsTab";
import { ageText } from "../src/screens/zonematrix/zoneMatrixSummaryUi";
import type { SummaryFinding, SummaryHeadline, SummaryPolicy, ZoneMatrixSummaryResponse } from "../src/screens/zonematrix/zoneMatrixApi";

/** Zone matrix step 4b-2b part 2: Violations and Coverage tabs on the stored summary. Synthetic values only. */
const HASH = "a".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN = { view_blocks: false, edit: false, publish: false };
const activeSummary = { version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z" };
const activeDetail = { ...activeSummary, zones: [], unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [] };

const run = (over: Record<string, unknown> = {}) => ({
  run_id: "run-1", state: "COMPLETE", failure_reason: null, version_id: "v-active", version_number: 3, content_sha256: HASH,
  started_at: "2026-11-25T07:00:00Z", finished_at: "2026-11-25T07:05:00Z", snapshot_collected_at: "2026-11-25T06:00:00Z",
  coverage: "COMPLETE", counts: null, ...over,
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
  violated_pairs: [{ source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [] }],
  first_observed_at: "2026-11-20T08:00:00Z", first_observed_any_version: "2026-11-18T08:00:00Z", last_observed_at: "2026-11-25T07:05:00Z",
  run_id: "run-1", version_id: "v-active", version_number: 3, content_sha256: HASH, on_active_version: true,
  policy_staleness: "CURRENT", snapshot_age_seconds: 5400, ...over,
});
const headline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active", version_number: 3, distinct_violating_rules: 1, violated_zone_pairs: 1, critical_rules: 0,
  covered_by_earlier_rule: 0, conditional_rules: 0, outside_headline_ordered_layer: 0, not_evaluable_findings: 0, unknown_findings: 0,
  became_undecided_findings: 0, stale_policies: 0, incomplete_policies: 0, older_version_policies: 0, not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 0, exact: true, count_text: "1 violating rule in 1 policy",
  text: "1 rule violates the zone matrix", outside_headline_text: null,
  staleness_headline: { count: 0, exact: true, text: "All evaluated policies are current" }, ...over,
});
const summary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  evaluated_at: "2026-11-25T08:00:00Z", state: "READY", state_text: "findings of the latest complete run of each policy",
  masked: false, policy_names_masked: false, witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: headline(), headline_absent_reason: null, policies: [policy()], not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 1, total_findings: 1, returned: 1, more: 0, capped: false,
  findings: [finding()],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  ...over,
});

interface Served { readonly gets: string[]; summaryStatus: number; body: unknown; answer: (url: string) => unknown }
function serve(init: { status?: number; body?: unknown; answer?: (url: string) => unknown } = {}): Served {
  const served: Served = { gets: [], summaryStatus: init.status ?? 200, body: init.body ?? summary(), answer: init.answer ?? (() => undefined) };
  const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    served.gets.push(url);
    const path = url.split("?")[0];
    if (path === "/api/v2/zone-matrix/summary") {
      const custom = served.answer(url);
      return custom !== undefined ? reply(served.summaryStatus, custom) : reply(served.summaryStatus, served.body);
    }
    if (path === "/api/v2/zone-matrix/draft") return reply(200, { active: activeSummary, draft: null, diff: null, publish_check: null, can: CAN });
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeDetail, can: CAN });
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeSummary], can: CAN });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
  return served;
}

const open = async (name: "Violations" | "Coverage") => {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name }));
};
const summaryGets = (s: Served) => s.gets.filter((u) => u.startsWith("/api/v2/zone-matrix/summary"));

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

describe("Zone matrix: tab list and single summary read", () => {
  it("orders the tabs Matrix, Violations, Zones, Versions, Evaluate, Coverage and reads the stored summary once when zones load", async () => {
    const s = serve();
    render(<ZoneMatrixScreen />);
    await screen.findByRole("tab", { name: "Coverage" });
    expect(screen.getAllByRole("tab").map((t) => t.textContent)).toEqual(["Matrix", "Violations", "Zones", "Versions", "Evaluate", "Coverage"]);
    // The screen starts the one summary read once the zones load (for the coverage strip on every tab); switching tabs adds no second read.
    await waitFor(() => expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]));
    fireEvent.click(screen.getByRole("tab", { name: "Zones" }));
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);
    fireEvent.click(screen.getByRole("tab", { name: "Violations" }));
    await screen.findByRole("table", { name: "Violation findings" });
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);
    // The Coverage tab reuses the same read.
    fireEvent.click(screen.getByRole("tab", { name: "Coverage" }));
    await screen.findByRole("table", { name: "Policy coverage" });
    expect(summaryGets(s)).toHaveLength(1);
  });
});

describe("Zone matrix: Violations tab", () => {
  it("renders a ready state with its findings, names as sent, link to the policy viewer and what is counted", async () => {
    serve();
    await open("Violations");
    const table = await screen.findByRole("table", { name: "Violation findings" });
    const row = within(table).getByText(/RULE-ECHO/).closest("tr")!;
    expect(within(row).getByRole("link", { name: /Open #7 RULE-ECHO/ })).toHaveAttribute("href", "?screen=policy&policy_id=pol-1&rule=rule-7&standard=1");
    expect(within(row).getByText("POLICY-ALFA")).toBeInTheDocument();
    expect(within(row).getByTestId("violated-pair")).toHaveTextContent("ZONE-ALFA -> ZONE-BRAVO · Deny all · High");
    expect(within(row).getByText("Violation")).toBeInTheDocument();
    expect(within(row).getByText("Version 3")).toBeInTheDocument();
    expect(within(row).getByText("Yes")).toBeInTheDocument();
    expect(within(row).getByText(/snapshot age 1 h 30 min/)).toBeInTheDocument();
    expect(screen.getByTestId("findings-count")).toHaveTextContent("1 of 1 finding returned by the server.");
    expect(screen.getByTestId("summary-headline")).toHaveTextContent("1 rule violates the zone matrix");
    expect(screen.getByTestId("headline-count-text")).toHaveTextContent("1 violating rule in 1 policy");
    expect(screen.getByText("Resolved by a version change in the last 7 days")).toBeInTheDocument();
  });

  it("prints '+N more not shown' when the list is capped and says 'at least' for an inexact headline", async () => {
    serve({ body: summary({ capped: true, more: 37, total_findings: 38, unfiltered_findings: 38, returned: 1,
      headline: headline({ exact: false, distinct_violating_rules: 38, outside_headline_text: "3 more rules sit in other ordered layers" }) }) });
    await open("Violations");
    await screen.findByRole("table", { name: "Violation findings" });
    expect(screen.getByTestId("findings-count")).toHaveTextContent("1 of 38 findings returned by the server; +37 more not shown (the list is capped at 200).");
    expect(screen.getByText("At least 38 violating rules")).toBeInTheDocument();
    expect(screen.getByTestId("outside-headline-text")).toHaveTextContent("3 more rules sit in other ordered layers");
  });

  it("highlights BECAME_UNDECIDED as words, shows the covering rule, and keeps UNKNOWN and NOT_EVALUABLE rows", async () => {
    serve({ body: summary({ total_findings: 3, unfiltered_findings: 3, returned: 3, findings: [
      finding({ flags: ["BECAME_UNDECIDED"], outcome: "UNKNOWN", reason: "zone of a destination is not decidable", covering_rule_id: "rule-3" }),
      finding({ rule_uid: "uid-8", rule_id: "rule-8", rule_number: 8, rule_name: "RULE-FOXTROT", outcome: "NOT_EVALUABLE", highest_severity: null, reason: "service not evaluable",
        violated_pairs: [] }),
      finding({ rule_uid: "uid-9", rule_id: "rule-9", rule_number: 9, rule_name: "RULE-GOLF", flags: ["COVERED_BY_EARLIER_DENY"], covering_rule_id: "rule-2" }),
    ] }) });
    await open("Violations");
    const table = await screen.findByRole("table", { name: "Violation findings" });
    const undecided = within(table).getByText(/RULE-ECHO/).closest("tr")!;
    expect(within(undecided).getByTestId("flag-became-undecided")).toHaveTextContent("Became undecided");
    expect(within(undecided).queryByText("BECAME_UNDECIDED")).not.toBeInTheDocument();
    expect(within(undecided).getByText("Outcome unknown")).toBeInTheDocument();
    expect(within(undecided).getByText("rule-3")).toBeInTheDocument();
    const notEvaluable = within(table).getByText(/RULE-FOXTROT/).closest("tr")!;
    expect(within(notEvaluable).getByText("Not evaluable")).toBeInTheDocument();
    expect(within(notEvaluable).getByText("service not evaluable")).toBeInTheDocument();
    expect(within(notEvaluable).getAllByText("UNKNOWN").length).toBeGreaterThanOrEqual(2); // no zone pair, no severity
    const covered = within(table).getByText(/RULE-GOLF/).closest("tr")!;
    expect(within(covered).getByText("Covered by earlier deny")).toBeInTheDocument();

    // Client-side filters only narrow what was returned, and say so.
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "State" }));
    fireEvent.click(await screen.findByRole("option", { name: "Became undecided" }));
    await waitFor(() => expect(screen.queryByText(/RULE-GOLF/)).not.toBeInTheDocument());
    expect(screen.getByText(/RULE-ECHO/)).toBeInTheDocument();
    expect(screen.getByTestId("findings-count")).toHaveTextContent("3 of 3 findings returned by the server; 1 match the filters on this page.");
  });

  it("sends the outcome and policy filters to the server", async () => {
    const s = serve();
    await open("Violations");
    await screen.findByRole("table", { name: "Violation findings" });
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Outcome" }));
    fireEvent.click(await screen.findByRole("option", { name: "Not evaluable" }));
    await waitFor(() => expect(summaryGets(s)).toContain("/api/v2/zone-matrix/summary?outcome=NOT_EVALUABLE"));
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Policy" }));
    fireEvent.click(await screen.findByRole("option", { name: "POLICY-ALFA" }));
    await waitFor(() => expect(summaryGets(s)).toContain("/api/v2/zone-matrix/summary?policy_id=pol-1&outcome=NOT_EVALUABLE"));
  });

  it("is never green when a policy is stale or incomplete, even with zero violating rules", async () => {
    const data = summary({
      headline: headline({ distinct_violating_rules: 0, stale_policies: 1, incomplete_policies: 1, count_text: "0 violating rules found",
        text: "No violation found in the evaluable part", staleness_headline: { count: 1, exact: false, text: "At least 1 policy is stale" } }),
      policies: [
        policy({ staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 200000, snapshot_age_known: true } }),
        policy({ policy_id: "pol-2", policy_name: "POLICY-BRAVO", coverage: "INCOMPLETE", coverage_items: ["Inline layers not collected"] }),
      ],
      total_findings: 0, unfiltered_findings: 0, returned: 0, findings: [],
    });
    serve({ body: data });
    await open("Violations");
    await screen.findByTestId("summary-headline");
    expect(screen.getByTestId("summary-headline")).toHaveAttribute("data-proven", "no");
    expect(screen.getByText("No violation found, not proven")).toBeInTheDocument();
    expect(screen.queryByText("No violating rule found, exact")).not.toBeInTheDocument();
    expect(screen.getByTestId("staleness-headline")).toHaveTextContent("At least 1 policy is stale");
    expect(screen.getByText(/The server returned no finding for these filters\. This is not a statement that the policies comply/)).toBeInTheDocument();
    expect(headlineProven(data, data.headline!)).toBe(false);
  });

  it("shows green only when the response proves it", () => {
    const clean = summary({ headline: headline({ distinct_violating_rules: 0 }), findings: [], total_findings: 0, returned: 0, unfiltered_findings: 0 });
    expect(headlineProven(clean, clean.headline!)).toBe(true);
    for (const bad of [{ exact: false }, { stale_policies: 1 }, { incomplete_policies: 1 }, { not_evaluated_policies: 1 }, { unknown_findings: 1 },
      { not_evaluable_findings: 1 }, { became_undecided_findings: 1 }, { older_version_policies: 1 }]) {
      expect(headlineProven(clean, headline({ distinct_violating_rules: 0, ...bad }))).toBe(false);
    }
    expect(headlineProven({ ...clean, not_evaluated: [{ id: "x", name: "N", vendor: "asa", source_id: null, source_name: null, reason: "no policy collection" }] }, clean.headline!)).toBe(false);
  });

  it("prints the interim threshold label and the resolved-by-version-change list", async () => {
    serve({ body: summary({ resolved_by_version_change: { window_days: 7, total_last_7_days: 5, returned: 1, more: 4, capped: true, by_version: [],
      last_7_days: [{ ...finding(), resolved_from_version_id: "v-old", resolved_from_version_number: 2, resolved_in_version_id: "v-active",
        resolved_in_version_number: 3, resolved_at: "2026-11-24T09:00:00Z" }] } }) });
    await open("Violations");
    expect(await screen.findByTestId("staleness-threshold")).toHaveTextContent(`24 h (INTERIM): ${INTERIM}`);
    const resolved = screen.getByTestId("resolved-by-version");
    expect(within(resolved).getByTestId("resolved-count")).toHaveTextContent("5 findings resolved by a version change; 1 shown, +4 more not shown.");
    expect(within(resolved).getByText(/#7 RULE-ECHO/)).toHaveTextContent("version 2 to version 3");
  });

  it("shows the state text for NO_ACTIVE_VERSION and no finding list or green state", async () => {
    serve({ body: summary({ state: "NO_ACTIVE_VERSION", state_text: "no active zone matrix version: nothing is evaluated yet", headline: null,
      headline_absent_reason: "no active version: no headline row", active_version: null, findings: [], total_findings: 0, returned: 0, unfiltered_findings: 0 }) });
    await open("Violations");
    expect((await screen.findAllByText("no active zone matrix version: nothing is evaluated yet")).length).toBeGreaterThan(0);
    expect(screen.queryByRole("table", { name: "Violation findings" })).not.toBeInTheDocument();
    expect(screen.queryByText(/No violating rule found/)).not.toBeInTheDocument();
    expect(screen.getByText(/an empty list here would not mean there is nothing wrong/)).toBeInTheDocument();
  });

  it("shows the restricted panel for a refused read and the error panel with Retry for a failure", async () => {
    serve({ status: 403, body: { error: "FORBIDDEN", reason: "no" } });
    await open("Violations");
    expect(await screen.findByText("Zone matrix violations")).toBeInTheDocument();
    expect(screen.getByText(/This account can not view or change it\./)).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Violation findings" })).not.toBeInTheDocument();
  });

  it("retries after a failed read", async () => {
    const s = serve({ status: 500, body: { error: "INTERNAL", reason: "boom" } });
    await open("Violations");
    expect(await screen.findByText("The stored summary could not be read")).toBeInTheDocument();
    s.summaryStatus = 200;
    s.body = summary();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("table", { name: "Violation findings" })).toBeInTheDocument();
    expect(summaryGets(s)).toHaveLength(2);
  });

  it("shows only the names the server sent for a masked response", async () => {
    serve({ body: summary({ masked: true, policy_names_masked: true, policies: [policy({ policy_name: "POLICY-1" })],
      findings: [finding({ policy_name: "POLICY-1", rule_name: "RULE-2", violated_pairs: [{ source_zone_id: "z1", destination_zone_id: "z2",
        source_name: "ZONE-1", destination_name: "UNKNOWN", statement: null, severity: null, not_allowed_services: [] }] })] }) });
    await open("Violations");
    const row = (await screen.findByText(/RULE-2/)).closest("tr")!;
    expect(within(row).getByText("POLICY-1")).toBeInTheDocument();
    expect(within(row).getByTestId("violated-pair")).toHaveTextContent("ZONE-1 -> UNKNOWN · statement UNKNOWN · severity UNKNOWN");
    expect(screen.getByText("Names are shown as the server masked them.")).toBeInTheDocument();
    expect(screen.queryByText(/ZONE-ALFA|FW-TANGO/)).not.toBeInTheDocument();
  });

  it("does not link the rule when the finding has no rule id", async () => {
    serve({ body: summary({ findings: [finding({ rule_id: null })] }) });
    await open("Violations");
    const row = (await screen.findByText(/RULE-ECHO/)).closest("tr")!;
    expect(within(row).queryByRole("link")).not.toBeInTheDocument();
  });
});

describe("Zone matrix: Coverage tab", () => {
  it("prints an unknown snapshot age and null collection failures as UNKNOWN, never 0", async () => {
    serve({ body: summary({ policies: [
      policy({ staleness: { verdict: "STALE", reasons: ["SNAPSHOT_TIME_UNKNOWN"], snapshot_age_seconds: null, snapshot_age_known: false },
        coverage: "INCOMPLETE", collection_failures: null, has_complete_run: false, latest_complete_run: null,
        latest_run: run({ state: "FAILED", failure_reason: "snapshot unreadable", finished_at: "2026-11-25T07:10:00Z" }) }),
    ] }) });
    await open("Coverage");
    const table = await screen.findByRole("table", { name: "Policy coverage" });
    const row = within(table).getByText("POLICY-ALFA").closest("tr")!;
    expect(within(row).getByTestId("snapshot-age")).toHaveTextContent("UNKNOWN");
    expect(within(row).getByTestId("snapshot-age")).not.toHaveTextContent("0");
    expect(within(row).getByTestId("collection-failures")).toHaveTextContent("UNKNOWN");
    expect(within(row).getByText("STALE")).toBeInTheDocument();
    expect(within(row).getByTestId("stale-reason")).toHaveTextContent("Snapshot time unknown");
    expect(within(row).getByText("INCOMPLETE")).toBeInTheDocument();
    expect(within(row).getByText("No complete run: coverage is not known.")).toBeInTheDocument();
    expect(within(row).getByText(/Latest run: Failed · version 3 · finished/)).toBeInTheDocument();
    expect(within(row).getByText("snapshot unreadable")).toBeInTheDocument();
    expect(within(row).getByText(/Latest complete run:/)).toHaveTextContent("UNKNOWN");
  });

  it("shows a current complete policy, coverage items, failure count, snapshot age and finish time", async () => {
    serve({ body: summary({ policies: [policy({ coverage: "COMPLETE", coverage_items: ["Rulebase of the global layer"], collection_failures: 0 })] }) });
    await open("Coverage");
    const row = (await screen.findByText("POLICY-ALFA")).closest("tr")!;
    expect(within(row).getByText("CURRENT")).toBeInTheDocument();
    expect(within(row).getByText("COMPLETE")).toBeInTheDocument();
    expect(within(row).getByText("Rulebase of the global layer")).toBeInTheDocument();
    expect(within(row).getByTestId("collection-failures")).toHaveTextContent("0");
    expect(within(row).getByTestId("snapshot-age")).toHaveTextContent("1 h 30 min");
    expect(within(row).getAllByTestId("run-cell")).toHaveLength(2);
  });

  it("shows a stale policy with its reasons and an incomplete policy with its items, and the interim threshold label", async () => {
    serve({ body: summary({ policies: [
      policy({ staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD", "VERSION_NOT_ACTIVE"], snapshot_age_seconds: 200000, snapshot_age_known: true } }),
      policy({ policy_id: "pol-2", policy_name: "POLICY-BRAVO", coverage: "INCOMPLETE", coverage_items: ["Inline layer not collected", "Object group not expanded"], collection_failures: 2 }),
    ] }) });
    await open("Coverage");
    const table = await screen.findByRole("table", { name: "Policy coverage" });
    const stale = within(table).getByText("POLICY-ALFA").closest("tr")!;
    expect(within(stale).getAllByTestId("stale-reason").map((e) => e.textContent)).toEqual([
      "Snapshot older than the staleness threshold", "Latest complete run used a version that is not the active one"]);
    expect(within(stale).getByTestId("snapshot-age")).toHaveTextContent("2 d 7 h");
    const incomplete = within(table).getByText("POLICY-BRAVO").closest("tr")!;
    expect(within(incomplete).getByText("INCOMPLETE")).toBeInTheDocument();
    expect(within(incomplete).getByText("Inline layer not collected")).toBeInTheDocument();
    expect(within(incomplete).getByText("Object group not expanded")).toBeInTheDocument();
    expect(within(incomplete).getByTestId("collection-failures")).toHaveTextContent("2");
    expect(screen.getByTestId("staleness-threshold")).toHaveTextContent(INTERIM);
  });

  it("does not print the interim label when the threshold is not the interim value", async () => {
    serve({ body: summary({ staleness_threshold: { seconds: 43200, hours: 12, interim: false, source: "bank answer", label: "source: bank answer" } }) });
    await open("Coverage");
    const note = await screen.findByTestId("staleness-threshold");
    expect(note).toHaveTextContent("12 h: source: bank answer.");
    expect(note).not.toHaveTextContent("INTERIM");
    expect(note).not.toHaveTextContent("interim value");
  });

  it("lists the policies that are not evaluated with their reasons", async () => {
    serve({ body: summary({ not_evaluated: [{ id: "na-1", name: "POLICY-CHARLIE", vendor: "asa", source_id: "s2", source_name: "FW-JULIET-06",
      reason: "no policy collection for this vendor" }] }) });
    await open("Coverage");
    const table = await screen.findByRole("table", { name: "Policies not evaluated" });
    const row = within(table).getByText(/POLICY-CHARLIE/).closest("tr")!;
    expect(within(row).getByText("no policy collection for this vendor")).toBeInTheDocument();
    expect(screen.getByText("Policies not evaluated (1)")).toBeInTheDocument();
  });

  it("shows the state text when no policy has a complete run, and never an empty table as clean", async () => {
    serve({ body: summary({ state: "NO_COMPLETE_RUN", state_text: "no policy has a complete evaluation run yet", headline: null, policies: [] }) });
    await open("Coverage");
    expect(await screen.findByTestId("summary-state-text")).toHaveTextContent("no policy has a complete evaluation run yet");
    expect(screen.getByText(/No stored Check Point or Palo Alto policy is listed\. This is not a statement that nothing needs evaluating\./)).toBeInTheDocument();
  });

  it("shows the restricted panel for a refused read", async () => {
    serve({ status: 403, body: { error: "FORBIDDEN", reason: "no" } });
    await open("Coverage");
    expect(await screen.findByText("Zone matrix coverage")).toBeInTheDocument();
    expect(screen.getByText(/This account can not view or change it\./)).toBeInTheDocument();
  });

  it("shows the error panel with Retry for a failure", async () => {
    serve({ status: 500, body: { error: "INTERNAL", reason: "boom" } });
    await open("Coverage");
    expect(await screen.findByText("The stored summary could not be read")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});

describe("ageText", () => {
  it("prints UNKNOWN for an age that is not known", () => {
    expect(ageText(null)).toBe("UNKNOWN");
    expect(ageText(undefined)).toBe("UNKNOWN");
    expect(ageText(-5)).toBe("UNKNOWN");
    expect(ageText(0)).toBe("0 s");
    expect(ageText(5400)).toBe("1 h 30 min");
  });
});
