import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import {
  HOP_VERDICT_SIDE,
  NEIGHBOUR_CAP,
  NEIGHBOUR_SNAPSHOT_MISMATCH,
  NEIGHBOUR_TOPOLOGY_UNAVAILABLE,
  PathDiagram,
  PathResultView,
  buildPathDiagramModel,
} from "../src/screens/pathanalysis/PathResultView";
import { explain } from "../src/screens/pathanalysis/pathWords";
import { PathAnalysisPreview } from "../src/preview/PathAnalysisPreview";
import {
  PATH_ALLOWED,
  PATH_ALLOWED_TO_EDGE,
  PATH_DENIED,
  PATH_PARTIAL,
  PATH_TOPOLOGY_PREVIEW,
  PATH_VALIDATION_COMPLETE,
  tangoHop,
} from "../src/preview/pathPreviewData";
import type { PathVerification } from "../src/screens/pathanalysis/usePathValidation";
import * as api from "../src/auth/adminApi";
import type { PathResult, TopologySnapshotView } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  getTopology: vi.fn(),
}));

const getTopology = vi.mocked(api.getTopology);
const NOW = new Date("2026-11-04T08:30:00Z");

const VERIFIED: PathVerification = {
  state: {
    phase: "complete",
    branchId: "b1",
    validationId: PATH_VALIDATION_COMPLETE.validation_id,
    validation: PATH_VALIDATION_COMPLETE,
    failure: null,
    polls: 2,
    lastPolledAt: "2026-11-04T08:06:00Z",
  },
  start: () => undefined,
  checkAgain: () => undefined,
  reset: () => undefined,
};

beforeEach(() => {
  getTopology.mockReset();
});

