import { useState, useEffect, useMemo, useRef } from "react";
import { urlParam } from "../shell/urlParams";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import { ManagementTreePanel } from "./ManagementTreePanel";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { MONO, m3 } from "../theme/m3Theme";
import { RoleChip } from "../shell/States";
import { FilterBar, FilterRow, contentVersionText, downloadText, toCsv } from "./DeviceShared";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { useListSearch } from "../shell/listSearch";
import { requestBulkInventoryCollect, listDevices, getManagementTree, type ApiError, type DeviceSummary, type ClusterInventory, type ManagementTree, type ManagementTreeNode } from "../auth/adminApi";
import { deviceNameLabel, enrollmentStateLabel, enrollmentStateTone } from "../shell/deviceCopy";
import { JobStatusIndicator } from "../shell/JobStatusIndicator";
import { DeviceInventoryPanels, ClusterDetailPanels, VendorAvatar, deriveClusterTitle } from "./InventoryPanels";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const reasonCode = typeof apiErr.body?.reason_code === "string" ? (apiErr.body.reason_code as string) : undefined;
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

const VENDOR_LABEL: Record<string, string> = {
  check_point: "Check Point",
  palo_alto: "Palo Alto",
};

function vendorLabel(vendorHint: string): string {
  return VENDOR_LABEL[vendorHint] ?? vendorHint;
}

function collectionOutcome(device: DeviceSummary): string {
  const state = device.latest_job_state?.toUpperCase();
  if (!state) return "Not yet collected";
  if (state === "FAILED") {
    const reasonClass = device.latest_job_terminal_reason?.trim().toLowerCase().split(":", 1)[0] === "connect_failed"
      ? "Connection failed"
      : "Recorded failure";
    return `Collection attempt failed · ${reasonClass}`;
  }
  if (state === "COMPLETED" || state === "SUCCEEDED") return "Collection completed";
  return "Collection in progress";
}

function hasFailedCollection(device: DeviceSummary): boolean {
  return device.latest_job_state?.toUpperCase() === "FAILED";
}

// The sidebar's cluster row keeps only what tells the operator whether this cluster is worth a
// look: its name, that it is a cluster, and whether the connection is clean -- member count,
// virtual-system count and the raw cluster reference move to the detail view, which already
// shows every member's own version/serial/identity once selected.
// Same rule as the detail header (InventoryPanels): "Live" needs collected interface evidence, not
// just a successful confirm -- a cluster read as "Live" in this list while its own detail view said
// "Confirmed · Not collected" (Product Owner, 2026-09-22).
function clusterHasCollectedEvidence(members: readonly DeviceSummary[]): boolean {
  return members.some((m) => Boolean(m.ip_addresses && m.ip_addresses.trim().length > 0));
}

function clusterHealthTone(members: readonly DeviceSummary[]): "ok" | "warn" | "neutral" {
  const allEnrolled = members.every((m) => m.enrollment_state === "ENROLLED");
  const anyFailed = members.some(hasFailedCollection);
  if (!allEnrolled || anyFailed) return "warn";
  return clusterHasCollectedEvidence(members) ? "ok" : "neutral";
}

function clusterHealthLabel(members: readonly DeviceSummary[]): string {
  const anyFailed = members.some(hasFailedCollection);
  if (anyFailed) return "Collection issue";
  const allEnrolled = members.every((m) => m.enrollment_state === "ENROLLED");
  if (!allEnrolled) return "Not enrolled";
  return clusterHasCollectedEvidence(members) ? "Live" : "Confirmed · Not collected";
}

/** The list search: name, id, model, version, cluster, serial, management address or any interface address. */
export function deviceMatchesSearch(device: DeviceSummary, searchTerm: string): boolean {
  const term = searchTerm.toLowerCase().trim();
  if (!term) return true;
  return [device.hostname, device.device_id, device.model, device.platform_family, device.software_version,
    device.cluster_member_ref, device.serial_number, device.management_ip, device.ip_addresses, device.virtual_systems]
    .some((f) => Boolean(f && String(f).toLowerCase().includes(term)));
}

function DeviceRow({
  device,
  indented = false,
  selected = false,
  onSelect,
  trailingExtra,
}: {
  readonly device: DeviceSummary;
  readonly indented?: boolean;
  readonly selected?: boolean;
  readonly onSelect?: (device: DeviceSummary) => void;
  readonly trailingExtra?: React.ReactNode;
}) {
  const isEnrolled = device.enrollment_state === "ENROLLED";
  // "Live" means identity-verified AND at least one interface has been read -- confirm and
  // inventory collection are separate jobs (Evidence laws: "collection success != semantic
  // correctness"), so an enrolled device with zero collected interfaces is not yet "live" in any
  // sense a reader would expect from that word (Product Owner, 2026-09-22: "Live gözüküyor ama
  // cihazdan veri çekemiyorum" -- confirmed but never collected read as fully live).
  const isLive = isDeviceLive(device);

  // Review §3/§4: 44 px rows (two compact lines), the state chip only when the device is not Live -- when all
  // are Live the chip is noise; the list caption carries the Live count.
  return (
    <Box
      role={onSelect ? "button" : undefined}
      tabIndex={onSelect ? 0 : undefined}
      onClick={onSelect ? () => onSelect(device) : undefined}
      onKeyDown={
        onSelect
          ? (e) => {
              if (e.key === "Enter" || e.key === " ") onSelect(device);
            }
          : undefined
      }
      data-row="device"
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 1,
        height: 44,
        boxSizing: "border-box",
        px: 1,
        ml: indented ? 2.5 : 0,
        bgcolor: selected ? m3.primaryContainer : m3.scLowest,
        border: "1px solid",
        borderColor: selected ? m3.primary : m3.outlineVar,
        borderRadius: "8px",
        cursor: onSelect ? "pointer" : undefined,
        "&:hover": { borderColor: m3.primary },
      }}
    >
      <VendorAvatar vendorHint={device.vendor_hint} model={device.model} hostname={device.hostname} />

      <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", justifyContent: "center" }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1, minHeight: 20 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, minWidth: 0 }}>
            <Typography
              variant="body2"
              sx={{ fontWeight: 600, fontSize: 13, lineHeight: "18px", minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", color: m3.onSurface }}
            >
              {deviceNameLabel(device.hostname)}
            </Typography>
            <JobStatusIndicator
              state={device.latest_job_state}
              type={device.latest_job_type}
              terminalReason={device.latest_job_terminal_reason}
            />
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, flexShrink: 0 }}>
            {device.ha_role && <RoleChip role={device.ha_role} dense />}
            {!isLive && (
              <StatusChip
                tone={isEnrolled ? "neutral" : enrollmentStateTone(device.enrollment_state)}
                label={isEnrolled ? "Confirmed · Not collected" : enrollmentStateLabel(device.enrollment_state)}
                dense
              />
            )}
            {trailingExtra}
          </Box>
        </Box>
        <Typography variant="caption" sx={{ fontSize: 11, lineHeight: "15px", color: m3.onSurfaceVar, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          <span>{collectionOutcome(device)}</span>
          {" · "}
          <Box component="span" sx={{ fontFamily: MONO }}>{device.management_ip ?? "No IP"}</Box>
          {" · "}
          {device.role === "management_server" ? "Management server" : vendorLabel(device.vendor_hint)}
          {device.model ? ` · ${device.model}` : " · Unknown model"}
          {device.software_version ? ` · ${device.software_version}` : " · Unknown version"}
          {device.ha_role ? "" : " · No HA role"}
        </Typography>
      </Box>
    </Box>
  );
}

