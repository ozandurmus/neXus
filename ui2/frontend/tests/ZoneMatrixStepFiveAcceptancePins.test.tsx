import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ChangeFeedPage, CpFailoverSummary, DeviceSummary, LifecycleFleet, OverviewView } from "../src/auth/adminApi";
import { AttentionList } from "../src/screens/overview/AttentionList";
import { buildAttention, type AttentionInputs } from "../src/screens/overview/attentionRules";
import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type { SummaryFinding, SummaryHeadline, SummaryPolicy, ZoneMatrixSummaryResponse } from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Step 5 traceability acceptance pins for docs/design/ZONE_MATRIX_CONTRACT.md (FROZEN, revision 2, sections 7.1, 7.2,
 * 10.2 and Amendment 2026-11-23): ZM-26, ZM-35 and ZM-36 across the Overview attention list and the Violations tab,
 * driven with the wire shape produced by ZoneMatrixSummaryView. Synthetic names and values only.
 */
const NOW = new Date("2026-11-25T09:00:00Z");
const HASH = "a".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN = { view_blocks: false, edit: false, publish: false };
const agoSeconds = (s: number) => new Date(NOW.getTime() - s * 1000).toISOString();

const activeSummary = {
  version_id: "v-active", number: 4, state: "ACTIVE" as const, revision: 5, content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z",
};
const activeDetail = { ...activeSummary, zones: [], unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [] };

const overviewBase: AttentionInputs = {
  now: NOW,
  overview: {
    generated_at: NOW.toISOString(), masked: true,
    estate: { devices: [], counts: {}, no_archive: 0, compliance_state: "OK", facts: {} },
    exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] } },
  } as unknown as OverviewView,
  failover: { state: "ok", value: { devices: [] as DeviceSummary[], rows: [] as CpFailoverSummary[] } },
  lifecycle: { state: "ok", value: { as_of: NOW.toISOString(), devices: [], summary: {} } as LifecycleFleet },
  changes: { state: "ok", value: { events: [] } as unknown as ChangeFeedPage },
  controls: [],
};

const run = (over: Record<string, unknown> = {}) => ({
  run_id: "run-1", state: "COMPLETE", failure_reason: null, version_id: "v-active", version_number: 4, content_sha256: HASH,
  started_at: agoSeconds(3600), finished_at: agoSeconds(3500), snapshot_collected_at: agoSeconds(3600),
  coverage: "COMPLETE", counts: null, ...over,
});

const policy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-1", policy_name: "POLICY-ALFA", vendor: "palo_alto", source_id: "src-1", source_name: "FW-TANGO-04",
  container_id: null, container_name: null, collected_at: agoSeconds(3600), rule_count: 10,
  staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 3600, snapshot_age_known: true },
  coverage: "COMPLETE", coverage_items: [], collection_failures: 0, has_complete_run: true,
  latest_complete_run: run(), latest_run: run(), ...over,
});

const notEvaluableFinding = (over: Partial<SummaryFinding> = {}): SummaryFinding => ({
  policy_source_id: "pol-1", policy_name: "POLICY-ALFA", rule_uid: "uid-ne-1", inline_parent_path: [], rule_id: "rule-ne-1",
  rule_number: 4, rule_name: "RULE-FOXTROT", target_device_ids: [], outcome: "NOT_EVALUABLE", reason: "IPv6 not evaluated",
  highest_severity: null, flags: [], covering_rule_id: null, undecided_parts: [], violated_pairs: [],
  first_observed_at: agoSeconds(7200), first_observed_any_version: null, last_observed_at: agoSeconds(3500),
  run_id: "run-1", version_id: "v-active", version_number: 4, content_sha256: HASH, on_active_version: true,
  policy_staleness: "CURRENT", snapshot_age_seconds: 3600, ...over,
});

