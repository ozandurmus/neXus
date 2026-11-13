import { useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";

import { STATUS, STATUS_INK } from "../../shell/Charts";
import { DISPLAY_TZ_LABEL, formatUtc, relativeAge } from "../../shell/time";
import { MONO, m3 } from "../../theme/m3Theme";
import { SEVERITY_WORD, severityCounts, visibleRows, type AttentionResult, type AttentionRow, type AttentionSeverity } from "./attentionRules";

/**
 * The Overview attention list (docs/design/OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md, DRAFT): one row per finding,
 * most severe first -- severity word, what, where (masked names), since, one link. The rules are `attentionRules.ts`;
 * this component only draws them.
 */

const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, p: { xs: 2, md: 2.25 }, boxShadow: "none" } as const;

const TONE: Readonly<Record<AttentionSeverity, { bg: string; ink: string }>> = {
  critical: { bg: `${STATUS.critical}26`, ink: STATUS_INK.critical },
  serious: { bg: `${STATUS.serious}26`, ink: STATUS_INK.serious },
  warning: { bg: `${STATUS.warning}2e`, ink: STATUS_INK.warning },
  unknown: { bg: m3.sc, ink: m3.neutralInk },
};

function SeverityWord({ severity, compact = false }: { readonly severity: AttentionSeverity; readonly compact?: boolean }) {
  const t = TONE[severity];
  return (
    <Box component="span" data-severity={severity} sx={{ display: "inline-block", px: 1, py: 0.25, borderRadius: "6px", fontSize: compact ? 12 : 11.5,
      fontWeight: 700, whiteSpace: "nowrap", textAlign: "center", bgcolor: t.bg, color: t.ink, minWidth: compact ? 0 : 76 }}>
      {SEVERITY_WORD[severity]}
    </Box>
  );
}

const dateOnly = (iso: string) => formatUtc(iso, false).slice(0, 10);

/** "checked 2 h ago", "nearest expires 2026-11-22", "never backed up". */
export function sinceText(r: Pick<AttentionRow, "at" | "atKind" | "atLabel">, now = new Date()): string {
  if (!r.at) return r.atLabel === "—" ? "—" : r.atLabel.startsWith("never") ? r.atLabel : `${r.atLabel} UNKNOWN`;
  return r.atKind === "date" ? `${r.atLabel} ${dateOnly(r.at)}` : `${r.atLabel} ${relativeAge(r.at, now)}`;
}

/** "FW-ALFA-05, FW-BRAVO-02, FW-CHARLIE-03 and 9 more"; the lead word when the names are a sample. */
export function whereText(r: Pick<AttentionRow, "names" | "more" | "namesLead">): string {
  if (r.names.length === 0) return "";
  return `${r.namesLead ? `${r.namesLead} ` : ""}${r.names.join(", ")}${r.more > 0 ? ` and ${r.more} more` : ""}`;
}

function Row({ r, first }: { readonly r: AttentionRow; readonly first: boolean }) {
  const where = whereText(r);
  return (
    <Box component="li" data-attention={r.key} sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "92px minmax(0, 1fr) auto" },
      columnGap: 1.75, rowGap: 0.5, py: 1.25, px: 0.75, alignItems: "start", borderTop: first ? "none" : `1px solid ${m3.outlineVar}`,
      "&:hover": { bgcolor: m3.scLow } }}>
      <Box><SeverityWord severity={r.severity} /></Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: 14.5, fontWeight: 600, color: m3.onSurface }}>{r.what}</Typography>
        {where && <Typography data-where sx={{ fontSize: 12.5, fontFamily: MONO, color: m3.onSurface, overflowWrap: "anywhere" }}>{where}</Typography>}
        {r.detail && <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>{r.detail}</Typography>}
      </Box>
      <Box sx={{ textAlign: { xs: "left", sm: "right" }, whiteSpace: "nowrap" }}>
        <Tooltip title={r.at ? `${formatUtc(r.at)} ${DISPLAY_TZ_LABEL}` : ""}>
          <Typography data-since sx={{ fontSize: 12.5, fontFamily: MONO, color: m3.onSurfaceVar }}>{sinceText(r)}</Typography>
        </Tooltip>
        <Link href={r.href} underline="hover" sx={{ fontSize: 13, fontWeight: 600 }} aria-label={`${r.linkLabel}: ${r.what}`}>{r.linkLabel} →</Link>
      </Box>
    </Box>
  );
}

export function AttentionList({ result }: { readonly result: AttentionResult | null }) {
  const [expanded, setExpanded] = useState(false);
  const rows = result?.rows ?? [];
  const shown = visibleRows(rows, expanded);
  const hiddenSources = (result?.sources ?? []).filter((s) => s.state === "restricted" || s.state === "skipped").map((s) => s.label);
  const evaluated = result ? formatUtc(result.evaluatedAt, false).slice(11, 16) : null;
  return (
    <Card sx={CARD} data-testid="attention-list" component="section" aria-label="What to look at today">
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 1, flexWrap: "wrap", mb: 1 }}>
        <Typography component="h2" sx={{ fontSize: 16, fontWeight: 700 }}>What to look at today</Typography>
        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap" }} data-testid="attention-counts">
          {severityCounts(rows).map((c) => (
            <Box key={c.severity} component="span" sx={{ fontSize: 12, fontWeight: 700, px: 1, py: 0.25, borderRadius: "6px", bgcolor: TONE[c.severity].bg, color: TONE[c.severity].ink }}>
              {c.count} {SEVERITY_WORD[c.severity]}
            </Box>
          ))}
        </Box>
      </Box>
      {!result ? (
        <Typography sx={{ color: m3.onSurfaceVar, py: 1 }}>Reading the fleet…</Typography>
      ) : rows.length === 0 ? (
        <Box data-testid="attention-empty" sx={{ py: 1.5, px: 0.75 }}>
          <Typography sx={{ fontSize: 15, fontWeight: 700, color: m3.onSurface }}>Nothing needs attention.</Typography>
          <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}>
            Evaluated {evaluated} {DISPLAY_TZ_LABEL} from {result.sources.filter((s) => s.state === "ok").length} sources.
            {hiddenSources.length > 0 ? ` Not shown for your role: ${hiddenSources.join(", ")}.` : ""}
          </Typography>
        </Box>
      ) : (
        <>
          <Box component="ol" sx={{ listStyle: "none", m: 0, p: 0 }}>
            {shown.map((r, i) => <Row key={r.key} r={r} first={i === 0} />)}
          </Box>
          <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1, flexWrap: "wrap", mt: 1 }}>
            {rows.length > shown.length || expanded ? (
              <Link component="button" underline="hover" onClick={() => setExpanded((v) => !v)} sx={{ fontSize: 13, fontWeight: 600 }}>
                {expanded ? "Show fewer" : `Show all ${rows.length}`}
              </Link>
            ) : <span />}
            <Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>
              Evaluated {evaluated} {DISPLAY_TZ_LABEL}{hiddenSources.length > 0 ? ` · not shown for your role: ${hiddenSources.join(", ")}` : ""}
            </Typography>
          </Box>
        </>
      )}
    </Card>
  );
}
