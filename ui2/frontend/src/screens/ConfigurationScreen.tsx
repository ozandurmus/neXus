import { useState, useMemo } from "react";
import Box from "@mui/material/Box";
import InputBase from "@mui/material/InputBase";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { listConfigurations, listDevices, requestBulkConfigurationCollect, type ApiError, type ConfigurationDeviceListEntry, type DeviceSummary } from "../auth/adminApi";
import { VendorAvatar } from "./InventoryPanels";
import { DeviceList } from "./InventoryScreen";
import { ClusterConfigurationDetail, DeviceConfigurationDetail } from "./ConfigurationDetail";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
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

function changeStateTone(state: string | null): "ok" | "warn" | "neutral" | "bad" {
  if (state === "changed") return "bad";
  if (state === "unchanged") return "ok";
  return "neutral";
}

function changeStateLabel(state: string | null): string {
  if (state === null) return "Not collected";
  if (state === "first_run") return "First run";
  if (state === "changed") return "Changed";
  return "Aligned";
}

/**
 * Design language section 2/3: the list presents operational units. Members of a cluster
 * are grouped under one row keyed by their cluster reference; agreement is judged by the
 * canonical hash of each member's latest run -- "agree" only when every member was collected
 * and every hash matches, "differ" when two collected members disagree, and UNKNOWN when a
 * member has never been collected (absence of evidence is not agreement).
 */
export interface ClusterGroup {
  readonly clusterRef: string;
  readonly members: readonly ConfigurationDeviceListEntry[];
  readonly agreement: "agree" | "differ" | "unknown";
}

export type ConfigurationListRow =
  | { readonly kind: "device"; readonly device: ConfigurationDeviceListEntry }
  | { readonly kind: "cluster"; readonly group: ClusterGroup };

export function groupByCluster(entries: readonly ConfigurationDeviceListEntry[]): ConfigurationListRow[] {
  const groups = new Map<string, ConfigurationDeviceListEntry[]>();
  const rows: ConfigurationListRow[] = [];
  for (const entry of entries) {
    const ref = entry.cluster_member_ref;
    if (!ref) {
      rows.push({ kind: "device", device: entry });
      continue;
    }
    const members = groups.get(ref);
    if (members) {
      members.push(entry);
    } else {
      const created = [entry];
      groups.set(ref, created);
      rows.push({ kind: "cluster", group: { clusterRef: ref, members: created, agreement: "unknown" } });
    }
  }
  return rows.map((row) => {
    if (row.kind !== "cluster") return row;
    const members = row.group.members;
    const collected = members.filter((m) => m.canonical_hash);
    let agreement: ClusterGroup["agreement"];
    if (collected.length < members.length) {
      agreement = "unknown";
    } else {
      agreement = new Set(collected.map((m) => m.canonical_hash)).size === 1 ? "agree" : "differ";
    }
    return { kind: "cluster", group: { ...row.group, agreement } };
  });
}

function ClusterRow({
  group,
  selectedDeviceId,
  onSelect,
}: {
  readonly group: ClusterGroup;
  readonly selectedDeviceId: string | null;
  readonly onSelect: (device: ConfigurationDeviceListEntry) => void;
}) {
  const vendor = group.members[0]?.vendor ?? "unknown";
  const tone = group.agreement === "agree" ? "ok" : group.agreement === "differ" ? "bad" : "warn";
  const label = group.agreement === "agree" ? "Members agree" : group.agreement === "differ" ? "Config diff" : "UNKNOWN";
  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        gap: 1,
        p: 1.25,
        bgcolor: m3.scLowest,
        border: "1px solid",
        borderColor: group.members.some((m) => m.device_id === selectedDeviceId) ? m3.primary : m3.outlineVar,
        borderRadius: "12px",
      }}
    >
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{group.clusterRef}</Typography>
        <StatusChip tone={tone} label={label} dense />
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ fontSize: 11 }}>
        {vendorLabel(vendor)} · cluster · {group.members.length} members
      </Typography>
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
        {group.members.map((member) => (
          <Box
            key={member.device_id}
            role="button"
            tabIndex={0}
            onClick={() => onSelect(member)}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") onSelect(member);
            }}
            sx={{
              display: "flex", alignItems: "center", gap: 0.75, px: 1, py: 0.5, borderRadius: "8px", cursor: "pointer",
              border: "1px solid", borderColor: member.device_id === selectedDeviceId ? m3.primary : m3.outlineVar,
              bgcolor: member.device_id === selectedDeviceId ? "#f0f5ff" : "transparent",
            }}
          >
            <Typography variant="caption" sx={{ fontWeight: 600 }}>{member.hostname ?? member.device_id}</Typography>
            <StatusChip tone={changeStateTone(member.change_state)} label={changeStateLabel(member.change_state)} dense />
            {member.projected_settings !== null && member.projected_settings !== undefined && (
              <Typography variant="caption" color="text.secondary">{member.projected_settings} settings</Typography>
            )}
          </Box>
        ))}
      </Stack>
    </Box>
  );
}