/** Headline in the exact wire shape of ZoneMatrixSummaryView (count_text and staleness_headline.text are "N" or "at least N"). */
const serverHeadline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active", version_number: 4, distinct_violating_rules: 0, violated_zone_pairs: 0, critical_rules: 0,
  covered_by_earlier_rule: 0, conditional_rules: 0, outside_headline_ordered_layer: 0, not_evaluable_findings: 0, unknown_findings: 0,
  became_undecided_findings: 0, stale_policies: 0, incomplete_policies: 0, older_version_policies: 0, not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 0, exact: true, count_text: "0", text: "0 rules violate the zone matrix (v4)",
  outside_headline_text: null, staleness_headline: { count: 0, exact: true, text: "0" }, ...over,
});

const serverSummary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  evaluated_at: NOW.toISOString(), state: "READY", state_text: "findings of the latest complete run of each policy",
  masked: true, policy_names_masked: true, witnesses_included: false,
  staleness_threshold: { seconds: 86_400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 4, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: serverHeadline(), headline_absent_reason: null, policies: [policy()], not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 0, total_findings: 0, returned: 0, more: 0, capped: false,
  findings: [],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  cell_counts: [], cell_counts_exact: true, cell_counts_total: 0, cell_counts_returned: 0, cell_counts_more: 0, cell_counts_capped: false,
  ...over,
});

function serveSummary(body: ZoneMatrixSummaryResponse) {
  const reply = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input).split("?")[0];
    if (path === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    if (path === "/api/v2/zone-matrix/summary") return reply(200, body);
    if (path === "/api/v2/zone-matrix/draft") return reply(200, { active: activeSummary, draft: null, diff: null, publish_check: null, can: CAN });
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeDetail, can: CAN });
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeSummary], can: CAN });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
}

