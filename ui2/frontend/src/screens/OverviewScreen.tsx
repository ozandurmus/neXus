import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { EmptyPanel, ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { Icon, type IconName } from "../shell/Icon";
import { StatusChip } from "../shell/M3Widgets";
import { Donut, Meter, STATUS, StackedBar, foldSlices } from "../shell/Charts";
import { m3 } from "../theme/m3Theme";
import { getOverview, type CountTile, type EvidenceChip, type OverviewView } from "../auth/adminApi";

/**
 * Overview -- an executive summary over stored evidence (OVERVIEW_EXCEPTION_SCREEN_CONTRACT, FROZEN 2026-09-23;
 * visual pass requested by the PO the same day: "more appealing, an executive summary, per-vendor major and
 * minor versions as pie charts, more colour"). Every figure is still a stored-evidence query with its evidence
 * time; UNKNOWN is written as UNKNOWN, never 0; every figure opens the screen that lists it, filtered.
 */

export function relativeAge(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return "UNKNOWN";
  const s = Math.max(0, (now.getTime() - new Date(iso).getTime()) / 1000);
  if (s < 90) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 172800) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

function ageTone(iso: string | null | undefined): keyof typeof STATUS {
  if (!iso) return "neutral";
  const h = (Date.now() - new Date(iso).getTime()) / 3_600_000;
  return h < 24 ? "good" : h < 72 ? "warning" : "critical";
}

const CARD = { borderRadius: "20px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, p: 2.5, boxShadow: "0 1px 2px rgba(15,23,42,0.04)" } as const;
const q = (params: Record<string, string>) => `?${new URLSearchParams(params).toString()}`;
const pct = (a: number, b: number) => (b > 0 ? Math.round((100 * a) / b) : 0);

function SectionTitle({ icon, title, hint, right }: { readonly icon: IconName; readonly title: string; readonly hint?: string; readonly right?: React.ReactNode }) {
  return (
    <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 1.5, gap: 1, flexWrap: "wrap" }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
        <Box sx={{ width: 34, height: 34, borderRadius: "10px", bgcolor: m3.primaryContainer, color: m3.primary, display: "grid", placeItems: "center" }}>
          <Icon name={icon} size={18} />
        </Box>
        <Box>
          <Typography sx={{ fontSize: 17, fontWeight: 650, color: m3.onSurface, lineHeight: 1.2 }}>{title}</Typography>
          {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
        </Box>
      </Box>
      {right}
    </Box>
  );
}

/** One KPI of the executive band: a big number, a ratio meter and what it means. */
function Kpi({ label, value, of, suffix, tone, note, href }: {
  readonly label: string; readonly value: number | null; readonly of: number; readonly suffix?: string;
  readonly tone: keyof typeof STATUS | "primary"; readonly note: string; readonly href: string;
}) {
  return (
    <Link href={href} underline="none" sx={{ color: "inherit", display: "block", flex: "1 1 200px", minWidth: 190 }}>
      <Box sx={{ p: 2, borderRadius: "16px", bgcolor: "rgba(255,255,255,0.82)", border: "1px solid rgba(255,255,255,0.9)", height: "100%", "&:hover": { bgcolor: "#fff" } }}>
        <Typography sx={{ fontSize: 12, fontWeight: 600, letterSpacing: "0.04em", color: m3.onSurfaceVar }}>{label.toUpperCase()}</Typography>
        <Typography sx={{ fontSize: 34, fontWeight: 750, lineHeight: 1.15, color: m3.onSurface, my: 0.5 }}>
          {value === null ? "UNKNOWN" : `${pct(value, of)}%`}
          {value !== null && <Typography component="span" sx={{ fontSize: 13, fontWeight: 500, color: m3.onSurfaceVar, ml: 1 }}>{value} of {of}{suffix ? ` ${suffix}` : ""}</Typography>}
        </Typography>
        <Meter value={value ?? 0} of={of} tone={tone} />
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block", mt: 0.75 }}>{note}</Typography>
      </Box>
    </Link>
  );
}

function EvidenceDot({ label, chip, href }: { readonly label: string; readonly chip: EvidenceChip | undefined; readonly href: string }) {
  const tone = chip?.state === "OK" ? ageTone(chip.at) : "neutral";
  const text = chip?.state === "OK" ? relativeAge(chip.at) : chip?.state === "UNKNOWN" ? "UNKNOWN — no stored evidence" : "READ FAILED";
  return (
    <Tooltip title={chip?.at ? new Date(chip.at).toUTCString() : text}>
      <Link href={href} underline="none" sx={{ color: "inherit" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, px: 1.25, py: 0.5, borderRadius: "999px", bgcolor: "rgba(255,255,255,0.7)" }}>
          <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: STATUS[tone] }} />
          <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{label}</Typography>
          <Typography sx={{ fontSize: 12, fontWeight: 600, color: m3.onSurface }}>{text}</Typography>
        </Box>
      </Link>
    </Tooltip>
  );
}

