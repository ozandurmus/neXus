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
import { StackedBar, STATUS } from "../shell/Charts";
import { StatePanel, isRestricted } from "../shell/States";
import { formatUtc } from "../shell/time";
import { MONO, m3 } from "../theme/m3Theme";
import { responsesAreMasked } from "../auth/adminApi";
import {
  getComplianceOverview,
  getComplianceControls,
  triggerComplianceEvaluation,
  type ComplianceOverview,
  type ComplianceControlItem,
} from "../auth/adminApi";

type StatusFilter = "ALL" | "FAILING" | "UNAVAILABLE" | "PASSING";

/** NIST SP 800-53 Rev. 5 control families. */
const NIST_FAMILIES: Record<string, string> = {
  AC: "Access Control", AT: "Awareness and Training", AU: "Audit and Accountability", CA: "Assessment, Authorization and Monitoring",
  CM: "Configuration Management", CP: "Contingency Planning", IA: "Identification and Authentication", IR: "Incident Response",
  MA: "Maintenance", MP: "Media Protection", PE: "Physical and Environmental Protection", PL: "Planning", PM: "Program Management",
  PS: "Personnel Security", PT: "PII Processing and Transparency", RA: "Risk Assessment", SA: "System and Services Acquisition",
  SC: "System and Communications Protection", SI: "System and Information Integrity", SR: "Supply Chain Risk Management",
};

export function familyOf(c: ComplianceControlItem): string {
  if (c.category && c.category !== "null") return c.category;
  for (const f of c.frameworks ?? []) {
    if (!/NIST/i.test(f.framework)) continue;
    const m = /^([A-Z]{2})-/.exec((f.reference || f.clauseId || "").trim());
    if (m) return `${m[1]} · ${NIST_FAMILIES[m[1]] ?? "NIST family"}`;
  }
  return "Unmapped";
}

