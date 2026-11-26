import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type {
  SummaryFinding, SummaryHeadline, SummaryPolicy, ZoneMatrixSummaryResponse,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Zone matrix step 4b-2: Violations tab contract 7.1 row fields (rule_uid, Devices, Not-allowed part) and section 3.4 /
 * 7.1 policy-viewer link gate on policy_read. Synthetic masked fixtures only.
 */
const HASH = "b".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN = { view_blocks: false, edit: false, publish: false };

const activeSummary = {
  version_id: "v-active", number: 3, state: "ACTIVE" as const, revision: 4, content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z",
};
const activeDetail = { ...activeSummary, zones: [], unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [] };

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
  policy_source_id: "pol-1", policy_name: "POLICY-ALFA", rule_uid: "uid-7", inline_parent_path: [], rule_id: "rule-7",
  rule_number: 7, rule_name: "RULE-ECHO", target_device_ids: ["FW-TANGO-04", "FW-BRAVO-02"], outcome: "VIOLATION",
  reason: null, highest_severity: "HIGH", flags: [], covering_rule_id: null, undecided_parts: [],
  violated_pairs: [{
    source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [],
  }],
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

const summary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => {
  const findings = over.findings ?? [finding()];
  return {
    evaluated_at: "2026-11-25T08:00:00Z", state: "READY", state_text: "findings of the latest complete run of each policy",
    masked: false, policy_names_masked: false, witnesses_included: false,
    staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
    active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
    headline: headline(), headline_absent_reason: null, policies: [policy()], not_evaluated: [],
    filters: { policy_id: null, outcome: null, limit: 200 },
    unfiltered_findings: findings.length, total_findings: findings.length, returned: findings.length, more: 0, capped: false,
    findings,
    resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
    ...over,
  };
};

function serve(body: ZoneMatrixSummaryResponse) {
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

async function openViolationsTable() {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
  return screen.findByRole("table", { name: "Violation findings" });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Zone matrix Violations tab: contract 7.1 row fields and policy_read link gate", () => {
  it("renders rule number, rule name, and rule_uid beneath the label without duplicating rule_uid when rule_name is null or empty", async () => {
    serve(summary({
      findings: [
        finding({ rule_number: 7, rule_name: "RULE-ECHO", rule_uid: "uid-7", rule_id: "rule-7" }),
        finding({ rule_number: 8, rule_name: null, rule_uid: "uid-8", rule_id: "rule-8" }),
        finding({ rule_number: 9, rule_name: "", rule_uid: "uid-9", rule_id: "rule-9" }),
        finding({ rule_number: 10, rule_name: "uid-10", rule_uid: "uid-10", rule_id: "rule-10" }),
      ],
    }));

    const table = await openViolationsTable();
    const headers = within(table).getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers.slice(0, 5)).toEqual(["Rule", "Policy", "Devices", "Violated zone pairs", "Not-allowed part"]);

    const namedRow = within(table).getByText("#7 RULE-ECHO").closest("tr")!;
    expect(within(namedRow).getByRole("link", { name: "Open #7 RULE-ECHO in the policy viewer" }))
      .toHaveAttribute("href", "?screen=policy&policy_id=pol-1&rule=rule-7&standard=1");
    expect(within(namedRow).getByTestId("finding-rule-uid")).toHaveTextContent("uid-7");

    const nullNameRow = within(table).getByText("#8 uid-8").closest("tr")!;
    expect(within(nullNameRow).queryByTestId("finding-rule-uid")).toBeNull();

    const emptyNameRow = within(table).getByText("#9 uid-9").closest("tr")!;
    expect(within(emptyNameRow).queryByTestId("finding-rule-uid")).toBeNull();

    const sameAsUidRow = within(table).getByText("#10 uid-10").closest("tr")!;
    expect(within(sameAsUidRow).queryByTestId("finding-rule-uid")).toBeNull();
  });

  it("links the policy viewer for security_admin and aiview, and renders plain text without a link when policy_read is absent", async () => {
    // 1. security_admin: masked: false, policy_names_masked: false -> link rendered
    serve(summary({ masked: false, policy_names_masked: false }));
    let view = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
    let table = await screen.findByRole("table", { name: "Violation findings" });
    let row = within(table).getByText("#7 RULE-ECHO").closest("tr")!;
    expect(within(row).getByRole("link", { name: "Open #7 RULE-ECHO in the policy viewer" }))
      .toHaveAttribute("href", "?screen=policy&policy_id=pol-1&rule=rule-7&standard=1");
    view.unmount();
    vi.unstubAllGlobals();

    // 2. aiview (replay_viewer): masked: true, policy_names_masked: true -> link rendered
    serve(summary({ masked: true, policy_names_masked: true }));
    view = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
    table = await screen.findByRole("table", { name: "Violation findings" });
    row = within(table).getByText("#7 RULE-ECHO").closest("tr")!;
    expect(within(row).getByRole("link", { name: "Open #7 RULE-ECHO in the policy viewer" }))
      .toHaveAttribute("href", "?screen=policy&policy_id=pol-1&rule=rule-7&standard=1");
    view.unmount();
    vi.unstubAllGlobals();

    // 3. compliance_admin / viewer without policy_read: masked: false, policy_names_masked: true -> plain text, no link
    serve(summary({ masked: false, policy_names_masked: true }));
    table = await openViolationsTable();
    row = within(table).getByText("#7 RULE-ECHO").closest("tr")!;
    expect(within(row).queryByRole("link")).toBeNull();
    expect(within(row).getByText("#7 RULE-ECHO")).toBeInTheDocument();
    expect(within(row).getByTestId("finding-rule-uid")).toHaveTextContent("uid-7");
  });

  it("renders target_device_ids verbatim in the Devices column and UNKNOWN when empty", async () => {
    serve(summary({
      findings: [
        finding({ rule_uid: "uid-1", rule_id: "rule-1", rule_number: 1, rule_name: "RULE-WITH-DEVICES", target_device_ids: ["FW-TANGO-04", "FW-BRAVO-02"] }),
        finding({ rule_uid: "uid-2", rule_id: "rule-2", rule_number: 2, rule_name: "RULE-NO-DEVICES", target_device_ids: [] }),
      ],
    }));

    const table = await openViolationsTable();
    const withDevices = within(table).getByText("#1 RULE-WITH-DEVICES").closest("tr")!;
    expect(within(withDevices).getByTestId("finding-devices")).toHaveTextContent("FW-TANGO-04, FW-BRAVO-02");

    const noDevices = within(table).getByText("#2 RULE-NO-DEVICES").closest("tr")!;
    expect(within(noDevices).getByTestId("finding-devices")).toHaveTextContent("UNKNOWN");
    expect(within(noDevices).getByTestId("finding-devices")).not.toHaveTextContent(/\b0\b|none/i);
  });

  it("renders Not-allowed part across ALLOW_LISTED, multi-pair, DENY_ALL, undecided_parts, and empty/UNKNOWN rows while keeping violated-pair intact", async () => {
    serve(summary({
      findings: [
        finding({
          rule_uid: "uid-allow", rule_id: "rule-1", rule_number: 1, rule_name: "RULE-ALLOW-LISTED",
          violated_pairs: [{
            source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
            statement: "ALLOW_LISTED", severity: "HIGH", not_allowed_services: ["tcp/22", "udp/53"],
          }],
          undecided_parts: [],
        }),
        finding({
          rule_uid: "uid-multi", rule_id: "rule-2", rule_number: 2, rule_name: "RULE-MULTI-PAIR",
          violated_pairs: [
            {
              source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
              statement: "ALLOW_LISTED", severity: "HIGH", not_allowed_services: ["tcp/22"],
            },
            {
              source_zone_id: "z2", destination_zone_id: "z1", source_name: "ZONE-BRAVO", destination_name: "ZONE-ALFA",
              statement: "ALLOW_LISTED", severity: "MEDIUM", not_allowed_services: ["udp/53"],
            },
          ],
          undecided_parts: [],
        }),
        finding({
          rule_uid: "uid-deny", rule_id: "rule-3", rule_number: 3, rule_name: "RULE-DENY-ALL",
          violated_pairs: [{
            source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
            statement: "DENY_ALL", severity: "CRITICAL", not_allowed_services: [],
          }],
          undecided_parts: [],
        }),
        finding({
          rule_uid: "uid-undecided", rule_id: "rule-4", rule_number: 4, rule_name: "RULE-UNDECIDED-PART",
          outcome: "NOT_EVALUABLE", reason: "IPv6 not evaluated", highest_severity: null,
          violated_pairs: [],
          undecided_parts: ["IPv6 not evaluated", "application constraint"],
        }),
        finding({
          rule_uid: "uid-empty", rule_id: "rule-5", rule_number: 5, rule_name: "RULE-EMPTY-PART",
          outcome: "UNKNOWN", reason: "analysis budget", highest_severity: null,
          violated_pairs: [],
          undecided_parts: [],
        }),
        finding({
          rule_uid: "uid-both", rule_id: "rule-6", rule_number: 6, rule_name: "RULE-SERVICES-AND-UNDECIDED",
          violated_pairs: [{
            source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
            statement: "ALLOW_LISTED", severity: "HIGH", not_allowed_services: ["tcp/443"],
          }],
          undecided_parts: ["IPv6 not evaluated"],
        }),
        finding({
          rule_uid: "uid-null-stmt", rule_id: "rule-7", rule_number: 7, rule_name: "RULE-NULL-STATEMENT",
          violated_pairs: [{
            source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
            statement: null, severity: null, not_allowed_services: [],
          }],
          undecided_parts: [],
        }),
        finding({
          rule_uid: "uid-no-pairs", rule_id: "rule-8", rule_number: 8, rule_name: "RULE-VIOLATION-NO-PAIRS",
          violated_pairs: [],
          undecided_parts: [],
        }),
      ],
    }));

    const table = await openViolationsTable();

    const allowRow = within(table).getByText("#1 RULE-ALLOW-LISTED").closest("tr")!;
    expect(within(allowRow).getByTestId("violated-pair")).toHaveTextContent("ZONE-ALFA -> ZONE-BRAVO · Allow listed · High");
    expect(within(allowRow).getByTestId("finding-not-allowed")).toHaveTextContent("tcp/22, udp/53");

    const multiRow = within(table).getByText("#2 RULE-MULTI-PAIR").closest("tr")!;
    const multiNotAllowed = within(multiRow).getByTestId("finding-not-allowed");
    expect(multiNotAllowed).toHaveTextContent("ZONE-ALFA -> ZONE-BRAVO: tcp/22");
    expect(multiNotAllowed).toHaveTextContent("ZONE-BRAVO -> ZONE-ALFA: udp/53");

    const denyRow = within(table).getByText("#3 RULE-DENY-ALL").closest("tr")!;
    expect(within(denyRow).getByTestId("violated-pair")).toHaveTextContent("ZONE-ALFA -> ZONE-BRAVO · Deny all · Critical");
    expect(within(denyRow).getByTestId("finding-not-allowed")).toHaveTextContent("All traffic in pair (deny all)");

    const undecidedRow = within(table).getByText("#4 RULE-UNDECIDED-PART").closest("tr")!;
    expect(within(undecidedRow).getByTestId("finding-not-allowed")).toHaveTextContent("undecided: IPv6 not evaluated, application constraint");

    const emptyRow = within(table).getByText("#5 RULE-EMPTY-PART").closest("tr")!;
    expect(within(emptyRow).getByTestId("finding-not-allowed")).toHaveTextContent("UNKNOWN");

    const bothRow = within(table).getByText("#6 RULE-SERVICES-AND-UNDECIDED").closest("tr")!;
    expect(within(bothRow).getByTestId("finding-not-allowed")).toHaveTextContent("tcp/443");
    expect(within(bothRow).getByTestId("finding-not-allowed")).toHaveTextContent("undecided: IPv6 not evaluated");

    const nullStmtRow = within(table).getByText("#7 RULE-NULL-STATEMENT").closest("tr")!;
    expect(within(nullStmtRow).getByTestId("finding-not-allowed")).toHaveTextContent("UNKNOWN");

    const noPairsRow = within(table).getByText("#8 RULE-VIOLATION-NO-PAIRS").closest("tr")!;
    expect(within(noPairsRow).getByTestId("finding-not-allowed")).toHaveTextContent("UNKNOWN");
  });

  it("preserves all pairs, pair prefixes, DENY_ALL, and UNKNOWN alongside undecided_parts on mixed and partial rows", async () => {
    serve(summary({
      findings: [
        finding({
          rule_uid: "uid-mix-deny", rule_id: "rule-9", rule_number: 9, rule_name: "RULE-MIX-ALLOW-AND-DENY",
          violated_pairs: [
            {
              source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
              statement: "ALLOW_LISTED", severity: "HIGH", not_allowed_services: ["tcp/22"],
            },
            {
              source_zone_id: "UNASSIGNED", destination_zone_id: "z2", source_name: "UNASSIGNED", destination_name: "ZONE-BRAVO",
              statement: "DENY_ALL", severity: "CRITICAL", not_allowed_services: [],
            },
          ],
          undecided_parts: [],
        }),
        finding({
          rule_uid: "uid-mix-unknown", rule_id: "rule-10", rule_number: 10, rule_name: "RULE-MIX-ALLOW-AND-UNKNOWN",
          violated_pairs: [
            {
              source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
              statement: "ALLOW_LISTED", severity: "HIGH", not_allowed_services: ["tcp/22"],
            },
            {
              source_zone_id: "z2", destination_zone_id: "z1", source_name: "ZONE-BRAVO", destination_name: "ZONE-ALFA",
              statement: null, severity: null, not_allowed_services: [],
            },
          ],
          undecided_parts: [],
        }),
        finding({
          rule_uid: "uid-deny-undecided", rule_id: "rule-11", rule_number: 11, rule_name: "RULE-DENY-AND-UNDECIDED",
          violated_pairs: [{
            source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
            statement: "DENY_ALL", severity: "CRITICAL", not_allowed_services: [],
          }],
          undecided_parts: ["IPv6 not evaluated"],
        }),
        finding({
          rule_uid: "uid-null-undecided", rule_id: "rule-12", rule_number: 12, rule_name: "RULE-NULL-AND-UNDECIDED",
          violated_pairs: [{
            source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
            statement: null, severity: null, not_allowed_services: [],
          }],
          undecided_parts: ["IPv6 not evaluated"],
        }),
        finding({
          rule_uid: "uid-nopairs-undecided", rule_id: "rule-13", rule_number: 13, rule_name: "RULE-VIOLATION-NOPAIRS-UNDECIDED",
          violated_pairs: [],
          undecided_parts: ["IPv6 not evaluated"],
        }),
      ],
    }));

    const table = await openViolationsTable();

    const mixDeny = within(within(table).getByText("#9 RULE-MIX-ALLOW-AND-DENY").closest("tr")!).getByTestId("finding-not-allowed");
    expect(mixDeny).toHaveTextContent("ZONE-ALFA -> ZONE-BRAVO: tcp/22");
    expect(mixDeny).toHaveTextContent("UNASSIGNED -> ZONE-BRAVO: All traffic in pair (deny all)");

    const mixUnknown = within(within(table).getByText("#10 RULE-MIX-ALLOW-AND-UNKNOWN").closest("tr")!).getByTestId("finding-not-allowed");
    expect(mixUnknown).toHaveTextContent("ZONE-ALFA -> ZONE-BRAVO: tcp/22");
    expect(mixUnknown).toHaveTextContent("ZONE-BRAVO -> ZONE-ALFA: UNKNOWN");

    const denyUndecided = within(within(table).getByText("#11 RULE-DENY-AND-UNDECIDED").closest("tr")!).getByTestId("finding-not-allowed");
    expect(denyUndecided).toHaveTextContent("All traffic in pair (deny all)");
    expect(denyUndecided).toHaveTextContent("undecided: IPv6 not evaluated");

    const nullUndecided = within(within(table).getByText("#12 RULE-NULL-AND-UNDECIDED").closest("tr")!).getByTestId("finding-not-allowed");
    expect(nullUndecided).toHaveTextContent("UNKNOWN");
    expect(nullUndecided).toHaveTextContent("undecided: IPv6 not evaluated");

    const noPairsUndecided = within(within(table).getByText("#13 RULE-VIOLATION-NOPAIRS-UNDECIDED").closest("tr")!).getByTestId("finding-not-allowed");
    expect(noPairsUndecided).toHaveTextContent("UNKNOWN");
    expect(noPairsUndecided).toHaveTextContent("undecided: IPv6 not evaluated");
  });
});
