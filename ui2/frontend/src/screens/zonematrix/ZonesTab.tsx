import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import DialogContent from "@mui/material/DialogContent";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { StatePanel } from "../../shell/States";
import { m3 } from "../../theme/m3Theme";
import { TYPE } from "../../theme/tokens";
import type { ZoneTabProps } from "./ZoneMatrixScreen";
import { classifyBlockLine, decomposeIpRange } from "./cidrDecomposition";
import { Hash, Hint, SectionTitle, mono, panelSx } from "./zoneMatrixUi";
import {
  ZONE_ROLES, addressCount, createZone, deleteZone, getZoneVersion, lookupZoneAddress, updateZone, zoneErrorCode, zoneErrorLines,
  zoneRoleLabel, type ZoneDiffRow, type ZoneLookupResult, type ZoneVersionDetail, type ZoneView,
} from "./zoneMatrixApi";

export const CANONICAL_BLOCK_NOTE =
  "One block per line, in canonical CIDR form: the network address and its prefix length, for example 192.0.2.0/24. "
  + "A single address is written as /32 (IPv4) or /128 (IPv6). A block with host bits set, such as 192.0.2.7/24, is refused. "
  + "Enter a range like 192.0.2.10 - 192.0.2.20 to preview its exact CIDR decomposition before saving.";

/** The line next to the Show blocks action and the lookup: both are written to the audit log by the server. */
export const AUDIT_LINE = "Viewing blocks is recorded in the audit log";

/** Maximum overlapping-block rows rendered before printing "+N more". */
export const REDUNDANT_BLOCKS_CAP = 50;

/** The label of one version as the tab names it. */
export function versionLabel(version: { readonly state: string; readonly number: number | null; readonly revision: number }): string {
  return version.state === "DRAFT" ? `Draft r${version.revision}` : `Version ${version.number ?? "UNKNOWN"}`;
}

const CHANGE_WORD: Record<string, string> = { ADDED: "Added in draft", CHANGED: "Changed in draft" };

/**
 * The Zones tab: the zones of the active version (or, for an editor, of the draft being worked on), their effective
 * sizes, the unassigned space, the blocks when asked for, the address lookup and the zone editor.
 */