function ComplianceMetricCard({
  title,
  count,
  note,
  badge,
}: {
  readonly title: string;
  readonly count: string | null;
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
        boxShadow: "none",
        borderRadius: "10px",
        p: 2.25,
        display: "flex",
        flexDirection: "column",
        gap: 1,
        border: "1px solid",
        borderColor: m3.outlineVar,
      }}
    >
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase", color: m3.onSurfaceVar }}>{title}</Typography>
        {badge && (
          <Chip
            size="small"
            label={badge.label}
            sx={{ bgcolor: badgeBg, color: badgeFg, fontWeight: 600, fontSize: 11, height: 22 }}
          />
        )}
      </Stack>
      {count === null
        ? <Typography sx={{ fontSize: 22, lineHeight: "40px", fontWeight: 650, color: m3.neutralInk }}>UNKNOWN</Typography>
        : <Typography variant="h1" sx={{ color: m3.onSurface }}>{count}</Typography>}
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
  const [loadError, setLoadError] = useState<{ message: string; restricted: boolean } | null>(null);

  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [ov, ctrlRes] = await Promise.all([
        getComplianceOverview(),
        getComplianceControls(),
      ]);
      setOverview(ov);
      setControls(ctrlRes.controls ?? []);
      setLoadError(null);
    } catch (e) {
      const err = e as { status?: number; message?: string };
      setLoadError({ message: err?.message ?? `status ${err?.status ?? "?"}`, restricted: isRestricted(e) });
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
        const inDesc = c.description.toLowerCase().includes(q) || (c.category ?? "").toLowerCase().includes(q);
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

  /**
   * Control families (review §3): the NIST SP 800-53 family of each control's NIST mapping (AC, IA, AU, ...), the
   * grouping an auditor already uses; the product's own category when the catalog carries one; else "Unmapped".
   */
  const families = useMemo(() => {
    const by = new Map<string, { controls: number; pass: number; fail: number; unavailable: number }>();
    for (const c of controls) {
      const key = familyOf(c);
      const f = by.get(key) ?? { controls: 0, pass: 0, fail: 0, unavailable: 0 };
      f.controls++; f.pass += c.pass_count; f.fail += c.fail_count; f.unavailable += c.data_unavailable_count;
      by.set(key, f);
    }
    return [...by.entries()].map(([name, f]) => ({ name, ...f, checks: f.pass + f.fail + f.unavailable }))
      .sort((a, b) => b.fail - a.fail || a.name.localeCompare(b.name));
  }, [controls]);

  /**
   * Export Audit Report (review §6): a cover section (as-of time, frameworks, device scope, mask state) and one row
   * per control with its per-firewall counts and the firewalls it fails on, then the data gaps with their reason.
   * Built from exactly what this screen read; nothing is re-evaluated.
   */
  const exportAuditReport = () => {
    const esc = (v: unknown) => `"${String(v ?? "").replace(/"/g, '""')}"`;
    const now = new Date().toISOString();
    const lines: string[] = [];
    lines.push(`# neXus compliance audit report`);
    lines.push(`# as of,${esc(now)} (UTC)`);
    lines.push(`# frameworks evaluated,${esc((overview?.frameworks ?? []).map((f) => f.framework).join("; "))}`);
    lines.push(`# device scope,${esc(overview ? `${overview.evaluated_firewalls} of ${overview.total_firewalls} firewalls evaluated` : "UNKNOWN")}`);
    lines.push(`# assured / observed / coverage,${esc(overview ? `${overview.assured_compliance_pct}% / ${overview.observed_compliance_pct}% / ${overview.evidence_coverage_pct}%` : "UNKNOWN")}`);
    lines.push(`# names,${esc(responsesAreMasked() ? "masked (aiview pseudonyms)" : "as recorded")}`);
    lines.push("");
    lines.push(["control_id", "title", "category", "severity", "status", "framework_references", "firewalls", "pass", "fail", "unavailable", "failing_firewalls", "data_gap_reason"].join(","));
    for (const c of controls) {
      lines.push([c.control_id, c.title, c.category, c.severity, c.status,
        (c.frameworks ?? []).map((f) => `${f.framework} ${f.reference || f.clauseId || ""}`.trim()).join("; "),
        c.target_device_count, c.pass_count, c.fail_count, c.data_unavailable_count,
        (c.affected_devices ?? []).join("; "), c.data_unavailable_count > 0 ? (c.missing_reason ?? "evidence not collected") : ""].map(esc).join(","));
    }
    const blob = new Blob([lines.join("\n")], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `nexus-compliance-audit-${formatUtc(now, false).replace(/[: ]/g, "-")}Z.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const subtitle =
    overview && overview.evaluated_firewalls > 0
      ? "CIS Benchmark · PCI-DSS v4.0.1 · NIST SP 800-53 · Financial Baseline"
      : "No framework assigned · nothing assessed yet";

  return (
    <ScreenRoot>
      <ScreenHeader
        title="Compliance"
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
            <M3Button emphasis="filled" icon="download" onClick={exportAuditReport} disabled={loading || controls.length === 0}>
              Export Audit Report
            </M3Button>
          </Stack>
        }
      />

      {/* 4 Top KPI Cards */}
      {/* Evaluation scope (Astra review 2026-09-23): the headline is every control on every evaluated firewall, not one
          framework; every control maps to CIS, which is why the CIS card carries the same totals. */}
      {overview && overview.evaluated_firewalls > 0 && (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: -1 }}>
          Scope: all {controls.length} controls on {overview.evaluated_firewalls} of {overview.total_firewalls} evaluated firewalls
          {" · "}{controls.reduce((n, c) => n + c.pass_count + c.fail_count + c.data_unavailable_count, 0)} control checks (one control on one firewall)
          {" · "}every control maps to CIS, so the CIS card shows the same totals.
        </Typography>
      )}
      <MetricGrid>
        <ComplianceMetricCard
          title="Assured Compliance"
          count={overview ? `${overview.assured_compliance_pct}%` : null}
          badge={{
            label: !overview ? "UNKNOWN" : overview.assured_compliance_pct >= 70 ? "High Assurance" : "Improvement Needed",
            tone: !overview ? "neutral" : overview.assured_compliance_pct >= 70 ? "ok" : "warn",
          }}
          note="Passing checks among all assigned checks (a check without evidence counts against)"
        />
        <ComplianceMetricCard
          title="Evidence Coverage"
          count={overview ? `${overview.evidence_coverage_pct}%` : null}
          badge={{
            label: overview ? `${overview.evaluated_firewalls} of ${overview.total_firewalls} firewalls` : "UNKNOWN",
            tone: "neutral",
          }}
          note={overview ? `Checks with collected evidence · observed ${overview.observed_compliance_pct}% (pass among judged checks)` : "Checks with collected evidence"}
        />
        <ComplianceMetricCard
          title="Critical failing checks"
          count={overview ? String(overview.critical_deficiencies) : null}
          badge={{
            label: !overview ? "UNKNOWN" : overview.critical_deficiencies === 0 ? "Zero Critical" : "Immediate Action",
            tone: !overview || overview.critical_deficiencies === 0 ? "neutral" : "bad",
          }}
          note="Checks of a critical-severity control that failed (one control on one firewall counts once)"
        />
        <ComplianceMetricCard
          title="Data Gaps"
          count={overview ? String(overview.data_gaps) : null}
          badge={{
            label: !overview ? "UNKNOWN" : overview.data_gaps > 0 ? "Evidence not collected" : "Full Coverage",
            tone: !overview ? "neutral" : overview.data_gaps > 0 ? "warn" : "ok",
          }}
          note="Checks awaiting evidence: the command a control needs is not in the collection scope yet"
        />
      </MetricGrid>

      {loadError && (loadError.restricted
        ? <StatePanel variant="restricted" title="Compliance" body="This account can not view compliance results." />
        : <StatePanel variant="error" title="Compliance could not be read" body="The results below may be missing." code={loadError.message}
            action={<M3Button emphasis="text" onClick={loadData}>Retry</M3Button>} />)}

      {/* Framework cards: the same stacked Pass / Fail / Unavailable bar as the Overview (review §3) */}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(250px, 1fr))" }}>
        {overview?.frameworks.map((f) => (
          <Card key={f.framework} sx={{ bgcolor: m3.scLowest, boxShadow: "none", borderRadius: "10px", p: 2, display: "flex",
                                         flexDirection: "column", gap: 1, border: "1px solid", borderColor: m3.outlineVar }}>
            <Stack direction="row" justifyContent="space-between" alignItems="baseline">
              <Typography sx={{ fontSize: 14, fontWeight: 600, color: m3.onSurface }}>{f.framework}</Typography>
              <Typography sx={{ fontSize: 15, fontWeight: 700, color: m3.onSurface }}>{f.score_pct}%</Typography>
            </Stack>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{f.total_controls} control checks (control × firewall)</Typography>
            <StackedBar parts={[
              { label: "Pass", count: f.pass_count, color: STATUS.good },
              { label: "Fail", count: f.fail_count, color: STATUS.critical },
              { label: "Unavailable (no evidence)", count: f.data_unavailable_count, color: STATUS.neutral },
            ]} />
            <Stack direction="row" spacing={2} sx={{ mt: 0.25 }}>
              <Typography variant="body2" sx={{ color: m3.goodInk, fontWeight: 500 }}>{f.pass_count} pass</Typography>
              <Typography variant="body2" sx={{ color: m3.criticalInk, fontWeight: 500 }}>{f.fail_count} fail</Typography>
              <Typography variant="body2" sx={{ color: m3.neutralInk, fontWeight: 500 }}>{f.data_unavailable_count} unavailable</Typography>
            </Stack>
          </Card>
        ))}
      </Box>

      {/* Control families (review §3, from the M3 study): where an audit reviewer starts */}
      {families.length > 0 && (
        <Card sx={{ bgcolor: m3.scLowest, borderRadius: "10px", boxShadow: "none", border: "1px solid", borderColor: m3.outlineVar, overflow: "hidden" }}>
          <Box sx={{ px: 2, pt: 1.75, pb: 0.5 }}>
            <Typography sx={{ fontSize: 16, fontWeight: 600 }}>Control families</Typography>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>grouped by NIST SP 800-53 family · a check is one control on one firewall · coverage = checks with evidence · click a family to filter the list</Typography>
          </Box>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Family</TableCell>
                <TableCell align="right">Controls</TableCell>
                <TableCell align="right">Checks</TableCell>
                <TableCell sx={{ width: 220 }}>Pass · Fail · Unavailable</TableCell>
                <TableCell align="right">Coverage</TableCell>
                <TableCell align="right">Failing checks</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {families.map((f) => (
                <TableRow key={f.name} hover sx={{ cursor: "pointer" }} onClick={() => setSearchQuery(f.name === "Unmapped" ? "" : f.name.split(" · ")[0] + "-")}>
                  <TableCell sx={{ fontWeight: 600 }}>{f.name}</TableCell>
                  <TableCell align="right">{f.controls}</TableCell>
                  <TableCell align="right">{f.checks}</TableCell>
                  <TableCell>
                    <StackedBar height={8} parts={[
                      { label: "Pass", count: f.pass, color: STATUS.good },
                      { label: "Fail", count: f.fail, color: STATUS.critical },
                      { label: "Unavailable", count: f.unavailable, color: STATUS.neutral },
                    ]} />
                  </TableCell>
                  <TableCell align="right">{f.checks > 0 ? `${Math.round((100 * (f.pass + f.fail)) / f.checks)}%` : "UNKNOWN"}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600, color: f.fail > 0 ? m3.criticalInk : m3.onSurfaceVar }}>{f.fail}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {/* Filter and Search Bar */}
      <Card
        sx={{
          bgcolor: m3.scLowest,
          borderRadius: "10px",
          p: 2,
          boxShadow: "none",
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
              label={`All · ${controls.length}`}
              onClick={() => setStatusFilter("ALL")}
              sx={{
                bgcolor: statusFilter === "ALL" ? m3.primaryContainer : m3.scLow,
                color: statusFilter === "ALL" ? m3.onPrimaryContainer : m3.onSurface,
                fontWeight: statusFilter === "ALL" ? 600 : 400,
              }}
            />
            <Chip
              clickable
              label={`Failing · ${failingCount}`}
              onClick={() => setStatusFilter("FAILING")}
              sx={{
                bgcolor: statusFilter === "FAILING" ? m3.errorContainer : m3.scLow,
                color: statusFilter === "FAILING" ? m3.onErrorContainer : m3.onSurface,
                fontWeight: statusFilter === "FAILING" ? 600 : 400,
              }}
            />
            <Chip
              clickable
              label={`Data gaps · ${unavailCount}`}
              onClick={() => setStatusFilter("UNAVAILABLE")}
              sx={{
                bgcolor: statusFilter === "UNAVAILABLE" ? m3.warningContainer : m3.scLow,
                color: statusFilter === "UNAVAILABLE" ? m3.onWarningContainer : m3.onSurface,
                fontWeight: statusFilter === "UNAVAILABLE" ? 600 : 400,
              }}
            />
            <Chip
              clickable
              label={`Passing · ${passingCount}`}
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
          borderRadius: "10px",
          boxShadow: "none",
          border: "1px solid",
          borderColor: m3.outlineVar,
          overflow: "hidden",
        }}
      >
        <TableContainer>
          <Table size="medium">
            <TableHead sx={{ bgcolor: m3.scLow }}>
              <TableRow>
                <TableCell>Control · code & title</TableCell>
                <TableCell sx={{ width: 110 }}>Severity</TableCell>
                <TableCell sx={{ width: 100 }} align="right">Firewalls</TableCell>
                <TableCell sx={{ width: 190 }}>Pass · Fail · Unavailable</TableCell>
                <TableCell sx={{ width: 150 }}>Outcome</TableCell>
                <TableCell sx={{ width: 70 }} align="right">Detail</TableCell>
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
                          <Typography variant="body2" sx={{ fontFamily: MONO, color: m3.onSurfaceVar, fontSize: 11.5 }}>
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

                    <TableCell align="right">
                      <Typography sx={{ fontSize: 13, fontWeight: 500 }}>{c.target_device_count}</Typography>
                    </TableCell>

                    <TableCell>
                      {/* Segmented Stacked Bar (Green / Red / Amber) */}
                      <Stack spacing={0.5}>
                        <StackedBar height={8} parts={[
                          { label: "Pass", count: c.pass_count, color: STATUS.good },
                          { label: "Fail", count: c.fail_count, color: STATUS.critical },
                          { label: "Unavailable (no evidence)", count: c.data_unavailable_count, color: STATUS.neutral },
                        ]} />
                        <Typography variant="body2" sx={{ fontSize: 11, color: m3.onSurfaceVar }}>
                          {c.pass_count} · {c.fail_count} · {c.data_unavailable_count} · {c.compliance_pct}% assured
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
                            : "Evidence not collected"
                        }
                        sx={{
                          fontWeight: 600,
                          fontSize: 12,
                          bgcolor:
                            c.status === "PASS"
                              ? m3.successContainer
                              : c.status === "FAIL"
                              ? m3.errorContainer
                              : m3.scHigh,
                          color:
                            c.status === "PASS"
                              ? m3.onSuccessContainer
                              : c.status === "FAIL"
                              ? m3.onErrorContainer
                              : m3.neutralInk,
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
                      : "No firewall listed for this control"}
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
