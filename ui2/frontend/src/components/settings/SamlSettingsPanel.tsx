import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { M3Button } from "../../shell/M3Widgets";
import { PRODUCT_NAME } from "../../brand/product";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { Timestamp } from "../../shell/Timestamp";
import { roleTokenLabel } from "../../shell/roleLabel";
import {
  getSamlSettings,
  saveSamlSettings,
  type ApiError,
  type SamlSettingsBody,
  type SamlSettingsProblem,
  type SamlSettingsView,
} from "../../auth/adminApi";

const FIELDS = ["spBaseUrl", "spEntityId", "idpMetadataXml", "idpEntityId", "idpSsoUrl", "idpSsoBinding", "idpSigningCertPem",
  "groupClaimAttribute", "adminGroupValue", "usersGroupValue", "clockSkewSeconds", "signAuthnRequests", "spCertPem",
  "spPrivateKeyPem"] as const;
type Field = (typeof FIELDS)[number];
type FieldErrors = Partial<Record<Field, string>>;

/** The editable form. Numbers are kept as typed; the pasted metadata and the SP private key are never loaded back. */
interface Form {
  enabled: boolean;
  spBaseUrl: string;
  spEntityId: string;
  idpMetadataXml: string;
  idpEntityId: string;
  idpSsoUrl: string;
  idpSsoBinding: string;
  idpSigningCertPem: string;
  groupClaimAttribute: string;
  adminGroupValue: string;
  usersGroupValue: string;
  clockSkewSeconds: string;
  signAuthnRequests: boolean;
  spCertPem: string;
  spPrivateKeyPem: string;
  clearSpKey: boolean;
}

function formOf(view: SamlSettingsView): Form {
  return {
    enabled: view.enabled,
    spBaseUrl: view.spBaseUrl,
    spEntityId: view.spEntityId,
    idpMetadataXml: "",
    idpEntityId: view.idpEntityId,
    idpSsoUrl: view.idpSsoUrl,
    idpSsoBinding: view.idpSsoBinding,
    idpSigningCertPem: view.idpSigningCertPem,
    groupClaimAttribute: view.groupClaimAttribute,
    adminGroupValue: view.adminGroupValue,
    usersGroupValue: view.usersGroupValue,
    clockSkewSeconds: String(view.clockSkewSeconds),
    signAuthnRequests: view.signAuthnRequests,
    spCertPem: view.spKey.source === "SETTINGS" ? view.spKey.certPem : "",
    spPrivateKeyPem: "",
    clearSpKey: false,
  };
}

function bodyOf(form: Form): SamlSettingsBody {
  return {
    enabled: form.enabled,
    spBaseUrl: form.spBaseUrl.trim(),
    spEntityId: form.spEntityId.trim(),
    idpMetadataXml: form.idpMetadataXml.trim() || undefined,
    idpEntityId: form.idpEntityId.trim(),
    idpSsoUrl: form.idpSsoUrl.trim(),
    idpSsoBinding: form.idpSsoBinding,
    idpSigningCertPem: form.idpSigningCertPem,
    groupClaimAttribute: form.groupClaimAttribute.trim(),
    adminGroupValue: form.adminGroupValue.trim(),
    usersGroupValue: form.usersGroupValue.trim(),
    clockSkewSeconds: Number(form.clockSkewSeconds),
    signAuthnRequests: form.signAuthnRequests,
    spCertPem: form.spCertPem,
    spPrivateKeyPem: form.spPrivateKeyPem,
    clearSpKey: form.clearSpKey || undefined,
  };
}

interface Failure {
  readonly fields: FieldErrors;
  readonly general: readonly string[];
}

