import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import {
  NO_REASON_VALUE, UNKNOWN_VENDOR_VALUE,
  buildDeviceOptions, buildPolicySourceOptions, buildPolicyVendorByRunId, buildReasonOptions, buildVendorOptions,
  matchesFirstObserved, matchesViolationFilters, normalizePolicyStaleness, reasonLabel,
} from "../src/screens/zonematrix/violationFilters";
import type { SummaryFinding, SummaryHeadline, SummaryPolicy, ZoneMatrixSummaryResponse } from "../src/screens/zonematrix/zoneMatrixApi";

/** Zone matrix step 4b-2: contract 7.1 client-side filters on the Violations tab. Synthetic values only. */
const HASH = "d".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN = { view_blocks: false, edit: false, publish: false };
const FIXED_NOW_ISO = "2026-11-25T12:00:00Z";
const FIXED_NOW_MS = Date.parse(FIXED_NOW_ISO);

const activeSummary = {
  version_id: "v-active", number: 4, state: "ACTIVE", revision: 6, content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z",
};
const activeDetail = { ...activeSummary, zones: [], unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [] };

const run = (runId: string, over: Record<string, unknown> = {}) => ({
  run_id: runId, state: "COMPLETE", failure_reason: null, version_id: "v-active", version_number: 4, content_sha256: HASH,
  started_at: "2026-11-25T07:00:00Z", finished_at: "2026-11-25T07:05:00Z", snapshot_collected_at: "2026-11-25T06:00:00Z",
  coverage: "COMPLETE", counts: null, ...over,
});

const policy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-1", policy_name: "POLICY-ALFA", vendor: "CP", source_id: "src-1", source_name: "FW-TANGO-04",
  container_id: null, container_name: null, collected_at: "2026-11-25T06:00:00Z", rule_count: 18,
  staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 5400, snapshot_age_known: true },
  coverage: "COMPLETE", coverage_items: [], collection_failures: 0, has_complete_run: true,
  latest_complete_run: run("run-cp-1"), latest_run: run("run-cp-1"), ...over,
});

const finding = (over: Partial<SummaryFinding> = {}): SummaryFinding => ({
  policy_source_id: "pol-1", policy_name: "POLICY-ALFA", rule_uid: "uid-1", inline_parent_path: [], rule_id: "rule-1", rule_number: 1,
  rule_name: "RULE-ALFA", target_device_ids: ["FW-TANGO-04"], outcome: "VIOLATION", reason: null, highest_severity: "HIGH", flags: [],
  covering_rule_id: null, undecided_parts: [],
  violated_pairs: [{
    source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [],
  }],
  first_observed_at: "2026-11-25T06:00:00Z", first_observed_any_version: "2026-11-18T08:00:00Z", last_observed_at: "2026-11-25T07:05:00Z",
  run_id: "run-cp-1", version_id: "v-active", version_number: 4, content_sha256: HASH, on_active_version: true,
  policy_staleness: "CURRENT", snapshot_age_seconds: 5400, ...over,
});

const headline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active", version_number: 4, distinct_violating_rules: 5, violated_zone_pairs: 2, critical_rules: 1,
  covered_by_earlier_rule: 0, conditional_rules: 0, outside_headline_ordered_layer: 1, not_evaluable_findings: 1, unknown_findings: 1,
  became_undecided_findings: 0, stale_policies: 1, incomplete_policies: 0, older_version_policies: 0, not_evaluated_policies: 0,
  resolved_by_version_change_last_7_days: 2, exact: false, count_text: "At least 5 violating rules across 2 policies",
  text: "At least 5 rules violate the zone matrix", outside_headline_text: "+1 rule outside headline in another ordered layer",
  staleness_headline: { count: 1, exact: false, text: "At least 1 policy is stale" }, ...over,
});

const summary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  evaluated_at: FIXED_NOW_ISO, state: "READY", state_text: "findings of the latest complete run of each policy",
  masked: true, policy_names_masked: true, witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 4, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: headline(), headline_absent_reason: null, policies: [policy()], not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 1, total_findings: 1, returned: 1, more: 0, capped: false,
  findings: [finding()],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 2, returned: 0, more: 2, capped: true, last_7_days: [], by_version: [] },
  ...over,
});

