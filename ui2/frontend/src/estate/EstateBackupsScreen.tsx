import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import {
  getBackupPolicy,
  getBackupStoreQuotas,
  listJobs,
  type BackupPolicy,
  type BackupStoreQuotas,
  type JobEventView,
} from "../auth/adminApi";
import { sizeAnomalyWord } from "../screens/BackupDeviationCard";
import { LatestAttemptCell, useLatestBackupAttempts } from "../screens/BackupAttempts";
import type { SourceRead } from "../screens/overview/attentionRules";
import { formatBytes } from "../shell/bytes";
import { isRestricted } from "../shell/States";
import { formatStamp } from "../shell/time";
import {
  EstateCard,
  EvidenceStamp,
  MoreFooter,
  StateBadge,
} from "./EstatePrimitives";
import { dossierHref, standardBackupsHref } from "./estateRouting";
import { EstateBackupsAttemptsDrawer, type AttemptsUnit } from "./EstateBackupsAttemptsDrawer";
import {
  buildBackupsLensModel,
  type BackupNightCell,
  type EstateBackupRow,
  type NightCellState,
} from "./estateSlice2Model";
import {
  ESTATE_MONO,
  ESTATE_SANS,
  ESTATE_UNKNOWN_HATCH,
  ev,
} from "./estateTokens";
import { useEstateData } from "./useEstateData";

function NightStripCellView({ cell, unitName }: { readonly cell: BackupNightCell; readonly unitName: string }) {
  const sx = (() => {
    const base = {
      width: "13px",
      height: "19px",
      borderRadius: "2px",
      display: "inline-grid",
      placeItems: "center",
      fontFamily: ESTATE_MONO,
      fontSize: "9px",
      fontWeight: 800,
      lineHeight: 1,
      flexShrink: 0,
      boxSizing: "border-box" as const,
    };
    switch (cell.state) {
      case "stored":
        return { ...base, bgcolor: ev.tOk, color: ev.tGlyph };
      case "anomaly":
        return { ...base, bgcolor: ev.tWatch, color: ev.tGlyph };
      case "failed":
        return { ...base, bgcolor: ev.tAct, color: ev.tGlyph };
      case "unknown":
        return {
          ...base,
          backgroundImage: ESTATE_UNKNOWN_HATCH,
          border: `1px dashed ${ev.tUnk}`,
          color: ev.surface,
        };
      case "none":
        return {
          ...base,
          bgcolor: "transparent",
          border: `1.5px dashed ${ev.tNa}`,
          color: ev.ink3,
        };
    }
  })();

  const label = `${unitName} · ${cell.label}: ${cell.reason}`;
  return (
    <Tooltip title={label} disableInteractive>
      <Box
        component="i"
        role="img"
        aria-label={label}
        data-night-state={cell.state}
        data-hatched={cell.state === "unknown" ? "true" : undefined}
        sx={{ ...sx, fontStyle: "normal" }}
      >
        {cell.glyph ? (
          <Box
            component="span"
            aria-hidden
            sx={
              cell.state === "unknown"
                ? {
                    bgcolor: ev.tUnk,
                    color: ev.surface,
                    borderRadius: "2px",
                    px: "1px",
                    fontSize: "8px",
                    lineHeight: 1.1,
                  }
                : undefined
            }
          >
            {cell.glyph}
          </Box>
        ) : null}
      </Box>
    </Tooltip>
  );
}

function NightSwatch({ state }: { readonly state: NightCellState }) {
  const sample: BackupNightCell = {
    dayKey: "legend",
    label: "legend",
    state,
    glyph: state === "failed" ? "!" : state === "anomaly" ? "~" : state === "unknown" ? "?" : null,
    sizeBytes: null,
    validationLevel: null,
    reason: state,
  };
  return <NightStripCellView cell={sample} unitName="Legend" />;
}

