import { Box, Typography } from "@mui/material";

import { KpiCard, KpiRow } from "../shell/KpiCard";
import { plural } from "../shell/plural";
import { relativeAge } from "../shell/time";
import type { ContainerSummary } from "./PolicyScreen";
import { snapshotFreshness } from "./PolicyRuleViewer";

/**
 * The fleet summary Policy > Hygiene opens on while no container is chosen (UI review 2026-11-05, C2; Argon R-16): the
 * tab used to be a bare prompt. It states only what the loaded catalog proves about the estate's stored policy -- how many
 * containers and packages there are, how many rule snapshots are stale (the hygiene verdict is only as current as the
 * snapshot it reads), how many containers no gateway is assigned to -- and says plainly that the finding counts themselves
 * are computed per policy, so they are **not evaluated** here rather than shown as zero. Opening a container runs the
 * analysis for it, as before.
 */
export function PolicyHygieneFleet({ rows, now }: { readonly rows: readonly ContainerSummary[]; readonly now: Date }) {
  if (rows.length === 0) return null;
  const packages = rows.reduce((sum, row) => sum + row.policyIds.length, 0);
  const stale = rows.filter((row) => { const state = snapshotFreshness(row.collectedAt, now).state; return state === "stale" || state === "old"; });
  const unknownAge = rows.filter((row) => snapshotFreshness(row.collectedAt, now).state === "unknown").length;
  const unassigned = rows.filter((row) => row.gateways.length === 0).length;
  const oldest = rows.map((row) => Date.parse(row.collectedAt)).filter(Number.isFinite).sort((a, b) => a - b)[0];
  return (
    <Box component="section" aria-label="Hygiene fleet summary" sx={{ display: "flex", flexDirection: "column", gap: 2, mb: 2 }}>
      <KpiRow>
        <KpiCard label="Containers" value={rows.length} sentence={`${plural(packages, "package")} stored`} />
        <KpiCard label="Stale snapshots" value={stale.length} sentence={unknownAge
          ? `of ${rows.length} containers older than 24 h · ${unknownAge} with no collection time`
          : `of ${rows.length} containers older than 24 h${oldest !== undefined && stale.length ? ` · oldest ${relativeAge(new Date(oldest).toISOString(), now)}` : ""}`}
          detail={stale.length ? "Hygiene reads the snapshot as it was, not as the policy is now." : undefined} />
        <KpiCard label="Without a gateway" value={unassigned} sentence={`of ${rows.length} containers have no installation target`} />
        <KpiCard label="Findings" state="not_evaluated"
          reason="Hygiene findings (shadowed, conflicting, broad, unused, expired, disabled) are computed for one policy at a time."
          sentence="computed per container: open one below" />
      </KpiRow>
      <Typography variant="body2" sx={{ color: "text.secondary", maxWidth: 820 }}>
        Hygiene is judged per container. Open one from the list to see its findings; a finding the stored evidence cannot decide is shown as UNKNOWN, never as clean.
      </Typography>
    </Box>
  );
}
