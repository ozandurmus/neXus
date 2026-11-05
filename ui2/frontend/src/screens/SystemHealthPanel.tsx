import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Typography from "@mui/material/Typography";

import {
  getSystemHealth, requestSystemLogCollect, type ApiError, type HealthState, type SystemHealth, type SystemHealthDevice,
  type SystemLogCollectOutcome,
} from "../auth/adminApi";
import { DataTable, type DataColumn } from "../shell/DataTable";
import { KpiCard, KpiRow } from "../shell/KpiCard";
import { M3Button } from "../shell/M3Widgets";
import { PLANE_OPERATIONS, useRoleAllows } from "../shell/actionAccess";
import { StatePanel, isRestricted } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import { Timestamp } from "../shell/Timestamp";
import { vendorLabel } from "../shell/presenters";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { m3 } from "../theme/m3Theme";
import { TYPE, type StateRole } from "../theme/tokens";
import { describeSystemLogCollectError, explainSystemLogRefusalCode, type SystemLogCollectMessage } from "./DeviceLogsPanel";

/**
 * Operations › System health ("who in the fleet is struggling"; mockup screen 2 of 2026-10-17, realised with stored data
 * only -- docs/design/SYSTEM_HEALTH_SCREEN_2026_10_20.md). Everything here is the server's `/api/v2/system-logs/health`
 * body: the state word is the server's fixed rule (shown under the KPI row), the sparkline is the stored sar days of the
 * window, a figure that is not evidenced is written UNKNOWN (never 0), and a fact the vendor has no source for is written
 * "No source". A row opens Device › Logs at the row's newest day.
 */

/** How often the panel asks again while the server's first snapshot is still being computed. */
export const COMPUTING_POLL_MS = 5_000;
/** Maximum number of COMPUTING poll retries before stopping and showing a plain bounded-wait state (20 s total). */
export const MAX_COMPUTING_POLLS = 4;
/** Bounded wait for the initial read before showing a plain timeout state. */
export const LOAD_TIMEOUT_MS = 20_000;

const STATE_WORD: Record<HealthState, { readonly tone: StateRole; readonly word: string }> = {
  CRITICAL: { tone: "bad", word: "Critical" },
  ATTENTION: { tone: "warn", word: "Attention" },
  NORMAL: { tone: "ok", word: "Normal" },
  UNKNOWN: { tone: "unknown", word: "UNKNOWN" },
};

/** The sentence behind a reason code, for the pill's hover; the code itself stays beside it. */
export function reasonWords(code: string | null | undefined): string | null {
  if (!code) return null;
  if (code.startsWith("CPU_P95_OVER_THRESHOLD_") && code.endsWith("_IN_A_ROW")) return "CPU p95 above the threshold on consecutive sar days";
  if (code.startsWith("CPU_P95_OVER_THRESHOLD_")) return "CPU p95 above the threshold on one or two sar days";
  if (code.startsWith("NEW_CRASH_OR_DUMP_FILE_ALERT_")) return "a new crash or dump file was listed since monitoring began";
  if (code.startsWith("RESTARTS_")) return "three or more cpwd process restarts in one hour";
  switch (code) {
    case "NO_SAR_DAY_STORED": return "no sar text of the window is stored";
    case "SAR_TEXT_WITHOUT_CPU_SAMPLES": return "the stored sar text holds no CPU sample";
    case "SAR_TEXT_WITHOUT_MEMORY_SECTION": return "the stored sar text has no memory section";
    case "CPWD_NOT_COLLECTED": return "cpwd.elg has not been collected";
    case "CRASH_LISTING_NOT_RUN": return "the crash and dump listing has not run";
    case "SYSTEM_LOG_STORE_UNAVAILABLE": return "the service has no artefact store mounted";
    case "NO_APPLICABLE_FACT": return "this vendor has no CPU, restart or crash source";
    case "COLLECTION_OFF": return "collection is off for this device";
    case "SYSTEM_LOG_DISABLED_ON_MANAGEMENT": return "management servers are not collected (setting off)";
    case "NO_COLLECTION_RUN_RECORDED": return "no collection run has been recorded yet";
    default: return code.startsWith("CPWD_LAST_OUTCOME_") ? "the newest cpwd read did not end in data" : null;
  }
}

