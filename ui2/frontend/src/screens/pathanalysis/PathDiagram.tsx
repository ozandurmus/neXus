import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Background,
  Controls,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import {
  getTopology,
  type PathBranch,
  type PathEndpoint,
  type PathHop,
  type PathResult,
  type PathValidation,
  type TopologySnapshotView,
} from "../../auth/adminApi";
import { VendorBadge } from "../../shell/States";
import { StatusPill } from "../../shell/StatusPill";
import { MONO, m3 } from "../../theme/m3Theme";
import { LABEL_UPPER, RADIUS, TYPE } from "../../theme/tokens";
import { HopDeviceBadge } from "./PathValidationPanel";
import { hopValidation, verifiedBranchId, type ValidationHopView } from "./pathValidationWords";
import { explain, verdictTone, verdictWord } from "./pathWords";
import type { PathVerification } from "./usePathValidation";

/** Cap on extra attached neighbour segments shown when "Show neighbours" is enabled. */
export const NEIGHBOUR_CAP = 40;

/** Same verdict lattice colours `PathResultView.tsx` uses for hop cards. */
export const HOP_VERDICT_SIDE: Readonly<Record<string, string>> = {
  ALLOWED: m3.success,
  DENIED: m3.error,
  UNKNOWN: m3.neutralInk,
  CONDITIONAL: m3.attention,
};

const COL_STEP = 230;
const LANE_STEP = 190;

/** Resolves a segment's masked network prefix from the held topology or the segment identifier. */
export function segmentPrefix(
  segmentId: string | null | undefined,
  topology?: TopologySnapshotView | null,
  fallback?: string | null,
): string {
  if (segmentId && topology?.segments) {
    const found = topology.segments.find((s) => s.segment_id === segmentId);
    if (found?.network) return found.network;
  }
  if (segmentId) {
    const stripped = segmentId.startsWith("SEGMENT-") ? segmentId.slice("SEGMENT-".length) : segmentId;
    return stripped;
  }
  if (fallback) return fallback;
  return "segment";
}

export type PathDiagramNodeKind =
  | "source"
  | "destination"
  | "hop"
  | "segment"
  | "unknown"
  | "return-hop"
  | "return-segment"
  | "neighbour-segment";

export interface PathDiagramNodeData extends Record<string, unknown> {
  readonly kind: PathDiagramNodeKind;
  readonly title: string;
  readonly subtitle?: string | null;
  readonly branchId?: string;
  readonly hopIndex?: number;
  readonly nodeId?: string;
  readonly vendor?: string;
  readonly verdict?: string;
  readonly ownVerdict?: string;
  readonly verdictColor?: string;
  readonly blocking?: boolean;
  readonly ingressInterface?: string | null;
  readonly ingressZone?: string | null;
  readonly egressInterface?: string | null;
  readonly egressZone?: string | null;
  readonly segmentId?: string;
  readonly prefix?: string;
  readonly reasonCode?: string;
  readonly reasonText?: string;
  readonly reasons?: readonly string[];
  readonly deviceView?: ValidationHopView;
  readonly neighbour?: boolean;
  readonly direction?: "forward" | "return" | "neighbour";
  readonly label: ReactNode;
}

export interface PathDiagramEdgeData extends Record<string, unknown> {
  readonly branchId?: string;
  readonly direction: "forward" | "return" | "neighbour";
  readonly dashed: boolean;
  readonly verdict?: string;
}

export interface PathDiagramOptions {
  readonly validation?: PathValidation | null;
  readonly verifiedBranchId?: string;
  readonly showReturn?: boolean;
  readonly showNeighbours?: boolean;
  readonly topology?: TopologySnapshotView | null;
  readonly neighbourCap?: number;
  readonly highlightedHop?: { readonly branchId: string; readonly hopIndex: number } | null;
  readonly onSelectHop?: (branchId: string, hopIndex: number) => void;
}

export interface PathDiagramModel {
  readonly nodes: Node<PathDiagramNodeData>[];
  readonly edges: Edge<PathDiagramEdgeData>[];
  readonly hasReturnPath: boolean;
  readonly totalNeighbourCount: number;
  readonly shownNeighbourCount: number;
  readonly hiddenNeighbourCount: number;
  readonly neighbourNotice: string | null;
  /**
   * Set when neighbours were asked for but the held topology is not the snapshot the path was computed on (snapshot ids
   * differ, or either is missing): no attachment from it is drawn, so two collection passes are never mixed.
   */
  readonly neighbourSnapshotNotice: string | null;
}

/** Shown instead of neighbours when the held topology is not the path's own snapshot. */
export const NEIGHBOUR_SNAPSHOT_MISMATCH =
  "Neighbours not shown: the current topology snapshot differs from the one this path was computed on.";
/** Shown instead of neighbours when the topology could not be read. */
export const NEIGHBOUR_TOPOLOGY_UNAVAILABLE = "Neighbours not shown: the topology could not be read.";

