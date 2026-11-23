import { useState, type ReactNode } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import type { ReadinessCheck, ReadinessMember } from "../../auth/adminApi";
import { m3 } from "../../theme/m3Theme";
import { ReadinessChecksTable } from "../ReadinessChecksTable";
import { groupChecks, type CheckCell, type CheckEntry } from "./failoverRowModel";

/**
 * The readiness checks of one unit, grouped by what they mean for a failover (REDESIGN section 4): Blocking, Unknown / not
 * evaluated, Warnings (open), Passed and Information (one line each, opened to the two-column table). Every check lands in
 * exactly one group, so the five counts add up to the number of checks. Not evaluated is its own group and never a pass.
 */
export function ChecksByConsequence({ checks, members = [], vendor, observedAt, rowAction }: {
  checks: ReadinessCheck[]; members?: ReadinessMember[]; vendor?: string; observedAt?: string | null;
  /** An action the server offers for one check row (the PAN config sync on check 4), under its title. */
  rowAction?: (checkNo: number, failed: boolean) => ReactNode;
}) {
  const groups = groupChecks(checks, members, vendor, observedAt);
  const total = new Set(checks.map(c => c.checkNo)).size;
  const open = [
    { key: "blocking", title: "Blocking", entries: groups.blocking, border: m3.error, note: "These stop a failover." },
    { key: "unknown", title: "Unknown / not evaluated", entries: groups.unknown, border: m3.outline,
      note: "Not established: this is not a pass. A failover repeats the check and stops if it cannot be established." },
    { key: "warnings", title: "Warnings", entries: groups.warnings, border: m3.warning, note: "Shown for your judgement; they do not block." },
  ] as const;
  const closed = [
    { key: "passed", title: "Passed", entries: groups.passed },
    { key: "info", title: "Information", entries: groups.info },
  ] as const;
  if (checks.length === 0) return <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>No observations yet</Typography>;
  return <Box aria-label="Readiness checks by consequence" sx={{ display: "grid", gap: 1.5 }}>
    <Typography variant="caption" data-check-counts sx={{ color: m3.onSurfaceVar }}>
      {total} checks: {groups.blocking.length} blocking · {groups.unknown.length} unknown · {groups.warnings.length} warnings · {groups.passed.length} passed · {groups.info.length} information
    </Typography>
    {open.filter(group => group.entries.length > 0).map(group => <Box key={group.key} component="section" aria-label={group.title} data-check-group={group.key}
      sx={{ borderLeft: `4px solid ${group.border}`, pl: 1.5, py: 0.5 }}>
      <Typography variant="subtitle2">{group.title} ({group.entries.length})</Typography>
      <Typography variant="caption" sx={{ color: m3.onSurfaceVar, display: "block", mb: 0.5 }}>{group.note}</Typography>
      {group.entries.map(entry => <EntryRow key={entry.no} entry={entry}
        action={rowAction?.(entry.no, group.key === "blocking")} />)}
    </Box>)}
    {closed.filter(group => group.entries.length > 0).map(group => {
      const nos = new Set(group.entries.map(entry => entry.no));
      return <Box key={group.key} component="details" data-check-group={group.key} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 1.5, px: 1.5, py: 0.75 }}>
        <Box component="summary" sx={{ cursor: "pointer" }}>
          <Box component="span" sx={{ fontWeight: 600 }}>{group.title} ({group.entries.length})</Box>
          <Box component="span" sx={{ color: m3.onSurfaceVar }}>: {group.entries.map(entry => entry.title).join(", ")}</Box>
        </Box>
        <Box sx={{ mt: 1 }}><ReadinessChecksTable compact vendor={vendor} members={members} observedAt={observedAt}
          checks={checks.filter(c => nos.has(c.checkNo))} rowAction={rowAction} /></Box>
      </Box>;
    })}
  </Box>;
}

function EntryRow({ entry, action }: { entry: CheckEntry; action?: ReactNode }) {
  const [first, second] = entry.cells;
  return <Box data-check-entry={entry.no} sx={{ display: "grid", gridTemplateColumns: "minmax(140px, 22%) 1fr 1fr", columnGap: 1.5, py: 0.75,
    borderTop: `1px solid ${m3.outlineVar}` }}>
    <Box><Typography variant="body2" sx={{ fontWeight: 600 }}>{entry.title}</Typography>{action}</Box>
    {entry.same
      ? <Box sx={{ gridColumn: "2 / span 2" }}><Cell cell={first} caption="Both members" /></Box>
      : <><Cell cell={first} /><Cell cell={second} /></>}
  </Box>;
}

function Cell({ cell, caption }: { cell: CheckCell; caption?: string }) {
  const [more, setMore] = useState(false);
  return <Box data-member-cell={cell.memberLabel} data-failed={cell.failed ? "true" : undefined}
    sx={{ px: 1, py: 0.5, borderRadius: 1, ...(cell.failed ? { bgcolor: m3.errorContainer, color: m3.onErrorContainer } : {}) }}>
    <Typography variant="caption" component="div" sx={{ color: cell.failed ? "inherit" : m3.onSurfaceVar }}>
      {caption ?? `${cell.memberLabel} · ${cell.role}`}</Typography>
    <Typography variant="body2">{cell.headline}</Typography>
    {cell.detail && <>
      <Button size="small" aria-expanded={more} onClick={() => setMore(value => !value)} sx={{ p: 0, minWidth: 0, textTransform: "none" }}>Details</Button>
      {more && <Box component="pre" data-check-detail sx={{ m: 0, mt: 0.5, fontFamily: "monospace", fontSize: 12, whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{cell.detail}</Box>}
    </>}
  </Box>;
}
