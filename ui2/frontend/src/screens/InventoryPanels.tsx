import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Link from "@mui/material/Link";
import MenuItem from "@mui/material/MenuItem";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import Tooltip from "@mui/material/Tooltip";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { ObjectHeader } from "../shell/ObjectHeader";
import { PaginationFooter } from "../shell/DataTable";
import { statusWord } from "../shell/presenters";
import type { Tone } from "../shell/tone";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { PLANE_COLLECT, PLANE_OPERATIONS, ROLE_REFUSAL, useRoleAllows } from "../shell/actionAccess";
import { bytesTitle, formatBytes } from "../shell/bytes";
import { formatStamp, relativeAge } from "../shell/time";
import { plural } from "../shell/plural";
import { deviceNameLabel, jobPhaseLabel, isTerminalJobState, enrollmentStateLabel, onboardingChip } from "../shell/deviceCopy";
import { MONO, m3 } from "../theme/m3Theme";
import { RoleChip, Ts, VendorBadge, vendorDisplayName } from "../shell/States";
import { Stamp } from "../shell/PresentedText";
import { ReferenceCell, clusterContextLinks, DeviceIdentityTable, deviceLiveWithInventory, knownHaRole, liveBasis, orderMembers, vsListOf } from "./DeviceShared";
import { JobStatusIndicator, latestJobSentence } from "../shell/JobStatusIndicator";
import {
  getDevice,
  getDeviceInventory,
  getClusterInventory,
  getHttpsCertificate,
  getDeviceConfiguration,
  getDeviceCompliance,
  listCpFailoverSummary,
  requestInventoryCollect,
  retryDeviceConfirm,
  collectDeviceBackup,
  type ApiError,
  type ClusterContext,
  type ClusterDifference,
  type ClusterInterface,
  type ClusterInventory,
  type ClusterRoute,
  type DeviceConfiguration,
  type DeviceComplianceResult,
  type CpFailoverSummary,
  type DeviceInventory,
  type GridMemberView,
  type GridSummaryView,
  type DeviceSummary,
  type IdentityCheckView,
  type InventoryAddress,
  type InventoryContext,
  type InventoryInterface,
  type InventoryRoute,
  type Presence,
  type BackupArtefact,
  getBackupPolicy,
  listDeviceBackups,
} from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { BackupAttempts } from "./BackupAttempts";
import { DeviationChip, ValidationChip, deviationState } from "./backupWords";
import { DeviceLogsPanel } from "./DeviceLogsPanel";
import { CpFailoverPanel } from "./CpFailoverPanel";
import { CertificateStatePill, HttpsCertificateCard } from "./HttpsCertificatePanel";
import { IdentityCard, identityTiles } from "./PlatformIdentity";
import {
  HeaderStateRow, READINESS_VENDORS, backupFact, backupsHref, clusterComplianceFact, clusterConfigChangedFact, complianceFact,
  configChangedFact, readinessFact, useMemberHeaderReads,
} from "./HeaderStatePills";

const POLL_INTERVAL_MS = 1750;

/** The services worth a chip on a grid member row, in display order; everything else is counted only. */
const GRID_MEMBER_SERVICES = ["DOT_DOH", "DFP", "ATP", "ANALYTICS", "REPORTING", "TAXII"] as const;

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
export function GridMembersPanel({ members, summary }: { readonly members: readonly GridMemberView[]; readonly summary?: GridSummaryView | null }) {
  if (members.length === 0 && !summary) {
    return (
      <EmptyPanel
        title="No grid member evidence"
        body="The member list is read during Grid Manager inventory; none has been recorded yet."
      />
    );
  }
  const pct = (v: number | null) => (v === null ? "\u2014" : `${v}%`);
  const count = (value: number, atLeast: boolean) => `${atLeast ? "≥" : ""}${value}`;
  const serviceColumns = (["DNS", "DHCP", "NTP"] as const).filter((service) =>
    members.some((member) => member.services.some((s) => s.service === service)));
  return (
    <Stack spacing={1.5}>
      {summary && <Stack spacing={1}>
        {(summary.dns_views !== null || summary.auth_zones !== null) && <Typography variant="body2">
          DNS: {summary.dns_views !== null && `${count(summary.dns_views, summary.dns_views_at_least)} views`}
          {summary.dns_views !== null && summary.auth_zones !== null && " · "}
          {summary.auth_zones !== null && `${count(summary.auth_zones, summary.auth_zones_at_least)} authoritative zones`}
        </Typography>}
        {(summary.dhcp_networks !== null || summary.dhcp_ranges !== null) && <Typography variant="body2">
          DHCP: {summary.dhcp_networks !== null && `${count(summary.dhcp_networks, summary.dhcp_networks_at_least)} networks`}
          {summary.dhcp_networks !== null && summary.dhcp_ranges !== null && " · "}
          {summary.dhcp_ranges !== null && `${count(summary.dhcp_ranges, summary.dhcp_ranges_at_least)} ranges`}
        </Typography>}
        {!!summary.top_networks?.length && <Box>
          <Typography variant="subtitle2">Most utilised networks</Typography>
          {summary.top_networks.map((row) => <Stack key={row.network} direction="row" spacing={1} alignItems="center">
            <Typography variant="body2" sx={{ minWidth: 135, fontFamily: MONO }}>{row.network}</Typography>
            <Box role="meter" aria-label={`${row.network} utilization`} aria-valuenow={row.percent} aria-valuemin={0} aria-valuemax={100}
              sx={{ width: 100, height: 6, borderRadius: 1, bgcolor: "action.hover" }}>
              <Box sx={{ width: `${Math.max(0, Math.min(100, row.percent))}%`, height: "100%", borderRadius: 1, bgcolor: "primary.main" }} />
            </Box>
            <Typography variant="body2">{row.percent.toFixed(1)}%</Typography>
          </Stack>)}
        </Box>}
        {!!summary.licenses?.length && <Box>
          <Typography variant="subtitle2">Licenses</Typography>
          {summary.licenses.map((license, index) => {
            const expired = !!license.expiry_date && /^\d{4}-\d{2}-\d{2}/.test(license.expiry_date)
              && license.expiry_date.slice(0, 10) < new Date().toISOString().slice(0, 10);
            return <Typography key={index} variant="body2" color={expired ? "error.main" : "text.primary"}>
              {license.member} · {[license.type, license.kind].filter(Boolean).join(" / ")}
              {license.expiry_date && ` · expires on ${license.expiry_date}`}
            </Typography>;
          })}
        </Box>}
      </Stack>}
      {members.length > 0 && <>
      <Typography variant="caption" color="text.secondary">
        {plural(members.length, "member")} · {members.filter((m) => m.grid_master).length} Grid Master ·{" "}
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
              {serviceColumns.map((service) => <TableCell key={service}>{service}</TableCell>)}
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
                      ? <StatusChip tone={m.ha_status === "ACTIVE" || m.ha_status === "PASSIVE" ? "ok" : "warn"} label={`Enabled · ${m.ha_status ?? "status unknown"}`} dense />
                      : <Typography variant="caption" color="text.secondary">single node</Typography>}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}>
                    <StatusChip tone={m.node_status === "WORKING" ? "ok" : m.node_status ? "warn" : "neutral"} label={m.node_status ?? "unknown"} dense />
                    {m.replication && (
                      <Typography variant="caption" color="text.secondary" sx={{ ml: 0.5 }}>replication {m.replication}</Typography>
                    )}
                  </TableCell>
                  {serviceColumns.map((service) => <TableCell key={service}>
                    {byService.has(service) && <StatusChip tone={serviceTone(byService.get(service)!)} label={byService.get(service)!} dense />}
                  </TableCell>)}
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
      </>}
    </Stack>
  );
}

/**
 * A manager's word for a managed device's management state, in sentence case (`FULLY_MANAGED` → "Fully managed",
 * FortiManager's `UP` → "Up"); `UNKNOWN` stays UNKNOWN. The expected states are neutral grey (P2-1B: `UP` was amber);
 * a device the manager calls unmanaged, down or unknown keeps the amber word.
 */