/** The held topology only when it is the snapshot this path was computed on; otherwise nothing from it is used. */
export function sameSnapshotTopology(
  result: PathResult,
  topology: TopologySnapshotView | null | undefined,
): TopologySnapshotView | null {
  if (!topology || !result.snapshot_id || !topology.snapshot_id) return null;
  return topology.snapshot_id === result.snapshot_id ? topology : null;
}

function EndpointLabel({ e }: { readonly e: PathEndpoint }) {
  if (!e.interface_name && !e.zone && !e.segment_id) {
    return <Box component="span" sx={{ color: m3.onSurfaceVar }}>none</Box>;
  }
  return (
    <>
      {(e.interface_name ?? "interface UNKNOWN")}
      {e.zone ? ` (${e.zone})` : ""}
    </>
  );
}

const UNKNOWN_STOP_REASONS = new Set([
  "EXITS_KNOWN_TOPOLOGY",
  "NO_ROUTE",
  "AMBIGUOUS_INGRESS",
  "UNKNOWN_HOP",
  "INGRESS_NOT_FOUND",
  "INGRESS_HINT_NOT_FOUND",
  "INGRESS_INTERFACE_NOT_FOUND",
  "ROUTING_LOOP",
  "HOP_LIMIT",
  "ANALYSIS_BUDGET",
  "NEXT_HOP_AMBIGUOUS",
  "NEXT_HOP_UNREACHABLE",
  "NEXT_HOP_KIND_UNKNOWN",
  "NEXT_VR_UNRESOLVED",
  "CONNECTED_SEGMENT_UNKNOWN",
  "ENGINE_DATA_ERROR",
]);

function isBranchUnknownStop(branch: PathBranch, result: PathResult): boolean {
  if (branch.verdict === "UNKNOWN" || branch.verdict === "ALLOWED_TO_EDGE") return true;
  if (UNKNOWN_STOP_REASONS.has(branch.verdict_reason) || UNKNOWN_STOP_REASONS.has(branch.termination)) return true;
  if (branch.termination !== "DELIVERED" && branch.termination !== "BLOCKED") return true;
  if (branch.hops.some((h) => h.verdict === "UNKNOWN")) return true;
  if (result.verdict === "UNKNOWN" && result.branches.length === 1) return true;
  return false;
}

function branchUnknownReasons(branch: PathBranch, result: PathResult): string[] {
  const codes: string[] = [];
  const push = (code: string | null | undefined) => {
    if (code && code !== "DELIVERED" && code !== "BLOCKED" && code !== "ALL_HOPS_ALLOWED" && code !== "FIRST_MATCHING_RULE" && !codes.includes(code)) {
      codes.push(code);
    }
  };
  if (branch.termination !== "DELIVERED" && branch.termination !== "BLOCKED") {
    push(branch.termination);
  }
  for (const hop of branch.hops) {
    if (hop.verdict === "UNKNOWN") {
      push(hop.verdict_reason);
    }
  }
  push(branch.verdict_reason);
  if (result.verdict === "UNKNOWN" || result.verdict === "ALLOWED_TO_EDGE") {
    push(result.verdict_reason);
  }
  if (codes.length === 0) push("UNKNOWN_HOP");
  return codes;
}

function makeEdge(
  id: string,
  source: string,
  target: string,
  options: {
    readonly branchId?: string;
    readonly direction: "forward" | "return" | "neighbour";
    readonly dashed: boolean;
    readonly color: string;
    readonly verdict?: string;
    readonly label?: string;
  },
): Edge<PathDiagramEdgeData> {
  return {
    id,
    source,
    target,
    label: options.label,
    data: {
      branchId: options.branchId,
      direction: options.direction,
      dashed: options.dashed,
      verdict: options.verdict,
    },
    style: {
      stroke: options.color,
      strokeWidth: options.direction === "neighbour" ? 1.5 : 2,
      ...(options.dashed ? { strokeDasharray: "6 4" } : {}),
    },
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: options.color,
    },
  };
}

/**
 * Deterministic left-to-right node and edge model for `@xyflow/react`:
 * - Source on the left, each branch on its own horizontal lane (ECMP parallel lanes), segments as network pills
 *   showing their masked prefix, hops with masked node name, verdict lattice colour, ingress/egress interfaces and
 *   optional stage-A6 device badge, and Destination (or a dashed edge to a "?" node on UNKNOWN stops) on the right.
 * - Optional return path lane(s) when `showReturn` is true.
 * - Optional attached neighbour segments (greyed out, capped at `NEIGHBOUR_CAP` = 40 with "N more not shown").
 */
