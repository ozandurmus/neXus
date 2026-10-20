import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { getChangeFeed, listDevices, type ChangeFeedCoverage, type ChangeFeedEvent, type ChangeFeedPage, type ChangeKind, type DeviceSummary } from "../auth/adminApi";
import { M3Button } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { PillRow, StatusPill } from "../shell/StatusPill";
import { Timestamp } from "../shell/Timestamp";
import { DISPLAY_TZ_LABEL, displayInputToIso, formatStamp, isoToDisplayInput } from "../shell/time";
import { jobTypeLabel, vendorLabel } from "../shell/presenters";
import { m3, MONO } from "../theme/m3Theme";
import { RADIUS, ROLE_COLORS, TYPE, type StateRole } from "../theme/tokens";

/**
 * Operations > Change feed (mockup "Cihaz logları için dört ekran" 2026-10-17, screen 3; PO 2026-10-20): one vertical
 * timeline across the estate, newest first -- user changes (who, device, what, when, with the stored diff), neXus's
 * own jobs in a neutral colour, and device events (process restarts, new crash/dump files). Filters: time range
 * (24 h, 7 d, custom in GMT+3), kinds, vendor, device. The feed orders and shows; it never says what caused what.
 *
 * Everything shown is what `GET /api/v2/changes/feed` returns from stored data: the server names each source and
 * its state in `coverage`, so an absent source reads as a note, never as "no changes". The aiview persona receives
 * the masked projection from the server; this panel renders names as they arrive and decides nothing by role.
 */

export type RangePreset = "24h" | "7d" | "custom";
export const KINDS: readonly { readonly id: ChangeKind; readonly label: string }[] = [
  { id: "USER", label: "User" },
  { id: "NEXUS_JOB", label: "neXus job" },
  { id: "DEVICE_EVENT", label: "Device event" },
];
const VENDORS = [["", "All vendors"], ["check_point", "Check Point"], ["palo_alto", "Palo Alto"]] as const;
/** After this long the loading line says the estate-wide read is still running (it is given up at the 20 s client timeout). */
export const SLOW_AFTER_MS = 5_000;

const SOURCE_LABELS: Readonly<Record<string, string>> = {
  policy_rule_history: "Policy rule history",
  pan_config: "Palo Alto config log",
  device_policy_install: "Policy installs",
  policy_change_evidence: "Policy change evidence",
  jobs: "neXus jobs",
  cp_cpwd_elg: "Check Point cpwd",
  device_crash_alert: "Crash and dump alerts",
};
export function sourceLabel(source: string): string {
  return SOURCE_LABELS[source] ?? source;
}

/** The mockup's palette mapped onto the role table: user = brand blue, neXus job = neutral slate, device event = its own state. */
export function kindTone(event: Pick<ChangeFeedEvent, "kind" | "tone">): StateRole {
  if (event.kind === "USER") return event.tone === "BAD" ? "bad" : "brand";
  if (event.kind === "NEXUS_JOB") return event.tone === "BAD" ? "bad" : event.tone === "WARN" ? "warn" : event.tone === "UNKNOWN" ? "unknown" : "neutral";
  return event.tone === "BAD" ? "bad" : event.tone === "WARN" ? "warn" : event.tone === "UNKNOWN" ? "unknown" : "neutral";
}
export function kindLabel(kind: ChangeKind): string {
  return KINDS.find((k) => k.id === kind)?.label ?? kind;
}
/** A source's coverage state as a pill tone: OK green, PARTIAL amber, UNAVAILABLE hatched unknown, the rest neutral. */
export function coverageTone(state: string): StateRole {
  switch (state) {
    case "OK": return "ok";
    case "PARTIAL": return "warn";
    case "UNAVAILABLE": return "unknown";
    default: return "neutral";
  }
}
export function coverageWord(state: string): string {
  switch (state) {
    case "OK": return "Read";
    case "PARTIAL": return "Partial";
    case "UNAVAILABLE": return "UNKNOWN";
    case "NOT_PERMITTED": return "Not permitted";
    case "OFF": return "Reader off";
    default: return state;
  }
}

/** The ISO instants a preset stands for, computed when the filters are applied (the server defaults to the last 24 h). */
export function rangeToQuery(preset: RangePreset, custom: { readonly from: string; readonly to: string }, now: Date = new Date()): { from?: string; to?: string } {
  if (preset === "24h") return { from: new Date(now.getTime() - 24 * 3600_000).toISOString(), to: now.toISOString() };
  if (preset === "7d") return { from: new Date(now.getTime() - 7 * 24 * 3600_000).toISOString(), to: now.toISOString() };
  return { from: custom.from || undefined, to: custom.to || undefined };
}

