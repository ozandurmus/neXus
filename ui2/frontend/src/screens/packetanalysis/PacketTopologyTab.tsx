import { useEffect, useState } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  MarkerType,
  Position,
  addEdge,
  applyNodeChanges,
  applyEdgeChanges,
  type Node,
  type Edge,
  type Connection,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  getPacketCaseTopologyInspect,
  getPacketCaseTopologyOverlay,
  type PacketAnalysisReport,
  type PacketCaptureInventory,
  type PacketFlowItem,
  type PacketNexusCorrelation,
  type PacketNexusCorrelationStatus,
  type PacketRef,
  type PacketTopology,
  type PacketTopologyInspectView,
  type PacketTopologyOverlayView,
  type PacketTopologyPoint,
} from "../../auth/adminApi";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { m3, MONO } from "../../theme/m3Theme";
import type { Tone } from "../../shell/tone";
import { paNum, reverseTuple } from "./PacketCharts";

export function correlationStatusTone(status: PacketNexusCorrelationStatus): Tone {
  switch (status) {
    case "MATCH":
      return "ok";
    case "AMBIGUOUS":
      return "attn";
    case "NOT_EVALUABLE":
      return "warn";
    case "NO_MATCH":
    default:
      return "neutral";
  }
}

export const TOPOLOGY_POINT_KINDS = [
  "Client",
  "Firewall",
  "IPS",
  "Router/Switch",
  "Load Balancer",
  "Proxy",
  "WAF",
  "Server",
  "Generic",
];

export function pathFromEdges(edges: readonly Edge[], direction: string): string[] {
  const selected = edges.filter((e) => e.data?.direction === direction);
  if (!selected.length) return [];
  const next = new Map<string, string>();
  const incoming = new Set<string>();
  for (const e of selected) {
    if (next.has(e.source) || incoming.has(e.target)) {
      throw new Error(
        "A path cannot branch. Each point needs at most one incoming and outgoing arrow per direction.",
      );
    }
    next.set(e.source, e.target);
    incoming.add(e.target);
  }
  const heads = [...next.keys()].filter((x) => !incoming.has(x));
  if (heads.length !== 1) {
    throw new Error("Connect one continuous path without cycles.");
  }
  const path = [heads[0]];
  while (next.has(path[path.length - 1])) {
    const n = next.get(path[path.length - 1])!;
    if (path.includes(n)) throw new Error("Path contains a cycle");
    path.push(n);
  }
  if (path.length !== selected.length + 1) {
    throw new Error("Path contains disconnected arrows");
  }
  return path;
}

