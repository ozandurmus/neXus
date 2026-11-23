import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "X-Nexus-Masked": "true" } });
}

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal("matchMedia", vi.fn((query: string) => {
    const min = query.match(/\(min-width:\s*(\d+)px\)/);
    const max = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
    const matches = min ? widthPx >= Number(min[1]) : max ? widthPx <= Number(max[1]) : false;
    return { matches, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn() };
  }));
}

const hop = (index: number, node: string, verdict: string) => ({
  hop_index: index, node_id: node, node_kind: "firewall", vendor: "PAN", context: "", routing_context: null, device_id: null,
  ingress: { attachment_id: null, segment_id: null, interface_name: "ethernet1/3", zone: "core-apps" },
  egress: { attachment_id: null, segment_id: null, interface_name: "ethernet1/4", zone: null },
  packet_in: null, packet_out: null, route: null, nat: null,
  security: { security_verdict: verdict, security_reason: "", deciding_rule: null, undecidable_rules: [] },
  post_nat_security: null, policy_stale: false, policy_collected_at: new Date(Date.now() - 2 * 3_600_000).toISOString(),
  own_verdict: verdict, verdict, verdict_reason: "", reasons: [], caveats: [], notes: [], flags: [],
});

function pathBody(verdict: string) {
  return {
    status: "COMPUTED", snapshot_id: "s", computed_at: new Date().toISOString(),
    query: { src: "192.0.2.10", dst: "198.51.100.40", protocol: "tcp", port: 8443, ingress_node_id: null, ingress_interface_name: null, edge_mode: false },
    verdict, verdict_reason: "reason", plain: true, caveats: [], warnings: [], ecmp_truncated: false, asymmetric: false, blocking_hop: null,
    ingress: { ingress_mode: "x", ingress_status: "y", candidates: [] },
    executive_summary: { headline: verdict === "UNKNOWN" ? "The stored evidence does not decide this path." : "Every unit on the path allows this traffic.", blocking_hops: [], unknown_hops: [], asymmetry_detected: false, ecmp_branch_count: 1, ecmp_truncated: false, freshness_status: "x" },
    branches: [{ branch_id: "b", verdict, verdict_reason: "", termination: "DESTINATION", blocking_hop_index: null, caveats: [], warnings: [], hops: [hop(0, "FW-TANGO-04", verdict), hop(1, "FW-JULIET-06", verdict)], return_path: { return_status: "ok", return_reason: "", branches: [] } }],
    limitations: "Intent only.",
  };
}

function stubFetch(pathVerdict: string, calls: string[]) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push(`${init?.method ?? "GET"} ${url}`);
    if (url === "/api/v2/path-analysis/query") return jsonResponse(200, pathBody(pathVerdict));
    if (url === "/api/v2/policy/devices") return jsonResponse(200, { policies: [{ id: "p1", name: "POL-22" }], devices: [] });
    if (url === "/api/v2/policy/policies/p1/lookup") {
      return jsonResponse(200, {
        verdict: "UNKNOWN", lookupReason: "A rule could match but cannot be decided.", decidingRule: null,
        undecidableRules: [{ ruleId: "r2", number: 4, name: "maybe", sectionId: "s", sectionName: "Sec", action: "Accept", lookupReason: "ZONE_UNKNOWN" }],
        policyId: "p1", collectedAt: new Date().toISOString(), limitations: "NAT is not evaluated.", query: { querySource: "192.0.2.10", queryDestination: "198.51.100.40", queryService: "tcp/443" }, parts: [],
      });
    }
    if (url === "/api/v2/zone-matrix/policies") {
      return jsonResponse(200, { policies: [{ id: "p1", name: "POL-22", vendor: "PAN", source_id: null, source_name: null, container_id: null, container_name: null, collected_at: null, rule_count: 3, target_count: 1 }], not_evaluated: [], can: { view_blocks: false, edit: false, publish: false, evaluate: true } });
    }
    if (url === "/api/v2/zone-matrix/evaluate") {
      return jsonResponse(200, { evaluated: true, coverage: "COMPLETE", outcome_counts: { VIOLATION: 1 }, version_number: 3, collected_at: new Date().toISOString(), coverage_items: [],
        headline: { distinct_violating_rules: 1, violated_zone_pairs: 1, unknown_rules: 0, not_evaluable_rules: 0 } });
    }
    return jsonResponse(200, {});
  });
}

