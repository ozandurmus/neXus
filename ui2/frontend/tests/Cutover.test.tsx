import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { App } from "../src/App";
import { CutoverScreen } from "../src/screens/CutoverScreen";
import { CutoverAssessmentPage } from "../src/screens/cutover/CutoverAssessmentPage";
import { FORBIDDEN_PATTERN, READINESS, coverageText } from "../src/screens/cutover/cutoverWording";
import { activeLeafId, groupOfLeaf } from "../src/shell/navModel";
import {
  createCutoverSegment, getCutoverAssessment, getCutoverFlows, getCutoverSegment, listCutoverSegments, listDevices, listPolicies, runCutoverAssessment,
  type CutoverAssessmentView, type CutoverFlowItem,
} from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (original) => ({
  ...(await original<typeof import("../src/auth/adminApi")>()),
  listCutoverSegments: vi.fn(), getCutoverSegment: vi.fn(), createCutoverSegment: vi.fn(), updateCutoverSegment: vi.fn(), deleteCutoverSegment: vi.fn(),
  runCutoverAssessment: vi.fn(), getCutoverAssessment: vi.fn(), getCutoverFlows: vi.fn(), deleteCutoverAssessment: vi.fn(),
  listDevices: vi.fn(), listPolicies: vi.fn(),
}));

/** Pre-cutover impact analysis screen (CUTOVER_IMPACT_UI_BRIEF_2026_11_14.md). Documentation ranges and masked names only. */
const SEGMENT_ID = "11111111-1111-4111-8111-111111111111";
const ASSESSMENT_ID = "22222222-2222-4222-8222-222222222222";
const segment = { segmentId: SEGMENT_ID, name: "SEG-ALPHA", revision: 2, blocks: ["192.0.2.0/24"], peerBlocks: ["198.51.100.0/24"], updatedAt: "2026-11-14T09:00:00Z" };
const devices = [
  { device_id: "d-cp", hostname: "FW-TANGO-04", vendor_hint: "check_point" },
  { device_id: "d-pan", hostname: "FW-JULIET-06", vendor_hint: "palo_alto" },
  { device_id: "d-asa", hostname: "FW-BRAVO-02", vendor_hint: "cisco_asa" },
];
const policies = [
  { id: "p-cp", name: "POLICY-ALPHA", vendor: "CP", targets: [{ deviceId: "d-cp", name: "FW-TANGO-04", context: "", syncStatus: "" }] },
  { id: "p-pan", name: "POLICY-BRAVO", vendor: "PAN", targets: [{ deviceId: "d-pan", name: "FW-JULIET-06", context: "vsys1", syncStatus: "" }] },
];
const coverage = [
  { code: "LOG_PERIOD", periodStart: "2026-11-01T00:00:00Z", periodEnd: "2026-11-08T00:00:00Z" },
  { code: "LOGGING_COMPLETENESS_UNKNOWN" }, { code: "UNLOGGED_FLOWS_INVISIBLE" },
  { code: "TARGET_FROM_ROUTED_PATH" }, { code: "FABRIC_REDIRECT_NOT_MODELLED" }, { code: "INSTALL_STATE_NOT_VERIFIED" },
];
const view = (over: Partial<CutoverAssessmentView> = {}): CutoverAssessmentView => ({
  assessmentId: ASSESSMENT_ID, segmentId: SEGMENT_ID, segmentRevision: 2, createdAt: "2026-11-14T10:00:00Z", actor: "ACTOR-ALPHA",
  targetSource: "PATH", topologySnapshotId: "topo-1", status: "COMPLETE", readiness: "BLOCKERS_FOUND", engineVersion: "cutover-1",
  headline: "2 of 5 evaluated flows would be dropped by the target policy.",
  counts: { tuples: 5, flows: 90, WOULD_DROP: 2, NOT_DECIDED: 1, NOT_EVALUABLE: 0, WOULD_ALLOW: 2, alreadyDroppedTuples: 1, missingRuleGroups: 1 },
  coverage, uploads: [{ index: 0, rowCount: 100, invalidRows: 2, inScopeFlows: 90, periodStart: "2026-11-01T00:00:00Z", periodEnd: "2026-11-08T00:00:00Z" }],
  stale: false, staleReasons: [], ...over,
});
const drop: CutoverFlowItem = {
  direction: "SEGMENT_TO_PEER", flowSource: "192.0.2.10", flowDestination: "198.51.100.20", protocol: "tcp", port: 22, flowCount: 40,
  decision: "WOULD_DROP", decisionReason: "", targetSource: "PATH", flags: ["OLD_PATH_ALLOWED"],
  decidingRule: { ruleId: "r9", number: 9, name: "RULE-CLEANUP", action: "drop" },
  pathFirewalls: [{ deviceId: "d-cp", vendor: "CP", hopVerdict: "ALLOWED" }, { deviceId: "d-pan", vendor: "PAN", hopVerdict: "DENIED", decidingRule: { number: 9, name: "RULE-CLEANUP" } }],
};
const dropped: CutoverFlowItem = { ...drop, flowSource: "192.0.2.11", flowCount: 3, flags: ["ALREADY_DROPPED_TODAY"], pathFirewalls: undefined };