function DeviceRow({
  device,
  selected,
  onSelect,
}: {
  readonly device: ConfigurationDeviceListEntry;
  readonly selected: boolean;
  readonly onSelect: (device: ConfigurationDeviceListEntry) => void;
}) {
  return (
    <Box
      role="button"
      tabIndex={0}
      onClick={() => onSelect(device)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") onSelect(device);
      }}
      sx={{
        display: "flex",
        alignItems: "flex-start",
        gap: 1.25,
        p: 1.25,
        bgcolor: selected ? "#f0f5ff" : m3.scLowest,
        border: "1px solid",
        borderColor: selected ? m3.primary : m3.outlineVar,
        borderRadius: "12px",
        cursor: "pointer",
        transition: "all 0.15s ease-in-out",
        "&:hover": {
          borderColor: m3.primary,
          boxShadow: "0 2px 6px rgba(0,0,0,0.06)",
        },
      }}
    >
      <VendorAvatar vendorHint={device.vendor} hostname={device.hostname} />
      <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {device.hostname ?? device.device_id}
          </Typography>
          <StatusChip tone={changeStateTone(device.change_state)} label={changeStateLabel(device.change_state)} dense />
        </Box>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: 11 }}>
            {vendorLabel(device.vendor)} · {device.last_collected_at ? `collected ${device.last_collected_at.slice(0, 10)}` : "never collected"}
          </Typography>
        </Box>
      </Box>
    </Box>
  );
}

