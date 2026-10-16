import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import * as echarts from "echarts";
import type { EChartsOption } from "echarts";
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
  const data = values.map((c, i) => {
    const m = report?.clocks?.[c.id];
    const correct = (t: number) =>
      m?.offset != null
        ? m.epoch + (t - m.epoch - m.offset) / (1 + m.drift_ppm / 1e6)
        : t;
    return [
      i,
      correct(c.inventory.start!) * 1000,
      correct(c.inventory.end!) * 1000,
    ];
  });
  const overlap = report?.window;
  const option: EChartsOption = {
    useUTC: true,
    animation: false,
    grid: { left: 150, right: 25, top: 20, bottom: 55 },
    tooltip: { renderMode: "richText" },
    xAxis: { type: "time", axisLabel: { color: "#657780" } },
    yAxis: {
      type: "category",
      data: values.map((c) => c.name),
      axisLabel: { width: 130, overflow: "truncate" },
      inverse: true,
    },
    dataZoom: [{ type: "slider", height: 18, bottom: 8 }],
    series: [
      {
        type: "custom",
        data,
        renderItem: (_params, api) => {
          const a = api.coord([api.value(1), api.value(0)]);
          const b = api.coord([api.value(2), api.value(0)]);
          return {
            type: "rect",
            shape: {
              x: a[0],
              y: a[1] - 9,
              width: Math.max(b[0] - a[0], 2),
              height: 18,
            },
            style: { fill: "#168a84", opacity: 0.8 },
          };
        },
        encode: { x: [1, 2], y: 0 },
        markArea:
          overlap?.common_start != null && overlap.common_end != null
            ? {
                silent: true,
                itemStyle: { color: "rgba(234,183,64,.18)" },
                data: [
                  [
                    { xAxis: overlap.common_start * 1000 },
                    { xAxis: overlap.common_end * 1000 },
                  ],
                ],
              }
            : undefined,
      },
    ],
  };

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
  const series: echarts.SeriesOption[] = data.items.map((p) => ({
    name: p.packet_key,
    type: "line",
    connectNulls: false,
    showSymbol: true,
    symbolSize: 5,
    lineStyle: {
      width: 1.2,
      color: p.retrans ? "#d78325" : "#268b86",
      opacity: 0.65,
    },
    itemStyle: { color: p.retrans ? "#d78325" : "#268b86" },
    data: points.map((point, i) => {
      const e = p.evidence.find((ev) => ev.point === point.id);
      return [
        i,
        e?.corrected_time != null ? (e.corrected_time - start) * 1000 : null,
      ];
    }),
    emphasis: { lineStyle: { width: 3 } },
  }));
  series.push({
    type: "scatter",
    name: "Missing appearance",
    symbol: "path://M-5,-5 L5,5 M5,-5 L-5,5",
    symbolSize: 12,
    itemStyle: { color: "#c44842" },
    data: data.events
      .filter(
        (e) =>
          pointIndex.has(e.point_b) &&
          data.items.some((p) => p.packet_key === e.packet_key),
      )
      .map((e) => [pointIndex.get(e.point_b)!, (e.ts - start) * 1000]),
  });
  const option: EChartsOption = {
    useUTC: true,
    animation: false,
    grid: { left: 80, right: 30, top: 45, bottom: 70 },
    tooltip: { renderMode: "richText", trigger: "item" },
    xAxis: {
      type: "category",
      position: "top",
      data: points.map((p) => p.label),
      axisLabel: { interval: 0, rotate: 10 },
    },
    yAxis: {
      type: "value",
      inverse: true,
      name: "Elapsed ms",
      axisLabel: { formatter: "{value} ms" },
    },
    dataZoom: [
      { type: "slider", yAxisIndex: 0, orient: "vertical", right: 0 },
      { type: "inside", yAxisIndex: 0 },
    ],
    series,
  };
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

  const option = useMemo<EChartsOption>(() => {
    const segments = report.segments;
    const index = new Map(segments.map((s, i) => [s.id, i]));
    const full: { value: number[]; row: PacketTimeseriesView["items"][number] }[] = [];
    const empty: typeof full = [];
    for (const row of data.items) {
      const segIdx = index.get(row.segment);
      if (segIdx === undefined) continue;
      const v = row[metric];
      const target = typeof v === "number" ? full : empty;
      target.push({
        value: [row.bucket, segIdx, typeof v === "number" ? v : -1],
        row,
      });
    }
    const max = full.reduce((m, x) => Math.max(m, x.value[2]), 1);
    const markers =
      report.onsets?.items
        .filter((o) => o.metric === metric && index.has(o.segment))
        .map((o) => ({
          value: [o.bucket, index.get(o.segment)!, 0],
          name: o.explanation,
        })) ?? [];
    return {
      animation: false,
      grid: { left: 220, right: 35, top: 55, bottom: 130 },
      tooltip: { renderMode: "richText" },
      toolbox: { feature: { brush: { type: ["lineX", "clear"] } } },
      brush: {
        xAxisIndex: 0,
        brushMode: "single",
        throttleType: "debounce",
        throttleDelay: 150,
      },
      xAxis: {
        type: "category",
        name: "UTC",
        data: Array.from({ length: data.bucket_count }, (_, i) =>
          new Date((data.start + i * data.bucket_seconds) * 1000)
            .toISOString()
            .slice(11, data.bucket_seconds < 1 ? 23 : 19),
        ),
      },
      yAxis: {
        type: "category",
        inverse: true,
        data: segments.map((s) => `${s.label} · ${s.direction}`),
        axisLabel: { width: 200, overflow: "truncate" },
      },
      visualMap: {
        min: 0,
        max,
        calculable: true,
        orient: "horizontal",
        left: "center",
        bottom: 0,
        seriesIndex: 0,
        inRange: { color: ["#d9efea", "#69bcb4", "#e9b44c", "#c44842"] },
        text: [descriptor.unit, "0"],
      },
      dataZoom: [{ type: "slider", xAxisIndex: 0, bottom: 55, height: 15 }],
      series: [
        {
          type: "heatmap",
          name: descriptor.label,
          data: full,
        },
        {
          type: "heatmap",
          name: "Unavailable",
          data: empty,
          itemStyle: { color: "#d9dfe3", borderColor: "#fff", borderWidth: 1 },
        },
        {
          type: "scatter",
          name: "Onset",
          symbol: "diamond",
          symbolSize: 14,
          data: markers,
          z: 10,
        },
      ],
    };
  }, [data, report, metric, descriptor]);

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
