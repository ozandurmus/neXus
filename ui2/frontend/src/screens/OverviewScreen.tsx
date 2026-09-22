import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { EmptyPanel, ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { getOverview, type CountTile, type EvidenceChip, type OverviewView } from "../auth/adminApi";

/**
 * Overview -- an exception-and-evidence screen (OVERVIEW_EXCEPTION_SCREEN_CONTRACT, FROZEN 2026-09-23; design
 * council: Astra + Fable). Every figure is a stored-evidence query from GET /api/v2/overview with its evidence
 * time; UNKNOWN is written as UNKNOWN, never as 0; zero is neutral, never green; every figure opens the screen
 * that lists it, with the filter applied.
 */

export function relativeAge(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return "UNKNOWN";
  const s = Math.max(0, (now.getTime() - new Date(iso).getTime()) / 1000);
  if (s < 90) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 172800) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

const SECTION = { borderRadius: "16px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, p: 2 } as const;

function SectionTitle({ overline, title, right }: { readonly overline: string; readonly title: string; readonly right?: React.ReactNode }) {
  return (
    <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", mb: 1.25, gap: 1, flexWrap: "wrap" }}>
      <Box>
        <Typography variant="overline" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.08em" }}>{overline}</Typography>
        <Typography variant="h6" sx={{ fontWeight: 600, mt: -0.5 }}>{title}</Typography>
      </Box>
      {right}
    </Box>
  );
}

function EvidenceChipView({ label, chip, href }: { readonly label: string; readonly chip: EvidenceChip | undefined; readonly href: string }) {
  const state = chip?.state ?? "READ_FAILED";
  const text = state === "OK" ? relativeAge(chip?.at) : state === "UNKNOWN" ? "UNKNOWN — no stored evidence" : "READ FAILED";
  return (
    <Tooltip title={chip?.at ? new Date(chip.at).toUTCString() : text}>
      <Link href={href} underline="none" sx={{ color: "inherit" }}>
        <Box sx={{ px: 1.5, py: 1, borderRadius: "12px", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow, minWidth: 150 }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em" }}>{label.toUpperCase()}</Typography>
          <Typography variant="body2" sx={{ fontWeight: 600, color: state === "OK" ? m3.onSurface : m3.error }}>{text}</Typography>
        </Box>
      </Link>
    </Tooltip>
  );
}

function Tile({ title, tile, href, subtitle }: { readonly title: string; readonly tile: CountTile | undefined; readonly href: string; readonly subtitle: string }) {
  const unknown = !tile || tile.state !== "OK";
  const attention = !unknown && tile.count > 0;
  return (
    <Link href={href} underline="none" sx={{ color: "inherit", display: "block" }}>
      <Card sx={{ ...SECTION, p: 2, height: "100%", borderColor: attention ? m3.error : m3.outlineVar, "&:hover": { boxShadow: m3.e1 } }}>
        <Typography sx={{ fontSize: 14, fontWeight: 500, color: m3.onSurfaceVar }}>{title}</Typography>
        <Typography variant="h3" sx={{ fontWeight: 600, my: 0.5, color: attention ? m3.error : m3.onSurface }}>
          {unknown ? "UNKNOWN" : tile.of !== undefined ? <>{tile.count}<Typography component="span" sx={{ fontSize: 18, color: m3.onSurfaceVar }}> of {tile.of}</Typography></> : tile.count}
        </Typography>
        <Typography variant="caption" color="text.secondary">{subtitle}</Typography>
      </Card>
    </Link>
  );
}

function ExceptionList<T>({ title, total, rows, empty, head, cells, rowHref, allHref }: {
  readonly title: string; readonly total: number; readonly rows: readonly T[]; readonly empty: string;
  readonly head: readonly string[]; readonly cells: (r: T) => React.ReactNode[]; readonly rowHref: (r: T) => string; readonly allHref: string;
}) {
  return (
    <Card sx={SECTION}>
      <SectionTitle overline="Morning exceptions" title={title} right={total > rows.length ? <Link href={allHref}>Show all ({total})</Link> : null} />
      {rows.length === 0 ? (
        <Typography variant="body2" color="text.secondary">{empty}</Typography>
      ) : (
        <Table size="small">
          <TableHead><TableRow>{head.map((h) => <TableCell key={h} sx={{ fontSize: 11, letterSpacing: "0.06em" }}>{h}</TableCell>)}</TableRow></TableHead>
          <TableBody>
            {rows.map((r, i) => (
              <TableRow key={i} hover sx={{ cursor: "pointer" }} onClick={() => { window.location.href = rowHref(r); }}>
                {cells(r).map((c, j) => <TableCell key={j} sx={{ fontSize: 12.5 }}>{c}</TableCell>)}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Card>
  );
}

const q = (params: Record<string, string>) => `?${new URLSearchParams(params).toString()}`;

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
  const ageTotal = Math.max(1, age.of ?? 0);
  const buckets: Array<[string, number, string, string]> = [
    ["under 24 h", age.lt24h, "lt24h", m3.primary],
    ["24–72 h", age.h24_72, "24h_72h", "#f9a825"],
    ["over 72 h", age.gt72h, "gt72h", m3.error],
    ["never", age.never, "never", m3.outline],
  ];

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Operational posture"
        subtitle={`${data.denominators.active_devices} active devices · ${p.check_point ?? "—"} Check Point · ${p.palo_alto ?? "—"} Palo Alto · ${data.denominators.clusters} clusters · read ${relativeAge(data.generated_at)}`}
        actions={data.masked ? <StatusChip tone="mem" label="aiview · names masked" /> : undefined}
      />

      <Card sx={SECTION}>
        <SectionTitle overline="Section 1" title="Evidence" />
        <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
          <EvidenceChipView label="Inventory" chip={data.evidence.inventory} href={`${q({ screen: "operations", tab: "jobs", job_type: "inventory_collect" })}`} />
          <EvidenceChipView label="Configuration" chip={data.evidence.configuration} href={`${q({ screen: "operations", tab: "jobs", job_type: "configuration_collect" })}`} />
          <EvidenceChipView label="Compliance" chip={data.evidence.compliance} href="?screen=compliance" />
          <EvidenceChipView label="Backup" chip={data.evidence.backup} href={`${q({ screen: "operations", tab: "jobs", job_type: "backup" })}`} />
          <EvidenceChipView label="Jobs" chip={data.evidence.jobs} href={`${q({ screen: "operations", tab: "jobs" })}`} />
          <EvidenceChipView label="Platform facts" chip={data.evidence.platform_facts} href="?screen=inventory" />
        </Box>
      </Card>

      <Box>
        <SectionTitle overline="Section 2" title="Needs attention" />
        <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))" }}>
          <Tile title="Failed jobs, 24 h" tile={a.failed_jobs_24h} subtitle={`of ${a.failed_jobs_24h?.terminal_24h ?? "—"} finished in 24 h`}
            href={q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24" })} />
          <Tile title="Devices without inventory in 24 h" tile={a.stale_inventory} subtitle={`latest inventory ${relativeAge(data.evidence.inventory?.at)}`}
            href={q({ screen: "inventory", inventory_age: "stale" })} />
          <Tile title="Clusters with member DIFF" tile={a.cluster_diff}
            subtitle={a.cluster_diff?.unknown ? `UNKNOWN for ${a.cluster_diff.unknown} cluster(s) without two member reads` : "every cluster comparable"}
            href={q({ screen: "configuration", cluster_diff: "present" })} />
          <Tile title="Configuration changed since previous collection" tile={a.config_changed} subtitle={`latest configuration ${relativeAge(data.evidence.configuration?.at)}`}
            href={q({ screen: "configuration", change_state: "changed" })} />
          <Tile title="Backup targets without an archive" tile={a.backup_missing} subtitle={`latest backup ${relativeAge(data.evidence.backup?.at)}`}
            href={q({ screen: "backups", artefact: "none" })} />
        </Box>
      </Box>

      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(420px, 1fr))" }}>
        <ExceptionList title="Failed jobs" total={ex.failed_jobs?.total ?? 0} rows={ex.failed_jobs?.rows ?? []} empty="No failed jobs in the last 24 h."
          head={["TARGET", "JOB", "REASON", "FINISHED"]}
          cells={(r) => [r.label ?? r.device_id.slice(0, 8), r.job_type, (r.terminal_reason ?? "").slice(0, 90), relativeAge(r.finished_at)]}
          rowHref={(r) => q({ screen: "operations", tab: "jobs", q: r.job_id })}
          allHref={q({ screen: "operations", tab: "jobs", state: "FAILED", since_hours: "24" })} />
        <ExceptionList title="Configuration changes" total={ex.config_changes?.total ?? 0} rows={ex.config_changes?.rows ?? []} empty="No configuration change in the latest collections."
          head={["DEVICE", "SECTIONS (LATEST)", "COLLECTED"]}
          cells={(r) => [r.label ?? r.device_id.slice(0, 8), r.sections_latest, relativeAge(r.collected_at)]}
          rowHref={(r) => q({ screen: "configuration", device_id: r.device_id })}
          allHref={q({ screen: "configuration", change_state: "changed" })} />
        <ExceptionList title="Cluster member DIFF" total={ex.cluster_diff?.total ?? 0} rows={ex.cluster_diff?.rows ?? []}
          empty={ex.cluster_diff?.unknown ? `No member differences in comparable clusters; member comparison not available for ${ex.cluster_diff.unknown} cluster(s).` : "No member differences in comparable clusters."}
          head={["CLUSTER", "SECTIONS", "SETTINGS", "COMPUTED"]}
          cells={(r) => [r.cluster_ref, r.diff_sections.join(", ") || r.diff_section_count, r.diff_setting_count, relativeAge(r.computed_at)]}
          rowHref={(r) => q({ screen: "configuration", cluster_ref: r.cluster_ref })}
          allHref={q({ screen: "configuration", cluster_diff: "present" })} />
      </Box>

      <Card sx={SECTION}>
        <SectionTitle overline="Section 4" title="Inventory evidence age" right={<Typography variant="caption" color="text.secondary">{age.of} active devices</Typography>} />
        <Box sx={{ display: "flex", height: 18, borderRadius: "9px", overflow: "hidden", border: `1px solid ${m3.outlineVar}` }}>
          {buckets.map(([label, count, key, color]) => count > 0 && (
            <Tooltip key={key} title={`${label}: ${count}`}>
              <Link href={q({ screen: "inventory", inventory_age: key })} sx={{ width: `${(100 * count) / ageTotal}%`, bgcolor: color, display: "block" }} />
            </Tooltip>
          ))}
        </Box>
        <Box sx={{ display: "flex", gap: 2, mt: 1, flexWrap: "wrap" }}>
          {buckets.map(([label, count, key, color]) => (
            <Link key={key} href={q({ screen: "inventory", inventory_age: key })} underline="hover" sx={{ color: "inherit", display: "flex", alignItems: "center", gap: 0.75 }}>
              <Box sx={{ width: 10, height: 10, borderRadius: "2px", bgcolor: color }} />
              <Typography variant="body2">{label}: <b>{count}</b></Typography>
            </Link>
          ))}
        </Box>
      </Card>

      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(420px, 1fr))" }}>
        <Card sx={SECTION}>
          <SectionTitle overline="Section 5" title="Compliance" right={<Typography variant="caption" color="text.secondary">evidence {relativeAge(data.evidence.compliance?.at)}</Typography>} />
          {c.state !== "OK" ? (
            <Typography variant="body2" color="text.secondary">UNKNOWN — compliance not yet evaluated. <Link href="?screen=compliance">Open Compliance to evaluate.</Link></Typography>
          ) : (
            <>
              <Typography variant="body2" sx={{ mb: 1 }}>
                <Link href="?screen=compliance">Evaluated firewalls {c.evaluated} of {c.of_firewalls}</Link> · Observed {c.observed_pct}% · Evidence coverage {c.coverage_pct}% ·{" "}
                <Link href={q({ screen: "compliance", severity: "critical", result: "fail" })}>Critical deficiencies {c.critical_deficiencies}</Link> ·{" "}
                <Link href={q({ screen: "compliance", result: "unavailable" })}>Data gaps {c.data_gaps}</Link>
              </Typography>
              <Table size="small">
                <TableHead><TableRow>{["FRAMEWORK", "PASS", "FAIL", "UNAVAILABLE", "PASS / TOTAL"].map((h) => <TableCell key={h} sx={{ fontSize: 11 }}>{h}</TableCell>)}</TableRow></TableHead>
                <TableBody>
                  {(c.frameworks ?? []).map((f) => (
                    <TableRow key={f.name} hover sx={{ cursor: "pointer" }} onClick={() => { window.location.href = q({ screen: "compliance", framework: f.name }); }}>
                      <TableCell sx={{ fontWeight: 600 }}>{f.name}</TableCell>
                      <TableCell>{f.pass}</TableCell>
                      <TableCell>{f.fail}</TableCell>
                      <TableCell>{f.unavailable}</TableCell>
                      <TableCell>{f.pass} / {f.total} ({f.score_pct}%)</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          )}
        </Card>

        <Card sx={SECTION}>
          <SectionTitle overline="Section 6" title="Platform" right={<Typography variant="caption" color="text.secondary">facts {relativeAge(p.evidence_at)}</Typography>} />
          <Typography variant="body2" sx={{ mb: 1 }}>
            Devices {p.devices} · <Link href={q({ screen: "inventory", vendor: "check_point" })}>Check Point {p.check_point}</Link> · <Link href={q({ screen: "inventory", vendor: "palo_alto" })}>Palo Alto {p.palo_alto}</Link> · Clusters {p.clusters}
          </Typography>
          <Typography variant="subtitle2" sx={{ mb: 0.5 }}>Check Point hotfix level</Typography>
          <Table size="small">
            <TableBody>
              {(p.hotfix_levels ?? []).map((h) => {
                const max = Math.max(1, ...(p.hotfix_levels ?? []).map((x) => x.count));
                return (
                  <TableRow key={h.level ?? "unknown"} hover sx={{ cursor: "pointer" }}
                    onClick={() => { window.location.href = q({ screen: "inventory", vendor: "check_point", hotfix_level: h.level ?? "unknown" }); }}>
                    <TableCell sx={{ fontFamily: "monospace", fontSize: 12, width: 220 }}>{h.level ?? "UNKNOWN"}</TableCell>
                    <TableCell><Box sx={{ height: 10, width: `${(100 * h.count) / max}%`, minWidth: 4, bgcolor: h.level ? m3.primary : m3.outline, borderRadius: "3px" }} /></TableCell>
                    <TableCell sx={{ width: 50, fontWeight: 600 }}>{h.count}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Card>
      </Box>

      <Typography variant="caption" color="text.secondary">
        <Link href={q({ screen: "operations", tab: "jobs" })} underline="hover">neXus</Link>: completed 24 h {n.completed_24h ?? "—"} · running {n.running ?? "—"} ·
        oldest running {n.oldest_running_submitted_at ? `${relativeAge(n.oldest_running_submitted_at)} (since submitted)` : "—"} ·
        last inventory: Check Point {relativeAge(n.last_inventory?.check_point)} · Palo Alto {relativeAge(n.last_inventory?.palo_alto)}
      </Typography>
      <Stack />
    </ScreenRoot>
  );
}
