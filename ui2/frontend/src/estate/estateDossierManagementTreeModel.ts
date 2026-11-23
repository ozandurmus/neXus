import type { DomainHaEntry, ManagementTree, ManagementTreeNode } from "../auth/adminApi";
import type { SourceRead } from "../screens/overview/attentionRules";
import { domainCounts, domainHaLine, notImported, rowType, splitDomainHa } from "../screens/ManagementTreePanel";

/**
 * Pure view model for the read-only management tree block on the Estate dossier.
 *
 * Evidence law: the tree is management-plane discovery/intent, never direct-device runtime truth. A tree that
 * was not read (loading, failed, restricted, malformed, or missing) is UNKNOWN with its own text, never "not managed".
 * Rows are masked labels only (cluster_member_ref ?? hostname ?? virtual_system), never raw addresses or serials.
 */

/** At most this many node rows are listed per domain; the rest print "+N more" through MoreFooter. */
export const MANAGEMENT_TREE_ROW_CAP = 12;

export const MANAGEMENT_TREE_BOUNDARY = "Management-plane intent, not device runtime truth.";

export type ManagementTreeBlockState = "UNKNOWN" | "OK";

export interface ManagementTreeRow {
  readonly key: string;
  /** Indentation depth: 0 for a top-level node, +1 per nesting level. */
  readonly depth: number;
  readonly label: string;
  readonly type: string;
  readonly notImported: boolean;
}

export interface ManagementTreeDomainView {
  readonly key: string;
  /** The Domain name from the tree, or a plain placeholder when the tree names none. */
  readonly name: string;
  /** Node counts by kind, as domainCounts counts them. Gateways include cluster members. */
  readonly counts: { readonly clusters: number; readonly gateways: number; readonly vs: number };
  /** Nodes in this domain that neXus does not import and nobody acknowledged (the server's own not-imported rule). */
  readonly notImported: number;
  /** Every node in this domain, nested nodes included. */
  readonly nodeTotal: number;
  /** The first MANAGEMENT_TREE_ROW_CAP nodes, depth-first. */
  readonly rows: readonly ManagementTreeRow[];
  /** One line: the Domain's Active/Standby placement, or why it is not known. */
  readonly haLine: string;
}

export interface ManagementTreeBlockModel {
  readonly state: ManagementTreeBlockState;
  readonly badgeWord: string;
  readonly headline: string;
  readonly detail: string;
  /** Age source for the evidence stamp: the tree's discovery time; null when the tree was not read. */
  readonly evidenceAt: string | null;
  readonly domains: readonly ManagementTreeDomainView[];
  /** Sum of notImported over all domains; null when the tree was not read. */
  readonly notImportedTotal: number | null;
}

function labelOf(n: ManagementTreeNode): string {
  const label = n.cluster_member_ref ?? n.hostname ?? n.virtual_system ?? null;
  return label && label.trim() !== "" ? label : "UNKNOWN";
}

function flatten(
  nodes: readonly ManagementTreeNode[],
  depth = 0,
  out: { depth: number; node: ManagementTreeNode }[] = [],
): { depth: number; node: ManagementTreeNode }[] {
  for (const node of nodes) {
    out.push({ depth, node });
    flatten(node.children ?? [], depth + 1, out);
  }
  return out;
}

function haLineFor(
  tree: ManagementTree,
  domain: string | null,
  bySection: ReadonlyMap<string, DomainHaEntry>,
): string {
  if (!tree.domain_ha || tree.domain_ha.observed_at === null) return "Domain HA not read";
  const entry = domain === null ? undefined : bySection.get(domain);
  return entry ? domainHaLine(entry) : "No Domain HA reported for this domain";
}

function unreadModel(headline: string): ManagementTreeBlockModel {
  return {
    state: "UNKNOWN",
    badgeWord: "UNKNOWN",
    headline,
    detail: `Unreadable is not the same as not managed. ${MANAGEMENT_TREE_BOUNDARY}`,
    evidenceAt: null,
    domains: [],
    notImportedTotal: null,
  };
}

export function buildManagementTreeBlockModel(
  tree: SourceRead<ManagementTree> | "loading" | null | undefined,
): ManagementTreeBlockModel {
  if (tree === "loading" || tree === null || tree === undefined) {
    return unreadModel("Management tree not read yet");
  }
  if (tree.state !== "ok" || !Array.isArray(tree.value.domains)) {
    return unreadModel("Management tree could not be read");
  }

  const value = tree.value;
  const { bySection } = splitDomainHa(value.domain_ha, value.domains);
  const domains: ManagementTreeDomainView[] = value.domains.map((d, index) => {
    const flat = flatten(Array.isArray(d?.nodes) ? d.nodes : []);
    const counts = domainCounts(d.nodes ?? []);
    const rows: ManagementTreeRow[] = flat.slice(0, MANAGEMENT_TREE_ROW_CAP).map(({ depth, node }, i) => ({
      key: `${index}-${i}`,
      depth,
      label: labelOf(node),
      type: rowType(node),
      notImported: Boolean(node.ack_token && !node.acknowledged),
    }));
    return {
      key: `domain-${index}`,
      name: d.domain && d.domain.trim() !== "" ? d.domain : "Domain not named in the tree",
      counts: { clusters: counts.clusters, gateways: counts.gateways, vs: counts.vs },
      notImported: counts.missing,
      nodeTotal: flat.length,
      rows,
      haLine: haLineFor(value, d.domain ?? null, bySection),
    };
  });

  const total = domains.length;
  return {
    state: "OK",
    badgeWord: "READ",
    headline: `${total} ${total === 1 ? "management domain" : "management domains"} read from the tree`,
    detail: MANAGEMENT_TREE_BOUNDARY,
    evidenceAt: value.discovered_at ?? null,
    domains,
    notImportedTotal: notImported(value.domains),
  };
}
