import { useRef, useState } from "react";
import Box from "@mui/material/Box";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  alignPacketTelemetry,
  deletePacketTelemetry,
  uploadPacketTelemetry,
  type PacketAnalysisPermissions,
  type PacketTelemetry,
  type PacketTelemetryFormat,
} from "../../auth/adminApi";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { m3, MONO } from "../../theme/m3Theme";
import { packetBytes, packetError } from "./packetFormat";

const FORMAT_LABEL: Record<PacketTelemetryFormat, string> = {
  sar: "sar report", vmstat: "vmstat", pidstat: "pidstat", snapshots: "tc qdisc / softnet_stat snapshots",
  syslog: "syslog / messages", unknown: "Not recognized",
};
/** The sniffer's closed reasons, in words an operator can act on. */
const NOTE_LABEL: Record<string, string> = {
  EMPTY: "The file is empty.",
  NO_KNOWN_FORMAT: "No sar, vmstat, pidstat, qdisc/softnet snapshot or syslog structure in the first 400 lines.",
  NO_KNOWN_BLOCK: "A sysstat file, but none of the blocks the report uses (CPU, queue, tasks, network, TCP).",
};
const OFFSET_LIMIT = 86_400;

/** What the report will do with the file's clock, stated plainly (never a silent guess). */
export function packetAlignmentText(item: PacketTelemetry): string {
  const zone = item.zone_confirmed && item.time_zone ? `${item.time_zone} (confirmed)` : "UTC (assumed, not confirmed)";
  const offset = item.offset_seconds ? `, clock offset ${item.offset_seconds > 0 ? "+" : ""}${item.offset_seconds} s` : "";
  const start = item.anchor_local ? `, first line at ${item.anchor_local}` : "";
  return `${zone}${offset}${start}`;
}

interface Props {
  readonly caseId: string;
  readonly items: readonly PacketTelemetry[];
  readonly permissions: PacketAnalysisPermissions | undefined;
  readonly maxBytes: number | undefined;
  readonly onChanged: () => void;
  readonly onError: (message: string | null) => void;
  readonly onNotice: (message: string | null) => void;
}

/**
 * Server-side logs attached to a case. The expert report places them on the capture timeline; the clock alignment is the
 * user's to confirm, and a file left unconfirmed is read as UTC with that stated in the report.
 */
