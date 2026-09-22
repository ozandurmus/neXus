import { useState, useMemo, useEffect, useCallback } from "react";
import { urlParam } from "../shell/urlParams";

/** Overview link values -> this screen's filter values (framework names are matched by keyword). */
function frameworkFromUrl(name: string | null): string {
  const n = (name ?? "").toUpperCase();
  if (n.includes("CIS")) return "CIS";
  if (n.includes("PCI")) return "PCI";
  if (n.includes("NIST")) return "NIST";
  if (n.includes("FINANCIAL")) return "FINANCIAL";
  return "ALL";
}
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Chip from "@mui/material/Chip";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import InputBase from "@mui/material/InputBase";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { ScreenHeader, MetricGrid, ScreenRoot } from "../shell/ScreenLayout";
import { Icon } from "../shell/Icon";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import {
  getComplianceOverview,
  getComplianceControls,
  triggerComplianceEvaluation,
  type ComplianceOverview,
  type ComplianceControlItem,
} from "../auth/adminApi";

type StatusFilter = "ALL" | "FAILING" | "UNAVAILABLE" | "PASSING";

function ComplianceMetricCard({
  title,
  count,
  note,
  badge,
}: {
  readonly title: string;
  readonly count: string;
  readonly note: string;
  readonly badge?: { readonly label: string; readonly tone: "ok" | "warn" | "bad" | "neutral" };
}) {
  const badgeBg =
    badge?.tone === "ok"
      ? m3.successContainer
      : badge?.tone === "warn"
      ? m3.warningContainer
      : badge?.tone === "bad"
      ? m3.errorContainer
      : m3.scHigh;
  const badgeFg =
    badge?.tone === "ok"
      ? m3.onSuccessContainer
      : badge?.tone === "warn"
      ? m3.onWarningContainer
      : badge?.tone === "bad"
      ? m3.onErrorContainer
      : m3.onSurfaceVar;

  return (
    <Card
      sx={{
        bgcolor: m3.scLowest,
        boxShadow: m3.e1,
        borderRadius: "16px",
        p: 2.5,
        display: "flex",
        flexDirection: "column",
        gap: 1.25,
        border: "1px solid",
        borderColor: m3.outlineVar,
      }}
    >
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography sx={{ fontSize: 14, fontWeight: 500, color: m3.onSurfaceVar }}>{title}</Typography>
        {badge && (
          <Chip
            size="small"
            label={badge.label}
            sx={{ bgcolor: badgeBg, color: badgeFg, fontWeight: 600, fontSize: 11, height: 22 }}
          />
        )}
      </Stack>
      <Typography variant="h1" sx={{ color: m3.onSurface }}>{count}</Typography>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{note}</Typography>
    </Card>
  );
}

