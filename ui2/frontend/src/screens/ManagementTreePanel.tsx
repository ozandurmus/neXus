import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import InputBase from "@mui/material/InputBase";
import MenuItem from "@mui/material/MenuItem";
import Select from "@mui/material/Select";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import {
  acknowledgeManagedItem, getManagementTree, importDiscoveryCandidates, listCredentials, responsesAreMasked,
  type CredentialView, type ManagementTree, type ManagementTreeNode,
} from "../auth/adminApi";
import { StatusChip } from "../shell/M3Widgets";
import { RoleChip, StatePanel, Ts, Unknown } from "../shell/States";
import { m3 } from "../theme/m3Theme";

const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none" } as const;

/** An ApiError ({status, body:{error}}) or an Error, as one readable line -- never "[object Object]". */
export function describeError(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (e && typeof e === "object" && "status" in e) {
    const err = e as { status: number; body?: { error?: string } };
    return `HTTP ${err.status}${err.body?.error ? ` · ${err.body.error}` : ""}`;
  }
  return String(e);
}

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
  PALO_ALTO_HA_PAIR_CLUSTER: "HA pair",
  PALO_ALTO_DEVICE: "Firewall",
  PALO_ALTO_VIRTUAL_SYSTEM: "Virtual system (VSYS)",
};

function nameOf(n: ManagementTreeNode): string {
  return n.cluster_member_ref ?? n.hostname ?? n.virtual_system ?? "UNKNOWN";
}

/** Counts per domain: clusters, gateways (incl. members), virtual systems, gateways not in neXus. */
export function domainCounts(nodes: readonly ManagementTreeNode[]): { clusters: number; gateways: number; vs: number; missing: number } {
  const c = { clusters: 0, gateways: 0, vs: 0, missing: 0 };
  const walk = (n: ManagementTreeNode) => {
    if (n.category === "cluster") c.clusters++;
    else if (n.category === "virtual_system") c.vs++;
    else if (n.category === "device") c.gateways++;
    if (n.ack_token && !n.acknowledged) c.missing++;
    n.children.forEach(walk);
  };
  nodes.forEach(walk);
  return c;
}

/** Devices the server manages that neXus does not enrol and nobody marked "not an issue" (the server's own count). */
export function notImported(domains: ManagementTree["domains"]): number {
  let n = 0;
  const walk = (x: ManagementTreeNode) => {
    if (x.ack_token && !x.acknowledged) n++;
    x.children.forEach(walk);
  };
  domains.forEach((d) => d.nodes.forEach(walk));
  return n;
}

interface RowActions {
  readonly canWrite: boolean;
  readonly selected: ReadonlySet<string>;
  readonly toggle: (candidateId: string) => void;
  readonly acknowledge: (token: string, reason: string) => Promise<void>;
  readonly undo: (token: string) => Promise<void>;
}

/** "Not an issue here": a short reason, kept with who decided (audited). */
function AckControl({ node, actions }: { readonly node: ManagementTreeNode; readonly actions: RowActions }) {
  const [editing, setEditing] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  if (!node.ack_token || !actions.canWrite) return null;
  if (node.acknowledged) {
    return (
      <Link component="button" type="button" underline="hover" sx={{ fontSize: 12 }} disabled={busy}
        onClick={async () => { setBusy(true); try { await actions.undo(node.ack_token!); } finally { setBusy(false); } }}>
        Undo
      </Link>
    );
  }
  if (!editing) {
    return <Link component="button" type="button" underline="hover" sx={{ fontSize: 12 }} onClick={() => setEditing(true)}>Not an issue</Link>;
  }
  return (
    <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
      <InputBase autoFocus value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why (e.g. decommissioned)"
        inputProps={{ "aria-label": "Reason", maxLength: 300 }}
        sx={{ fontSize: 12, px: 1, height: 28, border: `1px solid ${m3.outlineVar}`, borderRadius: "6px", width: 200 }} />
      <Button size="small" disabled={busy || reason.trim().length < 3}
        onClick={async () => { setBusy(true); try { await actions.acknowledge(node.ack_token!, reason.trim()); setEditing(false); } finally { setBusy(false); } }}>
        Save
      </Button>
      <Button size="small" color="inherit" onClick={() => setEditing(false)}>Cancel</Button>
    </Box>
  );
}