/** The device list as shown (current filters and sort), names as the server returned them (masked for aiview). */
export function inventoryCsv(devices: readonly DeviceSummary[]): string {
  return toCsv([
    ["hostname", "device_id", "vendor", "enrollment_state", "live", "model", "software_version", "ha_role", "cluster_ref",
      "management_ip", "serial_number", "hotfix_level", "content_versions", "uptime", "platform_facts_observed_at",
      "inventory_collected_at", "latest_job_state", "latest_job_terminal_reason"],
    ...devices.map((d) => {
      // A fact not read is written UNKNOWN (never blank or 0); a relation that does not exist stays empty.
      const u = (v: string | null | undefined) => (v === null || v === undefined || v === "" ? "UNKNOWN" : v);
      return [
        u(d.hostname), d.device_id, d.vendor_hint, d.enrollment_state, isDeviceLive(d) ? "yes" : "no", u(d.model), u(d.software_version),
        d.ha_role, d.cluster_member_ref, u(d.management_ip), u(d.serial_number),
        d.vendor_hint === "palo_alto" ? "" : u(d.hotfix_level), d.vendor_hint === "palo_alto" ? u(contentVersionText(d.content_versions)) : "",
        u(d.uptime_text), u(d.platform_facts_observed_at), u(d.inventory_collected_at), d.latest_job_state, d.latest_job_terminal_reason,
      ];
    }),
  ]);
}

/** "Live" = identity-verified AND at least one interface read (see DeviceRow). */
export function isDeviceLive(device: DeviceSummary): boolean {
  return device.enrollment_state === "ENROLLED" && Boolean(device.ip_addresses && device.ip_addresses.trim().length > 0);
}

/** Distinct clusters in a device list: every cluster reference, and those with at least one ENROLLED member. */
export function clusterCounts(devices: readonly DeviceSummary[]): { enrolled: number; active: number } {
  const all = new Set<string>();
  const active = new Set<string>();
  for (const d of devices) {
    if (!d.cluster_member_ref) continue;
    all.add(d.cluster_member_ref);
    if (d.enrollment_state === "ENROLLED") active.add(d.cluster_member_ref);
  }
  return { enrolled: all.size, active: active.size };
}

/** The Overview's inventory evidence age buckets, over the device's newest inventory run. */
export function inAgeBucket(device: DeviceSummary, bucket: string, now: number = Date.now()): boolean {
  if (device.enrollment_state !== "ENROLLED") return false;
  const at = device.inventory_collected_at ? new Date(device.inventory_collected_at).getTime() : null;
  const hours = at === null ? null : (now - at) / 3_600_000;
  switch (bucket) {
    case "stale": return hours === null || hours >= 24;
    case "lt24h": return hours !== null && hours < 24;
    case "24h_72h": return hours !== null && hours >= 24 && hours <= 72;
    case "gt72h": return hours !== null && hours > 72;
    case "never": return hours === null;
    default: return true;
  }
}

/** The device list's view: flat (default) or nested under each management server (PO, 2026-09-23). Remembered per browser. */
export type ListView = "flat" | "manager";
const LIST_VIEW_KEY = "nexus.deviceListView";

export function useListView(): [ListView, (v: ListView) => void] {
  const [view, setView] = useState<ListView>(() => {
    try {
      return window.localStorage.getItem(LIST_VIEW_KEY) === "manager" ? "manager" : "flat";
    } catch {
      return "flat";
    }
  });
  const set = (v: ListView) => {
    setView(v);
    try {
      window.localStorage.setItem(LIST_VIEW_KEY, v);
    } catch {
      // storage unavailable: the choice lasts for this page only
    }
  };
  return [view, set];
}

export function ListViewSelect({ value, onChange }: { readonly value: ListView; readonly onChange: (v: ListView) => void }) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
      <Typography variant="caption" color="text.secondary">View</Typography>
      <Box component="select" value={value} onChange={(e: React.ChangeEvent<HTMLSelectElement>) => onChange(e.target.value as ListView)}
        aria-label="Device list view"
        sx={{ fontSize: "12px", color: m3.onSurface, bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, borderRadius: "6px", px: 1, py: 0.4, cursor: "pointer" }}>
        <option value="flat">Flat list</option>
        <option value="manager">By manager</option>
      </Box>
    </Box>
  );
}

