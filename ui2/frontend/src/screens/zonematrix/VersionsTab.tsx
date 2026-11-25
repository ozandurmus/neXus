import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { StatePanel, Ts } from "../../shell/States";
import { plural } from "../../shell/plural";
import { actorLabel } from "../../shell/presenters";
import type { Tone } from "../../shell/tone";
import { m3 } from "../../theme/m3Theme";
import type { ZoneNotice, ZoneTabProps } from "./ZoneMatrixScreen";
import { Hash, Hint, SectionTitle, mono, panelSx } from "./zoneMatrixUi";
import { useEnsureSummary, type ZoneSummaryHandle } from "./zoneMatrixSummaryUi";
import {
  PUBLISH_FINAL_TEXT, addressCount, discardZoneDraft, publishCheckText, publishZoneDraft, referenceStatusWord,
  severityWord, statementWord, zoneErrorLines, zoneRoleLabel,
  type CellDiffRow, type CellDiffSide, type FamilySizes, type LooseningRow, type PublishCheck, type ZoneDiff,
  type SummaryResolvedByVersion, type ZoneDiffRow, type ZoneMove, type ZoneRevision, type ZoneVersionDetail,
  type ZoneVersionState, type ZoneVersionSummary,
} from "./zoneMatrixApi";

export const RATIONALE_LIMIT = 2000;

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
  return `${statementWord(side.statement)} (${severityWord(side.severity)}, ${mode})${services}${ref}`;
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
 * The Versions tab: first the draft for review (its changes, the publish panel, discard, its revisions) for the people
 * the server shows it to, then the history of published versions.
 */
export function VersionsTab({ data, refresh, notify, summary }: ZoneTabProps & { readonly summary: ZoneSummaryHandle }) {
  // The resolved-by-version counts come from the stored summary; it is read once, here or by another summary tab.
  useEnsureSummary(summary);
  const resolved = summary.data !== null && summary.data.state === "READY" ? summary.data.resolved_by_version_change : null;
  const { can, draft, diff, publish_check: publishCheck, draft_exists: exists } = data.draft;
  return (
    <Stack gap={3}>
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
      <History versions={data.versions} resolved={resolved} />
    </Stack>
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
      render: (v) => (v.published_by ? actorLabel(v.published_by).label : "Not published") },
    { key: "at", header: "Published at", title: () => undefined, render: (v) => (v.published_at ? <Ts at={v.published_at} /> : "Not published") },
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
          Version default: {statementWord(diff.default.statement_before)} ({severityWord(diff.default.severity_before)}) before;{" "}
          {statementWord(diff.default.statement_after)} ({severityWord(diff.default.severity_after)}) after.
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
