import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, Unknown, isRestricted } from "../../shell/States";
import { plural } from "../../shell/plural";
import { actorLabel } from "../../shell/presenters";
import type { Tone } from "../../shell/tone";
import { m3 } from "../../theme/m3Theme";
import { CONFIRM_PARTICIPANT_NOTE, HIDDEN_MASKED_TEXT, StatementShape } from "./MatrixCellDetail";
import type { ZoneNotice, ZoneTabProps } from "./ZoneMatrixScreen";
import { Hash, Hint, SectionTitle, knownSeverityWord, knownStatementWord, mono, panelSx } from "./zoneMatrixUi";
import { SummaryReadFailure, useEnsureSummary, type ZoneSummaryHandle } from "./zoneMatrixSummaryUi";
import {
  PUBLISH_FINAL_TEXT, addressCount, confirmCellReference, discardZoneDraft, matrixOf, publishCheckText, publishZoneDraft,
  referenceStatusWord, zoneErrorCode, zoneErrorLines, zoneRoleLabel,
  type CellDiffRow, type CellDiffSide, type FamilySizes, type LooseningRow, type MatrixCellView, type PublishCheck,
  type SummaryResolvedByVersion, type SummaryResolvedFinding, type ZoneDiff, type ZoneDiffRow, type ZoneMove,
  type ZoneRevision, type ZoneVersionDetail, type ZoneVersionState, type ZoneVersionSummary,
} from "./zoneMatrixApi";

export const RATIONALE_LIMIT = 2000;
/** Maximum explicit cells rendered in the reference-confirmation table before printing "+N more". */
export const REF_CELLS_CAP = 50;

const STATE_WORD: Record<ZoneVersionState, { readonly word: string; readonly tone: Tone }> = {
  DRAFT: { word: "Draft", tone: "attn" },
  ACTIVE: { word: "Active", tone: "brand" },
  RETIRED: { word: "Retired", tone: "neutral" },
  DISCARDED: { word: "Discarded", tone: "neutral" },
};

const CHANGE_KIND: Record<string, string> = {
  DRAFT_CREATE: "Draft started", ZONE_CREATE: "Zone added", ZONE_UPDATE: "Zone changed", ZONE_DELETE: "Zone removed",
  CELL_SET: "Cell set", CELL_CLEAR: "Cell cleared", DEFAULT_SET: "Version default changed",
  SERVICE_SET_SAVE: "Service set saved", SERVICE_SET_DELETE: "Service set removed",
  TEMPLATE_IMPORT: "Template imported", REFERENCE_CONFIRM: "Reference confirmed",
  DRAFT_DISCARD: "Draft discarded", PUBLISH: "Published",
};

const familyWord = (family: string) => (family === "IPV6" ? "IPv6" : "IPv4");

/** The built-in pseudo-zone is a state, worded as one. */
const placeName = (name: string) => (name === "UNASSIGNED" ? "unassigned" : name);

/** "256 IPv4 addresses move from Z-APP to Z-DB" (contract 3.2). */
export function moveText(move: ZoneMove): string {
  return `${addressCount(move.addresses)} ${familyWord(move.family)} ${move.addresses === "1" ? "address moves" : "addresses move"} from ${placeName(move.from)} to ${placeName(move.to)}`;
}

/** One loosening change in plain words (contract 3.5). */
export function looseningText(item: LooseningRow): string {
  const src = item.source_name ? placeName(item.source_name) : "UNKNOWN";
  const dst = item.destination_name ? placeName(item.destination_name) : "UNKNOWN";
  switch (item.kind) {
    case "DEFAULT_CHANGED": return "the version default changed";
    case "STATEMENT_RELAXED": return `${src} -> ${dst}: statement relaxed`;
    case "SERVICES_ADDED": return `${src} -> ${dst}: services added`;
    case "SEVERITY_DOWNGRADED": return `${src} -> ${dst}: severity lowered`;
    case "ADDRESSES_MOVED": return `${addressCount(item.addresses)} ${item.addresses === "1" ? "address moves" : "addresses move"} from ${src} to ${dst}`;
    case "UNASSIGNED_SHRUNK": return `${addressCount(item.addresses)} unassigned ${item.addresses === "1" ? "address moves" : "addresses move"} into ${dst}, which is less strict`;
    default: return `${item.kind}`;
  }
}

