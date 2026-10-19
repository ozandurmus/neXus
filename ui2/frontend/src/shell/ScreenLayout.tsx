import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";
import { TYPE, type Density, type ScreenType } from "../theme/tokens";
import { DensityScope } from "./displayMode";
import { KpiCard, KpiRow, type KpiCardProps } from "./KpiCard";
import { StatePanel } from "./States";

/** Shared building blocks the product screens use: the screen header, the root, and the older names of the unified components. */

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
      {/* The title block shrinks and its subtitle wraps (post-release review 2026-10-18, R1): a two-sentence tool contract
          used to run unbroken past the viewport and scroll the whole page sideways at 1440 px. */}
      <Stack data-testid="screen-header-title" spacing={0.75} sx={{ flex: "0 1 auto", minWidth: 0, maxWidth: 880 }}>
        <Typography variant="h2" sx={{ ...TYPE.pageTitle }}>{title}</Typography>
        <Typography variant="body1" sx={{ color: m3.onSurfaceVar, whiteSpace: "normal", overflowWrap: "anywhere" }}>{subtitle}</Typography>
      </Stack>
      {filters ? <Box sx={{ flex: "1 1 520px", minWidth: 0, maxWidth: 760 }}>{filters}</Box> : null}
      {/* The action buttons wrap at 1000 px instead of widening the page (post-release review 2026-10-18, R1 check). */}
      {actions ? <Box sx={{ display: "flex", gap: 1.25, alignItems: "center", flexWrap: "wrap", flex: "0 1 auto", minWidth: 0, justifyContent: "flex-end" }}>{actions}</Box> : null}
    </Box>
  );
}

/**
 * A headline figure under its older name: the one `KpiCard`. `title` is the card's label, `note` its denominator
 * sentence; `UNKNOWN` when the read failed or nothing is evidenced, `Not evaluated` when the evaluation has not run.
 */
export function MetricCard({ title, note, ...rest }: Omit<KpiCardProps, "label" | "sentence"> & { readonly title: string; readonly note: string }) {
  return <KpiCard label={title} sentence={note} {...rest} />;
}

/**
 * A state card under its older name: the one `StatePanel`, empty variant, with a heading-level title (a screen's own
 * empty state) and anything the caller puts inside it below the sentence.
 */
export function EmptyPanel({
  title,
  body,
  children,
}: {
  readonly title: string;
  readonly body: string;
  readonly children?: ReactNode;
}) {
  return <StatePanel variant="empty" title={title} body={body} titleAs="h4" announce="none">{children}</StatePanel>;
}

/** The KPI row under its older name. */
export function MetricGrid({ children }: { readonly children: ReactNode }) {
  return <KpiRow>{children}</KpiRow>;
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

/**
 * The root of one screen. Its density follows the screen type (design language 2026-10-19): a list screen is
 * compact, a summary, object or tool page comfortable; a caller may name the density outright. Default: comfortable.
 */
export function ScreenRoot({ children, screenType, density }: { readonly children: ReactNode; readonly screenType?: ScreenType; readonly density?: Density }) {
  return (
    <DensityScope screenType={screenType ?? "summary"} density={density}>
      <Box sx={{ p: 4, pt: 1, flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 3 }}>
        {children}
      </Box>
    </DensityScope>
  );
}
