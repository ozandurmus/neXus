import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PathAnalysisScreen, MASKED_INPUT_HINT, INPUT_HINT, toRequest } from "../src/screens/PathAnalysisScreen";
import { PathResultView } from "../src/screens/pathanalysis/PathResultView";
import { classifyPathFailure, dependsOn, explain, pathFormMissing, ruleLabel, ruleWithheld, verdictTone, verdictWord } from "../src/screens/pathanalysis/pathWords";
import { PATH_ALLOWED, PATH_ALLOWED_TO_EDGE, PATH_DENIED, PATH_DISCARD, PATH_PARTIAL, bravoHop, tangoHop } from "../src/preview/pathPreviewData";
import * as api from "../src/auth/adminApi";
import type { ApiError, PathResult } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  queryPath: vi.fn(),
  responsesAreMasked: vi.fn(() => false),
}));

const queryPath = vi.mocked(api.queryPath);
const responsesAreMasked = vi.mocked(api.responsesAreMasked);

function renderScreen() {
  return render(<ThemeProvider theme={m3Theme}><PathAnalysisScreen /></ThemeProvider>);
}
function renderResult(result: PathResult) {
  return render(<ThemeProvider theme={m3Theme}><PathResultView result={result} now={new Date("2026-11-04T08:30:00Z")} /></ThemeProvider>);
}
function fill(src = "198.51.100.20", dst = "203.0.113.200", port = "443") {
  fireEvent.change(screen.getByLabelText(/^Source/), { target: { value: src } });
  fireEvent.change(screen.getByLabelText(/^Destination/), { target: { value: dst } });
  fireEvent.change(screen.getByLabelText(/^Port/), { target: { value: port } });
}
function submit() {
  fireEvent.click(screen.getByTestId("path-submit"));
}
const refusal = (status: number, body: Record<string, unknown>): ApiError => ({ status, body });

beforeEach(() => {
  queryPath.mockReset();
  responsesAreMasked.mockReturnValue(false);
});

describe("the path query form", () => {
  it("starts empty with no verdict and says what the button waits for", () => {
    renderScreen();
    expect(screen.getByText("No path evaluated yet")).toBeInTheDocument();
    expect(screen.queryByTestId("path-result")).toBeNull();
    const button = screen.getByTestId("path-submit");
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent("Needs a source, a destination");
    expect(screen.getByTestId("path-input-hint")).toHaveTextContent(INPUT_HINT);
    expect(screen.getByTestId("path-input-hint")).toHaveAttribute("data-masked", "false");
  });

  it("accepts IPv4 hosts only and a port in range; an ingress interface needs its unit", () => {
    expect(pathFormMissing({ src: "198.51.100.20", dst: "203.0.113.200", protocol: "tcp", port: "443", ingressNode: "", ingressInterface: "", edge: false })).toEqual([]);
    expect(pathFormMissing({ src: "198.51.100.0/24", dst: "203.0.113.200", protocol: "tcp", port: "443", ingressNode: "", ingressInterface: "", edge: false })).toEqual(["an IPv4 source host (no prefix)"]);
    expect(pathFormMissing({ src: "198.51.100.20", dst: "example.invalid", protocol: "tcp", port: "70000", ingressNode: "", ingressInterface: "eth1", edge: false }))
      .toEqual(["an IPv4 destination host (no prefix)", "a port between 1 and 65535", "the ingress unit of that interface"]);
  });

  it("sends the body the API takes: only the fields that are set, the port as a number, edge_mode only when asked", async () => {
    queryPath.mockResolvedValue(PATH_ALLOWED);
    renderScreen();
    fill();
    fireEvent.change(screen.getByLabelText(/^Ingress unit/), { target: { value: "FW-TANGO-04/physical/main" } });
    fireEvent.change(screen.getByLabelText(/^Ingress interface/), { target: { value: "eth1" } });
    fireEvent.click(screen.getByRole("checkbox", { name: /Up to the edge/ }));
    submit();
    await waitFor(() => expect(queryPath).toHaveBeenCalledTimes(1));
    expect(queryPath).toHaveBeenCalledWith({ src: "198.51.100.20", dst: "203.0.113.200", protocol: "tcp", port: 443, ingress_node_id: "FW-TANGO-04/physical/main", ingress_interface_name: "eth1", edge_mode: true });
    expect(toRequest({ src: " 192.0.2.10 ", dst: "192.0.2.20", protocol: "udp", port: "53", ingressNode: "", ingressInterface: "eth9", edge: false }))
      .toEqual({ src: "192.0.2.10", dst: "192.0.2.20", protocol: "udp", port: 53 });
  });

  it("explains the masked input rule when the server has marked this session's responses as masked", () => {
    responsesAreMasked.mockReturnValue(true);
    renderScreen();
    expect(screen.getByTestId("path-input-hint")).toHaveTextContent(MASKED_INPUT_HINT);
    expect(screen.getByTestId("path-input-hint")).toHaveAttribute("data-masked", "true");
    expect(MASKED_INPUT_HINT).toContain("FW-TANGO-04");
    expect(MASKED_INPUT_HINT).toContain("192.0.2.10");
  });

  it("switches to the masked hint when the masking header arrives after mount", () => {
    renderScreen();
    expect(screen.getByTestId("path-input-hint")).toHaveAttribute("data-masked", "false");
    act(() => { window.dispatchEvent(new Event("nx-masked")); });
    expect(screen.getByTestId("path-input-hint")).toHaveAttribute("data-masked", "true");
  });
});

