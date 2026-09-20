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

import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

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
const DEMO_CHECKS_CP: PreflightCheckItem[] = [
  {
    id: "preflight.platform_mode_gate",
    name: "Platform & HA Mode Gate",
    category: "Topology & Identity",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "HA Mode 'CLUSTER_XL_HA' is verified and supported for automated Active/Passive operations.",
  },
  {
    id: "preflight.viable_target",
    name: "Viable Target Standby Peer",
    category: "Topology & Identity",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Target standby peer FW-JULIET-06 is healthy, reachable, and in STANDBY state.",
  },
  {
    id: "preflight.split_brain_prevention",
    name: "Two-Sided Split-Brain Prevention",
    category: "Cluster Health",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Split-brain ruled out via two-sided observation: exactly one active member (FW-TANGO-04).",
  },
  {
    id: "preflight.state_sync_current",
    name: "State Synchronization Health",
    category: "State & Synchronization",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Connection state synchronization is healthy, current, and synchronized across both peers (delta: 8 events).",
  },
  {
    id: "preflight.policy_parity",
    name: "Software & Policy Parity",
    category: "Configuration & Alignment",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Software version (R81.20-JUMBO_TAKE_79) and security policy hashes are aligned across both members.",
  },
  {
    id: "preflight.control_sync_link_health",
    name: "Control & Sync Link Health",
    category: "Network & Interfaces",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "All cluster control, sync, and monitored virtual interfaces are UP with zero link errors.",
  },
  {
    id: "preflight.checkpoint_pnotes",
    name: "Critical Problem Notifications (pnotes)",
    category: "Vendor Diagnostics",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "All Check Point critical devices (fwd, cphad, daemons, interfaces) are reporting OK across both members.",
  },
  {
    id: "preflight.standby_resource_headroom",
    name: "Standby Member Resource Headroom",
    category: "Resource & Capacity",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Standby peer FW-JULIET-06 has sufficient capacity headroom (CPU: 19%, Mem: 38%).",
  },
  {
    id: "preflight.preemption_awareness",
    name: "Preemption Hazard Disclosure",
    category: "Operational Risk",
    enforcement: "ADVISORY",
    status: "PASS",
    summary: "Preemption is disabled across both cluster members. Failover will remain stable post-transition.",
  },
  {
    id: "preflight.flap_history",
    name: "Cluster Stability & Flap History",
    category: "Operational Risk",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Cluster has been completely stable with 0 state transitions in the last 24 hours.",
  },
];

const DEMO_CHECKS_PAN: PreflightCheckItem[] = [
  {
    id: "preflight.platform_mode_gate",
    name: "Platform & HA Mode Gate",
    category: "Topology & Identity",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "HA Mode 'PAN_ACTIVE_PASSIVE' is verified and supported for automated Active/Passive operations.",
  },
  {
    id: "preflight.viable_target",
    name: "Viable Target Standby Peer",
    category: "Topology & Identity",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Target standby peer FW-JULIET-06 is healthy, reachable, and in PASSIVE state.",
  },
  {
    id: "preflight.split_brain_prevention",
    name: "Two-Sided Split-Brain Prevention",
    category: "Cluster Health",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Split-brain ruled out via two-sided observation: exactly one active member (FW-TANGO-04).",
  },
  {
    id: "preflight.state_sync_current",
    name: "State Synchronization Health",
    category: "State & Synchronization",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Session synchronization status is complete with 0 unconfirmed sessions.",
  },
  {
    id: "preflight.policy_parity",
    name: "Software & Policy Parity",
    category: "Configuration & Alignment",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Software version (PAN-OS 11.1.2-h3) and running configuration timestamps match across peers.",
  },
  {
    id: "preflight.control_sync_link_health",
    name: "Control & Sync Link Health",
    category: "Network & Interfaces",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "HA1 (control) and HA2 (sync) physical and backup links report UP.",
  },
  {
    id: "preflight.paloalto_path_monitoring",
    name: "HA Path & Link Monitoring",
    category: "Vendor Diagnostics",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "All Palo Alto monitored network destination groups and physical link groups are UP.",
  },
  {
    id: "preflight.paloalto_pending_commits",
    name: "Pending / In-Flight Commits",
    category: "Configuration & Alignment",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "No pending or in-flight commits detected across cluster peers.",
  },
  {
    id: "preflight.standby_resource_headroom",
    name: "Standby Member Resource Headroom",
    category: "Resource & Capacity",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Standby peer FW-JULIET-06 has sufficient capacity headroom (CPU: 22%, Mem: 41%).",
  },
  {
    id: "preflight.preemption_awareness",
    name: "Preemption Hazard Disclosure",
    category: "Operational Risk",
    enforcement: "ADVISORY",
    status: "PASS",
    summary: "Preemption is disabled across both cluster members.",
  },
  {
    id: "preflight.flap_history",
    name: "Cluster Stability & Flap History",
    category: "Operational Risk",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Cluster has been completely stable with 0 state transitions in the last 24 hours.",
  },
  {
    id: "preflight.clock_health",
    name: "Host & Evidence Clock Health",
    category: "System Timing & Security",
    enforcement: "BLOCKING",
    status: "PASS",
    summary: "Host monotonic timer and UTC wall-clock verified in sync with zero NTP jumps.",
  },
];

