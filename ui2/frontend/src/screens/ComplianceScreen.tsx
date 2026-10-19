import { Fragment, useState, useMemo, useEffect, useCallback, useRef } from "react";
import { ComplianceGuidance, observedValue } from "./ComplianceGuidance";
import { downloadRemediationReport } from "./complianceReport";
import { urlParam, useUrlParamSync, writeUrlParams } from "../shell/urlParams";

/**
 * The one-line headline under the title (UI review 2026-10-18 §4.3): `2491 checks · 118 of 123 firewalls · 164 critical
 * failing · 496 data gaps`, from the overview the screen already loads. The service records no evaluation time, so the
 * age the review asked for is stated as not recorded rather than invented. A failed read says UNKNOWN, never 0.
 */
export function complianceHeadline(overview: { readonly evaluated_firewalls: number; readonly total_firewalls: number; readonly critical_deficiencies: number;
  readonly data_gaps: number; readonly control_checks?: number } | null, checksFromControls: number, state: "loading" | "failed" | "loaded"): string {
  if (state === "loading") return "Reading the compliance evaluation…";
  if (state === "failed" || !overview) return "checks UNKNOWN · firewalls UNKNOWN · critical failing UNKNOWN · data gaps UNKNOWN";
  if (overview.evaluated_firewalls === 0) return "No framework assigned · nothing assessed yet";
  const checks = overview.control_checks ?? checksFromControls;
  return `${checks.toLocaleString("en-US")} checks · ${overview.evaluated_firewalls} of ${overview.total_firewalls} firewalls · `
    + `${overview.critical_deficiencies} critical failing · ${overview.data_gaps} data gaps · re-check time not recorded`;
}

/**
 * One device's checks as the control list needs them (`?device=<opaque id>`, from the device header's Compliance pill):
 * the masked name the service answered with, and each control's status on that device. NOT_APPLICABLE is not a check
 * (the header pill counts the same way); anything but PASS / FAIL is a check without evidence.
 */
export interface DeviceScope {
  readonly id: string;
  readonly name: string;
  readonly statuses: ReadonlyMap<string, ComplianceControlItem["status"]>;
}

export function deviceScopeOf(id: string, result: Pick<DeviceComplianceResult, "hostname" | "items">): DeviceScope {
  const statuses = new Map<string, ComplianceControlItem["status"]>();
  for (const item of result.items ?? []) {
    const status = String(item.displayStatus ?? "").toUpperCase();
    if (status === "NOT_APPLICABLE") continue;
    statuses.set(item.controlId, status === "PASS" ? "PASS" : status === "FAIL" ? "FAIL" : "DATA_UNAVAILABLE");
  }
  return { id, name: result.hostname || "name UNKNOWN", statuses };
}

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
import { KpiCard } from "../shell/KpiCard";
import { StatusPill } from "../shell/StatusPill";
import { Icon } from "../shell/Icon";
import { M3Button } from "../shell/M3Widgets";
import { StackedBar, STATUS } from "../shell/Charts";
import { StatePanel, isRestricted } from "../shell/States";
import { formatUtc } from "../shell/time";
import { Presented } from "../shell/PresentedText";
import { vendorLabel } from "../shell/presenters";
import { MONO, m3 } from "../theme/m3Theme";
import { responsesAreMasked } from "../auth/adminApi";
import {
  getDeviceCompliance,
  getComplianceOverview,
  getComplianceControls,
  getComplianceControlDetail,
  triggerComplianceEvaluation,
  type ComplianceOverview,
  type ComplianceControlItem,
  type ComplianceControlDetail,
  type DeviceComplianceResult,
} from "../auth/adminApi";

type StatusFilter = "ALL" | "FAILING" | "UNAVAILABLE" | "PASSING";
type SortMode = "SEVERITY" | "ID";

const SEVERITY_RANK: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
const STATUS_RANK: Record<string, number> = { FAIL: 0, DATA_UNAVAILABLE: 1, PASS: 2 };

/**
 * The default order of the control list (UI review 2026-10-18, F-14): non-compliant first and, inside each outcome,
 * the most severe first, so a non-compliant critical control opens the list; then the most firewalls failing, then the
 * control id so two renders agree. Control-ID order stays one choice away.
 */
