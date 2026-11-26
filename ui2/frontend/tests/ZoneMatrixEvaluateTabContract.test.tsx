import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

import { evaluationHeadlineText, evaluationProven, evaluationWitnessTraceHref } from "../src/screens/zonematrix/EvaluateTab";
import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type {
  EvaluationRuleRow, EvaluationWitness, ZoneMatrixCan, ZoneMatrixEvaluationResponse, ZoneMatrixPoliciesResponse,
  ZoneVersionDetail, ZoneVersionSummary,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Contract pins for Zone Matrix EvaluateTab (docs/design/ZONE_MATRIX_CONTRACT.md sections 7.1-7.2; ZM-7, ZM-26,
 * ZM-30, ZM-33): exactness / green-headline proof, lower-bound "at least N" counts, "Open in Policy" deep links, and
 * fail-closed "Open in Trace" witness deep links gated on view_blocks + witnesses_included. Synthetic values only.
 */
const HASH = "a".repeat(64);
const FULL_CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true, evaluate: true };
const NO_BLOCKS_CAN: ZoneMatrixCan = { view_blocks: false, edit: true, publish: true, confirm_reference: true, evaluate: true };

const ACTIVE_SUMMARY: ZoneVersionSummary = {
  version_id: "v-active",
  number: 3,
  state: "ACTIVE",
  revision: 4,
  content_sha256: HASH,
  created_at: "2026-11-13T10:00:00Z",
  updated_at: "2026-11-13T10:05:00Z",
  published_by: "user-compliance-1",
  published_at: "2026-11-13T10:06:00Z",
};

