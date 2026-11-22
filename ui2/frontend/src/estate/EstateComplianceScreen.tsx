import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import {
  getComplianceControlDetail,
  getComplianceControls,
  getComplianceOverview,
  getDeviceCompliance,
  responsesAreMasked,
  type ComplianceControlDetail,
  type ComplianceControlItem,
  type ComplianceOverview,
  type DeviceComplianceResult,
} from "../auth/adminApi";
import { PRODUCT_NAME } from "../brand/product";
import type { SourceRead } from "../screens/overview/attentionRules";
import { isRestricted } from "../shell/States";
import { formatUtc } from "../shell/time";
import { writeUrlParams } from "../shell/urlParams";
import {
  EstateCard,
  EvidenceStamp,
  HonestBar,
  MoreFooter,
  StateBadge,
  TileSwatch,
  UnitTile,
} from "./EstatePrimitives";
import { dossierHref, standardComplianceHref } from "./estateRouting";
import { buildComplianceLensModel } from "./estateSlice2Model";
import { ESTATE_MONO, ESTATE_SANS, ESTATE_UNKNOWN_HATCH, ev } from "./estateTokens";
import { useEstateData } from "./useEstateData";

export function EstateComplianceScreen({
  initialDeviceId = null,
  initialFamily = null,
}: {
  readonly initialDeviceId?: string | null;
  readonly initialFamily?: string | null;
}) {
  const { overview, controls: sharedControls, model } = useEstateData();
  const [compOverview, setCompOverview] = useState<SourceRead<ComplianceOverview>>({ state: "skipped" });
  const [localControls, setLocalControls] = useState<SourceRead<readonly ComplianceControlItem[]>>({ state: "skipped" });
  const [loading, setLoading] = useState<boolean>(true);
  const [selectedFramework, setSelectedFramework] = useState<string>("ALL");
  const [familyFilter, setFamilyFilter] = useState<string>(initialFamily ?? "");
  const [deviceFilterId, setDeviceFilterId] = useState<string | null>(initialDeviceId);
  const [deviceCompliance, setDeviceCompliance] = useState<SourceRead<DeviceComplianceResult> | "loading" | null>(null);
  const [expandedControlId, setExpandedControlId] = useState<string | null>(null);
  const [controlDetails, setControlDetails] = useState<Record<string, SourceRead<ComplianceControlDetail> | "loading">>({});
  const [reloadTick, setReloadTick] = useState<number>(0);

  useEffect(() => {
    setDeviceFilterId(initialDeviceId);
  }, [initialDeviceId]);

  useEffect(() => {
    setFamilyFilter(initialFamily ?? "");
  }, [initialFamily]);

  useEffect(() => {
    let active = true;
    setLoading(true);

    const ovP: Promise<SourceRead<ComplianceOverview>> = getComplianceOverview().then(
      (v) => (v && typeof v === "object" ? ({ state: "ok", value: v } as const) : ({ state: "failed" } as const)),
      (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
    );

    const ctrlP: Promise<SourceRead<readonly ComplianceControlItem[]>> =
      sharedControls.state === "ok" && reloadTick === 0
        ? Promise.resolve(sharedControls)
        : getComplianceControls().then(
            (r) => (Array.isArray(r?.controls) ? ({ state: "ok", value: r.controls } as const) : ({ state: "failed" } as const)),
            (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
          );

    Promise.all([ovP, ctrlP]).then(([ov, ct]) => {
      if (!active) return;
      setCompOverview(ov);
      setLocalControls(ct);
      setLoading(false);
    });

    return () => {
      active = false;
    };
  }, [sharedControls, reloadTick]);

  useEffect(() => {
    if (!deviceFilterId) {
      setDeviceCompliance(null);
      return;
    }
    let active = true;
    setDeviceCompliance("loading");
    getDeviceCompliance(deviceFilterId)
      .then((res) => {
        if (active) setDeviceCompliance({ state: "ok", value: res });
      })
      .catch((err: unknown) => {
        if (active) setDeviceCompliance({ state: isRestricted(err) ? "restricted" : "failed" });
      });
    return () => {
      active = false;
    };
  }, [deviceFilterId]);

  const effectiveControls: readonly ComplianceControlItem[] =
    localControls.state === "ok"
      ? localControls.value
      : sharedControls.state === "ok"
        ? sharedControls.value
        : [];

  const synthesizedOverview: ComplianceOverview | null = useMemo(() => {
    if (compOverview.state === "ok") return compOverview.value;
    if (overview?.compliance?.state === "OK") {
      return {
        total_firewalls: overview.compliance.of_firewalls ?? 0,
        evaluated_firewalls: overview.compliance.evaluated ?? 0,
        assured_compliance_pct: overview.compliance.assured_pct ?? 0,
        evidence_coverage_pct: overview.compliance.coverage_pct ?? 0,
        observed_compliance_pct: overview.compliance.observed_pct ?? 0,
        critical_deficiencies: overview.compliance.critical_deficiencies ?? 0,
        data_gaps: overview.compliance.data_gaps ?? 0,
        frameworks: (overview.compliance.frameworks ?? []).map((f) => ({
          framework: f.name,
          score_pct: f.score_pct,
          total_controls: f.total,
          pass_count: f.pass,
          fail_count: f.fail,
          data_unavailable_count: f.unavailable,
        })),
      };
    }
    return null;
  }, [compOverview, overview]);

  const complianceAt = overview?.evidence?.compliance?.at ?? null;
  const now = model?.now ?? new Date();

  const lensModel = useMemo(
    () =>
      buildComplianceLensModel({
        complianceOverview: synthesizedOverview,
        controls: effectiveControls,
        selectedFramework,
        familyFilter,
        complianceAt,
        model,
      }),
    [synthesizedOverview, effectiveControls, selectedFramework, familyFilter, complianceAt, model],
  );

  const toggleExpandControl = (controlId: string) => {
    if (expandedControlId === controlId) {
      setExpandedControlId(null);
      return;
    }
    setExpandedControlId(controlId);
    if (!controlDetails[controlId]) {
      setControlDetails((prev) => ({ ...prev, [controlId]: "loading" }));
      getComplianceControlDetail(controlId)
        .then((det) => {
          setControlDetails((prev) => ({ ...prev, [controlId]: { state: "ok", value: det } }));
        })
        .catch((err: unknown) => {
          setControlDetails((prev) => ({
            ...prev,
            [controlId]: { state: isRestricted(err) ? "restricted" : "failed" },
          }));
        });
    }
  };

  const exportAuditCsv = () => {
    const esc = (v: unknown) => `"${String(v ?? "").replace(/"/g, '""')}"`;
    const iso = new Date().toISOString();
    const lines: string[] = [
      `# ${PRODUCT_NAME} compliance audit report`,
      `# as of,${esc(iso)} (UTC)`,
      `# names,${esc(responsesAreMasked() ? "masked (aiview pseudonyms)" : "as recorded")}`,
      "",
      [
        "control_id",
        "title",
        "category",
        "severity",
        "status",
        "firewalls",
        "pass",
        "fail",
        "unavailable",
        "failing_firewalls",
        "data_gap_reason",
      ].join(","),
    ];
    for (const c of effectiveControls) {
      lines.push(
        [
          c.control_id,
          c.title,
          c.category,
          c.severity,
          c.status,
          c.target_device_count,
          c.pass_count,
          c.fail_count,
          c.data_unavailable_count,
          (c.affected_devices ?? []).join("; "),
          (c.data_unavailable_count ?? 0) > 0 ? (c.missing_reason ?? "evidence not collected") : "",
        ]
          .map(esc)
          .join(","),
      );
    }
    const blob = new Blob([lines.join("\n")], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `nexus-compliance-audit-${formatUtc(iso, false).replace(/[: ]/g, "-")}Z.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const readsFailed =
    !loading &&
    synthesizedOverview === null &&
    localControls.state !== "ok" &&
    sharedControls.state !== "ok";
  const readsRestricted =
    compOverview.state === "restricted" || localControls.state === "restricted";

  const sel = lensModel.selected;

  return (
    <Box data-testid="estate-compliance-screen">
      {/* Page Header */}
      <Box
        sx={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "flex-end",
          gap: "16px",
          mb: "16px",
        }}
      >
        <Box sx={{ minWidth: 0, flex: "1 1 540px" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
            <Typography
              sx={{
                fontSize: "11px",
                letterSpacing: "0.09em",
                textTransform: "uppercase",
                color: ev.ink3,
                fontWeight: 650,
              }}
            >
              Estate · controls lens
            </Typography>
            <EvidenceStamp
              at={complianceAt}
              source="compliance"
              now={now}
              missingLabel="UNKNOWN (not recorded)"
            />
          </Box>
          <Typography component="h1" sx={{ fontSize: "26px", fontWeight: 680, lineHeight: 1.2, mt: "2px" }}>
            Which controls fail, where, and how much do we not know?
          </Typography>
          <Typography sx={{ fontSize: "14.5px", color: ev.ink2, mt: "6px", maxWidth: "840px" }}>
            One honest bar instead of a pass rate: every check is one control on one firewall, and a check without
            evidence is shown as its own hatched share — never merged into passed.
          </Typography>
        </Box>

        <Box sx={{ display: "flex", gap: "8px", flexWrap: "wrap", ml: "auto" }}>
          <Box
            component="button"
            type="button"
            disabled={effectiveControls.length === 0}
            onClick={exportAuditCsv}
            sx={{
              height: "34px",
              px: "14px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontSize: "13px",
              fontWeight: 580,
              cursor: effectiveControls.length > 0 ? "pointer" : "not-allowed",
              opacity: effectiveControls.length > 0 ? 1 : 0.6,
              fontFamily: ESTATE_SANS,
            }}
          >
            Auditor CSV pack
          </Box>
          <Box
            component="a"
            href={standardComplianceHref({ deviceId: deviceFilterId })}
            sx={{
              display: "inline-flex",
              alignItems: "center",
              height: "34px",
              px: "14px",
              borderRadius: "8px",
              border: `1px solid ${ev.accent}`,
              bgcolor: ev.accent,
              color: ev.accentInk,
              fontSize: "13px",
              fontWeight: 620,
              textDecoration: "none",
              "&:hover": { opacity: 0.92, textDecoration: "none" },
            }}
          >
            Open standard Compliance →
          </Box>
        </Box>
      </Box>

      {/* Optional single-unit scope banner when navigated with ?device=<id> */}
      {deviceFilterId ? (
        <EstateCard sx={{ p: "12px 18px", mb: "14px" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap" }}>
            <StateBadge state="watch" word="Scoped unit" dense />
            {deviceCompliance === "loading" ? (
              <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>Reading unit control checks…</Typography>
            ) : deviceCompliance && deviceCompliance.state === "ok" ? (
              <Typography sx={{ fontSize: "13px", color: ev.ink }}>
                <b style={{ fontFamily: ESTATE_MONO }}>{deviceCompliance.value.hostname}</b>:{" "}
                <b style={{ color: ev.ok }}>{deviceCompliance.value.passCount}</b> pass ·{" "}
                <b style={{ color: ev.act }}>{deviceCompliance.value.failCount}</b> fail ·{" "}
                <b style={{ color: ev.unk }}>{deviceCompliance.value.dataUnavailableCount}</b> no evidence
              </Typography>
            ) : (
              <StateBadge state="unk" word="Unit compliance UNKNOWN" dense />
            )}
            <Box
              component="button"
              type="button"
              onClick={() => {
                setDeviceFilterId(null);
                writeUrlParams({ device: null });
              }}
              sx={{
                ml: "auto",
                border: `1px solid ${ev.lineStrong}`,
                bgcolor: ev.surface,
                color: ev.ink,
                borderRadius: "999px",
                px: "10px",
                py: "3px",
                fontSize: "12px",
                cursor: "pointer",
                fontFamily: ESTATE_SANS,
              }}
            >
              Show entire estate ×
            </Box>
          </Box>
        </EstateCard>
      ) : null}

      {readsFailed ? (
        <EstateCard sx={{ p: 3 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", mb: 1.5 }}>
            <StateBadge state={readsRestricted ? "na" : "unk"} word={readsRestricted ? "Restricted" : "UNKNOWN"} />
            <Typography sx={{ fontWeight: 650 }}>
              {readsRestricted
                ? "Compliance posture is restricted for this account."
                : "Compliance overview and controls could not be read — control posture is UNKNOWN."}
            </Typography>
          </Box>
          {!readsRestricted ? (
            <Box
              component="button"
              type="button"
              onClick={() => setReloadTick((n) => n + 1)}
              sx={{
                height: "32px",
                px: "12px",
                borderRadius: "8px",
                border: `1px solid ${ev.lineStrong}`,
                bgcolor: ev.surface,
                color: ev.ink,
                fontWeight: 600,
                cursor: "pointer",
              }}
            >
              Retry
            </Box>
          ) : null}
        </EstateCard>
      ) : (
        <>
          {/* Framework Chips */}
          <Box sx={{ display: "flex", gap: "6px", flexWrap: "wrap", alignItems: "center", mb: "14px" }}>
            <span style={{ fontSize: "12.5px", color: ev.ink3, marginRight: "4px" }}>Framework</span>
            {lensModel.frameworks.map((fw) => {
              const on = fw.key === sel.key;
              return (
                <Box
                  key={fw.key}
                  component="button"
                  type="button"
                  aria-pressed={on}
                  onClick={() => setSelectedFramework(fw.key)}
                  sx={{
                    height: "27px",
                    px: "11px",
                    borderRadius: "999px",
                    border: `1px solid ${on ? ev.ink : ev.line}`,
                    bgcolor: ev.surface,
                    color: on ? ev.ink : ev.ink2,
                    fontWeight: on ? 650 : 500,
                    fontSize: "12.5px",
                    boxShadow: on ? `inset 0 0 0 1px ${ev.ink}` : "none",
                    cursor: "pointer",
                    fontFamily: ESTATE_SANS,
                  }}
                >
                  {fw.label}
                </Box>
              );
            })}
            {familyFilter ? (
              <Box
                component="button"
                type="button"
                onClick={() => {
                  setFamilyFilter("");
                  writeUrlParams({ family: null });
                }}
                sx={{
                  height: "27px",
                  px: "11px",
                  borderRadius: "999px",
                  border: `1px solid ${ev.accent}`,
                  bgcolor: ev.accentBg,
                  color: ev.accent,
                  fontWeight: 650,
                  fontSize: "12.5px",
                  cursor: "pointer",
                  fontFamily: ESTATE_MONO,
                }}
              >
                Family: {familyFilter} ×
              </Box>
            ) : null}
          </Box>

          {/* Honest Bar Card */}
          <EstateCard sx={{ p: "16px 18px" }}>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", mb: "10px", flexWrap: "wrap" }}>
              <Typography component="h2" sx={{ fontSize: "18px", fontWeight: 680 }}>
                {sel.label}
              </Typography>
              <Typography sx={{ fontSize: "13px", color: ev.ink3 }}>
                {sel.total.toLocaleString("en-US")} checks
                {lensModel.evaluatedFirewalls !== null && lensModel.totalFirewalls !== null
                  ? ` across ${lensModel.evaluatedFirewalls} of ${lensModel.totalFirewalls} firewalls`
                  : ""}
              </Typography>
              <Box sx={{ ml: "auto" }}>
                <EvidenceStamp
                  at={complianceAt}
                  source="compliance read"
                  now={now}
                  missingLabel="UNKNOWN (not recorded)"
                />
              </Box>
            </Box>

            {sel.total <= 0 ? (
              <Box sx={{ display: "flex", alignItems: "center", gap: "10px", py: 1 }}>
                <StateBadge state="unk" word="UNKNOWN" />
                <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>
                  {loading ? "Reading control evaluations…" : "No evaluated control checks recorded for this framework."}
                </Typography>
              </Box>
            ) : (
              <Box
                role="img"
                aria-label={`${sel.label} honest bar: pass ${sel.passPct}%, fail ${sel.failPct}%, no evidence ${sel.unavailablePct}%`}
                data-testid="compliance-honest-bar"
                sx={{
                  display: "flex",
                  height: "28px",
                  borderRadius: "6px",
                  overflow: "hidden",
                  bgcolor: ev.surface2,
                  gap: "2px",
                }}
              >
                {sel.pass > 0 ? (
                  <Box
                    component="span"
                    data-bar-segment="pass"
                    sx={{
                      width: `${(sel.pass / sel.total) * 100}%`,
                      bgcolor: ev.tOk,
                      color: ev.tGlyph,
                      display: "flex",
                      alignItems: "center",
                      px: "10px",
                      fontFamily: ESTATE_MONO,
                      fontWeight: 700,
                      fontSize: "12.5px",
                      whiteSpace: "nowrap",
                      overflow: "hidden",
                    }}
                  >
                    ● pass {sel.passPct.toFixed(1)}%
                  </Box>
                ) : null}
                {sel.fail > 0 ? (
                  <Box
                    component="span"
                    data-bar-segment="fail"
                    sx={{
                      width: `${(sel.fail / sel.total) * 100}%`,
                      bgcolor: ev.tAct,
                      color: ev.tGlyph,
                      display: "flex",
                      alignItems: "center",
                      px: "10px",
                      fontFamily: ESTATE_MONO,
                      fontWeight: 700,
                      fontSize: "12.5px",
                      whiteSpace: "nowrap",
                      overflow: "hidden",
                    }}
                  >
                    ! fail {sel.failPct.toFixed(1)}%
                  </Box>
                ) : null}
                {sel.unavailable > 0 ? (
                  <Box
                    component="span"
                    data-bar-segment="unavailable"
                    data-hatched="true"
                    sx={{
                      width: `${(sel.unavailable / sel.total) * 100}%`,
                      backgroundImage: ESTATE_UNKNOWN_HATCH,
                      border: `1px dashed ${ev.tUnk}`,
                      display: "flex",
                      alignItems: "center",
                      px: "8px",
                      fontFamily: ESTATE_MONO,
                      fontWeight: 700,
                      fontSize: "12px",
                      whiteSpace: "nowrap",
                      overflow: "hidden",
                    }}
                  >
                    <Box
                      component="span"
                      sx={{
                        bgcolor: ev.surface,
                        color: ev.unk,
                        px: "6px",
                        py: "1px",
                        borderRadius: "4px",
                      }}
                    >
                      ? no evidence {sel.unavailablePct.toFixed(1)}%
                    </Box>
                  </Box>
                ) : null}
              </Box>
            )}

            <Box
              sx={{
                display: "grid",
                gridTemplateColumns: { xs: "1fr", sm: "repeat(3, minmax(0, 1fr))" },
                gap: "16px",
                mt: "14px",
              }}
            >
              <Box>
                <Box sx={{ display: "flex", alignItems: "baseline", gap: "8px" }}>
                  <Typography sx={{ fontSize: "22px", fontWeight: 700, fontFamily: ESTATE_MONO, color: ev.ok }}>
                    {sel.pass.toLocaleString("en-US")}
                  </Typography>
                  <EvidenceStamp at={complianceAt} now={now} missingLabel="UNKNOWN" />
                </Box>
                <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>checks pass with evidence</Typography>
              </Box>

              <Box>
                <Box sx={{ display: "flex", alignItems: "baseline", gap: "8px" }}>
                  <Typography sx={{ fontSize: "22px", fontWeight: 700, fontFamily: ESTATE_MONO, color: ev.act }}>
                    {sel.fail.toLocaleString("en-US")}
                  </Typography>
                  <EvidenceStamp at={complianceAt} now={now} missingLabel="UNKNOWN" />
                </Box>
                <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>
                  checks fail · <b style={{ color: ev.act }}>{sel.criticalChecksFail.toLocaleString("en-US")}</b> on critical controls
                </Typography>
              </Box>

              <Box>
                <Box sx={{ display: "flex", alignItems: "baseline", gap: "8px" }}>
                  <Typography sx={{ fontSize: "22px", fontWeight: 700, fontFamily: ESTATE_MONO, color: ev.unk }}>
                    {sel.unavailable.toLocaleString("en-US")}
                  </Typography>
                  <StateBadge state="unk" word="UNKNOWN" dense />
                </Box>
                <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>
                  checks have no evidence yet (never counted as pass)
                </Typography>
              </Box>
            </Box>
          </EstateCard>

          {/* Middle Grid: Families by Vendor Heatmap + Why No Evidence / Scope */}
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: { xs: "1fr", lg: "minmax(0, 1.45fr) minmax(0, 1fr)" },
              gap: "16px",
              mt: "16px",
              alignItems: "start",
            }}
          >
            {/* Left: Families by vendor heatmap */}
            <EstateCard>
              <Box sx={{ display: "flex", alignItems: "baseline", gap: "10px", px: "18px", pt: "14px", pb: "10px", flexWrap: "wrap" }}>
                <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
                  Families by vendor
                </Typography>
                <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                  failing share of evidenced checks; bar shows pass · fail · no evidence (click family to filter)
                </Typography>
                <Box sx={{ ml: "auto" }}>
                  <EvidenceStamp at={complianceAt} now={now} missingLabel="UNKNOWN" />
                </Box>
              </Box>

              <Box sx={{ overflowX: "auto", px: "18px", pb: "16px" }}>
                <Box
                  component="table"
                  aria-label="Control families by vendor"
                  sx={{
                    width: "100%",
                    borderCollapse: "collapse",
                    fontSize: "12.5px",
                    "& th": {
                      textAlign: "left",
                      fontSize: "11px",
                      letterSpacing: "0.07em",
                      textTransform: "uppercase",
                      color: ev.ink3,
                      fontWeight: 650,
                      p: "8px 8px",
                      borderBottom: `1px solid ${ev.line}`,
                      whiteSpace: "nowrap",
                    },
                    "& td": {
                      p: "8px 8px",
                      borderBottom: `1px solid ${ev.line}`,
                      verticalAlign: "top",
                    },
                    "& tr:last-of-type td": { borderBottom: 0 },
                  }}
                >
                  <thead>
                    <tr>
                      <th>Control family</th>
                      {lensModel.vendorColumns.map((vc) => (
                        <th key={vc.key}>{vc.label}</th>
                      ))}
                      <th style={{ textAlign: "right" }}>Checks</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lensModel.familyRows.length === 0 ? (
                      <tr>
                        <td colSpan={lensModel.vendorColumns.length + 2} style={{ color: ev.ink3 }}>
                          No control family breakdown recorded yet.
                        </td>
                      </tr>
                    ) : (
                      lensModel.familyRows.map((row) => {
                        const activeFam = familyFilter === row.familyCode;
                        return (
                          <tr key={row.familyKey} data-family-row={row.familyCode}>
                            <td>
                              <Box
                                component="button"
                                type="button"
                                aria-pressed={activeFam}
                                onClick={() => setFamilyFilter(activeFam ? "" : row.familyCode)}
                                sx={{
                                  border: 0,
                                  background: "none",
                                  p: 0,
                                  textAlign: "left",
                                  cursor: "pointer",
                                  color: activeFam ? ev.accent : ev.ink,
                                  fontFamily: ESTATE_SANS,
                                  "&:hover": { color: ev.accent },
                                }}
                              >
                                <b style={{ fontFamily: ESTATE_MONO }}>{row.familyCode}</b>{" "}
                                {row.familyTitle ? <span style={{ color: ev.ink2 }}>{row.familyTitle}</span> : null}
                              </Box>
                            </td>
                            {lensModel.vendorColumns.map((vc) => {
                              const cell = row.cells.get(vc.key);
                              if (!cell || cell.total === 0) {
                                return (
                                  <td key={vc.key} style={{ minWidth: "118px", color: ev.ink3 }}>
                                    <StateBadge state="na" word="No checks" dense />
                                  </td>
                                );
                              }
                              return (
                                <td key={vc.key} style={{ minWidth: "126px" }}>
                                  {cell.evidenced === 0 ? (
                                    <StateBadge state="unk" word="UNKNOWN (0 evidenced)" dense />
                                  ) : (
                                    <Box sx={{ display: "flex", alignItems: "center", gap: "6px" }}>
                                      <TileSwatch state={cell.badgeState} size={13} />
                                      <span
                                        style={{
                                          fontFamily: ESTATE_MONO,
                                          fontWeight: 650,
                                          color:
                                            cell.badgeState === "act"
                                              ? ev.act
                                              : cell.badgeState === "watch"
                                                ? ev.watch
                                                : ev.ok,
                                        }}
                                      >
                                        {cell.failPctOfEvidenced}% fail
                                      </span>
                                    </Box>
                                  )}
                                  <Box sx={{ mt: "5px" }}>
                                    <HonestBar
                                      height={8}
                                      ariaLabel={`${row.familyCode} on ${vc.label}`}
                                      segments={[
                                        { key: "pass", label: "Pass", count: cell.pass, state: "ok" },
                                        { key: "fail", label: "Fail", count: cell.fail, state: "act" },
                                        { key: "unk", label: "No evidence", count: cell.unavailable, state: "unk" },
                                      ]}
                                    />
                                  </Box>
                                  <Typography sx={{ fontSize: "11px", color: ev.ink3, mt: "3px" }}>
                                    {cell.fail} of {cell.evidenced} · {cell.unavailable} no evidence
                                  </Typography>
                                </td>
                              );
                            })}
                            <td style={{ textAlign: "right", fontFamily: ESTATE_MONO, fontWeight: 650 }}>
                              {row.totalChecks}
                            </td>
                          </tr>
                        );
                      })
                    )}
                  </tbody>
                </Box>
              </Box>
            </EstateCard>

            {/* Right: Data Gaps + Scope */}
            <Box sx={{ display: "flex", flexDirection: "column", gap: "16px" }}>
              <EstateCard>
                <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", px: "18px", pt: "14px", pb: "8px", gap: 1 }}>
                  <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>
                    Why {sel.unavailable.toLocaleString("en-US")} checks have no evidence
                  </Typography>
                  <EvidenceStamp at={complianceAt} now={now} missingLabel="UNKNOWN" />
                </Box>
                <Box sx={{ px: "18px", pb: "14px" }}>
                  {lensModel.dataGapReasons.length === 0 ? (
                    <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>
                      {sel.unavailable > 0
                        ? `${sel.unavailable} checks lack recorded evidence.`
                        : "Every check in this scope has recorded evidence."}
                    </Typography>
                  ) : (
                    lensModel.dataGapReasons.map((g, idx) => (
                      <Box
                        key={g.reason}
                        sx={{
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          gap: "12px",
                          py: "8px",
                          borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                          fontSize: "13px",
                        }}
                      >
                        <Box sx={{ minWidth: 0 }}>
                          <b style={{ color: ev.ink }}>{g.reason}</b>
                          <Typography sx={{ fontSize: "11.5px", color: ev.ink3 }}>
                            across {g.controlCount} {g.controlCount === 1 ? "control" : "controls"}
                          </Typography>
                        </Box>
                        <StateBadge state="unk" word={`${g.checksCount} UNKNOWN`} dense />
                      </Box>
                    ))
                  )}
                </Box>
              </EstateCard>

              <EstateCard>
                <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", px: "18px", pt: "14px", pb: "8px" }}>
                  <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>
                    Scope
                  </Typography>
                  <EvidenceStamp
                    at={complianceAt}
                    source="scope"
                    now={now}
                    missingLabel="not recorded"
                  />
                </Box>
                <Box sx={{ px: "18px", pb: "16px", fontSize: "13px", color: ev.ink2 }}>
                  <Typography sx={{ fontSize: "13px", mb: "8px" }}>
                    {lensModel.evaluatedFirewalls !== null && lensModel.totalFirewalls !== null ? (
                      <>
                        <b style={{ color: ev.ink }}>{lensModel.evaluatedFirewalls}</b> firewalls evaluated of{" "}
                        <b style={{ color: ev.ink }}>{lensModel.totalFirewalls}</b> in scope.
                      </>
                    ) : (
                      <>Firewall scope counts are UNKNOWN.</>
                    )}
                  </Typography>

                  {lensModel.unassessedFirewalls.length > 0 ? (
                    <Box sx={{ mb: "8px" }}>
                      <Typography sx={{ fontSize: "12.5px", color: ev.unk, fontWeight: 600, mb: "4px" }}>
                        {lensModel.unassessedFirewalls.length}{" "}
                        {lensModel.unassessedFirewalls.length === 1 ? "firewall is" : "firewalls are"} un-assessed or partly evidenced (first 12 listed, the rest counted):
                      </Typography>
                      <Box sx={{ display: "flex", flexWrap: "wrap", gap: "6px" }}>
                        {lensModel.unassessedFirewalls.slice(0, 12).map((u) => (
                          <Box
                            key={u.deviceId}
                            component="a"
                            href={dossierHref(u.deviceId)}
                            sx={{
                              fontFamily: ESTATE_MONO,
                              fontSize: "12px",
                              color: ev.accent,
                              textDecoration: "none",
                              "&:hover": { textDecoration: "underline" },
                            }}
                          >
                            {u.name}
                          </Box>
                        ))}
                        <MoreFooter shown={12} total={lensModel.unassessedFirewalls.length} noun="firewalls" testId="unassessed-more" />
                      </Box>
                    </Box>
                  ) : null}

                  <Box sx={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap", mt: "6px" }}>
                    <span>Evaluation timestamp:</span>
                    {complianceAt ? (
                      <EvidenceStamp at={complianceAt} now={now} />
                    ) : (
                      <StateBadge state="unk" word="UNKNOWN (not recorded)" dense />
                    )}
                  </Box>
                </Box>
              </EstateCard>
            </Box>
          </Box>

          {/* Bottom Section: Fix once, clear many */}
          <EstateCard sx={{ mt: "16px" }}>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "10px", flexWrap: "wrap" }}>
              <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
                Fix once, clear many
              </Typography>
              <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                controls ranked by how many firewalls one remediation would clear; UNKNOWN controls stay visible
              </Typography>
              <Box sx={{ ml: "auto" }}>
                <EvidenceStamp at={complianceAt} source="controls" now={now} missingLabel="UNKNOWN" />
              </Box>
            </Box>

            <Box sx={{ px: "18px", pb: "16px" }}>
              {lensModel.remediationRows.length === 0 ? (
                <Typography sx={{ fontSize: "13px", color: ev.ink2, py: 1 }}>
                  No failing or un-evidenced controls in the active filter.
                </Typography>
              ) : (
                lensModel.remediationRows.slice(0, 25).map((row, idx) => {
                  const c = row.control;
                  const isOpen = expandedControlId === c.control_id;
                  const detailState = controlDetails[c.control_id];
                  const passBoxCount = Math.min(20, c.pass_count ?? 0);
                  const unavailBoxCount = Math.min(20, c.data_unavailable_count ?? 0);

                  return (
                    <Box
                      key={c.control_id}
                      data-remediation-row={c.control_id}
                      sx={{
                        borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                        py: "11px",
                      }}
                    >
                      <Box
                        sx={{
                          display: "grid",
                          gridTemplateColumns: {
                            xs: "1fr",
                            md: "minmax(0, 1.4fr) 90px minmax(0, 1.1fr) auto",
                          },
                          gap: "14px",
                          alignItems: "center",
                        }}
                      >
                        <Box sx={{ minWidth: 0 }}>
                          <Box sx={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" }}>
                            <b style={{ fontSize: "13.5px", color: ev.ink }}>{c.title}</b>
                            <StateBadge state={row.badgeState} word={row.badgeWord} dense />
                          </Box>
                          <Typography sx={{ fontSize: "12px", color: ev.ink3, mt: "2px" }}>
                            <span style={{ fontFamily: ESTATE_MONO }}>{c.control_id}</span> · {row.frameworkRef} · {row.familyLabel}
                          </Typography>
                        </Box>

                        <Box sx={{ display: "flex", flexDirection: "column" }}>
                          <Typography
                            sx={{
                              fontFamily: ESTATE_MONO,
                              fontSize: "18px",
                              fontWeight: 700,
                              color: (c.fail_count ?? 0) > 0 ? ev.act : ev.unk,
                            }}
                          >
                            {(c.fail_count ?? 0) > 0 ? c.fail_count : c.data_unavailable_count ?? 0}
                          </Typography>
                          <span style={{ fontSize: "11px", color: ev.ink3 }}>
                            {(c.fail_count ?? 0) > 0 ? `of ${c.target_device_count} fail` : "no evidence"}
                          </span>
                        </Box>

                        <Box sx={{ display: "flex", flexWrap: "wrap", gap: "3px", alignItems: "center" }}>
                          {row.affectedUnits.slice(0, 24).map((u) => (
                            <UnitTile key={u.deviceId} unit={u} lens="compliance" size={13} now={now} />
                          ))}
                          <MoreFooter shown={24} total={row.affectedUnits.length} noun="units" testId="affected-more" />
                          {row.affectedUnits.length === 0 && (c.fail_count ?? 0) > 0
                            ? Array.from({ length: Math.min(20, c.fail_count) }).map((_, i) => (
                                <TileSwatch key={`f-${i}`} state="act" size={11} />
                              ))
                            : null}
                          {Array.from({ length: unavailBoxCount }).map((_, i) => (
                            <TileSwatch key={`u-${i}`} state="unk" size={11} />
                          ))}
                          {Array.from({ length: passBoxCount }).map((_, i) => (
                            <TileSwatch key={`p-${i}`} state="ok" size={11} />
                          ))}
                        </Box>

                        <Box sx={{ display: "flex", gap: "8px", alignItems: "center", justifyContent: "flex-end", flexWrap: "wrap" }}>
                          <EvidenceStamp at={complianceAt} now={now} missingLabel="UNKNOWN" />
                          <Box
                            component="button"
                            type="button"
                            aria-expanded={isOpen}
                            onClick={() => toggleExpandControl(c.control_id)}
                            sx={{
                              height: "28px",
                              px: "10px",
                              borderRadius: "8px",
                              border: `1px solid ${ev.lineStrong}`,
                              bgcolor: ev.surface,
                              color: ev.ink,
                              fontSize: "12px",
                              fontWeight: 580,
                              cursor: "pointer",
                              whiteSpace: "nowrap",
                              fontFamily: ESTATE_SANS,
                            }}
                          >
                            {isOpen
                              ? "Hide firewalls"
                              : `Show ${(c.fail_count ?? 0) > 0 ? c.fail_count : c.target_device_count} ${c.fail_count === 1 ? "firewall" : "firewalls"}`}
                          </Box>
                        </Box>
                      </Box>

                      {isOpen ? (
                        <Box
                          sx={{
                            mt: "10px",
                            p: "12px 14px",
                            borderRadius: "8px",
                            bgcolor: ev.surface2,
                            border: `1px solid ${ev.line}`,
                            fontSize: "12.5px",
                          }}
                        >
                          <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: "8px", flexWrap: "wrap", gap: 1 }}>
                            <span style={{ fontWeight: 600 }}>
                              Affected units for <span style={{ fontFamily: ESTATE_MONO }}>{c.control_id}</span>
                            </span>
                            <Box
                              component="a"
                              href={standardComplianceHref({ controlId: c.control_id })}
                              sx={{ color: ev.accent, fontWeight: 600, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
                            >
                              Open remediation detail in standard Compliance →
                            </Box>
                          </Box>

                          {detailState === "loading" ? (
                            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>Reading per-unit verdicts…</Typography>
                          ) : detailState && detailState.state === "ok" && detailState.value.devices.length > 0 ? (
                            <Box sx={{ display: "flex", flexDirection: "column", gap: "6px" }}>
                              {detailState.value.devices
                                .filter((d) => d.status !== "PASS")
                                .slice(0, 20)
                                .map((d) => {
                                  const bState = d.status === "FAIL" ? "act" : "unk";
                                  return (
                                    <Box
                                      key={d.device_id}
                                      sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}
                                    >
                                      <StateBadge state={bState} word={d.status === "FAIL" ? "Fail" : "UNKNOWN"} dense />
                                      <Box
                                        component="a"
                                        href={dossierHref(d.device_id)}
                                        sx={{ fontFamily: ESTATE_MONO, fontWeight: 600, color: ev.accent, textDecoration: "none" }}
                                      >
                                        {d.hostname ?? d.device_id}
                                      </Box>
                                      <span style={{ color: ev.ink2 }}>
                                        {d.observed_value ?? d.message ?? "No observed value"}
                                      </span>
                                    </Box>
                                  );
                                })}
                              <MoreFooter
                                shown={20}
                                total={detailState.value.devices.filter((d) => d.status !== "PASS").length}
                                noun="units"
                                testId="verdicts-more"
                              />
                            </Box>
                          ) : row.affectedUnits.length > 0 || row.unmatchedAffectedNames.length > 0 ? (
                            <Box sx={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
                              {row.affectedUnits.map((u) => (
                                <Box
                                  key={u.deviceId}
                                  component="a"
                                  href={dossierHref(u.deviceId)}
                                  sx={{ fontFamily: ESTATE_MONO, fontWeight: 600, color: ev.accent, textDecoration: "none" }}
                                >
                                  {u.name}
                                </Box>
                              ))}
                              {row.unmatchedAffectedNames.map((nm) => (
                                <span key={nm} style={{ fontFamily: ESTATE_MONO }}>{nm}</span>
                              ))}
                            </Box>
                          ) : (
                            <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                              {c.missing_reason ?? "No failing firewall names recorded."}
                            </Typography>
                          )}
                        </Box>
                      ) : null}
                    </Box>
                  );
                })
              )}
              <Box sx={{ pt: "10px" }}>
                <MoreFooter shown={25} total={lensModel.remediationRows.length} noun="controls" testId="remediation-more" />
              </Box>
            </Box>
          </EstateCard>
        </>
      )}
    </Box>
  );
}
