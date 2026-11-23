import { memo, useCallback, useEffect, useMemo, useState } from "react";
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
import { PaginationFooter } from "../shell/DataTable";
import { SortHeaderCell, useTableSort, type SortColumn } from "../shell/tableSort";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { plural } from "../shell/plural";
import { MONO, m3 } from "../theme/m3Theme";
import { orderMembers } from "./DeviceShared";
import { JobStatusIndicator } from "../shell/JobStatusIndicator";
import { type ClusterContext, type ClusterInterface, type ClusterInventory, type ClusterRoute, type InventoryAddress } from "../auth/adminApi";
import { DifferencesNote, buildUnifiedContextTabs, memberLabel, presenceLabel, type LabeledMember } from "./InventoryPanels";

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

/** V148: the zone(s) the members report for this interface; one value when they agree, "—" when none. */
function zoneLabel(iface: { readonly member_zones?: Record<string, string> }): string {
  const zones = Array.from(new Set(Object.values(iface.member_zones ?? {})));
  return zones.length > 0 ? zones.join(" / ") : "—";
}

/** V148: each member's own link-layer address; labelled per member only when there is more than one. */
function macCell(iface: { readonly member_macs?: Record<string, string> },
                 members: readonly LabeledMember[]): React.ReactNode {
  const entries = members
    .map((m) => [m, iface.member_macs?.[m.device_id]] as const)
    .filter((e): e is readonly [typeof e[0], string] => typeof e[1] === "string");
  if (entries.length === 0) return <Typography variant="body2" color="text.secondary">—</Typography>;
  return (
    <Stack spacing={0.25}>
      {entries.map(([m, mac]) => (
        <Typography key={m.device_id} variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.75rem" }}
          title={entries.length > 1 ? memberLabel(m, members) : undefined}>
          {mac}
        </Typography>
      ))}
    </Stack>
  );
}

function vlanLabel(iface: { readonly name: string; readonly kind: string; readonly vlan_id?: number | null }): string {
  if (iface.vlan_id !== undefined && iface.vlan_id !== null) return String(iface.vlan_id);
  if (iface.kind === "vlan" && iface.name.includes(".")) return iface.name.slice(iface.name.lastIndexOf(".") + 1);
  return "—";
}

/** The label the unified context tabs give the chassis's own interfaces (see buildUnifiedContextTabs). */
export const PHYSICAL_GROUP_LABEL = "Physical / VS0";

/** The routing tables page at this many routes. */
export const ROUTES_PAGE_SIZE = 50;

type SortableRoute = ClusterRoute & { readonly vsysLabel?: string };
/**
 * The routing table's sortable columns (tableSort.tsx), each by what its cell shows: prefixes and addresses with numeric
 * collation (10.0.2.0/24 before 10.0.10.0/24), a route without a next hop or interface last, and the alignment column
 * with the shared routes first ascending and the differing ones first descending.
 */
const ROUTE_SORT: ReadonlyArray<SortColumn<SortableRoute>> = [
  { key: "scope", value: (r) => r.vsysLabel ?? "Physical" },
  { key: "destination", value: (r) => r.destination },
  { key: "next_hop", value: (r) => r.next_hop },
  { key: "interface", value: (r) => r.interface },
  { key: "protocol", value: (r) => r.protocol },
  { key: "alignment", value: (r) => (r.presence !== "all" || r.differences.length > 0 ? 1 : 0), kind: "number" },
];

/**
 * A virtual system / VDOM scope in a table cell: the name only (the column header says VSYS), cut with an ellipsis, full
 * text on hover. `full` is for a group header row that spans the table (post-release review 2026-10-18, R18): there the
 * name has the whole row and is never cut.
 */