function csvFile(name = "synthetic.csv") {
  const text = "src,dst,dstport,proto\n192.0.2.8,198.51.100.8,443,tcp\n";
  const f = new File([text], name, { type: "text/csv" });
  vi.spyOn(f, "slice").mockReturnValue({ size: f.size, text: async () => text } as Blob);
  return f;
}
const visibleText = () => document.body.textContent ?? "";

beforeEach(() => {
  vi.mocked(listCutoverSegments).mockResolvedValue({ segments: [segment], canManage: true });
  vi.mocked(getCutoverSegment).mockResolvedValue({ ...segment, assessments: [view()], canManage: true });
  vi.mocked(listDevices).mockResolvedValue({ devices } as never);
  vi.mocked(listPolicies).mockResolvedValue({ policies, devices: [] } as never);
  vi.mocked(getCutoverAssessment).mockResolvedValue(view());
  vi.mocked(getCutoverFlows).mockImplementation((async (_id: string, kind: string, decision: string | null) => {
    if (kind === "MISSING_RULE") return { total: 1, page: 0, items: [{ direction: "SEGMENT_TO_PEER", flowDestination: "198.51.100.20", protocol: "tcp", port: 22, sourceCount: 2,
      flowSources: ["192.0.2.10", "192.0.2.12"], flowCount: 44, decisionCounts: [{ decision: "WOULD_DROP", count: 2 }] }] };
    if (decision === "NOT_DECIDED") return { total: 2, page: 0, items: [
      { ...drop, decision: "NOT_DECIDED", decisionReason: "EXITS_KNOWN_TOPOLOGY", decidingRule: null, flags: [] },
      { ...drop, flowSource: "192.0.2.13", decision: "NOT_DECIDED", decisionReason: "time or content constraint", decidingRule: null, flags: ["CONDITIONAL_RULE"],
        conditionalRule: { number: 4, name: "RULE-ALPHA" }, droppedConstraints: ["time window"] },
    ] };
    return { total: 2, page: 0, items: [drop, dropped] };
  }) as never);
  vi.mocked(runCutoverAssessment).mockResolvedValue({ assessmentId: ASSESSMENT_ID, readiness: "BLOCKERS_FOUND", status: "COMPLETE" });
  vi.mocked(createCutoverSegment).mockResolvedValue({ segmentId: SEGMENT_ID });
});
afterEach(() => { vi.clearAllMocks(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

describe("navigation", () => {
  it("is a screen of its own under Policy and changes, after Rule object usage", async () => {
    expect(activeLeafId("cutover", "?screen=cutover&segment=x")).toBe("cutover");
    expect(groupOfLeaf("cutover")?.label).toBe("Policy and changes");
    render(<App search="?screen=cutover" />);
    expect(await screen.findByRole("heading", { name: "Cutover impact" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "SEG-ALPHA" })).toBeInTheDocument();
  });
});

describe("segments", () => {
  it("locks the write controls for a role that may not manage, and says so", async () => {
    vi.mocked(listCutoverSegments).mockResolvedValue({ segments: [segment], canManage: false });
    render(<CutoverScreen />);
    expect(await screen.findByRole("button", { name: "New segment" })).toBeDisabled();
    expect(screen.getByText("Role: not permitted")).toBeInTheDocument();
  });

  it("sends one block per line and shows the server's refusal as it is", async () => {
    vi.mocked(createCutoverSegment).mockRejectedValueOnce({ status: 400, body: { error: "SEGMENT_BLOCKS: 192.0.2.1/24 is not canonical" } });
    render(<CutoverScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "New segment" }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: "SEG-BRAVO" } });
    fireEvent.change(screen.getByLabelText(/^Segment blocks/), { target: { value: "192.0.2.1/24\n\n192.0.2.128/25 " } });
    fireEvent.change(screen.getByLabelText(/^Peer blocks/), { target: { value: "198.51.100.0/24" } });
    fireEvent.click(screen.getByRole("button", { name: "Save segment" }));
    expect(await screen.findByText("SEGMENT_BLOCKS: 192.0.2.1/24 is not canonical")).toBeInTheDocument();
    expect(createCutoverSegment).toHaveBeenCalledWith({ name: "SEG-BRAVO", blocks: ["192.0.2.1/24", "192.0.2.128/25"], peerBlocks: ["198.51.100.0/24"] });
  });
});