export function ComplianceScreen() {
  const [overview, setOverview] = useState<ComplianceOverview | null>(null);
  const [controls, setControls] = useState<readonly ComplianceControlItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [reEvaluating, setReEvaluating] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(() =>
    urlParam("result") === "fail" ? "FAILING" : urlParam("result") === "unavailable" ? "UNAVAILABLE" : "ALL");
  const [severityFilter, setSeverityFilter] = useState<string>(() => (urlParam("severity") ? urlParam("severity")!.toUpperCase() : "ALL"));
  const [frameworkFilter, setFrameworkFilter] = useState<string>(() => frameworkFromUrl(urlParam("framework")));
  const [selectedControl, setSelectedControl] = useState<ComplianceControlItem | null>(null);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [ov, ctrlRes] = await Promise.all([
        getComplianceOverview(),
        getComplianceControls(),
      ]);
      setOverview(ov);
      setControls(ctrlRes.controls ?? []);
    } catch (e) {
      console.error("Failed to load compliance data", e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleReEvaluate = async () => {
    try {
      setReEvaluating(true);
      await triggerComplianceEvaluation();
      await loadData();
    } catch (e) {
      console.error("Re-evaluation failed", e);
    } finally {
      setReEvaluating(false);
    }
  };

  const filteredControls = useMemo(() => {
    return controls.filter((c) => {
      // Search
      const q = searchQuery.toLowerCase().trim();
      if (q) {
        const inTitle = c.title.toLowerCase().includes(q);
        const inId = c.control_id.toLowerCase().includes(q);
        const inDesc = c.description.toLowerCase().includes(q);
        const inFw = c.frameworks?.some((f) => {
          const ref = f.reference || f.clauseId || "";
          const fw = f.framework || "";
          return ref.toLowerCase().includes(q) || fw.toLowerCase().includes(q);
        });
        if (!inTitle && !inId && !inDesc && !inFw) return false;
      }

      // Status
      if (statusFilter === "FAILING" && c.status !== "FAIL") return false;
      if (statusFilter === "UNAVAILABLE" && c.status !== "DATA_UNAVAILABLE") return false;
      if (statusFilter === "PASSING" && c.status !== "PASS") return false;

      // Severity
      if (severityFilter !== "ALL" && c.severity !== severityFilter) return false;

      // Framework
      if (frameworkFilter !== "ALL") {
        const hasFw = c.frameworks?.some((f) => f.framework.toUpperCase().includes(frameworkFilter));
        if (!hasFw) return false;
      }

      return true;
    });
  }, [controls, searchQuery, statusFilter, severityFilter, frameworkFilter]);

  const failingCount = useMemo(() => controls.filter((c) => c.status === "FAIL").length, [controls]);
  const unavailCount = useMemo(() => controls.filter((c) => c.status === "DATA_UNAVAILABLE").length, [controls]);
  const passingCount = useMemo(() => controls.filter((c) => c.status === "PASS").length, [controls]);

  const subtitle =
    overview && overview.evaluated_firewalls > 0
      ? "CIS Benchmark · PCI-DSS v4.0.1 · NIST SP 800-53 · Financial Baseline"
      : "No framework assigned · nothing assessed yet";

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Compliance & Security Posture"
        subtitle={subtitle}
        actions={
          <Stack direction="row" spacing={1.5}>
            <M3Button
              emphasis="outlined"
              onClick={handleReEvaluate}
              disabled={reEvaluating || loading}
            >
              {reEvaluating ? "Evaluating..." : "Re-evaluate"}
            </M3Button>
            <M3Button emphasis="filled" icon="download">
              Export Audit Report
            </M3Button>
          </Stack>
        }
      />

      {/* 4 Top KPI Cards */}
      <MetricGrid>
        <ComplianceMetricCard
          title="Assured Compliance"
          count={overview ? `${overview.assured_compliance_pct}%` : "0%"}
          badge={{
            label: !overview ? "Loading" : overview.assured_compliance_pct >= 70 ? "High Assurance" : "Improvement Needed",
            tone: overview && overview.assured_compliance_pct >= 70 ? "ok" : "warn",
          }}
          note="PASS / Total Assigned Controls"
        />
        <ComplianceMetricCard
          title="Evidence Coverage"
          count={overview ? `${overview.evidence_coverage_pct}%` : "0%"}
          badge={{
            label: overview ? `${overview.evaluated_firewalls} Firewalls` : "0 Firewalls",
            tone: "neutral",
          }}
          note="Controls with collected evidence"
        />
        <ComplianceMetricCard
          title="Critical Deficiencies"
          count={overview ? String(overview.critical_deficiencies) : "0"}
          badge={{
            label: !overview ? "Loading" : overview.critical_deficiencies === 0 ? "Zero Critical" : "Immediate Action",
            tone: overview?.critical_deficiencies === 0 ? "ok" : "bad",
          }}
          note="High priority failing controls"
        />
        <ComplianceMetricCard
          title="Data Gaps"
          count={overview ? String(overview.data_gaps) : "0"}
          badge={{
            label: !overview ? "Loading" : overview.data_gaps > 0 ? "Missing Commands" : "Full Coverage",
            tone: overview && overview.data_gaps > 0 ? "warn" : "ok",
          }}
          note="Controls awaiting evidence collection"
        />
      </MetricGrid>

      {/* 4 Framework Cards */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(250px, 1fr))" }}>
        {overview?.frameworks.map((f) => (
          <Card
            key={f.framework}
            sx={{
              bgcolor: m3.scLowest,
              boxShadow: m3.e1,
              borderRadius: "16px",
              p: 2.25,
              display: "flex",
              flexDirection: "column",
              gap: 1,
              border: "1px solid",
              borderColor: m3.outlineVar,
            }}
          >
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Typography sx={{ fontSize: 15, fontWeight: 600, color: m3.onSurface }}>
                {f.framework}
              </Typography>
              <Chip
                size="small"
                label={`${f.score_pct}%`}
                sx={{
                  bgcolor: f.score_pct >= 70 ? m3.successContainer : m3.warningContainer,
                  color: f.score_pct >= 70 ? m3.onSuccessContainer : m3.onWarningContainer,
                  fontWeight: 600,
                  fontSize: 12,
                }}
              />
            </Stack>

            <LinearProgress
              variant="determinate"
              value={f.score_pct}
              sx={{
                height: 8,
                borderRadius: 4,
                bgcolor: m3.scHigh,
                "& .MuiLinearProgress-bar": {
                  bgcolor: f.score_pct >= 70 ? m3.success : m3.warning,
                  borderRadius: 4,
                },
              }}
            />

            <Stack direction="row" spacing={2} sx={{ mt: 0.5 }}>
              <Typography variant="body2" sx={{ color: m3.success, fontWeight: 500 }}>
                ✓ {f.pass_count} Passing
              </Typography>
              <Typography variant="body2" sx={{ color: m3.error, fontWeight: 500 }}>
                ✕ {f.fail_count} Failing
              </Typography>
              <Typography variant="body2" sx={{ color: m3.warning, fontWeight: 500 }}>
                ? {f.data_unavailable_count} Unavailable
              </Typography>
            </Stack>
          </Card>
        ))}
      </Box>

      {/* Filter and Search Bar */}
      <Card
        sx={{
          bgcolor: m3.scLowest,
          borderRadius: "16px",
          p: 2,
          boxShadow: m3.e1,
          border: "1px solid",
          borderColor: m3.outlineVar,
        }}
      >
        <Stack spacing={2}>
          <Stack direction={{ xs: "column", md: "row" }} spacing={2} alignItems="center">
            {/* Search Input */}
            <Box
              sx={{
                flex: 1,
                display: "flex",
                alignItems: "center",
                gap: 1,
                px: 1.5,
                py: 0.75,
                bgcolor: m3.scLow,
                border: "1px solid",
                borderColor: m3.outlineVar,
                borderRadius: "10px",
                width: "100%",
              }}
            >
              <Icon name="search" size={20} />
              <InputBase
                placeholder="Search controls (ID, title, PCI / CIS reference, keyword)..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                sx={{ flex: 1, fontSize: 14 }}
              />
              {searchQuery && (
                <IconButton size="small" onClick={() => setSearchQuery("")}>
                  ✕
                </IconButton>
              )}
            </Box>

            {/* Severity and Framework Dropdowns */}
            <Stack direction="row" spacing={1}>
              <select
                aria-label="Severity Filter"
                value={severityFilter}
                onChange={(e) => setSeverityFilter(e.target.value)}
                style={{
                  padding: "8px 12px",
                  borderRadius: "8px",
                  border: `1px solid ${m3.outlineVar}`,
                  backgroundColor: m3.scLow,
                  fontSize: 13,
                  fontWeight: 500,
                  color: m3.onSurface,
                }}
              >
                <option value="ALL">All Severities</option>
                <option value="CRITICAL">Critical</option>
                <option value="HIGH">High</option>
                <option value="MEDIUM">Medium</option>
                <option value="LOW">Low</option>
              </select>

              <select
                aria-label="Framework Filter"
                value={frameworkFilter}
                onChange={(e) => setFrameworkFilter(e.target.value)}
                style={{
                  padding: "8px 12px",
                  borderRadius: "8px",
                  border: `1px solid ${m3.outlineVar}`,
                  backgroundColor: m3.scLow,
                  fontSize: 13,
                  fontWeight: 500,
                  color: m3.onSurface,
                }}
              >
                <option value="ALL">All Frameworks</option>
                <option value="CIS">CIS Benchmark</option>
                <option value="PCI">PCI-DSS v4.0.1</option>
                <option value="NIST">NIST SP 800-53</option>
                <option value="FINANCIAL">Financial Baseline</option>
              </select>
            </Stack>
          </Stack>

          {/* Status Filter Pills */}
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            <Chip
              clickable
              label={`All (${controls.length})`}
              onClick={() => setStatusFilter("ALL")}
              sx={{
                bgcolor: statusFilter === "ALL" ? m3.primaryContainer : m3.scLow,
                color: statusFilter === "ALL" ? m3.onPrimaryContainer : m3.onSurface,
                fontWeight: statusFilter === "ALL" ? 600 : 400,
              }}
            />
            <Chip
              clickable
              label={`Failing (${failingCount})`}
              onClick={() => setStatusFilter("FAILING")}
              sx={{
                bgcolor: statusFilter === "FAILING" ? m3.errorContainer : m3.scLow,
                color: statusFilter === "FAILING" ? m3.onErrorContainer : m3.onSurface,
                fontWeight: statusFilter === "FAILING" ? 600 : 400,
              }}
            />
            <Chip
              clickable
              label={`Data Gaps (${unavailCount})`}
              onClick={() => setStatusFilter("UNAVAILABLE")}
              sx={{
                bgcolor: statusFilter === "UNAVAILABLE" ? m3.warningContainer : m3.scLow,
                color: statusFilter === "UNAVAILABLE" ? m3.onWarningContainer : m3.onSurface,
                fontWeight: statusFilter === "UNAVAILABLE" ? 600 : 400,
              }}
            />
            <Chip
              clickable
              label={`Passing (${passingCount})`}
              onClick={() => setStatusFilter("PASSING")}
              sx={{
                bgcolor: statusFilter === "PASSING" ? m3.successContainer : m3.scLow,
                color: statusFilter === "PASSING" ? m3.onSuccessContainer : m3.onSurface,
                fontWeight: statusFilter === "PASSING" ? 600 : 400,
              }}
            />
          </Stack>
        </Stack>
      </Card>

      {/* Controls Table */}
      <Card
        sx={{
          bgcolor: m3.scLowest,
          borderRadius: "16px",
          boxShadow: m3.e1,
          border: "1px solid",
          borderColor: m3.outlineVar,
          overflow: "hidden",
        }}
      >
        <TableContainer>
          <Table size="medium">
            <TableHead sx={{ bgcolor: m3.scLow }}>
              <TableRow>
                <TableCell sx={{ fontWeight: 600, fontSize: 13 }}>Control Code & Title</TableCell>
                <TableCell sx={{ fontWeight: 600, fontSize: 13, width: 110 }}>Severity</TableCell>
                <TableCell sx={{ fontWeight: 600, fontSize: 13, width: 100 }}>Firewalls</TableCell>
                <TableCell sx={{ fontWeight: 600, fontSize: 13, width: 170 }}>Posture Breakdown</TableCell>
                <TableCell sx={{ fontWeight: 600, fontSize: 13, width: 140 }}>Outcome</TableCell>
                <TableCell sx={{ fontWeight: 600, fontSize: 13, width: 90 }} align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {loading ? (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 6 }}>
                    <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                      Loading compliance controls...
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : filteredControls.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 6 }}>
                    <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                      No controls matching filter criteria.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                filteredControls.map((c) => (
                  <TableRow
                    key={c.control_id}
                    hover
                    onClick={() => setSelectedControl(c)}
                    sx={{ cursor: "pointer" }}
                  >
                    <TableCell>
                      <Stack spacing={0.5}>
                        <Typography sx={{ fontWeight: 600, fontSize: 14, color: m3.onSurface }}>
                          {c.title}
                        </Typography>
                        <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 0.5 }}>
                          <Typography variant="body2" sx={{ fontFamily: "monospace", color: m3.onSurfaceVar, fontSize: 11.5 }}>
                            {c.control_id}
                          </Typography>
                          {c.frameworks?.slice(0, 3).map((f, idx) => (
                            <Chip
                              key={f.reference || f.clauseId || idx}
                              size="small"
                              label={`${f.framework.replace("_", " ")} ${f.reference || f.clauseId || ""}`}
                              sx={{
                                height: 20,
                                fontSize: 10.5,
                                bgcolor: m3.secondaryContainer,
                                color: m3.onSecondaryContainer,
                              }}
                            />
                          ))}
                          {c.frameworks && c.frameworks.length > 3 && (
                            <Typography variant="body2" sx={{ fontSize: 11, color: m3.onSurfaceVar }}>
                              +{c.frameworks.length - 3} more
                            </Typography>
                          )}
                        </Stack>
                      </Stack>
                    </TableCell>

                    <TableCell>
                      <Chip
                        size="small"
                        label={c.severity}
                        sx={{
                          fontWeight: 600,
                          fontSize: 11,
                          height: 24,
                          bgcolor:
                            c.severity === "CRITICAL"
                              ? m3.errorContainer
                              : c.severity === "HIGH"
                              ? m3.attentionContainer
                              : c.severity === "MEDIUM"
                              ? m3.warningContainer
                              : m3.scHigh,
                          color:
                            c.severity === "CRITICAL"
                              ? m3.onErrorContainer
                              : c.severity === "HIGH"
                              ? m3.onAttentionContainer
                              : c.severity === "MEDIUM"
                              ? m3.onWarningContainer
                              : m3.onSurfaceVar,
                        }}
                      />
                    </TableCell>

                    <TableCell>
                      <Typography sx={{ fontSize: 13, fontWeight: 500 }}>
                        {c.target_device_count} {c.target_device_count === 1 ? "Firewall" : "Firewalls"}
                      </Typography>
                    </TableCell>

                    <TableCell>
                      {/* Segmented Stacked Bar (Green / Red / Amber) */}
                      <Stack spacing={0.5}>
                        <Box sx={{ display: "flex", height: 8, borderRadius: 4, overflow: "hidden", bgcolor: m3.scHigh }}>
                          {c.pass_count > 0 && (
                            <Box sx={{ flex: c.pass_count, bgcolor: m3.success }} title={`${c.pass_count} Passing`} />
                          )}
                          {c.fail_count > 0 && (
                            <Box sx={{ flex: c.fail_count, bgcolor: m3.error }} title={`${c.fail_count} Failing`} />
                          )}
                          {c.data_unavailable_count > 0 && (
                            <Box sx={{ flex: c.data_unavailable_count, bgcolor: m3.warning }} title={`${c.data_unavailable_count} Data Unavailable`} />
                          )}
                        </Box>
                        <Typography variant="body2" sx={{ fontSize: 11, color: m3.onSurfaceVar }}>
                          {c.compliance_pct}% Assurance
                        </Typography>
                      </Stack>
                    </TableCell>

                    <TableCell>
                      <Chip
                        size="small"
                        label={
                          c.status === "PASS"
                            ? "Compliant"
                            : c.status === "FAIL"
                            ? "Non-Compliant"
                            : "Data Unavailable"
                        }
                        sx={{
                          fontWeight: 600,
                          fontSize: 12,
                          bgcolor:
                            c.status === "PASS"
                              ? m3.successContainer
                              : c.status === "FAIL"
                              ? m3.errorContainer
                              : m3.warningContainer,
                          color:
                            c.status === "PASS"
                              ? m3.onSuccessContainer
                              : c.status === "FAIL"
                              ? m3.onErrorContainer
                              : m3.onWarningContainer,
                        }}
                      />
                    </TableCell>

                    <TableCell align="right">
                      <IconButton size="small" onClick={() => setSelectedControl(c)}>
                        →
                      </IconButton>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      {/* Control Detail Drawer */}
      <Drawer
        anchor="right"
        open={Boolean(selectedControl)}
        onClose={() => setSelectedControl(null)}
        PaperProps={{
          sx: {
            width: { xs: "100%", md: 540 },
            p: 3,
            bgcolor: m3.surface,
          },
        }}
      >
        {selectedControl && (
          <Stack spacing={2.5}>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
              <Stack spacing={0.5}>
                <Typography variant="h3" sx={{ color: m3.onSurface, fontWeight: 600 }}>
                  {selectedControl.title}
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: "monospace", color: m3.onSurfaceVar }}>
                  {selectedControl.control_id}
                </Typography>
              </Stack>
              <IconButton onClick={() => setSelectedControl(null)}>
                ✕
              </IconButton>
            </Stack>

            <Stack direction="row" spacing={1.5} alignItems="center">
              <Chip
                label={selectedControl.severity}
                sx={{
                  fontWeight: 600,
                  bgcolor:
                    selectedControl.severity === "CRITICAL"
                      ? m3.errorContainer
                      : selectedControl.severity === "HIGH"
                      ? m3.attentionContainer
                      : m3.warningContainer,
                  color:
                    selectedControl.severity === "CRITICAL"
                      ? m3.onErrorContainer
                      : selectedControl.severity === "HIGH"
                      ? m3.onAttentionContainer
                      : m3.onWarningContainer,
                }}
              />
              <Chip
                label={
                  selectedControl.status === "PASS"
                    ? "Compliant (PASS)"
                    : selectedControl.status === "FAIL"
                    ? "Non-Compliant (FAIL)"
                    : "Data Unavailable (DATA_UNAVAILABLE)"
                }
                sx={{
                  fontWeight: 600,
                  bgcolor:
                    selectedControl.status === "PASS"
                      ? m3.successContainer
                      : selectedControl.status === "FAIL"
                      ? m3.errorContainer
                      : m3.warningContainer,
                  color:
                    selectedControl.status === "PASS"
                      ? m3.onSuccessContainer
                      : selectedControl.status === "FAIL"
                      ? m3.onErrorContainer
                      : m3.onWarningContainer,
                }}
              />
            </Stack>

            {/* Missing Data Warning Alert if DATA_UNAVAILABLE */}
            {selectedControl.status === "DATA_UNAVAILABLE" && (
              <Card sx={{ bgcolor: m3.warningContainer, p: 2, borderRadius: "12px", border: "1px solid", borderColor: m3.warning }}>
                <Stack spacing={1}>
                  <Typography sx={{ fontWeight: 600, color: m3.onWarningContainer, fontSize: 14 }}>
                    ⚠ Data Unavailable / Command Not Collected
                  </Typography>
                  <Typography variant="body2" sx={{ color: m3.onWarningContainer, lineHeight: 1.5 }}>
                    {selectedControl.missing_reason ??
                      "The diagnostic command required for this control has not been collected from the firewall yet. The control has not been skipped; per audit integrity standards, it is reported as 'Data Unavailable'. When the command is added to the collection scope, it will be evaluated automatically."}
                  </Typography>
                </Stack>
              </Card>
            )}

            {/* Description & Security Rationale */}
            <Card sx={{ bgcolor: m3.scLowest, p: 2, borderRadius: "12px", border: "1px solid", borderColor: m3.outlineVar }}>
              <Typography sx={{ fontWeight: 600, fontSize: 13, mb: 1, color: m3.onSurface }}>
                Security Rationale & Audit Objective
              </Typography>
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar, lineHeight: 1.6 }}>
                {selectedControl.description}
              </Typography>
            </Card>

            {/* Regulatory Framework Mappings */}
            <Card sx={{ bgcolor: m3.scLowest, p: 2, borderRadius: "12px", border: "1px solid", borderColor: m3.outlineVar }}>
              <Typography sx={{ fontWeight: 600, fontSize: 13, mb: 1.5, color: m3.onSurface }}>
                Framework Mappings & Clauses
              </Typography>
              <Stack spacing={1}>
                {selectedControl.frameworks?.map((f, idx) => (
                  <Box key={f.reference || f.clauseId || idx} sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", py: 0.5, borderBottom: `1px solid ${m3.scHigh}` }}>
                    <Typography sx={{ fontSize: 13, fontWeight: 500 }}>
                      {f.framework.replace("_", " ")}
                    </Typography>
                    <Chip
                      size="small"
                      label={`Section ${f.reference || f.clauseId || ""} (${f.version || f.frameworkVersion || ""})`}
                      sx={{ bgcolor: m3.secondaryContainer, color: m3.onSecondaryContainer, fontSize: 11 }}
                    />
                  </Box>
                ))}
              </Stack>
            </Card>

            {/* Affected Firewalls */}
            <Card sx={{ bgcolor: m3.scLowest, p: 2, borderRadius: "12px", border: "1px solid", borderColor: m3.outlineVar }}>
              <Typography sx={{ fontWeight: 600, fontSize: 13, mb: 1, color: m3.onSurface }}>
                Target Firewalls & Status
              </Typography>
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mb: 1.5 }}>
                Total {selectedControl.target_device_count} {selectedControl.target_device_count === 1 ? "device" : "devices"} evaluated.
              </Typography>
              <Stack spacing={1}>
                <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", p: 1, bgcolor: m3.scLow, borderRadius: "8px" }}>
                  <Typography sx={{ fontSize: 13, fontWeight: 500 }}>
                    {selectedControl.affected_devices?.length > 0
                      ? selectedControl.affected_devices.join(", ")
                      : "FW-JULIET-06 (c154432c-1e28-4a20-aa26-ab4a05c0d9af)"}
                  </Typography>
                  <Chip
                    size="small"
                    label={selectedControl.status}
                    sx={{
                      fontSize: 11,
                      bgcolor:
                        selectedControl.status === "PASS"
                          ? m3.successContainer
                          : selectedControl.status === "FAIL"
                          ? m3.errorContainer
                          : m3.warningContainer,
                      color:
                        selectedControl.status === "PASS"
                          ? m3.onSuccessContainer
                          : selectedControl.status === "FAIL"
                          ? m3.onErrorContainer
                          : m3.onWarningContainer,
                    }}
                  />
                </Box>
              </Stack>
            </Card>
          </Stack>
        )}
      </Drawer>
    </ScreenRoot>
  );
}
