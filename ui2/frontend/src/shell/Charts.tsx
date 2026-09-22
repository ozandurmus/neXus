import { useState } from "react";
import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";

/**
 * Small, dependency-free SVG charts for the executive summary (dataviz method: fixed categorical order,
 * validated palette -- light slots 1..6 pass the adjacent CVD and normal-vision gates; three slots sit below
 * 3:1 contrast on the surface, so every chart ships visible labels with counts; status colours are reserved
 * for state and always paired with a word).
 */

/** Categorical slots 1..6, in fixed order (validated 2026-09-23 with scripts/validate_palette.js). */
export const SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"] as const;
/** "Other" (folded slices) and UNKNOWN (not evidenced) are neutral, never a series colour. */
export const OTHER = "#b4b2aa";
export const UNKNOWN_COLOR = "#8f8d85";
/** Status palette -- state only, always with a label. */
export const STATUS = { good: "#0ca30c", warning: "#fab219", serious: "#ec835a", critical: "#d03b3b", neutral: "#94A3B8" } as const;

export interface Slice {
  readonly label: string | null;
  readonly count: number;
  readonly href?: string;
}

/** At most {@code max} named slices; the rest fold into "Other"; UNKNOWN (null label) always last. */
export function foldSlices(slices: readonly Slice[], max = 5, otherHref?: string): Array<Slice & { color: string; display: string }> {
  const known = slices.filter((s) => s.label !== null && s.count > 0);
  const unknown = slices.filter((s) => s.label === null && s.count > 0);
  const head = known.slice(0, max);
  const rest = known.slice(max);
  const out: Array<Slice & { color: string; display: string }> = head.map((s, i) => ({ ...s, color: SERIES[i % SERIES.length], display: s.label as string }));
  if (rest.length > 0) {
    out.push({ label: "Other", count: rest.reduce((n, s) => n + s.count, 0), href: otherHref, color: OTHER, display: `Other (${rest.length})` });
  }
  for (const u of unknown) out.push({ ...u, color: UNKNOWN_COLOR, display: "UNKNOWN" });
  return out;
}

function arc(cx: number, cy: number, r: number, start: number, end: number): string {
  const large = end - start > Math.PI ? 1 : 0;
  const x1 = cx + r * Math.sin(start);
  const y1 = cy - r * Math.cos(start);
  const x2 = cx + r * Math.sin(end);
  const y2 = cy - r * Math.cos(end);
  return `M ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2}`;
}