/** The device cell: the pseudonym the server gave, or the opaque id when there is no hostname. */
export function deviceName(d: SystemHealthDevice): string {
  return d.hostname ?? d.device_id;
}

/** The deep link a row opens: Device › Logs, with the row's newest day for the tab to use. */
export function logsHref(d: SystemHealthDevice): string {
  const q = new URLSearchParams({ screen: "inventory", device_id: d.device_id, tab: "logs" });
  if (d.logs_day) q.set("log_day", d.logs_day);
  return `?${q.toString()}`;
}

/** A one-line text of the sparkline for a screen reader and the cell's hover. */
export function sparklineWords(d: SystemHealthDevice, windowDays: number): string {
  const days = d.cpu.days;
  const values = days.map((x) => (x.p95_percent === null ? `${x.day} UNKNOWN` : `${x.day} ${x.p95_percent} %`));
  const over = days.filter((x) => x.p95_percent !== null && x.p95_percent > d.cpu.threshold_percent).length;
  return `CPU p95 over ${windowDays} days, ${days.length} stored: ${values.join(", ")}; threshold ${d.cpu.threshold_percent} %; ${over} of ${days.length} days over`;
}

/**
 * The inline sparkline: one point per stored sar day on a 7-day axis (a missing day is a gap), the threshold as a dashed
 * line, the newest point marked, the stroke in the row's state ink. Plain SVG, `role="img"` with the words above.
 */
