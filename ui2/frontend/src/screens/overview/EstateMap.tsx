import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import type { EstateCondition, EstateView } from "../../auth/adminApi";
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

const VENDOR_ORDER = ["check_point", "palo_alto", "fortinet", "cisco_asa", "infoblox", "radware", "bluecoat"];
const q = (params: Record<string, string>) => `?${new URLSearchParams(params).toString()}`;

/**
 * Every device as one square, grouped by vendor in the vendor's own name and sorted by condition so colours form
 * blocks; a backup target without an archive carries a red ring. Hover names the device; a click opens it.
 */
export function EstateMap({ estate, cell = 16 }: { readonly estate: EstateView; readonly cell?: number }) {
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
            || Number(b.no_archive) - Number(a.no_archive));
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
                      {d.no_archive ? "Backup target without a stored archive" : ""}{d.no_archive ? <br /> : null}
                      Last read {d.last_read_at ? relativeAge(d.last_read_at) : "never"}
                    </Box>}>
                    <Box component="a" href={q({ screen: "inventory", device_id: d.device_id })}
                      aria-label={`${d.hostname ?? d.device_id}: ${CONDITION[d.condition].label}`}
                      sx={{ width: cell, height: cell, borderRadius: "3px", display: "block", background: CONDITION[d.condition].bg,
                        boxShadow: d.no_archive ? `0 0 0 2px ${m3.scLowest}, 0 0 0 4px ${EXEC.red}` : "none",
                        transition: "transform 120ms", "&:hover": { transform: "scale(1.35)" } }} />
                  </Tooltip>
                ))}
              </Box>
            </Box>
          );
        })}
      </Box>
      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2.25, alignItems: "center" }}>
        {(Object.keys(CONDITION) as EstateCondition[]).filter((c) => (estate.counts[c] ?? 0) > 0).map((c) => (
          <Box key={c} sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            <Box sx={{ width: 12, height: 12, borderRadius: "3px", background: CONDITION[c].bg }} />
            <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{CONDITION[c].label} <b style={{ color: m3.onSurface }}>{estate.counts[c]}</b></Typography>
          </Box>
        ))}
        {estate.no_archive > 0 && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            <Box sx={{ width: 10, height: 10, borderRadius: "3px", boxShadow: `0 0 0 2px ${EXEC.red}` }} />
            <Link href={q({ screen: "backups", artefact: "none" })} underline="hover" sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
              No archive <b style={{ color: m3.onSurface }}>{estate.no_archive}</b></Link>
          </Box>
        )}
      </Box>
    </Box>
  );
}

/** One hero fact: a big numeral with its population, one proportional bar, one plain sentence, one link. */
export function HeroFact({ label, value, of, unit, bad, color, sentence, href }: {
  readonly label: string; readonly value: number | null; readonly of: number | null; readonly unit: string;
  readonly bad: number; readonly color: string; readonly sentence: string; readonly href: string;
}) {
  const known = value !== null && of !== null && of > 0;
  return (
    <Box component="a" href={href} sx={{ display: "block", textDecoration: "none", color: "inherit", p: 1.5, borderRadius: "10px",
      "&:hover": { bgcolor: m3.scLow } }}>
      <Typography sx={{ fontSize: 12.5, fontWeight: 700, letterSpacing: "0.06em", color: m3.onSurfaceVar, textTransform: "uppercase" }}>{label}</Typography>
      <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, mt: 0.25 }}>
        <Typography sx={{ fontSize: 44, fontWeight: 700, lineHeight: 1.05, color: m3.onSurface }}>{known ? value : "n/a"}</Typography>
        {known && <Typography sx={{ fontSize: 16, color: m3.onSurfaceVar }}>of {of} {unit}</Typography>}
      </Box>
      {known && (
        <Box sx={{ display: "flex", height: 8, borderRadius: 4, overflow: "hidden", bgcolor: m3.sc, my: 1 }}>
          <Box sx={{ width: `${(100 * bad) / (of ?? 1)}%`, bgcolor: color }} />
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