export function PacketHostTelemetry({ caseId, items, permissions, maxBytes, onChanged, onError, onNotice }: Props) {
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<{ name: string; sent: number; total: number } | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [zone, setZone] = useState("");
  const [offset, setOffset] = useState("0");
  const [anchor, setAnchor] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const canRun = permissions?.run === true;

  const upload = async (file: File | undefined) => {
    if (!file) return;
    onNotice(null); onError(null);
    if (maxBytes && file.size > maxBytes) { onError(`A telemetry file may be at most ${packetBytes(maxBytes)}.`); return; }
    setBusy(true); setProgress({ name: file.name, sent: 0, total: file.size });
    try {
      const stored = await uploadPacketTelemetry(caseId, file, (sent, total) => setProgress({ name: file.name, sent, total }));
      onNotice(stored.recognized
        ? `Telemetry attached (${FORMAT_LABEL[stored.detected_format]}). Confirm its time zone so the report can align it.`
        : "Telemetry attached, but its format was not recognized; the report lists it with the reason.");
      onChanged();
    } catch (err) { onError(packetError(err, "The telemetry upload was refused.")); }
    finally { setBusy(false); setProgress(null); }
  };
  const startEdit = (item: PacketTelemetry) => {
    setEditing(item.telemetry_id);
    setZone(item.zone_confirmed && item.time_zone ? item.time_zone : "");
    setOffset(String(item.offset_seconds));
    setAnchor(item.anchor_local ?? "");
  };
  const save = async (item: PacketTelemetry) => {
    onNotice(null); onError(null);
    const seconds = Number(offset.trim() === "" ? "0" : offset);
    if (!Number.isInteger(seconds) || Math.abs(seconds) > OFFSET_LIMIT) {
      onError(`The clock offset must be a whole number of seconds within ±${OFFSET_LIMIT}.`);
      return;
    }
    setBusy(true);
    try {
      await alignPacketTelemetry(item.telemetry_id, { time_zone: zone.trim() || null, offset_seconds: seconds, anchor_local: anchor.trim() || null });
      setEditing(null); onNotice("Clock alignment saved."); onChanged();
    } catch (err) { onError(packetError(err, "The alignment was refused.")); }
    finally { setBusy(false); }
  };
  const remove = async (item: PacketTelemetry) => {
    onNotice(null); onError(null); setBusy(true);
    try { await deletePacketTelemetry(item.telemetry_id); onNotice("Telemetry file deleted."); onChanged(); }
    catch (err) { onError(packetError(err, "The file could not be deleted.")); }
    finally { setBusy(false); }
  };

  return (
    <Box sx={{ mt: 2 }}>
      <Typography variant="subtitle2">Host telemetry</Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        Server-side text files (sar, vmstat, pidstat, tc -s qdisc / softnet_stat snapshots, syslog). The format is detected from the content.
        The expert report places them on the capture timeline. A file with no confirmed time zone is read as UTC, and the report says so.
        They are kept as long as the case.
      </Typography>
      <TableContainer><Table size="small" aria-label="Host telemetry"><TableHead><TableRow>
        <TableCell>Name</TableCell><TableCell>Format</TableCell><TableCell>Size</TableCell><TableCell>Clock alignment</TableCell>
        {canRun && <TableCell />}
      </TableRow></TableHead><TableBody>
        {items.map(item => <TableRow key={item.telemetry_id}>
          <TableCell>{item.display_name}{item.sha256 && <Typography variant="caption" display="block" sx={{ fontFamily: MONO }}>sha256 {item.sha256.slice(0, 16)}…</Typography>}</TableCell>
          <TableCell>
            <StatusChip tone={item.recognized ? "ok" : "attn"} label={FORMAT_LABEL[item.detected_format] ?? item.detected_format} dense />
            {!item.recognized && item.recognition_note && <Typography variant="caption" display="block">{NOTE_LABEL[item.recognition_note] ?? item.recognition_note}</Typography>}
          </TableCell>
          <TableCell>{packetBytes(item.size_bytes)}</TableCell>
          <TableCell>
            {editing === item.telemetry_id ? (
              <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }} aria-label="Clock alignment form">
                <TextField label="Time zone" size="small" value={zone} onChange={e => setZone(e.target.value)} disabled={busy}
                  placeholder="Europe/Istanbul or +03:00" helperText="Empty: not confirmed, UTC assumed" />
                <TextField label="Clock offset (s)" size="small" value={offset} onChange={e => setOffset(e.target.value)} disabled={busy}
                  inputProps={{ inputMode: "numeric" }} helperText="Added to the file's time" />
                <TextField label="First line time" size="small" value={anchor} onChange={e => setAnchor(e.target.value)} disabled={busy}
                  placeholder="2026-10-08 13:00:00" helperText="Only for files without a date or time" />
                <M3Button emphasis="filled" disabled={busy} onClick={() => void save(item)}>Save alignment</M3Button>
                <M3Button emphasis="text" disabled={busy} onClick={() => setEditing(null)}>Cancel</M3Button>
              </Stack>
            ) : (
              <Typography variant="body2">{packetAlignmentText(item)}</Typography>
            )}
          </TableCell>
          {canRun && <TableCell>
            <M3Button emphasis="text" disabled={busy} onClick={() => startEdit(item)}>Set clock</M3Button>
            <M3Button emphasis="text" disabled={busy} onClick={() => void remove(item)}>Delete</M3Button>
          </TableCell>}
        </TableRow>)}
        {!items.length && <TableRow><TableCell colSpan={canRun ? 5 : 4}>No host telemetry attached</TableCell></TableRow>}
      </TableBody></Table></TableContainer>
      {canRun && (
        <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 1 }}>
          <M3Button emphasis="tonal" disabled={busy} onClick={() => input.current?.click()}>Add host telemetry</M3Button>
          <input ref={input} aria-label="Host telemetry file" type="file" hidden
            onChange={e => { void upload(e.target.files?.[0]); e.target.value = ""; }} />
        </Stack>
      )}
      {progress && <Box role="status" aria-label="Telemetry upload progress" sx={{ mt: 1 }}>
        <Typography variant="body2">Uploading {progress.name}: {packetBytes(progress.sent)} of {packetBytes(progress.total)}</Typography>
        <LinearProgress variant="determinate" value={progress.total ? Math.min(100, (100 * progress.sent) / progress.total) : 0} />
      </Box>}
    </Box>
  );
}
