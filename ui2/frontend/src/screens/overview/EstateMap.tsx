import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import type { EstateCondition, EstateConditionReason, EstateView } from "../../auth/adminApi";
import { STATUS, UNKNOWN_HATCH_CSS } from "../../shell/Charts";
import { vendorDisplayName } from "../../shell/States";
import { relativeAge } from "../../shell/time";
import { m3 } from "../../theme/m3Theme";

/** One meaning per colour across the screen (EXEC_OVERVIEW_DESIGN_2026_09_25_FABLE.md §2). */
export const EXEC = {
  red: STATUS.critical,
  amber: STATUS.warning,
  green: STATUS.good,
  blue: "#2a78d6",
  pale: "#b9d3f2",
  hatch: UNKNOWN_HATCH_CSS,
  hatchLight: "repeating-linear-gradient(45deg, #c9c7c0 0 2px, transparent 2px 5px)",
} as const;

export const CONDITION: Record<EstateCondition, { label: string; bg: string; order: number }> = {
  critical: { label: "Critical finding", bg: EXEC.red, order: 0 },
  ageing: { label: "Evidence ageing", bg: EXEC.amber, order: 1 },
  partly_assessed: { label: "Partly assessed", bg: EXEC.hatch, order: 2 },
  not_assessed: { label: "Not assessed", bg: EXEC.hatchLight, order: 3 },
  clear: { label: "Clear", bg: EXEC.green, order: 4 },
};

/** Critical findings in three shades by how many critical checks fail on the device (1, 2, 3 or more). */
const CRITICAL_SHADES = [
  { label: "1 critical check failing", min: 1, bg: "#eea0a0" },
  { label: "2 failing", min: 2, bg: "#dd5a5a" },
  { label: "3 or more failing", min: 3, bg: "#a51d1d" },
] as const;

function cellBg(d: { condition: EstateCondition; critical_fail: number | null }): string {
  if (d.condition !== "critical") return CONDITION[d.condition].bg;
  const n = d.critical_fail ?? 1;
  return n >= 3 ? CRITICAL_SHADES[2].bg : n === 2 ? CRITICAL_SHADES[1].bg : CRITICAL_SHADES[0].bg;
}

/** The tooltip line that says why a cell is not assessed: never green, never silent. */
export const NOT_ASSESSED_REASON: Record<EstateConditionReason, string> = {
  not_evaluated: "No compliance evaluation for this device",
  no_evidence: "No check has evidence yet (evidence not collected)",
  no_critical_control: "No critical control applies to this device, so no critical check was demonstrated",
};

/**
 * The "No archive" marker: a red dot with a white ring and a dark hairline in the cell's top-right corner, visible on
 * every fill (the earlier outer ring all but vanished against the pink and red critical shades).
 */
export const NO_ARCHIVE_MARK = {
  width: 9, height: 9, borderRadius: "50%", bgcolor: STATUS.critical, border: "2px solid #fff",
  boxShadow: "0 0 0 1px rgba(0, 0, 0, 0.6)", boxSizing: "content-box" as const,
};

const VENDOR_ORDER = ["check_point", "palo_alto", "fortinet", "cisco_asa", "infoblox", "radware", "bluecoat"];
const q = (params: Record<string, string>) => `?${new URLSearchParams(params).toString()}`;

/**
 * Every device as one square, grouped by vendor in the vendor's own name and sorted by condition so colours form
 * blocks; a backup target without an archive carries the corner marker. Hover names the device; a click opens it.
 */
