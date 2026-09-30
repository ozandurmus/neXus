import { useState } from "react";
import { Alert, Box, FormControlLabel, Stack, Switch, Typography } from "@mui/material";
import { acceptHttpsCertificate, getHttpsCertificate, setHttpsCertificateStrict } from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button } from "../shell/M3Widgets";
import { Ts } from "../shell/States";

export function HttpsCertificatePanel({ deviceId }: { readonly deviceId: string }) {
  const { data, error, refresh } = useFetchOnMount(() => getHttpsCertificate(deviceId), () => "Certificate details unavailable.");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  async function change(action: () => Promise<unknown>) {
    setBusy(true);
    setFailure(null);
    try { await action(); refresh(); }
    catch { setFailure("Certificate update failed. Refresh and try again."); }
    finally { setBusy(false); }
  }
  if (error) return <Alert severity="error">{error}</Alert>;
  if (!data?.available) return null;
  return <Stack spacing={2} aria-label="HTTPS certificate">
    <Typography variant="subtitle1">HTTPS certificate</Typography>
    {data.certificate_changed && <Alert severity="warning">Certificate changed. Review the observed certificate before accepting it.</Alert>}
    {failure && <Alert severity="error">{failure}</Alert>}
    {!data.certificates?.length && <Typography>No certificate observed yet.</Typography>}
    {data.certificates?.map(cert => <Box key={cert.trust_entry_id}>
      <Typography>{cert.status === "ACTIVE" ? "Pinned certificate" : "Pending certificate"}</Typography>
      <Typography sx={{ overflowWrap: "anywhere" }}>SHA-256: {cert.fingerprint_sha256}</Typography>
      <Typography>Subject CN: {cert.subject_cn || "Not present"}</Typography>
      <Typography>Issuer CN: {cert.issuer_cn || "Not present"}</Typography>
      <Typography>Expires: <Ts at={cert.not_after} /></Typography>
      {cert.status === "PENDING" && data.can_accept && <M3Button emphasis="outlined" disabled={busy}
        onClick={() => void change(() => acceptHttpsCertificate(deviceId, cert.trust_entry_id))}>Accept new certificate</M3Button>}
    </Box>)}
    {data.can_set_strict ? <FormControlLabel label="Refuse certificate changes (strict mode)" control={
      <Switch checked={data.strict ?? false} disabled={busy}
        onChange={(_, checked) => void change(() => setHttpsCertificateStrict(deviceId, checked))} />
    } /> : <Typography>Strict mode: {data.strict ? "On" : "Off"}</Typography>}
  </Stack>;
}