function cellSideText(side: CellDiffSide | null): string {
  if (!side) return "No endpoints";
  const mode = side.explicit ? "explicit" : "default";
  const services = side.services.length > 0 ? `: ${side.services.join(", ")}` : "";
  const ref = side.reference_status ? ` · reference ${referenceStatusWord(side.reference_status).toLowerCase()}` : "";
  return `${knownStatementWord(side.statement)} (${knownSeverityWord(side.severity)}, ${mode})${services}${ref}`;
}

/** One changed zone as a headline and its detail lines (the sizes have their own columns). */
export function zoneChangeLines(row: ZoneDiffRow): { readonly headline: string; readonly details: readonly string[] } {
  const headline = row.change === "ADDED" ? `Zone ${row.name_after ?? row.zone_id} added`
    : row.change === "REMOVED" ? `Zone ${row.name_before ?? row.zone_id} removed`
    : `Zone ${row.name_after ?? row.zone_id} changed${row.name_before && row.name_before !== row.name_after ? ` (was ${row.name_before})` : ""}`;
  const details: string[] = [];
  if (row.change === "CHANGED" && row.role_before !== row.role_after) details.push(`Zone role: ${zoneRoleLabel(row.role_before)} to ${zoneRoleLabel(row.role_after)}`);
  if (row.change === "ADDED" && row.role_after) details.push(`Zone role: ${zoneRoleLabel(row.role_after)}`);
  const blocks = (label: string, value: readonly string[] | number) => {
    if (typeof value === "number") { if (value > 0) details.push(`${label}: ${value}`); }
    else if (value.length > 0) details.push(`${label}: ${value.join(", ")}`);
  };
  blocks("Blocks added", row.blocks_added);
  blocks("Blocks removed", row.blocks_removed);
  return { headline, details };
}

const sizeChange = (before: string | undefined, after: string | undefined) =>
  before === after ? addressCount(after) : `${addressCount(before)} to ${addressCount(after)}`;

const sizesText = (sizes: FamilySizes | undefined) => `IPv4 ${addressCount(sizes?.IPV4)}, IPv6 ${addressCount(sizes?.IPV6)}`;

/**
 * The Versions tab (contract 7.1): first the draft for review (its diff with loosening changes and moved addresses, the
 * publish panel, discard, its revisions) for the people the server shows it to, reference confirmation for compliance,
 * the history of published versions, and the per-version lists of findings resolved by a matrix version change.
 */
export function VersionsTab({ data, refresh, notify, summary }: ZoneTabProps & { readonly summary: ZoneSummaryHandle }) {
  // The resolved-by-version counts and lists come from the stored summary; it is read once, here or by another summary tab.
  useEnsureSummary(summary);
  const resolved = summary.data !== null && summary.data.state === "READY" ? summary.data.resolved_by_version_change : null;
  const { can, draft, diff, publish_check: publishCheck, draft_exists: exists } = data.draft;
  const refTarget = draft ?? data.active;
  const canConfirmRef = Boolean(can.confirm_reference) && !(exists && draft === null);
  return (
    <Stack gap={3}>
      <SummaryReadFailure summary={summary} />
      {draft === null ? (
        // A reader the server withholds the draft from is told so above the tabs; nothing else to say here.
        exists ? null : (
          <StatePanel variant="empty" title="There is no draft" announce="none"
            body={can.edit ? "A draft starts when a zone is added or changed on the Zones tab." : "Nothing is waiting to be published."} />
        )
      ) : (
        <Stack gap={2}>
          <SectionTitle note={<>Draft r{draft.revision} against the active version. Content hash <Hash value={draft.content_sha256} />.</>}>Draft review</SectionTitle>
          {diff ? <DraftChanges diff={diff} /> : null}
          {can.publish ? (
            <PublishPanel key={`${draft.revision}:${draft.content_sha256}`} draft={draft} check={publishCheck}
              rationaleRequired={Boolean(diff?.rationale_required)} refresh={refresh} notify={notify} />
          ) : null}
          {can.edit ? <DiscardDraft key={draft.revision} draft={draft} refresh={refresh} notify={notify} /> : null}
          <Revisions revisions={draft.revisions} />
        </Stack>
      )}
      {refTarget ? (
        <ReferenceConfirmationSection version={refTarget} revision={draft ? draft.revision : null}
          canConfirm={canConfirmRef} draftWithheld={Boolean(exists && draft === null)} refresh={refresh} notify={notify} />
      ) : null}
      <History versions={data.versions} resolved={resolved} />
      <ResolvedByVersionLists summary={summary} />
    </Stack>
  );
}

