import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { KPI_FIGURE, LABEL_UPPER, RADIUS, ROLE_COLORS, TYPE, UNKNOWN_HATCH } from "../theme/tokens";
import { statusWord } from "./presenters";

/**
 * The one KPI card (design language 2026-10-19, component 3): a label, a figure, a denominator sentence and at most
 * one link; always above the table, at most four in a row (`KpiRow`). The figure is what the caller counted, or the
 * word for why there is none: `UNKNOWN` when the read failed or nothing is evidenced, `Not evaluated` when the
 * evaluation has not run -- never 0 or a dash standing in for either. A figure is neutral; it never takes a colour.
 */
export interface KpiCardProps {
  readonly label: string;
  readonly value?: number | string | null;
  /** Small words beside the figure (`ready of 40 clusters`); shown only with a figure. */
  readonly unit?: string;
  /** A second line under the figure, for the counts that do not fit the big font. */
  readonly detail?: string;
  /** The denominator sentence: `of 130 targets · as of 02:04`. */
  readonly sentence: string;
  readonly state?: "ok" | "unknown" | "not_evaluated";
  /** Why the figure is UNKNOWN or not evaluated, on hover. */
  readonly reason?: string;
  /** The one link: where the figure's rows are. */
  readonly href?: string;
  readonly linkLabel?: string;
  /** A status pill beside the label (`No data gaps`): a word with a colour, never the figure itself. */
  readonly badge?: ReactNode;
  /** Replaces the figure block entirely (a small chart, a sentence); the label and the sentence stay. */
  readonly children?: ReactNode;
}

export function KpiCard({ label, value, unit, detail, sentence, state, reason, href, linkLabel, badge, children }: KpiCardProps) {
  const word = state === "not_evaluated" ? statusWord("NOT_EVALUATED") : (state === "unknown" || value === null || value === undefined) ? "UNKNOWN" : null;
  return (
    <Card data-kpi={label} sx={{ bgcolor: m3.scLowest, boxShadow: "none", border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, p: 2,
                display: "flex", flexDirection: "column", gap: 0.75, minWidth: 0 }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1, minWidth: 0 }}>
        <Typography component="p" sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar, m: 0 }}>{label}</Typography>
        {badge}
      </Box>
      {children ?? (word
        ? <Typography component="p" title={reason} data-kpi-word sx={{ fontSize: TYPE.pageTitle.fontSize, lineHeight: "32px", fontWeight: 600, color: ROLE_COLORS.unknown.fg,
            letterSpacing: "0.02em", m: 0, alignSelf: "flex-start", px: 1, borderRadius: `${RADIUS.pill}px`, bgcolor: ROLE_COLORS.unknown.bg, backgroundImage: UNKNOWN_HATCH }}>{word}</Typography>
        : <Box sx={{ display: "flex", alignItems: "baseline", flexWrap: "wrap", columnGap: 1 }}>
            <Typography component="p" data-kpi-figure sx={{ ...KPI_FIGURE, color: m3.onSurface, m: 0 }}>{value}</Typography>
            {unit ? <Typography component="span" sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>{unit}</Typography> : null}
          </Box>)}
      {detail && !word ? <Typography component="p" sx={{ ...TYPE.label, color: m3.neutralInk, m: 0 }}>{detail}</Typography> : null}
      <Typography component="p" sx={{ ...TYPE.label, color: m3.onSurfaceVar, m: 0 }}>{sentence}</Typography>
      {href ? <Link href={href} underline="hover" sx={{ ...TYPE.label, fontWeight: 500, color: m3.primary, alignSelf: "flex-start" }}>{linkLabel ?? "All →"}</Link> : null}
    </Card>
  );
}

/** Up to four KPI cards on one row above a table; at 1000 px they wrap two by two. */
export function KpiRow({ children, ariaLabel }: { readonly children: ReactNode; readonly ariaLabel?: string }) {
  return (
    <Box aria-label={ariaLabel} sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))", maxWidth: 1180 }}>
      {children}
    </Box>
  );
}
