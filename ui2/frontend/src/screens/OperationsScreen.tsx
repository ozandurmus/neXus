import { canRunReadiness, haReadinessItems, HaReadinessList, type ReadinessProgress } from "./HaReadinessList";
import { ReadinessCard } from "./ReadinessChecksTable";
import { CpFailoverPanel } from "./CpFailoverPanel";
import { useState, useEffect, useRef } from "react";
import Card from "@mui/material/Card";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import Drawer from "@mui/material/Drawer";

import { ScreenHeader, MetricGrid, MetricCard, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, M3Tabs } from "../shell/M3Widgets";
import { StatePanel } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { JobLogsPanel } from "./JobLogsPanel";
import { DiagnosticPanel } from "./DiagnosticPanel";
import { urlParam } from "../shell/urlParams";
import { getJobStats, getCpFailoverRun, listDevices, listCpFailoverSummary, runCpFailoverReadiness, type CpFailoverSummary, type DeviceSummary } from "../auth/adminApi";
import { deriveClusterTitle } from "./InventoryPanels";

/** Operations screen for observed HA state, on-demand readiness and jobs. */
export function OperationsScreen() {
  // Counted from /api/v2/jobs (the same reads the Jobs screen makes); a failed read says so, never 0.
  const [jobStats, setJobStats] = useState<{ total24h: number; completed24h: number; failed24h: number; otherTerminal24h: number; otherLabel: string; running: number } | null>(null);
  useEffect(() => {
    getJobStats()
      .then((s) => {
        const others: Array<[string, number]> = [["outcome unknown", s.outcome_unknown_24h ?? 0], ["rejected", s.rejected_24h ?? 0], ["cancelled", s.cancelled_24h ?? 0]];
        setJobStats({ total24h: s.total_24h, completed24h: s.completed_24h, failed24h: s.failed_24h,
          otherTerminal24h: others.reduce((n, [, c]) => n + c, 0),
          otherLabel: others.filter(([, c]) => c > 0).map(([w, c]) => `${c} ${w}`).join(" · "), running: s.running });
      })
      .catch(() => setJobStats(null));
  }, []);

  const [selectedCluster, setSelectedCluster] = useState<string | null>(null);
  // The enrolled clusters, from the device list (cluster reference -> members). Replaces two hardcoded
  // demo names and their demo checks (2026-09-22): nothing here is shown that the store did not return.
  const [clusters, setClusters] = useState<ReadonlyArray<{ ref: string; title: string; members: DeviceSummary[] }> | null>(null);
  useEffect(() => {
    listDevices()
      .then(({ devices }) => {
        const byRef = new Map<string, DeviceSummary[]>();
        for (const d of devices ?? []) {
          if (d.cluster_member_ref) byRef.set(d.cluster_member_ref, [...(byRef.get(d.cluster_member_ref) ?? []), d]);
        }
        setClusters([...byRef.entries()].map(([ref, members]) => ({ ref, title: deriveClusterTitle(ref, members), members }))
          .sort((a, b) => a.title.localeCompare(b.title)));
      })
      .catch(() => setClusters([]));
  }, []);
  const selected = clusters?.find((c) => c.ref === selectedCluster) ?? null;
  const [cpRows, setCpRows] = useState<Record<string, CpFailoverSummary[]>>({});
  useEffect(() => {
    let mounted = true;
    void listCpFailoverSummary().then(rows => {
      if (!mounted) return;
      const byRef: Record<string, CpFailoverSummary[]> = {};
      for (const row of rows) (byRef[row.cluster_member_ref] ??= []).push(row);
      setCpRows(byRef);
    }).catch(() => { if (mounted) setCpRows({}); });
    return () => { mounted = false; };
  }, []);
  const [isRunning, setIsRunning] = useState<string | null>(null);
  const [expandedUnit, setExpandedUnit] = useState<string | null>(null);
  const [readinessError, setReadinessError] = useState<string | null>(null);
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

  const [tab, setTab] = useState(urlParam("tab") === "diagnostics" ? 4 : urlParam("tab") === "jobs" ? 1 : 0);

  // A cluster header elsewhere in the product links here as ?screen=operations&cluster_ref=<ref>; preselect it.
  useEffect(() => {
    if (!clusters || clusters.length === 0 || selectedCluster) return;
    const ref = urlParam("cluster_ref");
    if (ref && clusters.some((c) => c.ref === ref)) {
      setSelectedCluster(ref);
      setTab(0);
    }
  }, [clusters]);

  const handleRunReadiness = async (row: CpFailoverSummary): Promise<boolean> => {
    if (!canRunReadiness(row) || runLock.current) return false;
    runLock.current = true;
    setIsRunning(row.unitId);
    setReadinessError(null);
    try {
      const { runId } = await runCpFailoverReadiness(row);
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
    } catch {
      setReadinessError("Could not start or refresh the readiness run.");
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
    try {
      for (let index = 0; index < allowed.length; index++) {
        const row = allowed[index];
        setReadinessProgress(current => ({ ...current, [row.unitId]: "Running" }));
        const completed = await handleRunReadiness(row);
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

  const renderHaPanel = () => <>
    {clusters === null ? <StatePanel variant="empty" title="Reading the enrolled clusters…" />
      : clusters.length === 0 ? <StatePanel variant="empty" title="No HA pair or cluster enrolled"
        body="Readiness needs enrolled cluster members and a current health read from each one; none is enrolled, so no cluster can be assessed." />
      : <HaReadinessList clusters={clusters} rows={cpRows} running={isRunning} busy={bulkRunning || isRunning !== null}
        progress={readinessProgress} error={readinessError}
        onOpen={(ref, unitId) => { setSelectedCluster(ref); setExpandedUnit(unitId); }}
        onRun={row => { if (!batchLock.current) void handleRunReadiness(row); }}
        onBulkRun={units => { void handleBulkReadiness(units); }} />}
    <Drawer anchor="right" open={selectedCluster !== null} onClose={() => setSelectedCluster(null)}
      PaperProps={{ role: "dialog", "aria-modal": true, "aria-label": "HA readiness detail", sx: { width: "min(100vw, 1000px)", p: 2, bgcolor: m3.scLowest } }}>
      {selectedCluster && <>
        <M3Button emphasis="text" onClick={() => setSelectedCluster(null)}>Close detail</M3Button>
        <ReadinessCard checks={selectedReadinessRow?.readiness?.checks ?? []}
          members={selectedReadinessRow?.members} cluster={selected?.title ?? "Masked unit"} vendor={selectedReadinessRow?.vendor}
          masked={selectedReadinessRow?.masked} status={selectedReadinessRow?.readiness?.status}
          observedAt={selectedReadinessRow?.readiness?.observedAt} canRun={false} onRun={() => {}} />
        {selected?.members[0] && ["check_point", "palo_alto"].includes(selected.members[0].vendor_hint) ?
          <CpFailoverPanel key={selectedCluster} memberDeviceId={selected.members[0].device_id}
            vendor={selected.members[0].vendor_hint as "check_point" | "palo_alto"} initialUnitId={expandedUnit} />
          : <StatePanel variant="empty" title="UNSUPPORTED_MODE" />}
      </>}
    </Drawer>
  </>;

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Operations"
        subtitle="Inspect observed HA roles, readiness evaluations and jobs."
        actions={<M3Button emphasis="filled">Schedule collection</M3Button>}
      />
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
        <MetricCard
          title="Readiness checks"
          value={clusters ? `${readinessCounts.ready} ready · ${readinessCounts.notReady} not ready · ${readinessCounts.unknown} unknown` : null}
          note={clusters ? `${clusters.length} clusters enrolled · ${evaluatedCount} readiness records · ${readinessCounts.unsupported} not supported` : "reading enrolled clusters"}
        />
        <Card sx={{ bgcolor: m3.scLowest, boxShadow: "none", border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", p: 2.25,
                    display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar }}>Failed (24 h)</Typography>
          <Typography variant="h1">{jobStats ? jobStats.failed24h : "—"}</Typography>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{jobStats ? (jobStats.failed24h === 0 ? "none" : `${jobStats.failed24h} failed jobs`) : "read failed"}</Typography>
          {/* The header's "Job history" button duplicated the History tab (review §3); it lives here instead. */}
          <Box component="button" onClick={() => setTab(3)}
            sx={{ all: "unset", cursor: "pointer", color: m3.primary, fontSize: 13, fontWeight: 500 }}>
            Job history
          </Box>
        </Card>
      </MetricGrid>
      <M3Tabs
        ariaLabel="Operations sections"
        value={tab}
        onChange={setTab}
        tabs={[
          {
            label: "HA & readiness",
            panel: renderHaPanel(),
          },
          { label: "Jobs", panel: <Box><Typography variant="subtitle2" sx={{ mb: 1 }}>All jobs</Typography><JobLogsPanel
            initialState={urlParam("state") ?? ""} initialJobType={urlParam("job_type") ?? ""}
            initialSinceHours={urlParam("since_hours") ? Number(urlParam("since_hours")) : undefined} initialText={urlParam("q") ?? ""} /></Box> },
          { label: "Queue", panel: <Box><Typography variant="subtitle2" sx={{ mb: 1 }}>Queued and running jobs</Typography><JobLogsPanel initialState="REQUESTED,CLAIMED,EXECUTING" emptyTitle="Queue empty" emptyBody="0 requested · 0 claimed · 0 executing" /></Box> },
          { label: "History", panel: <Box><Typography variant="subtitle2" sx={{ mb: 1 }}>Finished jobs</Typography><JobLogsPanel initialState="COMPLETED,FAILED,OUTCOME_UNKNOWN,REJECTED" /></Box> },
          { label: "Diagnostics", panel: <DiagnosticPanel /> },
        ]}
      />
    </ScreenRoot>
  );
}
