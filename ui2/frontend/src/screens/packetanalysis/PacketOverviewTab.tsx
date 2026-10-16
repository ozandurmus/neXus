import { useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import {
  getPacketCaseEvents,
  type PacketAnalysisReport,
  type PacketCaptureInventory,
  type PacketEventPageView,
  type PacketFinding,
  type PacketRef,
  type PacketSegment,
  type PacketTopology,
} from "../../auth/adminApi";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import type { Tone } from "../../shell/tone";
import { m3, MONO } from "../../theme/m3Theme";
import { CoverageChart, paNum, paTime } from "./PacketCharts";

const LOSS_TYPES = new Set([
  "impactful_loss",
  "recovered_loss",
  "unrecovered_loss",
  "confirmed_device_drop",
  "handshake_blocked",
  "host_transmit_path_loss",
  "host_receive_path_loss",
]);

export function isChronicLoss(segment: PacketSegment, report?: PacketAnalysisReport): boolean {
  if (segment.chronic_loss) return true;
  const losses =
    report?.findings.filter((f) => f.hop === segment.id && LOSS_TYPES.has(f.type)) ?? [];
  return (
    losses.length > 0 &&
    losses.every((f) => f.chronic_loss || Boolean(f.metrics?.chronic_loss))
  );
}

export function segmentHealth(
  segment: PacketSegment,
  topology: PacketTopology,
  report?: PacketAnalysisReport,
): "clean" | "minor" | "degraded" | "unknown" {
  if (
    segment.reason ||
    segment.max_ms == null ||
    segment.p95_ms == null ||
    segment.loss_percent == null ||
    segment.eligible_ratio == null ||
    segment.eligible_ratio < (topology.min_eligible_ratio ?? 0.5)
  ) {
    return "unknown";
  }
  const chronic = isChronicLoss(segment, report);
  const window = report?.incident?.window;
  const losses =
    report?.findings.filter((f) => f.hop === segment.id && LOSS_TYPES.has(f.type)) ?? [];
  const overlaps = (f: PacketFinding) =>
    !window || (f.time_range[0] < window.end && f.time_range[1] >= window.start);
  const impactful =
    !chronic &&
    losses.some(
      (f) =>
        f.type !== "recovered_loss" &&
        !f.chronic_loss &&
        !f.metrics?.chronic_loss &&
        overlaps(f),
    );
  const count = [
    "impactful_loss",
    "unrecovered_loss",
    "confirmed_device_drop",
    "handshake_blocked",
    "host_transmit_path_loss",
    "host_receive_path_loss",
  ].reduce((n, k) => n + (segment.classes?.[k] ?? 0), 0);
  if (impactful || segment.loss_percent >= (chronic ? 1.0 : 0.1)) return "degraded";
  if (!chronic && count && !losses.length) return window ? "unknown" : "degraded";
  const uncertainty = segment.latency_uncertainty_ms ?? segment.offset_uncertainty_ms ?? 0;
  if (segment.max_ms - uncertainty >= 10 || segment.p95_ms - uncertainty >= 1) return "degraded";
  if (segment.max_ms + uncertainty >= 10 || segment.p95_ms + uncertainty >= 1) return "unknown";
  if (chronic) return "clean";
  return window && losses.length && losses.every((f) => !overlaps(f)) ? "minor" : "clean";
}

export function segmentRefs(report: PacketAnalysisReport, segment: PacketSegment): readonly PacketRef[] {
  const finding = report.findings
    .filter((f) => f.hop === segment.id)
    .flatMap((f) => f.evidence);
  const quality = report.quality
    .filter((q) => q.point === segment.point_a || q.point === segment.point_b)
    .flatMap((q) => q.evidence);
  const clocks = Object.values(report.clocks ?? {}).flatMap((c) => c.evidence);
  return finding.length ? finding : quality.length ? quality : clocks;
}

export function boundaryTitle(hop: string, topology: PacketTopology): string {
  const parts = hop.split(":");
  const a = parts[1] ?? hop;
  const b = parts[2] ?? "";
  const label = (id: string) => topology.points.find((p) => p.id === id)?.label ?? id;
  return b ? `${label(a)} → ${label(b)}` : label(a);
}

const HEALTH_TONE: Record<string, Tone> = {
  clean: "ok",
  minor: "attn",
  degraded: "bad",
  unknown: "neutral",
};

const SEVERITY_TONE: Record<string, Tone> = {
  high: "bad",
  critical: "bad",
  medium: "attn",
  low: "neutral",
};

export function InvestigationResultCard({
  report,
  topology,
  onEvidence,
}: {
  readonly report: PacketAnalysisReport;
  readonly topology: PacketTopology;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const host = report.host_path;
  const items = host?.items ?? [];
  const primary = [...items].sort(
    (a, b) => b.max_ms - b.uncertainty_ms - (a.max_ms - a.uncertainty_ms),
  )[0];
  const window = report.incident?.window;
  const rx = items.find((i) => i.direction === "rx");
  const tx = items.find((i) => i.direction === "tx");
  const evidence = primary?.evidence ?? report.findings[0]?.evidence ?? [];
  const local = host?.local_service?.cycle_aggregate;
  const hostPoints = new Set(
    topology.points.filter((p) => p.host_capture).map((p) => p.id),
  );
  const localRefs = (report.cycles?.top ?? [])
    .filter((r) => hostPoints.has(r.point))
    .flatMap((r) => r.evidence ?? []);
  const clockRefs = Object.values(report.clocks ?? {}).flatMap((c) => c.evidence);
  const clockUnknown = Object.values(report.clocks ?? {}).some(
    (c) => c.offset == null || c.uncertainty == null,
  );
  const uncertainty = Math.max(...items.map((i) => i.uncertainty_ms), 0);
  const clean = report.segments.filter(
    (s) => segmentHealth(s, topology, report) === "clean",
  );
  const unknown = report.segments.filter(
    (s) => segmentHealth(s, topology, report) === "unknown",
  );

  const row = (label: string, value: string, refs: readonly PacketRef[]) => (
    <Stack
      key={label}
      direction="row"
      spacing={2}
      alignItems="center"
      justifyContent="space-between"
      sx={{ py: 0.75, borderBottom: `1px solid ${m3.outlineVar}` }}
    >
      <Box>
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar, fontWeight: 600 }}>
          {label}
        </Typography>
        <Typography variant="body2">{value}</Typography>
      </Box>
      <M3Button
        emphasis="text"
        disabled={!refs.length}
        onClick={() => onEvidence(refs)}
      >
        Evidence ↗
      </M3Button>
    </Stack>
  );

  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Investigation result">
      <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 1, flexWrap: "wrap", gap: 1 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          Investigation result
        </Typography>
        <StatusChip
          tone={report.verdict === "unknown" ? "neutral" : "attn"}
          label={`Case verdict: ${report.verdict}`}
        />
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
          {report.flow_count} conversations · {report.scope}
        </Typography>
      </Stack>
      {row(
        "Where",
        primary
          ? `Delay is added between ${boundaryTitle(primary.hop, topology).replace(" → ", " and ")}`
          : report.findings[0]
            ? `Observed finding at ${boundaryTitle(report.findings[0].hop, topology)}`
            : "No supported degradation localisation",
        evidence,
      )}
      {row(
        "When",
        window
          ? `${paTime(window.start)} — ${paTime(window.end)}`
          : `Onset unknown: ${host?.window_reason ?? primary?.window?.reason ?? "no supported event window"}`,
        evidence,
      )}
      {row(
        "How much",
        `RX max ${paNum(rx?.max_ms)} ms · TX max ${paNum(tx?.max_ms)} ms · ${paNum(report.cycles?.stalled_total, "", 0)} stalled cycles`,
        evidence,
      )}
      {row(
        "Not the cause",
        clean.length
          ? `${clean.map((s) => `${s.label} (${paNum(s.max_ms)} ms, ${paNum(s.loss_percent)}% loss)`).join("; ")}${unknown.length ? ` · ${unknown.length} segment(s) remain unknown` : ""}`
          : `No segment can be marked clean from the available evidence.${unknown.length ? ` ${unknown.length} segment(s) remain unknown.` : ""}`,
        report.segments.flatMap((s) => segmentRefs(report, s)),
      )}
      {row(
        "Server-local p95",
        `${paNum(local?.p95_ms ?? host?.local_service?.p95_ms)} ms`,
        localRefs,
      )}
      {row(
        "Clock confidence",
        clockUnknown
          ? "Unknown for part of the path"
          : `Shared/reference clocks ±${paNum(uncertainty)} ms`,
        clockRefs,
      )}
    </Card>
  );
}

