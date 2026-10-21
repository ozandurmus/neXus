import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  getPacketCaseDeviceEvents,
  type ApiError,
  type PacketDeviceEvent,
  type PacketDeviceEventsResult,
  type PacketDeviceEventsRow,
  type PacketDeviceEventsView,
} from "../../auth/adminApi";
import { M3Button } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { PillRow, StatusPill } from "../../shell/StatusPill";
import { DISPLAY_TZ_LABEL, formatUtc } from "../../shell/time";
import { vendorLabel } from "../../shell/presenters";
import { m3, MONO } from "../../theme/m3Theme";
import { TYPE, type StateRole } from "../../theme/tokens";

/**
 * Packet Analysis > case > Device events (mockup 2026-10-17, screen 4): "what was the device saying at the moment of
 * the delay?". For one stored finding (its hop, delay and loss figures and start time) the server reads, for the
 * window start ± N seconds, the system-log facts neXus already stores for the firewalls on the capture path: cpwd
 * restart/termination lines, crash/dump alerts, collection gap markers and line counts per source.
 *
 * A capture point is tied to a device only by the case's stored inventory correlation; a capture point without one
 * reads "device not linked", never a guess from its label. Every row ends in one result word: Clean, Events (n),
 * Not collected, Not permitted or UNKNOWN. The aiview persona receives the masked projection from the server; this
 * view renders names as they arrive and decides nothing by role.
 */

export const DEFAULT_WINDOW_SECONDS = 30;
export const MIN_WINDOW_SECONDS = 5;
export const MAX_WINDOW_SECONDS = 600;

export function resultTone(result: PacketDeviceEventsResult): StateRole {
  switch (result) {
    case "CLEAN": return "ok";
    case "EVENTS": return "warn";
    case "UNKNOWN": return "unknown";
    default: return "neutral";
  }
}
export function resultWord(result: PacketDeviceEventsResult): string {
  switch (result) {
    case "CLEAN": return "Clean";
    case "EVENTS": return "Events";
    case "NOT_COLLECTED": return "Not collected";
    case "NOT_PERMITTED": return "Not permitted";
    default: return "UNKNOWN";
  }
}

const EVENT_KIND: Readonly<Record<string, string>> = { CPWD_LINE: "cpwd", CRASH_ALERT: "Crash/dump", GAP: "Gap" };
const SOURCE_LABELS: Readonly<Record<string, string>> = {
  cp_cpwd_elg: "cpwd.elg", cp_messages: "messages", cp_fwd_elg: "fwd.elg", cp_crash_listing: "crash listing",
  pan_system: "system log", pan_config: "config log",
};
export function sourceLabel(key: string): string {
  return SOURCE_LABELS[key] ?? key;
}

/** A display stamp with seconds; device-clock text is shown as it was stored, with its own label. */
function eventTime(event: PacketDeviceEvent) {
  if (event.time_basis === "DEVICE_LOCAL" && event.device_time) {
    return (
      <Box component="time" dateTime={event.time ?? undefined} title={`device clock; read as ${event.time ?? "UNKNOWN"} (Europe/Istanbul assumed)`}
        sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, whiteSpace: "nowrap" }}>
        {event.device_time}
        <Box component="span" sx={{ ml: 0.75, fontFamily: "Roboto, sans-serif", fontSize: TYPE.label.fontSize, color: m3.onSurfaceVar }}>device clock</Box>
      </Box>
    );
  }
  return (
    <Box component="time" dateTime={event.time ?? undefined} sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, whiteSpace: "nowrap" }}>
      {event.time ? `${formatUtc(event.time, true)} ${DISPLAY_TZ_LABEL}` : "UNKNOWN"}
    </Box>
  );
}

function stamp(iso: string | null | undefined): string {
  return iso ? `${formatUtc(iso, true)} ${DISPLAY_TZ_LABEL}` : "UNKNOWN";
}

function figure(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? "UNKNOWN" : `${value}${unit}`;
}

