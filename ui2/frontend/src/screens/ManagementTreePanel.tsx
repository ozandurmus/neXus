import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { getManagementTree, type ManagementTree, type ManagementTreeNode } from "../auth/adminApi";
import { StatusChip } from "../shell/M3Widgets";
import { RoleChip, StatePanel, Ts, Unknown } from "../shell/States";
import { m3 } from "../theme/m3Theme";

const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none" } as const;

/** Discovery kinds in the operator's words. */
export const KIND_LABEL: Readonly<Record<string, string>> = {
  PLAIN_HIGH_AVAILABILITY_CLUSTER: "Cluster",
  PLAIN_CLUSTER_MEMBER: "Member",
  STANDALONE_PRODUCT_GATEWAY: "Gateway",
  VIRTUALIZATION_CLUSTER: "VSX cluster",
  PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER: "VSX member",
  STANDALONE_VIRTUALIZATION_HOST: "VSX gateway",
  VIRTUAL_SYSTEM_CLUSTER: "Virtual system (cluster)",
  STANDALONE_VIRTUAL_SYSTEM: "Virtual system",
};

function nameOf(n: ManagementTreeNode): string {
  return n.cluster_member_ref ?? n.hostname ?? n.virtual_system ?? "UNKNOWN";
}

/** Counts per domain: clusters, gateways (incl. members), virtual systems. */
export function domainCounts(nodes: readonly ManagementTreeNode[]): { clusters: number; gateways: number; vs: number } {
  const c = { clusters: 0, gateways: 0, vs: 0 };
  const walk = (n: ManagementTreeNode) => {
    if (n.kind.endsWith("CLUSTER")) c.clusters++;
    else if (n.virtual_system !== undefined) c.vs++;
    else c.gateways++;
    n.children.forEach(walk);
  };
  nodes.forEach(walk);
  return c;
}

/** Devices discovery listed that are not in neXus: gateways, members and VSX hosts (clusters and virtual systems follow their members). */
export function notImported(domains: ManagementTree["domains"]): number {
  let n = 0;
  const walk = (x: ManagementTreeNode) => {
    if (!x.kind.endsWith("CLUSTER") && x.virtual_system === undefined && !x.imported) n++;
    x.children.forEach(walk);
  };
  domains.forEach((d) => d.nodes.forEach(walk));
  return n;
}

/** The member's own state, read from the member (never from the management server). */
function MemberState({ node }: { readonly node: ManagementTreeNode }) {
  if (!node.imported) return <StatusChip tone="neutral" label="Not imported" dense />;
  return (
    <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
      {node.virtual_system === undefined && !node.kind.endsWith("CLUSTER") && (node.ha_role ? <RoleChip role={node.ha_role} dense /> : null)}
      {node.last_collection_state === "FAILED" && <StatusChip tone="bad" label="Last collection failed" dense />}
      {node.last_collection_state === null && <Unknown word="NOT EVALUATED" reason="never collected" />}
      {node.last_collection_at && (
        <Typography component="span" sx={{ fontSize: 11.5, color: m3.onSurfaceVar }}>read <Ts at={node.last_collection_at} relative /></Typography>
      )}
    </Box>
  );
}

function NodeRow({ node, depth, onOpenDevice, onOpenCluster }: {
  readonly node: ManagementTreeNode;
  readonly depth: number;
  readonly onOpenDevice: (deviceId: string) => void;
  readonly onOpenCluster: (clusterRef: string) => void;
}) {
  const isCluster = node.kind.endsWith("CLUSTER") && node.cluster_member_ref !== undefined;
  const name = nameOf(node);
  const open = isCluster ? () => onOpenCluster(node.cluster_member_ref!) : node.device_id ? () => onOpenDevice(node.device_id!) : null;
  return (
    <>
      <Box data-kind={node.kind} sx={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 170px minmax(0,1.1fr)", alignItems: "center", gap: 1.5,
        minHeight: 40, px: 2, pl: 2 + depth * 2.5, borderTop: `1px solid ${m3.outlineVar}` }}>
        <Box sx={{ minWidth: 0, display: "flex", alignItems: "center", gap: 1 }}>
          {depth > 0 && <Box component="span" sx={{ color: m3.outline }}>└</Box>}
          {open
            ? <Link component="button" type="button" underline="hover" onClick={open} sx={{ fontWeight: 600, fontSize: 13, textAlign: "left" }}>{name}</Link>
            : <Typography sx={{ fontWeight: 600, fontSize: 13 }}>{name}</Typography>}
        </Box>
        <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{KIND_LABEL[node.kind] ?? node.kind}</Typography>
        <Box>{isCluster ? <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{node.children.length} member{node.children.length === 1 ? "" : "s"}</Typography> : <MemberState node={node} />}</Box>
      </Box>
      {node.children.map((k, i) => <NodeRow key={`${nameOf(k)}-${i}`} node={k} depth={depth + 1} onOpenDevice={onOpenDevice} onOpenCluster={onOpenCluster} />)}
    </>
  );
}

