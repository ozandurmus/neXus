import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { OverviewView } from "../src/auth/adminApi";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { OverviewScreen } from "../src/screens/OverviewScreen";
import { buildAttention, type AttentionInputs, type SourceRead } from "../src/screens/overview/attentionRules";
import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type {
  SummaryFinding, SummaryHeadline, SummaryPolicy, ZoneMatrixCan, ZoneMatrixEvaluationResponse, ZoneMatrixSummaryResponse,
  ZoneMatrixView, ZoneVersionDetail,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * ZM-30, the surface-wide half (docs/design/ZONE_MATRIX_CONTRACT.md 10.2, 6.1, 7.2): no surface shows "BDDK compliant",
 * "BDDK pass" or a BDDK score. (a) A source scan of every zone matrix screen file and the Overview row; (b) every tab and
 * the Overview row rendered in the states "all unknown", "stale", "incomplete" and "open violations".
 *
 * Already pinned and cited, not copied: the Matrix tab's own wording pin is `ZoneMatrixStepTwo.test.tsx`
 * ("never renders compliant, BDDK, pass, score, percent or green in the Matrix tab"); the Evaluate tab's
 * `ZoneMatrixStepThree.test.tsx` ("Forbidden marketing/compliance words never appear"). The service side (stored summary,
 * report title, header, CSV and the Java sources) is `ZoneMatrixBddkWordingPinTest`. Synthetic values only.
 */

/**
 * The forbidden set; the Java pin carries the same definition. BDDK followed in the same sentence by up to three words and
 * then compliant, compliance, pass, passed, score, uyumlu, uygun, gecti (Turkish letters), puan or a percent figure;
 * a bare "BDDK compliant" is the zero-word case.
 */
const FORBIDDEN = new RegExp(
  "(?<![\\p{L}\\p{N}])bddk(?![\\p{L}\\p{N}])(?:[^\\p{L}\\p{N}.!?;\\n]*[\\p{L}\\p{N}_]+){0,3}?[^\\p{L}\\p{N}.!?;\\n]*"
  + "(?:(?<![\\p{L}\\p{N}])(?:complian(?:t|ce)|pass(?:ed)?(?![\\p{L}])|score(?![\\p{L}])|uyumlu|uygun|ge\\u00e7t[i\\u0130\\u0131]|puan)"
  + "|\\d[\\d.,]*\\s*%|%\\s*\\d)",
  "iu",
);
const forbidden = (text: string) => FORBIDDEN.test(text);

describe("the forbidden set", () => {
  it("catches planted phrases and keeps the contract's own wording allowed", () => {
    for (const bad of ["BDDK compliant", "bddk COMPLIANT", "This is BDDK-compliant.", "BDDK pass", "BDDK passed", "BDDK score: 3", "the BDDK score",
      "BDDK 95%", "BDDK %95", "BDDK uyumlu", "BDDK'ya uygun", "BDDK geçti", "BDDK GEÇTİ", "BDDK puanı", "BDDK compliance status",
      "Overall BDDK result is a pass", "BDDK regulation shows 80 %"]) {
      expect(forbidden(bad), bad).toBe(true);
    }
    for (const ok of ["Segmentation policy evidence report - BDDK regulation: Planted regulation, 2020-03-15",
      "Overwatch proposal based on general segmentation practice, not on a BDDK clause", "1 of 2 cells confirmed by compliance",
      "BDDK-planted-article-12", "BDDK regulation. The cell is compliant.", "bddkcompliant", "BDDK 95"]) {
      expect(forbidden(ok), ok).toBe(false);
    }
  });
});

// ---------------------------------------------------------------------------------------------------- (a) source scan

/** Every zone matrix screen file, the Overview row's two modules and the navigation entry (path -> source text). */
const SURFACES: Record<string, string> = {
  ...(import.meta.glob("../src/screens/zonematrix/*.{ts,tsx}", { eager: true, query: "?raw", import: "default" }) as Record<string, string>),
  ...(import.meta.glob("../src/screens/overview/{attentionRules,zoneMatrixAttention}.ts", { eager: true, query: "?raw", import: "default" }) as Record<string, string>),
  ...(import.meta.glob("../src/shell/{navModel,tabCoverage}.ts", { eager: true, query: "?raw", import: "default" }) as Record<string, string>),
};

/** Source text without comments (a comment is not shown to anyone); string literals and JSX text stay. */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:"'`\\])\/\/[^\n]*/g, "$1");
}