describe("the honest states", () => {
  it("409 TOPOLOGY_NOT_BUILT: says no topology is held, who can build it, and that a query never builds one", async () => {
    queryPath.mockRejectedValue(refusal(409, { code: "TOPOLOGY_NOT_BUILT" }));
    renderScreen(); fill(); submit();
    const panel = await screen.findByText("Topology not built");
    const card = panel.closest("[data-state]")!;
    expect(card).toHaveAttribute("data-state", "not_evaluated");
    expect(card).toHaveTextContent("A query never builds one");
    expect(card).toHaveTextContent("A Security Administrator can build it with a topology rebuild");
    expect(card).toHaveTextContent("TOPOLOGY_NOT_BUILT");
    expect(screen.queryByTestId("path-result")).toBeNull();
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });

  it("422 PSEUDONYM_REFUSED: one refusal for raw, unknown and ambiguous values; never says which value", async () => {
    queryPath.mockRejectedValue(refusal(422, { code: "PSEUDONYM_REFUSED" }));
    renderScreen(); fill("192.0.2.77", "192.0.2.88"); submit();
    const title = await screen.findByText("Query refused: a value was not recognised");
    const card = title.closest("[data-state]")!;
    expect(card).toHaveTextContent("refused in one and the same way");
    expect(card).toHaveTextContent("nothing about them is stored");
    expect(card).toHaveTextContent("PSEUDONYM_REFUSED");
    expect(card.textContent).not.toContain("192.0.2.77");
    expect(card.textContent).not.toContain("192.0.2.88");
  });

  it("503 POLICY_BUSY: a wait with Retry, not a fault of the inputs", async () => {
    queryPath.mockRejectedValueOnce(refusal(503, { error: "POLICY_BUSY" })).mockResolvedValueOnce(PATH_ALLOWED);
    renderScreen(); fill(); submit();
    const title = await screen.findByText("Path analysis is busy");
    expect(title.closest("[data-state]")).toHaveAttribute("data-state", "error");
    expect(title.closest("[data-state]")).toHaveTextContent("not a fault of the inputs");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByTestId("path-result")).toBeInTheDocument();
    expect(queryPath).toHaveBeenCalledTimes(2);
  });

  it("429: the per-minute limit, with the number", async () => {
    queryPath.mockRejectedValue(refusal(429, { code: "PATH_QUERY_RATE_LIMIT" }));
    renderScreen(); fill(); submit();
    const title = await screen.findByText("Query limit reached");
    expect(title.closest("[data-state]")).toHaveTextContent("30 path queries a minute");
    expect(title.closest("[data-state]")).toHaveTextContent("PATH_QUERY_RATE_LIMIT");
  });

  it("503 AUDIT_UNAVAILABLE: the result is withheld because the record could not be written", async () => {
    queryPath.mockRejectedValue(refusal(503, { code: "AUDIT_UNAVAILABLE" }));
    renderScreen(); fill(); submit();
    const title = await screen.findByText("Result withheld: the query record could not be written");
    expect(title.closest("[data-state]")).toHaveTextContent("no result is shown");
  });

  it("400, 403, a timeout and an unknown failure each keep their own words and claim no verdict", async () => {
    renderScreen(); fill();
    queryPath.mockRejectedValueOnce(refusal(400, { code: "INVALID_REQUEST" })); submit();
    expect(await screen.findByText("The query was not accepted")).toBeInTheDocument();
    queryPath.mockRejectedValueOnce(refusal(403, { code: "ACTION_REFUSED" })); submit();
    expect((await screen.findByText("Path analysis queries")).closest("[data-state]")).toHaveAttribute("data-state", "restricted");
    queryPath.mockRejectedValueOnce(refusal(0, { error: "TIMEOUT" })); submit();
    expect(await screen.findByText("The request did not complete")).toBeInTheDocument();
    queryPath.mockRejectedValueOnce(refusal(500, {})); submit();
    const generic = await screen.findByText("The path could not be evaluated");
    expect(generic.closest("[data-state]")).toHaveTextContent("HTTP 500");
    expect(screen.queryByTestId("path-result")).toBeNull();
  });

  it("classifies every refusal by status and code", () => {
    expect(classifyPathFailure(refusal(409, { code: "TOPOLOGY_NOT_BUILT" })).kind).toBe("not_built");
    expect(classifyPathFailure(refusal(422, { code: "PSEUDONYM_REFUSED" })).kind).toBe("refused");
    expect(classifyPathFailure(refusal(429, {})).kind).toBe("rate_limited");
    expect(classifyPathFailure(refusal(503, { error: "POLICY_BUSY" })).kind).toBe("busy");
    expect(classifyPathFailure(refusal(503, { code: "AUDIT_UNAVAILABLE" })).kind).toBe("audit");
    expect(classifyPathFailure(refusal(400, { code: "INVALID_REQUEST" })).kind).toBe("invalid");
    expect(classifyPathFailure(refusal(403, {})).kind).toBe("restricted");
    expect(classifyPathFailure(refusal(0, { error: "TIMEOUT" })).kind).toBe("offline");
    expect(classifyPathFailure(refusal(409, { code: "SOMETHING_ELSE" })).kind).toBe("error");
    expect(classifyPathFailure(undefined).kind).toBe("error");
  });

  it("a 200 that is not a computed result is reported, never rendered as a verdict", async () => {
    queryPath.mockResolvedValue({ status: "WEIRD" } as unknown as PathResult);
    renderScreen(); fill(); submit();
    const title = await screen.findByText("The path could not be evaluated");
    expect(title.closest("[data-state]")).toHaveTextContent("UNEXPECTED_RESULT_SHAPE");
  });

  it("a new failure replaces an earlier result (no stale verdict beside a refusal)", async () => {
    queryPath.mockResolvedValueOnce(PATH_ALLOWED).mockRejectedValueOnce(refusal(409, { code: "TOPOLOGY_NOT_BUILT" }));
    renderScreen(); fill(); submit();
    expect(await screen.findByTestId("path-result")).toBeInTheDocument();
    submit();
    await screen.findByText("Topology not built");
    expect(screen.queryByTestId("path-result")).toBeNull();
  });
});

