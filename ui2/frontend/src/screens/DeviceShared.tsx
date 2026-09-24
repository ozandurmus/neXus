import { useState, type ReactNode, type ChangeEvent } from "react";
import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { MONO, m3 } from "../theme/m3Theme";
import { RoleChip, Ts, Unknown } from "../shell/States";
import { StatusChip } from "../shell/M3Widgets";
import { deviceNameLabel } from "../shell/deviceCopy";
import type { DeviceSummary } from "../auth/adminApi";

/**
 * Pieces the Devices and Configuration screens share (UI_VISUAL_REVIEW_2026_09_23_FABLE.md §3, §6): the labelled
 * filter row, the fixed member order, the per-member identity table, the cluster context strip and the CSV
 * download. One component per fact, so the two screens can not drift apart again.
 */

// ---------------------------------------------------------------------------------------------------------------
// Labelled filter row
// ---------------------------------------------------------------------------------------------------------------

export interface FilterOption<T extends string> {
  readonly value: T;
  readonly label: string;
  /** Written after the label as "Label · n"; a string lets a chip carry "40 enrolled · 39 active". */
  readonly count?: number | string;
}

/** The filter dropdowns side by side on one row (PO, 2026-09-24: chips took half the list column). */
export function FilterBar({ children }: { readonly children: ReactNode }) {
  return <Box sx={{ display: "flex", gap: 0.75, "& > *": { flex: "1 1 0", minWidth: 0 } }}>{children}</Box>;
}

/**
 * One filter dimension as a small labelled dropdown; each option reads "Label · n". A dimension narrowed away from
 * its first option ("All") is outlined in the accent colour, so an active filter is visible at a glance.
 */
export function FilterRow<T extends string>({ dimension, options, value, onChange }: {
  readonly dimension: string;
  readonly options: readonly FilterOption<T>[];
  readonly value: T;
  readonly onChange: (value: T) => void;
}) {
  const active = options.length > 0 && value !== options[0].value;
  return (
    <Box component="label" sx={{ display: "flex", flexDirection: "column", gap: 0.25, minWidth: 0 }}>
      <Typography component="span" sx={{ fontSize: 11, fontWeight: 600, color: m3.onSurfaceVar, pl: 0.25 }}>
        {dimension}
      </Typography>
      <Box component="select" value={value} aria-label={dimension}
        onChange={(e: ChangeEvent<HTMLSelectElement>) => onChange(e.target.value as T)}
        sx={{ width: "100%", minWidth: 0, height: 28, fontSize: 12, px: 0.75, borderRadius: "6px", cursor: "pointer",
              textOverflow: "ellipsis", fontWeight: active ? 600 : 500,
              color: active ? m3.onPrimaryContainer : m3.onSurface,
              bgcolor: active ? m3.primaryContainer : m3.scLowest,
              border: `1px solid ${active ? m3.primary : m3.outlineVar}` }}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.count === undefined ? o.label : `${o.label} · ${o.count}`}</option>
        ))}
      </Box>
    </Box>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Member order
// ---------------------------------------------------------------------------------------------------------------

/**
 * Members always in the same order, M1 then M2 (review §3: the order flipped between clusters and screens):
 * by display name with a numeric-aware compare, so a "-01"/"-02" suffix sorts naturally; device id breaks ties.
 * Presentation only -- never a join key or an identity rule; the role chip says which member is active.
 */
export function orderMembers<T extends { readonly device_id: string; readonly hostname?: string | null }>(members: readonly T[]): T[] {
  return [...members].sort((a, b) => {
    const byName = deviceNameLabel(a.hostname ?? null).localeCompare(deviceNameLabel(b.hostname ?? null), undefined, { numeric: true, sensitivity: "base" });
    return byName !== 0 ? byName : a.device_id.localeCompare(b.device_id);
  });
}

// ---------------------------------------------------------------------------------------------------------------
// Content versions
// ---------------------------------------------------------------------------------------------------------------

const CONTENT_VERSION_LABELS: ReadonlyArray<readonly [string, string]> = [
  ["app", "Applications"],
  ["threat", "Threats"],
  ["av", "Antivirus"],
  ["wildfire", "WildFire"],
  ["url", "URL filtering"],
];

/** A value made only of zeros and separators ("0", "0000.00.00.000"): the device's own answer, shown as reported. */
export function isReportedZero(value: string): boolean {
  return /^[0.\-\s]+$/.test(value) && value.includes("0");
}

