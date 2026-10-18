import { Box, Button, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from "@mui/material";

import { StatePanel } from "../shell/States";
import { plural } from "../shell/plural";
import { relativeAge } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import type { ContainerSummary } from "./PolicyScreen";

const GATEWAYS_SHOWN = 3;

/**
 * Policy > Installation and Objects before a container is chosen (UI review 2026-10-18, P0-4B: both were a blank pane).
 * It says what the tab needs and lists the stored containers with the gateways their packages target, so the operator
 * opens one from here. The targets are package intent (management intent, not runtime truth): the column says so and
 * the reported installation per package appears once a container is open.
 */
export function PolicyContainerPrompt({ tab, rows, now, open }: {
  readonly tab: "Objects" | "Installation" | "Hygiene";
  /** Rows with their display names already resolved by the caller. */
  readonly rows: readonly ContainerSummary[];
  readonly now: Date;
  readonly open: (row: ContainerSummary) => void;
}) {
  const title = tab === "Installation" ? "Select a policy container to see where its packages are installed"
    : tab === "Hygiene" ? "Select a policy container to see its hygiene findings" : "Select a policy container to see its objects";
  if (rows.length === 0) {
    return <StatePanel variant="empty" title={title}
      body="No stored policy container matches yet. Collect a management source from the tree on the left; its containers are listed here." />;
  }
  const installation = tab === "Installation";
  return (
    <Box component="section" aria-label={installation ? "Stored containers by installation target" : "Stored containers"}>
      <StatePanel variant="empty" title={title}
        body={installation
          ? "Open a container to see each package's installation targets and what the management server reports as installed. The gateways below are package intent, not runtime enforcement."
          : tab === "Hygiene"
            ? "Open a container to check its rules for shadowed, conflicting, disabled, unused, expired and broad rules. Where the stored evidence is insufficient the finding is UNKNOWN."
            : "Open a container to search its objects and see where each is used."} />
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, my: 1 }}>{plural(rows.length, "stored container")}.</Typography>
      <Box sx={{ overflowX: "auto" }}>
        <Table size="small" aria-label={installation ? "Containers and installation targets" : "Containers"} sx={{ "& td, & th": { px: 1, py: 0.75, overflowWrap: "anywhere" } }}>
          <TableHead><TableRow>
            {["Container", "Source", "Policies", installation ? "Installed on (targets)" : "Rules", "Last collected", ""].map((label, index) => <TableCell key={index} scope="col">{label}</TableCell>)}
          </TableRow></TableHead>
          <TableBody>
            {rows.map(row => (
              <TableRow key={row.key} hover>
                <TableCell scope="row">{row.containerName}</TableCell>
                <TableCell>{row.sourceName}</TableCell>
                <TableCell>{row.policyIds.length}</TableCell>
                <TableCell>{installation
                  ? row.gateways.length === 0 ? "Unassigned"
                    : <Tooltip title={row.gateways.join(", ")}><span>{row.gateways.slice(0, GATEWAYS_SHOWN).join(", ")}{row.gateways.length > GATEWAYS_SHOWN ? ` +${row.gateways.length - GATEWAYS_SHOWN} more` : ""}</span></Tooltip>
                  : row.rules ?? "UNKNOWN"}</TableCell>
                <TableCell title={row.collectedAt}>{row.collectedAt ? relativeAge(row.collectedAt, now) : "UNKNOWN"}</TableCell>
                <TableCell align="right"><Button size="small" aria-label={`Open ${row.containerName}`} onClick={() => open(row)}>Open</Button></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
    </Box>
  );
}
