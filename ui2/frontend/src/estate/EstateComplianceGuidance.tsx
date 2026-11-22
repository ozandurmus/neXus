import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { ComplianceGuidance as Guidance } from "../auth/adminApi";
import { GUIDANCE_WARNING } from "../screens/ComplianceGuidance";
import { ESTATE_MONO, ev } from "./estateTokens";

/**
 * Read-only remediation guidance for one device row in the Estate compliance drill-down.
 *
 * Shown only for FAIL and UNKNOWN rows that carry guidance. The standard `ComplianceGuidance` component is not
 * reused for rendering because it carries a "Copy CLI" button and copy state; this block keeps the same content and
 * imports the same GUIDANCE_WARNING text. It never renders an OK/PASS state and never invents text.
 */
const GUIDANCE_STATUSES: readonly string[] = ["FAIL", "UNKNOWN"];

export function EstateComplianceGuidance({ guidance, status }: { readonly guidance?: Guidance | null; readonly status: string }) {
  if (!guidance || !GUIDANCE_STATUSES.includes(status)) return null;
  const cli = guidance.cli?.trim() ? guidance.cli : null;
  return (
    <Box
      data-testid="estate-compliance-guidance"
      sx={{
        width: "100%",
        mt: "6px",
        p: "10px 12px",
        borderRadius: "8px",
        bgcolor: ev.surface2,
        border: `1px solid ${ev.line}`,
        fontSize: "12.5px",
        color: ev.ink,
      }}
    >
      <Typography sx={{ fontSize: "12.5px", fontWeight: 600 }}>How to fix</Typography>
      <Typography sx={{ fontSize: "12.5px" }}>{guidance.summary}</Typography>
      {guidance.steps.length > 0 && (
        <Box component="ol" sx={{ m: 0, pl: "20px", fontSize: "12.5px" }}>
          {guidance.steps.map((step, i) => <li key={i}>{step}</li>)}
        </Box>
      )}
      {cli && <>
        <Typography sx={{ fontSize: "12.5px", color: ev.watch, mt: "6px" }}>{GUIDANCE_WARNING}</Typography>
        <Box component="pre" sx={{ m: "4px 0 0", whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontFamily: ESTATE_MONO, fontSize: "12px", bgcolor: ev.sunk, p: "8px", borderRadius: "6px" }}>{cli}</Box>
      </>}
      {guidance.caution && (
        <Typography sx={{ fontSize: "12.5px", mt: "6px" }}><strong>Caution: </strong>{guidance.caution}</Typography>
      )}
      {guidance.references.length > 0 && <>
        <Typography sx={{ fontSize: "12.5px", fontWeight: 600, mt: "6px" }}>References</Typography>
        <Box component="ul" sx={{ m: 0, pl: "20px", fontSize: "12.5px", color: ev.ink2 }}>
          {guidance.references.map((reference, i) => <li key={i}>{reference}</li>)}
        </Box>
      </>}
    </Box>
  );
}
