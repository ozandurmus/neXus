import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { echarts } from "./echartsSetup";
import type { EChartsOption } from "./echartsSetup";
import { coverageOption, heatmapOption, ladderOption } from "./chartOptions";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type {
  PacketAnalysisReport,
  PacketCaptureInventory,
  PacketLadderView,
  PacketRef,
  PacketTimeseriesView,
  PacketTopologyPoint,
} from "../../auth/adminApi";
import { M3Button } from "../../shell/M3Widgets";
import { m3 } from "../../theme/m3Theme";

export const paTime = (v: number | null | undefined): string =>
  v == null || !Number.isFinite(v)
    ? "unknown"
    : new Date(Math.round(v * 1000)).toISOString().slice(11, 23) + " UTC";

export const paNum = (v: number | null | undefined, unit = "", digits = 2): string =>
  v == null || !Number.isFinite(v)
    ? "unknown"
    : v.toLocaleString(undefined, { maximumFractionDigits: digits }) + unit;

export function reverseTuple(value: string): string {
  try {
    const [proto, src, sport, dst, dport] = JSON.parse(value) as [string, string, number, string, number];
    return JSON.stringify([proto, dst, dport, src, sport]);
  } catch {
    return "";
  }
}

export function PacketChart({
  option,
  height = 260,
  ariaLabel = "Interactive packet capture chart",
  onClick,
  onBrush,
}: {
  readonly option: EChartsOption;
  readonly height?: number;
  readonly ariaLabel?: string;
  readonly onClick?: (params: unknown) => void;
  readonly onBrush?: (params: unknown) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let chart: echarts.ECharts | null = null;
    let resize: ResizeObserver | null = null;
    try {
      const isJsdom = typeof navigator !== "undefined" && /jsdom/i.test(navigator.userAgent);
      const probe = !isJsdom ? document.createElement("canvas") : null;
      const ctx = probe && typeof probe.getContext === "function" ? probe.getContext("2d") : null;
      if (ctx) {
        chart = echarts.init(el, undefined, {
          width: el.clientWidth || 720,
          height: el.clientHeight || height,
        });
        chart.setOption(option);
        if (onClick) chart.on("click", onClick);
        if (onBrush) {
          chart.on("brushEnd", onBrush);
          chart.dispatchAction({
            type: "takeGlobalCursor",
            key: "brush",
            brushOption: { brushType: "lineX", brushMode: "single" },
          });
        }
        if (typeof ResizeObserver !== "undefined") {
          resize = new ResizeObserver(() => {
            try {
              chart?.resize();
            } catch {
              // ignore resize errors in detached DOM
            }
          });
          resize.observe(el);
        }
      }
    } catch {
      // Headless JSDOM environments without canvas 2D support fall back to the accessible container.
    }
    return () => {
      try {
        resize?.disconnect();
      } catch {
        // ignore
      }
      try {
        chart?.dispose();
      } catch {
        // ignore
      }
    };
  }, [option, height, onClick, onBrush]);

  return (
    <div
      role="img"
      aria-label={ariaLabel}
      ref={ref}
      style={{ height, width: "100%" }}
    />
  );
}

export function CoverageChart({
  captures,
  report,
}: {
  readonly captures: readonly PacketCaptureInventory[];
  readonly report: PacketAnalysisReport | null;
}) {
  const values = captures.filter(
    (c) =>
      c.state === "ready" &&
      c.inventory?.timestamps_validated &&
      c.inventory?.start != null &&
      c.inventory?.end != null,
  );
  const hasClocks = !!report;
  const option = coverageOption(values, report);

  return (
    <Box>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1 }}>
        {hasClocks
          ? "Corrected timestamps where alignment is available. Amber marks common overlap."
          : "Observed timestamps. Run analysis to align clocks and calculate common overlap."}{" "}
        First and last packets bound observed coverage.
      </Typography>
      <PacketChart
        option={option}
        height={Math.max(220, values.length * 35 + 75)}
        ariaLabel="Capture coverage chart"
      />
    </Box>
  );
}

