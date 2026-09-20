import { useState, useEffect, useRef } from "react";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ListDetail, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { requestBulkInventoryCollect, listDevices, type ApiError, type DeviceSummary, type ClusterInventory } from "../auth/adminApi";
import { enrollmentStateLabel, enrollmentStateTone } from "../shell/deviceCopy";
import { JobStatusIndicator } from "../shell/JobStatusIndicator";
import { DeviceInventoryPanels, ClusterDetailPanels, VendorAvatar } from "./InventoryPanels";

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
      sx={{
        display: "flex",
        alignItems: "flex-start",
        gap: 1.25,
        p: 1.25,
        ml: indented ? 2.5 : 0,
        bgcolor: selected ? "#f0f5ff" : m3.scLowest,
        border: "1px solid",
        borderColor: selected ? m3.primary : m3.outlineVar,
        borderRadius: "12px",
        cursor: onSelect ? "pointer" : undefined,
        transition: "all 0.15s ease-in-out",
        "&:hover": {
          borderColor: m3.primary,
          boxShadow: "0 2px 6px rgba(0,0,0,0.06)",
        },
      }}
    >
      <VendorAvatar vendorHint={device.vendor_hint} model={device.model} hostname={device.hostname} />

      <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, minWidth: 0 }}>
            <Typography
              variant="body2"
              sx={{
                fontWeight: 600,
                minWidth: 0,
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                color: m3.onSurface,
              }}
            >
              {device.hostname ?? device.device_id ?? "Unknown"}
            </Typography>
            <JobStatusIndicator
              state={device.latest_job_state}
              type={device.latest_job_type}
              terminalReason={device.latest_job_terminal_reason}
            />
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <StatusChip
              tone={enrollmentStateTone(device.enrollment_state)}
              label={isEnrolled ? "Live" : enrollmentStateLabel(device.enrollment_state)}
              dense
            />
            {trailingExtra}
          </Box>
        </Box>
        <Typography variant="caption" color="text.secondary" sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {device.role === "management_server" ? "Management server" : vendorLabel(device.vendor_hint)}
          {device.model ? ` · ${device.model}` : " · Unknown model"}
          {device.software_version ? ` · ${device.software_version}` : " · Unknown version"}
          {device.ha_role ? ` · ${device.ha_role}` : " · No HA role"}
        </Typography>
      </Box>
    </Box>
  );
}

