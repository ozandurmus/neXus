import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type {
  ZoneMatrixCan, ZoneMatrixEvaluationResponse, ZoneMatrixPoliciesResponse, ZoneVersionDetail, ZoneVersionSummary,
} from "../src/screens/zonematrix/zoneMatrixApi";

const ACTIVE_SUMMARY: ZoneVersionSummary = {
  version_id: "v-active",
  number: 3,
  state: "ACTIVE",
  revision: 4,
  content_sha256: "a".repeat(64),
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
      name: "Z-USER",
      description: null,
      role: "USER",
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
  unassigned_addresses: { IPV4: "4294966784", IPV6: "340282366920938463463374607431768211456" },
  redundant_blocks: 0,
  revisions: [],
};

const POLICIES_FIXTURE: Omit<ZoneMatrixPoliciesResponse, "can"> = {
  policies: [
    {
      id: "pol-pan-1",
      name: "PAN-Core-Segmentation",
      vendor: "PAN",
      source_id: "src-1",
      source_name: "FW-TANGO-04",
      container_id: "dg-1",
      container_name: "DOM-BRAVO-02",
      collected_at: "2026-11-15T08:00:00Z",
      rule_count: 142,
      target_count: 2,
    },
  ],
  not_evaluated: [
    {
      id: "device:asa-1",
      name: "FW-JULIET-06",
      vendor: "ASA",
      source_id: "asa-1",
      source_name: "FW-JULIET-06",
      reason: "not evaluated: policy not collected",
    },
  ],
};