/** Part-to-whole donut, <= 6 named slices + Other + UNKNOWN, with a hover readout in the centre and a legend. */
export function Donut({ title, slices, size = 132, centerLabel }: {
  readonly title: string;
  readonly slices: ReadonlyArray<Slice & { color: string; display: string }>;
  readonly size?: number;
  readonly centerLabel?: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const total = slices.reduce((n, s) => n + s.count, 0);
  const r = size / 2 - 12;
  const stroke = 18;
  let angle = 0;
  const active = hover !== null ? slices[hover] : null;
  return (
    <Box sx={{ display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
      <Box sx={{ position: "relative", width: size, height: size, flexShrink: 0 }}>
        <svg width={size} height={size} role="img" aria-label={`${title}: ${slices.map((s) => `${s.display} ${s.count}`).join(", ")}`}>
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={m3.sc} strokeWidth={stroke} />
          {total > 0 && slices.map((s, i) => {
            const sweep = (2 * Math.PI * s.count) / total;
            const start = angle;
            angle += sweep;
            const path = sweep >= 2 * Math.PI - 1e-6
              ? null
              : arc(size / 2, size / 2, r, start + 0.012, start + sweep - 0.012);
            return path === null ? (
              <circle key={i} cx={size / 2} cy={size / 2} r={r} fill="none" stroke={s.color} strokeWidth={hover === i ? stroke + 4 : stroke}
                onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
            ) : (
              <path key={i} d={path} fill="none" stroke={s.color} strokeWidth={hover === i ? stroke + 4 : stroke}
                style={{ cursor: s.href ? "pointer" : "default", transition: "stroke-width 120ms" }}
                onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}
                onClick={() => { if (s.href) window.location.href = s.href; }} />
            );
          })}
        </svg>
        <Box sx={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", pointerEvents: "none", px: 3, textAlign: "center" }}>
          <Typography sx={{ fontSize: 22, fontWeight: 700, lineHeight: 1.1, color: m3.onSurface }}>{active ? active.count : total}</Typography>
          <Typography sx={{ fontSize: 10.5, color: m3.onSurfaceVar, lineHeight: 1.2, wordBreak: "break-word" }}>
            {active ? active.display : centerLabel ?? "devices"}
          </Typography>
        </Box>
      </Box>
      <Box sx={{ minWidth: 150, flex: 1 }}>
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em", fontWeight: 600 }}>{title.toUpperCase()}</Typography>
        {slices.length === 0 && <Typography variant="body2" color="text.secondary">UNKNOWN — nothing read yet</Typography>}
        {slices.map((s, i) => {
          const pct = total > 0 ? Math.round((100 * s.count) / total) : 0;
          const row = (
            <Box key={i} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}
              sx={{ display: "flex", alignItems: "center", gap: 1, py: 0.25, px: 0.5, borderRadius: "6px", bgcolor: hover === i ? m3.sc : "transparent" }}>
              <Box sx={{ width: 10, height: 10, borderRadius: "3px", bgcolor: s.color, flexShrink: 0 }} />
              <Typography sx={{ fontSize: 12.5, fontFamily: s.display === "UNKNOWN" || s.display.startsWith("Other") ? undefined : "monospace", flex: 1, color: m3.onSurface }}>{s.display}</Typography>
              <Typography sx={{ fontSize: 12.5, fontWeight: 600, color: m3.onSurface }}>{s.count}</Typography>
              <Typography sx={{ fontSize: 11.5, color: m3.onSurfaceVar, width: 36, textAlign: "right" }}>{pct}%</Typography>
            </Box>
          );
          return s.href ? <Link key={i} href={s.href} underline="none" sx={{ color: "inherit", display: "block" }}>{row}</Link> : row;
        })}
      </Box>
    </Box>
  );
}

/** A single ratio against its whole: a same-ramp track, the value as a word and a number. */
export function Meter({ value, of, tone }: { readonly value: number; readonly of: number; readonly tone: keyof typeof STATUS | "primary" }) {
  const pct = of > 0 ? Math.min(100, (100 * value) / of) : 0;
  const color = tone === "primary" ? m3.primary : STATUS[tone];
  return (
    <Box sx={{ height: 8, borderRadius: "4px", bgcolor: m3.sc, overflow: "hidden" }}>
      <Box sx={{ width: `${pct}%`, height: "100%", bgcolor: color, borderRadius: "4px", transition: "width 300ms" }} />
    </Box>
  );
}

/** Horizontal part-to-whole bar with 2 px gaps between segments and a tooltip per segment. */
export function StackedBar({ parts, height = 14 }: {
  readonly parts: ReadonlyArray<{ label: string; count: number; color: string; href?: string }>;
  readonly height?: number;
}) {
  const total = Math.max(1, parts.reduce((n, p) => n + p.count, 0));
  return (
    <Box sx={{ display: "flex", gap: "2px", height, borderRadius: `${height / 2}px`, overflow: "hidden", bgcolor: m3.sc }}>
      {parts.filter((p) => p.count > 0).map((p) => (
        <Tooltip key={p.label} title={`${p.label}: ${p.count} (${Math.round((100 * p.count) / total)}%)`}>
          <Box component={p.href ? "a" : "div"} href={p.href}
            sx={{ width: `${(100 * p.count) / total}%`, bgcolor: p.color, display: "block", minWidth: 3 }} />
        </Tooltip>
      ))}
    </Box>
  );
}