export function ZonesTab({ data, onShowBlocks, refresh, notify, reading }: ZoneTabProps & { readonly onShowBlocks: (next: boolean) => void }) {
  const { can, draft, diff } = data.draft;
  const active = data.active;
  // An edit always goes to the draft; with no draft yet, the first edit starts one from the active version.
  const base = draft ?? active;
  const [view, setView] = useState<"draft" | "active">("draft");
  const [editing, setEditing] = useState<ZoneView | "new" | null>(null);
  const [deleting, setDeleting] = useState<ZoneView | null>(null);
  const [busy, setBusy] = useState(false);
  const [activeBlocks, setActiveBlocks] = useState<ZoneVersionDetail | null>(null);
  const [activeFailure, setActiveFailure] = useState<string | null>(null);

  const onActive = can.edit && draft !== null && view === "active";
  // The draft read does not carry the active version's blocks (the screen did not ask for them): this one read does,
  // only on request. A published version never changes, so what was read stays valid until the blocks are hidden.
  const cached = activeBlocks && active && activeBlocks.version_id === active.version_id && data.blocks ? activeBlocks : null;
  const viewed: ZoneVersionDetail | null = can.edit ? (onActive ? (cached ?? active) : base) : active;
  const editable = can.edit && !onActive;
  const revision = draft ? draft.revision : null;
  useEffect(() => { if (!data.blocks) { setActiveBlocks(null); setActiveFailure(null); } }, [data.blocks]);

  const needsActiveBlocks = can.view_blocks && data.blocks && onActive && viewed !== null && viewed.zones.some((z) => z.blocks === null);
  const readActiveBlocks = async () => {
    if (!active) return;
    setBusy(true); setActiveFailure(null);
    try { setActiveBlocks(await getZoneVersion(active.version_id, true)); }
    catch (err) { setActiveFailure(zoneErrorLines(err).join(" ")); }
    finally { setBusy(false); }
  };

  const changes = useMemo(() => new Map<string, ZoneDiffRow>((diff?.zones ?? []).map((row) => [row.zone_id, row])), [diff]);
  const showChanges = editable && draft !== null && changes.size > 0;
  // Raw CIDR blocks are shown only with zone_matrix_zones_read (`can.view_blocks`) when the user asked for blocks.
  const showBlockList = can.view_blocks && data.blocks && viewed !== null && viewed.zones.some((z) => z.blocks !== null);

  const remove = async (zone: ZoneView) => {
    setBusy(true);
    try {
      await deleteZone(revision, zone.zone_id);
      notify({ kind: "done", lines: [`Zone ${zone.name} was removed from the draft. The draft is not published.`] });
    } catch (err) {
      notify({ kind: "refused", lines: zoneErrorLines(err) });
    } finally {
      setBusy(false);
      setDeleting(null);
      refresh();
    }
  };

  const columns: DataColumn<ZoneView>[] = [
    { key: "zone", header: "Zone", wrap: true, title: (z) => z.name, render: (z) => (
      <Box>
        <Typography component="span" variant="body2" sx={{ fontWeight: 600, display: "block" }}>{z.name}</Typography>
        {z.description ? <Typography component="span" variant="body2" sx={{ color: m3.onSurfaceVar, display: "block" }}>{z.description}</Typography> : null}
      </Box>
    ) },
    { key: "role", header: "Role", width: 150, render: (z) => zoneRoleLabel(z.role) },
    { key: "count", header: "Block count", width: 105, align: "right",
      render: (z) => (typeof z.block_count === "number" && Number.isFinite(z.block_count) && z.block_count >= 0 ? z.block_count : "UNKNOWN") },
    { key: "v4", header: "IPv4 addresses", width: 150, align: "right", title: (z) => z.effective_addresses?.IPV4, render: (z) => addressCount(z.effective_addresses?.IPV4) },
    { key: "v6", header: "IPv6 addresses", width: 190, align: "right", title: (z) => z.effective_addresses?.IPV6, render: (z) => addressCount(z.effective_addresses?.IPV6) },
    ...(showChanges ? [{ key: "change", header: "Against the active version", width: 170, title: () => undefined,
      render: (z: ZoneView) => (changes.get(z.zone_id) ? <StatusChip dense tone="attn" label={CHANGE_WORD[changes.get(z.zone_id)!.change] ?? "Changed in draft"} /> : <span>Unchanged</span>) }] : []),
    ...(showBlockList ? [{ key: "blocks", header: "Blocks", width: 230, wrap: true, mono: true, title: () => undefined,
      render: (z: ZoneView) => (z.blocks === null ? null : (
        <Box component="ul" aria-label={`Blocks of ${z.name}`} sx={{ m: 0, p: 0, listStyle: "none", ...mono }}>
          {z.blocks.length === 0 ? <li>No blocks</li> : z.blocks.map((block) => <li key={block}>{block}</li>)}
        </Box>
      )) }] : []),
    ...(editable ? [{ key: "actions", header: "Actions", width: 170, title: () => undefined,
      render: (z: ZoneView) => (
        <Stack direction="row" gap={0.5}>
          {/* Saving a zone replaces its whole block list, so the editor needs the current blocks on screen. */}
          <M3Button emphasis="text" disabled={z.blocks === null} onClick={() => { notify(null); setEditing(z); }}>Edit</M3Button>
          <M3Button emphasis="text" onClick={() => { notify(null); setDeleting(z); }}>Delete</M3Button>
        </Stack>
      ) }] : []),
  ];

  return (
    <Stack gap={2}>
      <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
        {can.edit && draft && active ? (
          <ToggleButtonGroup exclusive size="small" value={view} aria-label="Version shown"
            onChange={(_e, next: "draft" | "active" | null) => { if (next) setView(next); }}>
            <ToggleButton value="draft">Draft</ToggleButton>
            <ToggleButton value="active">Active version</ToggleButton>
          </ToggleButtonGroup>
        ) : null}
        {can.view_blocks ? (
          <>
            <M3Button emphasis="tonal" disabled={reading} onClick={() => onShowBlocks(!data.blocks)}>{data.blocks ? "Hide blocks" : "Show blocks"}</M3Button>
            <Hint>{AUDIT_LINE}{data.blocks ? "; so is every reload while blocks are shown" : ""}</Hint>
          </>
        ) : null}
        <Box sx={{ flex: 1 }} />
        {editable ? <M3Button emphasis="filled" onClick={() => { notify(null); setEditing("new"); }}>Add zone</M3Button> : null}
      </Stack>

      {can.edit && !draft ? (
        <Hint>There is no draft. Adding or changing a zone starts a draft; nothing changes for the active version until another person publishes it.</Hint>
      ) : null}
      {editable && viewed !== null && viewed.zones.length > 0 && !data.blocks ? (
        <Hint>{can.view_blocks ? "Show blocks to edit a zone: saving replaces the zone's whole block list." : "Editing a zone's blocks needs the right to view blocks."}</Hint>
      ) : null}

      {viewed === null ? (
        <StatePanel variant="empty" title="No version has been published"
          body={can.edit ? "Add the first zone to start a draft." : "Zones appear here once a version is published and another person approves it."} />
      ) : <>
        <SectionTitle note={viewed.state === "DRAFT" ? "Zones as they stand in the draft." : "Zones of the version in force."}>
          {versionLabel(viewed)}{viewed.state === "DRAFT" ? "" : <Box component="span" sx={{ ml: 1.5, color: m3.onSurfaceVar, fontWeight: 400 }}><Hash value={viewed.content_sha256} /></Box>}
        </SectionTitle>
        <DataTable ariaLabel="Zones" columns={columns} rows={viewed.zones} rowKey={(z) => z.zone_id}
          emptyText="This version has no zones. Every address is unassigned." />
        <Box data-testid="unassigned-space" sx={{ ...panelSx, py: 1.25, flexDirection: "row", flexWrap: "wrap", gap: 2, alignItems: "baseline" }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>Unassigned</Typography>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Addresses that no zone holds. A state, not a zone and not &quot;internet&quot;.</Typography>
          <Typography variant="body2" title={viewed.unassigned_addresses?.IPV4}>IPv4 {addressCount(viewed.unassigned_addresses?.IPV4)}</Typography>
          <Typography variant="body2" title={viewed.unassigned_addresses?.IPV6}>IPv6 {addressCount(viewed.unassigned_addresses?.IPV6)}</Typography>
        </Box>
        {needsActiveBlocks ? (
          <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
            <M3Button emphasis="outlined" disabled={busy} onClick={() => { void readActiveBlocks(); }}>Show blocks of the active version</M3Button>
            <Hint>The blocks shown were read for the draft. This is a separate read, also recorded in the audit log</Hint>
          </Stack>
        ) : null}
        {activeFailure ? <Typography variant="body2" role="alert" sx={{ color: m3.error }}>{activeFailure}</Typography> : null}
        <RedundantBlocks value={viewed.redundant_blocks} showBlocks={can.view_blocks && data.blocks} />
        {can.view_blocks ? <AddressLookup key={viewed.version_id} version={viewed} /> : null}
      </>}

      {editing !== null ? (
        <ZoneDialog key={editing === "new" ? "new" : editing.zone_id} zone={editing === "new" ? null : editing} revision={revision}
          onClose={() => setEditing(null)} onChanged={refresh}
          onSaved={(name) => { setEditing(null); notify({ kind: "done", lines: [`Zone ${name} was saved to the draft. The draft is not published.`] }); refresh(); }} />
      ) : null}
      {deleting !== null ? (
        <Dialog open onClose={busy ? undefined : () => setDeleting(null)} aria-label={`Delete zone ${deleting.name}`} PaperProps={{ sx: { borderRadius: "28px", width: 480, maxWidth: "95vw" } }}>
          <DialogContent sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2 }}>
            <Typography component="h2" sx={{ ...TYPE.section, m: 0 }}>Delete zone {deleting.name}?</Typography>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              The zone is removed from the draft only. Its addresses become unassigned unless another zone holds them. Nothing is published by deleting.
            </Typography>
            <Stack direction="row" gap={1} justifyContent="flex-end">
              <M3Button emphasis="text" disabled={busy} onClick={() => setDeleting(null)}>Keep zone</M3Button>
              <M3Button emphasis="tonal" disabled={busy} onClick={() => { void remove(deleting); }}>Confirm delete</M3Button>
            </Stack>
          </DialogContent>
        </Dialog>
      ) : null}
    </Stack>
  );
}

