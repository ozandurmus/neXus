import { useCallback, useEffect, useMemo, useState } from "react";
import type { EChartsOption } from "echarts";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  getPacketCaseCharts,
  getPacketCaseDiagnosis,
  getPacketCaseDns,
  getPacketCaseFieldDiff,
  getPacketCaseFlows,
  getPacketCaseFrames,
  getPacketCaseLadder,
  getPacketCaseWaterfall,
  type PacketDiagnosisView,
  type PacketDnsView,
  type PacketFieldDiffView,
  type PacketFlowItem,
  type PacketFlowPage,
  type PacketLadderView,
  type PacketNexusCorrelation,
  type PacketRef,
  type PacketStreamChartsView,
  type PacketTopology,
  type PacketWaterfallView,
} from "../../auth/adminApi";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { statusWord } from "../../shell/presenters";
import { m3, MONO } from "../../theme/m3Theme";
import { LadderChart, PacketChart, paNum, paTime } from "./PacketCharts";
import { correlationStatusTone } from "./PacketTopologyTab";

export function PacketFlowsTab({
  caseId,
  topology,
  correlation,
  range,
  canRawView,
  selectedFlow,
  onSelectFlow,
  onRangeChange,
  onEvidence,
}: {
  readonly caseId: string;
  readonly topology: PacketTopology;
  readonly correlation?: PacketNexusCorrelation | null;
  readonly range: readonly [number, number] | null;
  readonly canRawView: boolean;
  readonly selectedFlow: PacketFlowItem | null;
  readonly onSelectFlow: (flow: PacketFlowItem | null) => void;
  readonly onRangeChange: (range: readonly [number, number] | null) => void;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const [flows, setFlows] = useState<PacketFlowPage>({ total: 0, items: [] });
  const [flowOffset, setFlowOffset] = useState(0);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState("");
  const [sort, setSort] = useState("bytes");

  useEffect(() => {
    let active = true;
    getPacketCaseFlows(caseId, {
      offset: flowOffset,
      limit: 50,
      search,
      filter,
      sort,
      start: range?.[0] ?? null,
      end: range?.[1] ?? null,
    })
      .then((res) => {
        if (active) setFlows(res);
      })
      .catch(() => {
        if (active) setFlows({ total: 0, items: [] });
      });
    return () => {
      active = false;
    };
  }, [caseId, flowOffset, search, filter, sort, range]);

  return (
    <Stack spacing={2}>
      <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Conversations">
        <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between" sx={{ mb: 1.5, flexWrap: "wrap", gap: 1 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
              Conversations
            </Typography>
            <StatusChip tone="neutral" label={paNum(flows.total, " total", 0)} dense />
          </Stack>
          <Stack direction="row" spacing={1.5} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
            <TextField
              size="small"
              inputProps={{ "aria-label": "Search conversations" }}
              placeholder="Search an address or port…"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setFlowOffset(0);
              }}
            />
            <select
              aria-label="Quick filter"
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value);
                setFlowOffset(0);
              }}
            >
              <option value="">All conversations</option>
              <option value="device_drops">Confirmed device drops</option>
              <option value="impactful">Impactful loss</option>
              <option value="handshakes">Incomplete handshakes</option>
              <option value="resets">Resets observed</option>
            </select>
            <select
              aria-label="Sort conversations"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              <option value="bytes">Sort by bytes</option>
              <option value="max_stall_ms">Sort by recovery wait</option>
              <option value="impactful_loss">Sort by impactful loss</option>
              <option value="retrans_observations">Sort by retransmissions</option>
              <option value="start">Sort by start</option>
              <option value="reset_origin">Sort by reset origin</option>
              <option value="handshake_rtt_ms">Sort by handshake RTT</option>
            </select>
          </Stack>
        </Stack>

        <TableContainer>
          <Table size="small" aria-label="Conversations table">
            <TableHead>
              <TableRow>
                <TableCell>Canonical conversation</TableCell>
                <TableCell>Bytes</TableCell>
                <TableCell>Duration</TableCell>
                <TableCell>Retrans observations</TableCell>
                <TableCell>Reset origin (supported only)</TableCell>
                <TableCell>Handshake RTT</TableCell>
                <TableCell>Confirmed device drops</TableCell>
                <TableCell>Loss classes</TableCell>
                <TableCell>Max recovery</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {flows.items.map((f) => {
                const flowCorr = correlation?.flows.find(
                  (fc) => fc.flow === f.flow || fc.tuple === f.tuple,
                );
                return (
                  <TableRow
                    key={f.flow}
                    hover
                    selected={selectedFlow?.flow === f.flow}
                    sx={{ cursor: "pointer" }}
                    onClick={() => onSelectFlow(f)}
                  >
                    <TableCell>
                      <Box component="code" sx={{ fontFamily: MONO, fontSize: 12 }}>
                        {f.tuple}
                      </Box>
                      {flowCorr && (
                        <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mt: 0.5, flexWrap: "wrap", gap: 0.5 }}>
                          <StatusChip
                            tone={correlationStatusTone(flowCorr.status)}
                            label={`neXus: ${statusWord(flowCorr.status)}`}
                            dense
                          />
                          {flowCorr.status === "MATCH" && (
                            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                              {flowCorr.hostname ?? flowCorr.device_id} · Rule #{flowCorr.rule_number ?? "?"}{" "}
                              {flowCorr.rule_name ?? flowCorr.rule_id} ({flowCorr.action ?? "unknown"})
                            </Typography>
                          )}
                        </Stack>
                      )}
                      {f.matching_unknown_reason && (
                        <Typography variant="caption" display="block" sx={{ color: m3.error }}>
                          Unknown matching: {f.matching_unknown_reason}
                        </Typography>
                      )}
                      <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
                        {f.has_reset ? "RST observed · " : ""}
                        {f.handshake_incomplete ? "Handshake incomplete in captures" : ""}
                      </Typography>
                    </TableCell>
                    <TableCell>{paNum(f.bytes, " B", 0)}</TableCell>
                    <TableCell>
                      {paNum(f.start != null && f.end != null ? f.end - f.start : null, " s")}
                    </TableCell>
                    <TableCell>{f.retrans_observations}</TableCell>
                    <TableCell>{f.reset_origin ?? "unknown / no supported reset"}</TableCell>
                    <TableCell>{paNum(f.handshake_rtt_ms, " ms")}</TableCell>
                    <TableCell sx={{ color: f.confirmed_device_drop ? m3.error : undefined }}>
                      {f.confirmed_device_drop ?? 0}
                    </TableCell>
                    <TableCell>
                      {f.impactful_loss} / {f.recovered_loss} / {f.unrecovered_loss} / {f.capture_miss} /{" "}
                      {f.handshake_blocked} / {f.unknown_events}
                    </TableCell>
                    <TableCell>{paNum(f.max_stall_ms, " ms")}</TableCell>
                  </TableRow>
                );
              })}
              {!flows.items.length && (
                <TableRow>
                  <TableCell colSpan={9}>No conversations</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>

        <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between" sx={{ mt: 1.5 }}>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            {flows.total
              ? `${flowOffset + 1}–${Math.min(flowOffset + 50, flows.total)} of ${flows.total}`
              : "No conversations"}
          </Typography>
          <Stack direction="row" spacing={1}>
            <M3Button
              emphasis="outlined"
              disabled={flowOffset === 0}
              onClick={() => setFlowOffset(Math.max(0, flowOffset - 50))}
            >
              Previous
            </M3Button>
            <M3Button
              emphasis="outlined"
              disabled={flowOffset + 50 >= flows.total}
              onClick={() => setFlowOffset(flowOffset + 50)}
            >
              Next
            </M3Button>
          </Stack>
        </Stack>
      </Card>

      {selectedFlow && (
        <>
          {correlation && (
            <FlowNexusCorrelationPanel
              key={`${selectedFlow.flow}-nexus-correlation`}
              flow={selectedFlow}
              correlation={correlation}
            />
          )}
          <FlowDiagnosisPanel
            key={`${selectedFlow.flow}-diagnosis`}
            caseId={caseId}
            flow={selectedFlow.flow}
            onEvidence={onEvidence}
          />
          <FlowWaterfallPanel
            key={`${selectedFlow.flow}-waterfall`}
            caseId={caseId}
            flow={selectedFlow.flow}
            range={range}
            onEvidence={onEvidence}
          />
          <FlowStreamChartsPanel
            key={`${selectedFlow.flow}-charts`}
            caseId={caseId}
            flow={selectedFlow.flow}
            range={range}
            onRangeChange={onRangeChange}
            onEvidence={onEvidence}
          />
          <FlowLadderPanel
            key={`${selectedFlow.flow}-ladder`}
            caseId={caseId}
            flow={selectedFlow.flow}
            topology={topology}
            range={range}
            canRawView={canRawView}
            onClose={() => onSelectFlow(null)}
            onEvidence={onEvidence}
          />
        </>
      )}
    </Stack>
  );
}