function SizeSparkline({ row }: { readonly row: EstateBackupRow }) {
  const validPoints = row.sizeSeries
    .map((bytes, idx) => (bytes !== null ? { idx, bytes } : null))
    .filter((p): p is { idx: number; bytes: number } => p !== null);

  if (validPoints.length === 0) {
    return <span style={{ fontSize: "12px", color: ev.ink3 }}>no archive</span>;
  }

  if (validPoints.length === 1 && !row.sizeDeviationLabel) {
    return (
      <span style={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink2 }}>
        1 archive ({formatBytes(validPoints[0].bytes)})
      </span>
    );
  }

  const values = validPoints.map((p) => p.bytes);
  const minVal = Math.min(...values);
  const maxVal = Math.max(...values);
  const base = row.medianSizeBytes ?? validPoints[0].bytes;
  const lo = Math.min(minVal, base * 0.85);
  const hi = Math.max(maxVal, base * 1.15, lo + 1);

  const coords = validPoints.map((p) => {
    const x = (p.idx * 112) / 13 + 4;
    const y = 22 - ((p.bytes - lo) / (hi - lo)) * 18;
    return { x, y: Math.max(3, Math.min(23, y)), bytes: p.bytes };
  });

  const pathD = coords
    .map((c, i) => `${i === 0 ? "M" : "L"}${c.x.toFixed(1)} ${c.y.toFixed(1)}`)
    .join(" ");
  const lastCoord = coords[coords.length - 1];

  return (
    <Box sx={{ display: "inline-flex", alignItems: "center", gap: "8px" }}>
      {coords.length >= 2 ? (
        <Box
          component="svg"
          viewBox="0 0 120 26"
          role="img"
          aria-label={`${row.unit.name} archive size trend`}
          sx={{ width: "112px", height: "24px", display: "block", flexShrink: 0 }}
        >
          <path d={pathD} fill="none" stroke={ev.ink3} strokeWidth={1.5} />
          <circle
            cx={lastCoord.x}
            cy={lastCoord.y}
            r={3.5}
            fill={row.hasSizeDeviation ? ev.tWatch : ev.tOk}
          />
        </Box>
      ) : null}
      {row.sizeDeviationLabel ? (
        <Box
          component="span"
          data-testid="backup-size-deviation"
          sx={{
            fontFamily: ESTATE_MONO,
            fontSize: "11.5px",
            fontWeight: row.hasSizeDeviation ? 700 : 500,
            color: row.hasSizeDeviation ? ev.watch : ev.ink3,
            whiteSpace: "nowrap",
          }}
        >
          {row.sizeDeviationLabel}
        </Box>
      ) : null}
    </Box>
  );
}

type BackupFilterMode = "all" | "attention" | "deviations" | "off";

const FAILED_ATTEMPT_CAP = 12;

/**
 * Units whose latest attempt is FAILED, named with that attempt (standard Backups parity, via the batched latest-attempt
 * read). Read-only; the rows come from the lens model, so the count matches "failed their latest attempt".
 */
function LatestFailedAttempts({ rows }: { readonly rows: readonly EstateBackupRow[] }) {
  const deviceIds = useMemo(() => rows.map((r) => r.unit.deviceId), [rows]);
  const attempts = useLatestBackupAttempts(deviceIds);
  if (rows.length === 0) return null;

  const shown = rows.slice(0, FAILED_ATTEMPT_CAP);
  const states = deviceIds.map((id) => attempts.get(id));
  const loading = states.some((v) => v === undefined || v === "loading");
  const failedRead = states.some((v) => v === "error");

  return (
    <Box data-testid="latest-failed-attempts" sx={{ mt: "16px" }}>
    <EstateCard>
      <Box sx={{ px: "18px", pt: "14px", pb: "10px" }}>
        <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
          Latest attempt failed
        </Typography>
        <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mt: "2px" }}>
          {rows.length} {rows.length === 1 ? "unit" : "units"} whose latest backup attempt is FAILED (same count as above).
        </Typography>
        {loading && !failedRead ? (
          <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mt: "6px" }}>Loading latest attempts…</Typography>
        ) : null}
        {failedRead ? (
          <Box sx={{ mt: "6px" }}>
            <StateBadge state="unk" word="UNKNOWN" dense />{" "}
            <Typography component="span" sx={{ fontSize: "12.5px", color: ev.ink2 }}>
              the latest attempts could not be read
            </Typography>
          </Box>
        ) : null}
      </Box>
      <Box sx={{ display: "flex", flexDirection: "column", gap: "6px", px: "18px", pb: "14px" }}>
        {shown.map((r) => (
          <Box
            key={r.unit.deviceId}
            data-failed-attempt-row={r.unit.name}
            sx={{ display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap", fontSize: "12.5px" }}
          >
            <Box
              component="a"
              href={dossierHref(r.unit.deviceId)}
              sx={{ fontFamily: ESTATE_MONO, fontWeight: 650, color: ev.accent, textDecoration: "none", minWidth: "140px" }}
            >
              {r.unit.name}
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <LatestAttemptCell latest={attempts.get(r.unit.deviceId) ?? "loading"} />
            </Box>
          </Box>
        ))}
        <Box>
          <MoreFooter shown={shown.length} total={rows.length} noun="failed units" testId="failed-attempts-more" />
        </Box>
      </Box>
    </EstateCard>
    </Box>
  );
}

