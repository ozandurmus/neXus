import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { deriveCoverageSummary, isCoverageClean } from "../src/screens/zonematrix/CoverageStrip";
import type {
  SummaryFinding, SummaryHeadline, SummaryNotEvaluated, SummaryPolicy, ZoneMatrixPoliciesResponse, ZoneMatrixSummaryResponse,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Zone matrix coverage strip on every tab (ZONE_MATRIX_CONTRACT.md 7.1, 7.2, ZM-26, ZM-30, ZM-35, Amendment 2026-11-23).
 * Synthetic test fixtures only.
 */
const HASH = "a".repeat(64);
const OLD_HASH = "b".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN = { view_blocks: false, edit: false, publish: false, evaluate: false };
const FORBIDDEN_WORDS = /compliant|bddk|\bpass(ed)?\b|score|percent|%/i;

const activeVersionSummary = {
  version_id: "v-active",
  number: 3,
  state: "ACTIVE",
  revision: 4,
  content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z",
  updated_at: "2026-11-12T08:00:00Z",
  published_by: "USER-2",
  published_at: "2026-11-12T08:00:00Z",
};

const activeVersionDetail = {
  ...activeVersionSummary,
  zones: [
    {
      zone_id: "z1",
      name: "Z-APP",
      description: null,
      role: "APPLICATION",
      block_count: 1,
      effective_addresses: { IPV4: "256", IPV6: "0" },
      blocks: null,
    },
    {
      zone_id: "z2",
      name: "Z-DB",
      description: null,
      role: "DATABASE",
      block_count: 1,
      effective_addresses: { IPV4: "256", IPV6: "0" },
      blocks: null,
    },
  ],
  unassigned_addresses: { IPV4: "4294966784", IPV6: "0" },
  redundant_blocks: 0,
  revisions: [],
};

const policiesCatalogFixture: Omit<ZoneMatrixPoliciesResponse, "can"> = {
  policies: [
    {
      id: "pol-1",
      name: "PLANTED-POLICY-ALFA",
      vendor: "check_point",
      source_id: "src-1",
      source_name: "FW-TANGO-04",
      container_id: null,
      container_name: null,
      collected_at: "2026-11-25T06:00:00Z",
      rule_count: 12,
      target_count: 1,
    },
  ],
  not_evaluated: [],
};

const run = (over: Record<string, unknown> = {}) => ({
  run_id: "run-1",
  state: "COMPLETE",
  failure_reason: null,
  version_id: "v-active",
  version_number: 3,
  content_sha256: HASH,
  started_at: "2026-11-25T07:00:00Z",
  finished_at: "2026-11-25T07:05:00Z",
  snapshot_collected_at: "2026-11-25T06:00:00Z",
  coverage: "COMPLETE",
  counts: null,
  ...over,
});

const makePolicy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-1",
  policy_name: "PLANTED-POLICY-ALFA",
  vendor: "check_point",
  source_id: "src-1",
  source_name: "FW-TANGO-04",
  container_id: "cnt-1",
  container_name: "DOM-BRAVO-02",
  collected_at: "2026-11-25T06:00:00Z",
  rule_count: 12,
  staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 5400, snapshot_age_known: true },
  coverage: "COMPLETE",
  coverage_items: [],
  collection_failures: 0,
  has_complete_run: true,
  latest_complete_run: run(),
  latest_run: run(),
  ...over,
});

const makeNotEvaluated = (over: Partial<SummaryNotEvaluated> = {}): SummaryNotEvaluated => ({
  id: "na-1",
  name: "PLANTED-ASA-CHARLIE",
  vendor: "cisco_asa",
  source_id: "asa-1",
  source_name: "FW-JULIET-06",
  reason: "not evaluated: policy not collected",
  ...over,
});