/** M3Configuration, backed by a real `GET /configuration` fetch (movement NXS-LOCAL-0165). */
export function ConfigurationScreen() {
  const { data, error, refresh } = useFetchOnMount(
    () => listConfigurations().then((result) => result.devices ?? []),
    describeApiError,
  );
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [selectedCluster, setSelectedCluster] = useState<{ ref: string; members: DeviceSummary[] } | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [filterMode, setFilterMode] = useState<"all" | "changed" | "first_run" | "uncollected">("all");
  const [vendorFilter, setVendorFilter] = useState<"all" | "check_point" | "palo_alto">("all");
  // Design language section 2: the list is the inventory's own tree (cluster -> virtual systems),
  // read from /devices; /configuration only supplies each device's change state and filters.
  const summariesFetch = useFetchOnMount<DeviceSummary[]>(() => listDevices().then((r) => r.devices ?? []), describeApiError);
  // When /devices cannot be read the tree still stands on what /configuration knows (hostname,
  // vendor, cluster reference) -- less detail, never an empty list over a populated store.
  const summaries: DeviceSummary[] | null = summariesFetch.data ?? (summariesFetch.error && data
    ? data.map((d) => ({ device_id: d.device_id, vendor_hint: d.vendor, enrollment_state: "", hostname: d.hostname, model: null, software_version: null, ha_role: null, cluster_member_ref: d.cluster_member_ref ?? null }))
    : null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<{ enrolled_devices: number; admitted: number; refused: number } | null>(null);

  const handleBulkCollect = async () => {
    setBulkBusy(true);
    setBulkResult(null);
    try {
      const res = await requestBulkConfigurationCollect();
      setBulkResult(res);
      refresh();
    } catch (err) {
      console.error("Bulk configuration collect failed:", err);
    } finally {
      setBulkBusy(false);
    }
  };

  const devices = data;
  const total = devices?.length ?? 0;
  const collectedCount = devices?.filter((d) => d.change_state !== null).length ?? 0;
  const changedCount = devices?.filter((d) => d.change_state === "changed").length ?? 0;
  const firstRunCount = devices?.filter((d) => d.change_state === "first_run").length ?? 0;
  const uncollectedCount = total - collectedCount;

  const filteredDevices = useMemo(() => {
    if (!devices) return [];
    return devices.filter((d) => {
      const matchSearch =
        !searchQuery ||
        (d.hostname ?? "").toLowerCase().includes(searchQuery.toLowerCase()) ||
        d.device_id.toLowerCase().includes(searchQuery.toLowerCase());
      if (!matchSearch) return false;
      if (filterMode === "changed") return d.change_state === "changed";
      if (filterMode === "first_run") return d.change_state === "first_run";
      if (filterMode === "uncollected") return d.change_state === null;
      return true;
    });
  }, [devices, searchQuery, filterMode]);

  const selectedDevice = devices?.find((d) => d.device_id === selectedDeviceId) ?? null;

  const treeDevices = useMemo(() => {
    if (!summaries) return [];
    const allowed = new Set(filteredDevices.map((d) => d.device_id));
    return summaries.filter((d) => allowed.has(d.device_id) && (vendorFilter === "all" || d.vendor_hint === vendorFilter));
  }, [summaries, filteredDevices, vendorFilter]);
  const selectedSummary = summaries?.find((d) => d.device_id === selectedDeviceId) ?? null;
  const vendorCount = (vendor: string) => summaries?.filter((d) => d.vendor_hint === vendor).length ?? 0;

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Configuration"
        subtitle={devices === null ? "Loading…" : `${collectedCount} device${collectedCount === 1 ? "" : "s"} collected${uncollectedCount > 0 ? ` · ${uncollectedCount} not collected` : ""}`}
        actions={
          <Stack direction="row" spacing={1.5}>
            <M3Button emphasis="outlined" icon="operations" disabled={bulkBusy} onClick={handleBulkCollect}>
              {bulkBusy ? "Starting..." : "Collect All"}
            </M3Button>
          </Stack>
        }
      />
      {bulkResult && (
        <Box sx={{ px: 3, pb: 2 }}>
          <Typography variant="body2" sx={{ color: m3.primary }}>
            Bulk configuration collect admitted {bulkResult.admitted} of {bulkResult.enrolled_devices} enrolled devices.
            {bulkResult.refused > 0 && ` (${bulkResult.refused} refused.)`} View Administration &gt; Job Logs for progress.
          </Typography>
        </Box>
      )}
      <ListDetail
        list={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            <Box sx={{ height: 48, display: "flex", alignItems: "center", gap: 1.5, px: 2,
                       borderRadius: "24px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, fontSize: 14 }}>
              <Icon name="search" size={20} />
              <InputBase
                placeholder="Device, serial or model"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                sx={{ flex: 1, fontSize: 14, color: "inherit" }}
              />
            </Box>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              {([
                ["all", `All ${summaries?.length ?? total}`],
                ["check_point", `Check Point ${vendorCount("check_point")}`],
                ["palo_alto", `Palo Alto ${vendorCount("palo_alto")}`],
              ] as const).map(([vendor, label]) => (
                <Box key={vendor} role="button" tabIndex={0} onClick={() => setVendorFilter(vendor)} sx={{ cursor: "pointer", opacity: vendorFilter === vendor ? 1 : 0.7 }}>
                  <StatusChip tone={vendorFilter === vendor ? "ok" : "neutral"} label={label} />
                </Box>
              ))}
            </Stack>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              {([
                ["all", `All ${total}`, "neutral"],
                ["changed", `Changed ${changedCount}`, changedCount > 0 ? "bad" : "neutral"],
                ["first_run", `First run ${firstRunCount}`, "neutral"],
                ["uncollected", `Not collected ${uncollectedCount}`, uncollectedCount > 0 ? "warn" : "neutral"],
              ] as const).map(([mode, label, tone]) => (
                <Box key={mode} role="button" tabIndex={0} onClick={() => setFilterMode(mode)} sx={{ cursor: "pointer", opacity: filterMode === mode ? 1 : 0.7 }}>
                  <StatusChip tone={tone} label={label} />
                </Box>
              ))}
            </Stack>
            {error && (
              <EmptyPanel title="Configuration unavailable" body={error}>
                <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
                  <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
                </Box>
              </EmptyPanel>
            )}
            {!error && devices === null && <EmptyPanel title="Devices" body="Loading…" />}
            {!error && devices !== null && devices.length === 0 && (
              <EmptyPanel title="No devices" body="Nothing is enrolled yet." />
            )}
            {!error && devices !== null && devices.length > 0 && (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1.25, maxHeight: "calc(100vh - 300px)", overflowY: "auto", pr: 0.5 }}>
                {summaries === null && !summariesFetch.error && <EmptyPanel title="Devices" body="Loading…" />}
                {summariesFetch.error && <EmptyPanel title="Devices unavailable" body={summariesFetch.error} />}
                {summaries !== null && treeDevices.length === 0 && <EmptyPanel title="No device matches" body="No device matches the filters." />}
                {summaries !== null && treeDevices.length > 0 && (
                  <DeviceList
                showVirtualSystems={false}
                    devices={treeDevices}
                    selectedDeviceId={selectedDeviceId}
                    selectedClusterRef={selectedCluster?.ref ?? null}
                    onSelectDevice={(d) => { setSelectedCluster(null); setSelectedDeviceId(d.device_id); }}
                    onSelectCluster={(ref, members) => { setSelectedDeviceId(null); setSelectedCluster({ ref, members }); }}
                  />
                )}
              </Box>
            )}
          </Box>
        }
        detail={
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, minHeight: 0 }}>
            {selectedCluster ? (
              <ClusterConfigurationDetail key={selectedCluster.ref} clusterRef={selectedCluster.ref} members={selectedCluster.members} />
            ) : selectedSummary ? (
              <DeviceConfigurationDetail key={selectedSummary.device_id} device={selectedSummary} />
            ) : selectedDeviceId && selectedDevice ? (
              <DeviceConfigurationDetail
                key={selectedDeviceId}
                device={{ device_id: selectedDevice.device_id, vendor_hint: selectedDevice.vendor, enrollment_state: "", hostname: selectedDevice.hostname, model: null, software_version: null, ha_role: null, cluster_member_ref: selectedDevice.cluster_member_ref ?? null }}
              />
            ) : (
              <EmptyPanel
                title="No device selected"
                body="Select a device or a cluster from the list. A device shows its operator snapshot and every configuration section; a cluster shows its members side by side with every difference marked."
              />
            )}
          </Box>
        }
      />
    </ScreenRoot>
  );
}
