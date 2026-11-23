import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { plural } from "../shell/plural";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import {
  projectDossierConfiguration,
  type DeviceConfigurationDetail,
  type EstateReadState,
} from "./estateDossierConfigurationModel";
import { ESTATE_MONO, ev } from "./estateTokens";

/**
 * Read-only Configuration block for one unit in the Estate dossier.
 * Reuses the configuration read already loaded by EstateDossierScreen (never calls getDeviceConfigurationText).
 */
export function EstateDossierConfigurationBlock({
  deviceId,
  vendor,
  role,
  configuration,
  now = new Date(),
}: {
  readonly deviceId: string;
  readonly vendor: string | null;
  readonly role: string | null;
  readonly configuration: EstateReadState<DeviceConfigurationDetail>;
  readonly now?: Date;
}) {
  const model = projectDossierConfiguration(configuration, { vendor, role });
  const standardHref = `?screen=inventory&device_id=${encodeURIComponent(deviceId)}&tab=configuration&standard=1`;

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box data-testid="dossier-configuration-block">
        <Box
          sx={{
            display: "flex",
            alignItems: "center",
            gap: "12px",
            px: "18px",
            pt: "14px",
            pb: "10px",
            flexWrap: "wrap",
          }}
        >
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Configuration
          </Typography>
          <StateBadge state={model.badge.state} word={model.badge.word} dense />
          <EvidenceStamp at={model.evidenceAt} source="configuration" now={now} missingLabel="not collected" />
          <Box
            component="a"
            href={standardHref}
            sx={{
              ml: "auto",
              fontSize: "12.5px",
              color: ev.accent,
              textDecoration: "none",
              "&:hover": { textDecoration: "underline" },
            }}
          >
            Open configuration (standard view) →
          </Box>
        </Box>

        {!model.evidenceAt ? (
          <Typography sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>
            {model.summaryText}
          </Typography>
        ) : (
          <Box sx={{ px: "18px", pb: "14px", display: "flex", flexDirection: "column", gap: "12px" }}>
            <Box sx={{ display: "flex", flexDirection: "column", gap: "4px" }}>
              <Typography sx={{ fontSize: "13px", fontWeight: 600, color: ev.ink }}>
                {`${plural(model.totalSectionCount, "section", "sections")} indexed · ${plural(model.totalEntryCount, "entry", "entries")} · ${plural(model.totalChangedCount, "changed section", "changed sections")} · ${plural(model.totalOverrideCount, "local override", "local overrides")}`}
              </Typography>
              <Typography sx={{ fontSize: "12px", color: ev.ink3, fontFamily: ESTATE_MONO }}>
                {`Read kind: ${model.readKind} · Hash: ${model.hashPrefix} · ${model.withheldText}`}
              </Typography>
            </Box>

            <Box data-testid="dossier-config-sections-list">
              {model.sectionsShown.length === 0 ? (
                <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
                  No configuration sections indexed in this snapshot.
                </Typography>
              ) : (
                <Box sx={{ display: "flex", flexDirection: "column" }}>
                  {model.sectionsShown.map((row, idx) => (
                    <Box
                      key={`${row.context}-${row.section}-${row.source ?? "na"}-${idx}`}
                      sx={{
                        display: "flex",
                        alignItems: "center",
                        gap: "10px",
                        flexWrap: "wrap",
                        py: "6px",
                        borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                        fontSize: "12.5px",
                      }}
                    >
                      <Typography component="span" sx={{ fontSize: "12px", color: ev.ink3, fontFamily: ESTATE_MONO }}>
                        {row.context}
                      </Typography>
                      <Typography component="span" sx={{ fontSize: "13px", fontWeight: 600, fontFamily: ESTATE_MONO }}>
                        {row.section}
                      </Typography>
                      <Typography component="span" sx={{ fontSize: "12px", color: ev.ink2 }}>
                        {row.source ?? "—"}
                      </Typography>
                      <Typography
                        component="span"
                        sx={{ ml: "auto", fontSize: "12.5px", fontFamily: ESTATE_MONO, color: ev.ink2 }}
                      >
                        {plural(row.entry_count, "entry", "entries")}
                      </Typography>
                      {row.has_override ? <StateBadge state="watch" word="Override" dense /> : null}
                    </Box>
                  ))}
                </Box>
              )}
              <MoreFooter
                shown={model.sectionsShown.length}
                total={model.totalSectionCount}
                noun="sections"
                testId="dossier-config-sections-more"
              />
            </Box>

            {model.totalChangedCount > 0 ? (
              <Box data-testid="dossier-config-changes-list" sx={{ pt: "4px", borderTop: `1px solid ${ev.line}` }}>
                <Typography sx={{ fontSize: "12px", fontWeight: 650, color: ev.ink3, mb: "4px" }}>
                  Run-to-run section changes
                </Typography>
                <Box sx={{ display: "flex", flexDirection: "column" }}>
                  {model.changedShown.map((row, idx) => (
                    <Box
                      key={`${row.context}-${row.section}-${idx}`}
                      sx={{
                        display: "flex",
                        alignItems: "center",
                        gap: "10px",
                        flexWrap: "wrap",
                        py: "6px",
                        borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                        fontSize: "12.5px",
                      }}
                    >
                      <Typography component="span" sx={{ fontSize: "12.5px", fontFamily: ESTATE_MONO, fontWeight: 600 }}>
                        {`${row.context} · ${row.section}`}
                      </Typography>
                      <StateBadge state={row.badgeState} word={row.severityWord} dense />
                      <Typography component="span" sx={{ fontSize: "12px", color: ev.ink2 }}>
                        {row.kindWord}
                      </Typography>
                      <Typography
                        component="span"
                        sx={{ ml: "auto", fontSize: "12px", fontFamily: ESTATE_MONO, color: ev.ink2 }}
                      >
                        {`${row.oldCountText} → ${row.newCountText}`}
                      </Typography>
                    </Box>
                  ))}
                </Box>
                <MoreFooter
                  shown={model.changedShown.length}
                  total={model.totalChangedCount}
                  noun="changed sections"
                  testId="dossier-config-changes-more"
                />
              </Box>
            ) : null}

            {model.totalOverrideCount > 0 ? (
              <Box data-testid="dossier-config-overrides-list" sx={{ pt: "4px", borderTop: `1px solid ${ev.line}` }}>
                <Typography sx={{ fontSize: "12px", fontWeight: 650, color: ev.ink3, mb: "4px" }}>
                  Local overrides (src=local)
                </Typography>
                <Box sx={{ display: "flex", flexDirection: "column" }}>
                  {model.overridesShown.map((ov, idx) => (
                    <Box
                      key={`${ov.context}-${ov.element_path}-${idx}`}
                      sx={{
                        display: "flex",
                        alignItems: "center",
                        gap: "10px",
                        flexWrap: "wrap",
                        py: "6px",
                        borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                        fontSize: "12.5px",
                      }}
                    >
                      <Typography component="span" sx={{ fontSize: "12.5px", fontFamily: ESTATE_MONO }}>
                        {`${ov.context} · ${ov.element_path}`}
                      </Typography>
                      <Typography component="span" sx={{ ml: "auto", fontSize: "12px", color: ev.ink2 }}>
                        {ov.panorama_source ? `Panorama: ${ov.panorama_source}` : "Local"}
                      </Typography>
                    </Box>
                  ))}
                </Box>
                <MoreFooter
                  shown={model.overridesShown.length}
                  total={model.totalOverrideCount}
                  noun="overrides"
                  testId="dossier-config-overrides-more"
                />
              </Box>
            ) : null}
          </Box>
        )}
      </Box>
    </EstateCard>
  );
}
