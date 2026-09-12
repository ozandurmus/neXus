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
  actions,
}: {
  readonly title: string;
  readonly subtitle: string;
  readonly actions?: ReactNode;
}) {
  return (
    <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", gap: 3, px: 0.5, pb: 0.5 }}>
      <Stack spacing={0.75}>
        <Typography variant="h2">{title}</Typography>
        <Typography variant="body1" sx={{ color: m3.onSurfaceVar }}>{subtitle}</Typography>
      </Stack>
      {actions ? <Box sx={{ display: "flex", gap: 1.25, alignItems: "center", flex: "none" }}>{actions}</Box> : null}
    </Box>
  );
}

export function MetricCard({ title, note }: { readonly title: string; readonly note: string }) {
  return (
    <Card sx={{ bgcolor: m3.scLowest, boxShadow: m3.e1, borderRadius: "16px", p: 2.5,
                display: "flex", flexDirection: "column", gap: 1.25 }}>
      <Typography sx={{ fontSize: 16, fontWeight: 500, letterSpacing: "0.15px" }}>{title}</Typography>
      <Typography variant="h1">0</Typography>
      <Typography variant="body2">{note}</Typography>
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
    <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2.5, boxShadow: "none",
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
    <Box sx={{ flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: "minmax(280px, 352px) minmax(0, 1fr)", gap: 2 }}>
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