export function managementWord(status: string): string {
  const value = status.trim();
  if (!value) return "UNKNOWN";
  if (value === "UNKNOWN") return value;
  const words = value.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
export function managementTone(status: string): Tone {
  return /^(UP|MANAGED|FULLY_MANAGED|CONNECTED)$/.test(status.trim()) ? "neutral" : "warn";
}

/** A Symantec Management Center's managed devices (ProxySG, Reporter, WSS) as it lists them; values read, never inferred. */
function ManagedDevicesPanel({ members, selected, onSelect }: {
  readonly members: readonly GridMemberView[];
  readonly selected?: string | null;
  readonly onSelect?: (name: string | null) => void;
}) {
  if (members.length === 0) {
    return <EmptyPanel title="No managed-device evidence" body="Collect reads the Management Center's device list; none has been recorded yet." />;
  }
  const deployment = (m: GridMemberView) => m.services.find((s) => s.service === "DEPLOYMENT")?.status ?? null;
  // The Deployment column only when some device reports one (R17): a column of dashes says nothing and cost the header its room.
  const hasDeployment = members.some((m) => deployment(m) !== null);
  const widths = hasDeployment ? ["20%", "14%", "12%", "14%", "14%", "14%", "12%"] : ["22%", "16%", "13%", "16%", "16%", "17%"];
  const selectedMember = members.find(m => m.virtual_system === selected);
  const shown = selectedMember ? [selectedMember] : members;
  return (
    <Stack spacing={1.5}>
      <Typography variant="caption" color="text.secondary">
        {plural(members.length, "device")} · manager-reported inventory
      </Typography>
      {selectedMember && <Stack direction="row" spacing={2} alignItems="center">
        <Typography variant="h6">{selectedMember.virtual_system}</Typography>
        <Button onClick={() => onSelect?.(null)}>All managed devices</Button>
      </Stack>}
      <TableContainer>
        {/* Fixed column widths at 1440 (UI review 2026-10-18, P2-1B / roadmap 3.4): a long value clips inside its own cell
            with the full text on hover, instead of pushing the Deployment column out of the card. */}
        <Table size="small" data-managed-devices sx={{ tableLayout: "fixed", "& td, & th": { overflow: "hidden", textOverflow: "ellipsis" }, "& th": { whiteSpace: "normal", lineHeight: 1.3 } }}>
          <colgroup>
            {widths.map((width, i) => <col key={i} style={{ width }} />)}
          </colgroup>
          <TableHead>
            <TableRow>
              <TableCell>Device</TableCell>
              <TableCell>Address</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Model</TableCell>
              <TableCell>OS version</TableCell>
              <TableCell>Management</TableCell>
              {hasDeployment && <TableCell>Deployment</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((m) => (
              <TableRow key={m.virtual_system}>
                <TableCell sx={{ fontWeight: 600, whiteSpace: "nowrap" }}>
                  <Button onClick={() => onSelect?.(m.virtual_system)}>{m.virtual_system}</Button>
                </TableCell>
                <TableCell title={m.address ?? undefined}>{m.address ?? "UNKNOWN"}</TableCell>
                <TableCell title={m.platform ?? undefined}>{m.platform ?? "\u2014"}</TableCell>
                <TableCell title={m.hardware_type ?? undefined}>{m.hardware_type ?? "\u2014"}</TableCell>
                <TableCell sx={{ whiteSpace: "nowrap" }} title={m.hypervisor ?? undefined}>{m.hypervisor ?? "\u2014"}</TableCell>
                <TableCell>{m.node_status ? <StatusChip tone={managementTone(m.node_status)} label={managementWord(m.node_status)} dense /> : "\u2014"}</TableCell>
                {hasDeployment && <TableCell>{deployment(m) ?? "\u2014"}</TableCell>}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
}

/**
 * What the header's identity badge rests on (PO decision 2026-10-18; it used to mean only "enrolled"): the worker's LAST
 * comparison of the SSH host key or HTTPS certificate the device presented with the fingerprint pinned for it
 * (`device_identity_check`, V165), and the open first-contact identity conflict if there is one. Expiry is in neither
 * check (PO 2026-10-18: it stays that way; the certificate pill says it).
 */
export const IDENTITY_VERIFIED_BASIS = "Identity verified means the last connection compared the SSH host key or HTTPS "
  + "certificate fingerprint the device presented with the pinned one and they were equal. Identity mismatch means they "
  + "were not equal, or the identity the device presented differs from the one recorded at enrollment. Not verified yet "
  + "means no comparison is on record: the device has not connected since this check began, the fingerprint was pinned "
  + "by the newest connection, or the transport has no pin. Certificate expiry is not part of the check.";

const IDENTITY_TRANSPORT_WORDS: Readonly<Record<string, string>> = {
  SSH_HOST_KEY: "SSH host key", HTTPS_CERTIFICATE: "HTTPS certificate fingerprint",
};

/** The three header states of the identity badge, each a word with its colour: green verified, red mismatch, grey not verified yet. */
export function identityBadge(device: { readonly vendor_hint?: string; readonly identity_check?: IdentityCheckView }):
  { readonly tone: Tone; readonly label: string; readonly title: string } {
  const check = device.identity_check;
  const when = check?.checked_at ? ` (${formatStamp(check.checked_at)})` : "";
  const what = IDENTITY_TRANSPORT_WORDS[check?.basis ?? ""] ?? "pinned identity";
  if (check?.state === "VERIFIED") {
    return { tone: "ok", label: "✓ Identity verified", title: `The last connection presented the pinned ${what}${when}.` };
  }
  if (check?.state === "MISMATCH") {
    return { tone: "bad", label: "Identity mismatch", title: check.basis === "RECORDED_IDENTITY"
      ? "The identity the device presented differs from the identity recorded at enrollment."
      : `The ${what} the device presented did not match the pinned one${when}.` };
  }
  return { tone: "neutral", label: "Not verified yet", title: check?.first_use
    ? "The newest connection pinned the fingerprint itself; no later connection has been compared with it yet."
    : device.vendor_hint === "palo_alto"
      ? "Palo Alto connections have no pinned fingerprint (certificate verification is off for this deployment, PO decision 2026-09-21)."
      : "No comparison of a pinned SSH host key or HTTPS certificate fingerprint is on record yet." };
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

type LabeledMember = { readonly device_id: string; readonly hostname?: string | null; readonly ha_role?: string | null };

function memberLabel(member: LabeledMember, allMembers: readonly LabeledMember[]): string {
  const label = deviceNameLabel(member.hostname);
  const ordered = orderMembers(allMembers);
  const sameName = ordered.filter((m) => deviceNameLabel(m.hostname) === label);
  if (sameName.length < 2) return label;
  const role = member.ha_role?.toLowerCase();
  if (role && sameName.filter((m) => m.ha_role?.toLowerCase() === role).length === 1) return `${label} (${role})`;
  return `${label} #${sameName.findIndex((m) => m.device_id === member.device_id) + 1}`;
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

function clusterVendorLabel(vendorHint: string | null | undefined): string {
  return vendorHint === "cisco_asa" ? "Cisco ASA failover"
    : vendorHint === "fortinet" ? "FortiGate HA"
    : vendorHint === "palo_alto" ? "Palo Alto PAN-OS HA" : "Check Point ClusterXL";
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
function ContextHaBadge({ context, headerRole, scoped = false }: { readonly context: InventoryContext | undefined; readonly headerRole?: string | null; readonly scoped?: boolean }) {
  const ha = context?.ha;
  if (!ha) return null;
  // One HA statement per device (UI review 2026-10-18, P0-5B and F-9): the header badge and the identity grid already say
  // the role. The "HA role:" line stays only for a selected virtual system, or when it differs from the header's; when the
  // role is the same, only what is new (the cluster mode) remains, without the repeated role.
  if (!scoped && (headerRole ?? "").trim().toUpperCase() === ha.role.trim().toUpperCase()) {
    return ha.cluster_mode ? <Typography variant="body2" color="text.secondary">Cluster mode: {ha.cluster_mode}</Typography> : null;
  }
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

function DifferencesNote({ differences, members }: { readonly differences: readonly ClusterDifference[]; readonly members: readonly LabeledMember[] }) {
  if (differences.length === 0) return null;
  return (
    <Stack spacing={0.25}>
      {differences.map((d) => {
        const member = members.find((m) => m.device_id === d.device_id);
        return member ? (
        <Typography key={`${d.device_id}-${d.field}`} variant="body2" color="text.secondary">
          {memberLabel(member, members)}: {d.field} = {d.value}
        </Typography>
        ) : null;
      })}
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
      member_macs: iface.mac_address ? { [deviceId]: iface.mac_address } : {},
      member_zones: iface.zone ? { [deviceId]: iface.zone } : {},
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
const PHYSICAL_GROUP_LABEL = "Physical / VS0";

/** The routing tables page at this many routes. */
export const ROUTES_PAGE_SIZE = 50;

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

function ClusterRoutesTable({
  routes,
  members = [],
}: {
  readonly routes: readonly (ClusterRoute & { readonly vsysLabel?: string })[];
  readonly members?: readonly { readonly device_id: string; readonly hostname?: string | null }[];
}) {
  const [diffOnly, setDiffOnly] = useState<boolean>(false);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  useEffect(() => { setPage(0); }, [diffOnly, search, routes.length]);

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
  // and the differences switch still run over every route and the page counter follows the filtered list.
  const pageCount = Math.max(1, Math.ceil(displayedRoutes.length / ROUTES_PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const pageRoutes = displayedRoutes.slice(safePage * ROUTES_PAGE_SIZE, (safePage + 1) * ROUTES_PAGE_SIZE);

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
              {showVsysColumn && <TableCell sx={{ fontWeight: 600 }}>{routes.some((r) => r.vsysLabel?.startsWith("VDOM:")) ? "VDOM" : "VSYS"}</TableCell>}
              <TableCell sx={{ fontWeight: 600 }}>Destination</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Next hop</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Interface</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>Protocol</TableCell>
              {!singleMember && <TableCell sx={{ fontWeight: 600 }}>Same on both members</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {pageRoutes.map((route, rowIndex) => {
              const index = safePage * ROUTES_PAGE_SIZE + rowIndex;
              const isShared = route.presence === "all";
              const isDiff = !isShared || route.differences.length > 0;
              let diffLabel = null;
              if (!isShared && Array.isArray(route.presence)) {
                const memberNames = route.presence.map((id) => memberNameById.get(id)).filter((name): name is string => Boolean(name)).join(", ");
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

/**
 * The group label of a merged, all-context table: the unified tab label for a virtual system (`VSYS: vsys1`,
 * `Physical / VS0`), `VDOM: root` for a FortiGate VDOM, whose contexts are the VDOM names themselves.
 */
function scopeGroupLabel(tabLabel: string, isPaloAlto: boolean): string {
  if (isPaloAlto || tabLabel === PHYSICAL_GROUP_LABEL) return tabLabel;
  return `VDOM: ${tabLabel.replace(/^(?:VSYS|VS|VSID):\s*/, "")}`;
}

/** A FortiGate's `root` VDOM leads the grouped table (it is the management VDOM); the unified tabs otherwise sort by name. */
function rootFirst<T extends { readonly context: string }>(tabs: readonly T[], groupByContext: boolean): readonly T[] {
  if (!groupByContext) return tabs;
  return [...tabs].sort((a, b) => Number(b.context.toLowerCase() === "root") - Number(a.context.toLowerCase() === "root"));
}

export function InterfacesPanel({
  contexts,
  virtualSystems,
  activeContext,
  onSelectContext,
  isPaloAlto = false,
  groupByContext = false,
  member,
  headerHaRole,
}: {
  readonly contexts: readonly InventoryContext[];
  readonly virtualSystems?: readonly string[] | string | null;
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
  /** Fortinet (post-release review 2026-10-18, 1.8): every VDOM in one table, grouped by VDOM like Palo Alto's virtual systems. */
  readonly groupByContext?: boolean;
  readonly member: { readonly device_id: string; readonly hostname?: string | null };
  /** The HA role the device header already states, so the per-context line does not repeat it. */
  readonly headerHaRole?: string | null;
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
  const grouped = isPaloAlto || groupByContext;
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
  if (!grouped) {
    // A device whose contexts are its members (an Infoblox grid, V72) has no "physical" context: show the first one
    // until the sidebar selects a member.
    const context = resolveContext(activeContext ?? "physical") ?? (activeContext ? undefined : contexts[0]);
    // One HA statement per device (UI review 2026-10-18, P0-5B): the header carries it; the per-context line is only
    // for a selected virtual system, whose own role can differ from the chassis.
    return (
      <Stack spacing={1}>
        <ContextHaBadge context={context} headerRole={headerHaRole} scoped={Boolean(activeContext)} />
        <ClusterInterfacesTable interfaces={context ? toClusterContexts([context], member.device_id)[0].interfaces : []} members={members} />
      </Stack>
    );
  }

  if (!activeContext) {
    const merged = rootFirst(tabs, groupByContext).flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return toClusterContexts([found], member.device_id)[0].interfaces.map((iface) => ({ ...iface, vsysLabel: scopeGroupLabel(tab.label, isPaloAlto) }));
    });
    return (
      <Stack spacing={1}>
        <ClusterInterfacesTable interfaces={merged} members={members} />
      </Stack>
    );
  }
  const context = resolveContext(activeContext);
  return (
    <Stack spacing={1}>
      <ContextHaBadge context={context} headerRole={headerHaRole} scoped />
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
  groupByContext = false,
  member,
}: {
  readonly contexts: readonly InventoryContext[];
  readonly virtualSystems?: readonly string[] | string | null;
  readonly activeContext?: string | null;
  readonly onSelectContext?: (ctx: string) => void;
  readonly isPaloAlto?: boolean;
  /** Fortinet: every VDOM's routes in one table with a VDOM column, as the header's route count is their sum (1.8). */
  readonly groupByContext?: boolean;
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
  const grouped = isPaloAlto || groupByContext;
  const vsList = Array.isArray(virtualSystems)
    ? virtualSystems
    : typeof virtualSystems === "string"
    ? virtualSystems.split(/,\s*/).filter(Boolean)
    : [];
  const { tabs, resolveContext } = useMemo(
    () => buildUnifiedContextTabs(contexts, vsList),
    [contexts, vsList]
  );

  if (!grouped) {
    // A device whose contexts are its members (an Infoblox grid, V72) has no "physical" context: show the first one
    // until the sidebar selects a member.
    const context = resolveContext(activeContext ?? "physical") ?? (activeContext ? undefined : contexts[0]);
    return <ClusterRoutesTable routes={context ? toClusterContexts([context], member.device_id)[0].routes : []} members={members} />;
  }

  if (!activeContext) {
    const merged = rootFirst(tabs, groupByContext).flatMap((tab) => {
      const found = resolveContext(tab.context);
      if (!found) return [];
      return toClusterContexts([found], member.device_id)[0].routes.map((route) => ({ ...route, vsysLabel: scopeGroupLabel(tab.label, isPaloAlto) }));
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
      <M3Button deviceJob plane={PLANE_COLLECT}
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

interface CountedContext {
  readonly context: string;
  readonly interfaces: readonly { readonly kind: string }[];
  readonly routes: readonly unknown[];
}

const SCOPE_NOUN: Readonly<Record<string, string>> = {
  fortinet: "VDOMs", check_point: "virtual systems", palo_alto: "virtual systems", cisco_asa: "contexts",
};

/**
 * The header's interface and route counts, on the same denominator the Interfaces and Routing tabs open with (UI
 * review 2026-10-18, P0-5B: "60 interfaces" in the header against "31 of 36 shown" in the tab). Palo Alto's tab merges every
 * virtual system, so its count is the sum; every other vendor opens on the first context (Physical / root), so that
 * count is the headline and the interfaces that live in the other contexts are stated beside it, never folded in.
 * Loopbacks are left out because the table leaves them out. With `scope` (a selected virtual system) only that context
 * is counted.
 */
export function interfaceRouteCounts(contexts: readonly CountedContext[], vendorHint: string | null | undefined, scope?: CountedContext | null):
  { readonly interfaces: number; readonly elsewhere: number; readonly routes: number; readonly scopes?: number } {
  const listed = (c: CountedContext) => c.interfaces.filter((i) => i.kind !== "loopback").length;
  if (scope) return { interfaces: listed(scope), elsewhere: 0, routes: scope.routes.length };
  // Palo Alto's and Fortinet's tabs open on every virtual system / VDOM grouped in one table (F-19; post-release review
  // 2026-10-18, 1.8), so the headline is the sum and the number of scopes is said beside it.
  if (vendorHint === "palo_alto" || vendorHint === "fortinet") {
    return { interfaces: contexts.reduce((n, c) => n + listed(c), 0), elsewhere: 0, routes: contexts.reduce((n, c) => n + c.routes.length, 0),
      scopes: vendorHint === "fortinet" && contexts.length > 1 ? contexts.length : undefined };
  }
  const first = contexts.find((c) => c.context.toLowerCase() === "physical" || c.context === "0") ?? contexts[0];
  const elsewhere = contexts.filter((c) => c !== first).reduce((n, c) => n + listed(c), 0);
  return { interfaces: first ? listed(first) : 0, elsewhere, routes: first ? first.routes.length : 0 };
}

export function interfaceRouteLabel(counts: ReturnType<typeof interfaceRouteCounts>, vendorHint: string | null | undefined): string {
  const where = counts.elsewhere > 0 ? ` · ${counts.elsewhere} in ${SCOPE_NOUN[vendorHint ?? ""] ?? "other contexts"}` : "";
  const scopes = counts.scopes ? ` · in ${counts.scopes} ${SCOPE_NOUN[vendorHint ?? ""] ?? "contexts"}` : "";
  return `${plural(counts.interfaces, "interface")}${where}${scopes} · ${plural(counts.routes, "route")}`;
}

/**
 * One HA statement per device (UI review 2026-10-18, P0-5B): a named but unconfirmed peer and the observed role read as
 * one sentence instead of three pills (`Standalone`, `HA peer named, not confirmed`, `PRIMARY`). A management server
 * has no HA role to show (PO decision 3: data a device does not have is not shown), and a gateway outside a cluster
 * whose collector wrote UNKNOWN or STANDALONE has none either (post-release review 2026-10-18, R9): the form pill
 * already says `Standalone`.
 */
export function haStatement(device: { readonly ha_peer_unconfirmed?: boolean; readonly ha_role: string | null; readonly cluster_member_ref: string | null; readonly role?: string }):
  { readonly kind: "statement"; readonly text: string } | { readonly kind: "role"; readonly role: string } | null {
  if (device.role === "management_server") return null;
  const role = knownHaRole(device);
  if (device.ha_peer_unconfirmed && !device.cluster_member_ref) {
    return { kind: "statement", text: role ? `HA: peer named, not confirmed · observed role ${role}` : "HA: peer named, not confirmed" };
  }
  if (!device.cluster_member_ref) return role ? { kind: "role", role: device.ha_role! } : null;
  return device.ha_role ? { kind: "role", role: device.ha_role } : null;
}

/**
 * One platform pill (R13): `Quantum 29200 · R81.20 · Gaia` instead of a model pill and a version pill. Nothing is
 * dropped; the two facts share a pill. Null when neither is known (the Identity grid then says Not read yet).
 */
export function platformPillLabel(device: Pick<DeviceSummary, "vendor_hint" | "model" | "platform_family" | "software_version">): string | null {
  const os = device.vendor_hint === "palo_alto" ? " · PAN-OS" : device.vendor_hint === "check_point" ? " · Gaia" : "";
  const parts = [device.model || device.platform_family || null, device.software_version ? `${device.software_version}${os}` : null].filter((p): p is string => Boolean(p));
  return parts.length > 0 ? parts.join(" · ") : null;
}

/** The vendors whose policy the Policy screen collects (its sources are MDS and Panorama). */
const POLICY_VENDORS: ReadonlySet<string> = new Set(["check_point", "palo_alto"]);

/**
 * The header's policy link (post-release review 2026-10-18, R10): `Open assigned policy` on a firewall or member whose
 * vendor's policy neXus collects, `Open policies this server manages →` on its management server, and nothing on an
 * appliance or a vendor without a policy source -- a link that leads to an empty screen is noise, not capability.
 */
export function policyLink(device: { readonly device_id: string; readonly vendor_hint: string; readonly role?: string | null }, href: string | undefined):
  { readonly href: string; readonly label: string } | null {
  if (!href || !POLICY_VENDORS.has(device.vendor_hint) || device.role === "appliance") return null;
  if (device.role === "management_server") return { href, label: "Open policies this server manages →" };
  return { href, label: "Open assigned policy" };
}

/**
 * The pane header of a selected virtual system: `CLS-ALPHA-01 › VS-ALPHA-01-13` (UI review 2026-10-18, P0-3B). The
 * parent is a link back to the unscoped pane, whose Cluster members, Configuration, Identity and Failover tabs stay
 * there; the switcher moves between sibling virtual systems without going through the sidebar.
 */
function ScopeBreadcrumb({ parent, scope, onParent }: {
  readonly parent: string;
  readonly scope: string;
  readonly onParent: () => void;
}) {
  return (
    <Box component="span" sx={{ display: "inline-flex", alignItems: "baseline", gap: 1, flexWrap: "wrap" }}>
      <Link component="button" type="button" underline="hover" color="inherit" onClick={onParent} title={`Back to ${parent}`}
        sx={{ font: "inherit", color: m3.primary, verticalAlign: "baseline" }}>{parent}</Link>
      <Box component="span" sx={{ color: m3.outline }}>›</Box>
      <Box component="span">{scope}</Box>
    </Box>
  );
}

function VsSwitcher({ scope, scopes, onSelect }: { readonly scope: string; readonly scopes: readonly string[]; readonly onSelect: (scope: string) => void }) {
  const options = scopes.includes(scope) ? scopes : [scope, ...scopes];
  return (
    <Select size="small" value={scope} onChange={(e) => onSelect(String(e.target.value))} inputProps={{ "aria-label": "Switch virtual system" }}
      sx={{ minWidth: 200, height: 32, fontSize: 13, fontFamily: MONO }}>
      {options.map((name) => <MenuItem key={name} value={name} sx={{ fontSize: 13, fontFamily: MONO }}>{name}</MenuItem>)}
    </Select>
  );
}

/**
 * The detail panels for one selected device (WORKER.md "Frontend"):
 * fetches `GET /devices/{id}/inventory`, or the cluster view when the
 * device carries a `cluster_member_ref`. Remounted by its caller (`key={
 * device.device_id}`) on every selection change so `useFetchOnMount`'s own
 * mount effect re-runs -- it never re-triggers on a changed fetcher alone.
 */

/** One header for every inventory entity (standalone device or cluster): avatar, title, status chips,
 * an optional action on the right and optional body rows below (member cards). */
export function InventoryEntityHeader({
  vendorHint,
  model,
  titlePrefix,
  title,
  breadcrumb,
  chips,
  action,
  readAges,
  state,
  link,
  children,
}: {
  readonly vendorHint: string;
  readonly model?: string | null;
  readonly titlePrefix?: string;
  readonly title: string;
  /** Replaces the title text (a selected virtual system: parent link, `›`, scope name); `title` still names the avatar. */
  readonly breadcrumb?: React.ReactNode;
  readonly chips: React.ReactNode;
  readonly action?: React.ReactNode;
  /** "Platform read 4 h ago · Config read 22 h ago" (UI review 2026-10-18, F-9): the header's own row, left. */
  readonly readAges?: React.ReactNode;
  /** The state row's pills in plane order (Compliance · Config changed · Backup · Readiness · Certificate), after the read ages. */
  readonly state?: React.ReactNode;
  /** A pill-shaped link that belongs to the entity ("Open assigned policy"), right-aligned inside the card. */
  readonly link?: React.ReactNode;
  readonly children?: React.ReactNode;
}) {
  return (
    <ObjectHeader
      avatar={<VendorAvatar vendorHint={vendorHint} model={model} hostname={title} />}
      title={<>{titlePrefix ? `${titlePrefix} ` : ""}{title}</>}
      breadcrumb={breadcrumb}
      badges={chips}
      action={action}
      readAges={readAges}
      state={state}
      link={link}
    >
      {children}
    </ObjectHeader>
  );
}

/** A link shaped as a pill, for a header: it sits on the right of the card, not floating above it. */
export function HeaderPillLink({ href, onClick, title, children }: {
  readonly href?: string;
  /** Given instead of `href`: the pill is a button that acts in place (no page load). */
  readonly onClick?: () => void;
  readonly title?: string;
  readonly children: React.ReactNode;
}) {
  const sx = { display: "inline-flex", alignItems: "center", height: 26, px: 1.5, borderRadius: "13px", border: `1px solid ${m3.outline}`,
    color: m3.primary, fontSize: 12.5, fontWeight: 500, textDecoration: "none", whiteSpace: "nowrap", bgcolor: "transparent", font: "inherit", cursor: "pointer",
    "&:hover": { bgcolor: m3.secondaryContainer }, "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 } } as const;
  if (onClick && !href) return <Box component="button" type="button" onClick={onClick} title={title} sx={sx}>{children}</Box>;
  return <Box component="a" href={href} title={title} sx={sx}>{children}</Box>;
}

/** A configuration read this much older than the platform read is flagged amber (UI review 2026-10-18, P1-1B). */
const CONFIG_STALE_AFTER_MS = 24 * 3_600_000;

/** One member's read times, for the cluster header's read ages (the oldest member is the headline, each member on hover). */
export interface MemberReadAges { readonly label: string; readonly platformAt: string | null | undefined; readonly configAt?: string | null }

/** The oldest of the given times; null when any of them is missing (an unread member makes the unit unread). */
export function oldestRead(times: ReadonlyArray<string | null | undefined>): string | null {
  if (times.length === 0 || times.some((t) => !t)) return null;
  return times.reduce<string>((oldest, t) => (new Date(t!).getTime() < new Date(oldest).getTime() ? t! : oldest), times[0]!);
}

/**
 * How long ago the platform facts and the configuration were read, the exact `YYYY-MM-DD HH:MM GMT+3` on hover (R15);
 * the full stamps stay in the Platform identity grid. With `members` (a cluster, post-release review 2026-10-18, R13)
 * the headline is the oldest member's read and the hover lists every member.
 */
export function HeaderReadAges({ platformAt, config, members }: {
  readonly platformAt: string | null | undefined;
  /** Omitted when the vendor has no configuration read, or the read could not be fetched (no claim is made then). */
  readonly config?: { readonly at: string | null };
  readonly members?: readonly MemberReadAges[];
}) {
  const perMember = (pick: (m: MemberReadAges) => string | null | undefined) =>
    members && members.length > 0 ? ` · ${members.map((m) => `${m.label} ${pick(m) ? formatStamp(pick(m)) : "not read yet"}`).join(" · ")}` : "";
  const stamp = (at: string, pick: (m: MemberReadAges) => string | null | undefined) =>
    `${formatStamp(at)}${members && members.length > 0 ? ` (oldest member)${perMember(pick)}` : " (exact times are in the Platform identity grid)"}`;
  // A configuration read more than a day older than the latest platform read is flagged (P1-1B): the platform moved on, the configuration did not.
  const configBehind = Boolean(config?.at && platformAt) && new Date(platformAt!).getTime() - new Date(config!.at!).getTime() >= CONFIG_STALE_AFTER_MS;
  return (
    <Typography component="div" variant="body2" data-testid="header-read-ages"
      sx={{ color: m3.onSurfaceVar, display: "flex", alignItems: "center", columnGap: 1, rowGap: 0.5, flexWrap: "wrap" }}>
      {platformAt
        ? <Tooltip title={stamp(platformAt, (m) => m.platformAt)}><span>Platform read {relativeAge(platformAt)}</span></Tooltip>
        : <Tooltip title={members && members.length > 0 ? `Not every member has a platform read${perMember((m) => m.platformAt)}` : ""}><span>Platform not read yet</span></Tooltip>}
      {config && <Box component="span" aria-hidden sx={{ color: m3.outline }}>·</Box>}
      {config && (config.at
        ? configBehind
          ? <Tooltip title={stamp(config.at, (m) => m.configAt)}><span><StatusChip tone="warn" label={`Config read ${relativeAge(config.at)}`} dense /></span></Tooltip>
          : <Tooltip title={stamp(config.at, (m) => m.configAt)}><span>Config read {relativeAge(config.at)}</span></Tooltip>
        : <Tooltip title={members && members.length > 0 ? `Not every member has a configuration read${perMember((m) => m.configAt)}` : ""}><span>Config not read yet</span></Tooltip>)}
    </Typography>
  );
}

function IdentityReference({ value }: { readonly value: string }) {
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <Typography variant="caption">Reference</Typography>
      <ReferenceCell value={value} />
    </Stack>
  );
}

/** Lets a panel inside the tab strip open another tab and land on an anchor in it (the Identity tab's link to the grid). */
const DetailTabsContext = createContext<{ readonly has: (label: string) => boolean; readonly open: (label: string, anchorId?: string) => void } | null>(null);

/** The detail tabs of the one device screen: the Configuration tab only when the vendor has a configuration read. */
function DetailTabs({ ariaLabel, tabs, configuration, initialTab, selectionKey, onlyTabs }: {
  readonly ariaLabel: string;
  readonly tabs: ReadonlyArray<{ label: string; panel: React.ReactNode }>;
  readonly configuration?: React.ReactNode;
  readonly initialTab?: string | null;
  readonly selectionKey?: string | null;
  /** A selected virtual system shows only its own data: these tabs and no others (the parent link leads back to the rest). */
  readonly onlyTabs?: readonly string[];
}) {
  const shown = tabs.filter((t) => (!onlyTabs || onlyTabs.includes(t.label)) && (t.label !== "Configuration" || Boolean(configuration)));
  const wanted = initialTab ? shown.findIndex((t) => t.label.toLowerCase() === initialTab.toLowerCase()) : -1;
  return <DetailTabsBody key={`${wanted}:${selectionKey ?? ""}`} ariaLabel={ariaLabel} tabs={shown} initial={Math.max(0, wanted)} />;
}

function DetailTabsBody({ ariaLabel, tabs, initial }: {
  readonly ariaLabel: string;
  readonly tabs: ReadonlyArray<{ label: string; panel: React.ReactNode }>;
  readonly initial: number;
}) {
  const [value, setValue] = useState(initial);
  const [anchor, setAnchor] = useState<string | null>(null);
  useEffect(() => {
    if (!anchor) return;
    document.getElementById(anchor)?.scrollIntoView?.({ behavior: "smooth", block: "start" });
    setAnchor(null);
  }, [anchor, value]);
  const nav = useMemo(() => ({
    has: (label: string) => tabs.some((t) => t.label === label),
    open: (label: string, anchorId?: string) => {
      const index = tabs.findIndex((t) => t.label === label);
      if (index < 0) return;
      setValue(index);
      if (anchorId) setAnchor(anchorId);
    },
  }), [tabs]);
  return (
    <DetailTabsContext.Provider value={nav}>
      <M3Tabs ariaLabel={ariaLabel} tabs={tabs} value={value} onChange={setValue} />
    </DetailTabsContext.Provider>
  );
}

/** The tab strip's navigation for a panel inside it (the Configuration tab's identity line opens the Identity tab's grid). */
export function useDetailTabs() {
  return useContext(DetailTabsContext);
}

/**
 * What the identity badge rests on, stated once on the Identity tab (UI review 2026-10-18, P0-1B; roadmap 2.6), next to
 * the badge itself.
 */
function IdentityBasisCard({ device }: { readonly device: DeviceSummary }) {
  const badge = identityBadge(device);
  return (
    <Box sx={{ p: 2, borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, display: "flex", flexDirection: "column", gap: 1 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Identity badge</Typography>
        <span title={badge.title}><StatusChip tone={badge.tone} label={badge.label} dense /></span>
      </Box>
      <Typography variant="body2">{IDENTITY_VERIFIED_BASIS}</Typography>
    </Box>
  );
}

/** M1 / M2 pills that pick one member of a cluster (the Logs tab, the header's Collect now). */
function MemberPills({ members, allMembers, selected, onSelect, ariaLabel, caption = true }: {
  readonly members: readonly DeviceSummary[];
  readonly allMembers: readonly LabeledMember[];
  readonly selected: DeviceSummary;
  readonly onSelect: (index: number) => void;
  readonly ariaLabel: string;
  readonly caption?: boolean;
}) {
  return (
    <Box role="group" aria-label={ariaLabel} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
      {members.map((m, i) => (
        <Box key={m.device_id} component="button" type="button" aria-pressed={m.device_id === selected.device_id}
          title={memberLabel(m, allMembers)} onClick={() => onSelect(i)}
          sx={{ cursor: "pointer", font: "inherit", fontSize: 13, fontWeight: 600, px: 1.5, py: 0.4, borderRadius: "14px", color: m3.onSurface,
            border: `1px solid ${m.device_id === selected.device_id ? m3.primary : m3.outline}`,
            bgcolor: m.device_id === selected.device_id ? m3.secondaryContainer : "transparent" }}>
          M{i + 1}
        </Box>
      ))}
      {caption && (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          {memberLabel(selected, allMembers)}{selected.ha_role ? ` · ${selected.ha_role.trim().toUpperCase()}` : ""}
        </Typography>
      )}
    </Box>
  );
}

/**
 * One Collect now per page (UI product review 2026-10-18, phase 1.3): the cluster header carries it, with M1 / M2 pills
 * choosing which member's inventory is read. The member cards and the Cluster members tab carry none.
 */
function ClusterCollectNow({ members, onCollected }: { readonly members: readonly DeviceSummary[]; readonly onCollected: () => void }) {
  const [selected, setSelected] = useState(0);
  const member = members[Math.min(selected, members.length - 1)];
  if (!member) return null;
  return (
    <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 0.75 }}>
      <MemberPills members={members} allMembers={members} selected={member} onSelect={setSelected} ariaLabel="Collect from member" caption={false} />
      <CollectNowButton key={member.device_id} deviceId={member.device_id} onCollected={onCollected} enrollmentState={member.enrollment_state} />
    </Box>
  );
}

export function DeviceInventoryPanels({
  device,
  initialVs,
  onDeviceStateChange,
  onSelectVs,
  managedEstate,
  policyHref,
  configuration,
  initialTab,
  onOpenCluster,
}: {
  readonly device: DeviceSummary;
  readonly initialVs?: string;
  /** A cluster member's header links to its parent; given, the cluster pane opens in place, else the link reloads with `?cluster_ref=`. */
  readonly onOpenCluster?: (clusterRef: string) => void;
  readonly onDeviceStateChange?: () => void;
  /** A selected virtual system moved (switcher) or was left (parent link, null): the caller keeps the sidebar's selection in step. */
  readonly onSelectVs?: (vs: string | null) => void;
  /** The management server's discovery content (domains, clusters, gateways ...), shown as the "Managed estate" tab. */
  readonly managedEstate?: React.ReactNode;
  /** Where "Open assigned policy" leads; shown as a pill on the right of the header card (UI review 2026-10-18, F-9). */
  readonly policyHref?: string;
  /** One device screen (PO 2026-09-25): the configuration tab's content, when the vendor has a configuration read. */
  readonly configuration?: React.ReactNode;
  /** "configuration" opens that tab (old Config links land here). */
  readonly initialTab?: string | null;
}) {
  const isCluster = device.cluster_member_ref !== null;
  const isPaloAlto = device.vendor_hint === "palo_alto";
  // A FortiGate's VDOMs are grouped in one table like Palo Alto's virtual systems (post-release review 2026-10-18, 1.8).
  const isFortinet = device.vendor_hint === "fortinet";
  const hasManagedDevices = device.role === "management_server" &&
    (device.vendor_hint === "bluecoat" || device.vendor_hint === "fortinet");
  // All five manager types (MDS, Panorama, FortiManager, Blue Coat MC, Radware Cyber Controller) show what they manage as
  // one "Managed estate" tab under the device header (UI review 2026-10-18, P0-2B).
  const isManager = device.role === "management_server" &&
    ["check_point", "palo_alto", "radware", "fortinet", "bluecoat"].includes(device.vendor_hint);
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
  const clusterInventoryFetch = useFetchOnMount<ClusterInventory | null>(
    () => device.cluster_member_ref === null ? Promise.resolve(null) : getClusterInventory(device.cluster_member_ref),
    describeApiError,
  );

  // P0-1B (UI review 2026-10-18): one certificate read feeds the header state pill and the Identity tab card.
  const certificateFetch = useFetchOnMount(() => getHttpsCertificate(device.device_id), () => "Certificate details unavailable.");
  // F-9: the header says how long ago the configuration was read; only a vendor with a configuration read is asked, and a
  // refused or failed read makes no claim at all (the exact time stays in the Platform identity grid).
  const configReadFetch = useFetchOnMount<DeviceConfiguration | null>(
    () => (configuration ? getDeviceConfiguration(device.device_id) : Promise.resolve(null)), () => "Configuration read unavailable.");
  // The header state row (roadmap 2.1): compliance only where the fleet assesses it (a vendor with a configuration read,
  // never a management server), readiness only for a member of a Check Point or Palo Alto cluster, the backup for every
  // device. A refused or failed read makes no claim: that pill is simply absent.
  const assessed = Boolean(configuration) && device.role !== "management_server";
  const readinessRead = isCluster && READINESS_VENDORS.has(device.vendor_hint);
  const backupsFetch = useFetchOnMount(() => listDeviceBackups(device.device_id), () => "Backups unavailable.");
  const complianceFetch = useFetchOnMount<DeviceComplianceResult | null>(
    () => (assessed ? getDeviceCompliance(device.device_id) : Promise.resolve(null)), () => "Compliance unavailable.");
  const readinessFetch = useFetchOnMount<CpFailoverSummary[] | null>(
    () => (readinessRead ? listCpFailoverSummary() : Promise.resolve(null)), () => "Readiness unavailable.");

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
  // A selected virtual system scopes the whole pane to it (P0-3B); only Check Point VSX and Palo Alto VSYS have them.
  const vsNames = (() => {
    const fromInventory = Array.isArray(deviceInventory?.virtual_systems) ? deviceInventory!.virtual_systems as readonly string[]
      : typeof deviceInventory?.virtual_systems === "string" ? deviceInventory.virtual_systems.split(/,\s*/).filter(Boolean) : [];
    return Array.from(new Set([...fromInventory, ...vsListOf(device)]));
  })();
  const scopedName = activeContext && !isCluster && !isManager && (device.vendor_hint === "check_point" || device.vendor_hint === "palo_alto")
    ? activeContext : null;
  const scopedContext = scopedName ? buildUnifiedContextTabs(deviceContexts, vsNames).resolveContext(scopedName) ?? null : null;
  const headerCounts = interfaceRouteCounts(deviceContexts, device.vendor_hint, scopedName ? scopedContext ?? { context: scopedName, interfaces: [], routes: [] } : null);
  const deviceIfaceCount = deviceContexts.reduce((acc, c) => acc + c.interfaces.length, 0);
  const deviceRouteCount = deviceContexts.reduce((acc, c) => acc + c.routes.length, 0);
  // PO 2026-09-25 (decision 3): data a device does not have is not shown. A single device whose completed inventory
  // reported no interfaces and no routes (a Management Center, a DefensePro, a Cyber Controller) shows neither tab nor
  // the count chip; a device never collected keeps them, with the Collect-now empty state.
  const singleDeviceHasNoNetworkEvidence = !isCluster && Boolean(deviceInventory?.collected_at) &&
    deviceIfaceCount === 0 && deviceRouteCount === 0 && deviceContexts.every((c) => !c.vs_name);
  // Live = device evidence has been read: interface addresses, or (HTTPS appliances and management servers whose
  // evidence is members / managed devices, not interfaces) a completed inventory run.
  const isLiveDevice = deviceLiveWithInventory(device, deviceInventory?.collected_at);
  const isEnrolledDevice = device.enrollment_state === "ENROLLED";
  const configRead = configuration ? configReadFetch.data : null;
  const stateFacts = [
    assessed ? complianceFact(complianceFetch.data, { configurationRead: Boolean(configRead?.collected_at), vendor: device.vendor_hint }) : null,
    configChangedFact(configRead),
    backupFact(backupsFetch.data?.backups, device, backupsHref(device.cluster_member_ref ?? device.device_id)),
    readinessRead && device.cluster_member_ref ? readinessFact(readinessFetch.data, device.cluster_member_ref) : null,
  ];

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={device.vendor_hint}
        model={device.model}
        title={deviceNameLabel(device.hostname)}
        breadcrumb={scopedName ? <ScopeBreadcrumb parent={deviceNameLabel(device.hostname)} scope={scopedName}
          onParent={() => { setActiveContext(null); onSelectVs?.(null); }} /> : undefined}
        chips={
          // Two pill rows at most (post-release review 2026-10-18, R13): this identity row -- state word, identity badge,
          // form (cluster link or vendor · mode), HA, one platform pill, counts -- and the state row below. The job tick,
          // the member marker and the `Members:` line are gone from here: the latest job is a sentence on the Live
          // chip's hover, and the members are the cluster page's own cards, one click away through the cluster pill.
          <>
            {scopedName && <VsSwitcher scope={scopedName} scopes={vsNames} onSelect={(next) => { setActiveContext(next); onSelectVs?.(next); }} />}
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
              <span title={isLiveDevice ? liveBasis(latestJobSentence(device.latest_job_state, device.latest_job_type)) : undefined}>
                <StatusChip
                  tone={isLiveDevice ? "ok" : "warn"}
                  label={!isEnrolledDevice ? enrollmentStateLabel(device.enrollment_state) : isLiveDevice ? "Live" : "Confirmed · Not collected"}
                  dense
                />
              </span>
            )}
            {(() => {
              const badge = identityBadge(device);
              return <span title={badge.title}><StatusChip tone={badge.tone} label={badge.label} dense /></span>;
            })()}
            {(() => {
              const ha = haStatement(device);
              return (
                <>
                  {device.cluster_member_ref ? (
                    // The member's way to its parent (roadmap 2.2): the cluster page holds Cluster members, Failover and the side-by-side configuration.
                    <HeaderPillLink title="Open the cluster: its members, failover and the members' configuration side by side"
                      {...(onOpenCluster ? { onClick: () => onOpenCluster(device.cluster_member_ref!) } : { href: `?screen=inventory&cluster_ref=${encodeURIComponent(device.cluster_member_ref)}` })}>
                      {clusterVendorLabel(device.vendor_hint)} · {device.cluster_member_ref}
                    </HeaderPillLink>
                  ) : (
                    // One HA statement per device (R16): a gateway with a named peer is not called Standalone beside its HA sentence.
                    <StatusChip
                      tone="neutral"
                      label={`${isPaloAlto ? "Palo Alto" : vendorDisplayName(device.vendor_hint)}${device.role === "management_server" ? " · Management server"
                        : device.role === "appliance" ? " · Appliance" : ha?.kind === "statement" ? "" : " · Standalone"}`}
                      dense
                    />
                  )}
                  {ha === null ? null : ha.kind === "statement" ? <StatusChip tone="neutral" label={ha.text} dense /> : <RoleChip role={ha.role} dense />}
                </>
              );
            })()}
            {platformPillLabel(device) && <StatusChip tone="neutral" label={platformPillLabel(device)!} dense />}
            {!singleDeviceHasNoNetworkEvidence && <StatusChip tone="neutral" label={interfaceRouteLabel(headerCounts, device.vendor_hint)} dense />}
          </>
        }
        action={<CollectNowButton deviceId={device.device_id} onCollected={refresh} enrollmentState={device.enrollment_state} />}
        readAges={<HeaderReadAges platformAt={device.platform_facts_observed_at}
          config={configuration && configReadFetch.data ? { at: configReadFetch.data.collected_at } : undefined} />}
        state={<HeaderStateRow facts={stateFacts} trailing={<CertificateStatePill view={certificateFetch.data} />} />}
        link={(() => {
          const link = policyLink(device, policyHref);
          return link ? <HeaderPillLink href={link.href}>{link.label}</HeaderPillLink> : undefined;
        })()}
      />

      <DetailTabs
        ariaLabel="Device detail"
        configuration={configuration}
        initialTab={isManager ? (initialVs ? "managed estate" : initialTab ?? "managed estate") : initialTab}
        selectionKey={scopedName ?? (isManager ? initialVs : undefined)}
        onlyTabs={scopedName ? ["Interfaces", "Routing"] : undefined}
        tabs={[
          ...(isManager ? [{
            label: "Managed estate",
            panel: hasManagedDevices
              ? <ManagedDevicesPanel members={deviceInventory?.grid_members ?? []} selected={activeContext} onSelect={setActiveContext} />
              : managedEstate ?? <EmptyPanel title="Managed estate" body="What this management server manages is read by discovery; open Administration to run Import from manager." />,
          }] : []),
          ...(!singleDeviceHasNoNetworkEvidence ? [{
            label: "Interfaces",
            panel: isCluster
              ? <ClusterInterfacesPanel contexts={clusterInventory?.contexts ?? []} members={clusterInventory ? orderMembers(clusterInventory.members) : undefined} virtualSystems={clusterInventory?.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />
              : <InterfacesPanel contexts={deviceInventory?.contexts ?? []} virtualSystems={deviceInventory?.virtual_systems ?? device.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} groupByContext={isFortinet} member={device} headerHaRole={device.ha_role} />,
          }, {
            label: "Routing",
            panel: isCluster
              ? <ClusterRoutesPanel contexts={clusterInventory?.contexts ?? []} members={clusterInventory ? orderMembers(clusterInventory.members) : undefined} virtualSystems={clusterInventory?.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} />
              : <RoutesPanel contexts={deviceInventory?.contexts ?? []} virtualSystems={deviceInventory?.virtual_systems ?? device.virtual_systems} activeContext={activeContext} onSelectContext={setActiveContext} isPaloAlto={isPaloAlto} groupByContext={isFortinet} member={device} />,
          }] : []),
          // The Cluster members tab is a cluster-level fact and lives on the cluster page only (roadmap 2.2); the member's
          // header links there. An Infoblox Grid Manager keeps its own Grid members tab.
          ...(device.vendor_hint === "infoblox" && !hasManagedDevices ? [{
            label: "Grid members",
            panel: <GridMembersPanel members={deviceInventory?.grid_members ?? []} summary={deviceInventory?.grid_summary} />,
          }] : []),
          {
            label: "Configuration",
            panel: configuration,
          },
          {
            label: "Backup",
            panel: <BackupPanel deviceId={device.device_id} vendorHint={device.vendor_hint} backupTarget={device.backup_target} />,
          },
          // System logs are collected from Check Point and Palo Alto devices only (phase B contract section 2); a
          // management server of those vendors gets the tab too, with the "collection disabled" state.
          ...((device.vendor_hint === "check_point" || device.vendor_hint === "palo_alto") ? [{
            label: "Logs",
            panel: <DeviceLogsPanel key={device.device_id} deviceId={device.device_id} vendorHint={device.vendor_hint} />,
          }] : []),
          {
            label: "Identity",
            // Roadmap 2.6: the audit grid (with reference and lifecycle), the certificate block and what the badge rests on, in one place.
            panel: (
              <Stack spacing={1.5}>
                <IdentityCard title={deviceNameLabel(device.hostname)} tiles={identityTiles(device, configRead?.collected_at ?? null, { configurationRead: Boolean(configuration) })} />
                <HttpsCertificateCard deviceId={device.device_id} data={certificateFetch.data}
                  error={certificateFetch.error} refresh={certificateFetch.refresh} />
                <IdentityBasisCard device={device} />
              </Stack>
            ),
          },
        ]}
      />
    </Stack>
  );
}

/**
 * The cluster pane's Logs tab (UI review 2026-10-18, F-21): the member Logs panel, one member at a time, chosen with the
 * M1 / M2 pills the member cards already use. The per-member stores stay as they are; this only picks which one to read.
 */
export function ClusterLogsPanel({ members, allMembers, vendorHint }: {
  readonly members: readonly DeviceSummary[];
  readonly allMembers: readonly LabeledMember[];
  readonly vendorHint: string;
}) {
  const [selected, setSelected] = useState(0);
  const member = members[Math.min(selected, members.length - 1)];
  if (!member) return <EmptyPanel title="No members" body="This cluster has no member whose logs can be read." />;
  return (
    <Stack spacing={1.5}>
      <MemberPills members={members} allMembers={allMembers} selected={member} onSelect={setSelected} ariaLabel="Cluster member" />
      <DeviceLogsPanel key={member.device_id} deviceId={member.device_id} vendorHint={vendorHint} />
    </Stack>
  );
}

export function ClusterDetailPanels({
  clusterRef,
  members,
  initialVs,
  onSelectVs,
  cache,
  onCacheUpdate,
  configuration,
  initialTab,
}: {
  readonly clusterRef: string;
  readonly members: readonly DeviceSummary[];
  readonly initialVs?: string | null;
  /** A selected virtual system moved (switcher) or was left (parent link, null): the caller keeps the sidebar's selection in step. */
  readonly onSelectVs?: (vs: string | null) => void;
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
  const inventoryMembers = clusterInventory
    ? orderMembers(clusterInventory.members.map((m) => ({
      ...m,
      ha_role: m.ha_role ?? members.find((summary) => summary.device_id === m.device_id)?.ha_role ?? null,
    })))
    : orderedMembers;
  const firstMember = orderedMembers[0];
  const isPaloAlto = firstMember?.vendor_hint === "palo_alto";
  const allContexts = clusterInventory?.contexts ?? [];
  const ifaceCount = allContexts.reduce((acc, c) => acc + c.interfaces.length, 0);
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
  // A selected virtual system scopes the whole pane to it (UI review 2026-10-18, P0-3B): header `CLS-ALPHA-01 › VS-ALPHA-01-13`
  // with a switcher, only that VS's Interfaces and Routing tabs, and the cluster's own tabs behind the parent link.
  const scopedName = activeVsContext;
  const scopedContext = scopedName ? buildUnifiedContextTabs(allContexts, virtualSystems).resolveContext(scopedName) ?? null : null;
  const headerCounts = interfaceRouteCounts(allContexts, firstMember?.vendor_hint,
    scopedName ? scopedContext ?? { context: scopedName, interfaces: [], routes: [] } : null);
  const leaveVs = () => { setActiveVsContext(null); onSelectVs?.(null); };
  const chooseVs = (next: string) => { setActiveVsContext(next); onSelectVs?.(next); };

  // The cluster header's state row (roadmap 2.1 / 2.2): readiness for the cluster, compliance pooled over the members,
  // the members that changed configuration, one backup pill per member. The plain Backups / Readiness links stay only
  // while the pill that would carry them is absent (a refused or failed read), so both ways in are always there.
  const [readsVersion, setReadsVersion] = useState(0);
  const vendor = firstMember?.vendor_hint ?? null;
  const readinessRead = Boolean(vendor && READINESS_VENDORS.has(vendor));
  const memberReads = useMemberHeaderReads(orderedMembers, { configuration: Boolean(configuration), assessed: Boolean(configuration), version: readsVersion });
  const readinessFetch = useFetchOnMount<CpFailoverSummary[] | null>(() => (readinessRead ? listCpFailoverSummary() : Promise.resolve(null)), () => "Readiness unavailable.");
  const labelled = orderedMembers.map((m, i) => ({ id: m.device_id, label: `M${i + 1}`, member: m }));
  const backupFacts = labelled.map((f) => backupFact(memberReads.backups[f.id], f.member, backupsHref(clusterRef), f.label));
  const readiness = readinessRead ? readinessFact(readinessFetch.data, clusterRef) : null;
  const clusterFacts = [
    configuration ? clusterComplianceFact(labelled.map((f) => ({ label: f.label, result: memberReads.compliance[f.id] ?? null,
      configurationRead: Boolean(memberReads.configuration[f.id]?.collected_at) })), vendor) : null,
    clusterConfigChangedFact(labelled.map((f) => ({ label: f.label, configuration: memberReads.configuration[f.id] ?? null }))),
    ...backupFacts,
    readiness,
  ];
  const fallbackLinks = clusterContextLinks(clusterRef).filter((l) =>
    (l.id === "backups" && backupFacts.every((f) => f === null)) || (l.id === "readiness" && readiness === null));

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={firstMember?.vendor_hint ?? "check_point"}
        model={firstMember?.model}
        title={clusterTitle}
        breadcrumb={scopedName ? <ScopeBreadcrumb parent={clusterTitle} scope={scopedName} onParent={leaveVs} /> : undefined}
        chips={
          <>
            {scopedName && <VsSwitcher scope={scopedName} scopes={virtualSystems} onSelect={chooseVs} />}
            <span title={isLive ? liveBasis(null) : undefined}>
              <StatusChip
                tone={isLive ? "ok" : "warn"}
                label={!isEnrolled ? "Not enrolled" : isLive ? "Live" : "Confirmed · Not collected"}
                dense
              />
            </span>
            <StatusChip
              tone="neutral"
              label={clusterVendorLabel(firstMember?.vendor_hint)}
              dense
            />
            {firstMember && platformPillLabel(firstMember) && <StatusChip tone="neutral" label={platformPillLabel(firstMember)!} dense />}
            <StatusChip tone="neutral" label={interfaceRouteLabel(headerCounts, firstMember?.vendor_hint)} dense />
          </>
        }
        action={<ClusterCollectNow members={orderedMembers} onCollected={() => { refresh(); setReadsVersion((v) => v + 1); }} />}
        // The cluster header's read ages (R13 / 2.1): the oldest member's platform and configuration read, each member on hover.
        readAges={<HeaderReadAges
          platformAt={oldestRead(orderedMembers.map((m) => m.platform_facts_observed_at))}
          // No claim about the configuration unless every member's read came back (a refused read on one member is not "not read yet").
          config={configuration && memberReads.loaded && orderedMembers.every((m) => memberReads.configuration[m.device_id])
            ? { at: oldestRead(orderedMembers.map((m) => memberReads.configuration[m.device_id]!.collected_at)) } : undefined}
          members={labelled.map((f) => ({ label: f.label, platformAt: f.member.platform_facts_observed_at, configAt: memberReads.configuration[f.id]?.collected_at ?? null }))} />}
        state={(
          // The "This cluster in:" strip is gone (UI review 2026-10-18, F-21): its links are the state pills themselves.
          <HeaderStateRow facts={clusterFacts}
            trailing={fallbackLinks.map((l) => <HeaderPillLink key={l.id} href={l.href}>{l.label}</HeaderPillLink>)} />
        )}
      >
        {/* 2 Member Cards Sub-row */}
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
                      {memberLabel(m, orderedMembers)}
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
                  {/* One Collect now per page (phase 1.3): the header's, with the M1 / M2 pills; the card no longer repeats it. */}
                  <Typography variant="caption" color="text.secondary" sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {m.model ?? "Unknown model"} · {m.software_version ?? "Unknown version"}
                  </Typography>
                </Box>
                {isPaloAlto && m.virtual_systems && (
                  <Typography variant="caption" color="text.secondary">
                    VSYS: {m.virtual_systems.split(/,\s*/).filter(Boolean).map((name, index) => (
                      <Box component="span" key={name}>
                        {index > 0 ? ", " : ""}
                        <Box component="span" sx={name === scopedName ? { fontWeight: 700, color: m3.onSurface } : undefined}>{name}</Box>
                      </Box>
                    ))}
                  </Typography>
                )}
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
        selectionKey={scopedName}
        onlyTabs={scopedName ? ["Interfaces", "Routing"] : undefined}
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
                      <TableCell sx={{ fontWeight: 600 }}>Last read</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {orderedMembers.map((m) => (
                      <TableRow key={m.device_id} hover>
                        <TableCell sx={{ fontWeight: 600 }}>{memberLabel(m, orderedMembers)}</TableCell>
                        <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{m.device_id}</TableCell>
                        <TableCell>
                          <RoleChip role={m.ha_role} dense />
                        </TableCell>
                        <TableCell>{m.model ?? "UNKNOWN"} · {m.software_version ?? "UNKNOWN"}</TableCell>
                        <TableCell>
                          <Stack direction="row" spacing={1} alignItems="center">
                            <StatusChip tone={m.enrollment_state === "ENROLLED" ? "ok" : "warn"} label={statusWord(m.enrollment_state)} dense />
                            <JobStatusIndicator state={m.latest_job_state} type={m.latest_job_type} terminalReason={m.latest_job_terminal_reason} />
                          </Stack>
                        </TableCell>
                        {/* One Collect now per page: the header carries it; this tab shows how fresh each member's read is. */}
                        <TableCell><Ts at={m.inventory_collected_at} relative seconds={false} /></TableCell>
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
          // Logs are stored per member; the cluster pane picks the member with the M1 / M2 pills (F-21).
          ...(firstMember && (firstMember.vendor_hint === "check_point" || firstMember.vendor_hint === "palo_alto") ? [{
            label: "Logs",
            panel: <ClusterLogsPanel members={orderedMembers} allMembers={orderedMembers} vendorHint={firstMember.vendor_hint} />,
          }] : []),
          {
            label: "Identity",
            panel: (
              <Stack spacing={1}>
                <IdentityReference value={clusterRef} />
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                  {plural(orderedMembers.length, "member")} · {clusterVendorLabel(firstMember?.vendor_hint)}
                  {virtualSystems.length > 0 ? ` · ${virtualSystems.length} ${isPaloAlto ? "VSYS" : "VSX"}: ${virtualSystems.join(", ")}` : ""}
                  {" "}· each value is read from that member itself; a value not read is UNKNOWN.
                </Typography>
                <DeviceIdentityTable devices={orderedMembers} ariaLabel="Member identity" />
              </Stack>
            ),
          },
          ...(firstMember && (firstMember.vendor_hint === "check_point" || firstMember.vendor_hint === "palo_alto")
            ? [{ label: "Failover", panel: <CpFailoverPanel memberDeviceId={firstMember.device_id} vendor={firstMember.vendor_hint} /> }] : []),
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
  const roleAllowed = useRoleAllows(PLANE_OPERATIONS);
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
          disabled={phase === "submitting" || !roleAllowed}
          inputProps={{ "aria-label": "Backup reason" }}
        />
        <M3Button deviceJob plane={PLANE_OPERATIONS}
          emphasis="tonal"
          onClick={handleSubmit}
          disabled={tooShort || phase === "submitting"}
        >
          Request backup
        </M3Button>
      </Stack>
      {!roleAllowed && (
        <Typography role="status" variant="body2" color="text.secondary">{ROLE_REFUSAL}</Typography>
      )}
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

/** The ASA backup is the configuration text, not an image archive (PO decision 4, council 2026-09-25). */
export const ASA_BACKUP_LABEL = "Configuration text (running + startup) — certificates, VPN images and profiles not included · secret-bearing";

/** How many backup attempts the Backup tab shows before "Show all". */
export const BACKUP_ATTEMPTS_SHOWN = 5;

/**
 * A device's Backup tab (UI review 2026-10-18, F-16 / P1-2B): the request control, then the stored archives under their
 * own title (sizes in MB, exact bytes on hover, `Checked (V1)`), then the attempts, newest five first with "Show all".
 */
export function BackupPanel({ deviceId, vendorHint, backupTarget }: { readonly deviceId: string; readonly vendorHint?: string | null;
  /** The device's backup-target switch as the device list reports it; `false` with no archive is one empty state (3.3). */
  readonly backupTarget?: boolean }) {
  const [attemptsVersion, setAttemptsVersion] = useState(0);
  const [retentionDays, setRetentionDays] = useState<number | null>(null);
  const fetcher = useFetchOnMount<{ backups: BackupArtefact[] }>(
    () => listDeviceBackups(deviceId),
    describeApiError,
  );
  useEffect(() => {
    let cancelled = false;
    getBackupPolicy().then((policy) => { if (!cancelled && Number.isFinite(policy.backup_retention_days)) setRetentionDays(policy.backup_retention_days); })
      .catch(() => { /* the title then omits the retention; nothing is guessed */ });
    return () => { cancelled = true; };
  }, []);

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

  // Not a backup target and nothing stored (post-release review 2026-10-18, 3.3): one state that says why the tab is
  // empty, instead of "No backups" under "Stored archives (0)" and "No backup attempts" under it. Attempts, if any
  // exist from before the switch was turned off, still list below it.
  if (backups.length === 0 && backupTarget === false) {
    return (
      <Stack spacing={2.5}>
        <RequestBackupControl deviceId={deviceId} onAdmitted={() => { fetcher.refresh(); setAttemptsVersion((v) => v + 1); }} />
        <EmptyPanel title="Not a backup target"
          body="This device is not switched on as a backup target, so neXus keeps no archive for it and schedules no backup. The switch is on the Backups screen; a switched-on device is backed up at the next 02:00 GMT+3 run." />
        <BackupAttempts key={deviceId} deviceId={deviceId} version={attemptsVersion} collapseTo={BACKUP_ATTEMPTS_SHOWN} quietWhenEmpty />
      </Stack>
    );
  }

  return (
    <Stack spacing={2.5}>
      <RequestBackupControl deviceId={deviceId} onAdmitted={() => { fetcher.refresh(); setAttemptsVersion((v) => v + 1); }} />
      <Box>
        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          Stored archives ({backups.length}){retentionDays !== null ? ` · retention ${retentionDays} d` : ""}
        </Typography>
        {vendorHint === "cisco_asa" && (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }}>{ASA_BACKUP_LABEL}</Typography>
        )}
        {backups.length === 0 ? (
          <EmptyPanel
            title="No backups"
            body="No backup has been retained for this device."
          />
        ) : (
          <>
            <Table size="small" aria-label="Stored archives">
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
                    {/* The one timestamp component (R15): `2026-10-18 02:04 GMT+3 · 14 h ago`, the UTC value on hover. */}
                    <TableCell><Stamp at={b.collected_at} /></TableCell>
                    <TableCell title={bytesTitle(b.size_bytes)} sx={{ whiteSpace: "nowrap" }}>{formatBytes(b.size_bytes)}</TableCell>
                    <TableCell>{b.digest_prefix}</TableCell>
                    <TableCell><ValidationChip level={b.validation_level || "UNKNOWN"} withLevel /></TableCell>
                    <TableCell><DeviationChip state={deviationState(b.deviation_state)} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              Backup archives are compared by digest only: unchanged means identical bytes and changed means different bytes, not archive contents.
            </Typography>
          </>
        )}
      </Box>
      <Box>
        <Typography variant="subtitle2" sx={{ mb: 1 }}>Backup attempts</Typography>
        <BackupAttempts key={deviceId} deviceId={deviceId} version={attemptsVersion} collapseTo={BACKUP_ATTEMPTS_SHOWN} />
      </Box>
    </Stack>
  );
}
