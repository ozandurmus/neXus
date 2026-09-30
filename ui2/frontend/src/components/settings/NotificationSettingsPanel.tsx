import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { M3Button } from "../../shell/M3Widgets";
import { RestrictedPanel } from "../../shell/States";
import {
  getNotificationSettings,
  saveNotificationSettings,
  testNotification,
  type ApiError,
  type NotificationSettingsView,
  type NotificationRouteView,
} from "../../auth/adminApi";

type Message = { text: string; type: "success" | "error" | "info" };
const TYPES: { type: NotificationRouteView["type"]; title: string; description: string }[] = [
  { type: "admin_event", title: "Administration changes", description: "Identity, roles, credentials, devices, backup target and notification settings" },
  { type: "login_security", title: "Sign-in and access", description: "Rejected sign-ins and sessions, and session takeover" },
  { type: "backup_failure", title: "Backup failures", description: "Failed or partial backup jobs" },
  { type: "job_failure", title: "Other job failures", description: "Failed jobs other than backups" },
  { type: "config_change", title: "Configuration changes", description: "New configuration notifications" },
  { type: "compliance_regression", title: "Compliance regressions", description: "Controls changing from PASS to FAIL" },
  { type: "device_health", title: "Device reachability", description: "Unreachable, reachable again and host-key mismatch" },
  { type: "security_scan", title: "Security scans", description: "Daily and weekly security findings: new, fixed and accepted counts" },
];

function problemsOf(err: unknown): string {
  const body = (err as ApiError)?.body as { problems?: string[]; error?: string } | undefined;
  if (body?.problems?.length) return body.problems.join("; ");
  if (typeof body?.error === "string") return body.error;
  return `request failed${(err as ApiError)?.status ? ` (status ${(err as ApiError).status})` : ""}`;
}

/**
 * Administration › Notifications: remote logging (syslog, RFC 5424 over UDP/TCP) and an internal SMTP relay,
 * per-type routes, and a test send using the current form values.
 */
