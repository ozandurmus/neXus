import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getPolicyHygieneSummary, listPolicies, type PolicyHygieneSummary, type PolicyMetadata } from "../auth/adminApi";
import { plural } from "../shell/plural";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { ESTATE_MONO, ev } from "./estateTokens";
import { standardPolicyHref } from "./estateRouting";
import { buildDossierHygieneModel, type DossierHygieneRead } from "./estateDossierHygieneModel";

/**
 * Read-only policy hygiene for one unit. Joins the stored policy containers that name this unit (by device id)
 * to the fleet hygiene summary by container id, and shows READY counts per finding class with the snapshot age of
 * each container. PENDING is shown as pending, never zero. Carries no action control; the link opens the standard view.
 */
export function EstateDossierHygieneBlock({ deviceId }: { readonly deviceId: string }) {
  const [state, setState] = useState<{
    policies: DossierHygieneRead<readonly PolicyMetadata[]> | null;
    summary: DossierHygieneRead<PolicyHygieneSummary> | null;
  }>({ policies: null, summary: null });

  useEffect(() => {
    let active = true;
    setState({ policies: null, summary: null });
    listPolicies().then(
      (r) => {
        if (active) setState((s) => ({ ...s, policies: { ok: true, value: r.policies } }));
      },
      () => {
        if (active) setState((s) => ({ ...s, policies: { ok: false } }));
      },
    );
    getPolicyHygieneSummary().then(
      (value) => {
        if (active) setState((s) => ({ ...s, summary: { ok: true, value } }));
      },
      () => {
        if (active) setState((s) => ({ ...s, summary: { ok: false } }));
      },
    );
    return () => {
      active = false;
    };
  }, [deviceId]);

  const header = (
    <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "10px", flexWrap: "wrap" }}>
      <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
        Policy hygiene
      </Typography>
      <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>read-only: counts per finding class and snapshot age</Typography>
    </Box>
  );
  const link = (
    <Box
      component="a"
      href={standardPolicyHref(deviceId)}
      sx={{ fontSize: "12.5px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
    >
      Open policy hygiene (standard view)
    </Box>
  );

  if (state.policies === null || state.summary === null) {
    return (
      <EstateCard sx={{ mt: "16px" }}>
        {header}
        <Typography sx={{ px: "18px", pb: "14px", fontSize: "12.5px", color: ev.ink3 }}>Reading policy hygiene</Typography>
      </EstateCard>
    );
  }

  const m = buildDossierHygieneModel({ deviceId, policies: state.policies, summary: state.summary });

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box data-testid="dossier-policy-hygiene-block" data-hygiene-state={m.badge}>
        {header}
        <Box sx={{ px: "18px", pb: "8px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
          <StateBadge state={m.badge} word={m.badgeWord} dense />
          <Typography sx={{ fontSize: "14px", fontWeight: 650 }}>{m.headline}</Typography>
          <EvidenceStamp at={m.evidenceAt} source="policy hygiene" missingLabel="UNKNOWN" />
        </Box>
        <Typography sx={{ px: "18px", pb: "8px", fontSize: "12.5px", color: ev.ink3 }}>{m.countsLine}</Typography>
        {m.pendingLine ? (
          <Typography data-testid="dossier-hygiene-pending" sx={{ px: "18px", pb: "8px", fontSize: "12.5px", color: ev.ink2 }}>
            {m.pendingLine}
          </Typography>
        ) : null}
        {m.totals.length > 0 ? (
          <Box sx={{ px: "18px", pb: "8px" }}>
            <Typography sx={{ fontSize: "12px", color: ev.ink3, fontWeight: 600 }}>Rules flagged per finding class (READY containers)</Typography>
            {m.totals.map((t) => (
              <Box key={t.finding} sx={{ display: "flex", gap: "10px", fontSize: "13px" }}>
                <Box sx={{ fontFamily: ESTATE_MONO, minWidth: "220px" }}>{t.finding}</Box>
                <Box>{plural(t.rules, "rule", "rules")}</Box>
              </Box>
            ))}
          </Box>
        ) : null}
        {m.rows.length > 0 ? (
          <Box sx={{ px: "18px", pb: "8px", display: "flex", flexDirection: "column", gap: "6px" }}>
            {m.rows.map((row) => (
              <Box key={row.key} data-testid="dossier-hygiene-row" data-row-state={row.kind} sx={{ fontSize: "13px" }}>
                <Box sx={{ fontWeight: 600 }}>{row.label}</Box>
                {row.kind === "ready" ? (
                  <Box sx={{ color: ev.ink2 }}>
                    {plural(row.rules, "rule", "rules")} in this container
                    {row.counts.map((c) => ` · ${c.finding}: ${plural(c.rules, "rule", "rules")}`).join("")}
                  </Box>
                ) : row.kind === "pending" ? (
                  <Box sx={{ color: ev.ink2 }}>PENDING: no counts yet, not counted as zero</Box>
                ) : (
                  <Box sx={{ color: ev.ink2 }}>UNKNOWN: this container could not be read</Box>
                )}
                <Box sx={{ color: ev.ink3, fontSize: "12px" }}>
                  <EvidenceStamp at={row.snapshotAt} source="policy snapshot" missingLabel="UNKNOWN" />
                </Box>
              </Box>
            ))}
            <MoreFooter shown={m.rows.length} total={m.totalContainers} noun="containers" testId="dossier-hygiene-more" />
          </Box>
        ) : null}
        <Box sx={{ px: "18px", pb: "14px" }}>{link}</Box>
      </Box>
    </EstateCard>
  );
}