describe("the node and edge model built from a path result", () => {
  it("single path: left-to-right source, segment pills with masked prefixes, hops with verdict colours/interfaces/device badges, and destination", () => {
    const model = buildPathDiagramModel(PATH_ALLOWED, {
      validation: PATH_VALIDATION_COMPLETE,
      verifiedBranchId: "b1",
      topology: PATH_TOPOLOGY_PREVIEW,
    });

    const source = model.nodes.find((n) => n.data.kind === "source")!;
    const dest = model.nodes.find((n) => n.data.kind === "destination")!;
    const hops = model.nodes.filter((n) => n.data.kind === "hop");
    const segments = model.nodes.filter((n) => n.data.kind === "segment");

    expect(source.data.title).toBe("198.51.100.20");
    expect(dest.data.title).toBe("203.0.113.200");
    expect(hops.map((h) => h.data.nodeId)).toEqual([
      "FW-TANGO-04/physical/main",
      "FW-BRAVO-02/vsys1/vr-main",
    ]);
    expect(segments.map((s) => s.data.prefix)).toEqual([
      "198.51.100.0/26",
      "192.0.2.0/30",
      "203.0.113.0/26",
    ]);

    // Left-to-right x ordering: source < seg1 < hop1 < seg2 < hop2 < seg3 < destination
    expect(source.position.x).toBeLessThan(segments[0].position.x);
    expect(segments[0].position.x).toBeLessThan(hops[0].position.x);
    expect(hops[0].position.x).toBeLessThan(segments[1].position.x);
    expect(segments[1].position.x).toBeLessThan(hops[1].position.x);
    expect(hops[1].position.x).toBeLessThan(segments[2].position.x);
    expect(segments[2].position.x).toBeLessThan(dest.position.x);

    // Hop metadata: verdict colour, interfaces, and A6 device validation
    expect(hops[0].data.verdictColor).toBe(HOP_VERDICT_SIDE.ALLOWED);
    expect(hops[0].data.ingressInterface).toBe("eth1");
    expect(hops[0].data.egressInterface).toBe("eth2");
    expect(hops[0].data.deviceView).toEqual({
      kind: "validated",
      hop: PATH_VALIDATION_COMPLETE.hops[0],
    });
    expect(hops[1].data.ingressInterface).toBe("ethernet1/1");
    expect(hops[1].data.egressInterface).toBe("ethernet1/2");
    expect(hops[1].data.deviceView).toEqual({
      kind: "validated",
      hop: PATH_VALIDATION_COMPLETE.hops[1],
    });
  });

  it("ECMP: draws equal-cost branches as parallel horizontal lanes", () => {
    const model = buildPathDiagramModel(PATH_PARTIAL);
    const b1Hops = model.nodes.filter((n) => n.data.kind === "hop" && n.data.branchId === "b1");
    const b2Hops = model.nodes.filter((n) => n.data.kind === "hop" && n.data.branchId === "b2");

    expect(b1Hops).toHaveLength(3);
    expect(b2Hops).toHaveLength(2);
    expect(b1Hops[0].position.y).toBe(0);
    expect(b2Hops[0].position.y).toBeGreaterThan(b1Hops[0].position.y);
    expect(b1Hops.every((h) => h.position.y === b1Hops[0].position.y)).toBe(true);
    expect(b2Hops.every((h) => h.position.y === b2Hops[0].position.y)).toBe(true);

    // Verdict lattice colours match PathResultView
    expect(b1Hops[0].data.verdictColor).toBe(HOP_VERDICT_SIDE.ALLOWED);
    expect(b1Hops[1].data.verdictColor).toBe(HOP_VERDICT_SIDE.UNKNOWN);
    expect(b1Hops[2].data.verdictColor).toBe(HOP_VERDICT_SIDE.CONDITIONAL);
  });

  it("UNKNOWN stops (EXITS_KNOWN_TOPOLOGY, NO_ROUTE, AMBIGUOUS_INGRESS, UNKNOWN_HOP): draws known hops then a dashed edge to a '?' node with the reason in words", () => {
    // 1. EXITS_KNOWN_TOPOLOGY
    const edgeModel = buildPathDiagramModel(PATH_ALLOWED_TO_EDGE);
    const edgeUnknown = edgeModel.nodes.find((n) => n.data.kind === "unknown")!;
    expect(edgeUnknown.data.title).toBe("?");
    expect(edgeUnknown.data.reasonText).toContain(explain("EXITS_KNOWN_TOPOLOGY"));
    const edgeToUnknown = edgeModel.edges.find((e) => e.target === edgeUnknown.id)!;
    expect(edgeToUnknown.data?.dashed).toBe(true);
    expect(edgeToUnknown.style?.strokeDasharray).toBe("6 4");

    // 2. NO_ROUTE
    const noRouteResult: PathResult = {
      ...PATH_ALLOWED,
      verdict: "UNKNOWN",
      verdict_reason: "NO_ROUTE",
      plain: false,
      branches: [
        {
          ...PATH_ALLOWED.branches[0],
          verdict: "UNKNOWN",
          verdict_reason: "NO_ROUTE",
          termination: "NO_ROUTE",
          hops: [
            {
              ...tangoHop(),
              egress: { attachment_id: null, segment_id: null, interface_name: null, zone: null },
              route: null,
              own_verdict: "UNKNOWN",
              verdict: "UNKNOWN",
              verdict_reason: "NO_ROUTE",
              reasons: ["NO_ROUTE"],
            },
          ],
          return_path: { return_status: "NOT_COMPUTED", return_reason: "NO_ROUTE", branches: [] },
        },
      ],
    };
    const noRouteModel = buildPathDiagramModel(noRouteResult);
    const noRouteUnknown = noRouteModel.nodes.find((n) => n.data.kind === "unknown")!;
    expect(noRouteUnknown.data.title).toBe("?");
    expect(noRouteUnknown.data.reasonText).toContain(explain("NO_ROUTE"));
    const noRouteEdge = noRouteModel.edges.find((e) => e.target === noRouteUnknown.id)!;
    expect(noRouteEdge.source).toBe("hop-b1-1");
    expect(noRouteEdge.data?.dashed).toBe(true);
    expect(noRouteEdge.style?.strokeDasharray).toBe("6 4");

    // 3. AMBIGUOUS_INGRESS (no branches)
    const ambiguousIngress: PathResult = {
      ...PATH_ALLOWED,
      verdict: "UNKNOWN",
      verdict_reason: "AMBIGUOUS_INGRESS",
      plain: false,
      ingress: {
        ingress_mode: "INFERRED",
        ingress_status: "AMBIGUOUS_INGRESS",
        candidates: [
          { node_id: "FW-TANGO-04/physical/main", attachment_id: "a1", segment_id: "SEGMENT-198.51.100.0/26" },
          { node_id: "FW-JULIET-06/physical/main", attachment_id: "a2", segment_id: "SEGMENT-198.51.100.0/26" },
        ],
      },
      branches: [],
    };
    const ambModel = buildPathDiagramModel(ambiguousIngress);
    expect(ambModel.nodes.filter((n) => n.data.kind === "hop")).toHaveLength(0);
    const ambUnknown = ambModel.nodes.find((n) => n.data.kind === "unknown")!;
    expect(ambUnknown.data.title).toBe("?");
    expect(ambUnknown.data.reasonText).toContain(explain("AMBIGUOUS_INGRESS"));
    const ambEdge = ambModel.edges.find((e) => e.target === ambUnknown.id)!;
    expect(ambEdge.source).toBe("path-source");
    expect(ambEdge.data?.dashed).toBe(true);

    // 4. UNKNOWN_HOP (in PATH_PARTIAL)
    const partialModel = buildPathDiagramModel(PATH_PARTIAL);
    const unknownNodes = partialModel.nodes.filter((n) => n.data.kind === "unknown");
    expect(unknownNodes).toHaveLength(2);
    expect(unknownNodes[0].data.reasonText).toContain(explain("UNKNOWN_HOP"));
    expect(unknownNodes[0].data.reasonText).toContain(explain("CP_ACCESS_NAT_ORDER_UNKNOWN"));
    expect(unknownNodes[1].data.reasonText).toContain(explain("STALE_OR_OUT_OF_SYNC_POLICY"));
    for (const u of unknownNodes) {
      const edge = partialModel.edges.find((e) => e.target === u.id)!;
      expect(edge.data?.dashed).toBe(true);
      expect(edge.style?.strokeDasharray).toBe("6 4");
    }
  });

  it("return path: omitted by default and added behind the showReturn toggle when the result carries it", () => {
    const withoutReturn = buildPathDiagramModel(PATH_ALLOWED, { showReturn: false });
    expect(withoutReturn.hasReturnPath).toBe(true);
    expect(withoutReturn.nodes.filter((n) => n.data.kind === "return-hop")).toHaveLength(0);

    const withReturn = buildPathDiagramModel(PATH_ALLOWED, { showReturn: true });
    const returnHops = withReturn.nodes.filter((n) => n.data.kind === "return-hop");
    const returnEdges = withReturn.edges.filter((e) => e.data?.direction === "return");
    expect(returnHops.map((h) => h.data.nodeId)).toEqual([
      "FW-BRAVO-02/vsys1/vr-main",
      "FW-TANGO-04/physical/main",
    ]);
    expect(returnEdges.length).toBeGreaterThanOrEqual(3);

    const deniedModel = buildPathDiagramModel(PATH_DENIED, { showReturn: true });
    expect(deniedModel.hasReturnPath).toBe(false);
    expect(deniedModel.nodes.filter((n) => n.data.kind === "return-hop")).toHaveLength(0);
  });
});

