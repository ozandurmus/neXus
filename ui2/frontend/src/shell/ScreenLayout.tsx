import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";

/** Shared building blocks the six product screens use for their empty state. */

export function ScreenHeader({
  title,
  subtitle,
  filters,
  actions,
}: {
  readonly title: string;
  readonly subtitle: string;
  /** List filters beside the title (PO, 2026-09-24): they narrow the screen's list, never its detail. */
  readonly filters?: ReactNode;
  readonly actions?: ReactNode;
}) {
  return (
    <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", flexWrap: "wrap", columnGap: 3, rowGap: 1.5, px: 0.5, pb: 0.5 }}>
      <Stack spacing={0.75} sx={{ flex: "none" }}>
        <Typography variant="h2">{title}</Typography>
        <Typography variant="body1" sx={{ color: m3.onSurfaceVar }}>{subtitle}</Typography>
      </Stack>
      {filters ? <Box sx={{ flex: "1 1 520px", minWidth: 0, maxWidth: 760 }}>{filters}</Box> : null}
      {actions ? <Box sx={{ display: "flex", gap: 1.25, alignItems: "center", flex: "none" }}>{actions}</Box> : null}
    </Box>
  );
}

/**
 * A headline figure. What the caller counted, or the word for why there is none (review §3): `UNKNOWN` when the
 * read failed or nothing is evidenced, `NOT EVALUATED` when the evaluation has not run -- never "0" or "—"
 * standing in for either.
 */
export function MetricCard({ title, value, note, state, reason }: {
  readonly title: string;
  readonly value?: number | string | null;
  readonly note: string;
  readonly state?: "ok" | "unknown" | "not_evaluated";
  readonly reason?: string;
}) {
  const word = state === "not_evaluated" ? "NOT EVALUATED" : (state === "unknown" || value === null || value === undefined) ? "UNKNOWN" : null;
  return (
    <Card sx={{ bgcolor: m3.scLowest, boxShadow: "none", border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2.25,
                display: "flex", flexDirection: "column", gap: 1 }}>
      <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar }}>{title}</Typography>
      {word
        ? <Typography title={reason} sx={{ fontSize: 22, lineHeight: "32px", fontWeight: 650, color: m3.neutralInk, letterSpacing: "0.02em" }}>{word}</Typography>
        : <Typography variant="h1">{value}</Typography>}
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{note}</Typography>
    </Card>
  );
}

export function EmptyPanel({
  title,
  body,
  children,
}: {
  readonly title: string;
  readonly body: string;
  readonly children?: ReactNode;
}) {
  return (
    <Card sx={{ bgcolor: m3.scLow, borderRadius: "10px", p: 2.25, boxShadow: "none",
                border: `1px solid ${m3.outlineVar}`, display: "flex", flexDirection: "column", gap: 1 }}>
      <Typography variant="h4">{title}</Typography>
      <Typography variant="body2" sx={{ maxWidth: 640 }}>{body}</Typography>
      {children}
    </Card>
  );
}

export function MetricGrid({ children }: { readonly children: ReactNode }) {
  return (
    <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(230px, 1fr))" }}>
      {children}
    </Box>
  );
}

/** The canvas's list-plus-detail split (Inventory, Configuration, Administration). */
export function ListDetail({ list, detail }: { readonly list: ReactNode; readonly detail: ReactNode }) {
  return (
    <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(280px, 352px) minmax(0, 1fr)", gap: 2, alignItems: "start" }}>
      {list}
      {detail}
    </Box>
  );
}

export function ScreenRoot({ children }: { readonly children: ReactNode }) {
  return (
    <Box sx={{ p: 4, pt: 1, flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 3 }}>
      {children}
    </Box>
  );
}
