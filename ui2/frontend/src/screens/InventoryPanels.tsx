import { useCallback, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { deviceNameLabel, jobPhaseLabel, isTerminalJobState } from "../shell/deviceCopy";
import { m3 } from "../theme/m3Theme";
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

export function VendorAvatar({
  vendorHint,
  model,
  hostname,
}: {
  readonly vendorHint: string;
  readonly model?: string | null;
  readonly hostname?: string | null;
}) {
  let label = "DEV";
  let bg: string = m3.outline;

  const isVsx =
    (model && model.toUpperCase().includes("VSX")) ||
    (hostname && hostname.toUpperCase().includes("VSX"));

  if (vendorHint === "check_point") {
    if (isVsx) {
      label = "VSX";
      bg = m3.vsx;
    } else {
      label = "CP";
      bg = m3.cp;
    }
  } else if (vendorHint === "palo_alto") {
    label = "PAN";
    bg = m3.pan;
  }

  return (
    <Box
      sx={{
        width: 32,
        height: 32,
        borderRadius: "8px",
        bgcolor: bg,
        color: "#ffffff",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontWeight: 700,
        fontSize: 11,
        letterSpacing: 0.5,
        flexShrink: 0,
      }}
    >
      {label}
    </Box>
  );
}

function AddressChip({ address }: { readonly address: InventoryAddress }) {
  return (
    <Stack direction="row" spacing={0.75} alignItems="center">
      <Typography variant="body2">{address.address}</Typography>
      {address.role === "cluster_virtual" && <StatusChip tone="mem" label="VIP" dense />}
    </Stack>
  );
}

function InterfacesTable({
  interfaces,
}: {
  readonly interfaces: readonly (InventoryInterface & { readonly vsysLabel?: string })[];
}) {
  const [upOnly, setUpOnly] = useState(true);
  const [search, setSearch] = useState("");

  // The loopback interface is collected evidence but never operator-relevant on this screen,
  // for either vendor -- present on every device, never carrying a difference worth surfacing.
  const withoutLoopback = interfaces.filter((iface) => iface.kind !== "loopback");

  if (withoutLoopback.length === 0) {
    return <EmptyPanel title="No interface evidence" body="This context has no collected interfaces yet." />;
  }

  const showVsysColumn = withoutLoopback.some((iface) => iface.vsysLabel !== undefined);

  const filtered = withoutLoopback.filter((iface) => {
    if (upOnly && iface.state?.toLowerCase() === "down") {
      return false;
    }
    if (search.trim()) {
      const q = search.toLowerCase();
      const matchName = iface.name.toLowerCase().includes(q);
      const matchKind = iface.kind?.toLowerCase().includes(q);
      const matchAddr = iface.addresses.some((a) => a.address.toLowerCase().includes(q));
      return matchName || matchKind || matchAddr;
    }
    return true;
  });

  return (
    <Stack spacing={1.5}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          Interfaces · {filtered.length} {upOnly && withoutLoopback.length > filtered.length && `(${withoutLoopback.length - filtered.length} down hidden)`}
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
            {showVsysColumn && <TableCell>VSYS</TableCell>}
            <TableCell>Name</TableCell>
            <TableCell>Kind</TableCell>
            <TableCell>State</TableCell>
            <TableCell>VLAN</TableCell>
            <TableCell>Addresses</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {filtered.map((iface) => (
            <TableRow key={`${iface.vsysLabel ?? ""}:${iface.name}`}>
              {showVsysColumn && (
                <TableCell>
                  <StatusChip tone="neutral" label={iface.vsysLabel ?? "Physical"} dense />
                </TableCell>
              )}
              <TableCell>{iface.name}</TableCell>
              <TableCell>{iface.kind}</TableCell>
              <TableCell>
                <StatusChip tone={iface.state === "up" ? "ok" : iface.state === "down" ? "bad" : "neutral"} label={iface.state} dense />
              </TableCell>
              <TableCell>{iface.vlan_id ?? "—"}</TableCell>
              <TableCell>
                <Stack spacing={0.5}>
                  {iface.addresses.length === 0 ? "—" : iface.addresses.map((a) => <AddressChip key={a.address} address={a} />)}
                </Stack>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
}

/** device_inventory_ha (migration V17): the selected context's own HA role, shown above its interfaces. */
function ContextHaBadge({ context }: { readonly context: InventoryContext | undefined }) {
  const ha = context?.ha;
  if (!ha) return null;
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <Typography variant="body2" color="text.secondary">HA role:</Typography>
      <StatusChip tone={ha.role === "ACTIVE" ? "ok" : "neutral"} label={ha.role} dense />
      {ha.cluster_mode && (
        <Typography variant="body2" color="text.secondary">
          {ha.cluster_mode}
        </Typography>
      )}
    </Stack>
  );
}

function RoutesTable({
  routes,
}: {
  readonly routes: readonly (InventoryRoute & { readonly vsysLabel?: string })[];
}) {
  if (routes.length === 0) {
    return <EmptyPanel title="No routing evidence" body="This context has no collected routes yet." />;
  }
  const showVsysColumn = routes.some((route) => route.vsysLabel !== undefined);
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          {showVsysColumn && <TableCell>VSYS</TableCell>}
          <TableCell>Destination</TableCell>
          <TableCell>Next hop</TableCell>
          <TableCell>Interface</TableCell>
          <TableCell>Protocol</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {routes.map((route, index) => (
          <TableRow key={`${route.vsysLabel ?? ""}-${route.destination}-${route.next_hop ?? ""}-${route.interface ?? ""}-${index}`}>
            {showVsysColumn && (
              <TableCell>
                <StatusChip tone="neutral" label={route.vsysLabel ?? "Physical"} dense />
              </TableCell>
            )}
            <TableCell>{route.destination}</TableCell>
            <TableCell>{route.next_hop ?? "—"}</TableCell>
            <TableCell>{route.interface ?? "—"}</TableCell>
            <TableCell>
              <StatusChip tone="neutral" label={route.protocol} dense />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
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
      const memberStates = iface.member_states ? Object.values(iface.member_states) : [];
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
          Interfaces · {filtered.length} {upOnly && withoutLoopback.length > filtered.length && `(${withoutLoopback.length - filtered.length} down hidden)`}
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
            {sharedAddressOnly ? (
              <TableCell sx={{ fontWeight: 600 }}>Address</TableCell>
            ) : (
              <>
                <TableCell sx={{ fontWeight: 600 }}>Cluster VIP</TableCell>
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
            const memberStates = iface.member_states ? Object.values(iface.member_states) : [];
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
                {sharedAddressOnly ? (
                  <TableCell>
                    {cidrAddress ? (
                      <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>
                        {cidrAddress}
                      </Typography>
                    ) : (
                      <Typography variant="body2" color="text.secondary">—</Typography>
                    )}
                  </TableCell>
                ) : (
                  <>
                    <TableCell>
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
                    </TableCell>
                    {members.map((m) => {
                      const mAddrs = iface.member_addresses?.[m.device_id] ?? [];
                      const mState = iface.member_states?.[m.device_id];
                      return (
                        <TableCell key={m.device_id}>
                          {mAddrs.length > 0 ? (
                            <Stack direction="row" spacing={0.75} alignItems="center">
                              <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.8125rem" }}>
                                {mAddrs.map((a) => a.address).join(", ")}
                              </Typography>
                              {mState && (
                                <Box
                                  sx={{
                                    width: 7,
                                    height: 7,
                                    borderRadius: "50%",
                                    bgcolor: mState.toLowerCase() === "up" ? m3.success : m3.outline,
                                  }}
                                  title={`State: ${mState}`}
                                />
                              )}
                            </Stack>
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
                      <StatusChip tone="warn" label="Degraded" dense />
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
            Unified Routing Table · {routes.length} routes
          </Typography>
          <Stack direction="row" spacing={0.5} sx={{ bgcolor: m3.scLow, p: 0.5, borderRadius: "10px" }}>
            <Box
              role="button"
              tabIndex={0}
              onClick={() => setDiffOnly(false)}
              sx={{
                px: 1.5,
                py: 0.5,
                borderRadius: "8px",
                cursor: "pointer",
                bgcolor: !diffOnly ? "#ffffff" : "transparent",
                boxShadow: !diffOnly ? "0 1px 3px rgba(0,0,0,0.1)" : "none",
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
                bgcolor: diffOnly ? "#ffffff" : "transparent",
                boxShadow: diffOnly ? "0 1px 3px rgba(0,0,0,0.1)" : "none",
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
                    bgcolor: "#fee2e2",
                    color: "#991b1b",
                    fontSize: "0.75rem",
                    fontWeight: 700,
                  }}
                >
                  {diffCount}
                </Box>
              )}
            </Box>
          </Stack>
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
        <Box sx={{ p: 2.5, bgcolor: "#ecfdf5", border: "1px solid #a7f3d0", borderRadius: "10px", textAlign: "center" }}>
          <Typography variant="body2" sx={{ color: "#065f46", fontWeight: 600 }}>
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
              <TableCell sx={{ fontWeight: 600 }}>Cluster Alignment / Diff</TableCell>
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
                    bgcolor: isDiff ? "#fffbeb" : "inherit",
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
                  <TableCell>
                    {isDiff && diffLabel ? (
                      <Stack direction="row" spacing={0.75} alignItems="center">
                        <Box
                          sx={{
                            display: "inline-flex",
                            alignItems: "center",
                            px: 1,
                            py: 0.25,
                            borderRadius: "6px",
                            bgcolor: "#fef3c7",
                            border: "1px solid #f59e0b",
                            color: "#92400e",
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
                  </TableCell>
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
}: {
  readonly contexts: readonly InventoryContext[];
  readonly virtualSystems?: readonly string[] | string | null;
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
}) {
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

  // Check Point's VSX virtual systems stay their own tabbed contexts, Physical showing only the
  // chassis's own interfaces; Palo Alto's vsys merges by default, matching the single-device
  // reference view (see ClusterInterfacesPanel for the same vendor split on the cluster path).
  if (!isPaloAlto) {
    return (
      <ContextTabs
        contexts={tabs}
        activeContext={activeContext}
        onSelectContext={onSelectContext}
        render={(name) => {
          const context = resolveContext(name);
          return (
            <Stack spacing={1}>
              <ContextHaBadge context={context} />
              <InterfacesTable interfaces={context?.interfaces ?? []} />
            </Stack>
          );
        }}
      />
    );
  }

  if (!activeContext) {
    const merged = tabs.flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return found.interfaces.map((iface) => ({ ...iface, vsysLabel: tab.label }));
    });
    return (
      <Stack spacing={1}>
        <ContextHaBadge context={resolveContext("physical")} />
        <InterfacesTable interfaces={merged} />
      </Stack>
    );
  }
  const context = resolveContext(activeContext);
  return (
    <Stack spacing={1}>
      <ContextHaBadge context={context} />
      <InterfacesTable interfaces={context?.interfaces ?? []} />
    </Stack>
  );
}

export function RoutesPanel({
  contexts,
  virtualSystems,
  activeContext,
  onSelectContext,
  isPaloAlto = false,
}: {
  readonly contexts: readonly InventoryContext[];
  readonly virtualSystems?: readonly string[] | string | null;
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
}) {
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
    return (
      <ContextTabs
        contexts={tabs}
        activeContext={activeContext}
        onSelectContext={onSelectContext}
        render={(name) => <RoutesTable routes={resolveContext(name)?.routes ?? []} />}
      />
    );
  }

  if (!activeContext) {
    const merged = tabs.flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return found.routes.map((route) => ({ ...route, vsysLabel: tab.label }));
    });
    return <RoutesTable routes={merged} />;
  }
  const context = resolveContext(activeContext);
  return <RoutesTable routes={context?.routes ?? []} />;
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
              bgcolor: name.toLowerCase().includes("vsys") ? "#fef3c7" : "#e0e7ff",
              color: name.toLowerCase().includes("vsys") ? "#92400e" : "#3730a3",
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

  // Check Point's VSX virtual systems are their own contexts -- Physical shows only the chassis's
  // own interfaces, never merged with a virtual system's. Selection is the sidebar's own VS
  // sub-navigation under this cluster (already driving activeContext); no second tab strip is
  // rendered here to pick the same thing again. Palo Alto's vsys genuinely shares the single-
  // device reference view instead: merged by default, tagged by a VSYS column.
  if (!isPaloAlto) {
    const found = resolveContext(activeContext ?? "physical");
    return found
      ? <ClusterInterfacesTable interfaces={found.interfaces} members={members} />
      : renderUncollected(activeContext ?? "physical");
  }

  if (!activeContext) {
    const merged = tabs.flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return found.interfaces.map((iface) => ({ ...iface, vsysLabel: tab.label }));
    });
    return <ClusterInterfacesTable interfaces={merged} members={members} sharedAddressOnly={isPaloAlto} />;
  }

  const found = resolveContext(activeContext);
  return found
    ? <ClusterInterfacesTable interfaces={found.interfaces} members={members} sharedAddressOnly={isPaloAlto} />
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
            bgcolor: name.toLowerCase().includes("vsys") ? "#fef3c7" : "#e0e7ff",
            color: name.toLowerCase().includes("vsys") ? "#92400e" : "#3730a3",
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

  // Same split as ClusterInterfacesPanel: Check Point's VS selection comes from the sidebar
  // alone, no second tab strip here to pick the same context again.
  if (!isPaloAlto) {
    const found = resolveContext(activeContext ?? "physical");
    return found
      ? <ClusterRoutesTable routes={found.routes} members={members} />
      : renderUncollected(activeContext ?? "physical");
  }

  if (!activeContext) {
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
export function DeviceInventoryPanels({
  device,
  initialVs,
  onDeviceStateChange,
}: {
  readonly device: DeviceSummary;
  readonly initialVs?: string;
  readonly onDeviceStateChange?: () => void;
}) {
  const isCluster = device.cluster_member_ref !== null;
  const isPaloAlto = device.vendor_hint === "palo_alto";
  const [activeContext, setActiveContext] = useState<string | null>(initialVs ?? null);

  useEffect(() => {
    if (initialVs) {
      setActiveContext(initialVs);
    }
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

  return (
    <Stack spacing={2}>
      <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
        <Typography variant="caption" color="text.secondary">
          Devices · {isPaloAlto ? "Palo Alto" : "Check Point"}
          {device.cluster_member_ref ? ` ${isPaloAlto ? "PAN-OS HA" : "ClusterXL"} (${device.cluster_member_ref})` : ""}
        </Typography>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 2, flexWrap: "wrap" }}>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <Typography variant="h3" sx={{ fontWeight: 600 }}>
                {deviceNameLabel(device.hostname)}
              </Typography>
              <Typography variant="caption" sx={{ fontFamily: "monospace", color: m3.primary }}>
                {device.device_id}
              </Typography>
              <JobStatusIndicator
                state={device.latest_job_state}
                type={device.latest_job_type}
                terminalReason={device.latest_job_terminal_reason}
                size="medium"
              />
            </Box>
            {isCluster && clusterInventory && clusterInventory.members.length > 0 ? (
              <Typography variant="caption" color="text.secondary">
                Members: {clusterInventory.members.map((m) => deviceNameLabel(m.hostname)).join(" · ")}
              </Typography>
            ) : null}
          </Box>
          <CollectNowButton deviceId={device.device_id} onCollected={refresh} enrollmentState={device.enrollment_state} />
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", pt: 0.5 }}>
          <StatusChip
            tone={device.enrollment_state === "ENROLLED" ? "ok" : "warn"}
            label={device.enrollment_state === "ENROLLED" ? "✓ Live" : device.enrollment_state}
            dense
          />
          <StatusChip tone="ok" label="✓ Identity verified" dense />
          {device.software_version && (
            <StatusChip tone="neutral" label={`${device.software_version} · ${isPaloAlto ? "PAN-OS" : "Gaia"}`} dense />
          )}
          {device.model && (
            <StatusChip tone="neutral" label={device.model} dense />
          )}
          {isCluster && clusterInventory && (
            <ClusterMembersMarker inventory={clusterInventory} />
          )}
        </Box>
      </Box>

      <M3Tabs
        ariaLabel="Device detail"
        tabs={[
          {
            label: "Interfaces",
            panel: isCluster
              ? <ClusterInterfacesPanel contexts={clusterInventory?.contexts ?? []} members={clusterInventory?.members} virtualSystems={clusterInventory?.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />
              : <InterfacesPanel contexts={deviceInventory?.contexts ?? []} virtualSystems={deviceInventory?.virtual_systems ?? device.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />,
          },
          {
            label: "Routing",
            panel: isCluster
              ? <ClusterRoutesPanel contexts={clusterInventory?.contexts ?? []} members={clusterInventory?.members} virtualSystems={clusterInventory?.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />
              : <RoutesPanel contexts={deviceInventory?.contexts ?? []} virtualSystems={deviceInventory?.virtual_systems ?? device.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />,
          },
          {
            label: "Cluster members",
            panel: isCluster && clusterInventory
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
                      {clusterInventory.members.map((member) => (
                        <TableRow key={member.device_id}>
                          <TableCell sx={{ fontWeight: 500 }}>{deviceNameLabel(member.hostname)}</TableCell>
                          <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{member.device_id}</TableCell>
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
            label: "Backup",
            panel: <BackupPanel deviceId={device.device_id} />,
          },
          {
            label: "Identity & provenance",
            panel: (
              <EmptyPanel
                title="No identity or provenance evidence"
                body="Identity requires a direct, verified device read; none has occurred yet."
              />
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
}: {
  readonly clusterRef: string;
  readonly members: readonly DeviceSummary[];
  readonly initialVs?: string | null;
  readonly cache?: Map<string, ClusterInventory>;
  readonly onCacheUpdate?: (ref: string, inv: ClusterInventory) => void;
}) {
  const cachedData = cache?.get(clusterRef);
  const [clusterInventory, setClusterInventory] = useState<ClusterInventory | null>(cachedData ?? null);
  const [error, setError] = useState<string | null>(null);
  const [activeVsContext, setActiveVsContext] = useState<string | null>(initialVs ?? null);

  useEffect(() => {
    if (initialVs !== undefined) {
      setActiveVsContext(initialVs);
    }
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

  const firstMember = members[0];
  const isPaloAlto = firstMember?.vendor_hint === "palo_alto";
  const allContexts = clusterInventory?.contexts ?? [];
  const ifaceCount = allContexts.reduce((acc, c) => acc + c.interfaces.length, 0);
  const routeCount = allContexts.reduce((acc, c) => acc + c.routes.length, 0);
  const isEnrolled = members.some((m) => m.enrollment_state === "ENROLLED");
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
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          gap: 1.5,
          p: 2,
          bgcolor: m3.scLowest,
          borderRadius: "16px",
          border: `1px solid ${m3.outlineVar}`,
          boxShadow: "0 1px 3px rgba(0,0,0,0.04)",
        }}
      >
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 2, flexWrap: "wrap" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
            <VendorAvatar
              vendorHint={firstMember?.vendor_hint ?? "check_point"}
              model={firstMember?.model}
              hostname={clusterTitle}
            />
            <Box>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                <Typography variant="h5" sx={{ fontWeight: 700, color: m3.onSurface }}>
                  CLS &gt; {clusterTitle}
                </Typography>
                <Typography
                  variant="caption"
                  sx={{
                    fontFamily: "monospace",
                    fontSize: "0.75rem",
                    color: m3.primary,
                    bgcolor: m3.scHigh,
                    px: 0.75,
                    py: 0.2,
                    borderRadius: "4px",
                    border: `1px solid ${m3.outlineVar}`,
                  }}
                  title={`Verified API Cluster Reference: ${clusterRef}`}
                >
                  {clusterRef}
                </Typography>
                <StatusChip
                  tone={isEnrolled ? "ok" : "warn"}
                  label={isEnrolled ? "✓ Live" : "Not enrolled"}
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
                {firstMember?.model && (
                  <StatusChip tone="neutral" label={firstMember.model} dense />
                )}
                <StatusChip tone="neutral" label={`${ifaceCount} interfaces · ${routeCount} routes`} dense />
              </Box>
            </Box>
          </Box>
        </Box>

        {/* 2 Member Cards Sub-row */}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" }, gap: 1.5, pt: 0.5 }}>
          {members.map((m) => {
            const isActive = m.ha_role?.toUpperCase() === "ACTIVE";
            return (
              <Box
                key={m.device_id}
                sx={{
                  p: 1.5,
                  borderRadius: "12px",
                  bgcolor: isActive ? "#f0fdf4" : m3.scLow,
                  border: "1px solid",
                  borderColor: isActive ? "#86efac" : m3.outlineVar,
                  display: "flex",
                  flexDirection: "column",
                  gap: 0.75,
                  transition: "all 0.15s ease-in-out",
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
                  <StatusChip
                    tone={isActive ? "ok" : "mem"}
                    label={m.ha_role ? m.ha_role.toUpperCase() : "MEMBER"}
                    dense
                  />
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
      </Box>

      <M3Tabs
        ariaLabel="Cluster detail"
        tabs={[
          {
            label: "Interfaces",
            panel: (
              <ClusterInterfacesPanel
                contexts={allContexts}
                members={clusterInventory?.members ?? members}
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
                members={clusterInventory?.members ?? members}
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
                    {members.map((m) => (
                      <TableRow key={m.device_id} hover>
                        <TableCell sx={{ fontWeight: 600 }}>{deviceNameLabel(m.hostname)}</TableCell>
                        <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{m.device_id}</TableCell>
                        <TableCell>
                          <StatusChip
                            tone={m.ha_role === "ACTIVE" || m.ha_role === "active" ? "ok" : "neutral"}
                            label={m.ha_role ?? "—"}
                            dense
                          />
                        </TableCell>
                        <TableCell>{m.model ?? "—"} · {m.software_version ?? "—"}</TableCell>
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
            label: "Identity & provenance",
            panel: (
              <EmptyPanel
                title="Identity and cluster provenance"
                body={`Cluster identity verified across ${members.length} members. Observed from active ${firstMember?.vendor_hint === "palo_alto" ? "Palo Alto PAN-OS HA" : "Check Point ClusterXL"} runtime topology.`}
              />
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
                  <TableCell>{b.collected_at}</TableCell>
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