describe("the neighbour cap", () => {
  it("adds directly attached non-path segments greyed out, caps at 40 items, and states 'N more not shown' past the cap", () => {
    const extraCount = 52;
    const extraSegments = Array.from({ length: extraCount }, (_, i) => ({
      segment_id: `ts-extra-${i + 1}`,
      segment_kind: "LAN",
      network: `192.0.2.${i + 1}/32`,
    }));
    const extraAttachments = Array.from({ length: extraCount }, (_, i) => ({
      attachment_id: `ta-extra-${i + 1}`,
      node_id: i % 2 === 0 ? "FW-TANGO-04/physical/main" : "FW-BRAVO-02/vsys1/vr-main",
      segment_id: `ts-extra-${i + 1}`,
      interface_name: `eth-extra-${i + 1}`,
      address: `192.0.2.${i + 1}/32`,
      zone: null,
    }));
    const unrelatedAttachment = {
      attachment_id: "ta-unrelated",
      node_id: "FW-ROMEO-01/physical/main",
      segment_id: "ts-unrelated",
      interface_name: "eth99",
      address: "192.0.2.250/32",
      zone: null,
    };

    const bigTopology: TopologySnapshotView = {
      ...PATH_TOPOLOGY_PREVIEW,
      segments: [...PATH_TOPOLOGY_PREVIEW.segments, ...extraSegments],
      attachments: [
        // Includes path segments (which must not be duplicated as neighbours) + 52 attached extras + 1 unrelated node
        ...PATH_TOPOLOGY_PREVIEW.attachments.filter((a) =>
          ["SEGMENT-198.51.100.0/26", "SEGMENT-192.0.2.0/30", "SEGMENT-203.0.113.0/26"].includes(a.segment_id),
        ),
        ...extraAttachments,
        unrelatedAttachment,
      ],
    };

    const model = buildPathDiagramModel(PATH_ALLOWED, {
      showNeighbours: true,
      topology: bigTopology,
    });

    const neighbourNodes = model.nodes.filter((n) => n.data.kind === "neighbour-segment");
    expect(NEIGHBOUR_CAP).toBe(40);
    expect(neighbourNodes).toHaveLength(40);
    expect(model.totalNeighbourCount).toBe(52);
    expect(model.shownNeighbourCount).toBe(40);
    expect(model.hiddenNeighbourCount).toBe(12);
    expect(model.neighbourNotice).toBe("12 more not shown");

    // Render in the component and toggle "Show neighbours"
    render(
      <ThemeProvider theme={m3Theme}>
        <PathDiagram result={PATH_ALLOWED} topology={bigTopology} />
      </ThemeProvider>,
    );
    expect(screen.queryByTestId("diagram-neighbours-truncated")).toBeNull();
    fireEvent.click(screen.getByTestId("diagram-toggle-neighbours"));
    expect(screen.getByTestId("diagram-neighbours-truncated")).toHaveTextContent("12 more not shown");
    expect(screen.getAllByTestId(/^diagram-neighbour-\d+$/)).toHaveLength(40);
  });

  it("fetches GET /api/v2/topology when Show neighbours is toggled on without a preloaded snapshot", async () => {
    getTopology.mockResolvedValue(PATH_TOPOLOGY_PREVIEW);
    render(
      <ThemeProvider theme={m3Theme}>
        <PathDiagram result={PATH_ALLOWED} />
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByTestId("diagram-toggle-neighbours"));
    await waitFor(() => expect(getTopology).toHaveBeenCalledTimes(1));
    expect(await screen.findByTestId("diagram-neighbour-0")).toHaveTextContent("192.0.2.64/26");
  });
});