/** Which enrolled devices and clusters each management server's domains hold, from its managed-estate tree. */
export function managerGroups(tree: ManagementTree): Array<{ domain: string | null; deviceIds: Set<string> }> {
  return tree.domains.map((d) => {
    const ids = new Set<string>();
    const walk = (n: ManagementTreeNode) => {
      if (n.device_id) ids.add(n.device_id);
      n.children.forEach(walk);
    };
    d.nodes.forEach(walk);
    return { domain: d.domain, deviceIds: ids };
  }).filter((g) => g.deviceIds.size > 0);
}

type DeviceListProps = Parameters<typeof FlatDeviceList>[0] & {
  /** Nest what each management server (MDS, Panorama) manages under it, by domain (PO, 2026-09-23). Off while a filter is active. */
  readonly groupByManager?: boolean;
};

/**
 * The device list. With {@code groupByManager}, each management server is a top row; its domains and, in each, the
 * clusters and gateways it manages (the flat list's own rows, VS/VSYS included) nest under it. Devices no manager
 * lists stay at the top level. Membership comes from each manager's managed-estate tree; until it loads, or when it
 * cannot be read, the flat list is shown -- nothing is hidden.
 */
export function DeviceList(props: DeviceListProps) {
  const { groupByManager = false, devices, ...rest } = props;
  const managers = useMemo(() => devices.filter((d) => d.role === "management_server"), [devices]);
  const [trees, setTrees] = useState<ReadonlyMap<string, ManagementTree>>(new Map());
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());
  const managerKey = managers.map((m) => m.device_id).join(",");
  useEffect(() => {
    if (!groupByManager || managers.length === 0) return;
    let cancelled = false;
    Promise.all(managers.map((m) => getManagementTree(m.device_id).then((t) => [m.device_id, t] as const).catch(() => null)))
      .then((rows) => {
        if (cancelled) return;
        setTrees(new Map(rows.filter((r): r is readonly [string, ManagementTree] => r !== null)));
      });
    return () => { cancelled = true; };
  }, [groupByManager, managerKey]);

  if (!groupByManager || managers.length === 0 || trees.size === 0) return <FlatDeviceList devices={devices} {...rest} />;
  const byId = new Map(devices.map((d) => [d.device_id, d]));
  const placed = new Set<string>();
  const sections = managers.map((m) => {
    const tree = trees.get(m.device_id);
    const groups = tree ? managerGroups(tree).map((g) => {
      const members = [...g.deviceIds].map((id) => byId.get(id)).filter((d): d is DeviceSummary => d !== undefined && d.role !== "management_server");
      // a whole cluster moves with any of its members, so a cluster is never split across two places
      const refs = new Set(members.map((d) => d.cluster_member_ref).filter(Boolean));
      const all = devices.filter((d) => members.includes(d) || (d.cluster_member_ref && refs.has(d.cluster_member_ref)));
      all.forEach((d) => placed.add(d.device_id));
      return { domain: g.domain, devices: all };
    }).filter((g) => g.devices.length > 0) : [];
    placed.add(m.device_id);
    return { manager: m, groups };
  });
  const unmanaged = devices.filter((d) => !placed.has(d.device_id));
  const toggle = (k: string) => setOpen((prev) => { const n = new Set(prev); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  return (
    <Stack spacing={1.25}>
      {sections.map(({ manager, groups }) => {
        const expanded = open.has(manager.device_id);
        const total = groups.reduce((a, g) => a + g.devices.length, 0);
        return (
          <Box key={manager.device_id} data-row="manager" sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <Box data-row="manager-head" sx={{ display: "flex", alignItems: "center", gap: 1, height: 44, boxSizing: "border-box", px: 1,
              borderRadius: "8px", border: "1px solid", minWidth: 0,
              borderColor: rest.selectedDeviceId === manager.device_id ? m3.primary : m3.outlineVar,
              bgcolor: rest.selectedDeviceId === manager.device_id ? m3.primaryContainer : m3.scLow }}>
              <Box component="span" role="button" tabIndex={0} aria-expanded={expanded}
                aria-label={expanded ? "Collapse managed devices" : "Expand managed devices"}
                onClick={() => toggle(manager.device_id)}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") toggle(manager.device_id); }}
                sx={{ cursor: "pointer", width: 18, textAlign: "center", color: "text.secondary", fontSize: "0.8rem", "&:hover": { color: m3.primary } }}>
                {expanded ? "▾" : "▸"}
              </Box>
              <VendorAvatar vendorHint={manager.vendor_hint} model={manager.model} hostname={manager.hostname ?? manager.device_id} />
              <Box role="button" tabIndex={0} onClick={() => rest.onSelectDevice(manager)}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") rest.onSelectDevice(manager); }}
                sx={{ flex: 1, minWidth: 0, cursor: "pointer" }}>
                <Typography variant="body2" sx={{ fontWeight: 600, fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
                  title={manager.hostname ?? manager.device_id}>
                  {manager.hostname ?? "UNKNOWN"}
                </Typography>
                <Typography sx={{ fontSize: 11, color: m3.onSurfaceVar, whiteSpace: "nowrap" }}>
                  {manager.vendor_hint === "palo_alto" ? "Panorama" : "Management server (MDS)"}
                </Typography>
              </Box>
              <StatusChip tone="neutral" label={`${total} managed`} dense />
            </Box>
            {expanded && (
              <Box sx={{ pl: 2, display: "flex", flexDirection: "column", gap: 1, borderLeft: `2px solid ${m3.outlineVar}`, ml: 1 }}>
                {groups.map((g, i) => {
                  const k = `${manager.device_id}|${g.domain ?? i}`;
                  const domainOpen = groups.length === 1 || open.has(k);
                  return (
                    <Box key={k} data-domain={g.domain ?? ""} sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
                      {groups.length > 1 && (
                        <Box role="button" tabIndex={0} aria-expanded={domainOpen} onClick={() => toggle(k)}
                          onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") toggle(k); }}
                          sx={{ display: "flex", alignItems: "center", gap: 1, cursor: "pointer", px: 0.5, py: 0.25 }}>
                          <Box component="span" sx={{ color: m3.onSurfaceVar, width: 12, fontSize: 12 }}>{domainOpen ? "▾" : "▸"}</Box>
                          <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: "0.04em", color: m3.onSurfaceVar, flex: 1 }}>
                            {g.domain ?? "No domain"}
                          </Typography>
                          <Typography sx={{ fontSize: 11.5, color: m3.onSurfaceVar }}>{g.devices.length}</Typography>
                        </Box>
                      )}
                      {domainOpen && <FlatDeviceList devices={g.devices} {...rest} />}
                    </Box>
                  );
                })}
              </Box>
            )}
          </Box>
        );
      })}
      {unmanaged.length > 0 && <FlatDeviceList devices={unmanaged} {...rest} />}
    </Stack>
  );
}

function FlatDeviceList({
  devices,
  selectedDeviceId,
  selectedClusterRef,
  selectedVs = null,
  clusterOnly = false,
  showVirtualSystems = true,
  onSelectDevice,
  onSelectCluster,
}: {
  readonly devices: readonly DeviceSummary[];
  readonly selectedDeviceId: string | null;
  readonly selectedClusterRef: string | null;
  readonly selectedVs?: string | null;
  readonly clusterOnly?: boolean;
  /** False (Configuration): virtual systems are not tree nodes; the entity's own detail lists them. */
  readonly showVirtualSystems?: boolean;
  readonly onSelectDevice: (device: DeviceSummary, selectedVs?: string) => void;
  readonly onSelectCluster: (ref: string, members: DeviceSummary[], selectedVs?: string) => void;
}) {
  const [expandedRefs, setExpandedRefs] = useState<ReadonlySet<string>>(new Set());
  const [collapsedRefs, setCollapsedRefs] = useState<ReadonlySet<string>>(new Set());

  const groups = new Map<string, DeviceSummary[]>();
  const standalone: DeviceSummary[] = [];
  for (const device of devices) {
    if (device.cluster_member_ref) {
      const members = groups.get(device.cluster_member_ref) ?? [];
      members.push(device);
      groups.set(device.cluster_member_ref, members);
    } else {
      standalone.push(device);
    }
  }

  const toggle = (ref: string) => {
    if (clusterOnly) {
      setExpandedRefs((prev) => {
        const next = new Set(prev);
        if (next.has(ref)) next.delete(ref);
        else next.add(ref);
        return next;
      });
    } else {
      setCollapsedRefs((prev) => {
        const next = new Set(prev);
        if (next.has(ref)) next.delete(ref);
        else next.add(ref);
        return next;
      });
    }
  };

  return (
    <Stack spacing={1.25}>
      {[...groups.entries()].map(([ref, members]) => {
        const isCollapsed = clusterOnly ? !expandedRefs.has(ref) : collapsedRefs.has(ref);
        const firstMember = members[0];
        const isPaloAlto = firstMember?.vendor_hint === "palo_alto";
        const isSelected = selectedClusterRef === ref;
        const clusterTitle = deriveClusterTitle(ref, members);
        const clusterVsList = Array.from(
          new Set(
            members.flatMap((m) =>
              m.virtual_systems ? m.virtual_systems.split(/,\s*/) : []
            )
          )
        ).filter(Boolean).sort();

        return (
          <Box key={ref} sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <Box
              role="button"
              tabIndex={0}
              onClick={() => {
                if (clusterOnly) {
                  setExpandedRefs((prev) => new Set(prev).add(ref));
                }
                onSelectCluster(ref, members);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  if (clusterOnly) {
                    setExpandedRefs((prev) => new Set(prev).add(ref));
                  }
                  onSelectCluster(ref, members);
                }
              }}
              data-row="cluster"
              sx={{
                display: "flex",
                alignItems: "center",
                gap: 1,
                height: 44,
                boxSizing: "border-box",
                px: 1,
                cursor: "pointer",
                bgcolor: isSelected ? m3.primaryContainer : m3.scLow,
                borderRadius: "8px",
                border: "1px solid",
                borderColor: isSelected ? m3.primary : m3.outlineVar,
                "&:hover": { borderColor: m3.primary },
              }}
            >
              <VendorAvatar
                vendorHint={firstMember?.vendor_hint ?? "check_point"}
                model={firstMember?.model}
                hostname={clusterTitle}
              />
              <Box sx={{ flex: 1, minWidth: 0, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
                <Typography
                  variant="body2"
                  sx={{ fontWeight: 600, fontSize: 13, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
                  title={clusterTitle}
                >
                  {clusterTitle}
                </Typography>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, flexShrink: 0 }}>
                  <StatusChip tone="neutral" label="CLS" dense />
                  {clusterHealthLabel(members) !== "Live" && (
                    <StatusChip
                      tone={clusterHealthTone(members)}
                      label={clusterHealthLabel(members)}
                      dense
                    />
                  )}
                  {showVirtualSystems && (
                  <Box
                    component="span"
                    onClick={(e) => {
                      e.stopPropagation();
                      toggle(ref);
                    }}
                    sx={{
                      cursor: "pointer",
                      px: 0.5,
                      color: "text.secondary",
                      fontSize: "0.875rem",
                      "&:hover": { color: m3.primary },
                    }}
                    title={isCollapsed ? "Expand virtual systems" : "Collapse virtual systems"}
                  >
                    {isCollapsed ? "▼" : "▲"}
                  </Box>
                  )}
                </Box>
              </Box>
            </Box>
            {!isCollapsed && (
              <Stack spacing={1}>
                {/* Design language §2: a member is a column or a chip inside the cluster's
                    own detail, never a sibling row in this list -- the cluster's own caption
                    line above already names every member, and its detail header repeats them
                    with IP/serial/version. Nothing here loses that information; it only stops
                    duplicating each member as its own selectable row. */}
                {showVirtualSystems && clusterVsList.length > 0 && (
                  <Box sx={{ pl: 2.5, display: "flex", flexDirection: "column", gap: 0.75, mt: 0.5 }}>
                    <Typography
                      variant="caption"
                      sx={{
                        fontWeight: 700,
                        color: m3.onSurfaceVar,
                        fontSize: "0.72rem",
                        letterSpacing: "0.04em",
                        textTransform: "uppercase",
                        display: "flex",
                        alignItems: "center",
                        gap: 0.5,
                      }}
                    >
                      <span>{isPaloAlto ? "Virtual Systems (VSYS)" : "Virtual Systems (VSX)"}</span>
                      <Chip
                        size="small"
                        label={clusterVsList.length}
                        sx={{ height: 18, fontSize: "0.65rem", fontWeight: 700, bgcolor: m3.scHigh }}
                      />
                    </Typography>
                    <Stack spacing={0.5}>
                      {clusterVsList.map((vsName) => {
                        const isVsSelected = selectedClusterRef === ref && selectedVs === vsName;
                        return (
                          <Box
                            key={vsName}
                            role="button"
                            tabIndex={0}
                            onClick={(e) => {
                              e.stopPropagation();
                              onSelectCluster(ref, members, vsName);
                            }}
                            onKeyDown={(e) => {
                              if (e.key === "Enter" || e.key === " ") {
                                e.stopPropagation();
                                onSelectCluster(ref, members, vsName);
                              }
                            }}
                            sx={{
                              display: "flex",
                              alignItems: "center",
                              gap: 1,
                              p: 1,
                              borderRadius: "8px",
                              bgcolor: isVsSelected ? m3.primaryContainer : m3.scLowest,
                              border: "1px solid",
                              borderColor: isVsSelected ? m3.primary : m3.outlineVar,
                              cursor: "pointer",
                              "&:hover": { borderColor: m3.primary },
                            }}
                          >
                            <Chip
                              label={isPaloAlto ? "VSYS" : "VS"}
                              size="small"
                              sx={{
                                height: 20,
                                fontSize: "0.68rem",
                                fontWeight: 700,
                                bgcolor: m3.scHigh,
                                color: m3.onSurface,
                                borderRadius: "4px",
                              }}
                            />
                            <Typography
                              variant="body2"
                              sx={{
                                fontWeight: isVsSelected ? 700 : 500,
                                color: isVsSelected ? m3.primary : m3.onSurface,
                                flex: 1,
                                minWidth: 0,
                                overflow: "hidden",
                                textOverflow: "ellipsis",
                                whiteSpace: "nowrap",
                                fontSize: "0.82rem",
                              }}
                            >
                              {vsName}
                            </Typography>
                            <Typography variant="caption" sx={{ color: "text.secondary", fontSize: "0.7rem" }}>
                              {isPaloAlto ? "PAN-OS" : "VSX"}
                            </Typography>
                          </Box>
                        );
                      })}
                    </Stack>
                  </Box>
                )}
              </Stack>
            )}
          </Box>
        );
      })}
      {!clusterOnly && standalone.map((device) => {
        const isPaloAlto = device.vendor_hint === "palo_alto";
        const standaloneVsList = device.virtual_systems
          ? device.virtual_systems.split(/,\s*/).filter(Boolean)
          : [];
        const isDeviceSelected = device.device_id === selectedDeviceId;
        const isExpanded = !collapsedRefs.has(device.device_id);

        if (standaloneVsList.length === 0 || !showVirtualSystems) {
          return (
            <DeviceRow
              key={device.device_id}
              device={device}
              selected={isDeviceSelected && !selectedVs}
              onSelect={(dev) => onSelectDevice(dev)}
              trailingExtra={standaloneVsList.length > 0
                ? <StatusChip tone="neutral" label={`${standaloneVsList.length} ${isPaloAlto ? "VSYS" : "VS"}`} dense />
                : undefined}
            />
          );
        }

        return (
          <Box key={device.device_id} sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <DeviceRow
              device={device}
              selected={isDeviceSelected && !selectedVs}
              onSelect={(dev) => onSelectDevice(dev)}
              trailingExtra={
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                  <StatusChip
                    tone="neutral"
                    label={`${standaloneVsList.length} ${isPaloAlto ? "VSYS" : "VS"}`}
                    dense
                  />
                  <Box
                    component="span"
                    onClick={(e) => {
                      e.stopPropagation();
                      toggle(device.device_id);
                    }}
                    sx={{
                      cursor: "pointer",
                      px: 0.5,
                      color: "text.secondary",
                      fontSize: "0.875rem",
                      "&:hover": { color: m3.primary },
                    }}
                    title={isExpanded ? "Collapse virtual systems" : "Expand virtual systems"}
                  >
                    {isExpanded ? "▲" : "▼"}
                  </Box>
                </Box>
              }
            />
            {isExpanded && (
              <Box sx={{ pl: 2.5, display: "flex", flexDirection: "column", gap: 0.75, mt: 0.25 }}>
                <Typography
                  variant="caption"
                  sx={{
                    fontWeight: 700,
                    color: m3.onSurfaceVar,
                    fontSize: "0.72rem",
                    letterSpacing: "0.04em",
                    textTransform: "uppercase",
                    display: "flex",
                    alignItems: "center",
                    gap: 0.5,
                  }}
                >
                  <span>{isPaloAlto ? "Virtual Systems (VSYS)" : "Virtual Systems (VSX)"}</span>
                  <Chip
                    size="small"
                    label={standaloneVsList.length}
                    sx={{ height: 18, fontSize: "0.65rem", fontWeight: 700, bgcolor: m3.scHigh }}
                  />
                </Typography>
                <Stack spacing={0.5}>
                  {standaloneVsList.map((vsName) => {
                    const isVsSelected = isDeviceSelected && selectedVs === vsName;
                    return (
                      <Box
                        key={vsName}
                        role="button"
                        tabIndex={0}
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelectDevice(device, vsName);
                        }}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.stopPropagation();
                            onSelectDevice(device, vsName);
                          }
                        }}
                        sx={{
                          display: "flex",
                          alignItems: "center",
                          gap: 1,
                          p: 1,
                          borderRadius: "8px",
                          bgcolor: isVsSelected ? m3.primaryContainer : m3.scLowest,
                          border: "1px solid",
                          borderColor: isVsSelected ? m3.primary : m3.outlineVar,
                          cursor: "pointer",
                          "&:hover": { borderColor: m3.primary },
                        }}
                      >
                        <Chip
                          label={isPaloAlto ? "VSYS" : "VS"}
                          size="small"
                          sx={{
                            height: 20,
                            fontSize: "0.68rem",
                            fontWeight: 700,
                            bgcolor: m3.scHigh,
                            color: m3.onSurface,
                            borderRadius: "4px",
                          }}
                        />
                        <Typography
                          variant="body2"
                          sx={{
                            fontWeight: isVsSelected ? 700 : 500,
                            color: isVsSelected ? m3.primary : m3.onSurface,
                            flex: 1,
                            minWidth: 0,
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                            whiteSpace: "nowrap",
                            fontSize: "0.82rem",
                          }}
                        >
                          {vsName}
                        </Typography>
                        <Typography variant="caption" sx={{ color: "text.secondary", fontSize: "0.7rem" }}>
                          {isPaloAlto ? "PAN-OS" : "VSX"}
                        </Typography>
                      </Box>
                    );
                  })}
                </Stack>
              </Box>
            )}
          </Box>
        );
      })}
    </Stack>
  );
}