describe("new assessment", () => {
  async function openSegment() {
    window.history.replaceState(null, "", `/?screen=cutover&segment=${SEGMENT_ID}`);
    render(<CutoverScreen />);
    return screen.findByRole("region", { name: "New assessment" });
  }
  async function addFile(panel: HTMLElement) {
    fireEvent.change(within(panel).getByLabelText("Traffic-log CSV files"), { target: { files: [csvFile()] } });
    await waitFor(() => expect(within(panel).getByLabelText(/I confirm the column mappings/)).toBeEnabled());
    fireEvent.click(within(panel).getByLabelText(/I confirm the column mappings/));
  }

  it("defaults to the computed path: no firewall picker, the redirect caveat, and a spec without an override", async () => {
    const panel = await openSegment();
    expect(within(panel).getByText("Target firewalls: from each flow's computed path")).toBeInTheDocument();
    expect(within(panel).getByText(/fabric redirect \(contracts, service graph \/ PBR\) is not modelled yet/)).toBeInTheDocument();
    expect(within(panel).queryByLabelText(/Target firewall/)).toBeNull();
    // The segment page and its panel carry no forbidden word and no green state either (ACI-1).
    expect(visibleText()).not.toMatch(FORBIDDEN_PATTERN);
    expect(document.querySelector("[data-pill='ok']")).toBeNull();
    await addFile(panel);
    fireEvent.click(within(panel).getByRole("button", { name: "Run assessment" }));
    await waitFor(() => expect(runCutoverAssessment).toHaveBeenCalled());
    const [segmentId, spec, files] = vi.mocked(runCutoverAssessment).mock.calls[0];
    expect(segmentId).toBe(SEGMENT_ID);
    expect(spec).toEqual({ mappings: [{ source: "src", destination: "dst", port: "dstport", protocol: "proto" }] });
    expect(files).toHaveLength(1);
    expect(new URLSearchParams(window.location.search).get("assessment")).toBe(ASSESSMENT_ID);
  });

  it("names a missing topology and links to Trace", async () => {
    vi.mocked(runCutoverAssessment).mockRejectedValueOnce({ status: 409, body: { error: "TOPOLOGY_NOT_BUILT: no topology is held; rebuild it on the Trace screen or name a target override" } });
    const panel = await openSegment();
    await addFile(panel);
    fireEvent.click(within(panel).getByRole("button", { name: "Run assessment" }));
    expect(await within(panel).findByText(/TOPOLOGY_NOT_BUILT/)).toBeInTheDocument();
    expect(within(panel).getByRole("link", { name: "Open Trace to rebuild the topology" })).toHaveAttribute("href", "?screen=trace&mode=path");
  });

  it("a Palo Alto override lists only its policies and needs both zones before it runs", async () => {
    const panel = await openSegment();
    fireEvent.click(within(panel).getByLabelText("Override target"));
    fireEvent.mouseDown(within(panel).getByLabelText(/Target firewall/));
    fireEvent.click(await screen.findByRole("option", { name: /FW-JULIET-06/ }));
    fireEvent.mouseDown(await within(panel).findByLabelText(/Stored policy installed on it/));
    const options = await screen.findAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual(["Select the policy", "POLICY-BRAVO"]);
    fireEvent.click(screen.getByRole("option", { name: "POLICY-BRAVO" }));
    await addFile(panel);
    const run = within(panel).getByRole("button", { name: "Run assessment" });
    expect(run).toBeDisabled();
    fireEvent.change(within(panel).getByLabelText(/Segment-side zone/), { target: { value: "ZONE-ALPHA" } });
    fireEvent.change(within(panel).getByLabelText(/Peer-side zone/), { target: { value: "ZONE-BRAVO" } });
    expect(run).toBeEnabled();
    fireEvent.click(run);
    await waitFor(() => expect(runCutoverAssessment).toHaveBeenCalled());
    expect(vi.mocked(runCutoverAssessment).mock.calls[0][1].targetOverride).toEqual({ deviceId: "d-pan", policyId: "p-pan", context: "", segmentZone: "ZONE-ALPHA", peerZone: "ZONE-BRAVO" });
  });
});

