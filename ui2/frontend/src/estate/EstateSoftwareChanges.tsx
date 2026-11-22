import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getSoftwareChanges } from "../auth/adminApi";
import { PRODUCT_NAME } from "../brand/product";
import { EstateCard, EvidenceStamp, MoreFooter } from "./EstatePrimitives";
import {
  buildEstateSoftwareModel,
  SOFTWARE_CHANGE_WINDOW_DAYS,
  type SoftwareChangesRead,
} from "./estateSoftwareModel";
import { ESTATE_MONO, ev } from "./estateTokens";

/**
 * Read-only "Software changes" section under the Estate change feed. Same 90-day read as the standard
 * Recent upgrades panel. Every figure carries the age of its own evidence; a failed read is never shown as "no changes".
 */
export function EstateSoftwareChanges({
  now,
  unitsById,
}: {
  readonly now: Date;
  readonly unitsById: ReadonlyMap<string, { readonly name: string }>;
}) {
  const [read, setRead] = useState<SoftwareChangesRead>({ status: "not_read" });

  useEffect(() => {
    let active = true;
    setRead({ status: "loading" });
    getSoftwareChanges(SOFTWARE_CHANGE_WINDOW_DAYS).then(
      (res) => {
        if (!active) return;
        setRead(
          Array.isArray(res?.changes)
            ? {
                status: "ok",
                days: typeof res.days === "number" ? res.days : SOFTWARE_CHANGE_WINDOW_DAYS,
                changes: res.changes,
                installEvents: Array.isArray(res.install_events) ? res.install_events : [],
              }
            : { status: "failed" },
        );
      },
      () => {
        if (active) setRead({ status: "failed" });
      },
    );
    return () => {
      active = false;
    };
  }, []);

  const model = useMemo(() => buildEstateSoftwareModel(read, unitsById), [read, unitsById]);

  return (
    <Box data-testid="estate-software-changes" sx={{ mt: "16px" }}>
      <EstateCard>
        <Box sx={{ px: "18px", py: "12px", display: "flex", gap: "12px", alignItems: "baseline", flexWrap: "wrap" }}>
          <Typography component="h2" sx={{ fontSize: "15px", fontWeight: 700, color: ev.ink }}>
            Software changes
          </Typography>
          <Typography data-testid="software-changes-headline" sx={{ fontSize: "13px", color: ev.ink2 }}>
            {model.headline}
          </Typography>
          {model.status === "ok" ? (
            <Box data-testid="software-changes-total-stamp">
              <EvidenceStamp at={model.newestAt} now={now} source="newest software change" />
            </Box>
          ) : null}
        </Box>

        {model.status === "not_read" || model.status === "loading" || model.status === "failed" ? (
          <Box sx={{ px: "18px", pb: "12px", fontSize: "12.5px", color: model.status === "failed" ? ev.act : ev.ink3 }}>
            {model.status === "failed"
              ? `${PRODUCT_NAME} could not read software changes just now. Reload the Changes screen to try again.`
              : null}
          </Box>
        ) : null}

        {model.status === "ok" && model.totalChanges > 0 ? (
          <Box component="ul" aria-label="Software changes" sx={{ listStyle: "none", m: 0, p: 0 }}>
            {model.rows.map((row) => (
              <Box
                component="li"
                key={row.key}
                data-software-change-row={row.deviceId}
                sx={{
                  borderTop: `1px solid ${ev.line}`,
                  px: "18px",
                  py: "10px",
                  display: "grid",
                  gridTemplateColumns: { xs: "1fr", md: "minmax(0, 1fr) 180px" },
                  gap: "8px",
                  alignItems: "start",
                }}
              >
                <Box sx={{ minWidth: 0 }}>
                  <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "12.5px", color: ev.ink, overflowWrap: "anywhere" }}>
                    {row.unitName}
                  </Typography>
                  <Typography sx={{ fontSize: "13px", color: ev.ink2, overflowWrap: "anywhere" }}>{row.summary}</Typography>
                </Box>
                <EvidenceStamp at={row.at} now={now} source="software change" />
              </Box>
            ))}
            <Box sx={{ px: "18px", py: "8px", borderTop: `1px solid ${ev.line}` }}>
              <MoreFooter shown={model.rows.length} total={model.totalChanges} noun="version changes" testId="software-changes-more" />
            </Box>
          </Box>
        ) : null}

        {model.status === "ok" && model.perUnit.length > 0 ? (
          <Box sx={{ px: "18px", py: "10px", borderTop: `1px solid ${ev.line}` }}>
            <Typography sx={{ fontSize: "12px", color: ev.ink3, mb: "4px" }}>
              Version changes per unit in {model.days} days
            </Typography>
            {model.perUnit.map((u) => (
              <Box
                key={u.deviceId}
                data-software-unit-count={u.deviceId}
                sx={{ display: "flex", gap: "10px", alignItems: "center", flexWrap: "wrap", py: "2px" }}
              >
                <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "12.5px", color: ev.ink, minWidth: "180px" }}>
                  {u.unitName}
                </Typography>
                <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>{`${u.count} version change${u.count === 1 ? "" : "s"}`}</Typography>
                <EvidenceStamp at={u.newestAt} now={now} source="newest change of this unit" />
              </Box>
            ))}
          </Box>
        ) : null}

        {model.status === "ok" && model.installEventLine ? (
          <Box sx={{ px: "18px", py: "10px", borderTop: `1px solid ${ev.line}`, fontSize: "12px", color: ev.ink3 }}>
            {model.installEventLine}
          </Box>
        ) : null}
      </EstateCard>
    </Box>
  );
}