function makeEvaluation(withWitnesses: boolean, zeroProvenWithUndecided = false): Omit<ZoneMatrixEvaluationResponse, "can"> {
  if (zeroProvenWithUndecided) {
    return {
      engine_version: "step3-v1",
      version_id: "v-active",
      version_number: 3,
      content_sha256: "a".repeat(64),
      policy_id: "pol-pan-1",
      policy_name: "PAN-Core-Segmentation",
      source_id: "src-1",
      source_name: "FW-TANGO-04",
      container_id: "dg-1",
      container_name: "DOM-BRAVO-02",
      vendor: "PAN",
      collected_at: "2026-11-15T08:00:00Z",
      witnesses_included: false,
      targets: [],
      evaluated: true,
      not_evaluated_reason: null,
      coverage: "INCOMPLETE",
      coverage_items: [
        { code: "PAN_LOCAL_RULES_NOT_COLLECTED", description: "local rules not collected" },
      ],
      failure_count: 0,
      outcome_counts: { VIOLATION: 0, COMPLIANT: 1, NOT_EVALUABLE: 2, UNKNOWN: 0 },
      not_applicable: { disabled: 1, deny_type: 3, outside_security_rulebase: 1, apply_layer_decided_by_inline: 0, schedule_ended: 0, total: 5, ended_schedules: [] },
      headline: {
        distinct_violating_rules: 0,
        violated_zone_pairs: 0,
        critical_rules: 0,
        covered_by_earlier_rule: 0,
        conditional_rules: 0,
        outside_headline_ordered_layer: 0,
        not_evaluable_rules: 2,
        unknown_rules: 0,
        incomplete_policies: 1,
        not_evaluated_policies: 0,
        exact: false,
        text: "no proven violations in evaluated rules",
        outside_headline_text: null,
      },
      rules: [
        {
          finding_identity: { policy_id: "pol-pan-1", rule_uid: "uid-r2", inline_parent_path: [], violated_pairs: [] },
          rule_id: "r2",
          rule_uid: "uid-r2",
          rule_number: 2,
          rule_name: "DualStackRule",
          section_id: "s1",
          section_name: "Pre rules",
          inline_parent_path: [],
          target_device_ids: [],
          outcome: "NOT_EVALUABLE",
          reason: "IPv6 not evaluated",
          highest_severity: null,
          flags: [],
          covering_rule_id: null,
          touched_pairs: [],
          touched_pair_names: [],
          violated_cells: [],
          undecided_parts: [],
          nat_view: null,
        },
      ],
      zone_definition_gaps: [],
      hygiene_budget_reached: false,
      duration_ms: 14,
      hygiene_ms: 5,
    };
  }

  return {
    engine_version: "step3-v1",
    version_id: "v-active",
    version_number: 3,
    content_sha256: "a".repeat(64),
    policy_id: "pol-pan-1",
    policy_name: "PAN-Core-Segmentation",
    source_id: "src-1",
    source_name: "FW-TANGO-04",
    container_id: "dg-1",
    container_name: "DOM-BRAVO-02",
    vendor: "PAN",
    collected_at: "2026-11-15T08:00:00Z",
    witnesses_included: withWitnesses,
    targets: [{ device_id: "gw-1", name: "FW-BRAVO-02", context: "vsys1", sync_status: "IN_SYNC" }],
    evaluated: true,
    not_evaluated_reason: null,
    coverage: "INCOMPLETE",
    coverage_items: [
      { code: "PAN_LOCAL_RULES_NOT_COLLECTED", description: "local rules not collected" },
    ],
    failure_count: 0,
    outcome_counts: { VIOLATION: 1, COMPLIANT: 4, NOT_EVALUABLE: 1, UNKNOWN: 0 },
    not_applicable: { disabled: 2, deny_type: 5, outside_security_rulebase: 2, apply_layer_decided_by_inline: 0, schedule_ended: 1, total: 10, ended_schedules: [] },
    headline: {
      distinct_violating_rules: 1,
      violated_zone_pairs: 1,
      critical_rules: 1,
      covered_by_earlier_rule: 1,
      conditional_rules: 0,
      outside_headline_ordered_layer: 0,
      not_evaluable_rules: 1,
      unknown_rules: 0,
      incomplete_policies: 1,
      not_evaluated_policies: 0,
      exact: false,
      text: "at least 1 rule violates the zone matrix (v3) across 1 zone pair; 1 critical, of which 1 covered by an earlier rule and 0 conditional (identity / time)",
      outside_headline_text: null,
    },
    rules: [
      {
        finding_identity: { policy_id: "pol-pan-1", rule_uid: "uid-r1", inline_parent_path: [], violated_pairs: ["z1->z2"] },
        rule_id: "r1",
        rule_uid: "uid-r1",
        rule_number: 7,
        rule_name: "Allow-User-To-Database",
        section_id: "s1",
        section_name: "Pre rules",
        inline_parent_path: [],
        target_device_ids: ["gw-1"],
        outcome: "VIOLATION",
        reason: null,
        highest_severity: "CRITICAL",
        flags: ["COVERED_BY_EARLIER_DENY", "NAT_MAY_APPLY"],
        covering_rule_id: "r-deny-early",
        touched_pairs: ["z1->z2"],
        touched_pair_names: ["Z-USER -> Z-DB"],
        violated_cells: [
          {
            source: "z1",
            destination: "z2",
            source_name: "Z-USER",
            destination_name: "Z-DB",
            statement: "DENY_ALL",
            severity: "CRITICAL",
            not_allowed_services: ["tcp/5432"],
            nat_view: "NAT:nat-1",
            witness: withWitnesses
              ? { source_range: "192.0.2.10-192.0.2.20", destination_range: "203.0.113.50", service: "tcp/5432" }
              : null,
          },
        ],
        undecided_parts: [],
        nat_view: "TRANSLATED",
      },
    ],
    zone_definition_gaps: [
      {
        rule_id: "r1",
        rule_number: 7,
        rule_name: "Allow-User-To-Database",
        section_name: "Pre rules",
        range_count: 1,
        unassigned_private_ranges: withWitnesses ? ["198.51.100.0-198.51.100.255"] : null,
      },
    ],
    hygiene_budget_reached: false,
    duration_ms: 19,
    hygiene_ms: 6,
  };
}

interface Sent {
  readonly method: string;
  readonly path: string;
  readonly body?: unknown;
}