const ACTIVE_DETAIL: ZoneVersionDetail = {
  ...ACTIVE_SUMMARY,
  zones: [
    {
      zone_id: "z1",
      name: "ZONE-ALFA",
      description: null,
      role: "USER",
      block_count: 1,
      effective_addresses: { IPV4: "256", IPV6: "0" },
      blocks: null,
    },
    {
      zone_id: "z2",
      name: "ZONE-BRAVO",
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

function makePolicies(can: ZoneMatrixCan, policyId = "pol-pan-1"): ZoneMatrixPoliciesResponse {
  return {
    policies: [
      {
        id: policyId,
        name: "POLICY-ALFA",
        vendor: "PAN",
        source_id: "src-1",
        source_name: "FW-TANGO-04",
        container_id: "dg-1",
        container_name: "DOM-BRAVO-02",
        collected_at: "2026-11-15T08:00:00Z",
        rule_count: 10,
        target_count: 1,
      },
    ],
    not_evaluated: [],
    can,
  };
}

function makeViolatingRule(witness: EvaluationWitness | null, ruleId = "rule-7"): EvaluationRuleRow {
  return {
    finding_identity: { policy_id: "pol-pan-1", rule_uid: "uid-r7", inline_parent_path: [], violated_pairs: ["z1->z2"] },
    rule_id: ruleId,
    rule_uid: "uid-r7",
    rule_number: 7,
    rule_name: "RULE-ECHO",
    section_id: "s1",
    section_name: "Pre rules",
    inline_parent_path: [],
    target_device_ids: ["gw-1"],
    outcome: "VIOLATION",
    reason: null,
    highest_severity: "CRITICAL",
    flags: [],
    covering_rule_id: null,
    touched_pairs: ["z1->z2"],
    touched_pair_names: ["ZONE-ALFA -> ZONE-BRAVO"],
    violated_cells: [
      {
        source: "z1",
        destination: "z2",
        source_name: "ZONE-ALFA",
        destination_name: "ZONE-BRAVO",
        statement: "DENY_ALL",
        severity: "CRITICAL",
        not_allowed_services: ["tcp/443"],
        nat_view: "ORIGINAL",
        witness,
      },
    ],
    undecided_parts: [],
    nat_view: "ORIGINAL",
  };
}

function makeEval(over: Partial<ZoneMatrixEvaluationResponse> = {}): ZoneMatrixEvaluationResponse {
  return {
    engine_version: "step3-v1",
    version_id: "v-active",
    version_number: 3,
    content_sha256: HASH,
    policy_id: "pol-pan-1",
    policy_name: "POLICY-ALFA",
    source_id: "src-1",
    source_name: "FW-TANGO-04",
    container_id: "dg-1",
    container_name: "DOM-BRAVO-02",
    vendor: "PAN",
    collected_at: "2026-11-15T08:00:00Z",
    witnesses_included: false,
    targets: [{ device_id: "gw-1", name: "FW-BRAVO-02", context: "vsys1", sync_status: "IN_SYNC" }],
    evaluated: true,
    not_evaluated_reason: null,
    coverage: "COMPLETE",
    coverage_items: [],
    failure_count: 0,
    outcome_counts: { VIOLATION: 0, COMPLIANT: 3, NOT_EVALUABLE: 0, UNKNOWN: 0 },
    not_applicable: {
      disabled: 0,
      deny_type: 0,
      outside_security_rulebase: 0,
      apply_layer_decided_by_inline: 0,
      schedule_ended: 0,
      total: 0,
      ended_schedules: [],
    },
    headline: {
      distinct_violating_rules: 0,
      violated_zone_pairs: 0,
      critical_rules: 0,
      covered_by_earlier_rule: 0,
      conditional_rules: 0,
      outside_headline_ordered_layer: 0,
      not_evaluable_rules: 0,
      unknown_rules: 0,
      incomplete_policies: 0,
      not_evaluated_policies: 0,
      exact: true,
      text: "0 rules violate the zone matrix (v3)",
      outside_headline_text: null,
    },
    rules: [],
    zone_definition_gaps: [],
    hygiene_budget_reached: false,
    duration_ms: 12,
    hygiene_ms: 3,
    can: FULL_CAN,
    ...over,
  };
}

function setupEvaluateServer(evaluation: ZoneMatrixEvaluationResponse, policiesCan: ZoneMatrixCan = evaluation.can) {
  const reply = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const path = url.split("?")[0];
    if (method === "GET" && path === "/api/v2/zone-matrix/draft") {
      return reply(200, { active: ACTIVE_SUMMARY, draft: null, diff: null, publish_check: null, can: policiesCan });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions/v-active") {
      return reply(200, { ...ACTIVE_DETAIL, can: policiesCan });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions") {
      return reply(200, { versions: [ACTIVE_SUMMARY], can: policiesCan });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/policies") {
      return reply(200, makePolicies(policiesCan, evaluation.policy_id));
    }
    if (method === "POST" && path === "/api/v2/zone-matrix/evaluate") {
      return reply(200, evaluation);
    }
    return reply(404, { error: "NOT_FOUND", reason: "no such route" });
  }));
}