function renderOverview(body: ZoneMatrixSummaryResponse) {
  const result = buildAttention({ ...overviewBase, zoneMatrix: { state: "ok", value: body } }, ["Policy"]);
  return render(<AttentionList result={result} />);
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Zone matrix step 5 acceptance pins: ZM-26", () => {
  const combinedImperfect = serverSummary({
    headline: serverHeadline({
      exact: false, stale_policies: 1, incomplete_policies: 1, not_evaluable_findings: 1,
      count_text: "at least 0", text: "no proven violations in evaluated rules",
      staleness_headline: { count: 0, exact: false, text: "at least 0" },
    }),
    policies: [
      policy({ policy_id: "pol-cur", policy_name: "POLICY-CUR" }),
      policy({
        policy_id: "pol-stale", policy_name: "POLICY-STALE", collected_at: agoSeconds(30 * 3600),
        staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 30 * 3600, snapshot_age_known: true },
      }),
      policy({
        policy_id: "pol-inc", policy_name: "POLICY-INC", coverage: "INCOMPLETE", coverage_items: ["RULEBASE_TRUNCATED"], collection_failures: 1,
      }),
    ],
    findings: [notEvaluableFinding({ policy_source_id: "pol-inc", policy_name: "POLICY-INC" })],
    total_findings: 1, unfiltered_findings: 1, returned: 1,
    cell_counts_exact: false,
  });

  const incompleteOnly = serverSummary({
    headline: serverHeadline({
      exact: false, incomplete_policies: 1, count_text: "at least 0", text: "no proven violations in evaluated rules",
      staleness_headline: { count: 0, exact: false, text: "at least 0" },
    }),
    policies: [policy({ coverage: "INCOMPLETE", coverage_items: ["PAN_LOCAL_RULES_NOT_COLLECTED"], collection_failures: 0 })],
    cell_counts_exact: false,
  });

  const staleOnly = serverSummary({
    headline: serverHeadline({
      exact: false, stale_policies: 1, count_text: "at least 0", text: "no proven violations in evaluated rules",
      staleness_headline: { count: 0, exact: false, text: "at least 0" },
    }),
    policies: [policy({
      collected_at: agoSeconds(30 * 3600),
      staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 30 * 3600, snapshot_age_known: true },
    })],
    cell_counts_exact: false,
  });

  const notEvaluableOnly = serverSummary({
    headline: serverHeadline({
      exact: false, not_evaluable_findings: 1, count_text: "at least 0", text: "no proven violations in evaluated rules",
      // Corrected server answer (ZoneMatrixStaleness.withUndecided): undecided findings make the staleness headline non-exact.
      staleness_headline: { count: 0, exact: false, text: "at least 0" },
    }),
    policies: [policy()],
    findings: [notEvaluableFinding()],
    total_findings: 1, unfiltered_findings: 1, returned: 1,
    cell_counts_exact: false,
  });

  it.each([
    ["combined incomplete + stale + NOT_EVALUABLE fixture", combinedImperfect],
    ["incomplete coverage alone", incompleteOnly],
    ["stale policy alone", staleOnly],
    ["one NOT_EVALUABLE rule alone", notEvaluableOnly],
  ])("zm26: Overview attention row never renders '0' or green for %s", (_label, fixture) => {
    const { unmount } = renderOverview(fixture);
    const list = screen.getByTestId("attention-list");
    const row = list.querySelector('[data-attention="zone-matrix-violations"]') as HTMLElement;
    expect(row).not.toBeNull();
    expect(row.querySelector('[data-severity="unknown"]')).not.toBeNull();
    expect(row.querySelector('[data-pill="ok"]')).toBeNull();
    expect(within(row).getByText("no proven violations in evaluated rules")).toBeInTheDocument();
    expect(within(row).queryByText("0")).toBeNull();
    expect(row.textContent ?? "").not.toMatch(/\b0 rules violate|\b0 violating|\bOK\b|all clear|\bgreen\b/i);
    unmount();
  });

  it.each([
    ["combined incomplete + stale + NOT_EVALUABLE fixture", combinedImperfect],
    ["incomplete coverage alone", incompleteOnly],
    ["stale policy alone", staleOnly],
    ["one NOT_EVALUABLE rule alone", notEvaluableOnly],
  ])("zm26: Violations tab headline never renders '0' or green for %s", async (_label, fixture) => {
    serveSummary(fixture);
    const { unmount } = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
    const headlineBox = await screen.findByTestId("summary-headline");
    expect(headlineBox).toHaveAttribute("data-proven", "no");
    expect(headlineBox.querySelector('[data-pill="unknown"]')).not.toBeNull();
    expect(headlineBox.querySelector('[data-pill="ok"]')).toBeNull();
    expect(within(headlineBox).getByText("No violation found, not proven")).toBeInTheDocument();
    expect(within(headlineBox).getByText("no proven violations in evaluated rules")).toBeInTheDocument();
    expect(within(headlineBox).getByTestId("headline-count-text")).toHaveTextContent("at least 0");
    expect(within(headlineBox).queryByText("0")).toBeNull();
    expect(headlineBox.textContent ?? "").not.toMatch(/\b0 rules violate|\b0 violating|No violating rule found, exact|\bgreen\b/i);
    unmount();
  });

  it.each([
    ["one NOT_EVALUABLE rule alone", { not_evaluable_findings: 1 }],
    ["one UNKNOWN finding alone", { unknown_findings: 1 }],
    ["one became-undecided finding alone", { became_undecided_findings: 1 }],
  ])("zm26: Violations and Coverage tabs never print a bare '0' for %s, even if a response claims an exact staleness headline", async (_label, counts) => {
    const staleServerAnswer = serverSummary({
      ...notEvaluableOnly,
      headline: serverHeadline({
        exact: false, not_evaluable_findings: 0, count_text: "at least 0", text: "no proven violations in evaluated rules",
        staleness_headline: { count: 0, exact: true, text: "0" }, ...counts,
      }),
    });
    serveSummary(staleServerAnswer);
    const { unmount } = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
    const headlineBox = await screen.findByTestId("summary-headline");
    expect(headlineBox).toHaveAttribute("data-proven", "no");
    expect(headlineBox.querySelector('[data-pill="ok"]')).toBeNull();
    expect(within(headlineBox).queryByText("0")).toBeNull();
    expect(within(headlineBox).getByTestId("staleness-headline")).toHaveTextContent("at least 0");
    fireEvent.click(await screen.findByRole("tab", { name: "Coverage" }));
    const coverageLine = await screen.findByTestId("staleness-headline");
    expect(coverageLine).toHaveTextContent("at least 0");
    expect(coverageLine.textContent?.trim()).not.toBe("0");
    unmount();
  });

  it("zm26: a proven exact headline still prints the bare staleness number on both tabs (positive control)", async () => {
    serveSummary(serverSummary({
      headline: serverHeadline(),
      policies: [policy()],
      findings: [],
      total_findings: 0, unfiltered_findings: 0, returned: 0,
    }));
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
    const headlineBox = await screen.findByTestId("summary-headline");
    expect(headlineBox).toHaveAttribute("data-proven", "yes");
    expect(within(headlineBox).getByTestId("staleness-headline")).toHaveTextContent(/^0$/);
  });
});

