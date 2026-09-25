import { useCallback, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import type { Tone } from "../shell/tone";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { deviceNameLabel, jobPhaseLabel, isTerminalJobState, enrollmentStateLabel, onboardingChip } from "../shell/deviceCopy";
import { MONO, m3 } from "../theme/m3Theme";
import { RoleChip, Ts, VendorBadge, vendorDisplayName } from "../shell/States";
import { ClusterContextStrip, DeviceIdentityTable, orderMembers } from "./DeviceShared";
import { JobStatusIndicator } from "../shell/JobStatusIndicator";
import {
  getDevice,
  getDeviceInventory,
  getClusterInventory,
  requestInventoryCollect,
  retryDeviceConfirm,
  collectDeviceBackup,
  type ApiError,
  type ClusterContext,
  type ClusterDifference,
  type ClusterInterface,
  type ClusterInventory,
  type ClusterRoute,
  type DeviceInventory,
  type GridMemberView,
  type DeviceSummary,
  type InventoryAddress,
  type InventoryContext,
  type InventoryInterface,
  type InventoryRoute,
  type Presence,
  type BackupArtefact,
  listDeviceBackups,
} from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";

const POLL_INTERVAL_MS = 1750;

/** The services worth a chip on a grid member row, in display order; everything else is counted only. */
const GRID_MEMBER_SERVICES = ["DNS", "DHCP", "NTP", "DOT_DOH", "DFP", "ATP", "ANALYTICS", "REPORTING", "TAXII"] as const;

function serviceTone(status: string): Tone {
  if (status === "WORKING") return "ok";
  if (status === "WARNING") return "warn";
  if (status === "FAILED" || status === "ERROR") return "bad";
  return "neutral";
}

function percentTone(value: number | null): Tone {
  if (value === null) return "neutral";
  if (value >= 90) return "bad";
  if (value >= 75) return "warn";
  return "ok";
}

/** V72: an Infoblox Grid Manager's members -- role in the grid, hardware, HA, node health and services, as reported. */
function GridMembersPanel({ members }: { readonly members: readonly GridMemberView[] }) {
  if (members.length === 0) {
    return (
      <EmptyPanel
        title="No grid member evidence"
        body="The member list is read when the Grid Manager is confirmed and after every completed backup; none has been recorded yet."
      />
    );
  }
  const pct = (v: number | null) => (v === null ? "\u2014" : `${v}%`);
  return (
    <Stack spacing={1.5}>
      <Typography variant="caption" color="text.secondary">
        {members.length} members · {members.filter((m) => m.grid_master).length} Grid Master ·{" "}
        {members.filter((m) => m.master_candidate && !m.grid_master).length} master candidates ·{" "}
        {members.filter((m) => m.ha_enabled).length} HA pairs · read from the Grid Manager, nothing inferred
      </Typography>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Member</TableCell>
              <TableCell>Role</TableCell>
              <TableCell>Hardware</TableCell>
              <TableCell>HA</TableCell>
              <TableCell>Node</TableCell>
              <TableCell align="right">Disk</TableCell>
              <TableCell align="right">Memory</TableCell>
              <TableCell align="right">CPU</TableCell>
              <TableCell align="right">DB</TableCell>
              <TableCell>Services</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {members.map((m) => {
              const byService = new Map(m.services.map((s) => [s.service, s.status] as const));
              const shown = GRID_MEMBER_SERVICES.filter((s) => byService.has(s));
              const active = m.services.filter((s) => s.status === "WORKING" || s.status === "WARNING").length;
              return (
                <TableRow key={m.virtual_system}>
                  <TableCell sx={{ fontWeight: 600, whiteSpace: "nowrap" }}>{m.virtual_system}</TableCell>
                  <TableCell>
                    {m.grid_master ? <StatusChip tone="mem" label="Grid Master" dense />
                      : m.master_candidate ? <StatusChip tone="neutral" label="Master candidate" dense />
                      : <Typography variant="caption" color="text.secondary">member</Typography>}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}>
                    <Typography variant="body2">{m.hardware_type ?? "\u2014"}</Typography>
                    <Typography variant="caption" color="text.secondary">{[m.platform, m.hypervisor].filter(Boolean).join(" · ")}</Typography>
                  </TableCell>
                  <TableCell>
                    {m.ha_enabled
                      ? <StatusChip tone={m.ha_status === "ACTIVE" || m.ha_status === "PASSIVE" ? "ok" : "warn"} label={m.ha_status ?? "HA"} dense />
                      : <Typography variant="caption" color="text.secondary">single node</Typography>}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}>
                    <StatusChip tone={m.node_status === "WORKING" ? "ok" : m.node_status ? "warn" : "neutral"} label={m.node_status ?? "unknown"} dense />
                    {m.replication && (
                      <Typography variant="caption" color="text.secondary" sx={{ ml: 0.5 }}>replication {m.replication}</Typography>
                    )}
                  </TableCell>
                  <TableCell align="right"><StatusChip tone={percentTone(m.disk_percent)} label={pct(m.disk_percent)} dense /></TableCell>
                  <TableCell align="right"><StatusChip tone={percentTone(m.memory_percent)} label={pct(m.memory_percent)} dense /></TableCell>
                  <TableCell align="right"><StatusChip tone={percentTone(m.cpu_percent)} label={pct(m.cpu_percent)} dense /></TableCell>
                  <TableCell align="right"><StatusChip tone={percentTone(m.db_percent)} label={pct(m.db_percent)} dense /></TableCell>
                  <TableCell>
                    <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, alignItems: "center" }}>
                      {shown.map((svc) => (
                        <StatusChip key={svc} tone={serviceTone(byService.get(svc)!)} label={svc.replace("_", "/")} dense />
                      ))}
                      <Typography variant="caption" color="text.secondary">{active} of {m.services.length} active</Typography>
                    </Box>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
}

/** A Symantec Management Center's managed devices (ProxySG, Reporter, WSS) as it lists them; values read, never inferred. */
function ManagedDevicesPanel({ members }: { readonly members: readonly GridMemberView[] }) {
  if (members.length === 0) {
    return <EmptyPanel title="No managed-device evidence" body="Collect reads the Management Center's device list; none has been recorded yet." />;
  }
  const deployment = (m: GridMemberView) => m.services.find((s) => s.service === "DEPLOYMENT")?.status ?? null;
  return (
    <Stack spacing={1.5}>
      <Typography variant="caption" color="text.secondary">
        {members.length} devices · {members.filter((m) => m.platform === "ProxySG").length} ProxySG · read from the Management Center, nothing inferred
      </Typography>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Device</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Model</TableCell>
              <TableCell>OS version</TableCell>
              <TableCell>Management</TableCell>
              <TableCell>Deployment</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {members.map((m) => (
              <TableRow key={m.virtual_system}>
                <TableCell sx={{ fontWeight: 600, whiteSpace: "nowrap" }}>{m.virtual_system}</TableCell>
                <TableCell>{m.platform ?? "\u2014"}</TableCell>
                <TableCell>{m.hardware_type ?? "\u2014"}</TableCell>
                <TableCell sx={{ whiteSpace: "nowrap" }}>{m.hypervisor ?? "\u2014"}</TableCell>
                <TableCell>{m.node_status ? <StatusChip tone={/MANAGED$/.test(m.node_status) && !/UNMANAGED/.test(m.node_status) ? "ok" : "warn"} label={m.node_status.replace("_", " ")} dense /> : "\u2014"}</TableCell>
                <TableCell>{deployment(m) ?? "\u2014"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
}

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const code = typeof apiErr.body?.code === "string" ? (apiErr.body.code as string) : undefined;
  const reason = typeof apiErr.body?.reason === "string" ? (apiErr.body.reason as string) : undefined;
  const reasonCode = typeof apiErr.body?.reason_code === "string" ? (apiErr.body.reason_code as string) : undefined;
  if (code === "DEVICE_NOT_ELIGIBLE" && reason && reason.includes("DRAFT")) {
    return "Device is pending enrollment confirmation. Inventory can be collected once enrolled.";
  }
  if (code && reason) return `${code}: ${reason}`;
  if (apiErr.body?.error === "VALIDATION_FAILED") {
    if (reasonCode === "DEVICE_NOT_DRAFT") {
      return "Device is already confirmed and enrolled (please refresh).";
    }
    if (reasonCode === "address_ref_invalid") {
      return "Invalid IP address or hostname. Check for whitespace or unsupported characters.";
    }
    if (reasonCode === "credential_reference_not_found") {
      return "Selected credential was not found.";
    }
    if (reasonCode === "DEVICE_NOT_FOUND") {
      return "Device not found.";
    }
    if (reasonCode) return `Validation failed: ${reasonCode}`;
  }
  if (reasonCode) return reasonCode;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

function extractMemberBase(hostname: string): string {
  let name = hostname.trim();
  name = name.replace(/[-._](?:0?[1-9]|active|passive|standby|pri|sec|[a-b])$/i, "");
  name = name.replace(/[-._]+$/, "");
  return name;
}

export function deriveClusterTitle(clusterRef: string, members: readonly DeviceSummary[]): string {
  const isRawUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clusterRef.trim());
  if (!clusterRef.includes("|") && !isRawUuid && clusterRef.trim().length > 0) {
    return clusterRef;
  }

  const names = members
    .map((m) => m.hostname?.trim())
    .filter((h): h is string => Boolean(h && h.length > 0));

  if (names.length === 0) {
    return clusterRef;
  }

  const bases = names.map(extractMemberBase);
  // Check if all members unanimously resolve to the same base name
  if (bases.every((b) => b === bases[0]) && bases[0].length >= 3) {
    const base = bases[0];
    return base.toUpperCase().endsWith("-CLS") || base.toUpperCase().endsWith("_CLS")
      ? base
      : `${base}-CLS`;
  }

  // Otherwise, find longest common prefix across the extracted bases
  let prefix = bases[0];
  for (let i = 1; i < bases.length; i++) {
    while (!bases[i].startsWith(prefix)) {
      prefix = prefix.slice(0, -1);
      if (!prefix) break;
    }
  }
  prefix = prefix.replace(/[-._]+$/, "").trim();

  if (prefix.length >= 3) {
    return prefix.toUpperCase().endsWith("-CLS") || prefix.toUpperCase().endsWith("_CLS")
      ? prefix
      : `${prefix}-CLS`;
  }

  // Fall back to joined member hostnames or raw ref
  if (names.length <= 2) {
    return `${names.join(" / ")}-CLS`;
  }

  return clusterRef;
}

/** The vendor monogram (review §4: one vendor identity everywhere); VSX when the model or name says so. */
export function VendorAvatar({
  vendorHint,
  model,
  hostname,
}: {
  readonly vendorHint: string;
  readonly model?: string | null;
  readonly hostname?: string | null;
}) {
  const isVsx = vendorHint === "check_point"
    && Boolean((model && model.toUpperCase().includes("VSX")) || (hostname && hostname.toUpperCase().includes("VSX")));
  return <VendorBadge vendor={vendorHint} vsx={isVsx} />;
}

/** device_inventory_ha (migration V17): the selected context's own HA role, shown above its interfaces. */
function ContextHaBadge({ context }: { readonly context: InventoryContext | undefined }) {
  const ha = context?.ha;
  if (!ha) return null;
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <Typography variant="body2" color="text.secondary">HA role:</Typography>
      <RoleChip role={ha.role} dense />
      {ha.cluster_mode && (
        <Typography variant="body2" color="text.secondary">
          {ha.cluster_mode}
        </Typography>
      )}
    </Stack>
  );
}

function presenceLabel(presence: Presence): string {
  return presence === "all" ? "All members" : presence.join(", ");
}

function DifferencesNote({ differences }: { readonly differences: readonly ClusterDifference[] }) {
  if (differences.length === 0) return null;
  return (
    <Stack spacing={0.25}>
      {differences.map((d) => (
        <Typography key={`${d.device_id}-${d.field}`} variant="body2" color="text.secondary">
          {d.device_id}: {d.field} = {d.value}
        </Typography>
      ))}
    </Stack>
  );
}

function calculateNetwork(cidr: string): string {
  if (!cidr || !cidr.includes("/")) return "—";
  const [ip, prefixStr] = cidr.split("/");
  const prefix = parseInt(prefixStr, 10);
  if (isNaN(prefix) || prefix < 0 || prefix > 32) return "—";
  const parts = ip.split(".").map(Number);
  if (parts.length !== 4 || parts.some(isNaN)) return "—";
  const ipNum = ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
  const mask = prefix === 0 ? 0 : (~0 << (32 - prefix)) >>> 0;
  const netNum = (ipNum & mask) >>> 0;
  const netParts = [
    (netNum >>> 24) & 255,
    (netNum >>> 16) & 255,
    (netNum >>> 8) & 255,
    netNum & 255,
  ];
  return `${netParts.join(".")}/${prefix}`;
}

/** One table for every entity: a standalone device is rendered as a one-member cluster, so the
 * interface and route tables, chips and palette are identical for a device and a cluster (Product
 * Owner, 2026-09-22). Member addresses and state go under the device's own id; only a
 * cluster-virtual address (never present on a standalone) stays on the row itself. */
export function toClusterContexts(contexts: readonly InventoryContext[], deviceId: string): ClusterContext[] {
  return contexts.map((ctx) => ({
    context: ctx.context,
    vs_name: ctx.vs_name,
    interfaces: ctx.interfaces.map((iface) => ({
      name: iface.name,
      kind: iface.kind,
      vlan_id: iface.vlan_id,
      addresses: iface.addresses.filter((a) => a.role === "cluster_virtual"),
      presence: "all" as const,
      differences: [],
      member_addresses: { [deviceId]: iface.addresses.filter((a) => a.role !== "cluster_virtual") },
      member_states: { [deviceId]: iface.state ?? "unknown" },
    })),
    routes: ctx.routes.map((route) => ({
      destination: route.destination,
      next_hop: route.next_hop,
      interface: route.interface,
      protocol: route.protocol,
      presence: "all" as const,
      differences: [],
    })),
  }));
}

function ipv4InCidr(address: string, cidr: string): boolean {
  const [net, bitsStr] = cidr.split("/");
  const bits = Number(bitsStr);
  const toNum = (ip: string) => {
    const parts = ip.split(".").map(Number);
    if (parts.length !== 4 || parts.some((n) => Number.isNaN(n))) return null;
    return ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
  };
  const a = toNum(address);
  const n = toNum(net);
  if (a === null || n === null || Number.isNaN(bits) || bits < 0 || bits > 32) return false;
  const mask = bits === 0 ? 0 : (~0 << (32 - bits)) >>> 0;
  return ((a & mask) >>> 0) === ((n & mask) >>> 0);
}

/** A virtual system's "cphaprob -a if" addresses carry no prefix (Product Owner, 2026-09-22: the
 * Network column stayed empty for every VS). The same context's own routes do: a route on that
 * interface whose destination contains the address gives the prefix -- evidence from the same
 * collection pass, never a default. Left bare when no such route exists. */
function withRoutePrefixes(context: ClusterContext): ClusterContext {
  const prefixFor = (ifaceName: string, address: string): string | null => {
    if (address.includes("/")) return null;
    for (const r of context.routes) {
      if (!r.destination.includes("/")) continue;
      if (r.interface && r.interface.toLowerCase() !== ifaceName.toLowerCase()) continue;
      if (r.next_hop && r.next_hop !== "0.0.0.0" && r.next_hop !== "—") continue;
      if (ipv4InCidr(address, r.destination)) return r.destination.split("/")[1];
    }
    return null;
  };
  const fix = (ifaceName: string, a: InventoryAddress): InventoryAddress => {
    const p = prefixFor(ifaceName, a.address);
    return p ? { ...a, address: `${a.address}/${p}` } : a;
  };
  return {
    ...context,
    interfaces: context.interfaces.map((iface) => ({
      ...iface,
      addresses: iface.addresses.map((a) => fix(iface.name, a)),
      member_addresses: iface.member_addresses
        ? Object.fromEntries(Object.entries(iface.member_addresses).map(([id, list]) => [id, list.map((a) => fix(iface.name, a))]))
        : iface.member_addresses,
    })),
  };
}

function vlanLabel(iface: { readonly name: string; readonly kind: string; readonly vlan_id?: number | null }): string {
  if (iface.vlan_id !== undefined && iface.vlan_id !== null) return String(iface.vlan_id);
  if (iface.kind === "vlan" && iface.name.includes(".")) return iface.name.slice(iface.name.lastIndexOf(".") + 1);
  return "—";
}

function ClusterInterfacesTable({
  interfaces,
  members = [],
  sharedAddressOnly = false,
}: {
  readonly interfaces: readonly (ClusterInterface & { readonly vsysLabel?: string })[];
  readonly members?: readonly { readonly device_id: string; readonly hostname?: string | null }[];
  /** Palo Alto HA members share one address per interface -- render it once instead of
   * one identical-looking column per member (design language §3: render one view when
   * members agree; per-member columns are for a cluster whose members genuinely differ,
   * as Check Point's own cluster VIP + per-member addresses do). */
  readonly sharedAddressOnly?: boolean;
}) {
  const [upOnly, setUpOnly] = useState(true);
  const [search, setSearch] = useState("");
  // A standalone device (toClusterContexts) is a one-member cluster: its one address column is
  // simply "Addresses", and a cluster-virtual address can only ever appear on a real cluster.
  const singleMember = members.length <= 1;
  const anyVip = interfaces.some((iface) => iface.addresses.some((a) => a.role === "cluster_virtual"));
  // Design language §3 / D-UI1: when every member reports the same addresses on every row, render
  // one view -- one Address column, no per-member repetition (Product Owner, 2026-09-22: a VSX
  // virtual system's two members always agree, two identical columns said nothing).
  const membersAgree = !singleMember && interfaces.length > 0 && interfaces.every((iface) => {
    const lists = members.map((m) => (iface.member_addresses?.[m.device_id] ?? []).map((a) => a.address).sort().join(","));
    return lists.every((l) => l === lists[0]) && iface.differences.length === 0;
  });
  const oneAddressColumn = sharedAddressOnly || singleMember || membersAgree;
  const showVipColumn = !oneAddressColumn && anyVip;

  // The loopback interface is collected evidence but never operator-relevant on this screen,
  // for either vendor -- present on every device, never carrying a difference worth surfacing.
  const withoutLoopback = interfaces.filter((iface) => iface.kind !== "loopback");

  if (withoutLoopback.length === 0) {
    return <EmptyPanel title="No interface evidence" body="This context has no collected interfaces yet." />;
  }

  // A row carries vsysLabel only in the cluster's merged, all-VS default view; a single
  // selected VS's own table never needs it since every row already shares that context.
  const showVsysColumn = withoutLoopback.some((iface) => iface.vsysLabel !== undefined);

  const filtered = withoutLoopback.filter((iface) => {
    if (upOnly) {
      const memberStates = iface.member_states ? Object.values(iface.member_states).filter((v): v is string => typeof v === "string") : [];
      const allDown = memberStates.length > 0 && memberStates.every((s) => s.toLowerCase() === "down");
      const hasVip = iface.addresses.some((a) => a.role === "cluster_virtual");
      // Hide strictly when all members are closed/down and there is no VIP.
      // Degraded/mixed (one member down), warnings, and diffs stay strictly visible.
      if (allDown && !hasVip && iface.differences.length === 0) {
        return false;
      }
    }
    if (search.trim()) {
      const q = search.toLowerCase();
      const matchName = iface.name.toLowerCase().includes(q);
      const matchVip = iface.addresses.some((a) => a.address.toLowerCase().includes(q));
      const matchMember =
        iface.member_addresses &&
        Object.values(iface.member_addresses).some((list) =>
          list.some((a) => a.address.toLowerCase().includes(q))
        );
      const matchVsys = iface.vsysLabel?.toLowerCase().includes(q);
      return matchName || matchVip || matchMember || matchVsys;
    }
    return true;
  });

  return (
    <Stack spacing={1.5}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          Interfaces · {filtered.length} of {withoutLoopback.length} shown{upOnly ? " · Active only" : ""}
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center">
          <TextField
            size="small"
            placeholder="Filter interfaces..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            sx={{ "& .MuiInputBase-root": { height: 32, fontSize: "0.8125rem", borderRadius: "8px" } }}
          />
          <M3Button
            emphasis={upOnly ? "filled" : "outlined"}
            onClick={() => setUpOnly(!upOnly)}
          >
            {upOnly ? "✓ Active only" : "All ports"}
          </M3Button>
        </Stack>
      </Box>

      <Table size="small">
        <TableHead>
          <TableRow>
            {showVsysColumn && <TableCell sx={{ fontWeight: 600 }}>VSYS</TableCell>}
            <TableCell sx={{ fontWeight: 600 }}>Interface</TableCell>
            <TableCell sx={{ fontWeight: 600 }}>Kind</TableCell>
            <TableCell sx={{ fontWeight: 600 }}>VLAN</TableCell>
            {oneAddressColumn ? (
              <TableCell sx={{ fontWeight: 600 }}>Address</TableCell>
            ) : (
              <>
                {showVipColumn && <TableCell sx={{ fontWeight: 600 }}>Cluster VIP</TableCell>}
                {members.map((m) => (
                  <TableCell key={m.device_id} sx={{ fontWeight: 600 }}>
                    {deviceNameLabel(m.hostname)}
                  </TableCell>
                ))}
              </>
            )}
            <TableCell sx={{ fontWeight: 600 }}>Network</TableCell>
            <TableCell sx={{ fontWeight: 600 }}>State</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {filtered.map((iface) => {
            const vips = iface.addresses.filter((a) => a.role === "cluster_virtual");
            let cidrAddress: string | undefined;
            if (iface.member_addresses) {
              for (const addrs of Object.values(iface.member_addresses)) {
                const found = addrs.find((a) => a.address.includes("/"));
                if (found) {
                  cidrAddress = found.address;
                  break;
                }
              }
            }
            if (!cidrAddress && vips.length > 0) {
              cidrAddress = vips[0].address;
            }
            const network = cidrAddress ? calculateNetwork(cidrAddress) : "—";

            // Members agree or disagree with each other -- never "not literally up, not literally
            // down" against two hardcoded values. Two members reporting the same "unknown" agree;
            // Degraded means the members' own states genuinely differ from each other, nothing else.
            const memberStates = iface.member_states ? Object.values(iface.member_states).filter((v): v is string => typeof v === "string") : [];
            const normalizedStates = memberStates.map((s) => s.toLowerCase());
            const distinctStates = new Set(normalizedStates);
            const isMixed = memberStates.length > 0 && distinctStates.size > 1;
            const sharedState = memberStates.length > 0 && distinctStates.size === 1 ? normalizedStates[0] : null;
            const allUp = sharedState === "up";
            const allDown = sharedState === "down";

            return (
              <TableRow key={`${iface.vsysLabel ?? ""}:${iface.name}`} hover>
                {showVsysColumn && (
                  <TableCell>
                    <StatusChip tone="neutral" label={iface.vsysLabel ?? "Physical"} dense />
                  </TableCell>
                )}
                <TableCell sx={{ fontWeight: 600, fontFamily: "monospace" }}>{iface.name}</TableCell>
                <TableCell>{iface.kind}</TableCell>
                <TableCell>{vlanLabel(iface)}</TableCell>
                {oneAddressColumn ? (
                  <TableCell>
                    {(() => {
                      const firstMember = members[0]?.device_id;
                      const agreed = firstMember ? (iface.member_addresses?.[firstMember] ?? []) : [];
                      const all = agreed.length > 0 ? agreed : Object.values(iface.member_addresses ?? {}).flat();
                      const shown = sharedAddressOnly && !singleMember && !membersAgree
                        ? (cidrAddress ? [{ address: cidrAddress }] : [])
                        : all;
                      return shown.length > 0 || vips.length > 0 ? (
                        <Stack spacing={0.25}>
                          {shown.map((a) => (
                            <Typography key={a.address} variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>
                              {a.address}
                            </Typography>
                          ))}
                          {vips.map((a) => (
                            <Stack key={a.address} direction="row" spacing={0.75} alignItems="center">
                              <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>{a.address}</Typography>
                              <StatusChip tone="mem" label="VIP" dense />
                            </Stack>
                          ))}
                        </Stack>
                      ) : (
                        <Typography variant="body2" color="text.secondary">—</Typography>
                      );
                    })()}
                  </TableCell>
                ) : (
                  <>
                    {showVipColumn && <TableCell>
                      {vips.length > 0 ? (
                        <Stack direction="row" spacing={0.75} alignItems="center">
                          <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>
                            {vips.map((a) => a.address).join(", ")}
                          </Typography>
                          <StatusChip tone="mem" label="VIP" dense />
                        </Stack>
                      ) : (
                        <Typography variant="body2" color="text.secondary">—</Typography>
                      )}
                    </TableCell>}
                    {members.map((m) => {
                      const mAddrs = iface.member_addresses?.[m.device_id] ?? [];
                      const mState = iface.member_states?.[m.device_id];
                      return (
                        <TableCell key={m.device_id}>
                          {mAddrs.length > 0 ? (
                            // Review §3: the coloured dot after the address carried the member's link state by colour
                            // alone; the word is now on hover here and, when members differ, in the State column.
                            <Typography variant="body2" title={mState ? `${deviceNameLabel(m.hostname)} link state: ${mState}` : undefined}
                              sx={{ fontFamily: MONO, fontSize: "0.8125rem" }}>
                              {mAddrs.map((a) => a.address).join(", ")}
                            </Typography>
                          ) : (
                            <Typography variant="body2" color="text.secondary">—</Typography>
                          )}
                        </TableCell>
                      );
                    })}
                  </>
                )}
                <TableCell sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>
                  {network}
                </TableCell>
                <TableCell>
                  <Stack spacing={0.5}>
                    {allUp ? (
                      <StatusChip tone="ok" label="Up" dense />
                    ) : allDown ? (
                      <StatusChip tone="neutral" label="Down" dense />
                    ) : isMixed ? (
                      <Stack spacing={0.25}>
                        <StatusChip tone="warn" label="Degraded" dense />
                        {members.map((m) => iface.member_states?.[m.device_id] ? (
                          <Typography key={m.device_id} variant="caption" sx={{ color: m3.onSurfaceVar, whiteSpace: "nowrap" }}>
                            {deviceNameLabel(m.hostname)}: {iface.member_states[m.device_id]}
                          </Typography>
                        ) : null)}
                      </Stack>
                    ) : sharedState ? (
                      <StatusChip tone="neutral" label={sharedState.charAt(0).toUpperCase() + sharedState.slice(1)} dense />
                    ) : (
                      <StatusChip tone={iface.presence === "all" ? "ok" : "warn"} label={presenceLabel(iface.presence)} dense />
                    )}
                    {iface.differences.length > 0 && (
                      <DifferencesNote differences={iface.differences} />
                    )}
                  </Stack>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </Stack>
  );
}

function ClusterRoutesTable({
  routes,
  members = [],
}: {
  readonly routes: readonly (ClusterRoute & { readonly vsysLabel?: string })[];
  readonly members?: readonly { readonly device_id: string; readonly hostname?: string | null }[];
}) {
  const [diffOnly, setDiffOnly] = useState<boolean>(false);
  const [search, setSearch] = useState("");

  if (routes.length === 0) {
    return <EmptyPanel title="No routing evidence" body="This context has no collected routes yet." />;
  }
  // A standalone device (toClusterContexts) has nothing to align against: same table, no
  // alignment column and no differences switch.
  const singleMember = members.length <= 1;

  const showVsysColumn = routes.some((route) => route.vsysLabel !== undefined);

  const diffRoutes = routes.filter((r) => r.presence !== "all" || r.differences.length > 0);
  const diffCount = diffRoutes.length;

  let displayedRoutes = diffOnly ? diffRoutes : routes;

  if (search.trim()) {
    const q = search.toLowerCase();
    displayedRoutes = displayedRoutes.filter(
      (r) =>
        r.destination.toLowerCase().includes(q) ||
        (r.next_hop && r.next_hop.toLowerCase().includes(q)) ||
        (r.interface && r.interface.toLowerCase().includes(q)) ||
        r.protocol.toLowerCase().includes(q) ||
        (r.vsysLabel && r.vsysLabel.toLowerCase().includes(q))
    );
  }

  const memberNameById = new Map<string, string>();
  members.forEach((m) => memberNameById.set(m.device_id, deviceNameLabel(m.hostname)));

  return (
    <Stack spacing={1.5}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {singleMember ? "Routing table" : "Unified Routing Table"} · {routes.length} routes
          </Typography>
          {!singleMember && <Stack direction="row" spacing={0.5} sx={{ bgcolor: m3.scLow, p: 0.5, borderRadius: "10px" }}>
            <Box
              role="button"
              tabIndex={0}
              onClick={() => setDiffOnly(false)}
              sx={{
                px: 1.5,
                py: 0.5,
                borderRadius: "8px",
                cursor: "pointer",
                bgcolor: !diffOnly ? m3.scLowest : "transparent",
                boxShadow: !diffOnly ? m3.e2 : "none",
                fontWeight: !diffOnly ? 600 : 500,
                fontSize: "0.8125rem",
                color: !diffOnly ? m3.primary : m3.onSurfaceVar,
                transition: "all 0.15s ease-in-out",
              }}
            >
              All routes ({routes.length})
            </Box>
            <Box
              role="button"
              tabIndex={0}
              onClick={() => setDiffOnly(true)}
              sx={{
                px: 1.5,
                py: 0.5,
                borderRadius: "8px",
                cursor: "pointer",
                bgcolor: diffOnly ? m3.scLowest : "transparent",
                boxShadow: diffOnly ? m3.e2 : "none",
                fontWeight: diffOnly ? 600 : 500,
                fontSize: "0.8125rem",
                color: diffCount > 0 ? m3.error : diffOnly ? m3.primary : m3.onSurfaceVar,
                display: "flex",
                alignItems: "center",
                gap: 0.5,
                transition: "all 0.15s ease-in-out",
              }}
            >
              <span>Differences only</span>
              {diffCount > 0 && (
                <Box
                  component="span"
                  sx={{
                    px: 0.6,
                    py: 0.1,
                    borderRadius: "10px",
                    bgcolor: m3.errorContainer,
                    color: m3.onErrorContainer,
                    fontSize: "0.75rem",
                    fontWeight: 700,
                  }}
                >
                  {diffCount}
                </Box>
              )}
            </Box>
          </Stack>}
        </Box>

        <TextField
          size="small"
          placeholder="Filter routes..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          sx={{ "& .MuiInputBase-root": { height: 32, fontSize: "0.8125rem", borderRadius: "8px" } }}
        />
      </Box>

      {diffOnly && diffCount === 0 ? (
        <Box sx={{ p: 2.5, bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", textAlign: "center" }}>
          <Typography variant="body2" sx={{ color: m3.goodInk, fontWeight: 600 }}>
            ✓ All routes are identical across cluster members. No routing drift detected.
          </Typography>
        </Box>
      ) : displayedRoutes.length === 0 ? (
        <EmptyPanel title="No matching routes" body="No routes match the current filter." />
      ) : (
        <Table size="small">
          <TableHead>
            <TableRow>
              {showVsysColumn && <TableCell sx={{ fontWeight: 600 }}>VSYS</TableCell>}
              <TableCell sx={{ fontWeight: 600 }}>Destination</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Next hop</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Interface</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Protocol</TableCell>
              {!singleMember && <TableCell sx={{ fontWeight: 600 }}>Cluster Alignment / Diff</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {displayedRoutes.map((route, index) => {
              const isShared = route.presence === "all";
              const isDiff = !isShared || route.differences.length > 0;
              let diffLabel = null;
              if (!isShared && Array.isArray(route.presence)) {
                const memberNames = route.presence.map((id) => memberNameById.get(id) ?? id).join(", ");
                diffLabel = `DIFF > ${memberNames} only`;
              } else if (route.differences.length > 0) {
                diffLabel = `DIFF > ${route.differences.map((d) => `${d.field}: ${d.value}`).join(", ")}`;
              }

              return (
                <TableRow
                  key={`${route.vsysLabel ?? ""}-${route.destination}-${route.next_hop ?? ""}-${route.interface ?? ""}-${index}`}
                  hover
                  sx={{
                    bgcolor: isDiff ? m3.errorContainer : "inherit",
                  }}
                >
                  {showVsysColumn && (
                    <TableCell>
                      <StatusChip tone="neutral" label={route.vsysLabel ?? "Physical"} dense />
                    </TableCell>
                  )}
                  <TableCell sx={{ fontFamily: "monospace", fontWeight: 600, fontSize: "0.8125rem" }}>
                    {route.destination}
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>
                    {route.next_hop ?? "—"}
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>
                    {route.interface ?? "—"}
                  </TableCell>
                  <TableCell>
                    <StatusChip tone="neutral" label={route.protocol} dense />
                  </TableCell>
                  {!singleMember && <TableCell>
                    {isDiff && diffLabel ? (
                      <Stack direction="row" spacing={0.75} alignItems="center">
                        <Box
                          sx={{
                            display: "inline-flex",
                            alignItems: "center",
                            px: 1,
                            py: 0.25,
                            borderRadius: "6px",
                            bgcolor: m3.errorContainer,
                            border: `1px solid ${m3.criticalInk}`,
                            color: m3.onErrorContainer,
                            fontWeight: 700,
                            fontSize: "0.75rem",
                            fontFamily: "monospace",
                          }}
                        >
                          {diffLabel}
                        </Box>
                        {route.differences.length > 0 && (
                          <DifferencesNote differences={route.differences} />
                        )}
                      </Stack>
                    ) : (
                      <StatusChip
                        tone="ok"
                        label="✓ Shared"
                        dense
                      />
                    )}
                  </TableCell>}
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </Stack>
  );
}

/** More than one context renders as its own tab strip; exactly one renders inline, with no extra tab chrome. */
function ContextTabs({
  contexts,
  activeContext,
  onSelectContext,
  render,
}: {
  readonly contexts: readonly { context: string; label?: string; vsName?: string; originalContext?: any }[];
  readonly activeContext?: string | null;
  readonly onSelectContext?: (contextName: string) => void;
  readonly render: (contextName: string) => React.ReactNode;
}) {
  const [internalContext, setInternalContext] = useState<string | null>(null);

  if (contexts.length <= 1 && !activeContext) {
    return <>{render(contexts[0]?.context ?? "physical")}</>;
  }
  const effectiveContext = activeContext !== undefined ? activeContext : internalContext;
  const currentIndex = effectiveContext
    ? Math.max(
        0,
        contexts.findIndex((c) => {
          const act = effectiveContext.toLowerCase();
          return (
            c.context.toLowerCase() === act ||
            (c.vsName && c.vsName.toLowerCase() === act) ||
            (c.originalContext?.context && c.originalContext.context.toLowerCase() === act)
          );
        })
      )
    : 0;

  return (
    <M3Tabs
      ariaLabel="Inventory context"
      value={currentIndex}
      onChange={(next) => {
        const target = contexts[next];
        if (target) {
          if (onSelectContext) {
            onSelectContext(target.context);
          } else {
            setInternalContext(target.context);
          }
        }
      }}
      tabs={contexts.map((c) => ({ label: c.label ?? c.context, panel: render(c.context) }))}
    />
  );
}

export interface UnifiedContextTab<T> {
  readonly context: string;
  readonly label: string;
  readonly vsName?: string;
  readonly originalContext?: T;
}

export function buildUnifiedContextTabs<T extends { context: string; vs_name?: string | null }>(
  contexts: readonly T[],
  virtualSystems: readonly string[] = []
): {
  tabs: readonly UnifiedContextTab<T>[];
  resolveContext: (selectedName: string) => T | undefined;
} {
  const physicalCtx = contexts.find(
    (c) => c.context.toLowerCase() === "physical" || c.context === "0"
  );
  const virtualContexts = contexts.filter(
    (c) => c.context.toLowerCase() !== "physical" && c.context !== "0"
  );

  const sortedVirtualContexts = [...virtualContexts].sort((a, b) => {
    const numA = parseInt(a.context, 10);
    const numB = parseInt(b.context, 10);
    if (!isNaN(numA) && !isNaN(numB)) return numA - numB;
    return a.context.localeCompare(b.context);
  });

  const tabs: UnifiedContextTab<T>[] = [];
  const mappedVsNames = new Set<string>();

  if (physicalCtx) {
    tabs.push({
      context: physicalCtx.context,
      label: "Physical / VS0",
      originalContext: physicalCtx,
    });
  }

  const matchesContext = (vs: string, c: T): boolean => {
    const lowerVs = vs.toLowerCase();
    const lowerCtx = c.context.toLowerCase();
    const digits = c.context.replace(/\D+/g, "");
    return (
      (c.vs_name != null && c.vs_name.toLowerCase() === lowerVs) ||
      lowerCtx === lowerVs ||
      lowerVs === `vsys${lowerCtx}` ||
      lowerVs.includes(`(${lowerCtx})`) ||
      lowerVs.includes(`(vsid ${lowerCtx})`) ||
      lowerVs.includes(`(vsys ${lowerCtx})`) ||
      lowerVs.includes(`(vsys${lowerCtx})`) ||
      (digits.length > 0 &&
        (lowerVs.includes(`(vsid ${digits})`) ||
          lowerVs.includes(`(vsys${digits})`) ||
          lowerVs.includes(`(vsys ${digits})`) ||
          lowerVs.includes(`(${digits})`)))
    );
  };

  const unmappedVsList = virtualSystems.filter((vs) => {
    return !sortedVirtualContexts.some((c) => matchesContext(vs, c));
  });

  let nextUnmappedVsIdx = 0;
  for (const c of sortedVirtualContexts) {
    let resolvedVs: string | undefined = undefined;

    if (c.vs_name && c.vs_name.trim()) {
      resolvedVs = c.vs_name.trim();
    } else {
      const directMatch = virtualSystems.find((vs) => matchesContext(vs, c));
      if (directMatch) {
        resolvedVs = directMatch;
      } else if (!isNaN(parseInt(c.context, 10)) && nextUnmappedVsIdx < unmappedVsList.length) {
        resolvedVs = unmappedVsList[nextUnmappedVsIdx++];
      }
    }

    if (resolvedVs) {
      mappedVsNames.add(resolvedVs.toLowerCase());
      const hasParenthesis = resolvedVs.includes("(");
      const isNum = !isNaN(parseInt(c.context, 10));
      const prefix = resolvedVs.toLowerCase().includes("vsys") ? "VSYS" : "VS";
      const label = hasParenthesis || !isNum
        ? (resolvedVs.startsWith("VS:") || resolvedVs.startsWith("VSYS:") ? resolvedVs : `${prefix}: ${resolvedVs}`)
        : `VS: ${resolvedVs} (VSID ${c.context})`;
      tabs.push({
        context: resolvedVs,
        label,
        vsName: resolvedVs,
        originalContext: c,
      });
    } else {
      const isNum = !isNaN(parseInt(c.context, 10));
      tabs.push({
        context: c.context,
        label: isNum ? `VSID: ${c.context}` : c.context,
        originalContext: c,
      });
    }
  }

  for (const vs of virtualSystems) {
    if (!mappedVsNames.has(vs.toLowerCase()) && !tabs.some((t) => t.context.toLowerCase() === vs.toLowerCase())) {
      const prefix = vs.toLowerCase().includes("vsys") ? "VSYS" : "VS";
      tabs.push({
        context: vs,
        label: vs.startsWith("VS:") || vs.startsWith("VSYS:") ? vs : `${prefix}: ${vs}`,
        vsName: vs,
      });
    }
  }

  const resolveContext = (selectedName: string): T | undefined => {
    const sel = selectedName.toLowerCase();
    const tabMatch = tabs.find(
      (t) =>
        t.context.toLowerCase() === sel ||
        (t.vsName && t.vsName.toLowerCase() === sel) ||
        (t.originalContext && t.originalContext.context.toLowerCase() === sel)
    );
    if (tabMatch?.originalContext) {
      return tabMatch.originalContext;
    }
    return contexts.find(
      (c) =>
        c.context.toLowerCase() === sel ||
        (c.vs_name && c.vs_name.toLowerCase() === sel)
    );
  };

  return { tabs, resolveContext };
}

export function InterfacesPanel({
  contexts,
  virtualSystems,
  activeContext,
  onSelectContext,
  isPaloAlto = false,
  member,
}: {
  readonly contexts: readonly InventoryContext[];
  readonly virtualSystems?: readonly string[] | string | null;
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
  readonly member: { readonly device_id: string; readonly hostname?: string | null };
}) {
  const members = [member];
  if (contexts.length === 0) {
    return (
      <EmptyPanel
        title="No interface evidence"
        body="This device has not been collected yet. Use Collect now to read its interfaces."
      />
    );
  }
  const vsList = Array.isArray(virtualSystems)
    ? virtualSystems
    : typeof virtualSystems === "string"
    ? virtualSystems.split(/,\s*/).filter(Boolean)
    : [];
  const { tabs, resolveContext } = useMemo(
    () => buildUnifiedContextTabs(contexts, vsList),
    [contexts, vsList]
  );

  // Check Point's VSX virtual systems stay their own separate contexts, Physical showing only the
  // chassis's own interfaces; Palo Alto's vsys merges by default, matching the single-device
  // reference view (see ClusterInterfacesPanel for the same vendor split on the cluster path). No
  // tab-strip chrome here for either vendor: the left sidebar's own VS/VSYS sub-navigation already
  // sets activeContext for this device (DeviceList), so a second selector in the panel would just
  // duplicate it.
  if (!isPaloAlto) {
    // A device whose contexts are its members (an Infoblox grid, V72) has no "physical" context: show the first one
    // until the sidebar selects a member.
    const context = resolveContext(activeContext ?? "physical") ?? (activeContext ? undefined : contexts[0]);
    return (
      <Stack spacing={1}>
        <ContextHaBadge context={context} />
        <ClusterInterfacesTable interfaces={context ? toClusterContexts([context], member.device_id)[0].interfaces : []} members={members} />
      </Stack>
    );
  }

  if (!activeContext) {
    const merged = tabs.flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return toClusterContexts([found], member.device_id)[0].interfaces.map((iface) => ({ ...iface, vsysLabel: tab.label }));
    });
    return (
      <Stack spacing={1}>
        <ContextHaBadge context={resolveContext("physical")} />
        <ClusterInterfacesTable interfaces={merged} members={members} />
      </Stack>
    );
  }
  const context = resolveContext(activeContext);
  return (
    <Stack spacing={1}>
      <ContextHaBadge context={context} />
      <ClusterInterfacesTable interfaces={context ? toClusterContexts([context], member.device_id)[0].interfaces : []} members={members} />
    </Stack>
  );
}

export function RoutesPanel({
  contexts,
  virtualSystems,
  activeContext,
  onSelectContext,
  isPaloAlto = false,
  member,
}: {
  readonly contexts: readonly InventoryContext[];
  readonly virtualSystems?: readonly string[] | string | null;
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
  readonly member: { readonly device_id: string; readonly hostname?: string | null };
}) {
  const members = [member];
  if (contexts.length === 0) {
    return (
      <EmptyPanel
        title="No routing evidence"
        body="This device has not been collected yet. Use Collect now to read its routes."
      />
    );
  }
  const vsList = Array.isArray(virtualSystems)
    ? virtualSystems
    : typeof virtualSystems === "string"
    ? virtualSystems.split(/,\s*/).filter(Boolean)
    : [];
  const { tabs, resolveContext } = useMemo(
    () => buildUnifiedContextTabs(contexts, vsList),
    [contexts, vsList]
  );

  if (!isPaloAlto) {
    // A device whose contexts are its members (an Infoblox grid, V72) has no "physical" context: show the first one
    // until the sidebar selects a member.
    const context = resolveContext(activeContext ?? "physical") ?? (activeContext ? undefined : contexts[0]);
    return <ClusterRoutesTable routes={context ? toClusterContexts([context], member.device_id)[0].routes : []} members={members} />;
  }

  if (!activeContext) {
    const merged = tabs.flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return toClusterContexts([found], member.device_id)[0].routes.map((route) => ({ ...route, vsysLabel: tab.label }));
    });
    return <ClusterRoutesTable routes={merged} members={members} />;
  }
  const context = resolveContext(activeContext);
  return <ClusterRoutesTable routes={context ? toClusterContexts([context], member.device_id)[0].routes : []} members={members} />;
}

export function ClusterInterfacesPanel({
  contexts,
  members = [],
  clusterRef,
  virtualSystems = [],
  activeContext,
  onSelectContext,
  isPaloAlto = false,
}: {
  readonly contexts: readonly ClusterContext[];
  readonly members?: readonly { readonly device_id: string; readonly hostname?: string | null }[];
  readonly clusterRef?: string;
  readonly virtualSystems?: readonly string[];
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
}) {
  const { tabs, resolveContext } = useMemo(
    () => buildUnifiedContextTabs(contexts, virtualSystems),
    [contexts, virtualSystems]
  );

  if (tabs.length === 0) {
    return <EmptyPanel title="No interface evidence" body="No cluster member has been collected yet." />;
  }

  const memberNames = members.map((m) => deviceNameLabel(m.hostname)).join(", ");
  const renderUncollected = (name: string) => (
    <Box
      sx={{
        p: 2.5,
        bgcolor: m3.scLowest,
        borderRadius: "16px",
        border: `1px solid ${m3.outlineVar}`,
        display: "flex",
        flexDirection: "column",
        gap: 2,
      }}
    >
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
          <Chip
            label={name.toLowerCase().includes("vsys") ? "VSYS" : "VSX"}
            size="small"
            sx={{
              fontWeight: 700,
              bgcolor: m3.scHigh,
              color: m3.onSurface,
              borderRadius: "6px"
            }}
          />
          <Typography variant="h6" sx={{ fontWeight: 700, color: m3.onSurface }}>
            {name.toLowerCase().includes("vsys") ? "Virtual System (VSYS)" : "Virtual System"}: {name}
          </Typography>
          <StatusChip tone="neutral" label="Uncollected Instance" dense />
        </Box>
      </Box>
      <Typography variant="body2" color="text.secondary">
        Virtual System <strong>{name}</strong> operates on cluster <strong>{clusterRef ?? "Cluster"}</strong> across members (<strong>{memberNames}</strong>).
      </Typography>
      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" }, gap: 1.5 }}>
        <Box sx={{ p: 1.5, bgcolor: m3.scLow, borderRadius: "10px", border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Virtual System</Typography>
          <Typography variant="body2" sx={{ fontWeight: 700, mt: 0.5 }}>{name}</Typography>
        </Box>
        <Box sx={{ p: 1.5, bgcolor: m3.scLow, borderRadius: "10px", border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Parent Cluster</Typography>
          <Typography variant="body2" sx={{ fontWeight: 700, mt: 0.5 }}>{clusterRef ?? "Parent Cluster"}</Typography>
        </Box>
        <Box sx={{ p: 1.5, bgcolor: m3.scLow, borderRadius: "10px", border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>HA Redundancy</Typography>
          <Typography variant="body2" sx={{ fontWeight: 700, mt: 0.5 }}>{members.length} Nodes</Typography>
        </Box>
      </Box>
    </Box>
  );

  // Both vendors' virtual systems share the same reference view: merged by default (tagged by a
  // VSYS/VS column), filtered to one context when the sidebar's own VS sub-navigation under this
  // cluster picks one -- no second tab strip is rendered here to pick the same thing again.
  // sharedAddressOnly still separates Check Point's own per-member address columns (plus Cluster
  // VIP) from Palo Alto's single shared Address column; only the merge/filter structure is shared.
  // Product Owner, 2026-09-22: a Check Point cluster's own view carries only the physical
  // context -- its virtual systems are their own nodes in the sidebar, each with its own view.
  // Palo Alto keeps the merged vsys view (a vsys is not a separate node there).
  if (!activeContext) {
    if (!isPaloAlto) {
      const physical = resolveContext("physical");
      return <ClusterInterfacesTable interfaces={physical ? withRoutePrefixes(physical).interfaces : []} members={members} />;
    }
    const merged = tabs.flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return withRoutePrefixes(found).interfaces.map((iface) => ({ ...iface, vsysLabel: tab.label }));
    });
    return <ClusterInterfacesTable interfaces={merged} members={members} sharedAddressOnly={isPaloAlto} />;
  }

  const found = resolveContext(activeContext);
  return found
    ? <ClusterInterfacesTable interfaces={withRoutePrefixes(found).interfaces} members={members} sharedAddressOnly={isPaloAlto} />
    : renderUncollected(activeContext);
}

export function ClusterRoutesPanel({
  contexts,
  members = [],
  clusterRef,
  virtualSystems = [],
  activeContext,
  onSelectContext,
  isPaloAlto = false,
}: {
  readonly contexts: readonly ClusterContext[];
  readonly members?: readonly { readonly device_id: string; readonly hostname?: string | null }[];
  readonly clusterRef?: string;
  readonly virtualSystems?: readonly string[];
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
}) {
  const { tabs, resolveContext } = useMemo(
    () => buildUnifiedContextTabs(contexts, virtualSystems),
    [contexts, virtualSystems]
  );

  if (tabs.length === 0) {
    return <EmptyPanel title="No routing evidence" body="No cluster member has been collected yet." />;
  }

  const renderUncollected = (name: string) => (
    <Box
      sx={{
        p: 2.5,
        bgcolor: m3.scLowest,
        borderRadius: "16px",
        border: `1px solid ${m3.outlineVar}`,
        display: "flex",
        flexDirection: "column",
        gap: 2,
      }}
    >
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
        <Chip
          label={name.toLowerCase().includes("vsys") ? "VSYS" : "VSX"}
          size="small"
          sx={{
            fontWeight: 700,
            bgcolor: m3.scHigh,
            color: m3.onSurface,
            borderRadius: "6px"
          }}
        />
        <Typography variant="h6" sx={{ fontWeight: 700, color: m3.onSurface }}>
          Routing Topology: {name}
        </Typography>
      </Box>
      <Typography variant="body2" color="text.secondary">
        Virtual System <strong>{name}</strong> operates on cluster <strong>{clusterRef ?? "Cluster"}</strong>. No routing evidence collected yet.
      </Typography>
    </Box>
  );

  // Same merge/filter structure as ClusterInterfacesPanel, for both vendors: merged by default,
  // filtered to one context when the sidebar's own VS sub-navigation picks one.
  if (!activeContext) {
    if (!isPaloAlto) {
      const physical = resolveContext("physical");
      return <ClusterRoutesTable routes={physical?.routes ?? []} members={members} />;
    }
    const merged = tabs.flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return found.routes.map((route) => ({ ...route, vsysLabel: tab.label }));
    });
    return <ClusterRoutesTable routes={merged} members={members} />;
  }

  const found = resolveContext(activeContext);
  return found
    ? <ClusterRoutesTable routes={found.routes} members={members} />
    : renderUncollected(activeContext);
}

/** WORKER.md "Frontend": "a chip naming the members" -- the cluster row's own membership marker. */
export function ClusterMembersMarker({ inventory }: { readonly inventory: ClusterInventory }) {
  return (
    <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1, alignItems: "center" }}>
      {inventory.members.map((member) => (
        <Box
          key={member.device_id}
          sx={{
            display: "inline-flex",
            alignItems: "center",
            gap: 0.75,
            px: 1,
            py: 0.25,
            borderRadius: "8px",
            bgcolor: m3.memberContainer,
            color: m3.onMemberContainer,
          }}
        >
          <Typography variant="caption" sx={{ fontWeight: 600 }}>
            {deviceNameLabel(member.hostname)}
          </Typography>
          <JobStatusIndicator
            state={member.latest_job_state}
            type={member.latest_job_type}
            terminalReason={member.latest_job_terminal_reason}
          />
        </Box>
      ))}
    </Stack>
  );
}

/**
 * "Collect now" (WORKER.md "Frontend"): submits {@code POST /devices/{id}/
 * inventory/collect}, then polls {@code GET /devices/{id}} until its job
 * reaches a terminal state -- the same cancellable-interval pattern
 * `AddDeviceDialog` uses to watch a submitted job.
 */
export function CollectNowButton({ deviceId, onCollected, enrollmentState }: { readonly deviceId: string; readonly onCollected: () => void; readonly enrollmentState?: string }) {
  const [phase, setPhase] = useState<"idle" | "submitting" | "polling" | "error">("idle");
  const [jobState, setJobState] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [overrideEnrolled, setOverrideEnrolled] = useState(false);
  const isDraft = !overrideEnrolled && enrollmentState === "DRAFT";

  useEffect(() => {
    setOverrideEnrolled(false);
    setError(null);
  }, [deviceId]);

  // Check the latest job state on mount so that past failures are immediately visible and not hanging
  useEffect(() => {
    let cancelled = false;
    getDevice(deviceId)
      .then((result) => {
        if (cancelled) return;
        if (result.enrollment_state === "ENROLLED") {
          setOverrideEnrolled(true);
        }
        if (result.job) {
          setJobState(result.job.state);
          if (!isTerminalJobState(result.job.state)) {
            setPhase("polling");
          } else if (result.job.state === "FAILED" || result.job.state === "REJECTED") {
            const reason = result.job.terminal_reason ? `: ${result.job.terminal_reason}` : "";
            setError(`Last run failed (${result.job.state})${reason}`);
          }
        }
      })
      .catch(() => {
        // ignore initial query errors
      });
    return () => {
      cancelled = true;
    };
  }, [deviceId]);

  useEffect(() => {
    if (phase !== "polling") return undefined;
    let cancelled = false;

    const tick = () => {
      getDevice(deviceId)
        .then((result) => {
          if (cancelled) return;
          if (result.enrollment_state === "ENROLLED") {
            setOverrideEnrolled(true);
          }
          setJobState(result.job?.state ?? null);
          if (result.job !== null && isTerminalJobState(result.job.state)) {
            if (result.job.state === "FAILED" || result.job.state === "REJECTED") {
              const reason = result.job.terminal_reason ? `: ${result.job.terminal_reason}` : "";
              setError(`Operation failed (${result.job.state})${reason}`);
              setPhase("error");
            } else {
              setPhase("idle");
              setError(null);
              setOverrideEnrolled(true);
              onCollected();
            }
          }
        })
        .catch(() => {
          // Transient poll failure: keep polling rather than abandoning the flow.
        });
    };

    tick();
    const intervalId = setInterval(tick, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
  }, [phase, deviceId, onCollected]);

  const handleClick = async () => {
    setError(null);
    setPhase("submitting");
    try {
      if (isDraft) {
        await retryDeviceConfirm(deviceId);
      } else {
        await requestInventoryCollect(deviceId);
      }
      setPhase("polling");
    } catch (err) {
      const apiErr = err as Partial<ApiError>;
      if (isDraft && apiErr.body?.error === "VALIDATION_FAILED" && apiErr.body?.reason_code === "DEVICE_NOT_DRAFT") {
        setOverrideEnrolled(true);
        setPhase("idle");
        setError(null);
        onCollected();
        return;
      }
      setError(describeApiError(err));
      setPhase("error");
    }
  };

  const isRunning = phase === "submitting" || phase === "polling";
  const buttonLabel = isRunning
    ? isDraft
      ? `Confirming (${jobPhaseLabel(jobState ?? "REQUESTED")})...`
      : jobPhaseLabel(jobState ?? "REQUESTED")
    : isDraft
    ? error
      ? "Retry confirmation"
      : "Confirm enrollment"
    : "Collect now";

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, alignItems: "flex-start" }}>
      <M3Button
        emphasis="outlined"
        icon="download"
        onClick={handleClick}
        disabled={isRunning}
      >
        {buttonLabel}
      </M3Button>
      {isDraft && !error && !isRunning && (
        <Typography variant="body2" color="text.secondary">
          Device is pending enrollment confirmation.
        </Typography>
      )}
      {error && (
        <Typography variant="body2" color="error" sx={{ maxWidth: 450, wordBreak: "break-word" }}>
          {error}
        </Typography>
      )}
    </Box>
  );
}

/**
 * The detail panels for one selected device (WORKER.md "Frontend"):
 * fetches `GET /devices/{id}/inventory`, or the cluster view when the
 * device carries a `cluster_member_ref`. Remounted by its caller (`key={
 * device.device_id}`) on every selection change so `useFetchOnMount`'s own
 * mount effect re-runs -- it never re-triggers on a changed fetcher alone.
 */

/** One header for every inventory entity (standalone device or cluster): avatar, title, opaque
 * reference badge, status chips, an optional action on the right and optional body rows below
 * (member cards) -- so a standalone device and a cluster read the same way. */
export function InventoryEntityHeader({
  vendorHint,
  model,
  titlePrefix,
  title,
  reference,
  referenceTitle,
  chips,
  action,
  children,
}: {
  readonly vendorHint: string;
  readonly model?: string | null;
  readonly titlePrefix?: string;
  readonly title: string;
  readonly reference: string;
  readonly referenceTitle: string;
  readonly chips: React.ReactNode;
  readonly action?: React.ReactNode;
  readonly children?: React.ReactNode;
}) {
  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        gap: 1.5,
        p: 2,
        bgcolor: m3.scLowest,
        borderRadius: "10px",
        border: `1px solid ${m3.outlineVar}`,
        boxShadow: "none",
      }}
    >
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 2, flexWrap: "wrap" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, minWidth: 0 }}>
          <VendorAvatar vendorHint={vendorHint} model={model} hostname={title} />
          <Box sx={{ minWidth: 0 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
              <Typography variant="h5" sx={{ fontWeight: 700, color: m3.onSurface }}>
                {titlePrefix ? `${titlePrefix} ` : ""}{title}
              </Typography>
              {reference && (
              <Typography
                variant="caption"
                sx={{
                  fontFamily: MONO,
                  fontSize: "0.75rem",
                  color: m3.primary,
                  bgcolor: m3.scHigh,
                  px: 0.75,
                  py: 0.2,
                  borderRadius: "4px",
                  border: `1px solid ${m3.outlineVar}`,
                }}
                title={referenceTitle}
              >
                {reference}
              </Typography>
              )}
              {chips}
            </Box>
          </Box>
        </Box>
        {action}
      </Box>
      {children}
    </Box>
  );
}

/** The detail tabs of the one device screen: the Configuration tab only when the vendor has a configuration read. */
function DetailTabs({ ariaLabel, tabs, configuration, initialTab }: {
  readonly ariaLabel: string;
  readonly tabs: ReadonlyArray<{ label: string; panel: React.ReactNode }>;
  readonly configuration?: React.ReactNode;
  readonly initialTab?: string | null;
}) {
  const shown = tabs.filter((t) => t.label !== "Configuration" || Boolean(configuration));
  const wanted = initialTab === "configuration" ? shown.findIndex((t) => t.label === "Configuration") : -1;
  return <M3Tabs key={wanted} ariaLabel={ariaLabel} initial={Math.max(0, wanted)} tabs={shown} />;
}

export function DeviceInventoryPanels({
  device,
  initialVs,
  onDeviceStateChange,
  configuration,
  initialTab,
}: {
  readonly device: DeviceSummary;
  readonly initialVs?: string;
  readonly onDeviceStateChange?: () => void;
  /** One device screen (PO 2026-09-25): the configuration tab's content, when the vendor has a configuration read. */
  readonly configuration?: React.ReactNode;
  /** "configuration" opens that tab (old Config links land here). */
  readonly initialTab?: string | null;
}) {
  const isCluster = device.cluster_member_ref !== null;
  const isPaloAlto = device.vendor_hint === "palo_alto";
  const [activeContext, setActiveContext] = useState<string | null>(initialVs ?? null);

  // Re-clicking the device's own row (not a VS) sends initialVs=undefined -- that must clear
  // back to Physical too, not just switching to a different VS's value.
  useEffect(() => {
    setActiveContext(initialVs ?? null);
  }, [initialVs]);

  const deviceInventoryFetch = useFetchOnMount<DeviceInventory>(
    () => getDeviceInventory(device.device_id),
    describeApiError,
  );
  const clusterInventoryFetch = useFetchOnMount<ClusterInventory>(
    () => getClusterInventory(device.cluster_member_ref as string),
    describeApiError,
  );

  const deviceInventory = deviceInventoryFetch.data;
  const clusterInventory = clusterInventoryFetch.data;
  const error = isCluster ? clusterInventoryFetch.error : deviceInventoryFetch.error;
  const refresh = () => {
    deviceInventoryFetch.refresh();
    if (isCluster) clusterInventoryFetch.refresh();
    onDeviceStateChange?.();
  };

  if (error) {
    return (
      <EmptyPanel title="Inventory unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  const deviceContexts = deviceInventory?.contexts ?? [];
  const deviceIfaceCount = deviceContexts.reduce((acc, c) => acc + c.interfaces.length, 0);
  const deviceRouteCount = deviceContexts.reduce((acc, c) => acc + c.routes.length, 0);
  const isEnrolledDevice = device.enrollment_state === "ENROLLED";
  // Live = device evidence has been read: interface addresses, or (HTTPS appliances and management servers whose
  // evidence is members / managed devices, not interfaces) a completed inventory run.
  const isLiveDevice = isEnrolledDevice && (Boolean(device.ip_addresses) || Boolean(deviceInventory?.collected_at));

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={device.vendor_hint}
        model={device.model}
        title={deviceNameLabel(device.hostname)}
        reference={device.device_id}
        referenceTitle={`Device ID: ${device.device_id}`}
        chips={
          <>
            <JobStatusIndicator
              state={device.latest_job_state}
              type={device.latest_job_type}
              terminalReason={device.latest_job_terminal_reason}
              size="medium"
            />
            {onboardingChip(device.onboarding) ? (
              <span title={onboardingChip(device.onboarding)?.title}>
                <StatusChip tone={onboardingChip(device.onboarding)!.tone} label={onboardingChip(device.onboarding)!.label} dense />
              </span>
            ) : (
              <StatusChip
                tone={isLiveDevice ? "ok" : "warn"}
                label={!isEnrolledDevice ? enrollmentStateLabel(device.enrollment_state) : isLiveDevice ? "Live" : "Confirmed · Not collected"}
                dense
              />
            )}
            {isEnrolledDevice && <StatusChip tone="ok" label="✓ Identity verified" dense />}
            <StatusChip
              tone="neutral"
              label={
                device.cluster_member_ref
                  ? `${isPaloAlto ? "Palo Alto PAN-OS HA" : "Check Point ClusterXL"} · ${device.cluster_member_ref}`
                  : `${isPaloAlto ? "Palo Alto" : vendorDisplayName(device.vendor_hint)} · ${device.role === "management_server" ? "Management server"
                    : device.role === "appliance" ? "Appliance" : "Standalone"}`
              }
              dense
            />
            {device.software_version && (
              <StatusChip tone="neutral" label={`${device.software_version}${isPaloAlto ? " · PAN-OS" : device.vendor_hint === "check_point" ? " · Gaia" : ""}`} dense />
            )}
            {(device.model || device.platform_family) && <StatusChip tone="neutral" label={(device.model || device.platform_family)!} dense />}
            {device.ha_role && <RoleChip role={device.ha_role} dense />}
            <StatusChip tone="neutral" label={`${deviceIfaceCount} interfaces · ${deviceRouteCount} routes`} dense />
            {isCluster && clusterInventory && <ClusterMembersMarker inventory={clusterInventory} />}
          </>
        }
        action={<CollectNowButton deviceId={device.device_id} onCollected={refresh} enrollmentState={device.enrollment_state} />}
      >
        {isCluster && clusterInventory && clusterInventory.members.length > 0 ? (
          <Typography variant="caption" color="text.secondary">
            Members: {orderMembers(clusterInventory.members).map((m) => deviceNameLabel(m.hostname)).join(" · ")}
          </Typography>
        ) : null}
      </InventoryEntityHeader>

      <DetailTabs
        ariaLabel="Device detail"
        configuration={configuration}
        initialTab={initialTab}
        tabs={[
          {
            label: "Interfaces",
            panel: isCluster
              ? <ClusterInterfacesPanel contexts={clusterInventory?.contexts ?? []} members={clusterInventory ? orderMembers(clusterInventory.members) : undefined} virtualSystems={clusterInventory?.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />
              : <InterfacesPanel contexts={deviceInventory?.contexts ?? []} virtualSystems={deviceInventory?.virtual_systems ?? device.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} member={device} />,
          },
          {
            label: "Routing",
            panel: isCluster
              ? <ClusterRoutesPanel contexts={clusterInventory?.contexts ?? []} members={clusterInventory ? orderMembers(clusterInventory.members) : undefined} virtualSystems={clusterInventory?.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />
              : <RoutesPanel contexts={deviceInventory?.contexts ?? []} virtualSystems={deviceInventory?.virtual_systems ?? device.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} member={device} />,
          },
          {
            label: device.vendor_hint === "infoblox" ? "Grid members"
              : (device.vendor_hint === "bluecoat" || device.vendor_hint === "fortinet") && device.role === "management_server" ? "Managed devices" : "Cluster members",
            panel: device.vendor_hint === "infoblox"
              ? <GridMembersPanel members={deviceInventory?.grid_members ?? []} />
              : (device.vendor_hint === "bluecoat" || device.vendor_hint === "fortinet") && device.role === "management_server"
              ? <ManagedDevicesPanel members={deviceInventory?.grid_members ?? []} />
              : isCluster && clusterInventory
              ? (
                <Stack spacing={2}>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Hostname / Member</TableCell>
                        <TableCell>Device ID</TableCell>
                        <TableCell>Latest Job Status</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {orderMembers(clusterInventory.members).map((member) => (
                        <TableRow key={member.device_id}>
                          <TableCell sx={{ fontWeight: 500 }}>{deviceNameLabel(member.hostname)}</TableCell>
                          <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{member.device_id}</TableCell>
                          <TableCell>
                            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                              <JobStatusIndicator
                                state={member.latest_job_state}
                                type={member.latest_job_type}
                                terminalReason={member.latest_job_terminal_reason}
                              />
                              <Typography variant="caption">
                                {member.latest_job_state ?? "No recent jobs"}
                                {member.latest_job_terminal_reason ? ` (${member.latest_job_terminal_reason})` : ""}
                              </Typography>
                            </Box>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Stack>
              )
              : (
                <EmptyPanel
                  title="No cluster membership evidence"
                  body="Membership needs an identity-verified read from each peer; none has been collected
                        yet."
                />
              ),
          },
          {
            label: "Configuration",
            panel: configuration,
          },
          {
            label: "Backup",
            panel: <BackupPanel deviceId={device.device_id} />,
          },
          {
            label: "Identity & provenance",
            panel: (
              <Stack spacing={1}>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                  Platform identity as read from the device itself; a value not read is UNKNOWN.
                </Typography>
                <DeviceIdentityTable devices={[device]} ariaLabel="Device identity" />
              </Stack>
            ),
          },
        ]}
      />
    </Stack>
  );
}

export function ClusterDetailPanels({
  clusterRef,
  members,
  initialVs,
  cache,
  onCacheUpdate,
  configuration,
  initialTab,
}: {
  readonly clusterRef: string;
  readonly members: readonly DeviceSummary[];
  readonly initialVs?: string | null;
  /** One device screen (PO 2026-09-25): the members' configuration side by side, as a tab. */
  readonly configuration?: React.ReactNode;
  readonly initialTab?: string | null;
  readonly cache?: Map<string, ClusterInventory>;
  readonly onCacheUpdate?: (ref: string, inv: ClusterInventory) => void;
}) {
  const cachedData = cache?.get(clusterRef);
  const [clusterInventory, setClusterInventory] = useState<ClusterInventory | null>(cachedData ?? null);
  const [error, setError] = useState<string | null>(null);
  const [activeVsContext, setActiveVsContext] = useState<string | null>(initialVs ?? null);

  // Re-clicking the cluster's own row (not a VS) sends initialVs=undefined -- that must clear
  // back to Physical too, not just switching to a different VS's value.
  useEffect(() => {
    setActiveVsContext(initialVs ?? null);
  }, [initialVs]);

  const fetchInventory = useCallback(async () => {
    try {
      const data = await getClusterInventory(clusterRef);
      setClusterInventory(data);
      onCacheUpdate?.(clusterRef, data);
      setError(null);
    } catch (err) {
      if (!cachedData && !clusterInventory) {
        setError(describeApiError(err));
      }
    }
  }, [clusterRef, onCacheUpdate, cachedData, clusterInventory]);

  useEffect(() => {
    fetchInventory();
  }, [clusterRef]);

  const refresh = () => {
    fetchInventory();
  };

  if (error) {
    return (
      <EmptyPanel title="Cluster inventory unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  // Review §3: members always M1, M2 (by name); the role chip says which one is active.
  const orderedMembers = orderMembers(members);
  const inventoryMembers = clusterInventory ? orderMembers(clusterInventory.members) : orderedMembers;
  const firstMember = orderedMembers[0];
  const isPaloAlto = firstMember?.vendor_hint === "palo_alto";
  const allContexts = clusterInventory?.contexts ?? [];
  const ifaceCount = allContexts.reduce((acc, c) => acc + c.interfaces.length, 0);
  const routeCount = allContexts.reduce((acc, c) => acc + c.routes.length, 0);
  const isEnrolled = members.some((m) => m.enrollment_state === "ENROLLED");
  const isLive = isEnrolled && ifaceCount > 0;
  const clusterTitle = deriveClusterTitle(clusterRef, members);

  // Collect all distinct virtual systems from members & clusterInventory
  const vsSet = new Set<string>();
  if (clusterInventory?.virtual_systems) {
    for (const vs of clusterInventory.virtual_systems) {
      if (vs) vsSet.add(vs);
    }
  }
  for (const m of members) {
    if (m.virtual_systems) {
      for (const vs of m.virtual_systems.split(/,\s*/)) {
        if (vs.trim()) vsSet.add(vs.trim());
      }
    }
  }
  const virtualSystems = Array.from(vsSet).sort();

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={firstMember?.vendor_hint ?? "check_point"}
        model={firstMember?.model}
        titlePrefix="CLS >"
        title={clusterTitle}
        reference={clusterRef}
        referenceTitle={`Verified API Cluster Reference: ${clusterRef}`}
        chips={
          <>
            <StatusChip
              tone={isLive ? "ok" : "warn"}
              label={!isEnrolled ? "Not enrolled" : isLive ? "Live" : "Confirmed · Not collected"}
              dense
            />
            <StatusChip
              tone="neutral"
              label={firstMember?.vendor_hint === "palo_alto" ? "Palo Alto PAN-OS HA" : "Check Point ClusterXL"}
              dense
            />
            {firstMember?.software_version && (
              <StatusChip tone="neutral" label={`${firstMember.software_version} · ${firstMember?.vendor_hint === "palo_alto" ? "PAN-OS" : "Gaia"}`} dense />
            )}
            {(firstMember?.model || firstMember?.platform_family) && <StatusChip tone="neutral" label={(firstMember?.model || firstMember?.platform_family)!} dense />}
            <StatusChip tone="neutral" label={`${ifaceCount} interfaces · ${routeCount} routes`} dense />
          </>
        }
      >
        {/* 2 Member Cards Sub-row */}
        <ClusterContextStrip clusterRef={clusterRef} current="inventory" />
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, gap: 1.5, pt: 0.5 }}>
          {orderedMembers.map((m) => {
            return (
              <Box
                key={m.device_id}
                data-member-card={m.device_id}
                sx={{
                  p: 1.5,
                  borderRadius: "10px",
                  bgcolor: m3.scLow,
                  border: "1px solid",
                  borderColor: m3.outlineVar,
                  display: "flex",
                  flexDirection: "column",
                  gap: 0.75,
                }}
              >
                <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                    <Typography variant="body2" sx={{ fontWeight: 700, color: m3.onSurface }}>
                      {deviceNameLabel(m.hostname)}
                    </Typography>
                    <JobStatusIndicator
                      state={m.latest_job_state}
                      type={m.latest_job_type}
                      terminalReason={m.latest_job_terminal_reason}
                    />
                  </Box>
                  <RoleChip role={m.ha_role} dense />
                </Box>
                <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {m.model ?? "Unknown model"} · {m.software_version ?? "Unknown version"}
                  </Typography>
                  <CollectNowButton deviceId={m.device_id} onCollected={refresh} enrollmentState={m.enrollment_state} />
                </Box>
              </Box>
            );
          })}
        </Box>

        {/* No redundant VS selector here: the sidebar's own VS sub-navigation under this cluster
            already sets activeVsContext, and duplicating that control here just for a second click
            target -- next to a third one inside the panel below -- is exactly the confusing repetition
            this screen must not have. */}
      </InventoryEntityHeader>

      <DetailTabs
        ariaLabel="Cluster detail"
        configuration={configuration}
        initialTab={initialTab}
        tabs={[
          {
            label: "Interfaces",
            panel: (
              <ClusterInterfacesPanel
                contexts={allContexts}
                members={inventoryMembers}
                clusterRef={clusterTitle}
                virtualSystems={virtualSystems}
                activeContext={activeVsContext}
                onSelectContext={setActiveVsContext}
                isPaloAlto={isPaloAlto}
              />
            ),
          },
          {
            label: "Routing",
            panel: (
              <ClusterRoutesPanel
                contexts={allContexts}
                members={inventoryMembers}
                clusterRef={clusterTitle}
                virtualSystems={virtualSystems}
                activeContext={activeVsContext}
                onSelectContext={setActiveVsContext}
                isPaloAlto={isPaloAlto}
              />
            ),
          },
          {
            label: "Cluster members",
            panel: (
              <Stack spacing={2}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell sx={{ fontWeight: 600 }}>Member Hostname</TableCell>
                      <TableCell sx={{ fontWeight: 600 }}>Device ID</TableCell>
                      <TableCell sx={{ fontWeight: 600 }}>HA Role</TableCell>
                      <TableCell sx={{ fontWeight: 600 }}>Model / Version</TableCell>
                      <TableCell sx={{ fontWeight: 600 }}>Status</TableCell>
                      <TableCell sx={{ fontWeight: 600 }} align="right">Action</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {orderedMembers.map((m) => (
                      <TableRow key={m.device_id} hover>
                        <TableCell sx={{ fontWeight: 600 }}>{deviceNameLabel(m.hostname)}</TableCell>
                        <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{m.device_id}</TableCell>
                        <TableCell>
                          <RoleChip role={m.ha_role} dense />
                        </TableCell>
                        <TableCell>{m.model ?? "UNKNOWN"} · {m.software_version ?? "UNKNOWN"}</TableCell>
                        <TableCell>
                          <Stack direction="row" spacing={1} alignItems="center">
                            <StatusChip tone={m.enrollment_state === "ENROLLED" ? "ok" : "warn"} label={m.enrollment_state} dense />
                            <JobStatusIndicator state={m.latest_job_state} type={m.latest_job_type} terminalReason={m.latest_job_terminal_reason} />
                          </Stack>
                        </TableCell>
                        <TableCell align="right">
                          <CollectNowButton deviceId={m.device_id} onCollected={refresh} enrollmentState={m.enrollment_state} />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Stack>
            ),
          },
          {
            label: "Configuration",
            panel: configuration,
          },
          {
            label: "Identity & provenance",
            panel: (
              <Stack spacing={1}>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                  {orderedMembers.length} members · {firstMember?.vendor_hint === "palo_alto" ? "Palo Alto PAN-OS HA" : "Check Point ClusterXL"}
                  {virtualSystems.length > 0 ? ` · ${virtualSystems.length} ${isPaloAlto ? "VSYS" : "VSX"}: ${virtualSystems.join(", ")}` : ""}
                  {" "}· each value is read from that member itself; a value not read is UNKNOWN.
                </Typography>
                <DeviceIdentityTable devices={orderedMembers} ariaLabel="Member identity" />
              </Stack>
            ),
          },
        ]}
      />
    </Stack>
  );
}

const BACKUP_REASON_MIN = 8;

/**
 * BK-12 manual backup trigger (14K BW-4). Collects the operator's reason,
 * refuses locally if it is shorter than {@link BACKUP_REASON_MIN} characters
 * after stripping (matching the server's own guard), and posts to the collect
 * route. A 409 shows the server's own `code` and `reason` verbatim; an
 * unrecognised response code still renders. A 202 tells the operator the
 * request was accepted and that the result appears in the list when the job
 * completes. Nothing here offers a restore, a download, or a decrypt.
 */
function RequestBackupControl({ deviceId, onAdmitted }: { readonly deviceId: string; readonly onAdmitted: () => void }) {
  const [reason, setReason] = useState("");
  const [phase, setPhase] = useState<"idle" | "submitting" | "admitted" | "refused" | "error">("idle");
  const [serverMessage, setServerMessage] = useState<string | null>(null);

  const stripped = reason.trim();
  const tooShort = stripped.length < BACKUP_REASON_MIN;

  const handleSubmit = async () => {
    if (tooShort) return;
    setPhase("submitting");
    setServerMessage(null);
    try {
      await collectDeviceBackup(deviceId, stripped);
      setPhase("admitted");
      setReason("");
      onAdmitted();
    } catch (err) {
      const apiErr = err as Partial<ApiError>;
      if (apiErr.status === 409) {
        const code = typeof apiErr.body?.code === "string" ? (apiErr.body.code as string) : "";
        const serverReason = typeof apiErr.body?.reason === "string" ? (apiErr.body.reason as string) : "";
        setServerMessage(code ? `${code}: ${serverReason}` : serverReason || "request refused");
        setPhase("refused");
      } else {
        setServerMessage(describeApiError(err));
        setPhase("error");
      }
    }
  };

  return (
    <Stack spacing={1.5}>
      <Typography variant="body2" color="text.secondary">
        Request a backup for this device. Provide a reason of at least {BACKUP_REASON_MIN} characters.
      </Typography>
      <Stack direction="row" spacing={1} alignItems="flex-start" sx={{ flexWrap: "wrap" }}>
        <TextField
          label="Reason"
          value={reason}
          onChange={(e) => {
            setReason(e.target.value);
            if (phase === "admitted" || phase === "refused" || phase === "error") setPhase("idle");
            setServerMessage(null);
          }}
          size="small"
          sx={{ minWidth: 320 }}
          disabled={phase === "submitting"}
          inputProps={{ "aria-label": "Backup reason" }}
        />
        <M3Button
          emphasis="tonal"
          onClick={handleSubmit}
          disabled={tooShort || phase === "submitting"}
        >
          Request backup
        </M3Button>
      </Stack>
      {tooShort && reason.length > 0 && (
        <Typography variant="body2" color="text.secondary">
          Reason must be at least {BACKUP_REASON_MIN} characters (after trimming).
        </Typography>
      )}
      {phase === "admitted" && (
        <Typography variant="body2" color="success.main">
          Backup request accepted. The result appears in the list below when the job completes.
        </Typography>
      )}
      {(phase === "refused" || phase === "error") && serverMessage && (
        <Typography variant="body2" color="error">
          {serverMessage}
        </Typography>
      )}
    </Stack>
  );
}

export function BackupPanel({ deviceId }: { readonly deviceId: string }) {
  const fetcher = useFetchOnMount<{ backups: BackupArtefact[] }>(
    () => listDeviceBackups(deviceId),
    describeApiError,
  );

  if (fetcher.error) {
    return (
      <EmptyPanel title="Backup unavailable" body={fetcher.error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={fetcher.refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  if (!fetcher.data) {
    return <EmptyPanel title="Backups" body="Loading…" />;
  }

  const backups = fetcher.data.backups;

  return (
    <Stack spacing={2.5}>
      <RequestBackupControl deviceId={deviceId} onAdmitted={fetcher.refresh} />
      {backups.length === 0 ? (
        <EmptyPanel
          title="No backups"
          body="No backup has been retained for this device."
        />
      ) : (
        <>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Collected time</TableCell>
                <TableCell>Size</TableCell>
                <TableCell>Digest prefix</TableCell>
                <TableCell>Validation level</TableCell>
                <TableCell>Deviation state</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {backups.map((b) => (
                <TableRow key={b.artefact_id}>
                  <TableCell><Ts at={b.collected_at} /></TableCell>
                  <TableCell>{b.size_bytes}</TableCell>
                  <TableCell>{b.digest_prefix}</TableCell>
                  <TableCell>{b.validation_level}</TableCell>
                  <TableCell>
                    {b.deviation_state === null ? (
                      <StatusChip tone="neutral" label="not evaluated" dense />
                    ) : (
                      <StatusChip tone="neutral" label={b.deviation_state} dense />
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Typography variant="body2" color="text.secondary">
            Backup archives are compared by digest only: unchanged means identical bytes and changed means different bytes, not archive contents.
          </Typography>
        </>
      )}
    </Stack>
  );
}
