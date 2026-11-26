import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type {
  SummaryFinding, SummaryHeadline, SummaryPolicy, SummaryResolvedFinding, ZoneMatrixSummaryResponse,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Round 2 traceability pins (docs/design/ZONE_MATRIX_CONTRACT.md sections 7.1, 7.2, 10.2; ZM-26 and ZM-36), over the wire
 * shape of ZoneMatrixSummaryView after the ZM-26 single-NOT_EVALUABLE fix (ZoneMatrixStaleness.withUndecided). New file, no
 * existing test edited. Synthetic names and counts only; automated validation only.
 */
const NOW_MS = Date.now();
const isoHoursAgo = (hours: number) => new Date(NOW_MS - hours * 3_600_000).toISOString();
const isoDaysAgo = (days: number) => isoHoursAgo(days * 24);
const HASH = "a".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN = { view_blocks: false, edit: false, publish: false, confirm_reference: false };

const activeSummary = {
  version_id: "v-active", number: 3, state: "ACTIVE" as const, revision: 5, content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z",
};
const activeDetail = { ...activeSummary, zones: [], unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [] };

const policy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-1", policy_name: "POLICY-ALFA", vendor: "PAN", source_id: "src-1", source_name: "FW-TANGO-04",
  container_id: null, container_name: null, collected_at: isoHoursAgo(2), rule_count: 14,
  staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 7200, snapshot_age_known: true },
  coverage: "COMPLETE", coverage_items: [], collection_failures: 0, has_complete_run: true,
  latest_complete_run: null, latest_run: null, ...over,
});

const notEvaluableFinding = (): SummaryFinding => ({
  policy_source_id: "pol-1", policy_name: "POLICY-ALFA", rule_uid: "uid-ne-1", inline_parent_path: [], rule_id: "rule-ne-1",
  rule_number: 4, rule_name: "RULE-FOXTROT", target_device_ids: [], outcome: "NOT_EVALUABLE", reason: "IPv6 not evaluated",
  highest_severity: null, flags: [], covering_rule_id: null, undecided_parts: [], violated_pairs: [],
  first_observed_at: isoHoursAgo(3), first_observed_any_version: null, last_observed_at: isoHoursAgo(1),
  run_id: "run-1", version_id: "v-active", version_number: 3, content_sha256: HASH, on_active_version: true,
  policy_staleness: "CURRENT", snapshot_age_seconds: 7200,
});

const headline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active", version_number: 3, distinct_violating_rules: 0, violated_zone_pairs: 0, critical_rules: 0,
  covered_by_earlier_rule: 0, conditional_rules: 0, outside_headline_ordered_layer: 0, not_evaluable_findings: 0, unknown_findings: 0,
  became_undecided_findings: 0, stale_policies: 0, incomplete_policies: 0, older_version_policies: 0, not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 0, exact: true, count_text: "0", text: "0 rules violate the zone matrix (v3)",
  outside_headline_text: null, staleness_headline: { count: 0, exact: true, text: "0" }, ...over,
});

const summary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  can: CAN, evaluated_at: new Date(NOW_MS).toISOString(), state: "READY", state_text: "findings of the latest complete run of each policy",
  masked: true, policy_names_masked: true, witnesses_included: false,
  staleness_threshold: { seconds: 86_400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: headline(), headline_absent_reason: null, policies: [policy()], not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 0, total_findings: 0, returned: 0, more: 0, capped: false,
  findings: [],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  cell_counts: [], cell_counts_exact: true, cell_counts_total: 0, cell_counts_returned: 0, cell_counts_more: 0, cell_counts_capped: false,
  ...over,
});

const resolved = (over: Partial<SummaryResolvedFinding> = {}): SummaryResolvedFinding => ({
  ...notEvaluableFinding(),
  outcome: "VIOLATION", reason: null, highest_severity: "HIGH",
  violated_pairs: [{
    source_zone_id: "z-1", destination_zone_id: "z-2", source_name: "ZONE-1", destination_name: "ZONE-2",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [],
  }],
  resolved_from_version_id: "v-old", resolved_from_version_number: 2,
  resolved_in_version_id: "v-active", resolved_in_version_number: 3, resolved_at: isoDaysAgo(1),
  ...over,
});

function serve(body: ZoneMatrixSummaryResponse) {
  const reply = (status: number, payload: unknown) =>
    new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input).split("?")[0];
    if (path === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    if (path === "/api/v2/zone-matrix/summary") return reply(200, body);
    if (path === "/api/v2/zone-matrix/draft") return reply(200, { active: activeSummary, draft: null, draft_exists: false, diff: null, publish_check: null, can: CAN });
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeDetail, can: CAN });
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeSummary], can: CAN });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
}