beforeEach(() => { resetSharedApiCache(); window.localStorage.clear(); });
afterEach(() => { resetSharedApiCache(); window.localStorage.clear(); vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

describe("Estate Ask workbench", () => {
  it("answers a path question with the verdict first, then the evidence hops, using the standard path API", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace");
    const calls: string[] = [];
    vi.stubGlobal("fetch", stubFetch("ALLOWED", calls));
    stubViewport(1280);
    render(<App />);

    const ask = await screen.findByTestId("estate-ask-screen");
    expect(within(ask).getByTestId("ask-empty")).toBeInTheDocument();
    const submit = within(ask).getByTestId("ask-submit");
    expect(submit).toBeDisabled();
    expect(submit).toHaveTextContent("Needs a source address");

    fireEvent.change(within(ask).getByTestId("ask-input"), { target: { value: "192.0.2.10 → 198.51.100.40 tcp/8443" } });
    expect(submit).toBeEnabled();
    fireEvent.click(submit);

    const answer = await within(ask).findByTestId("ask-answer");
    expect(answer).toHaveAttribute("data-ask-state", "ok");
    expect(answer).toHaveAttribute("data-ask-unprovable", "false");
    expect(answer).toHaveTextContent("Allowed by intent");
    expect(answer.querySelectorAll("[data-ask-hop]")).toHaveLength(2);
    expect(calls).toContain("POST /api/v2/path-analysis/query");
    expect(within(ask).getByRole("list", { name: "Recent questions" })).toBeInTheDocument();
    expect(within(ask).getByTestId("ask-standard-link")).toHaveAttribute("href", "?screen=trace&mode=path&standard=1");
  });

  it("says \"cannot be proven today\" when the path answer is UNKNOWN", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace");
    vi.stubGlobal("fetch", stubFetch("UNKNOWN", []));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    fireEvent.change(within(ask).getByTestId("ask-input"), { target: { value: "192.0.2.10 198.51.100.40 tcp 8443" } });
    fireEvent.click(within(ask).getByTestId("ask-submit"));
    const answer = await within(ask).findByTestId("ask-answer");
    expect(answer).toHaveAttribute("data-ask-state", "unk");
    expect(within(answer).getByTestId("ask-cannot-prove")).toHaveTextContent("Cannot be proven today");
    expect(answer.querySelector('[data-hatched="true"]')).not.toBeNull();
  });

  it("looks up the deciding rule in a stored policy and shows an UNKNOWN lookup as unprovable", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace&mode=lookup");
    const calls: string[] = [];
    vi.stubGlobal("fetch", stubFetch("ALLOWED", calls));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    fireEvent.change(within(ask).getByTestId("ask-input"), { target: { value: "192.0.2.10 198.51.100.40 tcp/443" } });
    const select = await within(ask).findByRole("combobox", { name: /Stored policy/ });
    await waitFor(() => expect(within(select).getByRole("option", { name: "POL-22" })).toBeInTheDocument());
    fireEvent.change(select, { target: { value: "p1" } });
    fireEvent.click(within(ask).getByTestId("ask-submit"));
    const answer = await within(ask).findByTestId("ask-answer");
    expect(answer).toHaveAttribute("data-ask-unprovable", "true");
    expect(answer).toHaveTextContent("Rule 4 could match");
    expect(calls).toContain("POST /api/v2/policy/policies/p1/lookup");
  });

  it("evaluates the zone matrix through the existing evaluate API and switches modes in the address", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace");
    const calls: string[] = [];
    vi.stubGlobal("fetch", stubFetch("ALLOWED", calls));
    stubViewport(1280);
    render(<App />);
    const ask = await screen.findByTestId("estate-ask-screen");
    fireEvent.click(within(ask).getByRole("tab", { name: /Zone matrix/ }));
    expect(window.location.search).toBe("?screen=trace&mode=zones");
    const select = await within(ask).findByRole("combobox", { name: /Policy to evaluate/ });
    await waitFor(() => expect(within(select).getByRole("option", { name: /POL-22/ })).toBeInTheDocument());
    fireEvent.change(select, { target: { value: "p1" } });
    fireEvent.click(within(ask).getByTestId("ask-submit"));
    const answer = await within(ask).findByTestId("ask-answer");
    expect(answer).toHaveAttribute("data-ask-state", "act");
    expect(answer).toHaveTextContent("Violations");
    expect(calls).toContain("POST /api/v2/zone-matrix/evaluate");
  });

  it("keeps the standard Trace screen reachable with standard=1", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace&mode=path&standard=1");
    vi.stubGlobal("fetch", stubFetch("ALLOWED", []));
    stubViewport(1280);
    render(<App />);
    expect(await screen.findByRole("navigation", { name: "Primary" })).toBeInTheDocument();
    expect(screen.queryByTestId("estate-ask-screen")).toBeNull();
  });

  it("updates ask-standard-link when switching between path, lookup, usage, cutover, and zones", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace");
    vi.stubGlobal("fetch", stubFetch("ALLOWED", []));
    stubViewport(1280);
    render(<App />);

    const ask = await screen.findByTestId("estate-ask-screen");
    const link = within(ask).getByTestId("ask-standard-link");
    expect(link).toHaveAttribute("href", "?screen=trace&mode=path&standard=1");
    expect(link).toHaveTextContent("Open the standard Trace screen →");

    fireEvent.click(within(ask).getByRole("tab", { name: /Which rule decides\?/ }));
    expect(link).toHaveAttribute("href", "?screen=trace&mode=lookup&standard=1");
    expect(link).toHaveTextContent("Open the standard Trace screen →");

    fireEvent.click(within(ask).getByRole("tab", { name: /Are this rule’s objects used\?/ }));
    expect(link).toHaveAttribute("href", "?screen=rule_usage&standard=1");
    expect(link).toHaveTextContent("Open the standard Rule object usage screen →");

    fireEvent.click(within(ask).getByRole("tab", { name: /What breaks at cutover\?/ }));
    expect(link).toHaveAttribute("href", "?screen=cutover&standard=1");
    expect(link).toHaveTextContent("Open the standard Cutover impact screen →");

    fireEvent.click(within(ask).getByRole("tab", { name: /Zone matrix/ }));
    expect(link).toHaveAttribute("href", "?screen=zone_matrix&standard=1");
    expect(link).toHaveTextContent("Open the standard Zone matrix screen →");
  });

  it("caps rendered evidence rows at ASK_EVIDENCE_ROW_CAP (25) and prints +N more rows via MoreFooter", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=trace&mode=zones");
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v2/zone-matrix/policies") {
        return jsonResponse(200, {
          policies: [{ id: "p1", name: "POL-22", vendor: "PAN", source_id: null, source_name: null, container_id: null, container_name: null, collected_at: null, rule_count: 30, target_count: 1 }],
          not_evaluated: [],
          can: { view_blocks: false, edit: false, publish: false, evaluate: true },
        });
      }
      if (url === "/api/v2/zone-matrix/evaluate") {
        const rules = Array.from({ length: 28 }, (_, i) => ({
          rule_id: `r-${i + 1}`,
          rule_uid: `u-${i + 1}`,
          rule_number: i + 1,
          rule_name: `rule-${i + 1}`,
          section_name: "Core",
          outcome: "VIOLATION",
          reason: null,
          highest_severity: "HIGH",
          violated_cells: [],
          undecided_parts: [],
        }));
        return jsonResponse(200, {
          evaluated: true,
          coverage: "COMPLETE",
          outcome_counts: { VIOLATION: 28 },
          version_number: 3,
          collected_at: new Date().toISOString(),
          rules,
          zone_definition_gaps: [],
          coverage_items: [],
          headline: { distinct_violating_rules: 28, violated_zone_pairs: 1, unknown_rules: 0, not_evaluable_rules: 0 },
        });
      }
      return jsonResponse(200, {});
    }));
    stubViewport(1280);
    render(<App />);

    const ask = await screen.findByTestId("estate-ask-screen");
    const select = await within(ask).findByRole("combobox", { name: /Policy to evaluate/ });
    await waitFor(() => expect(within(select).getByRole("option", { name: /POL-22/ })).toBeInTheDocument());
    fireEvent.change(select, { target: { value: "p1" } });
    fireEvent.click(within(ask).getByTestId("ask-submit"));

    const answer = await within(ask).findByTestId("ask-answer");
    expect(answer.querySelectorAll("[data-ask-hop]")).toHaveLength(25);
    const more = within(answer).getByTestId("ask-rows-more");
    expect(more).toHaveTextContent("+3 more rows");
  });
});