describe("source scan of the zone matrix surfaces", () => {
  it("no screen file, Overview row rule or navigation entry contains a forbidden phrase", () => {
    const files = Object.keys(SURFACES);
    for (const must of ["MatrixTab.tsx", "ViolationsTab.tsx", "ZonesTab.tsx", "VersionsTab.tsx", "EvaluateTab.tsx", "CoverageTab.tsx",
      "zoneMatrixUi.tsx", "zoneMatrixSummaryUi.tsx", "attentionRules.ts", "zoneMatrixAttention.ts"]) {
      expect(files.some((f) => f.endsWith(`/${must}`)), `scan misses ${must}`).toBe(true);
    }
    expect(files.length).toBeGreaterThanOrEqual(14);
    const hits = files.filter((f) => forbidden(withoutComments(SURFACES[f])));
    expect(hits).toEqual([]);
  });

  it("the comment stripper keeps a planted string literal and JSX text in view", () => {
    expect(forbidden(withoutComments('const a = "BDDK compliant"; // fine'))).toBe(true);
    expect(forbidden(withoutComments("<p>BDDK score</p>"))).toBe(true);
    expect(forbidden(withoutComments("// BDDK compliant\n/* BDDK pass */ const b = 1;"))).toBe(false);
  });
});

// ---------------------------------------------------------------------------------------------------- (b) rendered surfaces

const HASH = "a".repeat(64);
const INTERIM = "interim value set by the Product Owner (2026-11-23) until the bank answers; not the bank's value";
const CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true, evaluate: true };

