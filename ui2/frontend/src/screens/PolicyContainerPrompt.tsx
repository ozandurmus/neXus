import { Box, Button, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";

import { StatePanel } from "../shell/States";
import { plural } from "../shell/plural";
import { relativeAge } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import type { ContainerSummary } from "./PolicyScreen";
import { freshnessColor, snapshotFreshness } from "./PolicyRuleViewer";

/**
 * Policy > Objects and Hygiene before a container is chosen (UI review 2026-10-18, P0-4B: they were a blank pane). It says
 * what the tab needs and lists the stored containers, so the operator opens one from here. (Installation, which this once
 * also served, is a column and a section of Rules & Containers since UI review 2026-11-05, C2.)
 */
export function PolicyContainerPrompt({ tab, rows, now, open }: {
  readonly tab: "Objects" | "Hygiene";
  /** Rows with their display names already resolved by the caller. */
  readonly rows: readonly ContainerSummary[];
  readonly now: Date;
  readonly open: (row: ContainerSummary) => void;
}) {
  const title = tab === "Hygiene" ? "Select a policy container to see its hygiene findings" : "Select a policy container to see its objects";
  if (rows.length === 0) {
    return <StatePanel variant="empty" title={title}
      body="No stored policy container matches yet. Collect a management source from the tree on the left; its containers are listed here." />;
  }
  return (
    <Box component="section" aria-label="Stored containers">
      <StatePanel variant="empty" title={title}
        body={tab === "Hygiene"
          ? "Open a container to check its rules for shadowed, conflicting, disabled, unused, expired and broad rules. Where the stored evidence is insufficient the finding is UNKNOWN."
          : "Open a container to search its objects and see where each is used."} />
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, my: 1 }}>{plural(rows.length, "stored container")}.</Typography>
      <Box sx={{ overflowX: "auto" }}>
        <Table size="small" aria-label="Containers" sx={{ "& td, & th": { px: 1, py: 0.75, overflowWrap: "anywhere" } }}>
          <TableHead><TableRow>
            {["Container", "Source", "Policies", "Rules", "Last collected", ""].map((label, index) => <TableCell key={index} scope="col">{label}</TableCell>)}
          </TableRow></TableHead>
          <TableBody>
            {rows.map(row => { const freshness = snapshotFreshness(row.collectedAt, now); const ink = freshnessColor(freshness.state); return (
              <TableRow key={row.key} hover>
                <TableCell scope="row">{row.containerName}</TableCell>
                <TableCell>{row.sourceName}</TableCell>
                <TableCell>{row.policyIds.length}</TableCell>
                <TableCell>{row.rules ?? "UNKNOWN"}</TableCell>
                {/* Inked by age like the container table on the Rules tab: a month-old collection is not read as current. */}
                <TableCell title={row.collectedAt} data-freshness={freshness.state} sx={{ color: ink, fontWeight: ink ? 600 : undefined }}>
                  {row.collectedAt ? relativeAge(row.collectedAt, now) : "UNKNOWN"}</TableCell>
                <TableCell align="right"><Button size="small" aria-label={`Open ${row.containerName}`} onClick={() => open(row)}>Open</Button></TableCell>
              </TableRow>
            ); })}
          </TableBody>
        </Table>
      </Box>
    </Box>
  );
}
