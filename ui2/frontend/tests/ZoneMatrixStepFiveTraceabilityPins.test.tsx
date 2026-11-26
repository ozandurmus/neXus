import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { OverviewView } from "../src/auth/adminApi";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { OverviewScreen } from "../src/screens/OverviewScreen";
import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type {
  SummaryFinding, SummaryHeadline, SummaryPolicy, SummaryRun, ZoneMatrixSummaryResponse,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Step 5 acceptance-traceability pins (docs/design/ZONE_MATRIX_CONTRACT.md sections 7.1, 7.2, 10.2; ZM-26, ZM-35, ZM-36)
 * over the real ZoneMatrixSummaryView JSON shape. Synthetic names and counts only.
 *
 * Contradictions left unfixed and reported in docs/history/backlog/zone_matrix_delivery.md:
 * - ZM-26 (Violations / Coverage tabs): when all covered policies are CURRENT and COMPLETE with 0 violations and 1
 *   NOT_EVALUABLE finding, ZoneMatrixRunService sets staleness_headline = { count: 0, exact: true, text: "0" } (checking
 *   only CURRENT && COMPLETE), and ViolationsTab / CoverageTab render staleness_headline.text verbatim as a bare "0".
 * - ZM-36 (Versions tab): ZoneMatrixSummaryView (resolved_by_version_change.by_version) and VersionsTab expose and render
 *   per-version counts only, not per-version lists of resolved findings.
 */
const NOW_MS = Date.now();
const isoHoursAgo = (hours: number) => new Date(NOW_MS - hours * 3_600_000).toISOString();
const NOW = new Date(NOW_MS).toISOString();
const HASH = "a".repeat(64);
const INTERIM_LABEL = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN_READ = { view_blocks: false, edit: false, publish: false };
const SESSION_POLICY: SessionInfo = {
  displayName: "TEST-USER-01",
  roleTokens: [],
  permissions: ["Policy"],
  onSignOut: () => {},
};

const activeVersionSummary = {
  version_id: "v-active",
  number: 4,
  state: "ACTIVE" as const,
  revision: 5,
  content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z",
  updated_at: "2026-11-12T08:00:00Z",
  published_by: "USER-2",
  published_at: "2026-11-12T08:00:00Z",
};

const activeVersionDetail = {
  ...activeVersionSummary,
  zones: [],
  unassigned_addresses: { IPV4: "0", IPV6: "0" },
  redundant_blocks: 0,
  revisions: [],
};

function quietOverview(): OverviewView {
  return {
    generated_at: NOW,
    masked: true,
    denominators: { active_devices: 5, gateways: 4, clusters: 1, backup_targets: 4 },
    evidence: {
      inventory: { at: NOW, state: "OK" },
      configuration: { at: NOW, state: "OK" },
      compliance: { at: null, state: "UNKNOWN" },
      backup: { at: NOW, state: "OK" },
      jobs: { at: NOW, state: "OK" },
      platform_facts: { at: NOW, state: "OK" },
    },
    attention: {
      failed_jobs_24h: { count: 0, terminal_24h: 1, state: "OK" },
      stale_inventory: { count: 0, of: 5, state: "OK" },
      cluster_diff: { count: 0, of: 1, unknown: 0, state: "OK" },
      config_changed: { count: 0, of: 4, state: "OK" },
      backup_missing: { count: 0, of: 4, state: "OK" },
    },
    exceptions: {
      failed_jobs: { total: 0, rows: [], reasons: [] },
      config_changes: { total: 0, rows: [] },
      cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] },
    },
    inventory_age: { lt24h: 5, h24_72: 0, gt72h: 0, never: 0, of: 5 },
    compliance: { state: "UNKNOWN", reason: "not_evaluated" },
    platform: { devices: 5, check_point: 3, palo_alto: 2, clusters: 1, hotfix_levels: [], evidence_at: NOW },
    nexus: { completed_24h: 1, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: NOW, palo_alto: NOW } },
    estate: {
      devices: [
        {
          device_id: "d1",
          hostname: "FW-TANGO-04",
          vendor: "check_point",
          model: "Check Point 28000",
          condition: "clear",
          no_archive: false,
          critical_fail: 0,
          last_read_at: NOW,
        },
      ],
      counts: { critical: 0, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: 1 },
      no_archive: 0,
      compliance_state: "OK",
      facts: { critical: { k: 0, n: 1 }, backups: { a: 4, b: 4 }, evidence: { r: 5, d: 5, never: 0 } },
    },
  } as unknown as OverviewView;
}

