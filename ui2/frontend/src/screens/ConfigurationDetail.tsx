import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import InputBase from "@mui/material/InputBase";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Tabs, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import {
  getDeviceConfiguration,
  getDeviceConfigurationText,
  type DeviceConfiguration,
  type DeviceSummary,
} from "../auth/adminApi";
import { InventoryEntityHeader, deriveClusterTitle } from "./InventoryPanels";
import { DeviceConfigurationPanels } from "./ConfigurationPanels";
import {
  filterProjection,
  projectCheckPoint,
  projectCluster,
  projectPaloAlto,
  type ClusterProjection,
  type Origin,
  type Projection,
  type Section,
} from "./configurationProjection";

function vendorLabel(vendorHint: string | null | undefined): string {
  if (vendorHint === "check_point") return "Check Point";
  if (vendorHint === "palo_alto") return "Palo Alto Networks";
  return vendorHint ?? "Unknown vendor";
}

function originTone(origin: Origin): "neutral" | "ok" | "mem" | "warn" | "attn" | "bad" {
  switch (origin) {
    case "MEMBER":
      return "mem";
    case "OVERRIDE":
      return "warn";
    case "PAN":
      return "ok";
    default:
      return "neutral";
  }
}

interface Loaded {
  readonly configuration: DeviceConfiguration | null;
  readonly projection: Projection | null;
  readonly error: string | null;
}

