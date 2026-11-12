import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import FormHelperText from "@mui/material/FormHelperText";
import FormLabel from "@mui/material/FormLabel";
import MenuItem from "@mui/material/MenuItem";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { M3Button } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { Timestamp } from "../../shell/Timestamp";
import { roleTokenLabel } from "../../shell/roleLabel";
import {
  getLdapSettings,
  saveLdapSettings,
  testLdapSettings,
  type ApiError,
  type LdapSettingsBody,
  type LdapSettingsProblem,
  type LdapSettingsView,
  type LdapTestResult,
  type LdapTrustMode,
  type LdapUserMatch,
} from "../../auth/adminApi";

const MAX_SERVERS = 5;

/** Read by a screen reader, not drawn: tells the "Remove" buttons apart. */
const visuallyHidden = { position: "absolute", width: 1, height: 1, overflow: "hidden", clip: "rect(0 0 0 0)", whiteSpace: "nowrap" } as const;

/** The fields the form can put a message under; a problem for anything else is shown as a form-level one. */
const FIELDS = ["servers", "port", "domainDn", "adminGroupDn", "usersGroupDn", "bindDn", "bindPassword",
  "connectTimeoutSeconds", "trustMode", "trustPem", "serverType"] as const;
type Field = (typeof FIELDS)[number];
type FieldErrors = Partial<Record<Field, string>>;

/** The editable form. Numbers are kept as typed so a field can be empty while it is edited; the password is never loaded. */
interface Form {
  enabled: boolean;
  servers: string[];
  port: string;
  domainDn: string;
  adminGroupDn: string;
  usersGroupDn: string;
  bindDn: string;
  bindPassword: string;
  connectTimeoutSeconds: string;
  ldaps: boolean;
  trustMode: LdapTrustMode;
  trustPem: string;
}

function formOf(view: LdapSettingsView): Form {
  return {
    enabled: view.enabled,
    servers: view.servers.length ? [...view.servers] : [""],
    port: String(view.port),
    domainDn: view.domainDn,
    adminGroupDn: view.adminGroupDn,
    usersGroupDn: view.usersGroupDn,
    bindDn: view.bindDn,
    bindPassword: "",
    connectTimeoutSeconds: String(view.connectTimeoutSeconds),
    ldaps: view.ldaps,
    trustMode: view.trustMode === "TRUST_ANY" ? "TRUST_ANY" : "TRUST_PEM",
    trustPem: view.trustPem ?? "",
  };
}

const serverList = (servers: readonly string[]) => servers.map((s) => s.trim()).filter((s) => s !== "");

function bodyOf(form: Form): LdapSettingsBody {
  return {
    enabled: form.enabled,
    serverType: "ACTIVE_DIRECTORY",
    servers: serverList(form.servers),
    port: Number(form.port),
    domainDn: form.domainDn.trim(),
    adminGroupDn: form.adminGroupDn.trim(),
    usersGroupDn: form.usersGroupDn.trim(),
    bindDn: form.bindDn.trim(),
    bindPassword: form.bindPassword,
    connectTimeoutSeconds: Number(form.connectTimeoutSeconds),
    ldaps: form.ldaps,
    trustMode: form.trustMode,
    trustPem: form.trustPem,
  };
}

/** The server refuses a blank password once the target of the bind changed; say so before the round trip. */
function connectionChanged(saved: LdapSettingsView, form: Form): boolean {
  const body = bodyOf(form);
  return body.servers.join("\n") !== saved.servers.join("\n") || body.port !== saved.port || body.bindDn !== saved.bindDn
    || body.ldaps !== saved.ldaps || body.trustMode !== saved.trustMode || body.trustPem !== (saved.trustPem ?? "");
}

interface Failure {
  readonly fields: FieldErrors;
  /** Form-level problems, and problems for a field this form does not show. */
  readonly general: readonly string[];
}

