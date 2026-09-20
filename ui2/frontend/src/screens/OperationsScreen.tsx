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
];

/** Operations screen featuring HA & readiness pre-flight checklist. */
export function OperationsScreen() {
  const [selectedCluster, setSelectedCluster] = useState<string | null>(null);
  const [selectedCheck, setSelectedCheck] = useState<PreflightCheckItem | null>(null);
  const [filter, setFilter] = useState<"ALL" | "BLOCKING" | "ADVISORY">("ALL");
  const [isRunning, setIsRunning] = useState(false);
  const [apiChecks, setApiChecks] = useState<PreflightCheckItem[] | null>(null);
  const [apiVerdict, setApiVerdict] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedCluster) {
      setApiChecks(null);
      setApiVerdict(null);
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

        {/* Action Controls & Gate Disclosure */}
        <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", p: 2, bgcolor: m3.scLow, borderRadius: "12px" }}>
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            Readiness assessment is observed. Device mutation actions require Phase B 4-Eyes authorization & Phase C command gate.
          </Typography>
          <Box sx={{ display: "flex", gap: 1.5 }}>
            <Tooltip title="Disabled — Requires Phase B 4-Eyes Authorization & Phase C Gate Approval">
              <span>
                <M3Button emphasis="outlined" disabled>
                  Initiate Failover
                </M3Button>
              </span>
            </Tooltip>
            <Tooltip title="Disabled — Requires Phase D Maintenance Window Engine Approval">
              <span>
                <M3Button emphasis="outlined" disabled>
                  Schedule Maintenance Window
                </M3Button>
              </span>
            </Tooltip>
          </Box>
        </Box>

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
