import { useState } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import DialogContent from "@mui/material/DialogContent";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";

import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { StatePanel } from "../../shell/States";
import { MONO, m3 } from "../../theme/m3Theme";
import { TYPE } from "../../theme/tokens";
import type { ZoneMatrixData, ZoneNotice } from "./ZoneMatrixScreen";
import {
  ZONE_ROLES, addressCount, createZone, deleteZone, lookupZoneAddress, shortHash, updateZone, zoneErrorCode, zoneErrorLines,
  zoneRoleLabel, type ZoneLookupResult, type ZoneVersionDetail, type ZoneView,
} from "./zoneMatrixApi";

export const CANONICAL_BLOCK_NOTE =
  "One block per line, in canonical CIDR form: the network address and its prefix length, for example 192.0.2.0/24. "
  + "A single address is written as /32 (IPv4) or /128 (IPv6). A block with host bits set, such as 192.0.2.7/24, is refused.";

const mono = { fontFamily: MONO, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight } as const;

/** The label of one version as the tab names it. */
export function versionLabel(version: { readonly state: string; readonly number: number | null; readonly revision: number }): string {
  return version.state === "DRAFT" ? `Draft r${version.revision}, not published` : `Version ${version.number ?? "?"}`;
}

interface TabProps {
  readonly data: ZoneMatrixData;
  readonly refresh: () => void;
  readonly notify: (notice: ZoneNotice | null) => void;
}

/**
 * The Zones tab: the zones of the active version (or, for an editor, of the draft being worked on), their effective
 * sizes, the unassigned space, the blocks when asked for, the address lookup and the zone editor.
 */