describe("assessment page (ACI-1, ACI-6)", () => {
  it("shows the server's headline, the chip and the target label, with the coverage list directly under the verdict", async () => {
    render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    const headline = await screen.findByText("2 of 5 evaluated flows would be dropped by the target policy.");
    const verdict = headline.closest("[data-fact='verdict']")!;
    expect(within(verdict as HTMLElement).getByText("Blockers found")).toBeInTheDocument();
    expect(within(verdict as HTMLElement).getByText("Target: computed path")).toBeInTheDocument();
    const next = verdict.nextElementSibling!;
    expect(next.getAttribute("data-fact")).toBe("coverage");
    expect(next.tagName).not.toBe("DETAILS");
    expect([...next.querySelectorAll("[data-coverage]")].map((li) => li.getAttribute("data-coverage"))).toEqual(coverage.map((c) => c.code));
  });

  it("labels an operator override and the stale reasons, and never shows an empty coverage list", async () => {
    vi.mocked(getCutoverAssessment).mockResolvedValue(view({ targetSource: "OPERATOR_OVERRIDE", targetDeviceId: "d-cp", coverage: [], stale: true, staleReasons: ["TOPOLOGY_CHANGED"] }));
    render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    expect(await screen.findByText("Target: OPERATOR OVERRIDE")).toBeInTheDocument();
    expect(screen.getByText(/STALE: the topology the paths were computed on is no longer the one held/)).toBeInTheDocument();
    expect(screen.getByText(/The server returned no coverage list/)).toBeInTheDocument();
  });

  it.each(Object.keys(READINESS))("readiness %s: no green state and no forbidden word anywhere on the page", async (readiness) => {
    vi.mocked(getCutoverAssessment).mockResolvedValue(view({ readiness, headline: "No blocking flow found among 5 evaluated flows. Coverage is incomplete: read the coverage list." }));
    const { container } = render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    await screen.findAllByText("192.0.2.10");
    expect(container.querySelector("[data-fact='readiness']")!.textContent).toBe(READINESS[readiness].word);
    expect(container.querySelector("[data-pill='ok']")).toBeNull();
    expect(visibleText()).not.toMatch(FORBIDDEN_PATTERN);
  });

  it("no coverage sentence carries a forbidden word", () => {
    for (const code of ["LOG_PERIOD", "LOGGING_COMPLETENESS_UNKNOWN", "UNLOGGED_FLOWS_INVISIBLE", "INSTALL_STATE_NOT_VERIFIED", "NAT_NOT_EVALUATED", "TARGET_NOT_EVALUABLE",
      "TARGET_SNAPSHOT_INCOMPLETE", "ZONE_PAIR_MISSING", "IPV6_FLOWS_SET_ASIDE", "INVALID_ROWS", "OUT_OF_SCOPE_FLOWS", "NON_TCP_UDP_FLOWS", "APPLICATIONS_NOT_EVALUATED",
      "ASSESSMENT_INCOMPLETE", "ACTION_VALUES_UNRECOGNISED", "TARGET_FROM_OPERATOR_OVERRIDE", "TARGET_FROM_ROUTED_PATH", "FABRIC_REDIRECT_NOT_MODELLED"]) {
      const text = coverageText({ code, count: 3 });
      expect(text).not.toBe(code);
      expect(text).not.toMatch(FORBIDDEN_PATTERN);
    }
    expect(coverageText({ code: "SOMETHING_NEW" })).toBe("SOMETHING_NEW");
  });

  it("lists the firewalls on each flow's path by their masked names, and greys a flow already dropped today", async () => {
    render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    const path = (await screen.findAllByText(/Firewalls on the computed path/))[0].parentElement!;
    expect(within(path).getByText(/FW-TANGO-04 \(CP\) · ALLOWED/)).toBeInTheDocument();
    expect(within(path).getByText(/FW-JULIET-06 \(PAN\) · DENIED · rule #9 RULE-CLEANUP/)).toBeInTheDocument();
    const greyed = document.querySelector("tr[data-flags='ALREADY_DROPPED_TODAY']")!;
    expect(within(greyed as HTMLElement).getByText(/Already dropped today/)).toBeInTheDocument();
    expect(greyed.querySelector("[data-pill]")!.getAttribute("data-pill")).toBe("neutral");
    expect(getCutoverFlows).toHaveBeenCalledWith(ASSESSMENT_ID, "FLOW", "WOULD_DROP", 0);
  });

  it("groups Not decided by the server's reason and spells out a conditional rule", async () => {
    window.history.replaceState(null, "", "/?screen=cutover&tab=open");
    render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    expect(await screen.findByText(/Reason: EXITS_KNOWN_TOPOLOGY · 1 on this page/)).toBeInTheDocument();
    expect(screen.getByText(/Reason: time or content constraint · 1 on this page/)).toBeInTheDocument();
    expect(screen.getByText("Rule #4 RULE-ALPHA could match only if time window hold; not decided.")).toBeInTheDocument();
    expect(getCutoverFlows).toHaveBeenCalledWith(ASSESSMENT_ID, "FLOW", "NOT_DECIDED", 0);
  });

  it("asks the server for missing-rule groups on their own tab", async () => {
    render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);
    fireEvent.click(await screen.findByRole("tab", { name: /Missing rules · 1/ }));
    expect(await screen.findByText("192.0.2.10, 192.0.2.12")).toBeInTheDocument();
    expect(getCutoverFlows).toHaveBeenCalledWith(ASSESSMENT_ID, "MISSING_RULE", null, 0);
    expect(new URLSearchParams(window.location.search).get("tab")).toBe("missing");
  });

  it("carries the tuple in the Trace link fragment when linkable, and renders no link when unlinkable", async () => {
    const leadingZeroSrc = ["192", "0", "2", "010"].join(".");
    vi.mocked(getCutoverFlows).mockResolvedValueOnce({
      total: 5,
      page: 0,
      items: [
        drop,
        { ...drop, flowSource: "192.0.2.20", flowDestination: "198.51.100.30", protocol: "UDP", port: 53 },
        { ...drop, flowSource: "192.0.2.21", flowDestination: "198.51.100.31", protocol: "icmp", port: 8 },
        { ...drop, flowSource: "192.0.2.22", flowDestination: "198.51.100.32", protocol: "Tcp", port: 443 },
        { ...drop, flowSource: leadingZeroSrc, flowDestination: "198.51.100.33", protocol: "tcp", port: 443 },
      ],
    } as never);
    render(<CutoverAssessmentPage assessmentId={ASSESSMENT_ID} back={() => undefined} />);

    const tcpLink = await screen.findByRole("link", { name: "Open Trace for this tuple (192.0.2.10 → 198.51.100.20 tcp/22)" });
    expect(tcpLink).toHaveAttribute("href", "?screen=trace&mode=path#tuple=192.0.2.10,198.51.100.20,tcp,22");

    const udpLink = screen.getByRole("link", { name: "Open Trace for this tuple (192.0.2.20 → 198.51.100.30 UDP/53)" });
    expect(udpLink).toHaveAttribute("href", "?screen=trace&mode=path#tuple=192.0.2.20,198.51.100.30,udp,53");

    expect(screen.queryByRole("link", { name: /192\.0\.2\.21/ })).toBeNull();
    expect(screen.getByText("Open Trace for this tuple (192.0.2.21 → 198.51.100.31 icmp/8) · tuple not linkable")).toBeInTheDocument();

    expect(screen.queryByRole("link", { name: /192\.0\.2\.22/ })).toBeNull();
    expect(screen.getByText("Open Trace for this tuple (192.0.2.22 → 198.51.100.32 Tcp/443) · tuple not linkable")).toBeInTheDocument();

    expect(screen.queryByRole("link", { name: new RegExp(leadingZeroSrc.replace(/\./g, "\\.")) })).toBeNull();
    expect(screen.getByText(`Open Trace for this tuple (${leadingZeroSrc} → 198.51.100.33 tcp/443) · tuple not linkable`)).toBeInTheDocument();

    expect(visibleText()).not.toMatch(FORBIDDEN_PATTERN);
  });

  it("navigates from a Cutover flow link into Trace with the four tuple fields prefilled and without auto-running", async () => {
    window.history.replaceState(null, "", `/?screen=cutover&segment=${SEGMENT_ID}&assessment=${ASSESSMENT_ID}`);
    render(<App />);
    const link = await screen.findByRole("link", { name: "Open Trace for this tuple (192.0.2.10 → 198.51.100.20 tcp/22)" });
    fireEvent.click(link);

    expect(window.location.search).toBe("?screen=trace&mode=path");
    expect(window.location.hash).toBe("#tuple=192.0.2.10,198.51.100.20,tcp,22");
    expect(await screen.findByRole("heading", { name: "Trace" })).toBeInTheDocument();
    expect(screen.getByTestId("path-prefilled-note")).toHaveTextContent("Filled from a link: check the values, then press Evaluate path.");
    expect(screen.getByLabelText(/^Source/)).toHaveValue("192.0.2.10");
    expect(screen.getByLabelText(/^Destination/)).toHaveValue("198.51.100.20");
    expect(screen.getByLabelText(/^Port/)).toHaveValue("22");
    expect(screen.getByText("No path evaluated yet")).toBeInTheDocument();
  });
});