const isPublishedState = (state: ZoneVersionState | string): boolean => state === "ACTIVE" || state === "RETIRED";

const refStatusSpec = (status: string | null | undefined): { readonly label: string; readonly tone: Tone } => {
  switch (status) {
    case "CONFIRMED": return { label: "Confirmed", tone: "brand" };
    case "PROPOSED": return { label: "Proposed", tone: "attn" };
    case "EMPTY": return { label: "Empty", tone: "neutral" };
    default: return { label: "UNKNOWN", tone: "unknown" };
  }
};

function ReferenceConfirmationSection({ version, revision, canConfirm, draftWithheld, refresh, notify }: {
  readonly version: ZoneVersionDetail;
  readonly revision: number | null;
  readonly canConfirm: boolean;
  readonly draftWithheld: boolean;
  readonly refresh: (hideBlocks?: boolean) => void;
  readonly notify: (notice: ZoneNotice | null) => void;
}) {
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const matrix = matrixOf(version);
  const proposedCount = matrix.cells.filter((c) => c.reference_status === "PROPOSED").length;
  const shownCells = matrix.cells.slice(0, REF_CELLS_CAP);
  const moreCells = Math.max(0, matrix.cells.length - shownCells.length);

  const confirmOne = async (cell: MatrixCellView) => {
    const key = `${cell.source}->${cell.destination}`;
    setBusyKey(key);
    notify(null);
    try {
      await confirmCellReference(revision, cell.source, cell.destination, cell.cell_sha256);
      notify({ kind: "done", lines: [`Reference confirmed for ${cell.source_name} -> ${cell.destination_name}.`] });
      refresh();
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
      if (zoneErrorCode(err) === "REQUEST_CHANGED") refresh();
    } finally {
      setBusyKey(null);
    }
  };

  const columns: DataColumn<MatrixCellView>[] = [
    { key: "pair", header: "Cell", wrap: true, render: (c) => `${c.source_name || "UNKNOWN"} -> ${c.destination_name || "UNKNOWN"}` },
    {
      key: "statement", header: "Statement and severity", width: 230, wrap: true,
      render: (c) => <><StatementShape statement={c.statement} />{knownStatementWord(c.statement)} · {knownSeverityWord(c.severity)}</>,
    },
    {
      key: "reference", header: "Regulation reference", wrap: true,
      render: (c) => (c.regulation_reference === null ? HIDDEN_MASKED_TEXT : (c.regulation_reference || "None")),
    },
    {
      key: "status", header: "Reference status", width: 150,
      render: (c) => {
        const spec = refStatusSpec(c.reference_status);
        return <StatusChip dense tone={spec.tone} label={spec.label} />;
      },
    },
    {
      key: "confirmed", header: "Confirmed by", width: 230, wrap: true,
      render: (c) => (c.reference_status === "CONFIRMED"
        ? (c.confirmed_by
          ? <>{actorLabel(c.confirmed_by).label}{c.confirmed_at ? <> · <Ts at={c.confirmed_at} /></> : " · time UNKNOWN"}</>
          : "UNKNOWN")
        : "Not confirmed"),
    },
    ...(canConfirm ? [{
      key: "action", header: "Confirm", width: 170, title: () => undefined,
      render: (c: MatrixCellView) => (c.reference_status === "PROPOSED" ? (
        <M3Button emphasis="tonal" disabled={busyKey !== null} onClick={() => { void confirmOne(c); }}>
          Confirm reference
        </M3Button>
      ) : (
        <span>{c.reference_status === "CONFIRMED" ? "Already confirmed" : c.reference_status === "EMPTY" ? "No reference" : "UNKNOWN"}</span>
      )),
    } satisfies DataColumn<MatrixCellView>] : []),
  ];

  const scopeNote = version.state === "DRAFT"
    ? `Explicit cells in draft r${version.revision} (draft, not evaluated). Only a compliance administrator confirms a proposed regulation reference.`
    : draftWithheld
      ? `A draft is being prepared; the cells below are from active version ${version.number ?? "UNKNOWN"}.`
      : `Explicit cells in active version ${version.number ?? "UNKNOWN"}.`;

  return (
    <Box component="section" aria-label="Reference confirmation" data-testid="versions-reference-confirmation" sx={panelSx}>
      <SectionTitle note={scopeNote}>Reference confirmation</SectionTitle>
      <Typography variant="body2" data-testid="versions-reference-summary" sx={{ fontWeight: 600 }}>
        {matrix.cells_confirmed} of {matrix.cells_total} explicit {matrix.cells_total === 1 ? "cell" : "cells"} confirmed by compliance
        {` (${matrix.cells_with_reference} with regulation reference, ${proposedCount} proposed).`}
      </Typography>
      {canConfirm && proposedCount > 0 ? <Hint>{CONFIRM_PARTICIPANT_NOTE}</Hint> : null}
      <DataTable ariaLabel="Cell reference confirmations" columns={columns} rows={shownCells}
        rowKey={(c) => `${c.source}->${c.destination}`}
        emptyText="No explicit cells in this version; every pair follows the version default." />
      {moreCells > 0 ? (
        <Typography variant="body2" data-testid="versions-reference-more" sx={{ color: m3.onSurfaceVar }}>
          +{moreCells} more explicit {moreCells === 1 ? "cell" : "cells"} not shown.
        </Typography>
      ) : null}
    </Box>
  );
}

