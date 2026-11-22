import type { ManagementTree, ManagementTreeNode } from "../auth/adminApi";
import type { SourceRead } from "../screens/overview/attentionRules";
import type { BadgeState } from "./estateModel";

/**
 * Pure "Managed through" answer for the Estate unit dossier, read from the management tree.
 *
 * Evidence law: the tree is management-plane discovery/intent, never direct-device runtime truth.
 * An unreadable or missing tree is UNKNOWN (never "not managed"). Labels follow the standard view's
 * masked node label (cluster_member_ref ?? hostname ?? virtual_system), never raw addresses or serials.
 */
export type ManagedThroughState = "MANAGED" | "NOT_UNDER_ANY_NODE" | "UNKNOWN" | "READING";

export interface ManagedThroughResult {
  readonly state: ManagedThroughState;
  readonly badgeState: BadgeState;
  readonly badgeWord: string;
  /** Masked node label of the management node the unit sits under; null unless MANAGED. */
  readonly nodeLabel: string | null;
  readonly headline: string;
  readonly detail: string;
  /** Age source for the evidence stamp: the tree's discovery time; null when the tree was not read. */
  readonly evidenceAt: string | null;
  readonly evidenceSource: string;
}

const BOUNDARY =
  "Management-plane discovery and intent, not device runtime truth.";

function nodeLabelOf(n: ManagementTreeNode): string | null {
  const label = n.cluster_member_ref ?? n.hostname ?? n.virtual_system ?? null;
  return label && label.trim() !== "" ? label : null;
}

/** Nearest ancestor chain for the first node whose device_id matches; null when the unit is absent. */
function ancestorsOf(
  nodes: readonly ManagementTreeNode[],
  deviceId: string,
  trail: readonly ManagementTreeNode[] = [],
): readonly ManagementTreeNode[] | null {
  for (const node of nodes) {
    if (node.device_id === deviceId) return trail;
    const found = ancestorsOf(node.children ?? [], deviceId, [...trail, node]);
    if (found) return found;
  }
  return null;
}

export function managedThroughOf(
  deviceId: string,
  tree: SourceRead<ManagementTree> | "loading" | null | undefined,
): ManagedThroughResult {
  if (tree === "loading" || tree === null || tree === undefined) {
    return {
      state: "READING",
      badgeState: "na",
      badgeWord: "READING",
      nodeLabel: null,
      headline: "Reading management tree",
      detail: BOUNDARY,
      evidenceAt: null,
      evidenceSource: "management tree",
    };
  }
  if (tree.state !== "ok") {
    return {
      state: "UNKNOWN",
      badgeState: "unk",
      badgeWord: "UNKNOWN",
      nodeLabel: null,
      headline: "Management tree could not be read",
      detail: `Unreadable is not the same as not managed. ${BOUNDARY}`,
      evidenceAt: null,
      evidenceSource: "management tree",
    };
  }

  const evidenceAt = tree.value.discovered_at ?? null;
  const roots = Array.isArray(tree.value.domains) ? tree.value.domains.flatMap((d) => (Array.isArray(d?.nodes) ? d.nodes : [])) : [];
  const trail = ancestorsOf(roots, deviceId);
  if (trail === null) {
    return {
      state: "NOT_UNDER_ANY_NODE",
      badgeState: "na",
      badgeWord: "NOT UNDER A NODE",
      nodeLabel: null,
      headline: "Not under any management node",
      detail: `The tree was read and lists no management node above this unit. ${BOUNDARY}`,
      evidenceAt,
      evidenceSource: "management tree",
    };
  }

  const parent = trail.length > 0 ? trail[trail.length - 1] : null;
  const label = parent ? nodeLabelOf(parent) : null;
  if (!parent || label === null) {
    // Unit listed at top level (or its parent has no usable label): never guess a manager.
    return {
      state: trail.length > 0 ? "UNKNOWN" : "NOT_UNDER_ANY_NODE",
      badgeState: trail.length > 0 ? "unk" : "na",
      badgeWord: trail.length > 0 ? "UNKNOWN" : "NOT UNDER A NODE",
      nodeLabel: null,
      headline: trail.length > 0 ? "Managing node label unavailable" : "Not under any management node",
      detail: `${trail.length > 0 ? "The parent node has no readable label." : "The unit is listed at the top of the tree."} ${BOUNDARY}`,
      evidenceAt,
      evidenceSource: "management tree",
    };
  }

  return {
    state: "MANAGED",
    badgeState: "ok",
    badgeWord: "MANAGED",
    nodeLabel: label,
    headline: `Managed through ${label}`,
    detail: `Reported under ${label} in the management tree. ${BOUNDARY}`,
    evidenceAt,
    evidenceSource: "management tree",
  };
}
