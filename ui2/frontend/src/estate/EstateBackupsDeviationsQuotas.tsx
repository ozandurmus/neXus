import { useMemo, type MouseEvent } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { configChangesHref, sizeAnomalyWord } from "../screens/BackupDeviationCard";
import { plural } from "../shell/plural";
import {
  EstateCard,
  EvidenceStamp,
  MoreFooter,
  StateBadge,
} from "./EstatePrimitives";
import {
  projectBackupDeviations,
  projectBackupStoreQuotas,
  type BackupDeviationOverview,
  type BackupStoreQuotaOverview,
  type EstateReadState,
} from "./estateBackupsParityModel";
import { dossierHref, standardBackupsHref } from "./estateRouting";
import { ESTATE_MONO, ev } from "./estateTokens";

export interface EstateBackupsDeviationsQuotasProps {
  readonly deviations: BackupDeviationOverview | null;
  readonly backupsState: EstateReadState<unknown>["state"];
  readonly quotas: EstateReadState<BackupStoreQuotaOverview>;
  readonly backupEvidenceAt: string | null;
  readonly now: Date;
  readonly onNavigate?: (route: { readonly kind: "dossier"; readonly deviceId: string }) => void;
}

export function EstateBackupsDeviationsQuotas({
  deviations,
  backupsState,
  quotas,
  backupEvidenceAt,
  now,
  onNavigate,
}: EstateBackupsDeviationsQuotasProps) {
  const devModel = useMemo(
    () => projectBackupDeviations(deviations, backupsState),
    [deviations, backupsState],
  );
  const quotaModel = useMemo(
    () => projectBackupStoreQuotas(quotas),
    [quotas],
  );

  const handleDossierClick = (deviceId: string, e: MouseEvent<HTMLElement>) => {
    if (!onNavigate) return;
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
    e.preventDefault();
    onNavigate({ kind: "dossier", deviceId });
  };

  return (
    <Box
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", lg: "repeat(2, minmax(0, 1fr))" },
        gap: "14px",
        mt: "14px",
      }}
    >
      {/* Card 1: Backup deviations & size anomalies */}
      <EstateCard sx={{ p: "14px 18px" }}>
        <Box data-testid="estate-backups-deviations-card">
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", mb: "6px" }}>
            <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>
              Backup deviations &amp; size anomalies
            </Typography>
            <Box component="span">
              <StateBadge state={devModel.badge.state} word={devModel.badge.word} />
            </Box>
            <EvidenceStamp
              at={devModel.newestEvidenceAt ?? backupEvidenceAt}
              source="deviation check"
              now={now}
              missingLabel="no comparison timestamp"
            />
          </Box>

          <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mb: "10px" }}>
            {devModel.coverageText}
          </Typography>

          {devModel.majorShown.length > 0 ? (
            <Box sx={{ mb: devModel.anomaliesShown.length > 0 ? "12px" : 0 }}>
              <Typography
                sx={{
                  fontSize: "11px",
                  letterSpacing: "0.07em",
                  textTransform: "uppercase",
                  color: ev.ink3,
                  fontWeight: 650,
                  mb: "6px",
                }}
              >
                Active major configuration deviations
              </Typography>
              <Box
                component="ul"
                aria-label="Active major configuration deviations"
                sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "6px" }}
              >
                {devModel.majorShown.map((row) => (
                  <Box
                    component="li"
                    key={row.key}
                    data-major-deviation-group={row.deviceId}
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: "8px",
                      flexWrap: "wrap",
                      fontSize: "12.5px",
                      py: "4px",
                      borderTop: `1px solid ${ev.line}`,
                    }}
                  >
                    <Box
                      component="a"
                      href={dossierHref(row.deviceId)}
                      onClick={(e: MouseEvent<HTMLElement>) => handleDossierClick(row.deviceId, e)}
                      sx={{
                        fontFamily: ESTATE_MONO,
                        fontWeight: 700,
                        color: ev.accent,
                        textDecoration: "none",
                        "&:hover": { textDecoration: "underline" },
                      }}
                    >
                      {row.hostname}
                    </Box>
                    {row.categories.map((cat) => (
                      <Box component="span" key={cat.label}>
                        <StateBadge state="watch" word={cat.label} dense />
                      </Box>
                    ))}
                    <Box component="span" sx={{ color: ev.ink2 }}>
                      {row.changeSummary}
                    </Box>
                    <EvidenceStamp at={row.changedAt} source="changed" now={now} />
                    <Box
                      component="a"
                      href={`${configChangesHref(row.deviceId)}&standard=1`}
                      sx={{
                        ml: "auto",
                        color: ev.accent,
                        fontWeight: 600,
                        fontSize: "12px",
                        textDecoration: "none",
                        whiteSpace: "nowrap",
                        "&:hover": { textDecoration: "underline" },
                      }}
                    >
                      Configuration changes →
                    </Box>
                  </Box>
                ))}
              </Box>
              <Box sx={{ mt: "6px" }}>
                <MoreFooter
                  shown={devModel.majorShown.length}
                  total={devModel.majorTotal}
                  noun="major deviation groups"
                  testId="backup-major-deviations-more"
                />
              </Box>
            </Box>
          ) : null}

          {devModel.anomaliesShown.length > 0 ? (
            <Box>
              <Typography
                sx={{
                  fontSize: "11px",
                  letterSpacing: "0.07em",
                  textTransform: "uppercase",
                  color: ev.ink3,
                  fontWeight: 650,
                  mb: "4px",
                }}
              >
                Open size anomalies
              </Typography>
              <Typography sx={{ fontSize: "12px", color: ev.ink2, mb: "6px" }}>
                The last good archive is retained alongside the newest anomalous copy until a security administrator accepts the new size in standard Backups.
              </Typography>
              <Box
                component="ul"
                aria-label="Open size anomalies"
                sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "6px" }}
              >
                {devModel.anomaliesShown.map((anom) => (
                  <Box
                    component="li"
                    key={anom.device_id}
                    data-size-anomaly-row={anom.device_id}
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: "8px",
                      flexWrap: "wrap",
                      fontSize: "12.5px",
                      py: "4px",
                      borderTop: `1px solid ${ev.line}`,
                    }}
                  >
                    <Box
                      component="a"
                      href={dossierHref(anom.device_id)}
                      onClick={(e: MouseEvent<HTMLElement>) => handleDossierClick(anom.device_id, e)}
                      sx={{
                        fontFamily: ESTATE_MONO,
                        fontWeight: 700,
                        color: ev.accent,
                        textDecoration: "none",
                        "&:hover": { textDecoration: "underline" },
                      }}
                    >
                      {anom.hostname ?? anom.device_id}
                    </Box>
                    <Box component="span">
                      <StateBadge state="watch" word={sizeAnomalyWord(anom)} dense />
                    </Box>
                    <Box component="span" sx={{ color: ev.ink2 }}>
                      {`${anom.ratio_percent}% of ${anom.baseline_bytes.toLocaleString("en-US")} B baseline (${anom.current_bytes.toLocaleString("en-US")} B) · ${plural(anom.open_copies, "anomalous backup", "anomalous backups")}`}
                    </Box>
                    <EvidenceStamp at={anom.detected_at} source="detected" now={now} />
                    <Box
                      component="a"
                      href={standardBackupsHref(anom.hostname ?? undefined)}
                      sx={{
                        ml: "auto",
                        color: ev.accent,
                        fontWeight: 600,
                        fontSize: "12px",
                        textDecoration: "none",
                        whiteSpace: "nowrap",
                        "&:hover": { textDecoration: "underline" },
                      }}
                    >
                      Review in standard Backups →
                    </Box>
                  </Box>
                ))}
              </Box>
              <Box sx={{ mt: "6px" }}>
                <MoreFooter
                  shown={devModel.anomaliesShown.length}
                  total={devModel.anomaliesTotal}
                  noun="size anomalies"
                  testId="backup-size-anomalies-more"
                />
              </Box>
            </Box>
          ) : null}
        </Box>
      </EstateCard>

      {/* Card 2: Storage quota per store */}
      <EstateCard sx={{ p: "14px 18px" }}>
        <Box data-testid="estate-backups-store-quotas-card">
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", mb: "6px" }}>
            <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>
              Storage quota per store
            </Typography>
            <Box component="span">
              <StateBadge state={quotaModel.badge.state} word={quotaModel.badge.word} />
            </Box>
            <EvidenceStamp
              at={backupEvidenceAt}
              source="store quota"
              now={now}
              missingLabel="evidence time unknown"
            />
            <Box
              component="a"
              href={standardBackupsHref()}
              sx={{
                ml: "auto",
                color: ev.accent,
                fontWeight: 600,
                fontSize: "12.5px",
                textDecoration: "none",
                whiteSpace: "nowrap",
                "&:hover": { textDecoration: "underline" },
              }}
            >
              Manage quotas in standard Backups →
            </Box>
          </Box>

          <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mb: "10px" }}>
            {quotaModel.summaryText}
          </Typography>

          {quotaModel.rowsShown.length > 0 ? (
            <>
              <Box
                component="ul"
                aria-label="Storage quota per store"
                sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "6px" }}
              >
                {quotaModel.rowsShown.map((row) => (
                  <Box
                    component="li"
                    key={row.store}
                    data-store-quota-row={row.store}
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: "10px",
                      flexWrap: "wrap",
                      fontSize: "12.5px",
                      py: "4px",
                      borderTop: `1px solid ${ev.line}`,
                    }}
                  >
                    <Box
                      component="span"
                      sx={{ fontFamily: ESTATE_MONO, fontWeight: 650, minWidth: "120px", color: ev.ink }}
                    >
                      {row.store}
                    </Box>
                    <Box component="span" sx={{ fontFamily: ESTATE_MONO, color: ev.ink2 }}>
                      {row.usageText}
                    </Box>
                    {row.percentText !== null ? (
                      <Box component="span" sx={{ fontFamily: ESTATE_MONO, color: ev.ink3 }}>
                        {row.percentText}
                      </Box>
                    ) : null}
                    <Box component="span" sx={{ ml: "auto" }}>
                      <StateBadge state={row.rowBadge.state} word={row.rowBadge.word} dense />
                    </Box>
                  </Box>
                ))}
              </Box>
              <Box sx={{ mt: "6px" }}>
                <MoreFooter
                  shown={quotaModel.rowsShown.length}
                  total={quotaModel.rowsTotal}
                  noun="stores"
                  testId="backup-stores-more"
                />
              </Box>
            </>
          ) : null}
        </Box>
      </EstateCard>
    </Box>
  );
}