function setupServer(can: ZoneMatrixCan, zeroProven = false): Sent[] {
  const sent: Sent[] = [];
  const reply = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const [path] = url.split("?");
    const parsedBody = init?.body ? JSON.parse(String(init.body)) : undefined;
    sent.push({ method, path: url, body: parsedBody });

    if (method === "GET" && path === "/api/v2/zone-matrix/draft") {
      return reply(200, { active: ACTIVE_SUMMARY, draft: null, diff: null, publish_check: null, can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions/v-active") {
      return reply(200, { ...ACTIVE_DETAIL, can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions") {
      return reply(200, { versions: [ACTIVE_SUMMARY], can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/policies") {
      return reply(200, { ...POLICIES_FIXTURE, can });
    }
    if (method === "POST" && path === "/api/v2/zone-matrix/evaluate") {
      const wantW = Boolean((parsedBody as { include_witnesses?: boolean } | undefined)?.include_witnesses);
      return reply(200, { ...makeEvaluation(wantW, zeroProven), can });
    }
    return reply(404, { error: "NOT_FOUND", reason: "no such route" });
  }));
  return sent;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("Zone matrix step 3: Evaluate tab", () => {
  it("loads policies only when Evaluate opens, runs evaluation, gates witnesses on view_blocks, and shows non-evaluated ASA", async () => {
    const can: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true, evaluate: true };
    const sent = setupServer(can);
    const { container } = render(<ZoneMatrixScreen />);

    await screen.findByRole("tab", { name: "Evaluate" });
    expect(sent.some((s) => s.path.includes("/policies"))).toBe(false);

    fireEvent.click(screen.getByRole("tab", { name: "Evaluate" }));
    expect(await screen.findByTestId("not-evaluated-sources")).toHaveTextContent("FW-JULIET-06");
    expect(screen.getByTestId("not-evaluated-sources")).toHaveTextContent("not evaluated: policy not collected");

    // First run without witnesses
    fireEvent.click(await screen.findByRole("button", { name: "Evaluate policy" }));
    const headline = await screen.findByTestId("evaluation-headline");
    expect(headline).toHaveTextContent("at least 1 rule violates the zone matrix (v3) across 1 zone pair");
    expect(headline).toHaveTextContent("PAN_LOCAL_RULES_NOT_COLLECTED");
    expect(sent.find((s) => s.method === "POST" && s.path === "/api/v2/zone-matrix/evaluate")?.body).toEqual({
      policy_id: "pol-pan-1",
      version_id: "v-active",
      include_witnesses: false,
    });

    // Click finding row -> detail shown without witness IP range
    const table = screen.getByRole("table", { name: "Evaluated policy rules" });
    fireEvent.click(within(table).getByText("Allow-User-To-Database"));
    const detail = await screen.findByTestId("rule-finding-detail");
    expect(detail).toHaveTextContent("Z-USER -> Z-DB (NAT:nat-1)");
    expect(detail).toHaveTextContent("tcp/5432");
    expect(detail).not.toHaveTextContent("192.0.2.10-192.0.2.20");
    expect(screen.getByTestId("zone-definition-gaps")).toHaveTextContent("Withheld unless witness view is requested");

    // Request witnesses explicitly (audited)
    fireEvent.click(screen.getByLabelText(/Include witness addresses/));
    fireEvent.click(screen.getByRole("button", { name: "Evaluate policy" }));
    await screen.findByTestId("evaluation-headline");
    fireEvent.click(within(screen.getByRole("table", { name: "Evaluated policy rules" })).getByText("Allow-User-To-Database"));
    expect(await screen.findByTestId("rule-finding-detail")).toHaveTextContent("192.0.2.10-192.0.2.20 -> 203.0.113.50");
    expect(screen.getByTestId("zone-definition-gaps")).toHaveTextContent("198.51.100.0-198.51.100.255");

    // Forbidden marketing/compliance words never appear
    expect(container.textContent ?? "").not.toMatch(/bddk|\bpass(ed)?\b|score|%|green/i);
  });

  it("hides evaluation button and witness checkbox for read-only viewer and never shows 0 on incomplete coverage", async () => {
    const viewerCan: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, evaluate: false };
    setupServer(viewerCan);
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Evaluate" }));
    await screen.findByTestId("not-evaluated-sources");
    expect(screen.queryByRole("button", { name: "Evaluate policy" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Include witness addresses/)).not.toBeInTheDocument();
  });

  it("renders non-exact zero-proven-violations headline without displaying 0 rules allow", async () => {
    const evalNoBlocks: ZoneMatrixCan = { view_blocks: false, edit: true, publish: true, evaluate: true };
    setupServer(evalNoBlocks, true);
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Evaluate" }));
    expect(screen.queryByLabelText(/Include witness addresses/)).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: "Evaluate policy" }));
    const headline = await screen.findByTestId("evaluation-headline");
    expect(headline).toHaveAttribute("data-proven", "no");
    expect(headline.querySelector('[data-pill="ok"]')).toBeNull();
    expect(headline).toHaveTextContent("no proven violations in evaluated rules");
    expect(headline.textContent ?? "").not.toContain("0 rules violate");
    expect(screen.getByTestId("evaluation-counts")).toHaveTextContent("Violations: at least 0");
  });
});