function summaryRun(over: Partial<SummaryRun> = {}): SummaryRun {
  return {
    run_id: "run-1",
    state: "COMPLETE",
    failure_reason: null,
    version_id: "v-active",
    version_number: 4,
    content_sha256: HASH,
    started_at: isoHoursAgo(2),
    finished_at: isoHoursAgo(2),
    snapshot_collected_at: isoHoursAgo(2),
    coverage: "COMPLETE",
    counts: null,
    ...over,
  };
}

function summaryPolicy(over: Partial<SummaryPolicy> = {}): SummaryPolicy {
  return {
    policy_id: "pol-1",
    policy_name: "POLICY-ALFA",
    vendor: "PAN",
    source_id: "src-1",
    source_name: "FW-TANGO-04",
    container_id: null,
    container_name: null,
    collected_at: isoHoursAgo(2),
    rule_count: 14,
    staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 7200, snapshot_age_known: true },
    coverage: "COMPLETE",
    coverage_items: [],
    collection_failures: 0,
    has_complete_run: true,
    latest_complete_run: summaryRun(),
    latest_run: summaryRun(),
    ...over,
  };
}

function summaryFinding(over: Partial<SummaryFinding> = {}): SummaryFinding {
  return {
    policy_source_id: "pol-1",
    policy_name: "POLICY-ALFA",
    rule_uid: "uid-1",
    inline_parent_path: [],
    rule_id: "rule-1",
    rule_number: 1,
    rule_name: "RULE-ALFA",
    target_device_ids: ["FW-TANGO-04"],
    outcome: "VIOLATION",
    reason: null,
    highest_severity: "HIGH",
    flags: [],
    covering_rule_id: null,
    undecided_parts: [],
    violated_pairs: [
      {
        source_zone_id: "z1",
        destination_zone_id: "z2",
        source_name: "ZONE-ALFA",
        destination_name: "ZONE-BRAVO",
        statement: "DENY_ALL",
        severity: "HIGH",
        not_allowed_services: ["tcp/22"],
      },
    ],
    first_observed_at: isoHoursAgo(12),
    first_observed_any_version: isoHoursAgo(12),
    last_observed_at: isoHoursAgo(2),
    run_id: "run-1",
    version_id: "v-active",
    version_number: 4,
    content_sha256: HASH,
    on_active_version: true,
    policy_staleness: "CURRENT",
    snapshot_age_seconds: 7200,
    ...over,
  };
}

/** Exact ZoneMatrixSummaryView headline shape (staleness_headline.text is "0"/"N" or "at least 0"/"at least N"). */
function serverHeadline(over: Partial<SummaryHeadline> = {}): SummaryHeadline {
  return {
    version_id: "v-active",
    version_number: 4,
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
    text: "0 rules violate the zone matrix (v4)",
    outside_headline_text: null,
    staleness_headline: { count: 0, exact: true, text: "0" },
    ...over,
  };
}

function serverSummary(over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse {
  return {
    evaluated_at: NOW,
    state: "READY",
    state_text: "findings of the latest complete run of each policy",
    masked: true,
    policy_names_masked: true,
    witnesses_included: false,
    staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM_LABEL },
    active_version: { version_id: "v-active", version_number: 4, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
    headline: serverHeadline(),
    headline_absent_reason: null,
    policies: [summaryPolicy()],
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
  };
}

function stubOverviewAndZoneMatrix(zoneBody: ZoneMatrixSummaryResponse) {
  const reply = (payload: unknown) =>
    Promise.resolve(new Response(JSON.stringify(payload), { status: 200, headers: { "Content-Type": "application/json" } }));
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
    const path = url.split("?")[0];
    if (url === "/session/status") return reply({ csrf_token: "synthetic-token" });
    if (url.includes("/compliance/controls")) return reply({ total_controls: 0, controls: [] });
    if (url === "/devices") return reply({ devices: [] });
    if (url.includes("/cp-failover/summary")) return reply([]);
    if (url.startsWith("/api/v2/lifecycle")) return reply({ as_of: NOW, devices: [], summary: {} });
    if (url.startsWith("/api/v2/changes/feed")) return reply({ events: [] });
    if (path === "/api/v2/zone-matrix/summary") return reply(zoneBody);
    if (path === "/api/v2/zone-matrix/draft") return reply({ active: activeVersionSummary, draft: null, diff: null, publish_check: null, can: CAN_READ });
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply({ ...activeVersionDetail, can: CAN_READ });
    if (path === "/api/v2/zone-matrix/versions") return reply({ versions: [activeVersionSummary], can: CAN_READ });
    return reply(quietOverview());
  }));
}