export function EstateBackupsScreen({
  initialQuery = "",
}: {
  readonly initialQuery?: string;
}) {
  const { loading, fatalError, overview, backups, model, refresh } = useEstateData();
  const [filterMode, setFilterMode] = useState<BackupFilterMode>("all");
  const [query, setQuery] = useState<string>(initialQuery);
  const [attemptsUnit, setAttemptsUnit] = useState<AttemptsUnit | null>(null);
  const [policy, setPolicy] = useState<SourceRead<BackupPolicy>>({ state: "skipped" });
  const [quotas, setQuotas] = useState<SourceRead<BackupStoreQuotas>>({ state: "skipped" });
  const [backupJobs, setBackupJobs] = useState<SourceRead<readonly JobEventView[]>>({ state: "skipped" });

  useEffect(() => {
    setQuery(initialQuery);
  }, [initialQuery]);

  useEffect(() => {
    let active = true;
    const sinceIso = new Date(Date.now() - 14 * 86_400_000).toISOString();

    const policyP: Promise<SourceRead<BackupPolicy>> = getBackupPolicy().then(
      (p) => (p && typeof p === "object" ? ({ state: "ok", value: p } as const) : ({ state: "failed" } as const)),
      (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
    );

    const quotasP: Promise<SourceRead<BackupStoreQuotas>> = getBackupStoreQuotas().then(
      (q) => (Array.isArray(q?.stores) ? ({ state: "ok", value: q } as const) : ({ state: "failed" } as const)),
      (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
    );

    const jobsP: Promise<SourceRead<readonly JobEventView[]>> = listJobs({
      job_type: "backup",
      since: sinceIso,
      page_size: 400,
    }).then(
      (res) => (Array.isArray(res?.items) ? ({ state: "ok", value: res.items } as const) : ({ state: "failed" } as const)),
      (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
    );

    Promise.all([policyP, quotasP, jobsP]).then(([pol, qt, jb]) => {
      if (!active) return;
      setPolicy(pol);
      setQuotas(qt);
      setBackupJobs(jb);
    });

    return () => {
      active = false;
    };
  }, []);

  const now = model?.now ?? new Date();
  const backupEvidenceAt = overview?.evidence?.backup?.at ?? null;

  const lensModel = useMemo(
    () =>
      buildBackupsLensModel({
        now,
        estateUnits: model?.units ?? [],
        backupsRead: backups,
        policy,
        quotas,
        backupJobs,
        backupEvidenceAt,
      }),
    [now, model?.units, backups, policy, quotas, backupJobs, backupEvidenceAt],
  );

  const failedAttemptRows = useMemo(
    () => lensModel.rows.filter((r) => r.lastArchiveAt !== null && r.state === "act"),
    [lensModel.rows],
  );

  const attentionCount = useMemo(
    () => lensModel.rows.filter((r) => r.state === "act" || r.state === "unk" || r.state === "watch").length,
    [lensModel.rows],
  );

  const visibleRows = useMemo(() => {
    const q = query.trim().toUpperCase();
    return lensModel.rows.filter((r) => {
      if (filterMode === "off") {
        if (r.state !== "na") return false;
      } else if (filterMode === "attention") {
        if (r.state === "ok" || r.state === "na") return false;
      } else if (filterMode === "deviations") {
        if (!r.hasSizeDeviation) return false;
      } else {
        if (r.state === "na") return false;
      }

      if (!q) return true;
      return (
        r.unit.name.toUpperCase().includes(q) ||
        (r.unit.clusterRef ?? "").toUpperCase().includes(q) ||
        r.unit.vendorName.toUpperCase().includes(q)
      );
    });
  }, [lensModel.rows, filterMode, query]);

  if (fatalError) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 1 }}>
          Backups
        </Typography>
        <Box sx={{ mb: 1.5 }}>
          <StateBadge state={fatalError.restricted ? "na" : "unk"} word={fatalError.restricted ? "Restricted" : "UNKNOWN"} />
        </Box>
        <Typography sx={{ color: ev.ink2, mb: 2 }}>{fatalError.message}</Typography>
        {!fatalError.restricted ? (
          <Box
            component="button"
            type="button"
            onClick={refresh}
            sx={{
              height: "34px",
              px: "14px",
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
    );
  }

  if (loading && !model) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 0.5 }}>
          Backups
        </Typography>
        <Typography sx={{ color: ev.ink2 }}>Reading backup store manifests, deviations, and nightly runs…</Typography>
      </EstateCard>
    );
  }

  const openSizeAnomalies = backups.state === "ok" ? (backups.value.deviations?.size_anomalies ?? []) : [];

  return (
    <Box data-testid="estate-backups-screen">
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
              Estate · backups lens
            </Typography>
            <EvidenceStamp
              at={lensModel.lastFleetRunAt}
              source="backup"
              now={now}
              missingLabel="evidence time unknown"
            />
          </Box>
          <Typography component="h1" sx={{ fontSize: "26px", fontWeight: 680, lineHeight: 1.2, mt: "2px" }}>
            Is there a good, recent backup of everything that matters?
          </Typography>
          {backups.state !== "ok" ? (
            <Box sx={{ display: "flex", alignItems: "center", gap: "10px", mt: "6px", flexWrap: "wrap" }}>
              <StateBadge
                state={backups.state === "restricted" ? "na" : "unk"}
                word={backups.state === "restricted" ? "Restricted" : "UNKNOWN"}
              />
              <Typography sx={{ fontSize: "14.5px", color: ev.ink2 }}>
                Backup store manifests could not be read — archive states remain UNKNOWN.
              </Typography>
            </Box>
          ) : (
            <Typography sx={{ fontSize: "14.5px", color: ev.ink2, mt: "6px", maxWidth: "860px" }}>
              <Box component="b" sx={{ color: ev.ink }}>
                {lensModel.storedCount} of {lensModel.targetCount}
              </Box>{" "}
              backup targets hold a stored archive ({lensModel.validatedCount} validated).{" "}
              <Box component="b" sx={{ color: lensModel.neverBackedUpCount > 0 ? ev.act : ev.ink2 }}>
                {lensModel.neverBackedUpCount} have never had one
              </Box>{" "}
              and{" "}
              <Box component="b" sx={{ color: lensModel.failedRecentCount > 0 ? ev.act : ev.ink2 }}>
                {lensModel.failedRecentCount} failed their latest attempt
              </Box>
              .{" "}
              <Box component="b" sx={{ color: lensModel.sizeDeviationCount > 0 ? ev.watch : ev.ink2 }}>
                {lensModel.sizeDeviationCount} changed size sharply
              </Box>
              .
            </Typography>
          )}
        </Box>

        <Box sx={{ display: "flex", gap: "8px", flexWrap: "wrap", ml: "auto" }}>
          <Box
            component="a"
            href={standardBackupsHref(query)}
            data-testid="backups-standard-link"
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
            Open standard Backups →
          </Box>
        </Box>
      </Box>

      {/* Five-Fact Strip Card */}
      <EstateCard>
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: {
              xs: "1fr",
              sm: "repeat(2, minmax(0, 1fr))",
              lg: "repeat(5, minmax(0, 1fr))",
            },
          }}
        >
          {/* Fact 1: Last fleet run */}
          <Box sx={{ p: "14px 18px" }}>
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Last fleet run
            </Typography>
            <Box sx={{ mt: "4px", mb: "2px" }}>
              {lensModel.lastFleetRunAt ? (
                <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "18px", fontWeight: 700 }}>
                  {formatStamp(lensModel.lastFleetRunAt).slice(11, 16)}
                </Typography>
              ) : (
                <StateBadge state="unk" word="UNKNOWN" dense />
              )}
            </Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" }}>
              <span style={{ fontSize: "12px", color: ev.ink2 }}>
                {lensModel.lastFleetRunAt ? formatStamp(lensModel.lastFleetRunAt).slice(0, 10) : "not recorded"}
              </span>
              <EvidenceStamp at={lensModel.lastFleetRunAt} now={now} missingLabel="evidence time unknown" />
            </Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap", mt: "4px" }}>
              <span style={{ fontSize: "12px", color: ev.ink2 }}>
                oldest archive {lensModel.oldestArchiveAt ? formatStamp(lensModel.oldestArchiveAt).slice(0, 10) : "not recorded"}
              </span>
              <EvidenceStamp at={lensModel.oldestArchiveAt} source="oldest archive" now={now} missingLabel="evidence time unknown" />
            </Box>
          </Box>

          {/* Fact 2: Validated */}
          <Box sx={{ p: "14px 18px", borderLeft: { lg: `1px solid ${ev.line}` }, borderTop: { xs: `1px solid ${ev.line}`, lg: "none" } }}>
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Validated
            </Typography>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "8px", mt: "4px", mb: "2px" }}>
              {backups.state === "ok" ? (
                <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "20px", fontWeight: 700 }}>
                  {lensModel.validatedCount}
                </Typography>
              ) : (
                <StateBadge state="unk" word="UNKNOWN" dense />
              )}
              <EvidenceStamp at={backupEvidenceAt ?? lensModel.lastFleetRunAt} now={now} missingLabel="evidence time unknown" />
            </Box>
            <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>
              archive opened and manifest checked
            </Typography>
          </Box>

          {/* Fact 3: Retention */}
          <Box sx={{ p: "14px 18px", borderLeft: { lg: `1px solid ${ev.line}` }, borderTop: { xs: `1px solid ${ev.line}`, lg: "none" } }}>
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Retention
            </Typography>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "8px", mt: "4px", mb: "2px" }}>
              {lensModel.retentionDays !== null ? (
                <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "20px", fontWeight: 700 }}>
                  {lensModel.retentionDays} {lensModel.retentionDays === 1 ? "night" : "nights"}
                </Typography>
              ) : (
                <StateBadge state="unk" word="UNKNOWN" dense />
              )}
              <EvidenceStamp
                at={policy.state === "ok" ? (policy.value.updated_at ?? lensModel.lastFleetRunAt) : null}
                now={now}
                missingLabel="evidence time unknown"
              />
            </Box>
            <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>
              last good archive kept during an open size anomaly
            </Typography>
          </Box>

          {/* Fact 4: Store used */}
          <Box sx={{ p: "14px 18px", borderLeft: { lg: `1px solid ${ev.line}` }, borderTop: { xs: `1px solid ${ev.line}`, lg: "none" } }}>
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Store used
            </Typography>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "8px", mt: "4px", mb: "2px" }}>
              {lensModel.storeUsage.known && lensModel.storeUsage.usedBytes !== null ? (
                <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "20px", fontWeight: 700 }}>
                  {lensModel.storeUsage.usedPct !== null
                    ? `${lensModel.storeUsage.usedPct}%`
                    : formatBytes(lensModel.storeUsage.usedBytes)}
                </Typography>
              ) : (
                <StateBadge state="unk" word="UNKNOWN" dense />
              )}
              <EvidenceStamp at={backupEvidenceAt ?? lensModel.lastFleetRunAt} now={now} missingLabel="evidence time unknown" />
            </Box>
            <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>
              {lensModel.storeUsage.known && lensModel.storeUsage.usedBytes !== null
                ? lensModel.storeUsage.quotaBytes
                  ? `${formatBytes(lensModel.storeUsage.usedBytes)} of ${formatBytes(lensModel.storeUsage.quotaBytes)}`
                  : `${formatBytes(lensModel.storeUsage.usedBytes)} across ${lensModel.storeUsage.storeCount} ${lensModel.storeUsage.storeCount === 1 ? "store" : "stores"}`
                : "store measurement unavailable"}
            </Typography>
          </Box>

          {/* Fact 5: Not targets */}
          <Box sx={{ p: "14px 18px", borderLeft: { lg: `1px solid ${ev.line}` }, borderTop: { xs: `1px solid ${ev.line}`, lg: "none" } }}>
            <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>
              Not targets
            </Typography>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "8px", mt: "4px", mb: "2px" }}>
              <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "20px", fontWeight: 700 }}>
                {lensModel.nonTargetCount}
              </Typography>
              <EvidenceStamp at={backupEvidenceAt ?? lensModel.lastFleetRunAt} now={now} missingLabel="evidence time unknown" />
            </Box>
            <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>
              switched off as backup targets, listed via filter
            </Typography>
          </Box>
        </Box>
      </EstateCard>

      {/* Open Size Anomalies Callout */}
      {openSizeAnomalies.length > 0 ? (
        <EstateCard sx={{ mt: "14px", p: "12px 18px" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", mb: "6px" }}>
            <StateBadge state="watch" word={`${openSizeAnomalies.length} open size ${openSizeAnomalies.length === 1 ? "anomaly" : "anomalies"}`} />
            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
              The last good archive is retained alongside the newest anomalous copy until a security administrator accepts the new size in standard Backups.
            </Typography>
          </Box>
          <Box sx={{ display: "flex", flexDirection: "column", gap: "6px" }}>
            {openSizeAnomalies.map((anom) => (
              <Box
                key={anom.device_id}
                sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", fontSize: "12.5px" }}
              >
                <Box
                  component="a"
                  href={dossierHref(anom.device_id)}
                  sx={{ fontFamily: ESTATE_MONO, fontWeight: 700, color: ev.accent, textDecoration: "none" }}
                >
                  {anom.hostname ?? anom.device_id}
                </Box>
                <span style={{ fontFamily: ESTATE_MONO, fontWeight: 650, color: ev.watch }}>
                  {sizeAnomalyWord(anom)}
                </span>
                <span style={{ color: ev.ink2 }}>
                  {formatBytes(anom.median_bytes)} → {formatBytes(anom.plaintext_bytes)}
                </span>
                <EvidenceStamp at={anom.detected_at} source="detected" now={now} />
                <Box
                  component="a"
                  href={standardBackupsHref(anom.hostname ?? undefined)}
                  sx={{ ml: "auto", color: ev.accent, fontWeight: 600, textDecoration: "none" }}
                >
                  Review in standard Backups →
                </Box>
              </Box>
            ))}
          </Box>
        </EstateCard>
      ) : null}

      <LatestFailedAttempts rows={failedAttemptRows} />
      <EstateBackupsAttemptsDrawer unit={attemptsUnit} onClose={() => setAttemptsUnit(null)} />

      {/* Main 14-Night Table Card */}
      <EstateCard sx={{ mt: "16px" }}>
        <Box
          sx={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            gap: "10px",
            px: "18px",
            pt: "14px",
            pb: "10px",
          }}
        >
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Every target, last 14 nights
          </Typography>

          <Box sx={{ ml: "auto", display: "flex", gap: "6px", flexWrap: "wrap", alignItems: "center" }}>
            {(
              [
                ["all", `All ${lensModel.targetCount} targets`],
                ["attention", `Needs attention · ${attentionCount}`],
                ["deviations", `Size deviations · ${lensModel.sizeDeviationCount}`],
                ["off", `Not targets · ${lensModel.nonTargetCount}`],
              ] as const
            ).map(([modeKey, label]) => {
              const on = filterMode === modeKey;
              return (
                <Box
                  key={modeKey}
                  component="button"
                  type="button"
                  aria-pressed={on}
                  onClick={() => setFilterMode(modeKey)}
                  sx={{
                    height: "27px",
                    px: "10px",
                    borderRadius: "999px",
                    border: `1px solid ${on ? ev.ink : ev.line}`,
                    bgcolor: ev.surface,
                    color: on ? ev.ink : ev.ink2,
                    fontWeight: on ? 650 : 500,
                    fontSize: "12px",
                    boxShadow: on ? `inset 0 0 0 1px ${ev.ink}` : "none",
                    cursor: "pointer",
                    fontFamily: ESTATE_SANS,
                  }}
                >
                  {label}
                </Box>
              );
            })}

            <Box
              component="input"
              aria-label="Filter backup targets"
              placeholder="Filter unit or cluster"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              sx={{
                height: "27px",
                border: `1px solid ${ev.line}`,
                borderRadius: "8px",
                bgcolor: ev.bg,
                color: ev.ink,
                px: "10px",
                fontSize: "12px",
                fontFamily: ESTATE_SANS,
                minWidth: "160px",
              }}
            />
          </Box>
        </Box>

        <Box
          sx={{
            display: "flex",
            gap: "16px",
            flexWrap: "wrap",
            alignItems: "center",
            px: "18px",
            pb: "10px",
            fontSize: "12px",
            color: ev.ink2,
          }}
        >
          {(
            [
              ["stored", "stored + validated"],
              ["anomaly", "stored, size deviation (~)"],
              ["failed", "failed (!)"],
              ["none", "not attempted"],
              ["unknown", "result UNKNOWN (?)"],
            ] as const
          ).map(([st, label]) => (
            <Box key={st} component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "6px" }}>
              <NightSwatch state={st} />
              <span>{label}</span>
            </Box>
          ))}
        </Box>

        <Box sx={{ overflowX: "auto" }}>
          <Box
            component="table"
            aria-label="Backup targets, last 14 nights"
            sx={{
              width: "100%",
              borderCollapse: "collapse",
              fontSize: "13px",
              "& th": {
                textAlign: "left",
                fontSize: "11px",
                letterSpacing: "0.07em",
                textTransform: "uppercase",
                color: ev.ink3,
                fontWeight: 650,
                p: "8px 10px",
                borderBottom: `1px solid ${ev.line}`,
                whiteSpace: "nowrap",
                bgcolor: ev.surface,
              },
              "& td": {
                p: "9px 10px",
                borderBottom: `1px solid ${ev.line}`,
                verticalAlign: "middle",
              },
              "& tr:last-of-type td": { borderBottom: 0 },
              "& tbody tr:hover td": { bgcolor: ev.bg },
            }}
          >
            <thead>
              <tr>
                <th>Unit</th>
                <th>State</th>
                <th>14 nights (oldest → last night)</th>
                <th>Archive size &amp; deviation</th>
                <th style={{ textAlign: "right" }}>Last size</th>
                <th>Last archive</th>
                <th>Why</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {visibleRows.length === 0 ? (
                <tr>
                  <td colSpan={8} style={{ color: ev.ink3 }}>
                    No units match the selected filter.
                  </td>
                </tr>
              ) : (
                visibleRows.map((r) => (
                  <tr key={r.unit.deviceId} data-backup-row={r.unit.name}>
                    <td>
                      <Box
                        component="a"
                        href={dossierHref(r.unit.deviceId)}
                        sx={{
                          fontFamily: ESTATE_MONO,
                          fontWeight: 650,
                          fontSize: "12.5px",
                          color: ev.accent,
                          textDecoration: "none",
                          "&:hover": { textDecoration: "underline" },
                        }}
                      >
                        {r.unit.name}
                      </Box>
                      <Typography sx={{ fontSize: "11.5px", color: ev.ink3 }}>
                        {r.unit.vendorName}
                        {r.unit.clusterRef ? ` · ${r.unit.clusterRef}` : ""}
                      </Typography>
                    </td>
                    <td>
                      <StateBadge state={r.state} word={r.badgeWord} dense />
                    </td>
                    <td>
                      <Box
                        data-testid={`night-strip-${r.unit.name}`}
                        sx={{ display: "inline-flex", gap: "2px", alignItems: "center" }}
                      >
                        {r.nights.map((cell) => (
                          <NightStripCellView key={cell.dayKey} cell={cell} unitName={r.unit.name} />
                        ))}
                      </Box>
                    </td>
                    <td>
                      <SizeSparkline row={r} />
                    </td>
                    <td style={{ textAlign: "right", fontFamily: ESTATE_MONO, whiteSpace: "nowrap" }}>
                      {r.lastSizeBytes !== null ? formatBytes(r.lastSizeBytes) : "—"}
                    </td>
                    <td>
                      {r.state === "na" ? (
                        <span style={{ color: ev.ink3 }}>—</span>
                      ) : (
                        <EvidenceStamp
                          at={r.lastArchiveAt}
                          now={now}
                          missingLabel="never"
                        />
                      )}
                    </td>
                    <td style={{ fontSize: "12.5px", color: ev.ink2, maxWidth: "280px" }}>{r.why}</td>
                    <td>
                      <Box
                        component="button"
                        type="button"
                        data-testid="backup-attempts-open"
                        onClick={() => setAttemptsUnit({ deviceId: r.unit.deviceId, name: r.unit.name })}
                        sx={{
                          fontFamily: "inherit",
                          fontSize: "12px",
                          fontWeight: 600,
                          color: ev.accent,
                          bgcolor: "transparent",
                          border: 0,
                          p: 0,
                          mr: "12px",
                          cursor: "pointer",
                          whiteSpace: "nowrap",
                          "&:hover": { textDecoration: "underline" },
                        }}
                      >
                        Attempts
                      </Box>
                      <Box
                        component="a"
                        href={standardBackupsHref(r.unit.name)}
                        sx={{
                          fontSize: "12px",
                          fontWeight: 600,
                          color: ev.accent,
                          whiteSpace: "nowrap",
                          textDecoration: "none",
                          "&:hover": { textDecoration: "underline" },
                        }}
                      >
                        Standard Backups →
                      </Box>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </Box>
        </Box>
      </EstateCard>
    </Box>
  );
}
