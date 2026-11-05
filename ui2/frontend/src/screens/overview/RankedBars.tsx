import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import { OTHER, SERIES, UNKNOWN_COLOR, UNKNOWN_HATCH_CSS } from "../../shell/Charts";
import { m3 } from "../../theme/m3Theme";

/**
 * Ranked horizontal bars (UI review 2026-11-05, C5; R-18 with Fable's amendment): the largest slice first, the full
 * label beside its bar (wrapped, never cut), the count at the end, each row the filtered device list. They replace the
 * Fleet summary's donuts, whose centre repeated the total and whose legends cut version and model names.
 */
export interface RankedRow {
  /** The words on the row; the full value. */
  readonly display: string;
  readonly count: number;
  readonly href?: string;
  /** Folded slices (Other) and not-evidenced ones (UNKNOWN) are neutral, never a series colour. */
  readonly kind?: "named" | "other" | "unknown";
}

/** Named rows by count (largest first, ties by label), then Other, then UNKNOWN; at most `max` named rows. */
export function rankRows(rows: readonly { readonly label: string | null; readonly count: number; readonly href?: string }[],
  options: { readonly max?: number; readonly otherHref?: string; readonly unknownWord?: string } = {}): RankedRow[] {
  const max = options.max ?? 5;
  const named = rows.filter((r) => r.label !== null && r.count > 0)
    .slice().sort((a, b) => b.count - a.count || (a.label as string).localeCompare(b.label as string));
  const out: RankedRow[] = named.slice(0, max).map((r) => ({ display: r.label as string, count: r.count, href: r.href, kind: "named" }));
  const rest = named.slice(max);
  if (rest.length > 0) out.push({ display: `Other (${rest.length})`, count: rest.reduce((n, r) => n + r.count, 0), href: options.otherHref, kind: "other" });
  const unknown = rows.filter((r) => r.label === null && r.count > 0);
  if (unknown.length > 0) {
    out.push({ display: options.unknownWord ?? "UNKNOWN", count: unknown.reduce((n, r) => n + r.count, 0), href: unknown[0].href, kind: "unknown" });
  }
  return out;
}

export function RankedBars({ title, rows }: { readonly title: string; readonly rows: readonly RankedRow[] }) {
  const total = rows.reduce((n, r) => n + r.count, 0);
  let series = 0;
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, minWidth: 0 }}>
      <Typography variant="caption" sx={{ color: m3.onSurfaceVar, letterSpacing: "0.06em", fontWeight: 600 }}>{title.toUpperCase()}</Typography>
      {rows.length === 0
        ? <Typography variant="body2" sx={{ color: m3.neutralInk, fontWeight: 600 }}>UNKNOWN</Typography>
        : <Box component="ul" aria-label={title} sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
          {rows.map((row) => {
            const fill = row.kind === "unknown" ? UNKNOWN_HATCH_CSS : row.kind === "other" ? OTHER : SERIES[series++ % SERIES.length];
            const width = total > 0 ? Math.max(2, (100 * row.count) / total) : 0;
            const body = (
              <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) minmax(64px, 1.2fr) auto", alignItems: "center", columnGap: 1 }}>
                <Typography component="span" sx={{ fontSize: 12.5, color: row.kind === "unknown" ? UNKNOWN_COLOR : m3.onSurface, overflowWrap: "anywhere" }}>{row.display}</Typography>
                <Box sx={{ height: 10, borderRadius: "3px", bgcolor: m3.sc, overflow: "hidden" }}>
                  <Box data-bar-fill sx={{ width: `${width}%`, height: "100%", background: fill }} />
                </Box>
                <Typography component="span" sx={{ fontSize: 12.5, fontWeight: 600, color: m3.onSurface, fontVariantNumeric: "tabular-nums", textAlign: "right", minWidth: 24 }}>{row.count}</Typography>
              </Box>
            );
            return (
              <Box component="li" key={`${row.kind}:${row.display}`} data-ranked-row={row.display}>
                {row.href
                  ? <Link href={row.href} underline="none" aria-label={`${row.display}: ${row.count} ${row.count === 1 ? "device" : "devices"}`}
                      sx={{ display: "block", borderRadius: "4px", "&:hover": { bgcolor: m3.scLow } }}>{body}</Link>
                  : body}
              </Box>
            );
          })}
        </Box>}
    </Box>
  );
}

/**
 * A release name as an ordered tuple, only in the two shapes the server's own major projection writes: Check Point
 * `R81.20` and PAN-OS `11.1`. Anything else is not ordered (null), so no "oldest" is guessed from an unknown shape.
 */
export function majorOrder(vendor: "check_point" | "palo_alto", label: string): readonly number[] | null {
  const m = vendor === "check_point" ? /^R(\d+)(?:\.(\d+))?$/i.exec(label.trim()) : /^(\d+)\.(\d+)$/.exec(label.trim());
  return m ? [Number(m[1]), Number(m[2] ?? 0)] : null;
}

const devices = (n: number) => `${n} ${n === 1 ? "device" : "devices"}`;

/**
 * The verdict line over a vendor's bars (Fable, R-18): "N devices on more than one major version; oldest X". One major
 * reads "all on one major version"; devices without a read major are named as UNKNOWN, never folded into a version.
 */
export function majorVersionVerdict(vendor: "check_point" | "palo_alto", majors: readonly { readonly label: string | null; readonly count: number }[]): string {
  const named = majors.filter((m) => m.label !== null && m.count > 0);
  const unknown = majors.filter((m) => m.label === null).reduce((n, m) => n + m.count, 0);
  const known = named.reduce((n, m) => n + m.count, 0);
  const suffix = unknown > 0 ? `; ${devices(unknown)} with major version UNKNOWN` : "";
  if (named.length === 0) return unknown > 0 ? `Major version UNKNOWN for ${devices(unknown)}` : "No device with a read major version";
  if (named.length === 1) return `${known === 1 ? "1 device" : `All ${known} devices`} on one major version: ${named[0].label}${suffix}`;
  const ordered = named.map((m) => ({ label: m.label as string, order: majorOrder(vendor, m.label as string) }));
  const oldest = ordered.every((m) => m.order !== null)
    ? ordered.slice().sort((a, b) => (a.order![0] - b.order![0]) || (a.order![1] - b.order![1]))[0].label
    : "UNKNOWN";
  return `${devices(known)} on more than one major version (${named.length}); oldest ${oldest}${suffix}`;
}

/**
 * The verdict line of a vendor whose versions come only as the devices report them (Fortinet, Cisco ASA, ...): no
 * major version is derived from the reported text (vendor semantics law), so the line counts reported versions only.
 */
export function reportedVersionVerdict(rows: readonly { readonly label: string | null; readonly count: number }[]): string {
  const named = rows.filter((r) => r.label !== null && r.count > 0);
  const unknown = rows.filter((r) => r.label === null).reduce((n, r) => n + r.count, 0);
  const known = named.reduce((n, r) => n + r.count, 0);
  const suffix = unknown > 0 ? `; ${devices(unknown)} ${unknown === 1 ? "reports" : "report"} no version` : "";
  if (named.length === 0) return unknown > 0 ? `No version reported by ${devices(unknown)}` : "No device";
  if (named.length === 1) return `${known === 1 ? "1 device reports" : `All ${known} devices report`} one version: ${named[0].label}${suffix}`;
  return `${devices(known)} on ${named.length} reported versions; major version not derived from the reported text${suffix}`;
}
