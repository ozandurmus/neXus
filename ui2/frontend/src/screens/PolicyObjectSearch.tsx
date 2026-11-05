import { useEffect, useRef, useState } from "react";
import { Box, Button, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";

import { getPolicyObjects, type PolicyInventoryObject } from "../auth/adminApi";
import { StatePanel } from "../shell/States";
import { plural } from "../shell/plural";
import { m3 } from "../theme/m3Theme";
import { scheduleLabel } from "./PolicyRuleViewer";
import type { ContainerSummary } from "./PolicyScreen";

/** Containers asked at once, containers searched at most, and objects kept per container. The server answers one container per call. */
export const SEARCH_CONCURRENCY = 3;
export const SEARCH_CONTAINER_CAP = 100;
export const PER_CONTAINER_SHOWN = 5;
export const MIN_TERM = 2;

interface Hit { readonly row: ContainerSummary; readonly objects: readonly PolicyInventoryObject[]; readonly total: number }

/**
 * Objects, searched across containers (UI review 2026-11-05, C2; Argon R-16): the objects view answered one container at
 * a time, so "where is this host defined" meant opening up to 59 containers. This asks every stored container the same
 * question the per-container search asks (the existing domain objects read, same term, first page) and lists the
 * containers that answer, with their first matches. It is a client-side fan-out over the existing read -- bounded
 * (three at a time, a hundred containers at most) and honest about its limits: a container that could not be read is
 * counted as **not searched** (never as "no match"), and a search cut off by the cap says so. A server-side cross-container
 * endpoint would replace the fan-out without changing this screen's words.
 */
export function PolicyObjectSearch({ containers, open }: { readonly containers: readonly ContainerSummary[]; readonly open: (row: ContainerSummary) => void }) {
  const [text, setText] = useState("");
  const [term, setTerm] = useState("");
  const [hits, setHits] = useState<readonly Hit[]>([]);
  const [progress, setProgress] = useState({ done: 0, total: 0, failed: 0 });
  const run = useRef(0);
  useEffect(() => {
    const timer = window.setTimeout(() => setTerm(text.trim()), 400);
    return () => window.clearTimeout(timer);
  }, [text]);
  useEffect(() => {
    const id = ++run.current;
    if (term.length < MIN_TERM) { setHits([]); setProgress({ done: 0, total: 0, failed: 0 }); return; }
    const scope = containers.slice(0, SEARCH_CONTAINER_CAP);
    setHits([]); setProgress({ done: 0, total: scope.length, failed: 0 });
    let next = 0;
    const worker = async () => {
      while (next < scope.length && id === run.current) {
        const row = scope[next++];
        try {
          const page = await getPolicyObjects(row.sourceId, row.containerId, 0, term);
          if (id !== run.current) return;
          const objects = Array.isArray(page?.objects) ? page.objects : [];
          if (!Array.isArray(page?.objects)) throw new Error("Invalid object response");
          setProgress(p => ({ ...p, done: p.done + 1 }));
          if (page.total > 0) setHits(h => [...h, { row, objects: objects.slice(0, PER_CONTAINER_SHOWN), total: page.total }]);
        } catch {
          if (id === run.current) setProgress(p => ({ ...p, done: p.done + 1, failed: p.failed + 1 }));
        }
      }
    };
    void Promise.all(Array.from({ length: Math.min(SEARCH_CONCURRENCY, scope.length) }, worker));
    return () => { run.current++; };
  }, [term, containers]);
  const searching = term.length >= MIN_TERM && progress.done < progress.total;
  const matches = hits.reduce((n, h) => n + h.total, 0);
  const capped = containers.length > SEARCH_CONTAINER_CAP;
  return (
    <Box component="section" role="search" aria-label="Search objects across containers" sx={{ mb: 2 }}>
      <TextField size="small" label="Search all containers" value={text} sx={{ minWidth: 320 }} inputProps={{ maxLength: 1000 }}
        helperText={containers.length === 0 ? "No stored container to search" : `Name or value, across ${plural(Math.min(containers.length, SEARCH_CONTAINER_CAP), "container")}; at least ${MIN_TERM} characters`}
        disabled={containers.length === 0} onChange={e => setText(e.target.value)} />
      {term.length >= MIN_TERM && <Box role="status" sx={{ mt: 1 }}>
        <Typography variant="body2">
          {searching ? `Searching… ${progress.done} of ${progress.total} containers` : `${plural(matches, "match")} in ${plural(hits.length, "container")} · searched ${progress.total - progress.failed} of ${progress.total}`}
          {progress.failed > 0 && ` · ${plural(progress.failed, "container")} could not be searched (their result is UNKNOWN, not "no match")`}
          {capped && ` · only the first ${SEARCH_CONTAINER_CAP} of ${containers.length} containers are searched`}
        </Typography>
      </Box>}
      {!searching && term.length >= MIN_TERM && hits.length === 0 && progress.total > 0 && progress.failed === 0 &&
        <StatePanel variant="empty" title="No object matches in any stored container" body="Every container was searched. Objects are listed from the stored policy snapshots." />}
      {hits.length > 0 && <Box sx={{ overflowX: "auto", mt: 1 }}>
        <Table size="small" aria-label="Objects matching across containers" sx={{ "& td, & th": { px: 1, py: 0.75, overflowWrap: "anywhere" } }}>
          <TableHead><TableRow>{["Container", "Name", "Type", "Value / schedule", ""].map((label, i) => <TableCell key={i} scope="col">{label}</TableCell>)}</TableRow></TableHead>
          <TableBody>
            {[...hits].sort((a, b) => a.row.containerName.localeCompare(b.row.containerName)).flatMap(hit => hit.objects.map((object, index) => (
              <TableRow key={`${hit.row.key}:${object.id}`}>
                <TableCell>{index === 0 ? <><Typography variant="body2" sx={{ fontWeight: 600 }}>{hit.row.containerName}</Typography>
                  <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{hit.row.sourceName}{hit.total > PER_CONTAINER_SHOWN ? ` · ${hit.total - PER_CONTAINER_SHOWN} more here` : ""}</Typography></> : null}</TableCell>
                <TableCell>{object.name}</TableCell>
                <TableCell>{object.type}</TableCell>
                <TableCell sx={{ maxWidth: 320 }}>{object.schedule ? scheduleLabel(object.schedule) : object.values?.join("; ") || "—"}</TableCell>
                <TableCell align="right">{index === 0 && <Button size="small" aria-label={`Open ${hit.row.containerName}`} onClick={() => open(hit.row)}>Open container</Button>}</TableCell>
              </TableRow>
            )))}
          </TableBody>
        </Table>
      </Box>}
    </Box>
  );
}
