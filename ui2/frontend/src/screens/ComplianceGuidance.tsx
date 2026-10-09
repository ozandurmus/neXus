import { useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";
import type { ComplianceGuidance as Guidance } from "../auth/adminApi";

export const GUIDANCE_WARNING = "Verify against vendor documentation and your change process before applying.";
export const observedValue = (value?: string | null) => value?.trim() ? value : "observed value not recorded";

export function ComplianceGuidance({ guidance, status }: { guidance?: Guidance | null; status: string }) {
  const [copyStatus, setCopyStatus] = useState("");
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(guidance?.cli ?? "");
      setCopyStatus("Copied");
    } catch {
      setCopyStatus("Could not copy. Select the snippet and copy it manually.");
    }
  };
  return <Box component="details" open={status === "FAIL"} sx={{ mt: 1 }}>
    <summary>How to fix</summary>
    {guidance ? <>
      <Typography>{guidance.summary}</Typography>
      <ol>{guidance.steps.map((step, i) => <li key={i}>{step}</li>)}</ol>
      {guidance.cli?.trim() && <>
        <Typography>{GUIDANCE_WARNING}</Typography>
        <Box component="pre" sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{guidance.cli}</Box>
        <M3Button emphasis="outlined" onClick={copy}>Copy CLI</M3Button>
        <Typography role="status">{copyStatus}</Typography>
      </>}
      {guidance.caution && <Typography><strong>Caution: </strong>{guidance.caution}</Typography>}
      {guidance.references.length > 0 && <><Typography sx={{ fontWeight: 600 }}>References</Typography>
        <ul>{guidance.references.map((reference, i) => <li key={i}>{reference}</li>)}</ul></>}
    </> : <Typography>Guidance not recorded for this evaluation.</Typography>}
  </Box>;
}