export function buildPathDiagramModel(
  result: PathResult,
  options: PathDiagramOptions = {},
): PathDiagramModel {
  const {
    validation = null,
    verifiedBranchId: explicitVerifiedBranchId,
    showReturn = false,
    showNeighbours = false,
    topology: heldTopology = null,
    neighbourCap = NEIGHBOUR_CAP,
    highlightedHop = null,
    onSelectHop,
  } = options;
  // Segment prefixes and neighbours come only from the path's own topology snapshot.
  const topology = sameSnapshotTopology(result, heldTopology);

  const vBranchId = verifiedBranchId(validation, explicitVerifiedBranchId, result);
  const nodes: Node<PathDiagramNodeData>[] = [];
  const edges: Edge<PathDiagramEdgeData>[] = [];

  const hasReturnPath = result.branches.some((b) =>
    b.return_path.branches.some((rb) => rb.hops.length > 0),
  );

  const forwardLaneCount = Math.max(1, result.branches.length);
  const centerY = ((forwardLaneCount - 1) * LANE_STEP) / 2;

  const srcHost = result.query.src;
  const dstHost = result.query.dst;
  const serviceLabel = `${result.query.protocol.toUpperCase()}/${result.query.port}`;

  const sourceNodeId = "path-source";
  nodes.push({
    id: sourceNodeId,
    position: { x: 0, y: centerY },
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    initialWidth: 160,
    initialHeight: 72,
    data: {
      kind: "source",
      title: srcHost,
      subtitle: `Source · ${serviceLabel}`,
      direction: "forward",
      label: (
        <Box data-testid="diagram-source" sx={{ p: 1, borderRadius: `${RADIUS.card}px`, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, textAlign: "left" }}>
          <Typography component="div" sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar, fontSize: 10 }}>Source</Typography>
          <Typography component="div" sx={{ fontFamily: MONO, fontWeight: 700, fontSize: 13, color: m3.onSurface }}>{srcHost}</Typography>
          <Typography component="div" variant="caption" sx={{ color: m3.onSurfaceVar }}>{serviceLabel}</Typography>
        </Box>
      ),
    },
  });

  // Track path nodes and segments for the optional neighbour pass
  const pathUnitFirstDiagramNode = new Map<string, { readonly diagramNodeId: string; readonly x: number; readonly y: number; readonly nodeId: string }>();
  const pathSegmentIds = new Set<string>();

  if (result.branches.length === 0) {
    const reasonCode = result.verdict_reason || result.ingress.ingress_status || "UNKNOWN";
    const reasonText = explain(reasonCode);
    const candidates = result.ingress.candidates.map((c) => c.node_id).filter(Boolean);
    for (const c of result.ingress.candidates) {
      if (c.segment_id) pathSegmentIds.add(c.segment_id);
    }
    const unknownId = "path-unknown-ingress";
    nodes.push({
      id: unknownId,
      position: { x: COL_STEP, y: centerY },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      initialWidth: 220,
      initialHeight: 96,
      data: {
        kind: "unknown",
        title: "?",
        reasonCode,
        reasonText,
        reasons: [reasonCode],
        direction: "forward",
        label: (
          <Box data-testid="diagram-unknown-ingress" data-reason-code={reasonCode}
            sx={{ p: 1.25, borderRadius: `${RADIUS.card}px`, border: `2px dashed ${m3.neutralInk}`, bgcolor: m3.scLowest, textAlign: "left" }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Box component="span" sx={{ width: 24, height: 24, borderRadius: "12px", display: "grid", placeItems: "center", bgcolor: m3.secondaryContainer, color: m3.onSecondaryContainer, fontWeight: 700 }}>?</Box>
              <Typography component="span" sx={{ fontFamily: MONO, fontSize: 12, fontWeight: 600 }}>{reasonCode}</Typography>
            </Stack>
            <Typography variant="body2" sx={{ mt: 0.5, color: m3.onSurface }}>{reasonText}</Typography>
            {candidates.length > 0 && (
              <Typography variant="caption" sx={{ display: "block", mt: 0.5, fontFamily: MONO, color: m3.onSurfaceVar }}>
                Candidates: {candidates.join(", ")}
              </Typography>
            )}
          </Box>
        ),
      },
    });
    edges.push(
      makeEdge("edge-source-unknown-ingress", sourceNodeId, unknownId, {
        direction: "forward",
        dashed: true,
        color: m3.neutralInk,
        verdict: "UNKNOWN",
      }),
    );
  }

  let maxDestinationCol = 1;
  const pendingDestinationLinks: {
    readonly branch: PathBranch;
    readonly fromNodeId: string;
    readonly col: number;
    readonly blocked: boolean;
  }[] = [];

  result.branches.forEach((branch, branchIdx) => {
    const laneY = branchIdx * LANE_STEP;
    let col = 1;
    let prevNodeId = sourceNodeId;
    let afterUnknown = false;

    const addSegmentPill = (segId: string, suffix: string, dashed: boolean) => {
      pathSegmentIds.add(segId);
      const prefix = segmentPrefix(segId, topology);
      const pillNodeId = `seg-${branch.branch_id}-${suffix}`;
      nodes.push({
        id: pillNodeId,
        position: { x: col * COL_STEP, y: laneY + 28 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        initialWidth: 140,
        initialHeight: 36,
        data: {
          kind: "segment",
          title: prefix,
          segmentId: segId,
          prefix,
          branchId: branch.branch_id,
          direction: "forward",
          label: (
            <Box data-testid={`diagram-segment-${branch.branch_id}-${suffix}`} data-segment-id={segId}
              sx={{ px: 1.25, py: 0.5, borderRadius: `${RADIUS.pill}px`, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.secondaryContainer, color: m3.onSecondaryContainer, fontFamily: MONO, fontSize: 12, fontWeight: 600, display: "inline-block" }}>
              {prefix}
            </Box>
          ),
        },
      });
      edges.push(
        makeEdge(`edge-${prevNodeId}-${pillNodeId}`, prevNodeId, pillNodeId, {
          branchId: branch.branch_id,
          direction: "forward",
          dashed,
          color: dashed ? m3.neutralInk : m3.primary,
        }),
      );
      prevNodeId = pillNodeId;
      col += 1;
    };

    branch.hops.forEach((hop: PathHop, idx: number) => {
      const prevHop = idx > 0 ? branch.hops[idx - 1] : null;
      const segBefore = idx === 0
        ? (hop.ingress.segment_id ?? result.ingress.candidates.find((c) => c.node_id === hop.node_id)?.segment_id ?? null)
        : (prevHop?.egress.segment_id ?? hop.ingress.segment_id ?? null);

      if (segBefore) {
        addSegmentPill(segBefore, `h${hop.hop_index}-in`, afterUnknown);
      }

      if (hop.ingress.segment_id) pathSegmentIds.add(hop.ingress.segment_id);
      if (hop.egress.segment_id) pathSegmentIds.add(hop.egress.segment_id);

      const hopNodeId = `hop-${branch.branch_id}-${hop.hop_index}`;
      const blocking = branch.blocking_hop_index === hop.hop_index;
      const verdictColor = HOP_VERDICT_SIDE[hop.verdict] ?? m3.outline;
      const deviceView = hopValidation(validation, vBranchId, branch, hop);
      const isHighlighted = highlightedHop?.branchId === branch.branch_id && highlightedHop?.hopIndex === hop.hop_index;
      const safeNodeName = hop.node_id;

      if (!pathUnitFirstDiagramNode.has(hop.node_id)) {
        pathUnitFirstDiagramNode.set(hop.node_id, {
          diagramNodeId: hopNodeId,
          x: col * COL_STEP,
          y: laneY,
          nodeId: hop.node_id,
        });
      }

      nodes.push({
        id: hopNodeId,
        position: { x: col * COL_STEP, y: laneY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        initialWidth: 210,
        initialHeight: 108,
        data: {
          kind: "hop",
          title: safeNodeName,
          subtitle: `${hop.ingress.interface_name ?? "none"} → ${hop.egress.interface_name ?? "none"}`,
          branchId: branch.branch_id,
          hopIndex: hop.hop_index,
          nodeId: hop.node_id,
          vendor: hop.vendor,
          verdict: hop.verdict,
          ownVerdict: hop.own_verdict,
          verdictColor,
          blocking,
          ingressInterface: hop.ingress.interface_name,
          ingressZone: hop.ingress.zone,
          egressInterface: hop.egress.interface_name,
          egressZone: hop.egress.zone,
          deviceView,
          direction: "forward",
          label: (
            <Box
              data-testid={`diagram-hop-${branch.branch_id}-${hop.hop_index}`}
              data-hop-verdict={hop.verdict}
              data-verdict-color={verdictColor}
              data-branch-id={branch.branch_id}
              data-hop-index={hop.hop_index}
              data-highlighted={isHighlighted ? "true" : undefined}
              onClick={(e) => {
                e.stopPropagation();
                onSelectHop?.(branch.branch_id, hop.hop_index);
              }}
              sx={{
                p: 1.25,
                borderRadius: `${RADIUS.card}px`,
                border: `1px solid ${blocking ? m3.error : isHighlighted ? m3.primary : m3.outlineVar}`,
                borderLeft: `4px solid ${verdictColor}`,
                bgcolor: isHighlighted ? m3.primaryContainer : m3.scLowest,
                cursor: "pointer",
                textAlign: "left",
                display: "flex",
                flexDirection: "column",
                gap: 0.5,
              }}
            >
              <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap>
                <VendorBadge vendor={hop.vendor} />
                <Typography component="span" sx={{ fontFamily: MONO, fontWeight: 700, fontSize: 12, color: m3.onSurface, overflowWrap: "anywhere" }}>
                  {safeNodeName}
                </Typography>
              </Stack>
              <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap>
                <StatusPill
                  tone={verdictTone(hop.verdict)}
                  word={verdictWord(hop.verdict)}
                  dense
                  fact="diagram-hop-verdict"
                  suffix={hop.verdict === "CONDITIONAL" ? `own: ${verdictWord(hop.own_verdict)}` : blocking ? "blocking hop" : null}
                />
                <HopDeviceBadge view={deviceView} />
              </Stack>
              <Typography variant="caption" sx={{ fontFamily: MONO, color: m3.onSurfaceVar, display: "block" }}>
                <EndpointLabel e={hop.ingress} /> → <EndpointLabel e={hop.egress} />
              </Typography>
            </Box>
          ),
        },
      });

      edges.push(
        makeEdge(`edge-${prevNodeId}-${hopNodeId}`, prevNodeId, hopNodeId, {
          branchId: branch.branch_id,
          direction: "forward",
          dashed: afterUnknown,
          color: hop.verdict === "DENIED" ? m3.error : afterUnknown || hop.verdict === "UNKNOWN" ? m3.neutralInk : m3.primary,
          verdict: hop.verdict,
        }),
      );
      prevNodeId = hopNodeId;
      col += 1;
      if (hop.verdict === "UNKNOWN") {
        afterUnknown = true;
      }
    });

    const lastHop = branch.hops[branch.hops.length - 1];
    const unknownStop = isBranchUnknownStop(branch, result);

    if (unknownStop) {
      if (lastHop?.egress.segment_id && branch.termination === "EXITS_KNOWN_TOPOLOGY") {
        addSegmentPill(lastHop.egress.segment_id, "egress", true);
      }
      const codes = branchUnknownReasons(branch, result);
      const primaryCode = codes[0] ?? "UNKNOWN_HOP";
      const explanations = codes.map((c) => explain(c));
      const reasonText = [...new Set(explanations)].join(" ");
      const unknownNodeId = `path-unknown-${branch.branch_id}`;
      nodes.push({
        id: unknownNodeId,
        position: { x: col * COL_STEP, y: laneY },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        initialWidth: 220,
        initialHeight: 96,
        data: {
          kind: "unknown",
          title: "?",
          branchId: branch.branch_id,
          reasonCode: primaryCode,
          reasonText,
          reasons: codes,
          direction: "forward",
          label: (
            <Box
              data-testid={`diagram-unknown-${branch.branch_id}`}
              data-reason-code={primaryCode}
              sx={{ p: 1.25, borderRadius: `${RADIUS.card}px`, border: `2px dashed ${m3.neutralInk}`, bgcolor: m3.scLowest, textAlign: "left" }}
            >
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Box component="span" sx={{ width: 24, height: 24, borderRadius: "12px", display: "grid", placeItems: "center", bgcolor: m3.secondaryContainer, color: m3.onSecondaryContainer, fontWeight: 700 }}>?</Box>
                <Typography component="span" sx={{ fontFamily: MONO, fontSize: 12, fontWeight: 600 }}>{codes.join(" · ")}</Typography>
              </Stack>
              <Typography variant="body2" sx={{ mt: 0.5, color: m3.onSurface }}>{reasonText}</Typography>
            </Box>
          ),
        },
      });
      edges.push(
        makeEdge(`edge-${prevNodeId}-${unknownNodeId}`, prevNodeId, unknownNodeId, {
          branchId: branch.branch_id,
          direction: "forward",
          dashed: true,
          color: m3.neutralInk,
          verdict: "UNKNOWN",
        }),
      );
      maxDestinationCol = Math.max(maxDestinationCol, col + 1);
    } else {
      if (lastHop?.egress.segment_id && branch.termination === "DELIVERED") {
        addSegmentPill(lastHop.egress.segment_id, "egress", false);
      }
      const blocked = branch.verdict === "DENIED" || branch.termination === "BLOCKED";
      pendingDestinationLinks.push({
        branch,
        fromNodeId: prevNodeId,
        col,
        blocked,
      });
      maxDestinationCol = Math.max(maxDestinationCol, col);
    }
  });

  const destinationNodeId = "path-destination";
  if (pendingDestinationLinks.length > 0 || showReturn) {
    nodes.push({
      id: destinationNodeId,
      position: { x: maxDestinationCol * COL_STEP, y: centerY },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      initialWidth: 160,
      initialHeight: 72,
      data: {
        kind: "destination",
        title: dstHost,
        subtitle: `Destination · ${serviceLabel}`,
        direction: "forward",
        label: (
          <Box data-testid="diagram-destination" sx={{ p: 1, borderRadius: `${RADIUS.card}px`, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLowest, textAlign: "left" }}>
            <Typography component="div" sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar, fontSize: 10 }}>Destination</Typography>
            <Typography component="div" sx={{ fontFamily: MONO, fontWeight: 700, fontSize: 13, color: m3.onSurface }}>{dstHost}</Typography>
            <Typography component="div" variant="caption" sx={{ color: m3.onSurfaceVar }}>{serviceLabel}</Typography>
          </Box>
        ),
      },
    });
    for (const link of pendingDestinationLinks) {
      edges.push(
        makeEdge(`edge-${link.fromNodeId}-${destinationNodeId}`, link.fromNodeId, destinationNodeId, {
          branchId: link.branch.branch_id,
          direction: "forward",
          dashed: link.blocked,
          color: link.blocked ? m3.error : m3.success,
          verdict: link.branch.verdict,
        }),
      );
    }
  }

  // Optional return path lanes
  if (showReturn && hasReturnPath) {
    let returnLaneOffset = 0;
    for (const branch of result.branches) {
      const retStatus = branch.return_path.return_status;
      branch.return_path.branches.forEach((rb, rbIdx) => {
        if (rb.hops.length === 0) return;
        const laneY = (forwardLaneCount + returnLaneOffset) * LANE_STEP;
        returnLaneOffset += 1;
        let prevReturnId = nodes.some((n) => n.id === destinationNodeId) ? destinationNodeId : sourceNodeId;
        const totalCols = Math.max(maxDestinationCol - 1, rb.hops.length * 2);

        rb.hops.forEach((rh, rhIdx) => {
          const hopCol = Math.max(1, totalCols - rhIdx * 2);
          const returnHopId = `return-hop-${branch.branch_id}-${rbIdx}-${rh.hop_index}`;
          const safeName = rh.node_id;
          const asymmetric = retStatus === "ASYMMETRIC";

          nodes.push({
            id: returnHopId,
            position: { x: hopCol * COL_STEP, y: laneY },
            sourcePosition: Position.Left,
            targetPosition: Position.Right,
            initialWidth: 200,
            initialHeight: 88,
            data: {
              kind: "return-hop",
              title: safeName,
              subtitle: `Return #${rh.hop_index} · ${retStatus}`,
              branchId: branch.branch_id,
              hopIndex: rh.hop_index,
              nodeId: rh.node_id,
              egressInterface: rh.egress.interface_name,
              egressZone: rh.egress.zone,
              direction: "return",
              label: (
                <Box
                  data-testid={`diagram-return-hop-${branch.branch_id}-${rh.hop_index}`}
                  sx={{
                    p: 1,
                    borderRadius: `${RADIUS.card}px`,
                    border: `1px dashed ${asymmetric ? m3.attention : m3.outline}`,
                    bgcolor: m3.scLow,
                    textAlign: "left",
                  }}
                >
                  <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap>
                    <StatusPill
                      tone={retStatus === "SYMMETRIC" ? "ok" : asymmetric ? "warn" : "attn"}
                      word={`Return #${rh.hop_index}`}
                      dense
                      fact="diagram-return-hop"
                    />
                    <Typography component="span" sx={{ fontFamily: MONO, fontSize: 12, fontWeight: 600 }}>
                      {safeName}
                    </Typography>
                  </Stack>
                  <Typography variant="caption" sx={{ fontFamily: MONO, color: m3.onSurfaceVar, display: "block", mt: 0.5 }}>
                    out <EndpointLabel e={rh.egress} />
                    {rh.reverse_translated ? " · reverse-translated" : ""}
                  </Typography>
                </Box>
              ),
            },
          });

          edges.push(
            makeEdge(`edge-return-${prevReturnId}-${returnHopId}`, prevReturnId, returnHopId, {
              branchId: branch.branch_id,
              direction: "return",
              dashed: true,
              color: asymmetric ? m3.attention : m3.primary,
              label: rhIdx === 0 ? `return (${retStatus.toLowerCase()})` : undefined,
            }),
          );
          prevReturnId = returnHopId;

          if (rh.egress.segment_id) {
            pathSegmentIds.add(rh.egress.segment_id);
            const rPrefix = segmentPrefix(rh.egress.segment_id, topology);
            const rSegId = `return-seg-${branch.branch_id}-${rbIdx}-${rh.hop_index}`;
            const segCol = Math.max(0.5, hopCol - 1);
            nodes.push({
              id: rSegId,
              position: { x: segCol * COL_STEP, y: laneY + 24 },
              sourcePosition: Position.Left,
              targetPosition: Position.Right,
              initialWidth: 140,
              initialHeight: 36,
              data: {
                kind: "return-segment",
                title: rPrefix,
                segmentId: rh.egress.segment_id,
                prefix: rPrefix,
                branchId: branch.branch_id,
                direction: "return",
                label: (
                  <Box
                    data-testid={`diagram-return-segment-${branch.branch_id}-${rh.hop_index}`}
                    sx={{ px: 1, py: 0.25, borderRadius: `${RADIUS.pill}px`, border: `1px dashed ${m3.outline}`, bgcolor: m3.scLow, fontFamily: MONO, fontSize: 11 }}
                  >
                    {rPrefix}
                  </Box>
                ),
              },
            });
            edges.push(
              makeEdge(`edge-return-${prevReturnId}-${rSegId}`, prevReturnId, rSegId, {
                branchId: branch.branch_id,
                direction: "return",
                dashed: true,
                color: asymmetric ? m3.attention : m3.primary,
              }),
            );
            prevReturnId = rSegId;
          }
        });

        if (rb.termination === "DELIVERED") {
          edges.push(
            makeEdge(`edge-return-${prevReturnId}-${sourceNodeId}`, prevReturnId, sourceNodeId, {
              branchId: branch.branch_id,
              direction: "return",
              dashed: true,
              color: retStatus === "ASYMMETRIC" ? m3.attention : m3.primary,
            }),
          );
        }
      });
    }
  }

  // Optional directly-attached neighbour segments (greyed out, capped at `neighbourCap`)
  let totalNeighbourCount = 0;
  let shownNeighbourCount = 0;
  let hiddenNeighbourCount = 0;
  let neighbourNotice: string | null = null;
  const neighbourSnapshotNotice = showNeighbours && heldTopology && !topology ? NEIGHBOUR_SNAPSHOT_MISMATCH : null;

  if (showNeighbours) {
    interface NeighbourCandidate {
      readonly key: string;
      readonly nodeId: string;
      readonly hopDiagramNodeId: string;
      readonly hopX: number;
      readonly hopY: number;
      readonly segmentId: string;
      readonly interfaceName: string | null;
      readonly zone: string | null;
      readonly prefix: string;
    }

    const candidates: NeighbourCandidate[] = [];
    const seen = new Set<string>();

    for (const att of topology?.attachments ?? []) {
      const anchor = pathUnitFirstDiagramNode.get(att.node_id);
      if (!anchor || !att.segment_id || pathSegmentIds.has(att.segment_id)) continue;
      const key = `${att.node_id}|${att.segment_id}`;
      if (seen.has(key)) continue;
      seen.add(key);
      candidates.push({
        key,
        nodeId: att.node_id,
        hopDiagramNodeId: anchor.diagramNodeId,
        hopX: anchor.x,
        hopY: anchor.y,
        segmentId: att.segment_id,
        interfaceName: att.interface_name,
        zone: att.zone,
        prefix: segmentPrefix(att.segment_id, topology, att.address),
      });
    }

    for (const cand of result.ingress.candidates) {
      const anchor = pathUnitFirstDiagramNode.get(cand.node_id);
      if (!anchor || !cand.segment_id || pathSegmentIds.has(cand.segment_id)) continue;
      const key = `${cand.node_id}|${cand.segment_id}`;
      if (seen.has(key)) continue;
      seen.add(key);
      candidates.push({
        key,
        nodeId: cand.node_id,
        hopDiagramNodeId: anchor.diagramNodeId,
        hopX: anchor.x,
        hopY: anchor.y,
        segmentId: cand.segment_id,
        interfaceName: null,
        zone: null,
        prefix: segmentPrefix(cand.segment_id, topology),
      });
    }

    totalNeighbourCount = candidates.length;
    const cap = Math.max(0, neighbourCap);
    const shown = candidates.slice(0, cap);
    shownNeighbourCount = shown.length;
    hiddenNeighbourCount = Math.max(0, totalNeighbourCount - shownNeighbourCount);
    if (hiddenNeighbourCount > 0) {
      neighbourNotice = `${hiddenNeighbourCount} more not shown`;
    }

    const perAnchorCount = new Map<string, number>();
    shown.forEach((item, idx) => {
      const localIdx = perAnchorCount.get(item.hopDiagramNodeId) ?? 0;
      perAnchorCount.set(item.hopDiagramNodeId, localIdx + 1);
      const neighbourNodeId = `neighbour-${idx}-${item.segmentId}`;
      const offsetX = (localIdx % 2 === 0 ? -1 : 1) * (40 + Math.floor(localIdx / 2) * 30);
      const offsetY = -90 - Math.floor(localIdx / 2) * 48;
      const ifacePrefix = item.interfaceName ? `${item.interfaceName} · ` : "";

      nodes.push({
        id: neighbourNodeId,
        position: { x: item.hopX + offsetX, y: item.hopY + offsetY },
        sourcePosition: Position.Bottom,
        targetPosition: Position.Top,
        initialWidth: 150,
        initialHeight: 34,
        data: {
          kind: "neighbour-segment",
          title: item.prefix,
          subtitle: item.interfaceName ? item.interfaceName : null,
          nodeId: item.nodeId,
          segmentId: item.segmentId,
          prefix: item.prefix,
          ingressInterface: item.interfaceName,
          ingressZone: item.zone,
          neighbour: true,
          direction: "neighbour",
          label: (
            <Box
              data-testid={`diagram-neighbour-${idx}`}
              data-neighbour="true"
              data-segment-id={item.segmentId}
              sx={{
                px: 1,
                py: 0.25,
                borderRadius: `${RADIUS.pill}px`,
                border: `1px dashed ${m3.outlineVar}`,
                bgcolor: m3.scLow,
                color: m3.onSurfaceVar,
                opacity: 0.65,
                fontFamily: MONO,
                fontSize: 11,
              }}
            >
              {ifacePrefix}{item.prefix}
            </Box>
          ),
        },
      });

      edges.push(
        makeEdge(`edge-neighbour-${item.hopDiagramNodeId}-${neighbourNodeId}`, item.hopDiagramNodeId, neighbourNodeId, {
          direction: "neighbour",
          dashed: true,
          color: m3.outline,
        }),
      );
    });
  }

  return {
    nodes,
    edges,
    hasReturnPath,
    totalNeighbourCount,
    shownNeighbourCount,
    hiddenNeighbourCount,
    neighbourNotice,
    neighbourSnapshotNotice,
  };
}