/** The member's own state, read from the member (never from the management server). */
function MemberState({ node, actions }: { readonly node: ManagementTreeNode; readonly actions: RowActions }) {
  // A virtual system is a context of its gateway, never enrolled on its own: nothing to flag.
  if (node.category === "virtual_system") return null;
  if (node.category === "management_appliance") return <StatusChip tone="neutral" label="Management / log server" dense />;
  if (!node.imported) {
    return (
      <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        {node.acknowledged
          ? <StatusChip tone="neutral" label={`Not an issue · ${node.acknowledged_reason ?? ""}`} dense />
          : <StatusChip tone="warn" label="Not in neXus" dense />}
        <AckControl node={node} actions={actions} />
      </Box>
    );
  }
  return (
    <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
      {node.category === "device" && (node.ha_role ? <RoleChip role={node.ha_role} dense /> : null)}
      {node.last_collection_state === "FAILED" && <StatusChip tone="bad" label="Last collection failed" dense />}
      {node.last_collection_at
        ? <Typography component="span" sx={{ fontSize: 11.5, color: m3.onSurfaceVar }}>read <Ts at={node.last_collection_at} relative /></Typography>
        : <Unknown word="NOT EVALUATED" reason="never collected" />}
    </Box>
  );
}

function NodeRow({ node, depth, onOpenDevice, onOpenCluster, actions }: {
  readonly node: ManagementTreeNode;
  readonly depth: number;
  readonly onOpenDevice: (deviceId: string) => void;
  readonly onOpenCluster: (clusterRef: string) => void;
  readonly actions: RowActions;
}) {
  const isCluster = node.category === "cluster" && node.cluster_member_ref !== undefined;
  const name = nameOf(node);
  const open = isCluster ? () => onOpenCluster(node.cluster_member_ref!) : node.device_id ? () => onOpenDevice(node.device_id!) : null;
  const selectable = actions.canWrite && Boolean(node.ack_token) && !node.acknowledged && node.candidate_id !== null;
  return (
    <>
      <Box data-kind={node.kind} sx={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 170px minmax(0,1.3fr)", alignItems: "center", gap: 1.5,
        minHeight: 40, px: 2, pl: 2 + depth * 2.5, borderTop: `1px solid ${m3.outlineVar}` }}>
        <Box sx={{ minWidth: 0, display: "flex", alignItems: "center", gap: 1 }}>
          {depth > 0 && <Box component="span" sx={{ color: m3.outline }}>└</Box>}
          {selectable && (
            <Checkbox size="small" sx={{ p: 0.25 }} checked={actions.selected.has(node.candidate_id!)} onChange={() => actions.toggle(node.candidate_id!)}
              inputProps={{ "aria-label": `Select ${name} to import` }} />
          )}
          {open
            ? <Link component="button" type="button" underline="hover" onClick={open} sx={{ fontWeight: 600, fontSize: 13, textAlign: "left" }}>{name}</Link>
            : <Typography sx={{ fontWeight: 600, fontSize: 13 }}>{name}</Typography>}
        </Box>
        <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>
          {KIND_LABEL[node.kind] ?? node.kind}{node.model && node.category !== "cluster" ? ` · ${node.model}` : ""}
        </Typography>
        <Box>{isCluster ? <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{node.children.length} member{node.children.length === 1 ? "" : "s"}</Typography> : <MemberState node={node} actions={actions} />}</Box>
      </Box>
      {node.category !== "cluster" && node.children.length > 0 && node.children.every((k) => k.category === "virtual_system") ? (
        // A gateway's virtual systems as one line (Panorama lists every VSYS under each HA member).
        <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0,1fr) 170px minmax(0,1.3fr)", gap: 1.5, alignItems: "center",
          minHeight: 34, px: 2, pl: 2 + (depth + 1) * 2.5, borderTop: `1px solid ${m3.outlineVar}` }}>
          <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
            title={node.children.map(nameOf).join(", ")}>
            └ {node.children.map(nameOf).join(", ")}
          </Typography>
          <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{node.children.length} virtual system{node.children.length === 1 ? "" : "s"}</Typography>
          <Box />
        </Box>
      ) : node.children.map((k, i) => <NodeRow key={`${nameOf(k)}-${i}`} node={k} depth={depth + 1} onOpenDevice={onOpenDevice} onOpenCluster={onOpenCluster} actions={actions} />)}
    </>
  );
}

