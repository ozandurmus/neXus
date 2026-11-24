import { SshHostKeyPanel } from "./SshHostKeyPanel";
import { canRunReadiness, haReadinessItems, HaReadinessList, type ReadinessError, type ReadinessProgress } from "./HaReadinessList";
import { CpFailoverPanel } from "./CpFailoverPanel";
import { FailoverIncidentsPanel } from "./FailoverIncidentsPanel";
import { useState, useEffect, useRef, useCallback } from "react";
import Card from "@mui/material/Card";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import { Icon } from "../shell/Icon";

import { ScreenHeader, MetricGrid, MetricCard, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, M3Tabs } from "../shell/M3Widgets";
import { StatePanel } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { JobLogsPanel, type JobPill } from "./JobLogsPanel";
import { PolicyInstallMonitoringPanel } from "./PolicyInstallMonitoringPanel";
import { StaticRoutePreviewPanel } from "./StaticRoutePreviewPanel";
import { ScriptExecutionPanel } from "./ScriptExecutionPanel";
import { SystemHealthPanel } from "./SystemHealthPanel";
import { urlParam, useUrlParamSync, writeUrlParams } from "../shell/urlParams";
import { useJobWindow } from "../shell/JobWindow";
import { getJobStats, getCpFailoverRun, listDevices, listCpFailoverSummary, listFailoverIncidents, newBusinessHoursAckScope, runCpFailoverReadiness, type BusinessHoursAckScope, type CpFailoverSummary, type DeviceSummary, type FailoverIncidents } from "../auth/adminApi";
import { deriveClusterTitle } from "./InventoryPanels";
import { readinessReasonLabel, requestFailure } from "../shell/presenters";
import Button from "@mui/material/Button";

/**
 * The three Operations tabs; Queue and History (roadmap 2.4) are state pills on Jobs, their old routes still land there.
 * Device health (PO 2026-10-20, mockup screen 2; named System health until 2026-11-13) is a tab here rather than a rail entry: it is fleet-level operational
 * triage over the same OPERATE question as HA readiness and Jobs, and it adds no screen id of its own. Diagnostics moved to
 * Tools and the Change feed to the Changes screen (UI review 2026-11-05, C3/C4); their old `tab=` addresses are rewritten
 * to those screens before this one reads them (`legacyRoutes`). Scheduled jobs and Platform status were tabs here for part
 * of 2026-11-13; the PO's amendment of that day made them Administration pages, so Operations is the fleet's live activity
 * only, and their `tab=` addresses are rewritten to those pages the same way.
 */
const TAB_SLUGS = ["ha", "jobs", "health", "policy-install", "routes", "script-execution"] as const;
/**
 * `?tab=<slug>` to a tab index and, for the retired Queue / History routes, the pill that stands for them. A job link
 * that names only `?job=<id>` (roadmap 3.6) lands on Jobs, where the id is opened; the tab slug wins when both are given.
 */
export function tabFromUrl(slug: string | null, jobId: string | null = null): { readonly tab: number; readonly pill: JobPill | null } {
  switch (slug) {
    case "jobs": return { tab: 1, pill: null };
    case "queue": return { tab: 1, pill: "active" };
    case "history": return { tab: 1, pill: "finished" };
    case "health": return { tab: 2, pill: null };
    case "policy-install": return { tab: 3, pill: null };
    case "routes": return { tab: 4, pill: null };
    case "script-execution": return { tab: 5, pill: null };
    case "ha": return { tab: 0, pill: null };
    default: return jobId ? { tab: 1, pill: null } : { tab: 0, pill: null };
  }
}

/**
 * The one-line headline under the title (UI review 2026-10-18 §4.3): number · noun · denominator · age, from what the
 * screen already loads. A part whose read failed is written UNKNOWN, never 0; a part not yet read is left out.
 */