export interface ContentVersionPart { readonly key: string; readonly label: string; readonly value: string; readonly reportedZero: boolean }

/**
 * The Palo Alto content versions the device reported. How the data arrives (PaloAltoSystemInfoParser, V46):
 * a key is present only when `show system info` carried that tag, with the device's own value; the whole map
 * is null when no platform-facts read has landed. So a present "0" is the device's answer ("0 (as reported)"),
 * and a missing key or a null map is UNKNOWN -- never 0.
 */
export function contentVersionParts(versions: Readonly<Record<string, string>> | null | undefined): ContentVersionPart[] | null {
  if (!versions) return null;
  const parts = CONTENT_VERSION_LABELS.filter(([key]) => versions[key] !== undefined && versions[key] !== null && versions[key] !== "")
    .map(([key, label]) => ({ key, label, value: versions[key], reportedZero: isReportedZero(versions[key]) }));
  return parts.length > 0 ? parts : null;
}

/** Plain text of the content versions (for comparisons and exports); zero values carry "(as reported)". */
export function contentVersionText(versions: Readonly<Record<string, string>> | null | undefined): string | null {
  const parts = contentVersionParts(versions);
  return parts ? parts.map((p) => `${p.label} ${p.value}${p.reportedZero ? " (as reported)" : ""}`).join(" · ") : null;
}