function failureOf(err: unknown, what: string): Failure {
  const e = err as ApiError | undefined;
  const problems = (e?.body as { problems?: unknown } | undefined)?.problems;
  const fields: FieldErrors = {};
  const general: string[] = [];
  if (Array.isArray(problems)) {
    for (const p of problems as Partial<LdapSettingsProblem>[]) {
      const message = typeof p?.message === "string" ? p.message : "";
      if (!message) continue;
      const field = FIELDS.find((f) => f === p.field);
      if (field) fields[field] = fields[field] ? `${fields[field]} ${message}` : message;
      else general.push(message);
    }
  }
  if (!general.length && !Object.keys(fields).length) {
    general.push(`${what}${e?.status ? ` (status ${e.status})` : ""}.`);
  }
  return { fields, general };
}

const OUTCOMES: Readonly<Record<string, string>> = {
  ok: "Reached; the service account signed in.",
  service_bind_failed: "The server refused the bind DN or its password.",
  tls_trust_failed: "The server's certificate is not trusted: check the pasted certificate and its dates.",
  tls_name_mismatch: "The server certificate does not name this address: use the DNS name on the certificate.",
  tls_handshake_failed:
    "Connected, but the TLS handshake failed: check that LDAPS is enabled on this server and that it accepts TLS 1.2 or later with a current cipher suite.",
  timeout: "No answer within the connection timeout.",
  connect_failed: "Could not connect: check the name, the port and the network path.",
  strong_auth_required: "The server requires LDAP signing or TLS: turn LDAPS on.",
  search_failed: "Signed in, but the directory search failed: check the Domain DN and the service account's read rights.",
  settings_invalid: "The settings are not valid for a connection: correct the fields above.",
  directory_error: "The directory answered with an unexpected error.",
};

/** One per-server reason code as a sentence; an unknown code is shown as itself rather than guessed at. */
export function ldapOutcomeSentence(outcome: string): string {
  return OUTCOMES[outcome] ?? `Unrecognised outcome (${outcome}).`;
}

const USER_MATCH: Readonly<Record<LdapUserMatch, { text: string; good: boolean | null }>> = {
  found: { text: "Found", good: true },
  not_found: { text: "Not found", good: false },
  ambiguous: { text: "Ambiguous: more than one account matches", good: false },
  not_run: { text: "Not checked", good: null },
  error: { text: "The search failed", good: false },
};

const roleList = (tokens: readonly string[] | undefined) => (tokens ?? []).map(roleTokenLabel).join(", ");

function ResultRow({ label, text, good, testId }: { readonly label: string; readonly text: string; readonly good: boolean | null; readonly testId?: string }) {
  return (
    <Stack component="li" direction={{ xs: "column", sm: "row" }} spacing={{ xs: 0, sm: 2 }} data-testid={testId}
      sx={{ py: 0.75, borderTop: 1, borderColor: "divider" }}>
      <Typography variant="body2" sx={{ minWidth: 260, overflowWrap: "anywhere" }}>{label}</Typography>
      <Typography variant="body2" sx={{ fontWeight: 500 }}
        color={good === null ? "text.secondary" : good ? "success.main" : "error.main"}>{text}</Typography>
    </Stack>
  );
}

const flag = (value: boolean | null, yes: string, no: string) =>
  ({ text: value === null ? "Not checked" : value ? yes : no, good: value });

function TestResult({ result }: { readonly result: LdapTestResult }) {
  const user = USER_MATCH[result.userMatch] ?? USER_MATCH.error;
  return (
    <Box component="ul" aria-label="Test result" data-testid="ldap-test-result" sx={{ listStyle: "none", m: 0, p: 0 }}>
      {result.servers.map((s, i) => (
        <ResultRow key={`${s.server}-${i}`} testId="ldap-test-server" label={`Server ${s.server}`}
          text={ldapOutcomeSentence(s.outcome)} good={s.outcome === "ok"} />
      ))}
      <ResultRow label="Service bind" text={result.serviceBindOk ? "OK" : "Failed"} good={result.serviceBindOk} />
      <ResultRow label="Domain DN" {...flag(result.domainDnExists, "Found", "Not found")} />
      <ResultRow label="Administrators group" {...flag(result.adminGroupExists, "Found", "Not found")} />
      <ResultRow label="Users group" {...flag(result.usersGroupExists, "Found", "Not found")} />
      <ResultRow label="Test user" text={user.text} good={user.good} />
      <ResultRow label="Member of administrators group" {...flag(result.inAdminGroup, "Yes", "No")} />
      <ResultRow label="Member of users group" {...flag(result.inUsersGroup, "Yes", "No")} />
    </Box>
  );
}

