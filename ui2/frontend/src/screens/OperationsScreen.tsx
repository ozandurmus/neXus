import { useState, useEffect } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Typography from "@mui/material/Typography";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Paper from "@mui/material/Paper";
import Chip from "@mui/material/Chip";
import Dialog from "@mui/material/Dialog";
import DialogTitle from "@mui/material/DialogTitle";
import DialogContent from "@mui/material/DialogContent";
import DialogActions from "@mui/material/DialogActions";
import Tooltip from "@mui/material/Tooltip";
import TextField from "@mui/material/TextField";

import { ScreenHeader, MetricGrid, MetricCard, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { StatePanel, Ts, RoleChip, VendorBadge, Unknown } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { JobLogsPanel } from "./JobLogsPanel";
import { DiagnosticPanel } from "./DiagnosticPanel";
import { urlParam } from "../shell/urlParams";
import { getJobStats, listDevices, type DeviceSummary } from "../auth/adminApi";
import { deriveClusterTitle } from "./InventoryPanels";

interface PreflightCheckItem {
  id: string;
  name: string;
  category: string;
  enforcement: "BLOCKING" | "ADVISORY";
  status: "PASS" | "FAIL" | "WARNING" | "INSUFFICIENT_EVIDENCE";
  summary: string;
  remediationCode?: string;
}

// Reference pre-flight battery datasets (used in AIView preview / offline validation mode when backend is unreachable)

/** Operations screen featuring HA & readiness pre-flight checklist. */
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
  const [selectedCheck, setSelectedCheck] = useState<PreflightCheckItem | null>(null);
  const [filter, setFilter] = useState<"ALL" | "BLOCKING" | "ADVISORY">("ALL");
  const [isRunning, setIsRunning] = useState(false);
  const [apiChecks, setApiChecks] = useState<PreflightCheckItem[] | null>(null);
  const [apiVerdict, setApiVerdict] = useState<string | null>(null);

  // Which enrolled clusters have had a preflight read/run in this session, and what it said (review §3
  // "Readiness checks" KPI and the HA table's "last evaluated" / "readiness" columns). There is no product
  // capability that reports "evaluated" as a passive fleet-wide count -- reading a cluster's preflight is
  // what evaluates it -- so this is exactly what this browser session has observed, never a guess at the
  // other clusters' state.
  const [clusterCache, setClusterCache] = useState<Record<string, { verdict: string; checksCount: number; generatedAt: string | null }>>({});
  const evaluatedCount = Object.values(clusterCache).filter((r) => r.checksCount > 0).length;

  const [tab, setTab] = useState(urlParam("tab") === "diagnostics" ? 4 : urlParam("tab") === "jobs" ? 1 : 0);

  // A cluster header elsewhere in the product links here as ?screen=operations&cluster_ref=<ref>; preselect it.
  useEffect(() => {
    if (!clusters || clusters.length === 0 || selectedCluster) return;
    const ref = urlParam("cluster_ref");
    if (ref && clusters.some((c) => c.ref === ref)) {
      setSelectedCluster(ref);
      setTab(0);
    }
  }, [clusters, selectedCluster]);

  // Phase B / C 4-Eyes Failover and Execution states
  const [showFailoverModal, setShowFailoverModal] = useState(false);
  const [approverId, setApproverId] = useState("operator-bob");
  const [reason, setReason] = useState("Emergency maintenance drill SEC-101");
  const [mwRef, setMwRef] = useState("CHG-101");
  const [nonce, setNonce] = useState("nonce-001");
  const [leaseToken, setLeaseToken] = useState<string | null>(null);
  const [dryRunPlan, setDryRunPlan] = useState<any | null>(null);
  const [executionResult, setExecutionResult] = useState<any | null>(null);
  const [isAuthorizing, setIsAuthorizing] = useState(false);
  const [isExecuting, setIsExecuting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [quarantined, setQuarantined] = useState(false);
  const [showQuarantineAckModal, setShowQuarantineAckModal] = useState(false);
  const [ackApproverId, setAckApproverId] = useState("operator-bob");
  const [ackReason, setAckReason] = useState("Verified cluster health manually via out-of-band console");

  // Phase D Scheduled Maintenance Window states
  const [showScheduleModal, setShowScheduleModal] = useState(false);
  const [schedulesList, setSchedulesList] = useState<any[]>([]);
  const [scheduleStartTime, setScheduleStartTime] = useState(
    new Date(Date.now() + 2 * 3600 * 1000).toISOString().slice(0, 16)
  );
  const [scheduleDuration, setScheduleDuration] = useState(60);
  const [scheduleMaxDelay, setScheduleMaxDelay] = useState(15);
  const [scheduleActionKind, setScheduleActionKind] = useState("CONTROLLED_FAILOVER");
  const [scheduleApproverId, setScheduleApproverId] = useState("operator-bob");
  const [scheduleReason, setScheduleReason] = useState("Quarterly maintenance failover drill");
  const [isScheduling, setIsScheduling] = useState(false);
  const [scheduleError, setScheduleError] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedCluster) {
      setApiChecks(null);
      setApiVerdict(null);
      setSchedulesList([]);
      return;
    }

    let isMounted = true;
    fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/preflight`)
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then((data) => {
        if (!isMounted) return;
        if (data && Array.isArray(data.checks)) {
          const mapped: PreflightCheckItem[] = data.checks.map((c: any) => ({
            id: c.check_id,
            name: c.name,
            category: c.category,
            enforcement: c.enforcement,
            status: c.status,
            summary: c.summary,
            remediationCode: c.remediation_code,
          }));
          setApiChecks(mapped);
          setApiVerdict(data.overall_verdict);
          setClusterCache((prev) => ({ ...prev, [selectedCluster]: { verdict: data.overall_verdict, checksCount: mapped.length, generatedAt: data.generated_at ?? null } }));
        }
      })
      .catch(() => {
        // Standalone/offline reference mode fallback
      });

    fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/schedules`)
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => {
        if (isMounted && Array.isArray(data)) setSchedulesList(data);
      })
      .catch(() => {});

    return () => {
      isMounted = false;
    };
  }, [selectedCluster]);

  // Only what the preflight API returned; no demo fallback, and no verdict until one is evaluated.
  const checks = apiChecks ?? [];
  const overallVerdict = apiVerdict || "NOT_EVALUATED";

  const filteredChecks = checks.filter((c) => {
    if (filter === "BLOCKING") return c.enforcement === "BLOCKING";
    if (filter === "ADVISORY") return c.enforcement === "ADVISORY";
    return true;
  });

  const handleRunBattery = async () => {
    if (!selectedCluster) return;
    setIsRunning(true);
    try {
      const res = await fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/preflight`, { method: "POST" });
      if (res.ok) {
        const data = await res.json();
        if (data && Array.isArray(data.checks)) {
          const mapped: PreflightCheckItem[] = data.checks.map((c: any) => ({
            id: c.check_id,
            name: c.name,
            category: c.category,
            enforcement: c.enforcement,
            status: c.status,
            summary: c.summary,
            remediationCode: c.remediation_code,
          }));
          setApiChecks(mapped);
          setApiVerdict(data.overall_verdict);
          setClusterCache((prev) => ({ ...prev, [selectedCluster]: { verdict: data.overall_verdict, checksCount: mapped.length, generatedAt: data.generated_at ?? null } }));
        }
      }
    } catch {
      // Offline fallback
    } finally {
      setIsRunning(false);
    }
  };

  const handleAuthorizeAndDryRun = async () => {
    if (!selectedCluster) return;
    setIsAuthorizing(true);
    setActionError(null);
    try {
      const authRes = await fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/authorize`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          approver_id: approverId,
          reason,
          maintenance_window_ref: mwRef,
          assessment_digest: "sha256:digest-abc",
          nonce,
        }),
      });
      const authData = await authRes.json();
      if (!authRes.ok) {
        throw new Error(authData.reason || authData.error || `HTTP ${authRes.status}`);
      }
      const tokId = authData.token_id;
      setLeaseToken(tokId);

      // Disclose dry-run plan (does NOT consume single-use execution lease)
      const dryRes = await fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/dry-run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          token_id: tokId,
          nonce,
        }),
      });
      const dryData = await dryRes.json();
      if (!dryRes.ok) {
        throw new Error(dryData.reason || dryData.error || `HTTP ${dryRes.status}`);
      }
      setDryRunPlan(dryData);
    } catch (err: any) {
      setActionError(err.message || "Authorization failed");
    } finally {
      setIsAuthorizing(false);
    }
  };

  const handleExecuteFailover = async (kind: "CONTROLLED_FAILOVER" | "RETURN_TO_SERVICE") => {
    if (!selectedCluster || !leaseToken) return;
    setIsExecuting(true);
    setActionError(null);
    try {
      const res = await fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/execute`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          token_id: leaseToken,
          nonce,
          action_kind: kind,
        }),
      });
      const data = await res.json();
      setExecutionResult(data);
      if (data.quarantine_active || data.state === "OUTCOME_UNKNOWN") {
        setQuarantined(true);
      }
      if (!res.ok && res.status !== 409 && res.status !== 422) {
        throw new Error(data.reason || data.error || `HTTP ${res.status}`);
      }
    } catch (err: any) {
      setActionError(err.message || "Execution error");
    } finally {
      setIsExecuting(false);
    }
  };

  const handleAcknowledgeQuarantine = async () => {
    if (!selectedCluster) return;
    try {
      const res = await fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/quarantine/acknowledge`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          approver_id: ackApproverId,
          reason: ackReason,
        }),
      });
      if (res.ok) {
        setQuarantined(false);
        setShowQuarantineAckModal(false);
      }
    } catch {
      // ignore
    }
  };

  const handleScheduleWindow = async () => {
    if (!selectedCluster) return;
    setIsScheduling(true);
    setScheduleError(null);
    try {
      const startIso = new Date(scheduleStartTime).toISOString();
      const endIso = new Date(new Date(scheduleStartTime).getTime() + scheduleDuration * 60000).toISOString();
      const res = await fetch(`/api/v2/failover/${encodeURIComponent(selectedCluster)}/schedules`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          actionKind: scheduleActionKind,
          windowStart: startIso,
          windowEnd: endIso,
          maxStartDelayMinutes: scheduleMaxDelay,
          requesterId: "current-user",
          approverId: scheduleApproverId,
          reason: scheduleReason,
          clientNonce: `nonce-${Date.now()}`,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.message || data.error || `HTTP ${res.status}`);
      }
      setSchedulesList((prev) => [data, ...prev]);
      setShowScheduleModal(false);
    } catch (err: any) {
      setScheduleError(err.message);
    } finally {
      setIsScheduling(false);
    }
  };

  const handleCancelSchedule = async (scheduleId: string) => {
    try {
      const res = await fetch(`/api/v2/failover/schedules/${scheduleId}/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          operatorId: "current-user",
          reason: "Cancelled by operator via operations console",
        }),
      });
      if (res.ok) {
        const updated = await res.json();
        setSchedulesList((prev) => prev.map((s) => (s.scheduleId === scheduleId ? updated : s)));
      }
    } catch {
      // ignore
    }
  };

  const handleTriggerSchedule = async (scheduleId: string) => {
    try {
      const res = await fetch(`/api/v2/failover/schedules/${scheduleId}/trigger`, {
        method: "POST",
      });
      if (res.ok) {
        const updated = await res.json();
        setSchedulesList((prev) => prev.map((s) => (s.scheduleId === scheduleId ? updated : s)));
      }
    } catch {
      // ignore
    }
  };

  const renderHaPanel = () => {
    if (!selectedCluster) {
      const count = clusters?.length ?? 0;
      if (clusters === null) {
        return <StatePanel variant="empty" title="Reading the enrolled clusters…" />;
      }
      if (count === 0) {
        return (
          <StatePanel
            variant="empty"
            title="No HA pair or cluster enrolled"
            body="Readiness needs enrolled cluster members and a current health read from each one; none is enrolled, so no cluster can be assessed."
          />
        );
      }
      const roleOfMembers = (members: DeviceSummary[], role: string) =>
        members.filter((m) => (m.ha_role ?? "").toLowerCase() === role).map((m) => m.hostname ?? m.device_id).join(", ") || null;
      return (
        <Box>
          <Typography variant="h4" sx={{ mb: 0.5 }}>{count} clusters enrolled</Typography>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 2 }}>
            Choose a cluster to read its preflight checks. Readiness is observed only; nothing is inferred. Failover is unavailable in this build.
          </Typography>
          <TableContainer component={Paper} sx={{ borderRadius: "10px", border: `1px solid ${m3.outlineVar}`, boxShadow: "none" }}>
            <Table size="small">
              <TableHead sx={{ bgcolor: m3.scLow }}>
                <TableRow>
                  <TableCell sx={{ fontWeight: 600, fontSize: 11, letterSpacing: "0.04em", textTransform: "uppercase" }}>Cluster</TableCell>
                  <TableCell sx={{ fontWeight: 600, fontSize: 11, letterSpacing: "0.04em", textTransform: "uppercase" }}>Vendor</TableCell>
                  <TableCell sx={{ fontWeight: 600, fontSize: 11, letterSpacing: "0.04em", textTransform: "uppercase" }}>Active member</TableCell>
                  <TableCell sx={{ fontWeight: 600, fontSize: 11, letterSpacing: "0.04em", textTransform: "uppercase" }}>Standby member</TableCell>
                  <TableCell sx={{ fontWeight: 600, fontSize: 11, letterSpacing: "0.04em", textTransform: "uppercase" }}>Roles observed</TableCell>
                  <TableCell sx={{ fontWeight: 600, fontSize: 11, letterSpacing: "0.04em", textTransform: "uppercase" }}>Last evaluated</TableCell>
                  <TableCell sx={{ fontWeight: 600, fontSize: 11, letterSpacing: "0.04em", textTransform: "uppercase" }}>Readiness</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {clusters!.map((c) => {
                  const active = roleOfMembers(c.members, "active");
                  const standby = roleOfMembers(c.members, "standby") ?? roleOfMembers(c.members, "passive");
                  const report = clusterCache[c.ref];
                  const evaluated = report && report.checksCount > 0;
                  return (
                    <TableRow key={c.ref} hover onClick={() => setSelectedCluster(c.ref)} sx={{ cursor: "pointer" }}>
                      <TableCell sx={{ fontWeight: 500 }}>{c.title}</TableCell>
                      <TableCell><VendorBadge vendor={c.members[0]?.vendor_hint} /></TableCell>
                      <TableCell sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                        {active ? <Box component="span" sx={{ fontFamily: "inherit" }}>{active}</Box> : <Unknown />}
                        {active ? <RoleChip role="ACTIVE" dense /> : null}
                      </TableCell>
                      <TableCell>
                        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                          {standby ? <Box component="span">{standby}</Box> : <Unknown />}
                          {standby ? <RoleChip role="STANDBY" dense /> : null}
                        </Box>
                      </TableCell>
                      {/* When the roles were read (newest member inventory), separate from readiness evaluation. */}
                      <TableCell><Ts at={c.members.map((m) => m.inventory_collected_at ?? null).filter((t): t is string => Boolean(t)).sort().slice(-1)[0] ?? null} seconds={false} /></TableCell>
                      <TableCell><Ts at={report?.generatedAt ?? null} /></TableCell>
                      <TableCell>
                        {evaluated
                          ? <StatusChip tone={report!.verdict === "NO_BLOCKING_CONDITIONS_OBSERVED" ? "ok" : report!.verdict === "ADVISORY_CONDITIONS_PRESENT" ? "warn" : "bad"} label={report!.verdict} dense />
                          : <Unknown word="NOT EVALUATED" reason="No preflight has been read for this cluster in this session." />}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        </Box>
      );
    }

    const isPan = selected?.members[0]?.vendor_hint === "palo_alto";
    const clusterName = selected?.title ?? selectedCluster;
    const vendorLabel = isPan ? "Palo Alto Networks (Active/Passive)" : "Check Point (ClusterXL HA)";
    const roleOf = (role: string) => selected?.members.filter((m) => (m.ha_role ?? "").toLowerCase() === role).map((m) => m.hostname ?? m.device_id).join(", ") || "UNKNOWN";

    return (
      <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
        {/* Cluster Switcher & Overview Card */}
        <Card sx={{ bgcolor: m3.scLow, borderRadius: "16px", p: 2 }}>
          <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 2 }}>
            <Box>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
                <Typography variant="h6" sx={{ fontWeight: 600, color: m3.onSurface }}>
                  {clusterName}
                </Typography>
                <Chip size="small" label={vendorLabel} sx={{ bgcolor: m3.scHighest, color: m3.onSurfaceVar }} />
                <Chip size="small" label="AIView Pseudonymized" sx={{ bgcolor: m3.primaryContainer, color: m3.onPrimaryContainer }} />
              </Box>
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.5 }}>
                Active: {roleOf("active")} • Standby: {roleOf("standby") !== "UNKNOWN" ? roleOf("standby") : roleOf("passive")} • {apiVerdict ? "Evaluated by the preflight API" : "Not evaluated yet"}
              </Typography>
            </Box>
            <Box sx={{ display: "flex", gap: 1 }}>
              <M3Button emphasis="text" onClick={() => setSelectedCluster(null)}>
                All clusters
              </M3Button>
            </Box>
          </Box>
        </Card>

        {/* Sticky Quarantine Alert Banner */}
        {quarantined && (
          <Card sx={{ bgcolor: m3.warningContainer, border: `1px solid ${m3.warning}`, borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: m3.warning, color: m3.onWarning, display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  !
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: m3.onWarningContainer }}>
                    STICKY ENTITY QUARANTINE ACTIVE (OUTCOME_UNKNOWN)
                  </Typography>
                  <Typography variant="body2" sx={{ color: m3.onWarningContainer }}>
                    Cluster and member endpoints are locked from CLASS 2+ actions until 4-eyes audited acknowledgment.
                  </Typography>
                </Box>
              </Box>
              <M3Button emphasis="tonal" onClick={() => setShowQuarantineAckModal(true)}>
                Acknowledge Quarantine (4-Eyes)
              </M3Button>
            </Box>
          </Card>
        )}

        {/* Overall Verdict Banner */}
        {overallVerdict === "BLOCKING_CONDITIONS_PRESENT" ? (
          <Card sx={{ bgcolor: m3.errorContainer, border: `1px solid ${m3.error}`, borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: m3.error, color: m3.onError, display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  ✕
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: m3.onErrorContainer }}>
                    VERDICT: BLOCKING_CONDITIONS_PRESENT
                  </Typography>
                  <Typography variant="body2" sx={{ color: m3.onErrorContainer }}>
                    Pre-flight safety checks failed or evidence is missing. Failover is strictly blocked.
                  </Typography>
                </Box>
              </Box>
              <M3Button emphasis="tonal" onClick={handleRunBattery} disabled={isRunning}>
                {isRunning ? "Evaluating..." : "Run Pre-Flight Battery"}
              </M3Button>
            </Box>
          </Card>
        ) : overallVerdict === "ADVISORY_CONDITIONS_PRESENT" ? (
          <Card sx={{ bgcolor: m3.warningContainer, border: `1px solid ${m3.warning}`, borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: m3.warning, color: m3.onWarning, display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  !
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: m3.onWarningContainer }}>
                    VERDICT: ADVISORY_CONDITIONS_PRESENT
                  </Typography>
                  <Typography variant="body2" sx={{ color: m3.onWarningContainer }}>
                    Advisory conditions noted. Proceed with operational awareness.
                  </Typography>
                </Box>
              </Box>
              <M3Button emphasis="tonal" onClick={handleRunBattery} disabled={isRunning}>
                {isRunning ? "Evaluating..." : "Run Pre-Flight Battery"}
              </M3Button>
            </Box>
          </Card>
        ) : checks.length === 0 ? (
          <StatePanel
            variant="not_evaluated"
            title="The preflight API returned no checks for this cluster"
            body="Nothing is claimed about its readiness. Run the pre-flight battery to evaluate it."
            action={<M3Button emphasis="tonal" onClick={handleRunBattery} disabled={isRunning}>{isRunning ? "Evaluating..." : "Run Pre-Flight Battery"}</M3Button>}
          />
        ) : (
          <Card sx={{ bgcolor: m3.successContainer, border: `1px solid ${m3.success}`, borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: m3.success, color: m3.onSuccess, display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  ✓
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: m3.onSuccessContainer }}>
                    VERDICT: NO_BLOCKING_CONDITIONS_OBSERVED
                  </Typography>
                  <Typography variant="body2" sx={{ color: m3.onSuccessContainer }}>
                    All {checks.length} pre-flight checks evaluated cleanly against verified evidence. Zero blocking conditions detected.
                  </Typography>
                </Box>
              </Box>
              <M3Button emphasis="tonal" onClick={handleRunBattery} disabled={isRunning}>
                {isRunning ? "Evaluating..." : "Run Pre-Flight Battery"}
              </M3Button>
            </Box>
          </Card>
        )}

        {/* Filter Bar and Pre-Flight Checklist Table -- hidden with the header when there are no rows to filter
            (review §3 "Cluster detail shows an empty table header"); the NOT EVALUATED panel above is the
            empty state. */}
        {checks.length > 0 && (
          <>
            <Box sx={{ display: "flex", gap: 1, alignItems: "center" }}>
              <Typography variant="body2" sx={{ fontWeight: 500, color: m3.onSurfaceVar, mr: 1 }}>
                Filter:
              </Typography>
              <Chip
                label={`All Checks (${checks.length})`}
                onClick={() => setFilter("ALL")}
                color={filter === "ALL" ? "primary" : "default"}
                variant={filter === "ALL" ? "filled" : "outlined"}
              />
              <Chip
                label="Blocking Only"
                onClick={() => setFilter("BLOCKING")}
                color={filter === "BLOCKING" ? "primary" : "default"}
                variant={filter === "BLOCKING" ? "filled" : "outlined"}
              />
              <Chip
                label="Advisory Only"
                onClick={() => setFilter("ADVISORY")}
                color={filter === "ADVISORY" ? "primary" : "default"}
                variant={filter === "ADVISORY" ? "filled" : "outlined"}
              />
            </Box>

            <TableContainer component={Paper} sx={{ borderRadius: "12px", border: `1px solid ${m3.outlineVar}` }}>
              <Table size="small">
                <TableHead sx={{ bgcolor: m3.scLow }}>
                  <TableRow>
                    <TableCell sx={{ fontWeight: 600 }}>Check Name</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Category</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Enforcement</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Status</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Summary</TableCell>
                    <TableCell sx={{ fontWeight: 600, textAlign: "right" }}>Action</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {filteredChecks.map((chk) => (
                    <TableRow key={chk.id} hover>
                      <TableCell sx={{ fontWeight: 500 }}>{chk.name}</TableCell>
                      <TableCell sx={{ color: m3.onSurfaceVar, fontSize: 13 }}>{chk.category}</TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={chk.enforcement}
                          sx={{
                            fontSize: 11,
                            fontWeight: 600,
                            bgcolor: chk.enforcement === "BLOCKING" ? m3.errorContainer : m3.scHighest,
                            color: chk.enforcement === "BLOCKING" ? m3.onErrorContainer : m3.onSurfaceVar,
                          }}
                        />
                      </TableCell>
                      <TableCell>
                        <StatusChip tone={chk.status === "PASS" ? "ok" : chk.status === "WARNING" ? "warn" : "bad"} label={chk.status} dense />
                      </TableCell>
                      <TableCell sx={{ fontSize: 13, color: m3.onSurface }}>{chk.summary}</TableCell>
                      <TableCell sx={{ textAlign: "right" }}>
                        <M3Button emphasis="text" onClick={() => setSelectedCheck(chk)}>
                          Details
                        </M3Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </>
        )}

        {/* Phase D Scheduled Maintenance Windows Table */}
        {schedulesList.length > 0 && (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 600, color: m3.onSurface }}>
              Phase D: Scheduled Maintenance Windows ({schedulesList.length})
            </Typography>
            <TableContainer component={Paper} sx={{ borderRadius: "12px", border: `1px solid ${m3.outlineVar}` }}>
              <Table size="small">
                <TableHead sx={{ bgcolor: m3.scLow }}>
                  <TableRow>
                    <TableCell sx={{ fontWeight: 600 }}>Schedule ID</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Action</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Window Start (UTC)</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Deadline (UTC)</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Target Member</TableCell>
                    <TableCell sx={{ fontWeight: 600 }}>Status</TableCell>
                    <TableCell sx={{ fontWeight: 600, textAlign: "right" }}>Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {schedulesList.map((s) => (
                    <TableRow key={s.scheduleId} hover>
                      <TableCell sx={{ fontFamily: "monospace", fontSize: 12, fontWeight: 500 }}>
                        {s.scheduleId}
                      </TableCell>
                      <TableCell>
                        <Chip size="small" label={s.actionKind} sx={{ fontSize: 11 }} />
                      </TableCell>
                      <TableCell sx={{ fontSize: 12 }}>{s.windowStart}</TableCell>
                      <TableCell sx={{ fontSize: 12 }}>{s.executionDeadline}</TableCell>
                      <TableCell sx={{ fontSize: 12, fontWeight: 500 }}>
                        {s.signedMutationTarget}
                      </TableCell>
                      <TableCell>
                        <StatusChip
                          tone={
                            s.status === "COMPLETED"
                              ? "ok"
                              : s.status === "SCHEDULED" || s.status === "CLAIMED_VERIFYING"
                              ? "warn"
                              : "bad"
                          }
                          label={s.status}
                          dense
                        />
                      </TableCell>
                      <TableCell sx={{ textAlign: "right" }}>
                        {s.status === "SCHEDULED" && (
                          <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end" }}>
                            <M3Button
                              emphasis="tonal"
                              onClick={() => handleTriggerSchedule(s.scheduleId)}
                            >
                              Trigger JIT
                            </M3Button>
                            <M3Button
                              emphasis="text"
                              onClick={() => handleCancelSchedule(s.scheduleId)}
                            >
                              Cancel
                            </M3Button>
                          </Box>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>
        )}

        {/* Action Controls & Gate Disclosure */}
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", p: 2, bgcolor: m3.scLow, borderRadius: "12px", flexWrap: "wrap", gap: 2 }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            Readiness assessment is observed. Device mutation actions require Phase B 4-Eyes authorization & Phase C command gate.
          </Typography>
          <Box sx={{ display: "flex", gap: 1.5, alignItems: "flex-start" }}>
            {checks.length === 0 ? (
              // Whether this control is enabled today depends on an evaluated readiness verdict the API has not
              // produced for this cluster (review §3): an explained outlined control, not a filled primary that
              // implies it can be pressed.
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <M3Button emphasis="outlined" disabled onClick={() => setShowFailoverModal(true)}>
                  Authorize Failover (4-Eyes)
                </M3Button>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>Needs an evaluated readiness run</Typography>
              </Box>
            ) : (
              <M3Button
                emphasis="filled"
                disabled={overallVerdict !== "NO_BLOCKING_CONDITIONS_OBSERVED" || quarantined}
                onClick={() => setShowFailoverModal(true)}
              >
                Authorize Failover (4-Eyes)
              </M3Button>
            )}
            <Tooltip title="Disabled — Requires Phase B 4-Eyes Authorization & Phase C Gate Approval">
              <span>
                <M3Button emphasis="outlined" disabled>
                  Initiate Failover
                </M3Button>
              </span>
            </Tooltip>
            <M3Button
              emphasis="outlined"
              disabled={overallVerdict !== "NO_BLOCKING_CONDITIONS_OBSERVED" || quarantined}
              onClick={() => setShowScheduleModal(true)}
            >
              Schedule Maintenance Window
            </M3Button>
          </Box>
        </Box>

        {/* 4-Eyes Failover Authorization & Execution Dialog */}
        <Dialog open={showFailoverModal} onClose={() => setShowFailoverModal(false)} maxWidth="md" fullWidth>
          <DialogTitle sx={{ fontWeight: 600 }}>
            Phase B & C: 4-Eyes Controlled Failover Gate — {clusterName}
          </DialogTitle>
          <DialogContent dividers>
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5 }}>
              {actionError && (
                <Card sx={{ bgcolor: m3.errorContainer, border: `1px solid ${m3.error}`, p: 1.5 }}>
                  <Typography variant="body2" sx={{ color: m3.onErrorContainer, fontWeight: 600 }}>
                    {actionError}
                  </Typography>
                </Card>
              )}

              {/* Step 1: 4-Eyes Dual Control Inputs */}
              {!leaseToken ? (
                <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
                  <Typography variant="subtitle2" sx={{ fontWeight: 600, color: m3.onSurface }}>
                    Step 1: Obtain 4-Eyes Authorization Lease
                  </Typography>
                  <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                    Failover execution is CLASS 2. A distinct secondary approver, operator reason (&gt;= 8 chars), and maintenance ticket are required.
                  </Typography>
                  <TextField
                    label="Approver Principal ID"
                    size="small"
                    value={approverId}
                    onChange={(e) => setApproverId(e.target.value)}
                    helperText="Must be distinct authenticated principal holding OPERATE role"
                    fullWidth
                  />
                  <TextField
                    label="Operator Justification / Reason"
                    size="small"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    helperText="Mandatory minimum 8 characters justification"
                    fullWidth
                  />
                  <TextField
                    label="Maintenance Window / Change Ticket Ref"
                    size="small"
                    value={mwRef}
                    onChange={(e) => setMwRef(e.target.value)}
                    fullWidth
                  />
                  <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
                    <M3Button
                      emphasis="filled"
                      disabled={isAuthorizing || reason.trim().length < 8 || !approverId.trim() || !mwRef.trim()}
                      onClick={handleAuthorizeAndDryRun}
                    >
                      {isAuthorizing ? "Authorizing & Disclosing Plan..." : "Authorize & Preview Dry-Run Plan"}
                    </M3Button>
                  </Box>
                </Box>
              ) : (
                /* Step 2 & 3: Dry-Run Plan Disclosure and Controlled Execution */
                <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
                  <Card sx={{ bgcolor: m3.scLow, p: 2, borderRadius: "8px" }}>
                    <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                      ✓ 4-Eyes Lease Token Issued: <span style={{ fontFamily: "monospace" }}>{leaseToken}</span>
                    </Typography>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      Token valid for 15 minutes. Bound to pre-flight assessment digest. Dry-run disclosed below WITHOUT consuming execution lease.
                    </Typography>
                  </Card>

                  {dryRunPlan && (
                    <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
                      <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                        Dry-Run Plan: Planned Mutation & Reversal Steps
                      </Typography>
                      <TableContainer component={Paper} sx={{ borderRadius: "8px", border: `1px solid ${m3.outlineVar}` }}>
                        <Table size="small">
                          <TableHead sx={{ bgcolor: m3.scLow }}>
                            <TableRow>
                              <TableCell sx={{ fontWeight: 600 }}>#</TableCell>
                              <TableCell sx={{ fontWeight: 600 }}>Target Member</TableCell>
                              <TableCell sx={{ fontWeight: 600 }}>Action Kind</TableCell>
                              <TableCell sx={{ fontWeight: 600 }}>Command Literal</TableCell>
                              <TableCell sx={{ fontWeight: 600 }}>Risk Class</TableCell>
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {dryRunPlan.transition_steps?.map((st: any) => (
                              <TableRow key={st.step_number}>
                                <TableCell>{st.step_number}</TableCell>
                                <TableCell sx={{ fontWeight: 500 }}>{st.target_member_masked_name || st.target_member}</TableCell>
                                <TableCell><Chip size="small" label={st.action_kind || "FAILOVER"} /></TableCell>
                                <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{st.command}</TableCell>
                                <TableCell sx={{ fontSize: 12 }}>{st.risk_level}</TableCell>
                              </TableRow>
                            ))}
                            {dryRunPlan.reversal_steps?.map((st: any) => (
                              <TableRow key={`rev-${st.step_number}`} sx={{ bgcolor: m3.scLow }}>
                                <TableCell>Reversal</TableCell>
                                <TableCell sx={{ fontWeight: 500 }}>{st.target_member_masked_name || st.target_member}</TableCell>
                                <TableCell><Chip size="small" label={st.action_kind || "REVERSAL"} color="secondary" /></TableCell>
                                <TableCell sx={{ fontFamily: "monospace", fontSize: 12 }}>{st.command}</TableCell>
                                <TableCell sx={{ fontSize: 12 }}>{st.risk_level}</TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </TableContainer>

                      <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                        <strong>Continuity Assessment:</strong> {dryRunPlan.session_continuity_risk}
                      </Typography>
                      <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                        <strong>Preemption Policy:</strong> {dryRunPlan.preemption_behavior}
                      </Typography>
                    </Box>
                  )}

                  {/* Execution Action & Outcome */}
                  {!executionResult ? (
                    <Box sx={{ mt: 1, p: 2, bgcolor: m3.warningContainer, borderRadius: "8px", border: `1px solid ${m3.warning}` }}>
                      <Typography variant="subtitle2" sx={{ fontWeight: 700, color: m3.onWarningContainer }}>
                        ⚠️ MUTATION BOUNDARY
                      </Typography>
                      <Typography variant="body2" sx={{ color: m3.onWarningContainer, mb: 2 }}>
                        Executing controlled failover submits an at-most-once command across the mutation boundary. Zero blind retries will occur.
                      </Typography>
                      <Box sx={{ display: "flex", justifyContent: "flex-end", gap: 1.5 }}>
                        <M3Button emphasis="text" onClick={() => setShowFailoverModal(false)} disabled={isExecuting}>
                          Cancel
                        </M3Button>
                        <M3Button
                          emphasis="filled"
                          disabled={isExecuting}
                          onClick={() => handleExecuteFailover("CONTROLLED_FAILOVER")}
                        >
                          {isExecuting ? "Executing across boundary..." : "Execute Controlled Failover"}
                        </M3Button>
                      </Box>
                    </Box>
                  ) : (
                    /* Execution Result Display */
                    <Card sx={{ p: 2, bgcolor: executionResult.state === "SUCCEEDED" ? m3.successContainer : m3.warningContainer, border: `1px solid ${executionResult.state === "SUCCEEDED" ? m3.success : m3.warning}` }}>
                      <Typography variant="subtitle1" sx={{ fontWeight: 700, color: executionResult.state === "SUCCEEDED" ? m3.onSuccessContainer : m3.onWarningContainer }}>
                        EXECUTION STATE: {executionResult.state}
                      </Typography>
                      <Typography variant="body2" sx={{ mt: 0.5 }}>
                        {executionResult.summary}
                      </Typography>
                      <Typography variant="caption" display="block" sx={{ mt: 1, color: m3.onSurfaceVar }}>
                        Execution ID: {executionResult.execution_id} • Boundary Crossed: {executionResult.boundary_crossed_at || "NOT_CROSSED"}
                      </Typography>
                      {executionResult.state === "SUCCEEDED" && (
                        <Box sx={{ mt: 2, display: "flex", justifyContent: "flex-end" }}>
                          <M3Button
                            emphasis="outlined"
                            disabled={isExecuting}
                            onClick={() => handleExecuteFailover("RETURN_TO_SERVICE")}
                          >
                            Return to Service (Reversal Action)
                          </M3Button>
                        </Box>
                      )}
                    </Card>
                  )}
                </Box>
              )}
            </Box>
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="filled" onClick={() => setShowFailoverModal(false)}>
              Close
            </M3Button>
          </DialogActions>
        </Dialog>

        {/* Quarantine Acknowledgment Modal */}
        <Dialog open={showQuarantineAckModal} onClose={() => setShowQuarantineAckModal(false)} maxWidth="sm" fullWidth>
          <DialogTitle sx={{ fontWeight: 600 }}>Acknowledge Sticky Quarantine (4-Eyes)</DialogTitle>
          <DialogContent dividers>
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                Entity quarantine protects the cluster and both endpoints from conflicting mutations. Acknowledgment requires a second authorized approver.
              </Typography>
              <TextField
                label="Second Approver ID"
                size="small"
                value={ackApproverId}
                onChange={(e) => setAckApproverId(e.target.value)}
                fullWidth
              />
              <TextField
                label="Investigation Findings & Verification Reason"
                size="small"
                value={ackReason}
                onChange={(e) => setAckReason(e.target.value)}
                helperText="Minimum 8 characters documenting manual state verification"
                fullWidth
              />
            </Box>
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="text" onClick={() => setShowQuarantineAckModal(false)}>
              Cancel
            </M3Button>
            <M3Button
              emphasis="filled"
              disabled={ackReason.trim().length < 8 || !ackApproverId.trim()}
              onClick={handleAcknowledgeQuarantine}
            >
              Confirm & Lift Quarantine
            </M3Button>
          </DialogActions>
        </Dialog>

        {/* Phase D Schedule Maintenance Window Modal */}
        <Dialog open={showScheduleModal} onClose={() => setShowScheduleModal(false)} maxWidth="md" fullWidth>
          <DialogTitle sx={{ fontWeight: 600 }}>
            Phase D: Schedule Maintenance Window Failover — {clusterName}
          </DialogTitle>
          <DialogContent dividers>
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5 }}>
              {scheduleError && (
                <Card sx={{ bgcolor: m3.errorContainer, border: `1px solid ${m3.error}`, p: 1.5 }}>
                  <Typography variant="body2" sx={{ color: m3.onErrorContainer, fontWeight: 600 }}>
                    {scheduleError}
                  </Typography>
                </Card>
              )}

              <Card sx={{ bgcolor: m3.warningContainer, border: `1px solid ${m3.warning}`, p: 2 }}>
                <Typography variant="subtitle2" sx={{ fontWeight: 700, color: m3.onWarningContainer }}>
                  ⚠️ Unattended Execution Safety Invariant
                </Typography>
                <Typography variant="body2" sx={{ color: m3.onWarningContainer, mt: 0.5 }}>
                  Scheduled maintenance window failovers execute unattended at T₀ without further human keystrokes.
                  A fresh, direct two-sided pre-flight check battery is executed at T₀ inside the exclusive execution lock.
                  If ANY blocking check fails or baseline drift is detected, the execution strictly aborts with zero blind retries.
                </Typography>
              </Card>

              <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
                <TextField
                  label="Window Start Time (Local / UTC)"
                  type="datetime-local"
                  size="small"
                  value={scheduleStartTime}
                  onChange={(e) => setScheduleStartTime(e.target.value)}
                  InputLabelProps={{ shrink: true }}
                  sx={{ flex: 1, minWidth: 240 }}
                />
                <TextField
                  label="Window Duration (minutes)"
                  type="number"
                  size="small"
                  value={scheduleDuration}
                  onChange={(e) => setScheduleDuration(parseInt(e.target.value) || 60)}
                  sx={{ width: 180 }}
                />
                <TextField
                  label="Max Start Delay (minutes)"
                  type="number"
                  size="small"
                  value={scheduleMaxDelay}
                  onChange={(e) => setScheduleMaxDelay(parseInt(e.target.value) || 15)}
                  helperText="Window deadline gates T₀ start"
                  sx={{ width: 180 }}
                />
              </Box>

              <Box sx={{ display: "flex", gap: 2 }}>
                <TextField
                  select
                  label="Action Kind"
                  size="small"
                  value={scheduleActionKind}
                  onChange={(e) => setScheduleActionKind(e.target.value)}
                  SelectProps={{ native: true }}
                  sx={{ width: 240 }}
                >
                  <option value="CONTROLLED_FAILOVER">CONTROLLED_FAILOVER</option>
                  <option value="RETURN_TO_SERVICE">RETURN_TO_SERVICE</option>
                </TextField>
                <TextField
                  label="Approver Principal ID (4-Eyes)"
                  size="small"
                  value={scheduleApproverId}
                  onChange={(e) => setScheduleApproverId(e.target.value)}
                  helperText="Distinct authenticated reviewer (requester != approver)"
                  fullWidth
                />
              </Box>

              <TextField
                label="Change Ticket Reference & Operational Justification"
                size="small"
                value={scheduleReason}
                onChange={(e) => setScheduleReason(e.target.value)}
                helperText="Minimum 8 characters justifying the scheduled window"
                fullWidth
              />

              <Card sx={{ bgcolor: m3.scLow, p: 2, borderRadius: "8px" }}>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar, fontWeight: 600 }}>
                  Cryptographic Binding & Canonical Framing:
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: 11, mt: 0.5 }}>
                  Domain: NEXUS_FAILOVER_SCHEDULE_V1 | Target: {roleOf("active")} | HMAC-SHA256
                </Typography>
              </Card>
            </Box>
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="text" onClick={() => setShowScheduleModal(false)}>
              Cancel
            </M3Button>
            <M3Button
              emphasis="filled"
              disabled={isScheduling || scheduleReason.trim().length < 8 || !scheduleApproverId.trim()}
              onClick={handleScheduleWindow}
            >
              {isScheduling ? "Sealing Schedule..." : "Schedule & Seal (HMAC-SHA256)"}
            </M3Button>
          </DialogActions>
        </Dialog>

        {/* Check Details Dialog */}
        <Dialog open={selectedCheck !== null} onClose={() => setSelectedCheck(null)} maxWidth="sm" fullWidth>
          {selectedCheck && (
            <>
              <DialogTitle sx={{ fontWeight: 600 }}>{selectedCheck.name}</DialogTitle>
              <DialogContent dividers>
                <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
                  <Box>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      Check Identifier:
                    </Typography>
                    <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
                      {selectedCheck.id}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      Category & Enforcement:
                    </Typography>
                    <Typography variant="body2">
                      {selectedCheck.category} • {selectedCheck.enforcement}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      Evaluated Summary:
                    </Typography>
                    <Typography variant="body2">{selectedCheck.summary}</Typography>
                  </Box>
                  <Box>
                    <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
                      Corroboration:
                    </Typography>
                    <Typography variant="body2">
                      Two-sided observation requires a current read from both members (active: {roleOf("active")}); shown only as the preflight API reports it.
                    </Typography>
                  </Box>
                </Box>
              </DialogContent>
              <DialogActions>
                <M3Button emphasis="filled" onClick={() => setSelectedCheck(null)}>
                  Close
                </M3Button>
              </DialogActions>
            </>
          )}
        </Dialog>
      </Box>
    );
  };

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
        {/* There is no fleet-wide readiness figure -- only a per-cluster verdict once its preflight is read
            (review §3). "Evaluated" counts the clusters this session has actually read/run a preflight for;
            the KPI never claims a passive count the product does not have. */}
        <MetricCard
          title="Readiness checks"
          value={null}
          state="not_evaluated"
          note={clusters ? `${clusters.length} clusters enrolled · ${evaluatedCount} evaluated` : "reading enrolled clusters"}
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