function assertNoGreenElement(root: HTMLElement, context: string) {
  expect(root.querySelector('[data-proven="yes"]'), `${context}: proven marker`).toBeNull();
  expect(root.querySelector('[data-severity="ok"]'), `${context}: ok severity`).toBeNull();
  for (const el of Array.from(root.querySelectorAll<HTMLElement>("[class]"))) {
    expect(
      /(colorSuccess|standardSuccess|filledSuccess|outlinedSuccess)/.test(el.className.toString()),
      `${context}: green class on "${el.textContent}"`,
    ).toBe(false);
  }
}

const ZM26_ZERO_VIOLATION_FIXTURES: ReadonlyArray<{ readonly name: string; readonly body: ZoneMatrixSummaryResponse }> = [
  {
    name: "incomplete coverage",
    body: serverSummary({
      policies: [summaryPolicy({ coverage: "INCOMPLETE", coverage_items: ["PAN_LOCAL_RULES_NOT_COLLECTED"] })],
      headline: serverHeadline({
        incomplete_policies: 1,
        exact: false,
        count_text: "at least 0",
        text: "no proven violations in evaluated rules",
        staleness_headline: { count: 0, exact: false, text: "at least 0" },
      }),
    }),
  },
  {
    name: "stale policy",
    body: serverSummary({
      policies: [
        summaryPolicy({
          collected_at: isoHoursAgo(26),
          staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 93600, snapshot_age_known: true },
        }),
      ],
      headline: serverHeadline({
        stale_policies: 1,
        exact: false,
        count_text: "at least 0",
        text: "no proven violations in evaluated rules",
        staleness_headline: { count: 0, exact: false, text: "at least 0" },
      }),
    }),
  },
  {
    name: "one NOT_EVALUABLE rule",
    body: serverSummary({
      unfiltered_findings: 1,
      total_findings: 1,
      returned: 1,
      findings: [
        summaryFinding({
          outcome: "NOT_EVALUABLE",
          reason: "IPv6 not evaluated",
          highest_severity: null,
          violated_pairs: [],
        }),
      ],
      headline: serverHeadline({
        not_evaluable_findings: 1,
        exact: false,
        count_text: "at least 0",
        text: "no proven violations in evaluated rules",
        // ZoneMatrixRunService computes stalenessHeadline from CURRENT && COMPLETE alone, so text is "0" here.
        staleness_headline: { count: 0, exact: true, text: "0" },
      }),
    }),
  },
];

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Zone matrix step 5 traceability pins (ZM-26, ZM-35, ZM-36)", () => {
  it.each(ZM26_ZERO_VIOLATION_FIXTURES)(
    "zm26_overviewNeverRendersZeroOrGreenForIncompleteStaleOrNotEvaluableFixtures ($name)",
    async ({ name, body }) => {
      stubOverviewAndZoneMatrix(body);
      const { container } = render(
        <SessionContext.Provider value={SESSION_POLICY}>
          <OverviewScreen />
        </SessionContext.Provider>,
      );
      const list = await screen.findByTestId("attention-list");
      const headlineEl = await within(list).findByText("no proven violations in evaluated rules");
      const row = headlineEl.closest('[data-attention="zone-matrix-violations"]') as HTMLElement;
      expect(row, name).not.toBeNull();
      expect(within(row).getByText("UNKNOWN")).toHaveAttribute("data-severity", "unknown");
      assertNoGreenElement(row, `Overview ${name}`);
      expect(row.textContent ?? "", `Overview ${name} must never render a standalone 0`).not.toMatch(/\b0\b/);
      expect(container.querySelector('[data-testid="attention-empty"]')).toBeNull();
    },
  );

  it.each(ZM26_ZERO_VIOLATION_FIXTURES)(
    "zm26_violationsTabNeverRendersGreenForIncompleteStaleOrNotEvaluableAndShowsNoBareZeroWhenStaleOrIncomplete ($name)",
    async ({ name, body }) => {
      stubOverviewAndZoneMatrix(body);
      render(<ZoneMatrixScreen />);
      fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
      const panel = await screen.findByTestId("summary-headline");
      expect(panel).toHaveAttribute("data-proven", "no");
      assertNoGreenElement(panel, `Violations ${name}`);
      expect(within(panel).getByText("No violation found, not proven")).toBeInTheDocument();
      expect(within(panel).getByText("no proven violations in evaluated rules")).toBeInTheDocument();
      expect(within(panel).getByTestId("headline-count-text")).toHaveTextContent("at least 0");
      expect(within(panel).queryByText("No violating rule found, exact")).toBeNull();
      if (name !== "one NOT_EVALUABLE rule") {
        expect(within(panel).getByTestId("staleness-headline")).toHaveTextContent("at least 0");
        for (const child of Array.from(panel.querySelectorAll("*"))) {
          expect((child.textContent ?? "").trim(), `Violations ${name} bare zero`).not.toBe("0");
        }
      }
    },
  );

  it("zm35_overviewShowsAtLeastNUnlessEveryCoveredPolicyIsCurrentAndComplete", async () => {
    const atLeastText =
      "at least 3 rules violate the zone matrix (v4) across 2 zone pairs; 1 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)";
    const exactText =
      "3 rules violate the zone matrix (v4) across 2 zone pairs; 1 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)";

    const imperfectCases: ReadonlyArray<{ readonly label: string; readonly body: ZoneMatrixSummaryResponse; readonly detailExpected: string }> = [
      {
        label: "snapshot older than threshold",
        body: serverSummary({
          policies: [
            summaryPolicy({
              collected_at: isoHoursAgo(30),
              staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 108000, snapshot_age_known: true },
            }),
          ],
          headline: serverHeadline({
            distinct_violating_rules: 3,
            violated_zone_pairs: 2,
            critical_rules: 1,
            stale_policies: 1,
            exact: false,
            count_text: "at least 3",
            text: atLeastText,
            staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
        }),
        detailExpected: "1 policy is STALE (snapshot older than 24 h (interim value set by the Product Owner, not the bank's)",
      },
      {
        label: "last run FAILED",
        body: serverSummary({
          policies: [
            summaryPolicy({
              staleness: { verdict: "STALE", reasons: ["LAST_RUN_FAILED"], snapshot_age_seconds: 3600, snapshot_age_known: true },
              latest_run: summaryRun({ run_id: "run-2", state: "FAILED", failure_reason: "TIME_LIMIT" }),
            }),
          ],
          headline: serverHeadline({
            distinct_violating_rules: 3,
            violated_zone_pairs: 2,
            critical_rules: 1,
            stale_policies: 1,
            exact: false,
            count_text: "at least 3",
            text: atLeastText,
            staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
        }),
        detailExpected: "1 policy is STALE",
      },
      {
        label: "version not active",
        body: serverSummary({
          policies: [
            summaryPolicy({
              staleness: { verdict: "STALE", reasons: ["VERSION_NOT_ACTIVE"], snapshot_age_seconds: 3600, snapshot_age_known: true },
              latest_complete_run: summaryRun({ version_id: "v-older", version_number: 3 }),
            }),
          ],
          headline: serverHeadline({
            distinct_violating_rules: 3,
            violated_zone_pairs: 2,
            critical_rules: 1,
            stale_policies: 1,
            older_version_policies: 1,
            exact: false,
            count_text: "at least 3",
            text: atLeastText,
            staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
        }),
        detailExpected: "1 policy is still on an older matrix version",
      },
      {
        label: "CURRENT policy with INCOMPLETE coverage",
        body: serverSummary({
          policies: [summaryPolicy({ coverage: "INCOMPLETE", coverage_items: ["PAN_INTRAZONE_DEFAULT_ALLOW"] })],
          headline: serverHeadline({
            distinct_violating_rules: 3,
            violated_zone_pairs: 2,
            critical_rules: 1,
            incomplete_policies: 1,
            exact: false,
            count_text: "at least 3",
            text: atLeastText,
            staleness_headline: { count: 3, exact: false, text: "at least 3" },
          }),
        }),
        detailExpected: "1 policy is INCOMPLETE (collection incomplete)",
      },
    ];

    for (const c of imperfectCases) {
      stubOverviewAndZoneMatrix(c.body);
      const { unmount } = render(
        <SessionContext.Provider value={SESSION_POLICY}>
          <OverviewScreen />
        </SessionContext.Provider>,
      );
      const list = await screen.findByTestId("attention-list");
      const row = (await within(list).findByText(atLeastText)).closest('[data-attention="zone-matrix-violations"]') as HTMLElement;
      expect(row, c.label).not.toBeNull();
      expect(row.textContent ?? "", c.label).toContain(c.detailExpected);
      unmount();
      vi.unstubAllGlobals();
    }

    // All covered policies CURRENT and COMPLETE with nothing undecided -> exact "3 rules violate...", never "at least".
    stubOverviewAndZoneMatrix(
      serverSummary({
        policies: [summaryPolicy(), summaryPolicy({ policy_id: "pol-2", policy_name: "POLICY-BRAVO", vendor: "CP", source_name: "FW-JULIET-06" })],
        headline: serverHeadline({
          distinct_violating_rules: 3,
          violated_zone_pairs: 2,
          critical_rules: 1,
          exact: true,
          count_text: "3",
          text: exactText,
          staleness_headline: { count: 3, exact: true, text: "3" },
        }),
      }),
    );
    render(
      <SessionContext.Provider value={SESSION_POLICY}>
        <OverviewScreen />
      </SessionContext.Provider>,
    );
    const list = await screen.findByTestId("attention-list");
    const exactRow = (await within(list).findByText(exactText)).closest('[data-attention="zone-matrix-violations"]') as HTMLElement;
    expect(exactRow).not.toBeNull();
    expect(exactRow.textContent ?? "").not.toMatch(/at least/i);
  });

  it("zm36_overviewShowsFindingsResolvedByVersionChangeForSevenDaysAndOmitsRowOnceWindowExpires", async () => {
    // Within the 7-day window (even with 0 open violations and exact coverage): Overview renders the row with the 7-day count.
    stubOverviewAndZoneMatrix(
      serverSummary({
        headline: serverHeadline({
          resolved_by_version_change_last_7_days: 3,
          exact: true,
          count_text: "0",
          text: "0 rules violate the zone matrix (v4)",
          staleness_headline: { count: 0, exact: true, text: "0" },
        }),
        resolved_by_version_change: {
          window_days: 7,
          total_last_7_days: 3,
          returned: 1,
          more: 2,
          capped: true,
          last_7_days: [],
          by_version: [{ version_id: "v-active", version_number: 4, count: 3 }],
        },
      }),
    );
    const firstRender = render(
      <SessionContext.Provider value={SESSION_POLICY}>
        <OverviewScreen />
      </SessionContext.Provider>,
    );
    const list = await screen.findByTestId("attention-list");
    const row = (await within(list).findByText(/3 findings resolved by a version change in the last 7 days/))
      .closest('[data-attention="zone-matrix-violations"]') as HTMLElement;
    expect(row).not.toBeNull();
    expect(within(row).getByText("Review")).toHaveAttribute("data-severity", "serious");
    expect(within(row).getByRole("link", { name: /Open Zone matrix/ })).toHaveAttribute("href", "?screen=zone_matrix");
    firstRender.unmount();
    vi.unstubAllGlobals();

    // Past the 7-day window (resolved_by_version_change_last_7_days = 0, clean exact fleet): Overview renders no row.
    stubOverviewAndZoneMatrix(
      serverSummary({
        headline: serverHeadline({ resolved_by_version_change_last_7_days: 0 }),
        resolved_by_version_change: {
          window_days: 7,
          total_last_7_days: 0,
          returned: 0,
          more: 0,
          capped: false,
          last_7_days: [],
          by_version: [{ version_id: "v-active", version_number: 4, count: 3 }],
        },
      }),
    );
    const secondRender = render(
      <SessionContext.Provider value={SESSION_POLICY}>
        <OverviewScreen />
      </SessionContext.Provider>,
    );
    await screen.findByTestId("attention-empty");
    expect(secondRender.container.querySelector('[data-attention="zone-matrix-violations"]')).toBeNull();
  });
});