interface Filters { readonly preset: RangePreset; readonly from: string; readonly to: string; readonly kinds: readonly ChangeKind[]; readonly vendor: string; readonly device: string }
const initialFilters: Filters = { preset: "24h", from: "", to: "", kinds: KINDS.map((k) => k.id), vendor: "", device: "" };

export function ChangeFeedPanel({ initialDevice = "", initialVendor = "" }: { readonly initialDevice?: string; readonly initialVendor?: string } = {}) {
  const [draft, setDraft] = useState<Filters>({ ...initialFilters, device: initialDevice, vendor: initialVendor });
  const [filters, setFilters] = useState<Filters>(draft);
  const [range, setRange] = useState<{ from?: string; to?: string }>(() => rangeToQuery(draft.preset, draft));
  const [cursors, setCursors] = useState<string[]>([""]);
  const [data, setData] = useState<ChangeFeedPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [slow, setSlow] = useState(false);
  const [revision, setRevision] = useState(0);
  const [devices, setDevices] = useState<readonly DeviceSummary[]>([]);
  const cursor = cursors[cursors.length - 1];

  useEffect(() => {
    listDevices().then(({ devices: list }) => setDevices([...(list ?? [])].sort((a, b) => (a.hostname ?? a.device_id).localeCompare(b.hostname ?? b.device_id))))
      .catch(() => setDevices([]));
  }, []);

  useEffect(() => {
    let active = true;
    setData(null); setError(null); setSlow(false);
    const slowTimer = window.setTimeout(() => { if (active) setSlow(true); }, SLOW_AFTER_MS);
    getChangeFeed({ ...range, kinds: filters.kinds, vendor: filters.vendor, device: filters.device, cursor, limit: 50 })
      .then((page) => {
        if (!Array.isArray(page.events) || !Array.isArray(page.coverage)) throw new Error("Invalid change feed response");
        if (active) { setRange({ from: page.range_start, to: page.range_end }); setData(page); }
      })
      .catch((failure: unknown) => { if (active) setError(failure ?? new Error("Could not load the change feed")); })
      .finally(() => { window.clearTimeout(slowTimer); if (active) setSlow(false); });
    return () => { active = false; window.clearTimeout(slowTimer); };
    // `range` is set from the response so the pages of one read share one window; it must not retrigger the read.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters, cursor, revision]);

  const apply = () => {
    const kinds = draft.kinds.length === 0 ? KINDS.map((k) => k.id) : draft.kinds;
    const next = { ...draft, kinds };
    setDraft(next); setFilters(next); setRange(rangeToQuery(next.preset, next)); setCursors([""]);
  };
  const toggleKind = (kind: ChangeKind) => setDraft((d) => ({ ...d, kinds: d.kinds.includes(kind) ? d.kinds.filter((k) => k !== kind) : [...d.kinds, kind] }));

  const chip = (pressed: boolean, label: string, onClick: () => void) => (
    <Box component="button" type="button" aria-pressed={pressed} onClick={onClick}
      sx={{ font: "inherit", fontSize: TYPE.label.fontSize, fontWeight: 500, lineHeight: "20px", px: 1.25, py: 0.25, cursor: "pointer",
            borderRadius: `${RADIUS.pill}px`, border: `1px solid ${pressed ? m3.primary : m3.outlineVar}`,
            bgcolor: pressed ? m3.primaryContainer : m3.scLowest, color: pressed ? m3.onPrimaryContainer : m3.onSurface,
            "&:focus-visible": { outline: `2px solid ${m3.primary}`, outlineOffset: 2 } }}>
      {label}
    </Box>
  );

  const failed = error !== null;
  const timedOut = (error as { status?: number } | null)?.status === 0;
  const errorCode = (error as { body?: { code?: string; error?: string } } | null)?.body?.code ?? (error as { body?: { error?: string } } | null)?.body?.error ?? null;

  return (
    <Box role="region" aria-label="Change feed" data-testid="change-feed" sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 820 }}>
        Who changed what, when: user changes with their stored diff, neXus's own jobs in grey, and device events, newest
        first, from what neXus already stores. The feed orders and shows; it does not say what caused what.
      </Typography>

      <Box component="form" aria-label="Change feed filters" onSubmit={(event) => { event.preventDefault(); apply(); }}>
        <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
          <Box role="group" aria-label="Time range" sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
            {chip(draft.preset === "24h", "Last 24 h", () => setDraft((d) => ({ ...d, preset: "24h" })))}
            {chip(draft.preset === "7d", "7 d", () => setDraft((d) => ({ ...d, preset: "7d" })))}
            {chip(draft.preset === "custom", "Custom", () => setDraft((d) => ({ ...d, preset: "custom" })))}
          </Box>
          {draft.preset === "custom" && (["from", "to"] as const).map((field) => (
            <TextField key={field} size="small" type="datetime-local" InputLabelProps={{ shrink: true }}
              label={field === "from" ? `From (${DISPLAY_TZ_LABEL})` : `Until (${DISPLAY_TZ_LABEL}, exclusive)`}
              value={isoToDisplayInput(draft[field])} onChange={(e) => setDraft((d) => ({ ...d, [field]: displayInputToIso(e.target.value) ?? "" }))} />
          ))}
          <Box role="group" aria-label="Kinds" sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
            {KINDS.map((k) => <span key={k.id}>{chip(draft.kinds.includes(k.id), k.label, () => toggleKind(k.id))}</span>)}
          </Box>
          <TextField select size="small" label="Vendor" value={draft.vendor} sx={{ minWidth: 150 }} onChange={(e) => setDraft((d) => ({ ...d, vendor: e.target.value }))}>
            {VENDORS.map(([id, label]) => <MenuItem key={id || "all"} value={id}>{label}</MenuItem>)}
          </TextField>
          <TextField select size="small" label="Device" value={draft.device} sx={{ minWidth: 180 }} onChange={(e) => setDraft((d) => ({ ...d, device: e.target.value }))}>
            <MenuItem value="">All devices</MenuItem>
            {devices.map((d) => <MenuItem key={d.device_id} value={d.device_id}>{d.hostname ?? d.device_id}</MenuItem>)}
          </TextField>
          <M3Button emphasis="tonal" onClick={apply}>Apply feed filters</M3Button>
        </Stack>
      </Box>

      {data && (
        <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
          {formatStamp(data.range_start)} — {formatStamp(data.range_end)} · page {cursors.length}
          {data.masked ? " · masked view" : ""}
        </Typography>
      )}

      {failed && isRestricted(error) && <RestrictedPanel area="Change feed" role="Security Administrator" nextStep="Policy > Changes is gated the same way." />}
      {failed && !isRestricted(error) && (
        <StatePanel variant="error" announce="status" title={timedOut ? "Could not load the change feed · the read timed out after 20 s" : "Could not load the change feed"}
          code={errorCode} action={<M3Button emphasis="outlined" onClick={() => setRevision((n) => n + 1)}>Retry</M3Button>} />
      )}
      {!data && !failed && (
        <Typography role="status" variant="body2">{slow ? "Loading changes… the estate-wide read is still running (given up after 20 s)." : "Loading changes…"}</Typography>
      )}

      {data && <Coverage coverage={data.coverage} orderingNote={data.ordering_note} />}

      {data && data.events.length === 0 && (
        <StatePanel variant="empty" title="No stored change in this range"
          body="Nothing the covered sources stored falls in the range and filters. A source listed above as UNKNOWN, Partial or Not permitted may still hold changes." />
      )}

      {data && data.events.length > 0 && (
        <Box component="ol" aria-label="Change timeline" sx={{ listStyle: "none", m: 0, p: 0, display: "grid" }}>
          {data.events.map((event) => <TimelineItem key={event.key} event={event} />)}
        </Box>
      )}

      {data && (
        <Stack direction="row" alignItems="center" gap={1}>
          <M3Button emphasis="text" disabled={cursors.length === 1} onClick={() => setCursors((c) => c.slice(0, -1))}>Newer changes</M3Button>
          <M3Button emphasis="text" disabled={!data.next_cursor} onClick={() => setCursors((c) => [...c, data.next_cursor])}>Older changes</M3Button>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{data.events.length} on this page</Typography>
        </Stack>
      )}
    </Box>
  );
}