function failureOf(err: unknown, what: string): Failure {
  const e = err as ApiError | undefined;
  const problems = (e?.body as { problems?: unknown } | undefined)?.problems;
  const fields: FieldErrors = {};
  const general: string[] = [];
  if (Array.isArray(problems)) {
    for (const p of problems as Partial<SamlSettingsProblem>[]) {
      const message = typeof p?.message === "string" ? p.message : "";
      if (!message) continue;
      const field = FIELDS.find((f) => f === p.field);
      if (field) fields[field] = fields[field] ? `${fields[field]} ${message}` : message;
      else general.push(message);
    }
  }
  if (!general.length && !Object.keys(fields).length) general.push(`${what}${e?.status ? ` (status ${e.status})` : ""}.`);
  return { fields, general };
}

const roleList = (tokens: readonly string[] | undefined) => (tokens ?? []).map(roleTokenLabel).join(", ");
const mono = { sx: { fontFamily: "monospace", fontSize: 13 } } as const;

const KEY_SOURCE: Readonly<Record<string, string>> = {
  NONE: "No service provider key is configured: sign-in requests are not signed and encrypted assertions cannot be read.",
  FILE: "The service provider key comes from files on the server (UI2_SAML_SP_PRIVATE_KEY_PATH and UI2_SAML_SP_CERT_PATH). The files win over a key stored here.",
  SETTINGS: "A service provider key is stored here, encrypted. It is never shown again.",
};

/**
 * Administration > SAML sign-in (migration plan Step 4; `GET/PUT /api/v2/config/saml`). ADFS signs the assertion; neXus verifies
 * it with the IdP certificate taken from the pasted metadata. The group to role mapping lives here, as the LDAP one does. The
 * server validates and its messages are shown under the field they name. CLI: `nexus saml`, `saml-set`, `saml-off`.
 */
