import { useRef, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Dialog from "@mui/material/Dialog";
import Drawer from "@mui/material/Drawer";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { acceptBackupSize, type BackupDeviations, type BackupMajorDeviation, type BackupSizeAnomaly } from "../auth/adminApi";
import { MetricCard } from "../shell/ScreenLayout";
import { Stamp } from "../shell/PresentedText";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { requestFailure } from "../shell/presenters";
import { m3 } from "../theme/m3Theme";

/**
 * Backups "Active major deviations" (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md): the number of devices whose
 * configuration changed in a major section (interfaces, routing, rules, NAT, admin access, HA) in the last 7 days,
 * read from the configuration runs. UNKNOWN only when no device could be compared; otherwise "N of M devices
 * compared", so a partial comparison is never read as "no deviation".
 *
 * Backup size anomalies (V180, PO decision 2026-10-30, docs/design/BACKUP_SIZE_ANOMALY_RETENTION.md) count on the same
 * card: a backup that grew to 5x (or shrank to a fifth) of the device's normal size. While one is open the last good
 * backup is kept; a security_admin "accepts the new size" with a reason to release it.
 */

export const ACCEPT_REASON_MAX = 500;

/** The device's configuration Details > Changes tab (Inventory reads `config_tab`). */
export const configChangesHref = (deviceId: string) =>
  `?screen=inventory&device_id=${encodeURIComponent(deviceId)}&tab=configuration&config_tab=changes`;

const CATEGORY_WORDS: Record<string, string> = {
  interfaces: "Interfaces",
  routing: "Routing",
  access_rules: "Access rules",
  nat: "NAT",
  admin_access: "Admin access",
  high_availability: "Cluster / HA",
};

export function categoryWord(category: string): string {
  return CATEGORY_WORDS[category] ?? category;
}

/** Devices with at least one active major deviation (rows are per device and category) or an open size anomaly. */
export function majorDeviceIds(deviations: BackupDeviations | null): ReadonlySet<string> {
  return new Set([...(deviations?.active_major_deviations ?? []).map((row) => row.device_id),
    ...(deviations?.size_anomalies ?? []).map((row) => row.device_id)]);
}

/** "1.0 GB", "350 MB": the card's size words (binary units, as the server's summary). */
export function sizeWords(bytes: number): string {
  const gib = bytes / 1024 ** 3;
  return gib >= 1 ? `${gib.toFixed(1)} GB` : `${Math.round(bytes / 1024 ** 2)} MB`;
}

/** "Grew 29.0x" / "Shrank to 0.1x": the anomaly's chip. */
export function sizeAnomalyWord(row: Pick<BackupSizeAnomaly, "direction" | "ratio">): string {
  return row.direction === "shrink" ? `Shrank to ${row.ratio.toFixed(1)}x` : `Grew ${row.ratio.toFixed(1)}x`;
}

/** One line per device: its categories, newest change first. */
export function majorDeviationsByDevice(rows: readonly BackupMajorDeviation[]): { deviceId: string; name: string; vendor: string; changedAt: string; categories: string[]; sections: string[] }[] {
  const byDevice = new Map<string, { deviceId: string; name: string; vendor: string; changedAt: string; categories: string[]; sections: string[] }>();
  for (const row of rows) {
    const entry = byDevice.get(row.device_id) ?? { deviceId: row.device_id, name: row.hostname ?? row.device_id, vendor: row.vendor, changedAt: row.changed_at, categories: [], sections: [] };
    if (row.changed_at > entry.changedAt) entry.changedAt = row.changed_at;
    entry.categories.push(categoryWord(row.category));
    for (const s of row.sections) if (!entry.sections.includes(s)) entry.sections.push(s);
    byDevice.set(row.device_id, entry);
  }
  return Array.from(byDevice.values()).sort((a, b) => (a.changedAt < b.changedAt ? 1 : a.changedAt > b.changedAt ? -1 : a.name.localeCompare(b.name)));
}

function comparedSentence(deviations: BackupDeviations): string {
  const days = deviations.window_days ?? 7;
  const known = deviations.devices_known;
  const n = deviations.total_deviations_checked;
  return known !== undefined && known > 0
    ? `${n} of ${known} devices compared · last ${days} days`
    : `${n} device${n === 1 ? "" : "s"} compared · last ${days} days`;
}

/** Minute key in UTC ("2026-10-20T06:00"); the raw value when it is not a date, so nothing unequal is merged. */
function minuteKey(at: string): string {
  const t = new Date(at);
  return Number.isNaN(t.getTime()) ? at : t.toISOString().slice(0, 16);
}

export type DeviationLine = ReturnType<typeof majorDeviationsByDevice>[number];

/** One line of the list: a single device, or several devices with the same change in the same minute. */
export type DeviationGroup = { readonly key: string; readonly devices: readonly DeviationLine[] };

/**
 * UI review 2026-11-05, B2: simultaneous identical events are one event. Devices whose newest major change falls in
 * the same minute with the same categories and sections are one line ("21 devices, same minute, Admin access: users");
 * a device alone stays a line of its own. Order follows the per-device order (newest first).
 */
export function groupSimultaneousDeviations(lines: readonly DeviationLine[]): DeviationGroup[] {
  const groups = new Map<string, DeviationLine[]>();
  for (const line of lines) {
    const key = [minuteKey(line.changedAt), [...line.categories].sort().join("|"), [...line.sections].sort().join("|")].join("#");
    const group = groups.get(key);
    if (group) group.push(line); else groups.set(key, [line]);
  }
  return Array.from(groups.entries()).map(([key, devices]) => ({ key, devices }));
}

/** "Admin access: users" -- the change words of a group line. */
export function changeWords(line: Pick<DeviationLine, "categories" | "sections">): string {
  const sections = line.sections.join(", ");
  return sections ? `${line.categories.join(", ")}: ${sections}` : line.categories.join(", ");
}

export function BackupDeviationCard({ deviations, onOpen }: { readonly deviations: BackupDeviations | null; readonly onOpen?: () => void }) {
  // An open size anomaly is known without any configuration comparison, so it alone makes the figure known.
  const compared = deviations !== null && (deviations.total_deviations_checked > 0 || (deviations.size_anomalies ?? []).length > 0);
  const count = majorDeviceIds(deviations).size;
  return (
    <MetricCard
      title="Active major deviations"
      value={compared ? count : null}
      unit={compared ? (count === 1 ? "device" : "devices") : undefined}
      state={compared ? "ok" : "unknown"}
      reason={deviations
        ? "No device has two configuration reads to compare in the last 7 days, so no deviation can be claimed or ruled out"
        : "The deviation read failed"}
      note={deviations ? comparedSentence(deviations) : "Deviation state unavailable"}
      href={compared && count > 0 ? "#major-deviations" : undefined}
      linkLabel="Which devices →"
      onLinkClick={onOpen}
    />
  );
}

/** The devices behind the card, each linking to its configuration Changes tab; nothing when there are none. */
export function MajorDeviationList({ deviations, onChanged }: { readonly deviations: BackupDeviations | null; readonly onChanged?: () => void }) {
  const rows = majorDeviationsByDevice(deviations?.active_major_deviations ?? []);
  const sizes = deviations?.size_anomalies ?? [];
  if (rows.length === 0 && sizes.length === 0) return null;
  return (
    <Card id="major-deviations" aria-label="Active major deviations" sx={{ p: 2, borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none", maxWidth: 1180 }}>
      {sizes.length > 0 && <SizeAnomalyList rows={sizes} canAccept={deviations?.can_accept_size === true}
        roleNote={deviations?.size_role_note ?? null} onChanged={onChanged} />}
      {rows.length > 0 && <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar, mb: 1 }}>
        Major configuration changes · last {deviations?.window_days ?? 7} days
      </Typography>}
      {rows.length > 0 && <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: 0.75 }}>
        {groupSimultaneousDeviations(rows).map((group) => group.devices.length === 1
          ? <DeviceLine key={group.key} row={group.devices[0]} />
          : <GroupLine key={group.key} devices={group.devices} />)}
      </Box>}
    </Card>
  );
}