/**
 * Interactive `@xyflow/react` diagram of the computed path (and optional return path / directly attached neighbour
 * segments). Clicking any hop node notifies `onSelectHop(branchId, hopIndex)` so `PathResultView` scrolls to and
 * highlights that hop's card in the hop-by-hop list.
 */
export function PathDiagram({
  result,
  verification,
  topology: initialTopology = null,
  highlightedHop = null,
  onSelectHop,
}: {
  readonly result: PathResult;
  readonly verification?: PathVerification;
  readonly topology?: TopologySnapshotView | null;
  readonly highlightedHop?: { readonly branchId: string; readonly hopIndex: number } | null;
  readonly onSelectHop?: (branchId: string, hopIndex: number) => void;
}) {
  const [showReturn, setShowReturn] = useState(false);
  const [showNeighbours, setShowNeighbours] = useState(false);
  const [fetchedTopology, setFetchedTopology] = useState<TopologySnapshotView | null>(initialTopology);
  const [topologyUnavailable, setTopologyUnavailable] = useState(false);

  useEffect(() => {
    setFetchedTopology(initialTopology);
    setTopologyUnavailable(false);
  }, [initialTopology, result.snapshot_id]);

  useEffect(() => {
    if (!showNeighbours || fetchedTopology) return;
    let active = true;
    setTopologyUnavailable(false);
    getTopology()
      .then((res) => {
        if (!active) return;
        if (res && Array.isArray(res.attachments)) setFetchedTopology(res);
        else setTopologyUnavailable(true);
      })
      .catch(() => {
        // Fail closed: if topology cannot be read, only the path's own segments are shown, and the panel says so.
        if (active) setTopologyUnavailable(true);
      });
    return () => {
      active = false;
    };
  }, [showNeighbours, fetchedTopology]);

  const model = useMemo(
    () =>
      buildPathDiagramModel(result, {
        validation: verification?.state.validation ?? null,
        verifiedBranchId: verification?.state.branchId,
        showReturn,
        showNeighbours,
        topology: fetchedTopology,
        highlightedHop,
        onSelectHop,
      }),
    [result, verification?.state.validation, verification?.state.branchId, showReturn, showNeighbours, fetchedTopology, highlightedHop, onSelectHop],
  );

  return (
    <Card
      data-testid="path-diagram"
      aria-label="Path topology diagram"
      sx={{
        border: `1px solid ${m3.outlineVar}`,
        boxShadow: "none",
        borderRadius: `${RADIUS.card}px`,
        p: 2,
        bgcolor: m3.scLowest,
      }}
    >
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" flexWrap="wrap" useFlexGap>
          <Box>
            <Typography component="h4" sx={{ ...TYPE.section, m: 0, color: m3.onSurface }}>
              Computed path diagram
            </Typography>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              Only the computed path is drawn (source → hop units and transit segments → destination). Click a hop to scroll to its row below.
            </Typography>
          </Box>
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            {model.hasReturnPath && (
              <FormControlLabel
                control={
                  <Checkbox
                    size="small"
                    checked={showReturn}
                    inputProps={{ "data-testid": "diagram-toggle-return" } as Record<string, unknown>}
                    onChange={(e) => setShowReturn(e.target.checked)}
                  />
                }
                label="Show return path"
              />
            )}
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={showNeighbours}
                  inputProps={{ "data-testid": "diagram-toggle-neighbours" } as Record<string, unknown>}
                  onChange={(e) => setShowNeighbours(e.target.checked)}
                />
              }
              label="Show neighbours"
            />
            {model.neighbourNotice && (
              <Typography variant="body2" data-testid="diagram-neighbours-truncated" sx={{ color: m3.onSurfaceVar, fontWeight: 600 }}>
                {model.neighbourNotice}
              </Typography>
            )}
            {showNeighbours && (model.neighbourSnapshotNotice ?? (topologyUnavailable ? NEIGHBOUR_TOPOLOGY_UNAVAILABLE : null)) && (
              <Typography variant="body2" data-testid="diagram-neighbours-withheld" sx={{ color: m3.onSurfaceVar, fontWeight: 600 }}>
                {model.neighbourSnapshotNotice ?? NEIGHBOUR_TOPOLOGY_UNAVAILABLE}
              </Typography>
            )}
          </Stack>
        </Stack>

        <Box
          aria-label="Path topology canvas"
          sx={{
            height: Math.max(280, result.branches.length * 190 + (showReturn ? 180 : 0)),
            width: "100%",
            border: `1px solid ${m3.outlineVar}`,
            borderRadius: `${RADIUS.card}px`,
            bgcolor: m3.scLow,
          }}
        >
          <ReactFlow
            nodes={model.nodes}
            edges={model.edges}
            onNodeClick={(_, node) => {
              if (node.data?.kind === "hop" && typeof node.data.branchId === "string" && typeof node.data.hopIndex === "number") {
                onSelectHop?.(node.data.branchId, node.data.hopIndex);
              }
            }}
            fitView
            minZoom={0.2}
            nodesDraggable={false}
            nodesConnectable={false}
          >
            <Background color="#d6dfdf" gap={22} />
            <Controls />
          </ReactFlow>
        </Box>
      </Stack>
    </Card>
  );
}