function FlowNexusCorrelationPanel({
  flow,
  correlation,
}: {
  readonly flow: PacketFlowItem;
  readonly correlation: PacketNexusCorrelation;
}) {
  const fl = correlation.flows.find((fc) => fc.flow === flow.flow || fc.tuple === flow.tuple);
  if (!fl) return null;

  return (
    <Card
      sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}
      aria-label="Flow neXus correlation"
    >
      <Stack
        direction="row"
        spacing={1.5}
        alignItems="center"
        justifyContent="space-between"
        sx={{ flexWrap: "wrap", gap: 1, mb: 0.5 }}
      >
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          neXus policy & path correlation
        </Typography>
        <Stack direction="row" spacing={0.75} alignItems="center" sx={{ flexWrap: "wrap", gap: 0.75 }}>
          <StatusChip tone={correlationStatusTone(fl.status)} label={statusWord(fl.status)} dense />
          {fl.evidence.map((ev) => (
            <StatusChip key={ev} tone="mem" label={ev} dense />
          ))}
          {fl.policy_stale && <StatusChip tone="bad" label={statusWord("STALE_POLICY")} dense />}
          {fl.policy_incomplete && <StatusChip tone="bad" label={statusWord("INCOMPLETE_POLICY")} dense />}
        </Stack>
      </Stack>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1.5 }}>
        {correlation.evidence_separation_note}
      </Typography>

      <Stack direction={{ xs: "column", md: "row" }} spacing={1.5}>
        <Box
          aria-label="Selected flow capture proves"
          sx={{ flex: 1, p: 1.5, borderRadius: 1, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}
        >
          <Typography variant="caption" sx={{ fontWeight: 600, display: "block" }}>
            The capture proves
          </Typography>
          <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12 }}>
            Pre-NAT 5-tuple: {fl.pre_nat_src}:{fl.pre_nat_sport ?? "*"} → {fl.pre_nat_dst}:
            {fl.pre_nat_dport ?? "*"} ({fl.proto})
          </Typography>
          <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12 }}>
            {fl.nat_confirmed
              ? `Confirmed post-NAT 5-tuple: ${fl.post_nat_src}:${fl.post_nat_sport ?? "*"} → ${fl.post_nat_dst}:${fl.post_nat_dport ?? "*"}`
              : "No confirmed NAT translation"}
          </Typography>
        </Box>

        <Box
          aria-label="Selected flow configured policy says"
          sx={{ flex: 1, p: 1.5, borderRadius: 1, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}
        >
          <Typography variant="caption" sx={{ fontWeight: 600, display: "block" }}>
            Configured policy says
          </Typography>
          {fl.status === "MATCH" && (
            <>
              <Typography variant="body2">
                Firewall: <strong>{fl.hostname ?? fl.device_id}</strong> · Policy:{" "}
                <strong>{fl.policy_name ?? fl.policy_id}</strong>
              </Typography>
              <Typography variant="body2">
                Matching rule: <strong>#{fl.rule_number ?? "?"} {fl.rule_name ?? fl.rule_id}</strong> · Action:{" "}
                <strong>{fl.action ?? "unknown"}</strong>
              </Typography>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block" }}>
                Interfaces & zones: {fl.ingress_interface_name ?? "unknown"} ({fl.source_zone ?? "unassigned"}) →{" "}
                {fl.egress_interface_name ?? "unknown"} ({fl.destination_zone ?? "unassigned"})
              </Typography>
            </>
          )}
          {fl.status === "AMBIGUOUS" && (
            <>
              <Typography variant="body2" sx={{ mb: 0.5 }}>
                {fl.reason}
              </Typography>
              <Stack spacing={0.5} component="ul" sx={{ m: 0, pl: 2 }}>
                {fl.candidates.map((cand, idx) => (
                  <Typography key={`${cand.device_id}-${cand.rule_id ?? idx}`} component="li" variant="caption">
                    {cand.hostname ?? cand.device_id} · Policy {cand.policy_name ?? cand.policy_id} · Rule #
                    {cand.rule_number ?? "?"} {cand.rule_name ?? cand.rule_id} ({cand.action ?? "unknown"})
                  </Typography>
                ))}
              </Stack>
            </>
          )}
          {(fl.status === "NOT_EVALUABLE" || fl.status === "NO_MATCH") && (
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              {fl.reason}
            </Typography>
          )}
        </Box>
      </Stack>
    </Card>
  );
}