function DeviceLink({ row }: { readonly row: DeviationLine }) {
  return (
    <Link href={configChangesHref(row.deviceId)} underline="hover" sx={{ fontWeight: 600, color: m3.primary }}
      aria-label={`${row.name}: open the configuration changes`}>
      {row.name}
    </Link>
  );
}

function DeviceLine({ row }: { readonly row: DeviationLine }) {
  return (
    <Box component="li" sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
      <DeviceLink row={row} />
      {row.categories.map((c) => <StatusChip key={c} tone="bad" label={c} dense />)}
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        {row.sections.join(", ")} · changed <Stamp at={row.changedAt} />
      </Typography>
    </Box>
  );
}

/** Several devices, one change, one minute: one line, the devices behind a disclosure. */
function GroupLine({ devices }: { readonly devices: readonly DeviationLine[] }) {
  const [open, setOpen] = useState(false);
  const first = devices[0];
  return (
    <Box component="li" data-testid="deviation-group" sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <Typography sx={{ fontWeight: 600 }}>{devices.length} devices, same minute, {changeWords(first)}</Typography>
        {first.categories.map((c) => <StatusChip key={c} tone="bad" label={c} dense />)}
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>changed <Stamp at={first.changedAt} /></Typography>
        <Link component="button" type="button" underline="hover" onClick={() => setOpen((v) => !v)} aria-expanded={open}
          sx={{ color: m3.primary, fontSize: 13 }}>
          {open ? "Hide devices" : `Show ${devices.length} devices`}
        </Link>
      </Box>
      {open && <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap", pl: 1 }}>
        {devices.map((row) => <DeviceLink key={row.deviceId} row={row} />)}
      </Box>}
    </Box>
  );
}