export function EstateMap({ estate, cell = 20 }: { readonly estate: EstateView; readonly cell?: number }) {
  const groups = new Map<string, EstateView["devices"][number][]>();
  for (const d of estate.devices) {
    const list = groups.get(d.vendor) ?? [];
    list.push(d);
    groups.set(d.vendor, list);
  }
  const vendors = [...groups.keys()].sort((a, b) => {
    const ia = VENDOR_ORDER.indexOf(a);
    const ib = VENDOR_ORDER.indexOf(b);
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || a.localeCompare(b);
  });
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 3, alignItems: "flex-start" }}>
        {vendors.map((v) => {
          const list = [...(groups.get(v) ?? [])].sort((a, b) => CONDITION[a.condition].order - CONDITION[b.condition].order
            || (b.critical_fail ?? 0) - (a.critical_fail ?? 0) || Number(b.no_archive) - Number(a.no_archive));
          const cols = Math.max(4, Math.ceil(Math.sqrt(list.length * 1.6)));
          return (
            <Box key={v} sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 13, fontWeight: 700, mb: 1, color: m3.onSurface }}>
                {vendorDisplayName(v)} <Typography component="span" sx={{ fontSize: 13, fontWeight: 500, color: m3.onSurfaceVar }}>{list.length}</Typography>
              </Typography>
              <Box sx={{ display: "grid", gridTemplateColumns: `repeat(${cols}, ${cell}px)`, gap: "4px" }}>
                {list.map((d) => (
                  <Tooltip key={d.device_id} arrow title={
                    <Box sx={{ fontSize: 12 }}>
                      <b>{d.hostname ?? d.device_id.slice(0, 8)}</b><br />
                      {vendorDisplayName(d.vendor)}{d.model ? ` · ${d.model}` : ""}<br />
                      {CONDITION[d.condition].label}{d.critical_fail ? ` · ${d.critical_fail} critical checks failing` : ""}<br />
                      {d.condition === "not_assessed" && d.condition_reason ? <>{NOT_ASSESSED_REASON[d.condition_reason]}<br /></> : null}
                      {d.no_archive ? <>Backup target without a stored archive<br /></> : null}
                      Last read {d.last_read_at ? relativeAge(d.last_read_at) : "never"}
                    </Box>}>
                    <Box component="a" href={q({ screen: "inventory", device_id: d.device_id })}
                      data-condition={d.condition} data-no-archive={d.no_archive ? "true" : undefined}
                      aria-label={`${d.hostname ?? d.device_id}: ${CONDITION[d.condition].label}${d.no_archive ? ", no stored archive" : ""}`}
                      sx={{ width: cell, height: cell, borderRadius: "3px", display: "block", position: "relative", background: cellBg(d),
                        transition: "transform 120ms", "&:hover": { transform: "scale(1.35)" } }}>
                      {d.no_archive && <Box data-testid="no-archive-mark" component="span"
                        sx={{ ...NO_ARCHIVE_MARK, position: "absolute", top: -3, right: -3, display: "block" }} />}
                    </Box>
                  </Tooltip>
                ))}
              </Box>
            </Box>
          );
        })}
      </Box>
      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2.25, alignItems: "center" }}>
        {CRITICAL_SHADES.map((sh, i) => {
          const n = estate.devices.filter((d) => d.condition === "critical" && (i === 2 ? (d.critical_fail ?? 1) >= 3 : (d.critical_fail ?? 1) === sh.min)).length;
          return n > 0 ? (
            <Box key={sh.label} sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
              <Box sx={{ width: 12, height: 12, borderRadius: "3px", background: sh.bg }} />
              <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{sh.label} <b style={{ color: m3.onSurface }}>{n}</b></Typography>
            </Box>
          ) : null;
        })}
        {(Object.keys(CONDITION) as EstateCondition[]).filter((c) => c !== "critical" && (estate.counts[c] ?? 0) > 0).map((c) => (
          <Box key={c} sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            <Box sx={{ width: 12, height: 12, borderRadius: "3px", background: CONDITION[c].bg }} />
            <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{CONDITION[c].label} <b style={{ color: m3.onSurface }}>{estate.counts[c]}</b></Typography>
          </Box>
        ))}
        {estate.no_archive > 0 && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            <Box component="span" sx={{ ...NO_ARCHIVE_MARK, display: "inline-block" }} />
            <Link href={q({ screen: "backups", artefact: "none" })} underline="hover" sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
              No archive <b style={{ color: m3.onSurface }}>{estate.no_archive}</b></Link>
          </Box>
        )}
      </Box>
    </Box>
  );
}

/**
 * One hero fact: a big numeral with its population, one proportional bar, one plain sentence, one link.
 *
 * The bar keeps its meaning (only the shortfall is drawn, so a long bar is never a good sign) and now names it: each
 * sliver has an in-line label under the bar ("7 without an archive"), so the colour is never the only signal
 * (UI review 2026-10-18, F-13). A fact with nothing to name draws no sliver and no label.
 */