const openTab = async (name: string) => {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name }));
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Zone matrix round 2 traceability pins: ZM-26", () => {
  // The corrected server answer (ZoneMatrixStaleness.withUndecided): one NOT_EVALUABLE finding alone on a CURRENT and
  // COMPLETE policy makes the staleness headline non-exact, "at least 0".
  const singleNotEvaluable = summary({
    headline: headline({
      exact: false, not_evaluable_findings: 1, count_text: "at least 0", text: "no proven violations in evaluated rules",
      staleness_headline: { count: 0, exact: false, text: "at least 0" },
    }),
    findings: [notEvaluableFinding()], total_findings: 1, unfiltered_findings: 1, returned: 1, cell_counts_exact: false,
  });

  it("zm26_singleNotEvaluableOnCurrentCompletePolicyViolationsTabPrintsAtLeastZeroNeverABareZero", async () => {
    serve(singleNotEvaluable);
    await openTab("Violations");
    const box = await screen.findByTestId("summary-headline");
    expect(box).toHaveAttribute("data-proven", "no");
    expect(box.querySelector('[data-pill="ok"]')).toBeNull();
    expect(within(box).getByTestId("headline-count-text")).toHaveTextContent("at least 0");
    expect(within(box).getByTestId("staleness-headline")).toHaveTextContent("at least 0");
    for (const el of Array.from(box.querySelectorAll("*"))) {
      expect((el.textContent ?? "").trim(), "bare zero in the Violations headline").not.toBe("0");
    }
  });

  it("zm26_singleNotEvaluableOnCurrentCompletePolicyCoverageTabPrintsAtLeastZeroNeverABareZero", async () => {
    serve(singleNotEvaluable);
    await openTab("Coverage");
    const line = await screen.findByTestId("staleness-headline");
    expect(line).toHaveTextContent("at least 0");
    expect(line.textContent?.trim()).not.toBe("0");
  });
});