export function PacketTopologyTab({
  caseId,
  topology,
  captures,
  report,
  correlation,
  range,
  canEdit,
  initialInspectPoint,
  onTopologyChange,
  onSaveTopology,
  onSelectFlow,
  onEvidence,
  onError,
}: {
  readonly caseId: string;
  readonly topology: PacketTopology;
  readonly captures: readonly PacketCaptureInventory[];
  readonly report: PacketAnalysisReport | null;
  readonly correlation?: PacketNexusCorrelation | null;
  readonly range: readonly [number, number] | null;
  readonly canEdit: boolean;
  readonly initialInspectPoint?: string | null;
  readonly onTopologyChange: (next: PacketTopology) => void;
  readonly onSaveTopology: (next: PacketTopology) => Promise<void>;
  readonly onSelectFlow: (flow: PacketFlowItem) => void;
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
  readonly onError: (msg: string) => void;
}) {
  const [nodes, setNodes] = useState<Node[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [selection, setSelection] = useState<string | null>(initialInspectPoint ?? null);
  const [direction, setDirection] = useState<"forward" | "reverse">("forward");
  const [overlay, setOverlay] = useState<PacketTopologyOverlayView | null>(null);
  const [inspection, setInspection] = useState<PacketTopologyInspectView | null>(null);

  useEffect(() => {
    let active = true;
    getPacketCaseTopologyOverlay(caseId, {
      start: range?.[0] ?? null,
      end: range?.[1] ?? null,
    })
      .then((res) => {
        if (active) setOverlay(res);
      })
      .catch(() => {
        if (active) setOverlay(null);
      });
    return () => {
      active = false;
    };
  }, [caseId, range]);

  const inspectTarget = async (params: { point?: string; segment?: string }) => {
    try {
      const res = await getPacketCaseTopologyInspect(caseId, {
        ...params,
        start: range?.[0] ?? null,
        end: range?.[1] ?? null,
      });
      setInspection(res);
    } catch {
      setInspection(null);
    }
  };

  useEffect(() => {
    if (initialInspectPoint) {
      setSelection(initialInspectPoint);
      void inspectTarget({ point: initialInspectPoint });
    }
  }, [initialInspectPoint]);

  useEffect(() => {
    const deviceDrops = (device: string) =>
      overlay?.nodes.find((n) => n.device === device)?.confirmed_device_drops ?? 0;

    setNodes(
      topology.points.map((p) => {
        const hopCorr = correlation?.hops.find((h) => h.point_id === p.id);
        return {
          id: p.id,
          sourcePosition: Position.Right,
          targetPosition: Position.Left,
          position: { x: p.x, y: p.y },
          data: {
            label: (
              <div>
                <small>
                  {p.kind} · {p.side}
                </small>
                <div>
                  <strong>{p.label}</strong>
                </div>
                {hopCorr && (
                  <small style={{ display: "block", fontWeight: 600 }}>
                    neXus: {hopCorr.status}
                    {hopCorr.status === "MATCH"
                      ? ` · ${hopCorr.hostname ?? hopCorr.device_id} · ${hopCorr.interface_name ?? hopCorr.interface_id}${hopCorr.zone ? ` (${hopCorr.zone})` : ""}`
                      : ""}
                  </small>
                )}
                {overlay?.nodes
                  .filter((n) => n.device === p.device)
                  .map((n) => (
                    <small key={n.device} style={{ display: "block" }}>
                      {n.observations} observations · {n.retrans_observations} retrans ·{" "}
                      {n.supported_loss_events} adjacent supported loss events · {n.findings} findings
                    </small>
                  ))}
                {!!deviceDrops(p.device) && (
                  <small style={{ display: "block", color: m3.error }}>
                    {p.device}: {deviceDrops(p.device)} confirmed device drops (device total)
                  </small>
                )}
                <span style={{ fontSize: 11 }}>
                  {captures.find((c) => c.id === p.capture_id)?.name ?? "Assign a capture"}
                </span>
              </div>
            ),
          },
        };
      }),
    );

    setEdges(
      (["forward", "reverse"] as const).flatMap((dir) => {
        const path = topology[dir] ?? [];
        return path.slice(1).map((target, i) => {
          const source = path[i];
          const seg = report?.segments.find(
            (s) => s.point_a === source && s.point_b === target && s.direction === dir,
          );
          const metric = overlay?.segments.find(
            (s) => s.id === `${dir}:${source}:${target}`,
          );
          const color =
            metric?.classes?.confirmed_device_drop ||
            metric?.classes?.impactful_loss ||
            metric?.classes?.handshake_blocked
              ? "#c44842"
              : metric?.reason
                ? "#b48526"
                : dir === "reverse"
                  ? "#8384b6"
                  : "#168a84";
          return {
            id: `${dir}:${source}:${target}`,
            source,
            target,
            data: { direction: dir },
            label: metric
              ? `${metric.loss_percent == null ? "?" : metric.loss_percent.toFixed(1)}% · ${metric.p95_ms == null ? "?" : metric.p95_ms.toFixed(2)}ms · R${metric.retrans_observations}`
              : seg
                ? `${seg.loss_percent == null ? "?" : seg.loss_percent.toFixed(1)}% · ${seg.p95_ms == null ? "?" : seg.p95_ms.toFixed(2)}ms`
                : dir,
            style: { stroke: color, strokeWidth: 2 },
            markerEnd: { type: MarkerType.ArrowClosed, color },
          };
        });
      }),
    );
  }, [topology, captures, report, overlay, correlation]);

  const selected = topology.points.find((p) => p.id === selection);

  const currentTopology = (): PacketTopology => ({
    ...topology,
    points: topology.points.map((p) => {
      const n = nodes.find((node) => node.id === p.id);
      return { ...p, x: n?.position.x ?? p.x, y: n?.position.y ?? p.y };
    }),
    forward: pathFromEdges(edges, "forward"),
    reverse: pathFromEdges(edges, "reverse"),
  });

  const updatePoint = (patch: Partial<PacketTopologyPoint>) => {
    if (!selected) return;
    const base = currentTopology();
    onTopologyChange({
      ...base,
      points: base.points.map((p) => (p.id === selected.id ? { ...p, ...patch } : p)),
    });
  };

  const addPoint = (kind: string, x = 100 + topology.points.length * 180, y = 80) => {
    const id = "p" + String(topology.points.length + 1);
    const point: PacketTopologyPoint = {
      id,
      label: `${kind} ${topology.points.length + 1}`,
      device: `${kind} ${topology.points.length + 1}`,
      kind,
      side: "both",
      capture_id: captures[0]?.id ?? "",
      interface: null,
      source_cidr: null,
      translation: "none",
      x,
      y,
    };
    const base = currentTopology();
    onTopologyChange({
      ...base,
      points: [...base.points, point],
      forward: [...base.forward, id],
    });
    setSelection(id);
  };

  const connect = (c: Connection) => {
    setEdges((es) =>
      addEdge(
        {
          ...c,
          id: `${direction}:${c.source}:${c.target}`,
          data: { direction },
          label: direction,
          markerEnd: { type: MarkerType.ArrowClosed },
          style: {
            stroke: direction === "forward" ? "#168a84" : "#8384b6",
            strokeWidth: 2,
          },
        },
        es,
      ),
    );
  };

  return (
    <Stack spacing={2}>
      <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Topology workspace">
        <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between" sx={{ flexWrap: "wrap", gap: 1, mb: 1.5 }}>
          <Box>
            <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
              Capture topology & path overlay
            </Typography>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
              Topology overlay: {range ? "selected heatmap interval" : "full analysis window"}.{" "}
              {overlay?.note ?? "Arrows: loss % · p95 added transit (ms) · R retransmission observations."}
            </Typography>
          </Box>
          {canEdit && (
            <Stack direction="row" spacing={1.5} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
              {report?.auto_order_suggestion?.points.length === topology.points.length && (
                <M3Button
                  emphasis="outlined"
                  onClick={() =>
                    onTopologyChange({
                      ...currentTopology(),
                      forward: report.auto_order_suggestion!.points,
                    })
                  }
                >
                  Apply suggested order
                </M3Button>
              )}
              <select
                aria-label="Arrow direction"
                value={direction}
                onChange={(e) => setDirection(e.target.value as "forward" | "reverse")}
              >
                <option value="forward">Draw forward path</option>
                <option value="reverse">Draw return path</option>
              </select>
              <M3Button
                emphasis="filled"
                onClick={() => {
                  try {
                    void onSaveTopology(currentTopology());
                  } catch (e) {
                    onError(e instanceof Error ? e.message : String(e));
                  }
                }}
              >
                Save topology
              </M3Button>
            </Stack>
          )}
        </Stack>

        {canEdit && (
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1, mb: 1.5 }}>
            {TOPOLOGY_POINT_KINDS.map((k) => (
              <M3Button
                key={k}
                emphasis="tonal"
                onClick={() => {
                  try {
                    addPoint(k);
                  } catch (e) {
                    onError(e instanceof Error ? e.message : String(e));
                  }
                }}
              >
                ＋ {k}
              </M3Button>
            ))}
          </Stack>
        )}

        <Box
          aria-label="Topology canvas"
          sx={{
            height: 360,
            width: "100%",
            border: `1px solid ${m3.outlineVar}`,
            borderRadius: 1,
            bgcolor: m3.scLow,
          }}
        >
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={(changes) =>
              setNodes((n) =>
                applyNodeChanges(
                  changes.filter((x) => x.type !== "remove"),
                  n,
                ),
              )
            }
            onEdgesChange={(changes) => setEdges((e) => applyEdgeChanges(changes, e))}
            onConnect={connect}
            onNodeClick={(_, n) => {
              setSelection(n.id);
              void inspectTarget({ point: n.id });
            }}
            onEdgeClick={(_, e) => {
              void inspectTarget({ segment: e.id });
            }}
            fitView
            minZoom={0.15}
          >
            <Background color="#d6dfdf" gap={22} />
            <Controls />
          </ReactFlow>
        </Box>

        <Stack direction="row" spacing={1} sx={{ mt: 1.5, flexWrap: "wrap", gap: 1 }}>
          {topology.points.map((p) => (
            <M3Button
              key={p.id}
              emphasis={selection === p.id ? "filled" : "outlined"}
              onClick={() => {
                setSelection(p.id);
                void inspectTarget({ point: p.id });
              }}
            >
              Inspect {p.label}
            </M3Button>
          ))}
        </Stack>
      </Card>

      {inspection && (
        <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Selected device or arrow">
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Selected device / arrow
          </Typography>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }}>
            {inspection.flows.total} associated conversations
          </Typography>
          <Stack spacing={1} sx={{ mb: 1.5 }}>
            {inspection.findings.map((f) => (
              <Stack key={f.id} direction="row" spacing={1.5} alignItems="center" justifyContent="space-between">
                <Typography variant="body2">{f.summary}</Typography>
                <M3Button emphasis="text" onClick={() => onEvidence(f.evidence)}>
                  Evidence
                </M3Button>
              </Stack>
            ))}
            {!inspection.findings.length && (
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                No findings in this selected range.
              </Typography>
            )}
          </Stack>
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            {inspection.flows.items.map((f) => (
              <M3Button key={f.flow} emphasis="tonal" onClick={() => onSelectFlow(f)}>
                {f.tuple}
              </M3Button>
            ))}
          </Stack>
        </Card>
      )}

      {selected && (
        <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Selected point inspector">
          <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1 }}>
            Selected point: {selected.label}
          </Typography>
          <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 1.5 }}>
            <TextField
              label="Point label"
              size="small"
              value={selected.label}
              disabled={!canEdit}
              onChange={(e) => updatePoint({ label: e.target.value })}
            />
            <TextField
              label="Device name"
              size="small"
              value={selected.device}
              disabled={!canEdit}
              onChange={(e) => updatePoint({ device: e.target.value })}
            />
            <label>
              Capture file{" "}
              <select
                aria-label="Point capture file"
                value={selected.capture_id}
                disabled={!canEdit}
                onChange={(e) => updatePoint({ capture_id: e.target.value })}
              >
                <option value="">Select capture</option>
                {captures.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Side{" "}
              <select
                aria-label="Point side"
                value={selected.side}
                disabled={!canEdit}
                onChange={(e) => updatePoint({ side: e.target.value })}
              >
                {["ingress", "egress", "both"].map((x) => (
                  <option key={x} value={x}>
                    {x}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Translation{" "}
              <select
                aria-label="Point translation"
                value={selected.translation}
                disabled={!canEdit}
                onChange={(e) => updatePoint({ translation: e.target.value })}
              >
                <option value="none">None</option>
                <option value="nat">NAT / PAT</option>
                <option value="full_proxy">Full proxy (unsupported)</option>
                <option value="seq_randomization">Seq randomization (learn offsets)</option>
              </select>
            </label>
          </Stack>
        </Card>
      )}

      {correlation && (
        <NexusCorrelationCard
          correlation={correlation}
          selectedPointId={selection}
        />
      )}

      <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Translation mappings">
        <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" sx={{ flexWrap: "wrap", gap: 1, mb: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Client networks & translation mappings
          </Typography>
        </Stack>
        <TextField
          label="Client networks (comma separated)"
          size="small"
          fullWidth
          disabled={!canEdit}
          value={topology.client_cidrs.join(", ")}
          onChange={(e) =>
            onTopologyChange({
              ...topology,
              client_cidrs: e.target.value
                .split(",")
                .map((s) => s.trim())
                .filter(Boolean),
            })
          }
          sx={{ mb: 1.5 }}
        />
        {report?.nat_suggestions?.length ? (
          <Stack spacing={1} sx={{ mb: 1.5 }}>
            {report.nat_suggestions.map((s, i) => (
              <Stack
                key={i}
                direction="row"
                spacing={1.5}
                alignItems="center"
                justifyContent="space-between"
                sx={{ p: 1, borderRadius: 1, border: `1px solid ${m3.outlineVar}` }}
              >
                <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
                  <StatusChip tone="attn" label={paNum(s.samples, " matching packets", 0)} dense />
                  <Box component="code" sx={{ fontFamily: MONO, fontSize: 12 }}>
                    {s.tuple_a}
                  </Box>
                  <span>→</span>
                  <Box component="code" sx={{ fontFamily: MONO, fontSize: 12 }}>
                    {s.tuple_b}
                  </Box>
                </Stack>
                <Stack direction="row" spacing={1}>
                  {canEdit && (
                    <M3Button
                      emphasis="tonal"
                      disabled={topology.nat_mappings.some(
                        (m) =>
                          m.point_a === s.point_a &&
                          m.point_b === s.point_b &&
                          ((m.tuple_a === s.tuple_a && m.tuple_b === s.tuple_b) ||
                            (reverseTuple(m.tuple_a) === s.tuple_a &&
                              reverseTuple(m.tuple_b) === s.tuple_b)),
                      )}
                      onClick={() =>
                        onTopologyChange({
                          ...topology,
                          nat_mappings: [...topology.nat_mappings, s],
                        })
                      }
                    >
                      Confirm mapping
                    </M3Button>
                  )}
                  <M3Button emphasis="text" onClick={() => onEvidence(s.evidence ?? [])}>
                    Evidence
                  </M3Button>
                </Stack>
              </Stack>
            ))}
          </Stack>
        ) : (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }}>
            Declare both sides of a device as NAT and run analysis to learn candidates.
          </Typography>
        )}
      </Card>
    </Stack>
  );
}

