import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getHttpsCertificate, type HttpsCertificateView } from "../auth/adminApi";
import { certificateState, hasCertificateRecord } from "../screens/HttpsCertificatePanel";
import { isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { EstateCard } from "./EstatePrimitives";
import { ev } from "./estateTokens";

export const CERTIFICATE_UNKNOWN_TEXT = "UNKNOWN: the HTTPS certificate could not be read.";
export const CERTIFICATE_RESTRICTED_TEXT = "Restricted: your role does not include reading the HTTPS certificate.";
export const CERTIFICATE_NO_RECORD_TEXT = "No HTTPS certificate record.";

/** The standard device workspace that holds the HTTPS certificate panel. */
export function certificateStandardHref(deviceId: string): string {
  return `/devices/${encodeURIComponent(deviceId)}?standard=1`;
}

/**
 * Dossier block for the unit's HTTPS certificate: the state the standard device panel shows, read-only. It states
 * the expiry word, whether the certificate changed since trust was recorded, and the strict-mode flag as read. It
 * never accepts a certificate or changes strict mode; the action block links to the standard view. Without a
 * device id it renders nothing and makes no call.
 */
export function EstateDossierCertificateBlock({ deviceId }: { readonly deviceId: string | null }) {
  if (!deviceId) return null;
  return <CertificateLoader key={deviceId} deviceId={deviceId} />;
}

function describeCertificateError(err: unknown): string {
  return isRestricted(err) ? CERTIFICATE_RESTRICTED_TEXT : CERTIFICATE_UNKNOWN_TEXT;
}

function CertificateLoader({ deviceId }: { readonly deviceId: string }) {
  const fetched = useFetchOnMount<HttpsCertificateView>(
    () => getHttpsCertificate(deviceId),
    describeCertificateError,
  );
  const href = certificateStandardHref(deviceId);
  const malformed = fetched.data !== null && typeof fetched.data.available !== "boolean";

  let body;
  if (fetched.error || malformed) {
    const restricted = fetched.error === CERTIFICATE_RESTRICTED_TEXT || (fetched.rawError !== null && isRestricted(fetched.rawError));
    body = (
      <Typography
        data-testid={restricted ? "dossier-certificate-restricted" : "dossier-certificate-unknown"}
        sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}
      >
        {restricted ? CERTIFICATE_RESTRICTED_TEXT : CERTIFICATE_UNKNOWN_TEXT}
      </Typography>
    );
  } else if (!fetched.data) {
    body = (
      <Typography sx={{ px: "18px", pb: "14px", fontSize: "12.5px", color: ev.ink3 }}>Loading HTTPS certificate…</Typography>
    );
  } else {
    const view = fetched.data;
    if (!view.available) {
      body = (
        <Typography data-testid="dossier-certificate-none" sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>
          {CERTIFICATE_NO_RECORD_TEXT}
        </Typography>
      );
    } else {
      const state = certificateState(view);
      const pinned = view.certificates?.find((c) => c.status === "ACTIVE");
      const pendingCount = (view.certificates ?? []).filter((c) => c.status === "PENDING").length;
      body = (
        <Box sx={{ px: "18px", pb: "14px", display: "flex", flexDirection: "column", gap: "6px" }} data-testid="dossier-certificate">
          {state ? (
            <Typography data-testid="dossier-certificate-state" sx={{ fontSize: "14.5px", fontWeight: 650, color: ev.ink }}>
              {state.label}
            </Typography>
          ) : null}
          {hasCertificateRecord(view) ? null : (
            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>No certificate observed yet.</Typography>
          )}
          {view.certificate_changed === true ? (
            <Typography data-testid="dossier-certificate-changed" sx={{ fontSize: "12.5px", color: ev.ink2 }}>
              Certificate changed since trust was recorded.
            </Typography>
          ) : view.certificate_changed === false ? (
            <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>Certificate unchanged since trust was recorded.</Typography>
          ) : null}
          {pinned ? (
            <Typography sx={{ fontSize: "12px", color: ev.ink3, overflowWrap: "anywhere" }}>
              Pinned SHA-256: {pinned.fingerprint_sha256}
            </Typography>
          ) : null}
          {pendingCount > 0 ? (
            <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>
              {pendingCount} pending {pendingCount === 1 ? "certificate" : "certificates"} not shown here.
            </Typography>
          ) : null}
          <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
            Strict mode (refuse certificate changes): {view.strict === true ? "On" : view.strict === false ? "Off" : "not reported"}
          </Typography>
          <Typography variant="caption" component="div" sx={{ color: ev.ink3 }}>
            The read returns no observation time, so no evidence age is shown for this certificate.
          </Typography>
        </Box>
      );
    }
  }

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box sx={{ px: "18px", pt: "14px", pb: "8px" }}>
        <Typography sx={{ fontSize: "14px", fontWeight: 600, color: ev.ink }}>HTTPS certificate</Typography>
        <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>
          The certificate this device presents, as the standard device panel reads it. Read-only.
        </Typography>
      </Box>
      {body}
      <Box sx={{ px: "18px", pb: "14px" }}>
        <Box component="a" href={href} sx={{ fontSize: "13px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}>
          Open in standard view
        </Box>
      </Box>
    </EstateCard>
  );
}
