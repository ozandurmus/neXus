import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Typography from "@mui/material/Typography";

import { getBackupDeviations, type AsaConfigRelation } from "../auth/adminApi";
import { Stamp } from "../shell/PresentedText";
import { StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";

/**
 * ASA saved vs running configuration (V250/V253): a MISMATCH means the running configuration holds changes that are
 * not in the startup configuration. The worker stores only keyword counts of the differing lines (never a line or a
 * value); this shows them. Text only: no button, no device action.
 */

export const ASA_UNSAVED_HINT = "Unsaved changes on the device: run `write memory` after review";

/** "access-list 3, object-group 1": biggest count first, then by keyword. */
export function keywordCountsWords(counts: Readonly<Record<string, number>> | null | undefined): string {
  return Object.entries(counts ?? {})
    .sort(([ka, a], [kb, b]) => (b - a) || ka.localeCompare(kb))
    .map(([keyword, count]) => `${keyword} ${count}`)
    .join(", ");
}

/** Devices whose newest backup reads MISMATCH (the "Saved config differs" filter). */
export function savedConfigDiffersIds(relations: readonly AsaConfigRelation[] | null | undefined): ReadonlySet<string> {
  return new Set((relations ?? []).filter((r) => r.relation === "MISMATCH").map((r) => r.device_id));
}

// The Devices list and the open device's Configuration tab read the same body; one request serves both while it is in flight.
let inFlight: Promise<readonly AsaConfigRelation[]> | null = null;

function readRelations(): Promise<readonly AsaConfigRelation[]> {
  if (inFlight) return inFlight;
  const promise = Promise.resolve(getBackupDeviations()).then((d) => d?.asa_config_relations ?? []);
  inFlight = promise;
  const clear = () => { if (inFlight === promise) inFlight = null; };
  promise.then(clear, clear);
  return promise;
}

/** The relations of the deviations read; empty when it is unavailable (a missing read never hides a device). */
export function useAsaConfigRelations(enabled = true): readonly AsaConfigRelation[] | null {
  const [relations, setRelations] = useState<readonly AsaConfigRelation[] | null>(null);
  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
    try {
      readRelations()
        .then((rows) => { if (live) setRelations(rows); })
        .catch(() => { if (live) setRelations([]); });
    } catch {
      setRelations([]);
    }
    return () => { live = false; };
  }, [enabled]);
  return relations;
}

/** The device's Configuration tab note; nothing unless this device's newest backup differs from its startup configuration. */
export function AsaSavedConfigNote({ relation }: { readonly relation: AsaConfigRelation | null | undefined }) {
  if (!relation || relation.relation !== "MISMATCH") return null;
  const words = keywordCountsWords(relation.differing_keywords);
  return (
    <Card data-testid="asa-saved-config-diff" aria-label="Saved configuration differs"
      sx={{ px: 2, py: 1.25, borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, boxShadow: "none" }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
        <StatusChip tone="attn" label="Saved config differs" dense />
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {words ? `Differing lines by keyword: ${words}` : `${relation.lines_differing} differing line${relation.lines_differing === 1 ? "" : "s"}`}
        </Typography>
      </Box>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.5 }}>
        {relation.backup_created_at && <>Backup read <Stamp at={relation.backup_created_at} /></>}
        {relation.backup_created_at && relation.derived_at && " · "}
        {relation.derived_at && <>compared <Stamp at={relation.derived_at} /></>}
      </Typography>
      <Typography variant="body2" sx={{ mt: 0.5 }}>{ASA_UNSAVED_HINT}</Typography>
    </Card>
  );
}