const matrix: ZoneMatrixView = {
  default_statement: "ALLOW_LISTED", default_severity: "MEDIUM", unassigned_to_unassigned: "DENY_ALL",
  template_origin: { name: "Overwatch segmentation template (proposal)", revision: "2026-11-13.2" },
  service_sets: [],
  cells: [
    { source: "z1", destination: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO", statement: "DENY_ALL", services: [], severity: "HIGH",
      rationale: "Synthetic rationale", regulation_reference: "SEC-POL-4.2", reference_status: "PROPOSED", confirmed_by: null, confirmed_at: null, cell_sha256: "b".repeat(64) },
    { source: "z2", destination: "z1", source_name: "ZONE-BRAVO", destination_name: "ZONE-ALFA", statement: "DENY_ALL", services: [], severity: "LOW",
      rationale: null, regulation_reference: "SEC-POL-1.1", reference_status: "CONFIRMED", confirmed_by: "USER-1", confirmed_at: "2026-11-14T08:00:00Z", cell_sha256: "c".repeat(64) },
  ],
  cells_total: 2, cells_with_reference: 2, cells_confirmed: 1,
};
const activeSummary = { version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH, created_at: "2026-11-10T08:00:00Z",
  updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z" };
const activeDetail: ZoneVersionDetail = {
  ...activeSummary, zones: [
    { zone_id: "z1", name: "ZONE-ALFA", description: null, role: "DATABASE", block_count: 1, blocks: null, effective_addresses: { IPV4: "256", IPV6: "0" } },
    { zone_id: "z2", name: "ZONE-BRAVO", description: null, role: "USER", block_count: 1, blocks: null, effective_addresses: { IPV4: "256", IPV6: "0" } },
  ],
  unassigned_addresses: { IPV4: "4294966784", IPV6: "0" }, redundant_blocks: 0, revisions: [], matrix,
} as ZoneVersionDetail;

const run = (over: Record<string, unknown> = {}) => ({
  run_id: "run-1", state: "COMPLETE", failure_reason: null, version_id: "v-active", version_number: 3, content_sha256: HASH,
  started_at: "2026-11-25T07:00:00Z", finished_at: "2026-11-25T07:05:00Z", snapshot_collected_at: "2026-11-25T06:00:00Z", coverage: "COMPLETE", counts: null, ...over,
});
const policy = (over: Partial<SummaryPolicy> = {}): SummaryPolicy => ({
  policy_id: "pol-1", policy_name: "POLICY-ALFA", vendor: "check_point", source_id: "src-1", source_name: "FW-TANGO-04", container_id: null, container_name: null,
  collected_at: "2026-11-25T06:00:00Z", rule_count: 12, staleness: { verdict: "CURRENT", reasons: [], snapshot_age_seconds: 5400, snapshot_age_known: true },
  coverage: "COMPLETE", coverage_items: [], collection_failures: 0, has_complete_run: true,
  latest_complete_run: run() as SummaryPolicy["latest_complete_run"], latest_run: run() as SummaryPolicy["latest_run"], ...over,
});
const finding = (over: Partial<SummaryFinding> = {}): SummaryFinding => ({
  policy_source_id: "pol-1", policy_name: "POLICY-ALFA", rule_uid: "uid-7", inline_parent_path: [], rule_id: "rule-7", rule_number: 7, rule_name: "RULE-ECHO",
  target_device_ids: [], outcome: "VIOLATION", reason: null, highest_severity: "HIGH", flags: [], covering_rule_id: null, undecided_parts: [],
  violated_pairs: [{ source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO", statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [] }],
  first_observed_at: "2026-11-20T08:00:00Z", first_observed_any_version: "2026-11-18T08:00:00Z", last_observed_at: "2026-11-25T07:05:00Z",
  run_id: "run-1", version_id: "v-active", version_number: 3, content_sha256: HASH, on_active_version: true, policy_staleness: "CURRENT", snapshot_age_seconds: 5400, ...over,
} as SummaryFinding);
const headline = (over: Partial<SummaryHeadline> = {}): SummaryHeadline => ({
  version_id: "v-active", version_number: 3, distinct_violating_rules: 0, violated_zone_pairs: 0, critical_rules: 0, covered_by_earlier_rule: 0, conditional_rules: 0,
  outside_headline_ordered_layer: 0, not_evaluable_findings: 0, unknown_findings: 0, became_undecided_findings: 0, stale_policies: 0, incomplete_policies: 0,
  older_version_policies: 0, not_evaluated_policies: 0, resolved_by_version_change_last_7_days: 0, exact: false, count_text: "no proven violation",
  text: "no proven violations in evaluated rules", outside_headline_text: null,
  staleness_headline: { count: 0, exact: true, text: "All evaluated policies are current" }, ...over,
});
const summary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  evaluated_at: "2026-11-25T08:00:00Z", state: "READY", state_text: "findings of the latest complete run of each policy", masked: true, policy_names_masked: false,
  witnesses_included: false, staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: INTERIM },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" }, headline: headline(),
  headline_absent_reason: null, policies: [policy()], not_evaluated: [{ id: "device:asa-1", name: "FW-JULIET-06", vendor: "ASA", source_id: "asa-1",
    source_name: "FW-JULIET-06", reason: "not evaluated: policy not collected" }] as ZoneMatrixSummaryResponse["not_evaluated"],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 0, total_findings: 0, returned: 0, more: 0, capped: false, findings: [],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] }, ...over,
} as ZoneMatrixSummaryResponse);