export function SamlSettingsPanel() {
  const [saved, setSaved] = useState<SamlSettingsView | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [restricted, setRestricted] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [problems, setProblems] = useState<readonly string[]>([]);
  const [notice, setNotice] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setLoadError(null);
    getSamlSettings().then((view) => { setSaved(view); setForm(formOf(view)); }).catch((e) => {
      if (isRestricted(e)) { setRestricted(true); return; }
      setLoadError(failureOf(e, "The request failed").general.join(" "));
    });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (restricted) return <RestrictedPanel area="SAML sign-in" role="Security Admin" />;
  if (loadError) {
    return <StatePanel variant="error" title="SAML settings could not be read" body={loadError}
      action={<M3Button emphasis="outlined" onClick={load}>Retry</M3Button>} />;
  }
  if (!saved || !form) return <Typography sx={{ p: 3 }} role="status">Loading SAML settings…</Typography>;

  const change = <K extends keyof Form>(key: K, value: Form[K]) => {
    setNotice(false);
    setForm((current) => (current ? { ...current, [key]: value } : current));
    const cleared = FIELDS.find((f) => f === key);
    if (cleared) setErrors((current) => (current[cleared] ? { ...current, [cleared]: undefined } : current));
  };
  const fieldError = (field: Field, helper?: string) => ({ error: Boolean(errors[field]), helperText: errors[field] ?? helper });

  const save = async () => {
    setBusy(true);
    setNotice(false);
    setProblems([]);
    setErrors({});
    try {
      const view = await saveSamlSettings(bodyOf(form));
      setSaved(view);
      setForm(formOf(view));
      setNotice(true);
    } catch (e) {
      const failure = failureOf(e, "SAML settings were not saved: the request failed");
      setErrors(failure.fields);
      setProblems(Object.keys(failure.fields).length && !failure.general.length
        ? ["Not saved: correct the marked fields."] : failure.general);
    } finally {
      setBusy(false);
    }
  };

  const adminRoles = roleList(saved.adminGroupRoles);
  const usersRoles = roleList(saved.usersGroupRoles);
  const urls: ReadonlyArray<readonly [string, string, string]> = [
    ["Sign-in address", saved.loginUrl, "saml-login-url"],
    ["Assertion consumer service (ACS) address", saved.acsUrl, "saml-acs-url"],
    ["Service provider metadata", saved.metadataUrl, "saml-metadata-url"],
  ];

  return (
    <Stack spacing={2} sx={{ maxWidth: 900 }}>
      <Box>
        <Typography variant="h6" component="h3">SAML sign-in (ADFS)</Typography>
        <Typography variant="body2" color="text.secondary">
          Users sign in with single sign-on at ADFS and return with a signed assertion. LDAP and local accounts keep working beside it.
          The group to role mapping below stays inside {PRODUCT_NAME}.
        </Typography>
        <Typography variant="caption" color="text.secondary" data-testid="saml-settings-last-saved">
          {saved.configured ? <>Last saved <Timestamp at={saved.updatedAt} /></> : "Not configured yet: SAML sign-in is off."}
        </Typography>
      </Box>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="subtitle1" component="h4">Service provider ({PRODUCT_NAME})</Typography>
        <FormControlLabel label="Enable SAML sign-in"
          control={<Switch checked={form.enabled} onChange={(e) => change("enabled", e.target.checked)} />} />
        <TextField label={`Address users reach ${PRODUCT_NAME} at`} fullWidth value={form.spBaseUrl} autoComplete="off"
          placeholder="https://nexus.example.local" onChange={(e) => change("spBaseUrl", e.target.value)}
          {...fieldError("spBaseUrl", "https://host[:port], without a path. The addresses below are built from it.")} />
        <TextField label="Service provider entity id" fullWidth value={form.spEntityId} autoComplete="off"
          placeholder="Leave blank for the metadata address" onChange={(e) => change("spEntityId", e.target.value)}
          {...fieldError("spEntityId", "The identifier ADFS knows this service by (the relying party identifier).")} />
        {saved.configured && (
          <Box component="ul" aria-label="Addresses for the ADFS relying party trust" sx={{ listStyle: "none", m: 0, p: 0 }}>
            {urls.map(([label, value, id]) => (
              <Stack component="li" key={id} direction={{ xs: "column", sm: "row" }} spacing={{ xs: 0, sm: 2 }}
                sx={{ py: 0.5, borderTop: 1, borderColor: "divider" }}>
                <Typography variant="body2" sx={{ minWidth: 280 }}>{label}</Typography>
                <Typography variant="body2" data-testid={id} sx={{ fontFamily: "monospace", overflowWrap: "anywhere" }}>{value}</Typography>
              </Stack>
            ))}
          </Box>
        )}
      </Stack></CardContent></Card>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="subtitle1" component="h4">Identity provider (ADFS)</Typography>
        <TextField label="Paste the ADFS federation metadata (XML)" multiline minRows={4} maxRows={12} fullWidth
          value={form.idpMetadataXml} spellCheck={false} onChange={(e) => change("idpMetadataXml", e.target.value)}
          placeholder="<EntityDescriptor …>"
          InputProps={mono}
          {...fieldError("idpMetadataXml", "Optional. When pasted, it replaces the four fields below on save. Nothing is fetched from ADFS.")} />
        <TextField label="Identity provider entity id" fullWidth value={form.idpEntityId} autoComplete="off"
          onChange={(e) => change("idpEntityId", e.target.value)} {...fieldError("idpEntityId")} />
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
          <TextField label="Sign-in URL" fullWidth value={form.idpSsoUrl} autoComplete="off"
            placeholder="https://adfs.example.local/adfs/ls/" onChange={(e) => change("idpSsoUrl", e.target.value)}
            {...fieldError("idpSsoUrl")} />
          <TextField select label="Sign-in binding" value={form.idpSsoBinding} sx={{ minWidth: 200 }}
            onChange={(e) => change("idpSsoBinding", e.target.value)} {...fieldError("idpSsoBinding")}>
            <MenuItem value="REDIRECT">HTTP redirect</MenuItem>
            <MenuItem value="POST">HTTP POST</MenuItem>
          </TextField>
        </Stack>
        <TextField label="ADFS token-signing certificate(s) (PEM)" multiline minRows={4} maxRows={12} fullWidth
          value={form.idpSigningCertPem} spellCheck={false} onChange={(e) => change("idpSigningCertPem", e.target.value)}
          InputProps={mono}
          {...fieldError("idpSigningCertPem", "Every assertion must be signed by one of these. Include the next certificate before ADFS rolls over.")} />
      </Stack></CardContent></Card>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="subtitle1" component="h4">Groups and roles</Typography>
        <TextField label="Group claim name" fullWidth value={form.groupClaimAttribute} autoComplete="off"
          onChange={(e) => change("groupClaimAttribute", e.target.value)}
          {...fieldError("groupClaimAttribute", "The ADFS claim that carries the groups. Default: http://schemas.xmlsoap.org/claims/Group")} />
        <TextField label="Administrators group value" fullWidth value={form.adminGroupValue} autoComplete="off"
          onChange={(e) => change("adminGroupValue", e.target.value)}
          {...fieldError("adminGroupValue", adminRoles ? `Members receive: ${adminRoles}.` : "Members receive the administrator roles.")} />
        <TextField label="Users group value" fullWidth value={form.usersGroupValue} autoComplete="off"
          onChange={(e) => change("usersGroupValue", e.target.value)}
          {...fieldError("usersGroupValue", usersRoles ? `Members receive: ${usersRoles}.` : "Members receive the user roles.")} />
        <Typography variant="body2" color="text.secondary">
          A user who is in neither group is refused with a clear message and gets no access. Group changes in ADFS apply at the next sign-in.
        </Typography>
        <TextField label="Allowed clock difference (seconds)" type="number" value={form.clockSkewSeconds} sx={{ width: { sm: 280 } }}
          onChange={(e) => change("clockSkewSeconds", e.target.value)} inputProps={{ min: 0, max: 300, step: 1 }}
          {...fieldError("clockSkewSeconds", `0 to 300. How far the ADFS and ${PRODUCT_NAME} clocks may differ.`)} />
      </Stack></CardContent></Card>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="subtitle1" component="h4">Service provider key (optional)</Typography>
        <Typography variant="body2" color="text.secondary" data-testid="saml-key-source">{KEY_SOURCE[saved.spKey.source]}</Typography>
        <FormControlLabel label="Sign sign-in requests"
          control={<Switch checked={form.signAuthnRequests} onChange={(e) => change("signAuthnRequests", e.target.checked)} />} />
        {saved.spKey.source !== "FILE" && (
          <>
            <TextField label="Service provider certificate (PEM)" multiline minRows={3} maxRows={10} fullWidth value={form.spCertPem}
              spellCheck={false} onChange={(e) => change("spCertPem", e.target.value)} InputProps={mono}
              {...fieldError("spCertPem")} />
            <TextField label="Service provider private key (PKCS#8 PEM)" multiline minRows={3} maxRows={10} fullWidth
              value={form.spPrivateKeyPem} spellCheck={false} autoComplete="off"
              onChange={(e) => change("spPrivateKeyPem", e.target.value)} InputProps={mono}
              {...fieldError("spPrivateKeyPem", saved.spKey.privateKeySet ? "A key is stored. Leave blank to keep it." : "Stored encrypted and never shown again.")} />
            {saved.spKey.source === "SETTINGS" && (
              <FormControlLabel label="Remove the stored key"
                control={<Switch checked={form.clearSpKey} onChange={(e) => change("clearSpKey", e.target.checked)} />} />
            )}
          </>
        )}
      </Stack></CardContent></Card>

      {notice && <Alert severity="success">SAML settings saved.</Alert>}
      {problems.length > 0 && (
        <Alert severity="error" data-testid="saml-save-error">
          {problems.map((p) => <Box key={p}>{p}</Box>)}
        </Alert>
      )}
      <Box>
        <M3Button emphasis="filled" disabled={busy} onClick={() => void save()}>
          {busy ? "Saving…" : "Save SAML settings"}
        </M3Button>
      </Box>
    </Stack>
  );
}