function DeviceList({
  devices,
  selectedDeviceId,
  selectedClusterRef,
  selectedVs = null,
  clusterOnly = false,
  onSelectDevice,
  onSelectCluster,
}: {
  readonly devices: readonly DeviceSummary[];
  readonly selectedDeviceId: string | null;
  readonly selectedClusterRef: string | null;
  readonly selectedVs?: string | null;
  readonly clusterOnly?: boolean;
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
              sx={{
                display: "flex",
                alignItems: "center",
                gap: 1.25,
                p: 1.25,
                cursor: "pointer",
                bgcolor: isSelected ? "#ebf2ff" : m3.scHigh,
                borderRadius: "12px",
                border: "1px solid",
                borderColor: isSelected ? m3.primary : m3.outlineVar,
                transition: "all 0.15s ease-in-out",
                "&:hover": { bgcolor: isSelected ? "#ebf2ff" : m3.scHighest },
              }}
            >
              <VendorAvatar
                vendorHint={firstMember?.vendor_hint ?? "check_point"}
                model={firstMember?.model}
                hostname={ref}
              />
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>Cluster {ref}</Typography>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                    <StatusChip tone="mem" label={`${members.length} members`} dense />
                    {clusterVsList.length > 0 && (
                      <StatusChip tone="neutral" label={`${clusterVsList.length} ${isPaloAlto ? "VSYS" : "VS"}`} dense />
                    )}
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
                      title={isCollapsed ? "Expand members & virtual systems" : "Collapse members"}
                    >
                      {isCollapsed ? "▼" : "▲"}
                    </Box>
                  </Box>
                </Box>
                <Typography variant="caption" color="text.secondary" sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", display: "block" }}>
                  {isPaloAlto ? "PAN-OS HA" : "ClusterXL"} · {members.map((m) => m.hostname ?? m.device_id).join(" · ")}
                </Typography>
              </Box>
            </Box>
            {!isCollapsed && (
              <Stack spacing={1}>
                {members.map((member) => (
                  <DeviceRow
                    key={member.device_id}
                    device={member}
                    indented
                    selected={member.device_id === selectedDeviceId}
                    onSelect={onSelectDevice}
                  />
                ))}
                {clusterVsList.length > 0 && (
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
                              bgcolor: isVsSelected ? "#dbeafe" : m3.scLowest,
                              border: "1px solid",
                              borderColor: isVsSelected ? m3.primary : m3.outlineVar,
                              cursor: "pointer",
                              transition: "all 0.15s ease-in-out",
                              "&:hover": {
                                borderColor: m3.primary,
                                bgcolor: isVsSelected ? "#dbeafe" : "#f0f5ff",
                                boxShadow: "0 1px 4px rgba(0,0,0,0.05)",
                              },
                            }}
                          >
                            <Chip
                              label={isPaloAlto ? "VSYS" : "VS"}
                              size="small"
                              sx={{
                                height: 20,
                                fontSize: "0.68rem",
                                fontWeight: 700,
                                bgcolor: isPaloAlto ? "#fef3c7" : "#e0e7ff",
                                color: isPaloAlto ? "#92400e" : "#3730a3",
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

        if (standaloneVsList.length === 0) {
          return (
            <DeviceRow
              key={device.device_id}
              device={device}
              selected={isDeviceSelected && !selectedVs}
              onSelect={(dev) => onSelectDevice(dev)}
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
                          bgcolor: isVsSelected ? "#dbeafe" : m3.scLowest,
                          border: "1px solid",
                          borderColor: isVsSelected ? m3.primary : m3.outlineVar,
                          cursor: "pointer",
                          transition: "all 0.15s ease-in-out",
                          "&:hover": {
                            borderColor: m3.primary,
                            bgcolor: isVsSelected ? "#dbeafe" : "#f0f5ff",
                            boxShadow: "0 1px 4px rgba(0,0,0,0.05)",
                          },
                        }}
                      >
                        <Chip
                          label={isPaloAlto ? "VSYS" : "VS"}
                          size="small"
                          sx={{
                            height: 20,
                            fontSize: "0.68rem",
                            fontWeight: 700,
                            bgcolor: isPaloAlto ? "#fef3c7" : "#e0e7ff",
                            color: isPaloAlto ? "#92400e" : "#3730a3",
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
  const [searchTerm, setSearchTerm] = useState("");
  const [filterMode, setFilterMode] = useState<"all" | "cluster" | "check_point" | "palo_alto" | "stale" | "draft">("all");

  const devices = data;
  const total = devices?.length ?? 0;
  const clusterCount = new Set((devices ?? []).map((d) => d.cluster_member_ref).filter(Boolean)).size;
  const checkPointCount = devices?.filter((d) => d.vendor_hint === "check_point").length ?? 0;
  const paloAltoCount = devices?.filter((d) => d.vendor_hint === "palo_alto").length ?? 0;
  const draftCount = devices?.filter((d) => d.enrollment_state === "DRAFT").length ?? 0;
  const staleCount = devices?.filter((d) => d.enrollment_state === "DEGRADED" || d.enrollment_state === "UNREACHABLE").length ?? 0;

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

  const filteredDevices = (devices ?? []).filter((device) => {
    if (filterMode === "cluster" && !device.cluster_member_ref) return false;
    if (filterMode === "check_point" && device.vendor_hint !== "check_point") return false;
    if (filterMode === "palo_alto" && device.vendor_hint !== "palo_alto") return false;
    if (filterMode === "draft" && device.enrollment_state !== "DRAFT") return false;
    if (filterMode === "stale" && device.enrollment_state !== "DEGRADED" && device.enrollment_state !== "UNREACHABLE") return false;

    if (!searchTerm.trim()) return true;
    const term = searchTerm.toLowerCase().trim();
    const matchHostname = device.hostname?.toLowerCase().includes(term);
    const matchId = device.device_id.toLowerCase().includes(term);
    const matchModel = device.model?.toLowerCase().includes(term);
    const matchVersion = device.software_version?.toLowerCase().includes(term);
    const matchCluster = device.cluster_member_ref?.toLowerCase().includes(term);
    return Boolean(matchHostname || matchId || matchModel || matchVersion || matchCluster);
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
        title="Network inventory"
        subtitle={devices === null ? "Loading…" : `${total} device${total === 1 ? "" : "s"} enrolled`}
        actions={
          <>
            <M3Button emphasis="outlined" icon="download">Export inventory</M3Button>
            <M3Button emphasis="outlined" icon="operations" disabled={bulkBusy} onClick={handleBulkCollect}>
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
              gap: 1.5,
              minHeight: 0,
              maxHeight: "calc(100vh - 160px)",
              overflowY: "auto",
              position: "sticky",
              top: 16,
              pr: 0.5,
            }}
          >
            <Box
              sx={{
                height: 44,
                display: "flex",
                alignItems: "center",
                gap: 1.25,
                px: 2,
                borderRadius: "24px",
                bgcolor: m3.scLowest,
                border: `1px solid ${m3.outlineVar}`,
                color: m3.onSurface,
                boxShadow: "0 1px 2px rgba(0,0,0,0.04)",
                "&:focus-within": {
                  borderColor: m3.primary,
                  boxShadow: `0 0 0 2px ${m3.primaryContainer}`,
                },
              }}
            >
              <Icon name="search" size={18} />
              <input
                type="text"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder="Subnet, device, serial or IP"
                style={{
                  border: "none",
                  outline: "none",
                  background: "transparent",
                  width: "100%",
                  fontSize: "13px",
                  color: "inherit",
                }}
              />
              {searchTerm && (
                <Box
                  component="button"
                  onClick={() => setSearchTerm("")}
                  sx={{
                    border: "none",
                    bgcolor: "transparent",
                    cursor: "pointer",
                    p: 0.25,
                    color: m3.onSurfaceVar,
                    fontSize: 14,
                  }}
                >
                  ✕
                </Box>
              )}
            </Box>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              <Chip
                size="small"
                clickable
                onClick={() => setFilterMode("all")}
                label={`All ${total}`}
                sx={{
                  bgcolor: filterMode === "all" ? m3.primary : m3.scHigh,
                  color: filterMode === "all" ? m3.onPrimary : m3.onSurface,
                  fontWeight: filterMode === "all" ? 600 : 400,
                  borderRadius: "8px",
                }}
              />
              {clusterCount > 0 && (
                <Chip
                  size="small"
                  clickable
                  onClick={() => setFilterMode("cluster")}
                  label={`Clusters ${clusterCount}`}
                  sx={{
                    bgcolor: filterMode === "cluster" ? m3.primary : m3.scHigh,
                    color: filterMode === "cluster" ? m3.onPrimary : m3.onSurface,
                    fontWeight: filterMode === "cluster" ? 600 : 400,
                    borderRadius: "8px",
                  }}
                />
              )}
              <Chip
                size="small"
                clickable
                onClick={() => setFilterMode("check_point")}
                label={`Check Point ${checkPointCount}`}
                sx={{
                  bgcolor: filterMode === "check_point" ? m3.primary : m3.scHigh,
                  color: filterMode === "check_point" ? m3.onPrimary : m3.onSurface,
                  fontWeight: filterMode === "check_point" ? 600 : 400,
                  borderRadius: "8px",
                }}
              />
              <Chip
                size="small"
                clickable
                onClick={() => setFilterMode("palo_alto")}
                label={`Palo Alto ${paloAltoCount}`}
                sx={{
                  bgcolor: filterMode === "palo_alto" ? m3.primary : m3.scHigh,
                  color: filterMode === "palo_alto" ? m3.onPrimary : m3.onSurface,
                  fontWeight: filterMode === "palo_alto" ? 600 : 400,
                  borderRadius: "8px",
                }}
              />
              {draftCount > 0 && (
                <Chip
                  size="small"
                  clickable
                  onClick={() => setFilterMode("draft")}
                  label={`Draft ${draftCount}`}
                  sx={{
                    bgcolor: filterMode === "draft" ? m3.primary : m3.scHigh,
                    color: filterMode === "draft" ? m3.onPrimary : m3.onSurface,
                    fontWeight: filterMode === "draft" ? 600 : 400,
                    borderRadius: "8px",
                  }}
                />
              )}
              {staleCount > 0 && (
                <Chip
                  size="small"
                  clickable
                  onClick={() => setFilterMode("stale")}
                  label={`Stale ${staleCount}`}
                  sx={{
                    bgcolor: filterMode === "stale" ? m3.primary : m3.scHigh,
                    color: filterMode === "stale" ? m3.onPrimary : m3.onSurface,
                    fontWeight: filterMode === "stale" ? 600 : 400,
                    borderRadius: "8px",
                  }}
                />
              )}
            </Stack>
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
                devices={filteredDevices}
                selectedDeviceId={selectedDevice?.device_id ?? null}
                selectedClusterRef={selectedCluster?.ref ?? null}
                selectedVs={selectedCluster ? selectedCluster.initialVs : selectedDeviceVs}
                clusterOnly={filterMode === "cluster"}
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
              <DeviceInventoryPanels
                key={selectedDevice.device_id}
                device={selectedDevice}
                initialVs={selectedDeviceVs ?? undefined}
                onDeviceStateChange={refresh}
              />
            ) : (
              <M3Tabs
                ariaLabel="Device detail"
                tabs={[
                  {
                    label: "Interfaces",
                    panel: (
                      <Stack spacing={1.5}>
                        <EmptyPanel
                          title="No interface evidence"
                          body="Enrol a device from Administration to see its interfaces here; none is enrolled yet."
                        />
                        <Typography variant="body2">
                          Interface evidence is read over SSH or HTTPS. Values are observed, never written.
                        </Typography>
                      </Stack>
                    ),
                  },
                  {
                    label: "Routing",
                    panel: (
                      <EmptyPanel
                        title="No routing evidence"
                        body="Routing tables are read directly from an enrolled device; no device has been enrolled or read yet."
                      />
                    ),
                  },
                  {
                    label: "Cluster members",
                    panel: (
                      <EmptyPanel
                        title="No cluster membership evidence"
                        body="Membership needs an identity-verified read from each peer; none has been collected yet."
                      />
                    ),
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
            )}
          </Box>
        }
      />
    </ScreenRoot>
  );
}