export interface ResolvedVersionGroup {
  readonly versionKey: string;
  readonly versionId: string | null;
  readonly versionNumber: number | null;
  readonly allTimeCount: number | null;
  readonly findings: readonly SummaryResolvedFinding[];
}

/** Groups `resolved_by_version_change` per resolving matrix version, combining `by_version` counts and `last_7_days` rows. */
export function buildResolvedByVersionGroups(resolved: SummaryResolvedByVersion): readonly ResolvedVersionGroup[] {
  const groups = new Map<string, {
    versionKey: string;
    versionId: string | null;
    versionNumber: number | null;
    allTimeCount: number | null;
    findings: SummaryResolvedFinding[];
  }>();

  for (const row of resolved.by_version) {
    if (row.count <= 0) continue;
    const key = row.version_id || "UNKNOWN";
    groups.set(key, {
      versionKey: key,
      versionId: row.version_id || null,
      versionNumber: row.version_number,
      allTimeCount: row.count,
      findings: [],
    });
  }

  for (const f of resolved.last_7_days) {
    const key = f.resolved_in_version_id
      ?? (f.resolved_in_version_number !== null ? `num:${f.resolved_in_version_number}` : "UNKNOWN");
    const existing = groups.get(key);
    if (existing) {
      if (existing.versionNumber === null && f.resolved_in_version_number !== null) {
        existing.versionNumber = f.resolved_in_version_number;
      }
      existing.findings.push(f);
    } else {
      groups.set(key, {
        versionKey: key,
        versionId: f.resolved_in_version_id,
        versionNumber: f.resolved_in_version_number,
        allTimeCount: null,
        findings: [f],
      });
    }
  }

  return [...groups.values()].sort((a, b) => {
    if (a.versionNumber !== null && b.versionNumber !== null && a.versionNumber !== b.versionNumber) {
      return b.versionNumber - a.versionNumber;
    }
    if (a.versionNumber !== null && b.versionNumber === null) return -1;
    if (a.versionNumber === null && b.versionNumber !== null) return 1;
    return a.versionKey.localeCompare(b.versionKey);
  });
}

function resolvedRuleLabel(f: { readonly rule_number: number | null; readonly rule_name: string | null; readonly rule_uid: string }): string {
  const num = f.rule_number === null ? "#?" : `#${f.rule_number}`;
  return `${num} ${f.rule_name || f.rule_uid || "UNKNOWN"}`;
}