export function ContentVersions({ versions }: { readonly versions: Readonly<Record<string, string>> | null | undefined }) {
  const parts = contentVersionParts(versions);
  if (!parts) return <Unknown reason="Not collected: no content version has been read from this device yet (run Inventory collect)." />;
  const missing = CONTENT_VERSION_LABELS.filter(([key]) => !parts.some((p) => p.key === key)).map(([, label]) => label);
  return (
    <Box component="span" sx={{ display: "inline-flex", flexWrap: "wrap", columnGap: 1, rowGap: 0.25 }}>
      {parts.map((p) => (
        <Box component="span" key={p.key} sx={{ whiteSpace: "nowrap" }}>
          <Box component="span" sx={{ color: m3.onSurfaceVar }}>{p.label} </Box>
          <Box component="span" sx={{ fontFamily: MONO }}>{p.value}</Box>
          {p.reportedZero && (
            <Tooltip title="The device itself reported this value; it was read, not assumed.">
              <Box component="span" sx={{ color: m3.neutralInk, ml: 0.5 }}>(as reported)</Box>
            </Tooltip>
          )}
        </Box>
      ))}
      {missing.length > 0 && (
        <Tooltip title={`${missing.join(", ")}: the device's answer did not carry this value, so it is UNKNOWN.`}>
          <Box component="span" sx={{ color: m3.neutralInk, whiteSpace: "nowrap" }}>{missing.length} not reported</Box>
        </Tooltip>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Virtual systems cell
// ---------------------------------------------------------------------------------------------------------------

export function vsListOf(device: { readonly virtual_systems?: string | null }): string[] {
  return device.virtual_systems ? device.virtual_systems.split(/,\s*/).map((v) => v.trim()).filter(Boolean) : [];
}

/**
 * One member's virtual systems, collapsed: "8 · same as cluster" with a disclosure for the names; when the member
 * differs from the cluster's union, the difference is written inline. The full list stays in the platform
 * identity line above the table.
 */
export function VirtualSystemsCell({ vs, clusterVs }: { readonly vs: readonly string[]; readonly clusterVs?: readonly string[] }) {
  const [open, setOpen] = useState(false);
  const inCluster = clusterVs !== undefined && clusterVs.length > 0;
  const missing = inCluster ? clusterVs.filter((x) => !vs.includes(x)) : [];
  const extra = inCluster ? vs.filter((x) => !clusterVs.includes(x)) : [];
  const same = inCluster && missing.length === 0 && extra.length === 0;
  if (vs.length === 0 && !inCluster) return <Box component="span" sx={{ color: m3.onSurfaceVar }}>none</Box>;
  const summary = vs.length === 0 ? "none" : inCluster ? (same ? `${vs.length} · same as cluster` : `${vs.length} · differs`) : `${vs.length}`;
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.25, minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
        {vs.length > 0 ? (
          <Box component="button" type="button" aria-expanded={open} onClick={() => setOpen(!open)}
            sx={{ border: "none", bgcolor: "transparent", p: 0, font: "inherit", fontSize: 12.5, color: m3.onSurface, cursor: "pointer",
                  display: "inline-flex", alignItems: "center", gap: 0.5, whiteSpace: "nowrap" }}>
            <Box component="span" sx={{ color: m3.onSurfaceVar, width: 10 }}>{open ? "▾" : "▸"}</Box>
            {summary}
          </Box>
        ) : (
          <Box component="span">{summary}</Box>
        )}
        {inCluster && !same && <StatusChip tone="bad" label="DIFF" dense />}
      </Box>
      {inCluster && !same && (
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
          {missing.length > 0 && <>missing <Box component="span" sx={{ fontFamily: MONO }}>{missing.join(", ")}</Box></>}
          {missing.length > 0 && extra.length > 0 && " · "}
          {extra.length > 0 && <>only here <Box component="span" sx={{ fontFamily: MONO }}>{extra.join(", ")}</Box></>}
        </Typography>
      )}
      {open && <Typography variant="caption" sx={{ fontFamily: MONO, wordBreak: "break-word" }}>{vs.join(", ")}</Typography>}
    </Box>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Per-member identity table
// ---------------------------------------------------------------------------------------------------------------

const HEAD = { fontSize: 11, letterSpacing: "0.04em", whiteSpace: "nowrap" } as const;

/**
 * The per-member identity table (review §3 Devices › Identity & provenance, and Configuration's member table):
 * serial, model, software version, hotfix or content versions, uptime, HA role, management address, virtual
 * systems, platform-facts observed time, last inventory read and evidence source -- one component rendered by
 * both screens. A value the product has not read is UNKNOWN with its reason; a software version or hotfix
 * level that differs between members is marked DIFF (a compliance finding, PLATFORM_IDENTITY_FACTS_CONTRACT §2).
 */
export function DeviceIdentityTable({ devices, ariaLabel = "Member identity" }: {
  readonly devices: readonly DeviceSummary[];
  readonly ariaLabel?: string;
}) {
  const ordered = orderMembers(devices);
  const isPaloAlto = ordered.length > 0 && ordered.every((d) => d.vendor_hint === "palo_alto");
  const isMixed = !isPaloAlto && ordered.some((d) => d.vendor_hint === "palo_alto");
  const clusterVs = ordered.length > 1 ? Array.from(new Set(ordered.flatMap(vsListOf))).sort() : undefined;
  const distinct = (pick: (d: DeviceSummary) => string | null | undefined) =>
    ordered.length > 1 && new Set(ordered.map(pick).filter((v): v is string => Boolean(v))).size > 1;
  const versionDiff = distinct((d) => d.software_version);
  const levelDiff = distinct((d) => (d.vendor_hint === "palo_alto" ? contentVersionText(d.content_versions) : d.hotfix_level));
  const levelHeader = isPaloAlto ? "CONTENT VERSIONS" : isMixed ? "HOTFIX / CONTENT" : "HOTFIX / JUMBO";
  const vsHeader = isPaloAlto ? "VSYS" : "VSX";
  const gated = "Not collected: needs its own gated read (run Inventory collect).";
  const headers = ["MEMBER", "SERIAL", "MODEL", "SOFTWARE VERSION", levelHeader, "UPTIME", "HA ROLE", "MANAGEMENT ADDRESS",
    vsHeader, "ENROLLMENT", "PLATFORM FACTS OBSERVED", "LAST INVENTORY READ", "EVIDENCE SOURCE"];
  return (
    <TableContainer sx={{ overflowX: "auto", "& th:first-of-type, & td:first-of-type": { position: "sticky", left: 0, zIndex: 1, bgcolor: m3.scLowest, boxShadow: `1px 0 0 ${m3.outlineVar}` } }}>
      <Table size="small" aria-label={ariaLabel}>
        <TableHead>
          <TableRow>{headers.map((h) => <TableCell key={h} sx={HEAD}>{h}</TableCell>)}</TableRow>
        </TableHead>
        <TableBody>
          {ordered.map((m) => (
            <TableRow key={m.device_id} hover sx={{ verticalAlign: "top" }}>
              <TableCell sx={{ fontWeight: 600, fontSize: 12.5, whiteSpace: "nowrap" }}>{deviceNameLabel(m.hostname)}</TableCell>
              <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{m.serial_number ?? <Unknown reason={gated} />}</TableCell>
              <TableCell>{m.model || m.platform_family || <Unknown reason="Not read yet." />}</TableCell>
              <TableCell sx={{ fontFamily: MONO, fontSize: 12, bgcolor: versionDiff ? m3.errorContainer : undefined }}>
                <Box sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
                  {m.software_version ?? <Unknown reason="Not read at first contact." />}
                  {versionDiff && <StatusChip tone="bad" label="DIFF" dense />}
                </Box>
              </TableCell>
              <TableCell sx={{ fontSize: 12, bgcolor: levelDiff ? m3.errorContainer : undefined }}>
                <Box sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
                  {m.vendor_hint === "palo_alto"
                    ? <ContentVersions versions={m.content_versions} />
                    : <Box component="span" sx={{ fontFamily: MONO }}>{m.hotfix_level ?? <Unknown reason={gated} />}</Box>}
                  {levelDiff && <StatusChip tone="bad" label="DIFF" dense />}
                </Box>
              </TableCell>
              <TableCell sx={{ whiteSpace: "nowrap" }}>{m.uptime_text ?? <Unknown reason={gated} />}</TableCell>
              <TableCell><RoleChip role={m.ha_role} dense /></TableCell>
              <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{m.management_ip ?? <Unknown reason="No management address recorded." />}</TableCell>
              <TableCell><VirtualSystemsCell vs={vsListOf(m)} clusterVs={clusterVs} /></TableCell>
              <TableCell sx={{ fontSize: 12 }}>{m.enrollment_state || <Unknown />}</TableCell>
              <TableCell><Ts at={m.platform_facts_observed_at} /></TableCell>
              <TableCell><Ts at={m.inventory_collected_at} /></TableCell>
              <TableCell>
                {m.platform_facts_source
                  ? <Box component="span" title="The read that produced the platform facts" sx={{ fontFamily: MONO, fontSize: 11.5 }}>{m.platform_facts_source}</Box>
                  : <Unknown reason="No platform-facts read has been recorded for this member." />}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Cluster context strip
// ---------------------------------------------------------------------------------------------------------------

export type ContextScreen = "inventory" | "configuration" | "backups" | "readiness";

/** The links on a cluster header that carry the selection to the other screens (review §6, incident). */
export function clusterContextLinks(clusterRef: string): ReadonlyArray<{ readonly id: ContextScreen; readonly label: string; readonly href: string }> {
  const r = encodeURIComponent(clusterRef);
  return [
    { id: "inventory", label: "Inventory", href: `?screen=inventory&cluster_ref=${r}` },
    { id: "configuration", label: "Configuration", href: `?screen=configuration&cluster_ref=${r}` },
    { id: "backups", label: "Backups", href: `?screen=backups&q=${r}` },
    { id: "readiness", label: "Readiness", href: `?screen=operations&cluster_ref=${r}` },
  ];
}

export function ClusterContextStrip({ clusterRef, current }: { readonly clusterRef: string; readonly current: ContextScreen }) {
  const links = clusterContextLinks(clusterRef);
  return (
    <Box component="nav" aria-label="This cluster on other screens" sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap", fontSize: 12.5 }}>
      <Typography component="span" sx={{ fontSize: 12, color: m3.onSurfaceVar }}>This cluster in:</Typography>
      {links.map((l, i) => (
        <Box component="span" key={l.id} sx={{ display: "inline-flex", alignItems: "center", gap: 0.75 }}>
          <Link href={l.href} underline="hover" aria-current={l.id === current ? "page" : undefined}
            sx={{ fontSize: 12.5, fontWeight: l.id === current ? 600 : 500, color: l.id === current ? m3.onSurface : m3.primary }}>
            {l.label}
          </Link>
          {i < links.length - 1 && <Box component="span" sx={{ color: m3.outline }}>·</Box>}
        </Box>
      ))}
    </Box>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------------------------------------------------

/** One CSV cell: quoted when needed; a leading =, +, @ (or - not followed by a digit) is neutralised for spreadsheets. */
export function csvCell(value: string | number | null | undefined): string {
  let v = value === null || value === undefined ? "" : String(value);
  if (/^[=+@]/.test(v) || /^-[^\d]/.test(v)) v = `'${v}`;
  return /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

export function toCsv(rows: ReadonlyArray<ReadonlyArray<string | number | null | undefined>>): string {
  return rows.map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

/** Hand a text file built in the browser to the viewer (no request: the content is what is already on screen). */
export function downloadText(fileName: string, text: string, type = "text/csv;charset=utf-8"): void {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