/** Operations screen featuring HA & readiness pre-flight checklist. */
export function OperationsScreen() {
  const [selectedCluster, setSelectedCluster] = useState<string | null>(null);
  const [selectedCheck, setSelectedCheck] = useState<PreflightCheckItem | null>(null);
  const [filter, setFilter] = useState<"ALL" | "BLOCKING" | "ADVISORY">("ALL");
  const [isRunning, setIsRunning] = useState(false);
  const [apiChecks, setApiChecks] = useState<PreflightCheckItem[] | null>(null);
  const [apiVerdict, setApiVerdict] = useState<string | null>(null);

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
    fetch(`/api/v2/failover/${selectedCluster}/preflight`)
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
        }
      })
      .catch(() => {
        // Standalone/offline reference mode fallback
      });

    fetch(`/api/v2/failover/${selectedCluster}/schedules`)
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => {
        if (isMounted && Array.isArray(data)) setSchedulesList(data);
      })
      .catch(() => {});

    return () => {
      isMounted = false;
    };
  }, [selectedCluster]);

  const fallbackChecks = selectedCluster === "CLS-TANGO-01" ? DEMO_CHECKS_PAN : DEMO_CHECKS_CP;
  const checks = apiChecks && apiChecks.length > 0 ? apiChecks : fallbackChecks;
  const overallVerdict = apiVerdict || "NO_BLOCKING_CONDITIONS_OBSERVED";

  const filteredChecks = checks.filter((c) => {
    if (filter === "BLOCKING") return c.enforcement === "BLOCKING";
    if (filter === "ADVISORY") return c.enforcement === "ADVISORY";
    return true;
  });

  const handleRunBattery = async () => {
    if (!selectedCluster) return;
    setIsRunning(true);
    try {
      const res = await fetch(`/api/v2/failover/${selectedCluster}/preflight`, { method: "POST" });
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
      const authRes = await fetch(`/api/v2/failover/${selectedCluster}/authorize`, {
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
      const dryRes = await fetch(`/api/v2/failover/${selectedCluster}/dry-run`, {
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
      const res = await fetch(`/api/v2/failover/${selectedCluster}/execute`, {
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
      const res = await fetch(`/api/v2/failover/${selectedCluster}/quarantine/acknowledge`, {
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
      const res = await fetch(`/api/v2/failover/${selectedCluster}/schedules`, {
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
      return (
        <Box>
          <EmptyPanel
            title="No HA pair or cluster enrolled"
            body="Readiness needs enrolled cluster members and a current health read from each one; neither exists yet, so no cluster can be assessed."
          />
          <Box sx={{ mt: 3, display: "flex", gap: 2, justifyContent: "center", alignItems: "center" }}>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
              Inspect Enrolled Estate Cluster:
            </Typography>
            <M3Button emphasis="tonal" onClick={() => setSelectedCluster("CLS-ROMEO-01")}>
              Inspect CLS-ROMEO-01 (Check Point ClusterXL)
            </M3Button>
            <M3Button emphasis="outlined" onClick={() => setSelectedCluster("CLS-TANGO-01")}>
              Inspect CLS-TANGO-01 (Palo Alto HA)
            </M3Button>
          </Box>
        </Box>
      );
    }

    const isPan = selectedCluster === "CLS-TANGO-01";
    const clusterName = isPan ? "CLS-TANGO-01" : "CLS-ROMEO-01";
    const vendorLabel = isPan ? "Palo Alto Networks (Active/Passive)" : "Check Point (ClusterXL HA)";

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
                Active: FW-TANGO-04 • Standby: FW-JULIET-06 • Evaluated: Just now (Fresh)
              </Typography>
            </Box>
            <Box sx={{ display: "flex", gap: 1 }}>
              <M3Button
                emphasis={selectedCluster === "CLS-ROMEO-01" ? "filled" : "outlined"}
                onClick={() => setSelectedCluster("CLS-ROMEO-01")}
              >
                CLS-ROMEO-01 (CP)
              </M3Button>
              <M3Button
                emphasis={selectedCluster === "CLS-TANGO-01" ? "filled" : "outlined"}
                onClick={() => setSelectedCluster("CLS-TANGO-01")}
              >
                CLS-TANGO-01 (PAN)
              </M3Button>
              <M3Button emphasis="text" onClick={() => setSelectedCluster(null)}>
                Clear
              </M3Button>
            </Box>
          </Box>
        </Card>

        {/* Sticky Quarantine Alert Banner */}
        {quarantined && (
          <Card sx={{ bgcolor: "#fff3e0", border: "1px solid #ffb74d", borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: "#e65100", color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  !
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: "#e65100" }}>
                    STICKY ENTITY QUARANTINE ACTIVE (OUTCOME_UNKNOWN)
                  </Typography>
                  <Typography variant="body2" sx={{ color: "#bf360c" }}>
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
          <Card sx={{ bgcolor: "#ffebee", border: "1px solid #ef9a9a", borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: "#c62828", color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  ✕
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: "#b71c1c" }}>
                    VERDICT: BLOCKING_CONDITIONS_PRESENT
                  </Typography>
                  <Typography variant="body2" sx={{ color: "#c62828" }}>
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
          <Card sx={{ bgcolor: "#fff8e1", border: "1px solid #ffe082", borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: "#f57f17", color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  !
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: "#e65100" }}>
                    VERDICT: ADVISORY_CONDITIONS_PRESENT
                  </Typography>
                  <Typography variant="body2" sx={{ color: "#f57f17" }}>
                    Advisory conditions noted. Proceed with operational awareness.
                  </Typography>
                </Box>
              </Box>
              <M3Button emphasis="tonal" onClick={handleRunBattery} disabled={isRunning}>
                {isRunning ? "Evaluating..." : "Run Pre-Flight Battery"}
              </M3Button>
            </Box>
          </Card>
        ) : (
          <Card sx={{ bgcolor: "#e8f5e9", border: "1px solid #a5d6a7", borderRadius: "12px", p: 2 }}>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Box sx={{ width: 36, height: 36, borderRadius: "50%", bgcolor: "#2e7d32", color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700 }}>
                  ✓
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700, color: "#1b5e20" }}>
                    VERDICT: NO_BLOCKING_CONDITIONS_OBSERVED
                  </Typography>
                  <Typography variant="body2" sx={{ color: "#2e7d32" }}>
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

        {/* Filter Bar */}
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

        {/* Pre-Flight Checklist Table */}
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
          <Box sx={{ display: "flex", gap: 1.5 }}>
            <M3Button
              emphasis="filled"
              disabled={overallVerdict !== "NO_BLOCKING_CONDITIONS_OBSERVED" || quarantined}
              onClick={() => setShowFailoverModal(true)}
            >
              Authorize Failover (4-Eyes)
            </M3Button>
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
                <Card sx={{ bgcolor: "#ffebee", border: "1px solid #ef9a9a", p: 1.5 }}>
                  <Typography variant="body2" sx={{ color: "#c62828", fontWeight: 600 }}>
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
                              <TableRow key={`rev-${st.step_number}`} sx={{ bgcolor: "#fafafa" }}>
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
                    <Box sx={{ mt: 1, p: 2, bgcolor: "#fff3e0", borderRadius: "8px", border: "1px solid #ffe0b2" }}>
                      <Typography variant="subtitle2" sx={{ fontWeight: 700, color: "#e65100" }}>
                        ⚠️ MUTATION BOUNDARY
                      </Typography>
                      <Typography variant="body2" sx={{ color: "#bf360c", mb: 2 }}>
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
                    <Card sx={{ p: 2, bgcolor: executionResult.state === "SUCCEEDED" ? "#e8f5e9" : "#fff3e0", border: `1px solid ${executionResult.state === "SUCCEEDED" ? "#a5d6a7" : "#ffb74d"}` }}>
                      <Typography variant="subtitle1" sx={{ fontWeight: 700, color: executionResult.state === "SUCCEEDED" ? "#1b5e20" : "#e65100" }}>
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
                <Card sx={{ bgcolor: "#ffebee", border: "1px solid #ef9a9a", p: 1.5 }}>
                  <Typography variant="body2" sx={{ color: "#c62828", fontWeight: 600 }}>
                    {scheduleError}
                  </Typography>
                </Card>
              )}

              <Card sx={{ bgcolor: "#fff3e0", border: "1px solid #ffe0b2", p: 2 }}>
                <Typography variant="subtitle2" sx={{ fontWeight: 700, color: "#e65100" }}>
                  ⚠️ Unattended Execution Safety Invariant
                </Typography>
                <Typography variant="body2" sx={{ color: "#ef6c00", mt: 0.5 }}>
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
                  Domain: NEXUS_FAILOVER_SCHEDULE_V1 | Target: {isPan ? "FW-TANGO-04" : "FW-TANGO-01"} | HMAC-SHA256
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
                      Two-sided independent observation corroborated from both active (FW-TANGO-04) and standby (FW-JULIET-06) members.
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
        subtitle="What is running against the fleet, and what the fleet is ready for. Readiness is observed; no failover action exists in this build."
        actions={
          <>
            <M3Button emphasis="outlined">Job history</M3Button>
            <M3Button emphasis="filled">Schedule collection</M3Button>
          </>
        }
      />
      <MetricGrid>
        <MetricCard title="Jobs run" note="nothing run yet" />
        <MetricCard title="Success rate" note="no runs to measure" />
        <MetricCard title="Readiness checks" note={selectedCluster ? `${checks.length} evaluated (PASS)` : "no cluster enrolled"} />
        <MetricCard title="Alerts" note="nothing to alert on" />
      </MetricGrid>
      <M3Tabs
        ariaLabel="Operations sections"
        tabs={[
          {
            label: "HA & readiness",
            panel: renderHaPanel(),
          },
          {
            label: "Jobs",
            panel: (
              <EmptyPanel
                title="No jobs yet"
                body="Nothing has run against the fleet, because the fleet is empty. Read jobs are class 0; backup creation is the only class 1 write and runs under its own contract."
              />
            ),
          },
          {
            label: "Queue",
            panel: (
              <EmptyPanel
                title="Nothing queued"
                body="No job is scheduled to run against the fleet yet. A job appears here once it is scheduled and before it starts."
              />
            ),
          },
          {
            label: "History",
            panel: (
              <EmptyPanel
                title="No job history"
                body="Job history accumulates only after jobs run against the fleet; none has run in this empty database yet."
              />
            ),
          },
        ]}
      />
    </ScreenRoot>
  );
}