describe("neighbours come only from the path's own topology snapshot", () => {
  const newer: TopologySnapshotView = { ...PATH_TOPOLOGY_PREVIEW, snapshot_id: "SNAPSHOT-NEWER" };

  it("draws no attachment from a topology whose snapshot id differs from the path's, and says why", () => {
    const matched = buildPathDiagramModel(PATH_ALLOWED, { showNeighbours: true, topology: PATH_TOPOLOGY_PREVIEW });
    expect(matched.nodes.some((n) => n.data.kind === "neighbour-segment")).toBe(true);
    expect(matched.neighbourSnapshotNotice).toBeNull();

    for (const topology of [newer, { ...PATH_TOPOLOGY_PREVIEW, snapshot_id: undefined }]) {
      const model = buildPathDiagramModel(PATH_ALLOWED, { showNeighbours: true, topology });
      const fromTopology = model.nodes.filter(
        (n) => n.data.kind === "neighbour-segment" && topology.attachments.some((a) => a.segment_id === n.data.segmentId),
      );
      expect(fromTopology).toHaveLength(0);
      expect(model.neighbourSnapshotNotice).toBe(NEIGHBOUR_SNAPSHOT_MISMATCH);
    }
    // A path result without a snapshot id cannot be matched either.
    const unknown = buildPathDiagramModel({ ...PATH_ALLOWED, snapshot_id: "" }, { showNeighbours: true, topology: PATH_TOPOLOGY_PREVIEW });
    expect(unknown.neighbourSnapshotNotice).toBe(NEIGHBOUR_SNAPSHOT_MISMATCH);
    // Neighbours off: no notice.
    expect(buildPathDiagramModel(PATH_ALLOWED, { topology: newer }).neighbourSnapshotNotice).toBeNull();
  });

  it("a fetched topology from a newer snapshot shows the notice in place of neighbours", async () => {
    getTopology.mockResolvedValue(newer);
    render(
      <ThemeProvider theme={m3Theme}>
        <PathDiagram result={PATH_ALLOWED} />
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByTestId("diagram-toggle-neighbours"));
    expect(await screen.findByTestId("diagram-neighbours-withheld")).toHaveTextContent(NEIGHBOUR_SNAPSHOT_MISMATCH);
    expect(screen.queryByTestId("diagram-neighbour-0")).toBeNull();
  });

  it("a failed topology read says neighbours are not shown instead of silently drawing none", async () => {
    getTopology.mockRejectedValue(new Error("unavailable"));
    render(
      <ThemeProvider theme={m3Theme}>
        <PathDiagram result={PATH_ALLOWED} />
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByTestId("diagram-toggle-neighbours"));
    expect(await screen.findByTestId("diagram-neighbours-withheld")).toHaveTextContent(NEIGHBOUR_TOPOLOGY_UNAVAILABLE);
    expect(screen.queryByTestId("diagram-neighbour-0")).toBeNull();
  });
});

