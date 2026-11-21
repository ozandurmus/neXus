import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";

import type { PolicyHygieneSummary } from "../auth/adminApi";
import { formatStamp } from "../shell/time";
import { plural } from "../shell/plural";

/** The counted classes in display order; UNKNOWN is its own column and is never folded into a clean count. */
export const HYGIENE_SUMMARY_COLUMNS: readonly (readonly [string, string])[] = [
  ["shadowed", "Shadowed"], ["conflict", "Conflicting"], ["disabled", "Disabled"], ["unused", "Unused"], ["expired", "Expired"], ["broad", "Broad"], ["unknown", "UNKNOWN"],
];

/**
 * Fleet hygiene table (Policy > Hygiene, no container open): one row per stored container with the number of rules carrying
 * a finding of each class, the rule count and the time of the snapshot the counts were read from, and a total row. A container
 * whose analysis has not finished is shown as "not analysed yet", never as zeros. Counts are rules per class (a rule with two
 * findings counts once in each column), so the columns are not additive per rule.
 */
export function PolicyHygieneSummaryTable({ summary, open }: { readonly summary: PolicyHygieneSummary; readonly open?: (containerId: string, sourceId: string) => void }) {
  const cell = { px: 1, py: 0.75 } as const;
  return (
    <Box component="section" aria-label="Hygiene by container" sx={{ overflowX: "auto" }}>
      <Table size="small" aria-label="Hygiene findings per container">
        <TableHead><TableRow>
          <TableCell scope="col" sx={cell}>Container</TableCell>
          <TableCell scope="col" sx={cell}>Snapshot</TableCell>
          <TableCell scope="col" align="right" sx={cell}>Rules</TableCell>
          {HYGIENE_SUMMARY_COLUMNS.map(([key, label]) => <TableCell key={key} scope="col" align="right" sx={cell}>{label}</TableCell>)}
        </TableRow></TableHead>
        <TableBody>
          {summary.containers.map(row => (
            <TableRow key={`${row.sourceId}/${row.containerId}`} hover={!!open} onClick={open ? () => open(row.containerId, row.sourceId) : undefined} sx={open ? { cursor: "pointer" } : undefined}>
              <TableCell sx={cell}>{row.containerName}<Typography component="span" variant="caption" sx={{ display: "block", color: "text.secondary" }}>{row.sourceName}</Typography></TableCell>
              <TableCell sx={cell}>{formatStamp(row.snapshotAt)}</TableCell>
              {row.state === "READY" && row.counts
                ? <>
                  <TableCell align="right" sx={cell}>{row.rules ?? 0}</TableCell>
                  {HYGIENE_SUMMARY_COLUMNS.map(([key]) => <TableCell key={key} align="right" sx={cell}>{row.counts![key] ?? 0}</TableCell>)}
                </>
                : <TableCell colSpan={HYGIENE_SUMMARY_COLUMNS.length + 1} sx={{ ...cell, color: "text.secondary" }}>Not analysed yet</TableCell>}
            </TableRow>
          ))}
          <TableRow aria-label="Total">
            <TableCell sx={{ ...cell, fontWeight: 600 }}>Total · {plural(summary.containers.length - summary.pending, "container")}</TableCell>
            <TableCell sx={cell} />
            <TableCell align="right" sx={{ ...cell, fontWeight: 600 }}>{summary.rules}</TableCell>
            {HYGIENE_SUMMARY_COLUMNS.map(([key]) => <TableCell key={key} align="right" sx={{ ...cell, fontWeight: 600 }}>{summary.totals[key] ?? 0}</TableCell>)}
          </TableRow>
        </TableBody>
      </Table>
      {summary.pending > 0 && <Typography variant="caption" role="status" sx={{ color: "text.secondary", display: "block", mt: 0.5 }}>
        {plural(summary.pending, "container")} not analysed yet; totals cover the analysed ones only.
      </Typography>}
    </Box>
  );
}