async function openAndEvaluate(evaluation: ZoneMatrixEvaluationResponse, policiesCan: ZoneMatrixCan = evaluation.can) {
  setupEvaluateServer(evaluation, policiesCan);
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name: "Evaluate" }));
  fireEvent.click(await screen.findByRole("button", { name: "Evaluate policy" }));
  return screen.findByTestId("evaluation-headline");
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("EvaluateTab contract 7.2: evaluationProven and lower-bound counts (ZM-7, ZM-26)", () => {
  it("evaluationProven returns true only when every completeness, coverage_items, outcome_counts, and undecided check holds", () => {
    const clean = makeEval();
    expect(evaluationProven(clean)).toBe(true);

    expect(evaluationProven(makeEval({ evaluated: false }))).toBe(false);
    expect(evaluationProven(makeEval({ coverage: "INCOMPLETE" }))).toBe(false);
    expect(evaluationProven(makeEval({
      coverage_items: [{ code: "PAN_LOCAL_RULES_NOT_COLLECTED", description: "local rules not collected" }],
    }))).toBe(false);
    expect(evaluationProven(makeEval({ failure_count: 1 }))).toBe(false);
    expect(evaluationProven(makeEval({ outcome_counts: { VIOLATION: 0, COMPLIANT: 3, NOT_EVALUABLE: 1, UNKNOWN: 0 } }))).toBe(false);
    expect(evaluationProven(makeEval({ outcome_counts: { VIOLATION: 0, COMPLIANT: 3, NOT_EVALUABLE: 0, UNKNOWN: 1 } }))).toBe(false);
    expect(evaluationProven(makeEval({ outcome_counts: { COMPLIANT: 3, NOT_EVALUABLE: 0, UNKNOWN: 0 } }))).toBe(false);
    expect(evaluationProven(makeEval({ outcome_counts: { VIOLATION: 0, COMPLIANT: 3, UNKNOWN: 0 } }))).toBe(false);
    expect(evaluationProven(makeEval({ outcome_counts: { VIOLATION: 0, COMPLIANT: 3, NOT_EVALUABLE: 0 } }))).toBe(false);
    expect(evaluationProven(makeEval({ outcome_counts: {} }))).toBe(false);
    expect(evaluationProven(makeEval({ headline: { ...clean.headline, exact: false } }))).toBe(false);
    expect(evaluationProven(makeEval({ headline: { ...clean.headline, not_evaluable_rules: 1 } }))).toBe(false);
    expect(evaluationProven(makeEval({ headline: { ...clean.headline, unknown_rules: 1 } }))).toBe(false);
    expect(evaluationProven(makeEval({ headline: { ...clean.headline, incomplete_policies: 1 } }))).toBe(false);
    expect(evaluationProven(makeEval({ headline: { ...clean.headline, not_evaluated_policies: 1 } }))).toBe(false);
  });

  it("evaluationHeadlineText fails closed on bare 0 and unprefixed counts when unproven", () => {
    const clean = makeEval();
    expect(evaluationHeadlineText(clean, true)).toBe("0 rules violate the zone matrix (v3)");
    expect(evaluationHeadlineText(clean, false)).toBe("no proven violations in evaluated rules");

    const unprefixedPositive = makeEval({
      coverage: "INCOMPLETE",
      headline: {
        ...clean.headline,
        distinct_violating_rules: 2,
        exact: true,
        text: "2 rules violate the zone matrix (v3) across 1 zone pair",
      },
    });
    expect(evaluationHeadlineText(unprefixedPositive)).toBe("at least 2 rules violate the zone matrix (v3) across 1 zone pair");

    const alreadyPrefixed = makeEval({
      coverage: "INCOMPLETE",
      headline: {
        ...clean.headline,
        distinct_violating_rules: 2,
        exact: false,
        text: "at least 2 rules violate the zone matrix (v3) across 1 zone pair",
      },
    });
    expect(evaluationHeadlineText(alreadyPrefixed)).toBe("at least 2 rules violate the zone matrix (v3) across 1 zone pair");
  });

  it("never renders green or bare 0 when coverage is INCOMPLETE even if headline.exact is true", async () => {
    const incomplete = makeEval({
      coverage: "INCOMPLETE",
      headline: { ...makeEval().headline, exact: true, text: "0 rules violate the zone matrix (v3)" },
    });
    const headline = await openAndEvaluate(incomplete);
    expect(headline).toHaveAttribute("data-proven", "no");
    expect(headline.querySelector('[data-pill="ok"]')).toBeNull();
    expect(headline.querySelector('[data-pill="warn"]')).not.toBeNull();
    expect(headline).toHaveTextContent("no proven violations in evaluated rules");
    expect(headline.textContent).not.toMatch(/\b0 rules violate/i);
    expect(screen.getByTestId("evaluation-counts")).toHaveTextContent("Violations: at least 0");
    expect(screen.getByTestId("evaluation-counts").textContent).not.toMatch(/Violations:\s*0\b/);
    expect(screen.getByRole("combobox", { name: "Outcome filter" })).toBeInTheDocument();
  });

  it("never renders green or bare 0 when NOT_EVALUABLE > 0 or UNKNOWN > 0 on COMPLETE coverage", async () => {
    for (const counts of [
      { VIOLATION: 0, COMPLIANT: 2, NOT_EVALUABLE: 1, UNKNOWN: 0 },
      { VIOLATION: 0, COMPLIANT: 2, NOT_EVALUABLE: 0, UNKNOWN: 1 },
    ]) {
      const undecided = makeEval({
        coverage: "COMPLETE",
        outcome_counts: counts,
        headline: { ...makeEval().headline, exact: true, text: "0 rules violate the zone matrix (v3)" },
      });
      const headline = await openAndEvaluate(undecided);
      expect(headline).toHaveAttribute("data-proven", "no");
      expect(headline.querySelector('[data-pill="ok"]')).toBeNull();
      expect(headline).toHaveTextContent("no proven violations in evaluated rules");
      expect(headline.textContent).not.toMatch(/\b0 rules violate/i);
      expect(screen.getByTestId("evaluation-counts")).toHaveTextContent("Violations: at least 0");
      document.body.innerHTML = "";
      vi.unstubAllGlobals();
    }
  });

  it("fails closed when coverage_items is non-empty even if coverage is COMPLETE and headline.exact is true", async () => {
    const withCoverageNote = makeEval({
      coverage: "COMPLETE",
      coverage_items: [{ code: "PAN_LOCAL_RULES_NOT_COLLECTED", description: "local rules not collected" }],
      headline: { ...makeEval().headline, exact: true, text: "0 rules violate the zone matrix (v3)" },
    });
    const headline = await openAndEvaluate(withCoverageNote);
    expect(headline).toHaveAttribute("data-proven", "no");
    expect(headline.querySelector('[data-pill="ok"]')).toBeNull();
    expect(headline.querySelector('[data-pill="warn"]')).not.toBeNull();
    expect(headline).toHaveTextContent("no proven violations in evaluated rules");
    expect(headline.textContent).not.toMatch(/\b0 rules violate/i);
    expect(screen.getByTestId("evaluation-counts")).toHaveTextContent("Violations: at least 0");
    expect(screen.getByTestId("coverage-items")).toHaveTextContent("PAN_LOCAL_RULES_NOT_COLLECTED");
  });

  it("fails closed without coercing missing outcome_counts entries to 0", async () => {
    const missingCounts = makeEval({
      coverage: "COMPLETE",
      outcome_counts: { COMPLIANT: 3 },
      headline: { ...makeEval().headline, exact: true, text: "0 rules violate the zone matrix (v3)" },
    });
    const headline = await openAndEvaluate(missingCounts);
    expect(headline).toHaveAttribute("data-proven", "no");
    expect(headline.querySelector('[data-pill="ok"]')).toBeNull();
    expect(headline.querySelector('[data-pill="warn"]')).not.toBeNull();
    expect(headline).toHaveTextContent("no proven violations in evaluated rules");
    expect(headline.textContent).not.toMatch(/\b0 rules violate/i);
    const counts = screen.getByTestId("evaluation-counts");
    expect(counts).toHaveTextContent("Violations: UNKNOWN");
    expect(counts).toHaveTextContent("Not evaluable: UNKNOWN");
    expect(counts).toHaveTextContent("Outcome unknown: UNKNOWN");
    expect(counts.textContent).not.toMatch(/Violations:\s*0\b/);
  });

  it("prefixes unprefixed positive headline text with 'at least' when unproven", async () => {
    const clean = makeEval();
    const unprefixed = makeEval({
      coverage: "COMPLETE",
      coverage_items: [{ code: "PAN_LOCAL_RULES_NOT_COLLECTED", description: "local rules not collected" }],
      outcome_counts: { VIOLATION: 2, COMPLIANT: 1, NOT_EVALUABLE: 0, UNKNOWN: 0 },
      headline: {
        ...clean.headline,
        distinct_violating_rules: 2,
        exact: true,
        text: "2 rules violate the zone matrix (v3) across 1 zone pair",
      },
      rules: [makeViolatingRule(null)],
    });
    const headline = await openAndEvaluate(unprefixed);
    expect(headline).toHaveAttribute("data-proven", "no");
    expect(headline.querySelector('[data-pill="bad"]')).not.toBeNull();
    expect(headline.querySelector('[data-pill="ok"]')).toBeNull();
    expect(headline).toHaveTextContent("at least 2 rules violate the zone matrix (v3) across 1 zone pair");
    expect(screen.getByTestId("evaluation-counts")).toHaveTextContent("Violations: at least 2");
  });

  it("renders data-proven='yes', green ok pill, and exact Violations: 0 when COMPLETE with zero undecided or failures", async () => {
    const headline = await openAndEvaluate(makeEval());
    expect(headline).toHaveAttribute("data-proven", "yes");
    expect(headline.querySelector('[data-pill="ok"]')).not.toBeNull();
    expect(screen.getByTestId("evaluation-counts")).toHaveTextContent("Violations: 0");
    expect(screen.getByTestId("evaluation-counts").textContent).not.toContain("at least");
  });

  it("renders Violations: at least N when violations exist on incomplete coverage", async () => {
    const clean = makeEval();
    const withViolations = makeEval({
      coverage: "INCOMPLETE",
      outcome_counts: { VIOLATION: 2, COMPLIANT: 1, NOT_EVALUABLE: 1, UNKNOWN: 0 },
      headline: {
        ...clean.headline,
        distinct_violating_rules: 2,
        not_evaluable_rules: 1,
        incomplete_policies: 1,
        exact: false,
        text: "at least 2 rules violate the zone matrix (v3)",
      },
      rules: [makeViolatingRule(null)],
    });
    const headline = await openAndEvaluate(withViolations);
    expect(headline).toHaveAttribute("data-proven", "no");
    expect(headline.querySelector('[data-pill="bad"]')).not.toBeNull();
    expect(headline.querySelector('[data-pill="ok"]')).toBeNull();
    expect(screen.getByTestId("evaluation-counts")).toHaveTextContent("Violations: at least 2");
  });
});