export function ZonesTab({ data, onShowBlocks, refresh, notify }: TabProps & { readonly onShowBlocks: (next: boolean) => void }) {
  const { can, draft } = data.draft;
  const active = data.active;
  // An edit always goes to the draft; with no draft yet, the first edit starts one from the active version.
  const editBase = draft ?? active;
  const [view, setView] = useState<"draft" | "active">("draft");
  const viewed: ZoneVersionDetail | null = can.edit ? (view === "active" && active ? active : editBase) : active;
  const editable = can.edit && viewed === editBase;
  const revision = draft ? draft.revision : null;
  const [editing, setEditing] = useState<ZoneView | "new" | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const remove = async (zone: ZoneView) => {
    setBusy(true);
    try {
      await deleteZone(revision, zone.zone_id);
      notify({ kind: "done", lines: [`Zone ${zone.name} was removed from the draft.`] });
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
    } finally {
      setBusy(false);
      setDeleting(null);
      refresh();
    }
  };

  return (
    <Stack gap={2}>
      <Stack direction="row" gap={2} alignItems="center" flexWrap="wrap">
        {draft ? <StatusChip tone="attn" label={`Draft r${draft.revision}, not published`} /> : null}
        {can.edit && draft && active ? (
          <ToggleButtonGroup exclusive size="small" value={view} aria-label="Version shown"
            onChange={(_e, next: "draft" | "active" | null) => { if (next) setView(next); }}>
            <ToggleButton value="draft">Draft</ToggleButton>
            <ToggleButton value="active">Active version</ToggleButton>
          </ToggleButtonGroup>
        ) : null}
        {can.view_blocks ? (
          <FormControlLabel label="Show blocks"
            control={<Switch checked={data.blocks} onChange={(e) => onShowBlocks(e.target.checked)} inputProps={{ "aria-label": "Show blocks" }} />} />
        ) : null}
        {can.view_blocks ? <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Each view of the blocks is recorded in the audit log.</Typography> : null}
        <Box sx={{ flex: 1 }} />
        {editable ? <M3Button emphasis="filled" onClick={() => { notify(null); setEditing("new"); }}>Add zone</M3Button> : null}
      </Stack>

      {can.edit && !draft ? (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          There is no draft. Adding or changing a zone starts a draft; nothing changes for the active version until another person publishes it.
        </Typography>
      ) : null}
      {can.edit ? <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{CANONICAL_BLOCK_NOTE}</Typography> : null}

      {viewed === null ? (
        <StatePanel variant="empty" title="No zone version has been published yet"
          body={can.edit ? "Add the first zone to start a draft." : "Zones appear here once a version is published."} />
      ) : <>
        <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>
          {versionLabel(viewed)}
          <Box component="span" title={viewed.content_sha256} sx={{ ...mono, color: m3.onSurfaceVar, ml: 1.5, fontWeight: 400 }}>{shortHash(viewed.content_sha256)}</Box>
        </Typography>
        {viewed.zones.length === 0 ? <StatePanel variant="empty" title="This version has no zones" body="Every address is unassigned." /> : (
          <Table size="small" aria-label="Zones">
            <TableHead>
              <TableRow>
                <TableCell>Zone</TableCell><TableCell>Role</TableCell><TableCell align="right">Blocks</TableCell>
                <TableCell align="right">IPv4 addresses</TableCell><TableCell align="right">IPv6 addresses</TableCell>
                {editable ? <TableCell>Actions</TableCell> : null}
              </TableRow>
            </TableHead>
            <TableBody>
              {viewed.zones.map((zone) => (
                <TableRow key={zone.zone_id} data-zone={zone.name}>
                  <TableCell sx={{ verticalAlign: "top" }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{zone.name}</Typography>
                    {zone.description ? <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{zone.description}</Typography> : null}
                    {zone.blocks ? (
                      <Box component="ul" aria-label={`Blocks of ${zone.name}`} sx={{ m: 0, mt: 0.5, p: 0, listStyle: "none", ...mono }}>
                        {zone.blocks.length === 0 ? <li>No blocks</li> : zone.blocks.map((block) => <li key={block}>{block}</li>)}
                      </Box>
                    ) : null}
                  </TableCell>
                  <TableCell sx={{ verticalAlign: "top" }}>{zoneRoleLabel(zone.role)}</TableCell>
                  <TableCell align="right" sx={{ verticalAlign: "top" }}>{zone.block_count}</TableCell>
                  <TableCell align="right" sx={{ verticalAlign: "top" }} title={zone.effective_addresses.IPV4}>{addressCount(zone.effective_addresses.IPV4)}</TableCell>
                  <TableCell align="right" sx={{ verticalAlign: "top" }} title={zone.effective_addresses.IPV6}>{addressCount(zone.effective_addresses.IPV6)}</TableCell>
                  {editable ? (
                    <TableCell sx={{ verticalAlign: "top", whiteSpace: "nowrap" }}>
                      {deleting === zone.zone_id ? (
                        <Stack direction="row" gap={1} alignItems="center">
                          <Typography variant="body2">Delete {zone.name}?</Typography>
                          <M3Button emphasis="tonal" disabled={busy} onClick={() => { void remove(zone); }}>Confirm delete</M3Button>
                          <M3Button emphasis="text" disabled={busy} onClick={() => setDeleting(null)}>Keep</M3Button>
                        </Stack>
                      ) : (
                        <Stack direction="row" gap={1}>
                          {/* Saving a zone replaces its whole block list, so the editor needs the current blocks on screen. */}
                          <M3Button emphasis="text" disabled={zone.blocks === null} onClick={() => { notify(null); setEditing(zone); }}>Edit</M3Button>
                          <M3Button emphasis="text" onClick={() => { notify(null); setDeleting(zone.zone_id); }}>Delete</M3Button>
                        </Stack>
                      )}
                    </TableCell>
                  ) : null}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {editable && !data.blocks && viewed.zones.length > 0 ? (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            {can.view_blocks ? "Turn on Show blocks to edit a zone: saving replaces the zone's whole block list." : "Editing a zone's blocks needs the right to view blocks."}
          </Typography>
        ) : null}
        <Typography variant="body2" data-testid="unassigned-space">
          Unassigned address space (covered by no zone):{" "}
          <span title={viewed.unassigned_addresses.IPV4}>IPv4 {addressCount(viewed.unassigned_addresses.IPV4)}</span>,{" "}
          <span title={viewed.unassigned_addresses.IPV6}>IPv6 {addressCount(viewed.unassigned_addresses.IPV6)}</span> addresses.
        </Typography>
        <RedundantBlocks value={viewed.redundant_blocks} />
        {can.view_blocks ? <AddressLookup version={viewed} /> : null}
      </>}

      {editing !== null ? (
        <ZoneDialog key={editing === "new" ? "new" : editing.zone_id} zone={editing === "new" ? null : editing} revision={revision}
          onClose={() => setEditing(null)} onChanged={refresh}
          onSaved={(name) => { setEditing(null); notify({ kind: "done", lines: [`Zone ${name} was saved to the draft. The draft is not published.`] }); refresh(); }} />
      ) : null}
    </Stack>
  );
}

/** Blocks that repeat or sit inside another block of the same zone: accepted, shown as a warning (contract 3.2). */
function RedundantBlocks({ value }: { readonly value: ZoneVersionDetail["redundant_blocks"] }) {
  const count = typeof value === "number" ? value : value.length;
  if (count === 0) return null;
  return (
    <Box data-testid="redundant-blocks">
      <Typography variant="body2">
        Overlap warning: {count} {count === 1 ? "block repeats or sits" : "blocks repeat or sit"} inside another block of the same zone. This changes no zone's addresses.
      </Typography>
      {typeof value === "number" ? null : (
        <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
          {value.map((r, index) => (
            <li key={index}><Typography variant="body2">{r.zone}: <Box component="span" sx={mono}>{r.block}</Box> is inside <Box component="span" sx={mono}>{r.within}</Box></Typography></li>
          ))}
        </Box>
      )}
    </Box>
  );
}

/** Which zone one address is in, under the version on screen. Sent only on request: every lookup is audited. */
function AddressLookup({ version }: { readonly version: ZoneVersionDetail }) {
  const [address, setAddress] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ readonly address: string; readonly found: ZoneLookupResult } | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const submit = async () => {
    const asked = address.trim();
    if (!asked) return;
    setBusy(true); setFailure(null); setResult(null);
    try {
      setResult({ address: asked, found: await lookupZoneAddress(asked, version.version_id) });
    } catch (err) {
      setFailure(zoneErrorLines(err).join(" "));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Box component="form" aria-label="Address lookup" onSubmit={(e) => { e.preventDefault(); void submit(); }}
      sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2, display: "flex", flexDirection: "column", gap: 1.25 }}>
      <Typography component="h3" sx={{ ...TYPE.section, m: 0 }}>Address lookup</Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        Which zone holds one address under {version.state === "DRAFT" ? "the draft" : `version ${version.number ?? "?"}`}. Each lookup is recorded in the audit log.
      </Typography>
      <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
        <TextField size="small" label="Address" placeholder="192.0.2.7" value={address} onChange={(e) => setAddress(e.target.value)} sx={{ minWidth: 280 }} />
        <M3Button emphasis="tonal" disabled={busy || address.trim() === ""} onClick={() => { void submit(); }}>Look up</M3Button>
      </Stack>
      {result ? (
        <Typography variant="body2" role="status" data-testid="lookup-result">
          {result.found.zone_id === null || result.found.zone_name === "UNASSIGNED"
            ? `${result.address} is in no zone (UNASSIGNED).`
            : `${result.address} is in zone ${result.found.zone_name ?? result.found.zone_id}.`}
          {" "}{result.found.family === "IPV6" ? "IPv6" : "IPv4"}, {result.found.state === "DRAFT" ? "draft" : `version ${result.found.number ?? "?"}`}.
        </Typography>
      ) : null}
      {failure ? <Typography variant="body2" role="alert" sx={{ color: m3.error }}>{failure}</Typography> : null}
    </Box>
  );
}

/** Add or edit one zone of the draft. The request carries the draft revision on screen (`null` with no draft yet). */
function ZoneDialog({ zone, revision, onClose, onSaved, onChanged }: {
  readonly zone: ZoneView | null;
  readonly revision: number | null;
  readonly onClose: () => void;
  readonly onSaved: (name: string) => void;
  /** The draft changed under the editor: reload, keep what was typed. */
  readonly onChanged: () => void;
}) {
  const [name, setName] = useState(zone?.name ?? "");
  const [description, setDescription] = useState(zone?.description ?? "");
  const [role, setRole] = useState(zone?.role ?? "");
  const [blocks, setBlocks] = useState((zone?.blocks ?? []).join("\n"));
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<readonly string[]>([]);
  const save = async () => {
    const sent = blocks.split("\n").map((line) => line.trim()).filter((line) => line !== "");
    const input = { name: name.trim(), description: description.trim() === "" ? null : description.trim(), role: role === "" ? null : role, blocks: sent };
    setBusy(true); setErrors([]);
    try {
      if (zone) await updateZone(revision, zone.zone_id, input);
      else await createZone(revision, input);
      onSaved(input.name);
    } catch (err) {
      setErrors(zoneErrorLines(err, sent));
      if (zoneErrorCode(err) === "REQUEST_CHANGED") onChanged();
      setBusy(false);
    }
  };
  const title = zone ? `Edit zone ${zone.name}` : "Add zone";
  return (
    <Dialog open onClose={busy ? undefined : onClose} aria-label={title} PaperProps={{ sx: { borderRadius: "28px", width: 560, maxWidth: "95vw" } }}>
      <DialogContent sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2 }}>
        <Typography component="h2" sx={{ ...TYPE.section, m: 0 }}>{title}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          {revision === null ? "Saving starts a draft." : `Saving changes draft r${revision}.`} Nothing is published by saving.
        </Typography>
        <TextField size="small" label="Name" required value={name} onChange={(e) => setName(e.target.value)} inputProps={{ maxLength: 64 }}
          helperText="Unique, 1 to 64 characters" />
        <TextField size="small" label="Description" value={description} onChange={(e) => setDescription(e.target.value)} />
        <TextField select size="small" label="Zone role" value={role} onChange={(e) => setRole(e.target.value)} helperText="Optional">
          <MenuItem value="">None</MenuItem>
          {ZONE_ROLES.map((r) => <MenuItem key={r.value} value={r.value}>{r.label}</MenuItem>)}
        </TextField>
        <TextField label="Blocks" multiline minRows={6} maxRows={16} value={blocks} onChange={(e) => setBlocks(e.target.value)}
          placeholder={"192.0.2.0/24\n2001:db8::/32"} helperText={CANONICAL_BLOCK_NOTE} InputProps={{ sx: mono }} />
        {errors.length > 0 ? (
          <Box role="alert" sx={{ color: m3.error }}>
            {errors.map((line, index) => <Typography key={index} variant="body2" sx={{ fontWeight: index === 0 ? 600 : 400 }}>{line}</Typography>)}
          </Box>
        ) : null}
        <Stack direction="row" gap={1} justifyContent="flex-end">
          <M3Button emphasis="text" disabled={busy} onClick={onClose}>Cancel</M3Button>
          <M3Button emphasis="filled" disabled={busy || name.trim() === ""} onClick={() => { void save(); }}>Save to draft</M3Button>
        </Stack>
      </DialogContent>
    </Dialog>
  );
}