export function HeroFact({ label, value, of, unit, bad, color, sentence, href, sliverLabel, segments, unknownWord = "n/a", aside, ariaLabel }: {
  readonly label: string; readonly value: number | null; readonly of: number | null; readonly unit: string;
  readonly bad: number; readonly color: string; readonly sentence: string; readonly href: string;
  /** What the sliver counts, for example "without an archive"; printed after the count under the bar. */
  readonly sliverLabel?: string;
  /** Several named slivers instead of the single `bad` one (HA readiness: not ready, unknown). */
  readonly segments?: ReadonlyArray<{ readonly count: number; readonly color: string; readonly label: string }>;
  /** The word shown instead of the numeral when the figure is not known. */
  readonly unknownWord?: string;
  /** A marker beside the label (for example a Stale chip). */
  readonly aside?: ReactNode;
  readonly ariaLabel?: string;
}) {
  const known = value !== null && of !== null && of > 0;
  const slivers = (segments ?? (bad > 0 ? [{ count: bad, color, label: sliverLabel ?? "" }] : [])).filter((x) => x.count > 0);
  return (
    <Box component="a" href={href} aria-label={ariaLabel} sx={{ display: "block", textDecoration: "none", color: "inherit", p: 1.5, borderRadius: "10px",
      "&:hover": { bgcolor: m3.scLow } }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Typography sx={{ fontSize: 12.5, fontWeight: 700, letterSpacing: "0.06em", color: m3.onSurfaceVar, textTransform: "uppercase" }}>{label}</Typography>
        {aside}
      </Box>
      <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, mt: 0.25 }}>
        <Typography sx={{ fontSize: known ? 44 : 28, fontWeight: 700, lineHeight: 1.05, color: known ? m3.onSurface : m3.neutralInk }}>{known ? value : unknownWord}</Typography>
        {known && <Typography sx={{ fontSize: 16, color: m3.onSurfaceVar }}>of {of} {unit}</Typography>}
      </Box>
      {known && (
        <Box sx={{ display: "flex", height: 8, borderRadius: 4, overflow: "hidden", bgcolor: m3.sc, my: 1 }}>
          {slivers.map((x) => <Box key={x.label || x.color} sx={{ width: `${(100 * x.count) / (of ?? 1)}%`, background: x.color }} />)}
        </Box>
      )}
      {known && slivers.some((x) => x.label) && (
        <Box sx={{ display: "flex", flexWrap: "wrap", columnGap: 1.5, mb: 0.5 }}>
          {slivers.filter((x) => x.label).map((x) => (
            <Box key={x.label} sx={{ display: "flex", alignItems: "center", gap: 0.6 }}>
              <Box sx={{ width: 9, height: 9, borderRadius: "2px", background: x.color, flex: "none" }} />
              <Typography sx={{ fontSize: 12.5, color: m3.onSurface }}><b>{x.count}</b> {x.label}</Typography>
            </Box>
          ))}
        </Box>
      )}
      <Typography sx={{ fontSize: 14, color: m3.onSurfaceVar }}>{sentence}</Typography>
    </Box>
  );
}

/** A population bar in named parts, counts printed; hatched parts are "not evidenced". */
export function PopulationBar({ parts, height = 14 }: {
  readonly parts: ReadonlyArray<{ name: string; count: number; bg: string; href?: string }>; readonly height?: number;
}) {
  const total = parts.reduce((n, p) => n + p.count, 0);
  if (total === 0) return null;
  return (
    <Box>
      <Box sx={{ display: "flex", height, borderRadius: `${height / 2}px`, overflow: "hidden", gap: "2px" }}>
        {parts.filter((p) => p.count > 0).map((p) => (
          <Tooltip key={p.name} title={`${p.name}: ${p.count}`}>
            <Box component={p.href ? "a" : "div"} href={p.href} sx={{ width: `${(100 * p.count) / total}%`, background: p.bg, display: "block" }} />
          </Tooltip>
        ))}
      </Box>
      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1.75, mt: 0.75 }}>
        {parts.filter((p) => p.count > 0).map((p) => (
          <Box key={p.name} sx={{ display: "flex", alignItems: "center", gap: 0.6 }}>
            <Box sx={{ width: 10, height: 10, borderRadius: "2px", background: p.bg }} />
            <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{p.name} <b style={{ color: m3.onSurface }}>{p.count}</b></Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