describe("Zone matrix round 2 traceability pins: ZM-36", () => {
  const unmaskedNames = { rule: "RULE-PLANTED-CARDHOLDER", policy: "POLICY-PLANTED-CARDHOLDER", zoneA: "ZONE-PLANTED-A", zoneB: "ZONE-PLANTED-B" };

  it("zm36_versionsTabListsEachResolvedFindingUnderItsResolvingVersionNotOnlyCounts", async () => {
    serve(summary({
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 3, returned: 3, more: 0, capped: false,
        last_7_days: [
          resolved({ rule_uid: "uid-a", rule_number: 11, rule_name: "RULE-ECHO" }),
          resolved({ rule_uid: "uid-b", rule_number: 12, rule_name: "RULE-HOTEL" }),
          resolved({
            rule_uid: "uid-c", rule_number: 13, rule_name: "RULE-INDIA", resolved_from_version_id: "v-older", resolved_from_version_number: 1,
            resolved_in_version_id: "v-mid", resolved_in_version_number: 2,
          }),
        ],
        by_version: [
          { version_id: "v-active", version_number: 3, count: 2 },
          { version_id: "v-mid", version_number: 2, count: 1 },
        ],
      },
    }));
    await openTab("Versions");
    const section = await screen.findByTestId("versions-resolved-lists");
    const v3 = await within(section).findByTestId("resolved-version-group-v-active");
    const v3List = within(v3).getByRole("list", { name: "Findings resolved by version 3" });
    expect(within(v3List).getAllByRole("listitem")).toHaveLength(2);
    expect(v3List).toHaveTextContent("#11 RULE-ECHO");
    expect(v3List).toHaveTextContent("#12 RULE-HOTEL");
    expect(v3List).not.toHaveTextContent("RULE-INDIA");
    expect(v3List).toHaveTextContent("version 2 -> version 3");

    const v2 = within(section).getByTestId("resolved-version-group-v-mid");
    const v2List = within(v2).getByRole("list", { name: "Findings resolved by version 2" });
    expect(within(v2List).getAllByRole("listitem")).toHaveLength(1);
    expect(v2List).toHaveTextContent("#13 RULE-INDIA");
    expect(v2List).toHaveTextContent("version 1 -> version 2");
    expect(within(v3).queryByTestId("resolved-version-more-v-active")).toBeNull();
    expect(within(v2).queryByTestId("resolved-version-more-v-mid")).toBeNull();
  });

  it("zm36_aFindingResolvedMoreThanSevenDaysAgoIsCountedAllTimeButNeverListedInTheSevenDayWindow", async () => {
    // The server returns only rows resolved within 7 days (day 8 is dropped); the all-time by_version count still has it.
    serve(summary({
      headline: headline({ resolved_by_version_change_last_7_days: 1 }),
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 1, returned: 1, more: 0, capped: false,
        last_7_days: [resolved({ rule_uid: "uid-new", rule_number: 21, rule_name: "RULE-NEW", resolved_at: isoDaysAgo(6) })],
        by_version: [{ version_id: "v-active", version_number: 3, count: 2 }],
      },
    }));
    await openTab("Versions");
    const section = await screen.findByTestId("versions-resolved-lists");
    expect(await within(section).findByTestId("versions-resolved-window-count")).toHaveTextContent(
      "1 finding resolved by a matrix version change in the recent 7-day window (1 returned).",
    );
    const group = within(section).getByTestId("resolved-version-group-v-active");
    expect(group).toHaveTextContent("Version 3: 2 findings resolved in total (all time); 1 returned from the recent 7-day window.");
    expect(within(group).getAllByRole("listitem")).toHaveLength(1);
    expect(group).toHaveTextContent("#21 RULE-NEW");
    // The finding older than the window is not listed individually; it appears only as the unreturned remainder.
    expect(within(group).getByTestId("resolved-version-more-v-active")).toHaveTextContent("+1 more resolved by version 3");
    expect(within(group).getByTestId("resolved-version-more-v-active")).toHaveTextContent("individual rows older than 7 days are not returned");
  });

  it("zm36_aCappedListPrintsPlusNMoreForTheWindowAndForEachVersion", async () => {
    serve(summary({
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 5, returned: 2, more: 3, capped: true,
        last_7_days: [
          resolved({ rule_uid: "uid-a", rule_number: 31, rule_name: "RULE-ECHO" }),
          resolved({ rule_uid: "uid-b", rule_number: 32, rule_name: "RULE-HOTEL" }),
        ],
        by_version: [{ version_id: "v-active", version_number: 3, count: 5 }],
      },
    }));
    await openTab("Versions");
    const section = await screen.findByTestId("versions-resolved-lists");
    expect(await within(section).findByTestId("versions-resolved-window-count")).toHaveTextContent(
      "5 findings resolved by a matrix version change in the recent 7-day window (2 returned, +3 more not shown).",
    );
    const group = within(section).getByTestId("resolved-version-group-v-active");
    expect(within(group).getAllByRole("listitem")).toHaveLength(2);
    expect(within(group).getByTestId("resolved-version-more-v-active")).toHaveTextContent("+3 more resolved by version 3");
  });

  it("zm36_aMaskedViewListsNoZoneRuleOrSourceNamesWhileAnUnmaskedViewOfTheSameRowsDoes", async () => {
    const rows = (masked: boolean) => [
      resolved({
        rule_uid: "uid-m", rule_number: 41,
        rule_name: masked ? "RULE-0041" : unmaskedNames.rule,
        policy_name: masked ? "POLICY-MASKED-01" : unmaskedNames.policy,
        violated_pairs: [{
          source_zone_id: "z-1", destination_zone_id: "z-2",
          source_name: masked ? "ZONE-1" : unmaskedNames.zoneA, destination_name: masked ? "ZONE-2" : unmaskedNames.zoneB,
          statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [],
        }],
      }),
    ];
    const body = (masked: boolean) => summary({
      masked, policy_names_masked: masked,
      resolved_by_version_change: {
        window_days: 7, total_last_7_days: 1, returned: 1, more: 0, capped: false, last_7_days: rows(masked),
        by_version: [{ version_id: "v-active", version_number: 3, count: 1 }],
      },
    });

    // Control: the unmasked view prints the names the server sends, so the planted values are visible to this assertion.
    serve(body(false));
    const first = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Versions" }));
    const unmaskedText = (await screen.findByTestId("versions-resolved-lists")).textContent ?? "";
    for (const planted of Object.values(unmaskedNames)) expect(unmaskedText).toContain(planted);
    first.unmount();
    vi.unstubAllGlobals();

    // Masked (aiview) view: pseudonyms only, no zone, rule, policy or source name, and no address.
    serve(body(true));
    await openTab("Versions");
    const section = await screen.findByTestId("versions-resolved-lists");
    const list = await within(section).findByRole("list", { name: "Findings resolved by version 3" });
    expect(list).toHaveTextContent("#41 RULE-0041 · POLICY-MASKED-01 · ZONE-1 -> ZONE-2 · version 2 -> version 3");
    const maskedText = section.textContent ?? "";
    for (const planted of Object.values(unmaskedNames)) expect(maskedText).not.toContain(planted);
    expect(maskedText).not.toMatch(/\b(?:192\.0\.2|198\.51\.100)\./);
  });
});