interface State { readonly name: string; readonly body: ZoneMatrixSummaryResponse; readonly evaluation: "violating" | "undecided" }
const STATES: readonly State[] = [
  { name: "all unknown", evaluation: "undecided", body: summary({ state: "NO_COMPLETE_RUN", state_text: "no policy has a complete evaluation run yet",
    policies: [policy({ coverage: "UNKNOWN", has_complete_run: false, collection_failures: null, latest_complete_run: null, latest_run: null, collected_at: null,
      staleness: { verdict: "STALE", reasons: ["SNAPSHOT_TIME_UNKNOWN"], snapshot_age_seconds: null, snapshot_age_known: false } })],
    headline: headline({ not_evaluated_policies: 1, unknown_findings: 2 }), unfiltered_findings: 2, total_findings: 2, returned: 2,
    findings: [finding({ outcome: "UNKNOWN", reason: "zone of a destination is not decidable", violated_pairs: [], highest_severity: null }),
      finding({ rule_uid: "uid-8", rule_id: "rule-8", rule_number: 8, rule_name: "RULE-FOXTROT", outcome: "NOT_EVALUABLE", reason: "service not evaluable", violated_pairs: [], highest_severity: null })] }) },
  { name: "stale", evaluation: "undecided", body: summary({
    policies: [policy({ staleness: { verdict: "STALE", reasons: ["SNAPSHOT_OLDER_THAN_THRESHOLD"], snapshot_age_seconds: 200000, snapshot_age_known: true } })],
    headline: headline({ stale_policies: 1, staleness_headline: { count: 1, exact: false, text: "At least 1 policy is stale" } }) }) },
  { name: "incomplete", evaluation: "undecided", body: summary({
    policies: [policy({ coverage: "INCOMPLETE", coverage_items: ["Inline layers not collected"], collection_failures: 1 })],
    headline: headline({ incomplete_policies: 1, not_evaluable_findings: 1 }), unfiltered_findings: 1, total_findings: 1, returned: 1,
    findings: [finding({ outcome: "NOT_EVALUABLE", reason: "service not evaluable", violated_pairs: [], highest_severity: null, flags: ["BECAME_UNDECIDED"] })] }) },
  { name: "open violations", evaluation: "violating", body: summary({
    headline: headline({ exact: true, distinct_violating_rules: 1, violated_zone_pairs: 1, critical_rules: 1, count_text: "1 violating rule in 1 policy",
      text: "1 rule violates the zone matrix (v3) across 1 zone pair; 1 critical, of which 0 covered by an earlier rule and 0 conditional (identity / time)" }),
    unfiltered_findings: 1, total_findings: 1, returned: 1, findings: [finding({ highest_severity: "CRITICAL" })] }) },
];

function evaluation(kind: State["evaluation"]): Omit<ZoneMatrixEvaluationResponse, "can"> {
  const violating = kind === "violating";
  return {
    engine_version: "step3-v1", version_id: "v-active", version_number: 3, content_sha256: HASH, policy_id: "pol-1", policy_name: "POLICY-ALFA",
    source_id: "src-1", source_name: "FW-TANGO-04", container_id: null, container_name: null, vendor: "PAN", collected_at: "2026-11-15T08:00:00Z",
    witnesses_included: false, targets: [], evaluated: true, not_evaluated_reason: null, coverage: "INCOMPLETE",
    coverage_items: [{ code: "PAN_LOCAL_RULES_NOT_COLLECTED", description: "local rules not collected" }], failure_count: 0,
    outcome_counts: { VIOLATION: violating ? 1 : 0, COMPLIANT: 1, NOT_EVALUABLE: 1, UNKNOWN: 1 },
    not_applicable: { disabled: 1, deny_type: 1, outside_security_rulebase: 0, apply_layer_decided_by_inline: 0, schedule_ended: 0, total: 2, ended_schedules: [] },
    headline: { distinct_violating_rules: violating ? 1 : 0, violated_zone_pairs: violating ? 1 : 0, critical_rules: 0, covered_by_earlier_rule: 0, conditional_rules: 0,
      outside_headline_ordered_layer: 0, not_evaluable_rules: 1, unknown_rules: 1, incomplete_policies: 1, not_evaluated_policies: 0, exact: false,
      text: violating ? "at least 1 rule violates the zone matrix (v3) across 1 zone pair" : "no proven violations in evaluated rules", outside_headline_text: null },
    rules: [{
      finding_identity: { policy_id: "pol-1", rule_uid: "uid-r2", inline_parent_path: [], violated_pairs: [] }, rule_id: "r2", rule_uid: "uid-r2", rule_number: 2,
      rule_name: "DualStackRule", section_id: "s1", section_name: "Pre rules", inline_parent_path: [], target_device_ids: [], outcome: "NOT_EVALUABLE",
      reason: "IPv6 not evaluated", highest_severity: null, flags: [], covering_rule_id: null, touched_pairs: [], touched_pair_names: [], violated_cells: [],
      undecided_parts: [], nat_view: null,
    }],
    zone_definition_gaps: [], hygiene_budget_reached: false, duration_ms: 14, hygiene_ms: 5,
  } as unknown as Omit<ZoneMatrixEvaluationResponse, "can">;
}

