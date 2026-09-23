import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import InputBase from "@mui/material/InputBase";
import Link from "@mui/material/Link";
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
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { RoleChip, Ts, Unknown } from "../shell/States";
import { MONO, m3 } from "../theme/m3Theme";
import {
  getDeviceConfiguration,
  getDeviceConfigurationText,
  type DeviceConfiguration,
  type DeviceSummary,
} from "../auth/adminApi";
import { InventoryEntityHeader, deriveClusterTitle } from "./InventoryPanels";
import { DeviceConfigurationPanels } from "./ConfigurationPanels";
import {
  ClusterContextStrip,
  ContentVersions,
  DeviceIdentityTable,
  downloadText,
  orderMembers,
  toCsv,
  vsListOf,
} from "./DeviceShared";
import {
  filterProjection,
  projectCheckPoint,
  projectCluster,
  projectPaloAlto,
  type ClusterProjection,
  type MemberRow,
  type Origin,
  type Projection,
  type Section,
} from "./configurationProjection";

export { contentVersionText } from "./DeviceShared";

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

/** A cluster row's ORIGIN word as shown on screen and in the export: MEMBER for a member-specific setting. */
function originWord(row: MemberRow): string {
  return row.memberSpecific ? "MEMBER" : row.origin;
}

/** configurationProjection writes "—" for a setting absent on one member: it was read, and it is not there. */
const ABSENT = "—";

const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none" } as const;

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
    <Box sx={{ height: 36, display: "flex", alignItems: "center", gap: 1, px: 1.5, borderRadius: "18px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, minWidth: 260 }}>
      <Icon name="search" size={18} />
      <InputBase placeholder="Filter setting or value…" value={value} onChange={(e) => onChange(e.target.value)} sx={{ flex: 1, fontSize: 13, color: "inherit" }} />
    </Box>
  );
}

function SnapshotTiles({ projection }: { readonly projection: Projection }) {
  if (projection.snapshot.length === 0) return null;
  return (
    <Card sx={{ p: 2, ...CARD }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Basic configuration</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Operator snapshot</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">Selected current values</Typography>
      </Box>
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))" }}>
        {projection.snapshot.map((tile) => (
          <Box key={`${tile.group}-${tile.label}`} sx={{ p: 1.5, borderRadius: "8px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}>
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 0.5 }}>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em" }}>{tile.group}</Typography>
              <StatusChip tone={originTone(tile.origin)} label={tile.origin} dense />
            </Box>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{tile.label}</Typography>
            <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12.5, wordBreak: "break-all" }}>{tile.value}</Typography>
          </Box>
        ))}
      </Box>
    </Card>
  );
}