function FlowDiagnosisPanel({
  caseId,
  flow,
  onEvidence,
}: {
  readonly caseId: string;
  readonly flow: string;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const [result, setResult] = useState<PacketDiagnosisView | null>(null);

  useEffect(() => {
    let active = true;
    getPacketCaseDiagnosis(caseId, flow)
      .then((res) => {
        if (active) setResult(res);
      })
      .catch(() => {
        if (active) setResult(null);
      });
    return () => {
      active = false;
    };
  }, [caseId, flow]);

  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Flow diagnosis">
      <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
        Network or application?
      </Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }}>
        Per TCP leg and data direction. A hypothesis needs independent evidence; one counter is not a cause.
      </Typography>
      {result?.reason && <Typography variant="body2">{result.reason}</Typography>}
      <Stack spacing={1.5}>
        {result?.items.map((d, i) => (
          <Box
            key={i}
            sx={{ p: 1.5, borderRadius: 1, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}
          >
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                {d.direction} · {d.classification}
              </Typography>
              <M3Button
                emphasis="text"
                disabled={!d.evidence.length}
                onClick={() => onEvidence(d.evidence)}
              >
                Open diagnosis evidence
              </M3Button>
            </Stack>
            <Typography variant="body2" sx={{ mt: 0.5 }}>
              {d.reason}
            </Typography>
            <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, mt: 0.5 }}>
              Scaled window (max): {paNum(d.metrics.max_receive_window, " B")} · flight (max):{" "}
              {paNum(d.metrics.max_bytes_in_flight, " B")} · zero/full/shrink:{" "}
              {d.metrics.zero_windows}/{d.metrics.window_full}/{d.metrics.window_shrinks}
            </Typography>
            <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
              Receiver ACK p95: {paNum(d.metrics.ack_delay_p95_ms, " ms")} · path p95:{" "}
              {paNum(d.metrics.path_p95_ms, " ms")} · visible server service:{" "}
              {paNum(d.metrics.service_ms, " ms")} · supported loss events: {d.metrics.loss_count}
            </Typography>
          </Box>
        ))}
      </Stack>
      {result?.retransmissions && (
        <Box sx={{ mt: 2 }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>
            Retransmission breakdown (data direction)
          </Typography>
          <TableContainer>
            <Table size="small" aria-label="Retransmission breakdown">
              <TableHead>
                <TableRow>
                  <TableCell>Point</TableCell>
                  <TableCell>Direction</TableCell>
                  <TableCell>RTO-compatible</TableCell>
                  <TableCell>Fast</TableCell>
                  <TableCell>Spurious</TableCell>
                  <TableCell>SACK-driven</TableCell>
                  <TableCell>Unknown</TableCell>
                  <TableCell>Duplicate ACKs</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {result.retransmissions.points.map((p, i) => (
                  <TableRow key={i}>
                    <TableCell>{p.point}</TableCell>
                    <TableCell>{p.direction}</TableCell>
                    <TableCell>{paNum(p.rto, "", 0)}</TableCell>
                    <TableCell>{paNum(p.fast, "", 0)}</TableCell>
                    <TableCell>{paNum(p.spurious, "", 0)}</TableCell>
                    <TableCell>{paNum(p.sack, "", 0)}</TableCell>
                    <TableCell>{paNum(p.unknown, "", 0)}</TableCell>
                    <TableCell>{paNum(p.duplicate_acks, "", 0)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <Typography variant="subtitle2" sx={{ fontWeight: 600, mt: 1.5 }}>
            First observed reordering hop
          </Typography>
          {!result.retransmissions.reordering.length ? (
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
              No supported cross-hop arrival-order reversal.
            </Typography>
          ) : (
            <Stack spacing={0.5} sx={{ mt: 0.5 }}>
              {result.retransmissions.reordering.map((r, i) => (
                <M3Button key={i} emphasis="text" onClick={() => onEvidence(r.evidence)}>
                  {r.location} · {r.hop}: {r.reason}
                </M3Button>
              ))}
            </Stack>
          )}
        </Box>
      )}
    </Card>
  );
}

function FlowWaterfallPanel({
  caseId,
  flow,
  range,
  onEvidence,
}: {
  readonly caseId: string;
  readonly flow: string;
  readonly range: readonly [number, number] | null;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const [data, setData] = useState<PacketWaterfallView | null>(null);
  const [requestIndex, setRequestIndex] = useState(0);

  useEffect(() => {
    setRequestIndex(0);
  }, [flow, range]);

  useEffect(() => {
    let active = true;
    getPacketCaseWaterfall(caseId, {
      flow,
      request_index: requestIndex,
      start: range?.[0] ?? null,
      end: range?.[1] ?? null,
    })
      .then((res) => {
        if (active) setData(res);
      })
      .catch(() => {
        if (active) setData(null);
      });
    return () => {
      active = false;
    };
  }, [caseId, flow, requestIndex, range]);

  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Flow waterfall">
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ flexWrap: "wrap", gap: 1, mb: 1 }}>
        <Box>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Where did the time go?
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            Handshake and cleartext HTTP/1.x. Every link and device dwell uses frame evidence.
          </Typography>
        </Box>
        {!!data?.requests.length && (
          <label>
            HTTP request{" "}
            <select
              aria-label="Waterfall HTTP request"
              value={requestIndex}
              onChange={(e) => setRequestIndex(Number(e.target.value))}
            >
              {data.requests.map((r) => (
                <option key={r.index} value={r.index}>
                  {r.index + 1}. {r.label}
                </option>
              ))}
            </select>
          </label>
        )}
      </Stack>
      {data?.reason && <Typography variant="body2">{data.reason}</Typography>}
      <Stack spacing={2}>
        {data?.items.map((item) => (
          <Box key={item.kind}>
            <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>
              {item.title}
            </Typography>
            <PacketChart
              height={Math.max(200, item.bars.length * 30 + 60)}
              ariaLabel={`Waterfall chart: ${item.title}`}
              option={{
                animation: false,
                grid: { left: 260, right: 30, top: 20, bottom: 40 },
                xAxis: { type: "value", name: "Elapsed ms", min: 0 },
                yAxis: {
                  type: "category",
                  inverse: true,
                  data: item.bars.map((b) => b.label + (b.reason ? " · unknown" : "")),
                },
                series: [
                  {
                    type: "bar",
                    stack: "time",
                    silent: true,
                    itemStyle: { color: "transparent" },
                    data: item.bars.map((b) => b.start_ms),
                  },
                  {
                    type: "bar",
                    stack: "time",
                    barMinHeight: 2,
                    data: item.bars.map((b) => ({
                      value: b.duration_ms,
                      itemStyle: {
                        color: b.kind === "server_processing" ? "#c98a35" : "#168a84",
                      },
                    })),
                  },
                ],
              }}
              onClick={(p) => {
                const idx = (p as { dataIndex?: number })?.dataIndex;
                if (idx != null && item.bars[idx]) onEvidence(item.bars[idx].evidence);
              }}
            />
            <TableContainer>
              <Table size="small" aria-label={`Waterfall stages: ${item.title}`}>
                <TableHead>
                  <TableRow>
                    <TableCell>Stage / evidence</TableCell>
                    <TableCell>Duration</TableCell>
                    <TableCell>Clock uncertainty</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {item.bars.map((b, i) => (
                    <TableRow key={i}>
                      <TableCell>
                        <M3Button
                          emphasis="text"
                          disabled={!b.evidence.length}
                          onClick={() => onEvidence(b.evidence)}
                        >
                          {b.label}
                        </M3Button>
                        {b.reason && (
                          <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar }}>
                            {b.reason}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>{paNum(b.duration_ms, " ms")}</TableCell>
                      <TableCell title={b.note}>±{paNum(b.uncertainty_ms, " ms")}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>
        ))}
      </Stack>
    </Card>
  );
}

function FlowStreamChartsPanel({
  caseId,
  flow,
  range,
  onRangeChange,
  onEvidence,
}: {
  readonly caseId: string;
  readonly flow: string;
  readonly range: readonly [number, number] | null;
  readonly onRangeChange: (range: readonly [number, number] | null) => void;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const [point, setPoint] = useState("");
  const [direction, setDirection] = useState("forward");
  const [localRange, setLocalRange] = useState<readonly [number, number] | null>(null);
  const [result, setResult] = useState<PacketStreamChartsView | null>(null);

  useEffect(() => {
    let active = true;
    const activeRange = localRange ?? range;
    getPacketCaseCharts(caseId, {
      flow,
      point: point || undefined,
      direction,
      start: activeRange?.[0] ?? null,
      end: activeRange?.[1] ?? null,
      local: localRange ? true : undefined,
    })
      .then((res) => {
        if (active) setResult(res);
      })
      .catch(() => {
        if (active) setResult(null);
      });
    return () => {
      active = false;
    };
  }, [caseId, flow, point, direction, range, localRange]);

  const handleChartClick = useCallback(
    (p: unknown) => {
      const frames = (p as { data?: { frames?: number[] } })?.data?.frames?.filter(Boolean);
      const targetPoint = point || result?.point;
      if (!frames?.length || !targetPoint) return;
      getPacketCaseFrames(caseId, {
        flow,
        point: targetPoint,
        frames: frames.slice(0, 8),
      })
        .then(onEvidence)
        .catch(() => undefined);
    },
    [caseId, flow, point, result?.point, onEvidence],
  );

  const handleBrush = useCallback(
    (p: unknown) => {
      const areas = (p as { areas?: { coordRange?: number[] }[] })?.areas;
      const r = areas?.[0]?.coordRange;
      const selected =
        r && r.length === 2
          ? ([Math.min(...r) / 1000, Math.max(...r) / 1000] as const)
          : null;
      if (result?.aligned) onRangeChange(selected);
      else setLocalRange(selected);
    },
    [result?.aligned, onRangeChange],
  );

  const options = useMemo<EChartsOption[]>(() => {
    const base: EChartsOption = {
      useUTC: true,
      animation: false,
      grid: { left: 90, right: 35, top: 55, bottom: 75 },
      xAxis: { type: "time", name: "UTC" },
      yAxis: { type: "value" },
      tooltip: { trigger: "item", renderMode: "richText" },
      legend: { top: 0 },
    };
    const packets = result?.packets ?? [];
    const spans = packets
      .filter((p) => p.kind === "data" && p.sequence !== null)
      .flatMap((p) => [
        { value: [p.time * 1000, p.sequence], frames: [p.frame] },
        { value: [p.time * 1000, p.sequence! + p.length], frames: [p.frame] },
        { value: [p.time * 1000, null], frames: [] },
      ]);
    const ack = packets
      .filter((p) => p.kind === "ack" && p.sequence !== null)
      .map((p) => ({ value: [p.time * 1000, p.sequence], frames: [p.frame] }));
    const window = packets
      .filter((p) => p.kind === "ack" && p.window_edge !== null)
      .map((p) => ({ value: [p.time * 1000, p.window_edge], frames: [p.frame] }));
    const retrans = packets
      .filter((p) => p.kind === "data" && p.retransmission && p.sequence !== null)
      .map((p) => ({ value: [p.time * 1000, p.sequence], frames: [p.frame] }));
    const bucketSeries = (
      metric: "rtt_ms" | "throughput_bps",
      frame: "rtt_frame" | "data_frame",
    ) =>
      (result?.buckets ?? []).map((b) => ({
        value: [b.time * 1000, b[metric]],
        frames: b[frame] ? [b[frame]!] : [],
      }));
    return [
      {
        ...base,
        yAxis: { type: "value", name: "Sequence bytes" },
        series: [
          { type: "line", name: "Data byte spans", data: spans, connectNulls: false },
          { type: "line", name: "ACK", step: "end", data: ack },
          { type: "line", name: "Receive-window edge", step: "end", data: window },
          { type: "scatter", name: "Retransmission", symbol: "diamond", data: retrans },
        ],
      },
      {
        ...base,
        yAxis: { type: "value", name: "RTT (ms)" },
        series: [
          {
            name: "ACK RTT (includes receiver ACK delay)",
            type: "line",
            data: bucketSeries("rtt_ms", "rtt_frame"),
          },
        ],
      },
      {
        ...base,
        yAxis: { type: "value", name: "Payload bit/s" },
        series: [
          {
            name: "Observed payload throughput (includes retries)",
            type: "line",
            data: bucketSeries("throughput_bps", "data_frame"),
          },
        ],
      },
    ];
  }, [result]);

  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Flow stream charts">
      <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" sx={{ flexWrap: "wrap", gap: 1, mb: 1 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          TCP sequence, RTT and throughput
        </Typography>
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
          <label>
            Capture point{" "}
            <select
              aria-label="Stream chart capture point"
              value={point || result?.point || ""}
              onChange={(e) => {
                setPoint(e.target.value);
                setLocalRange(null);
              }}
            >
              {result?.points.map((p) => (
                <option key={p.point} value={p.point}>
                  {p.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            Data direction{" "}
            <select
              aria-label="Stream chart direction"
              value={direction}
              onChange={(e) => setDirection(e.target.value)}
            >
              <option value="forward">Forward</option>
              <option value="reverse">Reverse</option>
            </select>
          </label>
          <M3Button
            emphasis="outlined"
            onClick={() => {
              setLocalRange(null);
              onRangeChange(null);
            }}
          >
            Clear chart selection
          </M3Button>
        </Stack>
      </Stack>
      {result?.reason && <Typography variant="body2">{result.reason}</Typography>}
      <Typography variant="caption" display="block" sx={{ color: m3.onSurfaceVar, mb: 1 }}>
        {result?.note} Clock uncertainty:{" "}
        {result?.clock_uncertainty_ms == null
          ? "unknown"
          : `±${result.clock_uncertainty_ms.toFixed(3)} ms`}
        . {result?.sampling_note}
      </Typography>
      {result &&
        !result.reason &&
        options.map((opt, idx) => (
          <PacketChart
            key={idx}
            option={opt}
            height={260}
            ariaLabel={`Stream chart ${idx + 1}`}
            onClick={handleChartClick}
            onBrush={handleBrush}
          />
        ))}
    </Card>
  );
}

function FlowLadderPanel({
  caseId,
  flow,
  topology,
  range,
  canRawView,
  onClose,
  onEvidence,
}: {
  readonly caseId: string;
  readonly flow: string;
  readonly topology: PacketTopology;
  readonly range: readonly [number, number] | null;
  readonly canRawView: boolean;
  readonly onClose: () => void;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const [ladder, setLadder] = useState<PacketLadderView | null>(null);
  const [ladderOffset, setLadderOffset] = useState(0);
  const [fieldDiff, setFieldDiff] = useState<PacketFieldDiffView | null>(null);

  useEffect(() => {
    setLadderOffset(0);
    setFieldDiff(null);
  }, [flow, range]);

  useEffect(() => {
    let active = true;
    getPacketCaseLadder(caseId, {
      flow,
      offset: ladderOffset,
      limit: 100,
      start: range?.[0] ?? null,
      end: range?.[1] ?? null,
    })
      .then((res) => {
        if (active) setLadder(res);
      })
      .catch(() => {
        if (active) setLadder(null);
      });
    return () => {
      active = false;
    };
  }, [caseId, flow, ladderOffset, range]);

  const forwardPoints = topology.forward
    .map((id) => topology.points.find((p) => p.id === id))
    .filter((p): p is NonNullable<typeof p> => Boolean(p));

  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Multi-hop packet ladder">
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
        <Box>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar, textTransform: "uppercase" }}>
            Stream drill-down
          </Typography>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Multi-hop packet ladder
          </Typography>
        </Box>
        <M3Button emphasis="outlined" onClick={onClose}>
          Close ladder
        </M3Button>
      </Stack>

      {ladder?.local_metrics && ladder.local_metrics.length > 0 && (
        <TableContainer sx={{ mb: 1.5 }}>
          <Table size="small" aria-label="Ladder local metrics">
            <TableHead>
              <TableRow>
                <TableCell>Capture point / local tuple</TableCell>
                <TableCell>SYN → SYN/ACK</TableCell>
                <TableCell>SYN/ACK → ACK</TableCell>
                <TableCell>Max RTT</TableCell>
                <TableCell>Zero windows</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {ladder.local_metrics.map((m) => (
                <TableRow key={m.point}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {m.point}
                    </Typography>
                    <Typography variant="caption" sx={{ fontFamily: MONO }}>
                      {m.tuple}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    {paNum(m.handshake?.syn_to_synack_ms, " ms")}
                    {m.handshake && (
                      <Typography variant="caption" display="block">
                        Frames #{m.handshake.syn_frame} → #{m.handshake.synack_frame}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell>{paNum(m.handshake?.synack_to_ack_ms, " ms")}</TableCell>
                  <TableCell>{paNum(m.max_rtt_ms, " ms")}</TableCell>
                  <TableCell>{m.zero_windows}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {ladder?.flow_filters && ladder.flow_filters.length > 0 && (
        <Box sx={{ mb: 1.5 }}>
          <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
            Wireshark flow filters — one per file
          </Typography>
          <Stack spacing={0.75}>
            {ladder.flow_filters.map((f) => (
              <Stack key={f.capture_id} direction="row" spacing={1.5} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {f.file}
                </Typography>
                <Box component="code" sx={{ fontFamily: MONO, fontSize: 12 }}>
                  {f.display_filter}
                </Box>
                <M3Button
                  emphasis="text"
                  onClick={() => {
                    void navigator.clipboard?.writeText(f.display_filter);
                  }}
                >
                  Copy flow filter
                </M3Button>
              </Stack>
            ))}
          </Stack>
        </Box>
      )}

      {fieldDiff && (
        <Card
          sx={{ p: 1.5, mb: 1.5, boxShadow: "none", border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}
          aria-label="Matched packet field differences"
        >
          <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
            Matched packet field differences
          </Typography>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }}>
            {fieldDiff.note}
          </Typography>
          {fieldDiff.items.map((item, idx) => (
            <Box key={idx} sx={{ mb: 1 }}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {item.location}
              </Typography>
              <TableContainer>
                <Table size="small" aria-label={`Field diff: ${item.location}`}>
                  <TableHead>
                    <TableRow>
                      <TableCell>Field</TableCell>
                      <TableCell>Before</TableCell>
                      <TableCell>After</TableCell>
                      <TableCell>Status</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {Object.entries(item.fields).map(([name, field]) => (
                      <TableRow key={name}>
                        <TableCell>{name.replaceAll("_", " ")}</TableCell>
                        <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>
                          {field.before == null ? "unknown / not applicable" : String(field.before)}
                        </TableCell>
                        <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>
                          {field.after == null ? "unknown / not applicable" : String(field.after)}
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" sx={{ fontWeight: 600 }}>
                            {field.status}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              <M3Button emphasis="text" onClick={() => onEvidence(item.evidence)}>
                Evidence frames and filters
              </M3Button>
            </Box>
          ))}
        </Card>
      )}

      {ladder && (
        <>
          <LadderChart
            data={ladder}
            points={forwardPoints.length ? forwardPoints : topology.points}
            onEvidence={onEvidence}
          />
          <TableContainer sx={{ mt: 1.5 }}>
            <Table size="small" aria-label="Ladder packet appearances">
              <TableHead>
                <TableRow>
                  <TableCell>Time</TableCell>
                  <TableCell>Direction</TableCell>
                  <TableCell>Sequence / ACK</TableCell>
                  <TableCell>Length</TableCell>
                  <TableCell>Appearances / field differences</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {ladder.items.map((p) => (
                  <TableRow
                    key={p.packet_key}
                    hover
                    sx={{ cursor: "pointer" }}
                    onClick={() => onEvidence(p.evidence)}
                  >
                    <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>
                      {paTime(p.ts)}{" "}
                      {p.retrans && <StatusChip tone="attn" label="retrans" dense />}
                    </TableCell>
                    <TableCell>{p.direction}</TableCell>
                    <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>
                      {p.seq} / {p.ack}
                    </TableCell>
                    <TableCell>{p.length} B</TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 0.5 }}>
                        <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12 }}>
                          {p.evidence.map((e) => `${e.point} #${e.frame}`).join(" · ")}
                        </Typography>
                        {canRawView && (
                          <Box component="span" onClick={(ev) => ev.stopPropagation()}>
                            <M3Button
                              emphasis="tonal"
                              onClick={() => {
                                getPacketCaseFieldDiff(caseId, p.packet_key)
                                  .then(setFieldDiff)
                                  .catch((err) =>
                                    setFieldDiff({
                                      items: [],
                                      note: String((err as { body?: { reason?: string } })?.body?.reason ?? "Field diff unavailable"),
                                    }),
                                  );
                              }}
                            >
                              Field diff
                            </M3Button>
                          </Box>
                        )}
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between" sx={{ mt: 1.5 }}>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
              {ladder.total
                ? `${ladderOffset + 1}–${Math.min(ladderOffset + 100, ladder.total)} of ${ladder.total} packet identities`
                : "0 packet identities"}
            </Typography>
            <Stack direction="row" spacing={1}>
              <M3Button
                emphasis="outlined"
                disabled={ladderOffset === 0}
                onClick={() => setLadderOffset(Math.max(0, ladderOffset - 100))}
              >
                Previous
              </M3Button>
              <M3Button
                emphasis="outlined"
                disabled={ladderOffset + 100 >= ladder.total}
                onClick={() => setLadderOffset(ladderOffset + 100)}
              >
                Next
              </M3Button>
            </Stack>
          </Stack>
        </>
      )}
    </Card>
  );
}

export function PacketDnsTab({
  caseId,
  range,
  onEvidence,
}: {
  readonly caseId: string;
  readonly range: readonly [number, number] | null;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const [result, setResult] = useState<PacketDnsView | null>(null);
  const [point, setPoint] = useState("");
  const [resolver, setResolver] = useState("");
  const [offset, setOffset] = useState(0);

  useEffect(() => {
    let active = true;
    getPacketCaseDns(caseId, {
      offset,
      limit: 100,
      point: point || undefined,
      resolver: resolver || undefined,
      start: range?.[0] ?? null,
      end: range?.[1] ?? null,
    })
      .then((res) => {
        if (active) setResult(res);
      })
      .catch(() => {
        if (active) setResult(null);
      });
    return () => {
      active = false;
    };
  }, [caseId, point, resolver, offset, range]);

  return (
    <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="DNS analysis">
      <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" sx={{ flexWrap: "wrap", gap: 1, mb: 1 }}>
        <Box>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            DNS: resolver or return path?
          </Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            Same-point query → response turnaround; timeout {paNum(result?.timeout_ms, " ms")}. A timeout is not proof of a resolver fault.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <label>
            Observation point{" "}
            <select
              aria-label="DNS observation point"
              value={point}
              onChange={(e) => {
                setPoint(e.target.value);
                setOffset(0);
              }}
            >
              <option value="">All points (separate populations)</option>
              {result?.points.map((p) => (
                <option key={p.point} value={p.point}>
                  {p.label}
                </option>
              ))}
            </select>
          </label>
          <TextField
            size="small"
            inputProps={{ "aria-label": "DNS resolver filter" }}
            placeholder="All resolvers"
            value={resolver}
            onChange={(e) => {
              setResolver(e.target.value);
              setOffset(0);
            }}
          />
        </Stack>
      </Stack>

      {result?.reason && <Typography variant="body2">{result.reason}</Typography>}

      <TableContainer sx={{ mb: 1.5 }}>
        <Table size="small" aria-label="DNS summary">
          <TableHead>
            <TableRow>
              <TableCell>Point / resolver</TableCell>
              <TableCell>Queries / matchable</TableCell>
              <TableCell>Answered / timed out / unknown</TableCell>
              <TableCell>p50 / p95 / max (ms)</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {result?.summary.map((s, i) => (
              <TableRow key={i}>
                <TableCell>
                  {s.point} / {s.resolver}
                </TableCell>
                <TableCell>
                  {s.queries}/{s.matchable_queries}
                </TableCell>
                <TableCell>
                  {s.answered}/{s.timed_out_queries}/{s.unknown_queries}
                </TableCell>
                <TableCell>
                  {paNum(s.p50_ms)}/{paNum(s.p95_ms)}/{paNum(s.max_ms)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Stack spacing={1}>
        {result?.items.map((q, i) => (
          <Box
            key={i}
            component="button"
            type="button"
            onClick={() => onEvidence(q.evidence)}
            sx={{
              all: "unset",
              cursor: "pointer",
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              p: 1,
              borderRadius: 1,
              border: `1px solid ${m3.outlineVar}`,
              bgcolor: m3.scLow,
            }}
          >
            <Box>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {q.question ?? "unknown question"} · {q.resolver} · {q.status.replaceAll("_", " ")}
              </Typography>
              <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                {q.reason} · {q.location ?? q.point} · {paTime(q.corrected)} · {paNum(q.latency_ms, " ms")}
              </Typography>
            </Box>
            <Typography variant="caption" sx={{ color: m3.primary }}>
              Evidence ↗
            </Typography>
          </Box>
        ))}
      </Stack>
    </Card>
  );
}
