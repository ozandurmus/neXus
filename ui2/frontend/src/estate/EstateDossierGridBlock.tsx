import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { DeviceInventory } from "../auth/adminApi";
import type { SourceRead } from "../screens/overview/attentionRules";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import { ESTATE_MONO, ev } from "./estateTokens";
import {
  buildGridBlockModel,
  GRID_NOT_COLLECTED_BODY,
  gridStandardHref,
  UNKNOWN_WORD,
} from "./estateDossierGridModel";

/**
 * Read-only "Grid members" block for one Infoblox Grid Manager unit. Uses the inventory read the dossier already
 * holds (no call of its own), shows the member count and grid summary counts each with what they count, one row per
 * member up to the cap, and a link to the standard device view. Not drawn when there is no grid evidence.
 */
export function EstateDossierGridBlock({
  deviceId,
  inventory,
  now,
}: {
  readonly deviceId: string;
  readonly inventory: SourceRead<DeviceInventory> | "loading" | null;
  readonly now?: Date;
}) {
  const m = buildGridBlockModel(inventory);
  if (!m.drawn) return null;

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box data-testid="dossier-grid-members-block">
        <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "10px", flexWrap: "wrap" }}>
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Grid members
          </Typography>
          <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>read-only: as stored in the inventory read</Typography>
        </Box>

        {m.noEvidence ? (
          <Box sx={{ px: "18px", pb: "12px" }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
              <StateBadge state={m.badge} word={m.badgeWord} dense />
              <Typography sx={{ fontSize: "14px", fontWeight: 650 }}>{m.headline}</Typography>
            </Box>
            <Typography data-testid="grid-not-collected" sx={{ pt: "6px", fontSize: "12.5px", color: ev.ink2 }}>
              {GRID_NOT_COLLECTED_BODY}
            </Typography>
          </Box>
        ) : (
          <>
            <Box sx={{ px: "18px", pb: "8px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
              <StateBadge state={m.badge} word={m.badgeWord} dense />
              <Typography sx={{ fontSize: "14px", fontWeight: 650 }}>{m.headline}</Typography>
            </Box>
            <Typography data-testid="grid-member-counts" sx={{ px: "18px", pb: "8px", fontSize: "12.5px", color: ev.ink2 }}>
              {m.memberCount} member{m.memberCount === 1 ? "" : "s"} listed (count of members) · {m.gridMasterCount} Grid Master
              (count of Grid Master members) · {m.masterCandidateCount} master candidate
              {m.masterCandidateCount === 1 ? "" : "s"} (count of candidates) · {m.haEnabledCount} with HA enabled (count of
              members with HA on)
            </Typography>
            {m.summaryLines.length > 0 ? (
              <Box data-testid="grid-summary-lines" sx={{ px: "18px", pb: "8px" }}>
                {m.summaryLines.map((line) => (
                  <Box key={line.label} sx={{ display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap", py: "2px" }}>
                    <Typography sx={{ fontSize: "12px", color: ev.ink3, fontWeight: 600, minWidth: "150px" }}>{line.label}</Typography>
                    <Typography sx={{ fontSize: "14px", fontWeight: 650, fontFamily: ESTATE_MONO }}>{line.value}</Typography>
                  </Box>
                ))}
              </Box>
            ) : null}
            <Box sx={{ px: "18px", pb: "8px" }}>
              {m.rows.map((row, i) => (
                <Box
                  key={`${row.name}-${i}`}
                  data-grid-member-row={row.role}
                  sx={{ display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap", py: "8px", borderTop: `1px solid ${ev.line}` }}
                >
                  <Typography sx={{ fontSize: "13px", fontWeight: 650, fontFamily: ESTATE_MONO, minWidth: "150px" }}>{row.name}</Typography>
                  <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>{row.role}</Typography>
                  <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>HA: {row.ha}</Typography>
                  <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>Node: {row.node}</Typography>
                  <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>
                    Disk {row.disk} · Memory {row.memory} · CPU {row.cpu} · DB {row.db}
                  </Typography>
                  {row.services.length > 0 ? (
                    <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>Services: {row.services.join(", ")}</Typography>
                  ) : null}
                </Box>
              ))}
              {m.hiddenCount > 0 ? (
                <Typography data-testid="grid-members-more" sx={{ fontSize: "12.5px", color: ev.ink3, py: "6px" }}>+{m.hiddenCount} more</Typography>
              ) : null}
            </Box>
          </>
        )}

        <Box sx={{ px: "18px", pb: "14px", pt: "4px", display: "flex", alignItems: "baseline", gap: "12px", flexWrap: "wrap" }}>
          <EvidenceStamp at={m.evidenceAt} source={m.evidenceSource} now={now} missingLabel={UNKNOWN_WORD} />
          <Box
            component="a"
            href={gridStandardHref(deviceId)}
            sx={{ fontSize: "12.5px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
          >
            Grid members (standard view) →
          </Box>
        </Box>
      </Box>
    </EstateCard>
  );
}