describe("click-to-row between the diagram and the hop-by-hop list", () => {
  it("defaults to the hop-by-hop list, switches to Diagram on toggle, and clicking a hop scrolls to and highlights its row", () => {
    const scrollSpy = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true,
      writable: true,
      value: scrollSpy,
    });

    render(
      <ThemeProvider theme={m3Theme}>
        <PathResultView result={PATH_ALLOWED} verification={VERIFIED} now={NOW} />
      </ThemeProvider>,
    );

    // Default stays the current hop-by-hop list
    expect(screen.getByTestId("path-view-list")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("path-view-diagram")).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByTestId("path-diagram")).toBeNull();

    // Switch to the Diagram view
    fireEvent.click(screen.getByTestId("path-view-diagram"));
    expect(screen.getByTestId("path-view-diagram")).toHaveAttribute("aria-pressed", "true");
    const diagram = screen.getByTestId("path-diagram");
    expect(diagram).toBeInTheDocument();

    // Verify hop node shows masked name, interfaces, and A6 device badge
    const diagramHop2 = within(diagram).getByTestId("diagram-hop-b1-2");
    expect(diagramHop2).toHaveTextContent("FW-BRAVO-02/vsys1/vr-main");
    expect(diagramHop2).toHaveTextContent("ethernet1/1 (ZONE-TRANSIT) → ethernet1/2 (ZONE-DMZ)");
    expect(diagramHop2).toHaveTextContent("Device MISMATCH");

    // Click hop 2 in the diagram -> scrolls to and highlights hop 2's row in the hop-by-hop list
    const hopRow1 = screen.getByTestId("path-hop-1");
    const hopRow2 = screen.getByTestId("path-hop-2");
    expect(hopRow2).not.toHaveAttribute("data-highlighted");

    fireEvent.click(diagramHop2);
    expect(scrollSpy).toHaveBeenCalledTimes(1);
    expect(hopRow2).toHaveAttribute("data-highlighted", "true");
    expect(hopRow1).not.toHaveAttribute("data-highlighted");

    // Clicking hop 1 moves the highlight to hop 1
    fireEvent.click(within(diagram).getByTestId("diagram-hop-b1-1"));
    expect(scrollSpy).toHaveBeenCalledTimes(2);
    expect(hopRow1).toHaveAttribute("data-highlighted", "true");
    expect(hopRow2).not.toHaveAttribute("data-highlighted");
  });
});

describe("masked values only (no raw IPs in the rendered output)", () => {
  const RAW_PRIVATE_IP = /\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b/;

  it("renders the Path preview with the diagram and only masked/documentation addresses and pseudonyms", () => {
    const { container } = render(
      <ThemeProvider theme={m3Theme}>
        <PathAnalysisPreview />
      </ThemeProvider>,
    );
    expect(screen.getAllByTestId("path-diagram").length).toBeGreaterThanOrEqual(1);
    expect(container.textContent).toContain("FW-TANGO-04/physical/main");
    expect(container.textContent).toContain("FW-BRAVO-02/vsys1/vr-main");
    expect(container.textContent).toContain("192.0.2.0/30");
    expect(container.textContent).not.toMatch(RAW_PRIVATE_IP);
  });

  it("renders values exactly as the API returns them (server projection is the masking authority)", () => {
    const rawViewResult: PathResult = {
      ...PATH_ALLOWED,
      query: { ...PATH_ALLOWED.query, src: "10.10.1.5", dst: "192.168.20.99" },
    };

    const { container } = render(
      <ThemeProvider theme={m3Theme}>
        <PathDiagram result={rawViewResult} />
      </ThemeProvider>,
    );
    expect(container.textContent).toContain("10.10.1.5");
    expect(container.textContent).toContain("192.168.20.99");
    expect(container.textContent).not.toContain("[masked]");
  });
});
