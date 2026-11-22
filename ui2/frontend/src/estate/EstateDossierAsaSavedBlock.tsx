import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { AsaSavedConfigNote, useAsaConfigRelations } from "../screens/AsaSavedConfigDiff";
import { ev } from "./estateTokens";

export const ASA_SAVED_UNAVAILABLE_TEXT = "Saved-configuration comparison unavailable";

/** The standard view's Configuration tab for one device (read-only link target; no new route). */
export function standardConfigurationHref(deviceId: string): string {
  return `?screen=inventory&device_id=${encodeURIComponent(deviceId)}&tab=configuration&standard=1`;
}

/**
 * Dossier block: the ASA saved-versus-running note for this device, read-only. It reuses the standard Configuration
 * tab's note and relation read. Only a MISMATCH row (running differs from startup) is shown; MATCH renders nothing.
 * On a Cisco ASA, an empty read, a missing row for this device, or a NOT_EVALUABLE row says the comparison is
 * unavailable (UNKNOWN is never silent). On other vendors those cases render nothing. A still-loading read renders
 * nothing.
 */
export function EstateDossierAsaSavedBlock({
  deviceId,
  vendor,
}: {
  readonly deviceId: string;
  readonly vendor: string | null | undefined;
}) {
  const relations = useAsaConfigRelations(true);
  if (relations === null) return null;

  const unavailable = (
    <Box data-testid="dossier-asa-saved-unavailable" sx={{ px: "18px", py: "10px" }}>
      <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>{ASA_SAVED_UNAVAILABLE_TEXT}</Typography>
    </Box>
  );

  if (relations.length === 0) {
    return vendor === "cisco_asa" ? unavailable : null;
  }

  const relation = relations.find((r) => r.device_id === deviceId) ?? null;
  if (!relation || relation.relation === "NOT_EVALUABLE") {
    return vendor === "cisco_asa" ? unavailable : null;
  }
  if (relation.relation !== "MISMATCH") return null;

  return (
    <Box data-testid="dossier-asa-saved" sx={{ px: "18px", py: "10px", display: "flex", flexDirection: "column", gap: "8px" }}>
      <AsaSavedConfigNote relation={relation} />
      <Box component="a" href={standardConfigurationHref(deviceId)} sx={{ color: ev.accent, fontSize: "12.5px", alignSelf: "flex-end" }}>
        Open in standard view →
      </Box>
    </Box>
  );
}