export function PathHealthStrip({
  report,
  topology,
  onInspectPoint,
  onSelectSegment,
}: {
  readonly report: PacketAnalysisReport;
  readonly topology: PacketTopology;
  readonly onInspectPoint: (pointId: string) => void;
  readonly onSelectSegment: (segment: PacketSegment) => void;
}) {
  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Path health">
      <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1 }}>
        Forward path health
      </Typography>
      <Stack direction="row" spacing={1.5} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
        {topology.forward.map((id) => {
          const point = topology.points.find((p) => p.id === id);
          const segment = report.segments.find(
            (s) => s.point_a === id && s.direction === "forward",
          );
          const health = segment ? segmentHealth(segment, topology, report) : "unknown";
          return (
            <Stack key={id} direction="row" spacing={1} alignItems="center">
              <M3Button
                emphasis="outlined"
                onClick={() => onInspectPoint(id)}
              >
                {point?.label ?? id} ({point?.device ?? "point"})
              </M3Button>
              {segment && (
                <M3Button
                  emphasis="tonal"
                  onClick={() => onSelectSegment(segment)}
                >
                  → max {paNum(segment.max_ms)} ms · p95 {paNum(segment.p95_ms)} ms · {paNum(segment.loss_percent)}% loss [{health}]
                </M3Button>
              )}
            </Stack>
          );
        })}
      </Stack>
    </Card>
  );
}