type Severity = "critical" | "serious" | "warning";

function AttentionTile({ title, tile, href, subtitle, severity, icon }: {
  readonly title: string; readonly tile: CountTile | undefined; readonly href: string; readonly subtitle: string;
  readonly severity: Severity; readonly icon: IconName;
}) {
  const unknown = !tile || tile.state !== "OK";
  const active = !unknown && tile.count > 0;
  const color = active ? STATUS[severity] : STATUS.neutral;
  const word = unknown ? "Unknown" : active ? (severity === "critical" ? "Act now" : severity === "serious" ? "Review" : "Watch") : "Clear";
  return (
    <Link href={href} underline="none" sx={{ color: "inherit", display: "block" }}>
      <Card sx={{ ...CARD, p: 2, height: "100%", position: "relative", overflow: "hidden", "&:hover": { boxShadow: "0 4px 14px rgba(15,23,42,0.08)" } }}>
        <Box sx={{ position: "absolute", left: 0, top: 0, bottom: 0, width: 5, bgcolor: color }} />
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 0.5 }}>
          <Box sx={{ color, display: "flex" }}><Icon name={icon} size={18} /></Box>
          <Box sx={{ px: 1, py: 0.25, borderRadius: "999px", bgcolor: active ? `${color}22` : m3.sc, fontSize: 11, fontWeight: 700, color: active ? m3.onSurface : m3.onSurfaceVar }}>{word}</Box>
        </Box>
        <Typography sx={{ fontSize: 13.5, fontWeight: 550, color: m3.onSurfaceVar, minHeight: 38 }}>{title}</Typography>
        <Typography sx={{ fontSize: 32, fontWeight: 750, lineHeight: 1.1, color: m3.onSurface }}>
          {unknown ? "UNKNOWN" : tile.count}
          {!unknown && tile.of !== undefined && <Typography component="span" sx={{ fontSize: 15, fontWeight: 500, color: m3.onSurfaceVar }}> of {tile.of}</Typography>}
        </Typography>
        <Typography variant="caption" color="text.secondary">{subtitle}</Typography>
      </Card>
    </Link>
  );
}