describe("the verdict and the hops (fixture shaped like api-chain.golden.txt)", () => {
  it("ALLOWED: the verdict first, the headline, both hops with unit, ingress/egress, route, NAT, matched rule and verdict", () => {
    renderResult(PATH_ALLOWED);
    const verdict = screen.getByTestId("path-verdict");
    expect(verdict).toHaveAttribute("data-verdict", "ALLOWED");
    expect(within(verdict).getByText("Allowed")).toBeInTheDocument();
    expect(within(verdict).getByText("Traffic is ALLOWED across 2 hop(s)")).toBeInTheDocument();
    expect(within(verdict).getByText(/Query 198\.51\.100\.20 → 203\.0\.113\.200 · tcp\/443/)).toBeInTheDocument();
    expect(within(verdict).queryByText("Asymmetric return")).toBeNull();
    expect(within(verdict).queryByText("With caveats")).toBeNull();

    const hop1 = screen.getByTestId("path-hop-1");
    expect(hop1).toHaveAttribute("data-hop-verdict", "ALLOWED");
    expect(within(hop1).getByText("FW-TANGO-04/physical/main")).toBeInTheDocument();
    expect(within(hop1).getByLabelText("Check Point")).toBeInTheDocument();
    expect(within(hop1).getByText(/eth1 → eth2/)).toBeInTheDocument();
    expect(within(hop1).getByText(/203\.0\.113\.0\/26 via 192\.0\.2\.2 on eth2 \(static\)/)).toBeInTheDocument();
    expect(within(hop1).getByText(/Applied: #1 · NAT-n1 · NAT · static/)).toBeInTheDocument();
    // Check Point with destination NAT: both evaluations shown, the rule named once per address
    expect(within(hop1).getByText("Rule (pre-NAT address)")).toBeInTheDocument();
    expect(within(hop1).getByText("Rule (post-NAT address)")).toBeInTheDocument();
    expect(within(hop1).getAllByText("#1 · RULE-r1 · OBJ-SECTION-01 · Accept")).toHaveLength(2);
    expect(within(hop1).getByText(/198\.51\.100\.20 → 203\.0\.113\.200 tcp\/443/)).toBeInTheDocument();
    expect(within(hop1).getByText(/198\.51\.100\.20 → 203\.0\.113\.10 tcp\/443/)).toBeInTheDocument();
    const codes1 = within(hop1).getByTestId("hop-1-codes");
    expect(codes1).toHaveTextContent("CP_IMPLIED_RULES_NOT_MODELED");
    expect(codes1).toHaveTextContent("Check Point implied rules are not modelled.");
    expect(codes1).toHaveTextContent("CP_ROUTE_AFTER_NAT_UNKNOWN");

    const hop2 = screen.getByTestId("path-hop-2");
    expect(within(hop2).getByText("FW-BRAVO-02/vsys1/vr-main")).toBeInTheDocument();
    expect(within(hop2).getByLabelText("Palo Alto")).toBeInTheDocument();
    expect(within(hop2).getByText(/ethernet1\/1 \(ZONE-TRANSIT\) → ethernet1\/2 \(ZONE-DMZ\)/)).toBeInTheDocument();
    expect(within(hop2).getByText(/203\.0\.113\.0\/26 · connected on ethernet1\/2 \(connected\)/)).toBeInTheDocument();
    expect(within(hop2).getByText("#1 · RULE-WEB-01 · Local rules · allow")).toBeInTheDocument();
    expect(within(hop2).getByText("Matched rule")).toBeInTheDocument();
    expect(within(hop2).queryByTestId("rule-withheld")).toBeNull();

    // the return path is folded; opening it lists the two reverse-translated hops
    const ret = screen.getByTestId("return-path-b1");
    expect(within(ret).getByText("Return symmetric")).toBeInTheDocument();
    fireEvent.click(within(ret).getByRole("button", { name: /Show return hops · 2/ }));
    const rows = within(ret).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("FW-BRAVO-02/vsys1/vr-main");
    expect(rows[0]).toHaveTextContent("203.0.113.10 → 198.51.100.20 · reverse-translated");
    expect(screen.getByText(PATH_ALLOWED.limitations)).toBeInTheDocument();
  });

  it("DENIED: the blocking hop is pointed to from the summary, marked on the card with its rule, the return not computed", () => {
    renderResult(PATH_DENIED);
    expect(screen.getByTestId("path-verdict")).toHaveAttribute("data-verdict", "DENIED");
    expect(within(screen.getByTestId("path-overall-verdict")).getByText("Denied")).toBeInTheDocument();
    const summary = screen.getByTestId("path-summary-hops");
    expect(summary).toHaveTextContent("Blocked at hop 2");
    expect(summary).toHaveTextContent("FW-BRAVO-02/vsys1/vr-main");
    expect(summary).toHaveTextContent("rule #2 · RULE-DENY-01 · Local rules · deny");
    const hop2 = screen.getByTestId("path-hop-2");
    expect(hop2).toHaveAttribute("data-blocking", "true");
    expect(hop2).toHaveAttribute("data-hop-verdict", "DENIED");
    expect(within(hop2).getByText(/blocking hop/)).toBeInTheDocument();
    expect(screen.getByTestId("path-hop-1")).not.toHaveAttribute("data-blocking");
    const ret = screen.getByTestId("return-path-b1");
    expect(within(ret).getByText("Return not computed")).toBeInTheDocument();
    expect(within(ret).queryByRole("button")).toBeNull();
  });

  it("DENIED by a discard route: routing, not a rule, with the plain-word reason", () => {
    renderResult(PATH_DISCARD);
    expect(screen.getByTestId("path-summary-hops")).toHaveTextContent("The forwarding route is a discard (blackhole) route");
    const hop1 = screen.getByTestId("path-hop-1");
    expect(within(hop1).getByText(/203\.0\.113\.64\/26 · discard route \(static\)/)).toBeInTheDocument();
    expect(within(hop1).getByTestId("hop-1-reason")).toHaveTextContent("routing drops the packet before any rule");
    expect(within(hop1).getByText("Ingress → egress").parentElement).toHaveTextContent("eth1 → none");
  });

  it("ALLOWED_TO_EDGE is labelled as such and never reads as delivery", () => {
    renderResult(PATH_ALLOWED_TO_EDGE);
    const verdict = screen.getByTestId("path-verdict");
    expect(verdict).toHaveAttribute("data-verdict", "ALLOWED_TO_EDGE");
    expect(within(verdict).getByText("Allowed to the edge")).toBeInTheDocument();
    expect(verdict).toHaveTextContent("not a delivery claim");
    expect(verdict).toHaveTextContent("up to the edge");
    expect(screen.getByText(/End of branch: The path leaves the known topology/)).toBeInTheDocument();
    expect(screen.getByTestId("return-path-b1")).toHaveTextContent("not computed for a path that leaves the known topology");
  });

  it("PARTIAL with ECMP: branches side by side, UNKNOWN hop explained, CONDITIONAL hop names the hop it depends on, asymmetry warned, every caveat explained once", () => {
    renderResult(PATH_PARTIAL);
    const verdict = screen.getByTestId("path-verdict");
    expect(verdict).toHaveAttribute("data-verdict", "PARTIAL");
    expect(within(verdict).getByText("Partial")).toBeInTheDocument();
    expect(within(verdict).getByText("Asymmetric return")).toBeInTheDocument();
    expect(within(verdict).getByText("Branches truncated")).toBeInTheDocument();
    expect(within(verdict).getByText(/Equal-cost branches/)).toHaveTextContent("2");
    expect(within(verdict).getByText("Policy stale")).toBeInTheDocument();
    const notices = within(verdict).getByTestId("path-notices");
    for (const code of ["ECMP_TRUNCATED", "ASYMMETRIC_RETURN", "OUT_OF_STATE_DROP_LIKELY", "CLUSTER_UNCORROBORATED"]) {
      expect(notices).toHaveTextContent(code);
      expect(notices).toHaveTextContent(explain(code));
    }
    expect(screen.getByTestId("path-summary-hops")).toHaveTextContent("UNKNOWN at hop 2 (branch b1)");

    const b1 = screen.getByTestId("path-branch-b1");
    const b2 = screen.getByTestId("path-branch-b2");
    expect(within(b1).getByRole("heading", { name: "Branch b1" })).toBeInTheDocument();
    expect(within(b2).getByRole("heading", { name: "Branch b2" })).toBeInTheDocument();
    expect(within(b1).getByTestId("path-asymmetry-warning")).toHaveTextContent("out-of-state drop is likely");
    expect(within(b2).queryByTestId("path-asymmetry-warning")).toBeNull();

    // b1 hop 2: UNKNOWN from the Check Point access/NAT order, with both evaluations shown as context
    const unknownHop = within(b1).getByTestId("path-hop-2");
    expect(unknownHop).toHaveAttribute("data-hop-verdict", "UNKNOWN");
    expect(within(unknownHop).getByTestId("hop-2-reason")).toHaveTextContent("whether access rules see the pre-NAT or the post-NAT address is not established");
    expect(within(unknownHop).getByText("#4 · RULE-r4 · OBJ-SECTION-01 · Drop")).toBeInTheDocument();
    // b1 hop 3: CONDITIONAL on hop 2, own verdict kept visible, App-ID caveat in one line
    const conditional = within(b1).getByTestId("path-hop-3");
    expect(conditional).toHaveAttribute("data-hop-verdict", "CONDITIONAL");
    expect(within(conditional).getByTestId("hop-3-conditional")).toHaveTextContent("holds only if hop 2 resolves favourably");
    expect(within(conditional).getByText(/own: Allowed/)).toBeInTheDocument();
    expect(within(conditional).getByTestId("hop-3-codes")).toHaveTextContent("APP_ID_ASSUMED");
    expect(within(conditional).getByTestId("hop-3-codes")).toHaveTextContent("App-ID is assumed, not observed");
    expect(dependsOn(PATH_PARTIAL.branches[0], PATH_PARTIAL.branches[0].hops[2])).toEqual([2]);
    expect(dependsOn(PATH_PARTIAL.branches[0], PATH_PARTIAL.branches[0].hops[0])).toEqual([]);

    // b2 hop 2: stale policy, last-known rule as context only; local rules and PBR explained
    const stale = within(b2).getByTestId("path-hop-2");
    expect(within(stale).getByTestId("hop-2-stale")).toHaveTextContent("stale or out of sync");
    expect(stale).toHaveTextContent("last known, context only");
    expect(within(stale).getByTestId("hop-2-codes")).toHaveTextContent("PAN_LOCAL_RULES_NOT_COLLECTED");
    expect(within(stale).getByTestId("hop-2-codes")).toHaveTextContent("local rules were not collected");
    expect(within(stale).getByTestId("hop-2-codes")).toHaveTextContent("PBR_PRESENT");
    expect(within(b2).getByTestId("return-path-b2")).toHaveTextContent("Return incomplete");
    expect(within(b2).getByTestId("branch-notices-b2")).toHaveTextContent("RETURN_PATH_UNPROVEN");
    // equal-cost routes are marked on the first hop of both branches
    expect(within(within(b1).getByTestId("path-hop-1")).getByText(/\(static, equal-cost\)/)).toBeInTheDocument();
    expect(within(within(b2).getByTestId("path-hop-1")).getByText(/192\.0\.2\.6 on eth4/)).toBeInTheDocument();
  });

  it("UNKNOWN overall with an explicit ASYMMETRIC verdict word is tolerated and never green", () => {
    const asymmetric: PathResult = { ...PATH_ALLOWED, verdict: "UNKNOWN", verdict_reason: "ASYMMETRIC_RETURN", plain: false, asymmetric: true, warnings: ["ASYMMETRIC_RETURN", "OUT_OF_STATE_DROP_LIKELY"],
      executive_summary: { ...PATH_ALLOWED.executive_summary, headline: "Traffic is UNKNOWN: asymmetric return", asymmetry_detected: true } };
    renderResult(asymmetric);
    const verdict = screen.getByTestId("path-verdict");
    expect(within(verdict).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(verdict).getByText("Asymmetric return")).toBeInTheDocument();
    expect(verdict).toHaveTextContent("Return traffic crosses a different set of stateful units");
    expect(verdictTone("ASYMMETRIC")).toBe("warn");
    expect(verdictTone("ALLOWED_TO_EDGE")).not.toBe("ok");
    expect(verdictTone("CONDITIONAL")).not.toBe("ok");
    expect(verdictWord("ASYMMETRIC")).toBe("Asymmetric");
  });

  it("a decided hop without its rule shows the withheld marker instead of inventing one; a code without words keeps the code", () => {
    const withheld: PathResult = { ...PATH_ALLOWED, branches: [{ ...PATH_ALLOWED.branches[0],
      hops: [{ ...tangoHop(), security: { security_verdict: "ALLOWED", security_reason: "FIRST_MATCHING_RULE", deciding_rule: null, undecidable_rules: [] }, post_nat_security: null, notes: ["SOME_NEW_ENGINE_CODE"] }, bravoHop()] }] };
    renderResult(withheld);
    const hop1 = screen.getByTestId("path-hop-1");
    expect(within(hop1).getByTestId("rule-withheld")).toHaveTextContent("Rule withheld");
    expect(within(hop1).getByTestId("hop-1-codes")).toHaveTextContent("SOME_NEW_ENGINE_CODE");
    expect(within(hop1).getByTestId("hop-1-codes")).toHaveTextContent("No explanation recorded for this code.");
    expect(ruleWithheld("ALLOWED", null)).toBe(true);
    expect(ruleWithheld("UNKNOWN", null)).toBe(false);
    expect(ruleLabel({ rule_id: "x", rule_number: null, rule_name: null, section_id: null, section_name: "S", action: "drop" })).toBe("Unnamed rule · S · drop");
  });

  it("an undecidable rule on a hop is written as a possible match, never as the verdict", () => {
    const undecidable: PathResult = { ...PATH_ALLOWED, verdict: "UNKNOWN", verdict_reason: "UNKNOWN_HOP", plain: false,
      executive_summary: { ...PATH_ALLOWED.executive_summary, headline: "Traffic is UNKNOWN at hop 2", unknown_hops: [{ branch_id: "b1", hop_index: 2 }] },
      branches: [{ ...PATH_ALLOWED.branches[0], verdict: "UNKNOWN", verdict_reason: "UNKNOWN_HOP", hops: [tangoHop(), { ...bravoHop(), own_verdict: "UNKNOWN", verdict: "UNKNOWN", verdict_reason: "EARLIER_RULE_UNRESOLVED", reasons: ["EARLIER_RULE_UNRESOLVED"],
        security: { security_verdict: "UNKNOWN", security_reason: "EARLIER_RULE_UNRESOLVED", deciding_rule: bravoHop().security!.deciding_rule,
          undecidable_rules: [{ rule_id: "u1", rule_number: 1, rule_name: "RULE-GROUP-01", section_id: null, section_name: "Pre rules", action: "deny", lookup_reason: "ZONE_REQUIRED" }] } }] }] };
    renderResult(undecidable);
    const hop2 = screen.getByTestId("path-hop-2");
    expect(hop2).toHaveTextContent("UNKNOWN: rule #1 · RULE-GROUP-01 · Pre rules · deny could match (A zone is required");
    expect(hop2).toHaveTextContent("First proven match after uncertainty: #1 · RULE-WEB-01 · Local rules · allow");
    expect(screen.getByTestId("path-summary-hops")).toHaveTextContent("UNKNOWN at hop 2");
    expect(screen.getByTestId("path-summary-hops")).not.toHaveTextContent("rule #1 · RULE-WEB-01");
  });

  it("the full screen renders the result after a query and keeps the query line", async () => {
    queryPath.mockResolvedValue(PATH_DENIED);
    renderScreen(); fill("198.51.100.20", "203.0.113.200", "22"); submit();
    const result = await screen.findByTestId("path-result");
    expect(within(result).getByText("Traffic is DENIED at hop 2 by rule 2")).toBeInTheDocument();
    expect(screen.queryByText("No path evaluated yet")).toBeNull();
    expect(screen.getByTestId("path-submit")).toHaveTextContent("Evaluate path");
  });
});