export function PacketDeviceEventsTab({ caseId, initialFinding = "" }: { readonly caseId: string; readonly initialFinding?: string }) {
  const [draft, setDraft] = useState<{ finding: string; window: string }>({ finding: initialFinding, window: String(DEFAULT_WINDOW_SECONDS) });
  const [query, setQuery] = useState<{ finding: string; window: number }>({ finding: initialFinding, window: DEFAULT_WINDOW_SECONDS });
  const [data, setData] = useState<PacketDeviceEventsView | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setData(null); setError(null);
    getPacketCaseDeviceEvents(caseId, {
      finding: query.finding || undefined,
      window_seconds: query.window === DEFAULT_WINDOW_SECONDS ? undefined : query.window,
    }).then((view) => {
      if (!view || !Array.isArray(view.devices) || !Array.isArray(view.findings)) throw new Error("Invalid device events response");
      if (!active) return;
      setData(view);
      if (view.finding) setDraft((d) => (d.finding ? d : { ...d, finding: view.finding!.id }));
    }).catch((failure: unknown) => { if (active) setError(failure ?? new Error("Could not load the device events")); });
    return () => { active = false; };
  }, [caseId, query, revision]);

  const windowValue = Number(draft.window);
  const windowValid = Number.isInteger(windowValue) && windowValue >= MIN_WINDOW_SECONDS && windowValue <= MAX_WINDOW_SECONDS;
  const apply = () => { if (windowValid) setQuery({ finding: draft.finding, window: windowValue }); };

  const apiError = error as ApiError | null;
  const code = typeof apiError?.body?.code === "string" ? apiError.body.code : typeof apiError?.body?.error === "string" ? apiError.body.error : null;
  const reason = typeof apiError?.body?.message === "string" ? apiError.body.message : null;
  const notReady = apiError?.status === 409;
  const timedFindings = (data?.findings ?? []).filter((f) => f.start);

  return (
    <Box role="region" aria-label="Device events" sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 860 }}>
        What the firewalls on the capture path stored around the moment of a finding: cpwd restarts and terminations,
        crash and dump files, collection gaps and the log lines collected per source. Nothing here says what caused the
        finding; a quiet window says only that the stored logs hold no event.
      </Typography>

      <Box component="form" aria-label="Device events window" onSubmit={(event) => { event.preventDefault(); apply(); }}>
        <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
          <TextField select size="small" label="Finding" value={timedFindings.some((f) => f.id === draft.finding) ? draft.finding : ""} sx={{ minWidth: 320 }}
            onChange={(e) => setDraft((d) => ({ ...d, finding: e.target.value }))} disabled={timedFindings.length === 0}>
            {timedFindings.length === 0 && <MenuItem value="">No finding with a start time</MenuItem>}
            {timedFindings.map((f) => (
              <MenuItem key={f.id} value={f.id}>{`${f.type ?? "finding"} · ${f.hop_label ?? f.hop ?? "no hop"} · ${stamp(f.start)}`}</MenuItem>
            ))}
          </TextField>
          <TextField size="small" type="number" label="Window ± seconds" value={draft.window} sx={{ width: 170 }}
            inputProps={{ min: MIN_WINDOW_SECONDS, max: MAX_WINDOW_SECONDS, step: 1 }}
            error={!windowValid} helperText={windowValid ? undefined : `${MIN_WINDOW_SECONDS} to ${MAX_WINDOW_SECONDS} seconds`}
            onChange={(e) => setDraft((d) => ({ ...d, window: e.target.value }))} />
          <M3Button emphasis="tonal" onClick={apply} disabled={!windowValid}>Show device events</M3Button>
        </Stack>
      </Box>

      {error !== null && isRestricted(error) && <RestrictedPanel area="Device events" role="Operator" nextStep="Packet Analysis case reads are gated the same way." />}
      {error !== null && !isRestricted(error) && notReady && (
        <StatePanel variant="not_evaluated" announce="status" title="Device events need a completed analysis" body={reason ?? undefined} code={code} />
      )}
      {error !== null && !isRestricted(error) && !notReady && (
        <StatePanel variant="error" announce="status" title="Could not load the device events" body={reason ?? undefined} code={code}
          action={<M3Button emphasis="outlined" onClick={() => setRevision((n) => n + 1)}>Retry</M3Button>} />
      )}
      {!data && error === null && <Typography role="status" variant="body2">Loading device events…</Typography>}

      {data && !data.finding && (
        <StatePanel variant="empty" announce="status" title="No finding with a start time" body={data.notes.join(" ")} />
      )}
      {data && data.finding && data.window && (
        <>
          <FindingSheet view={data} />
          <DeviceTable rows={data.devices} />
          {data.devices.length === 0 && (
            <StatePanel variant="empty" announce="status" title="No capture point in the topology" body="Connect the capture points in Topology to list the devices on the path." />
          )}
          <Box role="status" aria-label="Device events notes" sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
            {data.notes.map((note) => (
              <Typography key={note} variant="caption" sx={{ color: m3.onSurfaceVar }}>{note}</Typography>
            ))}
          </Box>
        </>
      )}
    </Box>
  );
}

function FindingSheet({ view }: { readonly view: PacketDeviceEventsView }) {
  const f = view.finding!;
  const w = view.window!;
  const items: readonly [string, string][] = [
    ["Finding", f.type ?? "UNKNOWN"],
    ["Severity", f.severity ?? "UNKNOWN"],
    ["Hop", f.hop_label ?? f.hop ?? "UNKNOWN"],
    ["Start", stamp(f.start)],
    ["p95 delay", figure(f.figures.p95_ms, " ms")],
    ["Max delay", figure(f.figures.max_ms, " ms")],
    ["Loss", figure(f.figures.loss_percent, " %")],
    ["Loss events", figure(f.figures.event_count, "")],
  ];
  return (
    <Card aria-label="Finding sheet" sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}>
      <Box component="dl" sx={{ m: 0, display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(170px, 1fr))", gap: 1.5 }}>
        {items.map(([term, value]) => (
          <Box key={term}>
            <Typography component="dt" variant="caption" sx={{ color: m3.onSurfaceVar }}>{term}</Typography>
            <Typography component="dd" variant="body2" sx={{ m: 0, fontWeight: 500, overflowWrap: "anywhere" }}>{value}</Typography>
          </Box>
        ))}
      </Box>
      <Typography variant="caption" sx={{ display: "block", mt: 1.5, color: m3.onSurfaceVar }}>
        {`Window ${stamp(w.from)} — ${stamp(w.to)} (start ± ${w.half_seconds} s)`}{view.masked ? " · masked view" : ""}
      </Typography>
    </Card>
  );
}

