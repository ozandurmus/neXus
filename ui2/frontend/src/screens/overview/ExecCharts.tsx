import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { STATUS, STATUS_INK } from "../../shell/Charts";
import { m3 } from "../../theme/m3Theme";

export type Tone = "good" | "warning" | "serious" | "critical" | "neutral";

/** Tone by share: at or above {@code good} is good, at or above {@code warn} a warning, else critical. */
export function toneOf(pct: number | null, good: number, warn: number): Tone {
  if (pct === null) return "neutral";
  return pct >= good ? "good" : pct >= warn ? "warning" : "critical";
}

/**
 * One executive gauge: a ring filled to the share, the percentage in the middle, "value of total" under it, the
 * title and one plain sentence below. The whole gauge opens the list behind it.
 */
export function RingGauge({ title, value, of, caption, tone, href, size = 148 }: {
  readonly title: string; readonly value: number | null; readonly of: number | null; readonly caption: string;
  readonly tone: Tone; readonly href: string; readonly size?: number;
}) {
  const known = value !== null && of !== null && of > 0;
  const share = known ? value / of : 0;
  const stroke = 14;
  const r = size / 2 - stroke / 2 - 2;
  const c = 2 * Math.PI * r;
  const color = STATUS[tone];
  return (
    <Box component="a" href={href} sx={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 1, textDecoration: "none",
      color: "inherit", p: 1.5, borderRadius: "12px", "&:hover": { bgcolor: m3.scLow } }}>
      <Box sx={{ position: "relative", width: size, height: size }}>
        <svg width={size} height={size} role="img" aria-label={`${title}: ${known ? `${value} of ${of}` : "UNKNOWN"}`}>
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={m3.sc} strokeWidth={stroke} />
          {known && (
            <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
              strokeDasharray={`${Math.max(0.001, share) * c} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} />
          )}
        </svg>
        <Box sx={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}>
          <Typography sx={{ fontSize: size / 4.4, fontWeight: 700, lineHeight: 1, color: known ? STATUS_INK[tone] : m3.onSurfaceVar }}>
            {known ? `${Math.round(share * 1000) / 10}%` : "UNKNOWN"}
          </Typography>
          {known && <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, mt: 0.5 }}>{value} / {of}</Typography>}
        </Box>
      </Box>
      <Typography sx={{ fontSize: 15, fontWeight: 700, color: m3.onSurface, textAlign: "center" }}>{title}</Typography>
      <Typography sx={{ fontSize: 13, color: m3.onSurfaceVar, textAlign: "center", maxWidth: 240, lineHeight: 1.4 }}>{caption}</Typography>
    </Box>
  );
}

/** Horizontal bars, longest first: a label, a bar scaled to the largest (or to its own total), the count. */
export function BarList({ rows, color, unit }: {
  readonly rows: ReadonlyArray<{ label: string; value: number; total?: number; href?: string; note?: string }>;
  readonly color: string; readonly unit: string;
}) {
  const max = Math.max(1, ...rows.map((r) => r.total ?? r.value));
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 1.25 }}>
      {rows.map((r) => (
        <Box key={r.label}>
          <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1.5, mb: 0.5, alignItems: "baseline" }}>
            {r.href ? <Link href={r.href} underline="hover" sx={{ fontSize: 13.5, fontWeight: 600, color: m3.onSurface, minWidth: 0,
              overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }} title={r.label}>{r.label}</Link>
              : <Typography sx={{ fontSize: 13.5, fontWeight: 600, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.label}</Typography>}
            <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, whiteSpace: "nowrap" }}>
              <b style={{ color: m3.onSurface }}>{r.value}</b>{r.total !== undefined ? ` of ${r.total}` : ""} {unit}
            </Typography>
          </Box>
          <Tooltip title={r.note ?? ""} disableHoverListener={!r.note}>
            <Box sx={{ height: 10, borderRadius: 5, bgcolor: m3.sc, overflow: "hidden" }}>
              <Box sx={{ width: `${(100 * r.value) / (r.total ?? max)}%`, height: "100%", bgcolor: color, borderRadius: 5 }} />
            </Box>
          </Tooltip>
        </Box>
      ))}
    </Box>
  );
}

/** A 100 % bar in named parts with a legend -- pass / fail / not checked, newest / behind. */
export function PartsBar({ label, parts, href }: {
  readonly label: string; readonly href?: string;
  readonly parts: ReadonlyArray<{ name: string; count: number; color: string }>;
}) {
  const total = Math.max(1, parts.reduce((n, p) => n + p.count, 0));
  const first = parts[0];
  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", mb: 0.5, gap: 1 }}>
        {href ? <Link href={href} underline="hover" sx={{ fontSize: 13.5, fontWeight: 600, color: m3.onSurface }}>{label}</Link>
          : <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{label}</Typography>}
        <Typography sx={{ fontSize: 13, fontWeight: 700, color: m3.onSurface }}>{Math.round((1000 * first.count) / total) / 10}%
          <Typography component="span" sx={{ fontSize: 12, fontWeight: 400, color: m3.onSurfaceVar }}> {first.name.toLowerCase()}</Typography></Typography>
      </Box>
      <Box sx={{ display: "flex", height: 12, borderRadius: 6, overflow: "hidden", gap: "2px", bgcolor: m3.sc }}>
        {parts.filter((p) => p.count > 0).map((p) => (
          <Tooltip key={p.name} title={`${p.name}: ${p.count}`}>
            <Box sx={{ width: `${(100 * p.count) / total}%`, bgcolor: p.color }} />
          </Tooltip>
        ))}
      </Box>
    </Box>
  );
}

export function Legend({ items }: { readonly items: ReadonlyArray<{ name: string; color: string }> }) {
  return (
    <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
      {items.map((i) => (
        <Box key={i.name} sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <Box sx={{ width: 10, height: 10, borderRadius: "3px", bgcolor: i.color }} />
          <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{i.name}</Typography>
        </Box>
      ))}
    </Box>
  );
}