export function RankedFindingsAndSegments({
  caseId,
  report,
  topology,
  findings,
  range,
  onEvidence,
}: {
  readonly caseId: string;
  readonly report: PacketAnalysisReport;
  readonly topology: PacketTopology;
  readonly findings: readonly PacketFinding[];
  readonly range: readonly [number, number] | null;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const [selectedSegment, setSelectedSegment] = useState<PacketSegment | null>(null);
  const [events, setEvents] = useState<PacketEventPageView | null>(null);
  const [eventOffset, setEventOffset] = useState(0);

  const loadSegmentEvents = async (segment: PacketSegment, offset = 0) => {
    setSelectedSegment(segment);
    setEventOffset(offset);
    try {
      const page = await getPacketCaseEvents(caseId, {
        point_a: segment.point_a,
        point_b: segment.point_b,
        direction: segment.direction,
        offset,
        limit: 50,
        start: range?.[0] ?? null,
        end: range?.[1] ?? null,
      });
      setEvents(page);
    } catch {
      setEvents({ total: 0, items: [] });
    }
  };

  const hops = [...new Set(findings.map((f) => f.hop))].slice(0, 8);

  return (
    <Stack spacing={2}>
      <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Ranked findings">
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Ranked findings
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            First observed event · click for frame evidence
          </Typography>
        </Stack>
        {hops.length ? (
          hops.map((hop) => {
            const segment = report.segments.find((s) => s.id === hop);
            return (
              <Box key={hop} sx={{ mb: 2 }}>
                <Typography variant="subtitle2" sx={{ mb: 0.75 }}>
                  {segment?.label ??
                    topology.points.find((p) => p.id === hop.split(":")[1])?.label ??
                    hop}{" "}
                  · {segment?.direction ?? hop.split(":")[0]}
                </Typography>
                <Stack spacing={1}>
                  {findings
                    .filter((f) => f.hop === hop)
                    .map((f) => (
                      <Box
                        key={f.id}
                        component="button"
                        type="button"
                        title={f.tooltip ?? f.evidence_note}
                        onClick={() => onEvidence(f.evidence)}
                        sx={{
                          all: "unset",
                          cursor: "pointer",
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "space-between",
                          gap: 1.5,
                          p: 1.25,
                          borderRadius: 1,
                          border: `1px solid ${m3.outlineVar}`,
                          bgcolor: m3.scLow,
                        }}
                      >
                        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
                          <StatusChip
                            tone={SEVERITY_TONE[f.severity] ?? "neutral"}
                            label={f.type.replaceAll("_", " ")}
                            dense
                          />
                          <Box>
                            <Typography variant="body2" sx={{ fontWeight: 600 }}>
                              {f.headline ?? f.summary}
                            </Typography>
                            {f.downstream_summary && (
                              <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
                                {f.downstream_summary}
                              </Typography>
                            )}
                            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                              {paTime(f.time_range?.[0])} · {f.confidence}
                            </Typography>
                          </Box>
                        </Stack>
                        <Typography variant="body2" sx={{ color: m3.primary }}>
                          Evidence ↗
                        </Typography>
                      </Box>
                    ))}
                </Stack>
              </Box>
            );
          })
        ) : (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            {range
              ? "No supported missing-packet events in the selected interval."
              : "No supported missing-packet events in this analysis."}
          </Typography>
        )}
      </Card>

      <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Hop-by-hop evidence">
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Hop-by-hop evidence
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            Click a segment to inspect every missing appearance
          </Typography>
        </Stack>
        <TableContainer>
          <Table size="small" aria-label="Hop-by-hop segments">
            <TableHead>
              <TableRow>
                <TableCell>Path segment</TableCell>
                <TableCell>Direction</TableCell>
                <TableCell>Loss</TableCell>
                <TableCell>Transit p95</TableCell>
                <TableCell>Matched</TableCell>
                <TableCell>Matchable</TableCell>
                <TableCell>Confidence / limitation</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {report.segments.map((s) => {
                const health = segmentHealth(s, topology, report);
                return (
                  <TableRow
                    key={s.id}
                    hover
                    sx={{ cursor: "pointer" }}
                    onClick={() => void loadSegmentEvents(s, 0)}
                  >
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {s.label}
                        </Typography>
                        <StatusChip tone={HEALTH_TONE[health] ?? "neutral"} label={health} dense />
                      </Stack>
                      {s.loss_suspect && (
                        <Typography variant="caption" display="block" sx={{ color: m3.error }}>
                          {s.classes?.confirmed_device_drop
                            ? "Confirmed device drop — stage evidence"
                            : "Loss suspect — supported disappearance here"}
                        </Typography>
                      )}
                      {s.symptom_note && (
                        <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
                          {s.symptom_note}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>{s.direction}</TableCell>
                    <TableCell>{paNum(s.loss_percent, "%")}</TableCell>
                    <TableCell>{paNum(s.p95_ms, " ms", 3)}</TableCell>
                    <TableCell>
                      {s.location === "device_stage" ? "not applicable" : paNum(s.matched, "", 0)}
                    </TableCell>
                    <TableCell>
                      {s.location === "device_stage"
                        ? "not applicable"
                        : paNum(s.eligible_ratio == null ? null : s.eligible_ratio * 100, "%")}
                    </TableCell>
                    <TableCell>
                      {s.quality_notes?.length ? (
                        <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
                          {s.quality_notes.join("; ")}
                        </Typography>
                      ) : null}
                      <Typography variant="caption">
                        {s.reason ??
                          `Estimated · offset uncertainty ±${paNum(s.latency_uncertainty_ms ?? s.offset_uncertainty_ms, " ms", 3)}`}
                      </Typography>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      {selectedSegment && events && (
        <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Segment events">
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
              {selectedSegment.label} · {selectedSegment.direction} ({events.total} events)
            </Typography>
            <M3Button emphasis="outlined" onClick={() => { setSelectedSegment(null); setEvents(null); }}>
              Close events
            </M3Button>
          </Stack>
          <Stack spacing={1}>
            {events.items.map((e) => (
              <Box
                key={e.id}
                component="button"
                type="button"
                onClick={() => onEvidence(e.evidence)}
                sx={{
                  all: "unset",
                  cursor: "pointer",
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  p: 1,
                  borderRadius: 1,
                  border: `1px solid ${m3.outlineVar}`,
                }}
              >
                <Stack direction="row" spacing={1.5} alignItems="center">
                  <StatusChip tone="attn" label={e.kind.replaceAll("_", " ")} dense />
                  <Box>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {e.reason}
                    </Typography>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      {paTime(e.ts)} · recovery {paNum(e.recovery_ms, " ms")}
                    </Typography>
                  </Box>
                </Stack>
                <Typography variant="caption" sx={{ color: m3.primary }}>
                  Evidence ↗
                </Typography>
              </Box>
            ))}
          </Stack>
          <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mt: 1.5 }}>
            <M3Button
              emphasis="outlined"
              disabled={eventOffset === 0}
              onClick={() => void loadSegmentEvents(selectedSegment, Math.max(0, eventOffset - 50))}
            >
              Previous
            </M3Button>
            <M3Button
              emphasis="outlined"
              disabled={eventOffset + 50 >= events.total}
              onClick={() => void loadSegmentEvents(selectedSegment, eventOffset + 50)}
            >
              Next
            </M3Button>
          </Stack>
        </Card>
      )}
    </Stack>
  );
}

export function CaptureQualitySection({
  captures,
  report,
  topology,
  onEvidence,
}: {
  readonly captures: readonly PacketCaptureInventory[];
  readonly report: PacketAnalysisReport;
  readonly topology: PacketTopology;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  return (
    <Stack spacing={2}>
      <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Capture quality">
        <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1 }}>
          Capture quality comes first
        </Typography>
        <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap", gap: 1.5 }}>
          {report.quality.map((q) => (
            <Box
              key={q.point}
              component="button"
              type="button"
              onClick={() => onEvidence(q.evidence)}
              sx={{
                all: "unset",
                cursor: "pointer",
                p: 1.5,
                borderRadius: 1,
                border: `1px solid ${m3.outlineVar}`,
                bgcolor: m3.scLow,
                minWidth: 210,
              }}
            >
              <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                {topology.points.find((p) => p.id === q.point)?.label ?? q.point}
              </Typography>
              <Typography variant="caption" display="block">
                Drops: {paNum(q.ifdrop, "", 0)} / {paNum(q.osdrop, "", 0)}
              </Typography>
              {q.index_scope && (
                <Typography variant="caption" display="block">
                  {q.index_scope.excluded_flows} flows / {q.index_scope.excluded_frames} frames outside indexing scope
                </Typography>
              )}
              <Typography variant="caption" display="block">
                {paNum(q.excluded, " excluded", 0)}
              </Typography>
              {q.prefilter_excluded !== undefined && (
                <Typography variant="caption" display="block">
                  {paNum(q.prefilter_excluded, " pre-filter excluded", 0)}
                </Typography>
              )}
              <Typography variant="caption" display="block">
                {paNum(q.truncated, " truncated", 0)}
              </Typography>
              <Typography variant="caption" display="block">
                {paNum(q.possible_offload, " large frames", 0)}
              </Typography>
            </Box>
          ))}
        </Stack>
      </Card>

      <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Capture coverage">
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Capture coverage
          </Typography>
          <StatusChip tone="ok" label="CLOCK CORRECTED" dense />
        </Stack>
        <CoverageChart captures={captures} report={report} />
        {!!report.limitations?.length && (
          <Box sx={{ mt: 1.5 }}>
            <Typography variant="subtitle2">Interpretation limits</Typography>
            {report.limitations.map((l) => (
              <Typography key={l} variant="body2" sx={{ color: m3.onSurfaceVar }}>
                • {l}
              </Typography>
            ))}
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
              {report.scope}
            </Typography>
          </Box>
        )}
      </Card>
    </Stack>
  );
}

export function FrameEvidenceModal({
  refs,
  onClose,
}: {
  readonly refs: readonly PacketRef[];
  readonly onClose: () => void;
}) {
  return (
    <Box
      role="dialog"
      aria-modal="true"
      aria-label="Frame evidence"
      sx={{
        position: "fixed",
        inset: 0,
        bgcolor: "rgba(0,0,0,0.45)",
        zIndex: 1300,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        p: 2,
      }}
      onClick={onClose}
    >
      <Card
        sx={{
          p: 2.5,
          maxWidth: 960,
          width: "100%",
          maxHeight: "85vh",
          overflow: "auto",
          border: `1px solid ${m3.outlineVar}`,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Box>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar, textTransform: "uppercase" }}>
              Verify in Wireshark
            </Typography>
            <Typography variant="h6">Frame evidence</Typography>
          </Box>
          <M3Button emphasis="outlined" onClick={onClose}>
            Close
          </M3Button>
        </Stack>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1.5 }}>
          Open the named file in Wireshark and paste its display filter. Times are UTC.
        </Typography>
        <TableContainer>
          <Table size="small" aria-label="Evidence frames">
            <TableHead>
              <TableRow>
                <TableCell>Capture point / file</TableCell>
                <TableCell>Frame</TableCell>
                <TableCell>Observed</TableCell>
                <TableCell>Corrected</TableCell>
                <TableCell>Display filter</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {refs.map((e, i) => (
                <TableRow key={`${e.capture_id}-${e.frame}-${i}`}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {e.point}
                    </Typography>
                    <Typography variant="caption" sx={{ fontFamily: MONO }}>
                      {e.file}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontFamily: MONO }}>
                      #{e.frame}
                    </Typography>
                    {e.sequence_translation && (
                      <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
                        {e.sequence_translation.reason
                          ? `Unknown translation: ${e.sequence_translation.reason}`
                          : `SEQ ${e.sequence_translation.observed_seq} → ${e.sequence_translation.canonical_seq}; ACK ${e.sequence_translation.observed_ack} → ${e.sequence_translation.canonical_ack}`}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>
                    {paTime(e.observed_time)}
                  </TableCell>
                  <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>
                    {paTime(e.corrected_time)}
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 0.5 }}>
                      <Box component="code" sx={{ fontFamily: MONO, fontSize: 12 }}>
                        {e.display_filter}
                      </Box>
                      <M3Button
                        emphasis="text"
                        onClick={() => {
                          void navigator.clipboard?.writeText(e.display_filter);
                        }}
                      >
                        Copy
                      </M3Button>
                    </Stack>
                    {e.flow_filter && (
                      <Typography variant="caption" display="block" sx={{ fontFamily: MONO, color: m3.onSurfaceVar }}>
                        Flow filter: {e.flow_filter}
                      </Typography>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {!refs.length && (
                <TableRow>
                  <TableCell colSpan={5}>No frame evidence available.</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>
    </Box>
  );
}