/** Each source and its state, as a row of pills with the note on hover; a live region so a change of coverage is announced. */
function Coverage({ coverage, orderingNote }: { readonly coverage: readonly ChangeFeedCoverage[]; readonly orderingNote: string }) {
  return (
    <Box role="status" aria-label="Coverage" sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
      <PillRow ariaLabel="Sources">
        {coverage.map((c) => (
          <StatusPill key={c.source} tone={coverageTone(c.state)} word={coverageWord(c.state)} suffix={sourceLabel(c.source)}
            count={c.state === "OK" || c.state === "PARTIAL" ? c.events : null} title={c.note} dense fact={`coverage:${c.source}`} />
        ))}
      </PillRow>
      <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{orderingNote}</Typography>
    </Box>
  );
}

function TimelineItem({ event }: { readonly event: ChangeFeedEvent }) {
  const tone = kindTone(event);
  const dot = ROLE_COLORS[tone];
  const deviceLink = event.links.device;
  const deviceWord = event.device_name ?? (event.device_id ? event.device_id : null);
  return (
    <Box component="li" data-kind={event.kind} data-source={event.source}
      sx={{ display: "grid", gridTemplateColumns: "212px 18px minmax(0, 1fr)", gap: 1.25, alignItems: "start" }}>
      <Box sx={{ pt: 0.25, textAlign: "right" }}>
        {event.time_basis === "DEVICE_LOCAL"
          ? <Box component="time" dateTime={event.time} title={`device clock; ordered as ${event.time} (Europe/Istanbul assumed)`}
              sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight, whiteSpace: "nowrap", display: "block" }}>
              {event.device_time}
              <Box component="span" sx={{ display: "block", fontFamily: "Roboto, sans-serif", fontSize: TYPE.label.fontSize, color: m3.onSurfaceVar }}>device clock</Box>
            </Box>
          : <Timestamp at={event.time} seconds stacked />}
      </Box>
      <Box aria-hidden sx={{ position: "relative", alignSelf: "stretch", minHeight: 40 }}>
        <Box sx={{ position: "absolute", left: 8, top: 0, bottom: 0, width: 2, bgcolor: m3.outlineVar }} />
        <Box data-dot={tone} sx={{ position: "absolute", left: 3, top: 8, width: 12, height: 12, borderRadius: "50%", bgcolor: tone === "unknown" ? m3.neutralInk : tone === "neutral" ? m3.onSurfaceVar : dot.fg, border: `2px solid ${m3.scLowest}` }} />
      </Box>
      <Box sx={{ pb: 1.75, minWidth: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
        <Typography variant="body2" sx={{ color: m3.onSurface, overflowWrap: "anywhere" }}>
          <Box component="span" sx={{ fontWeight: 600 }}>{event.actor ?? "UNKNOWN"}</Box>
          {deviceWord && <>
            {" · "}
            {deviceLink ? <Box component="a" href={deviceLink} sx={{ color: m3.primary, fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{deviceWord}</Box>
              : <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{deviceWord}</Box>}
          </>}
          {" · "}
          {event.source === "jobs" && event.links.job_type ? `${jobTypeLabel(event.links.job_type, event.vendor ?? undefined)} · ${event.what}` : event.what}
        </Typography>
        <PillRow>
          <StatusPill tone={tone} word={kindLabel(event.kind)} dense fact={`kind:${event.kind}`} />
          <StatusPill tone="neutral" word={sourceLabel(event.source)} dense />
          {event.vendor && <StatusPill tone="neutral" word={vendorLabel(event.vendor)} dense />}
          {event.links.policy_id && <StatusPill tone="brand" word="Policy history" dense href={`?screen=policy&policy_id=${encodeURIComponent(event.links.policy_id)}`} />}
          {event.links.job_id && <StatusPill tone="brand" word="Job" dense href={`?screen=operations&tab=jobs&job=${encodeURIComponent(event.links.job_id)}`} />}
          {!event.links.job_id && event.links.job_type && <StatusPill tone="brand" word="Jobs of this type" dense href={`?screen=operations&tab=jobs&job_type=${encodeURIComponent(event.links.job_type)}`} />}
        </PillRow>
        {event.detail && <Typography variant="caption" sx={{ color: m3.onSurfaceVar, overflowWrap: "anywhere" }}>{event.detail}</Typography>}
        {event.diff.length > 0 && <Diff lines={event.diff} />}
      </Box>
    </Box>
  );
}

/** The compact diff block: collapsed by default, mono, removed lines in the error ink and added lines in the success ink. */
function Diff({ lines }: { readonly lines: ChangeFeedEvent["diff"] }) {
  const added = lines.filter((l) => l.op === "+").length, removed = lines.filter((l) => l.op === "-").length;
  return (
    <Box component="details" sx={{ "& summary": { cursor: "pointer", fontSize: TYPE.label.fontSize, color: m3.onSurfaceVar } }}>
      <Box component="summary">{`Diff · ${added} added · ${removed} removed`}</Box>
      <Box component="pre" sx={{ m: 0, mt: 0.5, p: 1, fontFamily: MONO, fontSize: TYPE.mono.fontSize, lineHeight: TYPE.mono.lineHeight,
                                 bgcolor: m3.scLow, border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.pill}px`, overflowX: "auto", whiteSpace: "pre" }}>
        {lines.map((line, index) => (
          <Box key={index} component="span" data-op={line.op} sx={{ display: "block", color: line.op === "+" ? m3.onSuccessContainer : line.op === "-" ? m3.onErrorContainer : m3.onSurfaceVar }}>
            {`${line.op} ${line.text}`}
          </Box>
        ))}
      </Box>
    </Box>
  );
}