/** Blocks that repeat or sit inside another block of the same zone: accepted, shown as a warning (contract 3.2, 7.1). */
function RedundantBlocks({ value, showBlocks }: {
  readonly value: ZoneVersionDetail["redundant_blocks"] | null | undefined;
  readonly showBlocks: boolean;
}) {
  if (typeof value === "number") {
    if (!Number.isFinite(value) || value < 0) {
      return (
        <Box data-testid="redundant-blocks" sx={panelSx}>
          <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
            <StatusChip dense tone="unknown" label="UNKNOWN" />
            <Typography variant="body2">Overlap warnings: UNKNOWN (overlap check is not available for this version).</Typography>
          </Stack>
        </Box>
      );
    }
    if (value === 0) return null;
    return (
      <Box data-testid="redundant-blocks" sx={panelSx}>
        <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
          <StatusChip dense tone="warn" label="Overlap warning" />
          <Typography variant="body2">
            Overlap warning: {value} {value === 1 ? "block repeats or sits" : "blocks repeat or sit"} inside another block of the same zone. This changes no zone&apos;s addresses.
          </Typography>
        </Stack>
      </Box>
    );
  }
  if (!Array.isArray(value)) {
    return (
      <Box data-testid="redundant-blocks" sx={panelSx}>
        <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
          <StatusChip dense tone="unknown" label="UNKNOWN" />
          <Typography variant="body2">Overlap warnings: UNKNOWN (overlap check is not available for this version).</Typography>
        </Stack>
      </Box>
    );
  }
  const count = value.length;
  if (count === 0) return null;
  const rows = showBlocks ? value.slice(0, REDUNDANT_BLOCKS_CAP) : [];
  const more = showBlocks && value.length > REDUNDANT_BLOCKS_CAP ? value.length - REDUNDANT_BLOCKS_CAP : 0;
  return (
    <Box data-testid="redundant-blocks" sx={panelSx}>
      <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
        <StatusChip dense tone="warn" label="Overlap warning" />
        <Typography variant="body2">
          Overlap warning: {count} {count === 1 ? "block repeats or sits" : "blocks repeat or sit"} inside another block of the same zone. This changes no zone&apos;s addresses.
        </Typography>
      </Stack>
      {rows.length > 0 ? (
        <Box component="ul" aria-label="Overlapping blocks" sx={{ m: 0, pl: 2.5 }}>
          {rows.map((r, index) => (
            <li key={index}>
              <Typography variant="body2">
                {r.zone || "UNKNOWN"}: <Box component="span" sx={mono}>{r.block || "UNKNOWN"}</Box> is inside <Box component="span" sx={mono}>{r.within || "UNKNOWN"}</Box>
              </Typography>
            </li>
          ))}
        </Box>
      ) : null}
      {more > 0 ? (
        <Typography variant="body2" data-testid="redundant-blocks-more" sx={{ color: m3.onSurfaceVar }}>
          +{more} more overlapping {more === 1 ? "block" : "blocks"} not shown.
        </Typography>
      ) : null}
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
  const under = version.state === "DRAFT" ? "the draft" : `version ${version.number ?? "UNKNOWN"}`;
  return (
    <Box component="form" aria-label="Address lookup" onSubmit={(e) => { e.preventDefault(); void submit(); }} sx={panelSx}>
      <SectionTitle note={`Which zone holds one address under ${under}.`}>Address lookup</SectionTitle>
      <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
        <TextField size="small" label="Address" placeholder="192.0.2.7" value={address} onChange={(e) => setAddress(e.target.value)} sx={{ minWidth: 280 }}
          inputProps={{ "aria-describedby": "zone-lookup-audit", style: mono }} />
        <M3Button emphasis="tonal" disabled={busy || address.trim() === ""} onClick={() => { void submit(); }}>Look up</M3Button>
        <Hint id="zone-lookup-audit">A lookup is recorded in the audit log</Hint>
      </Stack>
      {result ? (
        <Box role="status" data-testid="lookup-result">
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {result.found.zone_id === null
              ? `${result.address} is not in any zone (unassigned).`
              : `${result.address} is in ${result.found.zone_name ?? "a zone that has no name in this view"}.`}
          </Typography>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            {result.found.family === "IPV6" ? "IPv6" : result.found.family === "IPV4" ? "IPv4" : "family UNKNOWN"},{" "}
            {result.found.state === "DRAFT" ? "draft, not published" : `version ${result.found.number ?? "UNKNOWN"}`}
          </Typography>
        </Box>
      ) : null}
      {failure ? <Typography variant="body2" role="alert" sx={{ color: m3.error }}>{failure}</Typography> : null}
    </Box>
  );
}

/** The non-empty lines of the blocks box: what is sent, and what the screen counts. */
const blockLines = (text: string) => text.split("\n").map((line) => line.trim()).filter((line) => line !== "");

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
  const [showRangeHelper, setShowRangeHelper] = useState(false);
  const [rangeStart, setRangeStart] = useState("");
  const [rangeEnd, setRangeEnd] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<readonly string[]>([]);
  const lines = useMemo(() => blockLines(blocks), [blocks]);
  const classifications = useMemo(() => lines.map(classifyBlockLine), [lines]);
  const rangeProposals = useMemo(
    () => classifications.filter((c): c is Extract<typeof c, { readonly kind: "range" }> => c.kind === "range"),
    [classifications],
  );
  const rangeErrors = useMemo(
    () => classifications.filter((c): c is Extract<typeof c, { readonly kind: "invalid_range" }> => c.kind === "invalid_range"),
    [classifications],
  );
  const hasUnappliedRanges = rangeProposals.length > 0 || rangeErrors.length > 0;
  const decomposedTotal = useMemo(
    () => rangeProposals.reduce((sum, item) => sum + item.decomposed.cidrs.length, 0),
    [rangeProposals],
  );
  const helperPreview = useMemo(() => {
    if (rangeStart.trim() === "" && rangeEnd.trim() === "") return null;
    return decomposeIpRange(rangeStart, rangeEnd);
  }, [rangeStart, rangeEnd]);

  const applyDecompositions = () => {
    if (rangeErrors.length > 0 || rangeProposals.length === 0) return;
    const expanded = classifications.flatMap((c) => (c.kind === "range" ? c.decomposed.cidrs : [c.line]));
    setBlocks(expanded.join("\n"));
    setErrors([]);
  };

  const appendHelperCidrs = () => {
    if (!helperPreview || !helperPreview.ok) return;
    const added = helperPreview.result.cidrs.join("\n");
    setBlocks((prev) => (prev.trim() === "" ? added : `${prev.replace(/\s+$/, "")}\n${added}`));
    setRangeStart("");
    setRangeEnd("");
    setErrors([]);
  };

  const save = async () => {
    const input = { name: name.trim(), description: description.trim() === "" ? null : description.trim(), role: role === "" ? null : role, blocks: lines };
    setBusy(true); setErrors([]);
    try {
      if (zone) await updateZone(revision, zone.zone_id, input);
      else await createZone(revision, input);
      onSaved(input.name);
    } catch (err) {
      setErrors(zoneErrorLines(err, lines));
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
          {zone ? " Saving replaces the zone's whole block list." : ""}
        </Typography>
        <TextField size="small" label="Name" required value={name} onChange={(e) => setName(e.target.value)} inputProps={{ maxLength: 64 }}
          helperText="Unique, 1 to 64 characters" />
        <TextField size="small" label="Description" value={description} onChange={(e) => setDescription(e.target.value)} />
        <TextField select size="small" label="Zone role" value={role} onChange={(e) => setRole(e.target.value)} helperText="Optional">
          <MenuItem value="">None</MenuItem>
          {ZONE_ROLES.map((r) => <MenuItem key={r.value} value={r.value}>{r.label}</MenuItem>)}
        </TextField>
        <TextField label="Blocks" multiline minRows={6} maxRows={16} value={blocks} onChange={(e) => setBlocks(e.target.value)}
          placeholder={"192.0.2.0/24\n2001:db8::/32"} helperText={`${lines.length} ${lines.length === 1 ? "block" : "blocks"}. ${CANONICAL_BLOCK_NOTE}`} InputProps={{ sx: mono }} />
        <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
          <M3Button emphasis="text" disabled={busy} onClick={() => setShowRangeHelper((v) => !v)}>
            {showRangeHelper ? "Hide range helper" : "Decompose IP range"}
          </M3Button>
        </Stack>
        {showRangeHelper ? (
          <Box data-testid="range-helper-panel" sx={{ ...panelSx, bgcolor: m3.scLow, p: 1.5 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>Decompose address range to CIDR</Typography>
            <Stack direction={{ xs: "column", sm: "row" }} gap={1.5}>
              <TextField size="small" label="Start address" placeholder="192.0.2.10" value={rangeStart}
                onChange={(e) => setRangeStart(e.target.value)} InputProps={{ sx: mono }} fullWidth />
              <TextField size="small" label="End address" placeholder="192.0.2.20" value={rangeEnd}
                onChange={(e) => setRangeEnd(e.target.value)} InputProps={{ sx: mono }} fullWidth />
            </Stack>
            {helperPreview ? (
              helperPreview.ok ? (
                <Stack gap={1}>
                  <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                    {helperPreview.result.family === "IPV6" ? "IPv6" : "IPv4"}:{" "}
                    {addressCount(helperPreview.result.addressCount.toString())} {helperPreview.result.addressCount === 1n ? "address" : "addresses"}{" "}
                    ({helperPreview.result.cidrs.length} CIDR {helperPreview.result.cidrs.length === 1 ? "block" : "blocks"})
                  </Typography>
                  <Box component="ul" aria-label="Range CIDR preview" sx={{ m: 0, p: 0, listStyle: "none", maxHeight: 140, overflowY: "auto", ...mono }}>
                    {helperPreview.result.cidrs.map((cidr) => <li key={cidr}>{cidr}</li>)}
                  </Box>
                </Stack>
              ) : (
                <Typography variant="body2" role="alert" sx={{ color: m3.error }}>{helperPreview.reason}</Typography>
              )
            ) : (
              <Hint>Enter start and end IPv4 or IPv6 addresses to preview the exact CIDR decomposition.</Hint>
            )}
            <Stack direction="row" justifyContent="flex-end">
              <M3Button emphasis="tonal" disabled={busy || !helperPreview?.ok} onClick={appendHelperCidrs}>Add CIDRs to blocks</M3Button>
            </Stack>
          </Box>
        ) : null}
        {hasUnappliedRanges ? (
          <Box data-testid="cidr-decomposition-preview" sx={{ ...panelSx, bgcolor: m3.scLow, p: 1.5 }}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>CIDR decomposition preview</Typography>
              {rangeProposals.length > 0 ? (
                <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                  {decomposedTotal} CIDR {decomposedTotal === 1 ? "block" : "blocks"} from {rangeProposals.length} {rangeProposals.length === 1 ? "range" : "ranges"}
                </Typography>
              ) : null}
            </Stack>
            {rangeErrors.length > 0 ? (
              <Box role="alert" sx={{ color: m3.error, display: "flex", flexDirection: "column", gap: 0.5 }}>
                {rangeErrors.map((err, idx) => (
                  <Typography key={`${err.line}-${idx}`} variant="body2">
                    <Box component="span" sx={mono}>{err.line}</Box>: {err.reason}
                  </Typography>
                ))}
              </Box>
            ) : null}
            {rangeProposals.map((item, idx) => (
              <Box key={`${item.line}-${idx}`} sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2">
                  <Box component="span" sx={mono}>{item.line}</Box>{" "}
                  <Box component="span" sx={{ color: m3.onSurfaceVar }}>
                    ({item.decomposed.family === "IPV6" ? "IPv6" : "IPv4"},{" "}
                    {addressCount(item.decomposed.addressCount.toString())} {item.decomposed.addressCount === 1n ? "address" : "addresses"})
                  </Box>
                </Typography>
                <Box component="ul" aria-label={`CIDR decomposition for ${item.line}`} sx={{ m: 0, pl: 2, maxHeight: 140, overflowY: "auto", ...mono }}>
                  {item.decomposed.cidrs.map((cidr) => <li key={cidr}>{cidr}</li>)}
                </Box>
              </Box>
            ))}
            <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
              <Hint>
                {rangeErrors.length > 0
                  ? "Fix the invalid address range above before applying or saving."
                  : "Apply the CIDR decomposition before saving."}
              </Hint>
              {rangeProposals.length > 0 ? (
                <M3Button emphasis="tonal" disabled={busy || rangeErrors.length > 0} onClick={applyDecompositions}>
                  Apply CIDR decomposition
                </M3Button>
              ) : null}
            </Stack>
          </Box>
        ) : null}
        {errors.length > 0 ? (
          <Box role="alert" sx={{ color: m3.error }}>
            {errors.map((line, index) => <Typography key={index} variant="body2" sx={{ fontWeight: index === 0 ? 600 : 400 }}>{line}</Typography>)}
          </Box>
        ) : null}
        <Stack direction="row" gap={1} justifyContent="flex-end">
          <M3Button emphasis="text" disabled={busy} onClick={onClose}>Cancel</M3Button>
          <M3Button emphasis="filled" disabled={busy || name.trim() === "" || hasUnappliedRanges} onClick={() => { void save(); }}>Save to draft</M3Button>
        </Stack>
      </DialogContent>
    </Dialog>
  );
}