export function Sparkline({ device, windowDays, today }: { readonly device: SystemHealthDevice; readonly windowDays: number; readonly today: string }) {
  const width = 120, height = 26, padX = 3, padY = 3;
  const t = device.cpu.threshold_percent;
  const dayIndex = (day: string) => {
    const ms = Date.parse(`${day}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`);
    return windowDays - 1 + Math.round(ms / 86_400_000); // today is the last slot
  };
  const x = (i: number) => padX + (Math.max(0, Math.min(windowDays - 1, i)) / Math.max(1, windowDays - 1)) * (width - 2 * padX);
  const y = (v: number) => padY + (1 - Math.max(0, Math.min(100, v)) / 100) * (height - 2 * padY);
  const points = device.cpu.days.filter((d) => d.p95_percent !== null).map((d) => ({ x: x(dayIndex(d.day)), y: y(d.p95_percent as number) }));
  const ink = device.state === "CRITICAL" ? m3.criticalInk : device.state === "ATTENTION" ? m3.warningInk : device.state === "NORMAL" ? m3.goodInk : m3.neutralInk;
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={sparklineWords(device, windowDays)} data-sparkline={device.device_id}
      style={{ display: "block", overflow: "visible" }}>
      <line x1={padX} x2={width - padX} y1={y(t)} y2={y(t)} stroke={m3.outlineVar} strokeWidth={1} strokeDasharray="3 3" />
      {points.length > 1 ? <polyline fill="none" stroke={ink} strokeWidth={1.6} strokeLinejoin="round" points={points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ")} /> : null}
      {points.map((p, i) => <circle key={i} cx={p.x} cy={p.y} r={i === points.length - 1 ? 2.5 : 1.3} fill={ink} />)}
    </svg>
  );
}

function percent(v: number | null): string {
  return v === null ? "UNKNOWN" : `${Math.round(v)} %`;
}

/** `UNKNOWN` hatched, `No source` neutral, else the value; one presentation for every fact cell. */
function FactValue({ level, value, title }: { readonly level: string; readonly value: string; readonly title?: string }) {
  if (level === "NOT_APPLICABLE") return <Box component="span" title={title ?? "this vendor has no source for this fact"} sx={{ color: m3.onSurfaceVar }}>No source</Box>;
  if (level === "UNKNOWN" || value === "UNKNOWN") return <StatusPill tone="unknown" word="UNKNOWN" dense title={title} />;
  return <Box component="span" title={title}>{value}</Box>;
}

export function describeHealthError(err: unknown): string {
  if (isRestricted(err)) return "restricted";
  const e = err as { status?: number; name?: string; body?: { code?: string; message?: string; reason?: string; error?: string } };
  if (e?.status === 0 || e?.name === "AbortError" || e?.body?.error === "TIMEOUT") {
    return "The system health read timed out after 20 s.";
  }
  const body = e?.body;
  return body?.message ?? body?.reason ?? body?.error ?? body?.code ?? "The system health read failed.";
}

export function describeFleetSystemLogCollectOutcome(outcome: Partial<SystemLogCollectOutcome>): SystemLogCollectMessage {
  const targets = outcome.targets ?? 0;
  const admitted = outcome.admitted ?? 0;
  const deduplicated = outcome.deduplicated ?? 0;
  const refused = outcome.refused ?? 0;
  const base = `${targets} supported ${targets === 1 ? "device" : "devices"}: ${admitted} admitted, ${deduplicated} already running (deduplicated), ${refused} refused.`;
  const codes = Array.isArray(outcome.refusal_codes) ? outcome.refusal_codes.filter((c): c is string => typeof c === "string") : [];
  const text = codes.length > 0 ? `${base} ${codes.map((c) => explainSystemLogRefusalCode(c)).join(" · ")}` : base;
  const tone: SystemLogCollectMessage["tone"] = admitted > 0 ? "ok" : deduplicated > 0 ? "warn" : "bad";
  return { tone, text };
}

export function SystemHealthPanel({ now = new Date() }: { readonly now?: Date }) {
  const { data, error, rawError, refresh } = useFetchOnMount<SystemHealth>(getSystemHealth, describeHealthError);
  const canCollect = useRoleAllows(PLANE_OPERATIONS);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [collecting, setCollecting] = useState(false);
  const [fleetMessage, setFleetMessage] = useState<SystemLogCollectMessage | null>(null);
  const [polls, setPolls] = useState(0);
  const [loadTimedOut, setLoadTimedOut] = useState(false);

  const serverComputing = data?.computation.state === "COMPUTING";
  const computationFailed = data?.computation.state === "FAILED";
  const apiReason = data?.computation.reason ?? data?.computation.error ?? null;
  const boundedWaitReached = serverComputing && (polls >= MAX_COMPUTING_POLLS || apiReason !== null);
  const computing = serverComputing && !boundedWaitReached;
  const notComputedYet = boundedWaitReached || computationFailed;

  useEffect(() => {
    if (data || error) {
      setLoadTimedOut(false);
      return;
    }
    const handle = window.setTimeout(() => setLoadTimedOut(true), LOAD_TIMEOUT_MS);
    return () => window.clearTimeout(handle);
  }, [data, error]);

  useEffect(() => {
    if (data && data.computation.state !== "COMPUTING") {
      setPolls(0);
    }
  }, [data]);

  useEffect(() => {
    if (!computing) return;
    const handle = window.setTimeout(() => {
      setPolls((n) => n + 1);
      refresh();
    }, COMPUTING_POLL_MS);
    return () => window.clearTimeout(handle);
  }, [computing, data, refresh]);

  const retry = () => {
    setPolls(0);
    setLoadTimedOut(false);
    refresh();
  };

  const handleFleetCollectConfirm = async () => {
    setCollecting(true);
    try {
      const outcome = await requestSystemLogCollect();
      setFleetMessage(describeFleetSystemLogCollectOutcome(outcome));
      refresh();
    } catch (err) {
      const e = err as Partial<ApiError> | null;
      const body = (e?.body ?? {}) as Record<string, unknown>;
      if (typeof body.targets === "number") {
        setFleetMessage(describeFleetSystemLogCollectOutcome(body as Partial<SystemLogCollectOutcome>));
      } else {
        setFleetMessage(describeSystemLogCollectError(err));
      }
    } finally {
      setCollecting(false);
    }
  };

  if (error && isRestricted(rawError)) {
    return <StatePanel variant="restricted" title="System health is not available for this role" announce="status"
      body="The fleet health view needs the system-log read permission (operator and above, aiview included)." />;
  }
  if (error) {
    const errCode = (rawError as { body?: { code?: string; error?: string } } | null)?.body?.code
      ?? (rawError as { body?: { error?: string } } | null)?.body?.error
      ?? "SYSTEM_HEALTH_READ_FAILED";
    return <StatePanel variant="error" title="System health could not be read" body={error} announce="status"
      code={errCode} action={<M3Button emphasis="outlined" onClick={retry}>Retry</M3Button>} />;
  }
  if (!data && loadTimedOut) {
    return <StatePanel variant="error" title="System health could not be read"
      body="Not computed yet (scheduled computation is off) · the read timed out after 20 s."
      announce="status" code="TIMEOUT" action={<M3Button emphasis="outlined" onClick={retry}>Retry</M3Button>} />;
  }
  if (!data) return <StatePanel variant="empty" title="Reading system health…" announce="status" />;

  const today = now.toISOString().slice(0, 10);
  const k = data.kpis;
  const supportedCount = k.monitored?.devices ?? data.devices.length;
  const threshold = data.cpu_p95_threshold_percent;
  const computedAt = data.computation.computed_at;
  const byVendor = k.monitored ? Object.entries(k.monitored.by_vendor).map(([v, n]) => `${vendorLabel(v)} ${n}`).join(" · ") : "";
  const byState = k.collection_not_ok ? Object.entries(k.collection_not_ok.by_state).map(([s, n]) => `${n} ${collectionWord(s).toLowerCase()}`).join(" · ") : "";
  const notComputedLabel = apiReason ?? "not computed yet (scheduled computation is off)";
  const asOf = computedAt
    ? `computed ${new Date(computedAt).toISOString().slice(11, 16)} UTC`
    : notComputedYet ? notComputedLabel : "not computed yet";
  const noFigure = computing
    ? { state: "not_evaluated" as const, reason: "the first computation is running" }
    : notComputedYet
      ? { state: (computationFailed ? "unknown" : "not_evaluated") as "unknown" | "not_evaluated", reason: notComputedLabel }
      : {};

  const columns: ReadonlyArray<DataColumn<SystemHealthDevice>> = [
    { key: "device", header: "Device", width: 220, mono: true, title: (d) => `${deviceName(d)} · ${vendorLabel(d.vendor_hint)}${d.cluster_member_ref ? ` · ${d.cluster_member_ref}` : ""}`,
      render: (d) => <Box component="a" href={logsHref(d)} onClick={(e) => e.stopPropagation()} sx={{ color: m3.primary, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}>{deviceName(d)}</Box> },
    { key: "vendor", header: "Vendor", width: 96, render: (d) => vendorLabel(d.vendor_hint) },
    { key: "state", header: "State", width: 128, title: (d) => `${d.state}${d.state_reason ? ` · ${d.state_reason}` : ""}`,
      render: (d) => <StatusPill tone={STATE_WORD[d.state].tone} word={STATE_WORD[d.state].word} dense fact="health-state"
        title={d.state_reason ? `${reasonWords(d.state_reason) ?? d.state_reason} (${d.state_reason})` : undefined} /> },
    { key: "cpu", header: `CPU p95 (${data.window_days} d)`, width: 200, title: (d) => sparklineWords(d, data.window_days),
      render: (d) => d.cpu.level === "NOT_APPLICABLE"
        ? <FactValue level="NOT_APPLICABLE" value="" />
        : <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            {d.cpu.days.length > 0 ? <Sparkline device={d} windowDays={data.window_days} today={today} /> : null}
            <FactValue level={d.cpu.level} value={percent(d.cpu.newest_p95_percent)} title={reasonWords(d.cpu.reason) ?? undefined} />
          </Box> },
    { key: "memory", header: "Memory used p95", width: 128, align: "right",
      render: (d) => <FactValue level={d.memory.level} value={percent(d.memory.newest_used_p95_percent)} title={reasonWords(d.memory.reason) ?? "sar %memused, newest stored day"} /> },
    { key: "restarts", header: "Restarts (7 d)", width: 120, align: "right",
      title: (d) => d.restarts.count === null ? (reasonWords(d.restarts.reason) ?? "UNKNOWN") : `${d.restarts.count} restart lines in ${data.window_days} d · at most ${d.restarts.max_in_one_hour} in one hour${d.restarts.busiest_hour ? ` (${d.restarts.busiest_hour})` : ""} · rule ${d.restarts.rule}`,
      render: (d) => d.restarts.count === null
        ? <FactValue level={d.restarts.level} value="UNKNOWN" title={reasonWords(d.restarts.reason) ?? undefined} />
        : d.restarts.level === "ATTENTION"
          ? <StatusPill tone="warn" word={`${d.restarts.max_in_one_hour} in 1 h`} count={d.restarts.count} dense />
          : <Box component="span">{d.restarts.count}</Box> },
    { key: "crash", header: "New crash/dump", width: 136,
      render: (d) => d.crash.level === "NOT_APPLICABLE" ? <FactValue level="NOT_APPLICABLE" value="" />
        : d.crash.level === "UNKNOWN" ? <StatusPill tone="unknown" word="UNKNOWN" dense title={reasonWords(d.crash.reason) ?? undefined} />
        : d.crash.new_alerts && d.crash.new_alerts > 0
          ? <StatusPill tone="bad" word="New" count={d.crash.new_alerts} age={d.crash.newest_alert_at} dense title="new crash or dump file alerts in the window" />
          : <StatusPill tone="neutral" word="None" dense title="no new crash or dump file since monitoring began" /> },
    { key: "collection", header: "Last collection", width: 200,
      title: (d) => `${collectionWord(d.collection.state)}${d.collection.reason ? ` · ${d.collection.reason}` : ""}${d.collection.last_job_at ? ` · ${d.collection.last_job_at}` : ""}`,
      render: (d) => <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        {d.collection.state === "OK" ? <Timestamp at={d.collection.last_job_at} compact />
          : <StatusPill tone={collectionTone(d.collection.state)} word={collectionWord(d.collection.state)} dense fact="collection-state"
              title={reasonWords(d.collection.reason) ?? d.collection.reason ?? undefined} age={d.collection.last_job_at} />}
      </Box> },
  ];

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {computing ? <StatePanel variant="not_evaluated" title="First computation running" announce="status"
        body="The server is reading the stored sar texts and watchdog logs of every monitored device for the first time since it started; this panel asks again every few seconds." /> : null}
      {notComputedYet ? <StatePanel variant={computationFailed ? "error" : "not_evaluated"}
        title={computationFailed ? "System health computation failed" : "Not computed yet (scheduled computation is off)"}
        announce="status"
        body={apiReason ?? "The first computation did not finish within the bounded wait: not computed yet (scheduled computation is off)."}
        code={apiReason ?? undefined}
        action={<M3Button emphasis="outlined" onClick={retry}>Retry</M3Button>} /> : null}
      {data.computation.state === "REFRESHING" ? <Typography role="status" sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>Snapshot older than {Math.round(data.computation.snapshot_ttl_seconds / 60)} min; a refresh is running in the background.</Typography> : null}
      {canCollect && (
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1.5, flexWrap: "wrap" }}>
          <M3Button
            deviceJob
            plane={PLANE_OPERATIONS}
            emphasis="tonal"
            icon="download"
            disabled={collecting}
            onClick={() => {
              setFleetMessage(null);
              setConfirmOpen(true);
            }}
          >
            Collect logs (all supported devices)
          </M3Button>
          {fleetMessage && (
            <Typography
              variant="body2"
              data-testid="fleet-system-log-collect-outcome"
              color={fleetMessage.tone === "bad" ? "error" : fleetMessage.tone === "warn" ? "warning.main" : "success.main"}
            >
              {fleetMessage.text}
            </Typography>
          )}
        </Box>
      )}
      <Dialog open={confirmOpen} onClose={() => { if (!collecting) setConfirmOpen(false); }}>
        <DialogTitle>Collect logs (all supported devices)</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="body2">
            Start system-log collection across all {supportedCount} supported {supportedCount === 1 ? "device" : "devices"}?
          </Typography>
          {fleetMessage && (
            <Typography
              variant="body2"
              data-testid="fleet-system-log-collect-dialog-outcome"
              color={fleetMessage.tone === "bad" ? "error" : fleetMessage.tone === "warn" ? "warning.main" : "success.main"}
            >
              {fleetMessage.text}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <M3Button emphasis="text" disabled={collecting} onClick={() => setConfirmOpen(false)}>
            {fleetMessage ? "Close" : "Cancel"}
          </M3Button>
          {!fleetMessage && (
            <M3Button
              deviceJob
              plane={PLANE_OPERATIONS}
              emphasis="filled"
              disabled={collecting}
              onClick={handleFleetCollectConfirm}
            >
              Confirm
            </M3Button>
          )}
        </DialogActions>
      </Dialog>
      <KpiRow ariaLabel="System health summary">
        <KpiCard label="Monitored devices" value={k.monitored?.devices ?? null} {...noFigure}
          detail={k.monitored ? byVendor : undefined}
          sentence={k.monitored ? `gateways the collection covers · ${k.monitored.not_collected_listed} listed with collection off · ${asOf}` : asOf} />
        <KpiCard label={`Crash/dump alerts (${data.window_days} d)`} value={k.crash_alerts?.devices ?? null} unit={k.crash_alerts ? "devices" : undefined} {...noFigure}
          detail={k.crash_alerts ? `${k.crash_alerts.alerts} alerts${k.crash_alerts.truncated ? " · list truncated" : ""}` : undefined}
          badge={k.crash_alerts && k.crash_alerts.devices > 0 ? <StatusPill tone="bad" word="Review" dense /> : undefined}
          sentence="files present before monitoring never alarm" />
        <KpiCard label={`CPU p95 above ${threshold} %`} value={k.cpu_over_threshold?.devices ?? null} unit={k.cpu_over_threshold ? "devices" : undefined} {...noFigure}
          detail={k.cpu_over_threshold ? `of ${k.cpu_over_threshold.evidenced} evidenced · ${k.cpu_over_threshold.unknown} UNKNOWN · ${k.cpu_over_threshold.not_applicable} no source` : undefined}
          sentence="newest stored sar day (yesterday's file, fetched in the 06:00 window)" />
        <KpiCard label="Collection not OK" value={k.collection_not_ok?.devices ?? null} unit={k.collection_not_ok ? "devices" : undefined} {...noFigure}
          detail={k.collection_not_ok ? (byState || "every listed device collected") : undefined}
          sentence="newest run per source: failed, unsupported, off, or never run" />
      </KpiRow>
      <Typography component="p" data-testid="health-rules" sx={{ ...TYPE.label, fontWeight: 400, color: m3.onSurfaceVar, m: 0, maxWidth: 1180 }}>
        State word, worst fact wins ({data.rules.state_order.join(" › ")}). Critical: {data.rules.critical.join("; ")}. Attention: {data.rules.attention.join("; ")}.
        {" "}Normal: {data.rules.normal}. UNKNOWN: {data.rules.unknown}. p95: {data.rules.p95}. Restart rule {data.rules.restart_rule.id} ({data.rules.restart_rule.status}): {data.rules.restart_rule.text}.
      </Typography>
      <DataTable ariaLabel="System health devices" columns={columns} rows={data.devices} rowKey={(d) => d.device_id} maxHeight="calc(100vh - 420px)"
        emptyText={computing ? "No row until the first computation finishes." : notComputedYet ? `Not computed yet (${apiReason ?? "scheduled computation is off"}).` : "No Check Point or Palo Alto gateway is enrolled for system-log collection."}
        onRowClick={(d) => window.location.assign(logsHref(d))} testId="system-health-table" />
    </Box>
  );
}

function collectionWord(state: string): string {
  switch (state) {
    case "OK": return "Collected";
    case "FAILED": return "Collection failed";
    case "UNSUPPORTED": return "Not supported";
    case "OFF": return "Collection off";
    case "NEVER": return "Not collected yet";
    default: return state;
  }
}

function collectionTone(state: string): StateRole {
  switch (state) {
    case "FAILED": return "bad";
    case "UNSUPPORTED": return "neutral";
    case "OFF": return "neutral";
    case "NEVER": return "unknown";
    default: return "ok";
  }
}

export { STATE_WORD };