export function compareControls(a: ComplianceControlItem, b: ComplianceControlItem, mode: SortMode = "SEVERITY"): number {
  if (mode === "ID") return a.control_id.localeCompare(b.control_id);
  return (STATUS_RANK[a.status] ?? 3) - (STATUS_RANK[b.status] ?? 3)
    || (SEVERITY_RANK[a.severity] ?? 4) - (SEVERITY_RANK[b.severity] ?? 4)
    || b.fail_count - a.fail_count
    || a.control_id.localeCompare(b.control_id);
}

/** NIST SP 800-53 Rev. 5 control families. */
const NIST_FAMILIES: Record<string, string> = {
  AC: "Access Control", AT: "Awareness and Training", AU: "Audit and Accountability", CA: "Assessment, Authorization and Monitoring",
  CM: "Configuration Management", CP: "Contingency Planning", IA: "Identification and Authentication", IR: "Incident Response",
  MA: "Maintenance", MP: "Media Protection", PE: "Physical and Environmental Protection", PL: "Planning", PM: "Program Management",
  PS: "Personnel Security", PT: "PII Processing and Transparency", RA: "Risk Assessment", SA: "System and Services Acquisition",
  SC: "System and Communications Protection", SI: "System and Information Integrity", SR: "Supply Chain Risk Management",
};

export function familyOf(c: ComplianceControlItem): string {
  if (c.family) return `${c.family} · ${NIST_FAMILIES[c.family] ?? "NIST family"}`;
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
  return (
    <KpiCard label={title} value={count} state={count === null ? "unknown" : undefined} sentence={note}
      badge={badge ? <StatusPill tone={badge.tone} word={badge.label} /> : undefined} />
  );
}