const makeFinding = (over: Partial<SummaryFinding> = {}): SummaryFinding => ({
  policy_source_id: "pol-1",
  policy_name: "PLANTED-POLICY-ALFA",
  rule_uid: "uid-7",
  inline_parent_path: [],
  rule_id: "rule-7",
  rule_number: 7,
  rule_name: "RULE-ECHO",
  target_device_ids: [],
  outcome: "VIOLATION",
  reason: null,
  highest_severity: "HIGH",
  flags: [],
  covering_rule_id: null,
  undecided_parts: [],
  violated_pairs: [{
    source_zone_id: "z1",
    destination_zone_id: "z2",
    source_name: "Z-APP",
    destination_name: "Z-DB",
    statement: "DENY_ALL",
    severity: "HIGH",
    not_allowed_services: [],
  }],
  first_observed_at: "2026-11-20T08:00:00Z",
  first_observed_any_version: "2026-11-18T08:00:00Z",
  last_observed_at: "2026-11-25T07:05:00Z",
  run_id: "run-1",
  version_id: "v-active",
  version_number: 3,
  content_sha256: HASH,
  on_active_version: true,
  policy_staleness: "CURRENT",
  snapshot_age_seconds: 5400,
  ...over,
});

const makeHeadline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active",
  version_number: 3,
  distinct_violating_rules: 0,
  violated_zone_pairs: 0,
  critical_rules: 0,
  covered_by_earlier_rule: 0,
  conditional_rules: 0,
  outside_headline_ordered_layer: 0,
  not_evaluable_findings: 0,
  unknown_findings: 0,
  became_undecided_findings: 0,
  stale_policies: 0,
  incomplete_policies: 0,
  older_version_policies: 0,
  not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 0,
  exact: true,
  count_text: "0",
  text: "0 rules violate the zone matrix (v3)",
  outside_headline_text: null,
  staleness_headline: { count: 0, exact: true, text: "All evaluated policies are current" },
  ...over,
});

const makeSummary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  evaluated_at: "2026-11-25T08:00:00Z",
  state: "READY",
  state_text: "findings of the latest complete run of each policy",
  masked: false,
  policy_names_masked: false,
  witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: makeHeadline(),
  headline_absent_reason: null,
  policies: [makePolicy()],
  not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 },
  unfiltered_findings: 0,
  total_findings: 0,
  returned: 0,
  more: 0,
  capped: false,
  findings: [],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  ...over,
});