function serve(state: State): void {
  const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const p = url.split("?")[0];
    const method = init?.method ?? "GET";
    if (p === "/api/v2/zone-matrix/summary") return reply(200, state.body);
    if (p === "/api/v2/zone-matrix/draft") return reply(200, { active: activeSummary, draft: null, diff: null, publish_check: null, can: CAN });
    if (p === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeDetail, can: CAN });
    if (p === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeSummary], can: CAN });
    if (p === "/api/v2/zone-matrix/policies") {
      return reply(200, { policies: [{ id: "pol-1", name: "POLICY-ALFA", vendor: "PAN", source_id: "src-1", source_name: "FW-TANGO-04", container_id: null,
        container_name: null, collected_at: "2026-11-15T08:00:00Z", rule_count: 12, target_count: 1 }],
      not_evaluated: [{ id: "device:asa-1", name: "FW-JULIET-06", vendor: "ASA", source_id: "asa-1", source_name: "FW-JULIET-06", reason: "not evaluated: policy not collected" }], can: CAN });
    }
    if (method === "POST" && p === "/api/v2/zone-matrix/evaluate") return reply(200, { ...evaluation(state.evaluation), can: CAN });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

/** The sanctioned negative sentences that name the word "compliant" to deny it; nothing else may. */
const NEGATIVE_SENTENCES = [/never counted as compliant/gi];

function assertNoClaim(label: string, container: HTMLElement): void {
  const text = container.textContent ?? "";
  expect(forbidden(text), `${label}: text matches the forbidden set`).toBe(false);
  expect(/bddk/i.test(text), `${label}: the word BDDK is shown on a screen (the reference text is withheld or entered by the bank only)`).toBe(false);
  expect(/\bscore\b|\bpass(ed)?\b|\d\s*%/i.test(text), `${label}: a score, pass or percent`).toBe(false);
  const withoutNegatives = NEGATIVE_SENTENCES.reduce((t, re) => t.replace(re, ""), text);
  expect(/\bcompliant\b/i.test(withoutNegatives), `${label}: a "compliant" state is shown`).toBe(false);
  expect(container.querySelector('[data-proven="yes"]'), `${label}: a proven-clean marker`).toBeNull();
  for (const el of Array.from(container.querySelectorAll<HTMLElement>("[class]"))) {
    if (/(colorSuccess|standardSuccess|filledSuccess|outlinedSuccess)/.test(el.className.toString())) {
      expect(/complian|\bpass|bddk|score|%/i.test(el.textContent ?? ""), `${label}: a green element reads "${el.textContent}"`).toBe(false);
    }
  }
}

async function visit(tab: string, marker: () => Promise<unknown>): Promise<void> {
  fireEvent.click(await screen.findByRole("tab", { name: tab }));
  await marker();
}

describe.each(STATES)("every tab in the state '$name'", (state) => {
  it("shows no BDDK compliance, pass, score or percent and no compliant state on the Matrix, Violations, Zones, Versions, Evaluate and Coverage tabs", async () => {
    serve(state);
    const { container } = render(<ZoneMatrixScreen />);
    await screen.findByTestId("matrix-header");
    assertNoClaim(`${state.name} / Matrix`, container);

    await visit("Violations", () => screen.findByRole("table", { name: "Violation findings" }).catch(() => screen.findByTestId("summary-headline")));
    await screen.findByTestId("summary-headline");
    assertNoClaim(`${state.name} / Violations`, container);

    await visit("Zones", () => screen.findByRole("table", { name: "Zones" }));
    assertNoClaim(`${state.name} / Zones`, container);

    await visit("Versions", () => waitFor(() => expect(container.textContent).toMatch(/Version 3|v3|3/)));
    assertNoClaim(`${state.name} / Versions`, container);

    await visit("Evaluate", () => screen.findByTestId("not-evaluated-sources"));
    fireEvent.click(await screen.findByRole("button", { name: "Evaluate policy" }));
    await screen.findByTestId("evaluation-headline");
    assertNoClaim(`${state.name} / Evaluate`, container);

    await visit("Coverage", () => screen.findByRole("table", { name: "Policy coverage" }));
    assertNoClaim(`${state.name} / Coverage`, container);
  });
});

// ---------------------------------------------------------------------------------------------------- Overview row

