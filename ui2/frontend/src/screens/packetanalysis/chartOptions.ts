// Pure ECharts option builders for Packet Analysis. Kept free of React so
// echartsSetup.test.ts can check every series type and component key they emit
// against the registered ECharts modules.
import type { EChartsOption, SeriesOption } from "./echartsSetup";
import type {
  PacketAnalysisReport,
  PacketCaptureInventory,
  PacketLadderView,
  PacketStreamChartsView,
  PacketTimeseriesView,
  PacketTopologyPoint,
} from "../../auth/adminApi";

export function coverageOption(
  values: readonly PacketCaptureInventory[],
  report: PacketAnalysisReport | null,
): EChartsOption {
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
  return {
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
}

export function ladderOption(
  data: PacketLadderView,
  points: readonly PacketTopologyPoint[],
  start: number,
): EChartsOption {
  const pointIndex = new Map(points.map((p, i) => [p.id, i]));
  const series: SeriesOption[] = data.items.map((p) => ({
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
  return {
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
}

export function heatmapOption(
  data: PacketTimeseriesView,
  report: PacketAnalysisReport,
  metric: string,
  descriptor: { readonly label: string; readonly unit: string },
): EChartsOption {
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
}

export function flowOptions(result: PacketStreamChartsView | null): EChartsOption[] {
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
}