function serveScreen(opts: {
  summaryStatus?: number;
  summaryBody?: unknown;
  summaryHandler?: (url: string) => Promise<Response> | Response;
  noActive?: boolean;
} = {}) {
  const requests: string[] = [];
  const reply = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    requests.push(url);
    const [path] = url.split("?");
    if (path === "/api/v2/zone-matrix/summary") {
      if (opts.summaryHandler) return await opts.summaryHandler(url);
      return reply(opts.summaryStatus ?? 200, opts.summaryBody ?? makeSummary());
    }
    if (opts.noActive) {
      if (path === "/api/v2/zone-matrix/draft") return reply(200, { active: null, draft: null, diff: null, publish_check: null, can: CAN });
      if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [], can: CAN });
    }
    if (path === "/api/v2/zone-matrix/draft") {
      return reply(200, { active: activeVersionSummary, draft: null, diff: null, publish_check: null, can: CAN });
    }
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeVersionDetail, can: CAN });
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeVersionSummary], can: CAN });
    if (path === "/api/v2/zone-matrix/policies") return reply(200, { ...policiesCatalogFixture, can: CAN });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));

  return requests;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Zone matrix coverage strip", () => {
  it("renders a READY summary with mixed policies, oldest evaluated snapshot age, and expandable detail without a table", async () => {
    const mixed = makeSummary({
      policies: [
        makePolicy({
          policy_id: "pol-1",
          policy_name: "PLANTED-POLICY-ALFA",
          staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 3600, snapshot_age_known: true },
          coverage: "COMPLETE",
        }),
        makePolicy({
          policy_id: "pol-2",
          policy_name: "PLANTED-POLICY-BRAVO",
          staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 200000, snapshot_age_known: true },
          coverage: "COMPLETE",
        }),
        makePolicy({
          policy_id: "pol-3",
          policy_name: "PLANTED-POLICY-CHARLIE",
          vendor: "palo_alto",
          staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 7200, snapshot_age_known: true },
          coverage: "INCOMPLETE",
          coverage_items: ["PAN_LOCAL_RULES_NOT_COLLECTED", "intrazone default allow; not evaluated"],
        }),
        makePolicy({
          policy_id: "pol-4",
          policy_name: "PLANTED-POLICY-DELTA",
          staleness: { verdict: "STALE", reasons: ["VERSION_NOT_ACTIVE"], snapshot_age_seconds: 5400, snapshot_age_known: true },
          coverage: "COMPLETE",
          latest_complete_run: run({ version_id: "v-old", version_number: 2, content_sha256: OLD_HASH }),
        }),
      ],
      not_evaluated: [
        makeNotEvaluated({ id: "asa-1", name: "PLANTED-ASA-ECHO", vendor: "cisco_asa", reason: "not evaluated: policy not collected" }),
        makeNotEvaluated({ id: "fgt-1", name: "PLANTED-FGT-FOXTROT", vendor: "fortinet", reason: "not evaluated: policy not collected" }),
      ],
      headline: makeHeadline({
        distinct_violating_rules: 3,
        stale_policies: 2,
        incomplete_policies: 1,
        older_version_policies: 1,
        not_evaluated_policies: 2,
        exact: false,
        count_text: "at least 3",
        text: "at least 3 rules violate the zone matrix (v3) across 1 zone pair; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)",
        staleness_headline: { count: 3, exact: false, text: "at least 3" },
      }),
    });

    serveScreen({ summaryBody: mixed });
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(strip).toHaveAttribute("data-coverage-state", "partial"));
    expect(strip).toHaveAttribute("data-clean", "no");
    expect(strip.querySelector("[data-pill='ok']")).toBeNull();
    expect(strip.querySelector("[data-pill='warn']")).not.toBeNull();

    const line = within(strip).getByTestId("coverage-strip-line");
    expect(line).toHaveTextContent("1 of 4 policies CURRENT and COMPLETE");
    expect(line).toHaveTextContent("2 STALE");
    expect(line).toHaveTextContent("1 INCOMPLETE");
    expect(line).toHaveTextContent("1 older-version");
    expect(line).toHaveTextContent("2 not evaluated");
    expect(line).toHaveTextContent("violation counts are at least 3");
    expect(within(strip).getByTestId("coverage-strip-oldest-age")).toHaveTextContent("2 d 7 h");

    // Collapsed by default; expanding shows ThresholdNote, reasons, coverage items and vendor reasons without a table.
    expect(within(strip).queryByTestId("coverage-strip-detail")).not.toBeInTheDocument();
    fireEvent.click(within(strip).getByRole("button", { name: "Coverage details" }));
    const detail = within(strip).getByTestId("coverage-strip-detail");
    expect(within(detail).getByTestId("coverage-strip-threshold")).toHaveTextContent(`24 h (INTERIM): ${INTERIM}`);
    expect(detail).toHaveTextContent("Every age is the age of the evidence");
    expect(detail).toHaveTextContent("Evaluated snapshot ages range from 1 h 0 min to 2 d 7 h.");
    expect(detail).toHaveTextContent("findings of the latest complete run of each policy");
    expect(detail).toHaveTextContent("at least 3");
    expect(within(detail).getByTestId("coverage-strip-stale-reasons")).toHaveTextContent("Snapshot older than the staleness threshold (1)");
    expect(within(detail).getByTestId("coverage-strip-stale-reasons")).toHaveTextContent("Latest complete run used a version that is not the active one (1)");
    expect(within(detail).getByTestId("coverage-strip-items")).toHaveTextContent("PAN_LOCAL_RULES_NOT_COLLECTED (1)");
    expect(within(detail).getByTestId("coverage-strip-items")).toHaveTextContent("intrazone default allow; not evaluated (1)");
    expect(within(detail).getByTestId("coverage-strip-not-evaluated")).toHaveTextContent("Cisco ASA (1: not evaluated: policy not collected)");
    expect(within(detail).getByTestId("coverage-strip-not-evaluated")).toHaveTextContent("Fortinet (1: not evaluated: policy not collected)");
    expect(within(strip).queryByRole("table")).not.toBeInTheDocument();

    fireEvent.click(within(strip).getByRole("button", { name: "Hide coverage details" }));
    expect(within(strip).queryByTestId("coverage-strip-detail")).not.toBeInTheDocument();
  });

  it("shows the clean OK chip only when every covered policy is CURRENT and COMPLETE, not_evaluated is empty, and nothing is undecided", async () => {
    const clean = makeSummary({
      policies: [
        makePolicy({ policy_id: "pol-1", staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 1800, snapshot_age_known: true } }),
        makePolicy({ policy_id: "pol-2", staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 5400, snapshot_age_known: true } }),
      ],
      not_evaluated: [],
      headline: makeHeadline(),
    });

    serveScreen({ summaryBody: clean });
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(strip).toHaveAttribute("data-clean", "yes"));
    expect(strip.querySelector("[data-pill='ok']")).not.toBeNull();
    const line = within(strip).getByTestId("coverage-strip-line");
    expect(line).toHaveTextContent("2 of 2 policies CURRENT and COMPLETE");
    expect(line).toHaveTextContent("Oldest evaluated snapshot age: 1 h 30 min");
    // Zero counts are omitted.
    expect(line).not.toHaveTextContent("STALE");
    expect(line).not.toHaveTextContent("INCOMPLETE");
    expect(line).not.toHaveTextContent("older-version");
    expect(line).not.toHaveTextContent("not evaluated");
    expect(line).not.toHaveTextContent("at least");
  });

  it("shows 'coverage not known' with UNKNOWN styling while the summary has not yet been read", async () => {
    let resolveSummary!: (res: Response) => void;
    serveScreen({
      summaryHandler: () => new Promise<Response>((resolve) => { resolveSummary = resolve; }),
    });
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    expect(strip).toHaveAttribute("data-coverage-state", "unknown");
    expect(strip).toHaveAttribute("data-clean", "no");
    expect(strip.querySelector("[data-pill='unknown']")).not.toBeNull();
    expect(strip.querySelector("[data-pill='ok']")).toBeNull();
    expect(within(strip).getByTestId("coverage-strip-line")).toHaveTextContent(/coverage not known/i);

    resolveSummary(new Response(JSON.stringify(makeSummary()), { status: 200, headers: { "Content-Type": "application/json" } }));
    await waitFor(() => expect(strip).toHaveAttribute("data-clean", "yes"));
  });

  it.each([
    ["restricted (403)", { summaryStatus: 403, summaryBody: { error: "ACTION_REFUSED", reason: "forbidden" } }],
    ["failed read (500)", { summaryStatus: 500, summaryBody: { error: "INTERNAL", reason: "database timeout" } }],
    ["NO_ACTIVE_VERSION", {
      noActive: true,
      summaryBody: makeSummary({
        state: "NO_ACTIVE_VERSION",
        state_text: "no active zone matrix version: nothing is evaluated yet",
        active_version: null,
        headline: null,
        policies: [],
      }),
    }],
    ["empty policies (NO_EVALUABLE_POLICY)", {
      summaryBody: makeSummary({
        state: "NO_EVALUABLE_POLICY",
        state_text: "no stored Check Point or Palo Alto policy to evaluate",
        policies: [],
      }),
    }],
    ["empty policies even if state claims READY", {
      summaryBody: makeSummary({
        state: "READY",
        policies: [],
      }),
    }],
    ["NO_COMPLETE_RUN", {
      summaryBody: makeSummary({
        state: "NO_COMPLETE_RUN",
        state_text: "no policy has a complete evaluation run yet",
        policies: [makePolicy({
          staleness: { verdict: "STALE", reasons: ["VERSION_NOT_ACTIVE"], snapshot_age_seconds: null, snapshot_age_known: false },
          coverage: "INCOMPLETE",
          has_complete_run: false,
          latest_complete_run: null,
        })],
      }),
    }],
  ])("shows 'coverage not known' with UNKNOWN styling and never clean for %s", async (_label, opts) => {
    serveScreen(opts);
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(strip).toHaveAttribute("data-coverage-state", "unknown"));
    expect(strip).toHaveAttribute("data-clean", "no");
    expect(strip.querySelector("[data-pill='unknown']")).not.toBeNull();
    expect(strip.querySelector("[data-pill='ok']")).toBeNull();
    expect(within(strip).getByTestId("coverage-strip-line")).toHaveTextContent(/coverage not known/i);
    expect(strip.textContent ?? "").not.toMatch(FORBIDDEN_WORDS);
  });

  it("is not clean and states 'at least' when all covered policies are CURRENT and COMPLETE but one source is not evaluated", async () => {
    const body = makeSummary({
      policies: [
        makePolicy({ policy_id: "pol-1" }),
        makePolicy({ policy_id: "pol-2", policy_name: "PLANTED-POLICY-BRAVO" }),
      ],
      not_evaluated: [makeNotEvaluated()],
      headline: makeHeadline({
        not_evaluated_policies: 1,
        staleness_headline: { count: 0, exact: true, text: "0" },
      }),
    });

    expect(isCoverageClean(body)).toBe(false);
    serveScreen({ summaryBody: body });
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(strip).toHaveAttribute("data-coverage-state", "partial"));
    expect(strip).toHaveAttribute("data-clean", "no");
    expect(strip.querySelector("[data-pill='ok']")).toBeNull();
    expect(strip.querySelector("[data-pill='warn']")).not.toBeNull();
    const line = within(strip).getByTestId("coverage-strip-line");
    expect(line).toHaveTextContent("2 of 2 policies CURRENT and COMPLETE");
    expect(line).toHaveTextContent("1 not evaluated");
    expect(line).toHaveTextContent("violation counts are at least 0");

    // Expanding never prints a bare "0" from staleness_headline.text when not clean.
    fireEvent.click(within(strip).getByRole("button", { name: "Coverage details" }));
    expect(within(strip).getByTestId("coverage-strip-detail")).toHaveTextContent("at least 0");
  });

  it("never counts a stale or incomplete policy as clean, and is not clean when a finding is NOT_EVALUABLE", () => {
    const withStale = makeSummary({
      policies: [
        makePolicy({ policy_id: "pol-1", staleness: { verdict: "STALE", reasons: ["LAST_RUN_FAILED"], snapshot_age_seconds: 1200, snapshot_age_known: true } }),
        makePolicy({ policy_id: "pol-2", coverage: "INCOMPLETE", coverage_items: ["PAN_LOCAL_RULES_NOT_COLLECTED"] }),
      ],
      headline: makeHeadline({ stale_policies: 1, incomplete_policies: 1, exact: false }),
    });
    const derived = deriveCoverageSummary(withStale);
    expect(derived.cleanPolicies).toBe(0);
    expect(derived.clean).toBe(false);

    const withNotEvaluable = makeSummary({
      policies: [makePolicy()],
      headline: makeHeadline({ not_evaluable_findings: 1, exact: false }),
    });
    expect(isCoverageClean(withNotEvaluable)).toBe(false);
  });

  it("reverts to 'coverage not known' if a subsequent summary re-read fails, never leaving a stale OK chip", async () => {
    let shouldFail = false;
    serveScreen({
      summaryHandler: () => shouldFail
        ? new Response(JSON.stringify({ error: "INTERNAL", reason: "read failed" }), { status: 500, headers: { "Content-Type": "application/json" } })
        : new Response(JSON.stringify(makeSummary()), { status: 200, headers: { "Content-Type": "application/json" } }),
    });
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(strip).toHaveAttribute("data-clean", "yes"));

    fireEvent.click(screen.getByRole("tab", { name: "Violations" }));
    await screen.findByRole("table", { name: "Violation findings" });
    shouldFail = true;
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Outcome" }));
    fireEvent.click(await screen.findByRole("option", { name: "Violation" }));

    await waitFor(() => expect(strip).toHaveAttribute("data-coverage-state", "unknown"));
    expect(strip.querySelector("[data-pill='ok']")).toBeNull();
    expect(within(strip).getByTestId("coverage-strip-line")).toHaveTextContent(/coverage not known/i);
  });

  it("renders on each of the six tabs and switches to the Coverage tab on 'Open Coverage'", async () => {
    const requests = serveScreen({ summaryBody: makeSummary() });
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(strip).toHaveAttribute("data-clean", "yes"));

    for (const tabName of ["Matrix", "Violations", "Zones", "Versions", "Evaluate", "Coverage"] as const) {
      fireEvent.click(screen.getByRole("tab", { name: tabName }));
      expect(screen.getByRole("tab", { name: tabName })).toHaveAttribute("aria-selected", "true");
      expect(screen.getByTestId("zone-matrix-coverage-strip")).toHaveTextContent("1 of 1 policies CURRENT and COMPLETE");
    }

    // Switch back to Zones, then use the strip's "Open Coverage" button to jump to Coverage.
    fireEvent.click(screen.getByRole("tab", { name: "Zones" }));
    expect(screen.getByRole("tab", { name: "Zones" })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(within(screen.getByTestId("zone-matrix-coverage-strip")).getByRole("button", { name: "Open Coverage" }));
    expect(screen.getByRole("tab", { name: "Coverage" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByRole("table", { name: "Policy coverage" })).toBeInTheDocument();

    // Only one summary request across all six tabs.
    expect(requests.filter((u) => u.startsWith("/api/v2/zone-matrix/summary"))).toEqual(["/api/v2/zone-matrix/summary"]);
  });

  it("keeps fleet-wide strip numbers unchanged when the Violations tab narrows findings by policy_id and outcome", async () => {
    const fleetPolicies = [
      makePolicy({ policy_id: "pol-1", policy_name: "PLANTED-POLICY-ALFA", staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 3600, snapshot_age_known: true } }),
      makePolicy({ policy_id: "pol-2", policy_name: "PLANTED-POLICY-BRAVO", staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 129600, snapshot_age_known: true } }),
      makePolicy({ policy_id: "pol-3", policy_name: "PLANTED-POLICY-CHARLIE", coverage: "INCOMPLETE", coverage_items: ["PAN_LOCAL_RULES_NOT_COLLECTED"] }),
    ];
    const fleetNotEvaluated = [makeNotEvaluated()];
    const fleetHeadline = makeHeadline({
      distinct_violating_rules: 1,
      stale_policies: 1,
      incomplete_policies: 1,
      not_evaluated_policies: 1,
      exact: false,
      count_text: "at least 1",
      text: "at least 1 rule violates the zone matrix (v3) across 1 zone pair; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)",
      staleness_headline: { count: 1, exact: false, text: "at least 1" },
    });

    const requests = serveScreen({
      summaryHandler: (url) => {
        const query = new URLSearchParams(url.split("?")[1] ?? "");
        const policyFilter = query.get("policy_id");
        const outcomeFilter = query.get("outcome");
        const filtered = Boolean(policyFilter || outcomeFilter);
        // Mirrors ZoneMatrixSummaryView.project: policy_id and outcome narrow `findings` only, never `headline`, `policies` or `not_evaluated`.
        const payload = makeSummary({
          policies: fleetPolicies,
          not_evaluated: fleetNotEvaluated,
          headline: fleetHeadline,
          filters: { policy_id: policyFilter, outcome: outcomeFilter, limit: 200 },
          unfiltered_findings: 1,
          total_findings: filtered ? 0 : 1,
          returned: filtered ? 0 : 1,
          findings: filtered ? [] : [makeFinding()],
        });
        return new Response(JSON.stringify(payload), { status: 200, headers: { "Content-Type": "application/json" } });
      },
    });

    render(<ZoneMatrixScreen />);
    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(within(strip).getByTestId("coverage-strip-line")).toHaveTextContent("1 of 3 policies CURRENT and COMPLETE"));
    const initialLineText = within(strip).getByTestId("coverage-strip-line").textContent;
    expect(initialLineText).toContain("1 STALE");
    expect(initialLineText).toContain("1 INCOMPLETE");
    expect(initialLineText).toContain("1 not evaluated");
    expect(initialLineText).toContain("1 d 12 h");

    fireEvent.click(screen.getByRole("tab", { name: "Violations" }));
    await screen.findByRole("table", { name: "Violation findings" });
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Policy" }));
    fireEvent.click(await screen.findByRole("option", { name: "PLANTED-POLICY-ALFA" }));
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Outcome" }));
    fireEvent.click(await screen.findByRole("option", { name: "Not evaluable" }));

    await waitFor(() =>
      expect(requests).toContain("/api/v2/zone-matrix/summary?policy_id=pol-1&outcome=NOT_EVALUABLE"));
    expect(await screen.findByTestId("findings-count")).toHaveTextContent("0 of 0 findings returned by the server; 1 before the server filters.");
    expect(within(strip).getByTestId("coverage-strip-line").textContent).toBe(initialLineText);
  });

  it("prints no policy or source names on the strip (normal or masked) and never uses forbidden compliance words (ZM-30)", async () => {
    const maskedBody = makeSummary({
      masked: true,
      policy_names_masked: true,
      policies: [
        makePolicy({
          policy_id: "pol-1",
          policy_name: "POLICY-MASKED-1",
          source_name: "FW-TANGO-04",
          container_name: "DOM-BRAVO-02",
          staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 100000, snapshot_age_known: true },
          coverage: "INCOMPLETE",
          coverage_items: ["PAN_LOCAL_RULES_NOT_COLLECTED"],
        }),
      ],
      not_evaluated: [
        makeNotEvaluated({ name: "POLICY-MASKED-2", source_name: "FW-JULIET-06" }),
      ],
      headline: makeHeadline({
        stale_policies: 1,
        incomplete_policies: 1,
        not_evaluated_policies: 1,
        exact: false,
        staleness_headline: { count: 0, exact: false, text: "at least 0" },
      }),
    });

    serveScreen({ summaryBody: maskedBody });
    render(<ZoneMatrixScreen />);

    const strip = await screen.findByTestId("zone-matrix-coverage-strip");
    await waitFor(() => expect(strip).toHaveAttribute("data-coverage-state", "partial"));
    fireEvent.click(within(strip).getByRole("button", { name: "Coverage details" }));
    expect(within(strip).getByTestId("coverage-strip-detail")).toBeInTheDocument();

    const stripText = strip.textContent ?? "";
    for (const planted of ["POLICY-MASKED-1", "POLICY-MASKED-2", "PLANTED-POLICY-ALFA", "FW-TANGO-04", "FW-JULIET-06", "DOM-BRAVO-02"]) {
      expect(stripText).not.toContain(planted);
    }
    expect(stripText).not.toMatch(FORBIDDEN_WORDS);

    // Opening the Coverage tab from the strip shows the server's masked names as returned.
    fireEvent.click(within(strip).getByRole("button", { name: "Open Coverage" }));
    const coverageTable = await screen.findByRole("table", { name: "Policy coverage" });
    expect(within(coverageTable).getByText("POLICY-MASKED-1")).toBeInTheDocument();
    const notEvalTable = screen.getByRole("table", { name: "Policies not evaluated" });
    expect(within(notEvalTable).getByText(/POLICY-MASKED-2/)).toBeInTheDocument();
  });
});