/**
 * The devices behind the card in a right-hand drawer (UI review 2026-11-05, B2): the backups table stays above the
 * fold and the list stays on Backups, one click from the card.
 */
export function MajorDeviationDrawer({ open, onClose, deviations, onChanged }: { readonly open: boolean; readonly onClose: () => void;
  readonly deviations: BackupDeviations | null; readonly onChanged?: () => void }) {
  return (
    <Drawer anchor="right" open={open} onClose={onClose} PaperProps={{ sx: { width: { xs: "100%", md: 720 }, p: 2, bgcolor: m3.surface } }}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 1.5 }}>
        <Typography component="h2" sx={{ fontSize: 18, fontWeight: 600 }}>Active major deviations</Typography>
        <M3Button emphasis="text" onClick={onClose}>Close</M3Button>
      </Box>
      <MajorDeviationList deviations={deviations} onChanged={onChanged} />
    </Drawer>
  );
}

/**
 * Open backup size anomalies: sizes and ratio, the last good backup kept, and (security_admin) "Accept new size" with a
 * mandatory reason. The actor is the server-side session, so the dialog sends the reason and nothing else.
 */
function SizeAnomalyList({ rows, canAccept, roleNote, onChanged }: { readonly rows: readonly BackupSizeAnomaly[];
  readonly canAccept: boolean; readonly roleNote: string | null; readonly onChanged?: () => void }) {
  const [target, setTarget] = useState<BackupSizeAnomaly | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const accept = async () => {
    if (!target || lock.current) return;
    lock.current = true; setBusy(true); setError(null);
    try {
      await acceptBackupSize(target.device_id, reason.trim());
      setTarget(null); setReason(""); onChanged?.();
    } catch (e) { setError(requestFailure(e as Parameters<typeof requestFailure>[0])); }
    finally { lock.current = false; setBusy(false); }
  };
  const name = (row: BackupSizeAnomaly) => row.hostname ?? row.device_id;
  return (
    <Box aria-label="Backup size anomalies" sx={{ mb: 1.5 }}>
      <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar, mb: 1 }}>
        Backup size anomalies · last good backup kept until the new size is accepted
      </Typography>
      <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: 0.75 }}>
        {rows.map((row) => (
          <Box component="li" key={row.device_id} sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Typography sx={{ fontWeight: 600 }}>{name(row)}</Typography>
            <StatusChip tone="bad" label={sizeAnomalyWord(row)} dense />
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              {sizeWords(row.median_bytes)} → {sizeWords(row.plaintext_bytes)} · {row.open_copies} anomalous
              {row.open_copies === 1 ? " backup" : " backups"} (only the newest kept) · detected <Stamp at={row.detected_at} />
            </Typography>
            {canAccept && <M3Button emphasis="outlined" disabled={busy}
              onClick={() => { setTarget(row); setReason(""); setError(null); }}>Accept new size</M3Button>}
          </Box>
        ))}
      </Box>
      {!canAccept && <Typography variant="body2" sx={{ mt: 0.5, color: m3.onSurfaceVar }}>
        {roleNote ?? "Only a security_admin can accept a new backup size."}</Typography>}
      <Dialog open={target !== null} onClose={() => { if (!busy) setTarget(null); }}>
        <DialogTitle>Accept new backup size</DialogTitle>
        <DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
          <Typography>{target ? `${name(target)}: ${sizeWords(target.median_bytes)} → ${sizeWords(target.plaintext_bytes)}` : ""}</Typography>
          <Typography>Accept only when the new size is expected. The last good backup is then no longer kept beyond the
            retention days, and the new size becomes the device's normal. Your account and the reason are recorded.</Typography>
          <TextField label="Reason" required multiline minRows={2} value={reason}
            inputProps={{ maxLength: ACCEPT_REASON_MAX }} onChange={(e) => setReason(e.target.value)} />
          {error && <Typography role="alert" color="error">{error}</Typography>}
        </Stack></DialogContent>
        <DialogActions>
          <M3Button emphasis="text" disabled={busy} onClick={() => setTarget(null)}>Cancel</M3Button>
          <M3Button emphasis="filled" disabled={busy || !reason.trim()} onClick={() => void accept()}>Accept</M3Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
