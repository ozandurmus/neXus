import { useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { StatePanel, Ts } from "../../shell/States";
import type { Tone } from "../../shell/tone";
import { MONO, m3 } from "../../theme/m3Theme";
import { TYPE } from "../../theme/tokens";
import type { ZoneMatrixData, ZoneNotice } from "./ZoneMatrixScreen";
import {
  addressCount, discardZoneDraft, publishCheckText, publishZoneDraft, shortHash, zoneErrorLines, zoneRoleLabel,
  type FamilySizes, type PublishCheck, type ZoneDiff, type ZoneDiffRow, type ZoneMove, type ZoneVersionDetail, type ZoneVersionState,
} from "./zoneMatrixApi";

const mono = { fontFamily: MONO, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight } as const;
const panel = { border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2, display: "flex", flexDirection: "column", gap: 1.25 } as const;

const STATE_WORD: Record<ZoneVersionState, { readonly word: string; readonly tone: Tone }> = {
  DRAFT: { word: "Draft", tone: "attn" },
  ACTIVE: { word: "Active", tone: "ok" },
  RETIRED: { word: "Retired", tone: "neutral" },
  DISCARDED: { word: "Discarded", tone: "neutral" },
};

const CHANGE_KIND: Record<string, string> = {
  DRAFT_CREATE: "Draft started", ZONE_CREATE: "Zone added", ZONE_UPDATE: "Zone changed", ZONE_DELETE: "Zone removed",
  DRAFT_DISCARD: "Draft discarded", PUBLISH: "Published",
};

const Hash = ({ value }: { readonly value: string | null | undefined }) =>
  <Box component="span" title={value ?? undefined} sx={mono}>{shortHash(value)}</Box>;

const familyWord = (family: string) => (family === "IPV6" ? "IPv6" : "IPv4");

/** "256 IPv4 addresses move from Z-APP to Z-DB" (contract 3.2). */
export function moveText(move: ZoneMove): string {
  return `${addressCount(move.addresses)} ${familyWord(move.family)} ${move.addresses === "1" ? "address moves" : "addresses move"} from ${move.from} to ${move.to}`;
}

/** One changed zone as a headline and its detail lines. */
export function zoneChangeLines(row: ZoneDiffRow): { readonly headline: string; readonly details: readonly string[] } {
  const headline = row.change === "ADDED" ? `Zone ${row.name_after ?? row.zone_id} added`
    : row.change === "REMOVED" ? `Zone ${row.name_before ?? row.zone_id} removed`
    : `Zone ${row.name_after ?? row.zone_id} changed${row.name_before && row.name_before !== row.name_after ? ` (was ${row.name_before})` : ""}`;
  const details: string[] = [];
  if (row.change === "CHANGED" && row.role_before !== row.role_after) details.push(`Zone role: ${zoneRoleLabel(row.role_before)} to ${zoneRoleLabel(row.role_after)}`);
  const blocks = (label: string, value: readonly string[] | number) => {
    if (typeof value === "number") { if (value > 0) details.push(`${label}: ${value}`); }
    else if (value.length > 0) details.push(`${label}: ${value.join(", ")}`);
  };
  blocks("Blocks added", row.blocks_added);
  blocks("Blocks removed", row.blocks_removed);
  const sizes = (family: "IPV4" | "IPV6") => {
    const before = row.effective_before?.[family], after = row.effective_after?.[family];
    if (before !== after) details.push(`${familyWord(family)} addresses: ${addressCount(before)} to ${addressCount(after)}`);
  };
  sizes("IPV4"); sizes("IPV6");
  return { headline, details };
}

interface TabProps {
  readonly data: ZoneMatrixData;
  readonly refresh: () => void;
  readonly notify: (notice: ZoneNotice | null) => void;
}

/** The Versions tab: the version history, the draft's change list, the publish panel and the draft's revisions. */
export function VersionsTab({ data, refresh, notify }: TabProps) {
  const { can, draft, diff, publish_check: publishCheck } = data.draft;
  return (
    <Stack gap={2.5}>
      <Stack gap={1}>
        <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Version history</Typography>
        {data.versions.length === 0 ? <StatePanel variant="empty" title="No version yet" body="A version appears here once a draft is started or published." /> : (
          <Table size="small" aria-label="Version history">
            <TableHead>
              <TableRow>
                <TableCell>Version</TableCell><TableCell>State</TableCell><TableCell>Content hash</TableCell>
                <TableCell>Published by</TableCell><TableCell>Published at</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.versions.map((v) => (
                <TableRow key={v.version_id}>
                  <TableCell>{v.number ?? (v.state === "DRAFT" ? `Draft r${v.revision}` : "Not published")}</TableCell>
                  <TableCell><StatusChip dense tone={STATE_WORD[v.state]?.tone ?? "neutral"} label={STATE_WORD[v.state]?.word ?? v.state} /></TableCell>
                  <TableCell><Hash value={v.content_sha256} /></TableCell>
                  <TableCell>{v.published_by ?? "Not published"}</TableCell>
                  <TableCell>{v.published_at ? <Ts at={v.published_at} /> : "Not published"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Stack>

      {draft === null ? (data.draft.draft_exists ? (
        <StatePanel variant="empty" title="A draft is being prepared" announce="none"
          body="Editors are changing the zones. The changes apply only after a second person publishes them." />
      ) : (
        <StatePanel variant="empty" title="There is no draft" announce="none"
          body={can.edit ? "A draft starts when a zone is added or changed on the Zones tab." : "Nothing is waiting to be published."} />
      )) : <>
        <Stack gap={1}>
          <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>
            Draft r{draft.revision}, not published <Box component="span" sx={{ ml: 1, color: m3.onSurfaceVar, fontWeight: 400 }}><Hash value={draft.content_sha256} /></Box>
          </Typography>
          {diff ? <DraftChanges diff={diff} /> : null}
        </Stack>
        {can.publish ? <PublishPanel key={`${draft.revision}:${draft.content_sha256}`} draft={draft} check={publishCheck} rationaleRequired={Boolean(diff?.rationale_required)} refresh={refresh} notify={notify} /> : null}
        {can.edit ? <DiscardDraft key={draft.revision} draft={draft} refresh={refresh} notify={notify} /> : null}
        <Stack gap={1}>
          <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Draft revisions</Typography>
          <Table size="small" aria-label="Draft revisions">
            <TableHead>
              <TableRow><TableCell>Revision</TableCell><TableCell>Actor</TableCell><TableCell>Time</TableCell><TableCell>Change</TableCell><TableCell>Content hash</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {draft.revisions.map((r) => (
                <TableRow key={r.revision}>
                  <TableCell>r{r.revision}</TableCell><TableCell>{r.actor}</TableCell>
                  <TableCell><Ts at={r.occurred_at} seconds /></TableCell>
                  <TableCell>{CHANGE_KIND[r.change_kind] ?? r.change_kind}</TableCell>
                  <TableCell><Hash value={r.content_sha256} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Stack>
      </>}
    </Stack>
  );
}

const sizesText = (sizes: FamilySizes | undefined) => `IPv4 ${addressCount(sizes?.IPV4)}, IPv6 ${addressCount(sizes?.IPV6)}`;

/** What publishing the draft would change against the active version: zones, then the addresses that move. */
function DraftChanges({ diff }: { readonly diff: ZoneDiff }) {
  if (diff.zones.length === 0 && diff.moves.length === 0) {
    return <Typography variant="body2">The draft is the same as the active version. There is nothing to publish.</Typography>;
  }
  return (
    <Stack gap={1.5} data-testid="draft-changes">
      <Box component="ul" aria-label="Changed zones" sx={{ m: 0, pl: 2.5 }}>
        {diff.zones.map((row) => {
          const { headline, details } = zoneChangeLines(row);
          return (
            <li key={row.zone_id}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{headline}</Typography>
              {details.map((line) => <Typography key={line} variant="body2" sx={{ color: m3.onSurfaceVar }}>{line}</Typography>)}
            </li>
          );
        })}
      </Box>
      {diff.moves.length > 0 ? (
        <Box component="ul" aria-label="Address moves" sx={{ m: 0, pl: 2.5 }}>
          {diff.moves.map((move, index) => (
            <li key={index} data-loosening={move.leaves_a_zone ? "true" : undefined}>
              <Typography variant="body2" sx={{ fontWeight: move.leaves_a_zone ? 600 : 400 }}>
                {moveText(move)}
                {move.leaves_a_zone ? <Box component="span" sx={{ ml: 1 }}><StatusChip dense tone="warn" label="Loosening change" /></Box> : null}
              </Typography>
              {move.leaves_a_zone ? (
                <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>These addresses leave zone {move.from}. Publishing needs a rationale.</Typography>
              ) : null}
            </li>
          ))}
        </Box>
      ) : null}
      <Typography variant="body2">
        Unassigned address space: {sizesText(diff.unassigned_before)} before; {sizesText(diff.unassigned_after)} after.
      </Typography>
    </Stack>
  );
}

/**
 * Four-eyes publish (contract 3.4). The server decides who may publish (`publish_check`); the request carries the
 * revision and hash of the draft on screen, so a draft that changed since is refused and reloaded.
 */
function PublishPanel({ draft, check, rationaleRequired, refresh, notify }: {
  readonly draft: ZoneVersionDetail;
  readonly check: PublishCheck | null;
  readonly rationaleRequired: boolean;
  readonly refresh: () => void;
  readonly notify: (notice: ZoneNotice | null) => void;
}) {
  const [rationale, setRationale] = useState("");
  const [busy, setBusy] = useState(false);
  const eligible = Boolean(check?.eligible);
  const publish = async () => {
    setBusy(true); notify(null);
    try {
      const done = await publishZoneDraft(draft.revision, draft.content_sha256, rationale.trim() === "" ? null : rationale.trim());
      notify({ kind: "done", lines: [`Published as version ${done.number}.`] });
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
    } finally {
      setBusy(false);
      refresh();
    }
  };
  return (
    <Box component="section" aria-label="Publish draft" sx={panel}>
      <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Publish</Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        Publishing makes draft r{draft.revision} the active version and retires the current one. Two different people are needed: the publisher must not have changed this draft.
      </Typography>
      <Typography variant="body2" data-testid="publish-check" sx={{ fontWeight: 600 }}>
        {eligible ? "This account can publish this draft." : publishCheckText(check?.reason)}
      </Typography>
      {rationaleRequired ? (
        <TextField label="Rationale" required multiline minRows={2} value={rationale} onChange={(e) => setRationale(e.target.value)}
          inputProps={{ maxLength: 2000 }} helperText="Required: addresses leave a zone in this draft. Say why." />
      ) : null}
      <Box>
        <M3Button emphasis="filled" disabled={!eligible || busy || (rationaleRequired && rationale.trim() === "")} onClick={() => { void publish(); }}>Publish draft</M3Button>
      </Box>
    </Box>
  );
}

function DiscardDraft({ draft, refresh, notify }: {
  readonly draft: ZoneVersionDetail;
  readonly refresh: () => void;
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
      refresh();
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
