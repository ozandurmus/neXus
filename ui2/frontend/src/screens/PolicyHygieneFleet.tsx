import { useEffect, useState } from "react";
import { Box, Typography } from "@mui/material";

import { getPolicyHygieneSummary, type PolicyHygieneSummary } from "../auth/adminApi";
import { PolicyHygieneSummaryTable } from "./PolicyHygieneSummaryTable";

import { KpiCard, KpiRow } from "../shell/KpiCard";
import { plural } from "../shell/plural";
import { relativeAge } from "../shell/time";
import type { ContainerSummary } from "./PolicyScreen";
import { snapshotFreshness } from "./PolicyRuleViewer";

/** Reason -> rule count as ranked list text: the largest `limit` entries ("reason (N rules)"), "+N more" for the rest; empty when the service sent no map. */
export function reasonList(reasons: Record<string, number> | undefined, limit: number): { readonly shown: readonly string[]; readonly more: number } {
  if (!reasons) return { shown: [], more: 0 };
  const ranked = Object.entries(reasons).filter(([, n]) => Number.isFinite(n) && n > 0)
    .sort(([a, x], [b, y]) => y - x || a.localeCompare(b));
  return { shown: ranked.slice(0, limit).map(([reason, n]) => `${reason} (${plural(n, "rule")})`), more: Math.max(0, ranked.length - limit) };
}

function WhyUnknown({ summary }: { readonly summary: PolicyHygieneSummary }) {
  const why = reasonList(summary.unknownReasons, 5), behind = reasonList(summary.blockerReasons, 3);
  if (why.shown.length === 0) return null;
  return (
    <Box aria-label="Why UNKNOWN" sx={{ maxWidth: 820 }}>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>Why UNKNOWN</Typography>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Rules whose shadowing verdict the stored evidence cannot decide, by reason: {why.shown.join("; ")}{why.more > 0 ? `; +${why.more} more` : ""}.
      </Typography>
      {behind.shown.length > 0 && (
        <Typography variant="body2" sx={{ color: "text.secondary" }}>
          Behind an earlier rule that is itself UNKNOWN for: {behind.shown.join("; ")}{behind.more > 0 ? `; +${behind.more} more` : ""}.
        </Typography>
      )}
    </Box>
  );
}

/**
 * The fleet summary Policy > Hygiene opens on while no container is chosen (UI review 2026-11-05, C2; Argon R-16): the
 * tab used to be a bare prompt. It states only what the loaded catalog proves about the estate's stored policy -- how many
 * containers and packages there are, how many rule snapshots are stale (the hygiene verdict is only as current as the
 * snapshot it reads), how many containers no gateway is assigned to -- and says plainly that the finding counts themselves
 * are computed per policy, so they are **not evaluated** here rather than shown as zero. Opening a container runs the
 * analysis for it, as before.
 */
export function PolicyHygieneFleet({ rows, now, open }: { readonly rows: readonly ContainerSummary[]; readonly now: Date; readonly open?: (containerId: string, sourceId: string) => void }) {
  // The summary is computed in the background from the stored snapshots; while any container is still pending it is re-read.
  const [summary, setSummary] = useState<PolicyHygieneSummary | null>(null);
  useEffect(() => {
    let active = true, timer: ReturnType<typeof setTimeout> | undefined;
    const load = () => getPolicyHygieneSummary().then(result => {
      if (!active || !Array.isArray(result.containers)) return;
      setSummary(result);
      if (result.pending > 0) timer = setTimeout(load, 15000);
    }).catch(() => { /* the card stays "not evaluated"; nothing is shown as zero */ });
    load();
    return () => { active = false; if (timer) clearTimeout(timer); };
  }, [rows.length]);
  if (rows.length === 0) return null;
  const analysed = summary ? summary.containers.length - summary.pending : 0;
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
        {summary && analysed > 0
          ? <KpiCard label="Findings" value={Object.entries(summary.totals).filter(([key]) => key !== "unknown").reduce((sum, [, n]) => sum + n, 0)}
            sentence={`rule findings across ${plural(analysed, "analysed container")}${summary.totals.unknown ? ` · ${summary.totals.unknown} UNKNOWN counted separately` : ""}`} />
          : <KpiCard label="Findings" state="not_evaluated"
            reason="The fleet hygiene summary is computed in the background from the stored snapshots and has not produced a result yet."
            sentence="not evaluated yet" />}
      </KpiRow>
      {summary && summary.totals.unknown > 0 && <WhyUnknown summary={summary} />}
      <Typography variant="body2" sx={{ color: "text.secondary", maxWidth: 820 }}>
        Counts are rules carrying a finding of each class, read from each container's stored snapshot (time shown); a finding the stored evidence cannot decide is counted as UNKNOWN, never as clean. Open a container below for the rules behind the numbers.
      </Typography>
      {summary && <PolicyHygieneSummaryTable summary={summary} open={open} />}
    </Box>
  );
}