export function ComplianceScreen() {
  const [overview, setOverview] = useState<ComplianceOverview | null>(null);
  const [controls, setControls] = useState<readonly ComplianceControlItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [reEvaluating, setReEvaluating] = useState(false);
  const [searchQuery, setSearchQuery] = useState(() => urlParam("q") ?? "");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(() =>
    urlParam("result") === "fail" ? "FAILING" : urlParam("result") === "unavailable" ? "UNAVAILABLE" : "ALL");
  const [severityFilter, setSeverityFilter] = useState<string>(() => (urlParam("severity") ? urlParam("severity")!.toUpperCase() : "ALL"));
  const [frameworkFilter, setFrameworkFilter] = useState<string>(() => frameworkFromUrl(urlParam("framework")));
  const [selectedControl, setSelectedControl] = useState<ComplianceControlItem | null>(null);
  const [detail, setDetail] = useState<ComplianceControlDetail | null>(null);
  const [detailError, setDetailError] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState(false);
  const [deviceFilter, setDeviceFilter] = useState("ALL");
  // `?device=<opaque id>` narrows the list to one device's checks (the device header's Compliance pill lands here).
  const [deviceParam, setDeviceParam] = useState<string | null>(() => urlParam("device"));
  const [deviceScope, setDeviceScope] = useState<{ readonly id: string; readonly state: "loading" } | { readonly id: string; readonly state: "failed" } | (DeviceScope & { readonly state: "loaded" }) | null>(null);
  useUrlParamSync("device", useCallback((id: string | null) => setDeviceParam(id), []));
  useEffect(() => {
    if (!deviceParam) { setDeviceScope(null); return; }
    let active = true;
    setDeviceScope({ id: deviceParam, state: "loading" });
    getDeviceCompliance(deviceParam)
      .then((result) => { if (active) setDeviceScope({ ...deviceScopeOf(deviceParam, result), state: "loaded" }); })
      .catch(() => { if (active) setDeviceScope({ id: deviceParam, state: "failed" }); });
    return () => { active = false; };
  }, [deviceParam]);
  const clearDevice = () => { setDeviceParam(null); writeUrlParams({ device: null }); };
  const [familyFilter, setFamilyFilter] = useState(() => urlParam("family") ?? "");
  const [vendorFilter, setVendorFilter] = useState(() => urlParam("vendor") ?? "ALL");
  const [sortMode, setSortMode] = useState<SortMode>("SEVERITY");
  const controlsRef = useRef<HTMLDivElement>(null);
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

  // The control drawer is addressable (roadmap 3.6): `?control=<id>` opens it once the controls are read; opening pushes
  // the id, closing replaces it away, and the browser's Back closes the drawer.
  const linkedControlApplied = useRef(false);
  useEffect(() => {
    if (linkedControlApplied.current || controls.length === 0) return;
    linkedControlApplied.current = true;
    const id = urlParam("control");
    const linked = id ? controls.find((c) => c.control_id === id) : undefined;
    if (linked) setSelectedControl(linked);
  }, [controls]);
  const openControl = (c: ComplianceControlItem) => { setSelectedControl(c); writeUrlParams({ control: c.control_id }, "push"); };
  const closeControl = () => { setSelectedControl(null); writeUrlParams({ control: null }); };
  useUrlParamSync("control", useCallback((id: string | null) => {
    setSelectedControl((current) => id === null ? null : current?.control_id === id ? current : (controls.find((c) => c.control_id === id) ?? current));
  }, [controls]));

  useEffect(() => {
    if (!selectedControl) { setDetail(null); return; }
    let active = true;
    setDetail(null);
    setDetailError(false);
    setDeviceFilter("ALL");
    getComplianceControlDetail(selectedControl.control_id)
      .then((value) => { if (active) setDetail(value); })
      .catch(() => { if (active) setDetailError(true); });
    return () => { active = false; };
  }, [selectedControl]);

  const exportDeviceReport = async (deviceId: string, format: "csv" | "html") => {
    setExporting(true);
    setExportError(false);
    try {
      const result = await getDeviceCompliance(deviceId);
      downloadRemediationReport(result, format, responsesAreMasked());
    } catch {
      setExportError(true);
    } finally {
      setExporting(false);
    }
  };

  const selectFamily = (name: string) => {
    const family = name.split(" · ")[0];
    setFamilyFilter(family);
    const url = new URL(window.location.href);
    if (family) url.searchParams.set("family", family);
    else url.searchParams.delete("family");
    window.history.pushState({}, "", url);
    controlsRef.current?.scrollIntoView?.({ behavior: "smooth" });
  };

  useEffect(() => {
    const onPop = () => setFamilyFilter(urlParam("family") ?? "");
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

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

  // With a device in scope the list holds that device's checks and every outcome word is the device's own; the fleet
  // counts in each row stay (they are the control's). A scope still loading or failed shows no row rather than the fleet.
  const scoped = useMemo(() => {
    if (!deviceScope) return { controls, statusOf: (c: ComplianceControlItem) => c.status };
    if (deviceScope.state !== "loaded") return { controls: [] as readonly ComplianceControlItem[], statusOf: (c: ComplianceControlItem) => c.status };
    const statuses = deviceScope.statuses;
    return { controls: controls.filter((c) => statuses.has(c.control_id)), statusOf: (c: ComplianceControlItem) => statuses.get(c.control_id) ?? c.status };
  }, [controls, deviceScope]);
  const statusOf = scoped.statusOf;

  const filteredControls = useMemo(() => {
    return scoped.controls.filter((c) => {
      if (familyFilter && familyOf(c).split(" · ")[0] !== familyFilter) return false;
      // Search
      const q = searchQuery.toLowerCase().trim();
      if (q) {
        const inTitle = c.title.toLowerCase().includes(q);
        const inId = c.control_id.toLowerCase().includes(q);
        const inDesc = (c.description ?? "").toLowerCase().includes(q) || (c.category ?? "").toLowerCase().includes(q);
        const inFw = c.frameworks?.some((f) => {
          const ref = f.reference || f.clauseId || "";
          const fw = f.framework || "";
          return ref.toLowerCase().includes(q) || fw.toLowerCase().includes(q);
        });
        if (!inTitle && !inId && !inDesc && !inFw) return false;
      }

      // Status (the device's own when one is in scope)
      const status = statusOf(c);
      if (statusFilter === "FAILING" && status !== "FAIL") return false;
      if (statusFilter === "UNAVAILABLE" && status !== "DATA_UNAVAILABLE") return false;
      if (statusFilter === "PASSING" && status !== "PASS") return false;

      // Severity
      if (severityFilter !== "ALL" && c.severity !== severityFilter) return false;

      // Vendor: a control with no recorded vendor cannot be shown to apply to the chosen one
      if (vendorFilter !== "ALL" && !(c.vendors ?? []).includes(vendorFilter)) return false;

      // Framework
      if (frameworkFilter !== "ALL") {
        const hasFw = c.frameworks?.some((f) => f.framework.toUpperCase().includes(frameworkFilter));
        if (!hasFw) return false;
      }

      return true;
    }).sort((a, b) => compareControls(deviceScope ? { ...a, status: statusOf(a) } : a, deviceScope ? { ...b, status: statusOf(b) } : b, sortMode));
  }, [scoped, statusOf, deviceScope, searchQuery, statusFilter, severityFilter, frameworkFilter, familyFilter, vendorFilter, sortMode]);

  /** The vendors the controls were evaluated on, with how many controls each covers (the order a reader expects). */
  const vendorOptions = useMemo(() => {
    const counts = new Map<string, number>();
    for (const c of controls) for (const v of c.vendors ?? []) counts.set(v, (counts.get(v) ?? 0) + 1);
    const order = ["check_point", "palo_alto"];
    return [...counts.entries()].sort(([a], [b]) => (order.indexOf(a) < 0 ? 9 : order.indexOf(a)) - (order.indexOf(b) < 0 ? 9 : order.indexOf(b)) || vendorLabel(a).localeCompare(vendorLabel(b)));
  }, [controls]);

  const failingCount = useMemo(() => scoped.controls.filter((c) => statusOf(c) === "FAIL").length, [scoped, statusOf]);
  const unavailCount = useMemo(() => scoped.controls.filter((c) => statusOf(c) === "DATA_UNAVAILABLE").length, [scoped, statusOf]);
  const passingCount = useMemo(() => scoped.controls.filter((c) => statusOf(c) === "PASS").length, [scoped, statusOf]);

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

  const scopeChecks = controls.reduce((n, c) => n + c.pass_count + c.fail_count + c.data_unavailable_count, 0);
  const subtitle = complianceHeadline(overview, scopeChecks, loading && !overview ? "loading" : loadError ? "failed" : "loaded");
  const cisChecks = overview?.frameworks.find((f) => f.framework === "CIS Benchmark")?.total_controls ?? null;
  // KPI badges state facts, not judgements (post-release review 2026-10-18, R12): the share of all checks, and the
  // critical failures with the number of distinct firewalls they fall on (from the controls' failing-device lists).
  const totalChecks = overview?.control_checks ?? (controls.length > 0 ? scopeChecks : null);
  const criticalFirewalls = controls.length > 0
    ? new Set(controls.filter((c) => c.severity === "CRITICAL" && c.fail_count > 0).flatMap((c) => c.affected_devices ?? [])).size : null;

  return (
    <ScreenRoot screenType="list">
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
              {reEvaluating ? "Checking..." : "Re-check"}
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
      {/* Said only when it is true: the CIS total is computed separately (UI review 2026-10-17, 2515 against 2523). */}
      {overview && overview.evaluated_firewalls > 0 && (
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: -1 }}>
          Scope: all {controls.length} controls on {overview.evaluated_firewalls} of {overview.total_firewalls} evaluated firewalls
          {" · "}{scopeChecks} control checks (one control on one firewall)
          {scopeChecks === cisChecks ? " · every control maps to CIS, so the CIS card shows the same totals." : "."}
          {(overview.pending_reevaluation ?? 0) > 0 && ` · ${overview.pending_reevaluation} firewall(s) shown with their previous evaluation while being re-evaluated`}
        </Typography>
      )}
      <MetricGrid>
        <ComplianceMetricCard
          title="Checks passing"
          count={overview ? `${overview.assured_compliance_pct}%` : null}
          badge={{
            label: !overview ? "UNKNOWN" : totalChecks !== null ? `${overview.assured_compliance_pct} % of ${totalChecks.toLocaleString("en-US")} checks` : `${overview.assured_compliance_pct} % of all checks`,
            tone: "neutral",
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
            label: !overview ? "UNKNOWN"
              : `${overview.critical_deficiencies} critical${criticalFirewalls !== null && overview.critical_deficiencies > 0 ? ` · ${criticalFirewalls} ${criticalFirewalls === 1 ? "firewall" : "firewalls"}` : ""}`,
            // Zero is neutral, never green; a count above zero keeps the critical colour with its word.
            tone: !overview || overview.critical_deficiencies === 0 ? "neutral" : "bad",
          }}
          note="Checks of a critical-severity control that failed (one control on one firewall counts once)"
        />
        <ComplianceMetricCard
          title="Data Gaps"
          count={overview ? String(overview.data_gaps) : null}
          badge={{
            // Zero is neutral, never green: no gap is an absence, not an achievement.
            label: !overview ? "UNKNOWN" : overview.data_gaps > 0 ? "Evidence not collected" : "No data gaps",
            tone: !overview ? "neutral" : overview.data_gaps > 0 ? "warn" : "neutral",
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
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>grouped by NIST SP 800-53 family · a check is one control on one firewall · coverage = checks with evidence · click a family to see its controls</Typography>
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
                <TableRow key={f.name} hover sx={{ cursor: "pointer" }} onClick={() => selectFamily(f.name)}>
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

      {/* Filter and Search Bar: sticky, so the filters stay in reach on a 64-control page (UI review 2026-10-18, F-14) */}
      <Card
        data-sticky-filter-bar
        sx={{
          position: "sticky",
          top: 0,
          zIndex: 3,
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

              <select
                aria-label="Sort controls"
                value={sortMode}
                onChange={(e) => setSortMode(e.target.value as SortMode)}
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
                <option value="SEVERITY">Sort: non-compliant, most severe first</option>
                <option value="ID">Sort: control ID</option>
              </select>
            </Stack>
          </Stack>

          {/* Status Filter Pills */}
          <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
            {/* One device's checks (the device header's Compliance pill): named by its masked name, removable here. */}
            {deviceScope && (
              <Chip data-testid="device-filter-chip" color="primary" variant="outlined"
                label={deviceScope.state === "loaded" ? `Device: ${deviceScope.name} · ${scoped.controls.length} checks`
                  : deviceScope.state === "loading" ? "Device: reading its checks…" : "Device: UNKNOWN · its checks could not be read"}
                onDelete={clearDevice} deleteIcon={<span aria-label="Remove device filter" role="img">✕</span>} />
            )}
            {familyFilter && <Chip label={`Family: ${familyFilter} · ${NIST_FAMILIES[familyFilter] ?? "Unmapped"}`} onDelete={() => selectFamily("")} />}
            <Chip
              clickable
              label={`All · ${scoped.controls.length}`}
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
              label={`Not yet checked · ${unavailCount}`}
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
            {/* Vendor pills beside the outcome pills: neutral, they are identity, not status */}
            {vendorOptions.length > 0 && <Box component="span" aria-hidden sx={{ width: "1px", alignSelf: "stretch", bgcolor: m3.outlineVar, mx: 0.5 }} />}
            {vendorOptions.length > 0 && (
              <Stack direction="row" spacing={1} role="group" aria-label="Vendor filter" sx={{ flexWrap: "wrap", gap: 1 }}>
                <Chip clickable label="All vendors" aria-pressed={vendorFilter === "ALL"} onClick={() => setVendorFilter("ALL")}
                  sx={{ bgcolor: vendorFilter === "ALL" ? m3.secondaryContainer : m3.scLow, fontWeight: vendorFilter === "ALL" ? 600 : 400 }} />
                {vendorOptions.map(([v, n]) => (
                  <Chip key={v} clickable label={`${vendorLabel(v)} · ${n}`} title={v} aria-pressed={vendorFilter === v} onClick={() => setVendorFilter(v)}
                    sx={{ bgcolor: vendorFilter === v ? m3.secondaryContainer : m3.scLow, fontWeight: vendorFilter === v ? 600 : 400 }} />
                ))}
              </Stack>
            )}
          </Stack>
        </Stack>
      </Card>

      {/* Controls Table */}
      <Card ref={controlsRef}
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
                    <Typography variant="body2" role="status" sx={{ color: m3.onSurfaceVar }}>
                      {deviceScope?.state === "loading" ? "Reading the device's checks…"
                        : deviceScope?.state === "failed" ? "The device's checks could not be read. Remove the device filter, or reload to try again."
                        : "No controls matching filter criteria."}
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                filteredControls.map((c) => (
                  <TableRow
                    key={c.control_id}
                    hover
                    onClick={() => openControl(c)}
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
                          {(c.vendors ?? []).map((v) => (
                            <Chip key={v} size="small" variant="outlined" label={vendorLabel(v)} title={v}
                              sx={{ height: 20, fontSize: 10.5, color: m3.onSurfaceVar, borderColor: m3.outlineVar }} />
                          ))}
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
                      {/* The device's own outcome when one is in scope (its name on hover); the control's otherwise. */}
                      {(() => {
                        const status = statusOf(c);
                        return (
                          <Chip
                            size="small"
                            title={deviceScope?.state === "loaded" ? `${deviceScope.name}: ${status}` : c.status}
                            label={
                              status === "PASS"
                                ? "Compliant"
                                : status === "FAIL"
                                ? "Non-Compliant"
                                : "Evidence not collected"
                            }
                            sx={{
                              fontWeight: 600,
                              fontSize: 12,
                              bgcolor:
                                status === "PASS"
                                  ? m3.successContainer
                                  : status === "FAIL"
                                  ? m3.errorContainer
                                  : m3.scHigh,
                              color:
                                status === "PASS"
                                  ? m3.onSuccessContainer
                                  : status === "FAIL"
                                  ? m3.onErrorContainer
                                  : m3.neutralInk,
                            }}
                          />
                        );
                      })()}
                    </TableCell>

                    <TableCell align="right">
                      <IconButton size="small" onClick={() => openControl(c)}>
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
        onClose={closeControl}
        PaperProps={{
          sx: {
            width: { xs: "100%", md: 560 },
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
              <Stack direction="row" spacing={0.5} alignItems="center">
                <M3Button emphasis="text" onClick={() => { void navigator.clipboard.writeText(window.location.href); }}>Copy link</M3Button>
                <IconButton aria-label="Close control detail" onClick={closeControl}>
                  ✕
                </IconButton>
              </Stack>
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
                title={selectedControl.status}
                label={
                  selectedControl.status === "PASS"
                    ? "Compliant"
                    : selectedControl.status === "FAIL"
                    ? "Non-Compliant"
                    : "Evidence not collected"
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

            {detailError && <Typography>Could not load control detail.</Typography>}
            {!detail && !detailError && <Typography>Loading control detail...</Typography>}
            {detail?.rationale && <Card sx={{ p: 2 }}><Typography sx={{ fontWeight: 600 }}>Why it matters</Typography><Typography>{detail.rationale}</Typography></Card>}
            {detail && <Card sx={{ p: 2 }}>
              <Typography sx={{ fontWeight: 600 }}>What it should be</Typography>
              {detail.expected.map((line, i) => <Typography key={i}>{vendorLabel(line.vendor)}: {line.text}</Typography>)}
            </Card>}
            {detail && <Card sx={{ p: 2 }}>
              <Typography sx={{ fontWeight: 600, mb: 1 }}>Devices</Typography>
              <Stack direction="row" spacing={1} sx={{ mb: 1, flexWrap: "wrap" }}>
                {(["ALL", "FAIL", "PASS", "DATA_UNAVAILABLE"] as const).map((status) => <Chip key={status} clickable
                  label={`${({ ALL: "All", FAIL: "Failing", PASS: "Passing", DATA_UNAVAILABLE: "Not yet checked" })[status]} · ${status === "ALL" ? detail.devices.length : detail.devices.filter((d) => d.status === status).length}`}
                  color={deviceFilter === status ? "primary" : "default"} onClick={() => setDeviceFilter(status)} />)}
              </Stack>
              <TableContainer><Table size="small"><TableHead><TableRow>
                <TableCell>Device</TableCell><TableCell>Vendor</TableCell><TableCell>Status</TableCell><TableCell>Observed</TableCell><TableCell>Message</TableCell>
              </TableRow></TableHead><TableBody>
                {detail.devices.filter((d) => deviceFilter === "ALL" || d.status === deviceFilter).map((d) => <Fragment key={d.device_id}><TableRow>
                  <TableCell>{d.hostname ?? d.device_id}</TableCell><TableCell><Presented label={vendorLabel(d.vendor)} raw={d.vendor} /></TableCell>
                  <TableCell><Chip size="small" label={d.status === "FAIL" ? "Fail" : d.status === "PASS" ? "Pass" : "Not yet checked"} /></TableCell>
                  <TableCell>{d.message === "Evidence withheld" ? "withheld" : observedValue(d.observed_value)}</TableCell><TableCell>{d.message ?? "—"}</TableCell>
                </TableRow><TableRow><TableCell colSpan={5}>
                  <ComplianceGuidance guidance={d.guidance} status={d.status} />
                  <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                    <M3Button emphasis="outlined" disabled={exporting} onClick={() => exportDeviceReport(d.device_id, "csv")}>Remediation CSV</M3Button>
                    <M3Button emphasis="outlined" disabled={exporting} onClick={() => exportDeviceReport(d.device_id, "html")}>Printable HTML</M3Button>
                  </Stack>
                </TableCell></TableRow></Fragment>)}
              </TableBody></Table></TableContainer>
              {exportError && <Typography role="alert">Could not export the remediation report. Try again.</Typography>}
            </Card>}
            {detail && <Card sx={{ p: 2 }}>
              <Typography sx={{ fontWeight: 600 }}>Framework mappings</Typography>
              {detail.frameworks?.map((f, i) => <Typography key={i}>{f.framework.replace("_", " ")} · {f.reference || f.clauseId || ""}</Typography>)}
            </Card>}
          </Stack>
        )}
      </Drawer>
    </ScreenRoot>
  );
}
