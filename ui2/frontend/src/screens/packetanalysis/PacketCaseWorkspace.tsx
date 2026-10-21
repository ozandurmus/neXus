import { useEffect, useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import {
  getPacketCaseCorrelation,
  getPacketCaseFindings,
  getPacketCaseState,
  getPacketCaseTimeseries,
  savePacketCaseTopology,
  type ApiError,
  type PacketAnalysisPermissions,
  type PacketCaseWorkspaceState,
  type PacketFinding,
  type PacketFlowItem,
  type PacketNexusCorrelation,
  type PacketRef,
  type PacketSegment,
  type PacketTimeseriesView,
  type PacketTopology,
} from "../../auth/adminApi";
import { M3Button, M3Tabs, StatusChip } from "../../shell/M3Widgets";
import { StatePanel } from "../../shell/States";
import { m3 } from "../../theme/m3Theme";
import { HeatmapChart, paTime } from "./PacketCharts";
import { PacketDnsTab, PacketFlowsTab } from "./PacketFlowsTab";
import {
  CaptureQualitySection,
  FrameEvidenceModal,
  InvestigationResultCard,
  PathHealthStrip,
  RankedFindingsAndSegments,
} from "./PacketOverviewTab";
import {
  CaptureNotesSection,
  CyclesSection,
  ExportActions,
  IncidentSection,
  MicroburstsSection,
  ProtocolDiagnosticsSection,
  ReferenceCompareSection,
  SessionsSection,
  UdpMediaSection,
} from "./PacketEvidenceSections";
import { PacketDeviceEventsTab } from "./PacketDeviceEventsTab";
import { PacketTopologyTab } from "./PacketTopologyTab";

export type WorkspaceTab = "overview" | "topology" | "flows" | "timeseries" | "dns" | "udp" | "device_events";
/** The order of the "Investigation views" tabs below (labels registered in shell/tabCoverage.ts). */
export const WORKSPACE_TABS: readonly WorkspaceTab[] = ["overview", "topology", "flows", "timeseries", "dns", "udp", "device_events"];

function OverviewSection({
  title,
  defaultExpanded = false,
  children,
}: {
  readonly title: string;
  readonly defaultExpanded?: boolean;
  readonly children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultExpanded);
  return (
    <Card sx={{ boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label={title}>
      <Box
        component="button"
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        sx={{
          width: "100%", height: 44, display: "flex", alignItems: "center", gap: 1, px: 2, border: "none",
          bgcolor: open ? m3.scLow : "transparent", font: "inherit", cursor: "pointer", color: m3.onSurface, textAlign: "left",
        }}
      >
        <Box component="span" aria-hidden sx={{ color: m3.onSurfaceVar, width: 12 }}>{open ? "▾" : "▸"}</Box>
        <Box component="span" sx={{ fontWeight: 600, fontSize: 14 }}>{title}</Box>
      </Box>
      {open && <Box sx={{ p: 2 }}>{children}</Box>}
    </Card>
  );
}

export function PacketCaseWorkspace({
  caseId,
  permissions,
  onError,
  onNotice,
}: {
  readonly caseId: string;
  readonly permissions: PacketAnalysisPermissions | undefined;
  readonly onError: (msg: string) => void;
  readonly onNotice: (msg: string) => void;
}) {
  const [workspace, setWorkspace] = useState<PacketCaseWorkspaceState | null>(null);
  const [topology, setTopology] = useState<PacketTopology | null>(null);
  const [correlation, setCorrelation] = useState<PacketNexusCorrelation | null>(null);
  const [tab, setTab] = useState<WorkspaceTab>("overview");
  const [range, setRange] = useState<readonly [number, number] | null>(null);
  const [rangeFindings, setRangeFindings] = useState<readonly PacketFinding[] | null>(null);
  const [timeseries, setTimeseries] = useState<PacketTimeseriesView | null>(null);
  const [selectedFlow, setSelectedFlow] = useState<PacketFlowItem | null>(null);
  const [inspectPoint, setInspectPoint] = useState<string | null>(null);
  const [evidence, setEvidence] = useState<readonly PacketRef[] | null>(null);
  const [runningConflict, setRunningConflict] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setWorkspace(null);
    setTopology(null);
    setCorrelation(null);
    setRange(null);
    setRangeFindings(null);
    setTimeseries(null);
    setSelectedFlow(null);
    setInspectPoint(null);
    setEvidence(null);
    setRunningConflict(null);

    const p = getPacketCaseState(caseId);
    if (!p || typeof p.then !== "function") return () => { active = false; };
    p.then((data) => {
      if (!active || !data) return;
      setWorkspace(data);
      setTopology(data.topology);
      const initialCorr = data.nexus_correlation ?? data.report?.nexus_correlation ?? null;
      if (initialCorr) {
        setCorrelation(initialCorr);
      }
    }).catch((err: unknown) => {
      if (!active) return;
      const apiErr = err as ApiError | undefined;
      if (apiErr?.status === 409) {
        setRunningConflict(
          typeof apiErr.body?.reason === "string"
            ? apiErr.body.reason
            : "The case is being analysed; wait for the job to finish.",
        );
      }
    });
    return () => {
      active = false;
    };
  }, [caseId]);

  useEffect(() => {
    if (!workspace?.report) return;
    let active = true;
    const p = getPacketCaseTimeseries(caseId);
    if (p && typeof p.then === "function") {
      p.then((ts) => {
        if (active && ts) setTimeseries(ts);
      }).catch(() => undefined);
    }
    const corrPromise = getPacketCaseCorrelation(caseId);
    if (corrPromise && typeof corrPromise.then === "function") {
      corrPromise.then((corr) => {
        if (active && corr) setCorrelation(corr);
      }).catch(() => undefined);
    }
    return () => {
      active = false;
    };
  }, [caseId, workspace?.report]);

  useEffect(() => {
    if (!range || !workspace?.report) {
      setRangeFindings(null);
      return;
    }
    let active = true;
    const p = getPacketCaseFindings(caseId, { start: range[0], end: range[1] });
    if (p && typeof p.then === "function") {
      p.then((res) => {
        if (active && res?.items) setRangeFindings(res.items);
      }).catch(() => undefined);
    }
    return () => {
      active = false;
    };
  }, [caseId, range, workspace?.report]);

  if (runningConflict) {
    return <Alert severity="warning">{runningConflict}</Alert>;
  }

  if (!workspace || !topology) {
    return null;
  }

  const report = workspace.report;
  const visibleFindings = range ? (rangeFindings ?? []) : (report?.findings ?? []);

  const handleSaveTopology = async (next: PacketTopology) => {
    try {
      const updated = await savePacketCaseTopology(caseId, next);
      if (updated) {
        setWorkspace(updated);
        setTopology(updated.topology);
        const updatedCorr = updated.nexus_correlation ?? updated.report?.nexus_correlation ?? null;
        if (updatedCorr) {
          setCorrelation(updatedCorr);
        }
      }
      onNotice("Topology saved and re-analyzed.");
    } catch (err) {
      const apiErr = err as ApiError | undefined;
      const reason = apiErr?.body?.reason ?? apiErr?.body?.error;
      onError(typeof reason === "string" ? reason : "Topology could not be saved.");
    }
  };

  const tabIndex = Math.max(0, WORKSPACE_TABS.indexOf(tab));

  return (
    <Box aria-label="Case investigation workspace" sx={{ mt: 2 }}>
      {(report || range) && (
        <Card sx={{ p: 1.5, mb: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}>
          <Stack direction="row" spacing={2} alignItems="center" justifyContent="flex-end" sx={{ flexWrap: "wrap", gap: 1 }}>
            {report && <ExportActions caseId={caseId} canRawView={Boolean(permissions?.raw_view)} onError={onError} range={range} />}
            {range && (
              <Stack direction="row" spacing={1} alignItems="center">
                <StatusChip
                  tone="attn"
                  label={`Interval: ${paTime(range[0])} — ${paTime(range[1])}`}
                  dense
                />
                <M3Button emphasis="outlined" onClick={() => setRange(null)}>
                  Reset range
                </M3Button>
              </Stack>
            )}
          </Stack>
        </Card>
      )}

      <M3Tabs
        ariaLabel="Investigation views"
        value={tabIndex}
        onChange={(next) => setTab(WORKSPACE_TABS[next] ?? "overview")}
        tabs={[
          {
            label: "Overview & Verdict",
            panel: (
              <Stack spacing={2}>
                {report ? (
                  <>
                    <InvestigationResultCard
                      report={report}
                      topology={topology}
                      onEvidence={setEvidence}
                    />
                    <PathHealthStrip
                      report={report}
                      topology={topology}
                      onInspectPoint={(pointId) => {
                        setInspectPoint(pointId);
                        setTab("topology");
                      }}
                      onSelectSegment={(_segment: PacketSegment) => {
                        // Handled in Hop-by-hop table below
                      }}
                    />
                    <RankedFindingsAndSegments
                      caseId={caseId}
                      report={report}
                      topology={topology}
                      findings={visibleFindings}
                      range={range}
                      onEvidence={setEvidence}
                    />
                    <OverviewSection title="Timeline" defaultExpanded>
                      <IncidentSection report={report} range={range} onEvidence={setEvidence} />
                    </OverviewSection>
                    <OverviewSection title="Cycles, hold and release, host path">
                      <CyclesSection
                        caseId={caseId}
                        report={report}
                        topology={topology}
                        canRawView={Boolean(permissions?.raw_view)}
                        range={range}
                        onEvidence={setEvidence}
                      />
                    </OverviewSection>
                    <OverviewSection title="Evidence quality" defaultExpanded>
                      <Stack spacing={2}>
                        <CaptureQualitySection
                          captures={workspace.captures}
                          report={report}
                          topology={topology}
                          onEvidence={setEvidence}
                        />
                        <CaptureNotesSection report={report} />
                      </Stack>
                    </OverviewSection>
                    <OverviewSection title="Protocol, translation and onset details">
                      <ProtocolDiagnosticsSection report={report} topology={topology} onEvidence={setEvidence} />
                    </OverviewSection>
                    <OverviewSection title="Session and microburst details">
                      <Stack spacing={2}>
                        <SessionsSection caseId={caseId} report={report} onEvidence={setEvidence} />
                        <MicroburstsSection report={report} onEvidence={setEvidence} />
                      </Stack>
                    </OverviewSection>
                    <OverviewSection title="Reference and run comparison">
                      <ReferenceCompareSection caseId={caseId} />
                    </OverviewSection>
                  </>
                ) : (
                  <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}>
                    <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                      One path. Multiple perspectives.
                    </Typography>
                    <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                      Attach the captures, connect their capture points in Topology, then analyze the same traffic across the path.
                    </Typography>
                  </Card>
                )}
              </Stack>
            ),
          },
          {
            label: "Topology",
            panel: (
              <PacketTopologyTab
                caseId={caseId}
                topology={topology}
                captures={workspace.captures}
                report={report}
                correlation={correlation}
                range={range}
                canEdit={Boolean(permissions?.run)}
                initialInspectPoint={inspectPoint}
                onTopologyChange={setTopology}
                onSaveTopology={handleSaveTopology}
                onSelectFlow={(flow) => {
                  setSelectedFlow(flow);
                  setTab("flows");
                }}
                onEvidence={setEvidence}
                onError={onError}
              />
            ),
          },
          {
            label: "Flows & Ladder",
            panel: (
              <PacketFlowsTab
                caseId={caseId}
                topology={topology}
                correlation={correlation}
                range={range}
                canRawView={Boolean(permissions?.raw_view)}
                selectedFlow={selectedFlow}
                onSelectFlow={setSelectedFlow}
                onRangeChange={setRange}
                onEvidence={setEvidence}
              />
            ),
          },
          {
            label: "Timeseries & Quality",
            panel: (
              <Stack spacing={2}>
                {report && timeseries && (
                  <Card sx={{ p: 2, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }} aria-label="Timeseries heatmap">
                    <HeatmapChart
                      data={timeseries}
                      report={report}
                      selectedRange={range}
                      onSelect={setRange}
                    />
                  </Card>
                )}
                {report && (
                  <CaptureQualitySection
                    captures={workspace.captures}
                    report={report}
                    topology={topology}
                    onEvidence={setEvidence}
                  />
                )}
                {report && <CaptureNotesSection report={report} />}
              </Stack>
            ),
          },
          {
            label: "DNS",
            panel: (
              <PacketDnsTab
                caseId={caseId}
                range={range}
                onEvidence={setEvidence}
              />
            ),
          },
          {
            label: "UDP / Media",
            panel: (
              report ? (
                <UdpMediaSection report={report} onEvidence={setEvidence} />
              ) : (
                <Alert severity="info">Run the analysis to see UDP request/response, SIP and RTP evidence.</Alert>
              )
            ),
          },
          {
            label: "Device events",
            // Mockup 2026-10-17 screen 4: the stored system-log facts of the path firewalls around one finding.
            panel: report ? (
              <PacketDeviceEventsTab caseId={caseId} />
            ) : (
              <StatePanel variant="not_evaluated" announce="status" title="Run the analysis to see device events"
                body="Device events are read around a finding's start time; a case without a completed analysis has no finding yet." />
            ),
          },
        ]}
      />

      {evidence && (
        <FrameEvidenceModal
          refs={evidence}
          onClose={() => setEvidence(null)}
        />
      )}
    </Box>
  );
}