function ResolvedByVersionLists({ summary }: { readonly summary: ZoneSummaryHandle }) {
  const { data, error, rawError } = summary;
  return (
    <Box component="section" aria-label="Resolved-by-version lists" data-testid="versions-resolved-lists" sx={panelSx}>
      <SectionTitle note="Findings resolved because a published matrix version replaced an earlier one, grouped by the resolving matrix version.">
        Resolved-by-version lists
      </SectionTitle>
      {data === null ? (
        error === null ? (
          <Typography variant="body2" role="status">Reading stored summary for resolved-by-version lists…</Typography>
        ) : isRestricted(rawError) ? (
          <RestrictedPanel area="Resolved-by-version lists" />
        ) : (
          <StatePanel variant="unknown" announce="none" title="Resolved-by-version lists cannot be read" body={error}
            action={<M3Button emphasis="outlined" onClick={summary.reload}>Retry summary read</M3Button>} />
        )
      ) : data.state !== "READY" ? (
        <StatePanel variant="unknown" announce="none" title="Resolved-by-version lists not available yet"
          body={`Stored summary state: ${data.state_text}. An unavailable summary does not mean zero findings were resolved by a version change.`} />
      ) : (() => {
        const r = data.resolved_by_version_change;
        const groups = buildResolvedByVersionGroups(r);
        return (
          <Stack gap={1.25}>
            <Typography variant="body2" data-testid="versions-resolved-window-count">
              {r.total_last_7_days === 0 && groups.length === 0
                ? `0 findings resolved by a matrix version change (all time and in the recent ${r.window_days}-day window).`
                : `${r.total_last_7_days} finding${r.total_last_7_days === 1 ? "" : "s"} resolved by a matrix version change in the recent ${r.window_days}-day window (${r.returned} returned${r.capped ? `, +${r.more} more not shown` : ""}).`}
            </Typography>
            {groups.map((group) => {
              const unreturned = group.allTimeCount !== null && group.allTimeCount > group.findings.length
                ? group.allTimeCount - group.findings.length
                : 0;
              return (
                <Box key={group.versionKey} data-testid={`resolved-version-group-${group.versionKey}`}
                  sx={{ borderTop: `1px solid ${m3.outlineVar}`, pt: 1.25, display: "flex", flexDirection: "column", gap: 0.75 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    Version {group.versionNumber ?? "UNKNOWN"}:{" "}
                    {group.allTimeCount !== null
                      ? `${group.allTimeCount} ${group.allTimeCount === 1 ? "finding" : "findings"} resolved in total (all time)`
                      : "all-time total UNKNOWN"}
                    {`; ${group.findings.length} returned from the recent ${r.window_days}-day window.`}
                  </Typography>
                  {group.findings.length > 0 ? (
                    <Box component="ul" aria-label={`Findings resolved by version ${group.versionNumber ?? "UNKNOWN"}`} sx={{ m: 0, pl: 2.5 }}>
                      {group.findings.map((f, idx) => (
                        <li key={`${f.policy_source_id}/${f.rule_uid}/${idx}`}>
                          <Typography variant="body2">
                            <strong>{resolvedRuleLabel(f)}</strong>
                            {" · "}{f.policy_name || "UNKNOWN"}
                            {" · "}{f.violated_pairs.length > 0
                              ? f.violated_pairs.map((p) => `${p.source_name || "UNKNOWN"} -> ${p.destination_name || "UNKNOWN"}`).join("; ")
                              : "UNKNOWN pairs"}
                            {" · "}version {f.resolved_from_version_number ?? "UNKNOWN"} {"->"} version {f.resolved_in_version_number ?? "UNKNOWN"}
                            {" · "}{f.resolved_at ? <Ts at={f.resolved_at} /> : <Unknown reason="Resolved time is not recorded" />}
                          </Typography>
                        </li>
                      ))}
                    </Box>
                  ) : null}
                  {unreturned > 0 ? (
                    <Typography variant="body2" data-testid={`resolved-version-more-${group.versionKey}`} sx={{ color: m3.onSurfaceVar }}>
                      +{unreturned} more resolved by version {group.versionNumber ?? "UNKNOWN"} (outside the {r.window_days}-day detail window or server cap; individual rows older than {r.window_days} days are not returned by the summary route).
                    </Typography>
                  ) : null}
                </Box>
              );
            })}
          </Stack>
        );
      })()}
    </Box>
  );
}

/** The matrix versions that resolved findings (contract 3.5, ZM-36), as words: "version 4 (3), version 3 (1)". */
export function resolvedByVersionNote(resolved: SummaryResolvedByVersion): string | null {
  const parts = resolved.by_version.filter((v) => v.count > 0)
    .map((v) => `version ${v.version_number ?? "UNKNOWN"} (${v.count})`);
  if (parts.length === 0) return null;
  const total = resolved.by_version.reduce((n, v) => n + (v.count > 0 ? v.count : 0), 0);
  // by_version counts every time (no window), so the note says "in total" and never borrows the 7-day figure.
  return `${total} finding${total === 1 ? " is" : "s are"}, in total (all time), counted as resolved by a matrix version change, by matrix ${parts.length === 1 ? "version" : "versions"}: ${parts.join(", ")}.`;
}

function History({ versions, resolved }: { readonly versions: readonly ZoneVersionSummary[]; readonly resolved: SummaryResolvedByVersion | null }) {
  const resolvedCount = (v: ZoneVersionSummary): string => {
    if (resolved === null) return "—";
    const row = resolved.by_version.find((r) => r.version_id === v.version_id);
    return row ? String(row.count) : "0";
  };
  const note = resolved === null ? null : resolvedByVersionNote(resolved);
  const columns: DataColumn<ZoneVersionSummary>[] = [
    { key: "number", header: "Version", width: 110, render: (v) => (v.number !== null ? String(v.number) : `Draft r${v.revision}`) },
    { key: "state", header: "State", width: 130, render: (v) => <StatusChip dense tone={STATE_WORD[v.state]?.tone ?? "neutral"} label={STATE_WORD[v.state]?.word ?? v.state} /> },
    { key: "hash", header: "Content hash", width: 170, title: (v) => v.content_sha256, render: (v) => <Hash value={v.content_sha256} /> },
    { key: "by", header: "Published by", width: 220, title: (v) => (v.published_by ? actorLabel(v.published_by).raw ?? v.published_by : undefined),
      render: (v) => (v.published_by ? actorLabel(v.published_by).label : (isPublishedState(v.state) ? "UNKNOWN" : "Not published")) },
    { key: "at", header: "Published at", title: () => undefined,
      render: (v) => (v.published_at ? <Ts at={v.published_at} /> : (isPublishedState(v.state) ? "UNKNOWN" : "Not published")) },
    { key: "resolved", header: "Resolved by this matrix version (all time)", width: 230, wrap: true,
      title: () => "Findings that stopped being a finding because this matrix version replaced an earlier one",
      render: (v) => <span data-testid={`resolved-by-${v.version_id}`}>{resolvedCount(v)}</span> },
  ];
  return (
    <Stack gap={1}>
      <SectionTitle note="Every published version is immutable and carries the hash of its content.">Version history</SectionTitle>
 {note ? <Typography variant="body2" data-testid="resolved-by-version-note">{note}</Typography> : null}
      <DataTable ariaLabel="Version history" columns={columns} rows={versions} rowKey={(v) => v.version_id}
        emptyText="No version has been published." />
    </Stack>
  );
}

function Revisions({ revisions }: { readonly revisions: readonly ZoneRevision[] }) {
  const columns: DataColumn<ZoneRevision>[] = [
    { key: "revision", header: "Revision", width: 110, render: (r) => `r${r.revision}` },
    { key: "actor", header: "Actor", width: 220, title: (r) => actorLabel(r.actor).raw ?? r.actor, render: (r) => actorLabel(r.actor).label },
    { key: "time", header: "Time", width: 190, title: () => undefined, render: (r) => <Ts at={r.occurred_at} seconds /> },
    { key: "kind", header: "Change", width: 190, render: (r) => CHANGE_KIND[r.change_kind] ?? r.change_kind },
    { key: "hash", header: "Content hash", title: (r) => r.content_sha256, render: (r) => <Hash value={r.content_sha256} /> },
  ];
  return (
    <Stack gap={1}>
      <SectionTitle>Draft revisions</SectionTitle>
      <DataTable ariaLabel="Draft revisions" columns={columns} rows={revisions} rowKey={(r) => String(r.revision)} emptyText="No revisions." />
    </Stack>
  );
}

/** What publishing the draft would change against the active version: loosening changes first, then cells, moves and zones. */
function DraftChanges({ diff }: { readonly diff: ZoneDiff }) {
  const cells = diff.cells ?? [];
  const looseningList = diff.loosening ?? [];
  const defaultChanged = Boolean(
    diff.default
      && diff.default.statement_before !== null
      && (diff.default.statement_before !== diff.default.statement_after
        || diff.default.severity_before !== diff.default.severity_after),
  );
  if (diff.zones.length === 0 && diff.moves.length === 0 && cells.length === 0 && !defaultChanged && looseningList.length === 0) {
    return <Typography variant="body2">The draft is the same as the active version. There is nothing to publish.</Typography>;
  }
  const moveLoosening = diff.moves.filter((m) => m.leaves_a_zone);
  const ordered = [...moveLoosening, ...diff.moves.filter((m) => !m.leaves_a_zone)];
  const looseningCount = diff.loosening !== undefined ? looseningList.length : moveLoosening.length;
  const added = diff.zones.filter((z) => z.change === "ADDED").length;
  const removed = diff.zones.filter((z) => z.change === "REMOVED").length;
  const changed = diff.zones.length - added - removed;
  const cellColumns: DataColumn<CellDiffRow>[] = [
    { key: "pair", header: "Pair", wrap: true, title: () => undefined, render: (row) => (
      <Box>
        <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
          {placeName(row.source_name)} {"->"} {placeName(row.destination_name)} ({row.change.toLowerCase()})
        </Typography>
        {row.loosening && row.loosening.length > 0 ? (
          <Box component="span" sx={{ ml: 1 }}><StatusChip dense tone="warn" label="Loosening change" /></Box>
        ) : null}
      </Box>
    ) },
    { key: "before", header: "Before", wrap: true, title: () => undefined, render: (row) => cellSideText(row.before) },
    { key: "after", header: "After", wrap: true, title: () => undefined, render: (row) => cellSideText(row.after) },
  ];
  const columns: DataColumn<ZoneDiffRow>[] = [
    { key: "zone", header: "Zone", wrap: true, title: () => undefined, render: (row) => {
      const { headline, details } = zoneChangeLines(row);
      return (
        <Box>
          <Typography component="span" variant="body2" sx={{ fontWeight: 600, display: "block" }}>{headline}</Typography>
          {details.map((line) => <Typography key={line} component="span" variant="body2" sx={{ color: m3.onSurfaceVar, display: "block", overflowWrap: "anywhere" }}>{line}</Typography>)}
        </Box>
      );
    } },
    { key: "v4", header: "IPv4 addresses", width: 190, align: "right", title: () => undefined, render: (row) => sizeChange(row.effective_before?.IPV4, row.effective_after?.IPV4) },
    { key: "v6", header: "IPv6 addresses", width: 230, align: "right", title: () => undefined, render: (row) => sizeChange(row.effective_before?.IPV6, row.effective_after?.IPV6) },
  ];
  return (
    <Stack gap={1.5} data-testid="draft-changes">
      <Typography variant="body2" data-testid="draft-summary" sx={{ fontWeight: 600 }}>
        {[
          plural(added, "zone added", "zones added"), plural(removed, "zone removed", "zones removed"), plural(changed, "zone changed", "zones changed"),
          plural(diff.moves.length, "address move", "address moves"),
          ...(cells.length > 0 ? [plural(cells.length, "cell changed", "cells changed")] : []),
        ].join(", ")}
        {looseningCount > 0 ? `; ${plural(looseningCount, "loosening change", "loosening changes")}` : ""}.
      </Typography>

      {looseningList.length > 0 ? (
        <Box component="ul" aria-label="Loosening changes" sx={{ ...panelSx, m: 0, listStyle: "none", gap: 1 }}>
          {looseningList.map((item, index) => (
            <li key={index} data-loosening="true">
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                <StatusChip dense tone="warn" label="Loosening change" />
                <Box component="span" sx={{ ml: 1 }}>{looseningText(item)}</Box>
              </Typography>
            </li>
          ))}
        </Box>
      ) : null}

      {defaultChanged && diff.default ? (
        <Typography variant="body2" data-testid="default-diff">
          Version default: {knownStatementWord(diff.default.statement_before)} ({knownSeverityWord(diff.default.severity_before)}) before;{" "}
          {knownStatementWord(diff.default.statement_after)} ({knownSeverityWord(diff.default.severity_after)}) after.
        </Typography>
      ) : null}

      {cells.length > 0 ? (
        <DataTable ariaLabel="Changed cells" columns={cellColumns} rows={cells} rowKey={(r) => `${r.source}->${r.destination}`} />
      ) : null}

      {diff.moves.length > 0 ? (
        <Box component="ul" aria-label="Address moves" sx={{ ...panelSx, m: 0, listStyle: "none", gap: 1 }}>
          {ordered.map((move, index) => (
            <li key={index} data-loosening={move.leaves_a_zone ? "true" : undefined}>
              <Typography variant="body2" sx={{ fontWeight: move.leaves_a_zone ? 600 : 400 }}>
                {moveText(move)}
                {move.leaves_a_zone ? <Box component="span" sx={{ ml: 1 }}><StatusChip dense tone="warn" label="Loosening change" /></Box> : null}
              </Typography>
              {move.leaves_a_zone ? (
                <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>These addresses leave zone {placeName(move.from)}. Publishing needs a rationale.</Typography>
              ) : null}
            </li>
          ))}
        </Box>
      ) : null}

      {diff.zones.length > 0 ? <DataTable ariaLabel="Changed zones" columns={columns} rows={diff.zones} rowKey={(row) => row.zone_id} /> : null}
      <Typography variant="body2">
        Unassigned address space: {sizesText(diff.unassigned_before)} before; {sizesText(diff.unassigned_after)} after.
      </Typography>
    </Stack>
  );
}

/**
 * Four-eyes publish (contract 3.4). The server decides who may publish (`publish_check`); the request carries the
 * revision and hash of the draft on screen, so a draft that changed since is refused and reloaded. Publishing asks for
 * a confirmation that says it is final.
 */
function PublishPanel({ draft, check, rationaleRequired, refresh, notify }: {
  readonly draft: ZoneVersionDetail;
  readonly check: PublishCheck | null;
  readonly rationaleRequired: boolean;
  readonly refresh: (hideBlocks?: boolean) => void;
  readonly notify: (notice: ZoneNotice | null) => void;
}) {
  const [rationale, setRationale] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const eligible = Boolean(check?.eligible);
  const ready = eligible && (!rationaleRequired || rationale.trim() !== "");
  const publish = async () => {
    setBusy(true); notify(null);
    try {
      const done = await publishZoneDraft(draft.revision, draft.content_sha256, rationale.trim() === "" ? null : rationale.trim());
      notify({ kind: "done", lines: [`Published as version ${done.number}.`] });
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
    } finally {
      setBusy(false);
      setConfirming(false);
      refresh(true);
    }
  };
  return (
    <Box component="section" aria-label="Publish draft" sx={panelSx}>
      <SectionTitle>Publish</SectionTitle>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        Publishing makes draft r{draft.revision} the active version and retires the current one. Two different people are needed: the publisher must not have changed this draft.
      </Typography>
      <Typography variant="body2" data-testid="publish-check" sx={{ fontWeight: 600 }}>
        {eligible ? "This account can publish this draft." : publishCheckText(check?.reason)}
      </Typography>
      {rationaleRequired ? (
        <TextField label="Rationale" required multiline minRows={2} value={rationale} onChange={(e) => setRationale(e.target.value)}
          inputProps={{ maxLength: RATIONALE_LIMIT }}
          helperText={`Required: this version loosens the segmentation policy (for example when addresses leave a zone in this draft or a statement is relaxed). Say why. ${rationale.length} / ${RATIONALE_LIMIT}`} />
      ) : null}
      {confirming && ready ? (
        <Box role="group" aria-label="Confirm publish" sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Publish draft r{draft.revision} (<Box component="span" sx={mono}>{draft.content_sha256.slice(0, 12)}</Box>) as the next version?
          </Typography>
          <Typography variant="body2">{PUBLISH_FINAL_TEXT}</Typography>
          <Stack direction="row" gap={1}>
            <M3Button emphasis="filled" disabled={busy} onClick={() => { void publish(); }}>Confirm publish</M3Button>
            <M3Button emphasis="text" disabled={busy} onClick={() => setConfirming(false)}>Cancel</M3Button>
          </Stack>
        </Box>
      ) : (
        <>
          <Box><M3Button emphasis="filled" disabled={!ready || busy} onClick={() => setConfirming(true)}>Publish draft</M3Button></Box>
          {eligible ? <Hint>You will be asked to confirm. {PUBLISH_FINAL_TEXT}</Hint> : null}
        </>
      )}
    </Box>
  );
}

function DiscardDraft({ draft, refresh, notify }: {
  readonly draft: ZoneVersionDetail;
  readonly refresh: (hideBlocks?: boolean) => void;
  readonly notify: (notice: ZoneNotice | null) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const discard = async () => {
    setBusy(true); notify(null);
    try {
      await discardZoneDraft(draft.revision);
      notify({ kind: "done", lines: ["The draft was discarded. The active version is unchanged."] });
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
    } finally {
      setBusy(false);
      refresh(true);
    }
  };
  return confirming ? (
    <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
      <Typography variant="body2">Discard draft r{draft.revision}? Its changes are dropped; the active version is unchanged.</Typography>
      <M3Button emphasis="tonal" disabled={busy} onClick={() => { void discard(); }}>Confirm discard</M3Button>
      <M3Button emphasis="text" disabled={busy} onClick={() => setConfirming(false)}>Keep draft</M3Button>
    </Stack>
  ) : <Box><M3Button emphasis="outlined" onClick={() => setConfirming(true)}>Discard draft</M3Button></Box>;
}