interface Served { readonly gets: string[] }
function serve(body: ZoneMatrixSummaryResponse): Served {
  const served: Served = { gets: [] };
  const reply = (status: number, payload: unknown) => new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    served.gets.push(url);
    const path = url.split("?")[0];
    if (path === "/api/v2/zone-matrix/summary") return reply(200, body);
    if (path === "/api/v2/zone-matrix/draft") return reply(200, { active: activeSummary, draft: null, diff: null, publish_check: null, can: CAN });
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeDetail, can: CAN });
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeSummary], can: CAN });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
  return served;
}

const summaryGets = (s: Served) => s.gets.filter((u) => u.startsWith("/api/v2/zone-matrix/summary"));

const openViolations = async () => {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
  return screen.findByRole("table", { name: "Violation findings" });
};

const chooseOption = async (comboboxName: string | RegExp, optionName: string) => {
  fireEvent.mouseDown(screen.getByRole("combobox", { name: comboboxName }));
  fireEvent.click(await screen.findByRole("option", { name: optionName }));
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("violationFilters pure helpers", () => {
  it("builds sorted, de-duplicated options for vendor, policy source, device and reason", () => {
    const policies = [
      policy({ policy_id: "pol-pan", vendor: "PAN", source_id: "src-pan", source_name: "FW-JULIET-06", latest_complete_run: run("run-pan-1") }),
      policy({ policy_id: "pol-cp-1", vendor: "CP", source_id: "src-cp-1", source_name: "FW-TANGO-04", latest_complete_run: run("run-cp-1") }),
      policy({ policy_id: "pol-cp-2", vendor: "CP", source_id: "src-cp-2", source_name: null, latest_complete_run: run("run-cp-2") }),
    ];
    const findings = [
      finding({ run_id: "run-pan-1", policy_source_id: "pol-pan", target_device_ids: ["FW-JULIET-06", "FW-BRAVO-02"], reason: "NAT translation unresolved" }),
      finding({ run_id: "run-cp-1", policy_source_id: "pol-cp-1", target_device_ids: ["FW-TANGO-04", "FW-BRAVO-02"], reason: "SNAPSHOT_UNREADABLE" }),
      finding({ run_id: "run-cp-2", policy_source_id: "pol-cp-2", target_device_ids: ["FW-TANGO-04"], reason: "IPv6 not evaluated" }),
      finding({ run_id: "run-cp-2", policy_source_id: "POL-CP-2", target_device_ids: [], reason: "SNAPSHOT_UNREADABLE" }),
    ];
    const vendorByRun = buildPolicyVendorByRunId(policies);

    // Every finding joins a policy run, so "Vendor unknown" is omitted; duplicate "CP" is collapsed and sorted.
    expect(buildVendorOptions(policies, findings, vendorByRun)).toEqual([
      { value: "CP", label: "CP" },
      { value: "PAN", label: "PAN" },
    ]);

    // When an unjoined finding exists, "Vendor unknown" is appended as its own option.
    const withOrphan = [...findings, finding({ run_id: "run-orphan", policy_name: "PAN-LOOKALIKE" })];
    expect(buildVendorOptions(policies, withOrphan, vendorByRun)).toEqual([
      { value: "CP", label: "CP" },
      { value: "PAN", label: "PAN" },
      { value: UNKNOWN_VENDOR_VALUE, label: "Vendor unknown" },
    ]);

    // Policy source uses policies[].source_name when present, else opaque id without case folding.
    expect(buildPolicySourceOptions(findings, policies)).toEqual([
      { value: "pol-pan", label: "FW-JULIET-06" },
      { value: "pol-cp-1", label: "FW-TANGO-04" },
      { value: "pol-cp-2", label: "pol-cp-2" },
      { value: "POL-CP-2", label: "POL-CP-2" },
    ]);

    // Devices are distinct target_device_ids sorted as sent.
    expect(buildDeviceOptions(findings)).toEqual([
      { value: "FW-BRAVO-02", label: "FW-BRAVO-02" },
      { value: "FW-JULIET-06", label: "FW-JULIET-06" },
      { value: "FW-TANGO-04", label: "FW-TANGO-04" },
    ]);

    // Reasons format machine tokens via flagWords and keep prose reasons verbatim, sorted and de-duplicated.
    expect(reasonLabel("SNAPSHOT_UNREADABLE")).toBe("Snapshot unreadable");
    expect(reasonLabel("IPv6 not evaluated")).toBe("IPv6 not evaluated");
    expect(reasonLabel("UNKNOWN")).toBe("UNKNOWN");
    expect(buildReasonOptions(findings)).toEqual([
      { value: "IPv6 not evaluated", label: "IPv6 not evaluated" },
      { value: "NAT translation unresolved", label: "NAT translation unresolved" },
      { value: "SNAPSHOT_UNREADABLE", label: "Snapshot unreadable" },
    ]);
  });

  it("joins vendor strictly by run_id, keeps policy_source_id opaque, and normalises unrecognised staleness to UNKNOWN", () => {
    const policies = [
      policy({ policy_id: "pol-1", vendor: "PAN", latest_complete_run: run("run-pan-1") }),
    ];
    const vendorByRun = buildPolicyVendorByRunId(policies);

    // Even if policy_source_id and policy_name look like PAN, an unjoined run_id has vendor UNKNOWN.
    const orphan = finding({
      run_id: "run-unlisted", policy_source_id: "pol-1", policy_name: "PAN-FIREWALL-POLICY",
      policy_staleness: "UNRECOGNISED_VALUE", reason: null,
    });
    expect(matchesViolationFilters(orphan, vendorByRun, {}, FIXED_NOW_MS)).toBe(true);
    expect(matchesViolationFilters(orphan, vendorByRun, { vendor: "PAN" }, FIXED_NOW_MS)).toBe(false);
    expect(matchesViolationFilters(orphan, vendorByRun, { vendor: UNKNOWN_VENDOR_VALUE }, FIXED_NOW_MS)).toBe(true);

    // Opaque policy_source_id: no case folding, no trimming.
    expect(matchesViolationFilters(orphan, vendorByRun, { policySourceId: "pol-1" }, FIXED_NOW_MS)).toBe(true);
    expect(matchesViolationFilters(orphan, vendorByRun, { policySourceId: "POL-1" }, FIXED_NOW_MS)).toBe(false);
    expect(matchesViolationFilters(orphan, vendorByRun, { policySourceId: " pol-1 " }, FIXED_NOW_MS)).toBe(false);

    // Staleness: only CURRENT and STALE keep their verdict; everything else is UNKNOWN, never CURRENT.
    expect(normalizePolicyStaleness("CURRENT")).toBe("CURRENT");
    expect(normalizePolicyStaleness("STALE")).toBe("STALE");
    expect(normalizePolicyStaleness("UNKNOWN")).toBe("UNKNOWN");
    expect(normalizePolicyStaleness("current")).toBe("UNKNOWN");
    expect(normalizePolicyStaleness("PENDING")).toBe("UNKNOWN");
    expect(normalizePolicyStaleness("")).toBe("UNKNOWN");
    expect(matchesViolationFilters(orphan, vendorByRun, { staleness: "CURRENT" }, FIXED_NOW_MS)).toBe(false);
    expect(matchesViolationFilters(orphan, vendorByRun, { staleness: "STALE" }, FIXED_NOW_MS)).toBe(false);
    expect(matchesViolationFilters(orphan, vendorByRun, { staleness: "UNKNOWN" }, FIXED_NOW_MS)).toBe(true);
  });

  it("evaluates first_observed_at against fixed nowMs and puts null or unparsable timestamps only in Not recorded", () => {
    const exact24hIso = new Date(FIXED_NOW_MS - 24 * 3600 * 1000).toISOString();
    const over24hIso = new Date(FIXED_NOW_MS - 24 * 3600 * 1000 - 1).toISOString();
    const exact7dIso = new Date(FIXED_NOW_MS - 7 * 24 * 3600 * 1000).toISOString();
    const over7dIso = new Date(FIXED_NOW_MS - 7 * 24 * 3600 * 1000 - 1).toISOString();
    const futureIso = new Date(FIXED_NOW_MS + 60_000).toISOString();

    expect(matchesFirstObserved(exact24hIso, "LAST_24_HOURS", FIXED_NOW_MS)).toBe(true);
    expect(matchesFirstObserved(exact24hIso, "LAST_7_DAYS", FIXED_NOW_MS)).toBe(true);
    expect(matchesFirstObserved(exact24hIso, "OLDER_THAN_7_DAYS", FIXED_NOW_MS)).toBe(false);
    expect(matchesFirstObserved(exact24hIso, "NOT_RECORDED", FIXED_NOW_MS)).toBe(false);

    expect(matchesFirstObserved(over24hIso, "LAST_24_HOURS", FIXED_NOW_MS)).toBe(false);
    expect(matchesFirstObserved(over24hIso, "LAST_7_DAYS", FIXED_NOW_MS)).toBe(true);

    expect(matchesFirstObserved(exact7dIso, "LAST_7_DAYS", FIXED_NOW_MS)).toBe(true);
    expect(matchesFirstObserved(exact7dIso, "OLDER_THAN_7_DAYS", FIXED_NOW_MS)).toBe(false);

    expect(matchesFirstObserved(over7dIso, "LAST_7_DAYS", FIXED_NOW_MS)).toBe(false);
    expect(matchesFirstObserved(over7dIso, "OLDER_THAN_7_DAYS", FIXED_NOW_MS)).toBe(true);

    for (const bad of [null, undefined, "", "not-a-date", "UNKNOWN", "2026-02-30T08:00:00Z", "2026-13-01T00:00:00Z"]) {
      expect(matchesFirstObserved(bad, "NOT_RECORDED", FIXED_NOW_MS)).toBe(true);
      expect(matchesFirstObserved(bad, "LAST_24_HOURS", FIXED_NOW_MS)).toBe(false);
      expect(matchesFirstObserved(bad, "LAST_7_DAYS", FIXED_NOW_MS)).toBe(false);
      expect(matchesFirstObserved(bad, "OLDER_THAN_7_DAYS", FIXED_NOW_MS)).toBe(false);
    }

    // A future timestamp is not "Not recorded" and never enters a past range.
    expect(matchesFirstObserved(futureIso, "NOT_RECORDED", FIXED_NOW_MS)).toBe(false);
    expect(matchesFirstObserved(futureIso, "LAST_24_HOURS", FIXED_NOW_MS)).toBe(false);
    expect(matchesFirstObserved(futureIso, "LAST_7_DAYS", FIXED_NOW_MS)).toBe(false);
    expect(matchesFirstObserved(futureIso, "OLDER_THAN_7_DAYS", FIXED_NOW_MS)).toBe(false);
  });
});

describe("Zone matrix Violations tab: contract 7.1 filters", () => {
  const multiBody = () => {
    const policies = [
      policy({ policy_id: "pol-cp", policy_name: "POLICY-ALFA", vendor: "CP", source_id: "src-cp", source_name: "FW-TANGO-04", latest_complete_run: run("run-cp") }),
      policy({ policy_id: "pol-pan", policy_name: "POLICY-BRAVO", vendor: "PAN", source_id: "src-pan", source_name: "FW-JULIET-06", latest_complete_run: run("run-pan") }),
    ];
    const findings = [
      finding({
        rule_uid: "uid-1", rule_id: "rule-1", rule_number: 1, rule_name: "RULE-ALFA",
        run_id: "run-cp", policy_source_id: "pol-cp", policy_name: "POLICY-ALFA",
        target_device_ids: ["FW-TANGO-04", "FW-BRAVO-02"],
        reason: null, highest_severity: "CRITICAL",
        first_observed_at: "2026-11-25T06:00:00Z", // 6 hours before FIXED_NOW_ISO
        policy_staleness: "CURRENT",
      }),
      finding({
        rule_uid: "uid-2", rule_id: "rule-2", rule_number: 2, rule_name: "RULE-BRAVO",
        run_id: "run-pan", policy_source_id: "pol-pan", policy_name: "POLICY-BRAVO",
        target_device_ids: ["FW-JULIET-06"],
        outcome: "NOT_EVALUABLE", reason: "IPv6 not evaluated", highest_severity: null,
        first_observed_at: "2026-11-22T12:00:00Z", // 3 days before FIXED_NOW_ISO
        policy_staleness: "STALE",
      }),
      finding({
        rule_uid: "uid-3", rule_id: "rule-3", rule_number: 3, rule_name: "RULE-CHARLIE",
        run_id: "run-pan", policy_source_id: "pol-pan", policy_name: "POLICY-BRAVO",
        target_device_ids: ["FW-JULIET-06", "FW-BRAVO-02"],
        outcome: "UNKNOWN", reason: "SNAPSHOT_UNREADABLE", highest_severity: null,
        first_observed_at: "2026-11-10T12:00:00Z", // 15 days before FIXED_NOW_ISO
        policy_staleness: "MYSTERY_VERDICT",
      }),
      finding({
        rule_uid: "uid-4", rule_id: "rule-4", rule_number: 4, rule_name: "RULE-DELTA",
        run_id: "run-orphan", policy_source_id: "pol-unlisted", policy_name: "PAN-NAME-DECOY",
        target_device_ids: ["FW-BRAVO-02"],
        outcome: "NOT_EVALUABLE", reason: "unresolved object", highest_severity: "LOW",
        first_observed_at: "corrupt-timestamp",
        policy_staleness: "CURRENT",
      }),
      finding({
        rule_uid: "uid-5", rule_id: "rule-5", rule_number: 5, rule_name: "RULE-ECHO",
        run_id: "run-cp", policy_source_id: "POL-CP", policy_name: "POLICY-CASE",
        target_device_ids: [],
        outcome: "VIOLATION", reason: null, highest_severity: "HIGH",
        first_observed_at: null,
        policy_staleness: "STALE",
      }),
    ];
    return summary({
      policies,
      findings,
      unfiltered_findings: 5,
      total_findings: 5,
      returned: 5,
    });
  };

  it("narrows by Vendor joined by run_id, placing unjoined runs only in Vendor unknown", async () => {
    vi.spyOn(Date, "now").mockReturnValue(FIXED_NOW_MS);
    const s = serve(multiBody());
    const table = await openViolations();
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);

    expect(screen.getByText(/vendor, policy source, device, flag, reason, first observed and staleness only narrow the rows returned/i)).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: /First observed \(current version history, not rule's age\)/ })).toBeInTheDocument();

    // Joins by run_id; RULE-DELTA (run-orphan, policy_name PAN-NAME-DECOY) lands only in "Vendor unknown", never PAN.
    await chooseOption("Vendor", "PAN");
    expect(within(table).getByText(/RULE-BRAVO/)).toBeInTheDocument();
    expect(within(table).getByText(/RULE-CHARLIE/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-DELTA/)).not.toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ALFA/)).not.toBeInTheDocument();
    expect(screen.getByTestId("findings-count")).toHaveTextContent("5 of 5 findings returned by the server; 2 match the filters on this page.");

    await chooseOption("Vendor", "Vendor unknown");
    expect(within(table).getByText(/RULE-DELTA/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-BRAVO/)).not.toBeInTheDocument();
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);
  });

  it("narrows by Policy source with source_name labels and opaque id equality", async () => {
    vi.spyOn(Date, "now").mockReturnValue(FIXED_NOW_MS);
    const s = serve(multiBody());
    const table = await openViolations();

    await chooseOption("Policy source", "FW-TANGO-04");
    expect(within(table).getByText(/RULE-ALFA/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ECHO/)).not.toBeInTheDocument();

    await chooseOption("Policy source", "POL-CP");
    expect(within(table).getByText(/RULE-ECHO/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ALFA/)).not.toBeInTheDocument();
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);
  });

  it("narrows by Device and by Reason including No reason recorded without extra requests", async () => {
    vi.spyOn(Date, "now").mockReturnValue(FIXED_NOW_MS);
    const s = serve(multiBody());
    const table = await openViolations();

    await chooseOption("Device", "FW-JULIET-06");
    expect(within(table).getByText(/RULE-BRAVO/)).toBeInTheDocument();
    expect(within(table).getByText(/RULE-CHARLIE/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ALFA/)).not.toBeInTheDocument();
    await chooseOption("Device", "All devices");

    await chooseOption("Reason", "Snapshot unreadable");
    expect(within(table).getByText(/RULE-CHARLIE/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ALFA/)).not.toBeInTheDocument();

    await chooseOption("Reason", "No reason recorded");
    expect(within(table).getByText(/RULE-ALFA/)).toBeInTheDocument();
    expect(within(table).getByText(/RULE-ECHO/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-BRAVO/)).not.toBeInTheDocument();
    expect(within(table).queryByText(/RULE-DELTA/)).not.toBeInTheDocument();
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);
  });

  it("narrows by First observed ranges relative to render time and keeps unparsable dates only in Not recorded", async () => {
    vi.spyOn(Date, "now").mockReturnValue(FIXED_NOW_MS);
    const s = serve(multiBody());
    const table = await openViolations();

    await chooseOption(/First observed/, "Last 24 hours");
    expect(within(table).getByText(/RULE-ALFA/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-BRAVO/)).not.toBeInTheDocument();
    expect(within(table).queryByText(/RULE-DELTA/)).not.toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ECHO/)).not.toBeInTheDocument();

    await chooseOption(/First observed/, "Last 7 days");
    expect(within(table).getByText(/RULE-ALFA/)).toBeInTheDocument();
    expect(within(table).getByText(/RULE-BRAVO/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-CHARLIE/)).not.toBeInTheDocument();
    expect(within(table).queryByText(/RULE-DELTA/)).not.toBeInTheDocument();

    await chooseOption(/First observed/, "Older than 7 days");
    expect(within(table).getByText(/RULE-CHARLIE/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ALFA/)).not.toBeInTheDocument();
    expect(within(table).queryByText(/RULE-DELTA/)).not.toBeInTheDocument();

    await chooseOption(/First observed/, "Not recorded");
    expect(within(table).getByText(/RULE-DELTA/)).toBeInTheDocument();
    expect(within(table).getByText(/RULE-ECHO/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ALFA/)).not.toBeInTheDocument();
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);
  });

  it("narrows by Staleness (unrecognised values as Unknown), combines filters with AND, and preserves the headline on an empty match", async () => {
    vi.spyOn(Date, "now").mockReturnValue(FIXED_NOW_MS);
    const s = serve(multiBody());
    const table = await openViolations();

    await chooseOption("Staleness", "Current");
    expect(within(table).getByText(/RULE-ALFA/)).toBeInTheDocument();
    expect(within(table).getByText(/RULE-DELTA/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-CHARLIE/)).not.toBeInTheDocument();

    await chooseOption("Staleness", "Stale");
    expect(within(table).getByText(/RULE-BRAVO/)).toBeInTheDocument();
    expect(within(table).getByText(/RULE-ECHO/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-CHARLIE/)).not.toBeInTheDocument();

    await chooseOption("Staleness", "Unknown");
    expect(within(table).getByText(/RULE-CHARLIE/)).toBeInTheDocument();
    expect(within(table).queryByText(/RULE-ALFA/)).not.toBeInTheDocument();

    await chooseOption("Severity", "Critical");
    expect(within(table).getByText("No returned finding matches the filters on this page.")).toBeInTheDocument();
    expect(screen.getByTestId("findings-count")).toHaveTextContent("5 of 5 findings returned by the server; 0 match the filters on this page.");
    expect(screen.getByTestId("summary-headline")).toHaveTextContent("At least 5 rules violate the zone matrix");
    expect(screen.getByTestId("headline-count-text")).toHaveTextContent("At least 5 violating rules across 2 policies");
    expect(screen.getByTestId("outside-headline-text")).toHaveTextContent("+1 rule outside headline in another ordered layer");
    expect(screen.getByTestId("staleness-headline")).toHaveTextContent("At least 1 policy is stale");
    expect(summaryGets(s)).toEqual(["/api/v2/zone-matrix/summary"]);
  });

  it("resets paging to the first page when a new filter is selected", async () => {
    vi.spyOn(Date, "now").mockReturnValue(FIXED_NOW_MS);
    const bulkFindings = Array.from({ length: 65 }, (_, i) => finding({
      rule_uid: `uid-${i + 1}`,
      rule_id: `rule-${i + 1}`,
      rule_number: i + 1,
      rule_name: `RULE-ITEM-${i + 1}`,
      policy_staleness: i < 60 ? "CURRENT" : "STALE",
    }));
    serve(summary({
      findings: bulkFindings,
      unfiltered_findings: 65,
      total_findings: 65,
      returned: 65,
    }));

    const table = await openViolations();
    expect(screen.getByTestId("pagination-footer")).toHaveTextContent("1–50 of 65 findings");
    expect(within(table).getByText(/RULE-ITEM-1\b/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByTestId("pagination-footer")).toHaveTextContent("51–65 of 65 findings");
    expect(within(table).getByText(/RULE-ITEM-65\b/)).toBeInTheDocument();

    // Selecting a filter (60 matching rows, still > 1 page) resets offset back to 0 (1–50 of 60 findings).
    await chooseOption("Staleness", "Current");
    expect(screen.getByTestId("pagination-footer")).toHaveTextContent("1–50 of 60 findings");
    expect(within(table).getByText(/RULE-ITEM-1\b/)).toBeInTheDocument();
  });
});