/** One device's configuration read and projected; null projection when the device has no sanitized text. */
function useProjection(device: DeviceSummary | null): Loaded & { loading: boolean } {
  const [state, setState] = useState<Loaded & { loading: boolean }>({ configuration: null, projection: null, error: null, loading: false });
  useEffect(() => {
    if (!device) return;
    let cancelled = false;
    setState({ configuration: null, projection: null, error: null, loading: true });
    (async () => {
      try {
        const configuration = await getDeviceConfiguration(device.device_id);
        let projection: Projection | null = null;
        if (configuration.sanitized_text_available) {
          const text = await getDeviceConfigurationText(device.device_id);
          projection = configuration.vendor === "palo_alto" || device.vendor_hint === "palo_alto"
            ? projectPaloAlto(text, configuration.overrides.map((o) => o.element_path))
            : projectCheckPoint(text);
        }
        if (!cancelled) setState({ configuration, projection, error: null, loading: false });
      } catch (error) {
        if (!cancelled) setState({ configuration: null, projection: null, error: error instanceof Error ? error.message : "The configuration could not be read.", loading: false });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [device?.device_id]);
  return state;
}

function FilterBox({ value, onChange }: { readonly value: string; readonly onChange: (v: string) => void }) {
  return (
    <Box sx={{ height: 40, display: "flex", alignItems: "center", gap: 1, px: 1.5, borderRadius: "20px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, minWidth: 280 }}>
      <Icon name="search" size={18} />
      <InputBase placeholder="Filter setting or value…" value={value} onChange={(e) => onChange(e.target.value)} sx={{ flex: 1, fontSize: 13, color: "inherit" }} />
    </Box>
  );
}

function SnapshotTiles({ projection }: { readonly projection: Projection }) {
  if (projection.snapshot.length === 0) return null;
  return (
    <Card sx={{ p: 2, borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Basic configuration</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Operator snapshot</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">Selected current values</Typography>
      </Box>
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))" }}>
        {projection.snapshot.map((tile) => (
          <Box key={`${tile.group}-${tile.label}`} sx={{ p: 1.5, borderRadius: "12px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}>
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 0.5 }}>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em" }}>{tile.group}</Typography>
              <StatusChip tone={originTone(tile.origin)} label={tile.origin} dense />
            </Box>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{tile.label}</Typography>
            <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: 12.5, wordBreak: "break-all" }}>{tile.value}</Typography>
          </Box>
        ))}
      </Box>
    </Card>
  );
}

function SectionCard({ section }: { readonly section: Section }) {
  return (
    <Card sx={{ borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, display: "flex", flexDirection: "column", minHeight: 0 }}>
      <Box sx={{ px: 2, pt: 1.5, pb: 1, display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Current configuration</Typography>
          <Typography variant="subtitle1" sx={{ fontWeight: 600, mt: -0.5 }}>{section.label}</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">{section.rows.length} visible</Typography>
      </Box>
      <TableContainer sx={{ maxHeight: 420 }}>
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontSize: 11, letterSpacing: "0.06em" }}>SETTING</TableCell>
              <TableCell sx={{ fontSize: 11, letterSpacing: "0.06em" }}>CURRENT VALUE</TableCell>
              <TableCell sx={{ fontSize: 11, letterSpacing: "0.06em" }}>ORIGIN</TableCell>
              <TableCell sx={{ fontSize: 11, letterSpacing: "0.06em" }}>CONTEXT</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {section.rows.map((row) => (
              <TableRow key={row.key} hover>
                <TableCell sx={{ fontWeight: 600, fontSize: 12.5 }}>{row.setting}</TableCell>
                <TableCell sx={{ fontFamily: "monospace", fontSize: 12, wordBreak: "break-all" }}>{row.value}</TableCell>
                <TableCell><StatusChip tone={originTone(row.origin)} label={row.origin} dense /></TableCell>
                <TableCell sx={{ color: m3.onSurfaceVar }}>{row.context ?? "—"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Card>
  );
}

function vsListOf(device: DeviceSummary): string[] {
  return device.virtual_systems ? device.virtual_systems.split(/,\s*/).filter(Boolean) : [];
}

function haLabel(role: string | null | undefined): string {
  if (!role) return "UNKNOWN";
  const r = role.toLowerCase();
  if (r === "active" || r === "master") return "ACTIVE";
  if (r === "passive" || r === "standby" || r === "backup") return "PASSIVE";
  return role.toUpperCase();
}

function haTone(role: string | null | undefined): "neutral" | "ok" | "mem" | "warn" {
  const label = haLabel(role);
  if (label === "ACTIVE") return "ok";
  if (label === "PASSIVE") return "mem";
  if (label === "UNKNOWN") return "warn";
  return "neutral";
}

interface IdentityTile {
  readonly label: string;
  readonly value: string | null;
  /** Shown when the value is null: why it is not there. */
  readonly absent?: string;
  readonly mono?: boolean;
}

/**
 * Platform identity: what a security administrator checks first and what a
 * compliance review asks for -- vendor, model, software version, hotfix level,
 * serial number, HA role, virtual systems. A value the product has not read
 * says so ("not collected"); nothing is inferred.
 */
const CONTENT_VERSION_LABELS: ReadonlyArray<readonly [string, string]> = [
  ["app", "Applications"],
  ["threat", "Threats"],
  ["av", "Antivirus"],
  ["wildfire", "WildFire"],
  ["url", "URL filtering"],
];

export function contentVersionText(versions: Readonly<Record<string, string>> | null | undefined): string | null {
  if (!versions) return null;
  const parts = CONTENT_VERSION_LABELS.filter(([key]) => versions[key]).map(([key, label]) => `${label} ${versions[key]}`);
  return parts.length > 0 ? parts.join(" · ") : null;
}

function identityTiles(device: DeviceSummary, collectedAt: string | null): IdentityTile[] {
  const vs = vsListOf(device);
  const isPaloAlto = device.vendor_hint === "palo_alto";
  const needsGate = "not collected -- needs its own gated read";
  const observed = device.platform_facts_observed_at ? new Date(device.platform_facts_observed_at).toLocaleString() : null;
  return [
    { label: "Vendor", value: device.platform_family ? `${vendorLabel(device.vendor_hint)} · ${device.platform_family}` : vendorLabel(device.vendor_hint) },
    { label: "Model", value: device.model, absent: "not read at first contact" },
    { label: "Serial number", value: device.serial_number ?? null, absent: isPaloAlto ? "not read yet -- run Inventory collect" : needsGate, mono: true },
    { label: "Software version", value: device.software_version, absent: "not read at first contact" },
    isPaloAlto
      ? { label: "Content versions", value: contentVersionText(device.content_versions), absent: "not read yet -- run Inventory collect" }
      : { label: "Hotfix / Jumbo take", value: device.hotfix_level ?? null, absent: needsGate },
    { label: "Uptime", value: device.uptime_text ?? null, absent: isPaloAlto ? "not read yet -- run Inventory collect" : needsGate },
    { label: "HA role", value: haLabel(device.ha_role) },
    { label: isPaloAlto ? "Virtual systems (VSYS)" : "Virtual systems (VSX)", value: vs.length > 0 ? `${vs.length} · ${vs.join(", ")}` : "none" },
    { label: "Management address", value: device.management_ip ?? null, absent: "not recorded", mono: true },
    { label: "Enrollment", value: device.enrollment_state },
    { label: "Platform facts read", value: observed, absent: "never" },
    { label: "Last configuration read", value: collectedAt, absent: "never" },
  ];
}

function IdentityCard({ title, tiles }: { readonly title: string; readonly tiles: readonly IdentityTile[] }) {
  return (
    <Card sx={{ p: 2, borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Platform identity</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>{title}</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">What an audit asks for first</Typography>
      </Box>
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))" }}>
        {tiles.map((tile) => (
          <Box key={tile.label} sx={{ p: 1.5, borderRadius: "12px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em", display: "block", mb: 0.5 }}>{tile.label.toUpperCase()}</Typography>
            {tile.value !== null ? (
              <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: tile.mono ? "monospace" : undefined, wordBreak: "break-word" }}>{tile.value}</Typography>
            ) : (
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
                <StatusChip tone="warn" label="UNKNOWN" dense />
                <Typography variant="caption" color="text.secondary">{tile.absent}</Typography>
              </Box>
            )}
          </Box>
        ))}
      </Box>
    </Card>
  );
}

/** A cluster's members as one row each -- model, version, HA role, address, virtual systems -- so the two sides are compared at a glance. */
function ClusterMembersCard({ members }: { readonly members: readonly DeviceSummary[] }) {
  const isPaloAlto = members[0]?.vendor_hint === "palo_alto";
  const vsUnion = Array.from(new Set(members.flatMap(vsListOf))).sort();
  return (
    <Card sx={{ borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
      <Box sx={{ px: 2, pt: 1.5, pb: 1, display: "flex", justifyContent: "space-between", alignItems: "baseline", flexWrap: "wrap", gap: 1 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Platform identity</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Members</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">
          {vsUnion.length === 0
            ? `No ${isPaloAlto ? "virtual systems (VSYS)" : "virtual systems (VSX)"} recorded`
            : `${vsUnion.length} ${isPaloAlto ? "virtual system(s) (VSYS)" : "virtual system(s) (VSX)"}: ${vsUnion.join(", ")}`}
        </Typography>
      </Box>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              {["MEMBER", "MODEL", "SERIAL", "SOFTWARE VERSION", isPaloAlto ? "CONTENT VERSIONS" : "HOTFIX / JUMBO", "UPTIME", "HA ROLE", "MANAGEMENT ADDRESS", isPaloAlto ? "VSYS" : "VSX", "ENROLLMENT"].map((h) => (
                <TableCell key={h} sx={{ fontSize: 11, letterSpacing: "0.06em" }}>{h}</TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {members.map((m) => {
              const vs = vsListOf(m);
              const unknown = <StatusChip tone="warn" label="UNKNOWN" dense />;
              // A version or hotfix level that differs between members is a compliance finding in its own right.
              const distinct = (pick: (d: DeviceSummary) => string | null | undefined) =>
                new Set(members.map(pick).filter((v): v is string => Boolean(v))).size > 1;
              const versionDiff = distinct((d) => d.software_version);
              const levelDiff = distinct((d) => (isPaloAlto ? contentVersionText(d.content_versions) : d.hotfix_level));
              return (
                <TableRow key={m.device_id} hover>
                  <TableCell sx={{ fontWeight: 600, fontSize: 12.5 }}>{m.hostname ?? m.device_id}</TableCell>
                  <TableCell>{m.model ?? unknown}</TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{m.serial_number ?? unknown}</TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 12, bgcolor: versionDiff ? m3.errorContainer : undefined }}>
                    {m.software_version ?? unknown}{versionDiff && <StatusChip tone="bad" label="DIFF" dense />}
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 12, bgcolor: levelDiff ? m3.errorContainer : undefined }}>
                    {(isPaloAlto ? contentVersionText(m.content_versions) : m.hotfix_level) ?? unknown}{levelDiff && <StatusChip tone="bad" label="DIFF" dense />}
                  </TableCell>
                  <TableCell>{m.uptime_text ?? unknown}</TableCell>
                  <TableCell><StatusChip tone={haTone(m.ha_role)} label={haLabel(m.ha_role)} dense /></TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{m.management_ip ?? "—"}</TableCell>
                  <TableCell>{vs.length > 0 ? `${vs.length} · ${vs.join(", ")}` : "none"}</TableCell>
                  <TableCell>{m.enrollment_state}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Card>
  );
}

function headerChips(device: DeviceSummary, extra: React.ReactNode = null) {
  return (
    <>
      <StatusChip tone="neutral" label={vendorLabel(device.vendor_hint)} dense />
      {device.model && <StatusChip tone="neutral" label={device.model} dense />}
      {device.software_version && <StatusChip tone="neutral" label={device.software_version} dense />}
      {device.management_ip && <StatusChip tone="neutral" label={device.management_ip} dense />}
      {device.ha_role && <StatusChip tone="mem" label={device.ha_role.toUpperCase()} dense />}
      {extra}
    </>
  );
}

/** One device: header, summary bar, operator snapshot, section tables; the old detail (changes, overrides, native text) stays under Details. */
export function DeviceConfigurationDetail({ device }: { readonly device: DeviceSummary }) {
  const { configuration, projection, error, loading } = useProjection(device);
  const [query, setQuery] = useState("");
  useEffect(() => setQuery(""), [device.device_id]);
  const sections = useMemo(() => (projection ? filterProjection(projection.sections, query) : []), [projection, query]);
  const collected = configuration?.collected_at ? new Date(configuration.collected_at).toLocaleString() : null;

  const configurationTab = (
    <Stack spacing={2}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Current actual state</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Device configuration</Typography>
        </Box>
        <FilterBox value={query} onChange={setQuery} />
      </Box>
      <IdentityCard title={device.hostname ?? device.device_id} tiles={identityTiles(device, collected)} />
      {error && <EmptyPanel title="Configuration unavailable" body={error} />}
      {!error && loading && <EmptyPanel title="Configuration" body="Reading the device's configuration…" />}
      {!error && !loading && !projection && (
        <EmptyPanel title="No configuration read yet" body="Collect the device's configuration first (Collect All, or Collect now on the Details tab)." />
      )}
      {projection && (
        <>
          <Card sx={{ px: 2, py: 1.25, borderRadius: "12px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip tone="ok" label="CURRENT ACTUAL" dense />
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{projection.settingCount} projected settings</Typography>
            <Typography variant="caption" color="text.secondary">Source plane: {projection.sourcePlane}</Typography>
            {collected && <Typography variant="caption" color="text.secondary">Collected {collected}</Typography>}
            <Box sx={{ flexGrow: 1 }} />
            <Typography variant="caption" color="text.secondary">
              {projection.withheldCount} secret-bearing setting{projection.withheldCount === 1 ? "" : "s"} withheld
            </Typography>
          </Card>
          <SnapshotTiles projection={projection} />
          {sections.length === 0 ? (
            <EmptyPanel title="No setting matches" body={`Nothing in ${projection.settingCount} settings matches "${query}".`} />
          ) : (
            <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(460px, 1fr))" }}>
              {sections.map((section) => <SectionCard key={section.label} section={section} />)}
            </Box>
          )}
        </>
      )}
    </Stack>
  );

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={device.vendor_hint}
        model={device.model}
        titlePrefix={`Configuration · ${vendorLabel(device.vendor_hint)}`}
        title={device.hostname ?? device.device_id}
        reference={device.device_id}
        referenceTitle="Device identifier"
        chips={headerChips(device, <StatusChip tone="ok" label="Current" dense />)}
      />
      <M3Tabs
        ariaLabel="Configuration sections"
        tabs={[
          { label: "Configuration", panel: configurationTab },
          { label: "Details", panel: <DeviceConfigurationPanels deviceId={device.device_id} hostname={device.hostname} vendorHint={device.vendor_hint} /> },
        ]}
      />
    </Stack>
  );
}

/** A cluster: every member's configuration side by side; a real difference is a DIFF row, a member-specific one is marked MEMBER. */
export function ClusterConfigurationDetail({ clusterRef, members }: { readonly clusterRef: string; readonly members: readonly DeviceSummary[] }) {
  const [state, setState] = useState<{ cluster: ClusterProjection | null; missing: string[]; error: string | null; loading: boolean }>({ cluster: null, missing: [], error: null, loading: true });
  const [query, setQuery] = useState("");
  const [diffOnly, setDiffOnly] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setState({ cluster: null, missing: [], error: null, loading: true });
    setQuery("");
    (async () => {
      const loaded: Array<{ id: string; projection: Projection }> = [];
      const missing: string[] = [];
      for (const member of members) {
        try {
          const configuration = await getDeviceConfiguration(member.device_id);
          if (!configuration.sanitized_text_available) {
            missing.push(member.hostname ?? member.device_id);
            continue;
          }
          const text = await getDeviceConfigurationText(member.device_id);
          loaded.push({
            id: member.device_id,
            projection: member.vendor_hint === "palo_alto" ? projectPaloAlto(text, configuration.overrides.map((o) => o.element_path)) : projectCheckPoint(text),
          });
        } catch {
          missing.push(member.hostname ?? member.device_id);
        }
      }
      if (!cancelled) setState({ cluster: loaded.length > 0 ? projectCluster(loaded) : null, missing, error: null, loading: false });
    })();
    return () => {
      cancelled = true;
    };
  }, [clusterRef, members.map((m) => m.device_id).join(",")]);

  const nameOf = (id: string) => members.find((m) => m.device_id === id)?.hostname ?? id;
  const first = members[0];
  const cluster = state.cluster;
  const visibleSections = useMemo(() => {
    if (!cluster) return [];
    const q = query.trim().toLowerCase();
    return cluster.sections
      .map((s) => ({
        label: s.label,
        rows: s.rows.filter((r) => (!diffOnly || r.diff) && (!q || r.setting.toLowerCase().includes(q) || s.label.toLowerCase().includes(q)
          || Object.values(r.values).some((v) => v.toLowerCase().includes(q)))),
      }))
      .filter((s) => s.rows.length > 0);
  }, [cluster, query, diffOnly]);

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={first?.vendor_hint ?? "check_point"}
        model={first?.model}
        titlePrefix={`Configuration · ${vendorLabel(first?.vendor_hint)} cluster`}
        title={deriveClusterTitle(clusterRef, members)}
        reference={clusterRef}
        referenceTitle="Cluster reference"
        chips={
          <>
            <StatusChip tone="neutral" label={`${members.length} members`} dense />
            {members.map((m) => (
              <StatusChip key={m.device_id} tone="mem" label={`${m.hostname ?? m.device_id} · ${haLabel(m.ha_role)}`} dense />
            ))}
            {(() => {
              const vs = Array.from(new Set(members.flatMap(vsListOf)));
              return vs.length > 0
                ? <StatusChip tone="neutral" label={`${vs.length} ${first?.vendor_hint === "palo_alto" ? "VSYS" : "VSX"}`} dense />
                : null;
            })()}
            {cluster && (cluster.diffCount > 0
              ? <StatusChip tone="bad" label={`Config diff · ${cluster.diffCount}`} dense />
              : state.missing.length === 0 ? <StatusChip tone="ok" label="Members agree" dense /> : <StatusChip tone="warn" label="UNKNOWN" dense />)}
          </>
        }
      />
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Current actual state · members side by side</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Cluster configuration</Typography>
        </Box>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <Stack direction="row" spacing={0.5} alignItems="center">
            <Switch size="small" checked={diffOnly} onChange={(e) => setDiffOnly(e.target.checked)} />
            <Typography variant="body2">Differences only</Typography>
          </Stack>
          <FilterBox value={query} onChange={setQuery} />
        </Stack>
      </Box>
      <ClusterMembersCard members={members} />
      {state.missing.length > 0 && (
        <EmptyPanel
          title={cluster ? "One side has no configuration read" : "No member has a configuration read"}
          body={`${state.missing.join(", ")}: no configuration collected yet. Agreement between members is UNKNOWN until every member is read; nothing is inferred from one side.`}
        />
      )}
      {state.loading && <EmptyPanel title="Cluster configuration" body="Reading every member's configuration…" />}
      {cluster && (
        <>
          <Card sx={{ px: 2, py: 1.25, borderRadius: "12px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip tone="ok" label="CURRENT ACTUAL" dense />
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{cluster.settingCount} settings across {cluster.memberIds.length} members</Typography>
            <Typography variant="body2" sx={{ color: cluster.diffCount > 0 ? m3.error : m3.onSurfaceVar, fontWeight: 600 }}>
              {cluster.diffCount === 0 ? "no differences" : `${cluster.diffCount} difference${cluster.diffCount === 1 ? "" : "s"}`}
            </Typography>
            <Typography variant="caption" color="text.secondary">Hostnames and member addresses are expected to differ (MEMBER) and are not counted.</Typography>
          </Card>
          {visibleSections.length === 0 ? (
            <EmptyPanel title={diffOnly ? "No differences" : "No setting matches"} body={diffOnly ? "Every setting that is not member-specific holds the same value on every member." : `Nothing matches "${query}".`} />
          ) : (
            <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(560px, 1fr))" }}>
              {visibleSections.map((section) => (
                <Card key={section.label} sx={{ borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}` }}>
                  <Box sx={{ px: 2, pt: 1.5, pb: 1, display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
                    <Box>
                      <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Current configuration</Typography>
                      <Typography variant="subtitle1" sx={{ fontWeight: 600, mt: -0.5 }}>{section.label}</Typography>
                    </Box>
                    <Typography variant="caption" color="text.secondary">
                      {section.rows.length} visible · {section.rows.filter((r) => r.diff).length} diff
                    </Typography>
                  </Box>
                  <TableContainer sx={{ maxHeight: 440 }}>
                    <Table size="small" stickyHeader>
                      <TableHead>
                        <TableRow>
                          <TableCell sx={{ fontSize: 11, letterSpacing: "0.06em" }}>SETTING</TableCell>
                          {cluster.memberIds.map((id) => (
                            <TableCell key={id} sx={{ fontSize: 11, letterSpacing: "0.06em" }}>{nameOf(id).toUpperCase()}</TableCell>
                          ))}
                          <TableCell sx={{ fontSize: 11, letterSpacing: "0.06em" }}>ORIGIN</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {section.rows.map((row) => (
                          <TableRow key={row.key} hover sx={row.diff ? { bgcolor: m3.errorContainer } : undefined}>
                            <TableCell sx={{ fontWeight: 600, fontSize: 12.5 }}>
                              {row.setting}
                              {row.diff && <StatusChip tone="bad" label="DIFF" dense />}
                            </TableCell>
                            {cluster.memberIds.map((id) => (
                              <TableCell key={id} sx={{ fontFamily: "monospace", fontSize: 12, wordBreak: "break-all" }}>{row.values[id]}</TableCell>
                            ))}
                            <TableCell><StatusChip tone={originTone(row.origin)} label={row.memberSpecific ? "MEMBER" : row.origin} dense /></TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Card>
              ))}
            </Box>
          )}
        </>
      )}
    </Stack>
  );
}
