import { useState } from "react";
import { Alert, Box, FormControlLabel, Stack, Switch, Typography } from "@mui/material";
import { acceptHttpsCertificate, getHttpsCertificate, setHttpsCertificateStrict, type HttpsCertificateView } from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { Ts } from "../shell/States";
import type { Tone } from "../shell/tone";

const DAY_MS = 86_400_000;

/**
 * The pinned HTTPS certificate's state as a header pill, always a word with the colour (UI review 2026-10-18,
 * P0-1B): expired is critical, under 30 days warning, otherwise neutral; a device with an HTTPS endpoint and no
 * certificate yet reads "No certificate observed". Null when the device has no HTTPS endpoint at all.
 * PO 2026-10-18: this pill is separate from "Identity verified", which expiry does not change.
 */
export function certificateState(view: HttpsCertificateView | null | undefined, now: Date = new Date()):
  { readonly tone: Tone; readonly label: string } | null {
  if (!view?.available) return null;
  const pinned = view.certificates?.find((c) => c.status === "ACTIVE");
  if (!pinned) return { tone: "neutral", label: "No certificate observed" };
  const notAfter = Date.parse(pinned.not_after);
  if (Number.isNaN(notAfter)) return { tone: "neutral", label: "Certificate expiry UNKNOWN" };
  const remaining = notAfter - now.getTime();
  if (remaining <= 0) {
    const days = Math.floor(-remaining / DAY_MS);
    return { tone: "bad", label: days === 0 ? "Certificate expired today" : `Certificate expired ${days} d ago` };
  }
  const days = Math.floor(remaining / DAY_MS);
  return {
    tone: days < 30 ? "warn" : "neutral",
    label: days === 0 ? "Certificate expires today" : `Certificate expires in ${days} d`,
  };
}

export function CertificateStatePill({ view, now }: { readonly view: HttpsCertificateView | null | undefined; readonly now?: Date }) {
  const state = certificateState(view, now);
  return state ? <StatusChip tone={state.tone} label={state.label} dense /> : null;
}

/** The certificate card for a page that does not load the certificate itself (Administration device workspace). */
export function HttpsCertificatePanel({ deviceId }: { readonly deviceId: string }) {
  const fetched = useFetchOnMount(() => getHttpsCertificate(deviceId), () => "Certificate details unavailable.");
  return <HttpsCertificateCard deviceId={deviceId} {...fetched} />;
}

/** SHA-256, Subject CN, Issuer CN, Expires and Strict mode, with the accept and strict-mode actions. */
export function HttpsCertificateCard({ deviceId, data, error, refresh }: {
  readonly deviceId: string;
  readonly data: HttpsCertificateView | null;
  readonly error: string | null;
  readonly refresh: () => void;
}) {
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
