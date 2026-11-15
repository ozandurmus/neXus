// Single registration point for the ECharts modules used by Packet Analysis.
// Importing "echarts/core" instead of the full "echarts" bundle keeps the
// vendor chunk small; a chart type or component that is missing here fails
// silently at runtime, so echartsSetup.test.ts checks every option builder
// against these lists.
import * as echarts from "echarts/core";
import { CustomChart, HeatmapChart, LineChart, ScatterChart } from "echarts/charts";
import {
  BrushComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  ToolboxComponent,
  TooltipComponent,
  VisualMapComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

export const REGISTERED_SERIES: readonly string[] = ["line", "scatter", "heatmap", "custom"];

// Top-level option keys that need a registered component. grid also provides
// the xAxis and yAxis models.
export const REGISTERED_COMPONENT_KEYS: readonly string[] = [
  "grid",
  "xAxis",
  "yAxis",
  "tooltip",
  "legend",
  "dataZoom",
  "toolbox",
  "brush",
  "visualMap",
];

// Series-level components (series.markArea) that need a registered component.
export const REGISTERED_SERIES_COMPONENT_KEYS: readonly string[] = ["markArea"];

echarts.use([
  LineChart,
  ScatterChart,
  HeatmapChart,
  CustomChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  ToolboxComponent,
  BrushComponent,
  VisualMapComponent,
  MarkAreaComponent,
  CanvasRenderer,
]);

export { echarts };
export type { EChartsOption, SeriesOption, ECharts } from "echarts";