const NOW = new Date().toISOString();
function overviewBody(): OverviewView {
  return {
    generated_at: NOW, masked: true, denominators: { active_devices: 5, gateways: 4, clusters: 1, backup_targets: 4 },
    evidence: { inventory: { at: NOW, state: "OK" }, configuration: { at: NOW, state: "OK" }, compliance: { at: null, state: "UNKNOWN" }, backup: { at: NOW, state: "OK" },
      jobs: { at: NOW, state: "OK" }, platform_facts: { at: NOW, state: "OK" } },
    attention: { failed_jobs_24h: { count: 0, terminal_24h: 1, state: "OK" }, stale_inventory: { count: 0, of: 5, state: "OK" }, cluster_diff: { count: 0, of: 1, unknown: 0, state: "OK" },
      config_changed: { count: 0, of: 4, state: "OK" }, backup_missing: { count: 0, of: 4, state: "OK" } },
    exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] }, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } },
    inventory_age: { lt24h: 5, h24_72: 0, gt72h: 0, never: 0, of: 5 }, compliance: { state: "UNKNOWN", reason: "not_evaluated" },
    platform: { devices: 5, check_point: 3, palo_alto: 2, clusters: 1, hotfix_levels: [], evidence_at: NOW },
    nexus: { completed_24h: 1, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: NOW, palo_alto: NOW } },
    estate: { devices: [], counts: { critical: 0, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: 0 }, no_archive: 0, compliance_state: "OK", facts: {} },
  } as unknown as OverviewView;
}

/** The stored summary as the Overview reads it (`?limit=1`): the same states, in the same shape as the server sends. */
function overviewZoneBody(state: State): unknown {
  return { ...state.body, findings: [] };
}

describe.each(STATES)("the Overview zone matrix row in the state '$name'", (state) => {
  it("is an UNKNOWN or Review row that never claims BDDK compliance, a pass, a score or a compliant state", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      const payload = url.includes("/compliance/controls") ? { total_controls: 0, controls: [] }
        : url === "/devices" ? { devices: [] }
        : url.includes("/cp-failover/summary") ? []
        : url.startsWith("/api/v2/lifecycle") ? { as_of: NOW, devices: [], summary: {} }
        : url.startsWith("/api/v2/changes/feed") ? { events: [] }
        : url.startsWith("/api/v2/zone-matrix/summary") ? overviewZoneBody(state)
        : overviewBody();
      return Promise.resolve(new Response(JSON.stringify(payload), { status: 200 }));
    }));
    const session: SessionInfo = { displayName: "TEST-USER-01", roleTokens: [], permissions: ["Policy"], onSignOut: () => {} };
    const { container } = render(<SessionContext.Provider value={session}><OverviewScreen /></SessionContext.Provider>);
    const row = await waitFor(() => {
      const el = container.querySelector<HTMLElement>('[data-attention="zone-matrix-violations"], [data-attention="unknown-zonematrix"]');
      if (!el) throw new Error("the zone matrix row is not there yet");
      return el;
    });
    assertNoClaim(`${state.name} / Overview row`, row);
    expect(row.textContent ?? "").not.toBe("");
  });

  it("the row built by the attention rules carries no forbidden text in any field", () => {
    const now = new Date("2026-11-26T09:00:00Z");
    const base: AttentionInputs = {
      now, overview: { generated_at: now.toISOString(), masked: true, estate: { devices: [], counts: {}, no_archive: 0, compliance_state: "OK", facts: {} },
        exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] } } } as unknown as OverviewView,
      failover: { state: "ok", value: { devices: [], rows: [] } }, lifecycle: { state: "ok", value: { as_of: now.toISOString(), devices: [], summary: {} } as never },
      changes: { state: "ok", value: { events: [] } as never }, controls: [],
    };
    const read: SourceRead<unknown> = { state: "ok", value: state.body };
    const rows = buildAttention({ ...base, zoneMatrix: read }, undefined).rows.filter((r) => r.key === "zone-matrix-violations" || r.key === "unknown-zonematrix");
    expect(rows.length, "a state with something undecided or violating is never silent").toBeGreaterThan(0);
    for (const r of rows) {
      const all = [r.what, r.detail ?? "", r.linkLabel, r.atLabel, ...r.names].join(" . ");
      expect(forbidden(all), all).toBe(false);
      expect(/bddk|\bscore\b|\bpass(ed)?\b|\d\s*%|\bcompliant\b/i.test(all), all).toBe(false);
    }
  });
});