export function operationsHeadline(input: {
  readonly stats: { readonly failed24h: number; readonly total24h: number } | null | "failed";
  readonly clusters: number | null;
  readonly ready: number;
  readonly unknown: number;
  readonly window: { readonly open: boolean; readonly label: string | null };
}): string {
  const parts: string[] = [];
  if (input.stats === "failed") parts.push("failed jobs UNKNOWN", "finished jobs UNKNOWN");
  else if (input.stats) parts.push(`${input.stats.failed24h} failed`, `${input.stats.total24h} finished (24 h)`);
  if (input.clusters !== null) parts.push(`${input.ready} of ${input.clusters} clusters ready`, `${input.unknown} unknown`);
  parts.push(input.window.open ? "job window open now" : (input.window.label ?? "job window UNKNOWN").replace(/^Next window: /, "next window "));
  return parts.join(" · ");
}

/** Operations screen for observed HA state, on-demand readiness and jobs. */
export function OperationsScreen() {
  // Counted from /api/v2/jobs (the same reads the Jobs screen makes); a failed read says so, never 0.
  const [jobStats, setJobStats] = useState<{ total24h: number; completed24h: number; failed24h: number; otherTerminal24h: number; otherLabel: string; running: number } | null>(null);
  const [jobStatsFailed, setJobStatsFailed] = useState(false);
  useEffect(() => {
    getJobStats()
      .then((s) => {
        const others: Array<[string, number]> = [["outcome unknown", s.outcome_unknown_24h ?? 0], ["rejected", s.rejected_24h ?? 0], ["cancelled", s.cancelled_24h ?? 0]];
        setJobStats({ total24h: s.total_24h, completed24h: s.completed_24h, failed24h: s.failed_24h,
          otherTerminal24h: others.reduce((n, [, c]) => n + c, 0),
          otherLabel: others.filter(([, c]) => c > 0).map(([w, c]) => `${c} ${w}`).join(" · "), running: s.running });
        setJobStatsFailed(false);
      })
      .catch(() => { setJobStats(null); setJobStatsFailed(true); });
  }, []);
  const jobWindow = useJobWindow();

  const [selectedCluster, setSelectedCluster] = useState<string | null>(null);
  // The enrolled clusters, from the device list (cluster reference -> members). Replaces two hardcoded
  // demo names and their demo checks (2026-09-22): nothing here is shown that the store did not return.
  const [baseClusters, setClusters] = useState<ReadonlyArray<{ ref: string; title: string; members: DeviceSummary[] }> | null>(null);
  const [fgtDevices, setFgtDevices] = useState<DeviceSummary[]>([]);
  useEffect(() => {
    listDevices()
      .then(({ devices }) => {
        const byRef = new Map<string, DeviceSummary[]>();
        setFgtDevices((devices ?? []).filter(d => d.vendor_hint === "fortinet" && !d.cluster_member_ref && d.ha_peer_unconfirmed === true));
        for (const d of devices ?? []) {
          if (d.cluster_member_ref) byRef.set(d.cluster_member_ref, [...(byRef.get(d.cluster_member_ref) ?? []), d]);
        }
        setClusters([...byRef.entries()].map(([ref, members]) => ({ ref, title: deriveClusterTitle(ref, members), members }))
          .sort((a, b) => a.title.localeCompare(b.title)));
      })
      .catch(() => setClusters([]));
  }, []);
  const [cpRows, setCpRows] = useState<Record<string, CpFailoverSummary[]>>({});
  // An enrolled single-address FortiGate cluster has no cluster reference of its own; the server names its readiness unit
  // `fgt-single|<device id>` and lists it only while its readiness switch is on, so it is shown only when a row came back.
  const clusters = baseClusters === null ? null : [...baseClusters, ...fgtDevices
    .filter(d => (cpRows[`fgt-single|${d.device_id}`] ?? []).length > 0)
    .map(d => ({ ref: `fgt-single|${d.device_id}`, title: deriveClusterTitle(`fgt-single|${d.device_id}`, [d]), members: [d] }))];
  const selected = clusters?.find((c) => c.ref === selectedCluster) ?? null;
  // A failed summary read is said so and every status becomes UNKNOWN: "could not be read" is never shown as "not ready".
  const [summaryFailure, setSummaryFailure] = useState<string | null>(null);
  // Whether the summary has answered (or failed) once: the list is drawn only after that and the incidents read have settled.
  const [summaryRead, setSummaryRead] = useState(false);
  const loadSummary = useCallback((alive: () => boolean = () => true) => {
    void listCpFailoverSummary().then(rows => {
      if (!alive()) return;
      const byRef: Record<string, CpFailoverSummary[]> = {};
      for (const row of rows) (byRef[row.cluster_member_ref] ??= []).push(row);
      setCpRows(byRef); setSummaryFailure(null); setSummaryRead(true);
    }).catch(error => { if (alive()) { setSummaryFailure(requestFailure(error as Parameters<typeof requestFailure>[0])); setSummaryRead(true); } });
  }, []);
  useEffect(() => {
    let mounted = true;
    loadSummary(() => mounted);
    return () => { mounted = false; };
  }, [loadSummary]);
  // The incidents are read once here; the list, the top panel and the detail all use this one read.
  const [incidents, setIncidents] = useState<FailoverIncidents | null>(null);
  // A failed read is not an empty list: with the incidents unknown, no row is shown Ready and no failover is offered.
  const [incidentsFailed, setIncidentsFailed] = useState(false);
  const loadIncidents = useCallback((alive: () => boolean = () => true) => {
    void (async () => listFailoverIncidents())().then(value => {
      if (alive()) setIncidents({ canRelease: value?.canRelease === true, roleNote: value?.roleNote ?? null,
        incidents: Array.isArray(value?.incidents) ? value.incidents : [] });
      if (alive()) setIncidentsFailed(false);
    }).catch(() => { if (alive()) { setIncidents(null); setIncidentsFailed(true); } });
  }, []);
  useEffect(() => {
    let mounted = true;
    loadIncidents(() => mounted);
    return () => { mounted = false; };
  }, [loadIncidents]);
  const sharedIncidents = { data: incidents, failed: incidentsFailed, reload: loadIncidents };
  const [isRunning, setIsRunning] = useState<string | null>(null);
  const [expandedUnit, setExpandedUnit] = useState<string | null>(null);
  const [readinessError, setReadinessError] = useState<ReadinessError | null>(null);
  const [bulkRunning, setBulkRunning] = useState(false);
  const [readinessProgress, setReadinessProgress] = useState<ReadinessProgress>({});
  const runLock = useRef(false);
  const batchLock = useRef(false);
  const readinessItems = haReadinessItems(clusters ?? [], cpRows);
  const readinessCounts = { ready: 0, notReady: 0, unknown: 0, unsupported: 0 };
  for (const item of readinessItems) {
    if (item.status === "READY") readinessCounts.ready++;
    else if (item.status === "NOT_READY") readinessCounts.notReady++;
    else if (item.status === "UNSUPPORTED") readinessCounts.unsupported++;
    else readinessCounts.unknown++;
  }
  const evaluatedCount = Object.values(cpRows).flat().filter((row) => row.readiness).length;

  const selectedReadinessRows = selectedCluster ? cpRows[selectedCluster] ?? [] : [];
  const selectedReadinessRow = selectedReadinessRows.find(row => row.unitId === expandedUnit)
    ?? selectedReadinessRows.find(row => row.unitId === row.clusterId);

  const [initialRoute] = useState(() => tabFromUrl(urlParam("tab"), urlParam("job")));
  const [tab, setTabState] = useState(initialRoute.tab);
  // A pill the screen asks of the Jobs table: the retired Queue / History routes on load, the Failed card's link later.
  const [pillRequest, setPillRequest] = useState<{ pill: JobPill; seq: number } | null>(initialRoute.pill ? { pill: initialRoute.pill, seq: 0 } : null);
  useUrlParamSync("tab", (nextTab) => {
    const nextRoute = tabFromUrl(nextTab, urlParam("job"));
    setTabState(nextRoute.tab);
    if (nextRoute.pill) {
      setPillRequest((current) => ({ pill: nextRoute.pill!, seq: (current?.seq ?? 0) + 1 }));
    }
  });
  // The tab is part of the address (a job link needs `tab=jobs` beside `job=<id>`); replaced, never pushed.
  const setTab = (next: number) => { setTabState(next); writeUrlParams({ tab: next === 0 ? null : TAB_SLUGS[next] }); };
  const openJobs = (pill: JobPill) => { setTab(1); setPillRequest((current) => ({ pill, seq: (current?.seq ?? 0) + 1 })); };

  // A cluster header elsewhere in the product links here as ?screen=operations&cluster_ref=<ref>; preselect it.
  useEffect(() => {
    if (!clusters || clusters.length === 0 || selectedCluster) return;
    const ref = urlParam("cluster_ref");
    if (ref && clusters.some((c) => c.ref === ref)) {
      setSelectedCluster(ref);
      setTab(0);
    }
  }, [clusters]);

  // `scope`: passed only by the bulk run, so that one bulk action asks the business-hours question once (P1-2).
  const handleRunReadiness = async (row: CpFailoverSummary, scope?: BusinessHoursAckScope): Promise<boolean> => {
    if (!canRunReadiness(row) || runLock.current) return false;
    runLock.current = true;
    setIsRunning(row.unitId);
    setReadinessError(null);
    try {
      const { runId } = await runCpFailoverReadiness(row, scope);
      let state = "PLANNED";
      for (let attempt = 0; attempt < 40 && !["DONE", "STOPPED"].includes(state); attempt++) {
        if (attempt > 0) await new Promise((resolve) => window.setTimeout(resolve, 3000));
        state = (await getCpFailoverRun(runId, row.vendor)).state;
      }
      if (!["DONE", "STOPPED"].includes(state)) throw new Error("Readiness run is still in progress.");
      const rows = await listCpFailoverSummary();
      const byRef: Record<string, CpFailoverSummary[]> = {};
      for (const item of rows) (byRef[item.cluster_member_ref] ??= []).push(item);
      setCpRows(byRef);
      return state === "DONE";
    } catch (error) {
      const body = (error as { body?: { code?: string; message?: string } })?.body;
      // Declining the business-hours question is a decision, not a failure: the row says the pre-checks were not started.
      setReadinessError({ unitId: row.unitId, cancelled: body?.code === "BUSINESS_HOURS_CONFIRM_REQUIRED",
        text: body?.code === "OUTSIDE_JOB_WINDOW" || body?.code === "BUSINESS_HOURS_CONFIRM_REQUIRED"
          ? body.message ?? "Device jobs are outside the scheduled window."
          : "Could not start or refresh the readiness run." });
      return false;
    } finally {
      runLock.current = false;
      setIsRunning(null);
    }
  };

  const handleBulkReadiness = async (units: CpFailoverSummary[]) => {
    if (batchLock.current || runLock.current) return;
    const allowed = units.filter(canRunReadiness);
    batchLock.current = true;
    setBulkRunning(true);
    setReadinessProgress(Object.fromEntries(allowed.map(row => [row.unitId, "Queued"])));
    // One confirmation for this bulk action's own requests, never for any other action.
    const scope = newBusinessHoursAckScope();
    try {
      for (let index = 0; index < allowed.length; index++) {
        const row = allowed[index];
        setReadinessProgress(current => ({ ...current, [row.unitId]: "Running" }));
        const completed = await handleRunReadiness(row, scope);
        setReadinessProgress(current => ({ ...current, [row.unitId]: completed ? "Completed" : "Failed" }));
        if (!completed) {
          // A timeout may leave a run active; do not submit another unit with uncertain completion.
          setReadinessProgress(current => ({ ...current, ...Object.fromEntries(allowed.slice(index + 1).map(unit => [unit.unitId, "Not run"])) }));
          break;
        }
      }
    } finally {
      batchLock.current = false;
      setBulkRunning(false);
    }
  };

  const reloadSummary = () => { loadSummary(); };
  // UI review 2026-11-05, B3: the job figures belong to the Jobs tab, the readiness figure to HA and failover.
  const jobKpis = (
    <MetricGrid>
      {/* One window with the Overview: jobs FINISHED in 24 h, every terminal state counted (review 2026-09-23). */}
      <MetricCard title="Jobs finished (24 h)" value={jobStats ? jobStats.total24h : null}
        note={jobStats
          ? `${jobStats.completed24h} completed · ${jobStats.failed24h} failed${jobStats.otherLabel ? ` · ${jobStats.otherLabel}` : ""} · ${jobStats.running} in flight`
          : "read failed"} />
      <MetricCard
        title="Success rate (24 h)"
        value={jobStats && jobStats.completed24h + jobStats.failed24h > 0 ? `${Math.round((100 * jobStats.completed24h) / (jobStats.completed24h + jobStats.failed24h))}%` : null}
        note={jobStats
          ? jobStats.completed24h + jobStats.failed24h === 0
            ? "no finished run in 24 h"
            : `completed of ${jobStats.completed24h + jobStats.failed24h} completed or failed${jobStats.otherTerminal24h ? `; ${jobStats.otherTerminal24h} other outcome${jobStats.otherTerminal24h === 1 ? "" : "s"} not counted` : ""}`
          : "read failed"}
      />
      <Card sx={{ bgcolor: m3.scLowest, boxShadow: "none", border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2.25,
                  display: "flex", flexDirection: "column", gap: 1 }}>
        <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar }}>Failed (24 h)</Typography>
        <Typography variant="h1">{jobStats ? jobStats.failed24h : "—"}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{jobStats ? (jobStats.failed24h === 0 ? "none" : `${jobStats.failed24h} failed jobs`) : "read failed"}</Typography>
        {/* The card's one link opens the Jobs table on its Failed pill (review §5: the team lead's shift triage). */}
        <Box component="button" onClick={() => openJobs("failed")}
          sx={{ all: "unset", cursor: "pointer", color: m3.primary, fontSize: 13, fontWeight: 500 }}>
          Open failed jobs
        </Box>
      </Card>
    </MetricGrid>
  );
  const readinessKpi = (
    <MetricGrid>
      <MetricCard
        title="Readiness checks"
        value={clusters ? readinessCounts.ready : null}
        unit={clusters ? `ready of ${clusters.length} clusters` : undefined}
        detail={clusters ? `${readinessCounts.notReady} not ready · ${readinessCounts.unknown} unknown` : undefined}
        note={clusters ? `${clusters.length} clusters enrolled · ${evaluatedCount} readiness records · ${readinessCounts.unsupported} not supported` : "reading enrolled clusters"}
      />
    </MetricGrid>
  );

  const renderHaPanel = () => <>
    {readinessKpi}
    {/* PO 2026-10-20 item 1: every open incident and estate-wide unresolved send, with its release (security_admin). */}
    <FailoverIncidentsPanel onReleased={reloadSummary} shared={sharedIncidents} />
    {summaryFailure && <Box role="status" data-readiness-summary-failed sx={{ mb: 1.5, px: 1.5, py: 1, borderRadius: 1, bgcolor: m3.warningContainer, color: m3.onWarningContainer, fontSize: 14 }}>
      Readiness could not be read ({summaryFailure}). Statuses below are unknown, not bad.
      <Button size="small" onClick={reloadSummary} sx={{ ml: 1.5, textTransform: "none" }}>Retry</Button></Box>}
    {clusters === null ? <StatePanel variant="empty" title="Reading the enrolled clusters…" />
      : clusters.length === 0 ? <StatePanel variant="empty" title="No HA pair or cluster enrolled"
        body="Readiness needs enrolled cluster members and a current health read from each one; none is enrolled, so no cluster can be assessed." />
      : <HaReadinessList clusters={clusters} rows={cpRows} running={isRunning} busy={bulkRunning || isRunning !== null}
        progress={readinessProgress} error={readinessError} incidents={incidents?.incidents ?? []} summaryFailed={summaryFailure !== null} incidentsFailed={incidentsFailed}
        settled={summaryRead && (incidents !== null || incidentsFailed)}
        onOpen={(ref, unitId) => { setSelectedCluster(ref); setExpandedUnit(unitId); }}
        onFailover={(ref, unitId) => { setSelectedCluster(ref); setExpandedUnit(unitId); }}
        onRun={row => { if (!batchLock.current) void handleRunReadiness(row); }}
        onBulkRun={units => { void handleBulkReadiness(units); }} onChanged={reloadSummary} />}
    <Drawer anchor="right" open={selectedCluster !== null} onClose={() => setSelectedCluster(null)}
      PaperProps={{ role: "dialog", "aria-modal": true, "aria-label": "HA readiness detail", sx: { width: "min(100vw, 1000px)", p: 2, bgcolor: m3.scLowest } }}>
      {selectedCluster && <>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <IconButton aria-label="Close detail" onClick={() => setSelectedCluster(null)}><Icon name="close" size={20} /></IconButton></Box>
        {selected?.members[0] && (["check_point", "palo_alto"].includes(selected.members[0].vendor_hint)
          || (["cisco_asa", "fortinet"].includes(selected.members[0].vendor_hint) && selectedReadinessRow?.readinessAvailable !== false)) ?
          <CpFailoverPanel key={selectedCluster} memberDeviceId={selected.members[0].device_id}
            vendor={selected.members[0].vendor_hint as "check_point" | "palo_alto" | "cisco_asa" | "fortinet"} initialUnitId={expandedUnit}
            summaryRows={selectedReadinessRows} incidents={sharedIncidents} />
          : <StatePanel variant="empty" title={readinessReasonLabel("UNSUPPORTED_MODE")} body={selected?.members[0]?.vendor_hint === "fortinet" && selectedReadinessRow?.readinessAvailable ? "FortiGate clusters are evaluated for readiness only, from the enrolled address (as the primary reports); failover is not offered." : selected?.members[0]?.vendor_hint === "cisco_asa" ? (selectedReadinessRow?.readinessAvailable ? "Cisco ASA pairs are evaluated for readiness only; failover is not offered." : "Readiness is not available yet for Cisco ASA pairs.") : "Failover and readiness detail are offered for Check Point and Palo Alto clusters only."} code="UNSUPPORTED_MODE" />}
      </>}
    </Drawer>
  </>;

  return (
    <ScreenRoot screenType="list">
      <SshHostKeyPanel />
      <ScreenHeader
        title="Operations"
        subtitle={operationsHeadline({ stats: jobStatsFailed ? "failed" : jobStats, clusters: clusters ? clusters.length : null,
          ready: readinessCounts.ready, unknown: readinessCounts.unknown, window: jobWindow })}
        actions={<M3Button emphasis="outlined" href="?screen=administration&tab=scheduled-jobs">Collection schedules</M3Button>}
      />
      <M3Tabs
        ariaLabel="Operations sections"
        value={tab}
        onChange={setTab}
        tabs={[
          {
            label: "HA and failover",
            panel: renderHaPanel(),
          },
          { label: "Jobs", panel: <Box>{jobKpis}<Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>All jobs</Typography><JobLogsPanel
            initialState={urlParam("state") ?? ""} initialJobType={urlParam("job_type") ?? ""}
            initialSinceHours={urlParam("since_hours") ? Number(urlParam("since_hours")) : undefined} initialText={urlParam("q") ?? ""}
            pillRequest={pillRequest} /></Box> },
          // The firewalls' own health: stored sar and watchdog facts per gateway (`tab=health`, the address it always had).
          // neXus' own health is Administration > Platform status and the indicator in the top bar (PO 2026-11-13).
          { label: "Device health", panel: <SystemHealthPanel /> },
          { label: "Policy installation", panel: <PolicyInstallMonitoringPanel /> },
          { label: "Static routes", panel: <StaticRoutePreviewPanel /> },
          { label: "Script execution", panel: <ScriptExecutionPanel /> },
        ]}
      />
    </ScreenRoot>
  );
}