function ScopeTag({ label, full = false }: { readonly label: string; readonly full?: boolean }) {
  const name = label.replace(/^(?:VSYS|VS|VDOM):\s*/i, "");
  return (
    <Box component="span" title={label} data-scope-tag={full ? "full" : "cell"}
      sx={{ display: "inline-block", maxWidth: full ? "none" : 132, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", verticalAlign: "middle",
            px: 1, height: 22, lineHeight: "22px", borderRadius: "8px", fontSize: 11, fontWeight: 500, bgcolor: m3.scHigh, color: m3.onSurface }}>
      {name}
    </Box>
  );
}

export function ClusterInterfacesTable({
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
  const [addressOnly, setAddressOnly] = useState(false);
  const [search, setSearch] = useState("");
  // Groups the operator flipped away from their default (physical collapsed, every virtual system open).
  const [flipped, setFlipped] = useState<ReadonlySet<string>>(new Set());
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
  // selected VS's own table never needs it since every row already shares that context. That view is grouped by VSYS,
  // the physical group collapsed by default (UI review 2026-10-18, F-19): 18 rows of dashes no longer lead the table.
  const grouped = withoutLoopback.some((iface) => iface.vsysLabel !== undefined);
  const showZoneColumn = withoutLoopback.some((iface) => Object.keys(iface.member_zones ?? {}).length > 0);
  const showMacColumn = withoutLoopback.some((iface) => Object.keys(iface.member_macs ?? {}).length > 0);

  const hasAddress = (iface: ClusterInterface) =>
    iface.addresses.length > 0 || Object.values(iface.member_addresses ?? {}).some((list) => list.length > 0);
  const filtered = withoutLoopback.filter((iface) => {
    if (addressOnly && !hasAddress(iface)) return false;
    if (upOnly) {
      const memberStates = members.map((m) => iface.member_states?.[m.device_id]).filter((v): v is string => typeof v === "string");
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
          Interfaces · {filtered.length} of {withoutLoopback.length} shown{upOnly ? " · Active only" : ""}{addressOnly ? " · With address only" : ""}
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center">
          <TextField
            size="small"
            placeholder="Filter interfaces..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            sx={{ "& .MuiInputBase-root": { height: 32, fontSize: "0.75rem", borderRadius: "8px" } }}
          />
          <M3Button
            emphasis={upOnly ? "filled" : "outlined"}
            onClick={() => setUpOnly(!upOnly)}
          >
            {upOnly ? "✓ Active only" : "All ports"}
          </M3Button>
          <Box component="button" type="button" aria-pressed={addressOnly} onClick={() => setAddressOnly(!addressOnly)}
            sx={{ cursor: "pointer", font: "inherit", fontSize: 13, px: 1.5, height: 32, borderRadius: "16px", color: m3.onSurface,
              border: `1px solid ${addressOnly ? m3.primary : m3.outline}`, bgcolor: addressOnly ? m3.secondaryContainer : "transparent" }}>
            With address only
          </Box>
        </Stack>
      </Box>

      {/* Horizontal scroll is the fallback; the cells below keep an address, a zone or a network on one line so a
          value is never wrapped into a column of fragments or cut at the pane edge (review 2026-10-17, 1440 px).
          NETWORK and STATE come straight after the addresses and the MAC column closes the row (UI review 2026-10-18,
          F-5: NETWORK was the column the pane edge cut), and the scope tag is shortened to its name. */}
      <TableContainer sx={{ maxWidth: "100%", overflowX: "auto" }}>
      <Table size="small" sx={{ "& th, & td": { px: 0.75 }, "& th": { whiteSpace: "nowrap" } }}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ fontWeight: 600 }}>Interface</TableCell>
            <TableCell sx={{ fontWeight: 600 }}>Kind</TableCell>
            <TableCell sx={{ fontWeight: 600 }}>VLAN</TableCell>
            {showZoneColumn && <TableCell sx={{ fontWeight: 600 }}>Zone</TableCell>}
            {oneAddressColumn ? (
              <TableCell sx={{ fontWeight: 600 }}>Address</TableCell>
            ) : (
              <>
                {showVipColumn && <TableCell sx={{ fontWeight: 600 }}>Cluster VIP</TableCell>}
                {members.map((m) => (
                  <TableCell key={m.device_id} sx={{ fontWeight: 600 }}>
                    {memberLabel(m, members)}
                  </TableCell>
                ))}
              </>
            )}
            <TableCell sx={{ fontWeight: 600, minWidth: 112 }}>Network</TableCell>
            <TableCell sx={{ fontWeight: 600 }}>State</TableCell>
            {showMacColumn && <TableCell sx={{ fontWeight: 600 }}>MAC</TableCell>}
          </TableRow>
        </TableHead>
        <TableBody>
          {(() => {
            const renderRow = (iface: (typeof filtered)[number]) => {
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
            const memberStates = members.map((m) => iface.member_states?.[m.device_id]).filter((v): v is string => typeof v === "string");
            const normalizedStates = memberStates.map((s) => s.toLowerCase());
            const distinctStates = new Set(normalizedStates);
            const isMixed = memberStates.length > 0 && distinctStates.size > 1;
            const sharedState = memberStates.length > 0 && distinctStates.size === 1 ? normalizedStates[0] : null;
            const allUp = sharedState === "up";
            const allDown = sharedState === "down";

            return (
              <TableRow key={`${iface.vsysLabel ?? ""}:${iface.name}`} hover>
                <TableCell sx={{ fontWeight: 600, fontFamily: "monospace", whiteSpace: "nowrap" }}>{iface.name}</TableCell>
                <TableCell>{iface.kind}</TableCell>
                <TableCell>{vlanLabel(iface)}</TableCell>
                {showZoneColumn && <TableCell sx={{ whiteSpace: "nowrap" }}>{zoneLabel(iface)}</TableCell>}
                {oneAddressColumn ? (
                  <TableCell sx={{ whiteSpace: "nowrap" }}>
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
                            <Typography key={a.address} variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.75rem" }}>
                              {a.address}
                            </Typography>
                          ))}
                          {vips.map((a) => (
                            <Stack key={a.address} direction="row" spacing={0.75} alignItems="center">
                              <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.75rem" }}>{a.address}</Typography>
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
                    {showVipColumn && <TableCell sx={{ whiteSpace: "nowrap" }}>
                      {vips.length > 0 ? (
                        <Stack direction="row" spacing={0.75} alignItems="center">
                          <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.75rem" }}>
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
                        <TableCell key={m.device_id} sx={{ whiteSpace: "nowrap" }}>
                          {mAddrs.length > 0 ? (
                            // Review §3: the coloured dot after the address carried the member's link state by colour
                            // alone; the word is now on hover here and, when members differ, in the State column.
                            <Typography variant="body2" title={mState ? `${memberLabel(m, members)} link state: ${mState}` : undefined}
                              sx={{ fontFamily: MONO, fontSize: "0.75rem" }}>
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
                <TableCell sx={{ fontFamily: "monospace", fontSize: "0.75rem", whiteSpace: "nowrap", minWidth: 112 }}>
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
                            {memberLabel(m, members)}: {iface.member_states[m.device_id]}
                          </Typography>
                        ) : null)}
                      </Stack>
                    ) : sharedState ? (
                      <StatusChip tone="neutral" label={sharedState.charAt(0).toUpperCase() + sharedState.slice(1)} dense />
                    ) : (
                      <StatusChip tone={iface.presence === "all" ? "ok" : "warn"} label={presenceLabel(iface.presence)} dense />
                    )}
                    {iface.differences.length > 0 && (
                      <DifferencesNote differences={iface.differences} members={members} />
                    )}
                  </Stack>
                </TableCell>
                {showMacColumn && <TableCell sx={{ whiteSpace: "nowrap" }}>{macCell(iface, members)}</TableCell>}
              </TableRow>
            );
            };
            if (!grouped) return filtered.map(renderRow);
            // Group by VSYS in the order the contexts came; a group with no row left after the filters disappears with them.
            const groups: { label: string; rows: typeof filtered }[] = [];
            for (const iface of filtered) {
              const label = iface.vsysLabel ?? "Physical";
              const group = groups.find((g) => g.label === label);
              if (group) group.rows.push(iface); else groups.push({ label, rows: [iface] });
            }
            const columns = 4 + (showZoneColumn ? 1 : 0) + (oneAddressColumn ? 1 : (showVipColumn ? 1 : 0) + members.length) + (showMacColumn ? 1 : 0);
            return groups.flatMap((group) => {
              // The physical group starts collapsed when virtual systems follow it; on its own there is nothing to make way for.
              const physical = group.label === PHYSICAL_GROUP_LABEL && groups.length > 1;
              // A search or the address chip is asking for rows: a group that holds them opens, whatever its default.
              const open = search.trim() !== "" || addressOnly ? true : physical === flipped.has(group.label);
              const withAddress = group.rows.filter(hasAddress).length;
              return [
                <TableRow key={`group:${group.label}`} data-vsys-group={group.label}>
                  <TableCell colSpan={columns} sx={{ p: 0, bgcolor: m3.scLow }}>
                    <Box component="button" type="button" aria-expanded={open} onClick={() => setFlipped((prev) => {
                        const next = new Set(prev);
                        if (next.has(group.label)) next.delete(group.label); else next.add(group.label);
                        return next;
                      })}
                      sx={{ all: "unset", boxSizing: "border-box", width: "100%", display: "flex", alignItems: "center", gap: 1, px: 1, py: 0.75, cursor: "pointer",
                        "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: -2 } }}>
                      <Box component="span" aria-hidden sx={{ color: m3.onSurfaceVar, width: 12 }}>{open ? "▾" : "▸"}</Box>
                      <ScopeTag label={group.label} full />
                      <Typography component="span" variant="body2" sx={{ color: m3.onSurfaceVar }}>
                        {plural(group.rows.length, "interface")} · {withAddress} with an address{!open ? " · collapsed" : ""}
                      </Typography>
                    </Box>
                  </TableCell>
                </TableRow>,
                ...(open ? group.rows.map(renderRow) : []),
              ];
            });
          })()}
        </TableBody>
      </Table>
      </TableContainer>
    </Stack>
  );
}

type RouteRowRoute = ClusterRoute & { readonly vsysLabel?: string };
type RouteRowMembers = readonly { readonly device_id: string; readonly hostname?: string | null }[];

/**
 * One routing-table row. Memoised: a header click moves the same rows around (their keys do not change) and only the
 * rows whose route, alignment words or columns changed are drawn again.
 */
const RouteRow = memo(function RouteRow({ route, diffLabel, isDiff, showVsysColumn, singleMember, members }: {
  readonly route: RouteRowRoute;
  readonly diffLabel: string | null;
  readonly isDiff: boolean;
  readonly showVsysColumn: boolean;
  readonly singleMember: boolean;
  readonly members: RouteRowMembers;
}) {
  return (
    <TableRow
      hover
      sx={{
        bgcolor: isDiff ? m3.errorContainer : "inherit",
      }}
    >
      {showVsysColumn && (
        <TableCell>
          <ScopeTag label={route.vsysLabel ?? "Physical"} />
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
              <DifferencesNote differences={route.differences} members={members} />
            )}
          </Stack>
        ) : (
          // A route both members carry is the expected case (R11): plain text; colour only where they differ.
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Shared</Typography>
        )}
      </TableCell>}
    </TableRow>
  );
});

const NO_MEMBERS: RouteRowMembers = [];

export function ClusterRoutesTable({
  routes,
  members = NO_MEMBERS,
}: {
  readonly routes: readonly (ClusterRoute & { readonly vsysLabel?: string })[];
  readonly members?: readonly { readonly device_id: string; readonly hostname?: string | null }[];
}) {
  const [diffOnly, setDiffOnly] = useState<boolean>(false);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  useEffect(() => { setPage(0); }, [diffOnly, search, routes.length]);
  const firstPage = useCallback(() => setPage(0), []);
  const sort = useTableSort(ROUTE_SORT, { onChange: firstPage });

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
  members.forEach((m) => memberNameById.set(m.device_id, memberLabel(m, members)));

  // 371 routes were one 16,915 px page (UI review 2026-10-18, F-15): the table pages at ROUTES_PAGE_SIZE, the filter
  // and the differences switch still run over every route, a header click orders every filtered route, and the page
  // counter follows the filtered list.
  const pageCount = Math.max(1, Math.ceil(displayedRoutes.length / ROUTES_PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const pageRoutes = sort.apply(displayedRoutes).slice(safePage * ROUTES_PAGE_SIZE, (safePage + 1) * ROUTES_PAGE_SIZE);
  const seen = new Map<string, number>();

  return (
    <Stack spacing={1.5}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {singleMember ? "Routing table" : "Unified Routing Table"} · {plural(routes.length, "route")}
            {displayedRoutes.length !== routes.length ? ` · ${displayedRoutes.length} match` : ""}
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
        <>
        <Table size="small">
          <TableHead>
            <TableRow>
              {showVsysColumn && <SortHeaderCell state={sort.header("scope")} label={routes.some((r) => r.vsysLabel?.startsWith("VDOM:")) ? "VDOM" : "VSYS"} sx={{ fontWeight: 600 }} />}
              <SortHeaderCell state={sort.header("destination")} label="Destination" sx={{ fontWeight: 600 }} />
              <SortHeaderCell state={sort.header("next_hop")} label="Next hop" sx={{ fontWeight: 600 }} />
              <SortHeaderCell state={sort.header("interface")} label="Interface" sx={{ fontWeight: 600 }} />
              <SortHeaderCell state={sort.header("protocol")} label="Protocol" sx={{ fontWeight: 600 }} />
              {!singleMember && <SortHeaderCell state={sort.header("alignment")} label="Same on both members" words={{ asc: "shared first", desc: "differences first" }} sx={{ fontWeight: 600 }} />}
            </TableRow>
          </TableHead>
          <TableBody>
            {pageRoutes.map((route) => {
              const isShared = route.presence === "all";
              const isDiff = !isShared || route.differences.length > 0;
              let diffLabel = null;
              if (!isShared && Array.isArray(route.presence)) {
                const memberNames = route.presence.map((id) => memberNameById.get(id)).filter((name): name is string => Boolean(name)).join(", ");
                diffLabel = `DIFF > ${memberNames} only`;
              } else if (route.differences.length > 0) {
                diffLabel = `DIFF > ${route.differences.map((d) => `${d.field}: ${d.value}`).join(", ")}`;
              }
              // The key is the route's own content plus how many identical routes precede it on the page, not its position:
              // a reorder then moves a row instead of rebuilding it.
              const content = `${route.vsysLabel ?? ""}-${route.destination}-${route.next_hop ?? ""}-${route.interface ?? ""}`;
              const occurrence = seen.get(content) ?? 0;
              seen.set(content, occurrence + 1);
              return (
                <RouteRow key={`${content}-${occurrence}`} route={route} diffLabel={diffLabel} isDiff={isDiff} showVsysColumn={showVsysColumn}
                  singleMember={singleMember} members={members} />
              );
            })}
          </TableBody>
        </Table>
        <Box component="nav" aria-label="Routes pages" sx={{ display: "flex", px: 1, py: 0.5 }}>
          <PaginationFooter offset={safePage * ROUTES_PAGE_SIZE} size={ROUTES_PAGE_SIZE} total={displayedRoutes.length} onPage={(offset) => setPage(Math.floor(offset / ROUTES_PAGE_SIZE))} />
        </Box>
        </>
      )}
    </Stack>
  );
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

  const memberNames = orderMembers(members).map((m) => memberLabel(m, members)).join(", ");
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
            {memberLabel(member, inventory.members)}
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