/**
 * Administration › LDAP: the Active Directory service-bind settings (LDAP_SERVICE_BIND_DESIGN_2026_11_11, sections 2, 3
 * and 7; `GET/PUT /api/v2/config/ldap`, `POST /api/v2/config/ldap/test`). The server validates; its messages are shown
 * under the field they name. The bind password is write-only: it is never loaded, and it is cleared after every save.
 */
export function DirectorySettingsPanel() {
  const [saved, setSaved] = useState<LdapSettingsView | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [restricted, setRestricted] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [saveProblems, setSaveProblems] = useState<readonly string[]>([]);
  const [savedNotice, setSavedNotice] = useState(false);
  const [busy, setBusy] = useState<"save" | "test" | null>(null);
  const [testUser, setTestUser] = useState("");
  const [testResult, setTestResult] = useState<LdapTestResult | null>(null);
  const [testProblems, setTestProblems] = useState<readonly string[]>([]);

  const load = useCallback(() => {
    setLoadError(null);
    getLdapSettings().then((view) => { setSaved(view); setForm(formOf(view)); }).catch((e) => {
      if (isRestricted(e)) { setRestricted(true); return; }
      setLoadError(failureOf(e, "The request failed").general.join(" "));
    });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (restricted) return <RestrictedPanel area="LDAP Settings" role="Security Admin" />;
  if (loadError) {
    return <StatePanel variant="error" title="LDAP settings could not be read" body={loadError}
      action={<M3Button emphasis="outlined" onClick={load}>Retry</M3Button>} />;
  }
  if (!saved || !form) return <Typography sx={{ p: 3 }} role="status">Loading LDAP settings…</Typography>;

  const change = <K extends keyof Form>(key: K, value: Form[K], field?: Field) => {
    setSavedNotice(false);
    setForm((current) => (current ? { ...current, [key]: value } : current));
    const cleared = field ?? FIELDS.find((f) => f === key);
    if (cleared) setErrors((current) => (current[cleared] ? { ...current, [cleared]: undefined } : current));
  };
  const setServer = (index: number, value: string) =>
    change("servers", form.servers.map((s, i) => (i === index ? value : s)));
  const fieldError = (field: Field, helper?: string) => ({ error: Boolean(errors[field]), helperText: errors[field] ?? helper });

  const save = async () => {
    setBusy("save");
    setSavedNotice(false);
    setSaveProblems([]);
    setErrors({});
    try {
      const view = await saveLdapSettings(bodyOf(form));
      setSaved(view);
      setForm(formOf(view));
      setSavedNotice(true);
    } catch (e) {
      const failure = failureOf(e, "LDAP settings were not saved: the request failed");
      setErrors(failure.fields);
      setSaveProblems(Object.keys(failure.fields).length && !failure.general.length
        ? ["Not saved: correct the marked fields."] : failure.general);
    } finally {
      setBusy(null);
    }
  };

  const test = async () => {
    setBusy("test");
    setTestResult(null);
    setTestProblems([]);
    setErrors({});
    try {
      setTestResult(await testLdapSettings(bodyOf(form), testUser.trim()));
    } catch (e) {
      const failure = failureOf(e, "The test did not run: the request failed");
      setErrors(failure.fields);
      setTestProblems(Object.keys(failure.fields).length && !failure.general.length
        ? ["The test did not run: correct the marked fields."] : failure.general);
    } finally {
      setBusy(null);
    }
  };

  const passwordHelper = !saved.bindPasswordSet
    ? "The service account's password. It is stored encrypted and never shown again."
    : form.bindPassword === "" && connectionChanged(saved, form)
      ? "The servers, port, bind DN, LDAPS or trust settings changed: enter the password again. The stored password is never sent to a different server."
      : "A password is stored. Leave blank to keep it.";
  const adminRoles = roleList(saved.adminGroupRoles);
  const usersRoles = roleList(saved.usersGroupRoles);

  return (
    <Stack spacing={2} sx={{ maxWidth: 900 }}>
      <Box>
        <Typography variant="h6" component="h3">LDAP directory</Typography>
        <Typography variant="body2" color="text.secondary">
          Users sign in with their sAMAccountName (for example jdoe) and their directory password. Local accounts keep working.
        </Typography>
        <Typography variant="caption" color="text.secondary" data-testid="ldap-settings-last-saved">
          {saved.configured ? <>Last saved <Timestamp at={saved.updatedAt} /></> : "Not configured yet: nothing is saved."}
        </Typography>
      </Box>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="subtitle1" component="h4">Directory</Typography>
        <FormControlLabel label="Enable LDAP authentication"
          control={<Switch checked={form.enabled} onChange={(e) => change("enabled", e.target.checked)} />} />
        <TextField select disabled label="Server type" value="ACTIVE_DIRECTORY" sx={{ maxWidth: 320 }} {...fieldError("serverType")}>
          <MenuItem value="ACTIVE_DIRECTORY">Active Directory</MenuItem>
        </TextField>

        <FormControl component="fieldset" error={Boolean(errors.servers)}>
          <FormLabel component="legend" sx={{ mb: 1 }}>LDAP server names or IPs</FormLabel>
          <Stack spacing={1}>
            {form.servers.map((server, index) => (
              <Stack key={index} direction="row" spacing={1} alignItems="center">
                <TextField label={`Server ${index + 1}`} size="small" fullWidth value={server} autoComplete="off"
                  error={Boolean(errors.servers)}
                  placeholder={index === 0 ? "dc1.example.local" : "192.0.2.10"}
                  onChange={(e) => setServer(index, e.target.value)} />
                <M3Button emphasis="text" disabled={form.servers.length === 1}
                  onClick={() => change("servers", form.servers.filter((_, i) => i !== index))}>
                  Remove<Box component="span" sx={visuallyHidden}> server {index + 1}</Box>
                </M3Button>
              </Stack>
            ))}
          </Stack>
          <FormHelperText sx={{ mx: 0 }} data-testid="ldap-servers-helper">
            {errors.servers ?? "Tried in order. Host name or IP address, no scheme or port."}
          </FormHelperText>
          <Box sx={{ mt: 0.5 }}>
            <M3Button emphasis="outlined" disabled={form.servers.length >= MAX_SERVERS}
              onClick={() => change("servers", [...form.servers, ""])}>Add server</M3Button>
            {form.servers.length >= MAX_SERVERS && (
              <Typography variant="caption" color="text.secondary" sx={{ ml: 1.5 }}>At most {MAX_SERVERS} servers.</Typography>
            )}
          </Box>
        </FormControl>

        <TextField label="Domain DN (search base)" fullWidth value={form.domainDn} autoComplete="off"
          placeholder="DC=example,DC=local" onChange={(e) => change("domainDn", e.target.value)}
          {...fieldError("domainDn", "Users and the two groups are looked up under this DN.")} />
        <TextField label="Administrators group DN" fullWidth value={form.adminGroupDn} autoComplete="off"
          placeholder="CN=nexus-admins,OU=Groups,DC=example,DC=local" onChange={(e) => change("adminGroupDn", e.target.value)}
          {...fieldError("adminGroupDn", adminRoles ? `Members receive: ${adminRoles}.` : "Members receive the administrator roles.")} />
        <TextField label="Users group DN" fullWidth value={form.usersGroupDn} autoComplete="off"
          placeholder="CN=nexus-users,OU=Groups,DC=example,DC=local" onChange={(e) => change("usersGroupDn", e.target.value)}
          {...fieldError("usersGroupDn", usersRoles ? `Members receive: ${usersRoles}.` : "Members receive the user roles.")} />
      </Stack></CardContent></Card>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="subtitle1" component="h4">Connection and service account</Typography>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
          <TextField label="Port" type="number" value={form.port} onChange={(e) => change("port", e.target.value)}
            inputProps={{ min: 1, max: 65535, step: 1 }} sx={{ width: { sm: 220 } }}
            {...fieldError("port", "636 for LDAPS, 389 for plain LDAP.")} />
          <TextField label="Connection timeout (seconds)" type="number" value={form.connectTimeoutSeconds}
            onChange={(e) => change("connectTimeoutSeconds", e.target.value)}
            inputProps={{ min: 1, max: 60, step: 1 }} sx={{ width: { sm: 260 } }}
            {...fieldError("connectTimeoutSeconds", "1 to 60.")} />
        </Stack>
        <TextField label="LDAP bind DN" fullWidth value={form.bindDn} autoComplete="off"
          placeholder="CN=svc-nexus,OU=Service Accounts,DC=example,DC=local or svc-nexus@example.local"
          onChange={(e) => change("bindDn", e.target.value)}
          {...fieldError("bindDn", "The service account neXus uses to look up users and group membership. A DN or name@domain.")} />
        <TextField label="LDAP bind password" type="password" fullWidth value={form.bindPassword} autoComplete="new-password"
          onChange={(e) => change("bindPassword", e.target.value)}
          {...fieldError("bindPassword", passwordHelper)} />
      </Stack></CardContent></Card>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="subtitle1" component="h4">Transport security</Typography>
        <FormControlLabel label="LDAPS"
          control={<Switch checked={form.ldaps} onChange={(e) => change("ldaps", e.target.checked)} />} />
        {!form.ldaps && (
          <Alert severity="warning">
            Plain LDAP sends the service account password and every user's password over the network in clear text.
          </Alert>
        )}
        {form.ldaps && (
          <>
            <FormControl component="fieldset" error={Boolean(errors.trustMode)}>
              <FormLabel component="legend">Server certificate</FormLabel>
              <RadioGroup value={form.trustMode} onChange={(e) => change("trustMode", e.target.value as LdapTrustMode)}>
                <FormControlLabel value="TRUST_PEM" control={<Radio />} label="Trust only the certificate below" />
                <FormControlLabel value="TRUST_ANY" control={<Radio />} label="Trust any certificate" />
              </RadioGroup>
              {errors.trustMode && <FormHelperText sx={{ mx: 0 }}>{errors.trustMode}</FormHelperText>}
            </FormControl>
            {form.trustMode === "TRUST_ANY" ? (
              <Alert severity="warning">
                neXus will accept any certificate, including one from a machine impersonating the directory. Use only on a
                trusted internal network; this risk was accepted by the Product Owner.
              </Alert>
            ) : (
              <TextField label="Certificate (PEM)" multiline minRows={5} maxRows={14} fullWidth value={form.trustPem}
                onChange={(e) => change("trustPem", e.target.value)} spellCheck={false}
                placeholder={"-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----"}
                InputProps={{ sx: { fontFamily: "monospace", fontSize: 13 } }}
                {...fieldError("trustPem", "The server's certificate or the issuing CA, PEM. The server name must match the certificate (an IP address must be in its subject alternative names).")} />
            )}
          </>
        )}
      </Stack></CardContent></Card>

      {savedNotice && <Alert severity="success">LDAP settings saved.</Alert>}
      {saveProblems.length > 0 && (
        <Alert severity="error" data-testid="ldap-save-error">
          {saveProblems.map((p) => <Box key={p}>{p}</Box>)}
        </Alert>
      )}
      <Box>
        <M3Button emphasis="filled" disabled={busy !== null} onClick={() => void save()}>
          {busy === "save" ? "Saving…" : "Save LDAP settings"}
        </M3Button>
      </Box>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Box>
          <Typography variant="subtitle1" component="h4">Test connection</Typography>
          <Typography variant="body2" color="text.secondary">
            Tests the form as entered above, saved or not. The test never signs the user in and never shows directory attributes.
          </Typography>
        </Box>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2} alignItems={{ sm: "flex-start" }}>
          <TextField label="Test user name (sAMAccountName)" size="small" value={testUser} autoComplete="off"
            placeholder="jdoe" onChange={(e) => setTestUser(e.target.value)} sx={{ width: { sm: 320 } }} />
          <M3Button emphasis="outlined" disabled={busy !== null} onClick={() => void test()}>
            {busy === "test" ? "Testing…" : "Test connection"}
          </M3Button>
        </Stack>
        {testProblems.length > 0 && (
          <Alert severity="error" data-testid="ldap-test-error">
            {testProblems.map((p) => <Box key={p}>{p}</Box>)}
          </Alert>
        )}
        {testResult && <TestResult result={testResult} />}
      </Stack></CardContent></Card>
    </Stack>
  );
}