describe("Zone matrix step 5 acceptance pins: ZM-35", () => {
  it("zm35: Overview renders exact N at the exact 24 h boundary when all policies are CURRENT and COMPLETE, and 'at least N' otherwise", () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const exactAt24h = serverSummary({
      headline: serverHeadline({
        distinct_violating_rules: 3, violated_zone_pairs: 2, exact: true, count_text: "3",
        text: "3 rules violate the zone matrix (v4) across 2 zone pairs; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)",
        staleness_headline: { count: 3, exact: true, text: "3" },
      }),
      policies: [
        policy({ policy_id: "pol-fresh", collected_at: agoSeconds(7200) }),
        policy({
          policy_id: "pol-boundary", collected_at: agoSeconds(86_400),
          staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 86_400, snapshot_age_known: true },
        }),
      ],
    });
    const { unmount } = renderOverview(exactAt24h);
    const exactRow = screen.getByTestId("attention-list").querySelector('[data-attention="zone-matrix-violations"]') as HTMLElement;
    expect(exactRow).not.toBeNull();
    expect(within(exactRow).getByText(/^3 rules violate the zone matrix \(v4\)/)).toBeInTheDocument();
    expect(exactRow.textContent ?? "").not.toContain("at least");
    expect(exactRow.textContent ?? "").not.toContain("STALE");
    expect(within(exactRow).getByText("oldest policy snapshot 24 h ago")).toBeInTheDocument();
    unmount();

    const atLeastText =
      "at least 3 rules violate the zone matrix (v4) across 2 zone pairs; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)";
    const imperfectCases: Array<[string, ZoneMatrixSummaryResponse, string]> = [
      [
        "snapshot 24 h + 1 s old",
        serverSummary({
          headline: serverHeadline({
            distinct_violating_rules: 3, violated_zone_pairs: 2, exact: false, stale_policies: 1, count_text: "at least 3",
            text: atLeastText, staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
          policies: [policy({
            collected_at: agoSeconds(86_401),
            staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 86_401, snapshot_age_known: true },
          })],
        }),
        "1 policy is STALE (snapshot older than 24 h (interim value set by the Product Owner, not the bank's)",
      ],
      [
        "latest run FAILED",
        serverSummary({
          headline: serverHeadline({
            distinct_violating_rules: 3, violated_zone_pairs: 2, exact: false, stale_policies: 1, count_text: "at least 3",
            text: atLeastText, staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
          policies: [policy({
            staleness: { verdict: "STALE", reasons: ["LAST_RUN_FAILED"], snapshot_age_seconds: 3600, snapshot_age_known: true },
            latest_run: run({ state: "FAILED", failure_reason: "TIME_LIMIT" }),
          })],
        }),
        "1 policy is STALE",
      ],
      [
        "policy on an inactive version",
        serverSummary({
          headline: serverHeadline({
            distinct_violating_rules: 3, violated_zone_pairs: 2, exact: false, stale_policies: 1, older_version_policies: 1,
            count_text: "at least 3", text: atLeastText, staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
          policies: [policy({
            staleness: { verdict: "STALE", reasons: ["VERSION_NOT_ACTIVE"], snapshot_age_seconds: 3600, snapshot_age_known: true },
          })],
        }),
        "1 policy is still on an older matrix version",
      ],
      [
        "INCOMPLETE coverage on a CURRENT policy",
        serverSummary({
          headline: serverHeadline({
            distinct_violating_rules: 3, violated_zone_pairs: 2, exact: false, incomplete_policies: 1, count_text: "at least 3",
            text: atLeastText, staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
          policies: [policy({ coverage: "INCOMPLETE", coverage_items: ["PAN_LOCAL_RULES_NOT_COLLECTED"] })],
        }),
        "1 policy is INCOMPLETE (collection incomplete)",
      ],
    ];

    for (const [_name, body, expectedDetail] of imperfectCases) {
      const view = renderOverview(body);
      const row = screen.getByTestId("attention-list").querySelector('[data-attention="zone-matrix-violations"]') as HTMLElement;
      expect(row).not.toBeNull();
      expect(within(row).getByText(/^at least 3 rules violate the zone matrix \(v4\)/)).toBeInTheDocument();
      expect(row.textContent ?? "").toContain(expectedDetail);
      view.unmount();
    }
  });
});

describe("Zone matrix step 5 acceptance pins: ZM-36", () => {
  it("zm36: Overview renders version-change resolved findings through day 7 and drops them on day 8 while by_version keeps the count", () => {
    const day7 = serverSummary({
      headline: serverHeadline({ resolved_by_version_change_last_7_days: 2 }),
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 2, returned: 0, more: 2, capped: true, last_7_days: [],
        by_version: [{ version_id: "v-active", version_number: 4, count: 2 }],
      },
    });
    const day7View = renderOverview(day7);
    const day7Row = screen.getByTestId("attention-list").querySelector('[data-attention="zone-matrix-violations"]') as HTMLElement;
    expect(day7Row).not.toBeNull();
    expect(day7Row.querySelector('[data-severity="serious"]')).not.toBeNull();
    expect(day7Row.textContent ?? "").toContain("2 findings resolved by a version change in the last 7 days");
    day7View.unmount();

    // Day 8: the 7-day window count drops to 0 (even though all-time by_version still records 2); Overview drops the row.
    const day8Clean = serverSummary({
      headline: serverHeadline({ resolved_by_version_change_last_7_days: 0 }),
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [],
        by_version: [{ version_id: "v-active", version_number: 4, count: 2 }],
      },
    });
    const day8CleanView = renderOverview(day8Clean);
    expect(screen.getByTestId("attention-list").querySelector('[data-attention="zone-matrix-violations"]')).toBeNull();
    expect(screen.getByTestId("attention-empty")).toHaveTextContent("Nothing needs attention.");
    day8CleanView.unmount();

    // Day 8 with an open violation: the open violation row remains, without any 7-day resolved text.
    const day8WithViolation = serverSummary({
      headline: serverHeadline({
        distinct_violating_rules: 1, violated_zone_pairs: 1, count_text: "1",
        text: "1 rule violates the zone matrix (v4) across 1 zone pair; 0 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)",
        resolved_by_version_change_last_7_days: 0,
      }),
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [],
        by_version: [{ version_id: "v-active", version_number: 4, count: 2 }],
      },
    });
    const day8ViolView = renderOverview(day8WithViolation);
    const day8ViolRow = screen.getByTestId("attention-list").querySelector('[data-attention="zone-matrix-violations"]') as HTMLElement;
    expect(day8ViolRow).not.toBeNull();
    expect(day8ViolRow.textContent ?? "").not.toContain("resolved by a version change in the last 7 days");
    day8ViolView.unmount();
  });
});