describe("EvaluateTab contract 7.1: Open in Policy, fail-closed Open in Trace, and witness gating (ZM-30, ZM-33)", () => {
  it("evaluationWitnessTraceHref allows single IPv4 host over tcp/udp and fails closed on ranges, CIDRs, IPv6, port ranges, and non-TCP/UDP", () => {
    expect(
      evaluationWitnessTraceHref({ source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "tcp/443" }),
    ).toBe("?screen=trace&mode=path#tuple=192.0.2.10,192.0.2.25,tcp,443");
    expect(
      evaluationWitnessTraceHref({ source_range: "198.51.100.1", destination_range: "203.0.113.9", service: "udp/53" }),
    ).toBe("?screen=trace&mode=path#tuple=198.51.100.1,203.0.113.9,udp,53");

    const invalidCases: EvaluationWitness[] = [
      { source_range: "192.0.2.10-192.0.2.20", destination_range: "192.0.2.25", service: "tcp/443" },
      { source_range: "192.0.2.0/24", destination_range: "192.0.2.25", service: "tcp/443" },
      { source_range: "2001:db8::1", destination_range: "2001:db8::2", service: "tcp/443" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "tcp/8080-8090" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "icmp" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "any" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "UNKNOWN" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "TCP/443" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "tcp/443/extra" },
      { source_range: "192.0.2.010", destination_range: "192.0.2.25", service: "tcp/443" },
    ];
    for (const witness of invalidCases) {
      expect(evaluationWitnessTraceHref(witness)).toBeNull();
    }
  });

  it("renders encoded Open in Policy link and Open in Trace link with audit notice for single-host IPv4 witness when view_blocks is true", async () => {
    const clean = makeEval();
    const evaluation = makeEval({
      policy_id: "pol/pan#1",
      witnesses_included: true,
      outcome_counts: { VIOLATION: 1, COMPLIANT: 0, NOT_EVALUABLE: 0, UNKNOWN: 0 },
      headline: { ...clean.headline, distinct_violating_rules: 1, violated_zone_pairs: 1, critical_rules: 1, text: "1 rule violates the zone matrix (v3)" },
      rules: [
        makeViolatingRule(
          { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "tcp/443" },
          "rule:7/a",
        ),
      ],
    });
    await openAndEvaluate(evaluation);
    fireEvent.click(within(screen.getByRole("table", { name: "Evaluated policy rules" })).getByText("RULE-ECHO"));

    const detail = await screen.findByTestId("rule-finding-detail");
    const policyLink = within(detail).getByTestId("evaluate-open-policy-link");
    expect(policyLink).toHaveTextContent("Open in Policy");
    expect(policyLink.getAttribute("href")).toBe(
      `?screen=policy&policy_id=${encodeURIComponent("pol/pan#1")}&rule=${encodeURIComponent("rule:7/a")}&standard=1`,
    );

    const notice = within(detail).getByTestId("evaluate-witness-audit-notice");
    expect(notice).toHaveTextContent("This view is recorded in the audit log");

    const traceLink = within(detail).getByTestId("evaluate-witness-trace-link");
    expect(traceLink).toHaveTextContent("Open in Trace");
    expect(traceLink.getAttribute("href")).toBe("?screen=trace&mode=path#tuple=192.0.2.10,192.0.2.25,tcp,443");
    expect(traceLink.getAttribute("href")!.split("#")[0]).not.toContain("192.0.2");
  });

  it("shows audit notice and monospace witness text without Trace link for CIDR, range, ICMP, or port-range witnesses", async () => {
    const clean = makeEval();
    const cases: EvaluationWitness[] = [
      { source_range: "192.0.2.0/24", destination_range: "192.0.2.25", service: "tcp/443" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "tcp/8080-8090" },
      { source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "icmp" },
    ];
    for (const witness of cases) {
      const evaluation = makeEval({
        witnesses_included: true,
        outcome_counts: { VIOLATION: 1, COMPLIANT: 0, NOT_EVALUABLE: 0, UNKNOWN: 0 },
        headline: { ...clean.headline, distinct_violating_rules: 1, violated_zone_pairs: 1, critical_rules: 1, text: "1 rule violates the zone matrix (v3)" },
        rules: [makeViolatingRule(witness)],
      });
      await openAndEvaluate(evaluation);
      fireEvent.click(within(screen.getByRole("table", { name: "Evaluated policy rules" })).getByText("RULE-ECHO"));

      const detail = await screen.findByTestId("rule-finding-detail");
      expect(within(detail).getByTestId("evaluate-witness-audit-notice")).toHaveTextContent("This view is recorded in the audit log");
      expect(detail).toHaveTextContent(witness.source_range);
      expect(detail).toHaveTextContent(witness.service);
      expect(within(detail).queryByTestId("evaluate-witness-trace-link")).toBeNull();
      document.body.innerHTML = "";
      vi.unstubAllGlobals();
    }
  });

  it("never renders witness checkbox, audit notice, or Trace link when view_blocks is false (masked/aiview or no blocks right)", async () => {
    const clean = makeEval();
    const evaluation = makeEval({
      can: NO_BLOCKS_CAN,
      witnesses_included: true,
      outcome_counts: { VIOLATION: 1, COMPLIANT: 0, NOT_EVALUABLE: 0, UNKNOWN: 0 },
      headline: { ...clean.headline, distinct_violating_rules: 1, violated_zone_pairs: 1, critical_rules: 1, text: "1 rule violates the zone matrix (v3)" },
      rules: [makeViolatingRule({ source_range: "192.0.2.10", destination_range: "192.0.2.25", service: "tcp/443" })],
    });
    await openAndEvaluate(evaluation, NO_BLOCKS_CAN);
    expect(screen.queryByLabelText(/Include witness addresses/)).toBeNull();

    fireEvent.click(within(screen.getByRole("table", { name: "Evaluated policy rules" })).getByText("RULE-ECHO"));
    const detail = await screen.findByTestId("rule-finding-detail");
    expect(within(detail).getByTestId("evaluate-open-policy-link")).toBeInTheDocument();
    expect(within(detail).queryByTestId("evaluate-witness-audit-notice")).toBeNull();
    expect(within(detail).queryByTestId("evaluate-witness-trace-link")).toBeNull();
    expect(detail.textContent).not.toContain("192.0.2.10");
  });
});