/**
 * A management server's inventory: its domains (CMAs) -- or, for Panorama, one group -- and in each the clusters,
 * gateways and virtual systems it manages. The list comes from discovery (gated management-plane reads); name, HA role
 * and last read are each member's own, from the device summary every other screen shows. Devices the server manages
 * that neXus does not enrol are flagged, can be imported from here, or marked "not an issue" with a reason.
 */
export function ManagementTreePanel({ deviceId, onOpenDevice, onOpenCluster }: {
  readonly deviceId: string;
  readonly onOpenDevice: (deviceId: string) => void;
  readonly onOpenCluster: (clusterRef: string) => void;
}) {
  const [state, setState] = useState<{ tree: ManagementTree | null; error: string | null }>({ tree: null, error: null });
  const [open, setOpen] = useState<ReadonlySet<number>>(new Set([0]));
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [credentials, setCredentials] = useState<readonly CredentialView[]>([]);
  const [credential, setCredential] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const canWrite = !responsesAreMasked();

  useEffect(() => {
    let cancelled = false;
    getManagementTree(deviceId)
      .then((tree) => { if (!cancelled) setState({ tree, error: null }); })
      .catch((e: unknown) => { if (!cancelled) setState({ tree: null, error: describeError(e) }); });
    return () => { cancelled = true; };
  }, [deviceId, reload]);
  useEffect(() => {
    if (!canWrite || selected.size === 0 || credentials.length > 0) return;
    listCredentials().then((r) => setCredentials(r.credentials)).catch(() => undefined);
  }, [canWrite, selected.size, credentials.length]);

  const actions: RowActions = {
    canWrite,
    selected,
    toggle: (id) => setSelected((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n; }),
    acknowledge: async (token, reason) => {
      try { await acknowledgeManagedItem(deviceId, token, true, reason); setReload((x) => x + 1); } catch (e) { setMessage(describeError(e)); }
    },
    undo: async (token) => {
      try { await acknowledgeManagedItem(deviceId, token, false); setReload((x) => x + 1); } catch (e) { setMessage(describeError(e)); }
    },
  };

  if (state.error) return <StatePanel variant="error" title="Managed estate" body={`The management tree could not be read: ${state.error}`} />;
  if (!state.tree) return <StatePanel variant="empty" title="Managed estate" body="Reading what this management server manages…" />;
  const t = state.tree;
  if (!t.discovered_at) {
    return <StatePanel variant="not_evaluated" title="Managed estate" body="No discovery has run against this management server yet. Run Import from manager in Administration to list what it manages." />;
  }
  const c = t.counts;
  const missing = c.not_in_nexus ?? notImported(t.domains);
  const importSelected = async () => {
    if (!t.run_id) return;
    setMessage(null);
    try {
      const r = await importDiscoveryCandidates(t.run_id, [...selected], credential || undefined);
      const n = r.results.filter((x) => x.outcome === "new").length;
      const refused = r.results.filter((x) => x.outcome === "refused" || x.outcome === "conflicting");
      setMessage(`${n} imported${refused.length ? ` · ${refused.length} not imported (${refused.map((x) => x.reason ?? x.outcome).join(", ")})` : ""}. First collection is queued.`);
      setSelected(new Set());
      setReload((x) => x + 1);
    } catch (e) {
      setMessage(`Import failed: ${describeError(e)}`);
    }
  };
  return (
    <Card sx={CARD} aria-label="Managed estate">
      <Box sx={{ px: 2, pt: 1.5, pb: 1.25 }}>
        <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Managed estate · from discovery</Typography>
        <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>{t.vendor === "palo_alto" ? "Firewalls and HA pairs" : "Domains, clusters and gateways"}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.5 }}>
          {t.vendor === "palo_alto" ? "" : `${c.domains ?? 0} domains · `}{c.clusters ?? 0} clusters · {c.gateways ?? 0} gateways · {c.virtual_systems ?? 0} virtual systems
          {c.management_appliances ? ` · ${c.management_appliances} management / log servers` : ""} · {c.imported ?? 0} in neXus ·
          discovered <Ts at={t.discovered_at} relative />
        </Typography>
        {missing > 0 && (
          <Box sx={{ mt: 1, display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <StatusChip tone="warn" label={`${missing} not in neXus`} dense />
            <Typography variant="body2">
              {missing} gateway{missing === 1 ? "" : "s"} this server manages {missing === 1 ? "is" : "are"} not enrolled in neXus.
              {canWrite ? " Select them below to import, or mark one Not an issue with a reason." : ""}
            </Typography>
          </Box>
        )}
        {(c.acknowledged ?? 0) > 0 && (
          <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar }}>{c.acknowledged} marked not an issue (shown below with their reason).</Typography>
        )}
        {canWrite && selected.size > 0 && (
          <Box sx={{ mt: 1, display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Select size="small" displayEmpty value={credential} onChange={(e) => setCredential(String(e.target.value))} sx={{ minWidth: 220, fontSize: 13 }}
              inputProps={{ "aria-label": "Credential" }}>
              <MenuItem value="">Discovery credential (default)</MenuItem>
              {credentials.map((cr) => <MenuItem key={cr.credential_reference_id} value={cr.credential_reference_id}>{cr.display_name}</MenuItem>)}
            </Select>
            <Button variant="contained" size="small" onClick={importSelected}>Import {selected.size} selected</Button>
            <Button size="small" color="inherit" onClick={() => setSelected(new Set())}>Clear</Button>
          </Box>
        )}
        {message && <Typography variant="body2" sx={{ mt: 0.75 }}>{message}</Typography>}
        <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar, mt: 0.5 }}>
          Name, HA role and last read come from each device itself, not from the management server (its connection state is not a liveness signal).
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
              <Box component="span" sx={{ fontWeight: 600, fontSize: 13.5, flex: 1 }}>{d.domain ?? (t.vendor === "palo_alto" ? "Managed firewalls" : "No domain")}</Box>
              {dc.missing > 0 && <StatusChip tone="warn" label={`${dc.missing} not in neXus`} dense />}
              <Box component="span" sx={{ fontSize: 12, color: m3.onSurfaceVar, fontVariantNumeric: "tabular-nums" }}>
                {dc.clusters} clusters · {dc.gateways} gateways{dc.vs > 0 ? ` · ${dc.vs} virtual systems` : ""}
              </Box>
            </Box>
            {expanded && (
              <Stack sx={{ pb: 0.5 }}>
                {d.nodes.map((n, j) => <NodeRow key={`${nameOf(n)}-${j}`} node={n} depth={0} onOpenDevice={onOpenDevice} onOpenCluster={onOpenCluster} actions={actions} />)}
              </Stack>
            )}
          </Box>
        );
      })}
    </Card>
  );
}