function NexusCorrelationCard({
  correlation,
  selectedPointId,
}: {
  readonly correlation: PacketNexusCorrelation;
  readonly selectedPointId: string | null;
}) {
  return (
    <Card
      sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}
      aria-label="neXus hop & policy correlation"
    >
      <Stack
        direction="row"
        spacing={1.5}
        alignItems="center"
        justifyContent="space-between"
        sx={{ flexWrap: "wrap", gap: 1, mb: 0.5 }}
      >
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          neXus inventory & policy correlation
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
          <StatusChip
            tone={correlation.inventory_status === "AVAILABLE" ? "ok" : "warn"}
            label={`Inventory: ${correlation.inventory_status}`}
            dense
          />
          <StatusChip
            tone="neutral"
            label={`${correlation.policy_snapshot_count} policy snapshot(s)`}
            dense
          />
        </Stack>
      </Stack>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1.5 }}>
        {correlation.evidence_separation_note}
      </Typography>
      {correlation.inventory_status !== "AVAILABLE" && (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1.5 }}>
          {correlation.inventory_reason}
        </Typography>
      )}

      <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
        Capture point correlation (firewall · interface · MAC · zone)
      </Typography>
      <Stack spacing={1.5} sx={{ mb: 2 }}>
        {correlation.hops.map((hop) => (
          <Box
            key={hop.point_id}
            aria-label={`Hop correlation ${hop.point_id}`}
            sx={{
              p: 1.5,
              borderRadius: 1,
              border: `1px solid ${selectedPointId === hop.point_id ? m3.primary : m3.outlineVar}`,
              bgcolor: m3.scLow,
            }}
          >
            <Stack
              direction="row"
              spacing={1}
              alignItems="center"
              justifyContent="space-between"
              sx={{ flexWrap: "wrap", gap: 1, mb: 1 }}
            >
              <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                {hop.point_label} ({hop.point_id} · {hop.side})
              </Typography>
              <Stack direction="row" spacing={0.75} alignItems="center" sx={{ flexWrap: "wrap", gap: 0.75 }}>
                <StatusChip tone={correlationStatusTone(hop.status)} label={hop.status} dense />
                {hop.evidence.map((ev) => (
                  <StatusChip key={ev} tone="mem" label={ev} dense />
                ))}
              </Stack>
            </Stack>

            <Stack direction={{ xs: "column", md: "row" }} spacing={1.5}>
              <Box
                aria-label={`Capture proves ${hop.point_id}`}
                sx={{ flex: 1, p: 1, borderRadius: 1, border: `1px solid ${m3.outlineVar}` }}
              >
                <Typography variant="caption" sx={{ fontWeight: 600, display: "block" }}>
                  The capture proves
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12 }}>
                  MACs: {hop.capture_proves.observed_macs.length ? hop.capture_proves.observed_macs.join(", ") : "none"}
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12 }}>
                  Addresses:{" "}
                  {hop.capture_proves.observed_addresses.length
                    ? hop.capture_proves.observed_addresses.join(", ")
                    : "none"}
                </Typography>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block" }}>
                  Capture interface: {hop.capture_proves.vendor_interface ?? "none"}
                </Typography>
              </Box>

              <Box
                aria-label={`Configured inventory says ${hop.point_id}`}
                sx={{ flex: 1, p: 1, borderRadius: 1, border: `1px solid ${m3.outlineVar}` }}
              >
                <Typography variant="caption" sx={{ fontWeight: 600, display: "block" }}>
                  Configured inventory says
                </Typography>
                {hop.status === "MATCH" && (
                  <>
                    <Typography variant="body2">
                      Firewall: <strong>{hop.hostname ?? hop.device_id}</strong> · Interface:{" "}
                      <strong>{hop.interface_name ?? hop.interface_id}</strong> · Zone:{" "}
                      <strong>{hop.zone ?? "unassigned"}</strong>
                    </Typography>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block", fontFamily: MONO }}>
                      MAC: {hop.mac_address ?? "none"} · Subnets: {(hop.interface_addresses ?? []).join(", ") || "none"}
                    </Typography>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block" }}>
                      {hop.reason}
                    </Typography>
                  </>
                )}
                {hop.status === "AMBIGUOUS" && (
                  <>
                    <Typography variant="body2" sx={{ mb: 0.5 }}>
                      {hop.reason}
                    </Typography>
                    <Stack spacing={0.5} component="ul" sx={{ m: 0, pl: 2 }}>
                      {hop.candidates.map((cand) => (
                        <Typography key={`${cand.device_id}-${cand.interface_id}`} component="li" variant="caption">
                          {cand.hostname ?? cand.device_id} · {cand.interface_name ?? cand.interface_id} · Zone:{" "}
                          {cand.zone ?? "unassigned"} ({cand.evidence.join(", ")})
                        </Typography>
                      ))}
                    </Stack>
                  </>
                )}
                {(hop.status === "NOT_EVALUABLE" || hop.status === "NO_MATCH") && (
                  <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                    {hop.reason}
                  </Typography>
                )}
              </Box>
            </Stack>
          </Box>
        ))}
      </Stack>

      {correlation.flows.length > 0 && (
        <>
          <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
            Path policy rule correlation (first-match 5-tuple containment)
          </Typography>
          <Stack spacing={1.5}>
            {correlation.flows.map((fl) => (
              <Box
                key={fl.flow}
                aria-label={`Flow correlation ${fl.flow}`}
                sx={{ p: 1.5, borderRadius: 1, border: `1px solid ${m3.outlineVar}`, bgcolor: m3.scLow }}
              >
                <Stack
                  direction="row"
                  spacing={1}
                  alignItems="center"
                  justifyContent="space-between"
                  sx={{ flexWrap: "wrap", gap: 1, mb: 1 }}
                >
                  <Box component="code" sx={{ fontFamily: MONO, fontSize: 12 }}>
                    {fl.tuple}
                  </Box>
                  <Stack direction="row" spacing={0.75} alignItems="center" sx={{ flexWrap: "wrap", gap: 0.75 }}>
                    <StatusChip tone={correlationStatusTone(fl.status)} label={fl.status} dense />
                    {fl.evidence.map((ev) => (
                      <StatusChip key={ev} tone="mem" label={ev} dense />
                    ))}
                    {fl.policy_stale && <StatusChip tone="bad" label="STALE_POLICY" dense />}
                    {fl.policy_incomplete && <StatusChip tone="bad" label="INCOMPLETE_POLICY" dense />}
                  </Stack>
                </Stack>

                <Stack direction={{ xs: "column", md: "row" }} spacing={1.5}>
                  <Box
                    aria-label={`Capture proves flow ${fl.flow}`}
                    sx={{ flex: 1, p: 1, borderRadius: 1, border: `1px solid ${m3.outlineVar}` }}
                  >
                    <Typography variant="caption" sx={{ fontWeight: 600, display: "block" }}>
                      The capture proves
                    </Typography>
                    <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12 }}>
                      Pre-NAT: {fl.pre_nat_src}:{fl.pre_nat_sport ?? "*"} → {fl.pre_nat_dst}:
                      {fl.pre_nat_dport ?? "*"} ({fl.proto})
                    </Typography>
                    <Typography variant="body2" sx={{ fontFamily: MONO, fontSize: 12 }}>
                      {fl.nat_confirmed
                        ? `Confirmed post-NAT: ${fl.post_nat_src}:${fl.post_nat_sport ?? "*"} → ${fl.post_nat_dst}:${fl.post_nat_dport ?? "*"}`
                        : "NAT: none confirmed"}
                    </Typography>
                  </Box>

                  <Box
                    aria-label={`Configured policy says flow ${fl.flow}`}
                    sx={{ flex: 1, p: 1, borderRadius: 1, border: `1px solid ${m3.outlineVar}` }}
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
                          Rule: <strong>#{fl.rule_number ?? "?"} {fl.rule_name ?? fl.rule_id}</strong> · Action:{" "}
                          <strong>{fl.action ?? "unknown"}</strong>
                        </Typography>
                        <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block" }}>
                          Path: {fl.ingress_interface_name ?? "unknown"} ({fl.source_zone ?? "unassigned"}) →{" "}
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
              </Box>
            ))}
          </Stack>
        </>
      )}
    </Card>
  );
}

