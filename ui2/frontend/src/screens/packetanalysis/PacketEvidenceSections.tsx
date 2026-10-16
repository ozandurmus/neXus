import { useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import MenuItem from "@mui/material/MenuItem";
import Select from "@mui/material/Select";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import {
  comparePacketCase,
  getPacketCaseFindingsExport,
  getPacketCaseReferenceTemplate,
  getPacketCaseSessionEvidence,
  packetCaseExpertReportHref,
  packetCaseExportHref,
  type PacketAnalysisReport,
  type PacketCompareResult,
  type PacketRef,
  type PacketTopology,
} from "../../auth/adminApi";
import { M3Button } from "../../shell/M3Widgets";
import { m3, MONO } from "../../theme/m3Theme";
import { paNum, paTime } from "./PacketCharts";

type OnEvidence = (refs: readonly PacketRef[]) => void;

const cardSx = { p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` } as const;

function SectionCard({ title, hint, children }: { readonly title: string; readonly hint?: ReactNode; readonly children?: ReactNode }) {
  return (
    <Card sx={cardSx} aria-label={title}>
      <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 0.5 }}>{title}</Typography>
      {hint && <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }}>{hint}</Typography>}
      {children}
    </Card>
  );
}

function EvidenceRow({ refs, title, onEvidence, children }: {
  readonly refs: readonly PacketRef[] | undefined; readonly title?: string; readonly onEvidence: OnEvidence; readonly children: ReactNode;
}) {
  return (
    <Box
      component="button"
      type="button"
      title={title}
      onClick={() => onEvidence(refs ?? [])}
      sx={{
        all: "unset", cursor: "pointer", display: "block", width: "100%", boxSizing: "border-box", p: 1, mb: 0.75,
        borderRadius: 1, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow,
      }}
    >
      <Typography variant="body2" component="div">{children}</Typography>
    </Box>
  );
}

function FramesButton({ refs, onEvidence, label = "Frames" }: { readonly refs: readonly PacketRef[] | undefined; readonly onEvidence: OnEvidence; readonly label?: string }) {
  return <M3Button emphasis="text" onClick={() => onEvidence(refs ?? [])}>{label}</M3Button>;
}

function SimpleTable({ label, headers, rows }: { readonly label: string; readonly headers: readonly string[]; readonly rows: readonly (readonly ReactNode[])[] }) {
  return (
    <TableContainer sx={{ maxHeight: 420, mb: 1 }}>
      <Table size="small" stickyHeader aria-label={label}>
        <TableHead>
          <TableRow>{headers.map((h) => <TableCell key={h}>{h}</TableCell>)}</TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r, i) => (
            <TableRow key={i}>{r.map((c, j) => <TableCell key={j}>{c}</TableCell>)}</TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

/** `per_hop_added` is stored as JSON text: [{hop, component, p95_ms}]; unparseable text is shown verbatim. */
function perHopAdded(raw: string | null | undefined): string {
  if (!raw) return "unknown";
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return raw;
    return parsed
      .map((h: { hop?: string; component?: string; p95_ms?: number | null }) => `${h.hop ?? "?"} ${h.component ?? ""} ${paNum(h.p95_ms ?? null)} ms`)
      .join("; ");
  } catch {
    return raw;
  }
}

const pointLabel = (topology: PacketTopology, id: string) => topology.points.find((p) => p.id === id)?.label ?? id;
const short = (v: string | null | undefined) => (v ? v.slice(0, 8) : "unknown");

// ---- cycles, delivery, hold/release, host path (reference OverviewView "Cycles") -----------------------------

export function CyclesSection({ caseId, report, topology, canRawView, range, onEvidence }: {
  readonly caseId: string; readonly report: PacketAnalysisReport; readonly topology: PacketTopology;
  readonly canRawView: boolean; readonly range: readonly [number, number] | null; readonly onEvidence: OnEvidence;
}) {
  const data = report.cycles;
  const visible = (t: number | undefined) => !range || (t !== undefined && t >= range[0] && t < range[1]);
  return (
    <Stack spacing={2}>
      <HoldReleaseSection report={report} topology={topology} range={range} onEvidence={onEvidence} />
      {data && (
        <SectionCard
          title="Opaque TCP cycles"
          hint={<>{data.note} {data.scope}. Stalled ≥ {paNum(data.stall_ms, " ms", 0)}. Top cycles use the selected time range; flow/port and three-period aggregates describe the full saved window.</>}
        >
          {canRawView && (
            <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
              <M3Button emphasis="outlined" href={packetCaseExportHref(caseId, "csv")}>Download CSV tables (ZIP)</M3Button>
              <M3Button emphasis="outlined" href={packetCaseExportHref(caseId, "xlsx")}>Download XLSX workbook</M3Button>
            </Stack>
          )}
          <Typography variant="subtitle2">Per flow / port</Typography>
          <SimpleTable
            label="Cycles per flow and port"
            headers={["Point", "Flow / port", "Cycles", "Stalled", "p95 ms", "Max ms", "Before p95", "Event p95", "Recovery p95", "Per-hop added p95 ms"]}
            rows={(data.flow_ports ?? []).map((r) => [
              pointLabel(topology, r.point), `${short(r.flow)} / ${r.port}`, r.cycles, r.stalled, paNum(r.p95_ms), paNum(r.max_ms),
              paNum(r.before_p95_ms), paNum(r.event_p95_ms), paNum(r.recovery_p95_ms), perHopAdded(r.per_hop_added),
            ])}
          />
          <Typography variant="subtitle2">Before / event / recovery</Typography>
          <SimpleTable
            label="Cycle periods"
            headers={["Point / flow", "Period", "Cycles", "p95 ms", "Reason"]}
            rows={(data.periods ?? []).map((r) => [`${pointLabel(topology, r.point)} / ${short(r.flow)}`, r.period, r.cycles, paNum(r.p95_ms), r.reason ?? ""])}
          />
          <Typography variant="subtitle2">RX / TX added time between adjacent points</Typography>
          <SimpleTable
            label="Cycle added time per boundary"
            headers={["Boundary", "Component", "Added ms ± uncertainty", "Reason"]}
            rows={(data.pairs ?? []).slice(0, 200).map((r) => [r.hop, r.component, `${paNum(r.added_ms)} ± ${paNum(r.uncertainty_ms)}`, r.reason ?? ""])}
          />
          <Typography variant="subtitle2">Top slow cycles — evidence at every observed point</Typography>
          <SimpleTable
            label="Top slow cycles"
            headers={["Cycle / point", "Port", "Period", "Cycle ms", "Local turnaround ms", "Evidence"]}
            rows={(data.top ?? []).filter((r) => visible(r.request_first)).map((r) => [
              `${short(r.cycle_id)} / ${pointLabel(topology, r.point)}`, r.port ?? "", r.period ?? "unknown", paNum(r.cycle_ms), paNum(r.turnaround_ms),
              <Stack key="ev" direction="row" spacing={1} alignItems="center"><FramesButton refs={r.evidence} onEvidence={onEvidence} />{r.reason && <Typography variant="caption">{r.reason}</Typography>}</Stack>,
            ])}
          />
        </SectionCard>
      )}
      {report.delivery && (
        <SectionCard title="Per-second delay versus drop, by direction" hint={report.delivery.note}>
          <SimpleTable
            label="Delivery series"
            headers={["Time / boundary", "Direction", "Delayed", "Dropped", "Capture miss / unknown", "Delay / recovery ms"]}
            rows={report.delivery.series.slice(0, 1000).map((r) => [
              `${paTime(r.start)} / ${pointLabel(topology, r.point_a)} → ${pointLabel(topology, r.point_b)}`, r.direction, r.delayed, r.dropped,
              `${r.capture_misses} / ${r.unknown}`, `${paNum(r.max_delay_ms)} / ${paNum(r.max_recovery_ms)}`,
            ])}
          />
        </SectionCard>
      )}
      <HostPathSection report={report} topology={topology} onEvidence={onEvidence} />
    </Stack>
  );
}

function HoldReleaseSection({ report, topology, range, onEvidence }: {
  readonly report: PacketAnalysisReport; readonly topology: PacketTopology; readonly range: readonly [number, number] | null; readonly onEvidence: OnEvidence;
}) {
  const all = report.incident?.hold_release ?? [];
  const visible = all.filter((f) => !range || (f.time_range[0] >= range[0] && f.time_range[0] < range[1]));
  return (
    <SectionCard
      title="Hold and release"
      hint="Each capture starts at its own first copy, so spacing is clock-independent. Every occurrence keeps its evidence."
    >
      {visible.length === 0 ? (
        <Typography variant="body2">No supported hold-and-release signature in this interval.</Typography>
      ) : (
        visible.slice(0, 200).map((f) => (
          <EvidenceRow key={f.id} refs={f.evidence} title={f.tooltip} onEvidence={onEvidence}>
            <strong>{pointLabel(topology, f.hop.split("->")[0] ?? f.hop)} · {f.hop} · {paTime(f.time_range[0])}</strong>
            <br />
            {f.summary} Wire {f.metrics.wire_spacing_ms.map((v) => paNum(v)).join("/")} ms → received {f.metrics.receive_spacing_ms.map((v) => paNum(v)).join("/")} ms.
          </EvidenceRow>
        ))
      )}
    </SectionCard>
  );
}

function HostPathSection({ report, topology, onEvidence }: { readonly report: PacketAnalysisReport; readonly topology: PacketTopology; readonly onEvidence: OnEvidence }) {
  const data = report.host_path;
  if (!data?.items?.length) return null;
  return (
    <SectionCard title="Host receive / transmit path" hint={<>{data.note} {data.scope}. This view uses the saved analysis window.</>}>
      {data.items.map((item, i) => (
        <Box key={`${item.hop}-${i}`} sx={{ mb: 2 }}>
          <Typography variant="subtitle2">{item.direction.toUpperCase()} · {item.hop}</Typography>
          {item.headline && <Typography variant="body2">{item.headline}</Typography>}
          <Typography variant="body2">
            Maximum {paNum(item.max_ms)} ms ± {paNum(item.uncertainty_ms)} ms; share of byte-weighted excess boundary delay {paNum(item.delay_share_percent)}%.
          </Typography>
          <Typography variant="body2">
            {item.window.status === "unknown" ? "Observed interval (onset unknown)" : "Event"} {paTime(item.window.start)} — {paTime(item.window.end)}; recovery {item.window.recovery_confirmed ? "confirmed" : "unknown"}.
            {item.window.reason ? ` ${item.window.reason}` : ""}
          </Typography>
          <SimpleTable
            label={`Host path periods ${item.hop}`}
            headers={["Period", "p95 ms", "Max ms", "Matched bytes"]}
            rows={(item.periods ?? []).map((p) => [p.period, paNum(p.p95_ms), paNum(p.max_ms), p.bytes])}
          />
          <FramesButton refs={item.evidence} onEvidence={onEvidence} label="Open boundary evidence" />
          {!!item.flow_details?.length && (
            <SimpleTable
              label={`Host path flows ${item.hop}`}
              headers={["Flow", "Boundary p95 ms", "Max ms", "Evidence"]}
              rows={item.flow_details.map((r) => [
                <Box key="f" component="span" sx={{ fontFamily: MONO }} title={r.reason}>{r.flow}</Box>, paNum(r.p95_ms), paNum(r.max_ms),
                <FramesButton key="e" refs={r.evidence} onEvidence={onEvidence} />,
              ])}
            />
          )}
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            {paNum(item.affected_flow_count, " affected flows", 0)} · {pointLabel(topology, item.hop)}
          </Typography>
        </Box>
      ))}
    </SectionCard>
  );
}

// ---- incident timeline (reference OverviewView "Timeline") -------------------------------------------------

const INCIDENT_METRICS: readonly [string, string][] = [
  ["rx_max_ms", "RX transit max ms"],
  ["tx_max_ms", "TX transit max ms"],
  ["tx_dropped", "TX dropped"],
  ["tx_delayed", "TX delayed"],
  ["retransmissions", "Retransmissions"],
];

export function IncidentSection({ report, range, onEvidence }: {
  readonly report: PacketAnalysisReport; readonly range: readonly [number, number] | null; readonly onEvidence: OnEvidence;
}) {
  const data = report.incident;
  if (!data) return <SectionCard title="Incident timeline"><Typography variant="body2">No incident series computed.</Typography></SectionCard>;
  const rows = (data.timeline ?? []).filter((r) => {
    const start = Number(r.start);
    return !range || (start >= range[0] && start < range[1]);
  });
  return (
    <SectionCard
      title="Incident timeline"
      hint={<>{data.headline} Same-copy RX/TX transit is separate from original-copy drops and sender retransmissions.{data.quality_note ? ` ${data.quality_note}` : ""}</>}
    >
      {data.window && (
        <Typography variant="body2" sx={{ mb: 1 }}>Event window {paTime(data.window.start)} — {paTime(data.window.end)}</Typography>
      )}
      {(data.loss_groups ?? []).map((g, i) => (
        <EvidenceRow key={i} refs={g.evidence} onEvidence={onEvidence}>
          <strong>{g.hop}</strong> · {g.headline} · {Object.entries(g.counts).map(([k, v]) => `${k}: ${v}`).join(", ")}
        </EvidenceRow>
      ))}
      <SimpleTable
        label="Incident timeline"
        headers={["Second", ...INCIDENT_METRICS.map(([, l]) => l), "Evidence"]}
        rows={rows.slice(0, 600).map((r) => [
          paTime(Number(r.start)),
          ...INCIDENT_METRICS.map(([k]) => paNum(r[k] as number | null | undefined)),
          <FramesButton key="e" refs={(r as unknown as { evidence?: readonly PacketRef[] }).evidence} onEvidence={onEvidence} />,
        ])}
      />
    </SectionCard>
  );
}

// ---- sessions / security / microbursts (reference "Session and microburst details") -----------------------

export function SessionsSection({ caseId, report, onEvidence }: { readonly caseId: string; readonly report: PacketAnalysisReport; readonly onEvidence: OnEvidence }) {
  const data = report.sessions;
  const [point, setPoint] = useState("");
  const [error, setError] = useState("");
  if (!data) return null;
  const selected = point || data.points[0]?.point || "";
  const openBucket = async (b: { point: string; start: number; end: number }) => {
    try {
      const res = await getPacketCaseSessionEvidence(caseId, { point: b.point, start: b.start, end: b.end });
      setError("");
      onEvidence(res?.evidence ?? []);
    } catch (e) {
      const body = (e as { body?: { reason?: string } } | undefined)?.body;
      setError(typeof body?.reason === "string" ? body.reason : "Bucket evidence unavailable");
    }
  };
  return (
    <SectionCard title="TCP lifecycle and security heuristics" hint={data.note}>
      <Typography variant="body2" sx={{ mb: 1 }}>{data.scope}. Half-open observation timeout: {data.timeout_seconds} s.</Typography>
      {report.security && (
        <Box sx={{ mb: 1 }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{report.security.note}</Typography>
          {(report.security.quality ?? []).map((q, i) => (
            <Typography key={`${q.point}-${q.status}-${i}`} variant="caption" display="block">{q.point} · {q.status}: {q.reason}</Typography>
          ))}
        </Box>
      )}
      <SimpleTable
        label="TCP lifecycle per point"
        headers={["Point", "SYN sessions", "SYN/ACK", "Established", "FIN/RST closure"]}
        rows={data.points.map((p) => [
          <Stack key="p" direction="row" spacing={1} alignItems="center" title={p.tooltip}>
            <M3Button emphasis="text" onClick={() => onEvidence(p.evidence)}>{p.label}</M3Button>
            <Typography variant="caption">({p.status})</Typography>
          </Stack>,
          paNum(p.syn_sent, "", 0), paNum(p.synack_seen, "", 0), paNum(p.established, "", 0), paNum(p.closed, "", 0),
        ])}
      />
      {error && <Alert severity="warning" sx={{ mb: 1 }}>{error}</Alert>}
      <Typography variant="subtitle2">Half-open occupancy over time</Typography>
      <Select size="small" value={selected} onChange={(e) => setPoint(String(e.target.value))} aria-label="Lifecycle capture point" sx={{ mb: 1, minWidth: 220 }}>
        {data.points.map((p) => <MenuItem key={p.point} value={p.point}>{p.label}</MenuItem>)}
      </Select>
      <SimpleTable
        label="Half-open occupancy"
        headers={["Bucket", "Coverage", "New SYN", "SYN/ACK", "Established", "Closed", "Half-open at end"]}
        rows={data.buckets.filter((b) => b.point === selected).map((b) => [
          <M3Button key="b" emphasis="text" onClick={() => void openBucket(b)}>{paTime(b.start)}</M3Button>,
          b.coverage, paNum(b.syns, "", 0), paNum(b.synacks, "", 0), paNum(b.established, "", 0), paNum(b.closed, "", 0), paNum(b.half_open, "", 0),
        ])}
      />
      <Typography variant="subtitle2">
        Supported failed handshake stopping hops ({data.failed_handshakes.length} of {data.failed_handshake_count} shown)
      </Typography>
      {data.failed_handshakes.map((f, i) => (
        <EvidenceRow key={i} refs={f.evidence} onEvidence={onEvidence}>
          {f.point_a} → {f.point_b}, {paTime(f.time)}: {f.reason}
        </EvidenceRow>
      ))}
    </SectionCard>
  );
}

export function MicroburstsSection({ report, onEvidence }: { readonly report: PacketAnalysisReport; readonly onEvidence: OnEvidence }) {
  const data = report.microbursts;
  if (!data) return null;
  return (
    <SectionCard title="Capture microbursts" hint={data.note}>
      <Typography variant="body2" sx={{ mb: 1 }}>Rolling window: {paNum(data.window_ms, " ms")}. {data.total} episodes; {data.items.length} shown.</Typography>
      <SimpleTable
        label="Microbursts per point"
        headers={["Point", "Peak observed Mbps", "Baseline / threshold Mbps", "Bursts", "Max duration ms", "Frequency Hz"]}
        rows={data.points.map((p) => [
          <M3Button key="p" emphasis="text" onClick={() => onEvidence(p.evidence)}>{p.label}</M3Button>,
          paNum(p.peak_observed_mbps), `${paNum(p.baseline_mbps)} / ${paNum(p.threshold_mbps)}`, paNum(p.burst_count, "", 0),
          paNum(p.max_duration_ms), paNum(p.frequency_hz),
        ])}
      />
      {data.items.map((b, i) => (
        <EvidenceRow key={i} refs={b.evidence} title={b.tooltip} onEvidence={onEvidence}>
          <strong>{b.point} · {paNum(b.peak_mbps, " Mbps")}</strong> · {paTime(b.corrected_start ?? b.start)}
          {b.corrected_start === null ? " (local unaligned capture time)" : ""} · {paNum(b.duration_ms, " ms")} · minimum inter-arrival {paNum(b.min_interarrival_us, " μs")}
          <br />
          {b.loss_associations.length === 0
            ? "No supported nearby loss onset association; cause unknown."
            : b.loss_associations.map((a) => `${a.segment}: ${a.reason}. Timing order: ${a.ordering}.`).join(" ")}
        </EvidenceRow>
      ))}
    </SectionCard>
  );
}

// ---- protocol, translation and onset details (reference ProtocolDiagnostics + ProxyEvidence) ---------------

export function ProtocolDiagnosticsSection({ report, topology, onEvidence }: {
  readonly report: PacketAnalysisReport; readonly topology: PacketTopology; readonly onEvidence: OnEvidence;
}) {
  const unknownVendor = (report.vendor_device_events ?? []).filter((e) => e.status === "unknown");
  const proxies = report.proxies;
  const decoderLimits = (report.application_status ?? []).filter((s) => s.reason);
  const segLabel = (id: string) => report.segments.find((s) => s.id === id)?.label ?? id;
  return (
    <Stack spacing={2}>
      {!!report.confirmed_device_drops?.length && (
        <SectionCard title="Confirmed device drops" hint="Positive vendor-stage evidence, not an inferred device cause.">
          {report.confirmed_device_drops.map((d) => (
            <Typography key={d.device + d.stage} variant="body2">{d.device} · {d.stage}: {d.count}</Typography>
          ))}
        </SectionCard>
      )}
      {!!report.sequence_translations?.length && (
        <SectionCard title="Sequence translations">
          {report.sequence_translations.map((m, i) => (
            <EvidenceRow key={i} refs={m.evidence} onEvidence={onEvidence}>
              {m.ingress} → {m.egress} · flow {short(m.flow)}:{" "}
              {m.status === "learned"
                ? `forward SEQ offset ${m.boundary_forward_offset}, ACK offset ${m.boundary_reverse_offset} (mod 2³²); ${m.samples} anchors`
                : `unknown — ${m.reason}`}
            </EvidenceRow>
          ))}
        </SectionCard>
      )}
      {unknownVendor.length > 0 && (
        <SectionCard title="Inspection evidence limitations">
          {unknownVendor.slice(0, 10).map((e, i) => (
            <EvidenceRow key={i} refs={e.evidence} onEvidence={onEvidence}>{e.device} · {e.stage}: {e.reason}</EvidenceRow>
          ))}
        </SectionCard>
      )}
      {decoderLimits.length > 0 && (
        <SectionCard title="Application decoder limitations">
          {decoderLimits.map((s) => <Typography key={s.point} variant="body2">{s.point} · unknown: {s.reason}</Typography>)}
        </SectionCard>
      )}
      {!!proxies?.transactions.length && (
        <SectionCard title="Full-proxy requests and independent TCP legs" hint={proxies.note}>
          <Typography variant="body2" sx={{ mb: 1 }}>
            {proxies.transaction_count} pairing records.{" "}
            {proxies.transaction_count > proxies.transactions.length && "The first 200 are shown; all pairings are stored with the case."}
          </Typography>
          {proxies.transactions.map((p, i) => (
            <EvidenceRow key={i} refs={p.evidence} onEvidence={onEvidence} title={p.reason ?? "One-to-one request evidence; timing only breaks bounded ties"}>
              <strong>{p.device} · {p.request || p.kind}</strong>{" "}
              {p.status === "matched" ? `Request paired (${p.kind === "tls_setup" ? "TLS setup only" : "HTTP/1.x"})` : `unknown: ${p.reason}`}
              {p.kind === "http" && p.status === "matched" && (
                <>
                  <br />Request dwell {paNum(p.request_dwell?.duration_ms ?? null, " ms")} ± {paNum(p.request_dwell?.uncertainty_ms ?? null, " ms")}
                  {p.request_dwell?.reason ? ` · ${p.request_dwell.reason}` : ""}
                  <br />Response dwell {paNum(p.response_dwell?.duration_ms ?? null, " ms")} ± {paNum(p.response_dwell?.uncertainty_ms ?? null, " ms")}
                  {p.response_dwell?.reason ? ` · ${p.response_dwell.reason}` : ""}
                </>
              )}
              <br /><small>Client leg {p.client_flow || "unknown"} / backend leg {p.server_flow || "unknown"}.</small>
            </EvidenceRow>
          ))}
          <Typography variant="subtitle2">Per-point leg classification</Typography>
          <SimpleTable
            label="Proxy legs"
            headers={["Point / TCP leg", "Loss classes", "Retransmissions", "Resets"]}
            rows={(proxies.legs ?? []).map((l) => [
              <span key="l">{l.point}<br /><small>{l.flow}</small></span>,
              Object.entries(l.loss_by_class).map(([k, v]) => `${k}: ${v}`).join(", ") || "None classified; see segment coverage",
              l.retransmissions, l.resets,
            ])}
          />
          {(proxies.reset_notes ?? []).map((n, i) => (
            <EvidenceRow key={`reset-${i}`} refs={n.evidence} onEvidence={onEvidence}>{n.device} · reset origin unknown: {n.reason}</EvidenceRow>
          ))}
        </SectionCard>
      )}
      {!!report.tls?.length && (
        <SectionCard title="TLS interception evidence" hint="Compared per visible handshake. An absent or encrypted chain remains unknown.">
          {report.tls.map((n, i) => (
            <EvidenceRow key={i} refs={n.evidence} title={n.reason} onEvidence={onEvidence}>
              {n.device || n.point} · {n.sni} · {n.status}: {n.reason}
            </EvidenceRow>
          ))}
        </SectionCard>
      )}
      {!!report.f5?.pairs.length && (
        <SectionCard
          title="F5 connection pairs and request forwarding"
          hint={<>{report.f5.note} Client/server TCP legs remain separate; forwarding intervals use tshark-decoded request-bearing frames.</>}
        >
          {report.f5.pairs.map((p, i) => (
            <Typography key={i} variant="body2">
              {p.role} · flowid {p.flowid} ↔ peerid {p.peerid} · processor {p.tmm}{p.reason && ` · unknown: ${p.reason}`}
              {p.client_flow && <small> Client conversation {p.client_flow} / server conversation {p.server_flow}</small>}
            </Typography>
          ))}
          {report.f5.requests.map((r, i) => (
            <EvidenceRow key={`req-${i}`} refs={r.evidence} onEvidence={onEvidence}>
              {r.device} · {r.request}: {paNum(r.request_dwell_ms, " ms")} ± {paNum(r.clock_uncertainty_ms, " ms")} <small>{r.reason || r.note}</small>
            </EvidenceRow>
          ))}
          {report.f5.resets.map((r, i) => (
            <EvidenceRow key={`rst-${i}`} refs={r.evidence} onEvidence={onEvidence}>
              {r.device} · {paTime(r.time)} · RST origin (TMM reported): {r.reason}
            </EvidenceRow>
          ))}
        </SectionCard>
      )}
      {!!report.offload_points?.some((p) => p.large_frames > 0) && (
        <SectionCard
          title="Offload / segmentation notes"
          hint="Matching uses TCP sequence coverage, not equality of capture packet counts. Loss counts describe affected upstream frames; missing-byte totals retain partial-frame detail."
        >
          {report.offload_points.filter((p) => p.large_frames > 0).map((p) => (
            <Typography key={p.point} variant="body2"><strong>{pointLabel(topology, p.point)}: {p.large_frames} large TCP frames.</strong> {p.note}</Typography>
          ))}
        </SectionCard>
      )}
      {report.onsets && (
        <SectionCard title="Degradation onset" hint={report.onsets.summary}>
          {Object.entries(report.onsets.directions ?? {}).map(([direction, d]) =>
            d.propagation_order.length > 0 && (
              <Typography key={direction} variant="body2" title={d.caveat} sx={{ color: m3.onSurfaceVar }}>
                Propagation · {direction}:{" "}
                {d.propagation_order
                  .map((g) => `${g.segments.map(segLabel).join(" / ")}${g.segments.length > 1 ? " (order unresolved)" : ""} at ${paTime(g.time)}`)
                  .join(" → ")}
              </Typography>
            ))}
          <Typography variant="subtitle2" sx={{ mt: 1 }}>Onset status and quality notes by segment</Typography>
          {report.segments.map((s) => (
            <Box key={s.id} sx={{ mb: 0.5 }}>
              <Typography variant="body2"><strong>{s.label} · {s.direction}: {s.onset_status ?? "unknown"}</strong></Typography>
              <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
                Quality notes: {s.onset_quality_notes?.capture_misses ?? 0} capture misses; {s.onset_quality_notes?.unknown_events ?? 0} unknown events. These are not counted as network loss.
              </Typography>
              {(s.onset_reasons ?? []).map((r) => <Typography key={r.metric} variant="caption" display="block">{r.metric}: {r.reason}</Typography>)}
              {(s.onset_quality_notes?.reasons ?? []).map((r) => <Typography key={r} variant="caption" display="block">{r}</Typography>)}
            </Box>
          ))}
          {report.onsets.items.map((o, i) => (
            <EvidenceRow key={i} refs={o.evidence} onEvidence={onEvidence}>{o.segment} · {o.explanation}</EvidenceRow>
          ))}
        </SectionCard>
      )}
    </Stack>
  );
}

/** Fragment reassembly and tunnel layer notes (reference CaptureNotes). */
export function CaptureNotesSection({ report }: { readonly report: PacketAnalysisReport }) {
  const fragments = report.fragment_points ?? [];
  const tunnels = report.tunnel_points ?? [];
  if (!fragments.length && !tunnels.length) return null;
  return (
    <SectionCard title="Capture notes">
      {fragments.map((p) => (
        <Typography key={p.point + p.status} variant="body2" sx={{ color: m3.onSurfaceVar }}>
          {p.point}: {p.datagrams} IP datagrams from {p.physical_fragments} fragments — {p.status}.
          {p.status !== "complete" && " Reassembly evidence incomplete or ambiguous; no guessed packet identity."}
        </Typography>
      ))}
      {tunnels.map((p) => (
        <Typography key={p.point} variant="body2" sx={{ color: m3.onSurfaceVar }}>
          {p.point}: tunnel layer {p.layer || "unknown — choose inner or outer in Topology"}; {p.selected} selected, {p.unselected} unselected.
          {p.truncated_inner > 0 && <strong> ERSPAN/inner payload truncation: {p.truncated_inner} observations; full payload unavailable.</strong>}
        </Typography>
      ))}
    </SectionCard>
  );
}

// ---- UDP / media (reference UdpMedia) ------------------------------------------------------------------------

export function UdpMediaSection({ report, onEvidence }: { readonly report: PacketAnalysisReport; readonly onEvidence: OnEvidence }) {
  const { udp, rtp, sip } = report;
  return (
    <Stack spacing={2}>
      <SectionCard title="UDP request / response" hint={udp ? udp.note : "Run analysis first."}>
        {udp && (
          <>
            <Typography variant="body2" sx={{ mb: 1 }}>{udp.scope}. {udp.items.length} of {udp.total} examples shown. DNS has its own view.</Typography>
            <SimpleTable
              label="UDP summary"
              headers={["Point / protocol / peer", "Answered / unanswered", "No response expected / unknown", "p50 / p95 / max ms"]}
              rows={udp.summary.map((s) => [
                `${s.point} · ${s.protocol} · ${s.endpoint}`, `${s.answered} / ${s.unanswered}`, `${s.no_response_expected} / ${s.unknown}`,
                `${paNum(s.p50_ms)} / ${paNum(s.p95_ms)} / ${paNum(s.max_ms)}`,
              ])}
            />
            {udp.items.map((r, i) => (
              <EvidenceRow key={i} refs={r.evidence} title={r.tooltip} onEvidence={onEvidence}>
                <strong>{r.point}: {r.protocol} · {r.status} · {paNum(r.latency_ms, " ms")}</strong> {r.hop || "No supported disappearance hop"} — {r.reason}
              </EvidenceRow>
            ))}
          </>
        )}
      </SectionCard>
      {sip && (
        <SectionCard title="SIP / SDP through devices" hint={sip.note}>
          <Typography variant="body2" sx={{ mb: 1 }}>{sip.scope}; {sip.comparisons.length} of {sip.total} shown.</Typography>
          {sip.comparisons.map((c, i) => (
            <EvidenceRow key={`sip-${i}`} refs={c.evidence} title={c.tooltip} onEvidence={onEvidence}>
              <strong>{c.call_id} · {c.hop}: {c.status}</strong> {c.reason} · media: {c.media_status || "not evaluated"}
              {Object.entries(c.changes).map(([field, v]) => <small key={field}><br />{field}: {String(v.before)} → {String(v.after)}</small>)}
            </EvidenceRow>
          ))}
          {sip.one_way.map((c, i) => (
            <EvidenceRow key={`audio-${i}`} refs={c.evidence} title={c.tooltip} onEvidence={onEvidence}>{c.call_id}: one-way audio · {c.hop} · {c.reason}</EvidenceRow>
          ))}
        </SectionCard>
      )}
      {rtp && (
        <SectionCard title="RTP streams" hint={<>{rtp.note} {rtp.scope}</>}>
          {rtp.streams.map((r, i) => (
            <EvidenceRow key={i} refs={r.evidence} title={rtp.note} onEvidence={onEvidence}>
              <strong>{r.point} · {r.direction} · SSRC {r.ssrc} · flow {r.flow}</strong> {r.packets} packets; gaps {r.gaps}; duplicates {r.duplicates}; reordered {r.reordered}; jitter p95/max {paNum(r.jitter_p95_ms)}/{paNum(r.jitter_max_ms)} ms
            </EvidenceRow>
          ))}
          {rtp.one_way.map((r, i) => (
            <EvidenceRow key={`one-${i}`} refs={r.evidence} onEvidence={onEvidence}>
              {r.point}: only {r.seen_direction} observed; {r.reason}; {r.stopping_hop || "stopping hop unknown"}
            </EvidenceRow>
          ))}
        </SectionCard>
      )}
    </Stack>
  );
}

// ---- exports and reference / run comparison ------------------------------------------------------------------

function saveJson(name: string, value: unknown) {
  if (typeof URL.createObjectURL !== "function") return;
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * Report exports. Raw-view roles download the engine files (HTML, FindingsExport JSON, CSV ZIP, XLSX) directly;
 * every other reader gets the FindingsExport through the masked JSON projection. The expert report workbook is offered
 * to every reader: the server gives raw-view roles the engine values and everyone else the masked projection. A selected
 * interval becomes the report's event window (before / after split at its start).
 */
export function ExportActions({ caseId, canRawView, onError, range = null }: {
  readonly caseId: string;
  readonly canRawView: boolean;
  readonly onError: (msg: string) => void;
  readonly range?: readonly [number, number] | null;
}) {
  const window = range && range[0] < range[1] ? { start: range[0], end: range[1] } : {};
  const expert = (
    <M3Button emphasis="outlined" href={packetCaseExpertReportHref(caseId, window)}>Expert report (XLSX)</M3Button>
  );
  if (canRawView) {
    return (
      <Stack direction="row" spacing={1} aria-label="Report exports">
        <M3Button emphasis="outlined" href={packetCaseExportHref(caseId, "html")}>Export HTML</M3Button>
        <M3Button emphasis="outlined" href={packetCaseExportHref(caseId, "json")}>Export JSON</M3Button>
        {expert}
      </Stack>
    );
  }
  return (
    <Stack direction="row" spacing={1} aria-label="Report exports">
      <M3Button
        emphasis="outlined"
        onClick={async () => {
          try {
            saveJson(`packet-analysis-${caseId.slice(0, 8)}-findings.json`, await getPacketCaseFindingsExport(caseId));
          } catch {
            onError("The findings export could not be prepared.");
          }
        }}
      >
        Export JSON
      </M3Button>
      {expert}
    </Stack>
  );
}

export function ReferenceCompareSection({ caseId }: { readonly caseId: string }) {
  const [reference, setReference] = useState<unknown>(null);
  const [against, setAgainst] = useState<unknown>(null);
  const [result, setResult] = useState<PacketCompareResult | null>(null);
  const [error, setError] = useState("");
  const load = async (file: File | undefined, save: (v: unknown) => void) => {
    if (!file) return;
    try {
      if (file.size > 64 * 1024 * 1024) throw new Error("JSON file exceeds 64 MiB");
      save(JSON.parse(await file.text()));
      setResult(null);
      setError("");
    } catch (e) {
      setError(String(e));
    }
  };
  const reason = (e: unknown) => {
    const body = (e as { body?: { reason?: string } } | undefined)?.body;
    return typeof body?.reason === "string" ? body.reason : "Comparison failed";
  };
  return (
    <SectionCard
      title="Reference and run comparison"
      hint="Comparison of the saved report on the server; nothing is stored. Missing or null metrics are not-computed. Generated timestamps and frame IDs are ignored in run diffs; changed analysis values are retained."
    >
      <Stack direction="row" spacing={2} alignItems="center" sx={{ flexWrap: "wrap", gap: 1, mb: 1 }}>
        <M3Button
          emphasis="outlined"
          onClick={async () => {
            try {
              saveJson("packet-analysis-reference.json", await getPacketCaseReferenceTemplate(caseId));
            } catch (e) {
              setError(reason(e));
            }
          }}
        >
          Export reference template
        </M3Button>
        <label>
          <Typography variant="caption">Reference JSON </Typography>
          <input type="file" accept=".json" aria-label="Reference JSON" onChange={(e) => void load(e.target.files?.[0], setReference)} />
        </label>
        <label>
          <Typography variant="caption">Previous analysis JSON export </Typography>
          <input type="file" accept=".json" aria-label="Previous analysis JSON export" onChange={(e) => void load(e.target.files?.[0], setAgainst)} />
        </label>
        <M3Button
          emphasis="filled"
          disabled={!reference && !against}
          onClick={async () => {
            try {
              const input: { reference?: unknown; against?: unknown } = {};
              if (reference) input.reference = reference;
              if (against) input.against = against;
              setResult(await comparePacketCase(caseId, input));
              setError("");
            } catch (e) {
              setError(reason(e));
            }
          }}
        >
          Compare with current saved report
        </M3Button>
      </Stack>
      {error && <Alert severity="warning" sx={{ mb: 1 }}>{error}</Alert>}
      {result?.items && (
        <>
          {result.counts && (
            <Typography variant="body2" sx={{ fontFamily: MONO }}>{Object.entries(result.counts).map(([k, v]) => `${k}: ${v}`).join(" · ")}</Typography>
          )}
          <SimpleTable
            label="Reference comparison"
            headers={["Item / metric", "Product value", "Expected", "Result"]}
            rows={result.items.map((r) => [
              <span key="d" title={r.reason ?? undefined}>{r.description}<br /><small>{r.metric}</small></span>,
              JSON.stringify(r.value), JSON.stringify(r.expected),
              <span key="s"><strong>{r.status}</strong>{r.reason && <small> {r.reason}</small>}</span>,
            ])}
          />
        </>
      )}
      {result?.diff && (
        <>
          <Typography variant="body2">
            {result.diff.metric_difference_count} metric differences{result.diff.truncated ? " (first 1,000 displayed)" : ""}. {result.diff.note}
          </Typography>
          <Typography variant="subtitle2">Differing findings</Typography>
          <Box component="pre" sx={{ fontFamily: MONO, fontSize: 12, maxHeight: 300, overflow: "auto" }}>{JSON.stringify(result.diff.findings, null, 2)}</Box>
          <Typography variant="subtitle2">Differing metrics</Typography>
          <Box component="pre" sx={{ fontFamily: MONO, fontSize: 12, maxHeight: 300, overflow: "auto" }}>{JSON.stringify(result.diff.metrics, null, 2)}</Box>
        </>
      )}
    </SectionCard>
  );
}