function DeviceTable({ rows }: { readonly rows: readonly PacketDeviceEventsRow[] }) {
  if (rows.length === 0) return null;
  return (
    <Table size="small" aria-label="Device events per device">
      <TableHead>
        <TableRow>
          <TableCell>Device</TableCell>
          <TableCell>Hop</TableCell>
          <TableCell>Events in window</TableCell>
          <TableCell>Lines per source</TableCell>
          <TableCell>Result</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((row, index) => <DeviceRow key={`${row.label}-${index}`} row={row} />)}
      </TableBody>
    </Table>
  );
}

function DeviceRow({ row }: { readonly row: PacketDeviceEventsRow }) {
  const linked = row.link.state === "LINKED";
  return (
    <TableRow data-result={row.result} data-linked={linked}>
      <TableCell sx={{ verticalAlign: "top" }}>
        {linked && row.link.device_id ? (
          <Box component="a" href={`?screen=inventory&device_id=${encodeURIComponent(row.link.device_id)}&tab=logs`}
            sx={{ color: m3.primary, fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{row.link.device_name ?? row.link.device_id}</Box>
        ) : (
          <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{row.link.device_name ?? row.label}</Box>
        )}
        <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar }}>
          {[row.kind, row.link.vendor ? vendorLabel(row.link.vendor) : null].filter(Boolean).join(" · ") || " "}
        </Typography>
        {!linked && (
          <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar, maxWidth: 280 }}>
            {row.link.reason ?? "device not linked"}
          </Typography>
        )}
      </TableCell>
      <TableCell sx={{ verticalAlign: "top" }}>
        <Typography variant="body2">{row.on_hop ? "On the finding's hop" : "On the path"}</Typography>
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{row.points.map((p) => p.label).join(", ")}</Typography>
      </TableCell>
      <TableCell sx={{ verticalAlign: "top", maxWidth: 460 }}>
        {row.events.length === 0 ? (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{row.event_count === 0 ? "None stored" : "—"}</Typography>
        ) : (
          <Box component="ul" aria-label="Events in window" sx={{ listStyle: "none", m: 0, p: 0, display: "grid", gap: 0.5 }}>
            {row.events.map((event, index) => (
              <Box component="li" key={index} data-kind={event.kind} sx={{ display: "flex", flexWrap: "wrap", gap: 0.75, alignItems: "baseline" }}>
                <StatusPill tone={event.kind === "GAP" ? "unknown" : "warn"} word={EVENT_KIND[event.kind] ?? event.kind} dense />
                {eventTime(event)}
                <Typography component="span" variant="body2" sx={{ overflowWrap: "anywhere" }}>{event.text}</Typography>
              </Box>
            ))}
          </Box>
        )}
      </TableCell>
      <TableCell sx={{ verticalAlign: "top" }}>
        {row.sources.length === 0 ? (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>—</Typography>
        ) : (
          <Box component="ul" aria-label="Lines per source" sx={{ listStyle: "none", m: 0, p: 0 }}>
            {row.sources.map((s) => (
              <Typography component="li" key={s.source_key} variant="caption" sx={{ display: "block", whiteSpace: "nowrap" }}
                title={s.last_outcome ? `last collection ${s.last_outcome}` : undefined}>
                {`${sourceLabel(s.source_key)} · ${s.chunks_overlapping} chunk${s.chunks_overlapping === 1 ? "" : "s"} · ${s.lines_in_overlapping_chunks} lines`}
              </Typography>
            ))}
          </Box>
        )}
      </TableCell>
      <TableCell sx={{ verticalAlign: "top" }}>
        <PillRow>
          <StatusPill tone={resultTone(row.result)} word={resultWord(row.result)} count={row.result === "EVENTS" ? row.event_count : null}
            title={row.result_reason ?? undefined} fact={`device-events:${row.result}`} />
        </PillRow>
        {/* An unlinked row's reason is already shown under the device; a linked row's reason is shown here. */}
        {row.result_reason && linked && (
          <Typography variant="caption" sx={{ display: "block", mt: 0.5, color: m3.onSurfaceVar, maxWidth: 240 }}>{row.result_reason}</Typography>
        )}
      </TableCell>
    </TableRow>
  );
}