export function NotificationSettingsPanel() {
  const [s, setS] = useState<NotificationSettingsView | null>(null);
  const [message, setMessage] = useState<Message | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [problems, setProblems] = useState<string[]>([]);

  const [restricted, setRestricted] = useState(false);
  useEffect(() => {
    getNotificationSettings().then(setS).catch((e) => {
      if ((e as ApiError)?.status === 403) {
        setRestricted(true);
        return;
      }
      setMessage({ text: `Settings could not be read: ${problemsOf(e)}`, type: "error" });
    });
  }, []);

  if (restricted) {
    return <RestrictedPanel area="Notifications" role="Security Admin" />;
  }
  if (!s) return message ? <Alert severity={message.type} sx={{ maxWidth: 900 }}>{message.text}</Alert> : <Typography sx={{ p: 3 }}>Loading notification settings…</Typography>;
  const change = <K extends keyof NotificationSettingsView>(k: K, v: NotificationSettingsView[K]) => setS({ ...s, [k]: v });
  const routeChange = (type: NotificationRouteView["type"], patch: Partial<NotificationRouteView>) =>
    change("routes", s.routes.map((route) => route.type === type ? { ...route, ...patch } : route));
  const fieldProblem = (name: string) => problems.find((problem) => problem.includes(name));

  const save = async () => {
    setBusy("save");
    setMessage(null);
    setProblems([]);
    try {
      setS(await saveNotificationSettings(s));
      setMessage({ text: "Notification settings saved.", type: "success" });
    } catch (e) {
      setProblems(((e as ApiError)?.body as { problems?: string[] } | undefined)?.problems ?? []);
      setMessage({ text: `Not saved: ${problemsOf(e)}`, type: "error" });
    } finally {
      setBusy(null);
    }
  };
  const test = async (kind: "syslog" | "mail", type?: NotificationRouteView["type"]) => {
    setBusy(type ?? kind);
    setMessage(null);
    try {
      const r = await testNotification(kind, type, s);
      setMessage({ text: `${kind === "syslog" ? "Syslog" : "Mail"} test: ${r.sent ? "sent" : "failed"} -- ${r.detail}`, type: r.sent ? "success" : "error" });
    } catch (e) {
      setMessage({ text: `Test refused: ${problemsOf(e)}`, type: "error" });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Stack spacing={2} sx={{ maxWidth: 900 }}>
      {message && <Alert severity={message.type}>{message.text}</Alert>}
      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="h6">Remote logging (syslog)</Typography>
        <FormControlLabel control={<Switch checked={s.syslog_enabled} onChange={(e) => change("syslog_enabled", e.target.checked)} />} label="Send to a syslog server" />
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
          <TextField label="Syslog host" value={s.syslog_host ?? ""} onChange={(e) => change("syslog_host", e.target.value)} fullWidth autoComplete="off" />
          <TextField label="Port" type="number" value={s.syslog_port} onChange={(e) => change("syslog_port", Number(e.target.value))} sx={{ width: { sm: 130 } }} />
          <TextField select label="Protocol" value={s.syslog_protocol} onChange={(e) => change("syslog_protocol", e.target.value as "udp" | "tcp")} sx={{ width: { sm: 130 } }}>
            <MenuItem value="udp">UDP</MenuItem><MenuItem value="tcp">TCP</MenuItem>
          </TextField>
          <TextField label="Facility" type="number" value={s.syslog_facility} onChange={(e) => change("syslog_facility", Number(e.target.value))} helperText="16 = local0" sx={{ width: { sm: 130 } }} />
        </Stack>
        <FormControlLabel control={<Switch checked={s.forward_audit_to_syslog} onChange={(e) => change("forward_audit_to_syslog", e.target.checked)} />} label="Forward audit events (table, operation, action, actor, time -- never the changed values)" />
        <FormControlLabel control={<Switch checked={s.notify_job_failure} onChange={(e) => change("notify_job_failure", e.target.checked)} />} label="Forward failed jobs to syslog" />
      </Stack></CardContent></Card>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="h6">Mail relay</Typography>
        <Typography variant="body2" color="text.secondary">Standard SMTP relay without an account; the relay must accept mail from this server's address.</Typography>
        <FormControlLabel control={<Switch checked={s.smtp_enabled} onChange={(e) => change("smtp_enabled", e.target.checked)} />} label="Send mail through an internal relay" />
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
          <TextField label="Relay host" value={s.smtp_host ?? ""} onChange={(e) => change("smtp_host", e.target.value)} fullWidth autoComplete="off" />
          <TextField label="Port" type="number" value={s.smtp_port} onChange={(e) => change("smtp_port", Number(e.target.value))} sx={{ width: { sm: 130 } }} />
        </Stack>
        <FormControlLabel control={<Switch checked={s.smtp_starttls} onChange={(e) => change("smtp_starttls", e.target.checked)} />} label="STARTTLS (certificate verified against the corporate CA)" />
        <TextField label="From address" value={s.smtp_from ?? ""} onChange={(e) => change("smtp_from", e.target.value)} error={!!fieldProblem("from address")} helperText={fieldProblem("from address")} fullWidth />
        <TextField label="Default recipients" value={s.smtp_to ?? ""} onChange={(e) => change("smtp_to", e.target.value)} error={problems.some((p) => p.startsWith("not an e-mail address:"))} helperText={problems.find((p) => p.startsWith("not an e-mail address:")) ?? "Comma separated; used by types without their own list."} fullWidth />
      </Stack></CardContent></Card>

      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Typography variant="h6">Notification types</Typography>
        {TYPES.map(({ type, title, description }) => {
          const route = s.routes.find((item) => item.type === type);
          if (!route) return null;
          return <Stack key={type} data-testid={`route-${type}`} spacing={1} sx={{ py: 1, borderTop: 1, borderColor: "divider" }}>
            <FormControlLabel control={<Switch checked={route.enabled} onChange={(e) => routeChange(type, { enabled: e.target.checked })} />} label={title} />
            <Typography variant="body2" color="text.secondary">{description}</Typography>
            <Stack direction={{ xs: "column", sm: "row" }} spacing={1}>
              <TextField label={`${title} recipients`} value={route.recipients ?? ""} onChange={(e) => routeChange(type, { recipients: e.target.value })}
                placeholder="default recipients" error={!!fieldProblem(`${type} recipients`)} helperText={fieldProblem(`${type} recipients`) ?? "Comma separated"} fullWidth />
              <M3Button emphasis="outlined" disabled={busy !== null} onClick={() => void test("mail", type)}>{busy === type ? "Sending…" : "Send test"}</M3Button>
            </Stack>
            <Typography variant="caption" color="text.secondary">Last sent: {route.last_sent_at ?? "Never"}{route.last_error ? ` · Last error: ${route.last_error}` : ""}</Typography>
          </Stack>;
        })}
      </Stack></CardContent></Card>

      <Stack direction="row" spacing={1.5}>
        <M3Button emphasis="filled" disabled={busy !== null} onClick={() => void save()}>{busy === "save" ? "Saving…" : "Save"}</M3Button>
        <M3Button emphasis="outlined" disabled={busy !== null} onClick={() => void test("syslog")}>{busy === "syslog" ? "Sending…" : "Send test syslog"}</M3Button>
      </Stack>
    </Stack>
  );
}
