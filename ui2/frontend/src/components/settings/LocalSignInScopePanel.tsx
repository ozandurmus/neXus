import { useCallback, useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import FormLabel from "@mui/material/FormLabel";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { M3Button } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { Timestamp } from "../../shell/Timestamp";
import {
  getLocalAuthMode,
  saveLocalAuthMode,
  type ApiError,
  type LocalAuthMode,
  type LocalAuthModeView,
} from "../../auth/adminApi";

function messageOf(err: unknown): string {
  const e = err as ApiError | undefined;
  const message = (e?.body as { message?: unknown } | undefined)?.message;
  return typeof message === "string" && message ? message : `The change was not saved: the request failed${e?.status ? ` (status ${e.status})` : ""}.`;
}

/**
 * Administration > Access and identity > Local sign-in (`GET/PUT /api/v2/config/local-auth-mode`, migration plan Step 3 and
 * PO decision D4). "All local accounts" is today's behaviour. "Break-glass only" lets just the root identity (nexusadmin) and
 * aiview sign in with a local password. The server refuses to switch it on when that could lock the administrator out, and
 * says why; every change is audited. CLI: `nexus local-auth-mode [ALL_LOCAL|BREAK_GLASS_ONLY]`.
 */
export function LocalSignInScopePanel() {
  const [saved, setSaved] = useState<LocalAuthModeView | null>(null);
  const [choice, setChoice] = useState<LocalAuthMode>("ALL_LOCAL");
  const [restricted, setRestricted] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setLoadError(null);
    getLocalAuthMode().then((view) => { setSaved(view); setChoice(view.mode); }).catch((e) => {
      if (isRestricted(e)) { setRestricted(true); return; }
      setLoadError(messageOf(e));
    });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (restricted) return <RestrictedPanel area="Local sign-in" role="Security Admin" />;
  if (loadError) {
    return <StatePanel variant="error" title="The local sign-in setting could not be read" body={loadError}
      action={<M3Button emphasis="outlined" onClick={load}>Retry</M3Button>} />;
  }
  if (!saved) return <Typography sx={{ p: 3 }} role="status">Loading local sign-in setting…</Typography>;

  const save = async () => {
    setBusy(true);
    setNotice(false);
    setProblem(null);
    try {
      const view = await saveLocalAuthMode(choice);
      setSaved(view);
      setChoice(view.mode);
      setNotice(true);
    } catch (e) {
      setProblem(messageOf(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card variant="outlined" data-testid="local-sign-in-scope">
      <CardContent>
        <Stack spacing={2}>
          <Typography variant="h6" component="h2">Local sign-in</Typography>
          <Typography variant="body2" color="text.secondary">
            Which local accounts may sign in with a password. Directory (LDAP and SAML) users are not affected. Break-glass
            sign-ins are audited, and a sign-in by the root identity raises an alert.
          </Typography>
          <FormControl>
            <FormLabel id="local-auth-mode-label">Local accounts allowed to sign in</FormLabel>
            <RadioGroup aria-labelledby="local-auth-mode-label" value={choice}
              onChange={(e) => { setNotice(false); setChoice(e.target.value as LocalAuthMode); }}>
              <FormControlLabel value="ALL_LOCAL" control={<Radio />} disabled={!saved.can_write}
                label="All local accounts (current behaviour)" />
              <FormControlLabel value="BREAK_GLASS_ONLY" control={<Radio />} disabled={!saved.can_write}
                label="Break-glass only: nexusadmin (root identity) and aiview" />
            </RadioGroup>
          </FormControl>
          {saved.updated_by ? (
            <Typography variant="caption" color="text.secondary">
              Last changed {saved.updated_at ? <Timestamp at={saved.updated_at} /> : null} by {saved.updated_by}
            </Typography>
          ) : null}
          {problem ? <Alert severity="error" data-testid="local-auth-mode-problem">{problem}</Alert> : null}
          {notice ? <Alert severity="success" data-testid="local-auth-mode-saved">Saved.</Alert> : null}
          <Stack direction="row">
            <M3Button emphasis="filled" disabled={!saved.can_write || busy || choice === saved.mode} onClick={save}>
              Save
            </M3Button>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}