function ExceptionList<T>({ icon, title, total, rows, empty, head, cells, rowHref, allHref }: {
  readonly icon: IconName; readonly title: string; readonly total: number; readonly rows: readonly T[]; readonly empty: string;
  readonly head: readonly string[]; readonly cells: (r: T) => React.ReactNode[]; readonly rowHref: (r: T) => string; readonly allHref: string;
}) {
  return (
    <Card sx={CARD}>
      <SectionTitle icon={icon} title={title} right={total > rows.length ? <Link href={allHref} sx={{ fontSize: 13 }}>Show all ({total})</Link> : null} />
      {rows.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{empty}</Typography>
      ) : (
        <Table size="small">
          <TableHead><TableRow>{head.map((h) => <TableCell key={h} sx={{ fontSize: 11, letterSpacing: "0.06em", color: m3.onSurfaceVar }}>{h}</TableCell>)}</TableRow></TableHead>
          <TableBody>
            {rows.map((r, i) => (
              <TableRow key={i} hover sx={{ cursor: "pointer" }} onClick={() => { window.location.href = rowHref(r); }}>
                {cells(r).map((c, j) => <TableCell key={j} sx={{ fontSize: 12.5, verticalAlign: "top" }}>{c}</TableCell>)}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Card>
  );
}

function VendorVersions({ vendor, name, majorTitle, minorTitle, versions, fallbackMinor, total }: {
  readonly vendor: "check_point" | "palo_alto"; readonly name: string; readonly majorTitle: string; readonly minorTitle: string;
  readonly versions: { major: Array<{ label: string | null; count: number }>; minor: Array<{ label: string | null; count: number }> } | undefined;
  readonly fallbackMinor?: Array<{ level: string | null; count: number }>;
  readonly total: number;
}) {
  const accent = vendor === "check_point" ? m3.cp : m3.pan;
  const majorLink = (label: string | null) => q({ screen: "inventory", vendor, sw_major: label ?? "unknown" });
  const minorLink = (label: string | null) => vendor === "check_point"
    ? q({ screen: "inventory", vendor, hotfix_level: label ?? "unknown" })
    : q({ screen: "inventory", vendor, sw_version: label ?? "unknown" });
  const major = foldSlices((versions?.major ?? []).map((s) => ({ ...s, href: majorLink(s.label) })), 5, q({ screen: "inventory", vendor }));
  const minorRaw = versions?.minor ?? (fallbackMinor ?? []).map((h) => ({ label: h.level, count: h.count }));
  const minor = foldSlices(minorRaw.map((s) => ({ ...s, href: minorLink(s.label) })), 5, q({ screen: "inventory", vendor }));
  return (
    <Card sx={{ ...CARD, borderTop: `4px solid ${accent}` }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.5 }}>
        <Typography sx={{ fontSize: 16, fontWeight: 650 }}>{name}</Typography>
        <Link href={q({ screen: "inventory", vendor })} sx={{ fontSize: 13 }}>{total} devices</Link>
      </Box>
      <Box sx={{ display: "grid", gap: 2.5, gridTemplateColumns: "repeat(auto-fit, minmax(290px, 1fr))" }}>
        <Donut title={majorTitle} slices={major} />
        <Donut title={minorTitle} slices={minor} />
      </Box>
    </Card>
  );
}

export function OverviewScreen() {
  const [data, setData] = useState<OverviewView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    getOverview().then((d) => { setData(d); setError(null); })
      .catch((e: { status?: number; message?: string }) => setError(e?.message ?? `The overview could not be read (status ${e?.status ?? "?"})`));
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 60_000);
    const onFocus = () => load();
    window.addEventListener("focus", onFocus);
    return () => { clearInterval(t); window.removeEventListener("focus", onFocus); };
  }, [load]);

  if (error) {
    return <ScreenRoot><ScreenHeader title="Operational posture" subtitle="The overview could not be read" /><EmptyPanel title="Overview unavailable" body={error} /></ScreenRoot>;
  }
  if (!data) {
    return <ScreenRoot><ScreenHeader title="Operational posture" subtitle="Reading the fleet…" /></ScreenRoot>;
  }

  const a = data.attention;
  const ex = data.exceptions;
  const age = data.inventory_age;
  const c = data.compliance;
  const p = data.platform;
  const n = data.nexus;
  const d = data.denominators;
  const fresh = age?.lt24h ?? 0;
  const protectedTargets = a.backup_missing?.state === "OK" ? (a.backup_missing.of ?? 0) - a.backup_missing.count : null;
  const agreeing = a.cluster_diff?.state === "OK" ? (a.cluster_diff.of ?? 0) - a.cluster_diff.count : null;
  const failed = a.failed_jobs_24h?.count ?? 0;

  const headline: string[] = [];
  headline.push(`Evidence is current for ${fresh} of ${age?.of ?? d.active_devices} devices.`);
  headline.push(failed > 0 ? `${failed} job${failed === 1 ? "" : "s"} failed in the last 24 hours.` : "No job failed in the last 24 hours.");
  if (protectedTargets !== null) headline.push(`${protectedTargets} of ${a.backup_missing.of} backup targets hold an archive.`);
  if (c.state === "OK") headline.push(`Compliance: ${c.assured_pct ?? c.observed_pct}% assured, evidence coverage ${c.coverage_pct}%, ${c.critical_deficiencies} critical deficiencies.`);

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Operational posture"
        subtitle={`${d.active_devices} active devices · ${p.check_point ?? "—"} Check Point · ${p.palo_alto ?? "—"} Palo Alto · ${d.clusters} clusters · read ${relativeAge(data.generated_at)}`}
        actions={data.masked ? <StatusChip tone="mem" label="aiview · names masked" /> : undefined}
      />

      {/* Executive summary band */}
      <Box sx={{ borderRadius: "24px", p: { xs: 2, md: 3 }, background: `linear-gradient(135deg, ${m3.primaryContainer} 0%, #EEF4FF 45%, #F3EEFF 100%)`, border: `1px solid ${m3.outlineVar}` }}>
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 2, flexWrap: "wrap", mb: 2 }}>
          <Box sx={{ maxWidth: 820 }}>
            <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: "0.1em", color: m3.primary }}>EXECUTIVE SUMMARY</Typography>
            <Typography sx={{ fontSize: 21, fontWeight: 650, lineHeight: 1.35, color: m3.onPrimaryContainer, mt: 0.5 }}>{headline.join(" ")}</Typography>
          </Box>
          <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", maxWidth: 560, justifyContent: "flex-end" }}>
            <EvidenceDot label="Inventory" chip={data.evidence.inventory} href={q({ screen: "operations", tab: "jobs", job_type: "inventory_collect" })} />
            <EvidenceDot label="Configuration" chip={data.evidence.configuration} href={q({ screen: "operations", tab: "jobs", job_type: "configuration_collect" })} />
            <EvidenceDot label="Compliance" chip={data.evidence.compliance} href="?screen=compliance" />
            <EvidenceDot label="Backup" chip={data.evidence.backup} href={q({ screen: "operations", tab: "jobs", job_type: "backup" })} />
            <EvidenceDot label="Jobs" chip={data.evidence.jobs} href={q({ screen: "operations", tab: "jobs" })} />
            <EvidenceDot label="Platform facts" chip={data.evidence.platform_facts} href="?screen=inventory" />
          </Box>
        </Box>
        <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap" }}>
          <Kpi label="Evidence current (24 h)" value={fresh} of={age?.of ?? 0} tone={pct(fresh, age?.of ?? 0) >= 95 ? "good" : "warning"}
            note={`${age?.h24_72 ?? 0} aging · ${age?.gt72h ?? 0} stale · ${age?.never ?? 0} never read`} href={q({ screen: "inventory", inventory_age: "stale" })} />
          <Kpi label="Backup targets protected" value={protectedTargets} of={a.backup_missing?.of ?? 0} tone={protectedTargets !== null && pct(protectedTargets, a.backup_missing.of ?? 0) >= 95 ? "good" : "serious"}
            note={`${a.backup_missing?.count ?? "—"} targets without an archive`} href={q({ screen: "backups", artefact: "none" })} />
          <Kpi label="Compliance evidence coverage" value={c.state === "OK" ? Math.round(c.coverage_pct ?? 0) : null} of={100} tone="primary"
            note={c.state === "OK" ? `${c.assured_pct ?? c.observed_pct}% assured · ${c.data_gaps} data gaps` : "not evaluated yet"} href="?screen=compliance" />
          <Kpi label="Clusters in agreement" value={agreeing} of={a.cluster_diff?.of ?? 0} tone={agreeing !== null && agreeing === (a.cluster_diff.of ?? 0) ? "good" : "warning"}
            note={`${a.cluster_diff?.count ?? "—"} with member differences${a.cluster_diff?.unknown ? ` · ${a.cluster_diff.unknown} not comparable` : ""}`} href={q({ screen: "configuration", cluster_diff: "present" })} />
        </Box>
      </Box>

      {/* Needs attention */}
      <Box>
        <SectionTitle icon="bell" title="Needs attention" hint="Each tile opens its filtered list" />
        <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))" }}>
          <AttentionTile icon="operations" severity="critical" title="Failed jobs, 24 h" tile={a.failed_jobs_24h} subtitle={`of ${a.failed_jobs_24h?.terminal_24h ?? "—"} finished in 24 h`}
            href={q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24" })} />
          <AttentionTile icon="devices" severity="warning" title="Devices without inventory in 24 h" tile={a.stale_inventory} subtitle={`latest inventory ${relativeAge(data.evidence.inventory?.at)}`}
            href={q({ screen: "inventory", inventory_age: "stale" })} />
          <AttentionTile icon="config" severity="serious" title="Clusters with member DIFF" tile={a.cluster_diff}
            subtitle={a.cluster_diff?.unknown ? `UNKNOWN for ${a.cluster_diff.unknown} cluster(s) without two member reads` : "every cluster comparable"}
            href={q({ screen: "configuration", cluster_diff: "present" })} />
          <AttentionTile icon="config" severity="serious" title="Configuration changed since previous collection" tile={a.config_changed} subtitle={`latest configuration ${relativeAge(data.evidence.configuration?.at)}`}
            href={q({ screen: "configuration", change_state: "changed" })} />
          <AttentionTile icon="backup" severity="critical" title="Backup targets without an archive" tile={a.backup_missing} subtitle={`latest backup ${relativeAge(data.evidence.backup?.at)}`}
            href={q({ screen: "backups", artefact: "none" })} />
        </Box>
      </Box>

      {/* Software versions */}
      <Box>
        <SectionTitle icon="grid" title="Software versions" hint={`Per vendor, from the latest reads · facts ${relativeAge(p.evidence_at)}`} />
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(560px, 1fr))" }}>
          <VendorVersions vendor="check_point" name="Check Point" majorTitle="Major version" minorTitle="Hotfix (jumbo take)"
            versions={p.versions?.check_point} fallbackMinor={p.hotfix_levels} total={p.check_point ?? 0} />
          <VendorVersions vendor="palo_alto" name="Palo Alto Networks" majorTitle="Major version (PAN-OS)" minorTitle="Exact version"
            versions={p.versions?.palo_alto} total={p.palo_alto ?? 0} />
        </Box>
      </Box>

      {/* Compliance + evidence age */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(460px, 1fr))" }}>
        <Card sx={CARD}>
          <SectionTitle icon="compliance" title="Compliance" hint={`evidence ${relativeAge(data.evidence.compliance?.at)}`} />
          {c.state !== "OK" ? (
            <Typography variant="body2" color="text.secondary">UNKNOWN — compliance not yet evaluated. <Link href="?screen=compliance">Open Compliance to evaluate.</Link></Typography>
          ) : (
            <>
              <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mb: 2 }}>
                <StatusChip tone="neutral" label={`${c.evaluated} of ${c.of_firewalls} firewalls evaluated`} />
                <StatusChip tone="neutral" label={`Observed ${c.observed_pct}% · coverage ${c.coverage_pct}%`} />
                <Link href={q({ screen: "compliance", severity: "critical", result: "fail" })} underline="none"><StatusChip tone="bad" label={`${c.critical_deficiencies} critical deficiencies`} /></Link>
                <Link href={q({ screen: "compliance", result: "unavailable" })} underline="none"><StatusChip tone="warn" label={`${c.data_gaps} data gaps`} /></Link>
              </Box>
              {(c.frameworks ?? []).map((f) => (
                <Link key={f.name} href={q({ screen: "compliance", framework: f.name })} underline="none" sx={{ color: "inherit", display: "block", mb: 1.5 }}>
                  <Box sx={{ display: "flex", justifyContent: "space-between", mb: 0.5 }}>
                    <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{f.name}</Typography>
                    <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{f.pass} / {f.total} pass · <b style={{ color: m3.onSurface }}>{f.score_pct}%</b></Typography>
                  </Box>
                  <StackedBar parts={[
                    { label: "Pass", count: f.pass, color: STATUS.good },
                    { label: "Fail", count: f.fail, color: STATUS.critical },
                    { label: "Unavailable (no evidence)", count: f.unavailable, color: STATUS.neutral },
                  ]} />
                </Link>
              ))}
              <Box sx={{ display: "flex", gap: 2, mt: 0.5 }}>
                {[["Pass", STATUS.good], ["Fail", STATUS.critical], ["Unavailable", STATUS.neutral]].map(([l, col]) => (
                  <Box key={l} sx={{ display: "flex", alignItems: "center", gap: 0.5 }}><Box sx={{ width: 10, height: 10, borderRadius: "3px", bgcolor: col }} /><Typography variant="caption">{l}</Typography></Box>
                ))}
              </Box>
            </>
          )}
        </Card>

        <Card sx={CARD}>
          <SectionTitle icon="devices" title="Inventory evidence age" hint={`${age?.of ?? 0} active devices · newest read per device`} />
          <Donut title="Last successful inventory" centerLabel="devices" slices={[
            { label: "under 24 h", display: "under 24 h", count: age?.lt24h ?? 0, color: STATUS.good, href: q({ screen: "inventory", inventory_age: "lt24h" }) },
            { label: "24–72 h", display: "24–72 h", count: age?.h24_72 ?? 0, color: STATUS.warning, href: q({ screen: "inventory", inventory_age: "24h_72h" }) },
            { label: "over 72 h", display: "over 72 h", count: age?.gt72h ?? 0, color: STATUS.critical, href: q({ screen: "inventory", inventory_age: "gt72h" }) },
            { label: "never", display: "never", count: age?.never ?? 0, color: STATUS.neutral, href: q({ screen: "inventory", inventory_age: "never" }) },
          ].filter((s) => s.count > 0)} />
          <Box sx={{ mt: 2, pt: 1.5, borderTop: `1px solid ${m3.outlineVar}` }}>
            <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
              <Link href={q({ screen: "operations", tab: "jobs" })} underline="hover">neXus</Link>: completed 24 h <b>{n.completed_24h ?? "—"}</b> · running <b>{n.running ?? "—"}</b> ·
              oldest running {n.oldest_running_submitted_at ? `${relativeAge(n.oldest_running_submitted_at)} (since submitted)` : "—"} ·
              last inventory: Check Point {relativeAge(n.last_inventory?.check_point)} · Palo Alto {relativeAge(n.last_inventory?.palo_alto)}
            </Typography>
          </Box>
        </Card>
      </Box>

      {/* Morning exceptions */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(420px, 1fr))" }}>
        <ExceptionList icon="operations" title="Failed jobs" total={ex.failed_jobs?.total ?? 0} rows={ex.failed_jobs?.rows ?? []} empty="No failed jobs in the last 24 h."
          head={["TARGET", "JOB", "REASON", "FINISHED"]}
          cells={(r) => [r.label ?? r.device_id.slice(0, 8), r.job_type, (r.terminal_reason ?? "").slice(0, 90), relativeAge(r.finished_at)]}
          rowHref={(r) => q({ screen: "operations", tab: "jobs", q: r.job_id })}
          allHref={q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24" })} />
        <ExceptionList icon="config" title="Configuration changes" total={ex.config_changes?.total ?? 0} rows={ex.config_changes?.rows ?? []} empty="No configuration change in the latest collections."
          head={["DEVICE", "SECTIONS (LATEST)", "COLLECTED"]}
          cells={(r) => [r.label ?? r.device_id.slice(0, 8), r.sections_latest, relativeAge(r.collected_at)]}
          rowHref={(r) => q({ screen: "configuration", device_id: r.device_id })}
          allHref={q({ screen: "configuration", change_state: "changed" })} />
        <ExceptionList icon="config" title="Cluster member DIFF" total={ex.cluster_diff?.total ?? 0} rows={ex.cluster_diff?.rows ?? []}
          empty={ex.cluster_diff?.unknown ? `No member differences in comparable clusters; member comparison not available for ${ex.cluster_diff.unknown} cluster(s).` : "No member differences in comparable clusters."}
          head={["CLUSTER", "SECTIONS", "SETTINGS", "COMPUTED"]}
          cells={(r) => [r.cluster_ref, r.diff_sections.join(", ") || r.diff_section_count, r.diff_setting_count, relativeAge(r.computed_at)]}
          rowHref={(r) => q({ screen: "configuration", cluster_ref: r.cluster_ref })}
          allHref={q({ screen: "configuration", cluster_diff: "present" })} />
      </Box>
    </ScreenRoot>
  );
}