/** One accordion row: "Section · n settings · n diff · n expected", expanded when it holds a difference (review §3). */
function SectionAccordion({ id, label, settings, diff, expected = 0, expanded, onToggle, children }: {
  readonly id: string;
  readonly label: string;
  readonly settings: number;
  readonly diff: number | null;
  readonly expected?: number;
  readonly expanded: boolean;
  readonly onToggle: () => void;
  readonly children: ReactNode;
}) {
  return (
    <Box id={id} data-section={label} sx={{ borderBottom: `1px solid ${m3.outlineVar}`, "&:last-of-type": { borderBottom: "none" } }}>
      <Box component="button" type="button" aria-expanded={expanded} onClick={onToggle}
        sx={{ width: "100%", height: 44, display: "flex", alignItems: "center", gap: 1, px: 2, border: "none", bgcolor: expanded ? m3.scLow : "transparent",
              font: "inherit", cursor: "pointer", color: m3.onSurface, textAlign: "left" }}>
        <Box component="span" sx={{ color: m3.onSurfaceVar, width: 12 }}>{expanded ? "▾" : "▸"}</Box>
        <Box component="span" sx={{ fontWeight: 600, fontSize: 13.5, flex: 1, minWidth: 0 }}>{label}</Box>
        <Box component="span" sx={{ fontSize: 12, color: m3.onSurfaceVar, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
          {settings} setting{settings === 1 ? "" : "s"}
          {diff !== null && <> · <Box component="span" sx={{ color: diff > 0 ? m3.criticalInk : m3.onSurfaceVar, fontWeight: diff > 0 ? 600 : 400 }}>{diff} diff</Box></>}
          {expected > 0 && <> · {expected} expected</>}
        </Box>
      </Box>
      {expanded && <Box sx={{ pb: 1 }}>{children}</Box>}
    </Box>
  );
}

function DeviceSectionTable({ section }: { readonly section: Section }) {
  return (
    <TableContainer sx={{ maxHeight: 420 }}>
      <Table size="small" stickyHeader>
        <TableHead>
          <TableRow>
            <TableCell sx={{ fontSize: 11, letterSpacing: "0.04em" }}>SETTING</TableCell>
            <TableCell sx={{ fontSize: 11, letterSpacing: "0.04em" }}>CURRENT VALUE</TableCell>
            <TableCell sx={{ fontSize: 11, letterSpacing: "0.04em" }}>ORIGIN</TableCell>
            <TableCell sx={{ fontSize: 11, letterSpacing: "0.04em" }}>CONTEXT</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {section.rows.map((row) => (
            <TableRow key={row.key} hover>
              <TableCell sx={{ fontWeight: 600, fontSize: 12.5 }}>{row.setting}</TableCell>
              <TableCell sx={{ fontFamily: MONO, fontSize: 12, wordBreak: "break-all" }}>{row.value}</TableCell>
              <TableCell><StatusChip tone={originTone(row.origin)} label={row.origin} dense /></TableCell>
              <TableCell sx={{ color: m3.onSurfaceVar }}>{row.context ?? "none"}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

interface IdentityTile {
  readonly label: string;
  readonly value: ReactNode | null;
  /** Shown when the value is null: why it is not there. */
  readonly absent?: string;
  readonly mono?: boolean;
}

/**
 * Platform identity: what a security administrator checks first and what a
 * compliance review asks for -- vendor, model, software version, hotfix level,
 * serial number, HA role, virtual systems. A value the product has not read
 * says so (UNKNOWN, with the reason); nothing is inferred.
 */
function identityTiles(device: DeviceSummary, collectedAt: string | null): IdentityTile[] {
  const vs = vsListOf(device);
  const isPaloAlto = device.vendor_hint === "palo_alto";
  const needsGate = "not collected -- needs its own gated read";
  return [
    { label: "Vendor", value: device.platform_family ? `${vendorLabel(device.vendor_hint)} · ${device.platform_family}` : vendorLabel(device.vendor_hint) },
    { label: "Model", value: device.model, absent: "not read at first contact" },
    { label: "Serial number", value: device.serial_number ?? null, absent: isPaloAlto ? "not read yet -- run Inventory collect" : needsGate, mono: true },
    { label: "Software version", value: device.software_version, absent: "not read at first contact" },
    isPaloAlto
      ? { label: "Content versions", value: device.content_versions ? <ContentVersions versions={device.content_versions} /> : null, absent: "not read yet -- run Inventory collect" }
      : { label: "Hotfix / Jumbo take", value: device.hotfix_level ?? null, absent: needsGate },
    { label: "Uptime", value: device.uptime_text ?? null, absent: isPaloAlto ? "not read yet -- run Inventory collect" : needsGate },
    { label: "HA role", value: device.ha_role ? <RoleChip role={device.ha_role} dense /> : null, absent: device.cluster_member_ref ? "not reported" : "standalone" },
    { label: isPaloAlto ? "Virtual systems (VSYS)" : "Virtual systems (VSX)", value: vs.length > 0 ? `${vs.length} · ${vs.join(", ")}` : "none" },
    { label: "Management address", value: device.management_ip ?? null, absent: "not recorded", mono: true },
    { label: "Enrollment", value: device.enrollment_state || null, absent: "not recorded" },
    {
      label: "Policy installed",
      value: device.policy_installed_at
        ? <><Ts at={device.policy_installed_at} />{device.policy_name ? ` · ${device.policy_name}` : ""}</>
        : device.policy_installed_at_text ? `${device.policy_installed_at_text} (as reported)` : null,
      absent: "not read yet -- read every evening at 23:00",
    },
    { label: "Platform facts read", value: device.platform_facts_observed_at ? <Ts at={device.platform_facts_observed_at} /> : null, absent: "never" },
    { label: "Last configuration read", value: collectedAt ? <Ts at={collectedAt} /> : null, absent: "never" },
  ];
}

function IdentityCard({ title, tiles }: { readonly title: string; readonly tiles: readonly IdentityTile[] }) {
  return (
    <Card sx={{ p: 2, ...CARD }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Platform identity</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>{title}</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">What an audit asks for first</Typography>
      </Box>
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))" }}>
        {tiles.map((tile) => (
          <Box key={tile.label} sx={{ p: 1.5, borderRadius: "8px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em", display: "block", mb: 0.5 }}>{tile.label.toUpperCase()}</Typography>
            {tile.value !== null && tile.value !== undefined ? (
              <Typography component="div" variant="body2" sx={{ fontWeight: 600, fontFamily: tile.mono ? MONO : undefined, wordBreak: "break-word" }}>{tile.value}</Typography>
            ) : (
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
                <Unknown />
                <Typography variant="caption" color="text.secondary">{tile.absent}</Typography>
              </Box>
            )}
          </Box>
        ))}
      </Box>
    </Card>
  );
}

/**
 * A cluster's members as one row each -- the same identity table the Devices screen's Identity & provenance tab
 * renders. The platform identity line above it keeps the full virtual-system list; the table collapses it.
 */
function ClusterMembersCard({ members }: { readonly members: readonly DeviceSummary[] }) {
  const isPaloAlto = members[0]?.vendor_hint === "palo_alto";
  const vsUnion = Array.from(new Set(members.flatMap(vsListOf))).sort();
  return (
    <Card sx={CARD}>
      <Box sx={{ px: 2, pt: 1.5, pb: 1, display: "flex", justifyContent: "space-between", alignItems: "baseline", flexWrap: "wrap", gap: 1 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Platform identity</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Members</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary" data-testid="platform-identity-line">
          {vsUnion.length === 0
            ? `No ${isPaloAlto ? "virtual systems (VSYS)" : "virtual systems (VSX)"} recorded`
            : `${vsUnion.length} ${isPaloAlto ? "virtual system(s) (VSYS)" : "virtual system(s) (VSX)"}: ${vsUnion.join(", ")}`}
        </Typography>
      </Box>
      <DeviceIdentityTable devices={members} ariaLabel="Member identity" />
    </Card>
  );
}

function headerChips(device: DeviceSummary, extra: ReactNode = null) {
  return (
    <>
      <StatusChip tone="neutral" label={vendorLabel(device.vendor_hint)} dense />
      {device.model && <StatusChip tone="neutral" label={device.model} dense />}
      {device.software_version && <StatusChip tone="neutral" label={device.software_version} dense />}
      {device.management_ip && <StatusChip tone="neutral" label={device.management_ip} dense />}
      {device.ha_role && <RoleChip role={device.ha_role} dense />}
      {extra}
    </>
  );
}

/** One device: header, summary bar, operator snapshot, section accordion; the old detail (changes, overrides, native text) stays under Details. */
export function DeviceConfigurationDetail({ device }: { readonly device: DeviceSummary }) {
  const { configuration, projection, error, loading } = useProjection(device);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<ReadonlySet<string> | null>(null);
  useEffect(() => { setQuery(""); setOpen(null); }, [device.device_id]);
  const sections = useMemo(() => (projection ? filterProjection(projection.sections, query) : []), [projection, query]);
  const collectedAt = configuration?.collected_at ?? null;
  // A single device has no peer to differ from: every section starts collapsed except the first, one click away.
  const isOpen = (label: string) => (open === null ? sections[0]?.label === label || query.trim() !== "" : open.has(label));
  const toggle = (label: string) => setOpen((prev) => {
    const next = new Set(prev ?? sections.filter((s) => isOpen(s.label)).map((s) => s.label));
    if (next.has(label)) next.delete(label); else next.add(label);
    return next;
  });

  const configurationTab = (
    <Stack spacing={2}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Observed configuration</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Device configuration</Typography>
        </Box>
        <FilterBox value={query} onChange={setQuery} />
      </Box>
      <IdentityCard title={device.hostname ?? device.device_id} tiles={identityTiles(device, collectedAt)} />
      {error && <EmptyPanel title="Configuration unavailable" body={error} />}
      {!error && loading && <EmptyPanel title="Configuration" body="Reading the device's configuration…" />}
      {!error && !loading && !projection && (
        <EmptyPanel title="No configuration read yet" body="Collect the device's configuration first (Collect All, or Collect now on the Details tab)." />
      )}
      {projection && (
        <>
          <Card sx={{ px: 2, py: 1.25, ...CARD, display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip tone="neutral" label="OBSERVED" dense />
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{projection.settingCount} projected settings</Typography>
            <Typography variant="caption" color="text.secondary">Source plane: {projection.sourcePlane}</Typography>
            {collectedAt && <Typography variant="caption" color="text.secondary">Collected <Ts at={collectedAt} relative /></Typography>}
            <Box sx={{ flexGrow: 1 }} />
            <Typography variant="caption" color="text.secondary">
              {projection.withheldCount} secret-bearing setting{projection.withheldCount === 1 ? "" : "s"} withheld
            </Typography>
          </Card>
          <SnapshotTiles projection={projection} />
          {sections.length === 0 ? (
            <EmptyPanel title="No setting matches" body={`Nothing in ${projection.settingCount} settings matches "${query}".`} />
          ) : (
            <Card sx={CARD}>
              {sections.map((section) => (
                <SectionAccordion key={section.label} id={`cfg-section-${slug(section.label)}`} label={section.label}
                  settings={section.rows.length} diff={null} expanded={isOpen(section.label)} onToggle={() => toggle(section.label)}>
                  <DeviceSectionTable section={section} />
                </SectionAccordion>
              ))}
            </Card>
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

function slug(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

/**
 * The per-cluster configuration evidence export (review §6): every setting of every member as shown on screen --
 * names as displayed (masked for aiview by the server), the ORIGIN word, and whether it is a difference -- built
 * in the browser from the data already loaded. No request is made; full-precision UTC time of the export.
 */
export function clusterEvidenceCsv(input: {
  readonly clusterTitle: string;
  readonly clusterRef: string;
  readonly members: ReadonlyArray<{ readonly id: string; readonly name: string; readonly role: string | null }>;
  readonly cluster: ClusterProjection;
  readonly exportedAt: string;
}): string {
  const { clusterTitle, clusterRef, members, cluster, exportedAt } = input;
  const header = ["cluster", "cluster_ref", "section", "setting", ...members.map((m) => `${m.name}${m.role ? ` (${m.role.toUpperCase()})` : ""}`), "origin", "difference", "exported_at_utc"];
  const rows = cluster.sections.flatMap((section) => section.rows.map((row) => [
    clusterTitle,
    clusterRef,
    section.label,
    row.setting,
    ...members.map((m) => {
      const v = row.values[m.id];
      return v === undefined || v === ABSENT ? "not present on this member" : v;
    }),
    originWord(row),
    row.diff ? "DIFF" : row.memberDiff ? "EXPECTED (member-specific)" : "",
    exportedAt,
  ]));
  return toCsv([header, ...rows]);
}

/** A cluster: every member's configuration side by side; a real difference is a DIFF row, a member-specific one is marked MEMBER. */
export function ClusterConfigurationDetail({ clusterRef, members: unorderedMembers }: { readonly clusterRef: string; readonly members: readonly DeviceSummary[] }) {
  // Review §3: members always M1, M2 (by name) -- in the header, the identity table and the setting columns.
  const members = useMemo(() => orderMembers(unorderedMembers), [unorderedMembers]);
  const [state, setState] = useState<{ cluster: ClusterProjection | null; missing: string[]; error: string | null; loading: boolean }>({ cluster: null, missing: [], error: null, loading: true });
  const [query, setQuery] = useState("");
  // Review §3: "Differences only" is ON by default when the cluster has differences (set once per load).
  const [diffOnly, setDiffOnly] = useState(false);
  const [open, setOpen] = useState<ReadonlySet<string> | null>(null);
  const [pendingJump, setPendingJump] = useState<string | null>(null);
  const defaultedFor = useRef<ClusterProjection | null>(null);

  useEffect(() => {
    let cancelled = false;
    setState({ cluster: null, missing: [], error: null, loading: true });
    setQuery("");
    setOpen(null);
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

  const cluster = state.cluster;
  useEffect(() => {
    if (!cluster || defaultedFor.current === cluster) return;
    defaultedFor.current = cluster;
    setDiffOnly(cluster.diffCount > 0 || cluster.memberDiffCount > 0);
  }, [cluster]);

  const nameOf = (id: string) => members.find((m) => m.device_id === id)?.hostname ?? id;
  const first = members[0];
  const clusterTitle = deriveClusterTitle(clusterRef, members);

  // Every section with its full counts (the accordion row says "n settings · n diff"), then the visible rows.
  const allSections = useMemo(() => (cluster ? cluster.sections.map((s) => ({
    label: s.label,
    total: s.rows.length,
    diff: s.rows.filter((r) => r.diff).length,
    expected: s.rows.filter((r) => r.memberDiff).length,
    rows: s.rows,
  })) : []), [cluster]);
  const visibleSections = useMemo(() => {
    const q = query.trim().toLowerCase();
    return allSections
      .map((s) => ({
        ...s,
        rows: s.rows.filter((r) => (!diffOnly || r.diff || r.memberDiff) && (!q || r.setting.toLowerCase().includes(q) || s.label.toLowerCase().includes(q)
          || Object.values(r.values).some((v) => v.toLowerCase().includes(q)))),
      }))
      .filter((s) => s.rows.length > 0);
  }, [allSections, query, diffOnly]);
  // One link per section › setting; repeated settings (e.g. five bonding groups) collapse to "×5" and jump to the first.
  const differences = useMemo(() => {
    const seen = new Map<string, { section: string; row: MemberRow; count: number }>();
    for (const s of allSections) for (const r of s.rows) if (r.diff) {
      const k = `${s.label}\u0000${r.setting}`;
      const hit = seen.get(k);
      if (hit) hit.count++; else seen.set(k, { section: s.label, row: r, count: 1 });
    }
    return [...seen.values()];
  }, [allSections]);

  const isOpen = (label: string, diff: number) => (open === null ? diff > 0 || query.trim() !== "" : open.has(label));
  const toggle = (label: string) => setOpen((prev) => {
    const next = new Set(prev ?? visibleSections.filter((s) => isOpen(s.label, s.diff + s.expected)).map((s) => s.label));
    if (next.has(label)) next.delete(label); else next.add(label);
    return next;
  });
  const jumpTo = (section: string, key: string) => {
    setOpen((prev) => new Set([...(prev ?? visibleSections.filter((s) => isOpen(s.label, s.diff + s.expected)).map((s) => s.label)), section]));
    setPendingJump(`cfg-row-${slug(key)}`);
  };
  useEffect(() => {
    if (!pendingJump) return;
    const el = document.getElementById(pendingJump);
    el?.scrollIntoView?.({ behavior: "smooth", block: "center" });
    setPendingJump(null);
  }, [pendingJump, open]);

  const exportCsv = () => {
    if (!cluster) return;
    const exportedAt = new Date().toISOString();
    const csv = clusterEvidenceCsv({
      clusterTitle,
      clusterRef,
      members: cluster.memberIds.map((id) => ({ id, name: nameOf(id), role: members.find((m) => m.device_id === id)?.ha_role ?? null })),
      cluster,
      exportedAt,
    });
    downloadText(`configuration-evidence-${slug(clusterTitle) || "cluster"}-${exportedAt.replace(/[:.]/g, "-")}.csv`, csv);
  };

  return (
    <Stack spacing={2}>
      <InventoryEntityHeader
        vendorHint={first?.vendor_hint ?? "check_point"}
        model={first?.model}
        titlePrefix={`Configuration · ${vendorLabel(first?.vendor_hint)} cluster`}
        title={clusterTitle}
        // A Palo Alto HA pair's reference is the two serials joined; the members table below shows each
        // serial in its own column, so the chip would only repeat them (PO, 2026-09-22).
        reference={first?.vendor_hint === "palo_alto" ? "" : clusterRef}
        referenceTitle="Cluster reference"
        action={
          <M3Button emphasis="tonal" icon="download" disabled={!cluster} onClick={exportCsv}>
            Export evidence (CSV)
          </M3Button>
        }
        chips={
          <>
            <StatusChip tone="neutral" label={`${members.length} members`} dense />
            {members.map((m) => (
              <Box key={m.device_id} component="span" data-member-chip={m.device_id}
                sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, fontSize: 12, fontWeight: 600 }}>
                {m.hostname ?? m.device_id}
                <RoleChip role={m.ha_role} dense />
              </Box>
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
      >
        <ClusterContextStrip clusterRef={clusterRef} current="configuration" />
      </InventoryEntityHeader>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 1 }}>
        <Box>
          <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>Observed configuration · members side by side</Typography>
          <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>Cluster configuration</Typography>
        </Box>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <Stack direction="row" spacing={0.5} alignItems="center">
            <Switch size="small" checked={diffOnly} onChange={(e) => setDiffOnly(e.target.checked)} inputProps={{ "aria-label": "Differences only" }} />
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
          <Card sx={{ px: 2, py: 1.25, ...CARD, display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip tone="neutral" label="OBSERVED" dense />
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{cluster.settingCount} distinct settings compared across {cluster.memberIds.length} members</Typography>
            <Typography variant="body2" sx={{ color: cluster.diffCount > 0 ? m3.criticalInk : m3.onSurfaceVar, fontWeight: 600 }}>
              {cluster.diffCount === 0 ? "no differences" : `${cluster.diffCount} difference${cluster.diffCount === 1 ? "" : "s"}`}
            </Typography>
            {cluster.memberDiffCount > 0 && (
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                {cluster.memberDiffCount} expected member-specific difference{cluster.memberDiffCount === 1 ? "" : "s"}
              </Typography>
            )}
            <Typography variant="caption" color="text.secondary">
              Hostnames, member addresses, HA device priority and interface physical settings (auto-negotiation, link speed, MTU, receive ring size) may hold different values: shown per member as MEMBER, not counted. Set on one member only, they count as a difference.
            </Typography>
          </Card>
          {differences.length > 0 && (
            <Box component="nav" aria-label="Differences" sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap", px: 0.5 }}>
              <Typography component="span" sx={{ fontSize: 12, fontWeight: 600, color: m3.criticalInk }}>Differences:</Typography>
              {differences.map(({ section, row, count }, i) => (
                <Box component="span" key={row.key} sx={{ display: "inline-flex", alignItems: "center", gap: 1 }}>
                  <Link component="button" type="button" underline="hover" onClick={() => jumpTo(section, row.key)} sx={{ fontSize: 12.5 }}>
                    {section} › {row.setting}{count > 1 ? ` ×${count}` : ""}
                  </Link>
                  {i < differences.length - 1 && <Box component="span" sx={{ color: m3.outline }}>·</Box>}
                </Box>
              ))}
            </Box>
          )}
          {visibleSections.length === 0 ? (
            <EmptyPanel title={diffOnly ? "No differences" : "No setting matches"} body={diffOnly ? "Every setting that is not member-specific holds the same value on every member." : `Nothing matches "${query}".`} />
          ) : (
            <Card sx={CARD}>
              {visibleSections.map((section) => (
                <SectionAccordion key={section.label} id={`cfg-section-${slug(section.label)}`} label={section.label}
                  settings={section.total} diff={section.diff} expected={section.expected} expanded={isOpen(section.label, section.diff + section.expected)} onToggle={() => toggle(section.label)}>
                  <TableContainer sx={{ maxHeight: 440 }}>
                    <Table size="small" stickyHeader>
                      <TableHead>
                        <TableRow>
                          <TableCell sx={{ fontSize: 11, letterSpacing: "0.04em" }}>SETTING</TableCell>
                          {cluster.memberIds.map((id) => (
                            <TableCell key={id} sx={{ fontSize: 11, letterSpacing: "0.04em" }}>{nameOf(id).toUpperCase()}</TableCell>
                          ))}
                          <TableCell sx={{ fontSize: 11, letterSpacing: "0.04em" }}>ORIGIN</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {section.rows.map((row) => (
                          <TableRow key={row.key} id={`cfg-row-${slug(row.key)}`} hover sx={row.diff ? { bgcolor: m3.errorContainer } : undefined}>
                            <TableCell sx={{ fontWeight: 600, fontSize: 12.5 }}>
                              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                                {row.setting}
                                {row.diff && <StatusChip tone="bad" label="DIFF" dense />}
                                {row.memberDiff && <StatusChip tone="neutral" label="EXPECTED" dense />}
                              </Box>
                            </TableCell>
                            {cluster.memberIds.map((id) => (
                              <TableCell key={id} sx={{ fontFamily: MONO, fontSize: 12, wordBreak: "break-all" }}>
                                {row.values[id] === undefined || row.values[id] === ABSENT
                                  ? <Box component="span" sx={{ color: m3.onSurfaceVar, fontFamily: "inherit" }} title="Read, and not present on this member">not present</Box>
                                  : row.values[id]}
                              </TableCell>
                            ))}
                            <TableCell><StatusChip tone={originTone(row.origin)} label={originWord(row)} dense /></TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </SectionAccordion>
              ))}
            </Card>
          )}
        </>
      )}
    </Stack>
  );
}