export function LadderChart({
  data,
  points,
  onEvidence,
}: {
  readonly data: PacketLadderView;
  readonly points: readonly PacketTopologyPoint[];
  readonly onEvidence: (refs: readonly PacketRef[]) => void;
}) {
  const pointIndex = new Map(points.map((p, i) => [p.id, i]));
  const timed = data.items.flatMap((p) =>
    p.evidence
      .map((e) => e.corrected_time)
      .filter((t): t is number => t != null),
  );
  if (!timed.length) {
    return (
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, py: 1 }}>
        Clock alignment is unknown. Frame evidence is available below.
      </Typography>
    );
  }
  const start = Math.min(...timed);
  const option = ladderOption(data, points, start);
  return (
    <PacketChart
      option={option}
      height={420}
      ariaLabel="Multi-hop packet ladder chart"
      onClick={(params) => {
        const key = (params as { seriesName?: string })?.seriesName;
        const p = data.items.find((item) => item.packet_key === key);
        if (p) onEvidence(p.evidence);
      }}
    />
  );
}

export function HeatmapChart({
  data,
  report,
  selectedRange,
  onSelect,
}: {
  readonly data: PacketTimeseriesView;
  readonly report: PacketAnalysisReport;
  readonly selectedRange: readonly [number, number] | null;
  readonly onSelect: (range: readonly [number, number] | null) => void;
}) {
  const metricKeys = Object.keys(data.metrics);
  const [metric, setMetric] = useState(
    data.metrics.loss_percent ? "loss_percent" : (metricKeys[0] ?? "loss_percent"),
  );
  const [clear, setClear] = useState(0);
  useEffect(() => {
    if (selectedRange === null) setClear((n) => n + 1);
  }, [selectedRange]);
  const descriptor = data.metrics[metric] ?? {
    label: metric,
    unit: "%",
    tooltip: "Per-bucket metric across path segments.",
  };

  const onBrush = useCallback(
    (event: unknown) => {
      const areas = (
        event as { areas?: { coordRange?: number[] | number[][] }[] }
      )?.areas;
      if (!areas?.length) {
        onSelect(null);
        return;
      }
      const raw = areas[0].coordRange;
      if (!raw) return;
      const range = Array.isArray(raw[0]) ? raw[0] : (raw as number[]);
      const left = Math.max(0, Math.floor(Math.min(...range)));
      const right = Math.min(
        data.bucket_count - 1,
        Math.ceil(Math.max(...range)),
      );
      if (Number.isFinite(left) && Number.isFinite(right)) {
        onSelect([
          data.start + left * data.bucket_seconds,
          data.start + (right + 1) * data.bucket_seconds,
        ]);
      }
    },
    [data.start, data.bucket_seconds, data.bucket_count, onSelect],
  );

  const option = useMemo<EChartsOption>(
    () => heatmapOption(data, report, metric, descriptor),
    [data, report, metric, descriptor],
  );

  return (
    <Box>
      <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" sx={{ flexWrap: "wrap", gap: 1 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>Path × time</Typography>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <label>
            Metric{" "}
            <select
              aria-label="Heatmap metric"
              value={metric}
              onChange={(e) => setMetric(e.target.value)}
            >
              {Object.entries(data.metrics).map(([key, m]) => (
                <option key={key} value={key} title={m.tooltip}>
                  {m.label} ({m.unit})
                </option>
              ))}
            </select>
          </label>
          <M3Button
            emphasis="outlined"
            onClick={() => {
              setClear((n) => n + 1);
              onSelect(null);
            }}
          >
            Clear time selection
          </M3Button>
        </Stack>
      </Stack>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.5, mb: 1 }}>
        {descriptor.tooltip} Diamonds mark detected onsets. NC = not capturing; ? = partial coverage or unavailable metric.
      </Typography>
      <PacketChart
        key={clear}
        option={option}
        height={Math.max(320, report.segments.length * 36 + 180)}
        ariaLabel="Path by time heatmap"
        onBrush={onBrush}
      />
    </Box>
  );
}