export function InventoryScreen() {
  const { data, error, refresh } = useFetchOnMount(
    () => listDevices().then((result) => result.devices ?? []),
    describeApiError,
  );
  const [selectedDevice, setSelectedDevice] = useState<DeviceSummary | null>(null);
  const [selectedDeviceVs, setSelectedDeviceVs] = useState<string | null>(null);
  const [selectedCluster, setSelectedCluster] = useState<{ ref: string; members: DeviceSummary[]; initialVs?: string } | null>(null);
  const clusterCacheRef = useRef<Map<string, ClusterInventory>>(new Map());
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<{ enrolled_devices: number; admitted: number; refused: number } | null>(null);
  // The top bar's search narrows this list (one search box, PO 2026-09-24).
  const searchTerm = useListSearch();
  // Review §4: one chip row per filter dimension, each labelled; the dimensions combine.
  const [vendorFilter, setVendorFilter] = useState<"all" | "check_point" | "palo_alto">(
    () => (urlParam("vendor") === "check_point" ? "check_point" : urlParam("vendor") === "palo_alto" ? "palo_alto" : "all"));
  const [scopeFilter, setScopeFilter] = useState<"all" | "cluster">("all");
  const [stateFilter, setStateFilter] = useState<"all" | "draft" | "failed" | "stale">("all");
  // Cross-screen links (global search, cluster context strip): preselect a cluster or a device once the list is in.
  const [pendingClusterRef, setPendingClusterRef] = useState<string | null>(() => urlParam("cluster_ref"));
  const [pendingDeviceId, setPendingDeviceId] = useState<string | null>(() => urlParam("device_id"));
  // Overview links (OVERVIEW_EXCEPTION_SCREEN_CONTRACT §4.2): inventory evidence age bucket and hotfix level.
  const [ageFilter, setAgeFilter] = useState<string | null>(() => urlParam("inventory_age"));
  const [hotfixFilter, setHotfixFilter] = useState<string | null>(() => urlParam("hotfix_level"));
  // Overview version donuts: an exact software version, or a Palo Alto major (x.y) / Check Point version.
  const [versionFilter, setVersionFilter] = useState<{ kind: "exact" | "major"; value: string } | null>(() =>
    urlParam("sw_version") ? { kind: "exact", value: urlParam("sw_version")! } : urlParam("sw_major") ? { kind: "major", value: urlParam("sw_major")! } : null);
  // Overview model donuts: Check Point appliance family (show asset system) or Palo Alto model.
  const [modelFilter, setModelFilter] = useState<string | null>(() => urlParam("hw_model"));
  const [sortMode, setSortMode] = useState<"name_asc" | "name_desc" | "vendor">("name_asc");
  const [listView, setListView] = useListView();

  const devices = data;
  const total = devices?.length ?? 0;
  const clusters = clusterCounts(devices ?? []);
  const checkPointCount = devices?.filter((d) => d.vendor_hint === "check_point").length ?? 0;
  const paloAltoCount = devices?.filter((d) => d.vendor_hint === "palo_alto").length ?? 0;
  const draftCount = devices?.filter((d) => d.enrollment_state === "DRAFT").length ?? 0;
  const staleCount = devices?.filter((d) => d.enrollment_state === "DEGRADED" || d.enrollment_state === "UNREACHABLE").length ?? 0;
  const failedCount = devices?.filter(hasFailedCollection).length ?? 0;

  // Auto-refresh when jobs are executing or queued
  useEffect(() => {
    const hasRunningJobs = devices?.some((d) => {
      const s = d.latest_job_state?.toUpperCase();
      return s === "EXECUTING" || s === "CLAIMED" || s === "REQUESTED";
    });
    if (!hasRunningJobs) return undefined;

    const interval = setInterval(() => {
      refresh();
    }, 2500);

    return () => clearInterval(interval);
  }, [devices, refresh]);

  // Sync selectedDevice with updated devices list from refresh
  useEffect(() => {
    if (selectedDevice && devices) {
      const updated = devices.find((d) => d.device_id === selectedDevice.device_id);
      if (
        updated &&
        (updated.enrollment_state !== selectedDevice.enrollment_state ||
          updated.latest_job_state !== selectedDevice.latest_job_state ||
          updated.latest_job_terminal_reason !== selectedDevice.latest_job_terminal_reason)
      ) {
        setSelectedDevice(updated);
      }
    }
  }, [devices, selectedDevice]);

  // `?cluster_ref=` / `?device_id=` open that cluster or device once the list is in (applied once).
  useEffect(() => {
    if (!devices) return;
    if (pendingClusterRef) {
      const members = devices.filter((d) => d.cluster_member_ref === pendingClusterRef);
      if (members.length > 0) {
        setSelectedCluster({ ref: pendingClusterRef, members });
        setSelectedDevice(null);
      }
      setPendingClusterRef(null);
      setPendingDeviceId(null);
      return;
    }
    if (pendingDeviceId) {
      const found = devices.find((d) => d.device_id === pendingDeviceId);
      if (found) {
        setSelectedDevice(found);
        setSelectedCluster(null);
      }
      setPendingDeviceId(null);
    }
  }, [devices, pendingClusterRef, pendingDeviceId]);

  const filteredDevices = (devices ?? []).filter((device) => {
    if (scopeFilter === "cluster" && !device.cluster_member_ref) return false;
    if (vendorFilter !== "all" && device.vendor_hint !== vendorFilter) return false;
    if (stateFilter === "draft" && device.enrollment_state !== "DRAFT") return false;
    if (stateFilter === "failed" && !hasFailedCollection(device)) return false;
    if (stateFilter === "stale" && device.enrollment_state !== "DEGRADED" && device.enrollment_state !== "UNREACHABLE") return false;
    if (ageFilter && !inAgeBucket(device, ageFilter)) return false;
    if (versionFilter) {
      const sw = device.software_version?.trim() || null;
      if (versionFilter.value === "unknown") { if (sw !== null) return false; }
      else if (versionFilter.kind === "exact" ? sw !== versionFilter.value
        : !(sw === versionFilter.value || (device.vendor_hint === "palo_alto" && sw !== null && (sw === versionFilter.value || sw.startsWith(`${versionFilter.value}.`))))) return false;
    }
    if (hotfixFilter && (hotfixFilter === "unknown" ? Boolean(device.hotfix_level) || device.role === "management_server" : device.hotfix_level !== hotfixFilter)) return false;
    if (modelFilter) {
      const model = (device.vendor_hint === "check_point" ? device.platform_family : device.model)?.trim() || null;
      if (modelFilter === "unknown" ? model !== null : model !== modelFilter) return false;
    }

    return deviceMatchesSearch(device, searchTerm);
  });

  const filteredCountLabel = `${filteredDevices.length} device${filteredDevices.length === 1 ? "" : "s"}`;
  const liveCount = filteredDevices.filter(isDeviceLive).length;
  const sortedDevices = [...filteredDevices].sort((a, b) => {
    const nameCmp = deviceNameLabel(a.hostname).localeCompare(deviceNameLabel(b.hostname), undefined, { sensitivity: "base" });
    if (sortMode === "vendor") {
      const vendorCmp = vendorLabel(a.vendor_hint).localeCompare(vendorLabel(b.vendor_hint));
      return vendorCmp !== 0 ? vendorCmp : nameCmp;
    }
    return sortMode === "name_desc" ? -nameCmp : nameCmp;
  });

  const handleBulkCollect = async () => {
    setBulkBusy(true);
    setBulkResult(null);
    try {
      const result = await requestBulkInventoryCollect();
      setBulkResult(result);
      refresh();
    } catch (err) {
      console.error("Bulk collect failed:", err);
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Devices"
        subtitle={devices === null ? "Loading…" : `${total} device${total === 1 ? "" : "s"} enrolled`}
        filters={
          <FilterBar>
          <FilterRow
            dimension="Vendor"
            value={vendorFilter}
            onChange={setVendorFilter}
            options={[
              { value: "all", label: "All", count: total },
              { value: "check_point", label: "Check Point", count: checkPointCount },
              { value: "palo_alto", label: "Palo Alto", count: paloAltoCount },
            ]}
          />
          <FilterRow
            dimension="Scope"
            value={scopeFilter}
            onChange={setScopeFilter}
            options={[
              { value: "all", label: "All", count: total },
              { value: "cluster", label: "Clusters", count: `${clusters.enrolled} enrolled · ${clusters.active} active` },
            ]}
          />
          <FilterRow
            dimension="State"
            value={stateFilter}
            onChange={setStateFilter}
            options={[
              { value: "all", label: "All", count: total },
              { value: "draft", label: "Draft", count: draftCount },
              // "Latest job failed" on ANY registered device, drafts included; Administration's "Last collection failed
              // (enrolled)" counts enrolled devices only -- two populations, both named (review 2026-09-23).
              { value: "failed", label: "Latest job failed", count: failedCount },
              { value: "stale", label: "Stale", count: staleCount },
            ]}
          />
          <FilterRow dimension="View" plain value={listView} onChange={setListView}
            options={[{ value: "flat", label: "Flat list" }, { value: "manager", label: "By manager" }]} />
          <FilterRow dimension="Sort" plain value={sortMode} onChange={setSortMode}
            options={[{ value: "name_asc", label: "Name (A→Z)" }, { value: "name_desc", label: "Name (Z→A)" }, { value: "vendor", label: "Vendor (A→Z)" }]} />
          </FilterBar>
        }
        actions={
          <>
            <M3Button emphasis="tonal" icon="download" disabled={!devices || devices.length === 0}
              onClick={() => downloadText(`devices-${new Date().toISOString().replace(/[:.]/g, "-")}.csv`, inventoryCsv(sortedDevices))}>
              Export inventory
            </M3Button>
            <M3Button emphasis="tonal" icon="operations" disabled={bulkBusy} onClick={handleBulkCollect}>
              {bulkBusy ? "Starting..." : "Bulk Collect"}
            </M3Button>
            <M3Button emphasis="filled" icon="plus" href="?screen=administration">Add device</M3Button>
          </>
        }
      />
      {bulkResult && (
        <Box sx={{ px: 3, pb: 2 }}>
          <Typography variant="body2" sx={{ color: m3.primary }}>
            Bulk collect admitted {bulkResult.admitted} of {bulkResult.enrolled_devices} enrolled devices.
            {bulkResult.refused > 0 && ` (${bulkResult.refused} refused.)`} View Administration &gt; Job Logs for progress.
          </Typography>
        </Box>
      )}
      <ListDetail
        list={
          <Box
            sx={{
              display: "flex",
              flexDirection: "column",
              gap: 1.25,
              minHeight: 0,
              maxHeight: "calc(100vh - 160px)",
              overflowY: "auto",
              overflowX: "hidden",
              position: "sticky",
              top: 16,
              pr: 0.5,
            }}
          >
            {(ageFilter || hotfixFilter || versionFilter || modelFilter) && (
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1, flexWrap: "wrap" }}>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>From Overview:</Typography>
                {ageFilter && <StatusChip tone="attn" label={`inventory ${({ stale: "older than 24 h or never", lt24h: "under 24 h", "24h_72h": "24–72 h", gt72h: "over 72 h", never: "never collected" } as Record<string, string>)[ageFilter] ?? ageFilter}`} dense />}
                {hotfixFilter && <StatusChip tone="attn" label={`hotfix ${hotfixFilter === "unknown" ? "UNKNOWN" : hotfixFilter}`} dense />}
                {versionFilter && <StatusChip tone="attn" label={`version ${versionFilter.value === "unknown" ? "UNKNOWN" : versionFilter.value}${versionFilter.kind === "major" ? " (major)" : ""}`} dense />}
                {modelFilter && <StatusChip tone="attn" label={`model ${modelFilter === "unknown" ? "UNKNOWN" : modelFilter}`} dense />}
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{filteredCountLabel}</Typography>
                <Box component="span" role="button" tabIndex={0} sx={{ cursor: "pointer", fontSize: 12, color: m3.primary }}
                  onClick={() => { setAgeFilter(null); setHotfixFilter(null); setVersionFilter(null); setModelFilter(null); }}>clear</Box>
              </Box>
            )}
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar, whiteSpace: "nowrap" }}
              title="A device row shows a state chip only when the device is not Live">
              {devices === null ? "" : `${filteredCountLabel} · ${liveCount} Live`}
            </Typography>
            {error && (
              <EmptyPanel title="Inventory unavailable" body={error}>
                <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
                  <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
                </Box>
              </EmptyPanel>
            )}
            {!error && devices === null && <EmptyPanel title="Devices" body="Loading…" />}
            {!error && devices !== null && devices.length === 0 && (
              <EmptyPanel title="No devices" body="Nothing is enrolled yet." />
            )}
            {!error && devices !== null && devices.length > 0 && filteredDevices.length === 0 && (
              <EmptyPanel title="No matches" body="No devices match the current search and filter criteria." />
            )}
            {!error && devices !== null && filteredDevices.length > 0 && (
              <DeviceList
                groupByManager={listView === "manager" && filteredDevices.length === (devices?.length ?? 0)}
                devices={sortedDevices}
                selectedDeviceId={selectedDevice?.device_id ?? null}
                selectedClusterRef={selectedCluster?.ref ?? null}
                selectedVs={selectedCluster ? selectedCluster.initialVs : selectedDeviceVs}
                clusterOnly={scopeFilter === "cluster"}
                onSelectDevice={(dev, vsName) => {
                  setSelectedDevice(dev);
                  setSelectedDeviceVs(vsName ?? null);
                  setSelectedCluster(null);
                }}
                onSelectCluster={(ref, members, vsName) => {
                  setSelectedCluster({ ref, members, initialVs: vsName });
                  setSelectedDevice(null);
                  setSelectedDeviceVs(null);
                }}
              />
            )}
          </Box>
        }
        detail={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            {selectedCluster ? (
              <ClusterDetailPanels
                key={selectedCluster.ref}
                clusterRef={selectedCluster.ref}
                members={selectedCluster.members}
                initialVs={selectedCluster.initialVs}
                cache={clusterCacheRef.current}
                onCacheUpdate={(ref, inv) => clusterCacheRef.current.set(ref, inv)}
              />
            ) : selectedDevice ? (
              <Stack spacing={2}>
                {selectedDevice.role === "management_server" && (
                  <ManagementTreePanel
                    deviceId={selectedDevice.device_id}
                    onOpenDevice={(id) => {
                      const d = devices?.find((x) => x.device_id === id);
                      if (d) { setSelectedCluster(null); setSelectedDeviceVs(null); setSelectedDevice(d); }
                    }}
                    onOpenCluster={(ref) => {
                      const members = (devices ?? []).filter((x) => x.cluster_member_ref === ref);
                      if (members.length > 0) { setSelectedDevice(null); setSelectedCluster({ ref, members }); }
                    }}
                  />
                )}
                <DeviceInventoryPanels
                  key={selectedDevice.device_id}
                  device={selectedDevice}
                  initialVs={selectedDeviceVs ?? undefined}
                  onDeviceStateChange={refresh}
                />
              </Stack>
            ) : (
              // Review §3: the detail tabs (Interfaces, Routing, Cluster members, Backup, Identity & provenance)
              // appear once a device or cluster is chosen; before that there is nothing for them to show.
              <EmptyPanel
                title={(devices?.length ?? 0) === 0 ? "No device to show" : "Select a device or cluster"}
                body={(devices?.length ?? 0) === 0
                  ? "Enrol a device from Administration; its interfaces, routing, cluster members and identity appear here once it is read."
                  : `${devices?.length ?? 0} devices are enrolled. Choose one on the left to see its interfaces, routing, cluster members, backups and identity. Interface evidence is read over SSH or HTTPS; values are observed, never written.`}
              />
            )}
          </Box>
        }
      />
    </ScreenRoot>
  );
}
