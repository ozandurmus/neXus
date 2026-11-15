import { describe, expect, it } from "vitest";
import { coverageOption, flowOptions, heatmapOption, ladderOption } from "./chartOptions";
import {
  REGISTERED_COMPONENT_KEYS,
  REGISTERED_SERIES,
  REGISTERED_SERIES_COMPONENT_KEYS,
} from "./echartsSetup";

// Top-level option keys that are plain settings and need no component.
const PLAIN_KEYS = new Set(["useUTC", "animation", "series", "color", "backgroundColor", "textStyle"]);

type Obj = Record<string, unknown>;

function asList(v: unknown): Obj[] {
  return (Array.isArray(v) ? v : v == null ? [] : [v]) as Obj[];
}

// Keys a chart option needs registered, collected from the built option.
function requiredModules(option: Obj): { series: Set<string>; components: Set<string> } {
  const series = new Set<string>();
  const components = new Set<string>();
  for (const key of Object.keys(option)) {
    if (PLAIN_KEYS.has(key)) continue;
    components.add(key);
  }
  for (const s of asList(option.series)) {
    series.add(String(s.type));
    for (const k of REGISTERED_SERIES_COMPONENT_KEYS) if (s[k] != null) components.add(k);
    for (const k of ["markLine", "markPoint", "markArea"]) if (s[k] != null) components.add(k);
  }
  const toolbox = option.toolbox as { feature?: Obj } | undefined;
  if (toolbox?.feature && "brush" in toolbox.feature) components.add("brush");
  for (const d of asList(option.dataZoom)) {
    expect(["slider", "inside"]).toContain(d.type); // both ship with DataZoomComponent
  }
  return { series, components };
}

function assertRegistered(option: Obj) {
  const { series, components } = requiredModules(option);
  expect(series.size).toBeGreaterThan(0);
  const allowed = new Set([...REGISTERED_COMPONENT_KEYS, ...REGISTERED_SERIES_COMPONENT_KEYS]);
  for (const t of series) expect(REGISTERED_SERIES, `series type ${t}`).toContain(t);
  for (const c of components) expect(allowed.has(c), `component ${c}`).toBe(true);
}

const capture = (id: string, start: number, end: number) =>
  ({
    id,
    name: id,
    state: "ready",
    inventory: { timestamps_validated: true, start, end },
  }) as never;

const report = {
  window: { common_start: 10, common_end: 20 },
  clocks: {},
  segments: [{ id: "s1", label: "A to B", direction: "fwd" }],
  onsets: { items: [{ metric: "loss_percent", segment: "s1", bucket: 0, explanation: "x" }] },
} as never;

describe("echarts registration covers every option builder", () => {
  it("coverage chart, with and without overlap", () => {
    const caps = [capture("a", 1, 30), capture("b", 5, 25)];
    assertRegistered(coverageOption(caps, report) as Obj);
    assertRegistered(coverageOption(caps, null) as Obj);
    const withOverlap = coverageOption(caps, report) as Obj;
    expect(requiredModules(withOverlap).components.has("markArea")).toBe(true);
  });

  it("ladder chart", () => {
    const points = [{ id: "p1", label: "P1" }, { id: "p2", label: "P2" }] as never;
    const data = {
      items: [
        {
          packet_key: "k1",
          retrans: false,
          evidence: [{ point: "p1", corrected_time: 1 }, { point: "p2", corrected_time: 1.1 }],
        },
      ],
      events: [{ point_b: "p2", packet_key: "k1", ts: 1.1 }],
    } as never;
    const option = ladderOption(data, points, 1) as Obj;
    assertRegistered(option);
    expect(requiredModules(option).series).toEqual(new Set(["line", "scatter"]));
  });

  it("heatmap", () => {
    const data = {
      metrics: {},
      start: 0,
      bucket_seconds: 1,
      bucket_count: 2,
      items: [
        { segment: "s1", bucket: 0, loss_percent: 3 },
        { segment: "s1", bucket: 1, loss_percent: null },
      ],
    } as never;
    const option = heatmapOption(data, report, "loss_percent", { label: "Loss", unit: "%" }) as Obj;
    assertRegistered(option);
    const { series, components } = requiredModules(option);
    expect(series).toEqual(new Set(["heatmap", "scatter"]));
    expect(components.has("visualMap")).toBe(true);
    expect(components.has("brush")).toBe(true);
    expect(components.has("toolbox")).toBe(true);
  });

  it("flow stream charts, empty and populated", () => {
    for (const o of flowOptions(null)) assertRegistered(o as Obj);
    const result = {
      packets: [
        { kind: "data", sequence: 1, length: 10, time: 1, frame: 1, retransmission: true, window_edge: null },
        { kind: "ack", sequence: 11, length: 0, time: 2, frame: 2, retransmission: false, window_edge: 99 },
      ],
      buckets: [{ time: 1, rtt_ms: 3, rtt_frame: 1, throughput_bps: 5, data_frame: 2 }],
    } as never;
    const options = flowOptions(result);
    expect(options).toHaveLength(3);
    const types = new Set<string>();
    for (const o of options) {
      assertRegistered(o as Obj);
      requiredModules(o as Obj).series.forEach((t) => types.add(t));
    }
    expect(types).toEqual(new Set(["line", "scatter"]));
  });

  it("registration lists have no duplicates", () => {
    expect(new Set(REGISTERED_SERIES).size).toBe(REGISTERED_SERIES.length);
    expect(new Set(REGISTERED_COMPONENT_KEYS).size).toBe(REGISTERED_COMPONENT_KEYS.length);
  });
});