/**
 * A Check Point management server's inventory: its domains (CMAs), and in each the clusters, gateways and virtual
 * systems it manages. The list comes from discovery (gated management-plane reads); HA role and collection state are
 * each member's own, read from the member itself.
 */
export function ManagementTreePanel({ deviceId, onOpenDevice, onOpenCluster }: {
  readonly deviceId: string;
  readonly onOpenDevice: (deviceId: string) => void;
  readonly onOpenCluster: (clusterRef: string) => void;
}) {
  const [state, setState] = useState<{ tree: ManagementTree | null; error: string | null }>({ tree: null, error: null });
  const [open, setOpen] = useState<ReadonlySet<number>>(new Set([0]));
  useEffect(() => {
    let cancelled = false;
    setState({ tree: null, error: null });
    getManagementTree(deviceId)
      .then((tree) => { if (!cancelled) setState({ tree, error: null }); })
      .catch((e: unknown) => { if (!cancelled) setState({ tree: null, error: e instanceof Error ? e.message : String(e) }); });
    return () => { cancelled = true; };
  }, [deviceId]);

  if (state.error) return <StatePanel variant="error" title="Managed estate" body={`The management tree could not be read: ${state.error}`} />;
  if (!state.tree) return <StatePanel variant="empty" title="Managed estate" body="Reading what this management server manages…" />;
  const t = state.tree;
  if (!t.discovered_at) {
    return <StatePanel variant="not_evaluated" title="Managed estate" body="No discovery has run against this management server yet. Run Import from manager in Administration to list its domains, clusters and gateways." />;
  }
  const c = t.counts;
  const missing = notImported(t.domains);
  return (
    <Card sx={CARD} aria-label="Managed estate">
      <Box sx={{ px: 2, pt: 1.5, pb: 1.25 }}>
        <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Managed estate · from discovery</Typography>
        <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Domains, clusters and gateways</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.5 }}>
          {c.domains ?? 0} domains · {c.clusters ?? 0} clusters · {c.gateways ?? 0} gateways · {c.virtual_systems ?? 0} virtual systems · {c.imported ?? 0} imported ·
          discovered <Ts at={t.discovered_at} relative />
        </Typography>
        {missing > 0 && (
          <Box sx={{ mt: 1, display: "flex", alignItems: "center", gap: 1 }}>
            <StatusChip tone="warn" label={`${missing} not in neXus`} dense />
            <Typography variant="body2">
              {missing} device{missing === 1 ? "" : "s"} this server manages {missing === 1 ? "is" : "are"} not enrolled in neXus. They are marked Not imported below; add them from Administration › Import from manager.
            </Typography>
          </Box>
        )}
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
          HA role and last read come from each member itself, not from the management server (its connection state is not a liveness signal).
        </Typography>
      </Box>
      {t.domains.map((d, i) => {
        const dc = domainCounts(d.nodes);
        const expanded = open.has(i);
        return (
          <Box key={d.domain ?? `none-${i}`} data-domain={d.domain ?? ""} sx={{ borderTop: `1px solid ${m3.outlineVar}` }}>
            <Box component="button" type="button" aria-expanded={expanded}
              onClick={() => setOpen((prev) => { const n = new Set(prev); if (n.has(i)) n.delete(i); else n.add(i); return n; })}
              sx={{ width: "100%", height: 44, display: "flex", alignItems: "center", gap: 1, px: 2, border: "none", bgcolor: expanded ? m3.scLow : "transparent",
                font: "inherit", cursor: "pointer", color: m3.onSurface, textAlign: "left" }}>
              <Box component="span" sx={{ color: m3.onSurfaceVar, width: 12 }}>{expanded ? "▾" : "▸"}</Box>
              <Box component="span" sx={{ fontWeight: 600, fontSize: 13.5, flex: 1 }}>{d.domain ?? "No domain"}</Box>
              <Box component="span" sx={{ fontSize: 12, color: m3.onSurfaceVar, fontVariantNumeric: "tabular-nums" }}>
                {dc.clusters} clusters · {dc.gateways} gateways{dc.vs > 0 ? ` · ${dc.vs} virtual systems` : ""}
              </Box>
            </Box>
            {expanded && (
              <Stack sx={{ pb: 0.5 }}>
                {d.nodes.map((n, j) => <NodeRow key={`${nameOf(n)}-${j}`} node={n} depth={0} onOpenDevice={onOpenDevice} onOpenCluster={onOpenCluster} />)}
              </Stack>
            )}
          </Box>
        );
      })}
    </Card>
  );
}
